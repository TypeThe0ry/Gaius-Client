package dev.gaius.parity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.server.level.ServerLevel;

final class LightFixtureWriter {
    private LightFixtureWriter() {
    }

    static void write(Path output, ServerLevel level, Map<ChunkPos, LevelChunkSection[]> chunks,
            Map<ChunkPos, Map<Integer, DataLayer[]>> layers, long seed, String fixtureKind) throws IOException {
        Files.createDirectories(output);
        byte[] table;
        try {
            table = (byte[]) Class.forName("dev.gaius.browser.kernel.light.BrowserLightStateTable")
                    .getMethod("bytes").invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new IOException("vanilla light table unavailable", error);
        }
        Files.write(output.resolve("table.bin"), table);
        int minSection = level.getMinSectionY();
        int count = level.getSectionsCount();
        List<String> rows = new ArrayList<>();
        List<String> cases = new ArrayList<>();
        for (Map.Entry<ChunkPos, LevelChunkSection[]> entry : chunks.entrySet()) {
            ChunkPos position = entry.getKey();
            String name = ParityCompat.x(position) + "," + ParityCompat.z(position);
            ByteBuffer states = ByteBuffer.allocate(count * 4096 * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (LevelChunkSection section : entry.getValue()) {
                for (int cell = 0; cell < 4096; cell++) {
                    int id = Block.getId(section.getBlockState(cell & 15, cell >> 8, (cell >> 4) & 15));
                    if (id < 0 || id > 65535) throw new IOException("state id exceeds the kernel input: " + id);
                    states.putShort((short) id);
                }
            }
            Files.write(output.resolve(name + ".states"), states.array());
            byte[] flags = new byte[count + 2];
            ByteBuffer light = ByteBuffer.allocate((count + 2) * 2048 * 2);
            Map<Integer, DataLayer[]> column = layers.get(position);
            if (column == null) throw new IOException("no vanilla light for " + name);
            for (int index = 0; index < count + 2; index++) {
                DataLayer[] pair = column.get(minSection - 1 + index);
                for (int kind = 0; kind < 2; kind++) {
                    DataLayer layer = pair == null ? null : pair[kind];
                    if (layer != null) {
                        flags[index] |= (byte) (kind == 0 ? 2 : 1);
                        light.put(layer.getData());
                    } else {
                        light.put(new byte[2048]);
                    }
                }
            }
            Files.write(output.resolve(name + ".light"), light.array());
            Files.write(output.resolve(name + ".flags"), flags);
            rows.add("{\"x\":" + ParityCompat.x(position) + ",\"z\":" + ParityCompat.z(position)
                    + ",\"name\":\"" + name + "\"}");
            boolean interior = true;
            for (int deltaX = -1; deltaX <= 1; deltaX++) {
                for (int deltaZ = -1; deltaZ <= 1; deltaZ++) {
                    interior &= chunks.containsKey(new ChunkPos(ParityCompat.x(position) + deltaX,
                            ParityCompat.z(position) + deltaZ));
                }
            }
            if (interior && cases.size() < 8) cases.add("\"" + name + "\"");
        }
        if (cases.isEmpty()) throw new IOException("no complete 3x3 vanilla light neighbourhood");
        String sourceSha256;
        try {
            Path source = Path.of(Block.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(source)) {
                byte[] buffer = new byte[65536];
                int bytes;
                while ((bytes = stream.read(buffer)) != -1) digest.update(buffer, 0, bytes);
            }
            sourceSha256 = HexFormat.of().formatHex(digest.digest());
        } catch (java.net.URISyntaxException | java.security.NoSuchAlgorithmException error) {
            throw new IOException("vanilla source identity unavailable", error);
        }
        Files.writeString(output.resolve("fixtures.json"), "{\"schema\":1,\"profile\":\"" + ParityCompat.PROFILE
                + "\",\"seed\":\"" + seed + "\",\"kind\":\"" + fixtureKind + "\",\"sourceSha256\":\""
                + sourceSha256 + "\",\"minSection\":" + minSection
                + ",\"sectionCount\":" + count + ",\"sky\":" + level.dimensionType().hasSkyLight()
                + ",\"columns\":[" + String.join(",", rows) + "],\"cases\":[" + String.join(",", cases) + "]}\n");
        System.out.println("JVM_LIGHT_FIXTURES columns=" + rows.size() + " cases=" + cases.size() + " output=" + output);
    }
}
