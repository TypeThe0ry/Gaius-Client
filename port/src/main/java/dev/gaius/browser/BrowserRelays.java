package dev.gaius.browser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.teavm.jso.JSBody;

/**
 * Relay list behind {@link BrowserRelaysScreen}: user relays persisted in localStorage
 * ({@code gaius.bridgeNodes}) plus the build's built-in relays, which can only be disabled
 * ({@code gaius.disabledRelays}). The page script {@code window.__gaiusRelays} owns the storage;
 * the WebSocket channel reads the same keys whenever it opens a connection.
 */
public final class BrowserRelays {
    public static final String USE_BOTH = "both";
    public static final String USE_MULTIPLAYER = "multiplayer";
    public static final String USE_LAN = "lan";

    private BrowserRelays() {
    }

    /** One relay row. {@code builtIn} rows come from the build and cannot be edited. */
    public record Relay(String url, String name, String use, boolean builtIn, boolean enabled) {
        public boolean secure() {
            return url.startsWith("wss:");
        }
    }

    public static boolean available() {
        return hasApi();
    }

    public static List<Relay> list() {
        List<Relay> relays = new ArrayList<>();
        String json = listJson();
        if (json == null || json.isEmpty()) {
            return relays;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return relays;
            }
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String url = string(object, "url");
                if (url.isEmpty()) {
                    continue;
                }
                relays.add(new Relay(url, string(object, "name"), normalizeUse(string(object, "use")),
                        "builtin".equals(string(object, "source")),
                        !object.has("enabled") || object.get("enabled").getAsBoolean()));
            }
        } catch (RuntimeException ignored) {
            // A damaged list shows as empty; saving rewrites it.
        }
        return relays;
    }

    /** Persists the user relays in this order (highest priority first). */
    public static String saveUser(List<Relay> relays) {
        JsonArray array = new JsonArray();
        for (Relay relay : relays) {
            if (relay.builtIn()) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("url", relay.url());
            object.addProperty("name", relay.name());
            object.addProperty("use", relay.use());
            array.add(object);
        }
        return saveUserJson(array.toString());
    }

    /** Normalized ws:/wss: tunnel URL, or an empty string when the address is not usable. */
    public static String normalize(String url) {
        String normalized = normalizeJs(url == null ? "" : url.trim());
        return normalized == null ? "" : normalized;
    }

    public static void setBuiltInEnabled(String url, boolean enabled) {
        setEnabledJs(url, enabled);
    }

    public static String normalizeUse(String use) {
        if (USE_MULTIPLAYER.equals(use) || USE_LAN.equals(use)) {
            return use;
        }
        return USE_BOTH;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    @JSBody(script = "return !!(globalThis.__gaiusRelays && typeof globalThis.__gaiusRelays.list === 'function');")
    private static native boolean hasApi();

    @JSBody(script = """
            const relays = globalThis.__gaiusRelays;
            try {
              return relays && typeof relays.list === 'function' ? String(relays.list()) : '[]';
            } catch (error) {
              return '[]';
            }
            """)
    private static native String listJson();

    @JSBody(params = "json", script = """
            const relays = globalThis.__gaiusRelays;
            try {
              return relays && typeof relays.saveUser === 'function'
                ? String(relays.saveUser(String(json)) || '') : 'Relay storage is unavailable';
            } catch (error) {
              return String(error && error.message || error);
            }
            """)
    private static native String saveUserJson(String json);

    @JSBody(params = "url", script = """
            const relays = globalThis.__gaiusRelays;
            try {
              return relays && typeof relays.normalize === 'function'
                ? String(relays.normalize(String(url)) || '') : '';
            } catch (error) {
              return '';
            }
            """)
    private static native String normalizeJs(String url);

    @JSBody(params = {"url", "enabled"}, script = """
            const relays = globalThis.__gaiusRelays;
            try {
              if (relays && typeof relays.setEnabled === 'function') relays.setEnabled(String(url), !!enabled);
            } catch (ignored) {
              // Storage can be unavailable; the row then keeps its previous state.
            }
            """)
    private static native void setEnabledJs(String url, boolean enabled);
}
