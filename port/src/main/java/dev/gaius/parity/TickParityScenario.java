package dev.gaius.parity;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelData;

/** The same tick-zero setup and virtual PLAY peer for JVM and Worker captures. */
public final class TickParityScenario {
    private TickParityScenario() {
    }

    public static void prepare(MinecraftServer server) {
        JsonObject script = TickPacketRecorder.script();
        String name = script.get("player").getAsString();
        server.setUsesAuthentication(false);
        server.setSingleplayerProfile(new GameProfile(UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name));
        server.setDefaultGameType(GameType.CREATIVE);
        server.setMotd("Gaius Integrated Server");
        JsonObject spawn = script.getAsJsonObject("spawn");
        BlockPos position = new BlockPos(spawn.get("x").getAsInt(), spawn.get("y").getAsInt(), spawn.get("z").getAsInt());
        server.overworld().setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, position, 0.0F, 0.0F));
        for (var command : script.getAsJsonArray("setupCommands")) {
            String text = command.getAsString();
            boolean[] applied = {false};
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withCallback((success, result) -> {
                TickPacketRecorder.setupCommand(text, success, result);
                applied[0] = success;
            }), text);
            if (!applied[0]) throw new IllegalStateException("parity setup command failed: " + text);
        }
        TickPacketRecorder.initialize(server);
        int radius = script.get("preloadRadius").getAsInt();
        int centerX = Math.floorDiv(position.getX(), 16);
        int centerZ = Math.floorDiv(position.getZ(), 16);
        for (int chunkZ = centerZ - radius; chunkZ <= centerZ + radius; chunkZ++) {
            for (int chunkX = centerX - radius; chunkX <= centerX + radius; chunkX++) preload(server, chunkX, chunkZ);
        }
        Map<String, Integer> ids = new HashMap<>();
        script.getAsJsonObject("serverboundIds").entrySet().forEach(entry -> ids.put(entry.getKey(), entry.getValue().getAsInt()));
        TickParityClient client = new TickParityClient(ids);
        TickPacketRecorder.driver(() -> client.beforeTick(server));
        TickPacketRecorder.ready(server);
    }

    /**
     * Vanilla's FULL chunk request, as {@code getChunk} makes it, but drained by the recorder's
     * serial settle instead of {@code managedBlock}. Requested from another thread, vanilla posts the
     * request to the server thread's chunk executor; the settle then runs it in order.
     */
    private static void preload(MinecraftServer server, int chunkX, int chunkZ) {
        Object[] request = new Object[1];
        Thread poster = new Thread(() -> request[0] = server.overworld().getChunkSource()
                .getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true), "TickParity-preload");
        poster.start();
        try {
            poster.join();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("parity preload interrupted", error);
        }
        if (!(request[0] instanceof CompletableFuture<?> future)) throw new IllegalStateException("parity preload not requested");
        // The completion callback may be deferred until the first scripted server tick. Vanilla
        // accepts that state; preload only establishes the request and must not add extra settle
        // events that change the recorded schedule.
        TickPacketRecorder.settle(server, "preload");
    }
}
