package dev.gaius.tools.patches.render;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Applies SectionSliceCachePatches to a clean client jar, checks the bytecode it leaves
 * (idempotence, constructor memo, every allocationMap mutation hooked, the one redirected lookup)
 * and then runs SectionSliceCacheRuntimeCheck on the patched vanilla classes.
 *
 * <p>Arguments: scratch dir, helper+check classes dir, client jar, then library jars.
 */
public final class SectionSliceCacheFixture {
    private SectionSliceCacheFixture() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: scratch checkClasses clientJar [libraries...]");
        }
        Path scratch = Path.of(args[0]);
        Path checkClasses = Path.of(args[1]);
        String jar = args[2];
        Path root = scratch.resolve("patched");
        Files.createDirectories(root);

        SectionSliceCachePatches.apply(jar, root);
        byte[] uber = Files.readAllBytes(root.resolve(SectionSliceCachePatches.UBER_GPU_BUFFER + ".class"));
        byte[] mesh = Files.readAllBytes(root.resolve(SectionSliceCachePatches.COMPILED_SECTION_MESH + ".class"));
        byte[] renderer = Files.readAllBytes(root.resolve(SectionSliceCachePatches.LEVEL_RENDERER + ".class"));
        SectionSliceCachePatches.apply(jar, root);
        check(Arrays.equals(uber, Files.readAllBytes(root.resolve(SectionSliceCachePatches.UBER_GPU_BUFFER + ".class"))),
                "UberGpuBuffer patch is not idempotent");
        check(Arrays.equals(mesh, Files.readAllBytes(root.resolve(SectionSliceCachePatches.COMPILED_SECTION_MESH + ".class"))),
                "CompiledSectionMesh patch is not idempotent");
        check(Arrays.equals(renderer, Files.readAllBytes(root.resolve(SectionSliceCachePatches.LEVEL_RENDERER + ".class"))),
                "LevelRenderer patch is not idempotent");

        checkUberHooks(node(uber));
        checkMeshMemo(node(mesh));
        checkRenderer(node(renderer), vanilla(jar, SectionSliceCachePatches.LEVEL_RENDERER));
        writeFakeGpuBuffer(jar, root);

        List<URL> urls = new ArrayList<>();
        urls.add(root.toUri().toURL());
        urls.add(checkClasses.toUri().toURL());
        for (int index = 2; index < args.length; index++) {
            urls.add(Path.of(args[index]).toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> runtime = Class.forName("dev.gaius.tools.patches.render.SectionSliceCacheRuntimeCheck",
                    true, loader);
            Method main = runtime.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        }
        System.out.println("SECTION_SLICE_CACHE_FIXTURE_PASS");
    }

    private static void checkUberHooks(ClassNode node) {
        int mutations = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && field.name.equals(SectionSliceCachePatches.ALLOCATION_MAP)) {
                    MethodInsnNode call = SectionSliceCachePatches.consumer(field, method.name);
                    if (call.name.equals("get")) {
                        continue;
                    }
                    mutations++;
                    check(call.getNext() instanceof MethodInsnNode hook && hook.getOpcode() == Opcodes.INVOKESTATIC
                                    && hook.owner.equals(SectionSliceCachePatches.CACHE) && hook.name.equals("invalidate"),
                            "allocationMap." + call.name + " in " + method.name + " is not followed by invalidate()");
                }
            }
        }
        check(mutations == 3, "expected put, remove and clear hooks, found " + mutations);
    }

    private static void checkMeshMemo(ClassNode node) {
        MethodNode constructor = node.methods.stream().filter(method -> method.name.equals("<init>"))
                .findFirst().orElseThrow();
        AbstractInsnNode cursor = constructor.instructions.getFirst();
        while (!(cursor instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL)) {
            cursor = cursor.getNext();
        }
        int[] expected = {Opcodes.ALOAD, Opcodes.NEW, Opcodes.DUP, Opcodes.INVOKESPECIAL, Opcodes.PUTFIELD};
        for (int opcode : expected) {
            cursor = cursor.getNext();
            check(cursor.getOpcode() == opcode, "CompiledSectionMesh memo initialization shape changed");
        }
        check(((FieldInsnNode) cursor).name.equals(SectionSliceCachePatches.MEMO_FIELD),
                "CompiledSectionMesh memo field is not initialized right after super()");
        long statics = node.methods.stream().filter(method -> method.name.equals(SectionSliceCachePatches.STATIC_SLICE)
                && (method.access & Opcodes.ACC_STATIC) != 0).count();
        check(statics == 1, "CompiledSectionMesh static slice lookup missing");
    }

    private static void checkRenderer(ClassNode patched, ClassNode original) {
        check(patched.methods.size() == original.methods.size(), "LevelRenderer method count changed");
        int differences = 0;
        for (int index = 0; index < patched.methods.size(); index++) {
            MethodNode after = patched.methods.get(index);
            MethodNode before = original.methods.get(index);
            AbstractInsnNode[] left = after.instructions.toArray();
            AbstractInsnNode[] right = before.instructions.toArray();
            check(left.length == right.length, "LevelRenderer." + after.name + " instruction count changed");
            for (int at = 0; at < left.length; at++) {
                if (left[at].getOpcode() == right[at].getOpcode() && sameCall(left[at], right[at])) {
                    continue;
                }
                differences++;
                check(right[at] instanceof MethodInsnNode vanilla && vanilla.name.equals("getRenderSectionSlice")
                                && left[at] instanceof MethodInsnNode redirect
                                && redirect.getOpcode() == Opcodes.INVOKESTATIC
                                && redirect.name.equals(SectionSliceCachePatches.STATIC_SLICE),
                        "LevelRenderer." + after.name + " changed beyond the slice lookup");
            }
        }
        check(differences == 1, "expected exactly one redirected instruction, found " + differences);
    }

    /**
     * Where the profile's GpuBuffer is an abstract class (26.2) the runtime check needs a concrete
     * subclass to allocate; it has no members (instances are made without constructors, and only
     * identity equals/hashCode are used).
     */
    private static void writeFakeGpuBuffer(String jar, Path root) throws Exception {
        ClassNode heap = vanilla(jar, SectionSliceCachePatches.UBER_GPU_BUFFER + "$UberGpuBufferHeap");
        String type = heap.fields.stream().filter(field -> field.name.equals("gpuBuffer")).findFirst()
                .orElseThrow().desc;
        String internal = type.substring(1, type.length() - 1);
        ClassNode buffer = vanilla(jar, internal);
        if ((buffer.access & Opcodes.ACC_INTERFACE) != 0) {
            return;
        }
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "dev/gaius/tools/patches/render/FakeGpuBuffer", null, internal, null);
        writer.visitEnd();
        Path output = root.resolve("dev/gaius/tools/patches/render/FakeGpuBuffer.class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static boolean sameCall(AbstractInsnNode left, AbstractInsnNode right) {
        if (left instanceof MethodInsnNode a && right instanceof MethodInsnNode b) {
            return a.owner.equals(b.owner) && a.name.equals(b.name) && a.desc.equals(b.desc);
        }
        return true;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static ClassNode vanilla(String jar, String owner) throws Exception {
        try (ZipFile zip = new ZipFile(jar); InputStream input = zip.getInputStream(zip.getEntry(owner + ".class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
