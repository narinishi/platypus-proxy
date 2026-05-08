package com.example.proxy.provider;

import com.example.proxy.CLIArgs;
import com.example.proxy.RetryPolicy;
import com.example.proxy.dialer.ContextDialer;
import com.example.proxy.dto.Endpoint;
import com.example.proxy.dto.SEGeoEntry;
import com.example.proxy.dto.SEIPEntry;
import com.example.proxy.http.OperaApiClient;
import com.example.proxy.logging.ConditionalLogger;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class OperaProvider implements ProxyProvider {

    @Override
    public ProviderSession initialize(CLIArgs cliArgs, ContextDialer dialer,
                                      ConditionalLogger logger) throws Exception {
        OperaApiClient client = new OperaApiClient(dialer);

        RetryPolicy.execute("anonymous registration",
                cliArgs.getInitRetries(), cliArgs.getInitRetryIntervalMillis(), logger, () -> {
            try {
                client.anonRegister();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        RetryPolicy.execute("device registration",
                cliArgs.getInitRetries(), cliArgs.getInitRetryIntervalMillis(), logger, () -> {
            try {
                client.registerDevice();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        if (cliArgs.isListCountries()) {
            try {
                List<SEGeoEntry> geos = client.geoList();
                System.out.println("country code\tcountry name");
                for (SEGeoEntry geo : geos) {
                    System.out.println(geo.getCountryCode() + "\t" +
                            (geo.getCountry() != null ? geo.getCountry() : ""));
                }
            } catch (Exception e) {
                logger.Error("Failed to list countries: %s", e.getMessage());
                System.exit(3);
            }
            System.exit(0);
        }

        if (cliArgs.isListProxies()) {
            try {
                String requestedGeo = "\"" + cliArgs.getCountry().toUpperCase() + "\",,";
                List<SEIPEntry> ips = client.discover(requestedGeo);
                String[] creds = client.getProxyCredentials();
                String auth = "Basic " + Base64.getEncoder().encodeToString(
                        (creds[0] + ":" + creds[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                System.out.println("Proxy login: " + creds[0]);
                System.out.println("Proxy password: " + creds[1]);
                System.out.println("Proxy-Authorization: " + auth);
                System.out.println();
                System.out.println("host,ip_address,port");
                for (int i = 0; i < ips.size(); i++) {
                    SEIPEntry ip = ips.get(i);
                    String hostname = ip.getGeo().getCountryCode().toLowerCase() + i + ".sec-tunnel.com";
                    if (ip.getPorts() != null) {
                        for (int port : ip.getPorts()) {
                            System.out.println(hostname + "," + ip.getIp() + "," + port);
                        }
                    }
                }
            } catch (Exception e) {
                logger.Error("Failed to list proxies: %s", e.getMessage());
                System.exit(3);
            }
            System.exit(0);
        }

        String requestedGeo = "\"" + cliArgs.getCountry().toUpperCase() + "\",,";
        logger.Info("Discovered endpoints for country %s", cliArgs.getCountry());
        List<SEIPEntry>[] ipsRef = new List[1];
        Exception discoverErr = RetryPolicy.execute("discover",
                cliArgs.getInitRetries(), cliArgs.getInitRetryIntervalMillis(), logger, () -> {
            try {
                List<SEIPEntry> result = client.discover(requestedGeo);
                if (result == null || result.isEmpty()) {
                    throw new RuntimeException("empty endpoints list");
                }
                ipsRef[0] = result;
            } catch (Exception e) {
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException(e);
            }
        });
        if (discoverErr != null) {
            logger.Error("Discover failed: %s", discoverErr.getMessage());
            System.exit(12);
        }

        List<SEIPEntry> ips = ipsRef[0];
        SEIPEntry selected = ips.get(0);
        String host = selected.getIp();
        int port = (selected.getPorts() != null && !selected.getPorts().isEmpty())
                ? selected.getPorts().get(0) : 443;
        String tlsName = cliArgs.getCountry().toLowerCase() + "0.sec-tunnel.com";

        Endpoint endpoint = new Endpoint(host, port, tlsName);
        logger.Info("Selected endpoint: %s (TLS name: %s)", endpoint.getNetAddr(), tlsName);

        String[] creds = client.getProxyCredentials();
        AtomicReference<String> authRef = new AtomicReference<>(
            "Basic " + Base64.getEncoder().encodeToString(
                    (creds[0] + ":" + creds[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        long refreshMs = cliArgs.getRotateMillis();

        return new ProviderSession(endpoint, () -> authRef.get(), 0,
            scheduler -> {
                ScheduledExecutorService refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "opera-refresh");
                    t.setDaemon(true);
                    return t;
                });
                refreshExecutor.scheduleAtFixedRate(() -> {
                    try {
                        logger.Info("Refreshing Opera login...");
                        client.login();
                        logger.Info("Login refreshed.");

                        logger.Info("Refreshing Opera device password...");
                        client.deviceGeneratePassword();
                        logger.Info("Device password refreshed.");

                        String[] newCreds = client.getProxyCredentials();
                        String newAuth = "Basic " + Base64.getEncoder().encodeToString(
                                (newCreds[0] + ":" + newCreds[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        authRef.set(newAuth);

                    } catch (Exception e) {
                        logger.Error("Opera refresh failed: %s", e.getMessage());
                    }
                }, refreshMs, refreshMs, TimeUnit.MILLISECONDS);
            });
    }
}
