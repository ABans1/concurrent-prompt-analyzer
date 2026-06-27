package com.example.concurrentpromptanalyzer.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Inbound payload for {@code POST /api/v1/batches}.
 *
 * <p>The collection-level constraints ({@code @NotNull}, {@code @NotEmpty}) are enforced here; the
 * per-element and size-bound constraints depend on configured limits and are validated in the
 * service layer (see {@code BatchProcessingService#submit}). Violations are translated to a
 * {@code 400 Bad Request} with field-level detail by the global exception handler.
 */
public record PromptBatchRequest(
        @NotNull(message = "prompts must not be null")
        @NotEmpty(message = "prompts must contain at least one entry")
        List<String> prompts) {
}
