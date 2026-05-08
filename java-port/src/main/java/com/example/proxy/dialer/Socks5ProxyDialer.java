package com.example.proxy.dialer;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Dialer that routes TCP connections through a SOCKS5 proxy server.
 * Implements RFC 1928 (SOCKS Protocol Version 5) and RFC 1929
 * (Username/Password Authentication).
 *
 * <p>Two modes are supported, matching Go's golang.org/x/net/proxy behavior:
 * <ul>
 *   <li>{@code socks5://} — DNS resolution is performed locally; the resolved
 *       IP address is sent to the SOCKS5 proxy in the CONNECT request.</li>
 *   <li>{@code socks5h://} — DNS resolution is delegated to the SOCKS5 proxy;
 *       the hostname is sent as-is (domain name address type).</li>
 * </ul>
 *
 * <p>Mirrors Go source: {@code xproxy.RegisterDialerType("socks5", ...)} and
 * {@code xproxy.FromURL(proxyURL, dialer)} in main.go.
 */
public class Socks5ProxyDialer implements ContextDialer {

    // SOCKS5 constants (RFC 1928)
    private static final byte SOCKS5_VERSION = 0x05;
    private static final byte AUTH_METHOD_NONE = 0x00;
    private static final byte AUTH_METHOD_USERPASS = 0x02;
    private static final byte AUTH_METHOD_NO_ACCEPTABLE = (byte) 0xFF;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte ATYP_IPV4 = 0x01;
    private static final byte ATYP_DOMAIN = 0x03;
    private static final byte ATYP_IPV6 = 0x04;
    private static final byte REP_SUCCESS = 0x00;

    private final String proxyHost;
    private final int proxyPort;
    private final boolean remoteDns;  // true for socks5h://
    private final Supplier<String> auth; // "username:password" or null
    private final ContextDialer next;

    /**
     * @param proxyHost  SOCKS5 proxy hostname
     * @param proxyPort  SOCKS5 proxy port
     * @param remoteDns  if true (socks5h), delegate DNS resolution to the proxy
     * @param auth       optional supplier returning "username:password" (null if no auth)
     * @param next       underlying raw-TCP dialer used to reach the proxy itself
     */
    public Socks5ProxyDialer(String proxyHost, int proxyPort,
                             boolean remoteDns, Supplier<String> auth,
                             ContextDialer next) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.remoteDns = remoteDns;
        this.auth = auth;
        this.next = next;
    }

    @Override
    public Socket dial(String network, String targetAddress) throws IOException {
        if (!"tcp".equals(network) && !"tcp4".equals(network) && !"tcp6".equals(network)) {
            throw new IOException("Socks5ProxyDialer only supports tcp connections");
        }

        // Step 1: Connect to SOCKS5 proxy
        Socket conn = next.dial("tcp", proxyHost + ":" + proxyPort);
        try {
            InputStream in = conn.getInputStream();
            OutputStream out = conn.getOutputStream();

            // Step 2: Authentication negotiation (RFC 1928 §3)
            negotiateAuth(in, out);

            // Step 3: Send CONNECT request (RFC 1928 §4)
            sendConnectRequest(targetAddress, out);

            // Step 4: Read CONNECT response (RFC 1928 §6)
            readConnectResponse(in);

            return conn;
        } catch (IOException e) {
            try { conn.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public Socket dialContext(java.net.InetSocketAddress addr) throws IOException {
        return dial("tcp", addr.getHostString() + ":" + addr.getPort());
    }

    /**
     * SOCKS5 authentication negotiation (RFC 1928 §3).
     */
    private void negotiateAuth(InputStream in, OutputStream out) throws IOException {
        boolean hasAuth = (auth != null);
        byte[] greeting;
        if (hasAuth) {
            greeting = new byte[]{
                SOCKS5_VERSION,
                0x02,                     // number of methods
                AUTH_METHOD_NONE,         // no auth
                AUTH_METHOD_USERPASS      // username/password
            };
        } else {
            greeting = new byte[]{
                SOCKS5_VERSION,
                0x01,                     // number of methods
                AUTH_METHOD_NONE          // no auth
            };
        }
        out.write(greeting);
        out.flush();

        // Read server's choice
        byte[] response = readExact(in, 2);
        if (response[0] != SOCKS5_VERSION) {
            throw new IOException("SOCKS5: invalid server version: " + (response[0] & 0xFF));
        }

        byte method = response[1];
        if (method == AUTH_METHOD_NO_ACCEPTABLE) {
            throw new IOException("SOCKS5: no acceptable authentication method");
        }

        if (method == AUTH_METHOD_USERPASS) {
            sendUserPassAuth(in, out);
        } else if (method == AUTH_METHOD_NONE) {
            // no auth needed
        } else {
            throw new IOException("SOCKS5: unexpected auth method: " + (method & 0xFF));
        }
    }

    /**
     * Username/password authentication (RFC 1929).
     */
    private void sendUserPassAuth(InputStream in, OutputStream out) throws IOException {
        String creds = auth.get();
        int colonIdx = creds.indexOf(':');
        String username;
        String password;
        if (colonIdx >= 0) {
            username = creds.substring(0, colonIdx);
            password = creds.substring(colonIdx + 1);
        } else {
            username = creds;
            password = "";
        }

        byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);

        if (usernameBytes.length > 255) {
            throw new IOException("SOCKS5: username too long (max 255 bytes)");
        }
        if (passwordBytes.length > 255) {
            throw new IOException("SOCKS5: password too long (max 255 bytes)");
        }

        // RFC 1929: VER | ULEN | UNAME | PLEN | PASSWD
        ByteArrayOutputStream msg = new ByteArrayOutputStream(3 + usernameBytes.length + passwordBytes.length);
        msg.write(0x01);                       // auth sub-negotiation version
        msg.write(usernameBytes.length);       // ULEN
        msg.write(usernameBytes);              // UNAME
        msg.write(passwordBytes.length);       // PLEN
        msg.write(passwordBytes);              // PASSWD
        out.write(msg.toByteArray());
        out.flush();

        // Read response: VER | STATUS
        byte[] resp = readExact(in, 2);
        if (resp[0] != 0x01) {
            throw new IOException("SOCKS5: invalid auth sub-negotiation version: " + (resp[0] & 0xFF));
        }
        if (resp[1] != 0x00) {
            throw new IOException("SOCKS5: authentication failed (status: " + (resp[1] & 0xFF) + ")");
        }
    }

    /**
     * Send SOCKS5 CONNECT request (RFC 1928 §4).
     */
    private void sendConnectRequest(String targetAddress, OutputStream out) throws IOException {
        // Parse target address: host:port
        int lastColon = targetAddress.lastIndexOf(':');
        if (lastColon <= 0) {
            throw new IOException("SOCKS5: invalid target address (missing port): " + targetAddress);
        }
        String host = targetAddress.substring(0, lastColon);
        int port;
        try {
            port = Integer.parseInt(targetAddress.substring(lastColon + 1));
        } catch (NumberFormatException e) {
            throw new IOException("SOCKS5: invalid port in target address: " + targetAddress);
        }
        if (port < 0 || port > 65535) {
            throw new IOException("SOCKS5: port out of range: " + port);
        }

        ByteArrayOutputStream req = new ByteArrayOutputStream(64);
        req.write(SOCKS5_VERSION);             // VER
        req.write(CMD_CONNECT);                // CMD = CONNECT
        req.write(0x00);                       // RSV

        if (remoteDns) {
            // socks5h: send hostname as domain name (ATYP 0x03)
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            if (hostBytes.length > 255) {
                throw new IOException("SOCKS5: hostname too long (max 255 bytes)");
            }
            req.write(ATYP_DOMAIN);
            req.write(hostBytes.length);
            req.write(hostBytes);
        } else {
            // socks5: resolve locally and send IP
            writeAddressBytes(host, req);
        }

        // Port (network byte order = big-endian)
        req.write((port >> 8) & 0xFF);
        req.write(port & 0xFF);

        out.write(req.toByteArray());
        out.flush();
    }

    /**
     * Write address bytes for CONNECT request.
     * Attempts to parse as IPv4 or IPv6; falls back to domain name.
     */
    private void writeAddressBytes(String host, ByteArrayOutputStream out) throws IOException {
        // Try IPv4
        byte[] ipv4 = parseIPv4(host);
        if (ipv4 != null) {
            out.write(ATYP_IPV4);
            out.write(ipv4);
            return;
        }

        // Try IPv6
        byte[] ipv6 = parseIPv6(host);
        if (ipv6 != null) {
            out.write(ATYP_IPV6);
            out.write(ipv6);
            return;
        }

        // Domain name
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > 255) {
            throw new IOException("SOCKS5: hostname too long (max 255 bytes)");
        }
        out.write(ATYP_DOMAIN);
        out.write(hostBytes.length);
        out.write(hostBytes);
    }

    /**
     * Read and validate SOCKS5 CONNECT response (RFC 1928 §6).
     */
    private void readConnectResponse(InputStream in) throws IOException {
        // VER | REP | RSV | ATYP
        byte[] header = readExact(in, 4);
        if (header[0] != SOCKS5_VERSION) {
            throw new IOException("SOCKS5: invalid response version: " + (header[0] & 0xFF));
        }
        if (header[1] != REP_SUCCESS) {
            throw new IOException("SOCKS5: CONNECT failed with error code " + (header[1] & 0xFF));
        }

        // Read the bound address (we don't need it, but must consume it)
        byte atyp = header[3];
        switch (atyp) {
            case ATYP_IPV4:
                readExact(in, 4);  // IPv4 address
                break;
            case ATYP_DOMAIN:
                int domainLen = in.read();
                if (domainLen < 0) {
                    throw new IOException("SOCKS5: unexpected end of stream reading domain length");
                }
                readExact(in, domainLen);  // domain name
                break;
            case ATYP_IPV6:
                readExact(in, 16); // IPv6 address
                break;
            default:
                throw new IOException("SOCKS5: unsupported address type in response: " + (atyp & 0xFF));
        }
        readExact(in, 2); // port number (2 bytes)
    }

    // ---- Utility methods ----

    private static byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int offset = 0;
        while (offset < len) {
            int n = in.read(buf, offset, len - offset);
            if (n < 0) {
                throw new IOException("SOCKS5: unexpected end of stream (expected " + len + " bytes, got " + offset + ")");
            }
            offset += n;
        }
        return buf;
    }

    /**
     * Parse a dotted-decimal IPv4 address to 4 bytes, or null if not IPv4.
     */
    private static byte[] parseIPv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return null;
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) return null;
                result[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return result;
    }

    /**
     * Parse an IPv6 address to 16 bytes, or null if not IPv6.
     * Handles :: notation and mixed IPv4-mapped IPv6.
     */
    private static byte[] parseIPv6(String host) {
        // Strip brackets if present: [::1]
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            if (addr instanceof java.net.Inet6Address) {
                return addr.getAddress();
            }
        } catch (java.net.UnknownHostException e) {
            // not an IP address
        }
        return null;
    }
}
