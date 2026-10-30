package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

public final class PatchTargetsTest {
    private static int checks;

    private PatchTargetsTest() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        archivePathValidation(root);
        byte[] vanilla = fixture(1, false);
        byte[] noise = fixture(1, true);
        MethodNode original = method(PatchTargets.read(vanilla), "value");
        check(PatchTargets.methodHash(original).equals(
                PatchTargets.methodHash(method(PatchTargets.read(noise), "value"))),
                "line numbers and constant-pool order are irrelevant");
        check(!PatchTargets.methodHash(original).equals(
                PatchTargets.methodHash(method(PatchTargets.read(fixture(2, false)), "value"))),
                "body constants affect the hash");
        check(PatchTargets.methodHash(controlFlow(false)).equals(
                PatchTargets.methodHash(controlFlow(true))), "branch and handler labels are symbolic");
        MethodNode flow = controlFlow(false);
        flow.tryCatchBlocks.get(0).type = "java/lang/RuntimeException";
        check(!PatchTargets.methodHash(flow).equals(PatchTargets.methodHash(controlFlow(false))),
                "exception handler types affect the hash");
        check(!PatchTargets.methodHash(dynamic("bootstrap")).equals(
                PatchTargets.methodHash(dynamic("otherBootstrap"))), "bootstrap handles affect the hash");
        MethodNode constants = new MethodNode(Opcodes.ACC_STATIC, "constants", "()V", null, null);
        constants.instructions.add(new LdcInsnNode(Float.intBitsToFloat(0x7fc00001)));
        String first = PatchTargets.methodHash(constants);
        constants.instructions.set(constants.instructions.getFirst(),
                new LdcInsnNode(Float.intBitsToFloat(0x7fc00002)));
        check(!first.equals(PatchTargets.methodHash(constants)), "raw NaN payloads affect the hash");

        Path source = root.resolve("vanilla.jar");
        Path input = root.resolve("input.jar");
        jar(source, vanilla);
        jar(input, fixture(2, false));
        Path output = root.resolve("out");
        Files.createDirectories(output.resolve("fixture"));
        Files.write(output.resolve("fixture/Example.class"), fixture(3, false));
        try (PatchTargets targets = new PatchTargets(source, input)) {
            var changed = targets.changed(output, Map.of("fixture/Example.class", new byte[0]), false);
            var row = changed.stream().filter(value -> value.get("method").equals("value")).findFirst().orElseThrow();
            check(row.get("targetHash").equals(PatchTargets.methodHash(original)),
                    "targets always bind to vanilla, not a previous patcher's output");
            var overrides = targets.changed(output, Map.of("fixture/Example.class", new byte[0]), true);
            check(overrides.stream().anyMatch(value -> value.get("operation").equals("override")),
                    "whole-class replacements carry a full class hash");
            check(overrides.stream().anyMatch(value -> value.get("method").equals("<clinit>")),
                    "whole-class replacements retain unchanged initializer footprints");
            ClassNode before = PatchTargets.read(vanilla);
            ClassNode after = PatchTargets.read(vanilla);
            MethodNode injected = new MethodNode(Opcodes.ACC_STATIC, "added", "()V", null, null);
            injected.instructions.add(new InsnNode(Opcodes.RETURN));
            after.methods.add(injected);
            var added = targets.changed(before, after).stream().filter(value -> value.get("method").equals("added"))
                    .findFirst().orElseThrow();
            check(Boolean.FALSE.equals(added.get("vanillaPresent"))
                    && added.get("targetHash").toString().length() == 64, "injections use an explicit absence hash");
            method(after, "<clinit>").instructions.insert(new FieldInsnNode(Opcodes.PUTSTATIC,
                    "fixture/Other", "y", "I"));
            var clinit = targets.changed(before, after).stream().filter(value -> value.get("method").equals("<clinit>"))
                    .findFirst().orElseThrow();
            check(clinit.get("vanillaStaticWrites").equals(List.of("fixture/Example.x:I"))
                    && clinit.get("staticWrites").equals(List.of("fixture/Example.x:I", "fixture/Other.y:I")),
                    "static writes include owners and descriptors");
            PatchTargets.restoreNode(before, after);
            check(after.methods.size() == before.methods.size(), "in-memory failures restore their node");
        }
        check(PatchRegistry.Spec.parse("PERF NOT_APPLICABLE").notApplicable(), "exclusion spec");
        check(PatchRegistry.Spec.parse("REQUIRED OVERRIDE").wholeClass(), "override spec");
        lazyConditionFailureState(root);
        try {
            checks += LoadingPresentationPatchesTest.run(root);
            checks += TitleScreenIconRowPatchTest.run();
        } catch (IOException | RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        checks += BrowserTitleLayoutTest.run();
        System.out.println("PATCH_TARGETS_TEST PASS checks=" + checks);
    }

    private static void archivePathValidation(Path root) throws IOException {
        Path output = root.resolve("archive-paths");
        check(ArchivePaths.resolveEntry(output, "a/b.class").equals(output.resolve("a/b.class").toAbsolutePath().normalize()),
                "normal archive entry stays below the output root");
        check(ArchivePaths.resolveEntry(Path.of("."), "a.class").equals(Path.of("a.class").toAbsolutePath().normalize()),
                "relative dot root resolves below the working directory");
        check(ArchivePaths.resolveEntry(Path.of("out/x"), "a.class").equals(Path.of("out/x/a.class").toAbsolutePath().normalize()),
                "relative nested root resolves below that directory");
        check(ArchivePaths.resolveEntry(output, "dir/a\\b.txt").equals(output.resolve("dir/a\\b.txt").toAbsolutePath().normalize()),
                "safe backslash character is accepted");
        for (String rejected : new String[] {"../x", "..\\x", "a\\..\\x", "/abs", "\\abs", "C:/x", "C:x",
                "a/../../x", ".", "nul\0entry"}) {
            try {
                ArchivePaths.resolveEntry(output, rejected);
                throw new AssertionError("archive entry was accepted: " + rejected);
            } catch (IOException error) {
                check(error.getMessage().contains(rejected), "rejection names archive entry " + rejected);
            }
        }
    }

    /** Regression fixture for the cached-null behavior of the MaterialRuleContext patch. */
    private static void lazyConditionFailureState(Path root) throws IOException {
        String context = "net/minecraft/world/level/levelgen/material/MaterialRuleContext";
        String owner = context + "$LazyXZCondition";
        Path jar = root.resolve("material-rule-context.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(zip, context + ".class", materialContextFixture(context));
            put(zip, owner + ".class", lazyFixture(context, owner, "lastUpdateXZ"));
            put(zip, context + "$LazyYCondition.class",
                    lazyFixture(context, context + "$LazyYCondition", "lastUpdateY"));
        }
        Path output = root.resolve("material-overlay");
        dev.gaius.tools.patches.worldgen.RenderpearlWorldgenPatches
                .patchMaterialRuleContextIntCounters(jar.toString(), output);
        ClassNode patched;
        patched = PatchTargets.read(Files.readAllBytes(output.resolve(owner + ".class")));
        MethodNode constructor = method(patched, "<init>");
        MethodNode test = method(patched, "test");
        check(hasFieldStoreAfterConstant(constructor, "browserLastUpdate", Opcodes.ICONST_M1),
                "lazy condition initializes shadow counter to vanilla counter - 1");
        check(hasInstruction(test, Opcodes.NEW, "java/lang/IllegalStateException"),
                "lazy condition preserves vanilla cached-null exception");
        check(hasFieldStoreAfterConstant(test, "browserResultInitialized", Opcodes.ICONST_0),
                "lazy condition clears cached result before compute");
    }

    private static boolean hasInstruction(MethodNode method, int opcode, String type) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode && instruction instanceof org.objectweb.asm.tree.TypeInsnNode node
                    && node.desc.equals(type)) return true;
        }
        return false;
    }

    private static boolean hasFieldStoreAfterConstant(MethodNode method, String field, int constant) {
        AbstractInsnNode previous = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD
                    && put.name.equals(field) && previous != null && previous.getOpcode() == constant) return true;
            if (instruction.getOpcode() >= 0) previous = instruction;
        }
        return false;
    }

    private static byte[] materialContextFixture(String owner) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "lastUpdateXZ", "J", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "lastUpdateY", "J", null, null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        for (String name : new String[] {"updateXZ", "updateY"}) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "(IIII)V", null, null);
            method.visitCode();
            appendLongIncrement(method, owner, "updateXZ".equals(name) ? "lastUpdateXZ" : "lastUpdateY");
            if ("updateXZ".equals(name)) appendLongIncrement(method, owner, "lastUpdateY");
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(4, 5);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void appendLongIncrement(org.objectweb.asm.MethodVisitor method, String owner, String field) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, field, "J");
        method.visitInsn(Opcodes.LCONST_1);
        method.visitInsn(Opcodes.LADD);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, field, "J");
    }

    private static byte[] lazyFixture(String context, String owner, String counter) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL, "context", "L" + context + ";", null, null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PROTECTED, "<init>", "(L" + context + ";)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, owner, "context", "L" + context + ";");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 2);
        constructor.visitEnd();
        var test = writer.visitMethod(Opcodes.ACC_PUBLIC, "test", "()Z", null, null);
        test.visitCode();
        test.visitVarInsn(Opcodes.ALOAD, 0);
        test.visitFieldInsn(Opcodes.GETFIELD, owner, "context", "L" + context + ";");
        test.visitFieldInsn(Opcodes.GETFIELD, context, counter, "J");
        test.visitVarInsn(Opcodes.LSTORE, 1);
        test.visitVarInsn(Opcodes.LLOAD, 1);
        test.visitInsn(Opcodes.LCONST_0);
        test.visitInsn(Opcodes.LCMP);
        var compute = new org.objectweb.asm.Label();
        test.visitJumpInsn(Opcodes.IFNE, compute);
        test.visitInsn(Opcodes.ICONST_0);
        test.visitInsn(Opcodes.IRETURN);
        test.visitLabel(compute);
        test.visitVarInsn(Opcodes.ALOAD, 0);
        test.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "compute", "()Z", false);
        test.visitInsn(Opcodes.IRETURN);
        test.visitMaxs(3, 3);
        test.visitEnd();
        writer.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "compute", "()Z", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst().orElseThrow();
    }

    private static byte[] fixture(int value, boolean noise) {
        ClassWriter writer = new ClassWriter(0);
        if (noise) writer.newConst("irrelevant pool entry");
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Example", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_STATIC, "x", "I", null, null).visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode();
        if (noise) {
            var label = new org.objectweb.asm.Label();
            method.visitLabel(label);
            method.visitLineNumber(77, label);
        }
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        var initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        initializer.visitInsn(Opcodes.ICONST_0);
        initializer.visitFieldInsn(Opcodes.PUTSTATIC, "fixture/Example", "x", "I");
        initializer.visitInsn(Opcodes.RETURN);
        initializer.visitMaxs(1, 0);
        initializer.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode controlFlow(boolean noise) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "flow", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        if (noise) method.instructions.add(new LineNumberNode(88, start));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new LookupSwitchInsnNode(end, new int[]{0}, new LabelNode[]{handler}));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, end));
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));
        return method;
    }

    private static MethodNode dynamic(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "dynamic", "()V", null, null);
        Handle handle = new Handle(Opcodes.H_INVOKESTATIC, "fixture/Bootstrap", name, "()V", false);
        method.instructions.add(new InvokeDynamicInsnNode("invoke", "()V", handle,
                new ConstantDynamic("constant", "I", handle, 1)));
        return method;
    }

    private static void jar(Path path, byte[] bytes) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fixture/Example.class"));
            zip.write(bytes);
            zip.closeEntry();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
