package com.example.concurrentpromptanalyzer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.exception.InvalidBatchException;
import com.example.concurrentpromptanalyzer.journal.BatchJournal;
import com.example.concurrentpromptanalyzer.model.BatchAcceptedResponse;
import com.example.concurrentpromptanalyzer.model.BatchResultResponse;
import com.example.concurrentpromptanalyzer.model.BatchStatus;
import com.example.concurrentpromptanalyzer.model.PromptBatchRequest;
import com.example.concurrentpromptanalyzer.model.PromptResult;
import com.example.concurrentpromptanalyzer.model.PromptStatus;
import com.example.concurrentpromptanalyzer.ratelimit.IntakeRateLimiter;
import com.example.concurrentpromptanalyzer.store.BatchStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BatchProcessingServiceTest {

    private ExecutorService executor;
    private StubInferenceClient inferenceClient;
    private IntakeRateLimiter rateLimiter;
    private BatchStore batchStore;
    private AnalyzerProperties properties;
    private BatchProcessingService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        inferenceClient = new StubInferenceClient();
        properties = new AnalyzerProperties();
        properties.getJournal().setEnabled(false); // no file I/O in this unit test
        rateLimiter = new IntakeRateLimiter(properties);
        batchStore = new BatchStore();
        BatchJournal journal = new BatchJournal(properties, new ObjectMapper());
        service = new BatchProcessingService(
                executor, inferenceClient, rateLimiter, batchStore, properties, journal);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void aggregatesAllPromptsAndReleasesPermitOnCompletion() throws Exception {
        // Default stub behaviour already returns success for every prompt.
        BatchAcceptedResponse ack = service.submit(new PromptBatchRequest(List.of("a", "b", "c")), "req-1");
        assertThat(ack.batchId()).isNotBlank();
        assertThat(ack.acceptedPromptCount()).isEqualTo(3);

        BatchResultResponse result = awaitCompleted(ack.batchId());

        assertThat(result.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(result.totalPrompts()).isEqualTo(3);
        assertThat(result.succeeded()).isEqualTo(3);
        assertThat(result.failed()).isZero();
        assertThat(result.results()).hasSize(3);
        // Every prompt index appears exactly once.
        assertThat(result.results().stream().map(PromptResult::index).toList())
                .containsExactly(0, 1, 2);
        // Intake permit is returned once the batch finishes (released just after status flips).
        awaitAllPermitsRestored();
    }

    @Test
    void recordsFailedPromptsWithoutDroppingThem() throws Exception {
        inferenceClient.on("ok", InferenceResult.success("done", 1));
        inferenceClient.on("bad", InferenceResult.failure("HTTP 429", 5));

        BatchAcceptedResponse ack = service.submit(new PromptBatchRequest(List.of("ok", "bad")), "req-2");
        BatchResultResponse result = awaitCompleted(ack.batchId());

        assertThat(result.totalPrompts()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        PromptResult failed = result.results().stream()
                .filter(r -> r.status() == PromptStatus.FAILED).findFirst().orElseThrow();
        assertThat(failed.attempts()).isEqualTo(5);
        assertThat(failed.error()).contains("429");
    }

    @Test
    void rejectsEmptyBatchWithoutConsumingAPermit() {
        assertThatThrownBy(() -> service.submit(new PromptBatchRequest(List.of()), "req-3"))
                .isInstanceOf(InvalidBatchException.class);
        assertThat(rateLimiter.availablePermits()).isEqualTo(properties.getMaxInFlightBatches());
    }

    @Test
    void rejectsBlankPromptWithoutConsumingAPermit() {
        assertThatThrownBy(() -> service.submit(new PromptBatchRequest(List.of("ok", "   ")), "req-4"))
                .isInstanceOf(InvalidBatchException.class)
                .hasMessageContaining("index 1");
        assertThat(rateLimiter.availablePermits()).isEqualTo(properties.getMaxInFlightBatches());
    }

    @Test
    void rejectsBatchLargerThanConfiguredMax() {
        properties.setMaxBatchSize(2);
        assertThatThrownBy(() -> service.submit(new PromptBatchRequest(List.of("a", "b", "c")), "req-5"))
                .isInstanceOf(InvalidBatchException.class)
                .hasMessageContaining("exceeds maximum");
    }

    private BatchResultResponse awaitCompleted(String batchId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            BatchResultResponse response = service.getBatch(batchId);
            if (response.status() == BatchStatus.COMPLETED) {
                return response;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Batch " + batchId + " did not complete within timeout");
    }

    private void awaitAllPermitsRestored() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int expected = properties.getMaxInFlightBatches();
        while (System.nanoTime() < deadline) {
            if (rateLimiter.availablePermits() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(rateLimiter.availablePermits()).isEqualTo(expected);
    }

    /**
     * Deterministic, thread-safe fake used instead of a Mockito mock: the workers invoke {@code infer}
     * from several pool threads at once, and concurrent dispatch on a Mockito mock can intermittently
     * return its default ({@code null}). A plain fake removes that flakiness.
     */
    private static final class StubInferenceClient extends InferenceClient {
        private final Map<String, InferenceResult> byPrompt = new ConcurrentHashMap<>();
        private final Function<String, InferenceResult> defaultAnswer =
                prompt -> InferenceResult.success("out:" + prompt, 1);

        private StubInferenceClient() {
            super(null, new AnalyzerProperties(), null);
        }

        void on(String prompt, InferenceResult result) {
            byPrompt.put(prompt, result);
        }

        @Override
        public InferenceResult infer(String prompt) {
            return byPrompt.getOrDefault(prompt, defaultAnswer.apply(prompt));
        }
    }
}
