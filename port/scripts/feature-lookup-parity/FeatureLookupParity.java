package dev.gaius.parity;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Differential test of the two feature-placement lookup patches against the vanilla classes
 * (MinecraftClientPatcher.patchBulkSectionAccessLookup and patchHolderReferenceTagMemo): the
 * patched {@code BulkSectionAccess} and {@code Holder.Reference} from a patched client jar run
 * the same random operation sequences as a renamed copy of the vanilla class from the vanilla
 * jar, inside one class loader (so they share every other Minecraft type), and every answer,
 * every thrown exception and every call the class makes on its collaborators must be equal.
 *
 * <p>BulkSectionAccess: runs of positions inside one section, jumps between sections, positions
 * above and below the build height, positions whose 22/20/22-bit section key aliases the cached
 * one (same key, a different section Y or a different section X/Z), and extreme ints. The level
 * is a proxy that serves a fixed height range and records every getChunk; the chunks hold fake
 * sections that count acquire and release. Compared: the section returned by every call, the
 * getChunk sequence, and the acquire/release counts after close().
 *
 * <p>Holder.Reference: references with random tag sets, never bound or rebound between rounds,
 * and random queries (heavily repeated tags, null, unbound). Compared: the boolean or the
 * exception class and message.
 *
 * <p>{@code --mutate <name>} edits the patched class before the run (the test must then fail,
 * proving it can see the defect it guards against).
 *
 * <p>usage: {@code FeatureLookupParity --vanilla-jar <jar> --patched-jar <jar> --work <dir>
 * --libs <path list> [--iterations N] [--seed N] [--mutate bulk-no-y|bulk-no-key|holder-hit-false|holder-no-clear]}
 */
public final class FeatureLookupParity {
    private static final String BULK = "net/minecraft/world/level/chunk/BulkSectionAccess";
    private static final String HOLDER_REFERENCE = "net/minecraft/core/Holder$Reference";
    private static final String VANILLA_BULK = "gaius/test/VanillaBulkSectionAccess";
    private static final String VANILLA_REFERENCE = "gaius/test/VanillaReference";
    private static final String FAKE_SECTION = "gaius/test/FakeSection";

    private static final List<String> failures = new ArrayList<>();
    private static final int MIN_SECTION_Y = -4;
    private static final int SECTIONS = 24;

    private FeatureLookupParity() {
    }

    public static void main(String[] args) throws Throwable {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            options.put(args[index], args[index + 1]);
        }
        Path work = Path.of(options.get("--work"));
        Files.createDirectories(work);
        int iterations = Integer.parseInt(options.getOrDefault("--iterations", "200000"));
        long seed = Long.parseLong(options.getOrDefault("--seed", "20261113"));
        String mutate = options.get("--mutate");

        Path unsigned = work.resolve("vanilla-unsigned.jar");
        stripSignature(Path.of(options.get("--vanilla-jar")), unsigned);
        Path patchedClasses = work.resolve("patched-classes");
        byte[] patchedBulk = readEntry(Path.of(options.get("--patched-jar")), BULK + ".class");
        byte[] patchedReference = readEntry(Path.of(options.get("--patched-jar")), HOLDER_REFERENCE + ".class");
        if (mutate != null) {
            patchedBulk = mutate(patchedBulk, mutate, "bulk");
            patchedReference = mutate(patchedReference, mutate, "holder");
        }
        write(patchedClasses.resolve(BULK + ".class"), patchedBulk);
        write(patchedClasses.resolve(HOLDER_REFERENCE + ".class"), patchedReference);

        List<URL> urls = new ArrayList<>();
        urls.add(patchedClasses.toUri().toURL());
        urls.add(unsigned.toUri().toURL());
        for (String entry : options.get("--libs").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        URLClassLoader minecraft = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        byte[] vanillaBulk = rename(readEntry(Path.of(options.get("--vanilla-jar")), BULK + ".class"), BULK, VANILLA_BULK);
        byte[] vanillaReference = rename(readEntry(Path.of(options.get("--vanilla-jar")), HOLDER_REFERENCE + ".class"),
                HOLDER_REFERENCE, VANILLA_REFERENCE);
        Loader loader = new Loader(minecraft);
        loader.define(VANILLA_BULK, vanillaBulk);
        loader.define(VANILLA_REFERENCE, vanillaReference);
        loader.define(FAKE_SECTION.replace('/', '.'), fakeSection());

        Random random = new Random(seed);
        String patched = BULK.replace('/', '.');
        // The patched class must really be the patched one (the Function view and the memo fields).
        Class<?> patchedBulkClass = Class.forName(patched, true, loader);
        if (!java.util.function.Function.class.isAssignableFrom(patchedBulkClass)) {
            fail("patched BulkSectionAccess does not implement Function (is this the patched jar?)");
        }
        Class<?> patchedReferenceClass = Class.forName(HOLDER_REFERENCE.replace('/', '.'), true, loader);
        if (java.util.Arrays.stream(patchedReferenceClass.getDeclaredFields()).noneMatch(f -> f.getName().equals("gaius$tagHit"))) {
            fail("patched Holder.Reference has no memo fields (is this the patched jar?)");
        }
        int calls = bulkSections(loader, patchedBulkClass, loader.loadClass(VANILLA_BULK.replace('/', '.')), random, iterations);
        int queries = referenceTags(loader, patchedReferenceClass, loader.loadClass(VANILLA_REFERENCE.replace('/', '.')),
                random, iterations);
        if (failures.isEmpty()) {
            System.out.println("FEATURE_LOOKUP_PARITY PASS bulkSectionCalls=" + calls + " tagQueries=" + queries
                    + (mutate == null ? "" : " mutate=" + mutate));
        } else {
            for (String failure : failures.subList(0, Math.min(failures.size(), 20))) {
                System.out.println("FEATURE_LOOKUP_PARITY_MISMATCH " + failure);
            }
            System.out.println("FEATURE_LOOKUP_PARITY FAIL mismatches=" + failures.size()
                    + (mutate == null ? "" : " mutate=" + mutate));
            System.exit(1);
        }
    }

    private static void fail(String message) {
        failures.add(message);
    }

    // ---- BulkSectionAccess ----------------------------------------------------------------

    private static final class LevelState {
        final Map<Long, Object> chunks = new HashMap<>();
        final List<String> chunkRequests = new ArrayList<>();
        final List<Object> sections = new ArrayList<>();
    }

    private static int bulkSections(Loader loader, Class<?> patched, Class<?> vanilla, Random random, int iterations)
            throws Throwable {
        Class<?> blockPosClass = loader.loadClass("net.minecraft.core.BlockPos");
        Class<?> levelAccessor = loader.loadClass("net.minecraft.world.level.LevelAccessor");
        Class<?> chunkAccess = loader.loadClass("net.minecraft.world.level.chunk.ChunkAccess");
        Class<?> protoChunk = loader.loadClass("net.minecraft.world.level.chunk.ProtoChunk");
        Class<?> sectionClass = loader.loadClass("net.minecraft.world.level.chunk.LevelChunkSection");
        Class<?> fake = loader.loadClass(FAKE_SECTION.replace('/', '.'));
        Constructor<?> blockPos = blockPosClass.getConstructor(int.class, int.class, int.class);
        sun.misc.Unsafe unsafe = unsafe();
        Field sectionsField = chunkAccess.getDeclaredField("sections");
        long sectionsOffset = unsafe.objectFieldOffset(sectionsField);

        LevelState[] states = {new LevelState(), new LevelState()};
        Object[] accesses = new Object[2];
        MethodHandle[] getSection = new MethodHandle[2];
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?>[] classes = {patched, vanilla};
        for (int side = 0; side < 2; side++) {
            LevelState state = states[side];
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getSectionIndex" -> ((Integer) args[0] >> 4) - MIN_SECTION_Y;
                case "getSectionsCount" -> SECTIONS;
                case "getChunk" -> {
                    if (args.length != 2 || !(args[0] instanceof Integer)) {
                        throw new UnsupportedOperationException(method.toString());
                    }
                    int chunkX = (Integer) args[0];
                    int chunkZ = (Integer) args[1];
                    state.chunkRequests.add(chunkX + "," + chunkZ);
                    long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
                    Object chunk = state.chunks.get(key);
                    if (chunk == null) {
                        chunk = unsafe.allocateInstance(protoChunk);
                        Object array = java.lang.reflect.Array.newInstance(sectionClass, SECTIONS);
                        for (int index = 0; index < SECTIONS; index++) {
                            Object section = unsafe.allocateInstance(fake);
                            fake.getField("label").setInt(section, (chunkX * 31 + chunkZ) * 97 + index);
                            java.lang.reflect.Array.set(array, index, section);
                            state.sections.add(section);
                        }
                        unsafe.putObject(chunk, sectionsOffset, array);
                        state.chunks.put(key, chunk);
                    }
                    yield chunk;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "level";
                default -> throw new UnsupportedOperationException("level." + method);
            };
            Object level = Proxy.newProxyInstance(loader, new Class<?>[] {levelAccessor}, handler);
            accesses[side] = classes[side].getConstructor(levelAccessor).newInstance(level);
            getSection[side] = lookup.findVirtual(classes[side], "getSection", MethodType.methodType(sectionClass, blockPosClass));
        }

        int calls = 0;
        int cx = 0;
        int cy = 0;
        int cz = 0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            int x;
            int y;
            int z;
            int kind = random.nextInt(100);
            if (kind < 55) {
                // a run inside the current section
                x = (cx << 4) + random.nextInt(16);
                y = (cy << 4) + random.nextInt(16);
                z = (cz << 4) + random.nextInt(16);
            } else if (kind < 70) {
                // jump to another section, sometimes outside the build height
                cx = random.nextInt(9) - 4;
                cz = random.nextInt(9) - 4;
                cy = random.nextInt(34) - 6;
                x = (cx << 4) + random.nextInt(16);
                y = (cy << 4) + random.nextInt(16);
                z = (cz << 4) + random.nextInt(16);
            } else if (kind < 80) {
                // the cached key with a different section Y (alias of the 20 bit Y)
                x = (cx << 4) + random.nextInt(16);
                z = (cz << 4) + random.nextInt(16);
                y = (cy << 4) + random.nextInt(16) + (random.nextInt(5) - 2) * (1 << 24);
            } else if (kind < 90) {
                // the cached key with a different section X or Z (alias of the 22 bit X/Z)
                x = (cx << 4) + random.nextInt(16) + (random.nextInt(3) - 1) * (1 << 26);
                z = (cz << 4) + random.nextInt(16) + (random.nextInt(3) - 1) * (1 << 26);
                y = (cy << 4) + random.nextInt(16);
            } else if (kind < 95) {
                int[] extremes = {Integer.MIN_VALUE, Integer.MAX_VALUE, -64, -65, 319, 320, 0, -1, 15, 16};
                x = random.nextInt(32) - 16;
                z = random.nextInt(32) - 16;
                y = extremes[random.nextInt(extremes.length)];
            } else {
                // a neighbour section of the current one
                x = (cx << 4) + random.nextInt(48) - 16;
                y = (cy << 4) + random.nextInt(48) - 16;
                z = (cz << 4) + random.nextInt(48) - 16;
            }
            Object[] answers = new Object[2];
            for (int side = 0; side < 2; side++) {
                Object position = blockPos.newInstance(x, y, z);
                Object section = getSection[side].invoke(accesses[side], position);
                answers[side] = section == null ? -1 : (Object) fake.getField("label").getInt(section);
            }
            calls++;
            if (!answers[0].equals(answers[1])) {
                fail("BulkSectionAccess.getSection(" + x + "," + y + "," + z + ") patched=" + answers[0]
                        + " vanilla=" + answers[1] + " at call " + calls);
                if (failures.size() > 40) {
                    return calls;
                }
            }
        }
        if (!states[0].chunkRequests.equals(states[1].chunkRequests)) {
            fail("getChunk sequence differs: patched " + states[0].chunkRequests.size() + " vs vanilla "
                    + states[1].chunkRequests.size());
        }
        for (int side = 0; side < 2; side++) {
            classes[side].getMethod("close").invoke(accesses[side]);
        }
        if (states[0].sections.size() != states[1].sections.size()) {
            fail("section count differs after the run");
        } else {
            for (int index = 0; index < states[0].sections.size(); index++) {
                Object a = states[0].sections.get(index);
                Object b = states[1].sections.get(index);
                int[] counts = {fake.getField("acquired").getInt(a), fake.getField("released").getInt(a),
                        fake.getField("acquired").getInt(b), fake.getField("released").getInt(b)};
                if (counts[0] != counts[2] || counts[1] != counts[3]) {
                    fail("acquire/release counts of section " + fake.getField("label").getInt(a) + " differ: "
                            + counts[0] + "/" + counts[1] + " vs " + counts[2] + "/" + counts[3]);
                    break;
                }
            }
        }
        return calls;
    }

    // ---- Holder.Reference -----------------------------------------------------------------

    private static int referenceTags(Loader loader, Class<?> patched, Class<?> vanilla, Random random, int iterations)
            throws Throwable {
        Class<?> identifier = loader.loadClass("net.minecraft.resources.Identifier");
        Class<?> resourceKey = loader.loadClass("net.minecraft.resources.ResourceKey");
        Class<?> tagKeyClass = loader.loadClass("net.minecraft.tags.TagKey");
        Method fromNamespaceAndPath = identifier.getMethod("fromNamespaceAndPath", String.class, String.class);
        Method createRegistryKey = resourceKey.getMethod("createRegistryKey", identifier);
        Method create = tagKeyClass.getMethod("create", resourceKey, identifier);
        Object registry = createRegistryKey.invoke(null, fromNamespaceAndPath.invoke(null, "gaius", "blocks"));
        List<Object> tags = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            tags.add(create.invoke(null, registry, fromNamespaceAndPath.invoke(null, "gaius", "tag_" + index)));
        }
        Method bind = null;
        Field tagsField = null;
        sun.misc.Unsafe unsafe = unsafe();
        Class<?>[] classes = {patched, vanilla};
        MethodHandle[] is = new MethodHandle[2];
        Method[] binds = new Method[2];
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (int side = 0; side < 2; side++) {
            is[side] = lookup.findVirtual(classes[side], "is", MethodType.methodType(boolean.class, tagKeyClass));
            binds[side] = classes[side].getDeclaredMethod("bindTags", Collection.class);
            binds[side].setAccessible(true);
        }
        int count = 24;
        Object[][] references = new Object[2][count];
        for (int side = 0; side < 2; side++) {
            for (int index = 0; index < count; index++) {
                references[side][index] = unsafe.allocateInstance(classes[side]);
            }
        }
        int queries = 0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            int index = random.nextInt(count);
            int action = random.nextInt(1000);
            if (action < 6) {
                // (re)bind the same random set on both sides
                Set<Object> set = new HashSet<>();
                int size = random.nextInt(8);
                for (int member = 0; member < size; member++) {
                    set.add(tags.get(random.nextInt(tags.size())));
                }
                for (int side = 0; side < 2; side++) {
                    binds[side].invoke(references[side][index], new ArrayList<>(set));
                }
                continue;
            }
            Object tag;
            int pick = random.nextInt(100);
            if (pick < 2) {
                tag = null;
            } else if (pick < 75) {
                tag = tags.get(random.nextInt(4));
            } else {
                tag = tags.get(random.nextInt(tags.size()));
            }
            Object[] answers = new Object[2];
            for (int side = 0; side < 2; side++) {
                try {
                    answers[side] = is[side].invoke(references[side][index], tag);
                } catch (Throwable throwable) {
                    // The helpful NullPointerException text depends on whether the JIT compiled the throwing
                    // frame (a null tag in an immutable Set), so only the class counts there.
                    answers[side] = throwable.getClass().getName()
                            + (throwable instanceof NullPointerException ? "" : ":" + throwable.getMessage());
                }
            }
            queries++;
            if (!answers[0].equals(answers[1])) {
                fail("Holder.Reference.is(" + (tag == null ? "null" : tag) + ") reference " + index + " patched="
                        + answers[0] + " vanilla=" + answers[1] + " at query " + queries);
                if (failures.size() > 40) {
                    return queries;
                }
            }
        }
        return queries;
    }

    // ---- class plumbing -------------------------------------------------------------------

    private static final class Loader extends ClassLoader {
        Loader(ClassLoader parent) {
            super(parent);
        }

        void define(String name, byte[] bytes) {
            defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static byte[] rename(byte[] bytes, String from, String to) {
        ClassNode node = new ClassNode();
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(node, new SimpleRemapper(from, to)), 0);
        // Holder is sealed (Reference and Direct only): the renamed copy stands alone.
        node.interfaces.clear();
        node.innerClasses.clear();
        node.nestHostClass = null;
        node.nestMembers = null;
        node.outerClass = null;
        node.outerMethod = null;
        node.outerMethodDesc = null;
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] fakeSection() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, FAKE_SECTION, null,
                "net/minecraft/world/level/chunk/LevelChunkSection", null);
        for (String field : new String[] {"label", "acquired", "released"}) {
            writer.visitField(Opcodes.ACC_PUBLIC, field, "I", null, null).visitEnd();
        }
        for (String name : new String[] {"acquire", "release"}) {
            String counter = name.equals("acquire") ? "acquired" : "released";
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitInsn(Opcodes.DUP);
            method.visitFieldInsn(Opcodes.GETFIELD, FAKE_SECTION, counter, "I");
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD);
            method.visitFieldInsn(Opcodes.PUTFIELD, FAKE_SECTION, counter, "I");
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(3, 1);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Defects the test must see: each edits the patched class the way a plausible mistake would. */
    private static byte[] mutate(byte[] bytes, String mutation, String target) {
        if (!mutation.startsWith(target)) {
            return bytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                switch (mutation) {
                    case "bulk-no-y" -> {
                        // the section Y compare always agrees
                        if (method.name.equals("getSection") && instruction instanceof MethodInsnNode call
                                && call.name.equals("y") && call.owner.endsWith("SectionPos")) {
                            org.objectweb.asm.tree.InsnList replacement = new org.objectweb.asm.tree.InsnList();
                            replacement.add(new InsnNode(Opcodes.POP2));
                            replacement.add(new InsnNode(Opcodes.DUP));
                            method.instructions.insertBefore(call, replacement);
                            method.instructions.remove(call);
                            changed = true;
                        }
                    }
                    case "bulk-no-key" -> {
                        // the first section key compare always agrees
                        if (method.name.equals("getSection") && instruction.getOpcode() == Opcodes.LCMP && !changed) {
                            org.objectweb.asm.tree.InsnList replacement = new org.objectweb.asm.tree.InsnList();
                            replacement.add(new InsnNode(Opcodes.POP2));
                            replacement.add(new InsnNode(Opcodes.POP2));
                            replacement.add(new InsnNode(Opcodes.ICONST_0));
                            method.instructions.insertBefore(instruction, replacement);
                            method.instructions.remove(instruction);
                            changed = true;
                        }
                    }
                    case "holder-hit-false" -> {
                        if (method.name.equals("is") && instruction.getOpcode() == Opcodes.ICONST_1 && !changed
                                && instruction.getNext() != null && instruction.getNext().getOpcode() == Opcodes.IRETURN) {
                            method.instructions.set(instruction, new InsnNode(Opcodes.ICONST_0));
                            changed = true;
                        }
                    }
                    case "holder-no-clear" -> {
                        if (method.name.equals("bindTags") && instruction instanceof FieldInsnNode field
                                && field.getOpcode() == Opcodes.PUTFIELD && field.name.startsWith("gaius$tag")) {
                            method.instructions.set(field, new InsnNode(Opcodes.POP2));
                            changed = true;
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown mutation " + mutation);
                }
            }
        }
        if (!changed) {
            throw new IllegalStateException("mutation " + mutation + " found nothing to change");
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static byte[] readEntry(Path jar, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry found = zip.getEntry(entry);
            if (found == null) {
                throw new IOException(jar + " has no " + entry);
            }
            try (var stream = zip.getInputStream(found)) {
                return stream.readAllBytes();
            }
        }
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static void stripSignature(Path input, Path output) throws IOException {
        if (Files.exists(output) && Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(input)) >= 0) {
            return;
        }
        try (ZipFile zip = new ZipFile(input.toFile()); OutputStream out = Files.newOutputStream(output);
                ZipOutputStream jar = new ZipOutputStream(out)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA")
                        || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                    continue;
                }
                jar.putNextEntry(new ZipEntry(name));
                if (!entry.isDirectory()) {
                    try (var stream = zip.getInputStream(entry)) {
                        stream.transferTo(jar);
                    }
                }
                jar.closeEntry();
            }
        }
    }
}
