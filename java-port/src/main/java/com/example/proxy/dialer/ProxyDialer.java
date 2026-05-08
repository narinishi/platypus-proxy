package com.example.proxy.dialer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.function.Supplier;
import javax.net.ssl.*;

/**
 * Dialer that connects to a Hola proxy server using the CONNECT tunnel method.
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

    public ProxyDialer(String address, String tlsServerName,
                       Supplier<String> authProvider,
                       ContextDialer next, boolean hideSNI) {
        this.address = address;
        this.tlsServerName = tlsServerName;
        this.authProvider = authProvider;
        this.next = next;
        this.hideSNI = hideSNI;
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

            // Step 4: Read and validate response (byte-by-byte, matching Go's readResponse)
            // Use a ByteArrayOutputStream to collect the response until we find \r\n\r\n,
            // then parse from it without touching the original InputStream.
            ByteArrayOutputStream respBuf = new ByteArrayOutputStream();
            byte[] endMarker = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            InputStream connIn = conn.getInputStream();
            byte[] oneByte = new byte[1];
            while (true) {
                int n = connIn.read(oneByte);
                if (n < 1) {
                    conn.close();
                    throw new IOException("CONNECT: unexpected EOF from proxy");
                }
                respBuf.write(oneByte[0] & 0xFF);
                byte[] data = respBuf.toByteArray();
                if (data.length >= 4) {
                    boolean found = true;
                    for (int i = 0; i < 4; i++) {
                        if (data[data.length - 4 + i] != endMarker[i]) {
                            found = false;
                            break;
                        }
                    }
                    if (found) break;
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
            // The original InputStream is untouched beyond the \r\n\r\n boundary.
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
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{
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

            SSLSocketFactory factory = sslContext.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                raw, serverName, raw.getPort(), true);

            // Handle hideSNI by setting empty server names in SSL parameters
            if (hideSNI) {
                SSLParameters params = sslSocket.getSSLParameters();
                try {
                    java.lang.reflect.Method setter = SSLParameters.class.getMethod(
                        "setServerNames", java.util.List.class);
                    setter.invoke(params, java.util.Collections.emptyList());
                } catch (Exception ignored) {
                    // Fallback: continue with SNI if reflection fails
                }
                sslSocket.setSSLParameters(params);
            }

            sslSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslSocket.startHandshake();
            return sslSocket;

        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IOException("TLS wrapping failed", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS wrapping failed", e);
        }
    }
}
