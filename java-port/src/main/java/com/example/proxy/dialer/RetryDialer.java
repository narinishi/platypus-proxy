package com.example.proxy.dialer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RetryDialer implements ContextDialer {
    private final ContextDialer inner;
    private final LookupNetIP resolver;
    private final Logger logger;

    public RetryDialer(ContextDialer inner, LookupNetIP resolver, Logger logger) {
        this.inner = inner;
        this.resolver = resolver;
        this.logger = logger;
    }

    @Override
    public Socket dial(String network, String address) throws IOException {
        try {
            return inner.dial(network, address);
        } catch (UpstreamBlockedError e) {
            logger.log(Level.INFO, "Destination {0} blocked by upstream. Rescuing with resolve&tunnel workaround.", address);
            String[] parts = address.split(":");
            String host = parts[0];
            String port = parts.length > 1 ? parts[1] : "80";
            String resolveNetwork = resolveNetworkFamily(network);
            try {
                List<java.net.InetAddress> addrs = resolver.lookup(host);
                for (java.net.InetAddress addr : addrs) {
                    if (!familyMatch(resolveNetwork, addr)) continue;
                    String target = addr.getHostAddress() + ":" + port;
                    try {
                        return inner.dial(network, target);
                    } catch (IOException ignored) {
                    }
                }
            } catch (java.net.UnknownHostException ex) {
                logger.log(Level.WARNING, "Resolution failed for " + host, ex);
            }
            throw new IOException("All resolution attempts failed for " + address, e);
        }
    }

    @Override
    public Socket dialContext(InetSocketAddress addr) throws IOException {
        try {
            return inner.dialContext(addr);
        } catch (IOException e) {
            if (isUpstreamBlocked(e)) {
                logger.log(Level.INFO, "Destination {0} blocked by upstream. Rescuing with resolve&tunnel workaround.", addr);
                String host = addr.getHostString();
                int port = addr.getPort();
                String resolveNetwork = resolveNetworkFamily("tcp");
                try {
                    List<java.net.InetAddress> addrs = resolver.lookup(host);
                    for (java.net.InetAddress addrItem : addrs) {
                        if (!familyMatch(resolveNetwork, addrItem)) continue;
                        String target = addrItem.getHostAddress() + ":" + port;
                        try {
                            return inner.dial("tcp", target);
                        } catch (IOException ignored) {
                        }
                    }
                } catch (java.net.UnknownHostException ex) {
                    logger.log(Level.WARNING, "Resolution failed for " + host, ex);
                }
                throw new IOException("All resolution attempts failed for " + addr, e);
            }
            throw e;
        }
    }

    private static String resolveNetworkFamily(String network) {
        switch (network) {
            case "tcp4": case "udp4": case "ip4": return "ip4";
            case "tcp6": case "udp6": case "ip6": return "ip6";
            default: return "ip";
        }
    }

    private static boolean familyMatch(String family, java.net.InetAddress addr) {
        if (family.equals("ip")) return true;
        boolean isIPv6 = addr instanceof java.net.Inet6Address;
        return (family.equals("ip6") && isIPv6) || (family.equals("ip4") && !isIPv6);
    }

    private boolean isUpstreamBlocked(IOException e) {
        if (e.getCause() instanceof UpstreamBlockedError) {
            return true;
        }
        if (e instanceof UpstreamBlockedError) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.contains("blocked by upstream");
    }
}
