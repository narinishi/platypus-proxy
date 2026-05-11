package com.example.proxy.handler;

import com.example.proxy.dialer.ContextDialer;
import com.example.proxy.logging.ConditionalLogger;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.nio.NIOConnection;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * HTTP forward proxy with CONNECT tunnelling and chunked‑transfer handling.
 * Chunked responses from the upstream server are decoded and the payload
 * is written as plain bytes; Grizzly automatically applies chunked encoding
 * when the 'Transfer-Encoding: chunked' header is present.
 */
public class ProxyHandler extends HttpHandler {

    private static final Charset HEADER_CHARSET = StandardCharsets.ISO_8859_1;
    private static final int MAX_HEADER_SIZE = 65_536;
    private static final int SO_TIMEOUT_MS = 30_000;
    private static final int SOCKET_BUFFER_SIZE = 64 * 1024;
    private static final int RELAY_BUFFER_SIZE = 256 * 1024;
    private static final int PIPE_BUFFER_SIZE = 128 * 1024;

    @SuppressWarnings("unused")
    private static final int DEFAULT_HTTP_PORT = 80;

    static final int MAX_IDLE_PROVIDER_SOCKETS = 16;

    private static final ThreadLocal<byte[]> HEADER_BUFFER =
            ThreadLocal.withInitial(() -> new byte[MAX_HEADER_SIZE]);

    private static final Set<String> HOP_BY_HOP_HEADERS;

    static {
        Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        s.addAll(List.of(
                "Connection", "Keep-Alive", "Proxy-Authenticate",
                "Proxy-Connection", "TE", "Trailers",
                // Transfer-Encoding is kept intentionally; Grizzly will add
                // chunked framing based on this header.
                "Upgrade"
        ));
        HOP_BY_HOP_HEADERS = Set.copyOf(s);
    }

    private static final Set<String> STRIP_HEADERS = Set.of("Expect");
    private static final AtomicLong TUNNEL_ID_SEQ = new AtomicLong(0);

    private final ContextDialer tunnelDialer;
    private final Supplier<String> authProvider;
    private final ConditionalLogger logger;
    private final ProviderConnectionPool providerPool;

    public ProxyHandler(ContextDialer tunnelDialer,
                        ContextDialer requestDialer,
                        Supplier<String> authProvider,
                        ConditionalLogger logger) {
        this.tunnelDialer = tunnelDialer;
        this.authProvider = authProvider;
        this.logger = logger;
        this.providerPool = new ProviderConnectionPool(requestDialer);
    }

    // ── entry point ──────────────────────────────────────────────────────────

    @Override
    public void service(Request request, Response response) throws Exception {
        String method = request.getMethod().toString();
        String target = request.getRequest().getRequestURI();
        String remoteAddr = request.getRemoteAddr();
        int remotePort = request.getRemotePort();

        logger.Info("%s:%d %s %s", remoteAddr, remotePort, method,
                "CONNECT".equalsIgnoreCase(method) ? target : request.getRequestURL().toString());

        if ("CONNECT".equalsIgnoreCase(method)) {
            handleTunnel(request, response, target);
        } else {
            handleRequest(request, response, method, target);
        }
    }

    // ── CONNECT tunnel (unchanged) ──────────────────────────────────────────

    private void handleTunnel(Request request, Response response,
                              String target) throws Exception {
        Socket upstream = null;
        SocketChannel clientCh = null;
        try {
            upstream = tunnelDialer.dial("tcp", target);
            tuneSocket(upstream);

            response.setStatus(200);
            response.setContentType("application/octet-stream");
            response.getOutputStream().flush();

            clientCh = detachClientChannel(request);
            Socket clientSock = clientCh.socket();
            tuneSocket(clientSock);

            logger.Info("Tunnel established: %s", target);
            long id = TUNNEL_ID_SEQ.incrementAndGet();
            startRelay(clientSock, upstream, clientCh, id);

        } catch (Exception e) {
            logger.Error("Cannot satisfy CONNECT: %s - %s", target, e.getMessage());
            closeQuietly(upstream);
            closeQuietly(clientCh);
            sendSimpleError(response, 502, "Bad Gateway");
        }
    }

    private SocketChannel detachClientChannel(Request request) throws IOException {
        NIOConnection nioConn = (NIOConnection) request.getRequest().getConnection();
        SocketChannel ch = (SocketChannel) nioConn.getChannel();
        SelectionKey sk = nioConn.getSelectionKey();
        if (sk != null) sk.cancel();
        ch.configureBlocking(true);
        return ch;
    }

    private void startRelay(Socket client, Socket upstream,
                            SocketChannel clientCh, long id) {
        Thread.ofVirtual().name("proxy-c2u-" + id).start(() -> {
            try (InputStream in = client.getInputStream();
                 OutputStream out = upstream.getOutputStream()) {
                relay(in, out);
                upstream.shutdownOutput();
            } catch (IOException e) {
                logger.Debug("c2u relay %d ended: %s", id, e.getMessage());
            } finally {
                closeQuietly(upstream);
            }
        });
        Thread.ofVirtual().name("proxy-u2c-" + id).start(() -> {
            try (InputStream in = upstream.getInputStream();
                 OutputStream out = client.getOutputStream()) {
                relay(in, out);
                client.shutdownOutput();
            } catch (IOException e) {
                logger.Debug("u2c relay %d ended: %s", id, e.getMessage());
            } finally {
                closeQuietly(clientCh);
            }
        });
    }

    private static void relay(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[RELAY_BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
    }

    // ── HTTP request forwarding ────────────────────────────────────────────

    private void handleRequest(Request request, Response response,
                               String method, String target) throws Exception {
        String host = request.getHeader("Host");
        if (host == null || host.isEmpty()) {
            sendSimpleError(response, 400, "Bad Request");
            return;
        }

        String path = target.startsWith("http") ? target : "http://" + host + target;

        Socket upstream = null;
        boolean reuse = false;
        try {
            upstream = providerPool.borrow();
            reuse = true;

            OutputStream upOut = upstream.getOutputStream();
            BufferedInputStream upIn = new BufferedInputStream(
                    upstream.getInputStream(), MAX_HEADER_SIZE);

            long contentLen = parseContentLength(request);
            boolean hasBody = contentLen > 0;
            String requestHead = buildRequestHead(method, path, host, request);

            upOut.write(requestHead.getBytes(StandardCharsets.UTF_8));
            if (hasBody) {
                pipe(request.getInputStream(), upOut, contentLen);
            }

            StatusAndHeaders resp = readStatusAndHeaders(upIn);

            // Retry only if no body was consumed
            if (resp == null) {
                providerPool.invalidate(upstream);
                reuse = false;

                if (hasBody) {
                    sendSimpleError(response, 502, "Bad Gateway");
                    return;
                }

                upstream = providerPool.borrowFresh();
                upOut = upstream.getOutputStream();
                upIn = new BufferedInputStream(
                        upstream.getInputStream(), MAX_HEADER_SIZE);
                upOut.write(requestHead.getBytes(StandardCharsets.UTF_8));

                resp = readStatusAndHeaders(upIn);
                if (resp == null) {
                    sendSimpleError(response, 502, "Bad Gateway");
                    return;
                }
            }

            // Filter headers: keep Transfer-Encoding, strip other hop-by-hop
            Map<String, List<String>> filteredHeaders = new LinkedHashMap<>(resp.headers());
            filteredHeaders.keySet().removeIf(h -> {
                if ("Transfer-Encoding".equalsIgnoreCase(h)) {
                    return false;  // Grizzly will apply chunked based on this
                }
                return HOP_BY_HOP_HEADERS.contains(h);
            });
            filteredHeaders.remove("Trailer");   // we discard trailers

            int statusCode = parseStatusCode(resp.statusLine());
            response.setStatus(statusCode);
            copyHeadersToResponse(filteredHeaders, response);

            // Commit headers before body
            OutputStream respOut = response.getOutputStream();
            respOut.flush();

            // Decide about socket reuse based on upstream's Connection header
            boolean keepAlive = isKeepAlive(resp.headers());
            if (!keepAlive) {
                reuse = false;
            }

            // Transfer body (automatically chunked by Grizzly when TE header present)
            transferResponseBody(method, statusCode, filteredHeaders, upIn, respOut);
            respOut.flush();

            if (!keepAlive) {
                closeQuietly(upstream);
            }
        } catch (Exception e) {
            logger.Error("HTTP fetch error: %s", e.getMessage());
            reuse = false;
            sendSimpleError(response, 500, "Server Error");
        } finally {
            if (upstream != null && !upstream.isClosed()) {
                if (reuse) {
                    providerPool.release(upstream);
                } else {
                    closeQuietly(upstream);
                }
            }
        }
    }

    // ── Request construction (unchanged) ───────────────────────────────────

    private String buildRequestHead(String method, String path,
                                    String host, Request request) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        for (String name : request.getHeaderNames()) {
            if (name.equalsIgnoreCase("Host") ||
                HOP_BY_HOP_HEADERS.contains(name) ||
                STRIP_HEADERS.contains(name)) {
                continue;
            }
            for (String val : request.getHeaders(name)) {
                sb.append(name).append(": ").append(val).append("\r\n");
            }
        }
        sb.append("Host: ").append(host).append("\r\n");
        String auth = authProvider.get();
        if (auth != null && !auth.isEmpty()) {
            sb.append("Proxy-Authorization: ").append(auth).append("\r\n");
        }
        boolean wantsClose = "close".equalsIgnoreCase(request.getHeader("Connection"))
                || "close".equalsIgnoreCase(request.getHeader("Proxy-Connection"));
        sb.append("Connection: ").append(wantsClose ? "close" : "keep-alive").append("\r\n\r\n");
        return sb.toString();
    }

    // ── Response body transfer ─────────────────────────────────────────────

    private void transferResponseBody(String method, int statusCode,
                                      Map<String, List<String>> headers,
                                      InputStream in, OutputStream out)
            throws IOException {
        if ("HEAD".equalsIgnoreCase(method)) return;
        if (isStatusWithoutBody(statusCode)) return;

        long contentLen = parseContentLength(headers);
        if (contentLen >= 0) {
            // Known content length
            pipe(in, out, contentLen);
        } else if (isChunked(headers)) {
            // Upstream is chunked: decode it and write raw bytes;
            // Grizzly will re-apply chunked encoding based on the
            // Transfer-Encoding header that was forwarded.
            pipeChunkedDecoded(in, out);
        } else {
            // No content-length and not chunked: read until timeout/close
            pipeWithTimeout(in, out);
        }
    }

    /**
     * Reads an upstream chunked body and writes the decoded payload bytes
     * to {@code out}. Chunk extensions and trailers are ignored.
     * This method does NOT write any chunk framing – the caller is
     * responsible for ensuring the downstream transport adds chunked
     * encoding (Grizzly does it automatically when the response contains
     * a {@code Transfer-Encoding: chunked} header).
     */
    private void pipeChunkedDecoded(InputStream in, OutputStream out)
            throws IOException {
        byte[] lineBuf = new byte[1024];       // for chunk-size lines
        byte[] dataBuf = new byte[PIPE_BUFFER_SIZE]; // for chunk data

        while (true) {
            String sizeLine = readLine(in, lineBuf, 1024);
            if (sizeLine == null) {
                throw new IOException("Premature end of chunked stream");
            }

            // Parse size (ignore extensions, e.g. ;foo=bar)
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0) ? sizeLine.substring(0, semi).trim()
                                     : sizeLine.trim();
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size: " + sizeLine, e);
            }

            if (chunkSize == 0) {
                // Last chunk – discard trailers, then stop
                while (true) {
                    String trailer = readLine(in, lineBuf, 1024);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                break;
            }

            // Copy chunk data verbatim
            long remaining = chunkSize;
            while (remaining > 0) {
                int toRead = (int) Math.min(dataBuf.length, remaining);
                int n = in.read(dataBuf, 0, toRead);
                if (n == -1) {
                    throw new IOException("Unexpected EOF in chunk data");
                }
                out.write(dataBuf, 0, n);
                remaining -= n;
            }

            // Skip trailing CRLF after the chunk
            if (!skipCRLF(in)) {
                throw new IOException("Missing CRLF after chunk data");
            }
        }
        out.flush();
    }

    // ── Header parsing and utility ─────────────────────────────────────────

    private static boolean isStatusWithoutBody(int status) {
        return (status >= 100 && status < 200) || status == 204 || status == 304;
    }

    private static void copyHeadersToResponse(Map<String, List<String>> headers,
                                              Response response) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (String value : entry.getValue()) {
                response.addHeader(entry.getKey(), value);
            }
        }
    }

    record StatusAndHeaders(String statusLine,
                            Map<String, List<String>> headers) {}

    private StatusAndHeaders readStatusAndHeaders(BufferedInputStream in)
            throws IOException {
        in.mark(MAX_HEADER_SIZE);
        byte[] buf = HEADER_BUFFER.get();
        int total = 0, headerEnd = -1;

        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) return null;
            total += n;
            int scanFrom = Math.max(0, total - n - 3);
            for (int i = scanFrom; i <= total - 4; i++) {
                if (buf[i] == '\r' && buf[i+1] == '\n' &&
                    buf[i+2] == '\r' && buf[i+3] == '\n') {
                    headerEnd = i + 4;
                    break;
                }
            }
            if (headerEnd >= 0) break;
        }
        if (headerEnd < 0) return null;

        String headerBlock = new String(buf, 0, headerEnd, HEADER_CHARSET);
        in.reset();
        in.skipNBytes(headerEnd);
        return parseStatusAndHeaders(headerBlock);
    }

    private static StatusAndHeaders parseStatusAndHeaders(String headerBlock) {
        String[] lines = headerBlock.split("\r\n");
        if (lines.length == 0) return null;
        String statusLine = lines[0];
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
        return new StatusAndHeaders(statusLine, headers);
    }

    // ── Low‑level I/O helpers ──────────────────────────────────────────────

    private static void pipe(InputStream in, OutputStream out, long limit)
            throws IOException {
        byte[] buf = new byte[PIPE_BUFFER_SIZE];
        long remaining = limit;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n == -1) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
        out.flush();
    }

    private void pipeWithTimeout(InputStream in, OutputStream out)
            throws IOException {
        byte[] buf = new byte[PIPE_BUFFER_SIZE];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (SocketTimeoutException e) {
            logger.Debug("Fallback pipe stopped after timeout");
        }
        out.flush();
    }

    /**
     * Reads one line terminated by CRLF from the stream.
     * Uses a reusable buffer to avoid single‑byte reads where possible.
     */
    private static String readLine(InputStream in, byte[] buf, int maxLen)
            throws IOException {
        int count = 0;
        while (count < maxLen) {
            int b = in.read();
            if (b == -1) {
                return count > 0 ? new String(buf, 0, count, HEADER_CHARSET) : null;
            }
            if (b == '\n') {
                // remove preceding \r if present
                if (count > 0 && buf[count - 1] == '\r') {
                    count--;
                }
                return new String(buf, 0, count, HEADER_CHARSET);
            }
            buf[count++] = (byte) b;
        }
        // maxLen exceeded, return what we have (error recovery)
        return new String(buf, 0, count, HEADER_CHARSET);
    }

    private static boolean skipCRLF(InputStream in) throws IOException {
        int c1 = in.read();
        int c2 = in.read();
        return c1 == '\r' && c2 == '\n';
    }

    // ── Header query helpers ──────────────────────────────────────────────

    private static boolean isChunked(Map<String, List<String>> headers) {
        List<String> te = headers.get("Transfer-Encoding");
        if (te == null) return false;
        for (String v : te) {
            for (String part : v.split(",")) {
                if (part.trim().equalsIgnoreCase("chunked")) return true;
            }
        }
        return false;
    }

    private static boolean isKeepAlive(Map<String, List<String>> headers) {
        String conn = getFirstHeader(headers, "Connection");
        return "keep-alive".equalsIgnoreCase(conn);
    }

    private static String getFirstHeader(Map<String, List<String>> headers,
                                         String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    private static long parseContentLength(Request request) {
        String cl = request.getHeader("Content-Length");
        if (cl != null) {
            try {
                return Long.parseLong(cl.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static long parseContentLength(Map<String, List<String>> headers) {
        String cl = getFirstHeader(headers, "Content-Length");
        if (cl != null) {
            try {
                return Long.parseLong(cl.trim());
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static int parseStatusCode(String statusLine) {
        if (statusLine == null || statusLine.isEmpty()) return 502;
        String[] parts = statusLine.split(" ", 3);
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {}
        }
        return 502;
    }

    static void tuneSocket(Socket socket) throws IOException {
        socket.setSoTimeout(SO_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
        socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
    }

    static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try { closeable.close(); } catch (Exception ignored) {}
        }
    }

    private void sendSimpleError(Response response, int status, String body) {
        if (!response.isCommitted()) {
            try {
                response.setStatus(status);
                response.setContentType("text/plain");
                response.getWriter().write(body);
            } catch (Exception ignored) {}
        }
    }
}