import dev.gaius.browser.BrowserIntegratedServerMain;
import java.lang.reflect.Field;
import java.util.Collection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;

/** Executes the extracted production scheduler; only the vanilla walk and browser hooks are doubles. */
public final class ServerTrackingTest {
    private static final MinecraftServer server = new MinecraftServer();

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ServerPlayer player(ServerChunkCache cache) {
        var player = new ServerPlayer(cache);
        server.getPlayerList().getPlayers().add(player);
        return player;
    }

    private static void request(ServerPlayer player, double x) {
        player.x = x;
        BrowserIntegratedServerMain.moveServerPlayerChunkTracking(player.level().getChunkSource(), player);
    }

    private static void flush() {
        BrowserIntegratedServerMain.flushForTest();
    }

    // Isolate stale-center defects from the old time throttle in original-source controls.
    private static void oldTimestamp(long value) throws Exception {
        try {
            Field field = BrowserIntegratedServerMain.class.getDeclaredField("lastTrackedAtNanos");
            field.setAccessible(true); field.setLong(null, value);
        } catch (NoSuchFieldException absent) { /* the corrected source has no throttle */ }
    }

    private static void clearOldTimestamp() throws Exception { oldTimestamp(0L); }

    private static int pending() throws Exception {
        try {
            Field field = BrowserIntegratedServerMain.class.getDeclaredField("pendingTrackedPlayers");
            field.setAccessible(true); return ((Collection<?>) field.get(null)).size();
        } catch (NoSuchFieldException absent) {
            Field field = BrowserIntegratedServerMain.class.getDeclaredField("pendingTrackedPlayer");
            field.setAccessible(true); return field.get(null) == null ? 0 : 1;
        }
    }

    public static void main(String[] args) throws Exception {
        BrowserIntegratedServerMain.installForTest(server);
        var cache = new ServerChunkCache();
        var p = player(cache);
        switch (args[0]) {
            case "cycle" -> {
                request(p, 0); flush(); clearOldTimestamp();
                request(p, 16); request(p, 0); flush(); clearOldTimestamp();
                request(p, 16); flush();
                check(p.processedX == 16, "A to B to A to B must end with B tracked");
            }
            case "latest" -> {
                request(p, 0); flush();
                oldTimestamp(Long.MAX_VALUE);
                request(p, 16); flush();
                check(p.processedX == 16, "final crossing must flush without another packet or time delay");
            }
            case "yield-before", "yield-after" -> {
                request(p, 0); flush(); clearOldTimestamp();
                cache.captureBeforeYield = args[0].equals("yield-before");
                cache.yield = () -> {
                    request(p, 0); flush();
                    check(cache.depth == 1, "yield must not reenter vanilla tracking");
                };
                request(p, 16); flush(); clearOldTimestamp();
                // The final request must override the queued A even if the walk processed A.
                request(p, 16); flush();
                check(p.processedX == 16, "live/captured center mismatch must not suppress the next B");
                check(cache.maxDepth == 1 && pending() == 0, "serialized latest request must drain");
            }
            case "yield-return" -> {
                request(p, 0); flush(); clearOldTimestamp();
                cache.captureBeforeYield = true;
                cache.yield = () -> { request(p, 0); flush(); };
                request(p, 16); flush();
                check(pending() == 1, "request received during a walk must survive its completion");
                flush();
                check(p.processedX == 0 && cache.maxDepth == 1, "next tick must process yielded return to A");
            }
            case "multiplayer" -> {
                var q = player(cache);
                request(p, 16); request(q, 32); request(p, 48); flush();
                check(p.processedX == 48 && q.processedX == 32,
                        "one player's latest request must not overwrite another player's pending work");
                check(cache.calls == 2 && pending() == 0, "coalesce per player in a bounded batch");
            }
            case "yield-multiplayer" -> {
                var q = player(cache);
                cache.captureBeforeYield = true;
                cache.yield = () -> { request(p, 32); request(q, 48); flush(); };
                request(p, 16); flush();
                check(cache.calls == 1, "yield must not start another player's walk");
                flush();
                check(p.processedX == 32 && q.processedX == 48 && cache.maxDepth == 1,
                        "all players' requests received during yield must survive");
            }
            case "disconnect" -> {
                request(p, 16); server.getPlayerList().getPlayers().remove(p); flush();
                check(cache.calls == 0 && pending() == 0, "departed players must not be retained or tracked");
            }
            case "yield-disconnect" -> {
                var q = player(cache);
                cache.yield = () -> {
                    request(q, 32);
                    server.getPlayerList().getPlayers().remove(q);
                    flush();
                };
                request(p, 16); flush();
                check(cache.calls == 1 && pending() == 0,
                        "tick during yield must release departed pending players");
            }
            case "connection-churn" -> {
                for (int index = 0; index < 50; index++) {
                    var q = player(cache); request(q, 16);
                    check(pending() == 1, "pending admission must stay bounded to live players");
                    server.getPlayerList().getPlayers().remove(q);
                }
                flush(); check(pending() == 0 && cache.calls == 0, "churn must not retain departed work");
            }
            case "reset" -> {
                request(p, 16); BrowserIntegratedServerMain.installForTest(server);
                check(pending() == 0, "server registration must reset pending tracking");
                request(p, 32); flush();
                check(p.processedX == 32, "reset must allow new tracking work");
            }
            case "dimension" -> {
                var next = new ServerChunkCache();
                request(p, 16); p.level().cache = next; flush();
                check(cache.calls == 0 && pending() == 0, "obsolete level cache must not move the player");
                check(next.calls == 1 && p.processedX == 16,
                        "pending request must use the current level without requiring another packet");
            }
            case "stop" -> {
                request(p, 16); BrowserIntegratedServerMain.markIntegratedServerStopped(server);
                check(pending() == 0, "server exit must release all pending players");
                flush(); check(cache.calls == 0, "stopped server must not drain tracking");
            }
            case "direct" -> {
                BrowserIntegratedServerMain.workerForTest = false;
                request(p, 16);
                check(cache.calls == 1 && p.processedX == 16 && pending() == 0,
                        "non-Worker call must retain immediate vanilla behavior");
            }
            case "exception" -> {
                cache.yield = () -> { throw new IllegalStateException("walk failed"); };
                request(p, 16);
                try { flush(); throw new AssertionError("walk failure hidden"); }
                catch (IllegalStateException expected) { check(expected.getMessage().equals("walk failed"), "failure identity"); }
                request(p, 32); flush();
                check(p.processedX == 32 && cache.maxDepth == 1, "failed walk must release serialization guard");
            }
            default -> throw new AssertionError("unknown scenario");
        }
        System.out.println("PASS: " + args[0]);
    }
}
