package com.example.concurrentpromptanalyzer.observability;

/** Centralised MDC keys so log patterns and code agree on the correlation field names. */
public final class MdcKeys {

    public static final String REQUEST_ID = "requestId";
    public static final String BATCH_ID = "batchId";
    public static final String PROMPT_INDEX = "promptIndex";
    public static final String ATTEMPT = "attempt";

    private MdcKeys() {
    }
}
