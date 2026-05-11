package com.example.proxy.provider;

import com.example.proxy.dto.Endpoint;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ProviderSession {
    public final Endpoint endpoint;
    public final Supplier<String> authProvider;
    public final long refreshIntervalMs;
    public final Consumer<ScheduledExecutorService> refreshStarter;

    public ProviderSession(Endpoint endpoint, Supplier<String> authProvider,
                          long refreshIntervalMs,
                          Consumer<ScheduledExecutorService> refreshStarter) {
        this.endpoint = endpoint;
        this.authProvider = authProvider;
        this.refreshIntervalMs = refreshIntervalMs;
        this.refreshStarter = refreshStarter;
    }
}
