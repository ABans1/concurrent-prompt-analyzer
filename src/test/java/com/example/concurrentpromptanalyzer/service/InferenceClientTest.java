package com.example.concurrentpromptanalyzer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class InferenceClientTest {

    private static final String URL = "http://localhost/mock/infer";

    private MockRestServiceServer server;
    private InferenceClient inferenceClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        AnalyzerProperties properties = new AnalyzerProperties();
        properties.getInference().setPath("/mock/infer");
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoffMs(1);
        properties.getRetry().setMaxBackoffMs(2);
        properties.getRetry().setJitterMs(0);

        // No-op sleeper keeps the test fast and deterministic (no real backoff sleeps).
        Sleeper noopSleeper = millis -> { };
        inferenceClient = new InferenceClient(restClient, properties, noopSleeper);
    }

    @Test
    void retriesAfter429ThenSucceeds() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"output\":\"ok\",\"model\":\"m\",\"latencyMs\":1}",
                        MediaType.APPLICATION_JSON));

        InferenceResult result = inferenceClient.infer("hello");

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("ok");
        assertThat(result.attempts()).isEqualTo(2);
        server.verify();
    }

    @Test
    void exhaustsRetriesOnPersistentFailureWithoutDropping() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        InferenceResult result = inferenceClient.infer("hello");

        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.error()).contains("429");
        server.verify();
    }

    @Test
    void failsFastOnNonRetryable400() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        InferenceResult result = inferenceClient.infer("hello");

        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(1);
        server.verify();
    }
}
