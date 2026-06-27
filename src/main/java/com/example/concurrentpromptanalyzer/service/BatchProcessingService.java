package com.example.concurrentpromptanalyzer.service;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.exception.BatchNotFoundException;
import com.example.concurrentpromptanalyzer.exception.InvalidBatchException;
import com.example.concurrentpromptanalyzer.journal.BatchJournal;
import com.example.concurrentpromptanalyzer.model.BatchAcceptedResponse;
import com.example.concurrentpromptanalyzer.model.BatchResultResponse;
import com.example.concurrentpromptanalyzer.model.PromptBatchRequest;
import com.example.concurrentpromptanalyzer.model.PromptResult;
import com.example.concurrentpromptanalyzer.observability.MdcKeys;
import com.example.concurrentpromptanalyzer.ratelimit.IntakeRateLimiter;
import com.example.concurrentpromptanalyzer.store.BatchRecord;
import com.example.concurrentpromptanalyzer.store.BatchStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the async, concurrent processing of a prompt batch.
 *
 * <p>On {@link #submit submit} the request is validated, an intake permit is reserved (429 if the
 * limit is hit), the batch is stored, processing is kicked off on the bounded worker pool, and an
 * acknowledgement is returned immediately — the HTTP caller never waits for inference.
 *
 * <p>Processing fans out one {@link CompletableFuture#supplyAsync} per prompt <em>on our own bounded
 * executor</em> (never {@code ForkJoinPool.commonPool()}), joins them with {@code allOf}, then
 * aggregates results, marks the batch {@code COMPLETED} and releases the intake permit.
 */
@Service
public class BatchProcessingService {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessingService.class);

    private final ExecutorService workerExecutor;
    private final InferenceClient inferenceClient;
    private final IntakeRateLimiter rateLimiter;
    private final BatchStore batchStore;
    private final AnalyzerProperties properties;
    private final BatchJournal journal;

    public BatchProcessingService(
            ExecutorService promptWorkerExecutor,
            InferenceClient inferenceClient,
            IntakeRateLimiter rateLimiter,
            BatchStore batchStore,
            AnalyzerProperties properties,
            BatchJournal journal) {
        this.workerExecutor = promptWorkerExecutor;
        this.inferenceClient = inferenceClient;
        this.rateLimiter = rateLimiter;
        this.batchStore = batchStore;
        this.properties = properties;
        this.journal = journal;
    }

    /** Validates, admits, stores and starts a batch, returning an immediate acknowledgement. */
    public BatchAcceptedResponse submit(PromptBatchRequest request, String requestId) {
        List<String> prompts = validateAndNormalize(request);

        // Reserve an intake permit BEFORE doing any work; throws 429 if the limit is hit.
        rateLimiter.acquire();
        try {
            String batchId = UUID.randomUUID().toString();
            BatchRecord record = new BatchRecord(batchId, prompts);
            batchStore.save(record);
            // Durably record the submission BEFORE returning the ack, so a crash right after the
            // 202 can still recover and process the batch.
            journal.recordSubmitted(batchId, prompts);
            log.info("Accepted batch {} with {} prompt(s)", batchId, prompts.size());

            // Capture the acknowledgement (status PENDING) before async work may flip the status.
            BatchAcceptedResponse ack = new BatchAcceptedResponse(
                    batchId, record.status(), prompts.size(), Instant.now());
            startProcessing(record, requestId, allIndices(prompts.size()));
            return ack;
        } catch (RuntimeException ex) {
            // If anything fails before async processing owns the permit, release it here.
            rateLimiter.release();
            throw ex;
        }
    }

    /** Returns the aggregated/live view of a batch, or 404 if unknown. */
    public BatchResultResponse getBatch(String batchId) {
        return batchStore.find(batchId)
                .map(BatchRecord::toResponse)
                .orElseThrow(() -> new BatchNotFoundException(batchId));
    }

    /**
     * Resumes a batch recovered from the journal: admits it, then processes only the prompts that
     * have no recorded result yet. Used by {@code JournalRecoveryService} on startup.
     */
    public void resume(BatchRecord record, String requestId) {
        batchStore.save(record);
        List<Integer> missing = record.missingIndices();
        if (missing.isEmpty()) {
            // All prompts already have results (crash happened before the COMPLETED event was written).
            record.markCompleted();
            journal.recordCompleted(record.batchId(), Instant.now());
            log.info("Recovered batch {} already had all results; marked COMPLETED", record.batchId());
            return;
        }
        rateLimiter.acquire();
        try {
            log.info("Resuming batch {}: re-processing {} of {} prompt(s)",
                    record.batchId(), missing.size(), record.totalPrompts());
            startProcessing(record, requestId, missing);
        } catch (RuntimeException ex) {
            rateLimiter.release();
            throw ex;
        }
    }

    private void startProcessing(BatchRecord record, String requestId, List<Integer> indices) {
        record.markRunning();
        List<String> prompts = record.prompts();

        List<CompletableFuture<Void>> futures = new ArrayList<>(indices.size());
        for (int index : indices) {
            final int idx = index;
            final String prompt = prompts.get(idx);
            // MDC is thread-local and does NOT cross into pool threads automatically, so we capture
            // the correlation context here and re-establish it inside each worker task.
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> processPrompt(record, requestId, idx, prompt), workerExecutor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, throwable) -> completeBatch(record, requestId, throwable));
    }

    private static List<Integer> allIndices(int size) {
        return IntStream.range(0, size).boxed().toList();
    }

    private void processPrompt(BatchRecord record, String requestId, int index, String prompt) {
        MDC.put(MdcKeys.REQUEST_ID, requestId);
        MDC.put(MdcKeys.BATCH_ID, record.batchId());
        MDC.put(MdcKeys.PROMPT_INDEX, Integer.toString(index));
        try {
            InferenceResult result = inferenceClient.infer(prompt);
            PromptResult promptResult = result.success()
                    ? PromptResult.success(index, prompt, result.output(), result.attempts())
                    : PromptResult.failed(index, prompt, result.error(), result.attempts());
            record.recordResult(promptResult);
            journal.recordResult(record.batchId(), promptResult);
        } catch (RuntimeException ex) {
            // Defensive: never let one prompt's unexpected error drop the rest of the batch.
            log.error("Unexpected error processing prompt index {}", index, ex);
            PromptResult failure = PromptResult.failed(index, prompt, "Unexpected error: " + ex.getMessage(), 1);
            record.recordResult(failure);
            journal.recordResult(record.batchId(), failure);
        } finally {
            MDC.remove(MdcKeys.REQUEST_ID);
            MDC.remove(MdcKeys.BATCH_ID);
            MDC.remove(MdcKeys.PROMPT_INDEX);
        }
    }

    private void completeBatch(BatchRecord record, String requestId, Throwable throwable) {
        MDC.put(MdcKeys.REQUEST_ID, requestId);
        MDC.put(MdcKeys.BATCH_ID, record.batchId());
        try {
            record.markCompleted();
            journal.recordCompleted(record.batchId(), Instant.now());
            BatchResultResponse response = record.toResponse();
            if (throwable != null) {
                log.error("Batch {} completed with an orchestration error", record.batchId(), throwable);
            }
            log.info("Batch completed: {} succeeded, {} failed of {}",
                    response.succeeded(), response.failed(), response.totalPrompts());
        } finally {
            rateLimiter.release();
            MDC.remove(MdcKeys.REQUEST_ID);
            MDC.remove(MdcKeys.BATCH_ID);
        }
    }

    /** Service-layer validation for limits that depend on runtime configuration. */
    private List<String> validateAndNormalize(PromptBatchRequest request) {
        if (request == null || request.prompts() == null || request.prompts().isEmpty()) {
            throw new InvalidBatchException("prompts must contain at least one entry");
        }
        int maxBatch = properties.getMaxBatchSize();
        if (request.prompts().size() > maxBatch) {
            throw new InvalidBatchException(
                    "Batch size " + request.prompts().size() + " exceeds maximum of " + maxBatch);
        }
        int maxLen = properties.getMaxPromptLength();
        List<String> normalized = new ArrayList<>(request.prompts().size());
        for (int i = 0; i < request.prompts().size(); i++) {
            String raw = request.prompts().get(i);
            if (raw == null || raw.isBlank()) {
                throw new InvalidBatchException("prompt at index " + i + " must not be blank");
            }
            String trimmed = raw.trim();
            if (trimmed.length() > maxLen) {
                throw new InvalidBatchException(
                        "prompt at index " + i + " exceeds maximum length of " + maxLen + " characters");
            }
            // Duplicates are intentionally preserved as distinct units of work.
            normalized.add(trimmed);
        }
        return normalized;
    }
}
