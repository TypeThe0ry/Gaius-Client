package dev.gaius.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class PatchTargets implements AutoCloseable {
    private final ZipFile vanilla;
    private final ZipFile input;
    private final Map<String, ClassNode> originals = new TreeMap<>();

    public PatchTargets(Path vanillaJar, Path inputJar) throws IOException {
        vanilla = new ZipFile(vanillaJar.toFile());
        try {
            input = new ZipFile(inputJar.toFile());
        } catch (IOException failure) {
            vanilla.close();
            throw failure;
        }
    }

    public List<Map<String, Object>> changed(Path root, Map<String, byte[]> before,
            boolean wholeClass)
            throws IOException {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : before.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".class")) {
                continue;
            }
            byte[] previous = entry.getValue();
            if (previous.length == 0) {
                previous = bytes(input, path);
            }
            Path file = root.resolve(path);
            targets.addAll(changed(previous == null ? null : read(previous),
                    Files.exists(file) ? read(Files.readAllBytes(file)) : null, wholeClass));
        }
        return targets;
    }

    public List<Map<String, Object>> changed(ClassNode before, ClassNode after)
            throws IOException {
        return changed(before, after, false);
    }

    private List<Map<String, Object>> changed(ClassNode before, ClassNode after,
            boolean wholeClass) throws IOException {
        String owner = after == null ? before.name : after.name;
        ClassNode original = original(owner);
        Map<String, MethodNode> oldMethods = methods(before);
        Map<String, MethodNode> newMethods = methods(after);
        Map<String, MethodNode> vanillaMethods = methods(original);
        TreeSet<String> names = new TreeSet<>(oldMethods.keySet());
        names.addAll(newMethods.keySet());
        List<Map<String, Object>> targets = new ArrayList<>();
        int changedMethods = 0;
        for (String key : names) {
            MethodNode old = oldMethods.get(key);
            MethodNode next = newMethods.get(key);
            if (!wholeClass && old != null && next != null && methodHash(old).equals(methodHash(next))) {
                continue;
            }
            changedMethods++;
            MethodNode method = next == null ? old : next;
            MethodNode source = vanillaMethods.get(key);
            Map<String, Object> target = target(owner, method.name, method.desc,
                    next == null ? "removed" : old == null ? "injected" : "rewrite",
                    source == null ? hash("absent-method", owner, key) : methodHash(source),
                    source != null);
            if (method.name.equals("<clinit>")) {
                target.put("vanillaStaticWrites", staticWrites(source));
                target.put("staticWrites", staticWrites(next));
            }
            targets.add(target);
        }
        boolean override = wholeClass || (before != null && after != null && !oldMethods.isEmpty()
                && changedMethods == names.size()) || changedMethods == 0;
        if (override || !shape(before).equals(shape(after))) {
            targets.add(0, target(owner, "<class>", "", override ? "override" : "structure",
                    original == null ? hash("absent-class", owner)
                            : override ? classHash(original) : hash(shape(original)),
                    original != null));
        }
        return targets;
    }

    private ClassNode original(String owner) throws IOException {
        if (!originals.containsKey(owner)) {
            byte[] data = bytes(vanilla, owner + ".class");
            originals.put(owner, data == null ? null : read(data));
        }
        return originals.get(owner);
    }

    private static byte[] bytes(ZipFile zip, String path) throws IOException {
        var entry = zip.getEntry(path);
        if (entry == null) {
            return null;
        }
        try (var stream = zip.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    public static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    public static void restoreNode(ClassNode before, ClassNode after) {
        try {
            for (var field : ClassNode.class.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.set(after, field.get(before));
                }
            }
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot restore an in-memory patch target", failure);
        }
    }

    private static Map<String, Object> target(String owner, String name, String descriptor,
            String operation, String targetHash, boolean present) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("className", owner);
        target.put("method", name);
        target.put("descriptor", descriptor);
        target.put("operation", operation);
        target.put("targetHash", targetHash);
        target.put("vanillaPresent", present);
        return target;
    }

    public static String methodHash(MethodNode method) {
        List<Object> body = new ArrayList<>(List.of(method.access, method.desc,
                new TreeSet<>(method.exceptions)));
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        int position = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode branch) {
                labels.put(branch, position);
            } else if (instruction.getOpcode() >= 0) {
                position++;
            }
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() < 0) {
                continue;
            }
            List<Object> item = new ArrayList<>();
            item.add(instruction.getOpcode());
            if (instruction instanceof IntInsnNode operand) {
                item.add(operand.operand);
            } else if (instruction instanceof VarInsnNode variable) {
                item.add(variable.var);
            } else if (instruction instanceof TypeInsnNode type) {
                item.add(type.desc);
            } else if (instruction instanceof FieldInsnNode field) {
                item.addAll(List.of(field.owner, field.name, field.desc));
            } else if (instruction instanceof MethodInsnNode call) {
                item.addAll(List.of(call.owner, call.name, call.desc, call.itf));
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                item.addAll(List.of(dynamic.name, dynamic.desc, constant(dynamic.bsm)));
                for (Object argument : dynamic.bsmArgs) {
                    item.add(constant(argument));
                }
            } else if (instruction instanceof JumpInsnNode jump) {
                item.add(label(labels, jump.label));
            } else if (instruction instanceof LdcInsnNode ldc) {
                item.add(constant(ldc.cst));
            } else if (instruction instanceof IincInsnNode increment) {
                item.addAll(List.of(increment.var, increment.incr));
            } else if (instruction instanceof TableSwitchInsnNode table) {
                item.addAll(List.of(table.min, table.max, label(labels, table.dflt)));
                for (LabelNode branch : table.labels) {
                    item.add(label(labels, branch));
                }
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                item.addAll(List.of(lookup.keys, label(labels, lookup.dflt)));
                for (LabelNode branch : lookup.labels) {
                    item.add(label(labels, branch));
                }
            } else if (instruction instanceof MultiANewArrayInsnNode array) {
                item.addAll(List.of(array.desc, array.dims));
            } else if (instruction.getType() != AbstractInsnNode.INSN) {
                throw new IllegalArgumentException("Unsupported opcode node " + instruction.getType());
            }
            body.add(item);
        }
        for (var handler : method.tryCatchBlocks) {
            body.add(List.of("handler", label(labels, handler.start), label(labels, handler.end),
                    label(labels, handler.handler), handler.type == null ? "*" : handler.type));
        }
        return hash(body);
    }

    private static int label(Map<LabelNode, Integer> labels, LabelNode branch) {
        Integer position = labels.get(branch);
        if (position == null) {
            throw new IllegalArgumentException("A bytecode target references an undefined label");
        }
        return position;
    }

    private static Object constant(Object value) {
        if (value instanceof Handle handle) {
            return List.of("handle", handle.getTag(), handle.getOwner(), handle.getName(),
                    handle.getDesc(), handle.isInterface());
        }
        if (value instanceof ConstantDynamic dynamic) {
            List<Object> result = new ArrayList<>(List.of("dynamic", dynamic.getName(),
                    dynamic.getDescriptor(), constant(dynamic.getBootstrapMethod())));
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                result.add(constant(dynamic.getBootstrapMethodArgument(index)));
            }
            return result;
        }
        if (value instanceof Float number) {
            return List.of("float", Float.floatToRawIntBits(number));
        }
        if (value instanceof Double number) {
            return List.of("double", Double.doubleToRawLongBits(number));
        }
        if (value instanceof Type type) {
            return List.of("type", type.getDescriptor());
        }
        if (value instanceof String || value instanceof Integer || value instanceof Long) {
            return List.of(value.getClass().getSimpleName(), value);
        }
        throw new IllegalArgumentException("Unsupported bytecode constant " + value);
    }

    private static List<Object> shape(ClassNode node) {
        if (node == null) {
            return List.of("absent");
        }
        List<Object> shape = new ArrayList<>(List.of(node.access, String.valueOf(node.superName),
                new TreeSet<>(node.interfaces)));
        Map<String, Object> fields = new TreeMap<>();
        for (var field : node.fields) {
            fields.put(field.name + field.desc, List.of(field.access,
                    field.value == null ? List.of() : constant(field.value)));
        }
        shape.add(fields);
        Map<String, Object> signatures = new TreeMap<>();
        for (var method : node.methods) {
            signatures.put(method.name + method.desc, List.of(method.access,
                    new TreeSet<>(method.exceptions)));
        }
        shape.add(signatures);
        return shape;
    }

    private static String classHash(ClassNode node) {
        Map<String, String> bodies = new TreeMap<>();
        methods(node).forEach((key, method) -> bodies.put(key, methodHash(method)));
        return hash(shape(node), bodies);
    }

    public static List<String> staticWrites(MethodNode method) {
        TreeSet<String> fields = new TreeSet<>();
        if (method != null) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTSTATIC) {
                    fields.add(field.owner + "." + field.name + ":" + field.desc);
                }
            }
        }
        return List.copyOf(fields);
    }

    private static String hash(Object... values) {
        StringBuilder text = new StringBuilder();
        for (Object value : values) {
            encode(text, value);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void encode(StringBuilder text, Object value) {
        if (value instanceof Iterable<?> items) {
            text.append('[');
            for (Object item : items) {
                encode(text, item);
            }
            text.append(']');
        } else if (value instanceof Map<?, ?> items) {
            text.append('{');
            items.forEach((key, item) -> {
                encode(text, key);
                encode(text, item);
            });
            text.append('}');
        } else {
            String scalar = String.valueOf(value);
            text.append(scalar.length()).append(':').append(scalar);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            input.close();
        } finally {
            vanilla.close();
        }
    }

    private static Map<String, MethodNode> methods(ClassNode node) {
        Map<String, MethodNode> result = new TreeMap<>();
        if (node != null) {
            for (MethodNode method : node.methods) {
                result.put(method.name + method.desc, method);
            }
        }
        return result;
    }
}
