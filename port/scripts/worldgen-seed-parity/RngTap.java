package dev.gaius.parity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Writes a copy of {@code WorldgenRandom} whose {@code next(int)} (the funnel of every
 * {@code nextInt}, {@code nextLong}, {@code nextFloat}, ... of the worldgen random) counts its
 * calls and folds each (bits, result) pair into a per-instance FNV-style chain; the static
 * {@code gaius$tapHash} is the wrapping sum of the chain after every draw. The same copy goes in
 * front of the vanilla class path and the patched one, so the seed-parity harness can compare how
 * many random draws a run made and, per random instance, their values in order: a patch that
 * adds, drops or reorders one draw changes the pair even when no block differs. The sum is
 * independent of the order in which chunks are generated (a plain global hash differs between two
 * vanilla runs, the chunk order is not fixed); the draw order inside one instance is part of it.
 *
 * <p>usage: {@code RngTap <vanilla jar> <output dir>}; the harness reads
 * {@code WorldgenRandom.gaius$tapCount} and {@code gaius$tapHash} by reflection when present.
 */
public final class RngTap {
    public static final String OWNER = "net/minecraft/world/level/levelgen/WorldgenRandom";

    private RngTap() {
    }

    public static void main(String[] args) throws IOException {
        ClassNode node = new ClassNode();
        try (ZipFile jar = new ZipFile(args[0]); var stream = jar.getInputStream(jar.getEntry(OWNER + ".class"))) {
            new ClassReader(stream.readAllBytes()).accept(node, 0);
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "gaius$tapCount", "J", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "gaius$tapHash", "J", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "gaius$tapChain", "J", null, null));
        MethodNode next = node.methods.stream()
                .filter(method -> method.name.equals("next") && method.desc.equals("(I)I")).findFirst()
                .orElseThrow(() -> new IllegalStateException("WorldgenRandom.next(int) was not found"));
        int returns = 0;
        for (AbstractInsnNode instruction : next.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.IRETURN) {
                continue;
            }
            int result = next.maxLocals;
            InsnList tap = new InsnList();
            tap.add(new VarInsnNode(Opcodes.ISTORE, result));
            tap.add(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, "gaius$tapCount", "J"));
            tap.add(new InsnNode(Opcodes.LCONST_1));
            tap.add(new InsnNode(Opcodes.LADD));
            tap.add(new FieldInsnNode(Opcodes.PUTSTATIC, OWNER, "gaius$tapCount", "J"));
            // chain = (chain ^ ((long) result << 6 ^ bits)) * 1099511628211L; hash += chain
            tap.add(new VarInsnNode(Opcodes.ALOAD, 0));
            tap.add(new VarInsnNode(Opcodes.ALOAD, 0));
            tap.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "gaius$tapChain", "J"));
            tap.add(new VarInsnNode(Opcodes.ILOAD, result));
            tap.add(new InsnNode(Opcodes.I2L));
            tap.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 6));
            tap.add(new InsnNode(Opcodes.LSHL));
            tap.add(new VarInsnNode(Opcodes.ILOAD, 1));
            tap.add(new InsnNode(Opcodes.I2L));
            tap.add(new InsnNode(Opcodes.LXOR));
            tap.add(new InsnNode(Opcodes.LXOR));
            tap.add(new LdcInsnNode(1099511628211L));
            tap.add(new InsnNode(Opcodes.LMUL));
            tap.add(new FieldInsnNode(Opcodes.PUTFIELD, OWNER, "gaius$tapChain", "J"));
            tap.add(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, "gaius$tapHash", "J"));
            tap.add(new VarInsnNode(Opcodes.ALOAD, 0));
            tap.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "gaius$tapChain", "J"));
            tap.add(new InsnNode(Opcodes.LADD));
            tap.add(new FieldInsnNode(Opcodes.PUTSTATIC, OWNER, "gaius$tapHash", "J"));
            tap.add(new VarInsnNode(Opcodes.ILOAD, result));
            next.instructions.insertBefore(instruction, tap);
            next.maxLocals = result + 1;
            next.maxStack = Math.max(next.maxStack, 6);
            returns++;
        }
        if (returns == 0) {
            throw new IllegalStateException("WorldgenRandom.next(int) has no return");
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        Path output = Path.of(args[1]).resolve(OWNER + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
        System.out.println("RNG_TAP " + returns + " return(s) instrumented");
    }
}
