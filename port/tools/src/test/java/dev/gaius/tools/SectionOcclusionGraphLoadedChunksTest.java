package dev.gaius.tools;

import dev.gaius.tools.patches.render.RenderPatches;
import java.lang.reflect.Method;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Executes the patched vanilla method without loading the rendering engine. */
public final class SectionOcclusionGraphLoadedChunksTest {
    private static final String GRAPH = "net/minecraft/client/renderer/SectionOcclusionGraph";
    private static final String SET = "it/unimi/dsi/fastutil/longs/LongOpenHashSet";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("pass vanilla client jars");
        for (String jar : args) check(jar);
    }

    private static void check(String jar) throws Exception {
        ClassNode original = new ClassNode();
        try (JarFile input = new JarFile(jar, false)) {
            try (var stream = input.getInputStream(input.getJarEntry(GRAPH + ".class"))) {
                new ClassReader(stream).accept(original, 0);
            }
        }
        MethodNode update = original.methods.stream()
                .filter(method -> method.name.equals("updateLoadedChunks"))
                .findFirst().orElseThrow();
        ClassNode fixture = new ClassNode();
        fixture.version = Opcodes.V21;
        fixture.access = Opcodes.ACC_PUBLIC;
        fixture.name = GRAPH;
        fixture.superName = "java/lang/Object";
        fixture.methods.add(update);
        Method patch = RenderPatches.class.getDeclaredMethod(
                "patchSectionOcclusionGraphBrowserLoadedChunkRebuild", ClassNode.class);
        patch.setAccessible(true);
        patch.invoke(null, fixture);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        fixture.accept(writer);
        writer.visitField(Opcodes.ACC_PUBLIC, "loadedChunks", "L" + SET + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "invalidations", "I", null, null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitTypeInsn(Opcodes.NEW, SET);
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, SET, "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, GRAPH, "loadedChunks", "L" + SET + ";");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        var invalidate = writer.visitMethod(Opcodes.ACC_PUBLIC, "invalidate", "()V", null, null);
        invalidate.visitCode();
        invalidate.visitVarInsn(Opcodes.ALOAD, 0);
        invalidate.visitInsn(Opcodes.DUP);
        invalidate.visitFieldInsn(Opcodes.GETFIELD, GRAPH, "invalidations", "I");
        invalidate.visitInsn(Opcodes.ICONST_1);
        invalidate.visitInsn(Opcodes.IADD);
        invalidate.visitFieldInsn(Opcodes.PUTFIELD, GRAPH, "invalidations", "I");
        invalidate.visitInsn(Opcodes.RETURN);
        invalidate.visitMaxs(0, 0);
        invalidate.visitEnd();
        writer.visitEnd();
        Class<?> graph = new ClassLoader(SectionOcclusionGraphLoadedChunksTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, writer.toByteArray(), 0, writer.toByteArray().length); }
        }.define();
        Class<?> set = Class.forName(SET.replace('/', '.'));
        Method add = set.getMethod("add", long.class);
        Method size = set.getMethod("size");
        Method contains = set.getMethod("contains", long.class);
        Method run = graph.getMethod("updateLoadedChunks", set, set);
        Object instance = graph.getConstructor().newInstance();
        Object empty = set.getConstructor().newInstance();
        run.invoke(instance, empty, empty);
        require(graph.getField("invalidations").getInt(instance) == 0, "empty update");

        Object added = set.getConstructor().newInstance();
        add.invoke(added, 42L);
        run.invoke(instance, added, empty);
        require(graph.getField("invalidations").getInt(instance) == 1, "new addition rebuilt");
        require(size.invoke(added).equals(1), "addition input set consumed");

        // The same add set can be observed by early and late update calls in a
        // browser frame. An already-loaded key is a true no-op.
        run.invoke(instance, added, empty);
        require(graph.getField("invalidations").getInt(instance) == 1, "add existing is a no-op");

        Object newAddition = set.getConstructor().newInstance();
        add.invoke(newAddition, 43L);
        run.invoke(instance, newAddition, empty);
        require(graph.getField("invalidations").getInt(instance) == 2, "new column addition ignored");
        require(size.invoke(newAddition).equals(1), "new addition input set consumed");

        Object removed = set.getConstructor().newInstance();
        add.invoke(removed, 42L);
        run.invoke(instance, empty, removed);
        require(graph.getField("invalidations").getInt(instance) == 3, "existing removal ignored");
        require(!(boolean) contains.invoke(graph.getField("loadedChunks").get(instance), 42L), "removal lost");
        require(size.invoke(removed).equals(1), "shared removal set consumed");

        Object reload = set.getConstructor().newInstance();
        add.invoke(reload, 42L);
        run.invoke(instance, reload, empty);
        require(graph.getField("invalidations").getInt(instance) == 4, "reloaded column ignored");
        require(size.invoke(graph.getField("loadedChunks").get(instance)).equals(2), "loaded columns differ");

        // Storage.replace reports the same position in both sets: addAll is
        // false for an existing key, while removeAll is true. This was the
        // rejected regression where checking addAll alone missed invalidate().
        Object replacementAdded = set.getConstructor().newInstance();
        Object replacementRemoved = set.getConstructor().newInstance();
        add.invoke(replacementAdded, 42L);
        add.invoke(replacementRemoved, 42L);
        run.invoke(instance, replacementAdded, replacementRemoved);
        require(graph.getField("invalidations").getInt(instance) == 5, "replacement did not rebuild");
        require(!(boolean) contains.invoke(graph.getField("loadedChunks").get(instance), 42L), "replacement removal lost");
        require(size.invoke(replacementAdded).equals(1), "replacement addition input set consumed");
        require(size.invoke(replacementRemoved).equals(1), "replacement removal input set consumed");

        Object restore = set.getConstructor().newInstance();
        add.invoke(restore, 42L);
        run.invoke(instance, restore, empty);
        require(graph.getField("invalidations").getInt(instance) == 6, "replacement restore ignored");

        Object absent = set.getConstructor().newInstance();
        add.invoke(absent, 99L);
        run.invoke(instance, empty, absent);
        require(graph.getField("invalidations").getInt(instance) == 6, "removal of absent key rebuilt");

        Object mixedAdded = set.getConstructor().newInstance();
        Object mixedRemoved = set.getConstructor().newInstance();
        add.invoke(mixedAdded, 42L); // existing: addAll=false
        add.invoke(mixedAdded, 44L); // new: addAll=true
        add.invoke(mixedRemoved, 42L); // existing: removeAll=true
        add.invoke(mixedRemoved, 99L); // absent: does not change the result
        run.invoke(instance, mixedAdded, mixedRemoved);
        require(graph.getField("invalidations").getInt(instance) == 7, "mixed batch did not rebuild once");
        Object loaded = graph.getField("loadedChunks").get(instance);
        require(!(boolean) contains.invoke(loaded, 42L), "mixed batch retained removed key");
        require((boolean) contains.invoke(loaded, 43L), "mixed batch lost existing key");
        require((boolean) contains.invoke(loaded, 44L), "mixed batch lost new key");
        require(size.invoke(mixedAdded).equals(2), "mixed addition input set consumed");
        require(size.invoke(mixedRemoved).equals(2), "mixed removal input set consumed");
        System.out.println("LOADED_CHUNKS_TEST PASS jar=" + jar);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
