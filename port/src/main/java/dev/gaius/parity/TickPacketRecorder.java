package dev.gaius.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Opt-in acquisition at the vanilla application boundary, not transport receipt time. */
public final class TickPacketRecorder {
    public static volatile boolean enabled;
    private static final int MAX_EVENTS = 200000;
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final Map<Connection, Link> links = new IdentityHashMap<>();
    private static final List<JsonObject> events = new ArrayList<>();
    private static JsonObject script;
    private static Consumer<String> sink;
    private static MinecraftServer server;
    private static Runnable tickDriver;
    private static long sequence;
    private static volatile int tick;
    private static int bytes;
    private static int actionIndex;
    private static boolean applying;
    private static boolean complete;
    private static TickParitySettleProbe settleProbe;
    private static volatile boolean settling;
    private static Runnable backgroundHold;

    private TickPacketRecorder() {
    }

    public static void configure(String json, Consumer<String> writer) {
        if (enabled || server != null) throw new IllegalStateException("parity already configured");
        JsonObject request = JsonParser.parseString(json).getAsJsonObject();
        if (request.get("schema").getAsInt() != 1 || request.get("ticks").getAsInt() < 1
                || request.get("ticks").getAsInt() > 10000) {
            throw new IllegalArgumentException("invalid tick parity script");
        }
        if (!request.get("profile").getAsString().matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || !request.get("player").getAsString().matches("[A-Za-z0-9_]{1,16}")
                || request.get("viewDistance").getAsInt() < 2 || request.get("viewDistance").getAsInt() > 8
                || request.get("simulationDistance").getAsInt() < 2 || request.get("simulationDistance").getAsInt() > 8
                || request.get("preloadRadius").getAsInt() < request.get("viewDistance").getAsInt()
                || request.get("preloadRadius").getAsInt() > 8) {
            throw new IllegalArgumentException("invalid parity scenario bounds");
        }
        String seed = request.get("seed").getAsString();
        if (!Long.toString(Long.parseLong(seed)).equals(seed)) throw new IllegalArgumentException("noncanonical seed");
        if (!request.has("settle") || !request.get("settle").getAsString().equals("quiescent")) {
            throw new IllegalArgumentException("unsupported settle strategy");
        }
        if (!request.has("teleportAcknowledgement")
                || !List.of("id", "id-position").contains(request.get("teleportAcknowledgement").getAsString())) {
            throw new IllegalArgumentException("unsupported teleport acknowledgement layout");
        }
        JsonObject spawn = request.getAsJsonObject("spawn");
        for (String axis : List.of("x", "y", "z")) {
            int coordinate = spawn.get(axis).getAsInt();
            if (coordinate < -30000000 || coordinate > 30000000) throw new IllegalArgumentException("spawn out of bounds");
        }
        int previous = 0;
        for (var value : request.getAsJsonArray("actions")) {
            JsonObject action = value.getAsJsonObject();
            int target = action.get("tick").getAsInt();
            if (target < 1 || target < previous || target > request.get("ticks").getAsInt()
                    || action.get("connection").getAsInt() != 0 || unhex(action.get("wire").getAsString()).length == 0) {
                throw new IllegalArgumentException("unordered or invalid parity action");
            }
            previous = target;
        }
        script = request;
        sink = writer;
        enabled = true;
        JsonObject header = event("session");
        header.add("script", script.deepCopy());
        emit(header);
    }

    public static JsonObject script() { return script; }
    public static int tick() { return tick; }
    public static void driver(Runnable driver) { tickDriver = driver; }

    /** The capture host's executor probe (TickParitySettleProbe); without one no capture starts. */
    public static void settleProbe(TickParitySettleProbe probe) { settleProbe = probe; }

    /**
     * Drains vanilla's asynchronous chunk work on the server thread: one main-thread chunk task runs
     * only while the background and IO executors are idle and the background workers are held, until
     * no executor has work left. Async generation, lighting and IO requested so far are therefore
     * complete, in a serial order, at an exact tick boundary instead of whenever a worker thread
     * happens to finish. The workers stay held afterwards until the next settle, so they never run
     * concurrently with the server thread's vanilla code.
     */
    public static void settle(MinecraftServer current, String phase) {
        if (!enabled) return;
        if (settleProbe == null) {
            IllegalStateException refused = new IllegalStateException("tick parity settle probe unavailable; capture refused");
            failure(refused.getMessage());
            throw refused;
        }
        checkThread();
        JsonObject start = event("settle-start");
        start.addProperty("phase", phase);
        emit(start);
        settling = true;
        Runnable held = backgroundHold;
        backgroundHold = null;
        try {
            backgroundHold = TickParitySettle.run(current, settleProbe, held);
        } catch (RuntimeException | Error refused) {
            // The capture is void: say so in the event stream before vanilla's crash handling runs.
            settling = false;
            failure("settle " + phase + ": " + refused);
            throw refused;
        } finally {
            settling = false;
        }
        JsonObject end = event("settle-end");
        end.addProperty("phase", phase);
        end.addProperty("serverTasks", current.getPendingTasksCount());
        emit(end);
    }

    public static void initialize(MinecraftServer current) {
        if (!enabled) return;
        if (server != null && server != current) throw new IllegalStateException("multiple parity servers");
        server = current;
        int view = script.get("viewDistance").getAsInt();
        int simulation = script.get("simulationDistance").getAsInt();
        if (current.getPlayerList().getViewDistance() != view) current.getPlayerList().setViewDistance(view);
        if (current.getPlayerList().getSimulationDistance() != simulation) current.getPlayerList().setSimulationDistance(simulation);
    }

    public static void beforeTick(MinecraftServer current) {
        if (!enabled || complete) return;
        initialize(current);
        if (current.getTickCount() != tick) throw new IllegalStateException("missing/repeated vanilla tick");
        tick++;
        JsonObject start = event("tick-start");
        start.addProperty("serverTick", current.getTickCount());
        emit(start);
        if (tickDriver != null) tickDriver.run();
        JsonArray actions = script.getAsJsonArray("actions");
        while (actionIndex < actions.size()) {
            JsonObject action = actions.get(actionIndex).getAsJsonObject();
            int target = action.get("tick").getAsInt();
            if (target > tick) break;
            if (target != tick) throw new IllegalStateException("late scripted input");
            apply(linkById(action.get("connection").getAsInt()),
                    unhex(action.get("wire").getAsString()), "script", actionIndex++);
        }
        settle(current, "tick-start");
    }

    public static void afterTick(MinecraftServer current) {
        if (!enabled || complete) return;
        if (current.getTickCount() != tick) throw new IllegalStateException("vanilla tick counter mismatch");
        if (current.getPlayerList().getViewDistance() != script.get("viewDistance").getAsInt()
                || current.getPlayerList().getSimulationDistance() != script.get("simulationDistance").getAsInt()) {
            throw new IllegalStateException("distance transition within recorded tick");
        }
        settle(current, "tick-end");
        JsonObject end = event("tick-end");
        end.addProperty("serverTick", current.getTickCount());
        end.addProperty("viewDistance", current.getPlayerList().getViewDistance());
        end.addProperty("simulationDistance", current.getPlayerList().getSimulationDistance());
        emit(end);
        if (tick == script.get("ticks").getAsInt()) {
            if (actionIndex != script.getAsJsonArray("actions").size()) throw new IllegalStateException("unapplied inputs");
            if (settleProbe != null) settleProbe.captureEnding(true);
            JsonObject footer = event("complete");
            footer.addProperty("actions", actionIndex);
            emit(footer);
            complete = true;
            enabled = false;
            releaseBackground();
            current.halt(false);
        }
    }

    public static void outboundProtocol(Connection connection, ProtocolInfo<?> protocol) {
        if (enabled && connection.getReceiving() == PacketFlow.SERVERBOUND) link(connection).outbound = protocol;
    }

    public static void inboundProtocol(Connection connection, ProtocolInfo<?> protocol, PacketListener listener) {
        if (!enabled || connection.getReceiving() != PacketFlow.SERVERBOUND) return;
        Link link = link(connection);
        link.inbound = protocol;
        link.listener = listener;
    }

    public static void outgoing(Connection connection, Packet<?> packet) {
        if (!enabled || connection.getReceiving() != PacketFlow.SERVERBOUND) return;
        checkThread();
        Link link = link(connection);
        if (packet instanceof BundlePacket<?> bundle) {
            JsonObject start = event("bundle-start");
            start.addProperty("connection", link.id);
            emit(start);
            for (Packet<?> child : bundle.subPackets()) outgoing(connection, child);
            JsonObject end = event("bundle-end");
            end.addProperty("connection", link.id);
            emit(end);
            return;
        }
        JsonObject row = packet("packet", link, packet, encode(link.outbound, packet));
        row.addProperty("order", link.outgoing++);
        emit(row);
    }

    public static void input(Connection connection, Packet<?> packet) {
        if (!enabled || connection.getReceiving() != PacketFlow.SERVERBOUND) return;
        checkThread();
        Link link = link(connection);
        JsonObject row = packet("input", link, packet, encode(link.inbound, packet));
        row.addProperty("order", link.inputs++);
        row.addProperty("source", applying ? "replay" : "live");
        emit(row);
    }

    public static void entity(Entity entity) {
        if (!enabled || !(entity.level() instanceof ServerLevel)) return;
        JsonObject row = entityEvent("entity");
        row.addProperty("id", entity.getId());
        row.addProperty("uuid", entity.getUUID().toString());
        row.addProperty("entityType", entity.getType().toString());
        row.addProperty("dimension", entity.level().dimension().toString());
        emit(row);
    }

    /** The scenario player as constructed, before vanilla places it: its yaw comes from the entity's own random. */
    public static void playerConstruct(ServerPlayer player) {
        if (!enabled) return;
        checkThread();
        JsonObject row = event("player-construct");
        row.addProperty("id", player.getId());
        row.addProperty("yRotBits", String.format("%08x", Float.floatToRawIntBits(player.getYRot())));
        emit(row);
    }

    public static void entityIdentity(Entity entity) {
        if (!enabled || !(entity.level() instanceof ServerLevel)) return;
        JsonObject row = entityEvent("entity-identity");
        row.addProperty("id", entity.getId());
        row.addProperty("uuid", entity.getUUID().toString());
        emit(row);
    }

    /** Start and end of one ChunkHolder.broadcastChanges call; its packets lie between the rows. */
    public static void chunkBroadcast(ChunkHolder holder, boolean start) {
        if (!enabled || server == null) return;
        checkThread();
        JsonObject row = event(start ? "chunk-broadcast-start" : "chunk-broadcast-end");
        row.addProperty("chunk", holder.getPos().toString());
        emit(row);
    }

    public static void setupCommand(String command, boolean success, int result) {
        JsonObject row = event("setup-command");
        row.addProperty("command", command);
        row.addProperty("success", success);
        row.addProperty("result", result);
        emit(row);
    }

    public static void ready(MinecraftServer current) {
        JsonObject row = event("ready");
        row.addProperty("seed", Long.toString(current.overworld().getSeed()));
        row.addProperty("viewDistance", current.getPlayerList().getViewDistance());
        row.addProperty("simulationDistance", current.getPlayerList().getSimulationDistance());
        var spawn = current.overworld().getRespawnData().pos();
        JsonObject position = new JsonObject();
        position.addProperty("x", spawn.getX());
        position.addProperty("y", spawn.getY());
        position.addProperty("z", spawn.getZ());
        row.add("spawn", position);
        emit(row);
    }

    public static int fixedDistance(MinecraftServer current, int requested, boolean simulation) {
        if (!enabled) return requested;
        return script.get(simulation ? "simulationDistance" : "viewDistance").getAsInt();
    }

    public static void playerSpawn(Connection connection, ServerPlayer player) {
        checkThread();
        JsonObject row = event("player-spawn");
        row.addProperty("connection", link(connection).id);
        row.addProperty("x", player.getX());
        row.addProperty("y", player.getY());
        row.addProperty("z", player.getZ());
        emit(row);
    }

    public static void response(Connection connection, byte[] wire) {
        apply(link(connection), wire, "response", -1);
    }

    public static void failure(String detail) {
        if (!enabled) return;
        if (settleProbe != null) {
            try {
                settleProbe.captureEnding(false);
            } catch (RuntimeException ignored) {
                // The failure row below is the evidence; a probe report is optional.
            }
        }
        JsonObject row = event("failure");
        row.addProperty("detail", detail);
        emit(row);
        enabled = false;
        releaseBackground();
    }

    /** Lets vanilla's shutdown drain the background executor once recording has ended. */
    public static void releaseBackground() {
        Runnable held = backgroundHold;
        backgroundHold = null;
        if (held != null) held.run();
    }

    public static boolean completed() { return complete; }

    /** True only while a DEBUG parity input is being synchronously applied. */
    public static boolean isApplying() { return enabled && applying; }

    private static void checkThread() {
        if (server != null && !server.isSameThread()) {
            throw new IllegalStateException("off-server-thread parity event; not an exact tick");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void apply(Link link, byte[] wire, String source, int index) {
        if (link.inbound == null || link.listener == null) throw new IllegalStateException("input before protocol binding");
        ByteBuf buffer = Unpooled.wrappedBuffer(wire);
        try {
            Packet packet = (Packet) ((StreamCodec) link.inbound.codec()).decode(buffer);
            if (buffer.isReadable()) throw new IllegalArgumentException("trailing input bytes");
            JsonObject row = packet("inject", link, packet, wire);
            row.addProperty("source", source);
            row.addProperty("action", index);
            emit(row);
            applying = true;
            try {
                packet.handle(link.listener);
            } catch (RuntimeException expected) {
                // PacketUtils.ensureRunningOnSameThread has already queued this packet on the
                // server executor. Vanilla Connection.channelRead0 treats this singleton as
                // control flow and returns without reporting an unexpected exception.
                if (!(expected instanceof RunningOnDifferentThreadException)
                        && !expected.getClass().getName().equals(RunningOnDifferentThreadException.class.getName())) {
                    throw expected;
                }
            }
        } finally {
            applying = false;
            buffer.release();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static byte[] encode(ProtocolInfo<?> protocol, Packet<?> packet) {
        if (protocol == null) throw new IllegalStateException("packet without protocol codec");
        ByteBuf buffer = Unpooled.buffer();
        try {
            ((StreamCodec) protocol.codec()).encode(buffer, packet);
            byte[] wire = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), wire);
            return wire;
        } finally {
            buffer.release();
        }
    }

    private static JsonObject packet(String kind, Link link, Packet<?> packet, byte[] wire) {
        ProtocolInfo<?> protocol = kind.equals("packet") ? link.outbound : link.inbound;
        JsonObject row = event(kind);
        row.addProperty("connection", link.id);
        row.addProperty("phase", protocol.id().id());
        row.addProperty("name", packet.type().id().toString().replace("minecraft:", ""));
        row.addProperty("wire", hex(wire));
        return row;
    }

    private static JsonObject event(String kind) {
        JsonObject row = new JsonObject();
        row.addProperty("kind", kind);
        row.addProperty("tick", tick);
        return row;
    }

    private static JsonObject entityEvent(String kind) {
        JsonObject row = event(kind);
        if (server != null && !server.isSameThread() && settling) {
            // The server thread is parked in settle(): this worker observation is ordered by it.
            row.addProperty("worker", true);
        } else if (server != null && !server.isSameThread()) {
            row.addProperty("tick", -1);
            row.addProperty("observedTick", tick);
            row.addProperty("thread", Thread.currentThread().getName());
        }
        return row;
    }

    private static synchronized void emit(JsonObject row) {
        row.addProperty("sequence", sequence++);
        String json = row.toString();
        bytes += json.length();
        if (sequence > MAX_EVENTS || bytes > MAX_BYTES) {
            enabled = false;
            throw new IllegalStateException("capture budget exhausted; incomplete capture");
        }
        if (sink != null) sink.accept(json);
        else events.add(row);
    }

    public static String drain() {
        JsonArray batch = new JsonArray();
        for (JsonObject row : events) batch.add(row);
        events.clear();
        return batch.toString();
    }

    private static Link link(Connection connection) {
        Link existing = links.get(connection);
        if (existing != null) return existing;
        Link created = new Link(links.size());
        links.put(connection, created);
        return created;
    }

    private static Link linkById(int id) {
        for (Link link : links.values()) if (link.id == id) return link;
        throw new IllegalArgumentException("unknown scripted connection " + id);
    }

    public static String hex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte item : value) {
            text.append(Character.forDigit((item >>> 4) & 15, 16));
            text.append(Character.forDigit(item & 15, 16));
        }
        return text.toString();
    }

    public static byte[] unhex(String text) {
        if ((text.length() & 1) != 0) throw new IllegalArgumentException("odd packet hex length");
        byte[] value = new byte[text.length() / 2];
        for (int index = 0; index < value.length; index++) {
            int high = Character.digit(text.charAt(index * 2), 16);
            int low = Character.digit(text.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("invalid packet hex");
            value[index] = (byte) ((high << 4) | low);
        }
        return value;
    }

    private static final class Link {
        final int id;
        ProtocolInfo<?> outbound;
        ProtocolInfo<?> inbound;
        PacketListener listener;
        int outgoing;
        int inputs;

        Link(int id) { this.id = id; }
    }
}
