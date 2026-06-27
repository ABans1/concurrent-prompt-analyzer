package com.example.concurrentpromptanalyzer.store;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * In-memory, thread-safe registry of batches keyed by batchId.
 *
 * <p>Deliberately simple for the demo; in production this would be backed by a shared store
 * (Redis/Postgres) so results survive restarts and scale horizontally.
 */
@Component
public class BatchStore {

    private final ConcurrentMap<String, BatchRecord> batches = new ConcurrentHashMap<>();

    public void save(BatchRecord record) {
        batches.put(record.batchId(), record);
    }

    public Optional<BatchRecord> find(String batchId) {
        return Optional.ofNullable(batches.get(batchId));
    }
}
