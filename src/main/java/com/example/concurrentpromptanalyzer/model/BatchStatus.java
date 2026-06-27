package com.example.concurrentpromptanalyzer.model;

/** Lifecycle of a submitted batch. */
public enum BatchStatus {
    /** Accepted and stored, not yet picked up by the worker pool. */
    PENDING,
    /** Workers are actively processing the prompts. */
    RUNNING,
    /** All prompts have a terminal result and the aggregate is ready. */
    COMPLETED
}
