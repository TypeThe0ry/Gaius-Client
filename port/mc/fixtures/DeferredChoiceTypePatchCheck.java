import dev.gaius.tools.patches.storage.StoragePatches;
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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * Checks MinecraftClientPatcher.patchDeferredChoiceTypeChecks on a vanilla client jar:
 *
 * <ul>
 *   <li>the whole jar holds exactly two {@code Util.fetchChoiceType} call sites outside
 *       {@code Util}, each followed by POP (so the patch covers every caller);</li>
 *   <li>the patch rewrites exactly those two instructions (full ASM text of each class compared,
 *       only the owner/name of the call differs) and nothing else;</li>
 *   <li>running it again on its own output is a no-op;</li>
 *   <li>a call whose result is used, a second site in EntityType$Builder and a missing block
 *       entity site each fail the patch.</li>
 * </ul>
 *   java DeferredChoiceTypePatchCheck <client-named.jar> <patch root> [<initializer list>]
 * The patch root then holds the two patched classes (the bootstrap gate runs them); the optional
 * list names every class of the jar with a static initializer.
 */
public final class DeferredChoiceTypePatchCheck {
    private static final String UTIL = "net/minecraft/util/Util";
    private static final String ENTITY_BUILDER = "net/minecraft/world/entity/EntityType$Builder";
    private static final String HELPER = "dev/gaius/browser/BrowserChoiceTypeChecks";

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        Path patched = Path.of(args[1]);
        List<String> sites = new ArrayList<>();
        List<String> initializers = new ArrayList<>();
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
                if (node.name.equals(UTIL)) {
                    continue;
                }
                for (MethodNode method : node.methods) {
                    for (AbstractInsnNode insn : method.instructions.toArray()) {
                        if (insn instanceof MethodInsnNode call && call.owner.equals(UTIL)
                                && call.name.equals("fetchChoiceType")) {
                            AbstractInsnNode next = call.getNext();
                            while (next != null && next.getOpcode() < 0) {
                                next = next.getNext();
                            }
                            sites.add(node.name + "." + method.name + method.desc
                                    + (next != null && next.getOpcode() == Opcodes.POP ? " POP" : " USED"));
                        }
                    }
                }
            }
        }
        if (args.length > 2) {
            initializers.sort(null);
            Files.write(Path.of(args[2]), initializers);
        }
        if (sites.size() != 2 || sites.stream().anyMatch(site -> !site.endsWith(" POP"))) {
            throw new IllegalStateException("fetchChoiceType call sites: " + sites);
        }
        String blockOwner = sites.stream().map(site -> site.substring(0, site.indexOf('.')))
                .filter(owner -> !owner.equals(ENTITY_BUILDER)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no block entity site: " + sites));

        StoragePatches.patchDeferredChoiceTypeChecks(jar, patched);
        List<String> written = new ArrayList<>();
        try (var walk = Files.walk(patched)) {
            walk.filter(Files::isRegularFile).forEach(path -> written.add(patched.relativize(path).toString()
                    .replace('\\', '/')));
        }
        written.sort(null);
        List<String> expectedFiles = new ArrayList<>(List.of(ENTITY_BUILDER + ".class", blockOwner + ".class"));
        expectedFiles.sort(null);
        if (!written.equals(expectedFiles)) {
            throw new IllegalStateException("written " + written + ", expected " + expectedFiles);
        }
        byte[][] first = new byte[2][];
        String[] owners = {ENTITY_BUILDER, blockOwner};
        for (int index = 0; index < 2; index++) {
            first[index] = Files.readAllBytes(patched.resolve(owners[index] + ".class"));
            expectOneCallSiteChange(vanilla(jar, owners[index]), first[index]);
        }

        StoragePatches.patchDeferredChoiceTypeChecks(jar, patched);
        for (int index = 0; index < 2; index++) {
            if (!Arrays.equals(first[index], Files.readAllBytes(patched.resolve(owners[index] + ".class")))) {
                throw new IllegalStateException("the patch is not idempotent for " + owners[index]);
            }
        }

        int negatives = 0;
        negatives += expectFailure(jar, ENTITY_BUILDER, node -> {
            MethodInsnNode call = firstCall(node);
            AbstractInsnNode pop = call.getNext();
            while (pop.getOpcode() != Opcodes.POP) {
                pop = pop.getNext();
            }
            node.methods.stream().filter(method -> method.instructions.contains(call)).findFirst().orElseThrow()
                    .instructions.insertBefore(pop, new InsnNode(Opcodes.NOP));
        }, "a result that is not dropped at once");
        negatives += expectFailure(jar, ENTITY_BUILDER, node -> {
            MethodInsnNode call = firstCall(node);
            MethodNode method = node.methods.stream().filter(candidate -> candidate.instructions.contains(call))
                    .findFirst().orElseThrow();
            org.objectweb.asm.tree.InsnList extra = new org.objectweb.asm.tree.InsnList();
            extra.add(new InsnNode(Opcodes.ACONST_NULL));
            extra.add(new InsnNode(Opcodes.ACONST_NULL));
            extra.add(new MethodInsnNode(Opcodes.INVOKESTATIC, call.owner, call.name, call.desc, false));
            extra.add(new InsnNode(Opcodes.POP));
            method.instructions.insert(extra);
            method.maxStack = Math.max(method.maxStack, 2);
        }, "a second EntityType$Builder site");
        negatives += expectFailure(jar, blockOwner, node -> firstCall(node).name = "fetchChoiceTypeElsewhere",
                "no block entity site");
        System.out.println("DEFERRED_CHOICE_TYPE_PATCH_OK sites=" + sites + " changed=2 idempotent=true negative="
                + negatives);
    }

    interface Mutation {
        void apply(ClassNode node);
    }

    private static MethodInsnNode firstCall(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(UTIL)
                        && call.name.equals("fetchChoiceType")) {
                    return call;
                }
            }
        }
        throw new IllegalStateException("no fetchChoiceType call in " + node.name);
    }

    private static int expectFailure(String jar, String owner, Mutation mutation, String what) throws Exception {
        ClassNode node = read(vanilla(jar, owner));
        mutation.apply(node);
        Path root = Files.createTempDirectory("gaius-choice-type-negative-");
        try {
            Path file = root.resolve(owner + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, write(node));
            byte[] before = Files.readAllBytes(file);
            try {
                StoragePatches.patchDeferredChoiceTypeChecks(jar, root);
            } catch (IllegalStateException expected) {
                // Fail closed: nothing written before the shape check failed.
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

    private static void expectOneCallSiteChange(byte[] vanilla, byte[] patched) {
        String[] before = text(vanilla).split("\n");
        String[] after = text(patched).split("\n");
        if (before.length != after.length) {
            throw new IllegalStateException("instruction count changed");
        }
        int changed = 0;
        for (int index = 0; index < before.length; index++) {
            if (before[index].equals(after[index])) {
                continue;
            }
            changed++;
            String expected = before[index].replace(UTIL + ".fetchChoiceType", HELPER + ".defer");
            if (!before[index].contains("INVOKESTATIC " + UTIL + ".fetchChoiceType") || !expected.equals(after[index])) {
                throw new IllegalStateException("unexpected change:\n- " + before[index] + "\n+ " + after[index]);
            }
        }
        if (changed != 1) {
            throw new IllegalStateException("changed lines " + changed);
        }
    }

    private static byte[] vanilla(String jar, String owner) throws Exception {
        try (ZipFile zip = new ZipFile(jar)) {
            return zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes();
        }
    }

    private static String text(byte[] bytes) {
        StringWriter out = new StringWriter();
        new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(out)), 0);
        return out.toString();
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
}
