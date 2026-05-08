# Missing Utilities and Logging

## 1. No `RandRange`

Go's cryptographically secure random range utility has no Java equivalent.

**Go** (`utils.go:313-319`):

```go
func RandRange(low, hi int64) int64 {
    if low >= hi {
        panic("RandRange: low boundary is greater or equal to high boundary")
    }
    delta := hi - low
    return low + rand.New(RandomSource).Int63n(delta+1)
}
```

Uses the cryptographically secure `RandomSource` (`csrand.go`).

**Status:** **Still missing** — Java uses `Math.random()` in places but has no `SecureRandom`-based range utility.

---

## File-by-File: Utilities Comparison

### utils.go → Various Java files

| Function | Go | Java | Status |
|---|---|
| `proxyh2()` (HTTP/2 copy) | `utils.go` | — | **Missing** |
| `RandRange()` | `utils.go` | — | **Missing** |
| `hijack()` | `utils.go` | Grizzly NIO channel access | **Different** |
| `flush()` | `utils.go` | `out.flush()` per-write | **Partial** |

See [07-pitfalls.md](07-pitfalls.md) for resolved items (basic_auth_header, proxy bidirectional copy, print_countries, print_proxies, get_hola_endpoint, copyHeader, delHopHeaders, copyBody, context cancellation, retryPolicy, deadline clearing).
