package dev.gaius.golden.worldgen;

import dev.gaius.browser.kernel.worldgen.WorldgenExport263;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.IdMapper;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

/**
 * Writes the vanilla 26.3 overworld as a worldgen kernel IR plus reference data for a few chunks,
 * computed by the real classes on the JVM:
 *
 * <pre>
 *   overworld.gwir   the generator IR (WorldgenExport263)
 *   chunk_X_Z.ref    i32 x, z; then
 *                    f32 final density[16 * 384 * 16] (volume order, fresh caching context)
 *                    i32 biome[24 * 64] (createResolverForChunk, fillBiomesFromNoise order)
 *                    i32 state[16 * 384 * 16] (NoiseChunk + aquifer like doFill, before surface)
 *                    i32 state[16 * 384 * 16] (after MaterialSystem.buildSurface on a ProtoChunk)
 *                    i32 world_surface_wg[256], ocean_floor_wg[256] (first free y, x + z * 16)
 *                    i32 magic 'GPP1', i32 post_count, i32 post[post_count]
 *                    (section_index << 16 | packed local position), then the stored biome ring
 * </pre>
 *
 * Biome ids are the index in the built-in biome registry listing, which is also what the IR says.
 * Usage: {@code WorldgenReference <seed> <output-directory> [x,z ...]}.
 */
public final class WorldgenReference {
    private static final int MIN_QUART_Y = QuartPos.fromBlock(-64);

    private WorldgenReference() {
    }

    public static void main(String[] args) throws IOException {
        long seed = Long.parseLong(args[0]);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
        Holder<NoiseGeneratorSettings> settingsHolder =
                lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        NoiseGeneratorSettings settings = settingsHolder.value();
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromPreset(lookup
                .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(source, settingsHolder);
        RandomState randomState = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings);
        LevelHeightAccessor height = LevelHeightAccessor.create(-64, 384);

        Map<Biome, Integer> biomeIds = new HashMap<>();
        IdMapper<Holder<Biome>> biomeMap = new IdMapper<>();
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h -> {
            biomeIds.put(h.value(), biomeIds.size());
            biomeMap.add(h);
        });
        byte[] ir = WorldgenExport263.export(generator, randomState, lookup, b -> biomeIds.getOrDefault(b, -1), height);
        Files.write(out.resolve("overworld.gwir"), ir);
        System.out.println("overworld.gwir: " + ir.length + " bytes");

        Reference reference = new Reference(generator, settings, randomState, source, biomeIds, biomeMap, height);
        String[] chunks = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : new String[] {"0,0", "7,-3"};
        for (String chunk : chunks) {
            String[] xz = chunk.split(",");
            int cx = Integer.parseInt(xz[0]);
            int cz = Integer.parseInt(xz[1]);
            Path file = out.resolve("chunk_" + cx + "_" + cz + ".ref");
            try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(file))) {
                data.writeInt(Integer.reverseBytes(cx));
                data.writeInt(Integer.reverseBytes(cz));
                reference.write(data, cx, cz);
            }
            System.out.println(file.getFileName());
        }
        dev.gaius.golden.worldgen.StageTiming.run(chunks, (chunkX, chunkZ) -> reference.write(
                new DataOutputStream(java.io.OutputStream.nullOutputStream()), chunkX, chunkZ));
    }

    private record Reference(
            NoiseBasedChunkGenerator generator,
            NoiseGeneratorSettings settings,
            RandomState randomState,
            MultiNoiseBiomeSource source,
            Map<Biome, Integer> biomeIds,
            IdMapper<Holder<Biome>> biomeMap,
            LevelHeightAccessor height) {

        void write(DataOutputStream data, int cx, int cz) throws IOException {
            DensityVolume volume = new DensityVolume(16, 384, 16, cx << 4, -64, cz << 4);
            SamplerContext context = SamplerContext.builder().enableCaches().build();
            DensitySampler.Bound density =
                    randomState.samplersWithContext(context).get(settings.noiseRouter().finalDensity());
            long densityStart = dev.gaius.golden.worldgen.StageTiming.start();
            try (ScopedDensityBuffer buffer = density.sampleVolume(volume)) {
                dev.gaius.golden.worldgen.StageTiming.finish("density", cx, cz, densityStart);
                for (int i = 0; i < volume.size(); i++) {
                    data.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(buffer.get(i))));
                }
            }

            Map<Long, int[]> storedBiomes = new HashMap<>();
            long biomeStart = dev.gaius.golden.worldgen.StageTiming.start();
            int[] biomeValues = chunkBiomeValues(cx, cz);
            storedBiomes.put(ChunkPos.pack(cx, cz), biomeValues);

            dev.gaius.golden.worldgen.StageTiming.finish("biomes", cx, cz, biomeStart);
            for (int biome : biomeValues) {
                data.writeInt(Integer.reverseBytes(biome));
            }

            int seaLevel = settings.seaLevel();
            Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
            Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
            Aquifer.FluidPicker picker = (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : sea;
            PalettedContainerFactory factory = new PalettedContainerFactory(
                    Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState(), null,
                    Strategy.createForBiomes(biomeMap), biomeMap.byId(0), null);
            ProtoChunk chunk = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, height, factory, null);
            int[] states = new int[volume.size()];
            long terrainStart = dev.gaius.golden.worldgen.StageTiming.start();
            try (NoiseChunk noiseChunk = new NoiseChunk(randomState, null, settings, picker, Blender.empty(), volume)) {
                Aquifer aquifer = noiseChunk.aquifer();
                Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
                Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
                DensitySampler.Bound finalDensity =
                        noiseChunk.cachingSamplers().get(settings.noiseRouter().finalDensity());
                try (ScopedDensityBuffer buffer = finalDensity.sampleVolume(volume)) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            for (int y = 383; y >= 0; y--) {
                                int index = volume.indexUnchecked(x, y, z);
                                int blockY = volume.blockY(y);
                                BlockState state = aquifer.computeSubstance(
                                        volume.blockX(x), blockY, volume.blockZ(z), buffer.get(index));
                                if (state == null) {
                                    state = settings.defaultBlock();
                                }
                                states[index] = Block.BLOCK_STATE_REGISTRY.getId(state);
                                if (!state.isAir()) {
                                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(blockY));
                                    section.setBlockState(x, SectionPos.sectionRelative(blockY), z, state, false);
                                    oceanFloor.update(x, blockY, z, state);
                                    worldSurface.update(x, blockY, z, state);
                                }
                            }
                        }
                    }
                }
                dev.gaius.golden.worldgen.StageTiming.finish("terrain", cx, cz, terrainStart);
                for (int s : states) {
                    data.writeInt(Integer.reverseBytes(s));
                }
                // buildSurface reads biomes through the region's BiomeManager: the chunks' biomes
                // (createResolverForChunk, the volume path) with the chunk's quart y clamp.
                BiomeResolver chunkBiomes = (qx, qy, qz) -> {
                    int clamped = Math.max(MIN_QUART_Y, Math.min(MIN_QUART_Y + 95, qy));
                    int ccx = qx >> 2;
                    int ccz = qz >> 2;
                    int[] values = storedBiomes.computeIfAbsent(ChunkPos.pack(ccx, ccz), k -> chunkBiomeValues(ccx, ccz));
                    int relativeY = clamped - MIN_QUART_Y;
                    return biomeMap.byId((values[(relativeY >> 2) * 64
                            + ((relativeY & 3) * 4 + (qz & 3)) * 4 + (qx & 3)]));
                };
                @SuppressWarnings("deprecation")
                long zoomSeed = BiomeManager.obfuscateSeed(randomState.seed());
                BiomeManager biomeManager = new BiomeManager(chunkBiomes, zoomSeed);
                long surfaceStart = dev.gaius.golden.worldgen.StageTiming.start();
                randomState.surfaceSystem().buildSurface(randomState, biomeManager,
                        new WorldGenerationContext(generator, height), chunk, noiseChunk,
                        settings.materialRule().value(), null);
                dev.gaius.golden.worldgen.StageTiming.finish("surface", cx, cz, surfaceStart);
            }
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 384; y++) {
                        BlockState state = chunk.getBlockState(new BlockPos((cx << 4) + x, y - 64, (cz << 4) + z));
                        states[volume.indexUnchecked(x, y, z)] = Block.BLOCK_STATE_REGISTRY.getId(state);
                    }
                }
            }
            for (int s : states) {
                data.writeInt(Integer.reverseBytes(s));
            }
            for (Heightmap.Types type : new Heightmap.Types[] {
                    Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG}) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        data.writeInt(Integer.reverseBytes(chunk.getHeight(type, x, z) + 1));
                    }
                }
            }
            writePostProcessing(data, chunk);
            for (int x = -1; x <= 4; x++) {
                for (int z = -1; z <= 4; z++) {
                    int qx = (cx << 2) + x;
                    int qz = (cz << 2) + z;
                    int[] values = storedBiomes.computeIfAbsent(ChunkPos.pack(qx >> 2, qz >> 2),
                            k -> chunkBiomeValues(qx >> 2, qz >> 2));
                    for (int y = 0; y < 96; y++) {
                        data.writeInt(Integer.reverseBytes(values[(y >> 2) * 64
                                + ((y & 3) * 4 + (qz & 3)) * 4 + (qx & 3)]));
                    }
                }
            }
        }

        private static void writePostProcessing(DataOutputStream data, ProtoChunk chunk) throws IOException {
            int count = 0;
            for (var list : chunk.getPostProcessing()) {
                if (list != null) count += list.size();
            }
            data.writeInt(Integer.reverseBytes(0x31505047)); // GPP1, little-endian marker
            data.writeInt(Integer.reverseBytes(count));
            for (int section = 0; section < chunk.getPostProcessing().length; section++) {
                var list = chunk.getPostProcessing()[section];
                if (list == null) continue;
                for (int index = 0; index < list.size(); index++) {
                    data.writeInt(Integer.reverseBytes((section << 16) | (list.getShort(index) & 0xFFFF)));
                }
            }
        }

        int[] chunkBiomeValues(int cx, int cz) {
            int[] values = new int[24 * 64];
            BiomeResolver resolver = chunkResolver(cx, cz);
            for (int section = 0; section < 24; section++) {
                for (int qx = 0; qx < 4; qx++) {
                    for (int qy = 0; qy < 4; qy++) {
                        for (int qz = 0; qz < 4; qz++) {
                            Holder<Biome> biome = resolver.getNoiseBiome((cx << 2) + qx,
                                    MIN_QUART_Y + section * 4 + qy, (cz << 2) + qz);
                            values[section * 64 + (qy * 4 + qz) * 4 + qx] = biomeIds.get(biome.value());
                        }
                    }
                }
            }
            return values;
        }

        /** {@code createBiomes}' resolver for one chunk. */
        BiomeResolver chunkResolver(int cx, int cz) {
            Climate.Sampler climate = randomState.createClimateSampler(SamplerContext.builder().enableCaches().build());
            return source.createResolverForChunk(climate, cx << 2, MIN_QUART_Y, cz << 2, 4, 96, 4);
        }
    }
}
