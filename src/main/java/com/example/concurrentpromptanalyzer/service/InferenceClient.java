package com.example.concurrentpromptanalyzer.service;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.model.InferenceRequest;
import com.example.concurrentpromptanalyzer.model.InferenceResponse;
import com.example.concurrentpromptanalyzer.observability.MdcKeys;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls the mock inference endpoint over HTTP and implements the worker-side resilience policy:
 * retry transient failures (HTTP 429, 5xx, timeouts/connection errors) with exponential backoff
 * plus jitter, and fail fast on non-retryable client errors (e.g. 400).
 *
 * <p>Crucially, when retries are exhausted the prompt is reported as a {@link InferenceResult#failure
 * failure} rather than throwing — so a single bad prompt never drops the rest of the batch.
 */
@Service
public class InferenceClient {

    private static final Logger log = LoggerFactory.getLogger(InferenceClient.class);

    private final RestClient restClient;
    private final AnalyzerProperties properties;
    private final Sleeper sleeper;

    public InferenceClient(RestClient inferenceRestClient, AnalyzerProperties properties, Sleeper sleeper) {
        this.restClient = inferenceRestClient;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    /**
     * Runs a single prompt through the mock endpoint with retry/backoff.
     *
     * @return a successful or failed {@link InferenceResult}; never throws for inference failures
     *         (interruption is the one exception, which is propagated as a failed result)
     */
    public InferenceResult infer(String prompt) {
        AnalyzerProperties.Retry retry = properties.getRetry();
        String path = properties.getInference().getPath();
        String lastError = null;

        for (int attempt = 1; attempt <= retry.getMaxAttempts(); attempt++) {
            MDC.put(MdcKeys.ATTEMPT, Integer.toString(attempt));
            try {
                try {
                    InferenceResponse response = restClient.post()
                            .uri(path)
                            .body(new InferenceRequest(prompt))
                            .retrieve()
                            .body(InferenceResponse.class);
                    String output = response != null ? response.output() : null;
                    return InferenceResult.success(output, attempt);
                } catch (RestClientResponseException ex) {
                    int statusCode = ex.getStatusCode().value();
                    lastError = "HTTP " + statusCode;
                    if (!isRetryable(statusCode)) {
                        log.warn("Non-retryable status {} for prompt; failing fast", statusCode);
                        return InferenceResult.failure(lastError, attempt);
                    }
                } catch (ResourceAccessException ex) {
                    lastError = "Network error: " + ex.getMessage();
                }
                // A retryable failure occurred: back off, or stop if attempts are exhausted.
                if (!backoffOrStop(retry, attempt, lastError)) {
                    return InferenceResult.failure(lastError, attempt);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return InferenceResult.failure("Interrupted during backoff", attempt);
            } finally {
                MDC.remove(MdcKeys.ATTEMPT);
            }
        }
        return InferenceResult.failure(
                lastError != null ? lastError : "Unknown error", retry.getMaxAttempts());
    }

    /**
     * Sleeps for the computed backoff before the next attempt.
     *
     * @return {@code true} if another attempt should be made, {@code false} if attempts are exhausted
     */
    private boolean backoffOrStop(AnalyzerProperties.Retry retry, int attempt, String reason)
            throws InterruptedException {
        if (attempt >= retry.getMaxAttempts()) {
            log.warn("Attempt {} failed ({}); retries exhausted", attempt, reason);
            return false;
        }
        long backoff = computeBackoffMs(retry, attempt);
        log.debug("Attempt {} failed ({}); backing off {}ms", attempt, reason, backoff);
        sleeper.sleep(backoff);
        return true;
    }

    /** Exponential backoff capped at {@code maxBackoffMs}, plus uniform random jitter. */
    long computeBackoffMs(AnalyzerProperties.Retry retry, int attempt) {
        double exponential = retry.getInitialBackoffMs() * Math.pow(retry.getMultiplier(), attempt - 1);
        long capped = (long) Math.min(exponential, retry.getMaxBackoffMs());
        long jitter = retry.getJitterMs() > 0 ? ThreadLocalRandom.current().nextLong(retry.getJitterMs()) : 0;
        return capped + jitter;
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }
}
