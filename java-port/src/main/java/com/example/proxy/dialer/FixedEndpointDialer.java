package com.example.proxy.dialer;

import javax.net.ssl.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;

public class FixedEndpointDialer implements ContextDialer {
    private final String fixedAddress;
    private final String tlsServerName;
    private final ContextDialer next;
    private final boolean hideSNI;

    public FixedEndpointDialer(String fixedAddress, String tlsServerName,
                           ContextDialer next, boolean hideSNI) {
        this.fixedAddress = fixedAddress;
        this.tlsServerName = tlsServerName;
        this.next = next;
        this.hideSNI = hideSNI;
    }

    @Override
    public Socket dial(String network, String address) throws IOException {
        Socket raw = next.dial("tcp", fixedAddress);

        raw.setTcpNoDelay(true);

        if (tlsServerName != null && !tlsServerName.isEmpty()) {
            return wrapTls(raw, tlsServerName);
        }
        return raw;
    }

    @Override
    public Socket dialContext(InetSocketAddress addr) throws IOException {
        String host = fixedAddress.split(":")[0];
        int port = Integer.parseInt(fixedAddress.split(":")[1]);

        InetSocketAddress targetAddr = new InetSocketAddress(host, port);

        Socket raw = next.dialContext(targetAddr);

        raw.setTcpNoDelay(true);

        if (tlsServerName != null && !tlsServerName.isEmpty()) {
            return wrapTls(raw, tlsServerName);
        }
        return raw;
    }

    // TODO: evaluate correctness
    // this codepath is for HTTP (i.e. non-HTTPS) requests
    private Socket wrapTls(Socket raw, String serverName) throws IOException {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {}
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {
                    }
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            }, null);

            SSLSocketFactory factory = sslContext.getSocketFactory();

            SSLSocket sslSocket = (SSLSocket) factory.createSocket(raw,
                    serverName, raw.getPort(), true);

            if (hideSNI) {
                SSLParameters params = sslSocket.getSSLParameters();
                params.setServerNames(Collections.emptyList());
                sslSocket.setSSLParameters(params);
            }

            sslSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslSocket.startHandshake();
            return sslSocket;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IOException("TLS setup failed", e);
        }
    }
}
