package dev.gaius.browser;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.teavm.jso.JSBody;

/** Routes browser-only HTTP calls through the same trusted bridge as Minecraft TCP. */
public final class BrowserHttpProxy {
    private BrowserHttpProxy() {
    }

    public static URL proxyResourcePack(URL target) {
        return proxy(target, "resource-pack");
    }

    public static URL proxyAuthentication(URL target) {
        return proxy(target, "auth");
    }

    /** Removes headers that XMLHttpRequest cannot legally set in a browser. */
    public static Map<String, String> browserSafeHeaders(Map<String, String> headers) {
        Map<String, String> filtered = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name != null && value != null && !isForbiddenBrowserHeader(name)) {
                    filtered.put(name, value);
                }
            }
        }
        String bridgeToken = bridgeToken();
        if (bridgeToken != null && !bridgeToken.isEmpty()) {
            filtered.put("X-Gaius-Bridge-Token", bridgeToken);
        }
        return filtered;
    }

    /** Retains pack metadata headers accepted by the RelayNode preflight. */
    public static Map<String, String> browserSafeResourcePackHeaders(
            URL target, Map<String, String> headers) {
        return browserSafeHeaders(headers);
    }

    public static String proxyTexture(String target) {
        // A player-uploaded skin is carried as a bounded PNG data URL. It is
        // already local to the browser and must not be sent through a remote
        // texture proxy (or exposed as a public URL in a LAN invite).
        if (target != null && target.startsWith("data:image/png;base64,")
                && target.length() <= 16_384) {
            return target;
        }
        try {
            return proxy(new URL(target), "texture").toExternalForm();
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException("Texture URL is invalid", exception);
        }
    }

    public static String proxyRealms(String target) {
        try {
            return proxy(new URL(target), "realms").toExternalForm();
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException("Realms URL is invalid", exception);
        }
    }

    public static void addRealmsCookie(
            HttpURLConnection connection, String name, String value) {
        if (connection == null || name == null || value == null) {
            throw new IllegalArgumentException("Realms cookie is incomplete");
        }
        if (containsHeaderBreak(name) || containsHeaderBreak(value)) {
            throw new IllegalArgumentException("Realms cookie contains a header break");
        }
        String header = "X-Gaius-Realms-Cookie";
        String existing = connection.getRequestProperty(header);
        String cookie = name + "=" + value;
        connection.setRequestProperty(
                header,
                existing == null || existing.isEmpty() ? cookie : existing + "; " + cookie);
    }

    private static boolean containsHeaderBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static boolean isForbiddenBrowserHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("proxy-") || lower.startsWith("sec-")) {
            return true;
        }
        return switch (lower) {
            case "accept-charset", "accept-encoding", "access-control-request-headers",
                    "access-control-request-method", "connection", "content-length",
                    "cookie", "cookie2", "date", "dnt", "expect", "host",
                    "keep-alive", "origin", "permissions-policy", "referer", "te",
                    "trailer", "transfer-encoding", "upgrade", "user-agent", "via" -> true;
            default -> false;
        };
    }

    private static URL proxy(URL target, String kind) {
        if (target == null) {
            throw new IllegalArgumentException("HTTP proxy target is missing");
        }
        try {
            URL proxied = new URL(createProxyUrl(target.toExternalForm(), kind));
            report(kind);
            return proxied;
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException("Browser bridge URL is invalid", exception);
        }
    }

    @JSBody(params = {"target", "kind"}, script = """
            const params = new URLSearchParams(location.search || '');
            const acceptedLinkFailed = !!globalThis.__gaiusLinkBridgeFailed;
            const acceptedLink = acceptedLinkFailed ? null : globalThis.__gaiusLinkBridgeUrl;
            const explicitBridge = acceptedLink || params.get('bridge') ||
              params.get('relay');
            let activeRelay = null;
            // HTTP requests such as server resource packs are issued after the TCP
            // channel has selected and attested a relay. Reuse that live candidate so
            // the HTTP side follows the same target tunnel instead of racing the
            // localhost development fallback.
            const nettyBridge = globalThis.__gaiusNettyBridge;
            if (!explicitBridge && nettyBridge && nettyBridge.channels instanceof Map) {
              const activeRelays = new Map();
              nettyBridge.channels.forEach(function(entry) {
                if (!entry || !entry.connected || !entry.currentCandidate ||
                    entry.currentCandidate.direct) return;
                const candidate = entry.currentCandidate;
                if (typeof candidate.url === 'string' && candidate.url.trim()) {
                  const url = candidate.url.trim();
                  const previous = activeRelays.get(url);
                  if (!previous || (!previous.token && candidate.token)) {
                    activeRelays.set(url, candidate);
                  }
                }
              });
              // A browser may have a status/list channel and a play channel alive at
              // the same time. Do not guess between different active relays: an
              // ambiguous selection must use the configured/default route instead.
              if (activeRelays.size === 1) activeRelay = activeRelays.values().next().value;
            }
            // Requests issued before any tunnel exists (the pre-connect Mojang
            // blocked-servers check, profile lookups) must not assume a bridge on
            // the page's own host: a hosted page such as GitHub Pages has nothing
            // on :8080 and the request stalls the connect. Only local development
            // hosts keep that fallback; everything else uses the bundled relay.
            const pageHost = String(location.hostname || '').toLowerCase();
            const localDevelopmentHost = pageHost === 'localhost' ||
              pageHost.endsWith('.localhost') || pageHost.startsWith('127.') ||
              pageHost === '[::1]' || pageHost === '::1';
            let registryRelay = null;
            const registryNodes = globalThis.__gaiusBridgeUrls;
            if (!localDevelopmentHost && Array.isArray(registryNodes)) {
              let bestPriority = -Infinity;
              for (let index = 0; index < registryNodes.length; index++) {
                const node = registryNodes[index];
                const url = typeof node === 'string' ? node : node && node.url;
                if (typeof url !== 'string' || !url.trim()) continue;
                const rawPriority = node && typeof node === 'object' ? Number(node.priority) : 0;
                const priority = Number.isFinite(rawPriority) ? rawPriority : 0;
                if (priority > bestPriority) {
                  bestPriority = priority;
                  registryRelay = url.trim();
                }
              }
            }
            const configured = explicitBridge || (activeRelay && activeRelay.url) || registryRelay ||
              (acceptedLinkFailed ? null : globalThis.__gaiusBridgeUrl);
            let bridge;
            if (configured && String(configured).trim()) {
              bridge = new URL(String(configured).trim(), location.href);
            } else {
              const scheme = location.protocol === 'https:' ? 'https:' : 'http:';
              const rawHost = String(
                globalThis.__gaiusBridgeHost || location.hostname || '127.0.0.1'
              );
              const host = rawHost.includes(':') &&
                !(rawHost.startsWith('[') && rawHost.endsWith(']'))
                ? '[' + rawHost + ']'
                : rawHost;
              const port = globalThis.__gaiusBridgePort || '8080';
              bridge = new URL(scheme + '//' + host + ':' + port + '/');
            }
            if (bridge.protocol === 'ws:') bridge.protocol = 'http:';
            if (bridge.protocol === 'wss:') bridge.protocol = 'https:';
            if (bridge.protocol !== 'http:' && bridge.protocol !== 'https:') {
              throw new Error('Unsupported Gaius bridge protocol: ' + bridge.protocol);
            }
            bridge.pathname = '/proxy/' + String(kind);
            bridge.hash = '';
            bridge.search = '';
            bridge.searchParams.set('url', String(target));
            if (kind === 'resource-pack') bridge.searchParams.set('stream', '1');
            return bridge.href;
            """)
    private static native String createProxyUrl(String target, String kind);

    @JSBody(script = """
            const params = new URLSearchParams(location.search || '');
            let token = params.get('bridgeToken') || params.get('relayToken') ||
              globalThis.__gaiusBridgeToken;
            const bridge = globalThis.__gaiusNettyBridge;
            if (!token && bridge && bridge.channels instanceof Map) {
              const active = new Map();
              bridge.channels.forEach(function(entry) {
                const candidate = entry && entry.connected && entry.currentCandidate;
                if (!candidate || candidate.direct || typeof candidate.url !== 'string') return;
                const previous = active.get(candidate.url);
                if (!previous || (!previous.token && candidate.token)) active.set(candidate.url, candidate);
              });
              if (active.size === 1) {
                const candidate = active.values().next().value;
                if (candidate && typeof candidate.token === 'string') token = candidate.token;
              }
            }
            return token && String(token).length ? String(token) : '';
            """)
    static native String bridgeToken();

    @JSBody(params = {"token"}, script = "globalThis.__gaiusBridgeToken = token;")
    static native void setBridgeTokenForSmoke(String token);

    private static void report(String kind) {
        try {
            reportBrowser(kind);
        } catch (UnsatisfiedLinkError ignored) {
            // JVM-side bytecode checks do not install TeaVM JS bodies.
        }
    }

    @JSBody(params = {"kind"}, script = """
            const counters = globalThis.__gaiusMinecraftCounters || (globalThis.__gaiusMinecraftCounters = {});
            const name = 'network.httpProxy.' + String(kind);
            counters[name] = (counters[name] || 0) + 1;
            """)
    private static native void reportBrowser(String kind);
}
