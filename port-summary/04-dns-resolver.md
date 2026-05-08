# DNS / Resolver Issues

## 1. No IPv4/IPv6 resolution distinction

Java's `LookupNetIP.lookup(host)` has no `network` parameter (`"ip4"`/`"ip6"`/`"ip"`). `LookupNetIP.java` only exposes `List<InetAddress> lookup(String host)`.

**Go** — `LookupNetIPer` interface accepts a `network` parameter:

```go
// dnsresolver.go:67-69
type LookupNetIPer interface {
    LookupNetIP(context.Context, string, string) ([]netip.Addr, error)
}
```

**Status:** **Still missing**

---

## 2. URL normalization (`goto begin` loop) not ported

Go's `DnsResolverFactory.parseURL()` uses a `goto begin` loop to re-parse URLs after DNS resolution. Java's URL parsing is single-pass.

**Status:** **Still missing**

---

## File-by-File: DNS Resolver Comparison

### dnsresolver.go → FastResolver.java / DnsResolverFactory.java / LookupNetIP.java

| Feature | Go | Java | Status |
|---|---|
| `LookupNetIP` with ctx + network param | Yes | `lookup(host)` only | **Missing** |
| URL normalization (`goto begin` loop) | Yes | — | **Missing** |
| DoH binary wire format | `dns.NewDoHResolver` | Hand-rolled JSON (RFC 8484) | **Different** |
| Custom `net.Resolver` with `PreferGo` | Yes | UDP-only `PlainDnsResolver` | **Different** |

See [07-pitfalls.md](07-pitfalls.md) for resolved items (parseResponse, DoT, port defaults, concurrent resolvers, network mapping).

### retrydialer.go → RetryDialer.java

| Feature | Go | Java | Status |
|---|---|
| IPv6 address handling | `SplitHostPort` | `parts[0]`/`parts[1]` | **Broken** |
| UpstreamBlockedError detection | Sentinel error comparison | `isUpstreamBlocked()` checks cause chain + message contains | **Fragile** |
| DNS re-resolution on block | `resolver.LookupNetIP(ctx, network, host)` then retry with resolved IPs | `resolver.lookup(host)` then retry with IPs | **Partial** |

See [07-pitfalls.md](07-pitfalls.md) for resolved items (network mapping, retry network preservation).
