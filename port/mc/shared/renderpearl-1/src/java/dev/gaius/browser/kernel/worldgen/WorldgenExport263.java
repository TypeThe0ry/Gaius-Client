package dev.gaius.browser.kernel.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.Interval;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.DistanceToPointFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.EndIslandFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.GradientFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.ShiftNoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.SimpleDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BlendDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.ClampFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.FindTopSurfaceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.IntervalSelectFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.LerpFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.PowFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RoundFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.SliceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.SplineFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.UnaryFunction;
import net.minecraft.world.level.levelgen.material.condition.AbovePreliminarySurfaceCondition;
import net.minecraft.world.level.levelgen.material.condition.BiomeCondition;
import net.minecraft.world.level.levelgen.material.condition.HoleCondition;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.condition.NoiseThresholdCondition;
import net.minecraft.world.level.levelgen.material.condition.NotCondition;
import net.minecraft.world.level.levelgen.material.condition.SteepCondition;
import net.minecraft.world.level.levelgen.material.condition.StoneDepthCondition;
import net.minecraft.world.level.levelgen.material.condition.TemperatureCondition;
import net.minecraft.world.level.levelgen.material.condition.VerticalGradientCondition;
import net.minecraft.world.level.levelgen.material.condition.WaterCondition;
import net.minecraft.world.level.levelgen.material.condition.YCondition;
import net.minecraft.world.level.levelgen.material.rule.BandlandsRule;
import net.minecraft.world.level.levelgen.material.rule.BlockRule;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Exports a 26.3 {@link NoiseBasedChunkGenerator} as the worldgen kernel's generator IR.
 *
 * <p>The density functions are exported the way {@code DensityFunctionCompiler} prepares them for
 * {@code compileSampler}: registry references inlined, every {@code cache} marker replaced by one
 * deduplicated cache per distinct input, and {@link DfRewriteRule#SLICE_UNIFORM_AXES} applied, so
 * the kernel compiles exactly the tree vanilla compiles. Anything the kernel does not model throws
 * {@link UnsupportedOperationException}; the caller then keeps the dimension on the Java path.
 */
public final class WorldgenExport263 {
    private final NoiseBasedChunkGenerator generator;
    private final NoiseGeneratorSettings settings;
    private final RandomState randomState;
    private final HolderLookup.Provider registries;
    private final LevelHeightAccessor height;
    private final ToIntFunction<Biome> biomeIds;

    private final Map<BlockState, Integer> states = new LinkedHashMap<>();
    private final Map<ResourceKey<NormalNoise>, Integer> noiseIndex = new LinkedHashMap<>();
    private final List<Holder<NormalNoise>> noiseHolders = new ArrayList<>();
    private final Map<Holder<Biome>, Integer> biomes = new LinkedHashMap<>();

    private final WorldgenIr.Nodes nodes = new WorldgenIr.Nodes();
    private final WorldgenIr.Splines splines = new WorldgenIr.Splines();
    private final Map<DensityFunction, Integer> nodeIds = new HashMap<>();
    private final Map<ExportCache, Integer> cacheIds = new IdentityHashMap<>();
    private final Map<Object, Integer> splineIds = new IdentityHashMap<>();
    private final Map<DensityFunction, ExportCache> preparedCaches = new HashMap<>();
    private final DfRewriteRule optimizer;

    private WorldgenExport263(
            NoiseBasedChunkGenerator generator,
            RandomState randomState,
            HolderLookup.Provider registries,
            ToIntFunction<Biome> biomeIds,
            LevelHeightAccessor height) {
        this.generator = generator;
        this.settings = generator.generatorSettings().value();
        this.randomState = randomState;
        this.registries = registries;
        this.biomeIds = biomeIds;
        this.height = height;
        DfRewriteRule caches = new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction function) {
                function = DfRewriteRule.INLINE_REFERENCE.rewrite(function);
                return function instanceof CacheFunction cache ? prepare(cache) : function.rewriteChildren(this);
            }
        };
        this.optimizer = DfRewriteRule.sequence(caches, DfRewriteRule.SLICE_UNIFORM_AXES);
    }

    /**
     * The generator IR of one dimension. {@code biomeIds} maps a biome to its id in the server's
     * biome registry (the ids chunk biome containers use).
     */
    public static byte[] export(
            NoiseBasedChunkGenerator generator,
            RandomState randomState,
            HolderLookup.Provider registries,
            ToIntFunction<Biome> biomeIds,
            LevelHeightAccessor height) {
        return new WorldgenExport263(generator, randomState, registries, biomeIds, height).build();
    }

    /** {@code DensityFunctionCompiler.reuseOrPrepareCache}: one cache per distinct input. */
    private DensityFunction prepare(CacheFunction cache) {
        ExportCache prepared = preparedCaches.get(cache.input());
        if (prepared == null) {
            DensityFunction input = optimizer.rewrite(cache.input());
            prepared = new ExportCache(input, input.range(), input.domainAxes());
            preparedCaches.put(cache.input(), prepared);
        }
        return prepared;
    }

    /** Stand-in for the compiler's private {@code PreparedCache}. */
    private static final class ExportCache implements DensityFunction {
        final DensityFunction input;
        final Interval range;
        final int axes;

        ExportCache(DensityFunction input, Interval range, int axes) {
            this.input = input;
            this.range = range;
            this.axes = axes;
        }

        @Override
        public DensitySampler compileSampler(DensityFunction.CompileContext context) {
            throw new UnsupportedOperationException("export-only cache");
        }

        @Override
        public DensityFunction rewriteChildren(DfRewriteRule rule) {
            return this;
        }

        @Override
        public Interval range() {
            return range;
        }

        @Override
        public int domainAxes() {
            return axes;
        }

        @Override
        public com.mojang.serialization.MapCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("export-only cache");
        }
    }

    private byte[] build() {
        NoiseSettings noise = settings.noiseSettings().clampToHeightAccessor(height);
        long seed = randomState.seed();
        boolean legacy = settings.useLegacyRandomSource();
        BlockState air = Blocks.AIR.defaultBlockState();
        state(air);
        int defaultBlock = state(settings.defaultBlock());
        int defaultFluid = state(settings.defaultFluid());
        int water = state(Blocks.WATER.defaultBlockState());
        int lava = state(Blocks.LAVA.defaultBlockState());

        // Roots, compiled in the order NoiseChunk/RandomState first ask for them.
        NoiseRouter router = settings.noiseRouter();
        Map<Integer, Integer> roots = new LinkedHashMap<>();
        Optional<Aquifer.Config> aquifers = settings.aquifers();
        if (aquifers.isPresent()) {
            Aquifer.Config c = aquifers.get();
            roots.put(WorldgenIr.ROLE_AQUIFER_BARRIER, root(c.barrierNoise()));
            roots.put(WorldgenIr.ROLE_AQUIFER_FLOODEDNESS, root(c.fluidLevelFloodednessNoise()));
            roots.put(WorldgenIr.ROLE_AQUIFER_SPREAD, root(c.fluidLevelSpreadNoise()));
            roots.put(WorldgenIr.ROLE_AQUIFER_LAVA, root(c.lavaNoise()));
            roots.put(WorldgenIr.ROLE_AQUIFER_EXCLUSION, root(c.exclusion()));
            roots.put(WorldgenIr.ROLE_AQUIFER_SURFACE_LEVEL, root(c.surfaceLevel()));
        }
        roots.put(WorldgenIr.ROLE_FINAL_DENSITY, root(router.finalDensity()));
        roots.put(WorldgenIr.ROLE_TEMPERATURE, root(router.temperature()));
        roots.put(WorldgenIr.ROLE_VEGETATION, root(router.vegetation()));
        roots.put(WorldgenIr.ROLE_CONTINENTS, root(router.continents()));
        roots.put(WorldgenIr.ROLE_EROSION, root(router.erosion()));
        roots.put(WorldgenIr.ROLE_DEPTH, root(router.depth()));
        roots.put(WorldgenIr.ROLE_RIDGES, root(router.ridges()));
        roots.put(WorldgenIr.ROLE_PRELIMINARY_SURFACE, root(router.chunkSurfaceLevel()));

        // Surface rules (they add their ore vein roots and noises).
        WorldgenIr.Buf surface = surface(settings.materialRule().value());

        WorldgenIr ir = new WorldgenIr(WorldgenIr.PROFILE_26_3);
        WorldgenIr.Buf s = ir.section(WorldgenIr.SETTINGS);
        s.i64(seed).bool(legacy).bool(aquifers.isPresent()).bool(false).bool(legacy);
        s.i32(noise.minY()).i32(noise.height()).i32(4).i32(8).i32(settings.seaLevel());
        s.u32(defaultBlock).u32(defaultFluid);
        s.i64(BiomeManager.obfuscateSeed(seed));
        s.i32(height.getMinY()).i32(height.getHeight());

        WorldgenIr.Buf biomeSection = biomes();
        int[] special = specials(air, water, lava);
        writeStates(ir.section(WorldgenIr.STATES), special);
        writeNoises(ir.section(WorldgenIr.NOISES));
        WorldgenIr.Buf density = ir.section(WorldgenIr.DENSITY);
        nodes.writeTo(density);
        splines.writeTo(density);
        WorldgenIr.Buf rootSection = ir.section(WorldgenIr.ROOTS);
        rootSection.u32(roots.size());
        for (Map.Entry<Integer, Integer> e : roots.entrySet()) {
            rootSection.u16(e.getKey()).u16(0).u32(e.getValue());
        }
        int seaLevel = settings.seaLevel();
        ir.section(WorldgenIr.FLUID).i32(Math.min(-54, seaLevel)).i32(-54).u32(lava).i32(seaLevel).u32(defaultFluid);
        ir.section(WorldgenIr.SURFACE).bytes(surface);
        ir.section(WorldgenIr.BIOMES).bytes(biomeSection);
        return ir.finish();
    }

    // ---- block states ----

    private int state(BlockState state) {
        Integer existing = states.get(state);
        if (existing != null) {
            return existing;
        }
        int index = states.size();
        states.put(state, index);
        return index;
    }

    private int[] specials(BlockState air, int water, int lava) {
        int[] special = new int[WorldgenIr.SPECIAL_COUNT];
        special[WorldgenIr.SPECIAL_AIR] = state(air);
        special[WorldgenIr.SPECIAL_WATER] = water;
        special[WorldgenIr.SPECIAL_LAVA] = lava;
        special[WorldgenIr.SPECIAL_TERRACOTTA] = state(Blocks.TERRACOTTA.defaultBlockState());
        special[WorldgenIr.SPECIAL_WHITE_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.white().defaultBlockState());
        special[WorldgenIr.SPECIAL_ORANGE_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.orange().defaultBlockState());
        special[WorldgenIr.SPECIAL_YELLOW_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.yellow().defaultBlockState());
        special[WorldgenIr.SPECIAL_BROWN_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.brown().defaultBlockState());
        special[WorldgenIr.SPECIAL_RED_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.red().defaultBlockState());
        special[WorldgenIr.SPECIAL_LIGHT_GRAY_TERRACOTTA] =
                state(Blocks.DYED_TERRACOTTA.lightGray().defaultBlockState());
        special[WorldgenIr.SPECIAL_PACKED_ICE] = state(Blocks.PACKED_ICE.defaultBlockState());
        special[WorldgenIr.SPECIAL_SNOW_BLOCK] = state(Blocks.SNOW_BLOCK.defaultBlockState());
        return special;
    }

    private void writeStates(WorldgenIr.Buf out, int[] special) {
        Block defaultBlock = settings.defaultBlock().getBlock();
        out.u32(states.size());
        for (BlockState state : states.keySet()) {
            int flags = 0;
            if (state.isAir()) {
                flags |= WorldgenIr.STATE_AIR;
            }
            if (!state.getFluidState().isEmpty()) {
                flags |= WorldgenIr.STATE_FLUID;
            }
            if (Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state)) {
                flags |= WorldgenIr.STATE_OCEAN_FLOOR_OPAQUE;
            }
            if (state.is(Blocks.WATER)) {
                flags |= WorldgenIr.STATE_WATER;
            }
            if (state.is(Blocks.LAVA)) {
                flags |= WorldgenIr.STATE_LAVA;
            }
            if (state.is(defaultBlock)) {
                flags |= WorldgenIr.STATE_DEFAULT_BLOCK;
            }
            out.u32(Block.BLOCK_STATE_REGISTRY.getId(state)).u32(flags);
        }
        out.u32(special.length);
        for (int s : special) {
            out.u32(s);
        }
    }

    // ---- noises ----

    private int noise(Holder<NormalNoise> holder) {
        ResourceKey<NormalNoise> key = holder.unwrapKey().orElseThrow(
                () -> new UnsupportedOperationException("inline (unregistered) noise parameters"));
        Integer existing = noiseIndex.get(key);
        if (existing != null) {
            return existing;
        }
        int index = noiseHolders.size();
        noiseIndex.put(key, index);
        noiseHolders.add(holder);
        return index;
    }

    private int noise(ResourceKey<NormalNoise> key) {
        return noise(registries.lookupOrThrow(Registries.NOISE).getOrThrow(key));
    }

    private void writeNoises(WorldgenIr.Buf out) {
        out.u32(noiseHolders.size());
        for (Holder<NormalNoise> holder : noiseHolders) {
            ResourceKey<NormalNoise> key = holder.unwrapKey().orElseThrow();
            long legacyOffset = holder.is(Noises.TEMPERATURE_NETHER) ? 0L : 1L;
            boolean legacy = holder.is(Noises.TEMPERATURE_NETHER) || holder.is(Noises.VEGETATION_NETHER);
            JsonObject json = NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, holder.value())
                    .getOrThrow().getAsJsonObject();
            out.u8(1).u8(legacy ? 1 : 0).u16(0).i64(legacy ? legacyOffset : 0L);
            out.str(key.identifier().toString());
            out.f64(json.has("base_amplitude") ? json.get("base_amplitude").getAsDouble() : 1.0);
            out.i32(json.get("base_octave").getAsInt());
            out.i32(json.has("octave_count") ? json.get("octave_count").getAsInt() : 1);
            int normalize = 1;
            if (json.has("normalize")) {
                JsonElement n = json.get("normalize");
                normalize = n.getAsJsonPrimitive().isBoolean() ? (n.getAsBoolean() ? 1 : 0) : 2;
            }
            out.u8(normalize);
            JsonArray modifiers = json.has("amplitude_modifiers") ? json.getAsJsonArray("amplitude_modifiers") : new JsonArray();
            out.u32(modifiers.size());
            for (JsonElement m : modifiers) {
                out.f64(m.getAsDouble());
            }
        }
    }

    // ---- density functions ----

    private int root(DensityFunction function) {
        return node(optimizer.rewrite(function));
    }

    private static double min(DensityFunction f) {
        Interval r = f.range();
        return r.min();
    }

    private static double max(DensityFunction f) {
        Interval r = f.range();
        return r.max();
    }

    private int begin(DensityFunction f, int op, int arg) {
        return nodes.begin(op, arg, f.domainAxes(), min(f), max(f));
    }

    private int node(DensityFunction f) {
        if (f instanceof DensityFunctions.HolderHolder holder) {
            return node(holder.function().value());
        }
        if (f instanceof ExportCache cache) {
            Integer existing = cacheIds.get(cache);
            if (existing != null) {
                return existing;
            }
            int input = node(cache.input);
            int id = nodes.begin(WorldgenIr.OP_CACHE, 0, cache.axes, cache.range.min(), cache.range.max());
            nodes.payload().u32(input);
            cacheIds.put(cache, id);
            return id;
        }
        Integer existing = nodeIds.get(f);
        if (existing != null) {
            return existing;
        }
        int id = emit(f);
        nodeIds.put(f, id);
        return id;
    }

    private int shift(DensityFunction f) {
        return node(f);
    }

    private int emit(DensityFunction f) {
        WorldgenIr.Buf p = nodes.payload();
        if (f instanceof ConstantFunction c) {
            int id = begin(f, WorldgenIr.OP_CONST, 0);
            p.f64(c.value());
            return id;
        }
        if (f instanceof NoiseFunction n) {
            int noise = noise(n.noise());
            DensityFunction zero = DensityFunctions.zero();
            boolean unshifted = n.shiftX().equals(zero) && n.shiftY().equals(zero) && n.shiftZ().equals(zero);
            int sx = unshifted ? WorldgenIr.NONE : shift(n.shiftX());
            int sy = unshifted ? WorldgenIr.NONE : shift(n.shiftY());
            int sz = unshifted ? WorldgenIr.NONE : shift(n.shiftZ());
            int id = begin(f, WorldgenIr.OP_NOISE, unshifted ? 0 : 1);
            p.u32(noise).f64(n.xzScale()).f64(n.yScale()).u32(sx).u32(sy).u32(sz);
            return id;
        }
        if (f instanceof ShiftNoiseFunction.ShiftA s) {
            int noise = noise(s.offsetNoise());
            int id = begin(f, WorldgenIr.OP_SHIFT_A, 0);
            p.u32(noise);
            return id;
        }
        if (f instanceof ShiftNoiseFunction.ShiftB s) {
            int noise = noise(s.offsetNoise());
            int id = begin(f, WorldgenIr.OP_SHIFT_B, 0);
            p.u32(noise);
            return id;
        }
        if (f instanceof ShiftNoiseFunction.Shift s) {
            int noise = noise(s.offsetNoise());
            int id = begin(f, WorldgenIr.OP_SHIFT, 0);
            p.u32(noise);
            return id;
        }
        if (f instanceof BlendedNoise b) {
            int id = begin(f, WorldgenIr.OP_OLD_BLENDED_NOISE, 0);
            p.f64(b.xzScale()).f64(b.yScale()).f64(b.xzFactor()).f64(b.yFactor()).f64(b.smearScaleMultiplier());
            return id;
        }
        if (f instanceof EndIslandFunction) {
            return begin(f, WorldgenIr.OP_END_ISLANDS, 0);
        }
        if (f instanceof GradientFunction g) {
            int id = begin(f, WorldgenIr.OP_GRADIENT, 0);
            p.u8(g.axis().ordinal()).u8(g.tiling().ordinal());
            p.i32(g.fromCoordinate()).i32(g.toCoordinate()).f64(g.fromValue()).f64(g.toValue());
            return id;
        }
        if (f instanceof DistanceToPointFunction d) {
            int id = begin(f, WorldgenIr.OP_DISTANCE_TO_POINT, 0);
            p.i32(d.point().getX()).i32(d.point().getY()).i32(d.point().getZ()).u8(d.metric().ordinal());
            return id;
        }
        if (f instanceof SimpleDensityFunction simple) {
            int op = switch (simple) {
                case BLEND_ALPHA -> WorldgenIr.OP_BLEND_ALPHA;
                case BLEND_OFFSET -> WorldgenIr.OP_BLEND_OFFSET;
                case BEARDIFIER -> WorldgenIr.OP_BEARDIFIER;
            };
            return begin(f, op, 0);
        }
        if (f instanceof UnaryFunction u) {
            int input = node(u.input());
            int type = switch (u.type()) {
                case ABS -> WorldgenIr.UNARY_ABS;
                case SQUARE -> WorldgenIr.UNARY_SQUARE;
                case CUBE -> WorldgenIr.UNARY_CUBE;
                case SQRT -> WorldgenIr.UNARY_SQRT;
                case HALF_NEGATIVE -> WorldgenIr.UNARY_HALF_NEGATIVE;
                case QUARTER_NEGATIVE -> WorldgenIr.UNARY_QUARTER_NEGATIVE;
                case RECIPROCAL -> WorldgenIr.UNARY_RECIPROCAL;
                case NEGATE -> WorldgenIr.UNARY_NEGATE;
                case SQUEEZE -> WorldgenIr.UNARY_SQUEEZE;
                case LOG -> WorldgenIr.UNARY_LOG;
                case SIGN -> WorldgenIr.UNARY_SIGN;
            };
            int id = begin(f, WorldgenIr.OP_UNARY, type);
            p.u32(input);
            return id;
        }
        if (f instanceof BinaryFunction b) {
            int left = node(b.left());
            int right = node(b.right());
            int type = switch (b.type()) {
                case ADD -> WorldgenIr.BINARY_ADD;
                case SUB -> WorldgenIr.BINARY_SUB;
                case MUL -> WorldgenIr.BINARY_MUL;
                case DIV -> WorldgenIr.BINARY_DIV;
                case MIN -> WorldgenIr.BINARY_MIN;
                case MAX -> WorldgenIr.BINARY_MAX;
            };
            int id = begin(f, WorldgenIr.OP_BINARY, type);
            p.u32(left).u32(right);
            return id;
        }
        if (f instanceof PowFunction pow) {
            int base = node(pow.base());
            int exponent = node(pow.exponent());
            int id = begin(f, WorldgenIr.OP_POW, 0);
            p.u32(base).u32(exponent);
            return id;
        }
        if (f instanceof ClampFunction c) {
            int input = node(c.input());
            int id = begin(f, WorldgenIr.OP_CLAMP, 0);
            p.u32(input).f64(c.min()).f64(c.max());
            return id;
        }
        if (f instanceof LerpFunction l) {
            int alpha = node(l.alpha());
            int first = node(l.first());
            int second = node(l.second());
            int id = begin(f, WorldgenIr.OP_LERP, 0);
            p.u32(alpha).u32(first).u32(second);
            return id;
        }
        if (f instanceof RangeChoiceFunction r) {
            int input = node(r.input());
            int in = node(r.whenInRange());
            int out = node(r.whenOutOfRange());
            int id = begin(f, WorldgenIr.OP_RANGE_CHOICE, 0);
            p.u32(input).f64(r.minInclusive()).f64(r.maxExclusive()).u32(in).u32(out);
            return id;
        }
        if (f instanceof IntervalSelectFunction s) {
            int input = node(s.input());
            int[] functions = new int[s.functions().size()];
            for (int i = 0; i < functions.length; i++) {
                functions[i] = node(s.functions().get(i));
            }
            int id = begin(f, WorldgenIr.OP_INTERVAL_SELECT, 0);
            p.u32(input).u32(s.thresholds().size());
            for (int i = 0; i < s.thresholds().size(); i++) {
                p.f64(s.thresholds().getFloat(i));
            }
            for (int fn : functions) {
                p.u32(fn);
            }
            return id;
        }
        if (f instanceof RoundFunction r) {
            int input = node(r.input());
            int multiple = node(r.multiple());
            int type = switch (r.type()) {
                case FLOOR -> 0;
                case ROUND -> 1;
                case CEIL -> 2;
                case TRUNCATE -> 3;
            };
            int id = begin(f, WorldgenIr.OP_ROUND, type);
            p.u32(input).u32(multiple);
            return id;
        }
        if (f instanceof SliceFunction s) {
            int input = node(s.input());
            int id = begin(f, WorldgenIr.OP_SLICE, s.axis().ordinal());
            p.i32(s.coordinate()).u32(input);
            return id;
        }
        if (f instanceof FindTopSurfaceFunction t) {
            int density = node(t.density());
            int upper = node(t.upperBound());
            int id = begin(f, WorldgenIr.OP_FIND_TOP_SURFACE, 0);
            p.u32(density).u32(upper).i32(t.lowerBound()).i32(t.cellHeight());
            return id;
        }
        if (f instanceof SplineFunction s) {
            int spline = spline(s.spline());
            int id = begin(f, WorldgenIr.OP_SPLINE, 0);
            p.u32(spline);
            return id;
        }
        if (f instanceof InterpolatedFunction i) {
            int input = node(i.input());
            int id = begin(f, WorldgenIr.OP_INTERPOLATED, 0);
            p.u32(input).i32(i.cellSizeXz()).i32(i.cellSizeY());
            return id;
        }
        if (f instanceof BlendDensityFunction b) {
            int input = node(b.input());
            int id = begin(f, WorldgenIr.OP_BLEND_DENSITY, 0);
            p.u32(input);
            return id;
        }
        if (f instanceof CacheFunction) {
            throw new IllegalStateException("cache marker left after the compiler rewrite");
        }
        throw new UnsupportedOperationException("density function " + f.getClass().getName());
    }

    private int spline(CubicSpline<SplineFunction.Coordinate> spline) {
        Integer existing = splineIds.get(spline);
        if (existing != null) {
            return existing;
        }
        int id;
        if (spline instanceof CubicSpline.Constant<SplineFunction.Coordinate> c) {
            id = splines.constant(c.value());
        } else if (spline instanceof CubicSpline.Multipoint<SplineFunction.Coordinate> m) {
            int coordinate = node(m.coordinate().function());
            int[] values = new int[m.values().size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = spline(m.values().get(i));
            }
            id = splines.multipoint(coordinate, m.locations(), m.derivatives(), values);
        } else {
            throw new UnsupportedOperationException("spline " + spline.getClass().getName());
        }
        splineIds.put(spline, id);
        return id;
    }

    // ---- material (surface) rules ----

    private final List<WorldgenIr.Buf> conditions = new ArrayList<>();
    private final List<WorldgenIr.Buf> rules = new ArrayList<>();
    private final Map<Object, Integer> conditionIds = new IdentityHashMap<>();
    private final Map<Object, Integer> ruleIds = new IdentityHashMap<>();
    private WorldGenerationContext anchors;

    private WorldgenIr.Buf surface(MaterialRule rootRule) {
        anchors = new WorldGenerationContext(generator, height);
        int[] noiseSlots = new int[WorldgenIr.SURFACE_NOISE_COUNT];
        noiseSlots[WorldgenIr.SURFACE_NOISE_SURFACE] = noise(Noises.SURFACE);
        noiseSlots[WorldgenIr.SURFACE_NOISE_SECONDARY] = noise(Noises.SURFACE_SECONDARY);
        noiseSlots[WorldgenIr.SURFACE_NOISE_CLAY_BANDS_OFFSET] = noise(Noises.CLAY_BANDS_OFFSET);
        noiseSlots[WorldgenIr.SURFACE_NOISE_BADLANDS_PILLAR] = noise(Noises.BADLANDS_PILLAR);
        noiseSlots[WorldgenIr.SURFACE_NOISE_BADLANDS_PILLAR_ROOF] = noise(Noises.BADLANDS_PILLAR_ROOF);
        noiseSlots[WorldgenIr.SURFACE_NOISE_BADLANDS_SURFACE] = noise(Noises.BADLANDS_SURFACE);
        noiseSlots[WorldgenIr.SURFACE_NOISE_ICEBERG_PILLAR] = noise(Noises.ICEBERG_PILLAR);
        noiseSlots[WorldgenIr.SURFACE_NOISE_ICEBERG_PILLAR_ROOF] = noise(Noises.ICEBERG_PILLAR_ROOF);
        noiseSlots[WorldgenIr.SURFACE_NOISE_ICEBERG_SURFACE] = noise(Noises.ICEBERG_SURFACE);
        int rootIndex = rule(rootRule);
        WorldgenIr.Buf out = new WorldgenIr.Buf();
        for (int n : noiseSlots) {
            out.u32(n);
        }
        out.u32(conditions.size());
        for (WorldgenIr.Buf c : conditions) {
            out.bytes(c);
        }
        out.u32(rules.size());
        for (WorldgenIr.Buf r : rules) {
            out.bytes(r);
        }
        out.u32(rootIndex);
        return out;
    }

    private int rule(MaterialRule r) {
        if (r instanceof MaterialRule.HolderHolder holder) {
            return rule(holder.holder().value());
        }
        Integer existing = ruleIds.get(r);
        if (existing != null) {
            return existing;
        }
        WorldgenIr.Buf b = new WorldgenIr.Buf();
        if (r instanceof BlockRule block) {
            b.u8(WorldgenIr.RULE_BLOCK).u32(state(block.resultState()));
        } else if (r instanceof SequenceRule sequence) {
            int[] children = new int[sequence.sequence().size()];
            for (int i = 0; i < children.length; i++) {
                children[i] = rule(sequence.sequence().get(i));
            }
            b.u8(WorldgenIr.RULE_SEQUENCE).u32(children.length);
            for (int c : children) {
                b.u32(c);
            }
        } else if (r instanceof ConditionRule condition) {
            int c = condition(condition.ifTrue());
            int then = rule(condition.thenRun());
            b.u8(WorldgenIr.RULE_CONDITION).u32(c).u32(then);
        } else if (r instanceof BandlandsRule) {
            b.u8(WorldgenIr.RULE_BANDLANDS);
        } else if (r instanceof OreVeinRule ore) {
            int density = root(ore.density());
            int richness = root(ore.richness());
            int gap = root(ore.fillerGap());
            b.u8(WorldgenIr.RULE_ORE_VEIN).u32(state(ore.oreBlock())).u32(state(ore.rawOreBlock()))
                    .u32(state(ore.fillerBlock())).f32(ore.rawOreChance()).u32(density).u32(richness).u32(gap);
        } else {
            throw new UnsupportedOperationException("material rule " + r.getClass().getName());
        }
        int id = rules.size();
        rules.add(b);
        ruleIds.put(r, id);
        return id;
    }

    private int condition(MaterialCondition c) {
        if (c instanceof MaterialCondition.HolderHolder holder) {
            return condition(holder.holder().value());
        }
        Integer existing = conditionIds.get(c);
        if (existing != null) {
            return existing;
        }
        WorldgenIr.Buf b = new WorldgenIr.Buf();
        if (c instanceof BiomeCondition biome) {
            List<Integer> list = new ArrayList<>();
            for (Holder<Biome> holder : biome.biomes()) {
                list.add(biome(holder));
            }
            b.u8(WorldgenIr.COND_BIOME).u32(list.size());
            for (int v : list) {
                b.u32(v);
            }
        } else if (c instanceof NoiseThresholdCondition n) {
            b.u8(WorldgenIr.COND_NOISE_THRESHOLD).u32(noise(n.noise())).f64(n.minThreshold()).f64(n.maxThreshold())
                    .bool(n.is3d());
        } else if (c instanceof VerticalGradientCondition v) {
            b.u8(WorldgenIr.COND_VERTICAL_GRADIENT).str(v.randomName().toString())
                    .i32(v.trueAtAndBelow().resolveY(anchors)).i32(v.falseAtAndAbove().resolveY(anchors));
        } else if (c instanceof YCondition y) {
            b.u8(WorldgenIr.COND_Y_ABOVE).i32(y.anchor().resolveY(anchors)).i32(y.surfaceDepthMultiplier())
                    .bool(y.addStoneDepth());
        } else if (c instanceof WaterCondition w) {
            b.u8(WorldgenIr.COND_WATER).i32(w.offset()).i32(w.surfaceDepthMultiplier()).bool(w.addStoneDepth());
        } else if (c instanceof TemperatureCondition) {
            b.u8(WorldgenIr.COND_TEMPERATURE);
        } else if (c instanceof SteepCondition) {
            b.u8(WorldgenIr.COND_STEEP);
        } else if (c instanceof NotCondition not) {
            int target = condition(not.target());
            b.u8(WorldgenIr.COND_NOT).u32(target);
        } else if (c instanceof HoleCondition) {
            b.u8(WorldgenIr.COND_HOLE);
        } else if (c instanceof AbovePreliminarySurfaceCondition) {
            b.u8(WorldgenIr.COND_ABOVE_PRELIMINARY_SURFACE);
        } else if (c instanceof StoneDepthCondition s) {
            b.u8(WorldgenIr.COND_STONE_DEPTH).i32(s.offset()).bool(s.addSurfaceDepth()).i32(s.secondaryDepthRange())
                    .bool(s.surfaceType() == CaveSurface.CEILING);
        } else {
            throw new UnsupportedOperationException("material condition " + c.getClass().getName());
        }
        int id = conditions.size();
        conditions.add(b);
        conditionIds.put(c, id);
        return id;
    }

    // ---- biomes ----

    private int biome(Holder<Biome> holder) {
        Integer existing = biomes.get(holder);
        if (existing != null) {
            return existing;
        }
        int index = biomes.size();
        biomes.put(holder, index);
        return index;
    }

    private Holder<Biome> biome(ResourceKey<Biome> key) {
        return registries.lookupOrThrow(Registries.BIOME).getOrThrow(key);
    }

    private WorldgenIr.Buf biomes() {
        BiomeSource source = generator.getBiomeSource();
        for (Holder<Biome> holder : source.possibleBiomes()) {
            biome(holder);
        }
        WorldgenIr.Buf tail = new WorldgenIr.Buf();
        int kind;
        if (source instanceof MultiNoiseBiomeSource multiNoise) {
            kind = WorldgenIr.BIOME_SOURCE_MULTI_NOISE;
            Climate.ParameterList<Holder<Biome>> list = presetOf(multiNoise);
            tail.u32(19).u32(list.values().size());
            for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : list.values()) {
                Climate.ParameterPoint p = entry.getFirst();
                for (Climate.Parameter parameter : List.of(
                        p.temperature(), p.humidity(), p.continentalness(), p.erosion(), p.depth(), p.weirdness())) {
                    tail.i64(parameter.min()).i64(parameter.max());
                }
                tail.i64(p.offset()).i64(p.offset());
                tail.u32(biome(entry.getSecond()));
            }
        } else if (source instanceof FixedBiomeSource fixed) {
            kind = WorldgenIr.BIOME_SOURCE_FIXED;
            tail.u32(biome(fixed.getNoiseBiome(0, 0, 0)));
        } else if (source instanceof TheEndBiomeSource) {
            kind = WorldgenIr.BIOME_SOURCE_THE_END;
            tail.u32(biome(biome(Biomes.THE_END))).u32(biome(biome(Biomes.END_HIGHLANDS)))
                    .u32(biome(biome(Biomes.END_MIDLANDS))).u32(biome(biome(Biomes.SMALL_END_ISLANDS)))
                    .u32(biome(biome(Biomes.END_BARRENS)));
        } else {
            throw new UnsupportedOperationException("biome source " + source.getClass().getName());
        }
        WorldgenIr.Buf out = new WorldgenIr.Buf();
        out.u8(kind).u8(0).u8(0).u8(0);
        // The provider's own serialization context: plain RegistryOps over a lookup can reject the
        // holders inside biome values as "not valid in current registry set".
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        out.u32(biomes.size());
        for (Holder<Biome> holder : biomes.keySet()) {
            Biome value = holder.value();
            int flags = 0;
            if (holder.is(Biomes.ERODED_BADLANDS)) {
                flags |= WorldgenIr.BIOME_ERODED_BADLANDS;
            }
            if (holder.is(Biomes.FROZEN_OCEAN)) {
                flags |= WorldgenIr.BIOME_FROZEN_OCEAN;
            }
            if (holder.is(Biomes.DEEP_FROZEN_OCEAN)) {
                flags |= WorldgenIr.BIOME_DEEP_FROZEN_OCEAN;
            }
            out.u32(biomeIds.applyAsInt(value)).f32(value.getBaseTemperature()).u8(frozen(ops, holder) ? 1 : 0)
                    .u8(flags).u16(0);
        }
        out.bytes(tail);
        return out;
    }

    /** The climate parameter list of a preset-backed multi-noise source. */
    private Climate.ParameterList<Holder<Biome>> presetOf(MultiNoiseBiomeSource source) {
        HolderLookup.RegistryLookup<MultiNoiseBiomeSourceParameterList> presets =
                registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        for (Holder.Reference<MultiNoiseBiomeSourceParameterList> preset
                : (Iterable<Holder.Reference<MultiNoiseBiomeSourceParameterList>>) presets.listElements()::iterator) {
            if (source.stable(preset.key())) {
                return preset.value().parameters();
            }
        }
        throw new UnsupportedOperationException("multi-noise biome source without a registered preset");
    }

    /** Whether the biome uses {@code Biome.TemperatureModifier.FROZEN} (read through its codec). */
    private static boolean frozen(RegistryOps<JsonElement> ops, Holder<Biome> holder) {
        try {
            JsonElement json = Biome.NETWORK_CODEC.encodeStart(ops, holder.value()).getOrThrow();
            JsonElement modifier = json.getAsJsonObject().get("temperature_modifier");
            return modifier != null && "frozen".equals(modifier.getAsString());
        } catch (RuntimeException e) {
            // Only the two frozen oceans use the modifier in vanilla data.
            return holder.is(Biomes.FROZEN_OCEAN) || holder.is(Biomes.DEEP_FROZEN_OCEAN);
        }
    }

    /** Biomes the export names (for callers that check a chunk's palette). */
    public static Set<Holder<Biome>> possibleBiomes(NoiseBasedChunkGenerator generator) {
        return generator.getBiomeSource().possibleBiomes();
    }
}
