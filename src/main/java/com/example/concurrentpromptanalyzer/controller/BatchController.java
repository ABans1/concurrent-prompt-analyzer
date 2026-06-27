package com.example.concurrentpromptanalyzer.controller;

import com.example.concurrentpromptanalyzer.model.BatchAcceptedResponse;
import com.example.concurrentpromptanalyzer.model.BatchResultResponse;
import com.example.concurrentpromptanalyzer.model.PromptBatchRequest;
import com.example.concurrentpromptanalyzer.observability.MdcKeys;
import com.example.concurrentpromptanalyzer.service.BatchProcessingService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public API:
 * <ul>
 *   <li>{@code POST /api/v1/batches} — submit an array of prompts; returns {@code 202 Accepted}
 *       with a batchId immediately while processing runs asynchronously.</li>
 *   <li>{@code GET /api/v1/batches/{batchId}} — poll for the live/aggregated result.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {

    private final BatchProcessingService batchProcessingService;

    public BatchController(BatchProcessingService batchProcessingService) {
        this.batchProcessingService = batchProcessingService;
    }

    @PostMapping
    public ResponseEntity<BatchAcceptedResponse> submit(@Valid @RequestBody PromptBatchRequest request) {
        String requestId = MDC.get(MdcKeys.REQUEST_ID);
        BatchAcceptedResponse ack = batchProcessingService.submit(request, requestId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ack);
    }

    @GetMapping("/{batchId}")
    public ResponseEntity<BatchResultResponse> get(@PathVariable String batchId) {
        return ResponseEntity.ok(batchProcessingService.getBatch(batchId));
    }
}
