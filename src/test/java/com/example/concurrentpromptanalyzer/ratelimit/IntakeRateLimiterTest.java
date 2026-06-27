package com.example.concurrentpromptanalyzer.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import com.example.concurrentpromptanalyzer.exception.TooManyBatchesException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntakeRateLimiterTest {

    private IntakeRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.setMaxInFlightBatches(3);
        rateLimiter = new IntakeRateLimiter(properties);
    }

    @Test
    void allowsUpToTheConfiguredLimit() {
        rateLimiter.acquire();
        rateLimiter.acquire();
        rateLimiter.acquire();
        assertThat(rateLimiter.availablePermits()).isZero();
    }

    @Test
    void rejectsBeyondTheLimitWith429() {
        rateLimiter.acquire();
        rateLimiter.acquire();
        rateLimiter.acquire();

        assertThatThrownBy(() -> rateLimiter.acquire())
                .isInstanceOf(TooManyBatchesException.class)
                .hasMessageContaining("limit 3");
    }

    @Test
    void releasingFreesAPermitForReuse() {
        rateLimiter.acquire();
        rateLimiter.acquire();
        rateLimiter.acquire();

        rateLimiter.release();
        assertThat(rateLimiter.availablePermits()).isEqualTo(1);

        // A subsequent acquire now succeeds without throwing.
        rateLimiter.acquire();
        assertThat(rateLimiter.availablePermits()).isZero();
    }
}
