package dev.gaius.browser;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Carries the initial game rules of a freshly created world from the client page to the
 * integrated-server Worker.
 *
 * <p>Where a profile's {@code LevelSettings} holds no game rules, the Create World screen hands
 * them to {@code Minecraft.doWorldLoad} as a separate {@code Optional<GameRules>}, and
 * {@code MinecraftServer} applies that value with {@code GameRules.setAll} before it saves them as
 * world data. The saved level does not carry them, and the Worker runs the dedicated server
 * {@code Main}, which has no such argument, so the value would otherwise be dropped at the
 * handoff. The rules cross the Worker boundary as the JSON form of vanilla's own
 * {@link GameRules#codec codec}; the server side decodes them against the world's enabled
 * features and passes the result to the {@code DedicatedServer} constructor where vanilla's
 * integrated server receives it. A profile whose {@code LevelSettings} holds the rules delivers
 * them through {@code level.dat} and never reaches this class.
 *
 * <p>This class depends on vanilla and Gson only (no JSO), so a plain JVM can exercise it.
 */
public final class BrowserGameRulesHandoff {
    public static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_MEMBERS = 512;
    private BrowserGameRulesHandoff() {
    }

    /** The JSON text of the rules, or an empty string when vanilla passed none. */
    public static String encode(Optional<GameRules> rules) {
        if (rules.isEmpty()) {
            return "";
        }
        // Encoding writes every rule of the instance; the feature set only matters when decoding.
        JsonElement json = GameRules.codec(FeatureFlagSet.of())
                .encodeStart(JsonOps.INSTANCE, rules.get())
                .getOrThrow(message -> new IllegalStateException("Game rules could not be encoded: " + message));
        return json.toString();
    }

    /**
     * The rules of {@link #encode}, decoded against the features enabled for the world, or empty
     * for an empty text. A text that does not decode is an error: silently starting the world with
     * default rules is the defect this class exists to prevent.
     */
    public static Optional<GameRules> decode(String text, FeatureFlagSet enabledFeatures) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalStateException("Initial game rules exceed " + MAX_BYTES + " UTF-8 bytes");
        }
        JsonElement json;
        try {
            json = JsonParser.parseString(text);
            validateShape(json, 0, new int[] {0});
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException) throw (IllegalStateException) exception;
            throw new IllegalStateException("Initial game rules are not valid JSON", exception);
        }
        return Optional.of(GameRules.codec(enabledFeatures)
                .parse(JsonOps.INSTANCE, json)
                .getOrThrow(message -> new IllegalStateException("Initial game rules are invalid: " + message)));
    }

    private static void validateShape(JsonElement value, int depth, int[] members) {
        if (depth > MAX_DEPTH) throw new IllegalStateException("Initial game rules JSON is too deep");
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (++members[0] > MAX_MEMBERS) throw new IllegalStateException("Too many game rule members");
                validateShape(entry.getValue(), depth + 1, members);
            }
        } else if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) validateShape(child, depth + 1, members);
        }
    }
}
