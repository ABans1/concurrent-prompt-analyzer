package com.example.concurrentpromptanalyzer.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed, validated configuration for the analyzer.
 *
 * <p>Because it is {@code @Validated}, any out-of-range value (e.g. a negative pool size or a
 * zero retry-attempt count) fails fast at application startup rather than surfacing as a confusing
 * runtime error later on.
 */
@Validated
@ConfigurationProperties(prefix = "analyzer")
public class AnalyzerProperties {

    /** Maximum number of prompts permitted in a single batch (validated -> 400). */
    @Min(1)
    private int maxBatchSize = 100;

    /** Maximum length of a single prompt in characters (validated -> 400). */
    @Min(1)
    private int maxPromptLength = 8000;

    /** Intake rate limit: max number of batches allowed in-flight simultaneously (else 429). */
    @Min(1)
    private int maxInFlightBatches = 10;

    @Valid
    @NotNull
    private Pool pool = new Pool();

    @Valid
    @NotNull
    private Retry retry = new Retry();

    @Valid
    @NotNull
    private Mock mock = new Mock();

    @Valid
    @NotNull
    private Inference inference = new Inference();

    @Valid
    @NotNull
    private Journal journal = new Journal();

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getMaxPromptLength() {
        return maxPromptLength;
    }

    public void setMaxPromptLength(int maxPromptLength) {
        this.maxPromptLength = maxPromptLength;
    }

    public int getMaxInFlightBatches() {
        return maxInFlightBatches;
    }

    public void setMaxInFlightBatches(int maxInFlightBatches) {
        this.maxInFlightBatches = maxInFlightBatches;
    }

    public Pool getPool() {
        return pool;
    }

    public void setPool(Pool pool) {
        this.pool = pool;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Mock getMock() {
        return mock;
    }

    public void setMock(Mock mock) {
        this.mock = mock;
    }

    public Inference getInference() {
        return inference;
    }

    public void setInference(Inference inference) {
        this.inference = inference;
    }

    public Journal getJournal() {
        return journal;
    }

    public void setJournal(Journal journal) {
        this.journal = journal;
    }

    /** Bounded worker pool settings. */
    public static class Pool {

        @Min(1)
        private int coreSize = 8;

        @Min(1)
        private int maxSize = 32;

        @Min(0)
        private int queueCapacity = 500;

        @NotBlank
        private String threadNamePrefix = "prompt-worker-";

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }
    }

    /** Retry / exponential-backoff settings for workers calling the mock inference endpoint. */
    public static class Retry {

        @Min(1)
        private int maxAttempts = 5;

        @Min(0)
        private long initialBackoffMs = 200;

        @Min(0)
        private long maxBackoffMs = 2000;

        @Positive
        private double multiplier = 2.0;

        @Min(0)
        private long jitterMs = 300;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public long getJitterMs() {
            return jitterMs;
        }

        public void setJitterMs(long jitterMs) {
            this.jitterMs = jitterMs;
        }
    }

    /** Mock inference endpoint behaviour. */
    public static class Mock {

        /** Every Nth call returns HTTP 429 to exercise worker retry/backoff. */
        @Min(1)
        private int failEveryNth = 3;

        /**
         * Maximum number of inference calls the mock will serve concurrently. Calls beyond this
         * are rejected with HTTP 429 (a true concurrency-based rate limiter), driving worker retry
         * and backoff under load.
         */
        @Min(1)
        private int maxConcurrent = 4;

        @Min(0)
        private long minLatencyMs = 20;

        @Min(0)
        private long maxLatencyMs = 120;

        public int getFailEveryNth() {
            return failEveryNth;
        }

        public void setFailEveryNth(int failEveryNth) {
            this.failEveryNth = failEveryNth;
        }

        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        public long getMinLatencyMs() {
            return minLatencyMs;
        }

        public void setMinLatencyMs(long minLatencyMs) {
            this.minLatencyMs = minLatencyMs;
        }

        public long getMaxLatencyMs() {
            return maxLatencyMs;
        }

        public void setMaxLatencyMs(long maxLatencyMs) {
            this.maxLatencyMs = maxLatencyMs;
        }
    }

    /**
     * Write-ahead journal settings. When enabled, batch submissions and per-prompt results are
     * appended to a durable log file that is replayed on startup to rebuild state and resume any
     * batches that were interrupted by a crash/restart.
     */
    public static class Journal {

        private boolean enabled = true;

        /** Append-only journal file (JSON-lines). */
        @NotBlank
        private String file = "data/batch-journal.jsonl";

        /**
         * When true, recovered batches that were not COMPLETED before the crash are re-processed on
         * startup (only their unfinished prompts).
         */
        private boolean recoverOnStartup = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }

        public boolean isRecoverOnStartup() {
            return recoverOnStartup;
        }

        public void setRecoverOnStartup(boolean recoverOnStartup) {
            this.recoverOnStartup = recoverOnStartup;
        }
    }

    /** HTTP client settings for workers calling the mock endpoint over the wire. */
    public static class Inference {

        @NotBlank
        private String baseUrl = "http://localhost:8080";

        @NotBlank
        private String path = "/mock/infer";

        @Min(1)
        @Max(60_000)
        private int connectTimeoutMs = 1000;

        @Min(1)
        @Max(60_000)
        private int readTimeoutMs = 3000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
