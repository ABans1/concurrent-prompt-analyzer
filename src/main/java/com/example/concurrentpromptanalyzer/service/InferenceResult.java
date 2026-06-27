package com.example.concurrentpromptanalyzer.service;

/**
 * Outcome of calling the mock inference endpoint for a single prompt, including how many attempts
 * it took (so a retried-then-succeeded prompt is visible in the aggregated result).
 */
public record InferenceResult(boolean success, String output, int attempts, String error) {

    public static InferenceResult success(String output, int attempts) {
        return new InferenceResult(true, output, attempts, null);
    }

    public static InferenceResult failure(String error, int attempts) {
        return new InferenceResult(false, null, attempts, error);
    }
}
