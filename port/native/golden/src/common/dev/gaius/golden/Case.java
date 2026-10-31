package dev.gaius.golden;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One golden fixture line: {"kind", "params", "inputs", "outputs"}.
 *
 * <p>Values are encoded by their Java type: Double and Float as 16-char
 * lowercase hex of the raw double bits (a float is widened first, which is
 * exact), Long as a decimal string, Integer as a JSON number, String and
 * Boolean as themselves. Lists, arrays and maps nest.
 */
final class Case {
    final String kind;
    final Map<String, Object> params = new LinkedHashMap<>();
    final List<List<Object>> inputs = new ArrayList<>();
    final List<Object> outputs = new ArrayList<>();

    Case(String kind) {
        this.kind = kind;
    }

    /** Adds a param; a key may be set once so two writers cannot silently clobber each other. */
    Case param(String key, Object value) {
        if (params.putIfAbsent(key, value) != null) {
            throw new IllegalStateException("duplicate fixture param \"" + key + "\" in " + kind);
        }
        return this;
    }

    Case input(Object... row) {
        inputs.add(Arrays.asList(row));
        return this;
    }

    Case output(Object value) {
        outputs.add(value);
        return this;
    }

    String toJson() {
        StringBuilder out = new StringBuilder(256 + 64 * outputs.size());
        out.append("{\"kind\":");
        appendValue(out, kind);
        out.append(",\"params\":");
        appendValue(out, params);
        out.append(",\"inputs\":");
        appendValue(out, inputs);
        out.append(",\"outputs\":");
        appendValue(out, outputs);
        return out.append('}').toString();
    }

    static String hex(double value) {
        String bits = Long.toHexString(Double.doubleToRawLongBits(value));
        return "0000000000000000".substring(bits.length()) + bits;
    }

    private static void appendValue(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case Double d -> out.append('"').append(hex(d)).append('"');
            case Float f -> out.append('"').append(hex((double) f)).append('"');
            case Long l -> out.append('"').append(l).append('"');
            case Integer i -> out.append(i);
            case Boolean b -> out.append(b);
            case String s -> appendString(out, s);
            case double[] array -> {
                List<Object> boxed = new ArrayList<>(array.length);
                for (double d : array) {
                    boxed.add(d);
                }
                appendValue(out, boxed);
            }
            case int[] array -> {
                List<Object> boxed = new ArrayList<>(array.length);
                for (int i : array) {
                    boxed.add(i);
                }
                appendValue(out, boxed);
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    appendValue(out, list.get(i));
                }
                out.append(']');
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    appendString(out, entry.getKey().toString());
                    out.append(':');
                    appendValue(out, entry.getValue());
                }
                out.append('}');
            }
            default -> throw new IllegalArgumentException(
                    "unsupported fixture value type " + value.getClass().getName());
        }
    }

    private static void appendString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
