package dev.gaius.browser;

import net.minecraft.server.players.PlayerList;

/**
 * Minecraft 26.3 version of the PlayerList compatibility helper.
 *
 * <p>26.3 removed PlayerList.setAllowCommandsForAllPlayers(boolean) and the field behind it;
 * PlayerList.isOp(NameAndId) now ends in {@code iconst_0; ireturn}. ServerPatches263 (P7a)
 * replaces that constant with {@link #commandsAllowedForAllPlayers()}, so setting the flag here
 * restores the 26.2 behaviour: every player of the browser Worker server counts as an operator
 * and receives the dedicated server's operator permissions, without writing ops.json.
 */
public final class BrowserPlayerListCompat {
    private static volatile boolean commandsAllowedForAllPlayers;

    private BrowserPlayerListCompat() {}

    /** Lets the single local Worker player use commands without writing ops.json. */
    public static void allowCommandsForAllPlayers(PlayerList playerList) {
        if (playerList == null) {
            throw new IllegalArgumentException("playerList");
        }
        commandsAllowedForAllPlayers = true;
    }

    /** Read by the patched PlayerList.isOp in place of its final {@code false}. */
    public static boolean commandsAllowedForAllPlayers() {
        return commandsAllowedForAllPlayers;
    }
}
