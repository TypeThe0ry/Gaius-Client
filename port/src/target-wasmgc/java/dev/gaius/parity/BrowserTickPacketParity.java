package dev.gaius.parity;

import java.util.Properties;
import net.minecraft.server.MinecraftServer;
import org.teavm.jso.JSBody;

/** Linked only by the explicit WasmGC TELEMETRY patch, never the JS target. */
public final class BrowserTickPacketParity {
    private static String configuredScript;
    private BrowserTickPacketParity() {
    }

    public static void properties(Properties properties) {
        String json = request();
        if (json == null || json.isEmpty()) return;
        // Vanilla may build server properties more than once during browser startup.
        // The recorder is process-wide and must only be configured once; accepting the
        // identical second callback avoids the false "parity already configured" crash.
        if (json.equals(configuredScript)) return;
        TickPacketRecorder.configure(json, BrowserTickPacketParity::publish);
        configuredScript = json;
        var script = TickPacketRecorder.script();
        properties.setProperty("level-seed", script.get("seed").getAsString());
        properties.setProperty("view-distance", script.get("viewDistance").getAsString());
        properties.setProperty("simulation-distance", script.get("simulationDistance").getAsString());
        properties.setProperty("gamemode", "creative");
        properties.setProperty("difficulty", "peaceful");
        properties.setProperty("online-mode", "false");
        properties.setProperty("level-type", "minecraft:normal");
        // Without the parity schedule (BrowserTickParityExecutors) no probe is installed and the first
        // settle fails the capture with a failure row instead of recording an unordered history.
        BrowserSettleProbe probe = BrowserTickParityExecutors.probe(BrowserTickPacketParity::publishProbe);
        if (probe != null) TickPacketRecorder.settleProbe(probe);
        publishProbe("{\"phase\":\"configured\",\"executor\":\"" + (probe != null ? "serial-fifo" : "absent")
                + "\",\"requested\":" + BrowserTickParityExecutors.requested() + "}");
    }

    public static void prepare(MinecraftServer server) {
        if (!TickPacketRecorder.enabled) return;
        BrowserSettleProbe probe = BrowserTickParityExecutors.probe(BrowserTickPacketParity::publishProbe);
        if (probe != null) {
            try {
                probe.begin();
            } catch (RuntimeException refused) {
                TickPacketRecorder.failure("tick parity probe: " + refused.getMessage());
                throw refused;
            }
        }
        TickParityScenario.prepare(server);
        publishProbe(chunkOrderTelemetry());
    }

    @JSBody(script = "return globalThis.__gaiusTickParityDebug === true ? globalThis.__gaiusTickParityScript : null;")
    private static native String request();

    @JSBody(params = {"json"}, script = "globalThis.postMessage({type:'tick-parity-event',json:json});")
    private static native void publish(String json);

    /** Probe reports travel beside the event rows, so the compared JSONL stays the JVM's schema. */
    @JSBody(params = {"json"}, script = "globalThis.postMessage({type:'tick-parity-probe',json:json});")
    private static native void publishProbe(String json);

    @JSBody(script = "var r = globalThis.__gaiusWorldgenStats && globalThis.__gaiusWorldgenStats.chunkGenerationOrder;"
            + "return JSON.stringify({phase:'chunk-order', entries:r || []});")
    private static native String chunkOrderTelemetry();
}
