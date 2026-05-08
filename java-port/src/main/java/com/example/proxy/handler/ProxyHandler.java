package com.example.proxy.handler;

import com.example.proxy.dialer.ContextDialer;
import com.example.proxy.logging.ConditionalLogger;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * HTTP proxy handler built on Grizzly's {@link HttpHandler}.
 * <p>
 * Handles both CONNECT tunneling and regular HTTP request forwarding.
 */
public class ProxyHandler extends HttpHandler {
    private static final Set<String> HOP_BY_HOP;
    static {
        Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        s.addAll(Arrays.asList(
                "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Connection",
                "Te", "Trailers", "Transfer-Encoding", "Upgrade"
        ));
        HOP_BY_HOP = s;
    }

    private final ContextDialer tunnelDialer;
    private final ContextDialer requestDialer;
    private final Supplier<String> authProvider;
    private final ConditionalLogger logger;
    private final HttpDialer httpDialer;

    public ProxyHandler(ContextDialer tunnelDialer, ContextDialer requestDialer,
                        Supplier<String> authProvider, ConditionalLogger logger) {
        this.tunnelDialer = tunnelDialer;
        this.requestDialer = requestDialer;
        this.authProvider = authProvider;
        this.logger = logger;
        this.httpDialer = new HttpDialer(requestDialer);
    }

    @Override
    public void service(Request request, Response response) throws Exception {
        String method = request.getMethod().toString();
        String target = request.getRequest().getRequestURI();
        String remoteAddr = request.getRemoteAddr();
        int remotePort = request.getRemotePort();

        String logUrl = "CONNECT".equalsIgnoreCase(method) ? target : request.getRequestURL().toString();
        logger.Info("%s:%d %s %s", remoteAddr, remotePort, method, logUrl);

        if ("CONNECT".equalsIgnoreCase(method)) {
            handleTunnel(request, response, target);
        } else {
            handleRequest(request, response, method, target);
        }
    }

    private void handleTunnel(Request request, Response response, String target) throws Exception {
        Socket upstream = null;
        try {
            upstream = tunnelDialer.dial("tcp", target);
            final Socket up = upstream;

            response.setStatus(200);
            response.setContentType("application/octet-stream");
            response.suspend();
            OutputStream clientOut = response.getOutputStream();
            clientOut.write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            clientOut.flush();

            logger.Info("Tunnel established: %s", target);

            CountDownLatch done = new CountDownLatch(2);
            Thread c2u = new Thread(() -> {
                try {
                    byte[] rbuf = new byte[128 * 1024];
                    InputStream clientIn = request.getInputStream();
                    int n;
                    while ((n = clientIn.read(rbuf)) != -1) {
                        up.getOutputStream().write(rbuf, 0, n);
                        up.getOutputStream().flush();
                    }
                } catch (Exception ignored) {}
                try { up.close(); } catch (Exception ignored) {}
                done.countDown();
            }, "proxy-c2u");
            Thread u2c = new Thread(() -> {
                try {
                    byte[] rbuf = new byte[128 * 1024];
                    int n;
                    while ((n = up.getInputStream().read(rbuf)) != -1) {
                        clientOut.write(rbuf, 0, n);
                        clientOut.flush();
                    }
                } catch (Exception ignored) {}
                done.countDown();
            }, "proxy-u2c");
            c2u.setDaemon(true);
            u2c.setDaemon(true);
            c2u.start();
            u2c.start();
            done.await(120, TimeUnit.SECONDS);

            response.resume();
            upstream.close();
        } catch (Exception e) {
            logger.Error("Can't satisfy CONNECT request: %s - %s", target, e.getMessage());
            if (!response.isSuspended()) {
                response.setStatus(502);
                response.setContentType("text/plain");
                try { response.getWriter().write("Bad Gateway"); } catch (Exception ignored) {}
            } else {
                response.resume();
            }
        } finally {
            if (upstream != null && !upstream.isClosed()) {
                try { upstream.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void handleRequest(Request request, Response response,
                                String method, String target) throws Exception {
        String host = request.getHeader("Host");

        if (host == null || host.isEmpty()) {
            response.setStatus(400);
            response.setContentType("text/plain");
            response.getWriter().write("Bad Request");
            return;
        }

        String path = target;
        if (!path.startsWith("http")) {
            path = "http://" + host + path;
        }

        int port = 80;
        String hostOnly = host;
        int colonIdx = host.lastIndexOf(':');
        if (colonIdx > 0) {
            try {
                port = Integer.parseInt(host.substring(colonIdx + 1));
                hostOnly = host.substring(0, colonIdx);
            } catch (NumberFormatException ignored) {}
        }
        String targetAddr = hostOnly + ":" + port;

        Socket upstream = null;
        boolean reuse = false;
        try {
            upstream = httpDialer.borrow(targetAddr);
            reuse = true;
            OutputStream upOut = upstream.getOutputStream();
            BufferedInputStream upIn = new BufferedInputStream(upstream.getInputStream());

            StringBuilder req = new StringBuilder();
            req.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");

            for (String name : request.getHeaderNames()) {
                if (!name.equalsIgnoreCase("Host") && !HOP_BY_HOP.contains(name)) {
                    for (String val : request.getHeaders(name)) {
                        req.append(name).append(": ").append(val).append("\r\n");
                    }
                }
            }
            req.append("Host: ").append(host).append("\r\n");

            String auth = authProvider.get();
            if (auth != null && !auth.isEmpty()) {
                req.append("Proxy-Authorization: ").append(auth).append("\r\n");
            }
            req.append("Connection: keep-alive\r\n");
            req.append("\r\n");

            upOut.write(req.toString().getBytes(StandardCharsets.UTF_8));
            upOut.flush();

            long contentLen = parseContentLength(request);
            if (contentLen > 0) {
                pipe(request.getInputStream(), upOut, contentLen);
            }

            StatusAndHeaders resp = readStatusAndHeaders(upIn);
            if (resp == null) {
                httpDialer.invalidate(targetAddr, upstream);
                reuse = false;
                upstream = httpDialer.borrow(targetAddr);
                upOut = upstream.getOutputStream();
                upIn = new BufferedInputStream(upstream.getInputStream());
                upOut.write(req.toString().getBytes(StandardCharsets.UTF_8));
                upOut.flush();
                resp = readStatusAndHeaders(upIn);
                if (resp == null) {
                    response.setStatus(502);
                    response.setContentType("text/plain");
                    response.getWriter().write("Bad Gateway");
                    upstream.close();
                    return;
                }
            }

            delHopHeaders(resp.headers);

            int statusCode = parseStatusCode(resp.statusLine);
            response.setStatus(statusCode);

            for (Map.Entry<String, List<String>> h : resp.headers.entrySet()) {
                for (String v : h.getValue()) {
                    response.addHeader(h.getKey(), v);
                }
            }

            OutputStream respOut = response.getOutputStream();
            respOut.flush();

            String connHeader = getFirstHeaderValue(resp.headers, "Connection");
            boolean keepAlive = connHeader != null && connHeader.equalsIgnoreCase("keep-alive");
            if (!keepAlive) {
                reuse = false;
            }

            long respContentLen = parseContentLength(resp.headers);
            if (respContentLen > 0) {
                pipe(upIn, respOut, respContentLen);
            } else {
                pipe(upIn, respOut);
            }
            respOut.flush();

            if (!keepAlive) {
                upstream.close();
            }
        } catch (Exception e) {
            logger.Error("HTTP fetch error: %s", e.getMessage());
            if (!response.isCommitted()) {
                response.setStatus(500);
                response.setContentType("text/plain");
                try { response.getWriter().write("Server Error"); } catch (Exception ignored) {}
            }
        } finally {
            if (upstream != null && !upstream.isClosed()) {
                if (reuse) {
                    httpDialer.release(targetAddr, upstream);
                } else {
                    try { upstream.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private static class StatusAndHeaders {
        final String statusLine;
        final Map<String, List<String>> headers;
        StatusAndHeaders(String statusLine, Map<String, List<String>> headers) {
            this.statusLine = statusLine;
            this.headers = headers;
        }
    }

    private StatusAndHeaders readStatusAndHeaders(BufferedInputStream in) throws IOException {
        in.mark(65536);
        byte[] buf = new byte[65536];
        int total = 0;
        int headerEnd = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                return null;
            }
            total += n;
            for (int i = 0; i <= total - 4; i++) {
                if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                    headerEnd = i + 4;
                    break;
                }
            }
            if (headerEnd > 0) break;
        }
        if (headerEnd == 0) {
            return null;
        }

        in.reset();
        byte[] headerBytes = new byte[headerEnd];
        System.arraycopy(buf, 0, headerBytes, 0, headerEnd);
        in.skip(headerEnd);

        String headerStr = new String(headerBytes, ISO_8859_1);
        String[] lines = headerStr.split("\r\n", -1);
        if (lines.length < 1) return null;

        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
        }
        return new StatusAndHeaders(lines[0], headers);
    }

    private void pipe(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[128 * 1024];
        long remaining = limit;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n == -1) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
        out.flush();
    }

    private void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[128 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private void delHopHeaders(Map<String, List<String>> headers) {
        headers.keySet().removeIf(k -> HOP_BY_HOP.contains(k));
    }

    private long parseContentLength(Request request) {
        String cl = request.getHeader("Content-Length");
        if (cl != null) {
            try { return Long.parseLong(cl.trim()); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private long parseContentLength(Map<String, List<String>> headers) {
        List<String> cl = headers.get("Content-Length");
        if (cl != null && !cl.isEmpty()) {
            try { return Long.parseLong(cl.get(0).trim()); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private String getFirstHeaderValue(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    private static int parseStatusCode(String statusLine) {
        if (statusLine == null || statusLine.isEmpty()) {
            return 502;
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {}
        }
        return 502;
    }

    /**
     * Simple connection pool for reusing TCP connections to upstream proxy.
     */
    private static class HttpDialer {
        private final ContextDialer dialer;
        private final ConcurrentMap<String, Deque<Socket>> pools = new ConcurrentHashMap<>();
        private static final int MAX_IDLE = 100;
        private static final int MAX_POOLS = 1000;

        HttpDialer(ContextDialer dialer) {
            this.dialer = dialer;
        }

        Socket borrow(String targetAddr) throws IOException {
            Deque<Socket> pool = pools.get(targetAddr);
            if (pool != null) {
                synchronized (pool) {
                    while (true) {
                        Socket sock = pool.pollFirst();
                        if (sock == null) break;
                        try {
                            sock.sendUrgentData(0);
                            return sock;
                        } catch (IOException e) {
                            try { sock.close(); } catch (Exception ignored) {}
                        }
                    }
                }
            }
            return dialer.dial("tcp", targetAddr);
        }

        void release(String targetAddr, Socket sock) {
            Deque<Socket> pool = pools.get(targetAddr);
            if (pool == null) {
                if (pools.size() >= MAX_POOLS) {
                    try { sock.close(); } catch (Exception ignored) {}
                    return;
                }
                Deque<Socket> newPool = new ArrayDeque<>();
                pool = pools.putIfAbsent(targetAddr, newPool);
                if (pool == null) pool = newPool;
            }
            synchronized (pool) {
                if (pool.size() >= MAX_IDLE) {
                    try { sock.close(); } catch (Exception ignored) {}
                    return;
                }
                pool.addLast(sock);
            }
        }

        void invalidate(String targetAddr, Socket sock) {
            Deque<Socket> pool = pools.get(targetAddr);
            if (pool != null) {
                synchronized (pool) {
                    pool.remove(sock);
                }
            }
            try { sock.close(); } catch (Exception ignored) {}
        }
    }
}
