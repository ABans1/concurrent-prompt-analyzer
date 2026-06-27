package com.example.concurrentpromptanalyzer.exception;

/**
 * Thrown when a client polls for a batchId that does not exist.
 * Mapped to HTTP 404 NOT FOUND by the global exception handler.
 */
public class BatchNotFoundException extends RuntimeException {

    public BatchNotFoundException(String batchId) {
        super("No batch found with id: " + batchId);
    }
}
