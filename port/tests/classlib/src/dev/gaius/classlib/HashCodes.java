package dev.gaius.classlib;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Value-based hash codes; they decide the bucket and with it the HashMap iteration order. */
final class HashCodes {
    private HashCodes() {
    }

    static void run() {
        String[] strings = {"", "a", "minecraft:stone", "Aa", "BB", "été", "世界",
            "😀", "polygenelubricants", "The quick brown fox jumps over the lazy dog"};
        for (int i = 0; i < strings.length; i++) {
            String s = strings[i];
            Out.check("string." + i, () -> s.hashCode());
        }
        long[] longs = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x100000000L, 0x123456789ABCDEFL};
        for (long v : longs) {
            Out.check("long." + v, () -> Long.valueOf(v).hashCode());
        }
        long[] doubleBits = {0, 0x8000000000000000L, 0x7ff8000000000000L, 0x7ff8000000000001L,
            0xfff0000000000000L, 0x3ff8000000000000L, 0x400921fb54442d18L, 1};
        for (long bits : doubleBits) {
            double d = Double.longBitsToDouble(bits);
            Out.check("double." + Long.toHexString(bits), () -> Double.valueOf(d).hashCode());
            Out.check("doubleToLongBits." + Long.toHexString(bits), () -> Long.toHexString(Double.doubleToLongBits(d)));
        }
        int[] floatBits = {0, 0x80000000, 0x7fc00000, 0x7fc00001, 0xff800000, 0x3fc00000, 1};
        for (int bits : floatBits) {
            float f = Float.intBitsToFloat(bits);
            Out.check("float." + Integer.toHexString(bits), () -> Float.valueOf(f).hashCode());
            Out.check("floatToIntBits." + Integer.toHexString(bits), () -> Integer.toHexString(Float.floatToIntBits(f)));
        }
        Out.check("boolean", () -> Boolean.TRUE.hashCode() + "," + Boolean.FALSE.hashCode());
        Out.check("char", () -> Character.valueOf('￿').hashCode());
        Out.check("byte", () -> Byte.valueOf((byte) -3).hashCode());
        Out.check("short", () -> Short.valueOf((short) -3).hashCode());
        Out.check("list.of", () -> List.of(1, 2, 3).hashCode());
        Out.check("arrayList", () -> new ArrayList<>(List.of("a", "b", "c")).hashCode());
        Out.check("linkedList", () -> new LinkedList<>(List.of("a", "b", "c")).hashCode());
        Out.check("arrays.asList", () -> Arrays.asList(1.5, -0.0, null).hashCode());
        Out.check("arrays.int", () -> Arrays.hashCode(new int[] {1, -2, 3}));
        Out.check("arrays.long", () -> Arrays.hashCode(new long[] {1, -2, Long.MIN_VALUE}));
        Out.check("arrays.double", () -> Arrays.hashCode(new double[] {1.5, -0.0, Double.NaN}));
        Out.check("arrays.float", () -> Arrays.hashCode(new float[] {1.5f, -0.0f, Float.NaN}));
        Out.check("arrays.byte", () -> Arrays.hashCode(new byte[] {1, -2, 127}));
        Out.check("arrays.object", () -> Arrays.hashCode(new Object[] {"x", 2, null}));
        Out.check("arrays.deep", () -> Arrays.deepHashCode(new Object[] {new int[] {1}, new String[] {"a"}}));
        Out.check("objects.hash", () -> Objects.hash("a", 1, 2.5, null));
        Out.check("objects.hashCode.null", () -> Objects.hashCode(null));
        Out.check("simpleEntry", () -> new AbstractMap.SimpleEntry<>("k", 7).hashCode());
        Out.check("map.entry", () -> Map.entry("k", 7).hashCode());
        Out.check("hashMap", () -> {
            Map<String, Integer> m = new HashMap<>();
            m.put("a", 1);
            m.put("b", 2);
            return m.hashCode();
        });
        Out.check("treeMap", () -> new TreeMap<>(Map.of("a", 1, "b", 2)).hashCode());
        Out.check("hashSet", () -> new HashSet<>(List.of("a", "b", "c")).hashCode());
        Out.check("map.of", () -> Map.of("a", 1, "b", 2).hashCode());
        Out.check("set.of", () -> java.util.Set.of("a", "b").hashCode());
        Out.check("record", () -> new HashOrder.Pos(1, 2, 3).hashCode());
        Out.check("record.toString", () -> new HashOrder.Pos(1, -2, 3).toString());
        Out.check("record.equals", () -> new HashOrder.Pos(1, 2, 3).equals(new HashOrder.Pos(1, 2, 3)));
        Out.check("uuid", () -> new UUID(0x123456789ABCDEFL, -2L).hashCode());
        Out.check("bigInteger", () -> new BigInteger("-123456789012345678901234567890").hashCode());
        Out.check("bigDecimal", () -> new BigDecimal("123.4500").hashCode());
        Out.check("optional", () -> Optional.of("x").hashCode() + "," + Optional.empty().hashCode());
        Out.check("string.chars", () -> new String(new char[] {'a', '\u0000', 'b'}).hashCode());
    }
}
