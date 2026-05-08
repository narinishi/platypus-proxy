package com.example.proxy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Command-line arguments for hola-proxy.
 * Uses Java standard library only (no external CLI framework).
 * Parsing logic mirrors the original Go implementation in main.go (using Go's flag package).
 */
public class CLIArgs {

    // ---- Parsed flag values (with defaults matching Go source) ----

    private String extVer = "";
    private String country = "us";
    private boolean listCountries = false;
    private boolean listProxies = false;
    private String test = "";
    private int limit = 3;
    private String bindAddress = "127.0.0.1:8080";
    private int verbosity = 20;
    private String timeout = "35s";
    private String rotate = "48h";
    private String proxyType = "direct";
    private String[] resolver = new String[]{
        "https://1.1.1.3/dns-query",
        "https://8.8.8.8/dns-query",
        "https://dns.google/dns-query",
        "https://security.cloudflare-dns.com/dns-query",
        "https://fidelity.vm-0.com/q",
        "https://wikimedia-dns.org/dns-query",
        "https://dns.adguard-dns.com/dns-query",
        "https://dns.quad9.net/dns-query",
        "https://doh.cleanbrowsing.org/doh/adult-filter/"
    };
    private boolean dontUseTrial = false;
    private boolean showVersion = false;
    private String outboundProxy = "";
    private String caFile = "";
    private String userAgent = null;
    private boolean hideSNI = true;
    private boolean cacheExtver = true;
    private String forcePortField = "";
    private String provider = "hola";
    private String fakeSNI = "";
    private String backoffInitial = "3s";
    private String backoffDeadline = "5m";
    private int initRetries = 0;
    private String initRetryInterval = "5s";

    // ---- Tracks which flags were explicitly set by the user ----
    private final Set<String> explicitlySet = new HashSet<>();

    // ---- Internal flags for help/version (not part of the data model) ----
    private boolean helpRequested = false;

    // ---- Private constructor: use parse() factory method ----
    private CLIArgs() {}

    /**
     * Parse command-line arguments using Java standard library.
     * Mirrors Go's flag package behavior from the original main.go.
     *
     * <p>Supported formats:</p>
     * <ul>
     *   <li>{@code -flag value} — space-separated</li>
     *   <li>{@code --flag value} — double-dash (also accepted)</li>
     *   <li>{@code -flag=value} — equals-sign assignment</li>
     *   <li>Boolean flags can be standalone toggles (no value needed)</li>
     * </ul>
     *
     * @param args raw command-line arguments (typically from main())
     * @return parsed CLIArgs instance, or {@code null} if help/version was printed or an error occurred
     *         (in which case the caller should exit with appropriate code)
     */
    public static CLIArgs parse(String[] args) {
        CLIArgs a = new CLIArgs();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            // Skip empty args
            if (arg.isEmpty()) continue;

            // Handle --flag=value or -flag=value
            String value = null;
            String flag;
            int eqIdx = arg.indexOf('=');
            if (eqIdx > 0) {
                flag = arg.substring(0, eqIdx);
                value = arg.substring(eqIdx + 1);
            } else {
                flag = arg;
            }

            // Must start with -
            if (!flag.startsWith("-")) {
                System.err.println("Unexpected argument: " + arg);
                printHelp();
                return null;
            }

            // Normalize: strip leading dashes
            String name = flag.replaceFirst("^-+", "");

            // Help flag
            if ("h".equals(name) || "help".equals(name)) {
                a.helpRequested = true;
                printHelp();
                return null; // signal caller to exit 0
            }

            // Version flags (-V, --version, -version matching Go)
            if ("V".equals(name) || "version".equals(name)) {
                a.showVersion = true;
                System.out.println("hola-proxy version 1.0-SNAPSHOT");
                return null; // signal caller to exit 0
            }

            // Boolean flags (standalone toggle, no value consumed)
            if ("list-countries".equals(name)) {
                a.listCountries = true;
                continue;
            }
            if ("list-proxies".equals(name)) {
                a.listProxies = true;
                continue;
            }
            if ("dont-use-trial".equals(name)) {
                a.dontUseTrial = true;
                continue;
            }
            if ("hide-SNI".equals(name)) {
                a.hideSNI = true;
                continue;
            }
            if ("cache-extver".equals(name)) {
                if (value != null) {
                    a.cacheExtver = "true".equalsIgnoreCase(value) || "1".equals(value);
                } else {
                    a.cacheExtver = true;
                }
                continue;
            }

            // Flags that require a value
            if (value == null) {
                // For --test, only consume next arg if it doesn't look like a flag
                if ("test".equals(name)) {
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        i++;
                        value = args[i];
                    }
                } else {
                    if (i + 1 >= args.length) {
                        System.err.println("Flag \"" + name + "\" requires a value");
                        printHelp();
                        return null;
                    }
                    i++;
                    value = args[i];
                }
            }

            switch (name) {
                case "ext-ver":
                    a.extVer = value;
                    break;
                case "test":
                    a.test = value != null ? value : "ip";
                    break;
                case "country":
                    a.country = value;
                    a.explicitlySet.add("country");
                    break;
                case "limit":
                    try { a.limit = Integer.parseInt(value); }
                    catch (NumberFormatException e) {
                        System.err.println("Invalid limit value: " + value);
                        return null;
                    }
                    break;
                case "bind-address":
                    a.bindAddress = value;
                    break;
                case "verbosity":
                    try { a.verbosity = Integer.parseInt(value); }
                    catch (NumberFormatException e) {
                        System.err.println("Invalid verbosity value: " + value);
                        return null;
                    }
                    break;
                case "timeout":
                    a.timeout = value;
                    a.explicitlySet.add("timeout");
                    break;
                case "rotate":
                    a.rotate = value;
                    break;
                case "proxy-type":
                    a.proxyType = value;
                    break;
                case "resolver":
                    // Comma-separated list (matches Go's CSVArg type)
                    a.resolver = value.split(",");
                    break;
                case "proxy":
                    a.outboundProxy = value;
                    break;
                case "cafile":
                    a.caFile = value;
                    break;
                case "user-agent":
                    a.userAgent = value;
                    a.explicitlySet.add("user-agent");
                    break;
                case "provider":
                    a.provider = value;
                    break;
                case "fake-sni":
                    a.fakeSNI = value;
                    break;
                case "force-port-field":
                    a.forcePortField = value;
                    break;
                case "backoff-initial":
                    a.backoffInitial = value;
                    break;
                case "backoff-deadline":
                    a.backoffDeadline = value;
                    break;
                case "init-retries":
                    try { a.initRetries = Integer.parseInt(value); }
                    catch (NumberFormatException e) {
                        System.err.println("Invalid init-retries value: " + value);
                        return null;
                    }
                    break;
                case "init-retry-interval":
                    a.initRetryInterval = value;
                    break;
                default:
                    System.err.println("Unknown flag: " + name);
                    printHelp();
                    return null;
            }
        }

        // ---- Provider-specific defaults (only apply if user didn't explicitly set the flag) ----
        if ("opera".equals(a.provider)) {
            if (!a.explicitlySet.contains("country")) {
                a.country = "EU";
            }
            if (!a.explicitlySet.contains("timeout")) {
                a.timeout = "10s";
            }
            if (!a.explicitlySet.contains("user-agent")) {
                a.userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 OPR/114.0.0.0";
            }
        }

        // ---- Validation (mirrors Go's post-parse checks in parse_args()) ----

        if (a.country.isEmpty()) {
            System.err.println("Country can't be empty string.");
            printUsage();
            return null;
        }
        if (a.proxyType.isEmpty()) {
            System.err.println("Proxy type can't be an empty string.");
            printUsage();
            return null;
        }
        if (a.listCountries && a.listProxies) {
            System.err.println("list-countries and list-proxies flags are mutually exclusive");
            printUsage();
            return null;
        }
        if (!"hola".equals(a.provider) && !"opera".equals(a.provider)) {
            System.err.println("Invalid provider: " + a.provider + ". Must be \"hola\" or \"opera\".");
            printUsage();
            return null;
        }

        return a;
    }

    /**
     * Print comprehensive help/usage information to stdout.
     * Mimics Go's flag.PrintDefaults() output format.
     */
    public static void printHelp() {
        System.out.println("Usage: hola-proxy [options]");
        System.out.println();
        System.out.println("Hola VPN proxy client (Java port)");
        System.out.println();
        printUsage();
    }

    private static void printUsage() {
        System.out.println("Options:");
        System.out.println("  -h, --help                       Show this help message and exit");
        System.out.println("  -V, --version                   Show program version and exit");
        System.out.println("  -ext-ver string                 Extension version to mimic in requests.");
        System.out.println("                                   Can be obtained from https://chrome.google.com/webstore/detail/hola-vpn-the-website-unbl/gkojfkhlekighikafcpjkiklfbnlmeio");
        System.out.println("  -country string                 Desired proxy location (default: \"us\" for hola, \"EU\" for opera)");
        System.out.println("  -list-countries                 List available countries and exit");
        System.out.println("  -list-proxies                   Output proxy list and exit");
        System.out.println("  -test string                   Test proxy and exit. Values: \"ip\" (fetch https://api.ipify.org?format=json),");
        System.out.println("                                   \"file\" (download 10MB from http://speedtest.tele2.net/10MB.zip)");
        System.out.println("  -limit int                      Amount of proxies in retrieved list (default: 3)");
        System.out.println("  -bind-address string            HTTP proxy listen address (default: \"127.0.0.1:8080\")");
        System.out.println("  -verbosity int                  Logging verbosity (10 - debug, 20 - info, 30 - warning, 40 - error, 50 - critical) (default: 20)");
        System.out.println("  -timeout duration               Timeout for network operations (e.g. 35s, 2m) (default: \"35s\" for hola, \"10s\" for opera)");
        System.out.println("  -rotate duration                Rotate user ID once per given period (e.g. 48h, 7d) (default: \"48h\")");
        System.out.println("  -proxy-type string              Proxy type: direct or lum (default: \"direct\")");
        System.out.println("  -resolver strings               Comma-separated list of DNS/DoH/DoT resolvers");
        System.out.println("                                   (default: https://1.1.1.3/dns-query,...)");
        System.out.println("  -dont-use-trial                 Use regular ports instead of trial ports");
        System.out.println("  -proxy string                   Sets base proxy to use for all dial-outs.");
        System.out.println("                                   Format: <http|https|socks5|socks5h>://[login:password@]host[:port]");
        System.out.println("  -cafile string                  Use custom CA certificate bundle file");
        System.out.println("  -user-agent string              Value of User-Agent header in requests.");
        System.out.println("                                   Default: Chrome for Windows (hola) or Opera for Windows (opera)");
        System.out.println("  -hide-SNI                       Hide SNI in TLS sessions with proxy server (default: true)");
        System.out.println("  -provider string                Proxy provider: \"hola\" or \"opera\" (default: \"hola\")");
        System.out.println("  -fake-sni string                Domain name to use as SNI in communications with Opera servers");
        System.out.println("  -cache-extver                   Cache Chrome and extension versions in .extver file (default: true)");
        System.out.println("                                   Set to false to always fetch fresh values");
        System.out.println("  -force-port-field string        Force specific port field/num (example 24232 or lum)");
        System.out.println("  -backoff-initial duration       Initial average backoff delay for zgettunnels (e.g. 3s, 1m) (default: \"3s\")");
        System.out.println("  -backoff-deadline duration      Total duration of zgettunnels method attempts (e.g. 5m, 1h) (default: \"5m\")");
        System.out.println("  -init-retries int               Number of attempts for initialization steps, zero for unlimited retry (default: 0)");
        System.out.println("  -init-retry-interval duration   Delay between initialization retries (e.g. 5s, 30s) (default: \"5s\")");
    }

    // ==================== Getters (all preserved for backward compatibility) ====================

    public boolean isShowVersion() {
        return showVersion;
    }

    public String getExtVer() {
        return extVer;
    }

    public String getCountry() {
        return country;
    }

    public boolean isListCountries() {
        return listCountries;
    }

    public boolean isListProxies() {
        return listProxies;
    }

    public String getTest() {
        return test;
    }

    public int getLimit() {
        return limit;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public int getVerbosity() {
        return verbosity;
    }

    public long getTimeoutMillis() {
        return parseDuration(timeout);
    }

    public long getRotateMillis() {
        return parseDuration(rotate);
    }

    public String getProxyType() {
        return proxyType;
    }

    public String[] getResolver() {
        return resolver;
    }

    public boolean isDontUseTrial() {
        return dontUseTrial;
    }

    public boolean useTrial() {
        return !dontUseTrial;
    }

    public String getOutboundProxy() {
        return outboundProxy;
    }

    public String getCaFile() {
        return caFile;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public boolean isHideSNI() {
        return hideSNI;
    }

    public boolean isCacheExtver() {
        return cacheExtver;
    }

    public String getForcePortField() {
        return forcePortField;
    }

    public String getProvider() {
        return provider;
    }

    public String getFakeSNI() {
        return fakeSNI;
    }

    public long getBackoffInitialMillis() {
        return parseDuration(backoffInitial);
    }

    public long getBackoffDeadlineMillis() {
        return parseDuration(backoffDeadline);
    }

    public int getInitRetries() {
        return initRetries;
    }

    public long getInitRetryIntervalMillis() {
        return parseDuration(initRetryInterval);
    }

    // ==================== Duration parsing utility ====================

    /**
     * Parse a duration string (e.g., "35s", "2m", "48h", "7d", "500ms") into milliseconds.
     * Matches Go's time.Duration parsing behavior used in the original source.
     */
    private static long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 0;
        }
        String value = duration;
        String unit = "";
        for (int i = 0; i < duration.length(); i++) {
            char c = duration.charAt(i);
            if (Character.isLetter(c)) {
                value = duration.substring(0, i);
                unit = duration.substring(i).toLowerCase();
                break;
            }
        }
        long val;
        try {
            val = Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
        switch (unit) {
            case "ms": return val;
            case "s": return val * 1000;
            case "m": return val * 60 * 1000;
            case "h": return val * 60 * 60 * 1000;
            case "d": return val * 24 * 60 * 60 * 1000;
            default: return val;
        }
    }
}
