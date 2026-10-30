package dev.gaius.golden.worldgen;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.IdMapper;
import net.minecraft.core.QuartPos;
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
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/** JVM evidence for fresh versus doFill-warmed NoiseChunk reads used by 26.3 carvers. */
public final class WorldgenNoiseChunkReadCheck263 {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static final int MIN_QUART_Y = QuartPos.fromBlock(MIN_Y);

    private WorldgenNoiseChunkReadCheck263() {}

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20261014L;
        int minChunk = args.length > 1 ? Integer.parseInt(args[1]) : -10;
        int maxChunk = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
        NoiseGeneratorSettings settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromPreset(lookup
                .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(source,
                lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD));
        RandomState randomState = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings);
        LevelHeightAccessor height = LevelHeightAccessor.create(MIN_Y, HEIGHT);
        IdMapper<Holder<Biome>> biomeMap = new IdMapper<>();
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(biomeMap::add);
        PalettedContainerFactory factory = new PalettedContainerFactory(
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState(), null,
                Strategy.createForBiomes(biomeMap), biomeMap.byId(0), null);
        Aquifer.FluidPicker picker = picker(settings);
        Climate.Sampler climate = randomState.createClimateSampler(
                net.minecraft.world.level.levelgen.densityfunction.SamplerContext.builder().enableCaches().build());
        Map<Long, BiomeResolver> biomeResolvers = new HashMap<>();
        BiomeResolver resolver = (qx, qy, qz) -> {
            int clampedY = Math.max(MIN_QUART_Y, Math.min(MIN_QUART_Y + 95, qy));
            int chunkX = qx >> 2;
            int chunkZ = qz >> 2;
            BiomeResolver local = biomeResolvers.computeIfAbsent(ChunkPos.pack(chunkX, chunkZ), key ->
                    source.createResolverForChunk(climate, chunkX << 2, MIN_QUART_Y, chunkZ << 2, 4, 96, 4));
            return local.getNoiseBiome(qx, clampedY, qz);
        };
        BiomeManager biomeManager = new BiomeManager(resolver, BiomeManager.obfuscateSeed(randomState.seed()));
        Method doFill = NoiseBasedChunkGenerator.class.getDeclaredMethod("doFill", NoiseChunk.class,
                net.minecraft.world.level.chunk.ChunkAccess.class);
        doFill.setAccessible(true);

        long aquiferQueries = 0;
        long aquiferStateMismatches = 0;
        long scheduleQueries = 0;
        long scheduleMismatches = 0;
        long topQueries = 0;
        long topMismatches = 0;
        long candidateColumns = 0;
        SamplerProbe samplerProbe = new SamplerProbe();
        long nonAirBlocks = 0;
        long grassBlocks = 0;
        long dirtBlocks = 0;
        long stoneBlocks = 0;
        long waterBlocks = 0;
        long lavaBlocks = 0;
        String firstAquiferMismatch = null;
        String firstTopMismatch = null;
        WorldGenerationContext generationContext = new WorldGenerationContext(generator, height);
        for (int cz = minChunk; cz <= maxChunk; cz++) {
            for (int cx = minChunk; cx <= maxChunk; cx++) {
                DensityVolume volume = new DensityVolume(16, HEIGHT, 16, cx << 4, MIN_Y, cz << 4);
                ProtoChunk warmChunk = newChunk(cx, cz, height, factory);
                ProtoChunk freshChunk = newChunk(cx, cz, height, factory);
                NoiseChunk warm = new NoiseChunk(randomState, null, settings, picker, Blender.empty(), volume);
                NoiseChunk fresh = new NoiseChunk(randomState, null, settings, picker, Blender.empty(), volume);
                doFill.invoke(generator, warm, warmChunk);
                randomState.surfaceSystem().buildSurface(randomState, biomeManager, generationContext, warmChunk,
                        warm, settings.materialRule().value(), null);
                copyStates(warmChunk, freshChunk, cx, cz);
                DensitySamplerSet freshSamplers = samplerProbe.wrap(fresh.cachingSamplers(), false);
                DensitySamplerSet warmSamplers = samplerProbe.wrap(warm.cachingSamplers(), true);

                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int worldX = (cx << 4) + x;
                        int worldZ = (cz << 4) + z;
                        for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
                            BlockState a = fresh.aquifer().computeSubstance(worldX, y, worldZ, 0.0);
                            boolean as = fresh.aquifer().shouldScheduleFluidUpdate();
                            BlockState b = warm.aquifer().computeSubstance(worldX, y, worldZ, 0.0);
                            boolean bs = warm.aquifer().shouldScheduleFluidUpdate();
                            aquiferQueries++;
                            if (!java.util.Objects.equals(a, b)) {
                                aquiferStateMismatches++;
                                if (firstAquiferMismatch == null) {
                                    firstAquiferMismatch = cx + "," + cz + " @ " + worldX + "," + y + "," + worldZ
                                            + " fresh=" + stateId(a) + " warm=" + stateId(b);
                                }
                            }
                            scheduleQueries++;
                            if (as != bs) scheduleMismatches++;
                        }
                    }
                }

                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int worldX = (cx << 4) + x;
                        int worldZ = (cz << 4) + z;
                        for (int y = MIN_Y + 1; y < MIN_Y + HEIGHT; y++) {
                            BlockPos pos = new BlockPos(worldX, y, worldZ);
                            BlockState state = warmChunk.getBlockState(pos);
                            if (!state.isAir()) nonAirBlocks++;
                            if (state.is(Blocks.GRASS_BLOCK)) grassBlocks++;
                            if (state.is(Blocks.DIRT)) dirtBlocks++;
                            if (state.is(Blocks.STONE)) stoneBlocks++;
                            if (state.is(Blocks.WATER)) waterBlocks++;
                            if (state.is(Blocks.LAVA)) lavaBlocks++;
                            if (!(state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM))
                                    || !warmChunk.getBlockState(pos.below()).is(Blocks.DIRT)) {
                                continue;
                            }
                            candidateColumns++;
                            for (boolean underFluid : new boolean[] {false, true}) {
                                Optional<BlockState> a = randomState.surfaceSystem().topMaterial(
                                        settings.materialRule().value(), randomState, generationContext,
                                        biomeGetter(biomeManager), freshChunk, freshSamplers, pos, underFluid);
                                Optional<BlockState> b = randomState.surfaceSystem().topMaterial(
                                        settings.materialRule().value(), randomState, generationContext,
                                        biomeGetter(biomeManager), warmChunk, warmSamplers, pos, underFluid);
                                topQueries++;
                                if (!java.util.Objects.equals(stateId(a.orElse(null)), stateId(b.orElse(null)))) {
                                    topMismatches++;
                                    if (firstTopMismatch == null) {
                                        firstTopMismatch = cx + "," + cz + " @ " + pos + " underFluid=" + underFluid
                                                + " fresh=" + stateId(a.orElse(null)) + " warm=" + stateId(b.orElse(null));
                                    }
                                }
                            }
                        }
                    }
                }
                fresh.close();
                warm.close();
            }
            System.out.println("completed cz=" + cz);
        }
        System.out.println("PROFILE=26.3 seed=" + seed + " chunks="
                + ((long) (maxChunk - minChunk + 1) * (maxChunk - minChunk + 1)));
        System.out.println("AQUIFER queries=" + aquiferQueries + " state_mismatches=" + aquiferStateMismatches
                + " first=" + firstAquiferMismatch);
        System.out.println("SCHEDULE queries=" + scheduleQueries + " mismatches=" + scheduleMismatches);
        System.out.println("TOP_MATERIAL candidates=" + candidateColumns + " queries=" + topQueries
                + " mismatches=" + topMismatches + " first=" + firstTopMismatch);
        System.out.println("SAMPLER_VALUE queries=" + samplerProbe.valueQueries + " mismatches="
                + samplerProbe.valueMismatches + " first=" + samplerProbe.firstMismatch);
        System.out.println("SAMPLER_VOLUME calls=" + samplerProbe.volumeCalls + " values="
                + samplerProbe.volumeValues + " mismatches=" + samplerProbe.volumeMismatches
                + " first=" + samplerProbe.firstMismatch);
        samplerProbe.printSlots(randomState.surfaceSystem().preliminarySurfaceFunction());
        System.out.println("STATES non_air=" + nonAirBlocks + " grass=" + grassBlocks + " dirt=" + dirtBlocks
                + " stone=" + stoneBlocks + " water=" + waterBlocks + " lava=" + lavaBlocks);
    }

    private static ProtoChunk newChunk(int cx, int cz, LevelHeightAccessor height, PalettedContainerFactory factory) {
        return new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, height, factory, null);
    }

    private static Aquifer.FluidPicker picker(NoiseGeneratorSettings settings) {
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : sea;
    }

    private static void copyStates(ProtoChunk from, ProtoChunk to, int cx, int cz) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
                    BlockPos pos = new BlockPos((cx << 4) + x, y, (cz << 4) + z);
                    to.setBlockState(pos, from.getBlockState(pos), 0);
                }
            }
        }
    }

    private static Function<BlockPos, Holder<Biome>> biomeGetter(BiomeManager manager) {
        return manager::getNoiseBiomeAtPosition;
    }

    private static String stateId(BlockState state) {
        return state == null ? "null" : Integer.toString(Block.BLOCK_STATE_REGISTRY.getId(state));
    }

    /** Compares the exact float answers returned by the samplers that topMaterial actually calls. */
    private static final class SamplerProbe {
        private final IdentityHashMap<DensityFunction, Integer> slots = new IdentityHashMap<>();
        private final Map<ValueKey, List<Integer>> freshValues = new HashMap<>();
        private final Map<ValueKey, Integer> warmValueIndex = new HashMap<>();
        private final Map<VolumeKey, List<int[]>> freshVolumes = new HashMap<>();
        private final Map<VolumeKey, Integer> warmVolumeIndex = new HashMap<>();
        private final Map<Integer, DensityFunction> functions = new HashMap<>();
        private final Map<Integer, Long> valueCounts = new HashMap<>();
        private final Map<Integer, Long> volumeCounts = new HashMap<>();
        private long valueQueries;
        private long valueMismatches;
        private long volumeCalls;
        private long volumeValues;
        private long volumeMismatches;
        private String firstMismatch;

        DensitySamplerSet wrap(DensitySamplerSet delegate, boolean warm) {
            return function -> {
                DensitySampler.Bound base = delegate.get(function);
                int slot = slots.computeIfAbsent(function, ignored -> slots.size());
                functions.put(slot, function);
                DensitySampler traced = new DensitySampler() {
                    @Override
                    public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
                        base.sampleVolume(buffer, volume);
                        int[] values = new int[volume.size()];
                        for (int i = 0; i < values.length; i++) {
                            values[i] = Float.floatToRawIntBits(buffer.get(i));
                        }
                        volumeCounts.merge(slot, 1L, Long::sum);
                        recordVolume(new VolumeKey(slot, volume), values, warm);
                    }

                    @Override
                    public float sampleValue(SamplerContext context, int x, int y, int z) {
                        float value = base.sampleValue(x, y, z);
                        valueCounts.merge(slot, 1L, Long::sum);
                        recordValue(new ValueKey(slot, x, y, z), Float.floatToRawIntBits(value), warm);
                        return value;
                    }
                };
                return new DensitySampler.Bound(traced, base.context());
            };
        }

        private void recordValue(ValueKey key, int value, boolean warm) {
            valueQueries++;
            if (!warm) {
                freshValues.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
                return;
            }
            List<Integer> expected = freshValues.get(key);
            int index = warmValueIndex.getOrDefault(key, 0);
            warmValueIndex.put(key, index + 1);
            if (expected == null || index >= expected.size() || expected.get(index) != value) {
                valueMismatches++;
                rememberMismatch("value " + key + " fresh="
                        + (expected == null || index >= expected.size() ? "missing" : expected.get(index))
                        + " warm=" + value);
            }
        }

        private void recordVolume(VolumeKey key, int[] values, boolean warm) {
            volumeCalls++;
            volumeValues += values.length;
            if (!warm) {
                freshVolumes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(values);
                return;
            }
            List<int[]> expected = freshVolumes.get(key);
            int index = warmVolumeIndex.getOrDefault(key, 0);
            warmVolumeIndex.put(key, index + 1);
            int[] expectedValues = expected == null || index >= expected.size() ? null : expected.get(index);
            if (expectedValues == null || expectedValues.length != values.length) {
                volumeMismatches++;
                rememberMismatch("volume " + key + " fresh="
                        + (expectedValues == null ? "missing" : expectedValues.length)
                        + " warm=" + values.length);
                return;
            }
            for (int i = 0; i < values.length; i++) {
                if (expectedValues[i] != values[i]) {
                    volumeMismatches++;
                    rememberMismatch("volume " + key + " index=" + i + " fresh="
                            + expectedValues[i] + " warm=" + values[i]);
                    break;
                }
            }
        }

        private void rememberMismatch(String message) {
            if (firstMismatch == null) firstMismatch = message;
        }

        private void printSlots(DensityFunction preliminary) {
            for (int slot = 0; slot < functions.size(); slot++) {
                DensityFunction function = functions.get(slot);
                String label = function == preliminary ? "preliminary" : function.getClass().getName();
                System.out.println("SAMPLER_SLOT slot=" + slot + " label=" + label
                        + " volume_calls=" + volumeCounts.getOrDefault(slot, 0L)
                        + " value_calls=" + valueCounts.getOrDefault(slot, 0L));
            }
        }
    }

    private record ValueKey(int slot, int x, int y, int z) {}

    private record VolumeKey(int slot, DensityVolume volume) {}
}
