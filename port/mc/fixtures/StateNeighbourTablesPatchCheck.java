import dev.gaius.tools.patches.platform.PlatformPatches;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * Checks MinecraftClientPatcher.patchStateDefinitionNeighbourTables on a vanilla client jar:
 *
 * <ul>
 *   <li>the whole jar calls {@code StateDefinition.createMultiPropertyStates} only from the
 *       StateDefinition constructor, and nothing outside StateDefinition and StateHolder calls
 *       {@code StateHolder.initializeNeighbors} or touches {@code StateDefinition$StateCollection}
 *       (so the tables come from exactly the construction the patch replaces);</li>
 *   <li>the patch writes only StateDefinition and changes only createMultiPropertyStates, by
 *       the prologue in front of the unchanged vanilla body (full ASM text of the class compared,
 *       frames and max values aside, which the writer recomputes);</li>
 *   <li>running it again on its own output changes nothing;</li>
 *   <li>a body without the Map.forEach visit, a second caller of createMultiPropertyStates and an
 *       exception handler in it each fail the patch without writing.</li>
 * </ul>
 *   java StateNeighbourTablesPatchCheck <client-named.jar> <patch root> [<initializer list>]
 */
public final class StateNeighbourTablesPatchCheck {
    private static final String PACKAGE = "net/minecraft/world/level/block/state/";
    private static final String DEFINITION = PACKAGE + "StateDefinition";
    private static final String COLLECTION = DEFINITION + "$StateCollection";
    private static final String HOLDER = PACKAGE + "StateHolder";
    private static final String TABLES = PACKAGE + "GaiusStateNeighbourTables";
    private static final String MULTI = "createMultiPropertyStates";

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        Path patched = Path.of(args[1]);
        try (ZipFile zip = new ZipFile(jar)) {
            if (zip.getEntry(COLLECTION + ".class") == null) {
                // The generation keeps a neighbour Table per state; the patch registers a drop.
                System.out.println("STATE_NEIGHBOUR_PATCH_DROPPED no " + COLLECTION);
                return;
            }
        }
        List<String> initializers = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode node = read(zip.getInputStream(entry).readAllBytes());
                if (node.methods.stream().anyMatch(method -> method.name.equals("<clinit>"))) {
                    initializers.add(node.name);
                }
                boolean own = node.name.equals(DEFINITION) || node.name.startsWith(COLLECTION)
                        || node.name.equals(HOLDER);
                for (MethodNode method : node.methods) {
                    for (AbstractInsnNode insn : method.instructions.toArray()) {
                        String member = insn instanceof MethodInsnNode call ? call.owner + "." + call.name
                                : insn instanceof org.objectweb.asm.tree.FieldInsnNode field ? field.owner + "." + field.name
                                : insn instanceof org.objectweb.asm.tree.TypeInsnNode type ? type.desc : null;
                        if (member == null) {
                            continue;
                        }
                        if ((member.equals(DEFINITION + "." + MULTI) && !(node.name.equals(DEFINITION)
                                && method.name.equals("<init>")))
                                || (member.equals(HOLDER + ".initializeNeighbors") && !node.name.equals(DEFINITION)
                                        && !node.name.equals(HOLDER))
                                || (member.startsWith(COLLECTION) && !own)) {
                            outside.add(node.name + "." + method.name + " -> " + member);
                        }
                    }
                }
            }
        }
        if (!outside.isEmpty()) {
            throw new IllegalStateException("state construction used outside StateDefinition: " + outside);
        }
        if (args.length > 2) {
            initializers.sort(null);
            Files.write(Path.of(args[2]), initializers);
        }

        PlatformPatches.patchStateDefinitionNeighbourTables(jar, patched);
        List<String> written = new ArrayList<>();
        try (var walk = Files.walk(patched)) {
            walk.filter(Files::isRegularFile).forEach(path -> written.add(patched.relativize(path).toString()
                    .replace('\\', '/')));
        }
        if (!written.equals(List.of(DEFINITION + ".class"))) {
            throw new IllegalStateException("written " + written);
        }
        byte[] first = Files.readAllBytes(patched.resolve(DEFINITION + ".class"));
        int prologue = expectPrologueOnly(vanilla(jar, DEFINITION), first);

        PlatformPatches.patchStateDefinitionNeighbourTables(jar, patched);
        if (!Arrays.equals(first, Files.readAllBytes(patched.resolve(DEFINITION + ".class")))) {
            throw new IllegalStateException("the patch is not idempotent");
        }

        int negatives = 0;
        negatives += expectFailure(jar, node -> {
            MethodNode method = multi(node);
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.name.equals("forEach")) {
                    method.instructions.set(call, new InsnNode(Opcodes.POP2));
                    return;
                }
            }
            throw new IllegalStateException("no forEach");
        }, "a body without the neighbour visit");
        negatives += expectFailure(jar, node -> {
            MethodNode extra = new MethodNode(Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE, "gaiusExtraCaller",
                    "()Ljava/lang/Object;", null, null);
            extra.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            extra.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            extra.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            extra.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DEFINITION, MULTI, multi(node).desc, false));
            extra.instructions.add(new InsnNode(Opcodes.ARETURN));
            extra.maxStack = 3;
            node.methods.add(extra);
        }, "a second caller");
        negatives += expectFailure(jar, node -> {
            MethodNode method = multi(node);
            LabelNode start = new LabelNode();
            LabelNode end = new LabelNode();
            LabelNode handler = new LabelNode();
            InsnList head = new InsnList();
            head.add(start);
            head.add(new InsnNode(Opcodes.NOP));
            head.add(end);
            method.instructions.insert(head);
            InsnList tail = new InsnList();
            tail.add(handler);
            tail.add(new InsnNode(Opcodes.ATHROW));
            method.instructions.add(tail);
            method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        }, "an exception handler");
        System.out.println("STATE_NEIGHBOUR_PATCH_OK written=" + written + " prologue=" + prologue
                + " idempotent=true negative=" + negatives);
    }

    interface Mutation {
        void apply(ClassNode node);
    }

    private static MethodNode multi(ClassNode node) {
        return node.methods.stream().filter(method -> method.name.equals(MULTI)).findFirst().orElseThrow();
    }

    private static int expectFailure(String jar, Mutation mutation, String what) throws Exception {
        ClassNode node = read(vanilla(jar, DEFINITION));
        mutation.apply(node);
        Path root = Files.createTempDirectory("gaius-state-neighbours-negative-");
        try {
            Path file = root.resolve(DEFINITION + ".class");
            Files.createDirectories(file.getParent());
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            Files.write(file, writer.toByteArray());
            byte[] before = Files.readAllBytes(file);
            try {
                PlatformPatches.patchStateDefinitionNeighbourTables(jar, root);
            } catch (IllegalStateException expected) {
                try (var walk = Files.walk(root)) {
                    long files = walk.filter(Files::isRegularFile).count();
                    if (files != 1 || !Arrays.equals(before, Files.readAllBytes(file))) {
                        throw new IllegalStateException("the failed patch wrote files for " + what);
                    }
                }
                return 1;
            }
            throw new IllegalStateException("the patch accepted " + what);
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /**
     * The patched class with the prologue removed must print exactly like vanilla; returns the
     * number of prologue instructions.
     */
    private static int expectPrologueOnly(byte[] vanilla, byte[] patched) {
        ClassNode node = read(patched);
        MethodNode method = multi(node);
        int[] opcodes = {Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.INVOKESTATIC, Opcodes.DUP,
            Opcodes.IFNULL, Opcodes.GETSTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.ARETURN, -1, Opcodes.POP};
        AbstractInsnNode cursor = method.instructions.getFirst();
        List<AbstractInsnNode> remove = new ArrayList<>();
        for (int opcode : opcodes) {
            while (cursor != null && cursor.getOpcode() < 0 && !(opcode == -1 && cursor instanceof LabelNode)) {
                cursor = cursor.getNext();
            }
            if (cursor == null || (opcode == -1 ? !(cursor instanceof LabelNode) : cursor.getOpcode() != opcode)) {
                throw new IllegalStateException("prologue differs at " + remove.size());
            }
            remove.add(cursor);
            cursor = cursor.getNext();
        }
        MethodInsnNode create = (MethodInsnNode) remove.get(3);
        MethodInsnNode finish = (MethodInsnNode) remove.get(7);
        if (!create.owner.equals(TABLES) || !create.name.equals("create")
                || !finish.owner.equals(TABLES) || !finish.name.equals("finish")
                || ((VarInsnNode) remove.get(0)).var != 0 || ((VarInsnNode) remove.get(1)).var != 1
                || ((VarInsnNode) remove.get(2)).var != 2) {
            throw new IllegalStateException("prologue calls differ");
        }
        for (AbstractInsnNode insn : remove) {
            method.instructions.remove(insn);
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        String before = text(vanilla);
        String after = text(writer.toByteArray());
        if (!before.equals(after)) {
            String[] a = before.split("\n");
            String[] b = after.split("\n");
            int index = 0;
            while (index < Math.min(a.length, b.length) && a[index].equals(b[index])) {
                index++;
            }
            throw new IllegalStateException("the patch changed more than the prologue at line " + index + ":\n- "
                    + (index < a.length ? a[index] : "<end>") + "\n+ " + (index < b.length ? b[index] : "<end>"));
        }
        return opcodes.length;
    }

    private static byte[] vanilla(String jar, String owner) throws Exception {
        try (ZipFile zip = new ZipFile(jar)) {
            return zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes();
        }
    }

    /** Class text without frames and max values (the writer recomputes them). */
    private static String text(byte[] bytes) {
        StringWriter out = new StringWriter();
        new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(out)), ClassReader.SKIP_FRAMES);
        StringBuilder kept = new StringBuilder();
        for (String line : out.toString().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("MAXSTACK") || trimmed.startsWith("MAXLOCALS")) {
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
