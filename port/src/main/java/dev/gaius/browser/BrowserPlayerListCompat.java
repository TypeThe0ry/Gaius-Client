package dev.gaius.browser;

import net.minecraft.server.players.PlayerList;

/**
 * Version-specific PlayerList operations used by the browser Worker server.
 *
 * <p>Minecraft 26.2 keeps the vanilla "allow commands for all players" setter.
 * Profiles whose PlayerList differs provide a replacement with the same
 * relative path in a source set of port/mc/shared (selected by the profile).
 */
public final class BrowserPlayerListCompat {
    private BrowserPlayerListCompat() {}

    /** Applies the world's command setting to the single local Worker player. */
    public static void setAllowCommandsForAllPlayers(PlayerList playerList, boolean allowed) {
        playerList.setAllowCommandsForAllPlayers(allowed);
    }
}
