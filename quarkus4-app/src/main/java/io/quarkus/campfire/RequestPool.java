package io.quarkus.campfire;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Where the requests an event loop can't finish run: cache misses, other routes, anything that needs
 * SQLite beyond a hit's prefetched reads. They render and gzip here; their SQL runs on the
 * {@link SqliteStage}, which they wait for. By default a virtual thread per request: waiting for the
 * stage unmounts it, so its carrier renders another request meanwhile and nothing needs sizing
 * (measured at 100k visible tabs: 1,604 pages/s against 905 with 12 platform threads; NOTES.md).
 * The requests in flight are bounded: past the bound a request is refused
 * ({@link java.util.concurrent.RejectedExecutionException}) and answered 503.
 */
@Singleton
public class RequestPool {
    /** Platform threads (with {@code campfire.request-pool.virtual=false}); 0: one per available processor. */
    @ConfigProperty(name = "campfire.request-pool.size", defaultValue = "0")
    int size;

    /** Requests in flight (virtual threads) or waiting for a thread (platform threads) at most. */
    @ConfigProperty(name = "campfire.request-pool.queue", defaultValue = "16384")
    int queue;

    /** A virtual thread per request (default), or a pool of platform threads: either only waits for SQLite, never calls it. */
    @ConfigProperty(name = "campfire.request-pool.virtual", defaultValue = "true")
    boolean virtual;

    private ThreadPoolExecutor executor;
    private java.util.concurrent.ExecutorService virtualExecutor;
    /** With virtual threads: a permit per request in flight. */
    private java.util.concurrent.Semaphore inFlight;

    @PostConstruct
    void start() {
        if (virtual) {
            virtualExecutor = java.util.concurrent.Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("campfire-request-v", 0).factory());
            inFlight = new java.util.concurrent.Semaphore(queue);
            return;
        }
        int threads = size > 0 ? size : Runtime.getRuntime().availableProcessors();
        AtomicInteger next = new AtomicInteger();
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue),
                task -> Thread.ofPlatform().name("campfire-request-" + next.getAndIncrement()).daemon(true).unstarted(task),
                new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
    }

    /** Runs {@code task} on a pool thread; throws {@link java.util.concurrent.RejectedExecutionException} when the queue is full. */
    void execute(Runnable task) {
        if (virtual) {
            if (!inFlight.tryAcquire()) throw new java.util.concurrent.RejectedExecutionException("request pool full");
            virtualExecutor.execute(() -> {
                try {
                    task.run();
                } finally {
                    inFlight.release();
                }
            });
            return;
        }
        executor.execute(task);
    }

    @PreDestroy
    void stop() throws InterruptedException {
        java.util.concurrent.ExecutorService e = virtual ? virtualExecutor : executor;
        e.shutdown();
        e.awaitTermination(5, TimeUnit.SECONDS);
    }
}
