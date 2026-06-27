package com.example.concurrentpromptanalyzer.model;

import jakarta.validation.constraints.NotBlank;

/** Request body for the mock inference endpoint ({@code POST /mock/infer}). */
public record InferenceRequest(
        @NotBlank(message = "prompt must not be blank")
        String prompt) {
}
