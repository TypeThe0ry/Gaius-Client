package dev.gaius.parity;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Negative controls for the seed-parity gate: writes a copy of a patched client jar with one
 * deliberate defect, so a run of worldgen-seed-parity.mjs --patched on it must FAIL.
 *
 * <ul>
 *   <li>{@code extra-draw}: OreFeature.place makes one more random draw before every return (the
 *       draw count and hash differ, and the draws after it shift).</li>
 *   <li>{@code hit-false}: Holder.Reference.is answers false for a remembered true tag; ore
 *       replacement fails and blocks differ.</li>
 * </ul>
 *
 * <p>usage: {@code MutatePatchedJar <patched client.jar> <out.jar> <mutation>}
 */
public final class MutatePatchedJar {
    private MutatePatchedJar() {
    }

    public static void main(String[] args) throws Exception {
        String target = switch (args[2]) {
            case "extra-draw" -> "net/minecraft/world/level/levelgen/feature/OreFeature";
            case "hit-false" -> "net/minecraft/core/Holder$Reference";
            default -> throw new IllegalArgumentException(args[2]);
        };
        try (ZipFile input = new ZipFile(args[0]); OutputStream out = Files.newOutputStream(Path.of(args[1]));
                ZipOutputStream output = new ZipOutputStream(out)) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes = entry.isDirectory() ? new byte[0] : input.getInputStream(entry).readAllBytes();
                if (entry.getName().equals(target + ".class")) {
                    bytes = mutate(bytes, args[2]);
                }
                output.putNextEntry(new ZipEntry(entry.getName()));
                output.write(bytes);
                output.closeEntry();
            }
        }
    }

    private static byte[] mutate(byte[] bytes, String mutation) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int changes = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (mutation.equals("extra-draw") && method.name.equals("place")
                        && method.desc.endsWith("Lnet/minecraft/core/BlockPos;)Z")
                        && instruction.getOpcode() == Opcodes.IRETURN) {
                    InsnList draw = new InsnList();
                    draw.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    draw.add(new InsnNode(Opcodes.ICONST_1));
                    draw.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "net/minecraft/util/RandomSource", "nextInt",
                            "(I)I", true));
                    draw.add(new InsnNode(Opcodes.POP));
                    method.instructions.insertBefore(instruction, draw);
                    method.maxStack = Math.max(method.maxStack, 3);
                    changes++;
                } else if (mutation.equals("hit-false") && method.name.equals("is") && instruction.getOpcode() == Opcodes.ICONST_1
                        && instruction.getNext() != null && instruction.getNext().getOpcode() == Opcodes.IRETURN) {
                    method.instructions.set(instruction, new InsnNode(Opcodes.ICONST_0));
                    changes++;
                }
            }
        }
        if (changes == 0) {
            throw new IllegalStateException("nothing to mutate for " + mutation);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
