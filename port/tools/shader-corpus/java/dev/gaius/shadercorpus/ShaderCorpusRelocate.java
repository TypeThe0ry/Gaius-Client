package dev.gaius.shadercorpus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/**
 * Copies the vanilla LWJGL binding classes Shaderc and Spvc (and their
 * $Functions holders) under dev/gaius/shadercorpus/nat as NativeShaderc and
 * NativeSpvc.  In shim mode the harness runs Minecraft against the
 * browser-patched Shaderc/Spvc (whose bodies call BrowserShaderc/BrowserSpvc);
 * the native toolchain behind those shims reaches the real native libraries
 * through these relocated copies.
 *
 * usage: ShaderCorpusRelocate SHADERC_JAR SPVC_JAR OUTPUT_DIR
 */
public final class ShaderCorpusRelocate {
    private ShaderCorpusRelocate() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: ShaderCorpusRelocate SHADERC_JAR SPVC_JAR OUTPUT_DIR");
        }
        Path output = Path.of(args[2]);
        relocate(args[0], "org/lwjgl/util/shaderc/Shaderc", "dev/gaius/shadercorpus/nat/NativeShaderc", output);
        relocate(args[1], "org/lwjgl/util/spvc/Spvc", "dev/gaius/shadercorpus/nat/NativeSpvc", output);
    }

    private static void relocate(String jar, String from, String to, Path output) throws IOException {
        Map<String, String> mapping = new HashMap<>();
        mapping.put(from, to);
        mapping.put(from + "$Functions", to + "$Functions");
        SimpleRemapper remapper = new SimpleRemapper(mapping);
        try (ZipFile zip = new ZipFile(jar)) {
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                var source = zip.getEntry(entry.getKey() + ".class");
                if (source == null) {
                    throw new IllegalStateException(entry.getKey() + " not found in " + jar);
                }
                byte[] bytes;
                try (var stream = zip.getInputStream(source)) {
                    bytes = stream.readAllBytes();
                }
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
                Path target = output.resolve(entry.getValue() + ".class");
                Files.createDirectories(target.getParent());
                Files.write(target, writer.toByteArray());
            }
        }
        System.out.println("Relocated " + from + " to " + to);
    }
}
