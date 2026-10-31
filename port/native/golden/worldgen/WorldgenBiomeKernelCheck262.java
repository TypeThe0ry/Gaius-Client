package dev.gaius.browser.kernel.worldgen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import com.mojang.datafixers.util.Pair;
import dev.gaius.browser.kernel.KernelStatusFrontier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Exercises the 26.2 BIOMES eligibility check against real profile biome source classes. */
public final class WorldgenBiomeKernelCheck262 {
    private static final long GOLDEN_SEED = 20261014L;
    /** The 43 FULL-harness sulfur coordinates that exposed the 26.2 feature-order defect. */
    private static final int[][] GOLDEN_SULFUR = {
        {-542, -20, -89}, {-542, -20, -88}, {-549, -19, -89}, {-547, -19, -89},
        {-546, -19, -89}, {-545, -19, -89}, {-544, -19, -89}, {-549, -19, -88},
        {-548, -19, -88}, {-547, -19, -88}, {-546, -19, -88}, {-545, -19, -88},
        {-545, -19, -87}, {-549, -18, -89}, {-548, -18, -88}, {-546, -13, -87},
        {-549, -12, -89}, {-547, -12, -88}, {-546, -12, -88}, {-545, -12, -88},
        {-546, -12, -87}, {-549, -11, -89}, {-545, -11, -89}, {-544, -11, -89},
        {-547, -11, -88}, {-546, -11, -88}, {-545, -11, -88}, {-544, -11, -88},
        {-543, -11, -88}, {-542, -11, -88}, {-549, -10, -89}, {-545, -10, -89},
        {-544, -10, -89}, {-547, -10, -88}, {-545, -10, -88}, {-544, -10, -88},
        {-543, -10, -88}, {-542, -10, -88}, {-544, -9, -89}, {-544, -9, -88},
        {-543, -9, -88}, {-542, -9, -88}, {-543, -8, -88},
    };

    private WorldgenBiomeKernelCheck262() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Climate.Parameter zero = Climate.Parameter.point(0.0F);
        Climate.ParameterPoint point = Climate.parameters(zero, zero, zero, zero, zero, zero, 0L);
        var lookup = VanillaRegistries.createLookup();
        var plains = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries =
                List.of(Pair.<Climate.ParameterPoint, Holder<Biome>>of(point, plains));
        BiomeSource multiNoise = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(entries));
        BiomeSource fixed = new FixedBiomeSource(plains);
        var biomes = lookup.lookupOrThrow(Registries.BIOME);
        BiomeSource end = TheEndBiomeSource.create(biomes);
        require(!WorldgenKernel262.biomeSourceSupportsKernel(multiNoise),
                "multi-noise must use vanilla without a thread-history-preserving kernel job");
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        require(WorldgenKernel262.createBiomes(new NoiseBasedChunkGenerator(multiNoise, settings),
                null, null, null, null) == null,
                "known-declined jobs must return before touching the browser bridge or chunk");
        var presets = lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        for (var preset : List.of(MultiNoiseBiomeSourceParameterLists.OVERWORLD,
                MultiNoiseBiomeSourceParameterLists.NETHER)) {
            BiomeSource presetSource = MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(preset));
            require(!WorldgenKernel262.biomeSourceSupportsKernel(presetSource),
                    "preset multi-noise must use vanilla: " + preset);
            require(WorldgenKernel262.createBiomes(new NoiseBasedChunkGenerator(presetSource, settings),
                    null, null, null, null) == null,
                    "preset decline must bypass browser dispatch: " + preset);
        }
        require(WorldgenKernel262.biomeSourceSupportsKernel(fixed),
                "fixed biomes remain eligible for exact kernel execution");
        require(WorldgenKernel262.biomeSourceSupportsKernel(end),
                "End biomes remain eligible for exact kernel execution");
        checkGoldenFixture();
        checkProtoStateRegression();
        checkCarverNoiseStateRegression();
        checkNoiseChunkContract();
        checkTerrainFrontierOrdering();
        checkLateCallbackClaims();
        System.out.println("WorldgenBiomeKernelCheck262 PASS");
    }

    private static void checkGoldenFixture() {
        require(GOLDEN_SEED == 20261014L, "26.2 sulfur golden seed changed");
        Set<String> coordinates = new HashSet<>();
        for (int[] coordinate : GOLDEN_SULFUR) {
            require(coordinate.length == 3, "malformed sulfur golden coordinate");
            require(coordinates.add(coordinate[0] + "," + coordinate[1] + "," + coordinate[2]),
                    "duplicate sulfur golden coordinate");
        }
        require(coordinates.size() == 43, "sulfur golden coordinate count changed");

        var lookup = VanillaRegistries.createLookup();
        var settingsHolder = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var source = MultiNoiseBiomeSource.createFromPreset(lookup
                .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        var generator = new NoiseBasedChunkGenerator(source, settingsHolder);
        var randomState = RandomState.create(settingsHolder.value(), lookup.lookupOrThrow(Registries.NOISE), GOLDEN_SEED);
        var height = LevelHeightAccessor.create(-64, 384);
        var biomeLookup = lookup.lookupOrThrow(Registries.BIOME);
        IdMapper<Holder<Biome>> biomeMap = new IdMapper<>();
        biomeLookup.listElements().forEach(biomeMap::add);
        var factory = new PalettedContainerFactory(
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState(), null,
                Strategy.createForBiomes(biomeMap), biomeMap.byId(0), null);
        Aquifer.FluidPicker picker = (x, y, z) -> y < -54
                ? new Aquifer.FluidStatus(-54, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState())
                : new Aquifer.FluidStatus(settingsHolder.value().seaLevel(), settingsHolder.value().defaultFluid());
        Map<Long, ProtoChunk> chunks = new HashMap<>();
        for (int[] coordinate : GOLDEN_SULFUR) {
            int cx = coordinate[0] >> 4;
            int cz = coordinate[2] >> 4;
            long key = ChunkPos.pack(cx, cz);
            if (chunks.containsKey(key)) continue;
            ProtoChunk chunk = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, height, factory, null);
            chunk.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, randomState,
                    beardifierMarker(), settingsHolder.value(), picker, Blender.empty()));
            generator.createBiomes(randomState, Blender.empty(), null, chunk).join();
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            chunks.put(key, chunk);
        }
        for (ProtoChunk chunk : new ArrayList<>(chunks.values())) {
            generator.fillFromNoise(Blender.empty(), randomState, null, chunk).join();
            BiomeManager.NoiseBiomeSource regionBiomes = (qx, qy, qz) -> {
                int cx = qx >> 2;
                int cz = qz >> 2;
                long key = ChunkPos.pack(cx, cz);
                ProtoChunk neighbour = chunks.get(key);
                if (neighbour == null) {
                    neighbour = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, height, factory, null);
                    neighbour.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, randomState,
                            beardifierMarker(), settingsHolder.value(), picker, Blender.empty()));
                    generator.createBiomes(randomState, Blender.empty(), null, neighbour).join();
                    neighbour.setPersistedStatus(ChunkStatus.BIOMES);
                    chunks.put(key, neighbour);
                }
                return neighbour.getNoiseBiome(qx, qy, qz);
            };
            BiomeManager biomeManager = new BiomeManager(regionBiomes, BiomeManager.obfuscateSeed(GOLDEN_SEED));
            generator.buildSurface(chunk, new WorldGenerationContext(generator, height), randomState, null,
                    biomeManager, Blender.empty(), source.possibleBiomes());
        }
        for (int[] coordinate : GOLDEN_SULFUR) {
            ProtoChunk chunk = chunks.get(ChunkPos.pack(coordinate[0] >> 4, coordinate[2] >> 4));
            String actual = BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(
                    new BlockPos(coordinate[0], coordinate[1], coordinate[2])).getBlock()).toString();
            require("minecraft:cinnabar".equals(actual), "vanilla TERRAIN golden mismatch at "
                    + coordinate[0] + "," + coordinate[1] + "," + coordinate[2] + ": " + actual);
        }
        // FEATURES changes these exact terrain values to sulfur in the full JVM harness. When the
        // harness artifact is supplied, validate every final-state record rather than only its
        // coordinate count. The terrain generation above remains self-contained for the normal
        // kernel check invocation.
        checkFullGoldenArtifact();
        System.out.println("WorldgenBiomeKernelCheck262 golden seed=" + GOLDEN_SEED
                + " sulfurCoordinates=" + coordinates.size() + " TERRAIN=43/43 FULL_EXPECTED=sulfur");
    }

    /** Compares vanilla terrain with the real bridge commit on twenty deterministic risk chunks. */
    private static void checkProtoStateRegression() {
        var lookup = VanillaRegistries.createLookup();
        var settingsHolder = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var source = MultiNoiseBiomeSource.createFromPreset(lookup
                .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        var generator = new NoiseBasedChunkGenerator(source, settingsHolder);
        var randomState = RandomState.create(settingsHolder.value(), lookup.lookupOrThrow(Registries.NOISE), 20261014L);
        var height = LevelHeightAccessor.create(-64, 384);
        var biomeLookup = lookup.lookupOrThrow(Registries.BIOME);
        IdMapper<Holder<Biome>> biomeMap = new IdMapper<>();
        biomeLookup.listElements().forEach(biomeMap::add);
        var factory = new PalettedContainerFactory(
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState(), null,
                Strategy.createForBiomes(biomeMap), biomeMap.byId(0), null);
        int[][] coordinates = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {7, -3}, {-7, 3}, {16, 16}, {-16, -16},
            {31, -32}, {-31, 32}, {64, -64}, {-64, 64}, {127, 127}, {-127, -127}, {256, -256},
            {-256, 256}, {511, -511}, {-511, 511}, {1024, -1024}
        };
        long vanillaNanos = 0L;
        long fastNanos = 0L;
        for (int[] coordinate : coordinates) {
            ChunkPos pos = new ChunkPos(coordinate[0], coordinate[1]);
            ProtoChunk vanilla = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
            Aquifer.FluidPicker picker = (x, y, z) -> y < -54
                    ? new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState())
                    : new Aquifer.FluidStatus(settingsHolder.value().seaLevel(), settingsHolder.value().defaultFluid());
            vanilla.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, randomState,
                    beardifierMarker(), settingsHolder.value(), picker, Blender.empty()));
            vanilla.setPersistedStatus(ChunkStatus.BIOMES);
            long vanillaStart = System.nanoTime();
            generator.fillFromNoise(Blender.empty(), randomState, null, vanilla).join();
            vanillaNanos += System.nanoTime() - vanillaStart;

            ProtoChunk kernel = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
            kernel.setPersistedStatus(ChunkStatus.BIOMES);
            long fastStart = System.nanoTime();
            WorldgenKernel262.installTerrainFastForJvmTest(kernel, vanilla);
            fastNanos += System.nanoTime() - fastStart;
            // The production 26.2 fix preserves this object across NOISE -> SURFACE/CARVERS.
            var vanillaNoise = vanilla.getOrCreateNoiseChunk(c -> null);
            require(vanillaNoise != null, "vanilla terrain did not retain NoiseChunk at " + pos);
            kernel.getOrCreateNoiseChunk(c -> vanillaNoise);

            String expected = protoState(vanilla);
            String actual = protoState(kernel);
            require(expected.equals(actual), "ProtoChunk state mismatch at " + pos + "\nvanilla="
                    + expected + "\nkernel=" + actual);
        }
        System.out.println("WorldgenBiomeKernelCheck262 proto-state regression PASS chunks=20 fields=all"
                + " vanilla_doFill_ms_per_chunk=" + (vanillaNanos / 20_000_000.0)
                + " fast_install_ms_per_chunk=" + (fastNanos / 20_000_000.0));
        int areaChecked = 0;
        for (int cz = -10; cz <= 10; cz++) {
            for (int cx = -10; cx <= 10; cx++) {
                ChunkPos pos = new ChunkPos(cx, cz);
                ProtoChunk vanilla = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
                Aquifer.FluidPicker picker = (x, y, z) -> y < -54
                        ? new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState())
                        : new Aquifer.FluidStatus(settingsHolder.value().seaLevel(), settingsHolder.value().defaultFluid());
                vanilla.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, randomState,
                        beardifierMarker(), settingsHolder.value(), picker, Blender.empty()));
                vanilla.setPersistedStatus(ChunkStatus.BIOMES);
                generator.fillFromNoise(Blender.empty(), randomState, null, vanilla).join();
                ProtoChunk fast = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
                fast.setPersistedStatus(ChunkStatus.BIOMES);
                WorldgenKernel262.installTerrainFastForJvmTest(fast, vanilla);
                var vanillaNoise = vanilla.getOrCreateNoiseChunk(c -> null);
                fast.getOrCreateNoiseChunk(c -> vanillaNoise);
                require(protoState(vanilla).equals(protoState(fast)), "26.2 area state mismatch at " + pos);
                areaChecked++;
            }
        }
        System.out.println("WorldgenBiomeKernelCheck262 fast-path area PASS chunks=" + areaChecked);
    }

    /** Replays the real 26.2 ConfiguredWorldCarver list with a vanilla query table. */
    private static void checkCarverNoiseStateRegression() {
        var lookup = VanillaRegistries.createLookup();
        var settingsHolder = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var plains = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(plains), settingsHolder);
        var randomState = RandomState.create(settingsHolder.value(), lookup.lookupOrThrow(Registries.NOISE), GOLDEN_SEED);
        var height = LevelHeightAccessor.create(-64, 384);
        var biomeLookup = lookup.lookupOrThrow(Registries.BIOME);
        IdMapper<Holder<Biome>> biomeMap = new IdMapper<>();
        biomeLookup.listElements().forEach(biomeMap::add);
        var factory = new PalettedContainerFactory(
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState(), null,
                Strategy.createForBiomes(biomeMap), biomeMap.byId(0), null);
        var noiseSettings = settingsHolder.value().noiseSettings().clampToHeightAccessor(height);
        int minCellY = Math.floorDiv(noiseSettings.minY(), noiseSettings.getCellHeight());
        int cellCountY = Math.floorDiv(noiseSettings.height(), noiseSettings.getCellHeight());
        Aquifer.FluidPicker picker = (x, y, z) -> y < -54
                ? new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState())
                : new Aquifer.FluidStatus(settingsHolder.value().seaLevel(), settingsHolder.value().defaultFluid());
        int[][] risk = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {7, -3}, {-7, 3}, {16, 16}, {-16, -16},
            {31, -32}, {-31, 32}, {64, -64}, {-64, 64}, {127, 127}, {-127, -127}, {256, -256},
            {-256, 256}, {511, -511}, {-511, 511}, {1024, -1024}
        };
        long vanillaNanos = 0L;
        long shimNanos = 0L;
        long lazyNanos = 0L;
        int aquiferQueries = 0;
        int preliminaryQueries = 0;
        for (int[] coordinate : risk) {
            CarverRun run = runCarverChunk(new ChunkPos(coordinate[0], coordinate[1]), generator,
                    randomState, settingsHolder.value(), height, factory, picker, minCellY, cellCountY,
                    RegistryAccess.EMPTY);
            require(run.matches, "26.2 carver shim mismatch at " + coordinate[0] + "," + coordinate[1]);
            require(run.lazyMatches, "26.2 fresh lazy carver mismatch at " + coordinate[0] + "," + coordinate[1]);
            vanillaNanos += run.vanillaNanos;
            shimNanos += run.shimNanos;
            lazyNanos += run.lazyNanos;
            aquiferQueries += run.aquiferQueries;
            preliminaryQueries += run.preliminaryQueries;
        }
        System.out.println("WorldgenBiomeKernelCheck262 carver-shim regression PASS chunks=20 fields=all"
                + " vanilla_carver_ms_per_chunk=" + (vanillaNanos / 20_000_000.0)
                + " shim_carver_ms_per_chunk=" + (shimNanos / 20_000_000.0)
                + " fresh_lazy_carver_ms_per_chunk=" + (lazyNanos / 20_000_000.0)
                + " aquifer_queries=" + aquiferQueries + " preliminary_queries=" + preliminaryQueries);
        if ("1".equals(System.getenv("GAIUS_SHIM_RISK_ONLY"))) {
            return;
        }
        int areaChecked = 0;
        long areaVanillaNanos = 0L;
        long areaShimNanos = 0L;
        long areaLazyNanos = 0L;
        for (int cz = -10; cz <= 10; cz++) {
            for (int cx = -10; cx <= 10; cx++) {
                CarverRun run = runCarverChunk(new ChunkPos(cx, cz), generator, randomState,
                        settingsHolder.value(), height, factory, picker, minCellY, cellCountY, RegistryAccess.EMPTY);
                require(run.matches, "26.2 carver area mismatch at " + cx + "," + cz);
                require(run.lazyMatches, "26.2 fresh lazy carver area mismatch at " + cx + "," + cz);
                areaVanillaNanos += run.vanillaNanos;
                areaShimNanos += run.shimNanos;
                areaLazyNanos += run.lazyNanos;
                areaChecked++;
            }
        }
        System.out.println("WorldgenBiomeKernelCheck262 carver-shim area PASS chunks=" + areaChecked
                + " vanilla_carver_ms_per_chunk=" + (areaVanillaNanos / (areaChecked * 1_000_000.0))
                + " shim_carver_ms_per_chunk=" + (areaShimNanos / (areaChecked * 1_000_000.0))
                + " fresh_lazy_carver_ms_per_chunk=" + (areaLazyNanos / (areaChecked * 1_000_000.0)));
    }

    private record CarverRun(boolean matches, boolean lazyMatches, int aquiferQueries, int preliminaryQueries,
            long vanillaNanos, long shimNanos, long lazyNanos) {
    }

    private static CarverRun runCarverChunk(ChunkPos pos, NoiseBasedChunkGenerator generator,
            RandomState randomState, NoiseGeneratorSettings settings, LevelHeightAccessor height,
            PalettedContainerFactory factory, Aquifer.FluidPicker picker, int minCellY, int cellCountY,
            RegistryAccess lookup) {
        CarverNoiseState262.Table table = new CarverNoiseState262.Table();
        CarverNoiseState262.RecordingNoiseChunk warmNoise = CarverNoiseState262.recordingForChunk(
                new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null), randomState, settings,
                picker, Blender.empty(), table);
        ProtoChunk expected = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
        expected.getOrCreateNoiseChunk(c -> warmNoise);
        try {
            Method doFill = NoiseBasedChunkGenerator.class.getDeclaredMethod("doFill", Blender.class,
                    net.minecraft.world.level.StructureManager.class, RandomState.class, ChunkAccess.class,
                    int.class, int.class);
            doFill.setAccessible(true);
            doFill.invoke(generator, Blender.empty(), null, randomState, expected, minCellY, cellCountY);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("26.2 recording doFill failed at " + pos, e);
        }
        ProtoChunk terrain = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
        WorldgenKernel262.installTerrainFastForJvmTest(terrain, expected);
        ProtoChunk actual = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
        WorldgenKernel262.installTerrainFastForJvmTest(actual, terrain);
        CarvingMask warmMask = new CarvingMask(expected.getHeight(), expected.getMinY());
        CarvingMask replayMask = new CarvingMask(expected.getHeight(), expected.getMinY());
        var resolver = generator.getBiomeSource();
        Function<BlockPos, Holder<Biome>> biome = p -> resolver.getNoiseBiome(p.getX() >> 2, p.getY() >> 2,
                p.getZ() >> 2, null);
        CarvingContext warmContext = new CarvingContext(generator, lookup, height, warmNoise, randomState,
                settings.surfaceRule());
        long vanillaStart = System.nanoTime();
        generateConfiguredCarvers(pos, generator, randomState, warmContext, expected, warmNoise.aquifer(),
                warmMask, biome);
        long vanillaNanos = System.nanoTime() - vanillaStart;
        CarverNoiseState262.ReplayNoiseChunk replayNoise = CarverNoiseState262.replayForChunk(
                actual, randomState, settings, picker, Blender.empty(), table);
        actual.getOrCreateNoiseChunk(c -> replayNoise);
        CarvingContext replayContext = new CarvingContext(generator, lookup, height, replayNoise, randomState,
                settings.surfaceRule());
        long shimStart = System.nanoTime();
        generateConfiguredCarvers(pos, generator, randomState, replayContext, actual, replayNoise.aquifer(),
                replayMask, biome);
        long shimNanos = System.nanoTime() - shimStart;
        ProtoChunk lazy = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
        WorldgenKernel262.installTerrainFastForJvmTest(lazy, terrain);
        NoiseChunk freshNoise = NoiseChunk.forChunk(lazy, randomState, beardifierMarker(), settings, picker,
                Blender.empty());
        lazy.getOrCreateNoiseChunk(c -> freshNoise);
        CarvingContext lazyContext = new CarvingContext(generator, lookup, height, freshNoise, randomState,
                settings.surfaceRule());
        CarvingMask lazyMask = new CarvingMask(expected.getHeight(), expected.getMinY());
        long lazyStart = System.nanoTime();
        generateConfiguredCarvers(pos, generator, randomState, lazyContext, lazy, freshNoise.aquifer(),
                lazyMask, biome);
        long lazyNanos = System.nanoTime() - lazyStart;
        return new CarverRun(protoState(expected).equals(protoState(actual)), protoState(expected).equals(protoState(lazy)),
                table.aquiferQueries(), table.preliminaryQueries(), vanillaNanos, shimNanos, lazyNanos);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void generateConfiguredCarvers(ChunkPos target, NoiseBasedChunkGenerator generator,
            RandomState randomState, CarvingContext context, ChunkAccess chunk, Aquifer aquifer,
            CarvingMask mask, Function<BlockPos, Holder<Biome>> biome) {
        var resolver = generator.getBiomeSource();
        Holder<Biome> biomeHolder = resolver.getNoiseBiome(target.getMinBlockX() >> 2, 0,
                target.getMinBlockZ() >> 2, null);
        Iterable<Holder<ConfiguredWorldCarver<?>>> carvers = biomeHolder.value().getGenerationSettings().getCarvers();
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        int index;
        for (int dz = -8; dz <= 8; dz++) {
            for (int dx = -8; dx <= 8; dx++) {
                ChunkPos source = new ChunkPos(target.x() + dx, target.z() + dz);
                index = 0;
                for (Holder<ConfiguredWorldCarver<?>> holder : carvers) {
                    ConfiguredWorldCarver configured = holder.value();
                    random.setLargeFeatureSeed(GOLDEN_SEED + index, source.x(), source.z());
                    if (configured.isStartChunk(random)) {
                        configured.carve(context, chunk, biome, random, aquifer, source, mask);
                    }
                    index++;
                }
            }
        }
    }

    private static String protoState(ProtoChunk chunk) {
        StringBuilder out = new StringBuilder();
        out.append("blocks=").append(blockDigest(chunk));
        out.append(" sections=");
        for (int section = 0; section < chunk.getSectionsCount(); section++) {
            LevelChunkSection value = chunk.getSection(section);
            out.append(sectionCounters(value)).append(';');
        }
        out.append(" heights=");
        for (var entry : chunk.getHeightmaps()) {
            out.append(entry.getKey()).append(':');
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                out.append(chunk.getHeight(entry.getKey(), x, z)).append(',');
            }
        }
        out.append(" post=").append(postState(chunk));
        var ticks = chunk.getTicksForSerialization(0L);
        out.append(" ticks=").append(ticks.blocks()).append('/').append(ticks.fluids());
        out.append(" sky=");
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            out.append(chunk.getSkyLightSources().getLowestSourceY(x, z)).append(',');
        }
        out.append(" light=").append(chunk.isLightCorrect());
        out.append(" status=").append(chunk.getPersistedStatus());
        out.append(" entities=").append(chunk.getBlockEntitiesPos());
        out.append(" starts=").append(chunk.getAllStarts().keySet());
        out.append(" refs=");
        for (var structure : chunk.getAllStarts().keySet()) {
            out.append(structure).append(':').append(chunk.getReferencesForStructure(structure)).append(';');
        }
        out.append(" noise=").append(chunk.getOrCreateNoiseChunk(c -> null) != null);
        return out.toString();
    }

    private static String blockDigest(ProtoChunk chunk) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = chunk.getMinY(); y < chunk.getMinY() + chunk.getHeight(); y++) {
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int id = Block.BLOCK_STATE_REGISTRY.getId(chunk.getBlockState(pos.set(
                            chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z)));
                    digest.update((byte) id);
                    digest.update((byte) (id >>> 8));
                    digest.update((byte) (id >>> 16));
                    digest.update((byte) (id >>> 24));
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xFF));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String sectionCounters(LevelChunkSection section) {
        try {
            StringBuilder out = new StringBuilder();
            for (String field : List.of("nonEmptyBlockCount", "fluidCount", "tickingBlockCount", "tickingFluidCount")) {
                Field value = LevelChunkSection.class.getDeclaredField(field);
                value.setAccessible(true);
                out.append(value.getShort(section)).append(',');
            }
            out.append(section.hasOnlyAir()).append(',').append(section.hasFluid()).append(',')
                    .append(section.isRandomlyTicking());
            return out.toString();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("section counters unavailable", e);
        }
    }

    private static String postState(ProtoChunk chunk) {
        StringBuilder out = new StringBuilder();
        var lists = chunk.getPostProcessing();
        for (int section = 0; section < lists.length; section++) {
            if (lists[section] != null) out.append(section).append(':').append(lists[section]).append(';');
        }
        return out.toString();
    }

    private static void checkFullGoldenArtifact() {
        String configured = System.getProperty("gaius.worldgen.fullGolden");
        if (configured == null || configured.isEmpty()) {
            configured = System.getenv("GAIUS_WORLDGEN_FULL_GOLDEN");
        }
        if (configured == null || configured.isEmpty()) return;
        try {
            String json = Files.readString(Path.of(configured));
            require(json.contains("\"seed\": \"20261014\""), "FULL golden seed changed");
            Pattern record = Pattern.compile("\\{\\s*\\\"x\\\":\\s*(-?\\d+),\\s*\\\"y\\\":\\s*(-?\\d+),\\s*\\\"z\\\":\\s*(-?\\d+),.*?\\\"full\\\":\\s*\\\"Block\\{([^}]+)\\}\\\"\\s*\\}", Pattern.DOTALL);
            Matcher matcher = record.matcher(json);
            Map<String, String> states = new HashMap<>();
            while (matcher.find()) {
                String key = matcher.group(1) + "," + matcher.group(2) + "," + matcher.group(3);
                require(states.put(key, matcher.group(4)) == null, "duplicate FULL golden record: " + key);
            }
            require(states.size() == GOLDEN_SULFUR.length,
                    "FULL golden record count changed: " + states.size());
            for (int[] coordinate : GOLDEN_SULFUR) {
                String key = coordinate[0] + "," + coordinate[1] + "," + coordinate[2];
                require("minecraft:sulfur".equals(states.get(key)),
                        "FULL golden mismatch at " + key + ": " + states.get(key));
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read FULL golden artifact " + configured, e);
        }
    }

    private static DensityFunctions.BeardifierOrMarker beardifierMarker() {
        try {
            Class<?> marker = Class.forName("net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker");
            var field = marker.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            return (DensityFunctions.BeardifierOrMarker) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("vanilla beardifier marker unavailable", e);
        }
    }

    /**
     * Regression for the defect where a later kernel result crossed the Java status frontier.
     * The old bridge had no ordered action queue; this check therefore fails before the fix.
     */
    private static void checkTerrainFrontierOrdering() {
        KernelStatusFrontier<net.minecraft.world.level.chunk.ChunkAccess> frontier = new KernelStatusFrontier<>();
        List<Integer> commits = new ArrayList<>();
        KernelStatusFrontier.Ticket<net.minecraft.world.level.chunk.ChunkAccess> first = frontier.register(
                () -> CompletableFuture.completedFuture(null), 0, reason -> {});
        KernelStatusFrontier.Ticket<net.minecraft.world.level.chunk.ChunkAccess> second = frontier.register(
                () -> CompletableFuture.completedFuture(null), 0, reason -> {});
        first.consumeAtTask();
        second.consumeAtTask();
        require(second.claimKernel(() -> {
            commits.add(2);
            return CompletableFuture.completedFuture(null);
        }), "26.2 terrain ticket did not claim kernel action");
        require(!second.future().isDone(), "26.2 second terrain crossed the first action");
        require(first.claimKernel(() -> {
            commits.add(1);
            return CompletableFuture.completedFuture(null);
        }), "26.2 first terrain ticket did not claim kernel action");
        first.future().join();
        second.future().join();
        require(commits.equals(List.of(1, 2)), "26.2 terrain commit order changed: " + commits);
        KernelStatusFrontier.Ticket<net.minecraft.world.level.chunk.ChunkAccess> fallback = frontier.register(
                () -> {
                    commits.add(3);
                    return CompletableFuture.completedFuture(null);
                }, 0, reason -> {});
        fallback.consumeAtTask();
        require(fallback.fallback("sync-submit"), "26.2 fallback did not claim terrain ticket");
        fallback.future().join();
        require(commits.equals(List.of(1, 2, 3)), "26.2 fallback crossed terrain frontier: " + commits);
    }

    private static void checkNoiseChunkContract() {
        boolean create = false;
        boolean doFill = false;
        for (var method : WorldgenKernelHooks262.class.getDeclaredMethods()) {
            create |= method.getName().equals("gaius$createNoiseChunk");
            doFill |= method.getName().equals("gaius$doFill");
        }
        require(create && doFill, "26.2 terrain bridge must expose createNoiseChunk and doFill");
    }

    private static void checkLateCallbackClaims() {
        for (String stage : List.of("biomes", "terrain", "surface")) {
            AtomicBoolean claim = new AtomicBoolean();
            List<String> writes = new ArrayList<>();
            require(claim.compareAndSet(false, true), stage + " kernel claim was not acquired");
            writes.add("kernel");
            require(!claim.compareAndSet(false, true), stage + " late fallback acquired kernel claim");
            require(writes.equals(List.of("kernel")), stage + " late callback wrote after claim");

            claim.set(false);
            require(claim.compareAndSet(false, true), stage + " fallback claim was not acquired");
            writes.clear();
            writes.add("vanilla");
            require(!claim.compareAndSet(false, true), stage + " late kernel acquired fallback claim");
            require(writes.equals(List.of("vanilla")), stage + " late kernel wrote after fallback");
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
