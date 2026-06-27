package com.example.concurrentpromptanalyzer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutorConfigTest {

    /**
     * Proves the worker pool genuinely scales up to {@code max-size} under load instead of stalling
     * at {@code core-size}. With a plain bounded-queue executor this test would fail, because tasks
     * beyond {@code core} would queue (capacity 100) and only {@code core} threads would ever run.
     */
    @Test
    void poolBurstsBeyondCoreUpToMaxUnderLoad() throws InterruptedException {
        int core = 2;
        int max = 6;
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.getPool().setCoreSize(core);
        properties.getPool().setMaxSize(max);
        properties.getPool().setQueueCapacity(100);

        ExecutorService executor = new ExecutorConfig().promptWorkerExecutor(properties);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;

        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        try {
            // Submit one blocking task at a time, waiting until it has actually started before the
            // next submission. This keeps getActiveCount() accurate at offer() time, so the pool
            // deterministically grows a fresh thread (up to max) for each task instead of queueing.
            for (int n = 1; n <= max; n++) {
                executor.submit(() -> {
                    started.incrementAndGet();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                int expected = n;
                awaitUntil(() -> started.get() >= expected);
            }

            // All `max` tasks are running simultaneously => the pool burst past core up to max.
            assertThat(started.get()).isEqualTo(max);
            assertThat(pool.getPoolSize()).isEqualTo(max);
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Condition not met within timeout");
    }

    /**
     * Proves the {@link ExecutorConfig.ForceQueueOrCallerRunsPolicy} never drops work: even when the
     * pool and the bounded queue are both saturated, every submitted task still runs (extras are
     * force-queued, and once the queue is full too they run on the caller thread). Also asserts the
     * pool stays bounded by {@code max-size}.
     */
    @Test
    void neverDropsTasksAndStaysBoundedUnderSaturation() throws InterruptedException {
        int core = 2;
        int max = 4;
        int queueCapacity = 10;
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.getPool().setCoreSize(core);
        properties.getPool().setMaxSize(max);
        properties.getPool().setQueueCapacity(queueCapacity);

        ExecutorService executor = new ExecutorConfig().promptWorkerExecutor(properties);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;

        int totalTasks = 2_000; // far more than max + queueCapacity, forcing buffering + caller-runs
        AtomicInteger ran = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(totalTasks);
        try {
            for (int i = 0; i < totalTasks; i++) {
                executor.execute(() -> {
                    ran.incrementAndGet();
                    done.countDown();
                });
            }

            assertThat(done.await(15, TimeUnit.SECONDS))
                    .as("all tasks should complete (none dropped)")
                    .isTrue();
            assertThat(ran.get()).isEqualTo(totalTasks);
            assertThat(pool.getLargestPoolSize())
                    .as("pool must never exceed max-size")
                    .isLessThanOrEqualTo(max);
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * Verifies worker threads carry the configured name prefix, so log lines and the MDC correlation
     * fields are traceable to the {@code prompt-worker-*} pool.
     */
    @Test
    void worksOnNamedWorkerThreads() throws InterruptedException {
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.getPool().setThreadNamePrefix("prompt-worker-");

        ExecutorService executor = new ExecutorConfig().promptWorkerExecutor(properties);
        ConcurrentHashMap<String, Boolean> threadNames = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(20);
        try {
            for (int i = 0; i < 20; i++) {
                executor.execute(() -> {
                    threadNames.put(Thread.currentThread().getName(), Boolean.TRUE);
                    done.countDown();
                });
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadNames.keySet()).allMatch(name -> name.startsWith("prompt-worker-"));
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
