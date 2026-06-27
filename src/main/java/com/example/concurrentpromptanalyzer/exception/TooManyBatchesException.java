package com.example.concurrentpromptanalyzer.exception;

/**
 * Thrown when the intake rate limit is exceeded (too many batches in-flight).
 * Mapped to HTTP 429 TOO MANY REQUESTS by the global exception handler.
 */
public class TooManyBatchesException extends RuntimeException {

    public TooManyBatchesException(String message) {
        super(message);
    }
}
