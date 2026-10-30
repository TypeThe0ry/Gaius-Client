package net.minecraft.world.level.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;

/**
 * Gaius light kernel bridge into the vanilla light storage. It lives in the vanilla package so it
 * can use the protected storage and queue API that the kernel results are written through
 * ({@code storingLightForSection}, {@code getDataLayerToWrite}, {@code setStoredLevel},
 * {@code enqueueIncrease/Decrease}); everything else stays in
 * {@code dev.gaius.browser.kernel.light.BrowserLightKernel}.
 *
 * <p>Must only run on the light engine's own task queue (a ThreadedLevelLightEngine task), like
 * every vanilla caller of these methods.
 */
public final class GaiusLightColumnAccess {
    private static final int SKY_INCREASE = 0;
    private static final int BLOCK_INCREASE = 1;
    private static final int SKY_DECREASE = 2;

    private GaiusLightColumnAccess() {
    }

    /** The engine of one layer, or null when the level has none (no sky light). */
    public static LightEngine<?, ?> engine(LevelLightEngine level, LightLayer layer) {
        LayerLightEventListener listener = level.getLayerListener(layer);
        return listener instanceof LightEngine<?, ?> engine ? engine : null;
    }

    public static boolean storing(LightEngine<?, ?> engine, long sectionKey) {
        return engine.storage.storingLightForSection(sectionKey);
    }

    /**
     * The stored layer the engine works on, or null when the section stores nothing. Read it with
     * {@link #copyLayer}: {@code getData()} would allocate the array of a homogeneous layer.
     */
    public static DataLayer storedLayer(LightEngine<?, ?> engine, long sectionKey) {
        return engine.storage.getDataLayer(sectionKey, true);
    }

    /** Copies a layer's 2048 nibble bytes into {@code out} without materializing it. */
    public static void copyLayer(DataLayer layer, byte[] out, int offset) {
        if (layer.isDefinitelyHomogenous()) {
            int level = layer.get(0, 0, 0) & 15;
            byte packed = (byte) (level | level << 4);
            for (int i = 0; i < DataLayer.SIZE; i++) {
                out[offset + i] = packed;
            }
        } else {
            System.arraycopy(layer.getData(), 0, out, offset, DataLayer.SIZE);
        }
    }

    /**
     * Copies the ring slice of a neighbour's layer into {@code out} (128 bytes): the levels of the
     * cells that touch the column, cell {@code (y << 4) | along} packed like a {@code DataLayer}.
     * {@code side} is where the neighbour lies: 0 north (its z 15 row), 1 south (z 0), 2 west
     * (its x 15 column), 3 east (x 0); along is x for north and south, z for west and east.
     */
    public static void copyRing(DataLayer layer, int side, byte[] out, int offset) {
        if (layer.isDefinitelyHomogenous()) {
            int level = layer.get(0, 0, 0) & 15;
            byte packed = (byte) (level | level << 4);
            for (int i = 0; i < 128; i++) {
                out[offset + i] = packed;
            }
            return;
        }
        byte[] data = layer.getData();
        for (int i = 0; i < 128; i++) {
            out[offset + i] = 0;
        }
        for (int y = 0; y < 16; y++) {
            for (int along = 0; along < 16; along++) {
                int x = side == 2 ? 15 : side == 3 ? 0 : along;
                int z = side == 0 ? 15 : side == 1 ? 0 : along;
                int index = y << 8 | z << 4 | x;
                int level = data[index >> 1] >> ((index & 1) << 2) & 15;
                int ring = y << 4 | along;
                out[offset + (ring >> 1)] |= (byte) (level << ((ring & 1) << 2));
            }
        }
    }

    /** {@code SkyLightSectionStorage.getBottomSectionY()} ({@code currentLowestY}). */
    public static int skyBottomSection(LightEngine<?, ?> sky) {
        return ((SkyLightSectionStorage) sky.storage).getBottomSectionY();
    }

    /** {@code propagateLightSources} turns the column's light on before lighting it. */
    public static void enableColumn(LightEngine<?, ?> engine, int chunkX, int chunkZ) {
        engine.storage.setLightEnabled(SectionPos.getZeroNode(chunkX, chunkZ), true);
    }

    public static LightChunk chunkForLighting(LightEngine<?, ?> engine, int chunkX, int chunkZ) {
        return engine.chunkSource.getChunkForLighting(chunkX, chunkZ);
    }

    /**
     * Writes a kernel layer into the stored section. {@code before} is the copy the job carried
     * (null when the section was empty then): if the section changed while the job ran (a
     * neighbour lit into it), the two are merged by the per-cell maximum, which stays a valid
     * lighting because both are closed under propagation. Returns false when the section no
     * longer stores light.
     */
    public static boolean writeLayer(LightEngine<?, ?> engine, long sectionKey, byte[] result, int offset,
            byte[] before) {
        DataLayer layer = engine.storage.getDataLayerToWrite(sectionKey);
        if (layer == null) {
            return false;
        }
        byte[] data = layer.getData();
        if (before != null && sameBytes(data, before)) {
            System.arraycopy(result, offset, data, 0, DataLayer.SIZE);
        } else {
            for (int i = 0; i < DataLayer.SIZE; i++) {
                int a = data[i] & 0xff;
                int b = result[offset + i] & 0xff;
                int low = Math.max(a & 15, b & 15);
                int high = Math.max(a >>> 4, b >>> 4);
                data[i] = (byte) (low | high << 4);
            }
        }
        engine.storage.markSectionAndNeighborsAsAffected(sectionKey);
        return true;
    }

    private static boolean sameBytes(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies one outgoing record of the kernel: light that vanilla's propagateIncrease or
     * propagateDecrease carries from the column into a neighbour cell, run on the live stored
     * level of that cell exactly as vanilla's loop body does.
     *
     * @param kind 0 sky increase, 1 block increase, 2 sky decrease, 3 block decrease
     * @param direction the Direction from the column into the neighbour cell
     * @param level increase: the new level; decrease: the old level of the column cell
     * @param entry increase: the vanilla queue entry to enqueue for the neighbour cell
     * @param emptySections sky only: countEmptySectionsBelowIfAtBorder of the column cell
     */
    public static void applyOutgoing(LightEngine<?, ?> engine, int kind, long pos, Direction direction, int level,
            int entry, int emptySections) {
        long sectionKey = SectionPos.blockToSection(pos);
        if (!engine.storage.storingLightForSection(sectionKey)) {
            return;
        }
        int stored = engine.storage.getStoredLevel(pos);
        boolean sky = kind == SKY_INCREASE || kind == SKY_DECREASE;
        if (kind == SKY_INCREASE || kind == BLOCK_INCREASE) {
            if (level <= stored) {
                return;
            }
            engine.storage.setStoredLevel(pos, level);
            if (level > 1) {
                engine.enqueueIncrease(pos, entry & 0xffffL);
            }
            if (sky && emptySections > 0) {
                propagateFromEmptySections(engine, pos, direction, level, true, emptySections);
            }
            return;
        }
        if (stored == 0) {
            return;
        }
        Direction back = direction.getOpposite();
        if (stored <= level - 1) {
            if (sky) {
                engine.storage.setStoredLevel(pos, 0);
                engine.enqueueDecrease(pos, LightEngine.QueueEntry.decreaseSkipOneDirection(stored, back));
                if (emptySections > 0) {
                    propagateFromEmptySections(engine, pos, direction, stored, false, emptySections);
                }
                return;
            }
            BlockState state = engine.getState(BlockPos.of(pos));
            int emission = state.getLightEmission();
            if (emission <= 0 || !engine.storage.lightOnInSection(sectionKey)) {
                emission = 0;
            }
            engine.storage.setStoredLevel(pos, 0);
            if (emission < stored) {
                engine.enqueueDecrease(pos, LightEngine.QueueEntry.decreaseSkipOneDirection(stored, back));
            }
            if (emission > 0) {
                engine.enqueueIncrease(pos,
                        LightEngine.QueueEntry.increaseLightFromEmission(emission, LightEngine.isEmptyShape(state)));
            }
        } else {
            engine.enqueueIncrease(pos, LightEngine.QueueEntry.increaseOnlyOneDirection(stored, false, back));
        }
    }

    /**
     * Lets vanilla carry the stored light of one face of a section into the adjacent section:
     * every cell of the face with a level above 1 is queued as an increase towards
     * {@code outward} only, which {@code propagateIncrease} runs with the live states and levels
     * of both cells (the from-shape is read, not assumed). Used when the adjacent section only
     * started storing light while a kernel job ran, so neither the job nor vanilla carried the
     * column's new light into it.
     */
    public static void pushFace(LightEngine<?, ?> engine, long sectionKey, Direction outward) {
        if (!engine.storage.storingLightForSection(sectionKey)) {
            return;
        }
        int baseX = SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey));
        int baseY = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey));
        int baseZ = SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey));
        int stepX = outward.getStepX();
        int stepY = outward.getStepY();
        int stepZ = outward.getStepZ();
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                int x = stepX < 0 ? baseX : stepX > 0 ? baseX + 15 : baseX + a;
                int y = stepY < 0 ? baseY : stepY > 0 ? baseY + 15 : baseY + (stepX != 0 ? a : b);
                int z = stepZ < 0 ? baseZ : stepZ > 0 ? baseZ + 15 : baseZ + b;
                long pos = BlockPos.asLong(x, y, z);
                int level = engine.storage.getStoredLevel(pos);
                if (level > 1) {
                    engine.enqueueIncrease(pos, LightEngine.QueueEntry.increaseOnlyOneDirection(level, false, outward));
                }
            }
        }
    }

    /**
     * {@code SkyLightEngine.propagateFromEmptySections} for a neighbour cell entered across the
     * chunk border (so the section edge is always crossed).
     */
    private static void propagateFromEmptySections(LightEngine<?, ?> engine, long pos, Direction direction,
            int level, boolean increase, int emptySections) {
        int x = BlockPos.getX(pos);
        int z = BlockPos.getZ(pos);
        int sectionX = SectionPos.blockToSectionCoord(x);
        int sectionZ = SectionPos.blockToSectionCoord(z);
        int top = SectionPos.blockToSectionCoord(BlockPos.getY(pos)) - 1;
        int bottom = top - emptySections + 1;
        for (int sectionY = top; sectionY >= bottom; sectionY--) {
            if (!engine.storage.storingLightForSection(SectionPos.asLong(sectionX, sectionY, sectionZ))) {
                continue;
            }
            int sectionBottom = SectionPos.sectionToBlockCoord(sectionY);
            for (int local = 15; local >= 0; local--) {
                long cell = BlockPos.asLong(x, sectionBottom + local, z);
                if (increase) {
                    engine.storage.setStoredLevel(cell, level);
                    if (level > 1) {
                        engine.enqueueIncrease(cell,
                                LightEngine.QueueEntry.increaseSkipOneDirection(level, true, direction.getOpposite()));
                    }
                } else {
                    engine.storage.setStoredLevel(cell, 0);
                    engine.enqueueDecrease(cell,
                            LightEngine.QueueEntry.decreaseSkipOneDirection(level, direction.getOpposite()));
                }
            }
        }
    }
}
