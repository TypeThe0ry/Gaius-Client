package dev.gaius.golden.light;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.ticks.ProtoChunkTicks;

public final class LightGolden {
    private final LevelHeightAccessor height;
    private final Map<ChunkPos, ProtoChunk> chunks = new LinkedHashMap<>();
    private final LevelLightEngine engine;

    private LightGolden(int minSection, int sectionCount, long seed) {
        height = LevelHeightAccessor.create(minSection * 16, sectionCount * 16);
        Strategy strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        Random random = new Random(seed);
        BlockState[] choices = {Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.TORCH.defaultBlockState(), Blocks.GLOWSTONE.defaultBlockState(),
                Blocks.STONE_SLAB.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState()};
        for (int chunkZ = -2; chunkZ <= 2; chunkZ++) {
            for (int chunkX = -2; chunkX <= 2; chunkX++) {
                LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
                for (int section = 0; section < sectionCount; section++) {
                    sections[section] = new LevelChunkSection(new PalettedContainer<>(
                            Blocks.AIR.defaultBlockState(), strategy), null);
                    for (int index = 0; index < 96; index++) {
                        sections[section].setBlockState(random.nextInt(16), random.nextInt(16), random.nextInt(16),
                                choices[random.nextInt(choices.length)]);
                    }
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            if ((localX + localZ + section) % 5 != 0) {
                                sections[section].setBlockState(localX, 8, localZ,
                                        choices[(section + Math.floorMod(chunkX + chunkZ, 4)) % 4]);
                            }
                        }
                    }
                    sections[section].setBlockState(0, 0, 8, Blocks.GLOWSTONE.defaultBlockState());
                    sections[section].setBlockState(15, 15, 8, Blocks.TORCH.defaultBlockState());
                }
                ChunkPos position = new ChunkPos(chunkX, chunkZ);
                ProtoChunk chunk = new ProtoChunk(position, UpgradeData.EMPTY, sections,
                        new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), height, null, null);
                chunk.initializeLightSources();
                chunks.put(position, chunk);
            }
        }
        BlockGetter level = new BlockGetter() {
            @Override
            public BlockState getBlockState(BlockPos position) {
                ProtoChunk chunk = chunks.get(new ChunkPos(position.getX() >> 4, position.getZ() >> 4));
                return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(position);
            }

            @Override
            public FluidState getFluidState(BlockPos position) {
                return getBlockState(position).getFluidState();
            }

            @Override
            public BlockEntity getBlockEntity(BlockPos position) {
                return null;
            }

            @Override
            public int getHeight() {
                return height.getHeight();
            }

            @Override
            public int getMinY() {
                return height.getMinY();
            }
        };
        engine = new LevelLightEngine(new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
                return chunks.get(new ChunkPos(chunkX, chunkZ));
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }
        }, true, true);
        for (Map.Entry<ChunkPos, ProtoChunk> entry : chunks.entrySet()) {
            for (int section = 0; section < sectionCount; section++) {
                engine.updateSectionStatus(SectionPos.of(entry.getKey(), minSection + section),
                        entry.getValue().getSections()[section].hasOnlyAir());
            }
            engine.setLightEnabled(entry.getKey(), false);
        }
        for (ChunkPos position : chunks.keySet()) engine.propagateLightSources(position);
        settle();
    }

    private void settle() {
        int rounds = 0;
        while (engine.hasLightWork()) {
            if (++rounds > 10000) throw new IllegalStateException("vanilla light did not settle");
            engine.runLightUpdates();
        }
    }

    private Map<String, List<Integer>> change(BlockState state, int localX, int localY, int localZ) {
        Map<String, List<Integer>> checks = new LinkedHashMap<>();
        for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
            for (int chunkX = -1; chunkX <= 1; chunkX++) {
                ProtoChunk chunk = chunks.get(new ChunkPos(chunkX, chunkZ));
                List<Integer> positions = new ArrayList<>();
                for (int section = 0; section < height.getSectionsCount(); section++) {
                    int worldY = (height.getMinSectionY() + section) * 16 + localY;
                    BlockPos position = new BlockPos(chunkX * 16 + localX, worldY, chunkZ * 16 + localZ);
                    chunk.getSections()[section].setBlockState(localX, localY, localZ, state);
                    chunk.getSkyLightSources().update(chunk, localX, worldY, localZ);
                    engine.checkBlock(position);
                    positions.add(localX); positions.add(worldY); positions.add(localZ);
                }
                checks.put(chunkX + "," + chunkZ, positions);
            }
        }
        settle();
        return checks;
    }

    private void snapshot(Path output, String profile, String sourceHash, long seed, String stage,
            String previous, Map<String, List<Integer>> checks) throws Exception {
        Files.createDirectories(output);
        Files.write(output.resolve("table.bin"), dev.gaius.browser.kernel.light.BrowserLightStateTable.bytes());
        List<String> rows = new ArrayList<>();
        List<String> cases = new ArrayList<>();
        int count = height.getSectionsCount();
        int minSection = height.getMinSectionY();
        for (Map.Entry<ChunkPos, ProtoChunk> entry : chunks.entrySet()) {
            ChunkPos position = entry.getKey();
            // getMinBlockX/Z exist on every profile; the x/z accessors do not.
            int chunkX = position.getMinBlockX() >> 4;
            int chunkZ = position.getMinBlockZ() >> 4;
            String name = chunkX + "," + chunkZ;
            ByteBuffer states = ByteBuffer.allocate(count * 4096 * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (LevelChunkSection section : entry.getValue().getSections()) {
                for (int cell = 0; cell < 4096; cell++) {
                    int state = Block.getId(section.getBlockState(cell & 15, cell >> 8, (cell >> 4) & 15));
                    if (state < 0 || state > 65535) throw new IllegalStateException("state exceeds u16");
                    states.putShort((short) state);
                }
            }
            Files.write(output.resolve(name + ".states"), states.array());
            byte[] flags = new byte[count + 2];
            ByteBuffer light = ByteBuffer.allocate((count + 2) * 4096);
            for (int section = 0; section < count + 2; section++) {
                for (LightLayer kind : new LightLayer[] {LightLayer.BLOCK, LightLayer.SKY}) {
                    DataLayer layer = engine.getLayerListener(kind).getDataLayerData(
                            SectionPos.of(position, minSection - 1 + section));
                    if (layer != null) {
                        flags[section] |= (byte) (kind == LightLayer.BLOCK ? 2 : 1);
                        light.put(layer.getData());
                    } else {
                        light.put(new byte[2048]);
                    }
                }
            }
            Files.write(output.resolve(name + ".flags"), flags);
            Files.write(output.resolve(name + ".light"), light.array());
            rows.add("{\"x\":" + chunkX + ",\"z\":" + chunkZ + ",\"name\":\"" + name + "\"}");
            if (Math.abs(chunkX) <= 1 && Math.abs(chunkZ) <= 1) cases.add("\"" + name + "\"");
        }
        List<String> updates = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : checks.entrySet()) {
            updates.add("\"" + entry.getKey() + "\":" + entry.getValue());
        }
        Files.writeString(output.resolve("fixtures.json"), "{\"schema\":1,\"profile\":\"" + profile
                + "\",\"seed\":\"" + seed + "\",\"kind\":\"seeded-column\",\"stage\":\"" + stage
                + "\",\"sourceSha256\":\"" + sourceHash + "\",\"minSection\":" + minSection
                + ",\"sectionCount\":" + count + ",\"sky\":true,\"previous\":"
                + (previous == null ? "null" : "\"../" + previous + "\"")
                + ",\"checks\":{" + String.join(",", updates) + "},\"columns\":["
                + String.join(",", rows) + "],\"cases\":[" + String.join(",", cases) + "]}\n");
        System.out.println("JVM_LIGHT " + seed + " " + stage + " sections=" + cases.size() * count);
    }

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]);
        String profile = arguments[1];
        String sourceHash = arguments[2];
        Path source = Path.of(Block.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        if (!sourceHash.equals(actualHash)) throw new IllegalStateException("unexpected vanilla class source");
        long seed = Long.parseLong(arguments[3]);
        int minSection = Integer.parseInt(arguments[4]);
        int count = Integer.parseInt(arguments[5]);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        LightGolden golden = new LightGolden(minSection, count, seed);
        golden.snapshot(output.resolve("initial"), profile, sourceHash, seed, "initial", null, Map.of());
        golden.snapshot(output.resolve("emitter-add"), profile, sourceHash, seed, "emitter-add", "initial",
                golden.change(Blocks.GLOWSTONE.defaultBlockState(), 15, 7, 8));
        golden.snapshot(output.resolve("emitter-remove"), profile, sourceHash, seed, "emitter-remove", "emitter-add",
                golden.change(Blocks.AIR.defaultBlockState(), 15, 7, 8));
        golden.snapshot(output.resolve("roof-close"), profile, sourceHash, seed, "roof-close", "emitter-remove",
                golden.change(Blocks.STONE.defaultBlockState(), 8, 15, 8));
        golden.snapshot(output.resolve("roof-open"), profile, sourceHash, seed, "roof-open", "roof-close",
                golden.change(Blocks.AIR.defaultBlockState(), 8, 15, 8));
    }
}
