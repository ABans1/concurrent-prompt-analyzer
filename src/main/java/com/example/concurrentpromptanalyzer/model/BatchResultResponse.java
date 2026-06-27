package com.example.concurrentpromptanalyzer.model;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated batch view returned by {@code GET /api/v1/batches/{batchId}}.
 *
 * <p>While the batch is still {@code PENDING}/{@code RUNNING} the {@code results} list reflects
 * whatever prompts have completed so far; once {@code COMPLETED} it contains every prompt exactly
 * once, ordered by {@code index}.
 */
public record BatchResultResponse(
        String batchId,
        BatchStatus status,
        int totalPrompts,
        int succeeded,
        int failed,
        Instant submittedAt,
        Instant completedAt,
        List<PromptResult> results) {
}
