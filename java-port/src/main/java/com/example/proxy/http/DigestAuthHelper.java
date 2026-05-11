package com.example.proxy.http;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class DigestAuthHelper {

    private final String username;
    private final String password;

    public DigestAuthHelper(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public byte[] execute(String method, String urlStr, byte[] postData, Supplier<String> userAgent) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", userAgent.get());
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(postData != null && postData.length > 0);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        if (postData != null && postData.length > 0) {
            OutputStream os = conn.getOutputStream();
            os.write(postData);
            os.flush();
        }

        int status = conn.getResponseCode();

        if (status == 401) {
            String authHeader = conn.getHeaderField("WWW-Authenticate");
            if (authHeader == null || !authHeader.startsWith("Digest ")) {
                conn.disconnect();
                throw new java.io.IOException("Expected Digest auth challenge, got: " + authHeader);
            }
            conn.disconnect();

            Map<String, String> challenge = parseDigestChallenge(authHeader.substring(7));
            String digestAuth = buildDigestResponse(method, url.getPath(), challenge);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", userAgent.get());
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Digest " + digestAuth);
            conn.setDoOutput(postData != null && postData.length > 0);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            if (postData != null && postData.length > 0) {
                OutputStream os = conn.getOutputStream();
                os.write(postData);
                os.flush();
            }

            status = conn.getResponseCode();
        }

        if (status < 200 || status >= 300) {
            String body = readBody(conn.getErrorStream());
            conn.disconnect();
            throw new java.io.IOException("HTTP " + status + ": " + body);
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        InputStream in = conn.getInputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        in.close();
        conn.disconnect();
        return buf.toByteArray();
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

        MessageDigest md5 = MessageDigest.getInstance("MD5");

        String ha1 = hex(md5.digest((username + ":" + realm + ":" + password).getBytes(StandardCharsets.UTF_8)));

        String ha2 = hex(md5.digest((method + ":" + uri).getBytes(StandardCharsets.UTF_8)));

        String cnonce = hex(Long.toHexString(new SecureRandom().nextLong()).getBytes(StandardCharsets.UTF_8));
        String nc = "00000001";

        String response;
        if ("auth".equals(qop) || "auth-int".equals(qop)) {
            response = hex(md5.digest((ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2)
                    .getBytes(StandardCharsets.UTF_8)));
        } else {
            response = hex(md5.digest((ha1 + ":" + nonce + ":" + ha2).getBytes(StandardCharsets.UTF_8)));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("username=\"").append(username).append("\"");
        sb.append(", realm=\"").append(realm).append("\"");
        sb.append(", nonce=\"").append(nonce).append("\"");
        sb.append(", uri=\"").append(uri).append("\"");
        sb.append(", response=\"").append(response).append("\"");
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

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static String readBody(InputStream stream) throws java.io.IOException {
        if (stream == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = stream.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toString("UTF-8");
    }
}
