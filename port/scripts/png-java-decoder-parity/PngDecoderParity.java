import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decodes every PNG of a client jar (and synthetic PNGs of every color type, bit depth and
 * filter, with transparency, split IDAT chunks and malformed inputs) with two builds of the
 * browser STBImage override, each in its own class loader, and compares width, height,
 * channels, every output byte and the failure reason for desired channels 0 to 4, both
 * vertical orientations.
 *
 *   java PngDecoderParity <reference classes> <candidate classes> <jar>...
 */
public final class PngDecoderParity {
    private PngDecoderParity() {
    }

    private record Decoder(Method load, Method flip, Method reason) {
        static Decoder of(Path classes) throws Exception {
            ClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
            Class<?> type = Class.forName("org.lwjgl.stb.STBImage", true, loader);
            return new Decoder(
                    type.getMethod("stbi_load_from_memory", ByteBuffer.class, IntBuffer.class, IntBuffer.class,
                            IntBuffer.class, int.class),
                    type.getMethod("stbi_set_flip_vertically_on_load", boolean.class),
                    type.getMethod("stbi_failure_reason"));
        }

        String decode(byte[] encoded, int desired, boolean flipped, MessageDigest digest) throws Exception {
            flip.invoke(null, flipped);
            ByteBuffer input = ByteBuffer.allocate(encoded.length + 5);
            input.position(3);
            input.put(encoded).flip().position(3);
            IntBuffer x = IntBuffer.allocate(2).position(1);
            IntBuffer y = IntBuffer.allocate(2).position(1);
            IntBuffer channels = IntBuffer.allocate(2).position(1);
            ByteBuffer pixels = (ByteBuffer) load.invoke(null, input, x, y, channels, desired);
            if (pixels == null) {
                return "fail " + reason.invoke(null);
            }
            byte[] bytes = new byte[pixels.remaining()];
            pixels.get(bytes);
            digest.reset();
            return x.get(1) + "x" + y.get(1) + " c" + channels.get(1) + " in@" + input.position() + " n"
                    + bytes.length + " " + HexFormat.of().formatHex(digest.digest(bytes));
        }

        long time(List<byte[]> corpus) throws Exception {
            flip.invoke(null, false);
            long start = System.nanoTime();
            for (byte[] encoded : corpus) {
                load.invoke(null, ByteBuffer.wrap(encoded), IntBuffer.allocate(1), IntBuffer.allocate(1),
                        IntBuffer.allocate(1), 4);
            }
            return System.nanoTime() - start;
        }
    }

    public static void main(String[] args) throws Exception {
        Decoder reference = Decoder.of(Path.of(args[0]));
        Decoder candidate = Decoder.of(Path.of(args[1]));
        List<String> names = new ArrayList<>();
        List<byte[]> corpus = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            try (ZipFile jar = new ZipFile(args[i])) {
                Enumeration<? extends ZipEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".png")) {
                        try (var stream = jar.getInputStream(entry)) {
                            names.add(entry.getName());
                            corpus.add(stream.readAllBytes());
                        }
                    }
                }
            }
        }
        int jarImages = corpus.size();
        Synthetic.addAll(names, corpus);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int cases = 0;
        int mismatches = 0;
        int failures = 0;
        for (int i = 0; i < corpus.size(); i++) {
            for (int desired = 0; desired <= 4; desired++) {
                for (boolean flipped : new boolean[] {false, true}) {
                    String expected = reference.decode(corpus.get(i), desired, flipped, digest);
                    String actual = candidate.decode(corpus.get(i), desired, flipped, digest);
                    cases++;
                    if (expected.startsWith("fail ")) {
                        failures++;
                    }
                    if (!expected.equals(actual)) {
                        if (mismatches++ < 10) {
                            System.out.println("MISMATCH " + names.get(i) + " desired=" + desired + " flip=" + flipped
                                    + "\n  reference " + expected + "\n  candidate " + actual);
                        }
                    }
                }
            }
        }
        System.out.println("PNG_PARITY images=" + corpus.size() + " jarImages=" + jarImages + " synthetic="
                + (corpus.size() - jarImages) + " cases=" + cases + " expectedFailures=" + failures
                + " mismatches=" + mismatches);
        List<byte[]> jarCorpus = corpus.subList(0, jarImages);
        long referenceBest = Long.MAX_VALUE;
        long candidateBest = Long.MAX_VALUE;
        for (int round = 0; round < 7; round++) {
            referenceBest = Math.min(referenceBest, reference.time(jarCorpus));
            candidateBest = Math.min(candidateBest, candidate.time(jarCorpus));
        }
        System.out.println("# jvm best of 7, desired 4, jar corpus: reference " + referenceBest / 1_000_000 + " ms, candidate "
                + candidateBest / 1_000_000 + " ms");
        System.exit(mismatches == 0 ? 0 : 1);
    }

    /** Hand-built PNGs: every legal color type and depth, every filter, tRNS, split IDAT, bad inputs. */
    static final class Synthetic {
        private static final int[][] TYPES = {
            {0, 1}, {0, 2}, {0, 4}, {0, 8}, {0, 16}, {2, 8}, {2, 16}, {3, 1}, {3, 2}, {3, 4}, {3, 8},
            {4, 8}, {4, 16}, {6, 8}, {6, 16}
        };

        static void addAll(List<String> names, List<byte[]> corpus) throws Exception {
            Random random = new Random(20261021L);
            for (int[] type : TYPES) {
                int colorType = type[0];
                int depth = type[1];
                for (int[] size : new int[][] {{1, 1}, {3, 2}, {7, 5}, {16, 16}, {33, 9}}) {
                    for (int filterMode = 0; filterMode <= 5; filterMode++) {
                        for (int variant = 0; variant < 3; variant++) {
                            byte[] png = build(random, colorType, depth, size[0], size[1], filterMode, variant);
                            names.add("synthetic/t" + colorType + "d" + depth + "/" + size[0] + "x" + size[1]
                                    + "/f" + filterMode + "v" + variant);
                            corpus.add(png);
                        }
                    }
                }
            }
            // Malformed: unknown filter, truncated scanlines, palette index out of range, no palette.
            names.add("synthetic/bad-filter");
            corpus.add(withRaw(random, 2, 8, 4, 3, raw -> raw[0] = 9, true, true));
            names.add("synthetic/truncated");
            corpus.add(withRaw(random, 6, 8, 4, 3, null, false, true));
            names.add("synthetic/palette-index");
            corpus.add(withRaw(random, 3, 8, 4, 3, raw -> raw[2] = (byte) 200, true, true));
            names.add("synthetic/no-palette");
            corpus.add(withRaw(random, 3, 8, 4, 3, null, true, false));
            names.add("synthetic/no-palette-4bit");
            corpus.add(withRaw(random, 3, 4, 4, 3, null, true, false));
        }

        interface Edit {
            void apply(byte[] raw);
        }

        private static int channels(int colorType) {
            return switch (colorType) {
                case 0, 3 -> 1;
                case 2 -> 3;
                case 4 -> 2;
                default -> 4;
            };
        }

        private static byte[] withRaw(Random random, int colorType, int depth, int width, int height, Edit edit,
                boolean complete, boolean palette) throws Exception {
            int rowBytes = (width * depth * channels(colorType) + 7) / 8;
            byte[] raw = new byte[height * (rowBytes + 1)];
            for (int y = 0; y < height; y++) {
                raw[y * (rowBytes + 1)] = 0;
                for (int x = 1; x <= rowBytes; x++) {
                    raw[y * (rowBytes + 1) + x] = (byte) random.nextInt(colorType == 3 ? 4 : 256);
                }
            }
            if (edit != null) {
                edit.apply(raw);
            }
            if (!complete) {
                raw = java.util.Arrays.copyOf(raw, raw.length - rowBytes);
            }
            ByteArrayOutputStream out = header(width, height, depth, colorType);
            if (palette) {
                chunk(out, "PLTE", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
            }
            chunk(out, "IDAT", deflate(raw));
            chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        }

        private static byte[] build(Random random, int colorType, int depth, int width, int height, int filterMode,
                int variant) throws Exception {
            int channels = channels(colorType);
            int bitsPerPixel = depth * channels;
            int rowBytes = (width * bitsPerPixel + 7) / 8;
            int bpp = Math.max(1, (bitsPerPixel + 7) / 8);
            int paletteSize = colorType == 3 ? Math.min(1 << depth, 1 + random.nextInt(1 << Math.min(depth, 8))) : 0;
            byte[][] rows = new byte[height][rowBytes];
            for (byte[] row : rows) {
                if (colorType == 3) {
                    // Valid palette indices only, packed at the bit depth.
                    int perByte = 8 / Math.min(depth, 8);
                    for (int x = 0; x < width; x++) {
                        int index = random.nextInt(paletteSize);
                        if (depth == 8) {
                            row[x] = (byte) index;
                        } else {
                            int shift = 8 - depth * (x % perByte + 1);
                            row[x / perByte] |= (byte) (index << shift);
                        }
                    }
                } else {
                    random.nextBytes(row);
                    if (variant == 2) {
                        // Smooth content so that the filters see small differences.
                        for (int i = 0; i < row.length; i++) {
                            row[i] = (byte) (i * 3);
                        }
                    }
                }
            }
            if (bitsPerPixel < 8 && colorType != 3) {
                // Padding bits of the last byte are part of the scanline; keep them random.
            }
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            for (int y = 0; y < height; y++) {
                int filter = filterMode == 5 ? random.nextInt(5) : filterMode;
                raw.write(filter);
                byte[] prior = y == 0 ? new byte[rowBytes] : rows[y - 1];
                byte[] row = rows[y];
                for (int x = 0; x < rowBytes; x++) {
                    int value = row[x] & 0xFF;
                    int left = x >= bpp ? row[x - bpp] & 0xFF : 0;
                    int up = prior[x] & 0xFF;
                    int upLeft = x >= bpp ? prior[x - bpp] & 0xFF : 0;
                    int predictor = switch (filter) {
                        case 0 -> 0;
                        case 1 -> left;
                        case 2 -> up;
                        case 3 -> (left + up) >>> 1;
                        default -> paeth(left, up, upLeft);
                    };
                    raw.write((value - predictor) & 0xFF);
                }
            }
            ByteArrayOutputStream out = header(width, height, depth, colorType);
            if (colorType == 3) {
                byte[] palette = new byte[paletteSize * 3];
                random.nextBytes(palette);
                chunk(out, "PLTE", palette);
                if (variant >= 1) {
                    byte[] alpha = new byte[Math.max(1, paletteSize / 2)];
                    random.nextBytes(alpha);
                    chunk(out, "tRNS", alpha);
                }
            } else if (variant == 1 && (colorType == 0 || colorType == 2)) {
                // Transparent color equal to the first pixel's samples.
                byte[] first = rows[0];
                byte[] trns = new byte[colorType == 0 ? 2 : 6];
                for (int c = 0; c < trns.length / 2; c++) {
                    int sample;
                    if (depth == 16) {
                        sample = ((first[c * 2] & 0xFF) << 8) | (first[c * 2 + 1] & 0xFF);
                    } else if (depth == 8) {
                        sample = first[c] & 0xFF;
                    } else {
                        sample = (first[0] & 0xFF) >>> (8 - depth);
                    }
                    trns[c * 2] = (byte) (sample >>> 8);
                    trns[c * 2 + 1] = (byte) sample;
                }
                chunk(out, "tRNS", trns);
            }
            byte[] compressed = deflate(raw.toByteArray());
            if (variant == 2 && compressed.length > 2) {
                int split = compressed.length / 3;
                chunk(out, "IDAT", java.util.Arrays.copyOfRange(compressed, 0, split));
                chunk(out, "tEXt", "Comment\0split".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                chunk(out, "IDAT", java.util.Arrays.copyOfRange(compressed, split, compressed.length));
            } else {
                chunk(out, "IDAT", compressed);
            }
            chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        }

        private static int paeth(int a, int b, int c) {
            int p = a + b - c;
            int pa = Math.abs(p - a);
            int pb = Math.abs(p - b);
            int pc = Math.abs(p - c);
            if (pa <= pb && pa <= pc) {
                return a;
            }
            return pb <= pc ? b : c;
        }

        private static ByteArrayOutputStream header(int width, int height, int depth, int colorType) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
            ByteBuffer ihdr = ByteBuffer.allocate(13);
            ihdr.putInt(width).putInt(height).put((byte) depth).put((byte) colorType).put((byte) 0).put((byte) 0)
                    .put((byte) 0);
            chunk(out, "IHDR", ihdr.array());
            return out;
        }

        private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws Exception {
            ByteBuffer length = ByteBuffer.allocate(4).putInt(data.length);
            out.write(length.array());
            byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.write(name);
            out.write(data);
            CRC32 crc = new CRC32();
            crc.update(name);
            crc.update(data);
            out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        }

        private static byte[] deflate(byte[] data) {
            Deflater deflater = new Deflater(6);
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.end();
            return out.toByteArray();
        }
    }
}
