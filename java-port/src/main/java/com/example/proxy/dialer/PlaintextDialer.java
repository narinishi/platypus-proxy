package com.example.proxy.dialer;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class PlaintextDialer implements ContextDialer {
    private final String fixedAddress;
    private final String tlsServerName;
    private final ContextDialer next;
    private final boolean hideSNI;

    public PlaintextDialer(String fixedAddress, String tlsServerName,
                           ContextDialer next, boolean hideSNI) {
        this.fixedAddress = fixedAddress;
        this.tlsServerName = tlsServerName;
        this.next = next;
        this.hideSNI = hideSNI;
    }

    @Override
    public Socket dial(String network, String address) throws IOException {
        Socket raw = next.dial("tcp", fixedAddress);
        if (tlsServerName != null && !tlsServerName.isEmpty()) {
            return wrapTls(raw, tlsServerName);
        }
        return raw;
    }

    @Override
    public Socket dialContext(InetSocketAddress addr) throws IOException {
        Socket raw = next.dialContext(new InetSocketAddress(fixedAddress.split(":")[0],
                Integer.parseInt(fixedAddress.split(":")[1])));
        if (tlsServerName != null && !tlsServerName.isEmpty()) {
            return wrapTls(raw, tlsServerName);
        }
        return raw;
    }

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
                try {
                    java.lang.reflect.Method setter = SSLParameters.class.getMethod(
                            "setServerNames", java.util.List.class);
                    setter.invoke(params, java.util.Collections.emptyList());
                } catch (Exception ignored) {}
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
