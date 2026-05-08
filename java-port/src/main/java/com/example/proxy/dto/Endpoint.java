package com.example.proxy.dto;

import java.net.URI;
import java.net.URISyntaxException;

public class Endpoint {
    private String host;
    private int port;
    private String tlsName;

    public Endpoint(String host, int port, String tlsName) {
        this.host = host;
        this.port = port;
        this.tlsName = tlsName;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getTlsName() { return tlsName; }

    public URI getUri() {
        try {
            if (tlsName == null || tlsName.isEmpty()) {
                return new URI("http", null, host, port, null, null, null);
            } else {
                return new URI("https", null, tlsName, port, null, null, null);
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public String getNetAddr() {
        return host + ":" + port;
    }
}
