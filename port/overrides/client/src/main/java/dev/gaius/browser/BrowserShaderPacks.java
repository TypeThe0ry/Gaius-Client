package dev.gaius.browser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.teavm.jso.JSBody;

/**
 * Vanilla-independent shader pack state and actions for the settings screen (Options, Video Settings, Shader
 * Packs...). The page script {@code window.__gaiusShaderPacks} owns the imported packs (IndexedDB)
 * and the selection; this class only reads its snapshot and forwards the screen's choices.
 */
public final class BrowserShaderPacks {
    /** Value of the "no shader pack" choice; the page script uses the same id. */
    public static final String OFF = "OFF";

    private BrowserShaderPacks() {
    }

    public record Pack(String id, String name) {
    }

    /** One reading of the page state; {@code revision} changes whenever any of it does. */
    public record Snapshot(boolean available, String selected, int revision, String message, String messageName,
            List<Pack> packs) {
    }

    public static Snapshot snapshot() {
        String json = snapshotJson();
        List<Pack> packs = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return new Snapshot(false, OFF, 0, "", "", packs);
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("packs")) {
                JsonObject pack = element.getAsJsonObject();
                packs.add(new Pack(pack.get("id").getAsString(), pack.get("name").getAsString()));
            }
            return new Snapshot(root.get("available").getAsBoolean(), root.get("selected").getAsString(),
                    root.get("revision").getAsInt(), root.get("message").getAsString(),
                    root.get("messageName").getAsString(), packs);
        } catch (RuntimeException ignored) {
            return new Snapshot(false, OFF, 0, "", "", new ArrayList<>());
        }
    }

    /** True where the page offers shader packs (the WebGPU renderer). */
    public static boolean available() {
        return snapshot().available();
    }

    @JSBody(params = {"id"}, script = "var s = globalThis.__gaiusShaderPacks;"
            + "if (s && typeof s.select === 'function') { s.select(id); }")
    public static native void select(String id);

    @JSBody(script = "var s = globalThis.__gaiusShaderPacks;"
            + "if (s && typeof s.openPicker === 'function') { s.openPicker(); }")
    public static native void openPicker();

    /** While the screen is open the page reports messages as codes for the native UI to translate. */
    @JSBody(params = {"open"}, script = "var s = globalThis.__gaiusShaderPacks;"
            + "if (s && typeof s.setScreenOpen === 'function') { s.setScreenOpen(open); }")
    public static native void setScreenOpen(boolean open);

    @JSBody(script = "var s = globalThis.__gaiusShaderPacks;"
            + "return s && typeof s.snapshot === 'function' ? s.snapshot() : '';")
    private static native String snapshotJson();
}
