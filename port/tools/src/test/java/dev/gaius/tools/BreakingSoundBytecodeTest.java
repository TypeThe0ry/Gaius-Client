package dev.gaius.tools;

import dev.gaius.tools.patches.audio.AudioPatches;
import dev.gaius.tools.patches.audio.RenderpearlAudioPatches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Regression test that both mining sound patch paths preserve vanilla's 8.0f divisor. */
public final class BreakingSoundBytecodeTest {
    private BreakingSoundBytecodeTest() {
    }

    public static int run(Path root) throws Exception {
        Path fixtures = root.resolve("breaking-sound-fixtures");
        Files.createDirectories(fixtures);
        checkLegacy(fixtures);
        checkModern(fixtures);
        return 2;
    }

    private static void checkLegacy(Path root) throws Exception {
        String owner = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
        Path jar = root.resolve("legacy.jar");
        putJar(jar, owner, "continueDestroyBlock",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z");
        Path output = root.resolve("legacy-out");
        AudioPatches.patchMultiPlayerGameModeBrowserHitSound(jar.toString(), output);
        ClassNode actual = Files.exists(output.resolve(owner + ".class"))
                ? read(output.resolve(owner + ".class")) : readFromJar(jar, owner);
        assertVanillaDivisor(find(actual, "continueDestroyBlock"), "legacy");
    }

    private static void checkModern(Path root) throws Exception {
        String owner = "net/minecraft/client/multiplayer/ClientLevel";
        Path jar = root.resolve("modern.jar");
        putJar(jar, owner, "playBreakingSound",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V");
        ClassNode before = readFromJar(jar, owner);
        Path output = root.resolve("modern-out");
        RenderpearlAudioPatches.patchClientLevelBreakingSoundVolume(jar.toString(), output);
        ClassNode actual = Files.exists(output.resolve(owner + ".class"))
                ? read(output.resolve(owner + ".class")) : readFromJar(jar, owner);
        assertVanillaDivisor(find(actual, "playBreakingSound"), "modern");
        check(PatchTargets.methodHash(find(before, "playBreakingSound")).equals(
                PatchTargets.methodHash(find(actual, "playBreakingSound"))),
                "modern patch preserves the complete playBreakingSound bytecode");
    }

    private static void assertVanillaDivisor(MethodNode method, String label) {
        int eights = 0;
        int fours = 0;
        boolean hitSound = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("getHitSound")) {
                hitSound = true;
            }
            if (instruction instanceof LdcInsnNode literal && Float.valueOf(8.0f).equals(literal.cst)) eights++;
            if (instruction instanceof LdcInsnNode literal && Float.valueOf(4.0f).equals(literal.cst)) fours++;
        }
        check(hitSound && eights == 1 && fours == 0,
                label + " mining sound must retain exactly one 8.0f and no 4.0f divisor");
    }

    private static MethodNode find(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode read(Path path) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(path)).accept(node, 0);
        return node;
    }

    private static ClassNode readFromJar(Path jar, String owner) throws Exception {
        return dev.gaius.tools.patches.support.ClientPatchSupport.read(jar.toString(), owner + ".class");
    }

    private static void putJar(Path jar, String owner, String method, String descriptor) throws Exception {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(owner + ".class"));
            zip.write(fixture(owner, method, descriptor));
            zip.closeEntry();
        }
    }

    private static byte[] fixture(String owner, String method, String descriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        var code = writer.visitMethod(Opcodes.ACC_PUBLIC, method, descriptor, null, null);
        code.visitCode();
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/block/SoundType",
                "getHitSound", "()Lnet/minecraft/sounds/SoundEvent;", false);
        code.visitLdcInsn(8.0f);
        code.visitInsn(Opcodes.FDIV);
        code.visitInsn(descriptor.endsWith(")Z") ? Opcodes.ICONST_0 : Opcodes.RETURN);
        if (descriptor.endsWith(")Z")) code.visitInsn(Opcodes.IRETURN);
        code.visitMaxs(2, 3);
        code.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
