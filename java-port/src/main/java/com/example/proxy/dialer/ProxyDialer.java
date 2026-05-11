package com.example.proxy.dialer;

import java.io.*;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import javax.net.ssl.*;

import com.example.proxy.resolver.LookupNetIP;
import com.example.proxy.resolver.advanced.AdvancedResolver;

/**
 * Dialer that connects to a proxy server using the CONNECT tunnel method.
 * 
 * <p>This mirrors Go's ProxyDialer in upstream.go:
 * 1. Dial the proxy address using the next dialer
 * 2. Optionally wrap with TLS (for HTTPS proxy servers)
 * 3. Send CONNECT request to establish tunnel
 * 4. Return the connected socket (now a tunnel to target)
 * 
 * <p>Critical: TLS wrapping happens BEFORE sending CONNECT, matching Go's UClient
 * approach where the tunnel itself goes through TLS.
 */
public class ProxyDialer implements ContextDialer {
    private static final String PROXY_CONNECT_METHOD = "CONNECT";
    private static final String PROXY_HOST_HEADER = "Host";
    private static final String PROXY_AUTHORIZATION_HEADER = "Proxy-Authorization";

    private final String address;
    private final String tlsServerName;
    private final Supplier<String> authProvider;
    private final ContextDialer next;
    private final boolean hideSNI;

    private final LookupNetIP resolver;

    private volatile SSLContext sslContext; // lazily initialized, thread‑safe via volatile

    public ProxyDialer(String address, String tlsServerName,
                       Supplier<String> authProvider,
                       ContextDialer next, boolean hideSNI,
                       LookupNetIP resolver) {
        this.address = address;
        this.tlsServerName = tlsServerName;
        this.authProvider = authProvider;
        this.next = next;
        this.hideSNI = hideSNI;
        this.resolver = resolver;
    }

    @Override
    public Socket dial(String network, String targetAddress) throws IOException {
        // Step 1: Dial the proxy address
        Socket conn = next.dial("tcp", address);

        try {
            // Step 2: Wrap with TLS BEFORE CONNECT (mirrors Go's UClient approach)
            // The CONNECT tunnel itself goes through TLS
            if (tlsServerName != null && !tlsServerName.isEmpty()) {
                conn = wrapTls(conn, tlsServerName);
            }

            // Step 3: Send CONNECT request
            StringBuilder connectRequest = new StringBuilder();
            connectRequest.append(PROXY_CONNECT_METHOD).append(" ");

            // Step 3.5: Optionally resolve address instead of relying on proxy's resolution
            if (resolver instanceof AdvancedResolver) {
                // Optimization: Square brackets indicate an IPv6 literal; no resolution needed
                if (targetAddress.charAt(0) != '[') {
                    int lastColon = targetAddress.lastIndexOf(':');
                    String host = targetAddress.substring(0, lastColon);
                    String portSuffix = targetAddress.substring(lastColon);

                    List<InetAddress> resolved =
                        ((AdvancedResolver) resolver).lookup(host);
                    InetAddress resolvedAddr = resolved.get(0);
                    String resolvedHost = resolvedAddr.getHostAddress();

                    StringBuilder sb = new StringBuilder(
                        resolvedHost.length() + portSuffix.length() + 2);
                    if (resolvedAddr instanceof Inet6Address) {
                        sb.append('[').append(resolvedHost).append(']');
                    } else {
                        sb.append(resolvedHost);
                    }
                    sb.append(portSuffix);
                    targetAddress = sb.toString();
                }
            }

            connectRequest.append(targetAddress).append(" HTTP/1.1\r\n");
            connectRequest.append(PROXY_HOST_HEADER).append(": ").append(targetAddress).append("\r\n");

            String auth = authProvider != null ? authProvider.get() : null;
            if (auth != null && !auth.isEmpty()) {
                connectRequest.append(PROXY_AUTHORIZATION_HEADER).append(": ").append(auth).append("\r\n");
            }

            connectRequest.append("\r\n");

            OutputStream out = conn.getOutputStream();
            out.write(connectRequest.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Step 4: Read and validate response (in a more optimal way)
            PushbackInputStream pin = new PushbackInputStream(conn.getInputStream(), 8192);
            ByteArrayOutputStream respBuf = new ByteArrayOutputStream(256);
            byte[] endMarker = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] buf = new byte[8192];
            int matchPos = 0;
            outer:
            while (matchPos < 4) {
                int n = pin.read(buf);
                if (n < 0) {
                    throw new IOException("CONNECT: unexpected EOF from proxy");
                }
                for (int i = 0; i < n; i++) {
                    respBuf.write(buf[i] & 0xFF);
                    if (buf[i] == endMarker[matchPos]) {
                        matchPos++;
                    } else if (matchPos > 0) {
                        matchPos = (buf[i] == endMarker[0]) ? 1 : 0;
                    }
                    if (matchPos == 4) {
                        // Unread any bytes past the end marker
                        int excess = n - i - 1;
                        if (excess > 0) {
                            pin.unread(buf, i + 1, excess);
                        }
                        break outer;
                    }
                }
            }

            // Parse response from the buffer
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(respBuf.toByteArray()), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();

            // Consume remaining headers
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Check for blocked host
                if (statusLine != null && statusLine.contains("403") &&
                    line.contains("X-Hola-Error: Forbidden Host")) {
                    conn.close();
                    throw new UpstreamBlockedError("Host blocked by upstream");
                }
            }

            // Validate 200 OK response
            if (statusLine == null || !statusLine.contains("200")) {
                conn.close();
                throw new IOException("CONNECT failed: " + statusLine);
            }

            // Socket is now a transparent tunnel to targetAddress.
            // Return a wrapper Socket to ensure the caller reads from the
            // PushbackInputStream, which holds any early tunnel data.
            return new TunnelSocket(conn, pin);

        } catch (IOException e) {
            try { conn.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public Socket dialContext(InetSocketAddress addr) throws IOException {
        return dial("tcp", addr.getHostString() + ":" + addr.getPort());
    }

    private SSLContext getSslContext() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext ctx = sslContext;
        if (ctx == null) {
            synchronized (this) {
                ctx = sslContext;
                if (ctx == null) {
                    ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[] {
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType)
                                    throws CertificateException {
                                // Permissive: trust all certificates for proxy connections
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                    }, null);
                    sslContext = ctx;
                }
            }
        }
        return ctx;
    }

    /**
     * Wrap socket with TLS. Uses a permissive trust manager since we're
     * connecting to user-specified proxy whose cert may be self-signed or
     * privately signed.
     * 
     * <p>Note: This is different from Go's utls.UClient which provides
     * specific TLS fingerprinting (HelloAndroid_11_OkHttp). For full parity,
     * a library like utls-java would be needed.
     */
    private Socket wrapTls(Socket raw, String serverName) throws IOException {
        try {
            SSLContext ctx = getSslContext();
            SSLSocketFactory factory = ctx.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(raw, serverName, raw.getPort(), true);

            // Handle hideSNI by setting empty server names in SSL parameters
            if (hideSNI) {
                SSLParameters params = sslSocket.getSSLParameters();
                params.setServerNames(Collections.emptyList());
                sslSocket.setSSLParameters(params);
            }

            sslSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslSocket.startHandshake();
            return sslSocket;

        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IOException("TLS wrapping failed", e);
        } catch (IOException e) {
            throw e; // rethrow directly
        } catch (Exception e) {
            throw new IOException("TLS wrapping failed", e);
        }
    }

}
