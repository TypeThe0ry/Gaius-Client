package dev.gaius.browser;

import net.minecraft.server.WorldStem;

/** Vanilla API seam for the 26.3 WorldStem signatures used by the singleplayer handoff. */
final class BrowserWorldLoaderCompat {
    private BrowserWorldLoaderCompat() {
    }

    static long seed(WorldStem stem) {
        return stem.worldDataAndGenSettings().genSettings().options().seed();
    }
}
