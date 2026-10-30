package dev.gaius.browser.kernel.mesh;

import java.util.List;
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
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
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
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int SWAMP_BELOW_COLOR = 0x4C763C;

    private SectionSnapshot() {
    }

    /** Non-air blocks of the center section in the last capture. */
    static int lastNonAir;

    /**
     * Fills {@code b} for the section {@code node}; returns false when the region cannot be
     * expressed (debug world, unexpected layout), so the caller keeps the vanilla compiler.
     */
    static boolean capture(MeshJobBuffers b, RenderSectionRegion region, long node, MeshModelTable table,
            Vec3 camera, List<BlockEntity> blockEntities, int blend) {
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
        if (!captureStates(b, copies, minX, minY, minZ, sx, sy, sz, table)) {
            return false;
        }
        captureLight(b, level.getLightEngine(), sx, sy, sz);
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
        collectBlockEntities(b, region, sx, sy, sz, table, blockEntities);
        return true;
    }

    // --- states ---------------------------------------------------------------------------------

    private static boolean captureStates(MeshJobBuffers b, SectionCopy[] copies, int minX, int minY, int minZ,
            int sx, int sy, int sz, MeshModelTable table) {
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
                    int[] ids = null;
                    int paletteSize = 0;
                    if (!(palette instanceof GlobalPalette)) {
                        paletteSize = palette.getSize();
                        ids = b.paletteIds(paletteSize);
                        for (int i = 0; i < paletteSize; i++) {
                            Object value = palette.valueFor(i);
                            int id = value instanceof BlockState state ? Block.BLOCK_STATE_REGISTRY.getId(state) : -1;
                            ids[i] = id < 0 || id >= table.stateCount ? air : id;
                        }
                    }
                    boolean center = dx == 0 && dy == 0 && dz == 0;
                    if (center && storage.getSize() == 4096) {
                        int[] unpacked = b.sectionScratch;
                        storage.unpack(unpacked);
                        int index = 0;
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                int at = MeshJobBuffers.regionIndex(0, y, z);
                                for (int x = 0; x < 16; x++) {
                                    int id = map(unpacked[index++], ids, paletteSize, air, table.stateCount);
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
                                int id = map(storage.get(row | x), ids, paletteSize, air, table.stateCount);
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

    private static int map(int value, int[] ids, int paletteSize, int air, int stateCount) {
        if (ids == null) {
            return value >= 0 && value < stateCount ? value : air;
        }
        return value >= 0 && value < paletteSize ? ids[value] : air;
    }

    // --- light ----------------------------------------------------------------------------------

    private static void captureLight(MeshJobBuffers b, LevelLightEngine engine, int sx, int sy, int sz) {
        LayerLightEventListener blockLight = engine.getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener skyLight = engine.getLayerListener(LightLayer.SKY);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = -1; y <= 16; y++) {
            for (int z = -1; z <= 16; z++) {
                int out = MeshJobBuffers.regionIndex(-1, y, z);
                for (int x = -1; x <= 16; x++) {
                    cursor.set((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
                    // Match RenderSectionRegion.getBrightness: getDataLayerData may return a
                    // queued packet layer that is not yet in the renderer's visible light map.
                    // The listener also implements vanilla's missing-sky-layer lookup.
                    int block = blockLight.getLightValue(cursor) & 15;
                    int sky = skyLight.getLightValue(cursor) & 15;
                    b.light[out++] = (byte) ((sky << 4) | block);
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
            MeshModelTable table, List<BlockEntity> out) {
        BlockPos.MutableBlockPos pos = null;
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
                    if (pos == null) {
                        pos = new BlockPos.MutableBlockPos();
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
