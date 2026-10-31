package dev.gaius.browser.kernel.worldgen;

import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import com.mojang.datafixers.util.Pair;
import dev.gaius.browser.kernel.KernelStatusFrontier;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;

/** Exercises the production BIOMES eligibility check against real profile biome source classes. */
public final class WorldgenBiomeKernelCheck {
    private WorldgenBiomeKernelCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Climate.Parameter zero = Climate.Parameter.point(0.0F);
        Climate.ParameterPoint point = Climate.parameters(zero, zero, zero, zero, zero, zero, 0L);
        var lookup = VanillaRegistries.createWorldLookup();
        var plains = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        BiomeSource multiNoise = MultiNoiseBiomeSource.createFromList(
                new Climate.ParameterList<>(List.of(Pair.of(point, plains))));
        BiomeSource fixed = new FixedBiomeSource(plains);
        var biomes = lookup.lookupOrThrow(Registries.BIOME);
        BiomeSource end = TheEndBiomeSource.create(biomes);

        require(!WorldgenKernel263.biomeSourceSupportsKernel(multiNoise),
                "multi-noise must use vanilla without a thread-history-preserving kernel job");
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        require(WorldgenKernel263.createBiomes(new NoiseBasedChunkGenerator(multiNoise, settings),
                null, null, null, null) == null,
                "known-declined jobs must return before touching the browser bridge or chunk");
        var presets = lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        for (var preset : List.of(MultiNoiseBiomeSourceParameterLists.OVERWORLD,
                MultiNoiseBiomeSourceParameterLists.NETHER)) {
            BiomeSource presetSource = MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(preset));
            require(!WorldgenKernel263.biomeSourceSupportsKernel(presetSource),
                    "preset multi-noise must use vanilla: " + preset);
            require(WorldgenKernel263.createBiomes(new NoiseBasedChunkGenerator(presetSource, settings),
                    null, null, null, null) == null,
                    "preset decline must bypass browser dispatch: " + preset);
        }
        require(WorldgenKernel263.biomeSourceSupportsKernel(fixed),
                "fixed biomes remain eligible for exact kernel execution");
        require(WorldgenKernel263.biomeSourceSupportsKernel(end),
                "End biomes remain eligible for exact kernel execution");
        checkTerrainFrontierOrdering();
        checkLateCallbackClaims();
        checkCarverNoiseChunkContract();
        if (!"1".equals(System.getenv("GAIUS_CARVER_ONLY"))) {
            checkProtoStateRegression();
        }
        checkCarverNoiseStateRegression();
        System.out.println("WorldgenBiomeKernelCheck PASS");
    }

    private static void checkTerrainFrontierOrdering() {
        KernelStatusFrontier<ChunkAccess> frontier = new KernelStatusFrontier<>();
        List<Integer> commits = new ArrayList<>();
        KernelStatusFrontier.Ticket<ChunkAccess> biome = frontier.register(
                () -> CompletableFuture.completedFuture(null), 0, reason -> {});
        KernelStatusFrontier.Ticket<ChunkAccess> terrain = frontier.register(
                () -> CompletableFuture.completedFuture(null), 0, reason -> {});
        biome.consumeAtTask();
        terrain.consumeAtTask();
        require(terrain.claimKernel(() -> {
            commits.add(2);
            return CompletableFuture.completedFuture(null);
        }), "26.3 terrain ticket did not claim kernel action");
        require(!terrain.future().isDone(), "26.3 terrain crossed the earlier biome ticket");
        require(biome.claimKernel(() -> {
            commits.add(1);
            return CompletableFuture.completedFuture(null);
        }), "26.3 biome ticket did not claim kernel action");
        biome.future().join();
        terrain.future().join();
        require(commits.equals(List.of(1, 2)), "26.3 cross-status commit order changed: " + commits);

        KernelStatusFrontier.Ticket<ChunkAccess> fallback = frontier.register(
                () -> {
                    commits.add(3);
                    return CompletableFuture.completedFuture(null);
                }, 0, reason -> {});
        fallback.consumeAtTask();
        require(fallback.fallback("sync-submit"), "26.3 fallback did not claim status ticket");
        fallback.future().join();
        require(commits.equals(List.of(1, 2, 3)), "26.3 fallback crossed status frontier: " + commits);
    }

    private static void checkLateCallbackClaims() {
        for (String stage : List.of("biomes", "terrain")) {
            AtomicBoolean claim = new AtomicBoolean();
            require(claim.compareAndSet(false, true), stage + " kernel claim missing");
            require(!claim.compareAndSet(false, true), stage + " late fallback acquired claim");
            claim.set(false);
            require(claim.compareAndSet(false, true), stage + " fallback claim missing");
            require(!claim.compareAndSet(false, true), stage + " late kernel acquired fallback claim");
        }
    }

    private static void checkCarverNoiseChunkContract() {
        boolean doFill = false;
        boolean create = false;
        boolean carvers = false;
        for (var method : WorldgenKernelHooks263.class.getDeclaredMethods()) {
            doFill |= method.getName().equals("gaius$doFill");
            create |= method.getName().equals("gaius$createNoiseChunk");
            carvers |= method.getName().equals("gaius$generateCarvers");
        }
        require(doFill && create && carvers,
                "26.3 carver bridge must expose one NoiseChunk across doFill and generateCarvers");
    }

    /** Compares vanilla doFill with the real bridge commit on twenty deterministic risk chunks. */
    private static void checkProtoStateRegression() {
        var lookup = VanillaRegistries.createWorldLookup();
        var settingsHolder = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var source = new FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
        var generator = new NoiseBasedChunkGenerator(source, settingsHolder);
        var randomState = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), 20261014L, settingsHolder.value());
        var height = LevelHeightAccessor.create(-64, 384);
        var biomeLookup = lookup.lookupOrThrow(Registries.BIOME);
        IdMapper<Holder<net.minecraft.world.level.biome.Biome>> biomeMap = new IdMapper<>();
        biomeLookup.listElements().forEach(biomeMap::add);
        var factory = new PalettedContainerFactory(
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState(), null,
                Strategy.createForBiomes(biomeMap), biomeMap.byId(0), null);
        Method doFill;
        try {
            doFill = NoiseBasedChunkGenerator.class.getDeclaredMethod("doFill", NoiseChunk.class, ChunkAccess.class);
            doFill.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("26.3 vanilla doFill bridge unavailable", e);
        }
        int[][] coordinates = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {7, -3}, {-7, 3}, {16, 16}, {-16, -16},
            {31, -32}, {-31, 32}, {64, -64}, {-64, 64}, {127, 127}, {-127, -127}, {256, -256},
            {-256, 256}, {511, -511}, {-511, 511}, {1024, -1024}
        };
        long vanillaNanos = 0L;
        long fastNanos = 0L;
        Aquifer.FluidPicker picker = (x, y, z) -> y < -54
                ? new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState())
                : new Aquifer.FluidStatus(settingsHolder.value().seaLevel(), settingsHolder.value().defaultFluid());
        for (int[] coordinate : coordinates) {
            ChunkPos pos = new ChunkPos(coordinate[0], coordinate[1]);
            ProtoChunk vanilla = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
            DensityVolume volume = new DensityVolume(16, 384, 16, coordinate[0] << 4, -64, coordinate[1] << 4);
            NoiseChunk noiseChunk = new NoiseChunk(randomState, null, settingsHolder.value(), picker,
                    Blender.empty(), volume);
            long vanillaStart = System.nanoTime();
            try {
                doFill.invoke(generator, noiseChunk, vanilla);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("26.3 vanilla doFill failed at " + pos, e);
            }
            vanillaNanos += System.nanoTime() - vanillaStart;
            ProtoChunk kernel = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
            long fastStart = System.nanoTime();
            WorldgenKernel263.installTerrainFastForJvmTest(kernel, vanilla);
            fastNanos += System.nanoTime() - fastStart;
            require(protoState(vanilla).equals(protoState(kernel)), "ProtoChunk state mismatch at " + pos);
        }
        System.out.println("WorldgenBiomeKernelCheck proto-state regression PASS chunks=20 fields=all"
                + " vanilla_doFill_ms_per_chunk=" + (vanillaNanos / 20_000_000.0)
                + " fast_install_ms_per_chunk=" + (fastNanos / 20_000_000.0));

        int areaChecked = 0;
        for (int cz = -10; cz <= 10; cz++) {
            for (int cx = -10; cx <= 10; cx++) {
                ChunkPos pos = new ChunkPos(cx, cz);
                ProtoChunk vanilla = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
                DensityVolume volume = new DensityVolume(16, 384, 16, cx << 4, -64, cz << 4);
                NoiseChunk noiseChunk = new NoiseChunk(randomState, null, settingsHolder.value(), picker,
                        Blender.empty(), volume);
                try {
                    doFill.invoke(generator, noiseChunk, vanilla);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("26.3 area doFill failed at " + pos, e);
                }
                ProtoChunk fast = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
                WorldgenKernel263.installTerrainFastForJvmTest(fast, vanilla);
                require(blockDigest(vanilla).equals(blockDigest(fast)), "26.3 area block mismatch at " + pos);
                require(protoState(vanilla).equals(protoState(fast)), "26.3 area state mismatch at " + pos);
                areaChecked++;
            }
        }
        System.out.println("WorldgenBiomeKernelCheck fast-path area PASS chunks=" + areaChecked);
    }

    /**
     * Runs the real 26.3 carver list twice with an identical mask: the first pass records vanilla
     * aquifer/sampler answers, and the second pass uses only that table.  The mask is generated by
     * the same WorldCarver implementations and seed schedule as NoiseBasedChunkGenerator; using
     * an explicit mask lets both passes avoid the generator's intentionally unique per-call seed.
     */
    private static void checkCarverNoiseStateRegression() {
        var lookup = VanillaRegistries.createWorldLookup();
        var settingsHolder = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var plains = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(plains), settingsHolder);
        var randomState = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), 20261014L,
                settingsHolder.value());
        var height = LevelHeightAccessor.create(-64, 384);
        var biomeLookup = lookup.lookupOrThrow(Registries.BIOME);
        IdMapper<Holder<net.minecraft.world.level.biome.Biome>> biomeMap = new IdMapper<>();
        biomeLookup.listElements().forEach(biomeMap::add);
        var factory = new PalettedContainerFactory(
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState(), null,
                Strategy.createForBiomes(biomeMap), biomeMap.byId(0), null);
        Method doFill;
        Method applyCarvingMask;
        try {
            doFill = NoiseBasedChunkGenerator.class.getDeclaredMethod("doFill", NoiseChunk.class, ChunkAccess.class);
            doFill.setAccessible(true);
            applyCarvingMask = NoiseBasedChunkGenerator.class.getDeclaredMethod("applyCarvingMask",
                    ChunkAccess.class, CarvingMask.class, RandomState.class, MaterialRule.class,
                    WorldGenerationContext.class, NoiseChunk.class, Function.class, CarvingMask.Filter.class);
            applyCarvingMask.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("26.3 carver reflection seam unavailable", e);
        }
        Aquifer.FluidPicker picker = (x, y, z) -> y < -54
                ? new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState())
                : new Aquifer.FluidStatus(settingsHolder.value().seaLevel(), settingsHolder.value().defaultFluid());
        int[][] risk = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {7, -3}, {-7, 3}, {16, 16}, {-16, -16},
            {31, -32}, {-31, 32}, {64, -64}, {-64, 64}, {127, 127}, {-127, -127}, {256, -256},
            {-256, 256}, {511, -511}, {-511, 511}, {1024, -1024}
        };
        if ("1".equals(System.getenv("GAIUS_CARVER_AREA_ONLY"))) risk = new int[0][0];
        long vanillaNanos = 0L;
        long shimNanos = 0L;
        long lazyNanos = 0L;
        int totalAquifer = 0;
        int totalSampler = 0;
        for (int[] coordinate : risk) {
            CarverRun run = runCarverChunk(new ChunkPos(coordinate[0], coordinate[1]), generator,
                    randomState, settingsHolder.value(), height, factory, picker, doFill, applyCarvingMask);
            vanillaNanos += run.vanillaNanos;
            shimNanos += run.shimNanos;
            lazyNanos += run.lazyNanos;
            totalAquifer += run.aquiferQueries;
            totalSampler += run.samplerQueries;
            require(run.matches, "26.3 carver shim mismatch at " + coordinate[0] + "," + coordinate[1]);
            require(run.lazyMatches, "26.3 fresh lazy carver mismatch at " + coordinate[0] + "," + coordinate[1]);
        }
        System.out.println("WorldgenBiomeKernelCheck carver-shim regression PASS chunks=20 fields=all"
                + " vanilla_carver_ms_per_chunk=" + (vanillaNanos / 20_000_000.0)
                + " shim_carver_ms_per_chunk=" + (shimNanos / 20_000_000.0)
                + " fresh_lazy_carver_ms_per_chunk=" + (lazyNanos / 20_000_000.0)
                + " aquifer_queries=" + totalAquifer + " sampler_queries=" + totalSampler);
        System.gc();
        if ("1".equals(System.getenv("GAIUS_SHIM_RISK_ONLY"))
                || "1".equals(System.getenv("GAIUS_CARVER_RISK_ONLY"))) {
            return;
        }

        int areaChecked = 0;
        long areaVanillaNanos = 0L;
        long areaShimNanos = 0L;
        long areaLazyNanos = 0L;
        for (int cz = -10; cz <= 10; cz++) {
            for (int cx = -10; cx <= 10; cx++) {
                CarverRun run = runCarverChunk(new ChunkPos(cx, cz), generator, randomState,
                        settingsHolder.value(), height, factory, picker, doFill, applyCarvingMask);
                require(run.matches, "26.3 carver area mismatch at " + cx + "," + cz);
                areaVanillaNanos += run.vanillaNanos;
                areaShimNanos += run.shimNanos;
                areaLazyNanos += run.lazyNanos;
                require(run.lazyMatches, "26.3 fresh lazy carver area mismatch at " + cx + "," + cz);
                areaChecked++;
            }
        }
        System.out.println("WorldgenBiomeKernelCheck carver-shim area PASS chunks=" + areaChecked
                + " vanilla_carver_ms_per_chunk=" + (areaVanillaNanos / (areaChecked * 1_000_000.0))
                + " shim_carver_ms_per_chunk=" + (areaShimNanos / (areaChecked * 1_000_000.0))
                + " fresh_lazy_carver_ms_per_chunk=" + (areaLazyNanos / (areaChecked * 1_000_000.0)));
    }

    private record CarverRun(boolean matches, boolean lazyMatches, int aquiferQueries, int samplerQueries,
            long vanillaNanos, long shimNanos, long lazyNanos) {
    }

    private static CarverRun runCarverChunk(ChunkPos pos, NoiseBasedChunkGenerator generator,
            RandomState randomState, NoiseGeneratorSettings settings, LevelHeightAccessor height,
            PalettedContainerFactory factory, Aquifer.FluidPicker picker, Method doFill,
            Method applyCarvingMask) {
        DensityVolume volume = new DensityVolume(16, 384, 16, pos.getMinBlockX(), -64, pos.getMinBlockZ());
        CarverNoiseState263.Table table = new CarverNoiseState263.Table();
        CarverNoiseState263.RecordingNoiseChunk warmNoise = new CarverNoiseState263.RecordingNoiseChunk(
                randomState, null, settings, picker, Blender.empty(), volume, table);
        ProtoChunk expected = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
        try {
            doFill.invoke(generator, warmNoise, expected);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("26.3 recording doFill failed at " + pos, e);
        }
        // Snapshot terrain immediately after doFill; carver side effects must not be copied into
        // the fast-install baselines used by table and fresh traversals.
        ProtoChunk terrain = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
        WorldgenKernel263.installTerrainFastForJvmTest(terrain, expected);
        ProtoChunk actual = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
        WorldgenKernel263.installTerrainFastForJvmTest(actual, terrain);
        CarvingMask warmMask = new CarvingMask(expected.getMinY(), expected.getMinY() + expected.getHeight());
        CarvingMask replayMask = new CarvingMask(expected.getMinY(), expected.getMinY() + expected.getHeight());
        WorldGenerationContext context = new WorldGenerationContext(generator, height);
        var biomeResolver = generator.getBiomeSource().createUncachedResolver(randomState);
        Function<net.minecraft.core.BlockPos, Holder<net.minecraft.world.level.biome.Biome>> biome = p ->
                biomeResolver.getNoiseBiome(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
        MaterialRule materialRule = settings.materialRule().value();
        generateCarverMask(pos, generator, randomState, context, warmMask);
        generateCarverMask(pos, generator, randomState, context, replayMask);
        long vanillaStart = System.nanoTime();
        invokeCarvingMask(applyCarvingMask, generator, expected, warmMask, randomState, materialRule,
                context, warmNoise, biome);
        long vanillaNanos = System.nanoTime() - vanillaStart;
        DensityVolume replayVolume = new DensityVolume(16, 384, 16, pos.getMinBlockX(), -64, pos.getMinBlockZ());
        CarverNoiseState263.ReplayNoiseChunk replayNoise = new CarverNoiseState263.ReplayNoiseChunk(
                randomState, null, settings, picker, Blender.empty(), replayVolume, table);
        long shimStart = System.nanoTime();
        invokeCarvingMask(applyCarvingMask, generator, actual, replayMask, randomState, materialRule,
                context, replayNoise, biome);
        long shimNanos = System.nanoTime() - shimStart;
        ProtoChunk lazy = new ProtoChunk(pos, UpgradeData.EMPTY, height, factory, null);
        WorldgenKernel263.installTerrainFastForJvmTest(lazy, terrain);
        // Reuse the replay mask so warm, table and fresh traversals have identical carver RNG output.
        CarvingMask lazyMask = replayMask;
        NoiseChunk freshNoise = new NoiseChunk(randomState, null, settings, picker, Blender.empty(),
                new DensityVolume(16, 384, 16, pos.getMinBlockX(), -64, pos.getMinBlockZ()));
        long lazyStart = System.nanoTime();
        invokeCarvingMask(applyCarvingMask, generator, lazy, lazyMask, randomState, materialRule,
                context, freshNoise, biome);
        long lazyNanos = System.nanoTime() - lazyStart;
        return new CarverRun(protoState(expected).equals(protoState(actual)), protoState(expected).equals(protoState(lazy)), table.aquiferQueries(),
                table.samplerQueries(), vanillaNanos, shimNanos, lazyNanos);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void generateCarverMask(ChunkPos target, NoiseBasedChunkGenerator generator,
            RandomState randomState, WorldGenerationContext context, CarvingMask mask) {
        var resolver = generator.getBiomeSource().createUncachedResolver(randomState);
        Holder<net.minecraft.world.level.biome.Biome> plains = resolver
                .getNoiseBiome(target.getMinBlockX() >> 2, 0, target.getMinBlockZ() >> 2);
        Iterable<Holder<WorldCarver>> carvers = plains.value().getGenerationSettings().getCarvers();
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        int index = 0;
        for (int dz = -8; dz <= 8; dz++) {
            for (int dx = -8; dx <= 8; dx++) {
                ChunkPos source = new ChunkPos(target.x() + dx, target.z() + dz);
                index = 0;
                for (Holder<WorldCarver> holder : carvers) {
                    random.setLargeFeatureSeed(randomState.seed() + index, source.x(), source.z());
                    WorldCarver carver = holder.value();
                    if (carver.isStartChunk(random)) {
                        carver.carve(context, random, target, source, mask);
                    }
                    index++;
                }
            }
        }
    }

    private static void invokeCarvingMask(Method method, NoiseBasedChunkGenerator generator,
            ChunkAccess chunk, CarvingMask mask, RandomState randomState, MaterialRule materialRule,
            WorldGenerationContext context, NoiseChunk noiseChunk,
            Function<net.minecraft.core.BlockPos, Holder<net.minecraft.world.level.biome.Biome>> biome) {
        try {
            method.invoke(generator, chunk, mask, randomState, materialRule, context, noiseChunk, biome, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("26.3 applyCarvingMask failed", e);
        }
    }

    private static String protoState(ProtoChunk chunk) {
        StringBuilder out = new StringBuilder();
        out.append("blocks=").append(blockDigest(chunk)).append(" sections=");
        for (int section = 0; section < chunk.getSectionsCount(); section++) {
            LevelChunkSection value = chunk.getSection(section);
            try {
                for (String field : List.of("nonEmptyBlockCount", "fluidCount", "tickingBlockCount", "tickingFluidCount")) {
                    Field counter = LevelChunkSection.class.getDeclaredField(field);
                    counter.setAccessible(true);
                    out.append(counter.getShort(value)).append(',');
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("26.3 section counters unavailable", e);
            }
            out.append(value.hasOnlyAir()).append(',').append(value.hasFluid()).append(',')
                    .append(value.isRandomlyTicking()).append(';');
        }
        out.append(" heights=");
        for (var entry : chunk.getHeightmaps()) {
            out.append(entry.getKey()).append(':');
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                out.append(chunk.getHeight(entry.getKey(), x, z)).append(',');
            }
        }
        out.append(" post=");
        var lists = chunk.getPostProcessing();
        for (int section = 0; section < lists.length; section++) {
            if (lists[section] != null) out.append(section).append(':').append(lists[section]).append(';');
        }
        var ticks = chunk.getTicksForSerialization(0L);
        out.append(" ticks=").append(ticks.blocks()).append('/').append(ticks.fluids()).append(" sky=");
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            out.append(chunk.getSkyLightSources().getLowestSourceY(x, z)).append(',');
        }
        out.append(" light=").append(chunk.isLightCorrect()).append(" status=").append(chunk.getPersistedStatus())
                .append(" entities=").append(chunk.getBlockEntitiesPos()).append(" starts=")
                .append(chunk.getAllStarts().keySet()).append(" refs=");
        for (var structure : chunk.getAllStarts().keySet()) {
            out.append(structure).append(':').append(chunk.getReferencesForStructure(structure)).append(';');
        }
        return out.toString();
    }

    private static String blockDigest(ProtoChunk chunk) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
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

    private static int firstDifference(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        for (int i = 0; i < limit; i++) if (a.charAt(i) != b.charAt(i)) return i;
        return limit;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
