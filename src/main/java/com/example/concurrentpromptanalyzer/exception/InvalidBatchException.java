package com.example.concurrentpromptanalyzer.exception;

/**
 * Thrown by service-layer validation that cannot be expressed with static bean-validation
 * annotations alone (e.g. limits that depend on runtime configuration such as max batch size
 * or max prompt length). Mapped to HTTP 400 BAD REQUEST.
 */
public class InvalidBatchException extends RuntimeException {

    public InvalidBatchException(String message) {
        super(message);
    }
}
