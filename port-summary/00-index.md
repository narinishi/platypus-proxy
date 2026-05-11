# Java Port vs Original Go Source — Remaining Gaps

*Updated 2026-05-11*

Unresolved divergences between the Java port and the original Go proxy source. For the complete record of resolved issues, see [07-pitfalls.md](07-pitfalls.md).

---

## Files

| # | File | Description |
|---|------|-------------|
| 1 | [01-critical-gaps.md](01-critical-gaps.md) | Critical / non-functional issues — fallback stubs, missing commands |
| 2 | [02-tls-security.md](02-tls-security.md) | TLS and security regressions — cert verification, TLS fingerprinting, CA file, hideSNI |
| 3 | [03-proxy-handler.md](03-proxy-handler.md) | Proxy handler protocol differences — HTTP transport, HTTP/2, request validation, status codes |
| 4 | [04-dns-resolver.md](04-dns-resolver.md) | DNS and resolver issues — IPv4/IPv6 distinction, URL normalization, DoH format |
| 5 | [05-api-client.md](05-api-client.md) | Hola API and data handling — TLS fingerprinting, exponential backoff |
| 6 | [06-utilities.md](06-utilities.md) | Missing utilities and logging — RandRange, HTTP/2 copy, hijack mechanism |
| 7 | [07-pitfalls.md](07-pitfalls.md) | Pitfalls and issues addressed during porting (complete list of all resolved items) |
| 8 | [08-performance.md](08-performance.md) | Performance and behavioral enhancements — virtual threads, removed flushes, AdvancedResolver DNS, AsyncLog, SOCKS5, TunnelSocket, gzip decompression |

## Related Documentation

The [port-enhancements/](../port-enhancements/) directory covers additions to the Java port that have **no equivalent** in the original Hola Go source:

- **Multi-provider support** (`--provider`, `-fake-sni`) — Hola Go only supports Hola; the Java port adds Opera/SurfEasy as a second provider backend. See `port-enhancements/ACTIONED.md` (Enhancement 4).
- **`--test` flag** (`--test ip` / `--test file`) — automated proxy verification without external test scripts. See `port-enhancements/ACTIONED.md` (Enhancement 5).
- **Opera provider implementation** — full SurfEasy REST API client, digest auth with SHA-256, device registration and rotation flow. See `port-enhancements/ACTIONED.md`.
- **Performance/behavioral enhancements** (AdvancedResolver DNS features, AsyncLog, SOCKS5 support, TunnelSocket, gzip/deflate decompression, FixedEndpointDialer) — Java additions not in Go. See `port-summary/08-performance.md` and `port-enhancements/ACTIONED.md`.
- **All enhancements actioned** — see `port-enhancements/TODO.md` (now empty) and `port-enhancements/ACTIONED.md`.

- **Security Regressions**: 4 (TLS cert verify, TLS fingerprinting, CA file loading, hideSNI fragility)
- **Protocol / Behavioral**: 2 (HTTP/2 tunnel, request validation)
- **DNS / Resolver**: 2 (LookupNetIP network param, URL normalization)
- **API / Data**: 2 (TLS fingerprinting on API calls, exponential backoff)
- **Missing Utilities**: 2 (RandRange, HTTP/2 copy)
- **Total**: 12 remaining gaps (unchanged — all new features are documented as enhancements, not gaps)
