package com.example.concurrentpromptanalyzer.service;

import org.springframework.stereotype.Component;

/**
 * Tiny abstraction over {@link Thread#sleep(long)} so retry/backoff timing can be stubbed in unit
 * tests, keeping them fast and deterministic instead of actually sleeping for seconds.
 */
public interface Sleeper {

    void sleep(long millis) throws InterruptedException;

    /** Production implementation that really sleeps the calling (worker) thread. */
    @Component
    class RealSleeper implements Sleeper {
        @Override
        public void sleep(long millis) throws InterruptedException {
            if (millis > 0) {
                Thread.sleep(millis);
            }
        }
    }
}
