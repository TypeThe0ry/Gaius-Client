package dev.gaius.browser.kernel.worldgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Binary writer for the worldgen kernel's generator IR (version 1). The format is specified in
 * {@code port/native/crates/gaius-worldgen/src/ir/mod.rs}; this class only knows its framing and
 * constants, the per-profile exporters fill the sections.
 */
public final class WorldgenIr {
    public static final int VERSION = 1;
    public static final int PROFILE_1_21_11 = 0;
    public static final int PROFILE_26_2 = 1;
    public static final int PROFILE_26_3 = 2;
    public static final int NONE = -1;

    public static final String SETTINGS = "SETT";
    public static final String STATES = "STAT";
    public static final String NOISES = "NOIS";
    public static final String DENSITY = "DENS";
    public static final String ROOTS = "ROOT";
    public static final String FLUID = "FLUI";
    public static final String VEINS = "VEIN";
    public static final String SURFACE = "SURF";
    public static final String BIOMES = "BIOM";

    // Density node ops.
    public static final int OP_CONST = 0;
    public static final int OP_NOISE = 1;
    public static final int OP_SHIFT_A = 2;
    public static final int OP_SHIFT_B = 3;
    public static final int OP_SHIFT = 4;
    public static final int OP_OLD_BLENDED_NOISE = 5;
    public static final int OP_END_ISLANDS = 6;
    public static final int OP_GRADIENT = 7;
    public static final int OP_Y_CLAMPED_GRADIENT = 8;
    public static final int OP_DISTANCE_TO_POINT = 9;
    public static final int OP_BLEND_ALPHA = 10;
    public static final int OP_BLEND_OFFSET = 11;
    public static final int OP_BEARDIFIER = 12;
    public static final int OP_UNARY = 13;
    public static final int OP_BINARY = 14;
    public static final int OP_MUL_OR_ADD = 15;
    public static final int OP_POW = 16;
    public static final int OP_CLAMP = 17;
    public static final int OP_LERP = 18;
    public static final int OP_RANGE_CHOICE = 19;
    public static final int OP_INTERVAL_SELECT = 20;
    public static final int OP_ROUND = 21;
    public static final int OP_SLICE = 22;
    public static final int OP_FIND_TOP_SURFACE = 23;
    public static final int OP_SPLINE = 24;
    public static final int OP_INTERPOLATED = 25;
    public static final int OP_CACHE = 26;
    public static final int OP_FLAT_CACHE = 27;
    public static final int OP_CACHE_2D = 28;
    public static final int OP_CACHE_ONCE = 29;
    public static final int OP_CACHE_ALL_IN_CELL = 30;
    public static final int OP_BLEND_DENSITY = 31;
    public static final int OP_SCALED_NOISE = 32;

    // Unary / binary / round argument codes.
    public static final int UNARY_ABS = 0;
    public static final int UNARY_SQUARE = 1;
    public static final int UNARY_CUBE = 2;
    public static final int UNARY_SQRT = 3;
    public static final int UNARY_HALF_NEGATIVE = 4;
    public static final int UNARY_QUARTER_NEGATIVE = 5;
    public static final int UNARY_RECIPROCAL = 6;
    public static final int UNARY_NEGATE = 7;
    public static final int UNARY_SQUEEZE = 8;
    public static final int UNARY_LOG = 9;
    public static final int UNARY_SIGN = 10;
    public static final int BINARY_ADD = 0;
    public static final int BINARY_SUB = 1;
    public static final int BINARY_MUL = 2;
    public static final int BINARY_DIV = 3;
    public static final int BINARY_MIN = 4;
    public static final int BINARY_MAX = 5;

    // Roles of the ROOT section.
    public static final int ROLE_TEMPERATURE = 1;
    public static final int ROLE_VEGETATION = 2;
    public static final int ROLE_CONTINENTS = 3;
    public static final int ROLE_EROSION = 4;
    public static final int ROLE_DEPTH = 5;
    public static final int ROLE_RIDGES = 6;
    public static final int ROLE_FINAL_DENSITY = 7;
    public static final int ROLE_PRELIMINARY_SURFACE = 8;
    public static final int ROLE_AQUIFER_BARRIER = 9;
    public static final int ROLE_AQUIFER_FLOODEDNESS = 10;
    public static final int ROLE_AQUIFER_SPREAD = 11;
    public static final int ROLE_AQUIFER_LAVA = 12;
    public static final int ROLE_AQUIFER_EXCLUSION = 13;
    public static final int ROLE_AQUIFER_SURFACE_LEVEL = 14;
    public static final int ROLE_VEIN_TOGGLE = 15;
    public static final int ROLE_VEIN_RIDGED = 16;
    public static final int ROLE_VEIN_GAP = 17;

    // State flags.
    public static final int STATE_AIR = 1;
    public static final int STATE_FLUID = 2;
    public static final int STATE_OCEAN_FLOOR_OPAQUE = 4;
    public static final int STATE_WATER = 8;
    public static final int STATE_LAVA = 16;
    public static final int STATE_DEFAULT_BLOCK = 32;

    // Special state slots (STAT section tail).
    public static final int SPECIAL_AIR = 0;
    public static final int SPECIAL_WATER = 1;
    public static final int SPECIAL_LAVA = 2;
    public static final int SPECIAL_TERRACOTTA = 3;
    public static final int SPECIAL_WHITE_TERRACOTTA = 4;
    public static final int SPECIAL_ORANGE_TERRACOTTA = 5;
    public static final int SPECIAL_YELLOW_TERRACOTTA = 6;
    public static final int SPECIAL_BROWN_TERRACOTTA = 7;
    public static final int SPECIAL_RED_TERRACOTTA = 8;
    public static final int SPECIAL_LIGHT_GRAY_TERRACOTTA = 9;
    public static final int SPECIAL_PACKED_ICE = 10;
    public static final int SPECIAL_SNOW_BLOCK = 11;
    public static final int SPECIAL_COUNT = 12;

    // Surface conditions and rules.
    public static final int COND_BIOME = 0;
    public static final int COND_NOISE_THRESHOLD = 1;
    public static final int COND_VERTICAL_GRADIENT = 2;
    public static final int COND_Y_ABOVE = 3;
    public static final int COND_WATER = 4;
    public static final int COND_TEMPERATURE = 5;
    public static final int COND_STEEP = 6;
    public static final int COND_NOT = 7;
    public static final int COND_HOLE = 8;
    public static final int COND_ABOVE_PRELIMINARY_SURFACE = 9;
    public static final int COND_STONE_DEPTH = 10;
    public static final int RULE_BLOCK = 0;
    public static final int RULE_SEQUENCE = 1;
    public static final int RULE_CONDITION = 2;
    public static final int RULE_BANDLANDS = 3;
    public static final int RULE_ORE_VEIN = 4;

    // Surface noise slots.
    public static final int SURFACE_NOISE_SURFACE = 0;
    public static final int SURFACE_NOISE_SECONDARY = 1;
    public static final int SURFACE_NOISE_CLAY_BANDS_OFFSET = 2;
    public static final int SURFACE_NOISE_BADLANDS_PILLAR = 3;
    public static final int SURFACE_NOISE_BADLANDS_PILLAR_ROOF = 4;
    public static final int SURFACE_NOISE_BADLANDS_SURFACE = 5;
    public static final int SURFACE_NOISE_ICEBERG_PILLAR = 6;
    public static final int SURFACE_NOISE_ICEBERG_PILLAR_ROOF = 7;
    public static final int SURFACE_NOISE_ICEBERG_SURFACE = 8;
    public static final int SURFACE_NOISE_COUNT = 9;

    public static final int BIOME_SOURCE_MULTI_NOISE = 0;
    public static final int BIOME_SOURCE_FIXED = 1;
    public static final int BIOME_SOURCE_THE_END = 2;
    public static final int BIOME_ERODED_BADLANDS = 1;
    public static final int BIOME_FROZEN_OCEAN = 2;
    public static final int BIOME_DEEP_FROZEN_OCEAN = 4;

    private final int profile;
    private final List<String> tags = new ArrayList<>();
    private final List<Buf> bodies = new ArrayList<>();

    public WorldgenIr(int profile) {
        this.profile = profile;
    }

    /** Starts a section; write its body into the returned buffer. */
    public Buf section(String tag) {
        if (tag.length() != 4) {
            throw new IllegalArgumentException("section tags have four characters: " + tag);
        }
        Buf body = new Buf();
        tags.add(tag);
        bodies.add(body);
        return body;
    }

    /** The finished IR: header followed by every section in order. */
    public byte[] finish() {
        Buf out = new Buf();
        out.u8('G');
        out.u8('W');
        out.u8('I');
        out.u8('R');
        out.u16(VERSION);
        out.u8(profile);
        out.u8(0);
        out.u32(tags.size());
        int total = 16;
        for (Buf body : bodies) {
            total += 8 + body.size();
        }
        out.u32(total);
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            for (int c = 0; c < 4; c++) {
                out.u8(tag.charAt(c));
            }
            out.u32(bodies.get(i).size());
            out.bytes(bodies.get(i));
        }
        return out.toByteArray();
    }

    /** Little-endian growable byte buffer. */
    public static final class Buf {
        private byte[] data = new byte[256];
        private int size;

        private void ensure(int extra) {
            if (size + extra > data.length) {
                int capacity = Math.max(data.length * 2, size + extra);
                byte[] grown = new byte[capacity];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
        }

        public int size() {
            return size;
        }

        public Buf u8(int v) {
            ensure(1);
            data[size++] = (byte) v;
            return this;
        }

        public Buf bool(boolean v) {
            return u8(v ? 1 : 0);
        }

        public Buf u16(int v) {
            ensure(2);
            data[size++] = (byte) v;
            data[size++] = (byte) (v >>> 8);
            return this;
        }

        public Buf u32(int v) {
            ensure(4);
            data[size++] = (byte) v;
            data[size++] = (byte) (v >>> 8);
            data[size++] = (byte) (v >>> 16);
            data[size++] = (byte) (v >>> 24);
            return this;
        }

        public Buf i32(int v) {
            return u32(v);
        }

        public Buf i64(long v) {
            u32((int) v);
            return u32((int) (v >>> 32));
        }

        public Buf f32(float v) {
            return u32(Float.floatToRawIntBits(v));
        }

        public Buf f64(double v) {
            return i64(Double.doubleToRawLongBits(v));
        }

        /** {@code u16} UTF-8 length, then the bytes. */
        public Buf str(String s) {
            byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > 0xFFFF) {
                throw new IllegalArgumentException("string too long for the IR");
            }
            u16(bytes.length);
            ensure(bytes.length);
            System.arraycopy(bytes, 0, data, size, bytes.length);
            size += bytes.length;
            return this;
        }

        public Buf bytes(Buf other) {
            ensure(other.size);
            System.arraycopy(other.data, 0, data, size, other.size);
            size += other.size;
            return this;
        }

        public byte[] toByteArray() {
            byte[] out = new byte[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    /**
     * The density node table of one generator: nodes are appended after their children and
     * referenced by index.
     */
    public static final class Nodes {
        private final Buf buf = new Buf();
        private int count;

        /** Writes a node header and returns the node's index; the caller then writes the payload. */
        public int begin(int op, int arg, int axes, double min, double max) {
            buf.u8(op).u8(arg).u8(axes).u8(0).f64(min).f64(max);
            return count++;
        }

        public Buf payload() {
            return buf;
        }

        public int count() {
            return count;
        }

        /** Writes {@code u32 count} and the nodes into {@code out}. */
        public void writeTo(Buf out) {
            out.u32(count);
            out.bytes(buf);
        }
    }

    /** The spline table that follows the nodes. */
    public static final class Splines {
        private final Buf buf = new Buf();
        private int count;

        public int constant(float value) {
            buf.u8(0).f32(value);
            return count++;
        }

        public int multipoint(int coordinate, float[] locations, float[] derivatives, int[] values) {
            buf.u8(1).u32(coordinate).u32(locations.length);
            for (float v : locations) {
                buf.f32(v);
            }
            for (float v : derivatives) {
                buf.f32(v);
            }
            for (int v : values) {
                buf.u32(v);
            }
            return count++;
        }

        public void writeTo(Buf out) {
            out.u32(count);
            out.bytes(buf);
        }
    }
}
