package dev.gaius.browser.kernel.mesh;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.GaiusLightColumnAccess;
import net.minecraft.world.phys.Vec3;

/**
 * Main-thread snapshot of one section job: copies what {@code SectionCompiler.compile} would read
 * from the {@link RenderSectionRegion} and the client level into {@link MeshJobBuffers}.
 *
 * <ul>
 *   <li>states: the 20^3 box (section plus two blocks) from the region's 27 section copies,
 *       straight from each copy's bit storage and palette (palette entries map to global ids
 *       once per section, the center section is bulk-unpacked);</li>
 *   <li>light: the 18^3 box from the same published listener values vanilla reads;
 *       queued packet layers are never mistaken for published light;</li>
 *   <li>biomes: the 6^3 quarts BiomeManager's zoom can reach, as palette indices (they cover
 *       the blocks two columns past the section, which a biome blend radius of 2 samples),
 *       plus the swamp grass noise per column the blend reaches;</li>
 *   <li>block entities of the section in vanilla order (x, then y, then z).</li>
 * </ul>
 * Nothing is allocated per block; the only allocations are the block entity list and the light
 * listeners' own lookups.
 */
final class SectionSnapshot {
    private static final class CaptureScratch {
        private static final BlockPos.MutableBlockPos POSITION = new BlockPos.MutableBlockPos();
    }

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int SWAMP_BELOW_COLOR = 0x4C763C;
    private static final Comparator<BlockEntity> BLOCK_ENTITY_ORDER = Comparator
            .comparingInt((BlockEntity entity) -> entity.getBlockPos().getZ())
            .thenComparingInt(entity -> entity.getBlockPos().getY())
            .thenComparingInt(entity -> entity.getBlockPos().getX());

    private SectionSnapshot() {
    }

    /** Non-air blocks of the center section in the last capture. */
    static int lastNonAir;
    private static MeshJobBuffers checkBuffer;
    private static List<BlockEntity> checkBlockEntities;

    /**
     * Fills {@code b} for the section {@code node}; returns false when the region cannot be
     * expressed (debug world, unexpected layout), so the caller keeps the vanilla compiler.
     */
    static boolean capture(MeshJobBuffers b, RenderSectionRegion region, long node, MeshModelTable table,
            Vec3 camera, List<BlockEntity> blockEntities, int blend) {
        return capture(b, region, node, table, camera, blockEntities, blend, MeshKernelBridge.fastSnapshotEnabled());
    }

    /** Compares both paths synchronously while leaving the legacy result in the submitted buffers. */
    static boolean captureChecked(MeshJobBuffers legacy, RenderSectionRegion region, long node, MeshModelTable table,
            Vec3 camera, List<BlockEntity> blockEntities, int blend) {
        if (checkBuffer == null) {
            checkBuffer = new MeshJobBuffers();
            checkBlockEntities = new java.util.ArrayList<>();
        }
        MeshJobBuffers fast = checkBuffer;
        List<BlockEntity> fastEntities = checkBlockEntities;
        fastEntities.clear();
        boolean timeCapture = MeshKernelBridge.snapshotTimingEnabled();
        long fastStarted = timeCapture ? System.nanoTime() : 0;
        boolean fastCaptured = capture(fast, region, node, table, camera, fastEntities, blend, true);
        if (timeCapture) MeshKernelBridge.noteSnapshotPathNanos(true, System.nanoTime() - fastStarted);
        long legacyStarted = timeCapture ? System.nanoTime() : 0;
        boolean legacyCaptured = capture(legacy, region, node, table, camera, blockEntities, blend, false);
        if (timeCapture) MeshKernelBridge.noteSnapshotPathNanos(false, System.nanoTime() - legacyStarted);
        int sx = SectionPos.x(node), sy = SectionPos.y(node), sz = SectionPos.z(node);
        int firstDifference = fastCaptured && legacyCaptured
                ? firstDifference(legacy, fast, blockEntities, fastEntities) : -1;
        int legacyLength = legacyCaptured ? snapshotLength(legacy, blockEntities) : 0;
        int fastLength = fastCaptured ? snapshotLength(fast, fastEntities) : 0;
        MeshKernelBridge.recordSnapshotCheck(sx, sy, sz,
                fastCaptured == legacyCaptured ? firstDifference : 0, legacyLength, fastLength);
        return legacyCaptured;
    }

    static boolean capture(MeshJobBuffers b, RenderSectionRegion region, long node, MeshModelTable table,
            Vec3 camera, List<BlockEntity> blockEntities, int blend, boolean fast) {
        MeshKernelAccess.Region access = (MeshKernelAccess.Region) (Object) region;
        SectionCopy[] copies = access.gaius$sections();
        ClientLevel level = access.gaius$level();
        int minX = access.gaius$minSectionX();
        int minY = access.gaius$minSectionY();
        int minZ = access.gaius$minSectionZ();
        int sx = SectionPos.x(node);
        int sy = SectionPos.y(node);
        int sz = SectionPos.z(node);
        if (copies == null || copies.length != 27 || level == null || minX != sx - 1 || minY != sy - 1
                || minZ != sz - 1 || level.isDebug()) {
            return false;
        }
        b.beginJob(table.wideIds());
        if (!captureStates(b, copies, minX, minY, minZ, sx, sy, sz, table, fast)) {
            return false;
        }
        captureLight(b, level.getLightEngine(), sx, sy, sz, fast);
        if (!captureBiomes(b, level, sx, sy, sz, blend)) {
            return false;
        }
        CardinalLighting cardinal = region.cardinalLighting();
        for (int d = 0; d < 6; d++) {
            b.floats[d] = cardinal.byFace(DIRECTIONS[d]);
        }
        b.floats[6] = (float) (camera.x - (sx << 4));
        b.floats[7] = (float) (camera.y - (sy << 4));
        b.floats[8] = (float) (camera.z - (sz << 4));
        collectBlockEntities(b, region, sx, sy, sz, table, blockEntities, fast);
        return true;
    }

    private static int snapshotLength(MeshJobBuffers b, List<BlockEntity> blockEntities) {
        return (b.wide ? b.ids32.length * 4 : b.ids16.length * 2) + b.light.length + b.quarts.length
                + b.biomeCount * MeshJobBuffers.PALETTE_INTS * 4 + b.swamp.length + b.floats.length * 4
                + 12 + (b.packedStateLength == 0 ? 0 : b.packedStateLength * 4) + blockEntities.size() * 12;
    }

    private static int firstDifference(MeshJobBuffers a, MeshJobBuffers b, List<BlockEntity> aEntities,
            List<BlockEntity> bEntities) {
        int offset = 0;
        if (a.wide != b.wide) return 0;
        if (a.wide) {
            for (int i = 0; i < a.ids32.length; i++, offset += 4) if (a.ids32[i] != b.ids32[i]) return offset;
        } else {
            for (int i = 0; i < a.ids16.length; i++, offset += 2) if (a.ids16[i] != b.ids16[i]) return offset;
        }
        for (int i = 0; i < a.light.length; i++, offset++) if (a.light[i] != b.light[i]) return offset;
        for (int i = 0; i < a.quarts.length; i++, offset++) if (a.quarts[i] != b.quarts[i]) return offset;
        int paletteLength = Math.min(a.biomeCount, b.biomeCount) * MeshJobBuffers.PALETTE_INTS;
        for (int i = 0; i < paletteLength; i++, offset += 4) if (a.palette[i] != b.palette[i]) return offset;
        if (a.biomeCount != b.biomeCount) return offset;
        for (int i = 0; i < a.swamp.length; i++, offset++) if (a.swamp[i] != b.swamp[i]) return offset;
        for (int i = 0; i < a.floats.length; i++, offset += 4) {
            if (Float.floatToRawIntBits(a.floats[i]) != Float.floatToRawIntBits(b.floats[i])) return offset;
        }
        if (a.header[MeshKernelBridge.H_SEED_HI] != b.header[MeshKernelBridge.H_SEED_HI]) return offset;
        offset += 4;
        if (a.header[MeshKernelBridge.H_SEED_LO] != b.header[MeshKernelBridge.H_SEED_LO]) return offset;
        offset += 4;
        if (a.header[MeshKernelBridge.H_PALETTE_COUNT] != b.header[MeshKernelBridge.H_PALETTE_COUNT]) return offset;
        offset += 4;
        int packedLength = Math.min(a.packedStateLength, b.packedStateLength);
        for (int i = 0; i < packedLength; i++, offset += 4) if (a.packedStates[i] != b.packedStates[i]) return offset;
        if (a.packedStateLength != b.packedStateLength) return offset;
        int entityCount = Math.min(aEntities.size(), bEntities.size());
        for (int i = 0; i < entityCount; i++, offset += 12) {
            BlockEntity left = aEntities.get(i), right = bEntities.get(i);
            if (left != right) return offset;
            BlockPos leftPos = left.getBlockPos(), rightPos = right.getBlockPos();
            if (leftPos.getX() != rightPos.getX() || leftPos.getY() != rightPos.getY()
                    || leftPos.getZ() != rightPos.getZ()) return offset;
        }
        return aEntities.size() == bEntities.size() ? -1 : offset;
    }

    // --- states ---------------------------------------------------------------------------------

    private static boolean captureStates(MeshJobBuffers b, SectionCopy[] copies, int minX, int minY, int minZ,
            int sx, int sy, int sz, MeshModelTable table, boolean fast) {
        if (fast && MeshProfile.PROFILE != 2
                && capturePackedStates(b, copies, minX, minY, minZ, sx, sy, sz, table)) {
            return true;
        }
        int air = table.airId;
        int nonAir = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    SectionCopy copy = copies[RenderSectionRegion.index(minX, minY, minZ, sx + dx, sy + dy, sz + dz)];
                    int x0 = dx < 0 ? 14 : 0;
                    int x1 = dx > 0 ? 1 : 15;
                    int y0 = dy < 0 ? 14 : 0;
                    int y1 = dy > 0 ? 1 : 15;
                    int z0 = dz < 0 ? 14 : 0;
                    int z1 = dz > 0 ? 1 : 15;
                    PalettedContainer<BlockState> states = null;
                    if (copy != null) {
                        MeshKernelAccess.Copy copyAccess = (MeshKernelAccess.Copy) (Object) copy;
                        if (copyAccess.gaius$debug()) {
                            return false;
                        }
                        states = copyAccess.gaius$states();
                    }
                    if (states == null) {
                        for (int y = y0; y <= y1; y++) {
                            for (int z = z0; z <= z1; z++) {
                                int at = MeshJobBuffers.regionIndex(dx * 16 + x0, dy * 16 + y, dz * 16 + z);
                                for (int x = x0; x <= x1; x++) {
                                    b.setId(at++, air);
                                }
                            }
                        }
                        continue;
                    }
                    MeshKernelAccess.Paletted paletted = (MeshKernelAccess.Paletted) (Object) states;
                    BitStorage storage = paletted.gaius$storage();
                    Palette<?> palette = paletted.gaius$palette();
                    long[] raw = fast && storage.getSize() == 4096 ? storage.getRaw() : null;
                    int bits = raw == null ? 0 : storage.getBits();
                    int[] ids = null;
                    int paletteSize = 0;
                    if (!(palette instanceof GlobalPalette) && fast) {
                        paletteSize = palette.getSize();
                        ids = b.paletteIds(paletteSize);
                        for (int i = 0; i < paletteSize; i++) {
                            Object value = palette.valueFor(i);
                            int id = value instanceof BlockState state ? Block.BLOCK_STATE_REGISTRY.getId(state) : -1;
                            ids[i] = id < 0 || id >= table.stateCount ? air : id;
                        }
                    }
                    boolean center = dx == 0 && dy == 0 && dz == 0;
                    if (fast && storage.getSize() == 4096 && raw != null) {
                        int valuesPerLong = bits == 0 ? 1 : 64 / bits;
                        long mask = bits == 0 ? 0L : (1L << bits) - 1L;
                        for (int y = y0; y <= y1; y++) {
                            for (int z = z0; z <= z1; z++) {
                                int at = MeshJobBuffers.regionIndex(dx * 16 + x0, dy * 16 + y, dz * 16 + z);
                                int row = (y << 8) | (z << 4);
                                int index = row | x0;
                                int wordIndex = bits == 0 ? 0 : index / valuesPerLong;
                                int shift = bits == 0 ? 0 : (index - wordIndex * valuesPerLong) * bits;
                                int slotsLeft = bits == 0 ? 0 : valuesPerLong - index % valuesPerLong;
                                long word = bits == 0 ? 0L : raw[wordIndex];
                                for (int x = x0; x <= x1; x++) {
                                    int value = bits == 0 ? 0 : (int) ((word >>> shift) & mask);
                                    int id = map(value, ids, paletteSize, air, table.stateCount);
                                    b.setId(at++, id);
                                    if (center && !table.isAir(id)) {
                                        nonAir++;
                                    }
                                    if (bits != 0 && x < x1) {
                                        if (--slotsLeft == 0) {
                                            word = raw[++wordIndex];
                                            shift = 0;
                                            slotsLeft = valuesPerLong;
                                        } else {
                                            shift += bits;
                                        }
                                    }
                                }
                            }
                        }
                        continue;
                    }
                    if (center && storage.getSize() == 4096) {
                        int[] unpacked = b.sectionScratch;
                        storage.unpack(unpacked);
                        int index = 0;
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                int at = MeshJobBuffers.regionIndex(0, y, z);
                                for (int x = 0; x < 16; x++) {
                                    int value = unpacked[index++];
                                    int id = fast ? map(value, ids, paletteSize, air, table.stateCount)
                                            : mapVanilla(value, palette, air, table.stateCount);
                                    b.setId(at++, id);
                                    if (!table.isAir(id)) {
                                        nonAir++;
                                    }
                                }
                            }
                        }
                        continue;
                    }
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            int at = MeshJobBuffers.regionIndex(dx * 16 + x0, dy * 16 + y, dz * 16 + z);
                            int row = (y << 8) | (z << 4);
                            for (int x = x0; x <= x1; x++) {
                                int value = raw != null ? packed(raw, bits, row | x) : storage.get(row | x);
                                int id = fast ? map(value, ids, paletteSize, air, table.stateCount)
                                        : mapVanilla(value, palette, air, table.stateCount);
                                b.setId(at++, id);
                                if (center && !table.isAir(id)) {
                                    nonAir++;
                                }
                            }
                        }
                    }
                }
            }
        }
        lastNonAir = nonAir;
        return true;
    }

    /** Preserves the earlier non-RD32 worker-side decoder path. */
    private static boolean capturePackedStates(MeshJobBuffers b, SectionCopy[] copies,
            int minX, int minY, int minZ, int sx, int sy, int sz, MeshModelTable table) {
        b.beginPackedStates();
        b.appendPackedState(table.airId);
        b.appendPackedState(27);
        int nonAir = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    SectionCopy copy = copies[RenderSectionRegion.index(minX, minY, minZ,
                            sx + dx, sy + dy, sz + dz)];
                    if (copy == null) {
                        b.appendPackedState(0);
                        b.appendPackedState(0);
                        b.appendPackedState(0);
                        continue;
                    }
                    MeshKernelAccess.Copy copyAccess = (MeshKernelAccess.Copy) (Object) copy;
                    if (copyAccess.gaius$debug()) {
                        b.packedStateLength = 0;
                        return false;
                    }
                    PalettedContainer<BlockState> states = copyAccess.gaius$states();
                    if (states == null) {
                        b.appendPackedState(0);
                        b.appendPackedState(0);
                        b.appendPackedState(0);
                        continue;
                    }
                    MeshKernelAccess.Paletted paletted = (MeshKernelAccess.Paletted) (Object) states;
                    BitStorage storage = paletted.gaius$storage();
                    if (storage.getSize() != 4096 || storage.getRaw() == null) {
                        b.packedStateLength = 0;
                        return false;
                    }
                    Palette<?> palette = paletted.gaius$palette();
                    int paletteSize = palette instanceof GlobalPalette ? -1 : palette.getSize();
                    b.appendPackedState(storage.getBits());
                    b.appendPackedState(paletteSize);
                    long[] raw = storage.getRaw();
                    b.appendPackedState(raw.length);
                    int[] ids = null;
                    if (paletteSize > 0) {
                        ids = b.paletteIds(paletteSize);
                        for (int i = 0; i < paletteSize; i++) {
                            Object value = palette.valueFor(i);
                            int id = value instanceof BlockState state ? Block.BLOCK_STATE_REGISTRY.getId(state) : -1;
                            ids[i] = id < 0 || id >= table.stateCount ? table.airId : id;
                            b.appendPackedState(ids[i]);
                        }
                    }
                    for (long word : raw) {
                        b.appendPackedState((int) word);
                        b.appendPackedState((int) (word >>> 32));
                    }
                    if (dx == 0 && dy == 0 && dz == 0) {
                        for (int i = 0; i < 4096; i++) {
                            int value = packed(raw, storage.getBits(), i);
                            int id = ids == null
                                    ? (value >= 0 && value < table.stateCount ? value : table.airId)
                                    : (value < paletteSize ? ids[value] : table.airId);
                            if (!table.isAir(id)) {
                                nonAir++;
                            }
                        }
                    }
                }
            }
        }
        lastNonAir = nonAir;
        return true;
    }

    /** Reads one packed entry without the virtual BitStorage.get call on the hot neighbour path. */
    private static int packed(long[] raw, int bits, int index) {
        if (bits == 0) {
            return 0;
        }
        int valuesPerWord = 64 / bits;
        int word = index / valuesPerWord;
        int shift = (index - word * valuesPerWord) * bits;
        return (int) ((raw[word] >>> shift) & ((1L << bits) - 1L));
    }

    private static int map(int value, int[] ids, int paletteSize, int air, int stateCount) {
        if (ids == null) {
            return value >= 0 && value < stateCount ? value : air;
        }
        return value >= 0 && value < paletteSize ? ids[value] : air;
    }

    private static int mapVanilla(int value, Palette<?> palette, int air, int stateCount) {
        if (palette instanceof GlobalPalette) {
            return value >= 0 && value < stateCount ? value : air;
        }
        Object state = palette.valueFor(value);
        int id = state instanceof BlockState blockState ? Block.BLOCK_STATE_REGISTRY.getId(blockState) : -1;
        return id >= 0 && id < stateCount ? id : air;
    }

    // --- light ----------------------------------------------------------------------------------

    private static void captureLight(MeshJobBuffers b, LevelLightEngine engine, int sx, int sy, int sz,
            boolean fast) {
        if (!fast) {
            captureVanillaLight(b, engine, sx, sy, sz);
            return;
        }
        LightEngine<?, ?> blockEngine = GaiusLightColumnAccess.engine(engine, LightLayer.BLOCK);
        LightEngine<?, ?> skyEngine = GaiusLightColumnAccess.engine(engine, LightLayer.SKY);
        LayerLightEventListener blockLight = engine.getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener skyLight = engine.getLayerListener(LightLayer.SKY);
        BlockPos.MutableBlockPos cursor = CaptureScratch.POSITION;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    DataLayer layer = blockEngine == null ? null : GaiusLightColumnAccess.visibleLayer(blockEngine,
                            SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                    DataLayer skyLayer = skyEngine == null ? null : GaiusLightColumnAccess.visibleLayer(skyEngine,
                            SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                    boolean homogeneous = layer != null && layer.isDefinitelyHomogenous();
                    int uniform = homogeneous ? layer.get(0, 0, 0) & 15 : 0;
                    byte[] raw = layer != null && !homogeneous ? layer.getData() : null;
                    boolean skyHomogeneous = skyLayer != null && skyLayer.isDefinitelyHomogenous();
                    int skyUniform = skyHomogeneous ? skyLayer.get(0, 0, 0) & 15 : 0;
                    byte[] skyRaw = skyLayer != null && !skyHomogeneous ? skyLayer.getData() : null;
                    boolean skyFallback = skyEngine == null || skyLayer == null;
                    boolean blockFallback = blockEngine == null;
                    int x0 = dx < 0 ? -1 : dx > 0 ? 16 : 0;
                    int x1 = dx < 0 ? -1 : dx > 0 ? 16 : 15;
                    int y0 = dy < 0 ? -1 : dy > 0 ? 16 : 0;
                    int y1 = dy < 0 ? -1 : dy > 0 ? 16 : 15;
                    int z0 = dz < 0 ? -1 : dz > 0 ? 16 : 0;
                    int z1 = dz < 0 ? -1 : dz > 0 ? 16 : 15;
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            int at = MeshJobBuffers.regionIndex(x0, y, z);
                            for (int x = x0; x <= x1; x++) {
                                if (skyFallback || blockFallback) {
                                    cursor.set((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
                                }
                                int sky;
                                if (skyFallback) {
                                    // Missing sky data keeps vanilla's top-section default and upward fallback.
                                    sky = skyLight.getLightValue(cursor) & 15;
                                } else if (skyHomogeneous) {
                                    sky = skyUniform;
                                } else {
                                    int localIndex = ((y - dy * 16) << 8) | ((z - dz * 16) << 4) | (x - dx * 16);
                                    int packed = skyRaw[localIndex >> 1] & 0xFF;
                                    sky = (packed >> ((localIndex & 1) << 2)) & 15;
                                }
                                int block;
                                if (blockFallback) {
                                    block = blockLight.getLightValue(cursor) & 15;
                                } else if (layer == null) {
                                    block = 0;
                                } else if (homogeneous) {
                                    block = uniform;
                                } else {
                                    int localIndex = ((y - dy * 16) << 8) | ((z - dz * 16) << 4) | (x - dx * 16);
                                    int packed = raw[localIndex >> 1] & 0xFF;
                                    block = (packed >> ((localIndex & 1) << 2)) & 15;
                                }
                                b.light[at++] = (byte) ((sky << 4) | block);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void captureVanillaLight(MeshJobBuffers b, LevelLightEngine engine, int sx, int sy, int sz) {
        LayerLightEventListener blockLight = engine.getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener skyLight = engine.getLayerListener(LightLayer.SKY);
        BlockPos.MutableBlockPos cursor = CaptureScratch.POSITION;
        for (int y = -1; y <= 16; y++) {
            for (int z = -1; z <= 16; z++) {
                int out = MeshJobBuffers.regionIndex(-1, y, z);
                for (int x = -1; x <= 16; x++) {
                    cursor.set((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
                    b.light[out++] = (byte) (((skyLight.getLightValue(cursor) & 15) << 4)
                            | (blockLight.getLightValue(cursor) & 15));
                }
            }
        }
    }

    // --- biomes ---------------------------------------------------------------------------------

    private static boolean captureBiomes(MeshJobBuffers b, ClientLevel level, int sx, int sy, int sz,
            int blend) {
        BiomeManager manager = level.getBiomeManager();
        long seed = ((MeshBiomeAccess) (Object) manager).gaius$biomeZoomSeed();
        b.header[MeshKernelBridge.H_SEED_HI] = (int) (seed >>> 32);
        b.header[MeshKernelBridge.H_SEED_LO] = (int) seed;
        int qx0 = (sx << 2) - 1;
        int qy0 = (sy << 2) - 1;
        int qz0 = (sz << 2) - 1;
        Biome swamp = null;
        int index = 0;
        for (int qy = 0; qy < MeshJobBuffers.QUARTS; qy++) {
            for (int qz = 0; qz < MeshJobBuffers.QUARTS; qz++) {
                for (int qx = 0; qx < MeshJobBuffers.QUARTS; qx++) {
                    Holder<Biome> holder = manager.getNoiseBiomeAtQuart(qx0 + qx, qy0 + qy, qz0 + qz);
                    int slot = b.biomeIndex(holder);
                    if (slot < 0) {
                        Biome biome = holder.value();
                        boolean isSwamp = biome.getSpecialEffects().grassColorModifier()
                                == BiomeSpecialEffects.GrassColorModifier.SWAMP;
                        slot = b.addBiome(holder, biome.getGrassColor(0.0, 0.0),
                                isSwamp ? MeshJobBuffers.GRASS_MODIFIER_SWAMP : MeshJobBuffers.GRASS_MODIFIER_NONE,
                                biome.getFoliageColor(), biome.getDryFoliageColor(), biome.getWaterColor());
                        if (slot < 0) {
                            return false;
                        }
                        if (isSwamp && swamp == null) {
                            swamp = biome;
                        }
                    }
                    b.quarts[index++] = (byte) slot;
                }
            }
        }
        if (swamp != null) {
            int baseX = sx << 4;
            int baseZ = sz << 4;
            for (int z = -blend; z < 16 + blend; z++) {
                for (int x = -blend; x < 16 + blend; x++) {
                    if ((swamp.getGrassColor(baseX + x, baseZ + z) & 0xFFFFFF) == SWAMP_BELOW_COLOR) {
                        b.setSwamp(x, z);
                    }
                }
            }
        }
        b.header[MeshKernelBridge.H_PALETTE_COUNT] = b.biomeCount;
        return true;
    }

    // --- block entities -------------------------------------------------------------------------

    private static void collectBlockEntities(MeshJobBuffers b, RenderSectionRegion region, int sx, int sy, int sz,
            MeshModelTable table, List<BlockEntity> out, boolean fast) {
        MeshKernelAccess.Region access = (MeshKernelAccess.Region) (Object) region;
        SectionCopy center = access.gaius$sections()[RenderSectionRegion.index(
                access.gaius$minSectionX(), access.gaius$minSectionY(), access.gaius$minSectionZ(), sx, sy, sz)];
        if (fast && center != null) {
            MeshKernelAccess.Copy copy = (MeshKernelAccess.Copy) (Object) center;
            Map<BlockPos, BlockEntity> entities = copy.gaius$blockEntities();
            if (entities != null && !entities.isEmpty()) {
                int first = out.size();
                for (Map.Entry<BlockPos, BlockEntity> entry : entities.entrySet()) {
                    BlockPos p = entry.getKey();
                    int px = p.getX(), py = p.getY(), pz = p.getZ();
                    if ((px >> 4) == sx && (py >> 4) == sy && (pz >> 4) == sz) {
                        int x = px - (sx << 4);
                        int y = py - (sy << 4);
                        int z = pz - (sz << 4);
                        if (table.hasBlockEntity(b.id(MeshJobBuffers.regionIndex(x, y, z)))) {
                            out.add(entry.getValue());
                        }
                    }
                }
                out.subList(first, out.size()).sort(BLOCK_ENTITY_ORDER);
            }
            return;
        }
        BlockPos.MutableBlockPos pos = CaptureScratch.POSITION;
        int baseX = sx << 4;
        int baseY = sy << 4;
        int baseZ = sz << 4;
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                int at = MeshJobBuffers.regionIndex(0, y, z);
                for (int x = 0; x < 16; x++) {
                    if (!table.hasBlockEntity(b.id(at + x))) {
                        continue;
                    }
                    pos.set(baseX + x, baseY + y, baseZ + z);
                    BlockEntity entity = region.getBlockEntity(pos);
                    if (entity != null) {
                        out.add(entity);
                    }
                }
            }
        }
    }
}
