package dev.gaius.tools;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.teavm.backend.javascript.codegen.NamingStrategy;
import org.teavm.backend.javascript.codegen.ScopedName;
import org.teavm.model.ClassHolder;
import org.teavm.model.ListableClassHolderSource;
import org.teavm.model.MutableClassHolderSource;

/** Executes the patched upstream bytecode using synthetic classes and names only. */
public final class TeaVMCoreBrowserPatcherDeterminismTest {
    private static final String RENDERER = "org/teavm/backend/javascript/rendering/Renderer";
    private static final String FREQUENCY =
            "org/teavm/backend/javascript/rendering/NameFrequencyEstimator";

    private TeaVMCoreBrowserPatcherDeterminismTest() {
    }

    public static void main(String[] args) throws Exception {
        checkRendererOrder();
        checkFrequencyOrder();
        checkTypeSetOrder();
        System.out.println("TeaVMCoreBrowserPatcherDeterminismTest passed (32 permutations)");
    }

    private static void checkRendererOrder() throws Exception {
        ClassNode renderer = read(RENDERER);
        TeaVMCoreBrowserPatcher.patchRendererClassOrder(renderer);
        MethodNode render = renderer.methods.stream().filter(method -> method.name.equals("render"))
                .findFirst().orElseThrow();
        // Keep the real upstream class traversal and recursive orderClasses bytecode.
        // Stop before asynchronous analysis and code generation, which need a full compiler.
        AbstractInsnNode end = null;
        for (AbstractInsnNode instruction : render.instructions) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("org/teavm/model/util/AsyncMethodFinder")) {
                end = instruction;
                break;
            }
        }
        check(end != null, "upstream renderer traversal boundary changed");
        while (end != null) {
            AbstractInsnNode next = end.getNext();
            render.instructions.remove(end);
            end = next;
        }
        render.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        render.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, RENDERER, "testOrder",
                "Ljava/util/List;"));
        render.instructions.add(new InsnNode(Opcodes.ICONST_1));
        render.instructions.add(new InsnNode(Opcodes.IRETURN));
        render.localVariables = null;
        render.tryCatchBlocks.clear();
        renderer.methods.removeIf(method -> !method.name.equals("render")
                && !method.name.equals("orderClasses")
                && !method.name.equals("gaiusSortedClassNames"));
        renderer.interfaces.clear();
        renderer.fields.clear();
        renderer.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "testOrder", "Ljava/util/List;", null, null));
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        renderer.methods.add(constructor);
        Class<?> fixture = new FixtureLoader(renderer).loadClass(RENDERER.replace('/', '.'));
        Object instance = fixture.getConstructor().newInstance();
        List<String> expected = List.of("fixture.ZParent", "fixture.BAlpha", "fixture.YZeta",
                "fixture.AChild", "fixture.Other");
        for (int seed = 0; seed < 32; seed++) {
            List<String> names = new ArrayList<>(expected);
            Collections.shuffle(names, new Random(seed));
            MutableClassHolderSource source = new MutableClassHolderSource();
            for (String name : names) {
                ClassHolder holder = new ClassHolder(name);
                holder.setParent(name.equals("fixture.AChild") ? "fixture.ZParent" : null);
                if (name.equals("fixture.AChild")) {
                    List<String> interfaces = new ArrayList<>(List.of("fixture.YZeta", "fixture.BAlpha"));
                    Collections.shuffle(interfaces, new Random(seed + 1));
                    holder.getInterfaces().addAll(interfaces);
                }
                source.putClassHolder(holder);
            }
            fixture.getMethod("render", ListableClassHolderSource.class, boolean.class)
                    .invoke(instance, source, false);
            @SuppressWarnings("unchecked")
            List<ClassHolder> actual = (List<ClassHolder>) fixture.getField("testOrder").get(null);
            List<String> order = actual.stream().map(ClassHolder::getName).toList();
            check(order.equals(expected), "class/interface order differs: " + order);
        }
    }

    private static void checkFrequencyOrder() throws Exception {
        ClassNode frequency = read(FREQUENCY);
        TeaVMCoreBrowserPatcher.patchNameFrequencyEstimator(frequency);
        Class<?> fixture = new FixtureLoader(frequency).loadClass(FREQUENCY.replace('/', '.'));
        List<String> names = List.of("AaAa", "AaBB", "BBAa", "BBBB");
        check(names.stream().map(String::hashCode).distinct().count() == 1,
                "synthetic names must collide in HashMap");
        Map<String, String> expected = null;
        for (int seed = 0; seed < 32; seed++) {
            Object estimator = fixture.getConstructor().newInstance();
            List<String> shuffled = new ArrayList<>(names);
            Collections.shuffle(shuffled, new Random(seed));
            for (String name : shuffled) {
                fixture.getMethod("appendClass", String.class).invoke(estimator, name);
            }
            Map<String, String> aliases = new LinkedHashMap<>();
            NamingStrategy naming = (NamingStrategy) Proxy.newProxyInstance(
                    NamingStrategy.class.getClassLoader(), new Class<?>[] {NamingStrategy.class},
                    (proxy, method, arguments) -> {
                        check(method.getName().equals("className"), "unexpected naming operation");
                        String name = (String) arguments[0];
                        String alias = "alias" + aliases.size();
                        aliases.put(name, alias);
                        return new ScopedName(alias, false);
                    });
            fixture.getMethod("apply", NamingStrategy.class).invoke(estimator, naming);
            check(new ArrayList<>(aliases.keySet()).equals(names), "equal-frequency tie is unstable");
            if (expected == null) {
                expected = aliases;
            } else {
                check(aliases.equals(expected), "equal-frequency aliases differ");
            }
        }
    }

    private static void checkTypeSetOrder() throws Exception {
        ClassNode typeSet = read("org/teavm/dependency/TypeSet");
        check(TeaVMCoreBrowserPatcher.patchTypeSetDomainOrder(typeSet), "TypeSet patch was skipped");
        int allocations = 0;
        for (MethodNode method : typeSet.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                    check(!type.desc.equals("java/util/HashSet"), "identity HashSet remains");
                    if (type.desc.equals("java/util/LinkedHashSet")) {
                        allocations++;
                    }
                }
            }
        }
        check(allocations == 2, "upstream TypeSet allocation shape changed");
        Class<?> fixture = new FixtureLoader(typeSet).loadClass("org.teavm.dependency.TypeSet");
        Class<?> analyzer = Class.forName("org.teavm.dependency.DependencyAnalyzer");
        Class<?> dependencyNode = Class.forName("org.teavm.dependency.DependencyNode");
        Class<?> valueType = Class.forName("org.teavm.model.ValueType");
        var nodeConstructor = dependencyNode.getDeclaredConstructor(analyzer, valueType);
        nodeConstructor.setAccessible(true);
        List<Object> nodes = List.of(nodeConstructor.newInstance(null, null),
                nodeConstructor.newInstance(null, null), nodeConstructor.newInstance(null, null));
        var constructor = fixture.getDeclaredConstructor(analyzer, dependencyNode);
        constructor.setAccessible(true);
        var addOne = fixture.getDeclaredMethod("addDomain", dependencyNode);
        var addMany = fixture.getDeclaredMethod("addDomain", Collection.class);
        var domain = fixture.getDeclaredMethod("domain");
        addOne.setAccessible(true);
        addMany.setAccessible(true);
        domain.setAccessible(true);
        Object individual = constructor.newInstance(null, nodes.get(0));
        addOne.invoke(individual, nodes.get(1));
        addOne.invoke(individual, nodes.get(0));
        addMany.invoke(individual, List.of(nodes.get(2), nodes.get(1)));
        check(new ArrayList<>((Set<?>) domain.invoke(individual)).equals(nodes),
                "individual domain insertion order or deduplication changed");
        Object collection = constructor.newInstance(null, nodes.get(0));
        addMany.invoke(collection, List.of(nodes.get(1), nodes.get(2), nodes.get(1)));
        check(new ArrayList<>((Set<?>) domain.invoke(collection)).equals(nodes),
                "collection domain insertion order or deduplication changed");
    }

    private static ClassNode read(String name) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(resource(name)).accept(node, 0);
        return node;
    }

    private static byte[] resource(String name) throws IOException {
        try (var input = TeaVMCoreBrowserPatcherDeterminismTest.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
            if (input == null) {
                throw new IOException("Missing upstream fixture bytecode: " + name);
            }
            return input.readAllBytes();
        }
    }

    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions = new HashMap<>();
        private final String family;

        FixtureLoader(ClassNode node) {
            super(TeaVMCoreBrowserPatcherDeterminismTest.class.getClassLoader());
            family = node.name.replace('/', '.');
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            definitions.put(family, writer.toByteArray());
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(family) && !name.startsWith(family + "$")) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                byte[] bytes = definitions.get(name);
                if (bytes == null) {
                    try {
                        bytes = resource(name.replace('.', '/'));
                    } catch (IOException exception) {
                        throw new ClassNotFoundException(name, exception);
                    }
                }
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
