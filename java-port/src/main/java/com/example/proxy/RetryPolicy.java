package com.example.proxy;

import com.example.proxy.logging.ConditionalLogger;

import java.util.function.Function;

public class RetryPolicy {

    public static Function<String, Function<Runnable, Exception>> create(int retries, long retryIntervalMs, ConditionalLogger logger) {
        return new Function<String, Function<Runnable, Exception>>() {
            @Override
            public Function<Runnable, Exception> apply(String name) {
                return new Function<Runnable, Exception>() {
                    @Override
                    public Exception apply(Runnable action) {
                        Exception lastError = null;
                        int maxAttempts = retries <= 0 ? Integer.MAX_VALUE : retries;
                        for (int i = 1; i <= maxAttempts; i++) {
                            if (i > 1) {
                                logger.Warning("Retrying action \"%s\" in %d ms...", name, retryIntervalMs);
                                try { Thread.sleep(retryIntervalMs); } catch (InterruptedException e) { return e; }
                            }
                            logger.Info("Attempting action \"%s\", attempt #%d...", name, i);
                            try {
                                action.run();
                                logger.Info("Action \"%s\" succeeded on attempt #%d", name, i);
                                return null;
                            } catch (Exception e) {
                                lastError = e;
                                logger.Warning("Action \"%s\" failed: %s", name, e.getMessage());
                            }
                        }
                        logger.Critical("All attempts for action \"%s\" have failed. Last error: %s", name,
                                lastError != null ? lastError.getMessage() : "unknown");
                        return lastError;
                    }
                };
            }
        };
    }

    public static Exception execute(String name, int retries, long retryIntervalMs, ConditionalLogger logger, Runnable action) {
        return create(retries, retryIntervalMs, logger).apply(name).apply(action);
    }
}
