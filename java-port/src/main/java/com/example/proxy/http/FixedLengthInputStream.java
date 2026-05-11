package com.example.proxy.http;

import java.io.IOException;
import java.io.InputStream;

class FixedLengthInputStream extends InputStream {
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