package dev.gaius.browser;

import net.minecraft.server.WorldStem;

/** Vanilla API seam for the legacy 1.21.11 WorldStem signatures used by the singleplayer handoff. */
final class BrowserWorldLoaderCompat {
    private BrowserWorldLoaderCompat() {
    }

    static long seed(WorldStem stem) {
        return stem.worldData().worldGenOptions().seed();
    }
}
