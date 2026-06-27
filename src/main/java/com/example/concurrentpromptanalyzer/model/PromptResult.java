package com.example.concurrentpromptanalyzer.model;

/**
 * Per-prompt outcome carried in the aggregated batch result.
 *
 * @param index    position of the prompt within the submitted array (stable ordering)
 * @param prompt   the (trimmed) prompt text
 * @param status   SUCCESS or FAILED
 * @param output   inference output when successful, otherwise {@code null}
 * @param attempts number of attempts made against the mock endpoint (>= 1)
 * @param error    error detail when failed, otherwise {@code null}
 */
public record PromptResult(
        int index,
        String prompt,
        PromptStatus status,
        String output,
        int attempts,
        String error) {

    public static PromptResult success(int index, String prompt, String output, int attempts) {
        return new PromptResult(index, prompt, PromptStatus.SUCCESS, output, attempts, null);
    }

    public static PromptResult failed(int index, String prompt, String error, int attempts) {
        return new PromptResult(index, prompt, PromptStatus.FAILED, null, attempts, error);
    }
}
