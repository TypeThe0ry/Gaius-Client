package dev.gaius.classlib;

import java.nio.charset.Charset;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Process defaults that other sections depend on. The runner starts every runtime with the
 * time zone UTC and the locale en_US.
 */
final class Env {
    private Env() {
    }

    static void run() {
        Out.note("java.version=" + System.getProperty("java.version") + " java.vm.name="
                + System.getProperty("java.vm.name") + " os.name=" + System.getProperty("os.name"));
        Out.check("locale", () -> Locale.getDefault().toString());
        Out.check("locale.language", () -> Locale.getDefault().getLanguage());
        Out.check("timezone", () -> TimeZone.getDefault().getID());
        Out.check("timezone.rawOffset", () -> TimeZone.getDefault().getRawOffset());
        // Host properties of the reference JVM (Windows or Linux), informative only.
        Out.note("lineSeparator.length=" + System.lineSeparator().length() + " file.separator="
                + System.getProperty("file.separator"));
        Out.check("defaultCharset", () -> Charset.defaultCharset().name());
        Out.check("nanoTime.monotonic", () -> {
            long a = System.nanoTime();
            long b = System.nanoTime();
            return b >= a;
        });
        Out.check("currentTimeMillis.after2020", () -> System.currentTimeMillis() > 1577836800000L);
    }
}
