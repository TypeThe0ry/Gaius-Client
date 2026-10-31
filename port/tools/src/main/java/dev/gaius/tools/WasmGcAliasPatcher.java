package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * WebAssembly GC target only: points calls of javac-visible stand-ins at the TeaVM class
 * library classes they represent.
 *
 * <p>A class library extension that needs package access to TeaVM's {@code java.nio} internals
 * (the linear-memory {@code ByteBuffer} views of {@code BrowserMemory}) has to live in
 * {@code org.teavm.classlib.java.nio}, where TeaVM maps it to {@code java.nio}. javac refuses a
 * reference to a {@code java.nio} class that the JDK does not have, so overlay code calls a
 * stand-in with the same static methods, and this patcher renames the owner after javac.
 *
 * <p>Usage: {@code WasmGcAliasPatcher <jar> <patch-dir>}; every class of the jar that calls a
 * stand-in is written to the patch directory.
 */
public final class WasmGcAliasPatcher {
    static final Map<String, String> ALIASES = Map.of(
            "dev/gaius/browser/wasm/LinearMemory", "java/nio/GaiusLinearMemory");

    private WasmGcAliasPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: WasmGcAliasPatcher <jar> <patch-dir>");
        }
        Path output = Path.of(args[1]);
        int patched = 0;
        try (ZipFile jar = new ZipFile(args[0])) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // Multi-release variants (META-INF/versions) target newer JDKs than ASM reads;
                // TeaVM compiles the base entries only.
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                byte[] bytes;
                try (var stream = jar.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                if (ALIASES.containsKey(node.name) || !rename(node)) {
                    continue;
                }
                ClassWriter writer = new ClassWriter(0);
                node.accept(writer);
                Path target = ArchivePaths.resolveEntry(output, entry.getName());
                Files.createDirectories(target.getParent());
                Files.write(target, writer.toByteArray());
                patched++;
            }
        }
        System.out.println("WasmGcAliasPatcher: " + patched + " classes call a java.nio alias");
    }

    private static boolean rename(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;
                    insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call) {
                    String owner = ALIASES.get(call.owner);
                    if (owner != null) {
                        call.owner = owner;
                        changed = true;
                    }
                } else if (insn instanceof FieldInsnNode field) {
                    String owner = ALIASES.get(field.owner);
                    if (owner != null) {
                        field.owner = owner;
                        changed = true;
                    }
                } else if (insn instanceof TypeInsnNode type) {
                    String owner = ALIASES.get(type.desc);
                    if (owner != null) {
                        type.desc = owner;
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }
}
