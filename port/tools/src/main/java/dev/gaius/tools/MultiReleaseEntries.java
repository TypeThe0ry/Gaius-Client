package dev.gaius.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;

/**
 * Multi-release scope of the jar-scanning LWJGL patchers
 * (LwjglUnsafeAccessPatcher, NativeMethodFallbackPatcher,
 * LwjglCallbackDescriptorPatcher).
 *
 * <p>LWJGL 3.4.3 ships {@code META-INF/versions/27} classes with class-file
 * major version 71, which this ASM cannot parse.  TeaVM reads only the base
 * entries of a jar ({@code ZipFile.getEntry}, no multi-release lookup), so a
 * patched versioned copy has no effect on the browser build.  The scanners
 * therefore leave {@code META-INF/versions/} alone, but only for a jar whose
 * versioned classes ASM cannot read: older jars (LWJGL 3.4.1, whose
 * versioned classes go up to major 69) are scanned exactly as before, which
 * keeps their output byte-identical.</p>
 */
final class MultiReleaseEntries {
    static final String VERSIONS_PREFIX = "META-INF/versions/";

    private MultiReleaseEntries() {
    }

    /** True when the scanners must leave every versioned entry of the jar unpatched. */
    static boolean leaveVersionedEntries(ZipFile jar) throws IOException {
        int unreadable = 0;
        int versioned = 0;
        int newestMajor = 0;
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!isVersionedClass(entry)) {
                continue;
            }
            versioned++;
            byte[] bytes;
            try (InputStream stream = jar.getInputStream(entry)) {
                bytes = stream.readAllBytes();
            }
            if (bytes.length >= 8) {
                newestMajor = Math.max(newestMajor, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
            }
            try {
                new ClassReader(bytes);
            } catch (IllegalArgumentException unsupported) {
                unreadable++;
            }
        }
        if (unreadable == 0) {
            return false;
        }
        System.out.println("LWJGL multi-release: leaving " + versioned + " " + VERSIONS_PREFIX
                + " classes unpatched (" + unreadable + " beyond ASM, newest class major "
                + newestMajor + "; TeaVM reads the base entries)");
        return true;
    }

    static boolean isVersioned(ZipEntry entry) {
        return entry.getName().startsWith(VERSIONS_PREFIX);
    }

    private static boolean isVersionedClass(ZipEntry entry) {
        return !entry.isDirectory()
                && entry.getName().startsWith(VERSIONS_PREFIX)
                && entry.getName().endsWith(".class");
    }
}
