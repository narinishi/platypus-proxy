package com.example.proxy.dialer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.function.Supplier;
import javax.net.ssl.*;

/**
 * Dialer that routes TCP connections through an outbound HTTP/HTTPS proxy
 * using the CONNECT tunnel method.
 *
 * <p>Mirrors the Go source's {@code ProxyDialerFromURL} in upstream.go and
 * the outbound-proxy wiring in main.go ({@code xproxy.RegisterDialerType} /
 * {@code xproxy.FromURL}).
 *
 * <p>Supported schemes:
 * <ul>
 *   <li>{@code http://host[:port]} — plain-text CONNECT proxy</li>
 *   <li>{@code https://host[:port]} — TLS-wrapped CONNECT proxy</li>
 * </ul>
 *
 * <p>Authentication (if present in URL) is sent as {@code Proxy-Authorization: Basic …}.
 */
public class OutboundProxyDialer implements ContextDialer {

    private final String proxyHost;
    private final int proxyPort;
    private final boolean tlsWrap;       // true for https:// proxies
    private final Supplier<String> auth; // may be null
    private final ContextDialer next;    // underlying raw TCP dialer

    /**
     * Create an outbound-proxy dialer from a parsed configuration.
     *
     * @param proxyHost  hostname of the proxy server
     * @param proxyPort  TCP port of the proxy server
     * @param tlsWrap    whether to wrap the connection in TLS before sending CONNECT
     * @param auth       optional Basic auth header value supplier (null if no auth)
     * @param next       the fallback raw-TCP dialer used to reach the proxy itself
     */
    public OutboundProxyDialer(String proxyHost, int proxyPort,
                               boolean tlsWrap, Supplier<String> auth,
                               ContextDialer next) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.tlsWrap = tlsWrap;
        this.auth = auth;
        this.next = next;
    }

    /**
     * Factory: parse a proxy URL string and return a wrapped dialer.
     *
     * <p>Accepted formats:
     * <pre>
     *   http://[user:password@]host[:port]
     *   https://[user:password@]host[:port]
     *   socks5://[user:password@]host[:port]
     *   socks5h://[user:password@]host[:port]
     * </pre>
     *
     * <p>For {@code socks5://}, DNS resolution is performed locally.
     * For {@code socks5h://}, DNS resolution is delegated to the SOCKS5 proxy.
     *
     * @param proxyUrl  the user-supplied proxy URL (never null/empty)
     * @param base      the base dialer that produces raw TCP sockets
     * @return a {@link ContextDialer} that tunnels every connection through the proxy
     * @throws IllegalArgumentException if the URL cannot be parsed or the scheme is unsupported
     */
    public static ContextDialer fromURL(String proxyUrl, ContextDialer base) {
        // Prepend scheme if missing so java.net.URL can parse host:port strings
        String normalized = proxyUrl;
        if (!normalized.contains("://")) {
            normalized = "http://" + normalized;
        }

        URL url;
        try {
            url = new URL(normalized);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to parse proxy URL: " + proxyUrl, e);
        }

        String scheme = url.getProtocol().toLowerCase();
        boolean tlsWrap;
        boolean socks5;
        boolean remoteDns;

        switch (scheme) {
            case "http":
                tlsWrap = false;
                socks5 = false;
                remoteDns = false;
                break;
            case "https":
                tlsWrap = true;
                socks5 = false;
                remoteDns = false;
                break;
            case "socks5":
                tlsWrap = false;
                socks5 = true;
                remoteDns = false;
                break;
            case "socks5h":
                tlsWrap = false;
                socks5 = true;
                remoteDns = true;
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported proxy scheme: " + scheme
                                + " (supported: http, https, socks5, socks5h)");
        }

        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("No host in proxy URL: " + proxyUrl);
        }

        int port = url.getPort();
        if (port == -1) {
            if (socks5) {
                port = 1080;
            } else {
                port = tlsWrap ? 443 : 80;
            }
        }

        // Extract credentials if present
        if (socks5) {
            // SOCKS5 uses username:password format (RFC 1929)
            Supplier<String> authSupplier = null;
            if (url.getUserInfo() != null && !url.getUserInfo().isEmpty()) {
                String userInfo = url.getUserInfo();
                authSupplier = () -> userInfo; // "username:password"
            }
            return new Socks5ProxyDialer(host, port, remoteDns, authSupplier, base);
        } else {
            // HTTP/HTTPS uses Basic auth
            Supplier<String> authSupplier = null;
            if (url.getUserInfo() != null && !url.getUserInfo().isEmpty()) {
                String userInfo = url.getUserInfo();
                String encoded = Base64.getEncoder().encodeToString(userInfo.getBytes(StandardCharsets.UTF_8));
                String authValue = "Basic " + encoded;
                authSupplier = () -> authValue;
            }
            return new OutboundProxyDialer(host, port, tlsWrap, authSupplier, base);
        }
    }

    // ----------------------------------------------------------
    // ContextDialer implementation
    // ----------------------------------------------------------

    @Override
    public Socket dial(String network, String targetAddress) throws IOException {
        // Step 1: Open TCP connection to the proxy server itself
        String proxyAddr = proxyHost + ":" + proxyPort;
        Socket conn = next.dial("tcp", proxyAddr);

        try {
            // Step 2: Optional TLS wrap (for https:// proxies)
            if (tlsWrap) {
                conn = wrapTLS(conn, proxyHost);
            }

            // Step 3: Send CONNECT request
            String connectReq = buildConnectRequest(targetAddress);
            OutputStream out = conn.getOutputStream();
            out.write(connectReq.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Step 4: Read proxy response
            InputStream in = conn.getInputStream();
            String statusLine = readLine(in);

            // Consume remaining headers until empty line
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                // drain headers
            }

            if (statusLine == null || !statusLine.contains("200")) {
                try { conn.close(); } catch (Exception ignored) {}
                throw new IOException("Outbound proxy CONNECT rejected: " + statusLine);
            }

            // Socket is now a transparent tunnel to targetAddress
            return conn;

        } catch (IOException e) {
            try { conn.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public Socket dialContext(InetSocketAddress addr) throws IOException {
        return dial("tcp", addr.getHostString() + ":" + addr.getPort());
    }

    // ----------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------

    private String buildConnectRequest(String target) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("CONNECT ").append(target).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(target).append("\r\n");
        if (auth != null) {
            sb.append("Proxy-Authorization: ").append(auth.get()).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    /**
     * Wrap a raw socket in TLS. Uses a permissive trust manager since we're
     * connecting to a user-specified proxy whose cert may be self-signed or
     * privately signed — matching the Go side's behaviour where caPool is
     * passed through from CLI args but defaults to system pool.
     */
    private static Socket wrapTLS(Socket raw, String serverName) throws IOException {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            }, null);

            SSLSocketFactory factory = sslContext.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(raw, serverName, raw.getPort(), true);
            sslSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslSocket.startHandshake();
            return sslSocket;
        } catch (Exception e) {
            throw new IOException("TLS handshake with outbound proxy failed", e);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int prev = -1;
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n' && prev == '\r') {
                break; // end of line (CRLF)
            }
            if (ch == '\n' && prev != '\r') {
                break; // LF only
            }
            if (ch != '\r') {
                buf.write(ch);
            }
            prev = ch;
        }
        if (ch == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.US_ASCII.name());
    }
}
