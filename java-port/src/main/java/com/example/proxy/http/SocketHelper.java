package com.example.proxy.http;

import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class SocketHelper {

    public static Socket wrapTLS(Socket raw, String serverName, boolean hideSNI) throws IOException {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
    
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {}
    
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            }, null);
    
            SSLSocketFactory factory = sslContext.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                raw, serverName, raw.getPort(), true);
    
            if (hideSNI) {
                SSLParameters params = sslSocket.getSSLParameters();
                params.setServerNames(Collections.emptyList());
                sslSocket.setSSLParameters(params);
            }
    
            sslSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslSocket.startHandshake();
            return sslSocket;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IOException("TLS handshake failed", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS handshake failed", e);
        }
    }
    
}
