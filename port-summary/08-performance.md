# Performance and Behavioral Enhancements

*Created 2026-05-08* — *Updated 2026-05-11*

Enhancements applied to the Java port that have **no equivalent** in the original Go code. These are **intentional additions**, not bugs. Some improve performance, others add capabilities absent from Go.

> **Proposal:** Consider renaming this file to `08-enhancements.md` and moving to `port-enhancements/` directory, since it documents Java-only features rather than porting gaps.

## Performance Optimizations

### 1. Virtual Threads for CONNECT Tunnel Copy

**File:** `ProxyHandler.java` (handleTunnel)

- **Go:** Uses OS goroutines (`go func()`) for bidirectional copy.
- **Java (original):** `new Thread(() -> {...}).start()` — full OS thread (~1MB stack) per direction.
- **Java (fixed):** `Thread.ofVirtual().name("proxy-c2u").start(() -> {...})` — JVM-managed virtual threads, matching Go's cost model.

**Requirement:** JDK 21+. Project uses JDK 25.

### 2. Removed Per-Write `flush()` in Tunnel Copy

**File:** `ProxyHandler.java` — both `proxy-c2u` and `proxy-u2c` threads.

- **Go:** `io.Copy()` — no flush.
- **Java (original):** `out.write(buf, 0, n); out.flush()` after every 128KB chunk.
- **Java (fixed):** `out.write(buf, 0, n)` only — kernel handles buffering via Nagle.

### 3. Removed `flush()` After HTTP Request Header Write

**File:** `ProxyHandler.java` (handleRequest)

- **Go:** `http.Transport` serializes into a `bufio.Writer`.
- **Java (original):** `write(); flush()`.
- **Java (fixed):** `write()` only.

### 4. Removed `sendUrgentData` Liveness Check

**File:** `ProxyHandler.java` (HttpDialer.borrow)

- **Go:** No liveness check — stale connection fails on first `write()`.
- **Java (original):** `socket.sendUrgentData(0)` — 1 RTT per pooled borrow.
- **Java (fixed):** Returns socket directly. Stale connections detected on write failure; retry uses `borrowFresh()`.

### 5. Added `borrowFresh()` Method

**File:** `ProxyHandler.java` (HttpDialer)

Bypasses pool entirely, always dials fresh. Used on socket-stale retry path.

---

## Behavioral Enhancements (Java-Only Features)

These features exist in the Java port but have **no equivalent** in the original Go code.

### 6. AsyncLog — Asynchronous Logging

**File:** `AsyncLog.java`

- **Go:** Logs synchronously via `log.Print` / `ConditionalLogger` wrapping `log.Logger`.
- **Java:** `AsyncLog` wraps `java.util.logging.Logger` and offloads I/O to a background single-thread executor. Prevents logging I/O from blocking request-handling threads.
- Also provides a `reset()` method for log level reconfiguration at runtime.

### 7. SOCKS5 Outbound Proxy Support

**Files:** `Socks5ProxyDialer.java`, `OutboundProxyDialer.java`

- **Go:** `xproxy.FromURL` with `RegisterDialerType("http", ...)` and `RegisterDialerType("https", ...)` only. SOCKS5 URL parsing is documented in help text but **not implemented** — `xproxy` would fail with `"unsupported proxy type"`.
- **Java:** Full RFC 1928/1929 SOCKS5 implementation in `Socks5ProxyDialer`:
  - Supports `socks5://` (DNS resolved locally) and `socks5h://` (DNS delegated to proxy)
  - GSSAPI and username/password authentication methods
  - TCP connect and UDP associate commands
  - Bound address/port extraction for UDP

### 8. TunnelSocket — PushbackInputStream Wrapper

**File:** `TunnelSocket.java`

- **Go:** Returns raw `net.Conn` from `DialContext`. CONNECT response is parsed with a one-off `readResponse()` that reads byte-by-byte; no general peek/unread mechanism.
- **Java:** `ProxyDialer` wraps the tunnel socket in a `TunnelSocket` that overrides `getInputStream()` to return a `PushbackInputStream`. This ensures any bytes read ahead during CONNECT response parsing are not lost when the caller (connection pool or caller) reads from the socket.

### 9. gzip/deflate HTTP Response Decompression

**File:** `HolaApiClient.java` (lines 143-172)

- **Go:** `do_req()` reads raw response body via `ioutil.ReadAll` — no decompression. No `Accept-Encoding` header set.
- **Java:** Detects `Content-Encoding: gzip` and `Content-Encoding: deflate` in API responses, wraps the input stream in `GZIPInputStream` / `InflaterInputStream` before reading. Automatically handles compressed API responses.

### 10. FixedEndpointDialer — Separate Request Dialer

**File:** `FixedEndpointDialer.java`

- **Go:** Request dialer is configured inline in `http.Transport.DialContext` — the function literal captures the endpoint address and dialer. No separate class.
- **Java:** Extracts the request dialer into its own `FixedEndpointDialer` class. Always connects to the proxy endpoint address, optionally wraps in TLS. Used for plain HTTP requests (non-CONNECT) that go through the HTTP proxy.

### 11. AdvancedResolver DNS Enhancements

**File:** `AdvancedResolver.java`

These DNS features are completely absent from Go's `dnsresolver.go`:

| Feature | Java | Go |
|---------|------|-----|
| DNSSEC DO bit | Sets EDNS(0) DO bit via boolean parameter | Not set |
| CNAME chasing | Follows CNAME chains up to depth 5 with loop detection | No CNAME chasing |
| Stale-while-revalidate cache | Serves stale entries within 1-hour window, triggers background refresh | No caching at all |
| Negative DNS caching | Caches NXDOMAIN with SOA-derived TTL (clamped min/max) | No caching |
| Semaphore-limited concurrency | Max 10 concurrent DoH requests via `Semaphore` | Unlimited goroutines |
| Dedicated retry executor | Separate `ScheduledExecutorService` for retry tasks | N/A |
| LRU cache eviction | Evicts oldest entries when cache > 1024 entries | N/A |

---

## Summary

| # | Enhancement | Category | Impact |
|---|---|
| 1 | Virtual threads | Performance | OS thread savings for CONNECT tunnels |
| 2 | Removed flush() per chunk | Performance | No forced TCP push per 128KB |
| 3 | Removed flush() after headers | Performance | No forced push for 1KB headers |
| 4 | Removed sendUrgentData check | Performance | Eliminates RTT per pooled borrow |
| 5 | borrowFresh() method | Performance | Guarantees fresh connection on retry |
| 6 | AsyncLog | Behavioral | Async logging offloads I/O |
| 7 | SOCKS5 proxy support | Behavioral | Full RFC 1928/1929 implementation |
| 8 | TunnelSocket | Behavioral | PushbackInputStream prevents byte loss |
| 9 | gzip/deflate decompression | Behavioral | Handles compressed API responses |
| 10 | FixedEndpointDialer | Behavioral | Clean separation of request dialer |
| 11 | AdvancedResolver DNS | Behavioral | CNAME chasing, stale cache, negative cache, DNSSEC, semaphore, LRU |
