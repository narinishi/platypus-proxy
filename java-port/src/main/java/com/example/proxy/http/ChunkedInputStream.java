package com.example.proxy.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

class ChunkedInputStream extends InputStream {
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