package dev.gaius.classlib;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.CRC32;

/** UUIDs (offline players), checksums, Base64, big numbers, BitSet and collection printing. */
final class Misc {
    private Misc() {
    }

    static void run() {
        for (String name : new String[] {"Steve", "Alex", "GaiusFile263", "élève", ""}) {
            Out.check("offlineUuid." + name, () -> UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)
                    .getBytes(StandardCharsets.UTF_8)).toString());
        }
        String[] uuids = {"069a79f4-44e9-4726-a5be-fca90e38aaf5", "069A79F4-44E9-4726-A5BE-FCA90E38AAF5",
            "1-2-3-4-5", "069a79f444e94726a5befca90e38aaf5", "zzzzzzzz-44e9-4726-a5be-fca90e38aaf5",
            "00000000-0000-0000-0000-000000000000", "ffffffff-ffff-ffff-ffff-ffffffffffff"};
        for (int i = 0; i < uuids.length; i++) {
            String text = uuids[i];
            Out.check("uuid.fromString." + i, () -> {
                UUID uuid = UUID.fromString(text);
                return uuid + " v" + uuid.version() + " var" + uuid.variant() + " " + uuid.getMostSignificantBits();
            });
        }
        Out.check("uuid.compare", () -> new UUID(-1, 0).compareTo(new UUID(1, 0)) + " " + new UUID(0, -1).compareTo(new UUID(0, 1)));
        Out.check("crc32", () -> {
            CRC32 crc = new CRC32();
            crc.update("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.US_ASCII));
            long a = crc.getValue();
            crc.update(new byte[] {1, 2, 3}, 1, 2);
            crc.update(255);
            return Long.toHexString(a) + " " + Long.toHexString(crc.getValue());
        });
        byte[] data = {0, -1, 2, -3, 4, 5, 6, 7, 8, 9, 10, (byte) 0xFB, (byte) 0xFF};
        Out.check("base64", () -> Base64.getEncoder().encodeToString(data) + " " + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(data) + " " + Arrays.toString(Base64.getDecoder().decode("AP8C/QQ=")));
        Out.check("base64.invalid", () -> Base64.getDecoder().decode("AP8C*QQ="));
        Out.check("bigInteger", () -> {
            BigInteger a = new BigInteger("123456789012345678901234567890");
            BigInteger b = BigInteger.valueOf(-987654321L);
            return a.multiply(b) + " " + a.mod(BigInteger.valueOf(97)) + " " + a.modPow(BigInteger.TEN, BigInteger.valueOf(1000003))
                    + " " + a.toString(36) + " " + a.bitLength() + " " + b.bitCount() + " " + a.shiftRight(70) + " "
                    + a.negate().shiftRight(3) + " " + a.gcd(BigInteger.valueOf(1234567890)) + " " + Arrays.toString(b.toByteArray());
        });
        Out.check("bigDecimal", () -> {
            BigDecimal a = new BigDecimal("2.675");
            return a.setScale(2, RoundingMode.HALF_UP) + " " + new BigDecimal(2.675).toPlainString().substring(0, 20) + " "
                    + BigDecimal.valueOf(1, -3).toPlainString() + " " + new BigDecimal("1E+3") + " "
                    + BigDecimal.ONE.divide(BigDecimal.valueOf(3), 10, RoundingMode.HALF_EVEN) + " "
                    + new BigDecimal("1.50").stripTrailingZeros() + " " + BigDecimal.valueOf(0.1) + " "
                    + new BigDecimal("123.456").round(new java.math.MathContext(4));
        });
        Out.check("bitSet", () -> {
            BitSet bits = new BitSet();
            bits.set(1);
            bits.set(64, 70);
            bits.set(200);
            bits.clear(66);
            return bits + " " + bits.cardinality() + " " + bits.length() + " " + bits.nextSetBit(2) + " "
                    + bits.nextClearBit(64) + " " + bits.previousSetBit(199) + " " + Arrays.toString(bits.toLongArray());
        });
        Out.check("bitSet.valueOf", () -> BitSet.valueOf(new long[] {5L, -1L}).toString().length() + " "
                + BitSet.valueOf(new byte[] {1, (byte) 0x80}));
        Out.check("arrays.toString", () -> Arrays.toString(new double[] {1, -0.0, 1e10, Double.NaN}) + " "
                + Arrays.toString(new float[] {1.5f, 1e10f}) + " " + Arrays.deepToString(new Object[] {new int[] {1}, null})
                + " " + Arrays.toString((int[]) null) + " " + Arrays.toString(new char[] {'a', 'b'}));
        Out.check("collections.toString", () -> List.of(1, 2) + " " + new TreeMap<>(java.util.Map.of("b", 1, "a", 2))
                + " " + new ArrayDeque<>(List.of("x", "y")) + " " + new ArrayList<>());
        Out.check("arraycopy.overlap", () -> {
            int[] a = {0, 1, 2, 3, 4, 5, 6, 7};
            System.arraycopy(a, 0, a, 2, 5);
            int[] b = {0, 1, 2, 3, 4, 5, 6, 7};
            System.arraycopy(b, 2, b, 0, 5);
            return Arrays.toString(a) + " " + Arrays.toString(b);
        });
        Out.check("arraycopy.bounds", () -> {
            System.arraycopy(new int[4], 2, new int[4], 0, 3);
            return "copied";
        });
        Out.check("arraycopy.type", () -> {
            System.arraycopy(new Object[] {"a", 1}, 0, new String[2], 0, 2);
            return "copied";
        });
        Out.check("list.get.bounds", () -> new ArrayList<>().get(0));
        Out.check("list.of.null", () -> List.of("a", null));
        Out.check("list.of.immutable", () -> {
            List.of(1).add(2);
            return "added";
        });
        Out.check("iterator.concurrentModification", () -> {
            List<Integer> list = new ArrayList<>(List.of(1, 2, 3));
            for (Integer value : list) {
                if (value == 1) {
                    list.remove(value);
                }
            }
            return list;
        });
        Out.check("subList", () -> {
            List<Integer> list = new ArrayList<>(List.of(1, 2, 3, 4, 5));
            list.subList(1, 3).clear();
            return list;
        });
        Out.check("arrays.asList.set", () -> {
            String[] array = {"a", "b"};
            Arrays.asList(array).set(0, "z");
            return array[0];
        });
        Out.check("stringValueOfDoubleArrayHash", () -> Arrays.hashCode(new double[] {0.1}) + " " + Arrays.equals(
                new double[] {-0.0}, new double[] {0.0}) + " " + Arrays.equals(new double[] {Double.NaN}, new double[] {Double.NaN}));
        Out.check("objectsRequire", () -> java.util.Objects.requireNonNullElse(null, "fallback") + " "
                + java.util.Objects.checkIndex(2, 3));
        Out.check("objectsCheckIndex.bad", () -> java.util.Objects.checkIndex(3, 3));
    }
}
