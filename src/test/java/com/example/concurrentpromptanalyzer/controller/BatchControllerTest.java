package com.example.concurrentpromptanalyzer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.concurrentpromptanalyzer.exception.BatchNotFoundException;
import com.example.concurrentpromptanalyzer.model.BatchAcceptedResponse;
import com.example.concurrentpromptanalyzer.model.BatchStatus;
import com.example.concurrentpromptanalyzer.service.BatchProcessingService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BatchController.class)
class BatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BatchProcessingService batchProcessingService;

    @Test
    void submitReturns202WithBatchId() throws Exception {
        BatchAcceptedResponse ack = new BatchAcceptedResponse(
                "batch-123", BatchStatus.PENDING, 2, Instant.now());
        when(batchProcessingService.submit(any(), any())).thenReturn(ack);

        mockMvc.perform(post("/api/v1/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompts\":[\"hello\",\"world\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.batchId").value("batch-123"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.acceptedPromptCount").value(2));
    }

    @Test
    void submitWithEmptyPromptsReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompts\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void submitWithMissingPromptsReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void submitWithMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void getUnknownBatchReturns404() throws Exception {
        when(batchProcessingService.getBatch(anyString()))
                .thenThrow(new BatchNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/batches/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
