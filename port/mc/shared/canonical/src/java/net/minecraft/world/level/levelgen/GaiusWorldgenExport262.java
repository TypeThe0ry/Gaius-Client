package net.minecraft.world.level.levelgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import dev.gaius.browser.kernel.worldgen.WorldgenIr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.CubicSpline;
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
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Exports a 26.2 (and 1.21.11-compatible) {@link NoiseBasedChunkGenerator} as the worldgen
 * kernel's generator IR, from the router as {@link RandomState#router()} wires it (noises
 * instantiated, blended noise and end islands seeded).
 *
 * <p>It lives in vanilla's package because most density function classes are package-private or
 * protected nested records of {@link DensityFunctions}; the remaining private ones are recognized
 * by class identity, their children read with {@code mapChildren} and their scalar fields from
 * the density function codec. Anything the kernel does not model throws
 * {@link UnsupportedOperationException}; the caller then keeps the dimension on the Java path.
 */
public final class GaiusWorldgenExport262 {
    private static final Class<?> CONSTANT = DensityFunctions.constant(1.0).getClass();
    private static final Class<?> MUL_OR_ADD =
            DensityFunctions.add(DensityFunctions.constant(1.0), DensityFunctions.yClampedGradient(0, 1, 0.0, 1.0))
                    .getClass();
    private static final Class<?> RANGE_CHOICE = DensityFunctions.rangeChoice(
            DensityFunctions.zero(), 0.0, 1.0, DensityFunctions.zero(), DensityFunctions.zero()).getClass();
    private static final Class<?> INTERVAL_SELECT = DensityFunctions.intervalSelect(
            DensityFunctions.zero(), it.unimi.dsi.fastutil.doubles.DoubleList.of(0.0),
            List.of(DensityFunctions.zero(), DensityFunctions.zero())).getClass();
    private static final Class<?> Y_CLAMPED_GRADIENT = DensityFunctions.yClampedGradient(0, 1, 0.0, 1.0).getClass();
    private static final Class<?> FIND_TOP_SURFACE = DensityFunctions.findTopSurface(
            DensityFunctions.zero(), DensityFunctions.zero(), 0, 8).getClass();

    private final NoiseBasedChunkGenerator generator;
    private final NoiseGeneratorSettings settings;
    private final RandomState randomState;
    private final HolderLookup.Provider registries;
    private final ToIntFunction<Biome> biomeIds;
    private final LevelHeightAccessor height;
    private final long seed;
    private final RegistryOps<JsonElement> ops;

    private final Map<BlockState, Integer> states = new LinkedHashMap<>();
    private final Map<ResourceKey<NormalNoise.NoiseParameters>, Integer> noiseIndex = new LinkedHashMap<>();
    private final List<Holder<NormalNoise.NoiseParameters>> noiseHolders = new ArrayList<>();
    private final Map<Holder<Biome>, Integer> biomes = new LinkedHashMap<>();
    private final WorldgenIr.Nodes nodes = new WorldgenIr.Nodes();
    private final WorldgenIr.Splines splines = new WorldgenIr.Splines();
    private final Map<DensityFunction, Integer> nodeIds = new HashMap<>();
    private final Map<Object, Integer> splineIds = new IdentityHashMap<>();
    private final List<WorldgenIr.Buf> conditions = new ArrayList<>();
    private final List<WorldgenIr.Buf> rules = new ArrayList<>();
    private WorldGenerationContext anchors;

    private GaiusWorldgenExport262(NoiseBasedChunkGenerator generator, RandomState randomState,
            HolderLookup.Provider registries, ToIntFunction<Biome> biomeIds, LevelHeightAccessor height, long seed) {
        this.generator = generator;
        this.settings = generator.generatorSettings().value();
        this.randomState = randomState;
        this.registries = registries;
        this.biomeIds = biomeIds;
        this.height = height;
        this.seed = seed;
        // The provider's own serialization context: its registry owners are the ones the
        // settings' holders were created with (a plain RegistryOps over a freshly built lookup
        // rejects them as "not valid in current registry set").
        this.ops = registries.createSerializationContext(JsonOps.INSTANCE);
    }

    /** BIOMES kernel eligibility shared with the 26.2 worldgen bridge. */
    public static boolean biomeSourceSupportsKernel(BiomeSource source) {
        return !(source instanceof MultiNoiseBiomeSource);
    }

    /**
     * The generator IR of one dimension. 26.2 RandomState does not expose its seed, so the caller
     * passes the world seed it was created with.
     */
    public static byte[] export(NoiseBasedChunkGenerator generator, RandomState randomState,
            HolderLookup.Provider registries, ToIntFunction<Biome> biomeIds, LevelHeightAccessor height, long seed,
            int profile) {
        return new GaiusWorldgenExport262(generator, randomState, registries, biomeIds, height, seed).build(profile);
    }

    private byte[] build(int profile) {
        NoiseSettings noise = settings.noiseSettings().clampToHeightAccessor(height);
        boolean legacy = settings.useLegacyRandomSource();
        state(Blocks.AIR.defaultBlockState());
        int defaultBlock = state(settings.defaultBlock());
        int defaultFluid = state(settings.defaultFluid());
        int water = state(Blocks.WATER.defaultBlockState());
        int lava = state(Blocks.LAVA.defaultBlockState());

        NoiseRouter router = randomState.router();
        Map<Integer, Integer> roots = new LinkedHashMap<>();
        roots.put(WorldgenIr.ROLE_TEMPERATURE, node(router.temperature()));
        roots.put(WorldgenIr.ROLE_VEGETATION, node(router.vegetation()));
        roots.put(WorldgenIr.ROLE_CONTINENTS, node(router.continents()));
        roots.put(WorldgenIr.ROLE_EROSION, node(router.erosion()));
        roots.put(WorldgenIr.ROLE_DEPTH, node(router.depth()));
        roots.put(WorldgenIr.ROLE_RIDGES, node(router.ridges()));
        roots.put(WorldgenIr.ROLE_PRELIMINARY_SURFACE, node(router.preliminarySurfaceLevel()));
        roots.put(WorldgenIr.ROLE_AQUIFER_BARRIER, node(router.barrierNoise()));
        roots.put(WorldgenIr.ROLE_AQUIFER_FLOODEDNESS, node(router.fluidLevelFloodednessNoise()));
        roots.put(WorldgenIr.ROLE_AQUIFER_SPREAD, node(router.fluidLevelSpreadNoise()));
        roots.put(WorldgenIr.ROLE_AQUIFER_LAVA, node(router.lavaNoise()));
        roots.put(WorldgenIr.ROLE_FINAL_DENSITY, node(router.finalDensity()));
        roots.put(WorldgenIr.ROLE_VEIN_TOGGLE, node(router.veinToggle()));
        roots.put(WorldgenIr.ROLE_VEIN_RIDGED, node(router.veinRidged()));
        roots.put(WorldgenIr.ROLE_VEIN_GAP, node(router.veinGap()));

        WorldgenIr.Buf surface = surface();
        int[] veins = {
            state(Blocks.COPPER_ORE.defaultBlockState()), state(Blocks.RAW_COPPER_BLOCK.defaultBlockState()),
            state(Blocks.GRANITE.defaultBlockState()), state(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()),
            state(Blocks.RAW_IRON_BLOCK.defaultBlockState()), state(Blocks.TUFF.defaultBlockState()),
        };
        WorldgenIr.Buf biomeSection = biomes(profile);
        int[] special = specials(water, lava);

        WorldgenIr ir = new WorldgenIr(profile);
        WorldgenIr.Buf s = ir.section(WorldgenIr.SETTINGS);
        s.i64(seed).bool(legacy).bool(settings.isAquifersEnabled()).bool(settings.oreVeinsEnabled()).bool(legacy);
        s.i32(noise.minY()).i32(noise.height()).i32(noise.getCellWidth()).i32(noise.getCellHeight());
        s.i32(settings.seaLevel()).u32(defaultBlock).u32(defaultFluid);
        s.i64(BiomeManager.obfuscateSeed(seed));
        s.i32(height.getMinY()).i32(height.getHeight());
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
        WorldgenIr.Buf veinSection = ir.section(WorldgenIr.VEINS);
        for (int v : veins) {
            veinSection.u32(v);
        }
        veinSection.i32(OreVeinifier.VeinType.COPPER.minY).i32(OreVeinifier.VeinType.COPPER.maxY);
        veinSection.i32(OreVeinifier.VeinType.IRON.minY).i32(OreVeinifier.VeinType.IRON.maxY);
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

    private int[] specials(int water, int lava) {
        int[] special = new int[WorldgenIr.SPECIAL_COUNT];
        special[WorldgenIr.SPECIAL_AIR] = state(Blocks.AIR.defaultBlockState());
        special[WorldgenIr.SPECIAL_WATER] = water;
        special[WorldgenIr.SPECIAL_LAVA] = lava;
        special[WorldgenIr.SPECIAL_TERRACOTTA] = state(Blocks.TERRACOTTA.defaultBlockState());
        special[WorldgenIr.SPECIAL_WHITE_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.white().defaultBlockState());
        special[WorldgenIr.SPECIAL_ORANGE_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.orange().defaultBlockState());
        special[WorldgenIr.SPECIAL_YELLOW_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.yellow().defaultBlockState());
        special[WorldgenIr.SPECIAL_BROWN_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.brown().defaultBlockState());
        special[WorldgenIr.SPECIAL_RED_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.red().defaultBlockState());
        special[WorldgenIr.SPECIAL_LIGHT_GRAY_TERRACOTTA] = state(Blocks.DYED_TERRACOTTA.lightGray().defaultBlockState());
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

    private int noise(Holder<NormalNoise.NoiseParameters> holder) {
        ResourceKey<NormalNoise.NoiseParameters> key = holder.unwrapKey().orElseThrow(
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

    private int noise(ResourceKey<NormalNoise.NoiseParameters> key) {
        return noise(registries.lookupOrThrow(Registries.NOISE).getOrThrow(key));
    }

    private void writeNoises(WorldgenIr.Buf out) {
        out.u32(noiseHolders.size());
        for (Holder<NormalNoise.NoiseParameters> holder : noiseHolders) {
            boolean legacy = holder.is(Noises.TEMPERATURE_NETHER) || holder.is(Noises.VEGETATION_NETHER);
            long legacyOffset = holder.is(Noises.TEMPERATURE_NETHER) ? 0L : 1L;
            NormalNoise.NoiseParameters p = holder.value();
            out.u8(0).u8(legacy ? 1 : 0).u16(0).i64(legacy ? legacyOffset : 0L);
            out.str(holder.unwrapKey().orElseThrow().identifier().toString());
            out.i32(p.firstOctave()).u32(p.amplitudes().size());
            for (int i = 0; i < p.amplitudes().size(); i++) {
                out.f64(p.amplitudes().getDouble(i));
            }
        }
    }

    // ---- density functions ----

    /** The type codec's view of {@code f} with its children replaced by zero, for private scalar fields. */
    @SuppressWarnings("unchecked")
    private JsonObject json(DensityFunction f) {
        DensityFunction shallow = f.mapChildren(child -> DensityFunctions.zero());
        MapCodec<DensityFunction> codec = (MapCodec<DensityFunction>) (MapCodec<?>) shallow.codec().codec();
        return codec.codec().encodeStart(ops, shallow).getOrThrow().getAsJsonObject();
    }

    private static List<DensityFunction> children(DensityFunction f) {
        List<DensityFunction> children = new ArrayList<>();
        f.mapChildren(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction child) {
                children.add(child);
                return child;
            }
        });
        return children;
    }

    private int begin(DensityFunction f, int op, int arg) {
        return nodes.begin(op, arg, 7, f.minValue(), f.maxValue());
    }

    private int node(DensityFunction f) {
        if (f instanceof DensityFunctions.HolderHolder holder) {
            return node(holder.function().value());
        }
        Integer existing = nodeIds.get(f);
        if (existing != null) {
            return existing;
        }
        int id = emit(f);
        nodeIds.put(f, id);
        return id;
    }

    private int emit(DensityFunction f) {
        WorldgenIr.Buf p = nodes.payload();
        Class<?> type = f.getClass();
        if (type == CONSTANT) {
            int id = begin(f, WorldgenIr.OP_CONST, 0);
            p.f64(f.minValue());
            return id;
        }
        if (f instanceof DensityFunctions.Marker marker) {
            int input = node(marker.wrapped());
            int op = switch (marker.type()) {
                case Interpolated -> WorldgenIr.OP_INTERPOLATED;
                case FlatCache -> WorldgenIr.OP_FLAT_CACHE;
                case Cache2D -> WorldgenIr.OP_CACHE_2D;
                case CacheOnce -> WorldgenIr.OP_CACHE_ONCE;
                case CacheAllInCell -> WorldgenIr.OP_CACHE_ALL_IN_CELL;
                case BlendDensity -> WorldgenIr.OP_BLEND_DENSITY;
            };
            int id = begin(f, op, 0);
            p.u32(input);
            if (op == WorldgenIr.OP_INTERPOLATED) {
                p.i32(0).i32(0);
            }
            return id;
        }
        if (f instanceof DensityFunctions.Noise n) {
            int noise = noise(n.noise().noiseData());
            int id = begin(f, WorldgenIr.OP_NOISE, 0);
            p.u32(noise).f64(n.xzScale()).f64(n.yScale()).u32(WorldgenIr.NONE).u32(WorldgenIr.NONE).u32(WorldgenIr.NONE);
            return id;
        }
        if (f instanceof DensityFunctions.ShiftedNoise s) {
            int sx = node(s.shiftX());
            int sy = node(s.shiftY());
            int sz = node(s.shiftZ());
            int noise = noise(s.noise().noiseData());
            int id = begin(f, WorldgenIr.OP_NOISE, 1);
            p.u32(noise).f64(s.xzScale()).f64(s.yScale()).u32(sx).u32(sy).u32(sz);
            return id;
        }
        if (f instanceof DensityFunctions.ShiftA s) {
            int noise = noise(s.offsetNoise().noiseData());
            int id = begin(f, WorldgenIr.OP_SHIFT_A, 0);
            p.u32(noise);
            return id;
        }
        if (f instanceof DensityFunctions.ShiftB s) {
            int noise = noise(s.offsetNoise().noiseData());
            int id = begin(f, WorldgenIr.OP_SHIFT_B, 0);
            p.u32(noise);
            return id;
        }
        if (f instanceof DensityFunctions.Shift s) {
            int noise = noise(s.offsetNoise().noiseData());
            int id = begin(f, WorldgenIr.OP_SHIFT, 0);
            p.u32(noise);
            return id;
        }
        if (f instanceof BlendedNoise) {
            JsonObject j = json(f);
            int id = begin(f, WorldgenIr.OP_OLD_BLENDED_NOISE, 0);
            p.f64(j.get("xz_scale").getAsDouble()).f64(j.get("y_scale").getAsDouble())
                    .f64(j.get("xz_factor").getAsDouble()).f64(j.get("y_factor").getAsDouble())
                    .f64(j.get("smear_scale_multiplier").getAsDouble());
            return id;
        }
        if (f instanceof DensityFunctions.EndIslandDensityFunction) {
            return begin(f, WorldgenIr.OP_END_ISLANDS, 0);
        }
        if (type == Y_CLAMPED_GRADIENT) {
            JsonObject j = json(f);
            int id = begin(f, WorldgenIr.OP_Y_CLAMPED_GRADIENT, 0);
            p.i32(j.get("from_y").getAsInt()).i32(j.get("to_y").getAsInt())
                    .f64(j.get("from_value").getAsDouble()).f64(j.get("to_value").getAsDouble());
            return id;
        }
        if (f instanceof DensityFunctions.BlendAlpha) {
            return begin(f, WorldgenIr.OP_BLEND_ALPHA, 0);
        }
        if (f instanceof DensityFunctions.BlendOffset) {
            return begin(f, WorldgenIr.OP_BLEND_OFFSET, 0);
        }
        if (f instanceof DensityFunctions.BeardifierOrMarker) {
            return begin(f, WorldgenIr.OP_BEARDIFIER, 0);
        }
        if (f instanceof DensityFunctions.Mapped m) {
            int input = node(m.input());
            int unary = switch (m.type()) {
                case ABS -> WorldgenIr.UNARY_ABS;
                case SQUARE -> WorldgenIr.UNARY_SQUARE;
                case CUBE -> WorldgenIr.UNARY_CUBE;
                case HALF_NEGATIVE -> WorldgenIr.UNARY_HALF_NEGATIVE;
                case QUARTER_NEGATIVE -> WorldgenIr.UNARY_QUARTER_NEGATIVE;
                case INVERT -> WorldgenIr.UNARY_RECIPROCAL;
                case SQUEEZE -> WorldgenIr.UNARY_SQUEEZE;
            };
            int id = begin(f, WorldgenIr.OP_UNARY, unary);
            p.u32(input);
            return id;
        }
        if (f instanceof DensityFunctions.Clamp c) {
            int input = node(c.input());
            int id = begin(f, WorldgenIr.OP_CLAMP, 0);
            p.u32(input).f64(c.minValue()).f64(c.maxValue());
            return id;
        }
        if (f instanceof DensityFunctions.TwoArgumentSimpleFunction two) {
            boolean add = two.type() == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD;
            if (type == MUL_OR_ADD) {
                double argument = two.argument1().minValue();
                int input = node(two.argument2());
                int id = begin(f, WorldgenIr.OP_MUL_OR_ADD, add ? 1 : 0);
                p.u32(input).f64(argument);
                return id;
            }
            int a = node(two.argument1());
            int b = node(two.argument2());
            int binary = switch (two.type()) {
                case ADD -> WorldgenIr.BINARY_ADD;
                case MUL -> WorldgenIr.BINARY_MUL;
                case MIN -> WorldgenIr.BINARY_MIN;
                case MAX -> WorldgenIr.BINARY_MAX;
            };
            int id = begin(f, WorldgenIr.OP_BINARY, binary);
            p.u32(a).u32(b);
            return id;
        }
        if (type == RANGE_CHOICE) {
            List<DensityFunction> c = children(f);
            JsonObject j = json(f);
            int input = node(c.get(0));
            int in = node(c.get(1));
            int out = node(c.get(2));
            int id = begin(f, WorldgenIr.OP_RANGE_CHOICE, 0);
            p.u32(input).f64(j.get("min_inclusive").getAsDouble()).f64(j.get("max_exclusive").getAsDouble())
                    .u32(in).u32(out);
            return id;
        }
        if (type == INTERVAL_SELECT) {
            List<DensityFunction> c = children(f);
            JsonArray thresholds = json(f).getAsJsonArray("thresholds");
            int input = node(c.get(0));
            int[] functions = new int[c.size() - 1];
            for (int i = 1; i < c.size(); i++) {
                functions[i - 1] = node(c.get(i));
            }
            int id = begin(f, WorldgenIr.OP_INTERVAL_SELECT, 0);
            p.u32(input).u32(thresholds.size());
            for (JsonElement t : thresholds) {
                p.f64(t.getAsDouble());
            }
            for (int fn : functions) {
                p.u32(fn);
            }
            return id;
        }
        if (type == FIND_TOP_SURFACE) {
            List<DensityFunction> c = children(f);
            JsonObject j = json(f);
            int density = node(c.get(0));
            int upper = node(c.get(1));
            int id = begin(f, WorldgenIr.OP_FIND_TOP_SURFACE, 0);
            p.u32(density).u32(upper).i32(j.get("lower_bound").getAsInt()).i32(j.get("cell_height").getAsInt());
            return id;
        }
        if (f instanceof DensityFunctions.Spline spline) {
            int s = spline(spline.spline());
            int id = begin(f, WorldgenIr.OP_SPLINE, 0);
            p.u32(s);
            return id;
        }
        throw new UnsupportedOperationException("density function " + type.getName());
    }

    private int spline(CubicSpline<DensityFunctions.Spline.Coordinate> spline) {
        Integer existing = splineIds.get(spline);
        if (existing != null) {
            return existing;
        }
        int id;
        if (spline instanceof CubicSpline.Constant<DensityFunctions.Spline.Coordinate> c) {
            id = splines.constant(c.value());
        } else if (spline instanceof CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate> m) {
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

    // ---- surface rules (read through their codec: the rule records are private) ----

    private WorldgenIr.Buf surface() {
        anchors = new WorldGenerationContext(generator, height);
        int[] noiseSlots = {
            noise(Noises.SURFACE), noise(Noises.SURFACE_SECONDARY), noise(Noises.CLAY_BANDS_OFFSET),
            noise(Noises.BADLANDS_PILLAR), noise(Noises.BADLANDS_PILLAR_ROOF), noise(Noises.BADLANDS_SURFACE),
            noise(Noises.ICEBERG_PILLAR), noise(Noises.ICEBERG_PILLAR_ROOF), noise(Noises.ICEBERG_SURFACE),
        };
        JsonElement rule = SurfaceRules.RuleSource.CODEC.encodeStart(ops, settings.surfaceRule()).getOrThrow();
        int root = rule(rule);
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
        out.u32(root);
        return out;
    }

    private static String typeOf(JsonObject j) {
        String type = j.get("type").getAsString();
        return type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type;
    }

    private int rule(JsonElement element) {
        JsonObject j = element.getAsJsonObject();
        WorldgenIr.Buf b = new WorldgenIr.Buf();
        switch (typeOf(j)) {
            case "block" -> b.u8(WorldgenIr.RULE_BLOCK).u32(state(
                    BlockState.CODEC.parse(ops, j.get("result_state")).getOrThrow()));
            case "sequence" -> {
                JsonArray list = j.getAsJsonArray("sequence");
                int[] children = new int[list.size()];
                for (int i = 0; i < children.length; i++) {
                    children[i] = rule(list.get(i));
                }
                b.u8(WorldgenIr.RULE_SEQUENCE).u32(children.length);
                for (int c : children) {
                    b.u32(c);
                }
            }
            case "condition" -> {
                int c = condition(j.get("if_true"));
                int then = rule(j.get("then_run"));
                b.u8(WorldgenIr.RULE_CONDITION).u32(c).u32(then);
            }
            case "bandlands" -> b.u8(WorldgenIr.RULE_BANDLANDS);
            default -> throw new UnsupportedOperationException("surface rule " + j.get("type"));
        }
        rules.add(b);
        return rules.size() - 1;
    }

    private int anchor(JsonElement element) {
        return VerticalAnchor.CODEC.parse(ops, element).getOrThrow().resolveY(anchors);
    }

    private int condition(JsonElement element) {
        JsonObject j = element.getAsJsonObject();
        WorldgenIr.Buf b = new WorldgenIr.Buf();
        switch (typeOf(j)) {
            case "biome" -> {
                List<Integer> list = new ArrayList<>();
                JsonElement biomesJson = j.get("biome_is");
                Iterable<JsonElement> ids = biomesJson.isJsonArray() ? biomesJson.getAsJsonArray() : List.of(biomesJson);
                for (JsonElement id : ids) {
                    String name = id.getAsString();
                    if (name.startsWith("#")) {
                        throw new UnsupportedOperationException("surface biome condition with a tag: " + name);
                    }
                    list.add(biome(biome(ResourceKey.create(Registries.BIOME, Identifier.parse(name)))));
                }
                b.u8(WorldgenIr.COND_BIOME).u32(list.size());
                for (int v : list) {
                    b.u32(v);
                }
            }
            case "noise_threshold" -> {
                ResourceKey<NormalNoise.NoiseParameters> key =
                        ResourceKey.create(Registries.NOISE, Identifier.parse(j.get("noise").getAsString()));
                b.u8(WorldgenIr.COND_NOISE_THRESHOLD).u32(noise(key)).f64(j.get("min_threshold").getAsDouble())
                        .f64(j.get("max_threshold").getAsDouble())
                        .bool(j.has("is_3d") && j.get("is_3d").getAsBoolean());
            }
            case "vertical_gradient" -> b.u8(WorldgenIr.COND_VERTICAL_GRADIENT).str(j.get("random_name").getAsString())
                    .i32(anchor(j.get("true_at_and_below"))).i32(anchor(j.get("false_at_and_above")));
            case "y_above" -> b.u8(WorldgenIr.COND_Y_ABOVE).i32(anchor(j.get("anchor")))
                    .i32(j.get("surface_depth_multiplier").getAsInt()).bool(j.get("add_stone_depth").getAsBoolean());
            case "water" -> b.u8(WorldgenIr.COND_WATER).i32(j.get("offset").getAsInt())
                    .i32(j.get("surface_depth_multiplier").getAsInt()).bool(j.get("add_stone_depth").getAsBoolean());
            case "temperature" -> b.u8(WorldgenIr.COND_TEMPERATURE);
            case "steep" -> b.u8(WorldgenIr.COND_STEEP);
            case "not" -> {
                int target = condition(j.get("invert"));
                b.u8(WorldgenIr.COND_NOT).u32(target);
            }
            case "hole" -> b.u8(WorldgenIr.COND_HOLE);
            case "above_preliminary_surface" -> b.u8(WorldgenIr.COND_ABOVE_PRELIMINARY_SURFACE);
            case "stone_depth" -> b.u8(WorldgenIr.COND_STONE_DEPTH).i32(j.get("offset").getAsInt())
                    .bool(j.get("add_surface_depth").getAsBoolean()).i32(j.get("secondary_depth_range").getAsInt())
                    .bool("ceiling".equals(j.get("surface_type").getAsString()));
            default -> throw new UnsupportedOperationException("surface condition " + j.get("type"));
        }
        conditions.add(b);
        return conditions.size() - 1;
    }

    // ---- biomes ----

    private int biome(Holder<Biome> holder) {
        // Holders from the surface rules and from the biome source can be distinct objects for
        // one biome (rules decoded from data carry their own references): key them by registry key.
        Holder<Biome> canonical = holder.unwrapKey().map(this::biome).orElse(holder);
        if (canonical != holder) {
            return biome(canonical);
        }
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

    private WorldgenIr.Buf biomes(int profile) {
        BiomeSource source = generator.getBiomeSource();
        for (Holder<Biome> holder : source.possibleBiomes()) {
            biome(holder);
        }
        WorldgenIr.Buf tail = new WorldgenIr.Buf();
        int kind;
        if (source instanceof MultiNoiseBiomeSource multiNoise) {
            kind = WorldgenIr.BIOME_SOURCE_MULTI_NOISE;
            Climate.ParameterList<Holder<Biome>> list = presetOf(multiNoise);
            tail.u32(6).u32(list.values().size());
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
            tail.u32(biome(fixed.getNoiseBiome(0, 0, 0, null)));
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
            out.u32(biomeIds.applyAsInt(value)).f32(value.getBaseTemperature()).u8(frozen(holder) ? 1 : 0)
                    .u8(flags).u16(0);
        }
        out.bytes(tail);
        return out;
    }

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

    /** Whether the biome uses {@code TemperatureModifier.FROZEN} (read through the network codec). */
    private boolean frozen(Holder<Biome> holder) {
        try {
            JsonElement json = Biome.NETWORK_CODEC.encodeStart(ops, holder.value()).getOrThrow();
            JsonElement modifier = json.getAsJsonObject().get("temperature_modifier");
            return modifier != null && "frozen".equals(modifier.getAsString());
        } catch (RuntimeException e) {
            // Only the two frozen oceans use the modifier in vanilla data.
            return holder.is(Biomes.FROZEN_OCEAN) || holder.is(Biomes.DEEP_FROZEN_OCEAN);
        }
    }
}
