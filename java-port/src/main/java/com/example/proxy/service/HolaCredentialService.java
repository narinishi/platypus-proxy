package com.example.proxy.service;

import com.example.proxy.dialer.ContextDialer;
import com.example.proxy.dto.BgInitResponse;
import com.example.proxy.dto.ZGetTunnelsResponse;
import com.example.proxy.http.HolaApiClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HolaCredentialService {
    private static final Logger log = Logger.getLogger(HolaCredentialService.class.getName());
    private static final int DEFAULT_LIST_LIMIT = 3;

    private final String extVer;
    private final String country;
    private final String proxyType;
    private final long initRetryInterval;
    private final long backoffInitial;
    private final long backoffDeadline;
    private final ContextDialer dialer;
    private final HolaApiClient apiClient;

    private volatile String currentAuthHeader;
    private volatile String currentUserUuid;
    private final Object lock = new Object();
    private volatile ScheduledFuture<?> rotationTask;

    public interface AuthProvider {
        String getAuth();
    }

    public HolaCredentialService(
            ContextDialer dialer,
            HolaApiClient apiClient,
            String extVer,
            String country,
            String proxyType,
            long initRetryInterval,
            long backoffInitial,
            long backoffDeadline) {
        this.dialer = dialer;
        this.apiClient = apiClient;
        this.extVer = extVer;
        this.country = country;
        this.proxyType = proxyType;
        this.initRetryInterval = initRetryInterval;
        this.backoffInitial = backoffInitial;
        this.backoffDeadline = backoffDeadline;
    }

    public static class AuthResult {
        public final AuthProvider authProvider;
        public final ZGetTunnelsResponse tunnels;
        public final String userUuid;

        public AuthResult(AuthProvider authProvider, ZGetTunnelsResponse tunnels, String userUuid) {
            this.authProvider = authProvider;
            this.tunnels = tunnels;
            this.userUuid = userUuid;
        }
    }

    public AuthResult createAuthProvider(long rotateIntervalMs) throws Exception {
        AuthResult initialResult = doProvision();
        currentAuthHeader = initialResult.authProvider.getAuth();
        currentUserUuid = initialResult.userUuid;

        if (rotateIntervalMs > 0) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hola-credential-rotation");
                t.setDaemon(true);
                return t;
            });
            rotationTask = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    AuthResult r = doProvision();
                    synchronized (lock) {
                        currentAuthHeader = r.authProvider.getAuth();
                        currentUserUuid = r.userUuid;
                    }
                    log.log(Level.INFO, "Credentials rotated successfully");
                } catch (Exception e) {
                    log.log(Level.WARNING, "Credential rotation failed", e);
                }
            }, rotateIntervalMs, rotateIntervalMs, TimeUnit.MILLISECONDS);
        }

        return new AuthResult(() -> currentAuthHeader, initialResult.tunnels, initialResult.userUuid);
    }

    private AuthResult doProvision() throws Exception {
        String userUuid = generateUserUuid();
        ZGetTunnelsResponse tunnels = fetchTunnels(userUuid);
        String authHeader = computeAuthHeader(tunnels, userUuid);
        return new AuthResult(() -> authHeader, tunnels, userUuid);
    }

    private ZGetTunnelsResponse fetchTunnels(String userUuid) throws Exception {
        long deadline = System.currentTimeMillis() + backoffDeadline;
        long retryInterval = initRetryInterval > 0 ? initRetryInterval : 1000;

        while (System.currentTimeMillis() < deadline) {
            try {
                BgInitResponse init = apiClient.backgroundInit(extVer, userUuid);
                return apiClient.zGetTunnels(userUuid, init.getKey(), extVer, country, proxyType, DEFAULT_LIST_LIMIT);
            } catch (HolaApiClient.TemporaryBanException e) {
                log.log(Level.SEVERE, "Hola API has temporarily banned this IP/credentials. The proxy cannot start. Please wait before retrying.");
                throw e;
            } catch (HolaApiClient.PermanentBanException e) {
                log.log(Level.SEVERE, "Hola API has permanently banned this IP/credentials.");
                throw e;
            } catch (Exception e) {
                log.log(Level.WARNING, "Direct tunnel call failed: " + e.getMessage());
            }
            try {
                return fetchTunnelsViaFallback(userUuid);
            } catch (Exception e) {
                log.log(Level.WARNING, "Fallback tunnel call also failed: " + e.getMessage());
            }
            Thread.sleep(retryInterval);
            retryInterval = Math.min(retryInterval * 2, 30000);
        }
        throw new IOException("All tunnel fetch attempts failed");
    }

    private ZGetTunnelsResponse fetchTunnelsViaFallback(String userUuid) throws Exception {
        HolaApiClient.FallbackConfig fbc = apiClient.getFallbackProxies();
        for (HolaApiClient.FallbackAgent agent : fbc.getAgents()) {
            try {
                ContextDialer agentDialer = (network, address) ->
                    dialer.dial(network, agent.netAddr());
                HolaApiClient agentClient = apiClient.withDialer(agentDialer);
                BgInitResponse init = agentClient.backgroundInit(extVer, userUuid);
                return agentClient.zGetTunnels(userUuid, init.getKey(), extVer, country, proxyType, DEFAULT_LIST_LIMIT);
            } catch (Exception e) {
                log.log(Level.WARNING, "Fallback agent " + agent.hostname() + " failed: " + e.getMessage());
            }
        }
        throw new IOException("All fallback proxy agents failed");
    }

    private String computeAuthHeader(ZGetTunnelsResponse tunnels, String userUuid) {
        String login = templateLogin(userUuid);
        String credentials = login + ":" + tunnels.getAgentKey();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "basic " + encoded;
    }

    private String templateLogin(String userUuid) {
        return "user-uuid-" + userUuid + "-is_prem-0";
    }

    private String generateUserUuid() {
        UUID uuid = UUID.randomUUID();
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        StringBuilder sb = new StringBuilder(32);
        sb.append(String.format("%016x", msb));
        sb.append(String.format("%016x", lsb));
        return sb.toString();
    }

    public void shutdown() {
        if (rotationTask != null) {
            rotationTask.cancel(false);
        }
    }
}
