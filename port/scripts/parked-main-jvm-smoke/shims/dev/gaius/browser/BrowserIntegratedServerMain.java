package dev.gaius.browser;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * JVM shim of the two entry points the patched {@code net.minecraft.server.Main.main} calls before
 * it reads the world. resumeAfterBootstrap behaves like the parked prewarm of the browser build:
 * it is reached after vanilla Bootstrap, waits, and re-parses the real arguments with vanilla's
 * parser. It then checks the result and ends Main before it touches server.properties or a world.
 */
public final class BrowserIntegratedServerMain {
    public static final String PARKED_WORLD = "gaius-parked-prewarm";
    public static final String REAL_WORLD = "parked-smoke-world";
    static final long PARK_MILLIS = 250L;

    private BrowserIntegratedServerMain() {
    }

    /** Ends the smoke's Main run once the parked handoff was checked. */
    public static final class Complete extends RuntimeException {
        public Complete(String detail) {
            super(detail);
        }
    }

    public static OptionSet resumeAfterBootstrap(OptionParser parser, OptionSet options)
            throws Exception {
        String phases = BrowserStartupScheduler.PHASES.toString().trim();
        require(phases.equals("bootstrap-complete bootstrap-validated"),
                "resumeAfterBootstrap must follow Bootstrap.bootStrap and Bootstrap.validate: " + phases);
        require(PARKED_WORLD.equals(options.valueOf("world")),
                "the first parse must carry the parked placeholder world, got " + options.valueOf("world"));
        requireBound(Attributes.MAX_HEALTH, "minecraft:max_health");
        requireBound(Attributes.MOVEMENT_SPEED, "minecraft:movement_speed");
        requireBound(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.STONE), "minecraft:stone (block)");
        requireBound(BuiltInRegistries.ITEM.wrapAsHolder(Items.DIAMOND), "minecraft:diamond (item)");
        // The parked interval: in the browser the start handshake arrives here.
        Thread.sleep(PARK_MILLIS);
        OptionSet real = parser.parse(
                "--nogui", "--universe", "/gaius/saves", "--world", REAL_WORLD, "--port", "25565");
        require(REAL_WORLD.equals(real.valueOf("world")),
                "the re-parse must carry the real world, got " + real.valueOf("world"));
        require(String.valueOf(real.valueOf("universe")).endsWith("saves"),
                "the re-parse lost the universe option: " + real.valueOf("universe"));
        require(real.has("nogui"), "the re-parse lost --nogui");
        requireBound(Attributes.MAX_HEALTH, "minecraft:max_health (after the park)");
        throw new Complete("PARKED_MAIN_JVM_SMOKE PASS phases=[" + phases + "] world=" + real.valueOf("world")
                + " holders=bound parkMillis=" + PARK_MILLIS);
    }

    public static void rethrowStartupFailure(Throwable exception) {
        if (exception instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (exception instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(exception);
    }

    private static void requireBound(Holder<?> holder, String id) {
        require(holder != null && holder.isBound() && holder.value() != null,
                "built-in holder " + id + " is unbound after vanilla Bootstrap");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }
}
