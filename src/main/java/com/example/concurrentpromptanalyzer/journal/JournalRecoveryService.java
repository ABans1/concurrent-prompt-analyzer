package com.example.concurrentpromptanalyzer.journal;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.service.BatchProcessingService;
import com.example.concurrentpromptanalyzer.store.BatchRecord;
import com.example.concurrentpromptanalyzer.store.BatchStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Replays the write-ahead journal once the application is ready: rebuilds {@code BatchStore} from the
 * recorded events and, for any batch that was not {@code COMPLETED} before the crash/restart,
 * resumes processing of its unfinished prompts.
 *
 * <p>Replay is last-writer-wins per prompt index, so a result recorded after a previous attempt
 * (e.g. a retry that eventually succeeded) supersedes the earlier one.
 */
@Component
public class JournalRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(JournalRecoveryService.class);
    private static final String RECOVERY_REQUEST_ID = "recovery";

    private final BatchJournal journal;
    private final BatchStore batchStore;
    private final BatchProcessingService batchProcessingService;
    private final boolean recoverOnStartup;

    public JournalRecoveryService(
            BatchJournal journal,
            BatchStore batchStore,
            BatchProcessingService batchProcessingService,
            AnalyzerProperties properties) {
        this.journal = journal;
        this.batchStore = batchStore;
        this.batchProcessingService = batchProcessingService;
        this.recoverOnStartup = properties.getJournal().isRecoverOnStartup();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!recoverOnStartup) {
            return;
        }
        recover();
    }

    /** Rebuilds state from the journal. Returns the number of batches resumed. */
    public int recover() {
        Map<String, ReconstructedBatch> batches = reconstruct();
        if (batches.isEmpty()) {
            return 0;
        }
        int resumed = 0;
        int restored = 0;
        for (ReconstructedBatch batch : batches.values()) {
            if (batch.prompts == null) {
                // RESULT/COMPLETED without a SUBMITTED record (truncated journal) — cannot rebuild.
                log.warn("Skipping batch {} during recovery: no SUBMITTED record found", batch.batchId);
                continue;
            }
            BatchRecord record = new BatchRecord(batch.batchId, batch.prompts);
            batch.results.values().forEach(record::recordResult);

            if (batch.completed) {
                record.markCompleted();
                batchStore.save(record);
                restored++;
            } else {
                try {
                    batchProcessingService.resume(record, RECOVERY_REQUEST_ID);
                    resumed++;
                } catch (RuntimeException ex) {
                    log.error("Failed to resume batch {} during recovery", batch.batchId, ex);
                }
            }
        }
        log.info("Journal recovery complete: {} batch(es) restored as COMPLETED, {} resumed", restored, resumed);
        return resumed;
    }

    private Map<String, ReconstructedBatch> reconstruct() {
        Map<String, ReconstructedBatch> batches = new LinkedHashMap<>();
        for (JournalEvent event : journal.readAll()) {
            ReconstructedBatch batch =
                    batches.computeIfAbsent(event.batchId(), ReconstructedBatch::new);
            switch (event.type()) {
                case SUBMITTED -> batch.prompts = event.prompts();
                // Last-writer-wins per index (a later successful retry supersedes an earlier failure).
                case RESULT -> batch.results.put(event.result().index(), event.result());
                case COMPLETED -> batch.completed = true;
            }
        }
        return batches;
    }

    /** Mutable accumulator for a single batch while replaying the journal. */
    private static final class ReconstructedBatch {
        private final String batchId;
        private java.util.List<String> prompts;
        private final Map<Integer, com.example.concurrentpromptanalyzer.model.PromptResult> results =
                new LinkedHashMap<>();
        private boolean completed;

        private ReconstructedBatch(String batchId) {
            this.batchId = batchId;
        }
    }
}
