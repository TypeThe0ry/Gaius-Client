package dev.gaius.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** Retain every other core patch, disabling only resource chunking for size comparisons. */
public final class ResourceBaseline {
    public static void main(String[] args) throws Exception {
        try (ZipFile jar = new ZipFile(args[0])) {
            for (String name : new String[] {
                    "org/teavm/backend/wasm/dependencies/WasmGCResourceDependency",
                    "org/teavm/backend/wasm/runtime/WasmGCResources",
                    "org/teavm/backend/wasm/intrinsics/WasmGCResourcesIntrinsic"}) {
                byte[] bytes;
                try (var input = jar.getInputStream(jar.getEntry(name + ".class"))) {
                    bytes = input.readAllBytes();
                }
                if (name.endsWith("WasmGCResources")) {
                    ClassNode node = new ClassNode();
                    new ClassReader(bytes).accept(node, 0);
                    TeaVMCoreBrowserPatcher.patchWasmGcResourceRead(node);
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    node.accept(writer);
                    bytes = writer.toByteArray();
                }
                Path output = Path.of(args[1]).resolve(name + ".class");
                Files.createDirectories(output.getParent());
                Files.write(output, bytes);
            }
        }
    }
}
