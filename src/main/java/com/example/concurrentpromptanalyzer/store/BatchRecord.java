package com.example.concurrentpromptanalyzer.store;

import com.example.concurrentpromptanalyzer.model.BatchResultResponse;
import com.example.concurrentpromptanalyzer.model.BatchStatus;
import com.example.concurrentpromptanalyzer.model.PromptResult;
import com.example.concurrentpromptanalyzer.model.PromptStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for one batch's lifecycle and per-prompt results.
 *
 * <p>Multiple worker threads write {@link PromptResult}s concurrently (one per prompt index) while
 * a client may read the aggregate at any time via the polling endpoint. Results live in a
 * {@link ConcurrentHashMap} keyed by prompt index, and {@code status}/{@code completedAt} are held
 * in atomics, so no external synchronisation is required.
 */
public final class BatchRecord {

    private final String batchId;
    private final List<String> prompts;
    private final Instant submittedAt;
    private final ConcurrentMap<Integer, PromptResult> results = new ConcurrentHashMap<>();
    private final AtomicReference<BatchStatus> status = new AtomicReference<>(BatchStatus.PENDING);
    private final AtomicReference<Instant> completedAt = new AtomicReference<>();

    public BatchRecord(String batchId, List<String> prompts) {
        this.batchId = batchId;
        this.prompts = List.copyOf(prompts);
        this.submittedAt = Instant.now();
    }

    public String batchId() {
        return batchId;
    }

    public List<String> prompts() {
        return prompts;
    }

    public int totalPrompts() {
        return prompts.size();
    }

    public BatchStatus status() {
        return status.get();
    }

    public void markRunning() {
        status.compareAndSet(BatchStatus.PENDING, BatchStatus.RUNNING);
    }

    public void markCompleted() {
        completedAt.set(Instant.now());
        status.set(BatchStatus.COMPLETED);
    }

    /** Records a single prompt's terminal result. Safe to call from any worker thread. */
    public void recordResult(PromptResult result) {
        results.put(result.index(), result);
    }

    /** Prompt indices that do not yet have a recorded result — used to resume after a crash. */
    public List<Integer> missingIndices() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            if (!results.containsKey(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    /** Builds an immutable, index-ordered snapshot of the batch for the API response. */
    public BatchResultResponse toResponse() {
        List<PromptResult> ordered = new ArrayList<>(results.values());
        ordered.sort(Comparator.comparingInt(PromptResult::index));
        long succeeded = ordered.stream().filter(r -> r.status() == PromptStatus.SUCCESS).count();
        long failed = ordered.stream().filter(r -> r.status() == PromptStatus.FAILED).count();
        return new BatchResultResponse(
                batchId,
                status.get(),
                prompts.size(),
                (int) succeeded,
                (int) failed,
                submittedAt,
                completedAt.get(),
                List.copyOf(ordered));
    }
}
