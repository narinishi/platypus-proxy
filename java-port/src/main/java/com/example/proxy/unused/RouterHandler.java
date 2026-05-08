package com.example.proxy.handler;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

public class RouterHandler extends HttpHandler {
    private final ProxyHandler proxyHandler;

    public RouterHandler(ProxyHandler proxyHandler) {
        this.proxyHandler = proxyHandler;
    }

    @Override
    public void service(Request request, Response response) throws Exception {
        // Delegate all requests to the proxy handler
        // Note: ProxyHandler works with raw Sockets, not Grizzly request/response.
        // This handler is a placeholder for future routing integration.
        response.setStatus(501);
        response.setContentType("text/plain");
        response.getWriter().write("Not Implemented");
    }
}
