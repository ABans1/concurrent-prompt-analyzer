package com.example.concurrentpromptanalyzer.model;

/** Response body returned by the mock inference endpoint on success. */
public record InferenceResponse(
        String output,
        String model,
        long latencyMs) {
}
