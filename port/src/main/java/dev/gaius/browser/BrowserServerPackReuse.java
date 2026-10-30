package dev.gaius.browser;

import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.resources.server.PackLoadFeedback;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.teavm.jso.JSBody;

/** Reuses one verified server pack across the bounded cold-pack reconnect. */
public final class BrowserServerPackReuse {
    private static final Set<UUID> EARLY_APPLIED = new HashSet<>();

    private BrowserServerPackReuse() {
    }

    public static boolean handleRequiredPack(
            Connection connection, ClientboundResourcePackPushPacket packet, URL parsedUrl) {
        UUID id = packet.id();
        String hash = packet.hash();
        if (!BrowserMultiplayerRecovery.reusePreservedServerPack(id, parsedUrl, hash)) {
            BrowserMultiplayerRecovery.rememberRequiredServerPack(id, parsedUrl, hash);
            // Let DownloadedPackSource report success only after download and reload.
            report("server-pack-download-required", id + " " + hash);
            return false;
        }

        EARLY_APPLIED.add(id);
        send(connection, id, ServerboundResourcePackPacket.Action.ACCEPTED);
        send(connection, id, ServerboundResourcePackPacket.Action.DOWNLOADED);
        send(connection, id, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        report("server-pack-reused", id + " " + hash);
        return true;
    }

    public static boolean keepServerPackForRecovery() {
        boolean keep = BrowserMultiplayerRecovery.keepServerPackForRecovery();
        if (keep) {
            report("server-pack-preserved", "cold-pack reconnect");
        } else {
            BrowserMultiplayerRecovery.clearServerPackReuse();
            EARLY_APPLIED.clear();
        }
        return keep;
    }

    public static boolean suppressEarlyApplied(
            UUID id, PackLoadFeedback.FinalResult result) {
        if (result != PackLoadFeedback.FinalResult.APPLIED) {
            EARLY_APPLIED.remove(id);
            return false;
        }
        return EARLY_APPLIED.remove(id);
    }

    private static void send(
            Connection connection,
            UUID id,
            ServerboundResourcePackPacket.Action action) {
        connection.send(new ServerboundResourcePackPacket(id, action));
    }

    @JSBody(params = {"event", "detail"}, script = """
            try {
              const counters = globalThis.__gaiusMinecraftCounters ||
                (globalThis.__gaiusMinecraftCounters = {});
              const key = 'multiplayer-recovery:' + event;
              counters[key] = (counters[key] || 0) + 1;
              const events = globalThis.__gaiusMinecraftEvents ||
                (globalThis.__gaiusMinecraftEvents = []);
              events.push({event: key, detail: String(detail || ''), at: Date.now()});
              if (events.length > 500) events.splice(0, events.length - 500);
            } catch (ignored) {}
            """)
    private static native void report(String event, String detail);
}
