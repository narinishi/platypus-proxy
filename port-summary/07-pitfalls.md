# Pitfalls and Issues Addressed During Porting

*Created 2026-05-07* — *Updated 2026-05-08*

Consolidated reference for every issue that was identified and fixed during the Go-to-Java port. The gap-analysis docs (01–06) track only **unresolved** issues; this file is the canonical record of all resolved problems.

---

## Difficulty Interpreting the Go Source (orig-go-src)

Several factors made it difficult to understand critical details in the Go proxy handler code, despite the source being available:

1. **Indirection through `http.Transport`:** Go's `HandleRequest` calls `httptransport.RoundTrip(req)` and the actual network connection behavior is buried in Go's standard library. The fact that `RoundTrip` calls `DialContext` for every new origin host, and that `DialContext` is the custom `ProxyDialer` which opens a CONNECT tunnel — this is invisible at the `HandleRequest` level. You have to trace through: `Handler` → `http.Transport` → `DialContext` → `ProxyDialer.dial()`. The Java port's `handleRequest` initially tried to open a raw TCP connection to the proxy endpoint and send the HTTP request directly, which is fundamentally wrong for Opera.

2. **Connection pooling is per-origin in Go:** Go's `http.Transport` maintains connection pools keyed by origin address (`host:port`). When you call `RoundTrip` for `http://speedtest.tele2.net/10MB.zip`, Go's transport creates a pool keyed on `speedtest.tele2.net:80`. The first call calls `DialContext("tcp", "speedtest.tele2.net:80")` which creates a CONNECT tunnel; subsequent requests reuse the pooled tunnel. The Java port's original `ConnectionPool` treated all connections as interchangeable (single pool keyed on `"upstream"`), which only works for providers like Hola where every request goes to the same proxy endpoint without CONNECT tunneling.

3. **`BufferedInputStream.mark()` size limitation:** The `readStatusAndHeaders` method used `in.mark(8192)` with an 8192-byte buffer, and checked for `\r\n\r\n` only at the last 4 bytes via `hasCRLFCRLF`. This works when the read stops at the header boundary (small content, or TCP segmentation aligns), but fails when body data follows in the same read — the `\r\n\r\n` is somewhere in the middle of the buffer, and the check at `buf[total-4..total-1]` misses it, returning null (triggering a 502 Bad Gateway). Go uses `bufio.Reader` which has a different buffering model and is not susceptible to this bug.

---

## Response Handling (ProxyHandler)

### 1. BufferedReader consumed body bytes

**Problem:** `handleRequest()` used `BufferedReader` + `InputStreamReader` to read the upstream HTTP response status line and headers. `BufferedReader` buffers up to 8KB internally — any bytes read into its buffer beyond the `\r\n\r\n` header boundary were lost because the subsequent body copy operated on the raw `InputStream`, not the `BufferedReader`.

**Symptom:** Upstream response body was truncated or empty. Client timed out waiting for body content.

**Fix:** Replaced `BufferedReader` with `BufferedInputStream`. New `readStatusAndHeaders()` method:
1. Calls `in.mark(8192)` before reading
2. Reads raw bytes until `\r\n\r\n` is found
3. Calls `in.reset()` then `in.skip(headerEnd)` to position stream just after headers
4. Parses status line and headers from the buffered byte array
5. The same `BufferedInputStream` is passed to `copyStream()` for the body — no bytes are lost

### 2. Missing `flush()` after response headers

**Problem:** Go's `HandleRequest` calls `flush(wr)` between `WriteHeader()` and `copyBody()`. Java skipped this flush. Grizzly's `Response.getOutputStream()` may buffer headers internally, delaying the HTTP status/headers until body data arrives.

**Fix:** Added `respOut.flush()` immediately after setting status and headers, before reading/copying the response body.

### 2a. `\r\n\r\n` header terminator scan found terminator only at buffer end

**Problem:** `readStatusAndHeaders()` used `hasCRLFCRLF(buf, total)` which checked only the **last 4 bytes** of accumulated data for `\r\n\r\n`. When the upstream HTTP response contained headers followed immediately by body data (e.g., a 10MB zip file), the first `read()` call filled most of the 8192-byte buffer with body bytes. The `\r\n\r\n` terminator was at position ~226 (end of headers), but the check only looked at `buf[8188..8191]` (null bytes from the zip content), so it was never found. The method returned null, causing a 502 Bad Gateway response.

**Symptom:** HTTP proxy requests returned 502 when the response had a body that started immediately after headers (most responses with Content-Length).

**Fix:** Replaced `hasCRLFCRLF` end-of-buffer check with a scan across the entire accumulated buffer:

```java
for (int i = 0; i <= total - 4; i++) {
    if (buf[i] == '\r' && buf[i + 1] == '\n' 
        && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
        headerEnd = i + 4;
        break;
    }
}
```

Also increased `in.mark()` size from 8192 to 65536 to handle larger header blocks safely.

### 3. `setContentType()` overwrote upstream Content-Type

**Problem:** `response.setContentType("application/octet-stream")` was called *after* copying response headers via `addHeader()`. Grizzly's `setContentType()` replaces any existing `Content-Type` header, so every response was returned with `Content-Type: application/octet-stream` regardless of the upstream value.

**Fix:** Removed the `setContentType("application/octet-stream")` call. Content type is now set only from upstream response headers.

### 4. CONNECT 200 sent via Grizzly API instead of raw socket

**Problem:** CONNECT handler used `response.setStatus(200)` + `response.getWriter().flush()` + `response.suspend()`. The 200 response may not have been committed to the wire due to Grizzly's buffering and suspend lifecycle.

**Fix:** After obtaining the raw `SocketChannel` from Grizzly's `FilterChainContext`, the 200 response is written directly to the socket output stream:
```java
clientOut.write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
clientOut.flush();
```
Matches Go's `fmt.Fprintf(localconn, "HTTP/1.1 200 OK\r\n\r\n")`.

### 5. `getWriter()` vs `getOutputStream()` conflict risk

**Problem:** Grizzly's `Response` prohibits mixing `getWriter()` and `getOutputStream()`. Error paths called `getWriter()` while the main code path called `getOutputStream()`, risking `IllegalStateException`.

**Fix:** Error paths guard with `!response.isCommitted()`. The CONNECT path no longer calls `getWriter()`. The main code path only calls `getOutputStream()`.

### 6. Connection pool `borrow()` passed literal `"upstream"` as address (later fixed in Enhancement 6)

**Problem:** `ConnectionPool.borrow()` fallback path called `dialer.dial("tcp", "upstream")` — the literal string `"upstream"` as the address. Harmless for Hola (which ignores the address), but broke Opera where `ProxyDialer` uses the address to create a CONNECT tunnel to the target origin.

**Symptom:** `--test file` with Opera returned HTTP 502 — `handleRequest` dialed "upstream" instead of `speedtest.tele2.net:80`, so the CONNECT tunnel was created to the wrong address.

**Fix:** Replaced single-pool `ConnectionPool` with `HttpDialer` that dials the actual target host:port. Uses per-address pools (`ConcurrentMap<String, Deque<Socket>>`). `handleRequest` now passes `hostOnly + ":" + port` as the dial address. Matches Go's `http.Transport` which pools connections keyed by origin address.

---

## Connection Pooling

### 7. HTTP connection pooling added

**Problem:** Original Java implementation opened a new TCP connection to the Hola endpoint for every HTTP request. Go's `http.Transport` reused idle connections via its built-in pool (`MaxIdleConns=100`, `IdleConnTimeout=90s`).

**Fix:** Added `ConnectionPool` inner class in `ProxyHandler.java`:
- `borrow()` — returns an idle socket (tested via `sendUrgentData(0)`) or dials a new one
- `release()` — returns socket to pool (up to 100 connections)
- `invalidate()` — removes and closes a dead socket
- `evictStale()` — background cleaner checks liveness every 45s via `sendUrgentData(0)`

### 8. Deadline clearing / stale connection handling

**Problem:** Go clears deadlines after hijack (`conn.SetDeadline(emptytime)`). Without this, long-lived tunnels could timeout. Java's `ConnectionPool` handles stale connections via the background eviction thread.

**Fix:** `evictStale()` uses `sendUrgentData(0)` as an active liveness probe. Stale connections are removed from the pool and closed. `borrow()` also tests liveness before returning a pooled socket.

---

## Hop-by-Hop Header Handling

### 9. Case-insensitivity in hop-by-hop header removal

**Problem:** Original Java used `HashSet` with case-sensitive `contains()`. HTTP headers are case-insensitive per RFC 7230, so headers like `connection` or `Connection` were not removed.

**Fix:** `ProxyHandler.java:24-32` now uses `TreeSet<>(String.CASE_INSENSITIVE_ORDER)`:
```java
Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
s.addAll(Arrays.asList("Connection", "Keep-Alive", "Proxy-Authenticate", ...
```

### 10. Multi-value response headers preserved

**Problem:** Original code used `response.setHeader()` which overwrites previous values, losing duplicate headers.

**Fix:** Uses `response.addHeader()` to preserve all values:
```java
for (Map.Entry<String, List<String>> h : respHeaders.entrySet()) {
    for (String v : h.getValue()) {
        response.addHeader(h.getKey(), v);
    }
}
```

### 11. Request body forwarding for all HTTP methods

**Problem:** Original code only forwarded body for POST/PUT/PATCH. Some clients (e.g., HTTP DELETE with body) would lose the request body.

**Fix:** Body forwarding is now unconditional — controlled by `Content-Length` only:
```java
long contentLen = parseContentLength(request);
if (contentLen > 0) {
    copyStream(request.getInputStream(), upOut, contentLen);
}
```

### 12. Auth header sent unconditionally

**Problem:** Original code conditionally sent the `Proxy-Authorization` header, sometimes omitting it.

**Fix:** `Proxy-Authorization` is now always sent when the auth provider returns a non-null value:
```java
String auth = authProvider.get();
if (auth != null && !auth.isEmpty()) {
    req.append("Proxy-Authorization: ").append(auth).append("\r\n");
}
```
Go always sends the header even if empty. Java omits only when null/empty, functionally equivalent since the auth provider always returns a non-empty value after setup.

---

## CONNECT Tunnel

### 13. CONNECT tunnel support added

**Problem:** Original Java had no CONNECT tunnel implementation. Go's `HandleTunnel` hijacks the client connection and proxies bidirectionally.

**Fix:** Full CONNECT tunnel via Grizzly NIO channel access + `proxyBidirectional()`:
```java
Socket upstream = tunnelDialer.dial("tcp", target);
// Access underlying NIO SocketChannel for raw byte streaming
FilterChainContext fcc = (FilterChainContext) request.getContext();
TCPNIOConnection tcpConn = (TCPNIOConnection) fcc.getConnection();
SocketChannel channel = (SocketChannel) tcpConn.getChannel();
proxyBidirectional(clientIn, upstream.getInputStream(), clientOut, upstream.getOutputStream());
```

### 14. Context cancellation on tunnels

**Problem:** Go's `proxy(ctx, ...)` selects on `ctx.Done()` to force-close on cancellation. Java had no timeout or cancellation, allowing tunnels to hang indefinitely.

**Fix:** `proxyBidirectional()` uses `CountDownLatch` with a 120-second timeout:
```java
CountDownLatch done = new CountDownLatch(2);
// ... launch copy threads that countDown when done ...
try { done.await(120, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
```

---

## Outbound Proxy

### 15. Outbound proxy support added

**Problem:** Go supports `-proxy` flag for HTTP/HTTPS/SOCKS5 proxy chaining. Java had no outbound proxy support.

**Fix:** Added `OutboundProxyDialer` (HTTP/HTTPS CONNECT) and `Socks5ProxyDialer` (SOCKS5). Both implement `ContextDialer` and wrap TLS when needed.

---

## Retry and Fallback

### 16. RetryDialer wrapping added

**Problem:** Go wraps the `handlerDialer` in `NewRetryDialer` to rescue blocked hosts via DNS re-resolution. Java had no retry wrapper.

**Fix:** Added `RetryDialer` that wraps `ProxyDialer`:
- On `UpstreamBlockedError`, resolves hostname via `FastResolver`
- Retries with resolved IP addresses
- Preserves network family (`tcp4`/`tcp6`)

### 17. RetryDialer network mapping fixed

**Problem:** Network family resolution (`tcp4`/`tcp6`/`ip4`/`ip6`) was not properly mapped.

**Fix:** `resolveNetworkFamily()` + `familyMatch()` correctly map network strings.

### 18. Fallback proxy system implemented

**Problem:** Go's `EnsureTransaction` fetches fallback proxy config from multiple URLs (Dropbox, S3) and retries through each agent. Java had no fallback mechanism.

**Fix:** Full fallback proxy system:
- `HolaApiClient.fetchFallbackPayload()` — randomly selects from `FALLBACK_CONF_URLS`, fetches via HTTP
- `decodeFallbackConfig()` — 3-byte rotation (last 3 → front) + base64 decode + JSON `FallbackConfig`
- `getFallbackProxies()` — thread-safe caching with TTL
- `HolaCredentialService.fetchTunnelsViaFallback()` — iterates fallback agents, creates proxied client for each

### 19. retryPolicy wrapper added

**Problem:** Go wraps init steps (Chrome version, ext version) with configurable retry+backoff. Java had no retry wrapper.

**Fix:** Added `RetryPolicy.java` with configurable retries + interval. Wraps Chrome version detection, extension version detection, and credential service startup.

---

## API Client

### 20. `tunnels` always null fixed

**Problem:** `ProxyApplication` did not capture `tunnels` from `AuthResult`, so endpoint selection always received null.

**Fix:** `ProxyApplication.java` now captures `tunnels` from `AuthResult` and passes it to `selectEndpoint()`.

### 21. Trial port logic inverted fixed

**Problem:** `selectPort()` returned wrong port for trial mode — direct port was returned when trial was requested and vice versa.

**Fix:** `selectPort()` correctly returns `tunnels.getPort().getTrial()` when trial is true, matching Go:
```java
case "direct": case "lum": case "pool": case "virt":
    return trial ? tunnels.getPort().getTrial() : tunnels.getPort().getDirect();
```

### 22. Country suffixes missing from zGetTunnels

**Problem:** Go formats country parameter as `.pool_lum_<cc>_shared`, `.pool_virt_pool_<cc>`, etc. Java was using the bare country code.

**Fix:** `HolaApiClient.java` now correctly formats per proxy type.

### 23. `ping_id` parameter missing

**Problem:** Go includes `ping_id=random_float` in `zGetTunnels` API call. Java omitted it.

**Fix:** Added `params.append("&ping_id=").append(Math.random())`.

### 24. `background_init` POST body format

**Problem:** Go sends `background_init` data as POST body (`login=1&ver=...`). Java was sending it as query parameters.

**Fix:** `HolaApiClient.java` now sends data as POST body string.

### 25. HTTP status acceptance range

**Problem:** Original Java only accepted 200. Go accepts 200, 201, 202, and 204.

**Fix:** `HolaApiClient.java` now accepts any 2xx status code.

### 26. UUID format mismatch

**Problem:** Java was using UUID with dashes. Go uses 32-char hex string (no dashes).

**Fix:** `HolaCredentialService.generateUserUuid()` produces 32-char hex string matching Go's `hex.EncodeToString`.

### 27. Login template format

**Problem:** Login template was missing the `"uuid-"` prefix required by the Hola API.

**Fix:** Template format is now `"user-uuid-uuid}-is_prem-0"`.

### 28. Auth header prefix case

**Problem:** Java used `"Basic "` (titlecase). Go uses `"basic "` (lowercase).

**Fix:** Changed to `"basic "` lowercase.

### 29. Empty response check added

**Problem:** Go throws `errors.New("empty response")` when `zGetTunnels` returns null/empty ipList. Java silently returned null, leading to NullPointerException downstream.

**Fix:** `zGetTunnels` throws `IOException("empty response")` on null/empty ipList, matching Go's behavior.

---

## DNS / Resolver

### 30. `PlainDnsResolver.parseResponse` fixed

**Problem:** DNS wire format parsing was incorrect — byte offsets for A/AAAA records were miscalculated.

**Fix:** `DnsResolverFactory.parseDnsResponse()` correctly:
- Skips the question section (name compression aware)
- Parses each answer record's type, class, TTL, and rdlength
- Extracts A records (type 1, 4 bytes) and AAAA records (type 28, 16 bytes)
- Uses `InetAddress.getByAddress()` with raw address bytes

### 31. DoT resolver implemented

**Problem:** DNS-over-TLS resolver was a no-op that fell back to system DNS.

**Fix:** Real DoT resolver in `DnsResolverFactory.java`:
- Establishes TLS connection to configured DNS server
- Uses length-prefixed wire format (2 bytes length + DNS query) per RFC 7858
- Sends standard A query and parses response via `parseDnsResponse()`

### 32. DNS port defaults per scheme

**Problem:** Go defaults ports per scheme (53/80/443/853). Java used a single default.

**Fix:** Port defaults:
| Scheme | Default port |
|--------|-------------|
| `dns` / `udp` | 53 |
| `tcp` | 53 |
| `http` | 80 |
| `https` / `doh` | 443 |
| `tls` / `dot` | 853 |

### 33. FastResolver concurrent racing

**Problem:** Go races multiple DNS queries concurrently and takes the first success. Java resolved sequentially.

**Fix:** Uses `CachedThreadPool` + `CountDownLatch` to race queries concurrently.

---

## Commands and CLI

### 34. `list-countries` command implemented

**Problem:** Original Java had a stub. Go fetches `vpn_countries.json` and displays ISO3166 names.

**Fix:** `listCountries()` fetches `https://client.hola.org/client_cgi/vpn_countries.json`, normalizes `uk→gb`, displays tab-separated code/name from `Iso3166.CODE_TO_COUNTRY`.

### 35. `list-proxies` command implemented

**Problem:** Original Java had a stub. Go calls `backgroundInit` + `zGetTunnels` and prints credentials + proxy listing.

**Fix:** `listProxies()` calls `backgroundInit` + `zGetTunnels`, computes auth header, prints login/password/auth-header, then CSV listing of tunnels with ports and vendor.

### 36. `hideSNI` support implemented

**Problem:** Go cleanly sets `ServerName: ""` in `tls.Config`. Java needed to clear SNI via a different mechanism.

**Fix:** Reflection-based `SSLParameters.setServerNames(emptyList())` — bypasses JDK guard preventing empty serverNames by invoking the underlying method directly.

### 37. Rotation scheduling fixed

**Problem:** Java used `scheduleAtFixedRate`. Go uses `time.NewTicker` which is equivalent to `scheduleWithFixedDelay` (no overlap).

**Fix:** Changed to `scheduleWithFixedDelay` to prevent rotation overlap.

---

## Go Reference

| # | Go file | Go function/line(s) | Java file | Java function/line(s) |
|---|---|---|
| 1 | `proxyhandler.go` | `HandleRequest` (80-101) | `ProxyHandler.java` | `handleRequest()`, `readStatusAndHeaders()` |
| 1a | `proxyhandler.go:80-101` | `RoundTrip` returns parsed response; Go's `ReadResponse` handles header parsing natively | `ProxyHandler.java` | `readStatusAndHeaders()` — Java reimplements HTTP response parsing; bug was end-of-buffer-only `\r\n\r\n` scan |
| 2 | `utils.go:289-296` | `flush(wr)` | `ProxyHandler.java` | `respOut.flush()` after headers |
| 3 | `proxyhandler.go:96-100` | `delHopHeaders` → `copyHeader` → `WriteHeader` → `flush` → `copyBody` | `ProxyHandler.java` | `handleRequest()` |
| 4 | `proxyhandler.go:65` | `fmt.Fprintf(localconn, "HTTP/1.1 200 OK\r\n\r\n")` | `ProxyHandler.java` | `handleTunnel()` raw socket write |
| 5 | Grizzly API conflict | N/A | `ProxyHandler.java` | writer vs output stream guards |
| 6 | `proxyhandler.go:35` | `DialContext: requestDialer.DialContext` | `PlaintextDialer.java` | `fixedAddress` parameter |
| 7 | `proxyhandler.go:24-36` | `http.Transport` pool | `ProxyHandler.java` | `ConnectionPool` inner class |
| 8 | `utils.go:281` | `conn.SetDeadline(emptytime)` | `ProxyHandler.java` | `ConnectionPool.evictStale()` |
| 9 | `utils.go:265-269` | `delHopHeaders()` | `ProxyHandler.java` | `delHopHeaders()`, `TreeSet(CASE_INSENSITIVE_ORDER)` |
| 10 | `proxyhandler.go:97` | `copyHeader()` | `ProxyHandler.java` | `addHeader()` loop |
| 11 | `proxyhandler.go:99-100` | unconditional body copy | `ProxyHandler.java` | unconditional `copyStream()` |
| 12 | `proxyhandler.go:87` | `req.Header.Add("Proxy-Authorization", s.auth())` | `ProxyHandler.java` | unconditional `req.append("Proxy-Authorization")` |
| 13 | `proxyhandler.go:45-78` | `HandleTunnel()` | `ProxyHandler.java` | `handleTunnel()`, `proxyBidirectional()` |
| 14 | `utils.go:53-77` | `proxy(ctx, ...)` | `ProxyHandler.java` | `proxyBidirectional()` with 120s timeout |
| 15 | `main.go:215-239` | outbound proxy dialer chain | Various | `OutboundProxyDialer`, `Socks5ProxyDialer` |
| 16 | `retrydialer.go:15-21` | `NewRetryDialer` | `RetryDialer.java` | |
| 17 | `retrydialer.go:48-52` | `resolveNetworkFamily` | `RetryDialer.java` | `resolveNetworkFamily()`, `familyMatch()` |
| 18 | `holaapi.go:466-491` | `EnsureTransaction`, `GetFallbackProxies` | `HolaApiClient.java`, `HolaCredentialService.java` | `getFallbackProxies()`, `fetchTunnelsViaFallback()` |
| 19 | `main.go:242,250,86,116` | retry wrappers | `RetryPolicy.java` | `execute()` |
| 20 | `ProxyApplication.java:106-115` | N/A | `ProxyApplication.java` | captures `tunnels` from `AuthResult` |
| 21 | Go `selectPort` | — | `ProxyApplication.java` | `selectPort()` fixed trial/direct swap |
| 22 | `holaapi.go:243-254` | country formatting | `HolaApiClient.java` | country suffix per proxy_type |
| 23 | `holaapi.go:256` | ping_id | `HolaApiClient.java` | `Math.random()` |
| 24 | `holaapi.go:210-231` | `background_init` POST | `HolaApiClient.java` | POST body |
| 25 | Go HTTP client | status code range | `HolaApiClient.java` | 2xx acceptance |
| 26 | Go UUID | `hex.EncodeToString` | `HolaCredentialService.java` | `generateUserUuid()` |
| 27 | `holaapi.go:493-500` | `TemplateLogin` | `HolaApiClient.java` | `"user-uuid-uuid}-is_prem-0"` |
| 28 | `holacredservice.go:28-29` | `"basic "` lowercase | `HolaCredentialService.java` | `"basic "` lowercase |
| 29 | `holaapi.go:367-383` | empty response error | `HolaApiClient.java` | `IOException("empty response")` |
| 30 | `dnsresolver.go:96-130` | DNS wire format parsing | `DnsResolverFactory.java` | `parseDnsResponse()` |
| 31 | `dnsresolver.go` | DoT | `DnsResolverFactory.java` | DoT via TLS + wire format |
| 32 | `dnsresolver.go:42-46` | port defaults | `DnsResolverFactory.java` | per-scheme defaults |
| 33 | `dnsresolver.go:96-130` | concurrent racing | `FastResolver.java` | `CachedThreadPool` + `CountDownLatch` |
| 34 | `utils.go` | `print_countries()` | `ProxyApplication.java` | `listCountries()` |
| 35 | `utils.go` | `print_proxies()` | `ProxyApplication.java` | `listProxies()` |
| 36 | `upstream.go:106-125` | `ServerName: ""` + verify | `PlaintextDialer.java`, `ProxyDialer.java` | reflection `setServerNames(emptyList())` |
| 37 | `holacredservice.go:69-70` | `time.NewTicker` | `HolaCredentialService.java` | `scheduleWithFixedDelay` |
