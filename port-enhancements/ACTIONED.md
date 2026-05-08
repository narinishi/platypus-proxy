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
