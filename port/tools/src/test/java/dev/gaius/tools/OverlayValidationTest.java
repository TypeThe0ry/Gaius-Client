package dev.gaius.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Composed outputs, hierarchy merges and negative fixtures must reach the real JVM verifier. */
public final class OverlayValidationTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    public static int run(Path scratch) throws Exception {
        Path root = scratch.resolve("overlay-validation"); Files.createDirectories(root);
        ClassNode base = type("fixture/Base", "java/lang/Object");
        ClassNode a = type("fixture/A", base.name), b = type("fixture/B", base.name);
        for (ClassNode node : List.of(base, a, b)) store(root, node);
        ClassNode noInit = type("fixture/NoInitialization", "java/lang/Object");
        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/AssertionError")); clinit.instructions.add(new InsnNode(Opcodes.DUP));
        clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false));
        clinit.instructions.add(new InsnNode(Opcodes.ATHROW)); clinit.maxStack = 2; noInit.methods.add(clinit); store(root, noInit);
        try (var hierarchy = new HierarchyClassWriter(List.of(root), null)) {
            check(hierarchy.commonSuperType(a.name, b.name).equals(base.name), "siblings retain real superclass");
            check(hierarchy.commonSuperType("java/util/ArrayList", "java/util/LinkedList").equals("java/util/AbstractList"), "JDK hierarchy");
            check(hierarchy.commonSuperType("java/util/List", "java/util/ArrayList").equals("java/util/List"), "interface assignment");
            check(hierarchy.commonSuperType("[Lfixture/A;", "[Lfixture/B;").equals("[Lfixture/Base;"), "reference arrays");
            check(hierarchy.commonSuperType("[[I", "[[J").equals("[Ljava/lang/Object;"), "primitive nested arrays");
            check(hierarchy.commonSuperType("[I", "[J").equals("java/lang/Object"), "primitive arrays");
            check(hierarchy.commonSuperType("[[Lfixture/A;", "[Lfixture/Base;").equals("[Ljava/lang/Object;"), "unequal array ranks");
            check(hierarchy.commonSuperType("[I", "java/lang/Cloneable").equals("java/lang/Cloneable"), "array interfaces");
            try { hierarchy.commonSuperType("fixture/Unknown", a.name); throw new AssertionError("unknown hierarchy accepted"); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("Unresolved frame hierarchy"), "missing header fails closed"); }
        }
        ClassNode join = type("fixture/Join", "java/lang/Object");
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "choose", "(Z)Lfixture/Base;", null, null);
        LabelNode other = new LabelNode(), merge = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0)); method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, other));
        instantiate(method, a.name); method.instructions.add(new JumpInsnNode(Opcodes.GOTO, merge));
        method.instructions.add(other); instantiate(method, b.name); method.instructions.add(merge);
        method.instructions.add(new InsnNode(Opcodes.ARETURN)); join.methods.add(method);
        Path output = root.resolve(join.name + ".class");
        try (var writer = new HierarchyClassWriter(List.of(root), join)) { join.accept(writer); Files.write(output, writer.toByteArray()); }
        check(OverlayClassCheck.verify(root, root.toString(), scratch.resolve("good-verify.json")) == 0, "all real fixture classes verify");
        ClassWriter wrong = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String x, String y) { return "java/lang/Object"; }
        };
        join.accept(wrong); Files.write(output, wrong.toByteArray());
        check(OverlayClassCheck.verify(root, root.toString(), scratch.resolve("bad-verify.json")) != 0, "Object merge rejected by JVM");
        check(Files.readString(scratch.resolve("bad-verify.json")).contains("VerifyError"), "verifier failure is preserved");
        Path missing = root.resolve("fixture/MissingChild.class");
        store(root, type("fixture/MissingChild", "fixture/MissingParent"));
        check(OverlayClassCheck.verify(root, root.toString(), scratch.resolve("missing-verify.json")) != 0
                && Files.readString(scratch.resolve("missing-verify.json")).contains("NoClassDefFoundError"), "missing dependencies are failures");
        Files.delete(missing);
        MethodNode duplicate = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        join.methods.add(duplicate);
        join.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "value", "I", null, null));
        join.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "value", "I", null, null));
        store(root, join);
        Path receipt = scratch.resolve("duplicate-members.json");
        check(OverlayClassCheck.checkMembers(root, receipt) != 0, "duplicate members rejected");
        check(Files.readString(receipt).contains("duplicate method") && Files.readString(receipt).contains("duplicate field"), "methods and fields scanned");
        spliterator(root.resolve("iterator"));
        System.out.println("OVERLAY_VALIDATION_TEST PASS checks=" + checks);
        return checks;
    }

    private static void instantiate(MethodNode m, String name) {
        m.instructions.add(new TypeInsnNode(Opcodes.NEW, name)); m.instructions.add(new InsnNode(Opcodes.DUP));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false));
    }

    private static ClassNode type(String name, String parent) {
        ClassNode node = new ClassNode(); node.version = Opcodes.V21; node.access = Opcodes.ACC_PUBLIC;
        node.name = name; node.superName = parent;
        MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false));
        ctor.instructions.add(new InsnNode(Opcodes.RETURN)); ctor.maxStack = 1; ctor.maxLocals = 1;
        node.methods.add(ctor); return node;
    }

    private static void store(Path root, ClassNode node) throws Exception {
        ClassWriter writer = new ClassWriter(0); node.accept(writer);
        Path file = root.resolve(node.name + ".class"); Files.createDirectories(file.getParent()); Files.write(file, writer.toByteArray());
    }

    private static void spliterator(Path root) throws Exception {
        String chunk = "net/minecraft/world/level/ChunkPos", owner = chunk + "$2";
        ClassNode node = type(owner, "java/util/Spliterators$AbstractSpliterator"); node.methods.clear();
        for (String field : List.of("val$from", "val$to")) node.fields.add(new FieldNode(Opcodes.ACC_FINAL, field, "L" + chunk + ";", null, null));
        for (String field : List.of("val$zDiff", "val$xDiff")) node.fields.add(new FieldNode(Opcodes.ACC_FINAL, field, "I", null, null));
        MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(JIL" + chunk + ";L" + chunk + ";II)V", null, null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); ctor.instructions.add(new VarInsnNode(Opcodes.LLOAD, 1)); ctor.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "<init>", "(JI)V", false));
        ctor.instructions.add(new InsnNode(Opcodes.RETURN)); ctor.maxStack = 4; ctor.maxLocals = 8; node.methods.add(ctor);
        MethodNode advance = new MethodNode(Opcodes.ACC_PUBLIC, "tryAdvance", "(Ljava/util/function/Consumer;)Z", null, null);
        advance.instructions.add(new InsnNode(Opcodes.ICONST_0)); advance.instructions.add(new InsnNode(Opcodes.IRETURN)); advance.maxStack = 1; advance.maxLocals = 2; node.methods.add(advance);
        Path vanilla = root.resolve("vanilla"); store(vanilla, node); store(vanilla, type(chunk, "java/lang/Object"));
        Path jar = root.resolve("input.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of(owner, chunk)) {
                zip.putNextEntry(new ZipEntry(name + ".class")); zip.write(Files.readAllBytes(vanilla.resolve(name + ".class"))); zip.closeEntry();
            }
        }
        // Reproduce the production ordering, where REQUIRED writes precede jar folding.
        Path out = root.resolve("out");
        AbstractSpliteratorBrowserPatcher.main(new String[]{jar.toString(), out.toString()});
        Path file = out.resolve(owner + ".class"); byte[] required = Files.readAllBytes(file);
        dev.gaius.tools.patches.platform.PlatformPatches.patchChunkPosSpliterator(jar.toString(), file);
        check(java.util.Arrays.equals(required, Files.readAllBytes(file)), "PERF preserves complete REQUIRED implementation");
        dev.gaius.tools.patches.platform.PlatformPatches.patchChunkPosSpliterator(jar.toString(), file);
        check(java.util.Arrays.equals(required, Files.readAllBytes(file)), "composition is idempotent");
        check(OverlayClassCheck.checkMembers(out, root.resolve("members.json")) == 0, "composed class has unique members");
        check(OverlayClassCheck.verify(out, jar.toString(), root.resolve("verify.json")) == 0, "composed constructor and methods verify");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{out.toUri().toURL(), jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> position = loader.loadClass(chunk.replace('/', '.'));
            var constructor = loader.loadClass(owner.replace('/', '.')).getDeclaredConstructor(long.class, int.class, position, position, int.class, int.class);
            var iterator = (java.util.Spliterator<?>) constructor.newInstance(13L, java.util.Spliterator.ORDERED, null, null, 0, 0);
            check(iterator.estimateSize() == 13L && iterator.characteristics() == java.util.Spliterator.ORDERED && iterator.trySplit() == null,
                    "REQUIRED constructor retains size, characteristics and sequential split behavior");
        }
        Path standalone = root.resolve("standalone");
        dev.gaius.tools.patches.platform.PlatformPatches.patchChunkPosSpliterator(jar.toString(), standalone.resolve(owner + ".class"));
        check(OverlayClassCheck.verify(standalone, jar.toString(), root.resolve("standalone-verify.json")) == 0, "original superclass shape remains supported");
        ClassNode partial = new ClassNode(); new ClassReader(required).accept(partial, 0); partial.methods.removeIf(m -> m.name.equals("estimateSize")); store(out, partial);
        try { dev.gaius.tools.patches.platform.PlatformPatches.patchChunkPosSpliterator(jar.toString(), file); throw new AssertionError("partial shape accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("Incomplete"), "partial shape rejected"); }
    }
}
