package dev.gaius.browser;

import java.util.Locale;
import org.teavm.jso.JSBody;

/**
 * Accepts {@code ws://} and {@code wss://} addresses in the Add Server / Direct Connection
 * screens. Vanilla's address parser rejects a scheme, so the Done button stayed disabled. The
 * patched {@code ServerAddress} methods pass the typed text through here first: the scheme and any
 * path are stripped for vanilla's parser, and the full URL is remembered so the browser channel
 * can open that endpoint directly. Every other address is returned unchanged.
 */
public final class BrowserServerAddress {
    private BrowserServerAddress() {
    }

    /** Returns the address without a ws:// or wss:// scheme and path; other text is untouched. */
    public static String normalize(String address) {
        String authority = authorityOf(address);
        return authority == null ? address : authority;
    }

    /** Like {@link #normalize} and also records the URL for the connection that follows. */
    public static String normalizeAndRemember(String address) {
        String authority = authorityOf(address);
        if (authority == null) {
            return address;
        }
        String host = hostOf(authority);
        if (!host.isEmpty()) {
            remember(host.toLowerCase(Locale.ROOT), address.trim());
        }
        return authority;
    }

    private static String authorityOf(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        int schemeLength = lower.startsWith("wss://") ? 6 : lower.startsWith("ws://") ? 5 : 0;
        if (schemeLength == 0) {
            return null;
        }
        String rest = trimmed.substring(schemeLength);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        rest = rest.substring(0, end);
        int at = rest.lastIndexOf('@');
        return at >= 0 ? rest.substring(at + 1) : rest;
    }

    private static String hostOf(String authority) {
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close > 1 ? authority.substring(1, close) : authority;
        }
        int colon = authority.indexOf(':');
        return colon > 0 && colon == authority.lastIndexOf(':')
                ? authority.substring(0, colon)
                : authority;
    }

    @JSBody(params = {"host", "url"}, script = """
            try {
              const targets = globalThis.__gaiusWsTargets || (globalThis.__gaiusWsTargets = {});
              targets[host] = url;
            } catch (ignored) {
            }
            """)
    private static native void remember(String host, String url);

}
