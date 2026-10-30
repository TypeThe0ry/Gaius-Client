package net.minecraft.world.level.levelgen;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.IdMapper;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;

/** JVM evidence for fresh versus fillFromNoise-warmed NoiseChunk reads used by 26.2 carvers. */
public final class WorldgenNoiseChunkReadCheck262 {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static final int MIN_QUART_Y = QuartPos.fromBlock(MIN_Y);

    private WorldgenNoiseChunkReadCheck262() {}

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20261014L;
        int minChunk = args.length > 1 ? Integer.parseInt(args[1]) : -10;
        int maxChunk = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        Holder<NoiseGeneratorSettings> settingsHolder = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        NoiseGeneratorSettings settings = settingsHolder.value();
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromPreset(lookup
                .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(source, settingsHolder);
        RandomState randomState = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), seed);
        LevelHeightAccessor height = LevelHeightAccessor.create(MIN_Y, HEIGHT);
        IdMapper<Holder<Biome>> biomeMap = new IdMapper<>();
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(biomeMap::add);
        PalettedContainerFactory factory = new PalettedContainerFactory(
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState(), null,
                Strategy.createForBiomes(biomeMap), biomeMap.byId(0), null);
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        Aquifer.FluidPicker picker = (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : sea;
        Climate.Sampler climate = randomState.sampler();
        BiomeManager biomeManager = new BiomeManager(
                (qx, qy, qz) -> source.getNoiseBiome(qx, qy, qz, climate),
                BiomeManager.obfuscateSeed(seed));
        Set<Holder<Biome>> possibleBiomes = source.possibleBiomes();
        WorldGenerationContext generationContext = new WorldGenerationContext(generator, height);

        long aquiferQueries = 0;
        long aquiferStateMismatches = 0;
        long scheduleQueries = 0;
        long scheduleMismatches = 0;
        long preliminaryQueries = 0;
        long preliminaryMismatches = 0;
        long topQueries = 0;
        long topMismatches = 0;
        long candidateColumns = 0;
        long nonAirBlocks = 0;
        long grassBlocks = 0;
        long dirtBlocks = 0;
        long stoneBlocks = 0;
        long waterBlocks = 0;
        long lavaBlocks = 0;
        String firstAquiferMismatch = null;
        String firstPreliminaryMismatch = null;
        String firstTopMismatch = null;
        Function<BlockPos, Holder<Biome>> biomeGetter = biomeManager::getNoiseBiomeAtPosition;
        for (int cz = minChunk; cz <= maxChunk; cz++) {
            for (int cx = minChunk; cx <= maxChunk; cx++) {
                ProtoChunk warmChunk = newChunk(cx, cz, height, factory, randomState, settings, picker);
                ProtoChunk freshChunk = newChunk(cx, cz, height, factory, randomState, settings, picker);
                generator.fillFromNoise(Blender.empty(), randomState, null, warmChunk).join();
                NoiseChunk warm = warmChunk.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(
                        c, randomState, DensityFunctions.BeardifierMarker.INSTANCE, settings, picker, Blender.empty()));
                NoiseChunk fresh = freshChunk.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(
                        c, randomState, DensityFunctions.BeardifierMarker.INSTANCE, settings, picker, Blender.empty()));
                generator.buildSurface(warmChunk, generationContext, randomState, null, biomeManager,
                        Blender.empty(), possibleBiomes);
                copyStates(warmChunk, freshChunk, cx, cz);

                MutableContext point = new MutableContext();
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int worldX = (cx << 4) + x;
                        int worldZ = (cz << 4) + z;
                        int freshSurface = fresh.preliminarySurfaceLevel(worldX, worldZ);
                        int warmSurface = warm.preliminarySurfaceLevel(worldX, worldZ);
                        preliminaryQueries++;
                        if (freshSurface != warmSurface) {
                            preliminaryMismatches++;
                            if (firstPreliminaryMismatch == null) {
                                firstPreliminaryMismatch = cx + "," + cz + " @ " + worldX + "," + worldZ
                                        + " fresh=" + freshSurface + " warm=" + warmSurface;
                            }
                        }
                        for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
                            point.set(worldX, y, worldZ);
                            BlockState a = fresh.aquifer().computeSubstance(point, 0.0);
                            boolean as = fresh.aquifer().shouldScheduleFluidUpdate();
                            BlockState b = warm.aquifer().computeSubstance(point, 0.0);
                            boolean bs = warm.aquifer().shouldScheduleFluidUpdate();
                            aquiferQueries++;
                            if (!Objects.equals(a, b)) {
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

                CarvingContext freshContext = new CarvingContext(generator, RegistryAccess.EMPTY, height, fresh,
                        randomState, settings.surfaceRule());
                CarvingContext warmContext = new CarvingContext(generator, RegistryAccess.EMPTY, height, warm,
                        randomState, settings.surfaceRule());
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
                                Optional<BlockState> a = freshContext.topMaterial(biomeGetter, freshChunk, pos, underFluid);
                                Optional<BlockState> b = warmContext.topMaterial(biomeGetter, warmChunk, pos, underFluid);
                                topQueries++;
                                if (!Objects.equals(stateId(a.orElse(null)), stateId(b.orElse(null)))) {
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
            }
            System.out.println("completed cz=" + cz);
        }
        System.out.println("PROFILE=26.2 seed=" + seed + " chunks="
                + ((long) (maxChunk - minChunk + 1) * (maxChunk - minChunk + 1)));
        System.out.println("AQUIFER queries=" + aquiferQueries + " state_mismatches=" + aquiferStateMismatches
                + " first=" + firstAquiferMismatch);
        System.out.println("SCHEDULE queries=" + scheduleQueries + " mismatches=" + scheduleMismatches);
        System.out.println("PRELIMINARY queries=" + preliminaryQueries + " mismatches=" + preliminaryMismatches
                + " first=" + firstPreliminaryMismatch);
        System.out.println("TOP_MATERIAL candidates=" + candidateColumns + " queries=" + topQueries
                + " mismatches=" + topMismatches + " first=" + firstTopMismatch);
        System.out.println("STATES non_air=" + nonAirBlocks + " grass=" + grassBlocks + " dirt=" + dirtBlocks
                + " stone=" + stoneBlocks + " water=" + waterBlocks + " lava=" + lavaBlocks);
    }

    private static ProtoChunk newChunk(int cx, int cz, LevelHeightAccessor height, PalettedContainerFactory factory,
            RandomState randomState, NoiseGeneratorSettings settings, Aquifer.FluidPicker picker) {
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, height, factory, null);
        chunk.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(
                c, randomState, DensityFunctions.BeardifierMarker.INSTANCE, settings, picker, Blender.empty()));
        return chunk;
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

    private static String stateId(BlockState state) {
        return state == null ? "null" : Integer.toString(Block.BLOCK_STATE_REGISTRY.getId(state));
    }

    private static final class MutableContext implements DensityFunction.FunctionContext {
        private int x;
        private int y;
        private int z;

        void set(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override public int blockX() { return x; }
        @Override public int blockY() { return y; }
        @Override public int blockZ() { return z; }
    }
}
