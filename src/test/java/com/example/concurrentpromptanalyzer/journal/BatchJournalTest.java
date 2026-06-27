package com.example.concurrentpromptanalyzer.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.model.PromptResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchJournalTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static BatchJournal journalAt(Path file) {
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.getJournal().setEnabled(true);
        properties.getJournal().setFile(file.toString());
        return new BatchJournal(properties, mapper());
    }

    @Test
    void appendsAndReadsBackEventsInOrder(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        BatchJournal journal = journalAt(file);

        journal.recordSubmitted("b1", List.of("alpha", "beta"));
        journal.recordResult("b1", PromptResult.success(0, "alpha", "out-a", 1));
        journal.recordResult("b1", PromptResult.failed(1, "beta", "HTTP 429", 5));
        journal.recordCompleted("b1", Instant.now());
        journal.close();

        List<JournalEvent> events = journalAt(file).readAll();

        assertThat(events).hasSize(4);
        assertThat(events.get(0).type()).isEqualTo(JournalEventType.SUBMITTED);
        assertThat(events.get(0).prompts()).containsExactly("alpha", "beta");
        assertThat(events.get(1).type()).isEqualTo(JournalEventType.RESULT);
        assertThat(events.get(1).result().output()).isEqualTo("out-a");
        assertThat(events.get(2).result().error()).isEqualTo("HTTP 429");
        assertThat(events.get(3).type()).isEqualTo(JournalEventType.COMPLETED);
        assertThat(events.get(3).completedAt()).isNotNull();
    }

    @Test
    void disabledJournalWritesNothingAndReadsEmpty(@TempDir Path dir) {
        Path file = dir.resolve("journal.jsonl");
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.getJournal().setEnabled(false);
        properties.getJournal().setFile(file.toString());
        BatchJournal journal = new BatchJournal(properties, mapper());

        journal.recordSubmitted("b1", List.of("alpha"));
        journal.close();

        assertThat(journal.readAll()).isEmpty();
        assertThat(file.toFile()).doesNotExist();
    }

    @Test
    void toleratesTornLastLineFromCrash(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.jsonl");
        BatchJournal journal = journalAt(file);
        journal.recordSubmitted("b1", List.of("alpha"));
        journal.close();

        // Simulate a half-written final line from a crash mid-append.
        java.nio.file.Files.writeString(file, "{\"type\":\"RESU", java.nio.file.StandardOpenOption.APPEND);

        List<JournalEvent> events = journalAt(file).readAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(JournalEventType.SUBMITTED);
    }
}
