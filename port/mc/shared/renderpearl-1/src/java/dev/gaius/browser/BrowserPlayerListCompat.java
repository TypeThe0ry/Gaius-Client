package dev.gaius.browser;

import net.minecraft.server.players.PlayerList;

/**
 * Minecraft 26.3 version of the PlayerList compatibility helper.
 *
 * <p>26.3 removed PlayerList.setAllowCommandsForAllPlayers(boolean) and the field behind it;
 * PlayerList.isOp(NameAndId) now ends in {@code iconst_0; ireturn}. ServerPatches263 (P7a)
 * replaces that constant with {@link #commandsAllowedForAllPlayers()}, so setting the flag here
 * restores the 26.2 behaviour: the single local player counts as an operator when the world
 * allows commands, without writing ops.json.
 */
public final class BrowserPlayerListCompat {
    private static volatile boolean commandsAllowedForAllPlayers;

    private BrowserPlayerListCompat() {}

    /** Applies the world's command setting to the single local Worker player. */
    public static void setAllowCommandsForAllPlayers(PlayerList playerList, boolean allowed) {
        if (playerList == null) {
            throw new IllegalArgumentException("playerList");
        }
        commandsAllowedForAllPlayers = allowed;
    }

    /** Read by the patched PlayerList.isOp in place of its final {@code false}. */
    public static boolean commandsAllowedForAllPlayers() {
        return commandsAllowedForAllPlayers;
    }
}
