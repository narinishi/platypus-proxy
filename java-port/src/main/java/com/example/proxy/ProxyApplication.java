package com.example.proxy;

import com.example.proxy.dialer.ContextDialer;
import com.example.proxy.dialer.FixedEndpointDialer;
import com.example.proxy.dialer.ProxyDialer;
import com.example.proxy.dialer.RetryDialer;
import com.example.proxy.dto.Endpoint;
import com.example.proxy.handler.HealthHandler;
import com.example.proxy.handler.ProxyHandler;
import com.example.proxy.logging.ConditionalLogger;
import com.example.proxy.provider.HolaProvider;
import com.example.proxy.provider.OperaProvider;
import com.example.proxy.provider.ProviderSession;
import com.example.proxy.provider.ProxyProvider;
import com.example.proxy.resolver.DnsResolverFactory;
import com.example.proxy.resolver.LookupNetIP;
import com.example.proxy.resolver.advanced.AdvancedResolver;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.ServerConfiguration;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

// TODO: identify why Opera backend seems to caused ads to be blocked on speedtest.net

public class ProxyApplication {
    // private static final Logger log = Logger.getLogger(ProxyApplication.class.getName());

    private static volatile boolean running = true;

    public static void main(String[] args) {
        CLIArgs cliArgs = CLIArgs.parse(args);
        if (cliArgs == null) {
            System.exit(0);
        }

        Level logLevel = parseLogLevel(cliArgs.getVerbosity());
        Logger log = Logger.getLogger("com.example.proxy");
        log.setLevel(logLevel);
        ConditionalLogger logger = new ConditionalLogger(log, cliArgs.getVerbosity());

        logger.Info("platypus-proxy client version 1.0-SNAPSHOT is starting...");

        ContextDialer dialer = createBaseDialer(cliArgs);

        // Wrap base dialer with outbound proxy if configured
        String outboundProxyUrl = cliArgs.getOutboundProxy();
        if (outboundProxyUrl != null && !outboundProxyUrl.isEmpty()) {
            logger.Info("Configuring outbound proxy: %s", outboundProxyUrl);
            try {
                ContextDialer proxyDialer =
                        com.example.proxy.dialer.OutboundProxyDialer.fromURL(outboundProxyUrl, dialer);
                dialer = proxyDialer;
                logger.Info("Outbound proxy dialer installed successfully.");
            } catch (IllegalArgumentException e) {
                logger.Error("Failed to configure outbound proxy: %s", e.getMessage());
                System.exit(7);
            }
        }

        // Select provider
        ProxyProvider provider;
        switch (cliArgs.getProvider()) {
            case "opera":
                provider = new OperaProvider();
                break;
            case "hola":
            default:
                provider = new HolaProvider();
                break;
        }

        // Initialize provider (handles list-countries, list-proxies internally)
        ProviderSession session;
        try {
            session = provider.initialize(cliArgs, dialer, logger);
        } catch (Exception e) {
            logger.Critical("Provider initialization failed: %s", e.getMessage());
            System.exit(4);
            return;
        }

        // Provider may have called System.exit for list/list-proxies
        if (session == null) {
            return;
        }

        Endpoint endpoint = session.endpoint;
        logger.Info("Constructing fallback DNS upstream...");
        LookupNetIP resolver;
        if (cliArgs.isDirectDns()) {
            resolver = new AdvancedResolver();
        } else {
            resolver = DnsResolverFactory.fastResolverFromUrls(cliArgs.getResolver());
        }

        // Determine hideSNI for Opera: when fakeSNI is empty, suppress SNI
        // For Hola: use the existing hideSNI flag
        boolean hideSNI;
        if ("opera".equals(cliArgs.getProvider())) {
            hideSNI = cliArgs.getFakeSNI() == null || cliArgs.getFakeSNI().isEmpty();
        } else {
            hideSNI = cliArgs.isHideSNI();
        }

        ContextDialer handlerDialer = new ProxyDialer(
                endpoint.getNetAddr(),
                endpoint.getTlsName(),
                session.authProvider,
                dialer,
                hideSNI,
                resolver
        );

        // retry dialer here is not used
        @SuppressWarnings("unused")
        ContextDialer retryDialer = new RetryDialer(handlerDialer, resolver, log);

        ContextDialer requestDialer = new FixedEndpointDialer(
                endpoint.getNetAddr(),
                endpoint.getTlsName(),
                dialer,
                hideSNI
        );

        ProxyHandler proxyHandler = new ProxyHandler(handlerDialer, requestDialer, session.authProvider, logger);

        int port = parsePort(cliArgs.getBindAddress());
        String host = parseHost(cliArgs.getBindAddress());

        logger.Info("Endpoint: %s", endpoint.getNetAddr());
        logger.Info("Starting proxy server...");

        // Setup shutdown hook for graceful shutdown via Grizzly
        AtomicReference<HttpServer> serverRef = new AtomicReference<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.Info("Shutting down...");
            running = false;
            HttpServer srv = serverRef.get();
            if (srv != null) {
                srv.shutdown();
            }
        }));

        // Create and configure Grizzly HTTP server
        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("proxy", host, port);

        // NOTE: large pool
        ThreadPoolConfig workerConfig = ThreadPoolConfig.defaultConfig()
            .setPoolName("grizzly-worker")
            .setCorePoolSize(32)   // enough for 32 concurrent blocking operations
            .setMaxPoolSize(128)   // allow burst
            .setQueueLimit(-1);    // unbounded queue (or set a reasonable limit)

        listener.getTransport().setWorkerThreadPoolConfig(workerConfig);

        listener.getKeepAlive().setMaxRequestsCount(1000);
        listener.getKeepAlive().setIdleTimeoutInSeconds(30);
        server.addListener(listener);
        ServerConfiguration config = server.getServerConfiguration();

        config.addHttpHandler(proxyHandler, "/");

        config.addHttpHandler(new HealthHandler(), "/health");

        serverRef.set(server);

        // Schedule provider refresh after server starts
        ScheduledExecutorService refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "provider-refresh");
            t.setDaemon(true);
            return t;
        });
        session.refreshStarter.accept(refreshScheduler);

        logger.Info("Init complete.");
        logger.Info("Proxy server started on %s:%d", host, port);
        logger.Info("Health endpoint: /health");

        // Start the Grizzly server (blocking until shutdown)
        try {
            server.start();

            if (cliArgs.getTest() != null && !cliArgs.getTest().isEmpty()) {
                runTest(cliArgs, logger);
                running = false;
            } else {
                while (running && server.isStarted()) {
                    Thread.sleep(500);
                }
            }
        } catch (Exception e) {
            if (running) {
                logger.Critical("Failed to start server: %s", e.getMessage());
                System.exit(6);
            }
        } finally {
            if (server.isStarted()) {
                server.shutdown();
            }
            refreshScheduler.shutdown();
        }
    }

    private static void runTest(CLIArgs cliArgs, ConditionalLogger logger) throws Exception {
        String host = parseHost(cliArgs.getBindAddress());
        int port = parsePort(cliArgs.getBindAddress());
        String testType = cliArgs.getTest();

        logger.Info("Test (%s): connecting to proxy at %s:%d", testType, host, port);

        long start = System.currentTimeMillis();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));

        if ("file".equals(testType)) {
            URL url = URI.create("http://speedtest.tele2.net/10MB.zip").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            logger.Info("Test: response code: %d", responseCode);

            java.io.InputStream in = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            long total = 0;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
            }
            in.close();

            double mb = total / (1024.0 * 1024.0);
            logger.Info("Test: downloaded %.2f MB in %d ms", mb, System.currentTimeMillis() - start);
            if (total >= 10L * 1024 * 1024) {
                logger.Info("Test: download size OK");
            } else {
                logger.Error("Test FAILED: expected ~10MB, got %.2f MB", mb);
            }
        } else {
            URL url = URI.create("https://api.ipify.org?format=json").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            logger.Info("Test: response code: %d", responseCode);

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append("\n");
            }
            reader.close();

            String respBody = body.toString().trim();
            logger.Info("Test: response body:\n%s", respBody);
            logger.Info("Test: completed in %d ms", System.currentTimeMillis() - start);

            if (!respBody.startsWith("{")) {
                logger.Error("Test FAILED: response is not JSON");
            } else {
                logger.Info("Test: response is valid JSON");
            }
        }
    }

    // TODO: clean up
    private static Level parseLogLevel(int verbosity) {
        if (verbosity <= 10) return Level.FINE;
        if (verbosity <= 20) return Level.INFO;
        if (verbosity <= 30) return Level.WARNING;
        if (verbosity <= 40) return Level.SEVERE;
        return Level.SEVERE;
    }

    private static ContextDialer createBaseDialer(CLIArgs cliArgs) {
        // NOTE: nothing needs resolving here
        
        return (network, address) -> {
            int lastColon = address.lastIndexOf(':');
            String host = lastColon > 0 ? address.substring(0, lastColon) : address;
            
            int port = lastColon > 0 ? Integer.parseInt(address.substring(lastColon + 1)) : 80;

            java.net.Socket socket = new java.net.Socket(host, port);

            socket.setTcpNoDelay(true);

            return socket;
        };
    }

    private static int parsePort(String bindAddress) {
        String[] parts = bindAddress.split(":");
        return parts.length > 1 ? Integer.parseInt(parts[1]) : 8080;
    }

    private static String parseHost(String bindAddress) {
        String[] parts = bindAddress.split(":");
        return parts.length > 0 ? parts[0] : "127.0.0.1";
    }
}
