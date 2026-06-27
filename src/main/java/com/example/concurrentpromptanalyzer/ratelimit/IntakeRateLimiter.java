package com.example.concurrentpromptanalyzer.ratelimit;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.exception.TooManyBatchesException;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * Intake rate limiter implemented with a counting {@link Semaphore}.
 *
 * <p>A permit is acquired (non-blocking) when a batch is admitted and released when the batch
 * finishes processing. If no permit is available the submission is rejected with
 * {@link TooManyBatchesException} (HTTP 429), so the service never accepts more than
 * {@code analyzer.max-in-flight-batches} concurrent batches.
 */
@Component
public class IntakeRateLimiter {

    private final Semaphore permits;
    private final int maxInFlight;

    public IntakeRateLimiter(AnalyzerProperties properties) {
        this.maxInFlight = properties.getMaxInFlightBatches();
        // Fair semaphore so admission order is predictable under contention.
        this.permits = new Semaphore(maxInFlight, true);
    }

    /**
     * Reserves an intake permit or fails fast.
     *
     * @throws TooManyBatchesException if the in-flight limit is already reached
     */
    public void acquire() {
        if (!permits.tryAcquire()) {
            throw new TooManyBatchesException(
                    "Too many batches in flight (limit " + maxInFlight + "). Please retry later.");
        }
    }

    /** Releases a previously acquired permit. Must be called exactly once per successful acquire. */
    public void release() {
        permits.release();
    }

    /** Permits currently available — exposed for tests/diagnostics. */
    public int availablePermits() {
        return permits.availablePermits();
    }
}
