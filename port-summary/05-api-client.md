# Hola API / Data Handling Issues

## File-by-File: API Client Comparison

### holaapi.go → HolaApiClient.java

| Feature | Go | Java | Status |
|---|---|
| TLS fingerprinting on API calls | `utls.UClient` via `httpClientWithProxy` | Standard JVM TLS | **Missing** |
| Exponential backoff on `zgettunnels` | `cenkalti/backoff` with configurable params | Simple `Thread.sleep` doubling | **Different** |

All other API client issues (VPNCountries, GetFallbackProxies, country suffixes, ping_id, background_init format, HTTP status range, UUID format, login template, auth header case, empty response check, fallback agent iteration) have been fixed. See [07-pitfalls.md](07-pitfalls.md).

### holacredservice.go → HolaCredentialService.java

| Feature | Go | Java | Status |
|---|---|
| Returns `(auth, tunnels, err)` | Yes | `AuthResult` with both | **Fixed** (see 07-pitfalls.md) |
| Fallback proxy support | `EnsureTransaction` with agents | `fetchTunnelsViaFallback()` iterates agents | **Fixed** (see 07-pitfalls.md) |
| Background credential refresh | Goroutine with `time.NewTicker` | `ScheduledExecutorService` | **Fixed** (see 07-pitfalls.md) |
| UUID format | Hex-encoded (no dashes) | Hex-encoded (no dashes) | **Fixed** (see 07-pitfalls.md) |
| Login template `prem` parameter | Hardcoded `"0"` | Hardcoded `"0"` | **Same** |
