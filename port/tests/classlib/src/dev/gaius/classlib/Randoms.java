package dev.gaius.classlib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * java.util.Random is a specified 48-bit LCG: every seeded sequence, including nextInt(bound)
 * rejection, nextDouble/nextFloat scaling and nextGaussian (polar method with StrictMath), must
 * be bit-identical. Vanilla seeds Random in world generation helpers, loot, structures and
 * command code, and Collections.shuffle with a seeded Random.
 */
final class Randoms {
    private Randoms() {
    }

    static void run() {
        long[] seeds = {0, 1, 42, -1, 0x5DEECE66DL, Long.MIN_VALUE, 263, 8675309};
        for (long seed : seeds) {
            String key = "seed" + seed;
            Out.check(key + ".nextInt", () -> {
                Random r = new Random(seed);
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    out.append(r.nextInt()).append(',');
                }
                return out;
            });
            Out.check(key + ".nextIntBound", () -> {
                Random r = new Random(seed);
                StringBuilder out = new StringBuilder();
                for (int bound : new int[] {1, 2, 3, 7, 10, 100, 1 << 30, (1 << 30) + 1, Integer.MAX_VALUE}) {
                    out.append(r.nextInt(bound)).append(',');
                }
                return out;
            });
            Out.check(key + ".nextLong", () -> {
                Random r = new Random(seed);
                return r.nextLong() + "," + r.nextLong() + "," + r.nextLong();
            });
            Out.check(key + ".nextDouble", () -> {
                Random r = new Random(seed);
                return Out.hex(r.nextDouble()) + "," + Out.hex(r.nextDouble()) + "," + r.nextDouble();
            });
            Out.check(key + ".nextFloat", () -> {
                Random r = new Random(seed);
                return Out.hex(r.nextFloat()) + "," + Out.hex(r.nextFloat()) + "," + r.nextFloat();
            });
            Out.check(key + ".nextBoolean", () -> {
                Random r = new Random(seed);
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    out.append(r.nextBoolean() ? '1' : '0');
                }
                return out;
            });
            Out.check(key + ".nextGaussian", () -> {
                Random r = new Random(seed);
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    out.append(Out.hex(r.nextGaussian())).append(',');
                }
                return out;
            });
            Out.check(key + ".nextBytes", () -> {
                Random r = new Random(seed);
                byte[] bytes = new byte[11];
                r.nextBytes(bytes);
                StringBuilder out = new StringBuilder();
                for (byte b : bytes) {
                    out.append(b).append(',');
                }
                return out;
            });
            Out.check(key + ".mixed", () -> {
                Random r = new Random(seed);
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < 40; i++) {
                    switch (i % 5) {
                        case 0 -> out.append(r.nextInt(17));
                        case 1 -> out.append(Out.hex(r.nextFloat()));
                        case 2 -> out.append(r.nextLong() >>> 40);
                        case 3 -> out.append(Out.hex(r.nextGaussian()));
                        default -> out.append(r.nextBoolean());
                    }
                    out.append(',');
                }
                return out;
            });
            Out.check(key + ".setSeed", () -> {
                Random r = new Random(1);
                r.nextInt();
                r.setSeed(seed);
                return r.nextInt() + "," + Out.hex(r.nextGaussian());
            });
            Out.check(key + ".shuffle", () -> {
                List<Integer> list = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    list.add(i);
                }
                Collections.shuffle(list, new Random(seed));
                return list;
            });
            Out.check(key + ".nextIntRange", () -> {
                Random r = new Random(seed);
                return r.nextInt(5, 10) + "," + r.nextInt(-100, 100) + "," + r.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
            });
            Out.check(key + ".nextLongBound", () -> {
                Random r = new Random(seed);
                return r.nextLong(1000) + "," + r.nextLong(-5, 5) + "," + r.nextLong(1L << 40);
            });
            Out.check(key + ".nextDoubleBound", () -> {
                Random r = new Random(seed);
                return Out.hex(r.nextDouble(10.0)) + "," + Out.hex(r.nextDouble(-1.0, 1.0)) + ","
                        + Out.hex(r.nextFloat(2.0f));
            });
            Out.check(key + ".ints", () -> {
                StringBuilder out = new StringBuilder();
                new Random(seed).ints(6, 0, 50).forEach(v -> out.append(v).append(','));
                return out;
            });
            Out.check(key + ".doubles", () -> {
                StringBuilder out = new StringBuilder();
                new Random(seed).doubles(3).forEach(v -> out.append(Out.hex(v)).append(','));
                return out;
            });
        }
        Random sweep = new Random(99);
        Out.Digest digest = new Out.Digest();
        for (int i = 0; i < 100_000; i++) {
            digest.add(sweep.nextInt(1 + (i & 1023)));
        }
        Out.put("sweep.nextIntBound.digest", digest);
    }
}
