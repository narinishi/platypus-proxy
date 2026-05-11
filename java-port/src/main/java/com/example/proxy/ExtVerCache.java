package com.example.proxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class ExtVerCache {

    private static final Path CACHE_FILE = Paths.get(".extver");
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000;

    public static class CachedVersions {
        public final long timestamp;
        public final String chromeVer;
        public final String extVer;

        public CachedVersions(long timestamp, String chromeVer, String extVer) {
            this.timestamp = timestamp;
            this.chromeVer = chromeVer;
            this.extVer = extVer;
        }
    }

    public static CachedVersions read() {
        if (!Files.exists(CACHE_FILE)) {
            return null;
        }
        try {
            String line = new String(Files.readAllBytes(CACHE_FILE), StandardCharsets.UTF_8).trim();
            if (line.isEmpty()) return null;
            String[] parts = line.split(",");
            if (parts.length < 3) return null;
            long timestamp = Long.parseLong(parts[0].trim());
            long age = System.currentTimeMillis() - timestamp * 1000L;
            if (age >= CACHE_TTL_MS) return null;
            return new CachedVersions(timestamp, parts[1].trim(), parts[2].trim());
        } catch (Exception e) {
            return null;
        }
    }

    public static void write(String chromeVer, String extVer) throws IOException {
        long now = System.currentTimeMillis() / 1000L;
        String line = now + "," + chromeVer + "," + extVer + System.lineSeparator();
        Files.write(CACHE_FILE, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
