# Enhancement Implementations

## Enhancement 1: Abort on Temporary/Permanent Ban

**Files modified:**
- `service/HolaCredentialService.java` — `fetchTunnels()` now catches `TemporaryBanException` and `PermanentBanException` before the generic `Exception` catch, logs a clear message to SEVERE, and immediately re-throws (no fallback or retry for bans).
- `ProxyApplication.java` — catches `TemporaryBanException` and `PermanentBanException` in the credential service block, prints a clear user-facing message, and exits with code 9.

**Exit code:** 9 for temporary/permanent ban.

---

## Enhancement 2 & 3: Extension Version Caching (.extver file + --cache-extver flag)

**New file:**
- `ExtVerCache.java` — `com.example.proxy` package. Reads/writes `.extver` file in working directory. CSV format: `timestamp,chromeVer,extVer` (single line). 24-hour TTL.

**Files modified:**
- `CLIArgs.java` — added `-cache-extver` boolean flag (default `true`). Supports `-cache-extver=false` to disable. Added `isCacheExtver()` getter and help text.
- `ProxyApplication.java` — version fetch flow now:
  1. Checks `.extver` cache if both `userAgent` and `extVer` are not provided via CLI and `-cache-extver` is true
  2. If cache is fresh (< 24h), uses cached Chrome version and extension version (skips both live fetches)
  3. If cache is stale or disabled, performs live fetches as before
  4. After fetches, writes cached values to `.extver` (if caching is enabled and values were fetched live)

**File format:** CSV — `unix_timestamp_seconds,chrome_version,extension_version`

---

## Enhancement 4: Multi-Provider Support (Hola + Opera)

Adds `--provider` CLI flag allowing the proxy to switch between Hola (existing) and Opera (SurfEasy) providers. Architecture matches the Go source exactly.

### New files (17)

**Provider package** (`provider/`):
- `ProxyProvider.java` — interface with `initialize()` returning `ProviderSession`
- `ProviderSession.java` — holds `endpoint`, `authProvider` (Supplier<String>), `refreshStarter` (Consumer<ScheduledExecutorService>)
- `HolaProvider.java` — extracts all Hola init from `ProxyApplication.main()`: version caching, UA discovery, ext-ver fetch, credential service, ban detection, endpoint selection, list-countries/list-proxies
- `OperaProvider.java` — full SurfEasy init flow: anonRegister → registerDevice → discover → endpoint selection + refresh scheduling

**HTTP client** (`http/`):
- `OperaApiClient.java` — SurfEasy REST client with SHA-256 digest auth, cookie management, custom SE-* headers, raw socket TLS via `ContextDialer`
- `DigestAuthHelper.java` — RFC 2617 Digest auth standalone utility (MD5/SHA-256)

**Opera DTOs** (`dto/`):
- `SEIPEntry.java`, `SEGeoEntry.java`, `SEStatusPair.java`, `SEStatusPairDeserializer.java`
- `SERegisterSubscriberResponse.java`, `SERegisterDeviceResponse.java`, `SERegisterDeviceData.java`
- `SEDeviceGeneratePasswordResponse.java`, `SEDeviceGeneratePasswordData.java`
- `SEGeoListResponse.java`, `SEDiscoverResponse.java`

### Modified files (2)

- `CLIArgs.java` — added `-provider` (default `"hola"`), `-fake-sni` flags, validation (must be `"hola"`|`"opera"`), getters, help text
- `ProxyApplication.java` — replaced hardcoded Hola flow with provider switch, session-based dialer creation, scheduled refresh

### Implementation details

- **Digest auth algorithm:** SurfEasy API uses `algorithm="SHA-256"` — server sends `WWW-Authenticate: Digest realm="ApiDigest", algorithm="SHA-256", qop="auth"`. Build digest response with correct algorithm.
- **Country/region codes:** Opera uses continental regions (`EU`, `AS`, `AM`) not ISO codes. Default is `EU`.
- **SNI suppression:** Opera API TLS suppresses SNI (`hideSNI=true`). Proxy connections use `fakeSNI` — suppress SNI when empty, send as ServerName when provided.
- **Refresh scheduling:** Starts after Grizzly server init. Calls `login()` + `deviceGeneratePassword()` at `--rotate` interval.

### Known issues

- End-to-end proxy traffic not yet verified (init succeeds, server starts on 127.0.0.1:8080)
- Refresh scheduling not tested end-to-end
- `--list-countries` output verified (returns regions: EU, AS, AM)

---

## Enhancement 5: `--test` CLI Flag

Adds `--test` flag for automated proxy verification without external test scripts.

### Files modified

- `CLIArgs.java` — `test` field changed from `boolean` to `String`. `--test` without value defaults to `"ip"`, `--test file` sets `"file"`. Value-consuming logic special-cased: consumes next arg only if it doesn't start with `-`.
- `ProxyApplication.java` — `runTest()` method dispatches on test type:
  - `"file"`: HTTP GET `http://speedtest.tele2.net/10MB.zip` through proxy, validates ~10MB
  - `"ip"` (default): HTTPS GET `https://api.ipify.org?format=json` through proxy, validates JSON response

### Changes to request forwarding (Opera provider compatibility)

The initial `--test file` implementation failed with Opera because the old `ConnectionPool` dialed the fixed address `"upstream"` instead of the target host:port. The `ProxyDialer` needs the real target address to create a CONNECT tunnel to the origin.

- `ProxyHandler.java` — replaced single-pool `ConnectionPool` with per-address `HttpDialer` that dials the actual target host:port. The `readStatusAndHeaders` method was also fixed: the old `hasCRLFCRLF()` check only looked at the last 4 bytes, missing the header terminator when body data followed in the same read. Replaced with a scan across the entire buffer.

### Test results

| Test | Provider | Result |
|------|----------|--------|
| `--test ip` | Opera | 200 OK, `{"ip":"77.111.247.x"}` |
| `--test file` | Opera | 200 OK, 10.00 MB downloaded |

---

## Enhancement 6: Refresh Scheduling

Fixed refresh scheduling in the Opera provider to match Hola patterns and prevent resource leaks.

### Files modified

- `OperaProvider.java:132` — changed `scheduleAtFixedRate` to `scheduleWithFixedDelay` to prevent overlapping executions (matches Hola pattern, pitfall #37 fix)
- `OperaProvider.java` — removed separate "opera-refresh" executor; Opera now uses the "provider-refresh" scheduler passed by `ProxyApplication`, eliminating the executor resource leak
- `OperaProvider.java` — added `refreshMs <= 0` guard to skip scheduling when `--rotate` is set to 0 or negative
- `OperaProvider.java` — set `refreshIntervalMs` on the `ProviderSession` to `refreshMs` (was hardcoded to `0` for Opera, which was a bug)

**Date fixed:** 2026-05-08

---

## Enhancement 7: Health Endpoint

Added a `/health` HTTP endpoint for health checks, absent from the Go original.

### New file

- `handler/HealthHandler.java` — Grizzly `HttpHandler` that returns HTTP 200 with body `"OK"` for any request to `/health`.

### Files modified

- `ProxyApplication.java` — registers the `HealthHandler` at the `/health` path on the Grizzly server.

---

## Enhancement 8: `--direct-dns` CLI Flag

Added `--direct-dns` flag to resolve domain names directly via DoH (1.1.1.1, 8.8.8.8) instead of relying on the proxy. Not present in the Go original.

### Files modified

- `CLIArgs.java` — added `-direct-dns` boolean flag (default `false`), with `isDirectDns()` getter and help text.
- `ProxyApplication.java` — when `directDns` is true, uses `AdvancedResolver` (DoH-based) instead of `DnsResolverFactory.fastResolverFromUrls`.

---

## Enhancement 9: Graceful Shutdown and Shutdown Hook

Added a shutdown hook and graceful `Grizzly` server lifecycle management, improving upon the Go original's bare `http.ListenAndServe`.

### Files modified

- `ProxyApplication.java` — added `volatile boolean running` flag, `Runtime.getRuntime().addShutdownHook` that calls `server.shutdown()`, and a `while` loop in `main()` that polls the running flag. The `finally` block shuts down both the Grizzly server and the refresh scheduler. This ensures clean teardown on SIGINT/SIGTERM.

---

## Enhancement 10: AsyncLog — Asynchronous Logging Wrapper

Added an asynchronous logging utility that prevents logging I/O from blocking request-handling threads. Not present in the Go original.

### New file

- `AsyncLog.java` — `com.example.proxy` package. Wraps `java.util.logging.Logger` with logging offloaded to a single-thread background executor. Provides `log(Level, String)` and `log(Level, String, Throwable)` methods, plus `reset(Level)` for runtime log level reconfiguration.

### Why

Go's `LogWriter` (in `logwriter.go`) uses a buffered channel (capacity 128) with a background goroutine for async log output. Java's `AsyncLog` provides equivalent behavior, preventing format-heavy log statements from blocking proxy request processing.

---

## Enhancement 11: SOCKS5 Outbound Proxy Support

Added full SOCKS5 (RFC 1928/1929) support for the `--proxy` outbound proxy flag. Go documents `socks5`/`socks5h` schemes in help text but **does not implement them** — only `http` and `https` are registered with `xproxy`.

### New file

- `Socks5ProxyDialer.java` — `com.example.proxy.dialer` package. Implements `ContextDialer` with:
  - SOCKS5 handshake (RFC 1928 §3)
  - GSSAPI and username/password authentication (RFC 1929)
  - TCP connect command — resolves DNS locally for `socks5://`, delegates to proxy for `socks5h://`
  - UDP associate command support
  - Bound address/port extraction

### Files modified

- `OutboundProxyDialer.java` — the `ProxyDialerFromURL` method now handles `socks5` and `socks5h` URL schemes in its switch statement, creating a `Socks5ProxyDialer` for these schemes.

---

## Enhancement 12: TunnelSocket — PushbackInputStream Wrapper

Added a `TunnelSocket` wrapper that prevents CONNECT response bytes from being lost during tunnel establishment. Not present in Go.

### New file

- `TunnelSocket.java` — `com.example.proxy` package. Extends `Socket` with:
  - `getInputStream()` returns a `PushbackInputStream` wrapping the underlying socket's input stream
  - Any bytes read-ahead during CONNECT response parsing can be pushed back for the caller to re-read

### Why

Go's `ProxyDialer.DialContext` uses a one-off `readResponse()` that reads byte-by-byte from the raw `net.Conn` — no buffered pushback needed because the same function both reads the response and returns the conn. In Java, the CONNECT response is parsed in `ProxyDialer`, but the caller (connection pool, HTTP handler) reads from the returned socket. Without `PushbackInputStream`, any bytes read during CONNECT parsing would be lost.

---

## Enhancement 13: gzip/deflate HTTP Response Decompression

Added automatic decompression of gzip/deflate-encoded HTTP responses from the Hola API. Not present in Go.

### Files modified

- `HolaApiClient.java` (lines 143-172) — after reading the raw HTTP response body, checks the `Content-Encoding` header:
  - `gzip` — wraps response bytes in `GZIPInputStream`
  - `deflate` — wraps response bytes in `InflaterInputStream`
  - Other / absent — returns raw bytes as-is

### Why

Go's `do_req` reads response body via `ioutil.ReadAll` with no decompression and no `Accept-Encoding` header. If the Hola API returns compressed responses (which it may depending on request headers), Go's code would receive compressed bytes. Java handles both cases transparently.

---

## Enhancement 14: FixedEndpointDialer — Separate Request Dialer

Extracted the plain HTTP request dialer into its own class for clarity and reuse. Not a separate abstraction in Go.

### New file

- `FixedEndpointDialer.java` — `com.example.proxy.dialer` package. Implements `ContextDialer`:
  - Always connects to a fixed proxy endpoint address (host:port)
  - Optionally wraps the connection in TLS
  - Used by `ProxyHandler` for non-CONNECT HTTP proxy requests

### Why

Go configures the request dialer inline in `http.Transport.DialContext` — a function literal captures the endpoint address and base dialer. Java's approach extracts this into its own class for testability and separation of concerns.

---

## Enhancement 15: AdvancedResolver DNS Enhancements

Added multiple DNS resolution enhancements to `AdvancedResolver` that are absent from Go's `dnsresolver.go`:

### Files modified

- `AdvancedResolver.java` — `com.example.proxy.resolver` package

### Features

| Feature | Description | Go equivalent |
|---------|-------------|---------------|
| DNSSEC DO bit | Sets EDNS(0) DNSSEC OK flag in DNS queries | Not set |
| CNAME chasing | Follows CNAME chains up to depth 5, with loop detection and per-name result caching | No chasing |
| Stale-while-revalidate cache | Serves stale entries within 1-hour window while triggering background refresh | No caching |
| Negative DNS caching | Caches NXDOMAIN responses using SOA minimum TTL, with min/max clamping | No caching |
| Semaphore-limited concurrency | Max 10 concurrent DoH requests via `Semaphore` (up to 3 retries after 100ms) | Unlimited goroutines |
| Dedicated retry executor | Separate `ScheduledExecutorService` for retry tasks (prevents blocking cache maintenance) | N/A |
| LRU cache eviction | Sorts by last-access time, evicts oldest when cache exceeds 1024 entries | N/A |
