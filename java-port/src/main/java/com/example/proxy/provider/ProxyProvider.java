package com.example.proxy.provider;

import com.example.proxy.CLIArgs;
import com.example.proxy.dialer.ContextDialer;
import com.example.proxy.logging.ConditionalLogger;

public interface ProxyProvider {
    ProviderSession initialize(CLIArgs args, ContextDialer baseDialer,
                               ConditionalLogger logger) throws Exception;
}
