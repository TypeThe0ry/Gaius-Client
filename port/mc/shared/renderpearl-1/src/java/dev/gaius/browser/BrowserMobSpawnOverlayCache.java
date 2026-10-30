package dev.gaius.browser;

import net.minecraft.world.level.biome.MobSpawnSettings;

/**
 * Identity cache for {@code MobSpawnSettingsModifier.Overlay.apply}, patched in by
 * {@code ServerPatches263.patchMobSpawnOverlayCache}.
 *
 * <p>{@code NaturalSpawner.createState} reads the NATURAL_MOB_SPAWNS environment attribute for
 * every entity on every server tick, and each read overlays the biome spawn settings onto the
 * dimension ones ({@code containsAll} over their categories and spawn costs, or a rebuilt
 * {@link MobSpawnSettings}). Both inputs are immutable registry values and the overlay is a pure
 * function of them, so the result for a pair of instances never changes. A small direct-mapped
 * table keyed by identity serves the handful of biome/dimension pairs around the players.
 */
public final class BrowserMobSpawnOverlayCache {
    private static final int SIZE = 32;
    private static final MobSpawnSettings[] FIRST = new MobSpawnSettings[SIZE];
    private static final MobSpawnSettings[] SECOND = new MobSpawnSettings[SIZE];
    private static final MobSpawnSettings[] RESULT = new MobSpawnSettings[SIZE];

    private BrowserMobSpawnOverlayCache() {
    }

    /** Returns the cached overlay of this pair, or {@code null} when it must be computed. */
    public static MobSpawnSettings lookup(MobSpawnSettings first, MobSpawnSettings second) {
        int slot = slot(first, second);
        return first != null && FIRST[slot] == first && SECOND[slot] == second ? RESULT[slot] : null;
    }

    /** Remembers the overlay of this pair and returns it. */
    public static MobSpawnSettings store(
            MobSpawnSettings first, MobSpawnSettings second, MobSpawnSettings result) {
        if (first != null && second != null && result != null) {
            int slot = slot(first, second);
            FIRST[slot] = first;
            SECOND[slot] = second;
            RESULT[slot] = result;
        }
        return result;
    }

    private static int slot(MobSpawnSettings first, MobSpawnSettings second) {
        int hash = System.identityHashCode(first) * 31 + System.identityHashCode(second);
        return (hash ^ (hash >>> 16)) & (SIZE - 1);
    }
}
