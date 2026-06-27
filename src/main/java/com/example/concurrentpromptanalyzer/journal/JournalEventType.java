package com.example.concurrentpromptanalyzer.journal;

/** The kind of record appended to the write-ahead journal. */
public enum JournalEventType {
    /** A batch was admitted; carries the (normalized) prompt list. */
    SUBMITTED,
    /** A single prompt reached a terminal result (SUCCESS/FAILED). */
    RESULT,
    /** A batch finished processing all prompts. */
    COMPLETED
}
