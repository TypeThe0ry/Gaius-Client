package dev.gaius.classlib.repro;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

/**
 * Direct ByteBuffer absolute and relative accessors against JVM semantics
 * (TeaVMClasslibWasmGcPatcher: getLong(int) position, putLong(int,long) bound, put(ByteBuffer)
 * offset, and the bound checks of the 14 absolute accessors). One line per case.
 */
public final class BufCompare {
    private BufCompare() {
    }

    static ByteBuffer fresh(ByteOrder order) {
        ByteBuffer b = ByteBuffer.allocateDirect(16).order(order);
        for (int i = 0; i < 16; i++) {
            b.put(i, (byte) (0x10 + i));
        }
        b.position(3);
        return b;
    }

    static void run(String name, ByteBuffer b, Supplier<Object> body) {
        String result;
        try {
            Object value = body.get();
            if (value instanceof Float f) {
                value = "f:" + Integer.toHexString(Float.floatToRawIntBits(f));
            } else if (value instanceof Double d) {
                value = "d:" + Long.toHexString(Double.doubleToRawLongBits(d));
            } else if (value instanceof Long l) {
                value = "j:" + Long.toHexString(l);
            } else if (value instanceof ByteBuffer) {
                value = "buf";
            }
            result = String.valueOf(value);
        } catch (Throwable e) {
            result = "throws " + e.getClass().getSimpleName();
        }
        StringBuilder bytes = new StringBuilder();
        int n = Math.min(16, b.capacity());
        for (int i = 0; i < n; i++) {
            bytes.append(Integer.toHexString(b.get(i) & 0xFF)).append(i < n - 1 ? "," : "");
        }
        System.out.println(name + " -> " + result + " pos=" + b.position() + " lim=" + b.limit()
                + " mem=" + bytes);
    }

    public static void main(String[] args) {
        int[] indexes = {-1, 0, 8, 9, 12, 13, 14, 15, 16};
        for (ByteOrder order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
            String o = order == ByteOrder.BIG_ENDIAN ? "BE" : "LE";
            for (int i : indexes) {
                ByteBuffer b;
                b = fresh(order); ByteBuffer b1 = b; run(o + " getChar(" + i + ")", b, () -> (int) b1.getChar(i));
                b = fresh(order); ByteBuffer b2 = b; run(o + " getShort(" + i + ")", b, () -> b2.getShort(i));
                b = fresh(order); ByteBuffer b3 = b; run(o + " getInt(" + i + ")", b, () -> b3.getInt(i));
                b = fresh(order); ByteBuffer b4 = b; run(o + " getFloat(" + i + ")", b, () -> b4.getFloat(i));
                b = fresh(order); ByteBuffer b5 = b; run(o + " getLong(" + i + ")", b, () -> b5.getLong(i));
                b = fresh(order); ByteBuffer b6 = b; run(o + " getDouble(" + i + ")", b, () -> b6.getDouble(i));
                b = fresh(order); ByteBuffer c1 = b; run(o + " putChar(" + i + ")", b, () -> c1.putChar(i, (char) 0xA1A2));
                b = fresh(order); ByteBuffer c2 = b; run(o + " putShort(" + i + ")", b, () -> c2.putShort(i, (short) 0xA1A2));
                b = fresh(order); ByteBuffer c3 = b; run(o + " putInt(" + i + ")", b, () -> c3.putInt(i, 0xA1A2A3A4));
                b = fresh(order); ByteBuffer c4 = b; run(o + " putFloat(" + i + ")", b, () -> c4.putFloat(i, Float.intBitsToFloat(0x3FA1A2A3)));
                b = fresh(order); ByteBuffer c5 = b; run(o + " putLong(" + i + ")", b, () -> c5.putLong(i, 0xA1A2A3A4A5A6A7A8L));
                b = fresh(order); ByteBuffer c6 = b; run(o + " putDouble(" + i + ")", b, () -> c6.putDouble(i, Double.longBitsToDouble(0x3FA1A2A3A4A5A6A7L)));
            }
            for (int p : new int[] {8, 9, 12, 13, 14, 15}) {
                ByteBuffer b;
                b = fresh(order); b.position(p); ByteBuffer r1 = b; run(o + " rel getShort@" + p, b, () -> r1.getShort());
                b = fresh(order); b.position(p); ByteBuffer r2 = b; run(o + " rel getInt@" + p, b, () -> r2.getInt());
                b = fresh(order); b.position(p); ByteBuffer r3 = b; run(o + " rel getFloat@" + p, b, () -> r3.getFloat());
                b = fresh(order); b.position(p); ByteBuffer r4 = b; run(o + " rel getLong@" + p, b, () -> r4.getLong());
                b = fresh(order); b.position(p); ByteBuffer r5 = b; run(o + " rel getDouble@" + p, b, () -> r5.getDouble());
                b = fresh(order); b.position(p); ByteBuffer s1 = b; run(o + " rel putShort@" + p, b, () -> s1.putShort((short) 0xA1A2));
                b = fresh(order); b.position(p); ByteBuffer s2 = b; run(o + " rel putInt@" + p, b, () -> s2.putInt(0xA1A2A3A4));
                b = fresh(order); b.position(p); ByteBuffer s3 = b; run(o + " rel putFloat@" + p, b, () -> s3.putFloat(Float.intBitsToFloat(0x3FA1A2A3)));
                b = fresh(order); b.position(p); ByteBuffer s4 = b; run(o + " rel putLong@" + p, b, () -> s4.putLong(0xA1A2A3A4A5A6A7A8L));
                b = fresh(order); b.position(p); ByteBuffer s5 = b; run(o + " rel putDouble@" + p, b, () -> s5.putDouble(Double.longBitsToDouble(0x3FA1A2A3A4A5A6A7L)));
            }
        }
        // put(ByteBuffer) from a heap source with a non-zero array offset, a direct source and a
        // read-only heap source.
        byte[] backing = new byte[32];
        for (int i = 0; i < 32; i++) {
            backing[i] = (byte) (0x40 + i);
        }
        ByteBuffer heapSlice = ByteBuffer.wrap(backing, 4, 20).slice();
        heapSlice.position(2).limit(6);
        ByteBuffer dst = fresh(ByteOrder.BIG_ENDIAN);
        dst.position(1);
        run("put(heap slice off=4 pos=2 len=4)", dst, () -> dst.put(heapSlice));
        System.out.println("  src pos=" + heapSlice.position());
        ByteBuffer direct = fresh(ByteOrder.BIG_ENDIAN);
        direct.position(10).limit(14);
        ByteBuffer dst2 = ByteBuffer.allocateDirect(16);
        for (int i = 0; i < 16; i++) {
            dst2.put(i, (byte) 0);
        }
        dst2.position(5);
        run("put(direct pos=10 len=4)", dst2, () -> dst2.put(direct));
        ByteBuffer ro = ByteBuffer.wrap(backing, 8, 8).asReadOnlyBuffer();
        ByteBuffer dst3 = fresh(ByteOrder.BIG_ENDIAN);
        dst3.position(2);
        run("put(read-only heap pos=8 len=8)", dst3, () -> dst3.put(ro));
        ByteBuffer big = ByteBuffer.wrap(new byte[20]);
        ByteBuffer dst4 = fresh(ByteOrder.BIG_ENDIAN);
        run("put(too large)", dst4, () -> dst4.put(big));
        // A direct slice and absolute reads through it.
        ByteBuffer base = fresh(ByteOrder.BIG_ENDIAN);
        base.position(4);
        ByteBuffer slice = base.slice();
        run("slice getInt(0)", slice, () -> slice.getInt(0));
        run("slice getLong(4)", slice, () -> slice.getLong(4));
        run("slice getLong(5)", slice, () -> slice.getLong(5));
        // An index whose old bound check overflows (index + 3 wraps negative).
        ByteBuffer ov2 = fresh(ByteOrder.BIG_ENDIAN);
        run("getInt(MAX_VALUE-1)", ov2, () -> ov2.getInt(Integer.MAX_VALUE - 1));
        ByteBuffer ov = fresh(ByteOrder.BIG_ENDIAN);
        run("getFloat(MAX_VALUE-1)", ov, () -> ov.getFloat(Integer.MAX_VALUE - 1));
        // Every absolute accessor at an index whose old bound check overflows, and a negative
        // index on a read-only buffer (ReadOnlyBufferException comes first on the JVM).
        int huge = Integer.MAX_VALUE - 1;
        ByteBuffer o1 = fresh(ByteOrder.BIG_ENDIAN);
        run("get(MAX_VALUE-1)", o1, () -> o1.get(huge));
        run("getChar(MAX_VALUE-1)", o1, () -> (int) o1.getChar(huge));
        run("getShort(MAX_VALUE-1)", o1, () -> o1.getShort(huge));
        run("getLong(MAX_VALUE-1)", o1, () -> o1.getLong(huge));
        run("getDouble(MAX_VALUE-1)", o1, () -> o1.getDouble(huge));
        run("getLong(MAX_VALUE-6)", o1, () -> o1.getLong(Integer.MAX_VALUE - 6));
        run("put(MAX_VALUE-1)", o1, () -> o1.put(huge, (byte) 1));
        run("putChar(MAX_VALUE-1)", o1, () -> o1.putChar(huge, 'x'));
        run("putShort(MAX_VALUE-1)", o1, () -> o1.putShort(huge, (short) 1));
        run("putInt(MAX_VALUE-1)", o1, () -> o1.putInt(huge, 1));
        run("putFloat(MAX_VALUE-1)", o1, () -> o1.putFloat(huge, 1f));
        run("putLong(MAX_VALUE-1)", o1, () -> o1.putLong(huge, 1L));
        run("putDouble(MAX_VALUE-1)", o1, () -> o1.putDouble(huge, 1d));
        run("putLong(MAX_VALUE-6)", o1, () -> o1.putLong(Integer.MAX_VALUE - 6, 1L));
        ByteBuffer ro2 = fresh(ByteOrder.BIG_ENDIAN).asReadOnlyBuffer();
        run("read-only putInt(-1)", ro2, () -> ro2.putInt(-1, 1));
        run("read-only putDouble(14)", ro2, () -> ro2.putDouble(14, 1d));
        run("read-only putFloat(0)", ro2, () -> ro2.putFloat(0, 1f));
        run("read-only getInt(12)", ro2, () -> ro2.getInt(12));
        run("read-only getInt(13)", ro2, () -> ro2.getInt(13));
        ByteBuffer empty = ByteBuffer.allocateDirect(2);
        run("2-byte getInt(0)", empty, () -> empty.getInt(0));
        run("2-byte getShort(0)", empty, () -> (int) empty.getShort(0));
        run("2-byte getShort(1)", empty, () -> (int) empty.getShort(1));
    }
}
