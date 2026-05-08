package com.example.proxy.http;

import com.example.proxy.dto.*;
import com.example.proxy.dialer.ContextDialer;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

public class OperaApiClient {

    public static final long SE_STATUS_OK = 0;

    private static final String API_BASE = "https://api2.sec-tunnel.com/v4";
    private static final String CLIENT_VERSION = "Stable 114.0.5282.21";
    private static final String CLIENT_TYPE = "se0316";
    private static final String DEVICE_NAME = "Opera-Browser-Client";
    private static final String OPERATING_SYSTEM = "Windows";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 OPR/114.0.0.0";

    private final ContextDialer dialer;
    private final Gson gson;

    public String subscriberEmail;
    public String subscriberPassword;
    public String deviceHash;
    public String assignedDeviceID;
    public String devicePassword;
    public String deviceIdHash;

    private final CookieManager cookieManager;

    public OperaApiClient(ContextDialer dialer) {
        this.dialer = dialer;
        this.gson = new Gson();
        this.cookieManager = new CookieManager();
    }

    public void resetCookies() {
        cookieManager.clear();
    }

    public void anonRegister() throws Exception {
        resetCookies();

        byte[] localPartRaw = new byte[32];
        new SecureRandom().nextBytes(localPartRaw);
        String localPart = Base64.getEncoder().encodeToString(localPartRaw);
        subscriberEmail = localPart + "@" + CLIENT_TYPE + ".best.vpn";

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        subscriberPassword = bytesToHex(sha1.digest(subscriberEmail.getBytes(StandardCharsets.UTF_8))).toUpperCase();

        String postData = "email=" + URLEncoder.encode(subscriberEmail, "UTF-8")
                + "&password=" + URLEncoder.encode(subscriberPassword, "UTF-8");

        byte[] resp = rpcCall("POST", API_BASE + "/register_subscriber", postData.getBytes(StandardCharsets.UTF_8));
        SERegisterSubscriberResponse res = gson.fromJson(new String(resp, StandardCharsets.UTF_8), SERegisterSubscriberResponse.class);
        if (res.getStatus().getCode() != SE_STATUS_OK) {
            throw new IOException("register_subscriber failed: code=" + res.getStatus().getCode()
                    + " msg=" + res.getStatus().getMessage());
        }
    }

    public void registerDevice() throws Exception {
        SecureRandom rng = new SecureRandom();
        byte[] deviceBytes = new byte[20];
        rng.nextBytes(deviceBytes);
        deviceHash = bytesToHex(deviceBytes).toUpperCase();

        String postData = "client_type=" + URLEncoder.encode(CLIENT_TYPE, "UTF-8")
                + "&device_hash=" + URLEncoder.encode(deviceHash, "UTF-8")
                + "&device_name=" + URLEncoder.encode(DEVICE_NAME, "UTF-8");

        byte[] resp = rpcCall("POST", API_BASE + "/register_device", postData.getBytes(StandardCharsets.UTF_8));
        SERegisterDeviceResponse res = gson.fromJson(new String(resp, StandardCharsets.UTF_8), SERegisterDeviceResponse.class);
        if (res.getStatus().getCode() != SE_STATUS_OK) {
            throw new IOException("register_device failed: code=" + res.getStatus().getCode()
                    + " msg=" + res.getStatus().getMessage());
        }

        assignedDeviceID = res.getData().getDeviceId();
        devicePassword = res.getData().getDevicePassword();

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        deviceIdHash = bytesToHex(sha1.digest(assignedDeviceID.getBytes(StandardCharsets.UTF_8))).toUpperCase();
    }

    public List<SEGeoEntry> geoList() throws Exception {
        String postData = "device_id=" + URLEncoder.encode(deviceIdHash, "UTF-8");

        byte[] resp = rpcCall("POST", API_BASE + "/geo_list", postData.getBytes(StandardCharsets.UTF_8));
        SEGeoListResponse res = gson.fromJson(new String(resp, StandardCharsets.UTF_8), SEGeoListResponse.class);
        if (res.getStatus().getCode() != SE_STATUS_OK) {
            throw new IOException("geo_list failed: code=" + res.getStatus().getCode()
                    + " msg=" + res.getStatus().getMessage());
        }
        return res.getGeos();
    }

    public List<SEIPEntry> discover(String requestedGeo) throws Exception {
        String postData = "serial_no=" + URLEncoder.encode(deviceIdHash, "UTF-8")
                + "&requested_geo=" + URLEncoder.encode(requestedGeo, "UTF-8");

        byte[] resp = rpcCall("POST", API_BASE + "/discover", postData.getBytes(StandardCharsets.UTF_8));
        SEDiscoverResponse res = gson.fromJson(new String(resp, StandardCharsets.UTF_8), SEDiscoverResponse.class);
        if (res.getStatus().getCode() != SE_STATUS_OK) {
            throw new IOException("discover failed: code=" + res.getStatus().getCode()
                    + " msg=" + res.getStatus().getMessage());
        }
        return res.getIPs();
    }

    public void login() throws Exception {
        resetCookies();

        String postData = "login=" + URLEncoder.encode(subscriberEmail, "UTF-8")
                + "&password=" + URLEncoder.encode(subscriberPassword, "UTF-8")
                + "&client_type=" + URLEncoder.encode(CLIENT_TYPE, "UTF-8");

        byte[] resp = rpcCall("POST", API_BASE + "/subscriber_login", postData.getBytes(StandardCharsets.UTF_8));
        SERegisterSubscriberResponse res = gson.fromJson(new String(resp, StandardCharsets.UTF_8), SERegisterSubscriberResponse.class);
        if (res.getStatus().getCode() != SE_STATUS_OK) {
            throw new IOException("subscriber_login failed: code=" + res.getStatus().getCode()
                    + " msg=" + res.getStatus().getMessage());
        }
    }

    public void deviceGeneratePassword() throws Exception {
        String postData = "device_id=" + URLEncoder.encode(assignedDeviceID, "UTF-8");

        byte[] resp = rpcCall("POST", API_BASE + "/device_generate_password", postData.getBytes(StandardCharsets.UTF_8));
        SEDeviceGeneratePasswordResponse res = gson.fromJson(new String(resp, StandardCharsets.UTF_8), SEDeviceGeneratePasswordResponse.class);
        if (res.getStatus().getCode() != SE_STATUS_OK) {
            throw new IOException("device_generate_password failed: code=" + res.getStatus().getCode()
                    + " msg=" + res.getStatus().getMessage());
        }
        devicePassword = res.getData().getDevicePassword();
    }

    public String[] getProxyCredentials() {
        return new String[]{deviceIdHash, devicePassword};
    }

    private byte[] rpcCall(String method, String urlStr, byte[] postData) throws Exception {
        URL url = new URL(urlStr);
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : 443;

        java.net.Socket socket = dialer.dial("tcp", host + ":" + port);
        try {
            socket = com.example.proxy.http.HolaApiClient.wrapTLS(socket, host, true);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            StringBuilder request = new StringBuilder();
            String path = url.getPath();
            if (url.getQuery() != null) {
                path = path + "?" + url.getQuery();
            }

            request.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(host).append("\r\n");
            request.append("User-Agent: ").append(USER_AGENT).append("\r\n");
            request.append("SE-Client-Version: ").append(CLIENT_VERSION).append("\r\n");
            request.append("SE-Operating-System: ").append(OPERATING_SYSTEM).append("\r\n");
            request.append("Content-Type: application/x-www-form-urlencoded\r\n");
            request.append("Accept: application/json\r\n");

            String cookies = cookieManager.getCookies(host);
            if (cookies != null && !cookies.isEmpty()) {
                request.append("Cookie: ").append(cookies).append("\r\n");
            }

            if (postData != null && postData.length > 0) {
                request.append("Content-Length: ").append(postData.length).append("\r\n");
            }

            request.append("Connection: close\r\n");
            request.append("\r\n");

            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            if (postData != null && postData.length > 0) {
                out.write(postData);
            }
            out.flush();

            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int prev3 = -1, prev2 = -1, prev1 = -1, cur;
            while ((cur = in.read()) != -1) {
                headerBuf.write(cur);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && cur == '\n') break;
                prev3 = prev2; prev2 = prev1; prev1 = cur;
            }

            String headerSection = headerBuf.toString("UTF-8");
            String[] headerLines = headerSection.split("\r\n");
            if (headerLines.length == 0) {
                throw new IOException("Empty HTTP response");
            }

            String statusLine = headerLines[0];
            int statusCode = parseStatusCode(statusLine);

            cookieManager.processHeaders(host, headerLines);

            if (statusCode == 401) {
                socket.close();
                return handleDigestAuth(method, urlStr, postData);
            }

            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("HTTP " + statusCode + ": " + statusLine);
            }

            boolean chunked = false;
            int contentLength = -1;
            for (int i = 1; i < headerLines.length; i++) {
                String lower = headerLines[i].toLowerCase();
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    chunked = true;
                } else if (lower.startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(headerLines[i].substring(15).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }

            InputStream bodyStream = in;
            if (chunked) {
                bodyStream = new ChunkedInputStream(in);
            } else if (contentLength > 0) {
                bodyStream = new FixedLengthInputStream(in, contentLength);
            }

            ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = bodyStream.read(buf)) != -1) {
                bodyBuf.write(buf, 0, n);
            }

            return bodyBuf.toByteArray();

        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private byte[] handleDigestAuth(String method, String urlStr, byte[] postData) throws Exception {
        URL url = new URL(urlStr);
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : 443;

        java.net.Socket socket = dialer.dial("tcp", host + ":" + port);
        try {
            socket = com.example.proxy.http.HolaApiClient.wrapTLS(socket, host, true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String path = url.getPath();
            if (url.getQuery() != null) {
                path = path + "?" + url.getQuery();
            }

            // First request without auth to get challenge
            StringBuilder req1 = new StringBuilder();
            req1.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
            req1.append("Host: ").append(host).append("\r\n");
            req1.append("User-Agent: ").append(USER_AGENT).append("\r\n");
            req1.append("SE-Client-Version: ").append(CLIENT_VERSION).append("\r\n");
            req1.append("SE-Operating-System: ").append(OPERATING_SYSTEM).append("\r\n");
            req1.append("Content-Type: application/x-www-form-urlencoded\r\n");
            req1.append("Accept: application/json\r\n");
            if (postData != null && postData.length > 0) {
                req1.append("Content-Length: ").append(postData.length).append("\r\n");
            }
            req1.append("Connection: close\r\n\r\n");

            out.write(req1.toString().getBytes(StandardCharsets.UTF_8));
            if (postData != null && postData.length > 0) {
                out.write(postData);
            }
            out.flush();

            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int prev3 = -1, prev2 = -1, prev1 = -1, cur;
            while ((cur = in.read()) != -1) {
                headerBuf.write(cur);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && cur == '\n') break;
                prev3 = prev2; prev2 = prev1; prev1 = cur;
            }

            String headerSection = headerBuf.toString("UTF-8");
            String[] headerLines = headerSection.split("\r\n");
            String wwwAuth = null;
            for (String line : headerLines) {
                if (line.toLowerCase().startsWith("www-authenticate:")) {
                    wwwAuth = line.substring(18).trim();
                    break;
                }
            }
            socket.close();

            if (wwwAuth == null) {
                throw new IOException("No WWW-Authenticate header in 401 response");
            }

            if (wwwAuth.startsWith("Digest ")) {
                wwwAuth = wwwAuth.substring(7);
            }
            Map<String, String> challenge = parseDigestChallenge(wwwAuth);
            String digestAuth = buildDigestResponse(method, path, challenge);

            // Second request with digest auth
            socket = dialer.dial("tcp", host + ":" + port);
            socket = com.example.proxy.http.HolaApiClient.wrapTLS(socket, host, true);
            in = socket.getInputStream();
            out = socket.getOutputStream();

            StringBuilder req2 = new StringBuilder();
            req2.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
            req2.append("Host: ").append(host).append("\r\n");
            req2.append("User-Agent: ").append(USER_AGENT).append("\r\n");
            req2.append("SE-Client-Version: ").append(CLIENT_VERSION).append("\r\n");
            req2.append("SE-Operating-System: ").append(OPERATING_SYSTEM).append("\r\n");
            req2.append("Content-Type: application/x-www-form-urlencoded\r\n");
            req2.append("Accept: application/json\r\n");
            req2.append("Authorization: Digest ").append(digestAuth).append("\r\n");
            if (postData != null && postData.length > 0) {
                req2.append("Content-Length: ").append(postData.length).append("\r\n");
            }
            req2.append("Connection: close\r\n\r\n");

            out.write(req2.toString().getBytes(StandardCharsets.UTF_8));
            if (postData != null && postData.length > 0) {
                out.write(postData);
            }
            out.flush();

            headerBuf = new ByteArrayOutputStream();
            prev3 = -1; prev2 = -1; prev1 = -1;
            while ((cur = in.read()) != -1) {
                headerBuf.write(cur);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && cur == '\n') break;
                prev3 = prev2; prev2 = prev1; prev1 = cur;
            }

            headerSection = headerBuf.toString("UTF-8");
            headerLines = headerSection.split("\r\n");
            String statusLine = headerLines[0];
            int statusCode = parseStatusCode(statusLine);

            cookieManager.processHeaders(host, headerLines);

            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("HTTP " + statusCode + ": " + statusLine);
            }

            boolean chunked = false;
            int contentLength = -1;
            for (int i = 1; i < headerLines.length; i++) {
                String lower = headerLines[i].toLowerCase();
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    chunked = true;
                } else if (lower.startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(headerLines[i].substring(15).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }

            InputStream bodyStream = in;
            if (chunked) {
                bodyStream = new ChunkedInputStream(in);
            } else if (contentLength > 0) {
                bodyStream = new FixedLengthInputStream(in, contentLength);
            }

            ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = bodyStream.read(buf)) != -1) {
                bodyBuf.write(buf, 0, n);
            }

            return bodyBuf.toByteArray();

        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private Map<String, String> parseDigestChallenge(String challenge) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] parts = challenge.split(",");
        for (String part : parts) {
            part = part.trim();
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            map.put(key, value);
        }
        return map;
    }

    private String buildDigestResponse(String method, String uri, Map<String, String> challenge) throws Exception {
        String realm = challenge.get("realm");
        String nonce = challenge.get("nonce");
        String opaque = challenge.get("opaque");
        String qop = challenge.get("qop");
        String algorithm = challenge.get("algorithm");
        if (algorithm == null || algorithm.isEmpty()) {
            algorithm = "MD5";
        }

        String digestAlgo;
        if ("SHA-256".equals(algorithm) || "SHA256".equalsIgnoreCase(algorithm)) {
            digestAlgo = "SHA-256";
        } else {
            digestAlgo = "MD5";
        }

        MessageDigest digester = MessageDigest.getInstance(digestAlgo);

        String ha1 = bytesToHex(digester.digest(("se0316" + ":" + realm + ":SILrMEPBmJuhomxWkfm3JalqHX2Eheg1YhlEZiMh8II")
                .getBytes(StandardCharsets.UTF_8)));
        String ha2 = bytesToHex(digester.digest((method + ":" + uri).getBytes(StandardCharsets.UTF_8)));

        byte[] cnonceBytes = new byte[16];
        new SecureRandom().nextBytes(cnonceBytes);
        String cnonce = bytesToHex(cnonceBytes);
        String nc = "00000001";

        String response;
        if ("auth".equals(qop) || "auth-int".equals(qop)) {
            response = bytesToHex(digester.digest((ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2)
                    .getBytes(StandardCharsets.UTF_8)));
        } else {
            response = bytesToHex(digester.digest((ha1 + ":" + nonce + ":" + ha2).getBytes(StandardCharsets.UTF_8)));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("username=\"se0316\"");
        sb.append(", realm=\"").append(realm).append("\"");
        sb.append(", nonce=\"").append(nonce).append("\"");
        sb.append(", uri=\"").append(uri).append("\"");
        sb.append(", response=\"").append(response).append("\"");
        if (algorithm != null && !algorithm.isEmpty()) {
            sb.append(", algorithm=").append(algorithm);
        }
        if (opaque != null && !opaque.isEmpty()) {
            sb.append(", opaque=\"").append(opaque).append("\"");
        }
        if (qop != null && !qop.isEmpty()) {
            sb.append(", qop=").append(qop);
            sb.append(", nc=").append(nc);
            sb.append(", cnonce=\"").append(cnonce).append("\"");
        }
        return sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static int parseStatusCode(String statusLine) {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); }
            catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private int chunkRemaining = 0;
        private boolean eof = false;

        ChunkedInputStream(InputStream in) { this.in = in; }

        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n == -1 ? -1 : (b[0] & 0xFF);
        }

        public int read(byte[] buf, int off, int len) throws IOException {
            if (eof) return -1;
            if (chunkRemaining <= 0) {
                String line = readLine();
                if (line == null || line.isEmpty()) { eof = true; return -1; }
                try { chunkRemaining = Integer.parseInt(line.trim(), 16); }
                catch (NumberFormatException e) { eof = true; return -1; }
                if (chunkRemaining == 0) { readLine(); eof = true; return -1; }
            }
            int toRead = Math.min(len, chunkRemaining);
            int n = in.read(buf, off, toRead);
            if (n > 0) {
                chunkRemaining -= n;
                if (chunkRemaining == 0) readLine();
            }
            return n;
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int prev = -1, cur;
            while ((cur = in.read()) != -1) {
                if (prev == '\r' && cur == '\n') break;
                if (prev != -1) line.write(prev);
                prev = cur;
            }
            if (prev == -1 && line.size() == 0) return null;
            if (prev != -1 && cur == -1) line.write(prev);
            return line.toString("UTF-8");
        }
    }

    private static class FixedLengthInputStream extends InputStream {
        private final InputStream in;
        private int remaining;

        FixedLengthInputStream(InputStream in, int length) { this.in = in; this.remaining = length; }

        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b != -1) remaining--;
            return b;
        }

        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = Math.min(len, remaining);
            int n = in.read(buf, off, toRead);
            if (n > 0) remaining -= n;
            return n;
        }
    }



    private static class CookieManager {
        private final Map<String, List<String>> cookies = new HashMap<>();

        void clear() { cookies.clear(); }

        void processHeaders(String host, String[] headers) {
            for (String line : headers) {
                String lower = line.toLowerCase();
                if (lower.startsWith("set-cookie:")) {
                    String cookieLine = line.substring(11).trim();
                    String[] parts = cookieLine.split(";");
                    if (parts.length > 0) {
                        String kv = parts[0].trim();
                        int eq = kv.indexOf('=');
                        if (eq > 0) {
                            String key = kv.substring(0, eq);
                            String value = kv.substring(eq + 1);
                            cookies.computeIfAbsent(host, k -> new ArrayList<>()).add(key + "=" + value);
                        }
                    }
                }
            }
        }

        String getCookies(String host) {
            List<String> list = cookies.get(host);
            if (list == null || list.isEmpty()) return "";
            return String.join("; ", list);
        }
    }
}
