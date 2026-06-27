package com.example.concurrentpromptanalyzer.controller;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.model.InferenceRequest;
import com.example.concurrentpromptanalyzer.model.InferenceResponse;
import jakarta.validation.Valid;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A mock, rate-limited inference endpoint that stands in for a real external LLM provider.
 *
 * <p>It enforces a genuine rate limit two ways, both of which drive the worker retry/backoff path
 * over the wire:
 * <ul>
 *   <li><strong>Concurrency cap</strong> — a {@link Semaphore} admits at most
 *       {@code analyzer.mock.max-concurrent} simultaneous calls; anything beyond that is rejected
 *       with {@code 429 TOO MANY REQUESTS} immediately.</li>
 *   <li><strong>Periodic 429</strong> — every Nth call ({@code analyzer.mock.fail-every-nth})
 *       returns {@code 429} deterministically, so retries are exercised even under light load.</li>
 * </ul>
 * Successful calls simulate variable latency.
 */
@RestController
@RequestMapping("/mock")
public class MockInferenceController {

    private static final Logger log = LoggerFactory.getLogger(MockInferenceController.class);

    private final AnalyzerProperties properties;
    private final AtomicLong callCounter = new AtomicLong();
    private final Semaphore concurrencyLimiter;

    public MockInferenceController(AnalyzerProperties properties) {
        this.properties = properties;
        this.concurrencyLimiter = new Semaphore(properties.getMock().getMaxConcurrent());
    }

    @PostMapping("/infer")
    public ResponseEntity<InferenceResponse> infer(@Valid @RequestBody InferenceRequest request)
            throws InterruptedException {
        AnalyzerProperties.Mock mock = properties.getMock();

        // Concurrency-based rate limit: reject immediately if too many calls are already in flight.
        if (!concurrencyLimiter.tryAcquire()) {
            log.debug("Mock returning 429 (concurrency limit {} exceeded)", mock.getMaxConcurrent());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        try {
            long call = callCounter.incrementAndGet();
            if (call % mock.getFailEveryNth() == 0) {
                log.debug("Mock returning 429 on call #{} (periodic)", call);
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
            }

            long latency = randomLatencyMs(mock);
            if (latency > 0) {
                Thread.sleep(latency);
            }

            String output = "inference(" + request.prompt() + ")";
            return ResponseEntity.ok(new InferenceResponse(output, "mock-model-v1", latency));
        } finally {
            concurrencyLimiter.release();
        }
    }

    private static long randomLatencyMs(AnalyzerProperties.Mock mock) {
        long min = mock.getMinLatencyMs();
        long max = Math.max(min, mock.getMaxLatencyMs());
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }
}
