package com.example.proxy.http;

import com.example.proxy.dialer.ContextDialer;
import com.example.proxy.dto.*;
import com.google.gson.Gson;

import javax.net.ssl.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * HTTP client for Hola API calls that uses the provided ContextDialer.
 * This ensures all API traffic goes through the outbound proxy if configured.
 * 
 * <p>Mirrors Go's httpClientWithProxy() in holaapi.go which uses the
 * dialer via http.Transport.DialContext.
 */
public class HolaApiClient {

    // TODO: move timeout values elsewhere and actually utilize them

    @SuppressWarnings("unused")
    private static final int DEFAULT_TIMEOUT = 30 * 1000;
    @SuppressWarnings("unused")
    private static final int TLS_HANDSHAKE_TIMEOUT = 10 * 1000;
    @SuppressWarnings("unused")
    private static final int IDLE_CONN_TIMEOUT = 90 * 1000;
    @SuppressWarnings("unused")
    private static final int MAX_IDLE_CONNS = 100;
    @SuppressWarnings("unused")
    private static final int MAX_XML_SIZE = 64 * 1024;

    private final ContextDialer dialer;
    private final Gson gson;
    private final Supplier<String> userAgent;

    public HolaApiClient(ContextDialer dialer, Supplier<String> userAgent) {
        this.dialer = dialer;
        this.gson = new Gson();
        this.userAgent = userAgent;
    }

    /**
     * Make an HTTP request using the dialer.
     * This is the core method that ensures all traffic uses the dialer chain.
     */
    private byte[] httpRequest(String method, String urlStr, byte[] postData) throws IOException {
        URL url = URI.create(urlStr).toURL();
        String host = url.getHost();
        int port = url.getPort();
        if (port == -1) {
            port = url.getDefaultPort();
        }
        String targetAddr = host + ":" + port;
        boolean isHttps = "https".equalsIgnoreCase(url.getProtocol());

        // Use the dialer to establish connection (this routes through outbound proxy if configured)
        Socket socket = dialer.dial("tcp", targetAddr);

        try {
            InputStream in;
            OutputStream out;

            if (isHttps) {
                // Wrap with TLS
                socket = wrapTLS(socket, host);
                SSLSocket sslSocket = (SSLSocket) socket;
                in = sslSocket.getInputStream();
                out = sslSocket.getOutputStream();
            } else {
                in = socket.getInputStream();
                out = socket.getOutputStream();
            }

            // Build HTTP request
            StringBuilder request = new StringBuilder();
            String path = url.getPath();
            if (url.getQuery() != null && !url.getQuery().isEmpty()) {
                path = path + "?" + url.getQuery();
            }
            if (path.isEmpty()) {
                path = "/";
            }

            request.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(host);
            if ((port == 80 && !isHttps) || (port == 443 && isHttps)) {
                // Standard port, omit from Host header
            } else {
                request.append(":").append(port);
            }
            request.append("\r\n");
            request.append("User-Agent: ").append(userAgent.get()).append("\r\n");

            if (postData != null && postData.length > 0) {
                request.append("Content-Length: ").append(postData.length).append("\r\n");
                request.append("Content-Type: application/x-www-form-urlencoded\r\n");
            }

            request.append("Connection: close\r\n");
            request.append("\r\n");

            out.write(request.toString().getBytes(StandardCharsets.UTF_8));

            if (postData != null && postData.length > 0) {
                out.write(postData);
            }
            out.flush();

            // Read response headers first by reading byte-by-byte until \r\n\r\n
            // NOTE: this is rather suboptimal
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int prev3 = -1, prev2 = -1, prev1 = -1, cur;
            while ((cur = in.read()) != -1) {
                headerBuf.write(cur);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && cur == '\n') {
                    break;
                }
                prev3 = prev2;
                prev2 = prev1;
                prev1 = cur;
            }

            String headerSection = headerBuf.toString("UTF-8");
            String[] headerLines = headerSection.split("\r\n");
            if (headerLines.length == 0) {
                throw new IOException("Invalid HTTP response: no status line");
            }

            String statusLine = headerLines[0];
            int statusCode = parseStatusCode(statusLine);

            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("HTTP request failed: " + statusLine);
            }

            boolean chunked = false;
            boolean gzip = false;
            boolean deflate = false;
            int contentLength = -1;
            for (int i = 1; i < headerLines.length; i++) {
                String lower = headerLines[i].toLowerCase();
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    chunked = true;
                } else if (lower.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(headerLines[i].substring(15).trim());
                    } catch (NumberFormatException ignored) {}
                } else if (lower.startsWith("content-encoding:") && lower.contains("gzip")) {
                    gzip = true;
                } else if (lower.startsWith("content-encoding:") && lower.contains("deflate")) {
                    deflate = true;
                }
            }

            InputStream bodyStream = in;
            if (chunked) {
                bodyStream = new ChunkedInputStream(in);
            } else if (contentLength > 0) {
                bodyStream = new FixedLengthInputStream(in, contentLength);
            }

            if (gzip) {
                bodyStream = new GZIPInputStream(bodyStream);
            } else if (deflate) {
                bodyStream = new InflaterInputStream(bodyStream);
            }

            ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int bytesRead;
            while ((bytesRead = bodyStream.read(buf)) != -1) {
                bodyBuf.write(buf, 0, bytesRead);
            }

            return bodyBuf.toByteArray();

        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static int parseStatusCode(String statusLine) {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /**
     * Wrap a socket with TLS. Uses a permissive trust manager since we're
     * connecting to Hola's servers whose cert may not be in the default trust store.
     * This mirrors Go's tls.UClient with HelloAndroid_11_OkHttp fingerprint.
     */
    public static Socket wrapTLS(Socket raw, String serverName) throws IOException {
        return SocketHelper.wrapTLS(raw, serverName, false);
    }

    public BgInitResponse backgroundInit(String extVer, String userUuid) throws IOException {
        String url = "https://client.hola.org/client_cgi/background_init?uuid=" +
                     URLEncoder.encode(userUuid, "UTF-8");
        String postData = "login=1&ver=" + URLEncoder.encode(extVer, "UTF-8");

        try {
            byte[] response = httpRequest("POST", url, postData.getBytes(StandardCharsets.UTF_8));
            String json = new String(response, StandardCharsets.UTF_8);
            BgInitResponse res = gson.fromJson(json, BgInitResponse.class);
            if (res.isBlocked()) {
                throw res.isPermanent() ?
                        new PermanentBanException("Permanent ban") :
                        new TemporaryBanException("Temporary ban");
            }
            return res;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Background init failed: " + e.getMessage(), e);
        }
    }

    public ZGetTunnelsResponse zGetTunnels(String userUuid, long sessionKey, String extVer,
                                           String country, String proxyType, int limit) throws IOException {
        String countryParam;
        switch (proxyType) {
            case "lum":
                countryParam = country + ".pool_lum_" + country + "_shared";
                break;
            case "virt":
                countryParam = country + ".pool_virt_pool_" + country;
                break;
            case "pool":
                countryParam = country + ".pool";
                break;
            default:
                countryParam = country;
                break;
        }

        StringBuilder params = new StringBuilder();
        params.append("country=").append(URLEncoder.encode(countryParam, "UTF-8"));
        params.append("&limit=").append(limit);
        params.append("&ping_id=").append(Math.random());
        params.append("&ext_ver=").append(URLEncoder.encode(extVer, "UTF-8"));
        params.append("&browser=chrome");
        params.append("&product=cws");
        params.append("&uuid=").append(URLEncoder.encode(userUuid, "UTF-8"));
        params.append("&session_key=").append(sessionKey);
        params.append("&is_premium=0");

        String url = "https://client.hola.org/client_cgi/zgettunnels" + "?" + params.toString();

        try {
            byte[] response = httpRequest("POST", url, null);
            String json = new String(response, StandardCharsets.UTF_8);
            ZGetTunnelsResponse res = gson.fromJson(json, ZGetTunnelsResponse.class);
            if (res.getIpList() == null || res.getIpList().isEmpty()) {
                throw new IOException("empty response");
            }
            return res;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("zGetTunnels failed: " + e.getMessage(), e);
        }
    }

    public String getExtVer(String prodVersion, String extensionId) throws IOException {
        String encodedX = URLEncoder.encode("id=" + extensionId + "&uc", "UTF-8");
        String fullUrl = "https://clients2.google.com/service/update2/crx" +
                "?prodversion=" + URLEncoder.encode(prodVersion, "UTF-8") +
                "&acceptformat=crx2,crx3" +
                "&x=" + encodedX;

        try {
            byte[] response = httpRequest("GET", fullUrl, null);
            String xml = new String(response, StandardCharsets.UTF_8);
            return parseExtVerFromXml(xml);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("chrome web store: " + e.getMessage(), e);
        }
    }

    private String parseExtVerFromXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new org.xml.sax.InputSource(new StringReader(xml)));

            String version = getVersionFromDoc(doc);
            if (version != null && !version.isEmpty()) {
                return version;
            }
            throw new IOException("no version data returned");
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("unmarshaling of chrome web store response failed: " + e.getMessage(), e);
        }
    }

    private String getVersionFromDoc(org.w3c.dom.Document doc) {
        org.w3c.dom.Element root = doc.getDocumentElement();
        if (root == null) return null;
        if (!"gupdate".equals(root.getTagName())) {
            root = getChildElement(root, "gupdate");
            if (root == null) return null;
        }

        org.w3c.dom.Element app = getChildElement(root, "app");
        if (app == null) return null;

        org.w3c.dom.Element updatecheck = getChildElement(app, "updatecheck");
        if (updatecheck == null) return null;

        String version = updatecheck.getAttribute("version");
        return (version != null && !version.isEmpty()) ? version : null;
    }

    private static org.w3c.dom.Element getChildElement(org.w3c.dom.Element parent, String tagName) {
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (node instanceof org.w3c.dom.Element && tagName.equals(node.getNodeName())) {
                return (org.w3c.dom.Element) node;
            }
        }
        return null;
    }

    public String getChromeVer() throws IOException {
        String url = "https://versionhistory.googleapis.com/v1/chrome/platforms/win/channels/stable/versions?alt=json&orderBy=version+desc&pageSize=1&prettyPrint=false";

        try {
            byte[] response = httpRequest("GET", url, null);
            String json = new String(response, StandardCharsets.UTF_8);
            ChromeVerResponse resp = gson.fromJson(json, ChromeVerResponse.class);
            String version = resp.getVersion();
            if (version != null && !version.isEmpty()) {
                return version;
            }
            throw new IOException("no version data returned");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("chrome browser version request failed: " + e.getMessage(), e);
        }
    }

    public static class TemporaryBanException extends IOException {
        public TemporaryBanException(String msg) { super(msg); }
    }

    public static class PermanentBanException extends IOException {
        public PermanentBanException(String msg) { super(msg); }
    }

    // ==================== Fallback Proxy Support ====================

    private static final String[] FALLBACK_CONF_URLS = {
        "https://hola.org/fallback/conf",
        "https://s3.amazonaws.com/hola/fallback/conf"
    };

    private static final String AGENT_SUFFIX = ".hola.org";

    private volatile byte[] cachedFallbackPayload;
    private long cachedFallbackUpdatedAt;
    private long cachedFallbackTtlMs;
    private final Object fallbackLock = new Object();

    public static class FallbackAgent {
        private String name;
        private String ip;
        private int port;

        public FallbackAgent() {}

        public FallbackAgent(String name, String ip, int port) {
            this.name = name;
            this.ip = ip;
            this.port = port;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String hostname() { return name + AGENT_SUFFIX; }
        public String netAddr() { return ip + ":" + port; }
    }

    public static class FallbackConfig {
        private java.util.List<FallbackAgent> agents;
        private long updatedAt;
        private long ttlMs;

        public java.util.List<FallbackAgent> getAgents() { return agents; }
        public void setAgents(java.util.List<FallbackAgent> agents) { this.agents = agents; }
        public long getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
        public long getTtlMs() { return ttlMs; }
        public void setTtlMs(long ttlMs) { this.ttlMs = ttlMs; }
        public boolean isExpired() { return System.currentTimeMillis() - updatedAt > ttlMs; }
        public void shuffleAgents() { java.util.Collections.shuffle(agents); }
        public FallbackConfig copy() {
            FallbackConfig c = new FallbackConfig();
            c.setAgents(new java.util.ArrayList<>(agents));
            c.setUpdatedAt(updatedAt);
            c.setTtlMs(ttlMs);
            return c;
        }
    }

    private static class FallbackConfResponse {
        public java.util.List<FallbackAgent> agents;
        public long updated_at;
        public long ttl;
    }

    private FallbackConfig decodeFallbackConfig(byte[] raw) throws IOException {
        if (raw.length < 4) throw new IOException("bad response length");
        byte[] rotated = new byte[raw.length];
        System.arraycopy(raw, raw.length - 3, rotated, 0, 3);
        System.arraycopy(raw, 0, rotated, 3, raw.length - 3);
        byte[] decoded = java.util.Base64.getDecoder().decode(rotated);
        FallbackConfResponse resp = gson.fromJson(new String(decoded, StandardCharsets.UTF_8), FallbackConfResponse.class);
        FallbackConfig config = new FallbackConfig();
        config.setAgents(resp.agents);
        config.setUpdatedAt(resp.updated_at);
        config.setTtlMs(resp.ttl);
        if (config.isExpired()) throw new IOException("fetched expired fallback config");
        config.shuffleAgents();
        return config;
    }

    public FallbackConfig getFallbackProxies() throws IOException {
        synchronized (fallbackLock) {
            if (cachedFallbackPayload == null || System.currentTimeMillis() - cachedFallbackUpdatedAt > cachedFallbackTtlMs) {
                cachedFallbackPayload = fetchFallbackPayload();
                FallbackConfig parsed = decodeFallbackConfig(cachedFallbackPayload);
                cachedFallbackUpdatedAt = parsed.getUpdatedAt();
                cachedFallbackTtlMs = parsed.getTtlMs();
            }
            return decodeFallbackConfig(cachedFallbackPayload).copy();
        }
    }

    private byte[] fetchFallbackPayload() throws IOException {
        java.util.Random rnd = new java.util.Random();
        String urlStr = FALLBACK_CONF_URLS[rnd.nextInt(FALLBACK_CONF_URLS.length)];
        return httpRequest("GET", urlStr, null);
    }

    public HolaApiClient withDialer(ContextDialer newDialer) {
        return new HolaApiClient(newDialer, userAgent);
    }
}
