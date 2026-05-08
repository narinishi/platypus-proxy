# Proxy Handler Protocol / Behavioral Differences

## 1. No HTTP/2 tunnel support

Go has a `proxyh2()` path for HTTP/2 CONNECT. Java has no protocol version check at all.

**Go** (`proxyhandler.go:54-77`):

```go
if req.ProtoMajor == 0 || req.ProtoMajor == 1 {
    localconn, _, err := hijack(wr)
    if err != nil {
        // ...
    }
    defer localconn.Close()
    fmt.Fprintf(localconn, "HTTP/%d.%d 200 OK\r\n\r\n", req.ProtoMajor, req.ProtoMinor)
    proxy(req.Context(), localconn, conn)
} else if req.ProtoMajor == 2 {
    wr.Header()["Date"] = nil
    wr.WriteHeader(http.StatusOK)
    flush(wr)
    proxyh2(req.Context(), req.Body, wr, conn)
} else {
    s.logger.Error("Unsupported protocol version: %s", req.Proto)
    http.Error(wr, "Unsupported protocol version.", http.StatusBadRequest)
}
```

**Status:** **Still missing**

---

## 2. Request validation missing

Go checks for empty `Host`/`Scheme` before dispatching. Java blindly routes.

**Go** (`proxyhandler.go:103-111`):

```go
func (s *ProxyHandler) ServeHTTP(wr http.ResponseWriter, req *http.Request) {
    isConnect := strings.ToUpper(req.Method) == "CONNECT"
    if (req.URL.Host == "" || req.URL.Scheme == "" && !isConnect) && req.ProtoMajor < 2 ||
        req.Host == "" && req.ProtoMajor == 2 {
        http.Error(wr, BAD_REQ_MSG, http.StatusBadRequest)
        return
    }
    // ...
}
```

**Status:** **Still missing**

---

## 3. Status code distinction lost

Go returns 500 for hijack/transport errors, 502 for dial failures. Java returns 502 for everything in `ProxyHandler.java`.

**Go** (`proxyhandler.go:49-51,89-92`):

```go
// CONNECT dial failure → 502
http.Error(wr, "Can't satisfy CONNECT request", http.StatusBadGateway)
// Hijack failure → 500
http.Error(wr, "Can't hijack client connection", http.StatusInternalServerError)
// HTTP fetch error → 500
http.Error(wr, "Server Error", http.StatusInternalServerError)
```

**Status:** **Still different** — Java returns 500 for HTTP fetch errors, 502 for CONNECT dial failures.

---

## Resolved Issues

All other proxy handler issues have been fixed. See [07-pitfalls.md](07-pitfalls.md) for:
- Hop-by-hop header removal (case-insensitive)
- Multi-value response headers preserved
- Request body forwarding (unconditional by Content-Length)
- Auth header sent unconditionally
- CONNECT tunnel support (NIO channel + bidirectional copy)
- Connection pooled (per-address `HttpDialer` replacing literal `"upstream"` pool)
- `\r\n\r\n` header terminator scan (scan across full buffer, not just end)
- Context cancellation (120s timeout on tunnel)
- `flush()` after response headers
- Content-Type overwrite fixed
- CONNECT 200 sent via Grizzly API instead of raw socket
- `getWriter()` vs `getOutputStream()` conflict risk
