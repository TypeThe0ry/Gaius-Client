package dev.gaius.browser;

import java.util.HashMap;
import org.lwjgl.system.MemoryUtil;

/**
 * Replays atlas work that a build-time run of the vanilla classes recorded (the GAIUSAP1 payload,
 * {@code port/tools/atlasprebake}): the mip chains {@code MipmapGenerator.generateMipLevels} makes
 * for the sprites of the vanilla pack, and the layouts {@code Stitcher.stitch} and
 * {@code Stitcher.gatherSprites} produce for the vanilla atlases.
 *
 * <p>The patches {@code MinecraftClientPatcher.patchMipmapGeneratorAtlasPrebake} and
 * {@code MinecraftClientPatcher.patchStitcherAtlasPrebake} hand this class primitives and strings
 * only. A recorded result is used only when every input of the vanilla method equals the recorded
 * one: for a mip chain the sprite name, image count, size, pixel format, mip level, strategy, alpha
 * cutoff bias bits, transparency and a 128-bit hash of the level-0 pixels; for a layout the ordered
 * sprite names and sizes, the mip level, the padding, and the maximum size within the range of
 * maxima the builder found to give the same layout. Anything else, a payload that is absent,
 * unverified or malformed, or {@code ?gaiusAtlasPrebake=0}, runs the vanilla code.
 * {@code ?gaiusAtlasPrebake=verify} runs the vanilla code too, compares its result with the
 * recorded one and keeps the vanilla result.
 *
 * <p>No Minecraft type appears here; pixels are read and written through their linear-memory
 * addresses, the same code on the JVM (the builder and the JVM gates) and in the browser.
 */
public final class BrowserAtlasPrebake {
    public static final int MODE_OFF = 0;
    public static final int MODE_ON = 1;
    public static final int MODE_VERIFY = 2;

    static final long MAGIC = 0x3150415355494147L; // "GAIUSAP1" little-endian
    static final int VERSION = 1;
    static final int FIXED_HEADER = 52;

    /** Where the payload bytes are; the browser one reads the page's verified copy. */
    public interface Source {
        int mode();

        /** The payload length, or a negative value when there is no verified payload. */
        int length();

        /** Why {@link #length} is negative (or "verified"). */
        String status();

        String profile();

        void copyTo(int offset, int length, long address);

        boolean equalsAt(int offset, int length, long address);

        void publish(int[] counters, String status);
    }

    // counters (published in this order)
    static final int MIP_HITS = 0;
    static final int MIP_MISS_KEY = 1;
    static final int MIP_MISS_HASH = 2;
    static final int MIP_MISS_LEVEL = 3;
    static final int MIP_UNCHECKED = 4;
    static final int STITCH_HITS = 5;
    static final int STITCH_MISSES = 6;
    static final int VERIFY_CHECKS = 7;
    static final int VERIFY_MISMATCHES = 8;
    static final int HIT_MICROS = 9;
    static final int COUNTERS = 10;

    private static Source source;
    private static boolean resolved;
    private static int mode;
    private static String status = "unresolved";
    private static Table table;
    private static final int[] counters = new int[COUNTERS];

    // stitch request being assembled (stitchBegin .. stitchEnd)
    private static String[] requestNames;
    private static int[] requestSizes;
    private static int requestCount;
    private static int requestMip;
    private static int requestPadding;
    private static int requestMaxWidth;
    private static int requestMaxHeight;

    // verify-mode walk observation
    /** Read by the patched {@code Stitcher$Region.walk}. */
    public static boolean observingWalk;
    private static int[] observedLayout;
    private static String[] observedNames;
    private static int observedIndex;
    private static boolean observedMatch;

    private BrowserAtlasPrebake() {
    }

    /** Replaces the payload source (JVM tools and tests); resets every counter and cached table. */
    public static synchronized void install(Source replacement) {
        source = replacement;
        resolved = false;
        table = null;
        status = "unresolved";
        java.util.Arrays.fill(counters, 0);
        observingWalk = false;
    }

    public static synchronized int[] counters() {
        return counters.clone();
    }

    public static synchronized String status() {
        resolve();
        return status;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            if (source == null) {
                source = BrowserAtlasPrebakeJs.SOURCE;
            }
            mode = source.mode();
            if (mode == MODE_OFF) {
                status = "off";
                return;
            }
            int length = source.length();
            if (length < 0) {
                status = source.status();
                return;
            }
            table = Table.parse(source, length);
            status = mode == MODE_VERIFY ? "verify" : "on";
        } catch (Throwable failure) {
            table = null;
            status = "invalid: " + failure;
        }
        publish();
    }

    private static void publish() {
        try {
            if (source != null) {
                source.publish(counters, status);
            }
        } catch (Throwable ignored) {
            // Diagnostics only.
        }
    }

    /** True in verify mode with a usable payload. */
    public static synchronized boolean verifying() {
        resolve();
        return table != null && mode == MODE_VERIFY;
    }

    // --- mip chains -----------------------------------------------------------------------------

    /** The key of a recorded mip chain; the level and the pixel hash are compared separately. */
    public static String mipKey(String id, int images, int width, int height, int format, int strategy,
            int biasBits, boolean transparent, boolean translucent) {
        return id + '\n' + images + ' ' + width + 'x' + height + ' ' + format + ' ' + strategy + ' '
                + Integer.toHexString(biasBits) + ' ' + (transparent ? 'T' : 't') + (translucent ? 'L' : 'l');
    }

    /**
     * The entry for these {@code generateMipLevels} arguments, or -1 (run vanilla). {@code pixels}
     * is the level-0 image's address, {@code bytesPerPixel} its format's component count.
     */
    public static synchronized int mipLookup(String id, int images, long pixels, int width, int height,
            int format, int bytesPerPixel, int level, int strategy, int biasBits, boolean transparent,
            boolean translucent) {
        resolve();
        if (table == null) {
            counters[MIP_UNCHECKED]++;
            return -1;
        }
        long started = System.nanoTime();
        int entry = table.mipIndex(mipKey(id, images, width, height, format, strategy, biasBits, transparent,
                translucent));
        if (entry < 0 || images != 1 || pixels == 0L || bytesPerPixel != 4 || width <= 0 || height <= 0) {
            counters[MIP_MISS_KEY]++;
            publish();
            return -1;
        }
        if (level < 1 || level > table.mipMaxLevel[entry] || (table.mipLevelMask[entry] & (1 << level)) == 0) {
            counters[MIP_MISS_LEVEL]++;
            publish();
            return -1;
        }
        long[] hash = hash128(pixels, (long) width * height * 4L);
        if (hash[0] != table.mipHashLo[entry] || hash[1] != table.mipHashHi[entry]) {
            counters[MIP_MISS_HASH]++;
            publish();
            return -1;
        }
        counters[MIP_HITS]++;
        counters[HIT_MICROS] += (int) ((System.nanoTime() - started) / 1000L);
        publish();
        return entry;
    }

    /** Writes the recorded level of {@code entry} at {@code pixels} (level 0: only if vanilla changed it). */
    public static synchronized void mipApply(int entry, int level, long pixels, int width, int height) {
        long started = System.nanoTime();
        int offset = table.mipLevelOffset(entry, level);
        if (offset < 0) {
            return;
        }
        checkLevelSize(entry, level, width, height);
        source.copyTo(table.dataStart + offset, levelBytes(table.mipWidth[entry], table.mipHeight[entry], level),
                pixels);
        counters[HIT_MICROS] += (int) ((System.nanoTime() - started) / 1000L);
    }

    /** Verify mode: compares the vanilla level at {@code pixels} with the recorded one. */
    public static synchronized void mipVerify(int entry, int level, long pixels, int width, int height) {
        counters[VERIFY_CHECKS]++;
        boolean equal;
        if (width != table.mipWidth[entry] >> level || height != table.mipHeight[entry] >> level) {
            equal = false;
        } else {
            int offset = table.mipLevelOffset(entry, level);
            int bytes = levelBytes(table.mipWidth[entry], table.mipHeight[entry], level);
            if (offset >= 0) {
                equal = source.equalsAt(table.dataStart + offset, bytes, pixels);
            } else {
                // Level 0 that vanilla leaves as it is: still the hashed input.
                long[] hash = hash128(pixels, bytes);
                equal = hash[0] == table.mipHashLo[entry] && hash[1] == table.mipHashHi[entry];
            }
        }
        if (!equal) {
            counters[VERIFY_MISMATCHES]++;
        }
        publish();
    }

    private static void checkLevelSize(int entry, int level, int width, int height) {
        if (width != table.mipWidth[entry] >> level || height != table.mipHeight[entry] >> level) {
            throw new IllegalStateException("atlas prebake: level " + level + " is " + width + "x" + height);
        }
    }

    static int levelBytes(int width, int height, int level) {
        return (width >> level) * (height >> level) * 4;
    }

    // --- stitch layouts -------------------------------------------------------------------------

    /** Starts a {@code Stitcher.stitch} request of {@code count} sprites. */
    public static synchronized void stitchBegin(int count, int mipLevel, int padding, int maxWidth, int maxHeight) {
        requestNames = new String[Math.max(count, 0)];
        requestSizes = new int[Math.max(count, 0) * 2];
        requestCount = 0;
        requestMip = mipLevel;
        requestPadding = padding;
        requestMaxWidth = maxWidth;
        requestMaxHeight = maxHeight;
    }

    /** Adds the next registered sprite (its entry name and size, in registration order). */
    public static synchronized void stitchSprite(String name, int width, int height) {
        if (requestNames == null || requestCount >= requestNames.length) {
            requestNames = null;
            return;
        }
        requestNames[requestCount] = name;
        requestSizes[requestCount * 2] = width;
        requestSizes[requestCount * 2 + 1] = height;
        requestCount++;
    }

    /**
     * The recorded layout for the request: {@code [storageX, storageY, n, (index, x, y) * n]} in
     * walk order, or null (run vanilla).
     */
    public static synchronized int[] stitchEnd() {
        resolve();
        String[] names = requestNames;
        int[] sizes = requestSizes;
        int count = requestCount;
        requestNames = null;
        requestSizes = null;
        if (table == null) {
            return null;
        }
        long started = System.nanoTime();
        int[] layout = names == null || count != names.length ? null
                : table.stitch(names, sizes, requestMip, requestPadding, requestMaxWidth, requestMaxHeight);
        if (layout == null) {
            counters[STITCH_MISSES]++;
        } else {
            counters[STITCH_HITS]++;
            counters[HIT_MICROS] += (int) ((System.nanoTime() - started) / 1000L);
            observedNames = names;
        }
        publish();
        return layout;
    }

    /** Verify mode: the vanilla storage size after a recorded layout was found. */
    public static synchronized void stitchVerifySize(int[] layout, int storageX, int storageY) {
        counters[VERIFY_CHECKS]++;
        if (layout[0] != storageX || layout[1] != storageY) {
            counters[VERIFY_MISMATCHES]++;
        }
        publish();
    }

    /** Verify mode: starts observing the vanilla walk against {@code layout}. */
    public static synchronized void walkBegin(int[] layout) {
        observedLayout = layout;
        observedIndex = 0;
        observedMatch = true;
        observingWalk = true;
    }

    /** Called by the patched {@code Stitcher$Region.walk} for every placed sprite while observing. */
    public static synchronized void walkObserved(String name, int x, int y) {
        int[] layout = observedLayout;
        if (layout == null || observedIndex >= layout[2]) {
            observedMatch = false;
            return;
        }
        int at = 3 + observedIndex * 3;
        int index = layout[at];
        if (observedNames == null || index < 0 || index >= observedNames.length
                || !observedNames[index].equals(name) || layout[at + 1] != x || layout[at + 2] != y) {
            observedMatch = false;
        }
        observedIndex++;
    }

    public static synchronized void walkEnd() {
        observingWalk = false;
        if (observedLayout != null) {
            counters[VERIFY_CHECKS]++;
            if (!observedMatch || observedIndex != observedLayout[2]) {
                counters[VERIFY_MISMATCHES]++;
            }
        }
        observedLayout = null;
        publish();
    }

    // --- hashing --------------------------------------------------------------------------------

    /** MurmurHash3 x64 128 (seed 0) of {@code length} bytes at {@code address}. */
    public static long[] hash128(long address, long length) {
        long h1 = 0L;
        long h2 = 0L;
        final long c1 = 0x87c37b91114253d5L;
        final long c2 = 0x4cf5ad432745937fL;
        long blocks = length >>> 4;
        long at = address;
        for (long block = 0; block < blocks; block++, at += 16) {
            long k1 = MemoryUtil.memGetLong(at);
            long k2 = MemoryUtil.memGetLong(at + 8);
            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729L;
            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;
            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5L;
        }
        int tail = (int) (length & 15);
        if (tail > 0) {
            long k1 = 0L;
            long k2 = 0L;
            for (int index = tail - 1; index >= 8; index--) {
                k2 ^= (MemoryUtil.memGetByte(at + index) & 0xffL) << ((index - 8) * 8);
            }
            for (int index = Math.min(tail, 8) - 1; index >= 0; index--) {
                k1 ^= (MemoryUtil.memGetByte(at + index) & 0xffL) << (index * 8);
            }
            if (tail > 8) {
                k2 *= c2;
                k2 = Long.rotateLeft(k2, 33);
                k2 *= c1;
                h2 ^= k2;
            }
            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;
        }
        h1 ^= length;
        h2 ^= length;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        h2 += h1;
        return new long[] {h1, h2};
    }

    private static long fmix64(long value) {
        long k = value;
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    /** A 128-bit hash of an ordered sprite list (names and sizes), the first filter of a layout key. */
    public static long[] listHash(String[] names, int[] sizes, int count) {
        long h1 = 0x9e3779b97f4a7c15L ^ count;
        long h2 = 0xc2b2ae3d27d4eb4fL ^ ((long) count << 32);
        for (int index = 0; index < count; index++) {
            String name = names[index];
            for (int at = 0; at < name.length(); at++) {
                h1 = mix(h1, name.charAt(at));
                h2 = mix(h2 ^ 0x5bd1e995L, name.charAt(at) * 0x100000001b3L);
            }
            h1 = mix(h1, 0x10000L + sizes[index * 2]);
            h2 = mix(h2, 0x20000L + sizes[index * 2 + 1]);
            h1 = mix(h1, 0x30000L + sizes[index * 2 + 1]);
            h2 = mix(h2, 0x40000L + sizes[index * 2]);
        }
        return new long[] {fmix64(h1 ^ h2 >>> 7), fmix64(h2 + h1)};
    }

    private static long mix(long hash, long value) {
        long k = value * 0x87c37b91114253d5L;
        k = Long.rotateLeft(k, 31) * 0x4cf5ad432745937fL;
        return Long.rotateLeft(hash ^ k, 27) * 5 + 0x52dce729L;
    }

    /** The largest power of two at most {@code value} as its exponent, or -1 for a value below 1. */
    public static int floorLog2(int value) {
        return value < 1 ? -1 : 31 - Integer.numberOfLeadingZeros(value);
    }

    // --- payload --------------------------------------------------------------------------------

    /** The parsed index of a GAIUSAP1 payload. */
    static final class Table {
        int dataStart;
        final HashMap<String, Integer> mipByKey = new HashMap<>();
        int[] mipWidth;
        int[] mipHeight;
        long[] mipHashLo;
        long[] mipHashHi;
        int[] mipMaxLevel;
        int[] mipLevelMask;
        boolean[] mipLevel0Changed;
        int[] mipDataOffset;
        // stitch keys
        int[] keyCount;
        long[] keyHashLo;
        long[] keyHashHi;
        String[][] keyNames;
        int[][] keySizes;
        // stitch records
        int[] recordKey;
        int[] recordMip;
        int[] recordPadding;
        int[] recordMinLog2;
        int[] recordMaxLog2;
        int[][] recordLayout;

        int mipIndex(String key) {
            Integer index = mipByKey.get(key);
            return index == null ? -1 : index;
        }

        /** The data offset of a stored level, or -1 when level 0 is not stored (unchanged by vanilla). */
        int mipLevelOffset(int entry, int level) {
            int offset = mipDataOffset[entry];
            int width = mipWidth[entry];
            int height = mipHeight[entry];
            if (level == 0) {
                return mipLevel0Changed[entry] ? offset : -1;
            }
            if (mipLevel0Changed[entry]) {
                offset += levelBytes(width, height, 0);
            }
            for (int skipped = 1; skipped < level; skipped++) {
                offset += levelBytes(width, height, skipped);
            }
            return offset;
        }

        int[] stitch(String[] names, int[] sizes, int mip, int padding, int maxWidth, int maxHeight) {
            if (maxWidth != maxHeight) {
                return null;
            }
            int log2 = floorLog2(maxWidth);
            long[] hash = null;
            for (int record = 0; record < recordKey.length; record++) {
                if (recordMip[record] != mip || recordPadding[record] != padding
                        || log2 < recordMinLog2[record] || log2 > recordMaxLog2[record]) {
                    continue;
                }
                int key = recordKey[record];
                if (keyCount[key] != names.length) {
                    continue;
                }
                if (hash == null) {
                    hash = listHash(names, sizes, names.length);
                }
                if (hash[0] != keyHashLo[key] || hash[1] != keyHashHi[key]) {
                    continue;
                }
                String[] stored = keyNames[key];
                int[] storedSizes = keySizes[key];
                boolean equal = true;
                for (int index = 0; equal && index < names.length; index++) {
                    equal = stored[index].equals(names[index])
                            && storedSizes[index * 2] == sizes[index * 2]
                            && storedSizes[index * 2 + 1] == sizes[index * 2 + 1];
                }
                if (equal) {
                    return recordLayout[record].clone();
                }
            }
            return null;
        }

        static Table parse(Source source, int length) {
            if (length < FIXED_HEADER) {
                throw new IllegalStateException("payload is truncated");
            }
            int headerLength;
            Reader header = Reader.copy(source, 0, FIXED_HEADER);
            try {
                if (header.i64() != MAGIC) {
                    throw new IllegalStateException("payload magic");
                }
                if (header.i32() != VERSION) {
                    throw new IllegalStateException("payload version");
                }
                headerLength = header.i32();
                int bodyLength = header.i32();
                if (headerLength < FIXED_HEADER + 4 || bodyLength < 16
                        || (long) headerLength + bodyLength != length) {
                    throw new IllegalStateException("payload lengths");
                }
            } finally {
                header.close();
            }
            Reader names = Reader.copy(source, FIXED_HEADER, headerLength - FIXED_HEADER);
            try {
                String profile = names.string16();
                String expected = source.profile();
                if (expected == null || !expected.equals(profile)) {
                    throw new IllegalStateException("payload profile " + profile + " is not " + expected);
                }
            } finally {
                names.close();
            }
            Reader indexLength = Reader.copy(source, headerLength, 4);
            int indexBytes;
            try {
                indexBytes = indexLength.i32();
            } finally {
                indexLength.close();
            }
            if (indexBytes < 12 || (long) headerLength + 4 + indexBytes > length) {
                throw new IllegalStateException("payload index length");
            }
            Table table = new Table();
            table.dataStart = headerLength + 4 + indexBytes;
            int dataLength = length - table.dataStart;
            Reader index = Reader.copy(source, headerLength + 4, indexBytes);
            try {
                int mips = index.count();
                table.mipWidth = new int[mips];
                table.mipHeight = new int[mips];
                table.mipHashLo = new long[mips];
                table.mipHashHi = new long[mips];
                table.mipMaxLevel = new int[mips];
                table.mipLevelMask = new int[mips];
                table.mipLevel0Changed = new boolean[mips];
                table.mipDataOffset = new int[mips];
                for (int entry = 0; entry < mips; entry++) {
                    String key = index.string16();
                    table.mipWidth[entry] = index.i32();
                    table.mipHeight[entry] = index.i32();
                    table.mipHashLo[entry] = index.i64();
                    table.mipHashHi[entry] = index.i64();
                    table.mipMaxLevel[entry] = index.u8();
                    table.mipLevelMask[entry] = index.u8();
                    table.mipLevel0Changed[entry] = index.u8() != 0;
                    index.u8();
                    table.mipDataOffset[entry] = index.i32();
                    int width = table.mipWidth[entry];
                    int height = table.mipHeight[entry];
                    int maxLevel = table.mipMaxLevel[entry];
                    if (width <= 0 || height <= 0 || maxLevel < 1 || maxLevel > 30
                            || (width >> maxLevel) <= 0 || (height >> maxLevel) <= 0
                            || table.mipDataOffset[entry] < 0) {
                        throw new IllegalStateException("mip entry " + key);
                    }
                    long end = table.mipDataOffset[entry];
                    if (table.mipLevel0Changed[entry]) {
                        end += levelBytes(width, height, 0);
                    }
                    for (int level = 1; level <= maxLevel; level++) {
                        end += levelBytes(width, height, level);
                    }
                    if (end > dataLength || table.mipByKey.put(key, entry) != null) {
                        throw new IllegalStateException("mip entry " + key);
                    }
                }
                int keys = index.count();
                table.keyCount = new int[keys];
                table.keyHashLo = new long[keys];
                table.keyHashHi = new long[keys];
                table.keyNames = new String[keys][];
                table.keySizes = new int[keys][];
                for (int key = 0; key < keys; key++) {
                    int count = index.count();
                    table.keyCount[key] = count;
                    table.keyHashLo[key] = index.i64();
                    table.keyHashHi[key] = index.i64();
                    String[] spriteNames = new String[count];
                    int[] sizes = new int[count * 2];
                    for (int sprite = 0; sprite < count; sprite++) {
                        spriteNames[sprite] = index.string16();
                        sizes[sprite * 2] = index.i32();
                        sizes[sprite * 2 + 1] = index.i32();
                    }
                    table.keyNames[key] = spriteNames;
                    table.keySizes[key] = sizes;
                }
                int records = index.count();
                table.recordKey = new int[records];
                table.recordMip = new int[records];
                table.recordPadding = new int[records];
                table.recordMinLog2 = new int[records];
                table.recordMaxLog2 = new int[records];
                table.recordLayout = new int[records][];
                for (int record = 0; record < records; record++) {
                    int key = index.i32();
                    if (key < 0 || key >= keys) {
                        throw new IllegalStateException("stitch record key");
                    }
                    table.recordKey[record] = key;
                    table.recordMip[record] = index.i32();
                    table.recordPadding[record] = index.i32();
                    table.recordMinLog2[record] = index.u8();
                    table.recordMaxLog2[record] = index.u8();
                    index.u8();
                    index.u8();
                    int storageX = index.i32();
                    int storageY = index.i32();
                    int placements = index.count();
                    if (placements != table.keyCount[key]) {
                        throw new IllegalStateException("stitch record placements");
                    }
                    int[] layout = new int[3 + placements * 3];
                    layout[0] = storageX;
                    layout[1] = storageY;
                    layout[2] = placements;
                    boolean[] seen = new boolean[placements];
                    for (int at = 3; at < layout.length; at += 3) {
                        int sprite = index.i32();
                        if (sprite < 0 || sprite >= placements || seen[sprite]) {
                            throw new IllegalStateException("stitch record index");
                        }
                        seen[sprite] = true;
                        layout[at] = sprite;
                        layout[at + 1] = index.i32();
                        layout[at + 2] = index.i32();
                    }
                    table.recordLayout[record] = layout;
                }
                if (index.remaining() != 0) {
                    throw new IllegalStateException("payload index has trailing bytes");
                }
            } finally {
                index.close();
            }
            return table;
        }
    }

    /** Little-endian reads over a copy of payload bytes in linear memory. */
    static final class Reader {
        private final long base;
        private final int length;
        private int at;

        private Reader(long base, int length) {
            this.base = base;
            this.length = length;
        }

        static Reader copy(Source source, int offset, int length) {
            long base = MemoryUtil.nmemAlloc(Math.max(length, 1));
            if (base == 0L) {
                throw new IllegalStateException("payload index allocation");
            }
            try {
                source.copyTo(offset, length, base);
            } catch (RuntimeException failure) {
                MemoryUtil.nmemFree(base);
                throw failure;
            }
            return new Reader(base, length);
        }

        void close() {
            MemoryUtil.nmemFree(base);
        }

        int remaining() {
            return length - at;
        }

        private void need(int bytes) {
            if (bytes < 0 || length - at < bytes) {
                throw new IllegalStateException("payload index is truncated");
            }
        }

        int u8() {
            need(1);
            return MemoryUtil.memGetByte(base + at++) & 0xff;
        }

        int u16() {
            need(2);
            int value = (MemoryUtil.memGetByte(base + at) & 0xff) | (MemoryUtil.memGetByte(base + at + 1) & 0xff) << 8;
            at += 2;
            return value;
        }

        int i32() {
            need(4);
            int value = MemoryUtil.memGetInt(base + at);
            at += 4;
            return value;
        }

        long i64() {
            need(8);
            long value = MemoryUtil.memGetLong(base + at);
            at += 8;
            return value;
        }

        int count() {
            int value = i32();
            if (value < 0 || value > length) {
                throw new IllegalStateException("payload count");
            }
            return value;
        }

        String string16() {
            int size = u16();
            need(size);
            char[] chars = new char[size];
            for (int index = 0; index < size; index++) {
                int value = MemoryUtil.memGetByte(base + at + index) & 0xff;
                if (value >= 0x80) {
                    throw new IllegalStateException("payload string is not ASCII");
                }
                chars[index] = (char) value;
            }
            at += size;
            return new String(chars);
        }
    }
}
