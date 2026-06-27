package com.example.concurrentpromptanalyzer.journal;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.model.PromptResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Append-only, thread-safe write-ahead journal for batches.
 *
 * <p>Each call appends a single JSON line and flushes it, so a process crash loses at most the
 * in-flight line. The journal is the source of truth used by {@code JournalRecoveryService} on
 * startup to rebuild {@code BatchStore} and resume interrupted batches.
 *
 * <p>Writes are best-effort with respect to the request path: a journal I/O error is logged but
 * never propagated, so journaling can never take down request processing.
 */
@Component
public class BatchJournal {

    private static final Logger log = LoggerFactory.getLogger(BatchJournal.class);

    private final boolean enabled;
    private final Path file;
    private final ObjectMapper mapper;
    private final Object writeLock = new Object();
    private BufferedWriter writer;

    public BatchJournal(AnalyzerProperties properties, ObjectMapper mapper) {
        AnalyzerProperties.Journal config = properties.getJournal();
        this.enabled = config.isEnabled();
        this.file = Path.of(config.getFile());
        this.mapper = mapper;
        if (enabled) {
            openWriter();
        }
    }

    private void openWriter() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Batch journal enabled at {}", file.toAbsolutePath());
        } catch (IOException ex) {
            log.error("Failed to open batch journal at {}; journaling disabled", file.toAbsolutePath(), ex);
            this.writer = null;
        }
    }

    public void recordSubmitted(String batchId, List<String> prompts) {
        append(JournalEvent.submitted(batchId, prompts));
    }

    public void recordResult(String batchId, PromptResult result) {
        append(JournalEvent.result(batchId, result));
    }

    public void recordCompleted(String batchId, Instant completedAt) {
        append(JournalEvent.completed(batchId, completedAt));
    }

    private void append(JournalEvent event) {
        if (!enabled || writer == null) {
            return;
        }
        try {
            String line = mapper.writeValueAsString(event);
            synchronized (writeLock) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException ex) {
            // Best-effort: never let a journal write failure break request processing.
            log.error("Failed to append {} event for batch {} to journal", event.type(), event.batchId(), ex);
        }
    }

    /** Reads and parses every event in the journal (for startup recovery). */
    public List<JournalEvent> readAll() {
        if (!enabled || !Files.exists(file)) {
            return List.of();
        }
        List<JournalEvent> events = new ArrayList<>();
        try {
            List<String> lines;
            synchronized (writeLock) {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            }
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    events.add(mapper.readValue(line, JournalEvent.class));
                } catch (IOException parseError) {
                    // Tolerate a torn last line from a crash mid-write; skip and continue.
                    log.warn("Skipping unparseable journal line: {}", line, parseError);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read batch journal at " + file.toAbsolutePath(), ex);
        }
        return events;
    }

    @PreDestroy
    public void close() {
        synchronized (writeLock) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ex) {
                    log.warn("Error closing batch journal", ex);
                }
            }
        }
    }
}
