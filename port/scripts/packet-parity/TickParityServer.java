package dev.gaius.parity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelStorageSource;

/** No browser overlays, TCP, wall-clock input scheduler, or shared world between runs. */
public final class TickParityServer {
    private TickParityServer() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        JsonObject script = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var writer = Files.newBufferedWriter(output.resolve("events.jsonl"), StandardCharsets.UTF_8);
                var chunkOrder = Files.newBufferedWriter(output.resolve("chunk-order.jsonl"), StandardCharsets.UTF_8)) {
            TickParityChunkOrder.configure(chunkOrder);
            TickPacketRecorder.configure(script.toString(), line -> {
                try { writer.write(line); writer.newLine(); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            TickPacketRecorder.settleProbe(new ExecutorProbe());
            LevelStorageSource source = LevelStorageSource.createDefault(output.resolve("universe"));
            var access = source.createAccess("tick-parity");
            PackRepository packs = ServerPacksSource.createPackRepository(access);
            Server server = MinecraftServer.spin(thread -> {
                WorldStem stem = ParityCompat.loadStem(packs, Long.parseLong(script.get("seed").getAsString()),
                        GameType.CREATIVE, Difficulty.PEACEFUL, true, true);
                return new Server(thread, access, packs, stem);
            });
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
            try {
                while (!server.finished) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("tick parity watchdog; incomplete run");
                    Thread.sleep(100);
                }
                if (server.failure != null) throw new IllegalStateException(server.failure);
                if (!TickPacketRecorder.completed()) throw new IllegalStateException("no completion footer");
            } finally {
                server.halt(true);
            }
        }
        System.exit(0);
    }

    /**
     * The settle probe over the executors TickParityExecutors installs: one background thread with a
     * FIFO queue and its pending count, and IO on the calling thread (so IO is never in flight when
     * the server thread asks).
     */
    private static final class ExecutorProbe implements TickParitySettleProbe {
        ExecutorProbe() {
            boolean vanillaPool = Util.backgroundExecutor().service() instanceof ForkJoinPool;
            if (vanillaPool || !TickParityExecutors.installed()) {
                throw new IllegalStateException("deterministic executor schedule not installed; capture refused");
            }
        }

        @Override
        public boolean idle() {
            if (TickParityExecutors.broken()) throw new IllegalStateException("background hold exceeded its limit; capture refused");
            return TickParityExecutors.idle();
        }

        @Override
        public boolean ioIdle() {
            return true;
        }

        @Override
        public Runnable holdBackground() {
            return TickParityExecutors.hold();
        }

        @Override
        public String toString() {
            return TickParityExecutors.describe();
        }
    }

    private static final class Server extends ParityCompat.ServerBase {
        volatile boolean finished;
        volatile String failure;

        Server(Thread thread, LevelStorageSource.LevelStorageAccess access, PackRepository packs,
                WorldStem stem) {
            super(thread, access, packs, stem);
        }

        @Override
        protected boolean initServer() {
            boolean result = super.initServer();
            TickParityScenario.prepare(this);
            return result;
        }

        @Override
        protected void parityTick(BooleanSupplier haveTime) { vanillaTick(haveTime); }
        @Override
        public boolean isSingleplayerOwner(NameAndId player) {
            return TickPacketRecorder.script().get("player").getAsString().equals(player.name());
        }
        @Override
        protected boolean realTimeTicks() { return true; }
        @Override
        protected void parityExit() { finished = true; }
        @Override
        protected void parityCrash(String report) { failure = report; }
    }
}
