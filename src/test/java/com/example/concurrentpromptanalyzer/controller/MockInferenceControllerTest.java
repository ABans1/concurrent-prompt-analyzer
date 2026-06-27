package com.example.concurrentpromptanalyzer.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.model.InferenceRequest;
import com.example.concurrentpromptanalyzer.model.InferenceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class MockInferenceControllerTest {

    private MockInferenceController controller;

    @BeforeEach
    void setUp() {
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.getMock().setFailEveryNth(3);
        properties.getMock().setMinLatencyMs(0);
        properties.getMock().setMaxLatencyMs(0);
        controller = new MockInferenceController(properties);
    }

    @Test
    void returns429OnEveryThirdCall() throws Exception {
        InferenceRequest request = new InferenceRequest("hello");

        assertThat(controller.infer(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.infer(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.infer(request).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(controller.infer(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.infer(request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.infer(request).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void successfulResponseEchoesPromptInOutput() throws Exception {
        ResponseEntity<InferenceResponse> response = controller.infer(new InferenceRequest("ping"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().output()).contains("ping");
        assertThat(response.getBody().model()).isEqualTo("mock-model-v1");
    }

    @Test
    void rejectsWith429WhenConcurrencyLimitExceeded() throws Exception {
        // maxConcurrent=0 means no permit is ever available, so even the very first call is
        // rejected by the concurrency limiter (the periodic-429 branch would only trigger later),
        // which deterministically proves the concurrency-based rate limit returns 429.
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.getMock().setMaxConcurrent(0);
        properties.getMock().setFailEveryNth(100);
        MockInferenceController limited = new MockInferenceController(properties);

        assertThat(limited.infer(new InferenceRequest("hello")).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
