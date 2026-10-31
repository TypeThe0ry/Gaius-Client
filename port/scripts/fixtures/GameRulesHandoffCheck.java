import com.mojang.serialization.Dynamic;
import dev.gaius.browser.BrowserGameRulesHandoff;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Plain-JVM check of the singleplayer game-rules handoff against one profile's vanilla jar.
 *
 * <p>The client page builds an {@code Optional<GameRules>} on the Create World screen; the
 * integrated-server Worker has to apply exactly those values (not the defaults) before the
 * levels load. This runs {@link BrowserGameRulesHandoff} over non-default rules the way both ends
 * use it and prints one {@code CHECK name=value} line per fact for the orchestrating smoke.
 *
 * <p>It also reports which vanilla channel carries game rules for the profile: a
 * {@code LevelSettings} that holds a {@code GameRules} reaches the Worker through level.dat
 * (verified here by reading a {@code game_rules} tag with vanilla's own
 * {@code LevelSettings.parse}); a {@code LevelSettings} without one does not, which is exactly the
 * case that needs the handoff.
 */
public final class GameRulesHandoffCheck {
    /** Bootstrap.bootStrap() reroutes System.out into the game log; the report needs the real stream. */
    private static final java.io.PrintStream REPORT = System.out;
    private static int checks;

    private GameRulesHandoffCheck() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        FeatureFlagSet features = FeatureFlags.DEFAULT_FLAGS;

        // The rules as the Create World screen holds them after the player changed some values.
        GameRules chosen = new GameRules(features);
        GameRules defaults = new GameRules(features);
        chosen.set(GameRules.RANDOM_TICK_SPEED, 7, null);
        chosen.set(GameRules.ADVANCE_TIME, false, null);
        chosen.set(GameRules.KEEP_INVENTORY, true, null);
        chosen.set(GameRules.MOB_GRIEFING, false, null);
        chosen.set(GameRules.SPREAD_VINES, false, null);
        chosen.set(GameRules.SPAWN_MOBS, false, null);
        chosen.set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 50, null);
        chosen.set(GameRules.MAX_ENTITY_CRAMMING, 5, null);
        int changed = 0;
        for (GameRule<?> rule : rules(chosen)) {
            if (!same(chosen, defaults, rule)) {
                changed++;
            }
        }
        require(changed == 8, "the fixture must differ from the defaults in all 8 rules, got " + changed);
        // CreateWorldScreen.onCreate copies the UI state's rules with the world's enabled features.
        GameRules sent = chosen.copy(features);

        // Client end: Optional<GameRules> -> text for the Worker start message.
        String text = BrowserGameRulesHandoff.encode(Optional.of(sent));
        require(!text.isEmpty() && text.startsWith("{"), "encoded rules must be a JSON object");
        require(text.contains("random_tick_speed"), "encoded rules lost random_tick_speed");
        require(BrowserGameRulesHandoff.encode(Optional.empty()).isEmpty(),
                "no rules must encode to the empty text");
        report("encodedBytes", text.length());

        // Server end: text -> Optional<GameRules> -> DedicatedServer constructor -> setAll.
        Optional<GameRules> received = BrowserGameRulesHandoff.decode(text, features);
        require(received.isPresent(), "the Worker lost the rules");
        GameRules server = new GameRules(features);
        require(!same(server, chosen, GameRules.RANDOM_TICK_SPEED),
                "the fixture must be distinguishable from a server that never received the rules");
        server.setAll(received.get(), null);
        int compared = 0;
        for (GameRule<?> rule : rules(chosen)) {
            require(same(server, chosen, rule), "rule " + rule + " differs after the handoff");
            compared++;
        }
        require(compared > 40, "too few rules compared: " + compared);
        report("rulesCompared", compared);
        require(server.get(GameRules.RANDOM_TICK_SPEED) == 7, "random_tick_speed not applied");
        require(!server.get(GameRules.ADVANCE_TIME), "advance_time not applied");
        require(server.get(GameRules.KEEP_INVENTORY), "keep_inventory not applied");
        require(server.get(GameRules.PLAYERS_SLEEPING_PERCENTAGE) == 50, "sleeping percentage not applied");

        // No rules sent (loading an existing world, or a start without the field): nothing to apply.
        require(BrowserGameRulesHandoff.decode("", features).isEmpty(), "empty text must decode to empty");
        require(BrowserGameRulesHandoff.decode(null, features).isEmpty(), "null text must decode to empty");

        // A text that does not decode must fail loudly, never fall back to default rules.
        expectFailure("not json", () -> BrowserGameRulesHandoff.decode("{not json", features));
        expectFailure("unknown rule", () ->
                BrowserGameRulesHandoff.decode("{\"minecraft:no_such_rule\":true}", features));
        expectFailure("wrong value type", () ->
                BrowserGameRulesHandoff.decode("{\"minecraft:random_tick_speed\":\"fast\"}", features));

        // Which channel does the profile's vanilla handoff use?
        Field carried = null;
        for (Field field : LevelSettings.class.getDeclaredFields()) {
            if (field.getType() == GameRules.class) {
                carried = field;
            }
        }
        boolean levelSettingsHoldRules = carried != null;
        report("levelSettingsHoldGameRules", levelSettingsHoldRules ? 1 : 0);
        if (levelSettingsHoldRules) {
            // The saved level carries the rules: vanilla's own reader must restore them from the
            // game_rules tag that PrimaryLevelData writes next to the other level settings.
            CompoundTag level = new CompoundTag();
            level.putString("LevelName", "rules");
            level.putInt("GameType", 0);
            level.putBoolean("hardcore", false);
            level.putInt("Difficulty", 2);
            level.putBoolean("allowCommands", true);
            Tag rulesTag = GameRules.codec(features).encodeStart(NbtOps.INSTANCE, sent).getOrThrow();
            level.put("game_rules", rulesTag);
            LevelSettings parsed = LevelSettings.parse(
                    new Dynamic<>(NbtOps.INSTANCE, level), WorldDataConfiguration.DEFAULT);
            Method accessor = LevelSettings.class.getMethod("gameRules");
            GameRules fromLevel = (GameRules) accessor.invoke(parsed);
            for (GameRule<?> rule : rules(chosen)) {
                require(same(fromLevel, chosen, rule), "level.dat reader lost rule " + rule);
            }
            report("levelDatCarriesRules", 1);
        }
        REPORT.println("CHECKS " + checks);
    }

    private static List<GameRule<?>> rules(GameRules rules) {
        return rules.availableRules().collect(Collectors.toList());
    }

    private static <T> boolean same(GameRules a, GameRules b, GameRule<T> rule) {
        return a.get(rule).equals(b.get(rule));
    }

    private static void expectFailure(String what, Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            checks++;
            return;
        }
        throw new AssertionError(what + " must fail the handoff");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }

    private static void report(String name, int value) {
        REPORT.println("CHECK " + name + "=" + value);
    }
}
