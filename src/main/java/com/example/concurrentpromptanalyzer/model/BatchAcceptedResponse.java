package com.example.concurrentpromptanalyzer.model;

import java.time.Instant;

/** Immediate acknowledgement returned (HTTP 202) the moment a batch is accepted. */
public record BatchAcceptedResponse(
        String batchId,
        BatchStatus status,
        int acceptedPromptCount,
        Instant submittedAt) {
}
