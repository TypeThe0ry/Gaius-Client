package dev.gaius.browser.kernel.mesh;

/**
 * Writer of the mesh kernel's MODEL TABLE, binary format version 1
 * ({@code port/native/crates/gaius-mesher/src/table.rs} documents every field; the constants and
 * record layouts here follow it). Profile independent: the 26.2 and 26.3 exporters
 * ({@code ModelTableExporter}) walk the block states and feed this writer plain numbers.
 *
 * <p>Geometry, UVs, face masks, model group runs and tint runs are deduplicated by value, so a
 * table holds each distinct quad position set once however many states share it; the exporter
 * deduplicates quads and model parts by identity before they reach the writer.</p>
 */
public final class MeshTableWriter {
    public static final int MAGIC = 0x42544d47; // "GMTB"
    public static final int VERSION = 1;
    public static final int PROFILE_1_21_11 = 0;
    public static final int PROFILE_26_2 = 1;
    public static final int PROFILE_26_3 = 2;

    public static final int HEADER_LEN = 64;
    public static final int STATE_LEN = 56;
    public static final int MAX_AIR_IDS = 8;
    public static final int MAX_TINTS_PER_STATE = 64;

    public static final int FACE_EMPTY = 0;
    public static final int FACE_FULL = 1;
    public static final int NO_FLUID_MODEL = 0xFFFF;
    public static final int NO_TINT = 0xFF;

    public static final int FLAG_AIR = 1;
    public static final int FLAG_SOLID_RENDER = 1 << 1;
    public static final int FLAG_RENDER_MODEL = 1 << 2;
    public static final int FLAG_LIGHT_PERMEABLE = 1 << 3;
    public static final int FLAG_COLLISION_FULL = 1 << 4;
    public static final int FLAG_EMISSIVE = 1 << 5;
    public static final int FLAG_SOLID = 1 << 6;
    public static final int FLAG_LEAVES = 1 << 7;
    public static final int FLAG_HALF_TRANSPARENT = 1 << 8;
    public static final int FLAG_BLOCKS_FLUID_FLOW = 1 << 9;
    public static final int FLAG_ICE = 1 << 10;
    public static final int FLAG_FLUID_FALLING = 1 << 11;
    public static final int FLAG_BARS_TAG = 1 << 12;
    public static final int FLAG_UNSUPPORTED = 1 << 13;
    public static final int FLAG_HAS_BLOCK_ENTITY = 1 << 14;

    public static final int SKIP_NONE = 0;
    public static final int SKIP_SAME_BLOCK = 1;
    public static final int SKIP_SAME_FLUID = 2;
    public static final int SKIP_BARS = 3;
    public static final int SKIP_SAME_BLOCK_VERTICAL = 4;
    public static final int SKIP_LEAVES = 5;

    public static final int TINT_CONSTANT = 0;
    public static final int TINT_GRASS = 1;
    public static final int TINT_GRASS_BELOW = 2;
    public static final int TINT_FOLIAGE = 3;
    public static final int TINT_DRY_FOLIAGE = 4;
    public static final int TINT_WATER = 5;

    public static final int OFFSET_NONE = 0;
    public static final int OFFSET_XZ = 1;
    public static final int OFFSET_XYZ = 2;

    /** One state record, filled by the exporter and written with {@link #state}. */
    public static final class StateRecord {
        public int flags;
        public int blockId;
        public int fluidModel = NO_FLUID_MODEL;
        public final int[] faces = new int[6];
        public float shade = 1.0f;
        public float fluidHeight;
        public float maxHorizontalOffset;
        public float maxVerticalOffset;
        public int firstGroup;
        public int groupCount;
        public int firstTint;
        public int tintCount;
        public int emission;
        public int skip;
        public int fluidGroup;
        public int sturdy;
        public int connTrue;
        public int connPresent;
        public int seedDx;
        public int seedDy;
        public int seedDz;
        public int offsetType;
        public boolean multipart;

        public void clear() {
            flags = 0;
            blockId = 0;
            fluidModel = NO_FLUID_MODEL;
            for (int i = 0; i < 6; i++) {
                faces[i] = FACE_EMPTY;
            }
            shade = 1.0f;
            fluidHeight = 0.0f;
            maxHorizontalOffset = 0.0f;
            maxVerticalOffset = 0.0f;
            firstGroup = 0;
            groupCount = 0;
            firstTint = 0;
            tintCount = 0;
            emission = 0;
            skip = SKIP_NONE;
            fluidGroup = 0;
            sturdy = 0;
            connTrue = 0;
            connPresent = 0;
            seedDx = 0;
            seedDy = 0;
            seedDz = 0;
            offsetType = OFFSET_NONE;
            multipart = false;
        }

        /** Drops everything the kernel would read from a model so the state stays valid. */
        public void markUnsupported() {
            flags |= FLAG_UNSUPPORTED;
            firstGroup = 0;
            groupCount = 0;
            multipart = false;
            firstTint = 0;
            tintCount = 0;
        }
    }

    private final int profile;
    private final int epoch;
    private final int stateCount;
    private final byte[] states;
    private final Bytes groups = new Bytes(1024);
    private final Bytes entries = new Bytes(1024);
    private final Bytes parts = new Bytes(4096);
    private final Bytes quadRefs = new Bytes(16384);
    private final Bytes quads = new Bytes(16384);
    private final Bytes geometry = new Bytes(32768);
    private final Bytes uvs = new Bytes(32768);
    private final Bytes tints = new Bytes(256);
    private final Bytes masks = new Bytes(1024);
    private final Bytes fluids = new Bytes(256);
    private final int[] airIds = new int[MAX_AIR_IDS];
    private int groupCount;
    private int entryCount;
    private int partCount;
    private int quadRefCount;
    private int quadCount;
    private int geometryCount;
    private int uvCount;
    private int tintCount;
    private int maskCount;
    private int fluidCount;
    private int airCount;
    private final IntKeyTable geometryKeys = new IntKeyTable();
    private final IntKeyTable uvKeys = new IntKeyTable();
    private final IntKeyTable maskKeys = new IntKeyTable();
    private final IntKeyTable groupRunKeys = new IntKeyTable();
    private final IntKeyTable tintRunKeys = new IntKeyTable();
    private final int[] keyScratch = new int[16];

    public MeshTableWriter(int profile, int epoch, int stateCount) {
        if (stateCount <= 0 || stateCount > (1 << 20)) {
            throw new IllegalArgumentException("state count out of range: " + stateCount);
        }
        this.profile = profile;
        this.epoch = epoch;
        this.stateCount = stateCount;
        this.states = new byte[stateCount * STATE_LEN];
    }

    public int epoch() {
        return epoch;
    }

    public int stateCount() {
        return stateCount;
    }

    public int quadCount() {
        return quadCount;
    }

    /** Quad corner positions {@code x0 y0 z0 .. x3 y3 z3}; returns the geometry id. */
    public int geometry(float[] xyz) {
        for (int i = 0; i < 12; i++) {
            keyScratch[i] = Float.floatToRawIntBits(xyz[i]);
        }
        int found = geometryKeys.find(keyScratch, 0, 12);
        if (found >= 0) {
            return found;
        }
        for (int i = 0; i < 12; i++) {
            geometry.putInt(keyScratch[i]);
        }
        geometryKeys.add(keyScratch, 0, 12, geometryCount);
        return geometryCount++;
    }

    /** Quad corner UVs {@code u0 v0 .. u3 v3}; returns the uv id. */
    public int uv(float[] uv) {
        for (int i = 0; i < 8; i++) {
            keyScratch[i] = Float.floatToRawIntBits(uv[i]);
        }
        int found = uvKeys.find(keyScratch, 0, 8);
        if (found >= 0) {
            return found;
        }
        for (int i = 0; i < 8; i++) {
            uvs.putInt(keyScratch[i]);
        }
        uvKeys.add(keyScratch, 0, 8, uvCount);
        return uvCount++;
    }

    /** A 16 x 16 face mask, {@code rows[v]} bit u; returns the face id (masks start at 2). */
    public int mask(int[] rows) {
        for (int i = 0; i < 16; i++) {
            keyScratch[i] = rows[i] & 0xFFFF;
        }
        int found = maskKeys.find(keyScratch, 0, 16);
        if (found >= 0) {
            return found + 2;
        }
        for (int i = 0; i < 16; i++) {
            masks.putShort(keyScratch[i]);
        }
        maskKeys.add(keyScratch, 0, 16, maskCount);
        return 2 + maskCount++;
    }

    /** Appends one quad; the caller deduplicates quads by identity. Returns the quad id. */
    public int quad(int geometryId, int uvId, int tintIndex, int direction, int shadeFace, int layer, int emission) {
        quads.putInt(geometryId);
        quads.putInt(uvId);
        quads.putShort(Math.max(-32768, Math.min(32767, tintIndex)));
        quads.putByte(direction);
        quads.putByte(shadeFace);
        quads.putByte(layer);
        quads.putByte(Math.max(0, Math.min(15, emission)));
        quads.putShort(0);
        return quadCount++;
    }

    /**
     * Appends one model part: {@code quadIds} holds the quads of getQuads(DOWN..EAST) then
     * getQuads(null) back to back, {@code ends[k]} the running total after list k. Returns the
     * part id.
     */
    public int part(int[] quadIds, int[] ends, boolean useAmbientOcclusion) {
        int total = ends[6];
        if (total > 0xFFFF) {
            throw new IllegalArgumentException("model part has too many quads: " + total);
        }
        int first = quadRefCount;
        for (int i = 0; i < total; i++) {
            quadRefs.putInt(quadIds[i]);
        }
        quadRefCount += total;
        parts.putInt(first);
        for (int k = 0; k < 7; k++) {
            parts.putShort(ends[k]);
        }
        parts.putByte(useAmbientOcclusion ? 1 : 0);
        parts.putByte(0);
        return partCount++;
    }

    /**
     * Model groups of one state, deduplicated as a run. {@code key} holds per group:
     * weighted (0/1), total weight, entry count, then (weight, part) per entry. Returns the first
     * group index of the run.
     */
    public int groupRun(int[] key, int length) {
        int found = groupRunKeys.find(key, 0, length);
        if (found >= 0) {
            return found;
        }
        int first = groupCount;
        int at = 0;
        while (at < length) {
            int weighted = key[at];
            int totalWeight = key[at + 1];
            int count = key[at + 2];
            groups.putInt(entryCount);
            groups.putShort(count);
            groups.putByte(weighted);
            groups.putByte(0);
            groups.putInt(totalWeight);
            at += 3;
            for (int i = 0; i < count; i++) {
                entries.putInt(key[at]);
                entries.putInt(key[at + 1]);
                at += 2;
            }
            entryCount += count;
            groupCount++;
        }
        groupRunKeys.add(key, 0, length, first);
        return first;
    }

    /** Tint sources of one state as (kind, argb) pairs, deduplicated as a run; returns the first. */
    public int tintRun(int[] kindArgb, int count) {
        int length = count * 2;
        int found = tintRunKeys.find(kindArgb, 0, length);
        if (found >= 0) {
            return found;
        }
        int first = tintCount;
        for (int i = 0; i < count; i++) {
            tints.putByte(kindArgb[2 * i]);
            tints.putByte(0);
            tints.putShort(0);
            tints.putInt(kindArgb[2 * i + 1]);
        }
        tintCount += count;
        tintRunKeys.add(kindArgb, 0, length, first);
        return first;
    }

    /**
     * One fluid model: layer, overlay flag, tint kind ({@link #NO_TINT} for none) and argb, then
     * {@code sprites} = still, flowing, overlay as u0 u1 v0 v1 each. Returns the model index.
     */
    public int fluidModel(int layer, boolean overlay, int tintKind, int tintArgb, float[] sprites) {
        fluids.putByte(layer);
        fluids.putByte(overlay ? 1 : 0);
        fluids.putByte(tintKind);
        fluids.putByte(0);
        fluids.putInt(tintArgb);
        for (int i = 0; i < 12; i++) {
            fluids.putFloat(sprites[i]);
        }
        return fluidCount++;
    }

    /** Records an air state id (the kernel's SIMD air skip); ignored past {@value #MAX_AIR_IDS}. */
    public void airId(int id) {
        if (airCount < MAX_AIR_IDS) {
            airIds[airCount++] = id;
        }
    }

    public int airIdCount() {
        return airCount;
    }

    public void state(int id, StateRecord s) {
        int at = id * STATE_LEN;
        byte[] o = states;
        putInt(o, at, s.flags);
        putShort(o, at + 4, s.blockId);
        putShort(o, at + 6, s.fluidModel);
        for (int k = 0; k < 6; k++) {
            putShort(o, at + 8 + 2 * k, s.faces[k]);
        }
        putInt(o, at + 20, Float.floatToRawIntBits(s.shade));
        putInt(o, at + 24, Float.floatToRawIntBits(s.fluidHeight));
        putInt(o, at + 28, Float.floatToRawIntBits(s.maxHorizontalOffset));
        putInt(o, at + 32, Float.floatToRawIntBits(s.maxVerticalOffset));
        putInt(o, at + 36, s.firstGroup);
        putInt(o, at + 40, s.firstTint);
        o[at + 44] = (byte) s.groupCount;
        o[at + 45] = (byte) s.tintCount;
        o[at + 46] = (byte) s.emission;
        o[at + 47] = (byte) s.skip;
        o[at + 48] = (byte) s.fluidGroup;
        o[at + 49] = (byte) s.sturdy;
        o[at + 50] = (byte) s.connTrue;
        o[at + 51] = (byte) s.connPresent;
        o[at + 52] = (byte) s.seedDx;
        o[at + 53] = (byte) s.seedDy;
        o[at + 54] = (byte) s.seedDz;
        o[at + 55] = (byte) ((s.offsetType & 3) | (s.multipart ? 0x80 : 0));
    }

    /** The finished table. */
    public byte[] encode() {
        int size = HEADER_LEN + pad(states.length) + pad(groups.length) + pad(entries.length)
                + pad(parts.length) + pad(quadRefs.length) + pad(quads.length) + pad(geometry.length)
                + pad(uvs.length) + pad(tints.length) + pad(masks.length) + pad(fluids.length)
                + pad(airCount * 4);
        byte[] out = new byte[size];
        putInt(out, 0, MAGIC);
        putShort(out, 4, VERSION);
        putShort(out, 6, profile);
        putInt(out, 8, epoch);
        int[] counts = {stateCount, groupCount, entryCount, partCount, quadRefCount, quadCount, geometryCount,
            uvCount, tintCount, maskCount, fluidCount, airCount, 0};
        for (int i = 0; i < counts.length; i++) {
            putInt(out, 12 + 4 * i, counts[i]);
        }
        int at = HEADER_LEN;
        at = copy(out, at, states, states.length);
        at = copy(out, at, groups.data, groups.length);
        at = copy(out, at, entries.data, entries.length);
        at = copy(out, at, parts.data, parts.length);
        at = copy(out, at, quadRefs.data, quadRefs.length);
        at = copy(out, at, quads.data, quads.length);
        at = copy(out, at, geometry.data, geometry.length);
        at = copy(out, at, uvs.data, uvs.length);
        at = copy(out, at, tints.data, tints.length);
        at = copy(out, at, masks.data, masks.length);
        at = copy(out, at, fluids.data, fluids.length);
        for (int i = 0; i < airCount; i++) {
            putInt(out, at + 4 * i, airIds[i]);
        }
        at += pad(airCount * 4);
        if (at != size) {
            throw new IllegalStateException("model table size mismatch: " + at + " != " + size);
        }
        return out;
    }

    private static int pad(int length) {
        return (length + 7) & ~7;
    }

    private static int copy(byte[] out, int at, byte[] source, int length) {
        System.arraycopy(source, 0, out, at, length);
        return at + pad(length);
    }

    private static void putInt(byte[] o, int at, int v) {
        o[at] = (byte) v;
        o[at + 1] = (byte) (v >>> 8);
        o[at + 2] = (byte) (v >>> 16);
        o[at + 3] = (byte) (v >>> 24);
    }

    private static void putShort(byte[] o, int at, int v) {
        o[at] = (byte) v;
        o[at + 1] = (byte) (v >>> 8);
    }

    /** Growable little-endian byte buffer. */
    static final class Bytes {
        byte[] data;
        int length;

        Bytes(int capacity) {
            data = new byte[capacity];
        }

        private void ensure(int extra) {
            if (length + extra > data.length) {
                int capacity = data.length;
                while (capacity < length + extra) {
                    capacity *= 2;
                }
                byte[] grown = new byte[capacity];
                System.arraycopy(data, 0, grown, 0, length);
                data = grown;
            }
        }

        void putByte(int v) {
            ensure(1);
            data[length++] = (byte) v;
        }

        void putShort(int v) {
            ensure(2);
            data[length++] = (byte) v;
            data[length++] = (byte) (v >>> 8);
        }

        void putInt(int v) {
            ensure(4);
            data[length++] = (byte) v;
            data[length++] = (byte) (v >>> 8);
            data[length++] = (byte) (v >>> 16);
            data[length++] = (byte) (v >>> 24);
        }

        void putFloat(float v) {
            putInt(Float.floatToRawIntBits(v));
        }
    }

    /**
     * Open-addressing map from variable-length int keys to int values. Keys are copied into one
     * pool; no per-entry objects.
     */
    static final class IntKeyTable {
        private int[] pool = new int[4096];
        private int poolLength;
        private int[] slotStart = new int[1024];
        private int[] slotLength = new int[1024];
        private int[] slotValue = new int[1024];
        private int[] slotHash = new int[1024];
        private boolean[] used = new boolean[1024];
        private int size;

        private static int hash(int[] key, int from, int length) {
            int h = 0x9E3779B9 ^ length;
            for (int i = 0; i < length; i++) {
                h = (h ^ key[from + i]) * 0x01000193;
                h ^= h >>> 15;
            }
            return h;
        }

        int find(int[] key, int from, int length) {
            int h = hash(key, from, length);
            int mask = used.length - 1;
            for (int slot = h & mask; used[slot]; slot = (slot + 1) & mask) {
                if (slotHash[slot] == h && slotLength[slot] == length && equal(slot, key, from, length)) {
                    return slotValue[slot];
                }
            }
            return -1;
        }

        private boolean equal(int slot, int[] key, int from, int length) {
            int start = slotStart[slot];
            for (int i = 0; i < length; i++) {
                if (pool[start + i] != key[from + i]) {
                    return false;
                }
            }
            return true;
        }

        void add(int[] key, int from, int length, int value) {
            if ((size + 1) * 2 > used.length) {
                grow();
            }
            if (poolLength + length > pool.length) {
                int capacity = pool.length;
                while (capacity < poolLength + length) {
                    capacity *= 2;
                }
                int[] grown = new int[capacity];
                System.arraycopy(pool, 0, grown, 0, poolLength);
                pool = grown;
            }
            System.arraycopy(key, from, pool, poolLength, length);
            insert(hash(key, from, length), poolLength, length, value);
            poolLength += length;
            size++;
        }

        private void insert(int h, int start, int length, int value) {
            int mask = used.length - 1;
            int slot = h & mask;
            while (used[slot]) {
                slot = (slot + 1) & mask;
            }
            used[slot] = true;
            slotHash[slot] = h;
            slotStart[slot] = start;
            slotLength[slot] = length;
            slotValue[slot] = value;
        }

        private void grow() {
            int[] oldStart = slotStart;
            int[] oldLength = slotLength;
            int[] oldValue = slotValue;
            int[] oldHash = slotHash;
            boolean[] oldUsed = used;
            int capacity = oldUsed.length * 2;
            slotStart = new int[capacity];
            slotLength = new int[capacity];
            slotValue = new int[capacity];
            slotHash = new int[capacity];
            used = new boolean[capacity];
            for (int i = 0; i < oldUsed.length; i++) {
                if (oldUsed[i]) {
                    insert(oldHash[i], oldStart[i], oldLength[i], oldValue[i]);
                }
            }
        }
    }
}
