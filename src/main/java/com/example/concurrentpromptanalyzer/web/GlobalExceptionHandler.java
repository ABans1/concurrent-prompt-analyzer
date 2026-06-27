package com.example.concurrentpromptanalyzer.web;

import com.example.concurrentpromptanalyzer.exception.BatchNotFoundException;
import com.example.concurrentpromptanalyzer.exception.InvalidBatchException;
import com.example.concurrentpromptanalyzer.exception.TooManyBatchesException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into consistent {@link ApiError} responses with the right HTTP status:
 * 400 for validation/malformed input, 404 for unknown batches, 429 for intake rate limiting,
 * and 500 for anything unexpected.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::formatViolation)
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", List.of("Request body is missing or not valid JSON"));
    }

    @ExceptionHandler(InvalidBatchException.class)
    public ResponseEntity<ApiError> handleInvalidBatch(InvalidBatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "Invalid batch", List.of(ex.getMessage()));
    }

    @ExceptionHandler(BatchNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(BatchNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Batch not found", List.of(ex.getMessage()));
    }

    @ExceptionHandler(TooManyBatchesException.class)
    public ResponseEntity<ApiError> handleTooMany(TooManyBatchesException ex) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", List.of(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                List.of("An unexpected error occurred"));
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message, List<String> details) {
        ApiError error = ApiError.of(status.value(), status.getReasonPhrase(), message, details);
        return ResponseEntity.status(status).body(error);
    }

    private static String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

    private static String formatViolation(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}
