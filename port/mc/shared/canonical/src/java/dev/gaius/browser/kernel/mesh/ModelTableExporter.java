package dev.gaius.browser.kernel.mesh;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MangroveRootsBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.GaiusMeshBlockAccess;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3fc;

/**
 * Exports the mesh kernel's MODEL TABLE (see {@link MeshTableWriter}) from one resource set:
 * every {@code Block.BLOCK_STATE_REGISTRY} state with its per-state predicates, occlusion face
 * masks, model parts and baked quads, tint sources and fluid model.
 *
 * <p>Runs incrementally on the render thread ({@link #step}) so a resource reload never costs
 * one long frame; sections keep the vanilla compiler until the table is finished.</p>
 *
 * <p>Nothing here relies on class names of vanilla internals: model selection is probed with a
 * scripted {@link RandomSource} (SingleVariant draws nothing, WeightedVariants one
 * {@code nextInt(totalWeight)}, MultiPartModel {@code nextLong()} then {@code setSeed} per
 * selected model), tint sources are probed against a {@link BlockAndTintGetter} that answers
 * each biome color resolver with a sentinel, and seed and offset rules are read back from
 * {@code getSeed}/{@code getOffset}. A state that does not fit the kernel's model (another random
 * pattern, an arithmetic tint, an occlusion face off the 1/16 grid, any exception) is marked
 * {@link MeshTableWriter#FLAG_UNSUPPORTED} and the kernel hands its sections back to vanilla.</p>
 */
final class ModelTableExporter {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final BlockPos PROBE = new BlockPos(9, 64, -7);
    private static final int MAX_WEIGHT_PROBES = 1024;
    private static final int SENTINEL_GRASS = 0xFF1A2B01;
    private static final int SENTINEL_FOLIAGE = 0xFF1A2B02;
    private static final int SENTINEL_DRY_FOLIAGE = 0xFF1A2B03;
    private static final int SENTINEL_WATER = 0xFF1A2B04;
    private static final int SENTINEL_OTHER = 0xFF1A2B0F;
    private static final int OFF_GRID = 1 << 24;
    private static final double GRID_EPSILON = 1.0E-6;

    final BlockStateModelSet models;
    final FluidStateModelSet fluidModels;
    final BlockColors colors;
    final int epoch;
    private final int stateCount;
    private final MeshTableWriter writer;
    private final long[] blockEntityStates;
    private final long[] airStates;
    private final MeshTableWriter.StateRecord record = new MeshTableWriter.StateRecord();
    private final IdentityHashMap<BlockStateModelPart, Integer> partIds = new IdentityHashMap<>();
    private final IdentityHashMap<BakedQuad, Integer> quadIds = new IdentityHashMap<>();
    private final IdentityHashMap<FluidModel, Integer> fluidModelIds = new IdentityHashMap<>();
    private final IdentityHashMap<Fluid, Integer> fluidGroups = new IdentityHashMap<>();
    @SuppressWarnings("unchecked")
    private final IdentityHashMap<VoxelShape, Integer>[] faceIds = new IdentityHashMap[6];
    private final ProbeRandom random = new ProbeRandom();
    private final TintProbe tintProbe = new TintProbe();
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private final float[] xyz = new float[12];
    private final float[] uv = new float[8];
    private final int[] rows = new int[16];
    private final int[] ends = new int[7];
    private final float[] sprites = new float[12];
    private int[] quadScratch = new int[256];
    private int[] groupKey = new int[256];
    private int[] tintKey = new int[128];
    private int nextFluidGroup = 3;
    private int cursor;
    private int unsupported;
    private int airId = -1;
    private long startedNanos;
    private long workNanos;
    private int probeKind;
    private int probeArgb;

    ModelTableExporter(BlockStateModelSet models, FluidStateModelSet fluidModels, BlockColors colors, int epoch) {
        this.models = models;
        this.fluidModels = fluidModels;
        this.colors = colors;
        this.epoch = epoch;
        this.stateCount = Block.BLOCK_STATE_REGISTRY.size();
        this.writer = new MeshTableWriter(MeshProfile.PROFILE, epoch, stateCount);
        this.blockEntityStates = new long[(stateCount + 63) >>> 6];
        this.airStates = new long[(stateCount + 63) >>> 6];
        for (int d = 0; d < 6; d++) {
            faceIds[d] = new IdentityHashMap<>();
        }
    }

    boolean matches(BlockStateModelSet models, FluidStateModelSet fluidModels, BlockColors colors) {
        return this.models == models && this.fluidModels == fluidModels && this.colors == colors;
    }

    /** Exports states until {@code deadlineNanos}; true once every state is written. */
    boolean step(long deadlineNanos) {
        long start = System.nanoTime();
        if (startedNanos == 0L) {
            startedNanos = start;
        }
        while (cursor < stateCount) {
            exportState(cursor++);
            if ((cursor & 31) == 0 && System.nanoTime() >= deadlineNanos) {
                break;
            }
        }
        workNanos += System.nanoTime() - start;
        return cursor >= stateCount;
    }

    /** The finished table; call once {@link #step} returned true. */
    MeshModelTable finish() {
        long start = System.nanoTime();
        byte[] bytes = writer.encode();
        workNanos += System.nanoTime() - start;
        System.out.println("[mesh-kernel] model table epoch " + epoch + ": " + stateCount + " states ("
                + unsupported + " left to vanilla), " + partIds.size() + " parts, " + writer.quadCount()
                + " quads, " + bytes.length + " bytes, " + (workNanos / 1_000_000L) + " ms of work over "
                + ((System.nanoTime() - startedNanos) / 1_000_000L) + " ms");
        return new MeshModelTable(epoch, bytes, stateCount, models, fluidModels, colors, blockEntityStates,
                airStates, airId);
    }

    private void exportState(int id) {
        MeshTableWriter.StateRecord s = record;
        s.clear();
        BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
        if (state == null) {
            s.markUnsupported();
            unsupported++;
            writer.state(id, s);
            return;
        }
        try {
            fill(id, state, s);
        } catch (RuntimeException error) {
            // Keep whatever describes the state as a neighbor; never mesh it in the kernel.
            s.markUnsupported();
        }
        if ((s.flags & MeshTableWriter.FLAG_UNSUPPORTED) != 0) {
            unsupported++;
        }
        writer.state(id, s);
    }

    private void fill(int id, BlockState state, MeshTableWriter.StateRecord s) {
        Block block = state.getBlock();
        int flags = 0;
        if (state.isAir()) {
            flags |= MeshTableWriter.FLAG_AIR;
            airStates[id >>> 6] |= 1L << (id & 63);
            if (airId < 0 && state == Blocks.AIR.defaultBlockState()) {
                airId = id;
            }
            writer.airId(id);
        }
        if (state.hasBlockEntity()) {
            flags |= MeshTableWriter.FLAG_HAS_BLOCK_ENTITY;
            blockEntityStates[id >>> 6] |= 1L << (id & 63);
        }
        if (state.isSolidRender()) {
            flags |= MeshTableWriter.FLAG_SOLID_RENDER;
        }
        boolean model = state.getRenderShape() == RenderShape.MODEL;
        if (model) {
            flags |= MeshTableWriter.FLAG_RENDER_MODEL;
        }
        if (MeshProfile.lightPermeable(state)) {
            flags |= MeshTableWriter.FLAG_LIGHT_PERMEABLE;
        }
        if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            flags |= MeshTableWriter.FLAG_COLLISION_FULL;
        }
        if (state.emissiveRendering()) {
            flags |= MeshTableWriter.FLAG_EMISSIVE;
        }
        if (state.isSolid()) {
            flags |= MeshTableWriter.FLAG_SOLID;
        }
        if (block instanceof LeavesBlock) {
            flags |= MeshTableWriter.FLAG_LEAVES;
        }
        if (block instanceof HalfTransparentBlock) {
            flags |= MeshTableWriter.FLAG_HALF_TRANSPARENT;
        }
        if (MeshProfile.blocksFluidFlow(state)) {
            flags |= MeshTableWriter.FLAG_BLOCKS_FLUID_FLOW;
        }
        if (block instanceof IceBlock) {
            flags |= MeshTableWriter.FLAG_ICE;
        }
        if (state.is(BlockTags.BARS)) {
            flags |= MeshTableWriter.FLAG_BARS_TAG;
        }
        s.flags = flags;
        s.blockId = BuiltInRegistries.BLOCK.getId(block) & 0xFFFF;
        s.shade = state.getShadeBrightness(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        s.emission = Math.max(0, Math.min(15, state.getLightEmission()));
        s.skip = skipKind(block);

        boolean supported = true;
        for (int d = 0; d < 6; d++) {
            int face = face(state.getFaceOcclusionShape(DIRECTIONS[d]), d);
            if ((face & OFF_GRID) != 0) {
                supported = false;
            }
            s.faces[d] = face & ~OFF_GRID;
            if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, DIRECTIONS[d])) {
                s.sturdy |= 1 << d;
            }
        }
        for (int d = 2; d < 6; d++) {
            BooleanProperty property = CrossCollisionBlock.PROPERTY_BY_DIRECTION.get(DIRECTIONS[d]);
            if (property != null && state.hasProperty(property)) {
                s.connPresent |= 1 << d;
                if (state.getValue(property)) {
                    s.connTrue |= 1 << d;
                }
            }
        }

        if (!seedOffset(state, s)) {
            supported = false;
        }
        if (state.hasOffsetFunction()) {
            s.offsetType = MeshTableWriter.OFFSET_XZ;
            for (int i = 0; i < 16; i++) {
                Vec3 offset = state.getOffset(new BlockPos(i * 37 - 300, 64, i * 91 + 17));
                if (offset.y != 0.0) {
                    s.offsetType = MeshTableWriter.OFFSET_XYZ;
                    break;
                }
            }
            s.maxHorizontalOffset = GaiusMeshBlockAccess.maxHorizontalOffset(block);
            s.maxVerticalOffset = GaiusMeshBlockAccess.maxVerticalOffset(block);
        }

        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            s.fluidHeight = fluid.getOwnHeight();
            s.fluidGroup = fluidGroup(fluid.getType());
            if (fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING)) {
                s.flags |= MeshTableWriter.FLAG_FLUID_FALLING;
            }
            int fluidModel = fluidModel(fluidModels.get(fluid), fluid);
            if (fluidModel < 0 || s.fluidGroup == 0) {
                supported = false;
            } else {
                s.fluidModel = fluidModel;
            }
        }

        if (!tints(state, s)) {
            supported = false;
        }
        if (model && !model(state, s)) {
            supported = false;
        }
        if (!supported) {
            s.markUnsupported();
        }
    }

    // --- per-state rules -----------------------------------------------------------------------

    private static int skipKind(Block block) {
        if (block instanceof LeavesBlock) {
            return MeshTableWriter.SKIP_LEAVES;
        }
        if (block instanceof IronBarsBlock) {
            return MeshTableWriter.SKIP_BARS;
        }
        if (block instanceof LiquidBlock) {
            return MeshTableWriter.SKIP_SAME_FLUID;
        }
        if (block instanceof MangroveRootsBlock) {
            return MeshTableWriter.SKIP_SAME_BLOCK_VERTICAL;
        }
        if (block instanceof HalfTransparentBlock || block instanceof PowderSnowBlock) {
            return MeshTableWriter.SKIP_SAME_BLOCK;
        }
        return MeshTableWriter.SKIP_NONE;
    }

    /** {@code getSeed(pos) == Mth.getSeed(pos + d)} for the d this state uses. */
    private static boolean seedOffset(BlockState state, MeshTableWriter.StateRecord s) {
        long seed = state.getSeed(PROBE);
        if (seed == Mth.getSeed(PROBE.getX(), PROBE.getY(), PROBE.getZ())) {
            return true;
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (seed == Mth.getSeed(PROBE.getX() + dx, PROBE.getY() + dy, PROBE.getZ() + dz)) {
                        s.seedDx = dx;
                        s.seedDy = dy;
                        s.seedDz = dz;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int fluidGroup(Fluid fluid) {
        if (Fluids.WATER.isSame(fluid)) {
            return 1;
        }
        if (Fluids.LAVA.isSame(fluid)) {
            return 2;
        }
        Fluid key = fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
        Integer known = fluidGroups.get(key);
        if (known != null) {
            return known;
        }
        if (nextFluidGroup > 255) {
            return 0;
        }
        int group = nextFluidGroup++;
        fluidGroups.put(key, group);
        return group;
    }

    // --- occlusion faces -----------------------------------------------------------------------

    /**
     * Face id of {@code getFaceOcclusionShape(direction)}: 0 for Shapes.empty(), 1 for
     * Shapes.block(), otherwise a 1/16 mask. Cells are set only where a box covers them fully
     * (conservative as an occluder); a box edge off the grid adds {@link #OFF_GRID}.
     */
    private int face(VoxelShape shape, int d) {
        if (shape == Shapes.empty()) {
            return MeshTableWriter.FACE_EMPTY;
        }
        if (shape == Shapes.block()) {
            return MeshTableWriter.FACE_FULL;
        }
        Integer cached = faceIds[d].get(shape);
        if (cached != null) {
            return cached;
        }
        for (int i = 0; i < 16; i++) {
            rows[i] = 0;
        }
        boolean offGrid = false;
        for (AABB box : shape.toAabbs()) {
            double u0;
            double u1;
            double v0;
            double v1;
            if (d <= 1) {
                u0 = box.minX;
                u1 = box.maxX;
                v0 = box.minZ;
                v1 = box.maxZ;
            } else if (d <= 3) {
                u0 = box.minX;
                u1 = box.maxX;
                v0 = box.minY;
                v1 = box.maxY;
            } else {
                u0 = box.minZ;
                u1 = box.maxZ;
                v0 = box.minY;
                v1 = box.maxY;
            }
            offGrid |= !onGrid(u0) || !onGrid(u1) || !onGrid(v0) || !onGrid(v1);
            int a0 = cellStart(u0);
            int a1 = cellEnd(u1);
            int b0 = cellStart(v0);
            int b1 = cellEnd(v1);
            if (a1 <= a0 || b1 <= b0) {
                continue;
            }
            int bits = ((1 << a1) - 1) & ~((1 << a0) - 1);
            for (int v = b0; v < b1; v++) {
                rows[v] |= bits;
            }
        }
        int id = writer.mask(rows) | (offGrid ? OFF_GRID : 0);
        faceIds[d].put(shape, id);
        return id;
    }

    private static boolean onGrid(double coordinate) {
        double scaled = coordinate * 16.0;
        return Math.abs(scaled - Math.rint(scaled)) <= GRID_EPSILON;
    }

    private static int cellStart(double coordinate) {
        int cell = (int) Math.ceil(coordinate * 16.0 - GRID_EPSILON);
        return Math.max(0, Math.min(16, cell));
    }

    private static int cellEnd(double coordinate) {
        int cell = (int) Math.floor(coordinate * 16.0 + GRID_EPSILON);
        return Math.max(0, Math.min(16, cell));
    }

    // --- tints ---------------------------------------------------------------------------------

    private boolean tints(BlockState state, MeshTableWriter.StateRecord s) {
        List<BlockTintSource> sources = colors.getTintSources(state);
        int count = sources == null ? 0 : sources.size();
        if (count == 0) {
            return true;
        }
        if (count > MeshTableWriter.MAX_TINTS_PER_STATE) {
            return false;
        }
        if (tintKey.length < count * 2) {
            tintKey = new int[count * 2];
        }
        for (int i = 0; i < count; i++) {
            BlockTintSource source = sources.get(i);
            if (source == null || !probeTint(source, state)) {
                return false;
            }
            tintKey[2 * i] = probeKind;
            tintKey[2 * i + 1] = probeArgb;
        }
        s.firstTint = writer.tintRun(tintKey, count);
        s.tintCount = count;
        return true;
    }

    /** Classifies {@code source.colorInWorld} for {@code state} into probeKind/probeArgb. */
    private boolean probeTint(BlockTintSource source, BlockState state) {
        TintProbe probe = tintProbe;
        probe.reset(state);
        int color;
        try {
            color = source.colorInWorld(state, probe, PROBE);
        } catch (RuntimeException error) {
            return false;
        }
        if (probe.calls == 0) {
            probeKind = MeshTableWriter.TINT_CONSTANT;
            probeArgb = color;
            return true;
        }
        if (probe.calls != 1 || color != probe.sentinel) {
            return false;
        }
        boolean here = probe.x == PROBE.getX() && probe.y == PROBE.getY() && probe.z == PROBE.getZ();
        boolean below = probe.x == PROBE.getX() && probe.y == PROBE.getY() - 1 && probe.z == PROBE.getZ();
        probeArgb = 0;
        if (probe.sentinel == SENTINEL_GRASS && below) {
            probeKind = MeshTableWriter.TINT_GRASS_BELOW;
            return true;
        }
        if (!here) {
            return false;
        }
        switch (probe.sentinel) {
            case SENTINEL_GRASS:
                probeKind = MeshTableWriter.TINT_GRASS;
                return true;
            case SENTINEL_FOLIAGE:
                probeKind = MeshTableWriter.TINT_FOLIAGE;
                return true;
            case SENTINEL_DRY_FOLIAGE:
                probeKind = MeshTableWriter.TINT_DRY_FOLIAGE;
                return true;
            case SENTINEL_WATER:
                probeKind = MeshTableWriter.TINT_WATER;
                return true;
            default:
                return false;
        }
    }

    // --- fluids --------------------------------------------------------------------------------

    private int fluidModel(FluidModel model, FluidState fluid) {
        if (model == null) {
            return -1;
        }
        Integer known = fluidModelIds.get(model);
        if (known != null) {
            return known;
        }
        int tintKind = MeshTableWriter.NO_TINT;
        int tintArgb = 0;
        BlockTintSource source = model.tintSource();
        if (source != null) {
            if (!probeTint(source, fluid.createLegacyBlock())) {
                fluidModelIds.put(model, -1);
                return -1;
            }
            tintKind = probeKind;
            tintArgb = probeArgb;
        }
        sprite(model.stillMaterial(), 0);
        sprite(model.flowingMaterial(), 4);
        sprite(model.overlayMaterial(), 8);
        int id = writer.fluidModel(model.layer().ordinal(), model.overlayMaterial() != null, tintKind, tintArgb,
                sprites);
        fluidModelIds.put(model, id);
        return id;
    }

    private void sprite(Material.Baked material, int at) {
        if (material == null) {
            sprites[at] = 0.0f;
            sprites[at + 1] = 0.0f;
            sprites[at + 2] = 0.0f;
            sprites[at + 3] = 0.0f;
            return;
        }
        TextureAtlasSprite sprite = material.sprite();
        sprites[at] = sprite.getU0();
        sprites[at + 1] = sprite.getU1();
        sprites[at + 2] = sprite.getV0();
        sprites[at + 3] = sprite.getV1();
    }

    // --- models --------------------------------------------------------------------------------

    /** Model groups of the state's BlockStateModel, probed through collectParts. */
    private boolean model(BlockState state, MeshTableWriter.StateRecord s) {
        BlockStateModel model = models.get(state);
        if (model == null) {
            return false;
        }
        ProbeRandom r = random;
        r.begin(-1, 0);
        parts.clear();
        model.collectParts(r, parts);
        if (r.illegal) {
            return false;
        }
        boolean multipart = r.multipart;
        int groupCount;
        if (multipart) {
            groupCount = r.groupCount;
            if (parts.size() != groupCount) {
                return false;
            }
        } else {
            if (parts.size() > 1 || r.groupCount != 0) {
                return false;
            }
            groupCount = parts.size();
        }
        if (groupCount > 255) {
            return false;
        }
        int[] bounds = new int[groupCount];
        Object[] firstParts = new Object[groupCount];
        for (int g = 0; g < groupCount; g++) {
            bounds[g] = r.bounds[g];
            firstParts[g] = parts.get(g);
        }
        int length = 0;
        for (int g = 0; g < groupCount; g++) {
            int bound = bounds[g];
            if (bound == 0) {
                ensureGroupKey(length + 5);
                groupKey[length++] = 0;
                groupKey[length++] = 1;
                groupKey[length++] = 1;
                groupKey[length++] = 1;
                groupKey[length++] = partId((BlockStateModelPart) firstParts[g]);
                continue;
            }
            if (bound > MAX_WEIGHT_PROBES) {
                return false;
            }
            ensureGroupKey(length + 3 + 2 * bound);
            int header = length;
            groupKey[length++] = 1;
            groupKey[length++] = bound;
            groupKey[length++] = 0;
            int entryCount = 0;
            Object current = null;
            int run = 0;
            for (int k = 0; k < bound; k++) {
                r.begin(g, k);
                parts.clear();
                model.collectParts(r, parts);
                if (r.illegal || r.multipart != multipart || parts.size() != Math.max(groupCount, 1)
                        || r.bounds[g] != bound) {
                    return false;
                }
                Object picked = parts.get(multipart ? g : 0);
                if (picked == current) {
                    run++;
                    continue;
                }
                if (current != null) {
                    groupKey[length++] = run;
                    groupKey[length++] = partId((BlockStateModelPart) current);
                    entryCount++;
                }
                current = picked;
                run = 1;
            }
            groupKey[length++] = run;
            groupKey[length++] = partId((BlockStateModelPart) current);
            entryCount++;
            groupKey[header + 2] = entryCount;
        }
        s.multipart = multipart;
        s.groupCount = groupCount;
        s.firstGroup = groupCount == 0 ? 0 : writer.groupRun(groupKey, length);
        return true;
    }

    private void ensureGroupKey(int length) {
        if (groupKey.length < length) {
            int[] grown = new int[Math.max(length, groupKey.length * 2)];
            System.arraycopy(groupKey, 0, grown, 0, groupKey.length);
            groupKey = grown;
        }
    }

    private int partId(BlockStateModelPart part) {
        Integer known = partIds.get(part);
        if (known != null) {
            return known;
        }
        int total = 0;
        for (int k = 0; k < 7; k++) {
            List<BakedQuad> quads = part.getQuads(k < 6 ? DIRECTIONS[k] : null);
            int count = quads.size();
            if (quadScratch.length < total + count) {
                int[] grown = new int[Math.max(total + count, quadScratch.length * 2)];
                System.arraycopy(quadScratch, 0, grown, 0, total);
                quadScratch = grown;
            }
            for (int i = 0; i < count; i++) {
                quadScratch[total++] = quadId(quads.get(i));
            }
            ends[k] = total;
        }
        int id = writer.part(quadScratch, ends, part.useAmbientOcclusion());
        partIds.put(part, id);
        return id;
    }

    private int quadId(BakedQuad quad) {
        Integer known = quadIds.get(quad);
        if (known != null) {
            return known;
        }
        for (int i = 0; i < 4; i++) {
            Vector3fc position = quad.position(i);
            xyz[3 * i] = position.x();
            xyz[3 * i + 1] = position.y();
            xyz[3 * i + 2] = position.z();
            long packed = quad.packedUV(i);
            uv[2 * i] = UVPair.unpackU(packed);
            uv[2 * i + 1] = UVPair.unpackV(packed);
        }
        BakedQuad.MaterialInfo material = quad.materialInfo();
        int id = writer.quad(writer.geometry(xyz), writer.uv(uv), material.tintIndex(), quad.direction().ordinal(),
                MeshProfile.shadeFace(quad), material.layer().ordinal(), material.lightEmission());
        quadIds.put(quad, id);
        return id;
    }

    // --- probes --------------------------------------------------------------------------------

    /**
     * RandomSource that records how a BlockStateModel draws: one optional {@code nextLong()}
     * (MultiPartModel), {@code setSeed} once per selected model, at most one
     * {@code nextInt(bound)} per group. {@link #begin} scripts the value one group's
     * {@code nextInt} returns; everything else returns 0, and any other call marks the model as
     * not expressible.
     */
    static final class ProbeRandom implements RandomSource {
        static final int MAX_GROUPS = 256;
        final int[] bounds = new int[MAX_GROUPS];
        boolean multipart;
        boolean illegal;
        int groupCount;
        private int group;
        private int scriptGroup;
        private int scriptValue;
        private boolean drew;

        void begin(int scriptedGroup, int value) {
            for (int i = 0; i < groupCount && i < MAX_GROUPS; i++) {
                bounds[i] = 0;
            }
            bounds[0] = 0;
            multipart = false;
            illegal = false;
            groupCount = 0;
            group = 0;
            scriptGroup = scriptedGroup;
            scriptValue = value;
            drew = false;
        }

        @Override
        public RandomSource fork() {
            illegal = true;
            return this;
        }

        @Override
        public PositionalRandomFactory forkPositional() {
            illegal = true;
            throw new UnsupportedOperationException("model probe");
        }

        @Override
        public void setSeed(long seed) {
            if (!multipart || groupCount >= MAX_GROUPS) {
                illegal = true;
                return;
            }
            group = groupCount++;
            bounds[group] = 0;
            drew = false;
        }

        @Override
        public int nextInt() {
            illegal = true;
            return 0;
        }

        @Override
        public int nextInt(int bound) {
            if (bound <= 0 || drew || (multipart && groupCount == 0)) {
                illegal = true;
                return 0;
            }
            drew = true;
            bounds[group] = bound;
            return group == scriptGroup ? Math.min(scriptValue, bound - 1) : 0;
        }

        @Override
        public long nextLong() {
            if (multipart || drew || groupCount != 0) {
                illegal = true;
                return 0L;
            }
            multipart = true;
            return 0L;
        }

        @Override
        public boolean nextBoolean() {
            illegal = true;
            return false;
        }

        @Override
        public float nextFloat() {
            illegal = true;
            return 0.0f;
        }

        @Override
        public double nextDouble() {
            illegal = true;
            return 0.0;
        }

        @Override
        public double nextGaussian() {
            illegal = true;
            return 0.0;
        }
    }

    /**
     * BlockAndTintGetter for tint source probes: the probed state at {@link #PROBE}, air
     * elsewhere, and a sentinel color per biome color resolver, recording where it was asked.
     */
    static final class TintProbe implements BlockAndTintGetter {
        private BlockState state;
        int calls;
        int sentinel;
        int x;
        int y;
        int z;

        void reset(BlockState probed) {
            state = probed;
            calls = 0;
            sentinel = 0;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            calls++;
            x = pos.getX();
            y = pos.getY();
            z = pos.getZ();
            if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
                sentinel = SENTINEL_GRASS;
            } else if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
                sentinel = SENTINEL_FOLIAGE;
            } else if (resolver == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER) {
                sentinel = SENTINEL_DRY_FOLIAGE;
            } else if (resolver == BiomeColors.WATER_COLOR_RESOLVER) {
                sentinel = SENTINEL_WATER;
            } else {
                sentinel = SENTINEL_OTHER;
            }
            return sentinel;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.getX() == PROBE.getX() && pos.getY() == PROBE.getY() && pos.getZ() == PROBE.getZ()
                    ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
