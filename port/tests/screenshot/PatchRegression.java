package dev.gaius.browser.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class PatchRegression {
    private PatchRegression() {
    }

    public static void main(String[] args) throws Exception {
        Path originalJar = Path.of(args[0]);
        Path patchedRoot = Path.of(args[1]);
        String imageName = "com/mojang/blaze3d/platform/NativeImage.class";
        String entityName = "net/minecraft/world/entity/Entity.class";
        ClassNode originalImage = original(originalJar, imageName);
        ClassNode image = read(Files.readAllBytes(patchedRoot.resolve(imageName)));
        MethodNode write = method(image, "writeToChannel", "(Ljava/nio/channels/WritableByteChannel;)Z");
        require(calls(write).equals(List.of("com/mojang/blaze3d/platform/NativeImage$Format.components()I",
                "dev/gaius/browser/BrowserPngWriter.write(Ljava/nio/channels/WritableByteChannel;JIII)Z")),
                "native callback retained in PNG encoder");
        String descriptor = "(Ljava/nio/file/Path;)V";
        require(instructions(method(originalImage, "writeToFile", descriptor))
                .equals(instructions(method(image, "writeToFile", descriptor))), "vanilla channel lifecycle changed");
        ClassNode entity = read(Files.readAllBytes(patchedRoot.resolve(entityName)));
        MethodNode constructor = method(entity, "<init>",
                "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V");
        int preserved = 0;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/util/Mth") || !call.name.equals("createInsecureUUID")) continue;
            require(call.desc.equals("(Lnet/minecraft/util/RandomSource;)Ljava/util/UUID;"), "random source removed");
            AbstractInsnNode discard = next(call);
            require(discard.getOpcode() == Opcodes.POP, "vanilla UUID not discarded");
            require(next(discard) instanceof MethodInsnNode replacement
                    && replacement.owner.equals("java/util/UUID") && replacement.name.equals("randomUUID"),
                    "global UUID replacement missing");
            preserved++;
        }
        require(preserved == 1, "entity RNG consumption not preserved exactly once");
        ClassNode math = original(originalJar, "net/minecraft/util/Mth.class");
        MethodNode uuid = method(math, "createInsecureUUID", "(Lnet/minecraft/util/RandomSource;)Ljava/util/UUID;");
        require(calls(uuid).stream().filter(call -> call.equals("net/minecraft/util/RandomSource.nextLong()J"))
                .count() == 2, "vanilla UUID random draw count changed");
        System.out.println("PATCH_REGRESSION PASS vanillaSavePath=true entityRandomDraws=2");
    }

    private static ClassNode original(Path jar, String name) throws Exception {
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            return read(archive.getInputStream(archive.getEntry(name)).readAllBytes());
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst().orElseThrow();
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        AbstractInsnNode result = instruction.getNext();
        while (result != null && result.getOpcode() < 0) result = result.getNext();
        return result;
    }

    private static List<String> calls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) result.add(call.owner + "." + call.name + call.desc);
        }
        return result;
    }

    private static List<String> instructions(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() < 0) continue;
            String value = String.valueOf(instruction.getOpcode());
            if (instruction instanceof MethodInsnNode call) value += call.owner + call.name + call.desc;
            if (instruction instanceof FieldInsnNode field) value += field.owner + field.name + field.desc;
            if (instruction instanceof VarInsnNode variable) value += variable.var;
            result.add(value);
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
