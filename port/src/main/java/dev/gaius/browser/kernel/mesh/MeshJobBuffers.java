package dev.gaius.browser.kernel.mesh;

/**
 * Reused flat arrays of one section job (the SECTION JOB of
 * {@code port/native/crates/gaius-mesher/src/job.rs}): 20^3 state ids, 20^3 packed light, the
 * 6^3 biome quarts with their palette and the swamp column mask. The main thread fills them for
 * one section at a time and {@link MeshKernelBridge#submit} copies them out, so nothing here is
 * allocated per section.
 */
public final class MeshJobBuffers {
    public static final int REGION = 20;
    public static final int MARGIN = 2;
    public static final int VOLUME = REGION * REGION * REGION;
    public static final int QUARTS = 6;
    public static final int QUART_VOLUME = QUARTS * QUARTS * QUARTS;
    public static final int MAX_BIOMES = 256;
    public static final int PALETTE_INTS = 5;

    public static final int GRASS_MODIFIER_NONE = 0;
    public static final int GRASS_MODIFIER_DARK_FOREST = 1;
    public static final int GRASS_MODIFIER_SWAMP = 2;
    /** Swamp mask columns per axis: the section plus the two columns a blend radius reaches. */
    public static final int SWAMP_SPAN = 20;

    public final int[] header = new int[MeshKernelBridge.HEADER_LENGTH];
    public final short[] ids16 = new short[VOLUME];
    public final int[] ids32 = new int[VOLUME];
    public final byte[] light = new byte[VOLUME];
    public final byte[] quarts = new byte[QUART_VOLUME];
    public final int[] palette = new int[MAX_BIOMES * PALETTE_INTS];
    /** Bit (z + 2) * 20 + (x + 2) for the columns x, z in -2..17 (the BIOME_BLEND layout). */
    public final byte[] swamp = new byte[SWAMP_SPAN * SWAMP_SPAN / 8];
    /** cardinal down, up, north, south, west, east, then camera x, y, z (section relative). */
    public final float[] floats = new float[9];
    /** Biome holders of the palette, compared by identity. */
    public final Object[] biomes = new Object[MAX_BIOMES];
    public int biomeCount;
    public boolean wide;
    /** Scratch for one unpacked 16^3 section. */
    public final int[] sectionScratch = new int[4096];
    /** Scratch for palette index to global id. */
    public int[] paletteIds = new int[256];

    /** Index of a section-relative position (each coordinate in -2..17). */
    public static int regionIndex(int x, int y, int z) {
        return ((y + MARGIN) * REGION + (z + MARGIN)) * REGION + (x + MARGIN);
    }

    /** Resets the biome palette and the swamp mask for a new job. */
    public void beginJob(boolean wideIds) {
        wide = wideIds;
        for (int i = 0; i < biomeCount; i++) {
            biomes[i] = null;
        }
        biomeCount = 0;
        for (int i = 0; i < swamp.length; i++) {
            swamp[i] = 0;
        }
    }

    public void setId(int index, int id) {
        if (wide) {
            ids32[index] = id;
        } else {
            ids16[index] = (short) id;
        }
    }

    public int id(int index) {
        return wide ? ids32[index] : ids16[index] & 0xFFFF;
    }

    /**
     * Palette slot of {@code biome}, or -1 when it is new and {@code colors} must be stored
     * with {@link #addBiome}. Linear by identity: a section touches a handful of biomes.
     */
    public int biomeIndex(Object biome) {
        for (int i = 0; i < biomeCount; i++) {
            if (biomes[i] == biome) {
                return i;
            }
        }
        return -1;
    }

    /** Adds a biome with its colors; returns its slot, or -1 when the palette is full. */
    public int addBiome(Object biome, int grass, int grassModifier, int foliage, int dryFoliage, int water) {
        if (biomeCount >= MAX_BIOMES) {
            return -1;
        }
        int slot = biomeCount++;
        biomes[slot] = biome;
        int at = slot * PALETTE_INTS;
        palette[at] = grass;
        palette[at + 1] = grassModifier;
        palette[at + 2] = foliage;
        palette[at + 3] = dryFoliage;
        palette[at + 4] = water;
        return slot;
    }

    /**
     * Marks section-relative column (x, z), each in -2..17, as the swamp modifier's "below -0.1"
     * color.
     */
    public void setSwamp(int x, int z) {
        int bit = (z + MARGIN) * SWAMP_SPAN + (x + MARGIN);
        swamp[bit >> 3] |= (byte) (1 << (bit & 7));
    }

    public int[] paletteIds(int size) {
        if (paletteIds.length < size) {
            paletteIds = new int[Math.max(size, paletteIds.length * 2)];
        }
        return paletteIds;
    }
}
