package com.example.concurrentpromptanalyzer.web;

import java.time.Instant;
import java.util.List;

/** Consistent error payload returned for all handled exceptions. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<String> details) {

    public static ApiError of(int status, String error, String message, List<String> details) {
        return new ApiError(Instant.now(), status, error, message, details);
    }
}
