package dev.gaius.classlib;

import java.util.Locale;

/**
 * String.format as vanilla uses it (debug screen, crash reports, timings, coordinates,
 * statistics) plus the corner cases of java.util.Formatter: rounding (HALF_UP of the decimal
 * representation), padding, flags, grouping, argument indexes and errors.
 */
final class Format {
    private static int index;

    private Format() {
    }

    static void run() {
        Locale[] locales = {Locale.ROOT, null};
        for (Locale locale : locales) {
            String tag = locale == null ? "default" : "root";
            index = 0;
            f(tag, locale, "%d", 42);
            f(tag, locale, "%d", Long.MIN_VALUE);
            f(tag, locale, "%5d|%-5d|%05d", 42, 42, -42);
            f(tag, locale, "%+d % d %(d", 7, 7, -7);
            f(tag, locale, "%,d", 1234567890);
            f(tag, locale, "%,d", -1234L);
            f(tag, locale, "%x %X %o", 255, 255, 8);
            f(tag, locale, "%x", -1);
            f(tag, locale, "%x", Long.MIN_VALUE);
            f(tag, locale, "%08X", 0xBEEF);
            f(tag, locale, "%#x %#o", 255, 8);
            f(tag, locale, "%.3f", Math.PI);
            f(tag, locale, "%.2f", 1.005);
            f(tag, locale, "%.2f", 2.675);
            f(tag, locale, "%.1f", 0.05);
            f(tag, locale, "%.1f", 0.15);
            f(tag, locale, "%.1f", 0.25);
            f(tag, locale, "%.1f", 0.35);
            f(tag, locale, "%.0f %.0f %.0f %.0f", 0.5, 1.5, 2.5, -0.5);
            f(tag, locale, "%.3f", 1e-10);
            f(tag, locale, "%.3f", -0.0);
            f(tag, locale, "%.2f", 123456789.987654321);
            f(tag, locale, "%.2f", 1e20);
            f(tag, locale, "%f", 1.0 / 3);
            f(tag, locale, "%10.2f|%-10.2f|%010.2f", Math.E, Math.E, -Math.E);
            f(tag, locale, "%,.2f", 1234567.891);
            f(tag, locale, "%.1f", 59.95f);
            f(tag, locale, "%.3f", 0.1f);
            f(tag, locale, "%f %f %f", Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
            f(tag, locale, "%e", 12345.6789);
            f(tag, locale, "%.2e", 0.000123456);
            f(tag, locale, "%E", 1e100);
            f(tag, locale, "%g", 12345.6789);
            f(tag, locale, "%g", 0.00001234);
            f(tag, locale, "%.3g", 1234567.0);
            f(tag, locale, "%a", 1.0);
            f(tag, locale, "%s|%10s|%-10s|%.3s", "abc", "abc", "abc", "abcdef");
            f(tag, locale, "%s %s", null, true);
            f(tag, locale, "%S", "mixed Case");
            f(tag, locale, "%b %b %B", null, "x", false);
            f(tag, locale, "%c%c%c", 'a', 0x1F600, (int) 'z');
            f(tag, locale, "%h", "hello");
            f(tag, locale, "100%%");
            f(tag, locale, "%2$s %1$s %2$s", "a", "b");
            f(tag, locale, "%s %<s %s", "x", "y");
            f(tag, locale, "%s", 1.0f);
            f(tag, locale, "%s", 100.0);
            f(tag, locale, "%s", 1e7);
            f(tag, locale, "%d%%", 50);
            // Vanilla shapes.
            f(tag, locale, "XYZ: %.3f / %.5f / %.3f", 123.456789, 64.0, -8.5);
            f(tag, locale, "Block: %d %d %d", 12, 64, -8);
            f(tag, locale, "Chunk: %d %d %d [%d %d in r.%d.%d.mca]", 1, 4, -1, 12, 0, 0, -1);
            f(tag, locale, "%d fps T: %s%s%s", 60, "inf", " vsync", "");
            f(tag, locale, "Facing: %s (%s) (%.1f / %.1f)", "north", "Towards negative Z", -179.95f, 12.04f);
            f(tag, locale, "%02d:%02d:%02d", 1, 2, 3);
            f(tag, locale, "%.2fMB", 1536.0 / 1024);
            f(tag, locale, "Mem: % 2d%% %03d/%03dMB", 42, 512, 2048);
            f(tag, locale, "%s: %s", "key", 3.25);
            f(tag, locale, "%3d%%", 7);
            f(tag, locale, "%.1f%%", 99.95);
            f(tag, locale, "%n");
            // Errors.
            f(tag, locale, "%d", "text");
            f(tag, locale, "%q", 1);
            f(tag, locale, "%.2d", 5);
            f(tag, locale, "%-d", 5);
            f(tag, locale, "%", 5);
        }
        Out.check("formatted", () -> "%s=%d".formatted("k", 3));
        Out.check("upper.default", () -> String.format("%S", "ß"));
    }

    static void f(String tag, Locale locale, String pattern, Object... args) {
        // The key has no spaces (differences.txt globs); the pattern leads the value.
        String key = tag + "." + (index++);
        Out.put(key, pattern + " -> " + Out.eval(() -> locale == null ? String.format(pattern, args)
                : String.format(locale, pattern, args)));
    }
}
