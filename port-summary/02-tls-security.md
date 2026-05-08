# TLS and Security Regressions

## 1. No TLS certificate verification

Every Java dialer uses a trust-all `X509TrustManager`. Go performs real `x509.Verify` against a configurable `caPool`.

**Go** (`upstream.go:110-125`):

```go
conn = tls.UClient(conn, &tls.Config{
    ServerName:         sni,
    InsecureSkipVerify: true,
    VerifyConnection: func(cs tls.ConnectionState) error {
        opts := x509.VerifyOptions{
            DNSName:       d.tlsServerName,
            Intermediates: x509.NewCertPool(),
            Roots:         d.caPool,
        }
        for _, cert := range cs.PeerCertificates[1:] {
            opts.Intermediates.AddCert(cert)
        }
        _, err := cs.PeerCertificates[0].Verify(opts)
        return err
    },
}, tls.HelloAndroid_11_OkHttp)
```

The same pattern is used in `plaintext.go:50-65` and `holaapi.go:424-459`.

Key design: `InsecureSkipVerify: true` disables Go's default verification, then `VerifyConnection` performs manual `x509.Verify` against the real hostname and the configured `caPool`. This allows setting `ServerName: ""` for SNI hiding while still validating certificates.

**Java** — all TLS code (`ProxyDialer.java`, `PlaintextDialer.java`, `OutboundProxyDialer.java`, `HolaApiClient.java`) uses a trust-all `X509TrustManager` with no cert verification.

**Status:** **Still missing** across all 4 files.

---

## 2. No TLS fingerprint mimicry

Go uses `utls.HelloAndroid_11_OkHttp` throughout (proxy dialer, Hola API client). Java uses default JVM TLS, making it trivially fingerprintable.

**Go** — every TLS connection uses the same fingerprint:

```go
// upstream.go:125
tls.HelloAndroid_11_OkHttp

// plaintext.go:65
tls.HelloAndroid_11_OkHttp

// holaapi.go:454
tlsConn := tls.UClient(conn, &cfg, tls.HelloAndroid_11_OkHttp)
```

This mimics the TLS ClientHello of Android 11's OkHttp library, making the proxy traffic indistinguishable from normal Android app traffic.

**Status:** **Still missing** — a utls-java library would be needed.

---

## 3. `-cafile` flag parsed but never used

`CLIArgs.caFile` exists but `ProxyApplication` never loads or applies it.

**Go** (`main.go:197-212`) — CA file is loaded into a cert pool and applied to all TLS configs:

```go
var caPool *x509.CertPool
if args.caFile != "" {
    caPool = x509.NewCertPool()
    certs, err := ioutil.ReadFile(args.caFile)
    if err != nil {
        mainLogger.Error("Can't load CA file: %v", err)
        return 15
    }
    if ok := caPool.AppendCertsFromPEM(certs); !ok {
        mainLogger.Error("Can't load certificates from CA file")
        return 15
    }
    UpdateHolaTLSConfig(&tls.Config{
        RootCAs: caPool,
    })
}
```

**Status:** **Still missing** — `CLIArgs.caFile` is parsed but never wired into any TLS context.

---

## 4. `hideSNI` implemented but fragile

Java uses reflection to call `SSLParameters.setServerNames(emptyList())` with a silent catch. Go cleanly sets `ServerName: ""` in `tls.Config` while keeping the real name for verification.

**Go** (`upstream.go:106-125`) — SNI is set to empty string, but `VerifyConnection` uses the real hostname:

```go
sni := d.tlsServerName
if d.hideSNI {
    sni = ""
}
conn = tls.UClient(conn, &tls.Config{
    ServerName:         sni,       // "" when hiding — no SNI in ClientHello
    InsecureSkipVerify: true,
    VerifyConnection: func(cs tls.ConnectionState) error {
        opts := x509.VerifyOptions{
            DNSName:       d.tlsServerName,  // real hostname for verification
            Intermediates: x509.NewCertPool(),
            Roots:         d.caPool,
        }
        // ... verify certs ...
    },
}, tls.HelloAndroid_11_OkHttp)
```

Java's approach (`HolaApiClient.java:226-234`) uses `SSLParameters.setServerNames(emptyList())` via reflection. This avoids the `SSLParameters.setServerNames` API that throws on empty list in standard JDK — the reflection bypasses that guard by invoking the underlying method directly.

**Status:** **Implemented but fragile** — relies on reflection with silent catch. No cert verification alongside it since Java lacks Go's `VerifyConnection` hook.

---

## File-by-File: TLS Comparison

| Feature | Go | Java | Status |
|---|---|---|---|
| CA pool cert verification | Full `x509.Verify` against `caPool` | Trust-all `X509TrustManager` | **Missing** |
| TLS fingerprinting (utls) | `HelloAndroid_11_OkHttp` | Standard JVM TLS | **Missing** |
| `-cafile` flag support | Loaded into cert pool, applied to all TLS | Parsed but unused | **Missing** |
| `hideSNI` + cert verify combo | Separate SNI/verify names via `VerifyConnection` | Reflection `setServerNames(emptyList())`, no verify | **Implemented but fragile** |
| Network validation (tcp only) | Rejects non-TCP in `DialContext` | Missing in `OutboundProxyDialer`/`ProxyDialer` | **Missing** |
| Context cancellation | `context.Context` throughout | Not supported | **Missing** — see 07-pitfalls.md for tunnel-level timeout |
