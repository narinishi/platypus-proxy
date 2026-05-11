package com.example.proxy.dialer;

import java.io.IOException;
import java.net.Socket;

@FunctionalInterface
public interface ContextDialer {
    Socket dial(String network, String address) throws IOException;

    default Socket dialContext(java.net.InetSocketAddress addr) throws IOException {
        return dial("tcp", addr.getHostString() + ":" + addr.getPort());
    }
}
