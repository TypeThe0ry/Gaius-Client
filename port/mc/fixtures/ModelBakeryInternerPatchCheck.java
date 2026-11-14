import dev.gaius.tools.patches.resources.ModernResourcePatches;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * Checks Minecraft262BrowserPatcher.patchModelBakeryInterner on a vanilla client jar and writes the
 * class the bake harness (ModelBakeInternerCheck.java next to it) runs:
 *
 * <ul>
 *   <li>the patched {@code ModelBakery$InternerImpl} differs from vanilla in exactly two
 *       instructions: the two {@code Interners.newStrongInterner()} calls of the constructor now
 *       call {@code BrowserStrongInterner.create()} (full ASM text of the class compared);</li>
 *   <li>running the patch again on its own output is a no-op;</li>
 *   <li>a class with a third interner call site, or with only one, fails the patch;</li>
 *   <li>{@code <harness root>} gets the patched class with the two call sites pointed at
 *       {@code ModelBakeInternerCheck$Switch.create()} (the only further change, checked the same
 *       way), so that one JVM can bake with each interner.</li>
 * </ul>
 *   java ModelBakeryInternerPatchCheck <client-named.jar> <patch root> <harness root>
 */
public final class ModelBakeryInternerPatchCheck {
    private static final String CLASS = "net/minecraft/client/resources/model/ModelBakery$InternerImpl";

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        Path patched = Path.of(args[1]);
        Path harness = Path.of(args[2]);
        byte[] vanilla;
        try (ZipFile zip = new ZipFile(jar)) {
            vanilla = zip.getInputStream(zip.getEntry(CLASS + ".class")).readAllBytes();
        }
        ModernResourcePatches.patchModelBakeryInterner(jar, patched);
        Path output = patched.resolve(CLASS + ".class");
        byte[] first = Files.readAllBytes(output);
        expectOnlyCallSiteChanges(vanilla, first, "dev/gaius/browser/BrowserStrongInterner", "create");

        ModernResourcePatches.patchModelBakeryInterner(jar, patched);
        if (!java.util.Arrays.equals(first, Files.readAllBytes(output))) {
            throw new IllegalStateException("the patch is not idempotent");
        }

        // A third call to Interners in the class, or a constructor with one site only, must fail.
        expectFailure(jar, vanilla, node -> {
            MethodNode vector = method(node, "vector");
            vector.instructions.insert(new InsnNode(Opcodes.POP));
            vector.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/google/common/collect/Interners",
                    "newWeakInterner", "()Lcom/google/common/collect/Interner;", false));
            vector.maxStack += 1;
        }, "a third Interners call");
        expectFailure(jar, vanilla, node -> {
            MethodNode init = method(node, "<init>");
            for (AbstractInsnNode insn : init.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.name.equals("newStrongInterner")) {
                    call.name = "newWeakInterner";
                    break;
                }
            }
        }, "one newStrongInterner site");

        ClassNode node = read(first);
        int retargeted = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals("dev/gaius/browser/BrowserStrongInterner")) {
                    call.owner = "ModelBakeInternerCheck$Switch";
                    retargeted++;
                }
            }
        }
        if (retargeted != 2) {
            throw new IllegalStateException("harness retarget sites=" + retargeted);
        }
        byte[] harnessClass = write(node);
        expectOnlyCallSiteChanges(vanilla, harnessClass, "ModelBakeInternerCheck$Switch", "create");
        Path harnessOutput = harness.resolve(CLASS + ".class");
        Files.createDirectories(harnessOutput.getParent());
        Files.write(harnessOutput, harnessClass);
        System.out.println("MODEL_BAKERY_INTERNER_PATCH_OK changed=2 idempotent=true negative=2");
    }

    interface Mutation {
        void apply(ClassNode node);
    }

    private static void expectFailure(String jar, byte[] vanilla, Mutation mutation, String what) throws Exception {
        ClassNode node = read(vanilla);
        mutation.apply(node);
        Path root = Files.createTempDirectory("gaius-interner-negative-");
        try {
            Path file = root.resolve(CLASS + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, write(node));
            try {
                ModernResourcePatches.patchModelBakeryInterner(jar, root);
            } catch (IllegalStateException expected) {
                return;
            }
            throw new IllegalStateException("the patch accepted " + what);
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static void expectOnlyCallSiteChanges(byte[] vanilla, byte[] patched, String owner, String name) {
        List<String> before = text(vanilla);
        List<String> after = text(patched);
        if (before.size() != after.size()) {
            throw new IllegalStateException("instruction text length changed: " + before.size() + " -> " + after.size());
        }
        String from = "INVOKESTATIC com/google/common/collect/Interners.newStrongInterner ()Lcom/google/common/collect/Interner;";
        String to = "INVOKESTATIC " + owner + "." + name + " ()Lcom/google/common/collect/Interner;";
        int changed = 0;
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).equals(after.get(i))) {
                continue;
            }
            if (!before.get(i).trim().equals(from) || !after.get(i).trim().equals(to)) {
                throw new IllegalStateException("unexpected change at line " + i + ":\n- " + before.get(i) + "\n+ " + after.get(i));
            }
            changed++;
        }
        if (changed != 2) {
            throw new IllegalStateException("changed call sites=" + changed);
        }
    }

    private static List<String> text(byte[] bytes) {
        StringWriter out = new StringWriter();
        new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(out)), ClassReader.SKIP_FRAMES);
        return new ArrayList<>(List.of(out.toString().split("\n")));
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private ModelBakeryInternerPatchCheck() {
    }
}
