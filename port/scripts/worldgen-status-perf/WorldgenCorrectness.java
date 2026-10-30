package dev.gaius.parity;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class WorldgenCorrectness {
    private WorldgenCorrectness() {
    }

    public static ChunkStatus stage() {
        String stage = System.getProperty("gaius.correctness.stage", "full");
        if (stage.equals("terrain")) return ParityCompat.TERRAIN;
        try {
            return (ChunkStatus) ChunkStatus.class.getField(stage.toUpperCase(java.util.Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("This profile has no status " + stage, error);
        }
    }

    public static void prepareNeighbours(ServerChunkCache chunks, List<ChunkPos> targets, ChunkStatus status) {
        if (status.isOrAfter(ChunkStatus.FEATURES)) {
            finishNeighbours(chunks, targets, ChunkStatus.FEATURES);
        }
    }

    public static void finishNeighbours(ServerChunkCache chunks, List<ChunkPos> targets, ChunkStatus status) {
        int radius = Integer.getInteger("gaius.correctness.halo", 2);
        if (targets.isEmpty() || radius < 1 || radius > 8) throw new IllegalArgumentException("Invalid halo");
        int minX = targets.stream().mapToInt(ParityCompat::x).min().orElseThrow() - radius;
        int maxX = targets.stream().mapToInt(ParityCompat::x).max().orElseThrow() + radius;
        int minZ = targets.stream().mapToInt(ParityCompat::z).min().orElseThrow() - radius;
        int maxZ = targets.stream().mapToInt(ParityCompat::z).max().orElseThrow() + radius;
        for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
            for (int chunkX = minX; chunkX <= maxX; chunkX++) {
                ChunkAccess neighbour = chunks.getChunk(chunkX, chunkZ, status, true);
                if (neighbour == null || !neighbour.getPersistedStatus().isOrAfter(status)) {
                    throw new IllegalStateException("Incomplete neighbour " + chunkX + "," + chunkZ);
                }
            }
        }
    }

    public static void snapshot(ChunkAccess chunk) throws IOException {
        Path directory = Path.of(System.getProperty("gaius.correctness.snapshots"));
        Files.createDirectories(directory);
        LevelChunkSection[] sections = chunk.getSections();
        int[] cells = new int[sections.length * 4096];
        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        int index = 0;
        for (LevelChunkSection section : sections) {
            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        cells[index++] = palette.computeIfAbsent(state, ignored -> palette.size());
                    }
                }
            }
        }
        int chunkX = ParityCompat.x(chunk.getPos());
        int chunkZ = ParityCompat.z(chunk.getPos());
        Path path = directory.resolve(chunkX + "," + chunkZ + ".blocks.gz");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(
                Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW))))) {
            output.writeInt(0x47574331);
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
            output.writeInt(chunk.getMinY());
            output.writeInt(sections.length * 16);
            output.writeInt(palette.size());
            for (BlockState state : palette.keySet()) {
                byte[] name = state.toString().getBytes(StandardCharsets.UTF_8);
                output.writeInt(name.length);
                output.write(name);
            }
            for (int value : cells) output.writeInt(value);
        }
    }
}
