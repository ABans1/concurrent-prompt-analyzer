package com.example.concurrentpromptanalyzer.config;

import java.io.Serial;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the two infrastructure beans the concurrency model depends on:
 *
 * <ul>
 *   <li>a <strong>bounded</strong> {@link ExecutorService} used as the worker pool — deliberately
 *       NOT the {@link java.util.concurrent.ForkJoinPool#commonPool() common pool}, which is sized
 *       for CPU-bound work and is a poor fit for blocking HTTP calls; and</li>
 *   <li>a {@link RestClient} with explicit connect/read timeouts so a stuck downstream cannot pin
 *       a worker forever.</li>
 * </ul>
 */
@Configuration
public class ExecutorConfig {

    /**
     * A bounded worker pool that actually scales up to {@code max-size} for blocking I/O work.
     *
     * <p>A plain {@link ThreadPoolExecutor} backed by a bounded queue only grows past {@code core}
     * once the queue is <em>full</em>, so {@code max-size} would effectively never be reached and
     * real parallelism would be pinned at {@code core-size}. To fix that we use a {@link ScalingTaskQueue}
     * whose {@code offer} refuses a task while the pool can still grow — forcing the executor to spin
     * up a new thread (up to {@code max-size}) <em>before</em> queueing. Only once the pool is at
     * {@code max-size} do tasks buffer in the queue; and only when the queue is also full does the
     * {@link ForceQueueOrCallerRunsPolicy} fall back to running on the caller thread (backpressure),
     * so no prompt is ever dropped.
     */
    @Bean(name = "promptWorkerExecutor", destroyMethod = "shutdown")
    public ExecutorService promptWorkerExecutor(AnalyzerProperties properties) {
        AnalyzerProperties.Pool pool = properties.getPool();
        ScalingTaskQueue queue = new ScalingTaskQueue(pool.getQueueCapacity());
        ThreadFactory threadFactory = new NamedThreadFactory(pool.getThreadNamePrefix());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                pool.getCoreSize(),
                pool.getMaxSize(),
                60L,
                TimeUnit.SECONDS,
                queue,
                threadFactory,
                new ForceQueueOrCallerRunsPolicy());
        queue.setExecutor(executor);
        // Let burst threads (and idle core threads) be reclaimed when traffic subsides.
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean
    public RestClient inferenceRestClient(AnalyzerProperties properties) {
        AnalyzerProperties.Inference inference = properties.getInference();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(inference.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(inference.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(inference.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * A bounded queue that makes a {@link ThreadPoolExecutor} prefer starting a new thread (up to
     * {@code maximumPoolSize}) over queueing while all current threads are busy. This is the
     * well-known pattern (used by Tomcat's task queue) for I/O-bound pools that must reach full
     * thread capacity rather than stalling at the core size.
     */
    static final class ScalingTaskQueue extends LinkedBlockingQueue<Runnable> {

        @Serial
        private static final long serialVersionUID = 1L;

        private transient ThreadPoolExecutor executor;

        ScalingTaskQueue(int capacity) {
            super(capacity);
        }

        void setExecutor(ThreadPoolExecutor executor) {
            this.executor = executor;
        }

        @Override
        public boolean offer(Runnable task) {
            if (executor == null) {
                return super.offer(task);
            }
            // If every live thread is busy but we haven't hit max yet, refuse to queue so the
            // executor is forced to add a new worker thread instead.
            if (executor.getActiveCount() >= executor.getPoolSize()
                    && executor.getPoolSize() < executor.getMaximumPoolSize()) {
                return false;
            }
            return super.offer(task);
        }

        /** Used by the rejection handler to actually enqueue once the pool is at max size. */
        boolean forceQueue(Runnable task) {
            return super.offer(task);
        }
    }

    /**
     * Rejection handler paired with {@link ScalingTaskQueue}: when {@code offer} returned {@code false}
     * only to trigger thread growth and the pool is now at max, genuinely enqueue the task; if the
     * queue is truly full, fall back to running on the caller thread (backpressure, never drop).
     */
    static final class ForceQueueOrCallerRunsPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Worker pool has been shut down");
            }
            if (executor.getQueue() instanceof ScalingTaskQueue queue && queue.forceQueue(task)) {
                return;
            }
            // Queue is full too: throttle the producer by running the task on the calling thread.
            new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(task, executor);
        }
    }

    /** Names worker threads so logs are easy to follow (e.g. {@code prompt-worker-1}). */
    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
