package com.example.proxy.provider;

import com.example.proxy.*;
import com.example.proxy.dialer.ContextDialer;
import com.example.proxy.dto.Endpoint;
import com.example.proxy.dto.ZGetTunnelsResponse;
import com.example.proxy.http.HolaApiClient;
import com.example.proxy.http.HolaApiClient.TemporaryBanException;
import com.example.proxy.http.HolaApiClient.PermanentBanException;
import com.example.proxy.logging.ConditionalLogger;
import com.example.proxy.service.HolaCredentialService;

import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

public class HolaProvider implements ProxyProvider {

    static final String HOLA_EXT_STORE_ID = "gkojfkhlekighikafcpjkiklfbnlmeio";

    @Override
    public ProviderSession initialize(CLIArgs cliArgs, ContextDialer dialer,
                                      ConditionalLogger logger) throws Exception {
        String userAgent = cliArgs.getUserAgent();
        String extVer = cliArgs.getExtVer();

        // Try cache first if both values are needed and caching is enabled
        if ((userAgent == null || userAgent.isEmpty()) && (extVer == null || extVer.isEmpty())
                && cliArgs.isCacheExtver()) {
            ExtVerCache.CachedVersions cached = ExtVerCache.read();
            if (cached != null) {
                logger.Info("Using cached Chrome and extension versions from .extver (age: %d hours)",
                        (System.currentTimeMillis() - cached.timestamp * 1000L) / 3600000L);
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                        + cached.chromeVer.substring(0, cached.chromeVer.indexOf('.'))
                        + ".0.0.0 Safari/537.36";
                extVer = cached.extVer;
            }
        }

        if (userAgent == null || userAgent.isEmpty()) {
            final ContextDialer d = dialer;
            final String[] uaRef = new String[1];
            Exception uaErr = RetryPolicy.execute("get latest version of Chrome browser",
                    cliArgs.getInitRetries(), cliArgs.getInitRetryIntervalMillis(), logger, () -> {
                try {
                    uaRef[0] = fetchChromeUserAgent(d, logger);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            if (uaErr != null) {
                logger.Critical("Can't detect latest Chrome version: %s", uaErr.getMessage());
                System.exit(8);
            }
            userAgent = uaRef[0];
        }

        if (extVer == null || extVer.isEmpty()) {
            final String uaForExt = userAgent;
            HolaApiClient apiClientForExt = new HolaApiClient(dialer, () -> uaForExt);
            final String[] extVerRef = new String[1];
            Exception extErr = RetryPolicy.execute("get latest version of browser extension",
                    cliArgs.getInitRetries(), cliArgs.getInitRetryIntervalMillis(), logger, () -> {
                try {
                    extVerRef[0] = apiClientForExt.getExtVer("113.0", HOLA_EXT_STORE_ID);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            if (extErr != null) {
                logger.Error("Can't detect latest browser extension version: %s", extErr.getMessage());
                logger.Error("Try to specify -ext-ver parameter.");
                System.exit(8);
            }
            extVer = extVerRef[0];
            logger.Info("discovered latest browser extension version: %s", extVer);
            logger.Warning("Detected latest extension version: \"%s\". Pass -ext-ver parameter to skip resolve and speedup startup", extVer);
        }

        // Cache fetched versions for next startup
        boolean userAgentProvided = cliArgs.getUserAgent() != null && !cliArgs.getUserAgent().isEmpty();
        boolean extVerProvided = cliArgs.getExtVer() != null && !cliArgs.getExtVer().isEmpty();
        if (cliArgs.isCacheExtver() && (!userAgentProvided || !extVerProvided)) {
            if (userAgent != null && extVer != null && !extVer.isEmpty()) {
                String chromeVer = extractChromeVer(userAgent);
                if (chromeVer != null) {
                    try {
                        ExtVerCache.write(chromeVer, extVer);
                        logger.Info("Cached Chrome version %s and extension version %s to .extver", chromeVer, extVer);
                    } catch (Exception e) {
                        logger.Warning("Failed to write .extver cache: %s", e.getMessage());
                    }
                }
            }
        }

        if (cliArgs.isListProxies()) {
            listProxies(cliArgs, extVer, dialer, logger);
            System.exit(0);
        }

        if (cliArgs.isListCountries()) {
            listCountries(dialer, logger);
            System.exit(0);
        }

        final String uaForCreds = userAgent;
        HolaApiClient apiClientForCreds = new HolaApiClient(dialer, () -> uaForCreds);
        HolaCredentialService credentialService = new HolaCredentialService(
                dialer, apiClientForCreds, extVer, cliArgs.getCountry(), cliArgs.getProxyType(),
                cliArgs.getInitRetries(), cliArgs.getInitRetryIntervalMillis(),
                cliArgs.getBackoffDeadlineMillis()
        );

        HolaCredentialService.AuthResult authResult;
        try {
            final HolaCredentialService cs = credentialService;
            final HolaCredentialService.AuthResult[] resultRef = new HolaCredentialService.AuthResult[1];
            Exception authErr = RetryPolicy.execute("run credentials service",
                    cliArgs.getInitRetries(), cliArgs.getInitRetryIntervalMillis(), logger, () -> {
                try {
                    resultRef[0] = cs.createAuthProvider(cliArgs.getRotateMillis());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            if (authErr != null) {
                throw authErr;
            }
            authResult = resultRef[0];
        } catch (TemporaryBanException e) {
            logger.Error("Temporary ban detected by Hola API. The proxy cannot start. Please wait before retrying.");
            System.exit(9);
            return null;
        } catch (PermanentBanException e) {
            logger.Error("Permanent ban detected by Hola API. The proxy cannot start.");
            System.exit(9);
            return null;
        } catch (Exception e) {
            logger.Error("Failed to run credentials service: %s", e.getMessage());
            System.exit(4);
            return null;
        }
        HolaCredentialService.AuthProvider authProvider = authResult.authProvider;
        ZGetTunnelsResponse tunnels = authResult.tunnels;

        Endpoint endpoint = selectEndpoint(tunnels, cliArgs.getProxyType(),
                cliArgs.useTrial(), cliArgs.getForcePortField());
        if (endpoint == null) {
            logger.Error("Unable to determine proxy endpoint");
            System.exit(5);
            return null;
        }

        // Hola refresh is already internal to HolaCredentialService
        return new ProviderSession(endpoint, () -> authProvider.getAuth(), 0, scheduler -> {});
    }

    private static Endpoint selectEndpoint(ZGetTunnelsResponse tunnels, String typ,
                                           boolean trial, String forcePortField) {
        if (tunnels == null || tunnels.getIpList() == null || tunnels.getIpList().isEmpty()) {
            return new Endpoint("127.0.0.1", 443, "pool.hola.org");
        }

        String firstIp = tunnels.getIpList().values().iterator().next();
        int port = selectPort(tunnels, typ, trial, forcePortField);
        String tlsName = tunnels.getIpList().keySet().iterator().next();
        return new Endpoint(firstIp, port, tlsName);
    }

    private static int selectPort(ZGetTunnelsResponse tunnels, String typ, boolean trial, String forcePortField) {
        if (forcePortField != null && !forcePortField.isEmpty()) {
            try {
                return Integer.parseInt(forcePortField);
            } catch (NumberFormatException ignored) {}
        }
        if (tunnels.getPort() == null) {
            return trial ? 443 : 80;
        }
        switch (typ) {
            case "peer":
                return trial ? tunnels.getPort().getTrialPeer() : tunnels.getPort().getPeer();
            case "direct":
            case "lum":
            case "pool":
            case "virt":
                return trial ? tunnels.getPort().getTrial() : tunnels.getPort().getDirect();
            default:
                return tunnels.getPort().getHola();
        }
    }

    private static String extractChromeVer(String userAgent) {
        String prefix = "Chrome/";
        int idx = userAgent.indexOf(prefix);
        if (idx < 0) return null;
        int start = idx + prefix.length();
        int end = userAgent.indexOf(' ', start);
        if (end < 0) end = userAgent.length();
        String ver = userAgent.substring(start, end);
        return ver.contains(".") ? ver : null;
    }

    private static String fetchChromeUserAgent(ContextDialer dialer, ConditionalLogger logger) {
        HolaApiClient apiClient = new HolaApiClient(dialer, () -> "");
        try {
            String chromeVer = apiClient.getChromeVer();
            logger.Info("latest Chrome version is %s", chromeVer);
            String majorVer = chromeVer.contains(".") ? chromeVer.substring(0, chromeVer.indexOf('.')) : chromeVer;
            String ua = String.format(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%s.0.0.0 Safari/537.36",
                majorVer);
            logger.Info("discovered latest Chrome User-Agent: %s", ua);
            return ua;
        } catch (Exception e) {
            logger.Warning("Can't detect latest Chrome version: %s. Using default User-Agent.", e.getMessage());
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";
        }
    }

    private static void listCountries(ContextDialer dialer, ConditionalLogger logger) {
        logger.Info("Listing available countries...");
        HolaApiClient apiClient = new HolaApiClient(dialer, () -> "");
        try {
            String url = "https://client.hola.org/client_cgi/vpn_countries.json";
            java.net.URL targetUrl = new java.net.URL(url);
            String host = targetUrl.getHost();
            int port = targetUrl.getDefaultPort();
            Socket socket = dialer.dial("tcp", host + ":" + port);
            try {
                socket = apiClient.wrapTLS(socket, host);
                SSLSocket sslSocket = (SSLSocket) socket;
                InputStream in = sslSocket.getInputStream();
                java.io.OutputStream out = sslSocket.getOutputStream();
                String request = "GET /client_cgi/vpn_countries.json HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n";
                out.write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    body.write(buf, 0, n);
                }
                String response = body.toString("UTF-8");
                String json = response.substring(response.indexOf("\r\n\r\n") + 4);
                com.google.gson.Gson gson = new com.google.gson.Gson();
                String[] countries = gson.fromJson(json, String[].class);
                java.util.Set<String> countrySet = new java.util.TreeSet<>();
                for (String c : countries) {
                    if ("uk".equals(c)) countrySet.add("gb");
                    else countrySet.add(c);
                }
                for (String code : countrySet) {
                    String name = com.example.proxy.Iso3166.CODE_TO_COUNTRY.getOrDefault(code.toUpperCase(), "Unknown");
                    System.out.println(code + "\t" + name);
                }
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.Error("Failed to list countries: %s", e.getMessage());
            System.exit(3);
        }
    }

    private static void listProxies(CLIArgs args, String extVer, ContextDialer dialer, ConditionalLogger logger) {
        logger.Info("Listing proxies...");
        String ua = args.getUserAgent();
        if (ua == null || ua.isEmpty()) {
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";
        }
        final String userAgent = ua;
        HolaApiClient apiClient = new HolaApiClient(dialer, () -> userAgent);
        try {
            String uuid = java.util.UUID.randomUUID().toString().replace("-", "");
            com.example.proxy.dto.BgInitResponse init = apiClient.backgroundInit(extVer, uuid);
            com.example.proxy.dto.ZGetTunnelsResponse tunnels = apiClient.zGetTunnels(
                uuid, init.getKey(), extVer, args.getCountry(), args.getProxyType(), args.getLimit());
            String login = "user-uuid-" + uuid + "-is_prem-0";
            String credentials = login + ":" + tunnels.getAgentKey();
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String authHeader = "basic " + encoded;
            System.out.println("Login:\t" + login);
            System.out.println("Password:\t" + tunnels.getAgentKey());
            System.out.println("Proxy-Authorization:\t" + authHeader);
            System.out.println();
            OutputStreamWriter stdout = new OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8);
            stdout.write("Host,IP,Port,Port (SSL),Port (Peer),Port (Trial),Port (Trial Peer),Vendor\n");
            Map<String, String> ipList = tunnels.getIpList();
            if (ipList != null && tunnels.getPort() != null) {
                for (Map.Entry<String, String> entry : ipList.entrySet()) {
                    String hostName = entry.getKey();
                    String ip = entry.getValue();
                    String vendor = tunnels.getVendor() != null ? tunnels.getVendor().getOrDefault(hostName, "") : "";
                    stdout.write(String.format("%s,%s,%d,%d,%d,%d,%d,%s\n",
                        hostName, ip,
                        tunnels.getPort().getDirect(),
                        tunnels.getPort().getHola(),
                        tunnels.getPort().getPeer(),
                        tunnels.getPort().getTrial(),
                        tunnels.getPort().getTrialPeer(),
                        vendor));
                }
            }
            stdout.flush();
        } catch (Exception e) {
            logger.Error("Failed to list proxies: %s", e.getMessage());
            System.exit(3);
        }
    }
}
