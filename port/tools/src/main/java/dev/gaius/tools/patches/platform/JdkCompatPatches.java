package dev.gaius.tools.patches.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * JDK APIs that Minecraft 26.3 uses and TeaVM 0.15's class library lacks (the TeaVM class
 * library gaps of the 26.3 link, milestone M2), owned by the lead package P0. Runs as the last
 * domain of its step, before the chain-tail guard, and covers the whole client jar (the client
 * and the singleplayer Worker are both compiled from it), not only the call sites TeaVM reported:
 *
 * <ul>
 *   <li>{@link #redirectJdkApis}: every {@code invokestatic Math.powExact(II)I},
 *       {@code invokevirtual ByteBuffer.slice(II)}, {@code Reader.readAllAsString()} and
 *       {@code Duration.isPositive()}, and every method handle to them, becomes an
 *       {@code invokestatic} of {@code dev.gaius.browser.BrowserJdkCompat} with the receiver
 *       as the first parameter. The operand stack is unchanged, so frames and maxima stay
 *       valid.</li>
 *   <li>{@link #remapScopedValue}: {@code java/lang/ScopedValue} and its nested types become
 *       {@code dev/gaius/browser/BrowserScopedValue} (same API) in every class that uses them
 *       (26.3: SolidDebugger): owners, descriptors, signatures, frames, local variables,
 *       inner-class entries and constants. A class-level rename rather than per-call helpers,
 *       because the type also appears in the field descriptor and signature of
 *       {@code SolidDebugger.STATUS}.</li>
 * </ul>
 *
 * <p>Both runtime classes live in the renderpearl-1 source set (port/mc/shared), so neither these
 * patches nor their targets exist for 26.2. After each patch, no class of the jar (read
 * root-first) may still reference a gap: a remaining reference fails the build. The G3 link-gap
 * smoke checks the same class of gap for any future JDK API through its TeaVM class library pass.
 *
 * <p>The platform domain of the Minecraft263BrowserPatcher step
 * ({@code patches/platform}): the step registers these patches under their historical ids
 * ({@code JdkCompatPatches263.<method>}) after its package domains and the worldgen float
 * rewrite; their profiles come from the specs. {@code jar} is the chain's current client jar,
 * {@code root} the directory whose classes are merged back into it. A class that an earlier
 * patch wrote under {@code root} is read from there.
 */
public final class JdkCompatPatches {
    static final String COMPAT = "dev/gaius/browser/BrowserJdkCompat";
    static final String SCOPED_VALUE = "java/lang/ScopedValue";
    static final String BROWSER_SCOPED_VALUE = "dev/gaius/browser/BrowserScopedValue";
    /** {@code java/lang/ScopedValue} as a whole name, alone or followed by a descriptor/signature delimiter. */
    private static final Pattern SCOPED_VALUE_NAME =
            Pattern.compile(Pattern.quote(SCOPED_VALUE) + "(?=[;<$.]|$)");

    /** A JDK method TeaVM lacks and its static helper in BrowserJdkCompat. */
    record Redirect(boolean isStatic, String owner, String name, String descriptor) {
        String helperDescriptor() {
            return isStatic ? descriptor : "(L" + owner + ";" + descriptor.substring(1);
        }

        boolean matches(String targetOwner, String targetName, String targetDescriptor) {
            return owner.equals(targetOwner) && name.equals(targetName)
                    && descriptor.equals(targetDescriptor);
        }

        String label() {
            return owner.substring(owner.lastIndexOf('/') + 1) + "." + name + descriptor;
        }
    }

    static final List<Redirect> REDIRECTS = List.of(
            new Redirect(true, "java/lang/Math", "powExact", "(II)I"),
            new Redirect(false, "java/nio/ByteBuffer", "slice", "(II)Ljava/nio/ByteBuffer;"),
            new Redirect(false, "java/io/Reader", "readAllAsString", "()Ljava/lang/String;"),
            new Redirect(false, "java/time/Duration", "isPositive", "()Z"));

    private JdkCompatPatches() {
    }

    // ------------------------------------------------------------ redirects

    public static void redirectJdkApis(String jar, Path root) throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        TreeSet<String> patched = new TreeSet<>();
        forEachClass(jar, root, (owner, bytes) -> {
            if (!mentionsRedirectName(bytes)) {
                return;
            }
            ClassNode node = parse(bytes);
            int redirected = redirect(node, counts);
            List<String> left = remainingRedirectTargets(node);
            if (!left.isEmpty()) {
                throw new IllegalStateException(owner + " still references " + left);
            }
            if (redirected > 0) {
                write(node, root);
                patched.add(owner);
            }
        });
        StringBuilder summary = new StringBuilder();
        for (Redirect redirect : REDIRECTS) {
            summary.append(' ').append(redirect.label()).append('=')
                    .append(counts.getOrDefault(redirect.label(), 0));
        }
        System.out.println("Redirected TeaVM class library gaps to " + COMPAT + ":" + summary
                + " in " + patched.size() + " classes " + patched);
    }

    private static boolean mentionsRedirectName(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        for (Redirect redirect : REDIRECTS) {
            if (text.contains(redirect.name())) {
                return true;
            }
        }
        return false;
    }

    private static Redirect redirectFor(String owner, String name, String descriptor) {
        for (Redirect redirect : REDIRECTS) {
            if (redirect.matches(owner, name, descriptor)) {
                return redirect;
            }
        }
        return null;
    }

    private static int redirect(ClassNode node, Map<String, Integer> counts) {
        int redirected = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    Redirect redirect = redirectFor(call.owner, call.name, call.desc);
                    if (redirect == null) {
                        continue;
                    }
                    int expected = redirect.isStatic() ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
                    if (call.getOpcode() != expected) {
                        throw new IllegalStateException(node.name + "." + method.name
                                + " calls " + redirect.label() + " with opcode " + call.getOpcode());
                    }
                    // invokevirtual (receiver, args) and invokestatic (receiver as first
                    // parameter, args) consume and produce the same operand stack.
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, COMPAT,
                            redirect.name(), redirect.helperDescriptor(), false));
                    counts.merge(redirect.label(), 1, Integer::sum);
                    redirected++;
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (int index = 0; index < dynamic.bsmArgs.length; index++) {
                        Object replaced = redirectConstant(dynamic.bsmArgs[index], counts);
                        if (replaced != dynamic.bsmArgs[index]) {
                            dynamic.bsmArgs[index] = replaced;
                            redirected++;
                        }
                    }
                } else if (instruction instanceof LdcInsnNode ldc) {
                    Object replaced = redirectConstant(ldc.cst, counts);
                    if (replaced != ldc.cst) {
                        ldc.cst = replaced;
                        redirected++;
                    }
                }
            }
        }
        return redirected;
    }

    /**
     * A method handle to a gap becomes a static handle to the helper: its method type (receiver
     * first) is the same, so LambdaMetafactory and MethodHandle callers see no difference.
     */
    private static Object redirectConstant(Object constant, Map<String, Integer> counts) {
        if (!(constant instanceof Handle handle)) {
            return constant;
        }
        Redirect redirect = redirectFor(handle.getOwner(), handle.getName(), handle.getDesc());
        if (redirect == null) {
            return constant;
        }
        int expected = redirect.isStatic() ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL;
        if (handle.getTag() != expected) {
            throw new IllegalStateException("method handle kind " + handle.getTag() + " for "
                    + redirect.label());
        }
        counts.merge(redirect.label() + "#handle", 1, Integer::sum);
        return new Handle(Opcodes.H_INVOKESTATIC, COMPAT, redirect.name(),
                redirect.helperDescriptor(), false);
    }

    /** Every reference to a redirect target that is still in {@code node}. */
    private static List<String> remainingRedirectTargets(ClassNode node) {
        List<String> left = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && redirectFor(call.owner, call.name, call.desc) != null) {
                    left.add(method.name + ": " + call.owner + "." + call.name + call.desc);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    List<Object> constants = new ArrayList<>(List.of(dynamic.bsmArgs));
                    constants.add(dynamic.bsm);
                    for (Object constant : constants) {
                        addRemainingHandle(left, method, constant);
                    }
                } else if (instruction instanceof LdcInsnNode ldc) {
                    addRemainingHandle(left, method, ldc.cst);
                }
            }
        }
        return left;
    }

    private static void addRemainingHandle(List<String> left, MethodNode method, Object constant) {
        if (constant instanceof Handle handle
                && redirectFor(handle.getOwner(), handle.getName(), handle.getDesc()) != null) {
            left.add(method.name + ": handle " + handle);
        } else if (constant instanceof ConstantDynamic dynamic) {
            addRemainingHandle(left, method, dynamic.getBootstrapMethod());
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                addRemainingHandle(left, method, dynamic.getBootstrapMethodArgument(index));
            }
        }
    }

    // --------------------------------------------------------- ScopedValue

    public static void remapScopedValue(String jar, Path root) throws IOException {
        TreeSet<String> patched = new TreeSet<>();
        forEachClass(jar, root, (owner, bytes) -> {
            if (!new String(bytes, StandardCharsets.ISO_8859_1).contains(SCOPED_VALUE)) {
                return;
            }
            if (owner.equals(SCOPED_VALUE) || owner.startsWith(SCOPED_VALUE + "$")) {
                throw new IllegalStateException("the client jar defines " + owner);
            }
            ClassNode node = parse(bytes);
            remapClass(node);
            byte[] written = write(node, root);
            if (new String(written, StandardCharsets.ISO_8859_1).contains(SCOPED_VALUE)) {
                throw new IllegalStateException(owner + " still mentions " + SCOPED_VALUE
                        + " after the rename (unhandled attribute or string constant)");
            }
            patched.add(owner);
        });
        System.out.println("Renamed " + SCOPED_VALUE + " to " + BROWSER_SCOPED_VALUE + " in "
                + patched.size() + " classes " + patched);
    }

    static String mapName(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = SCOPED_VALUE_NAME.matcher(text);
        return matcher.find() ? matcher.replaceAll(BROWSER_SCOPED_VALUE) : text;
    }

    private static Object mapConstant(Object constant) {
        if (constant instanceof Type type) {
            return type.getSort() == Type.METHOD ? Type.getMethodType(mapName(type.getDescriptor()))
                    : Type.getType(mapName(type.getDescriptor()));
        }
        if (constant instanceof Handle handle) {
            return new Handle(handle.getTag(), mapName(handle.getOwner()), handle.getName(),
                    mapName(handle.getDesc()), handle.isInterface());
        }
        if (constant instanceof ConstantDynamic dynamic) {
            Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int index = 0; index < arguments.length; index++) {
                arguments[index] = mapConstant(dynamic.getBootstrapMethodArgument(index));
            }
            return new ConstantDynamic(dynamic.getName(), mapName(dynamic.getDescriptor()),
                    (Handle) mapConstant(dynamic.getBootstrapMethod()), arguments);
        }
        return constant;
    }

    private static void mapNames(List<String> names) {
        if (names != null) {
            names.replaceAll(JdkCompatPatches::mapName);
        }
    }

    private static void mapFrameTypes(List<Object> types) {
        if (types == null) {
            return;
        }
        for (ListIterator<Object> iterator = types.listIterator(); iterator.hasNext(); ) {
            if (iterator.next() instanceof String name) {
                iterator.set(mapName(name));
            }
        }
    }

    /** Renames the ScopedValue types everywhere a class file can name a type. */
    static void remapClass(ClassNode node) {
        node.superName = mapName(node.superName);
        mapNames(node.interfaces);
        node.signature = mapName(node.signature);
        node.outerClass = mapName(node.outerClass);
        node.outerMethodDesc = mapName(node.outerMethodDesc);
        node.nestHostClass = mapName(node.nestHostClass);
        mapNames(node.nestMembers);
        mapNames(node.permittedSubclasses);
        for (InnerClassNode inner : node.innerClasses) {
            inner.name = mapName(inner.name);
            inner.outerName = mapName(inner.outerName);
        }
        if (node.recordComponents != null) {
            for (RecordComponentNode component : node.recordComponents) {
                component.descriptor = mapName(component.descriptor);
                component.signature = mapName(component.signature);
            }
        }
        for (FieldNode field : node.fields) {
            field.desc = mapName(field.desc);
            field.signature = mapName(field.signature);
        }
        for (MethodNode method : node.methods) {
            method.desc = mapName(method.desc);
            method.signature = mapName(method.signature);
            mapNames(method.exceptions);
            if (method.localVariables != null) {
                for (LocalVariableNode local : method.localVariables) {
                    local.desc = mapName(local.desc);
                    local.signature = mapName(local.signature);
                }
            }
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                block.type = mapName(block.type);
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type) {
                    type.desc = mapName(type.desc);
                } else if (instruction instanceof FieldInsnNode field) {
                    field.owner = mapName(field.owner);
                    field.desc = mapName(field.desc);
                } else if (instruction instanceof MethodInsnNode call) {
                    call.owner = mapName(call.owner);
                    call.desc = mapName(call.desc);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    dynamic.desc = mapName(dynamic.desc);
                    dynamic.bsm = (Handle) mapConstant(dynamic.bsm);
                    for (int index = 0; index < dynamic.bsmArgs.length; index++) {
                        dynamic.bsmArgs[index] = mapConstant(dynamic.bsmArgs[index]);
                    }
                } else if (instruction instanceof LdcInsnNode ldc) {
                    ldc.cst = mapConstant(ldc.cst);
                } else if (instruction instanceof MultiANewArrayInsnNode array) {
                    array.desc = mapName(array.desc);
                } else if (instruction instanceof FrameNode frame) {
                    mapFrameTypes(frame.local);
                    mapFrameTypes(frame.stack);
                }
            }
        }
    }

    // ------------------------------------------------------------------ io

    @FunctionalInterface
    private interface ClassAction {
        void accept(String owner, byte[] bytes) throws IOException;
    }

    /**
     * Visits every class of the jar and every class an earlier step wrote under {@code root},
     * each as the chain has patched it so far: {@code root} first, then the jar.
     */
    private static void forEachClass(String jar, Path root, ClassAction action)
            throws IOException {
        TreeSet<String> written = new TreeSet<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(file -> file.toString().endsWith(".class"))
                        .map(file -> root.relativize(file).toString().replace('\\', '/'))
                        .filter(name -> !name.startsWith("META-INF/")
                                && !name.endsWith("module-info.class"))
                        .forEach(name -> written.add(name.substring(0, name.length() - 6)));
            }
        }
        try (ZipFile zip = new ZipFile(jar)) {
            TreeSet<String> names = new TreeSet<>(written);
            zip.stream().map(ZipEntry::getName)
                    .filter(name -> name.endsWith(".class") && !name.startsWith("META-INF/")
                            && !name.endsWith("module-info.class"))
                    .forEach(name -> names.add(name.substring(0, name.length() - 6)));
            for (String owner : names) {
                byte[] bytes;
                if (written.contains(owner)) {
                    bytes = Files.readAllBytes(root.resolve(owner + ".class"));
                } else {
                    try (var stream = zip.getInputStream(zip.getEntry(owner + ".class"))) {
                        bytes = stream.readAllBytes();
                    }
                }
                action.accept(owner, bytes);
            }
        }
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Writes without recomputing frames or maxima: both patches keep the operand stack. */
    private static byte[] write(ClassNode node, Path root) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
        return bytes;
    }
}
