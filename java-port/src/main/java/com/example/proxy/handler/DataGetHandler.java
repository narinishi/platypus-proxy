package com.example.proxy.handler;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

public class DataGetHandler extends HttpHandler {
    @Override
    public void service(Request request, Response response) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":\"get data\"}");
    }
}
