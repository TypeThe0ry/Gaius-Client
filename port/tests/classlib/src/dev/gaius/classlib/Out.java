package dev.gaius.classlib;

import java.util.function.Supplier;

/**
 * Output of the suite: one {@code key = value} line per case, compared line by line across the
 * JVM, the TeaVM JavaScript build and the TeaVM WebAssembly GC build by runner/compare.mjs.
 * Lines that start with {@code #} are informative and never compared. Values are ASCII: other
 * characters are escaped, so the three runtimes print byte-identical lines for equal values.
 */
public final class Out {
    private static String prefix = "";

    private Out() {
    }

    static void section(String name) {
        prefix = name + ".";
    }

    public static void put(String key, Object value) {
        System.out.println(ascii(prefix + key) + " = " + ascii(String.valueOf(value)));
    }

    public static void note(String text) {
        System.out.println("# " + ascii(text));
    }

    /** Runs one case and prints its value, or the simple name of the exception it throws. */
    public static void check(String key, Supplier<Object> body) {
        put(key, eval(body));
    }

    public static String eval(Supplier<Object> body) {
        try {
            return show(body.get());
        } catch (Throwable e) {
            return "throws " + e.getClass().getSimpleName();
        }
    }

    /** Exact text for floating-point values (raw bits), plain text for everything else. */
    public static String show(Object value) {
        if (value instanceof Double d) {
            return d + " [" + hex(d) + "]";
        }
        if (value instanceof Float f) {
            return f + "f [" + hex(f) + "]";
        }
        return String.valueOf(value);
    }

    /**
     * Raw bits in hex. Every NaN prints as "NaN": the sign and payload of a NaN result are not
     * specified by Java (HotSpot on x86 returns the negative quiet NaN, JavaScript engines other
     * patterns) and only doubleToRawLongBits can observe them. The HashCodes section checks the
     * canonical NaN of doubleToLongBits on its own.
     */
    public static String hex(double value) {
        return value != value ? "NaN" : Long.toHexString(Double.doubleToRawLongBits(value));
    }

    public static String hex(float value) {
        return value != value ? "NaN" : Integer.toHexString(Float.floatToRawIntBits(value));
    }

    static String ascii(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7F && c != '\\') {
                if (out != null) {
                    out.append(c);
                }
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length() + 16).append(text, 0, i);
            }
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\n') {
                out.append("\\n");
            } else {
                out.append("\\u");
                String h = Integer.toHexString(c);
                for (int pad = h.length(); pad < 4; pad++) {
                    out.append('0');
                }
                out.append(h);
            }
        }
        return out == null ? text : out.toString();
    }

    /** 64-bit FNV-1a over the UTF-16 units of a string. */
    public static final class Digest {
        private long hash = 0xcbf29ce484222325L;
        private int count;

        public Digest add(String text) {
            for (int i = 0; i < text.length(); i++) {
                hash ^= text.charAt(i);
                hash *= 0x100000001b3L;
            }
            hash ^= 0x1F;
            hash *= 0x100000001b3L;
            count++;
            return this;
        }

        public Digest add(long value) {
            for (int i = 0; i < 8; i++) {
                hash ^= (value >>> (i * 8)) & 0xFF;
                hash *= 0x100000001b3L;
            }
            count++;
            return this;
        }

        @Override
        public String toString() {
            return count + ":" + Long.toHexString(hash);
        }
    }
}
