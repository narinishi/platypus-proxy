package com.example.proxy.dialer;

import java.io.IOException;

public class UpstreamBlockedError extends IOException {
    public UpstreamBlockedError(String message) {
        super(message);
    }
}
