package dev.gaius.tools;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Prints bytecode references into a package prefix, ordered by frequency. */
public final class BytecodeReferenceScanner {
    private BytecodeReferenceScanner() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "usage: BytecodeReferenceScanner JAR OWNER_PREFIX [OWNER_PREFIX...]");
        }
        Map<String, Integer> counts = new HashMap<>();
        try (ZipFile jar = new ZipFile(args[0])) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                ClassNode node = new ClassNode();
                try (var stream = jar.getInputStream(entry)) {
                    new ClassReader(stream.readAllBytes()).accept(
                            node,
                            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                for (var method : node.methods) {
                    for (var instruction = method.instructions.getFirst();
                            instruction != null;
                            instruction = instruction.getNext()) {
                        if (instruction instanceof MethodInsnNode call && matches(call.owner, args)) {
                            counts.merge(
                                    "M " + call.owner + "." + call.name + call.desc
                                            + " <- " + node.name + "." + method.name + method.desc,
                                    1,
                                    Integer::sum);
                        } else if (instruction instanceof FieldInsnNode field && matches(field.owner, args)) {
                            counts.merge(
                                    "F " + field.owner + "." + field.name + ":" + field.desc
                                            + " <- " + node.name + "." + method.name + method.desc,
                                    1,
                                    Integer::sum);
                        }
                    }
                }
            }
        }

        TreeMap<String, Integer> ordered = new TreeMap<>((left, right) -> {
            int frequency = Integer.compare(counts.get(right), counts.get(left));
            return frequency != 0 ? frequency : left.compareTo(right);
        });
        ordered.putAll(counts);
        ordered.forEach((symbol, count) -> System.out.printf("%5d %s%n", count, symbol));
    }

    private static boolean matches(String owner, String[] prefixes) {
        for (int index = 1; index < prefixes.length; index++) {
            if (owner.startsWith(prefixes[index])) {
                return true;
            }
        }
        return false;
    }
}
