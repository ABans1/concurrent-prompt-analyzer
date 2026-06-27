package com.example.concurrentpromptanalyzer.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.model.BatchResultResponse;
import com.example.concurrentpromptanalyzer.model.BatchStatus;
import com.example.concurrentpromptanalyzer.model.PromptResult;
import com.example.concurrentpromptanalyzer.ratelimit.IntakeRateLimiter;
import com.example.concurrentpromptanalyzer.service.BatchProcessingService;
import com.example.concurrentpromptanalyzer.service.InferenceClient;
import com.example.concurrentpromptanalyzer.service.InferenceResult;
import com.example.concurrentpromptanalyzer.store.BatchStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalRecoveryServiceTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static AnalyzerProperties propertiesAt(Path file) {
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.getJournal().setEnabled(true);
        properties.getJournal().setFile(file.toString());
        return properties;
    }

    @Test
    void resumesAnInterruptedBatchProcessingOnlyMissingPrompts(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");

        // --- Pre-crash: a batch was submitted and only prompt 0 finished, then the process died. ---
        AnalyzerProperties properties = propertiesAt(file);
        BatchJournal preCrash = new BatchJournal(properties, mapper());
        preCrash.recordSubmitted("b1", List.of("a", "b", "c"));
        preCrash.recordResult("b1", PromptResult.success(0, "a", "out-a", 1));
        preCrash.close();

        // --- Restart: fresh state, wire up recovery. ---
        executor = Executors.newFixedThreadPool(4);
        InferenceClient inferenceClient = mock(InferenceClient.class);
        when(inferenceClient.infer(anyString()))
                .thenAnswer(inv -> InferenceResult.success("out:" + inv.getArgument(0), 1));
        IntakeRateLimiter rateLimiter = new IntakeRateLimiter(properties);
        BatchStore store = new BatchStore();
        BatchJournal journal = new BatchJournal(properties, mapper());
        BatchProcessingService service =
                new BatchProcessingService(executor, inferenceClient, rateLimiter, store, properties, journal);
        JournalRecoveryService recovery =
                new JournalRecoveryService(journal, store, service, properties);

        int resumed = recovery.recover();
        assertThat(resumed).isEqualTo(1);

        BatchResultResponse result = awaitCompleted(service);
        assertThat(result.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(result.totalPrompts()).isEqualTo(3);
        assertThat(result.succeeded()).isEqualTo(3);
        assertThat(result.results()).extracting(PromptResult::index).containsExactly(0, 1, 2);
        // Already-finished prompt 0 ("a") must NOT be re-processed; only the missing ones are.
        verify(inferenceClient, never()).infer("a");
        verify(inferenceClient).infer("b");
        verify(inferenceClient).infer("c");
        // Permit acquired during resume is released on completion (released just after status flips).
        awaitPermitsRestored(rateLimiter, properties);
    }

    @Test
    void restoresAlreadyCompletedBatchWithoutReprocessing(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");

        AnalyzerProperties properties = propertiesAt(file);
        BatchJournal preCrash = new BatchJournal(properties, mapper());
        preCrash.recordSubmitted("done-1", List.of("x", "y"));
        preCrash.recordResult("done-1", PromptResult.success(0, "x", "ox", 1));
        preCrash.recordResult("done-1", PromptResult.success(1, "y", "oy", 1));
        preCrash.recordCompleted("done-1", Instant.now());
        preCrash.close();

        executor = Executors.newFixedThreadPool(2);
        InferenceClient inferenceClient = mock(InferenceClient.class);
        IntakeRateLimiter rateLimiter = new IntakeRateLimiter(properties);
        BatchStore store = new BatchStore();
        BatchJournal journal = new BatchJournal(properties, mapper());
        BatchProcessingService service =
                new BatchProcessingService(executor, inferenceClient, rateLimiter, store, properties, journal);
        JournalRecoveryService recovery =
                new JournalRecoveryService(journal, store, service, properties);

        int resumed = recovery.recover();

        assertThat(resumed).isZero();
        BatchResultResponse result = service.getBatch("done-1");
        assertThat(result.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(result.succeeded()).isEqualTo(2);
        verify(inferenceClient, never()).infer(anyString());
        assertThat(rateLimiter.availablePermits()).isEqualTo(properties.getMaxInFlightBatches());
    }

    private static void awaitPermitsRestored(IntakeRateLimiter rateLimiter, AnalyzerProperties properties)
            throws InterruptedException {
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

    private BatchResultResponse awaitCompleted(BatchProcessingService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            BatchResultResponse response = service.getBatch("b1");
            if (response.status() == BatchStatus.COMPLETED) {
                return response;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Recovered batch did not complete within timeout");
    }
}
