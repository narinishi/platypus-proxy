package com.example.proxy.logging;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Async wrapper for java.util.logging.Logger.
 *
 * Drop-in usage:
 *   Logger   sync = Logger.getLogger("com.example.Foo");
 *   AsyncLog log  = new AsyncLog(sync);
 *   log.log(Level.INFO, "Hello");
 *
 * Call shutdown() before exit to flush pending messages.
 */
public final class AsyncLog {

    private final Logger delegate;
    private final ExecutorService executor;

    public AsyncLog(Logger delegate) {
        this.delegate = delegate;
        this.executor = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "async-log-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );
    }

    /**
     * Convenience: wraps Logger.getLogger(name) automatically.
     */
    public AsyncLog(String name) {
        this(Logger.getLogger(name));
    }

    /**
     * Core method — level check is synchronous (cheap),
     * actual logging is asynchronous.
     */
    public void log(Level level, String msg) {
        if (!delegate.isLoggable(level)) {
            return; // fast-path: no allocation, no enqueue
        }
        // Capture level + message; submit to background thread
        executor.submit(() -> delegate.log(level, msg));
    }

    /**
     * Log with a throwable (common overload).
     */
    public void log(Level level, String msg, Throwable thrown) {
        if (!delegate.isLoggable(level)) {
            return;
        }
        executor.submit(() -> delegate.log(level, msg, thrown));
    }

    /**
     * Graceful shutdown — waits for queued messages to drain.
     * Call this before JVM exit (e.g., in a shutdown hook).
     */
    public void shutdown() {
        shutdown(5, TimeUnit.SECONDS);
    }

    public void shutdown(long timeout, TimeUnit unit) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}