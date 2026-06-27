package com.example.concurrentpromptanalyzer.journal;

import com.example.concurrentpromptanalyzer.model.PromptResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * One line in the write-ahead journal. Only the fields relevant to {@link #type} are populated; the
 * rest are omitted from the serialized JSON.
 *
 * @param type        the kind of event
 * @param batchId     the batch this event belongs to
 * @param timestamp   when the event was recorded
 * @param prompts     the normalized prompts (only for {@link JournalEventType#SUBMITTED})
 * @param result      the prompt result (only for {@link JournalEventType#RESULT})
 * @param completedAt completion time (only for {@link JournalEventType#COMPLETED})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JournalEvent(
        JournalEventType type,
        String batchId,
        Instant timestamp,
        List<String> prompts,
        PromptResult result,
        Instant completedAt) {

    public static JournalEvent submitted(String batchId, List<String> prompts) {
        return new JournalEvent(JournalEventType.SUBMITTED, batchId, Instant.now(), prompts, null, null);
    }

    public static JournalEvent result(String batchId, PromptResult result) {
        return new JournalEvent(JournalEventType.RESULT, batchId, Instant.now(), null, result, null);
    }

    public static JournalEvent completed(String batchId, Instant completedAt) {
        return new JournalEvent(JournalEventType.COMPLETED, batchId, Instant.now(), null, null, completedAt);
    }
}
