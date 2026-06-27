package com.example.concurrentpromptanalyzer.model;

/** Terminal outcome of a single prompt's inference. */
public enum PromptStatus {
    /** The mock inference endpoint returned a successful response (possibly after retries). */
    SUCCESS,
    /** Retries were exhausted; the prompt is recorded as failed rather than silently dropped. */
    FAILED
}
