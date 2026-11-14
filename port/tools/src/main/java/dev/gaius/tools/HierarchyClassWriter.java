package dev.gaius.tools;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Computes frames from class headers, without loading or initializing application classes. */
public final class HierarchyClassWriter extends ClassWriter implements AutoCloseable {
    private record Header(String parent, List<String> interfaces, boolean isInterface) { }
    private static final Map<ClassNode, Path> INPUTS = new WeakHashMap<>();
    private final List<Path> entries;
    private final Map<Path, ZipFile> archives = new HashMap<>();
    private final Map<String, Header> headers = new HashMap<>();

    public static ClassNode source(ClassNode node, String jar) {
        synchronized (INPUTS) { INPUTS.put(node, Path.of(jar).toAbsolutePath().normalize()); }
        return node;
    }

    /** The class being written wins, then this step's overlay, input jar, libraries and JDK. */
    public static HierarchyClassWriter create(ClassNode node, Path output) throws IOException {
        Path root = output.toAbsolutePath().normalize();
        for (String ignored : (node.name + ".class").split("/")) root = root.getParent();
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        paths.add(root);
        Path input;
        synchronized (INPUTS) { input = INPUTS.get(node); }
        String configured = System.getProperty("gaius.patch.input");
        if (input != null) paths.add(input);
        else if (configured != null) paths.add(Path.of(configured));
        String vanilla = System.getProperty("gaius.patch.vanilla");
        if (vanilla != null) paths.add(Path.of(vanilla));
        for (Path path : List.copyOf(paths)) {
            Path list = path.getParent() == null ? null : path.getParent().resolve("classpath.txt");
            if (list != null && Files.isRegularFile(list)) {
                for (String value : Files.readString(list).strip().split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    if (value.isBlank()) continue;
                    Path library = Path.of(value);
                    // Private version inputs can carry the original shared-work classpath.
                    int marker = value.replace('\\', '/').indexOf("/libraries/");
                    if (marker >= 0) library = list.getParent().resolve(value.substring(marker + 1));
                    if (!library.isAbsolute()) library = list.getParent().resolve(library);
                    if (Files.isRegularFile(library)) paths.add(library);
                }
            }
        }
        for (String value : System.getProperty("gaius.patch.classpath", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!value.isBlank()) paths.add(Path.of(value));
        }
        return new HierarchyClassWriter(new ArrayList<>(paths), node);
    }

    public HierarchyClassWriter(List<Path> entries, ClassNode current) {
        super(COMPUTE_FRAMES | COMPUTE_MAXS);
        this.entries = List.copyOf(entries);
        if (current != null) headers.put(current.name,
                new Header(current.superName, List.copyOf(current.interfaces), (current.access & Opcodes.ACC_INTERFACE) != 0));
    }

    private Header header(String name) {
        Header cached = headers.get(name);
        if (cached != null) return cached;
        try {
            ClassReader reader = null;
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    Path file = entry.resolve(name + ".class");
                    if (Files.isRegularFile(file)) { reader = new ClassReader(Files.readAllBytes(file)); break; }
                } else if (Files.isRegularFile(entry)) {
                    ZipFile zip = archives.get(entry);
                    if (zip == null) { zip = new ZipFile(entry.toFile()); archives.put(entry, zip); }
                    var member = zip.getEntry(name + ".class");
                    if (member != null) {
                        try (var stream = zip.getInputStream(member)) { reader = new ClassReader(stream); }
                        break;
                    }
                }
            }
            if (reader == null) {
                try (var stream = HierarchyClassWriter.class.getClassLoader().getResourceAsStream(name + ".class")) {
                    if (stream != null) reader = new ClassReader(stream);
                }
            }
            if (reader == null) throw new IllegalStateException("Unresolved frame hierarchy: " + name + " in " + entries);
            Header result = new Header(reader.getSuperName(), Arrays.asList(reader.getInterfaces()),
                    (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
            headers.put(name, result);
            return result;
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    private static String component(String array) {
        String value = array.substring(1);
        return value.startsWith("L") ? value.substring(1, value.length() - 1) : value;
    }

    private static boolean primitiveComponent(String array) { return array.charAt(1) != '[' && array.charAt(1) != 'L'; }
    private static String arrayOf(String value) { return "[" + (value.startsWith("[") ? value : "L" + value + ";"); }

    public boolean assignable(String parent, String child) {
        if (parent.equals(child) || parent.equals("java/lang/Object")) return true;
        if (child.startsWith("[")) {
            if (parent.equals("java/lang/Cloneable") || parent.equals("java/io/Serializable")) return true;
            if (!parent.startsWith("[")) return false;
            String p = component(parent), c = component(child);
            return primitiveComponent(parent) || primitiveComponent(child) ? parent.equals(child) : assignable(p, c);
        }
        if (parent.startsWith("[")) return false;
        var queue = new ArrayDeque<String>();
        var seen = new HashSet<String>();
        queue.add(child);
        while (!queue.isEmpty()) {
            String type = queue.removeFirst();
            if (!seen.add(type)) continue;
            if (type.equals(parent)) return true;
            Header info = header(type);
            if (info.parent != null) queue.add(info.parent);
            queue.addAll(info.interfaces);
        }
        return false;
    }

    public String commonSuperType(String first, String second) {
        if (assignable(first, second)) return first;
        if (assignable(second, first)) return second;
        if (first.startsWith("[") && second.startsWith("[")) {
            String a = component(first), b = component(second);
            return primitiveComponent(first) || primitiveComponent(second) ? "java/lang/Object" : arrayOf(commonSuperType(a, b));
        }
        if (first.startsWith("[") || second.startsWith("[")) return "java/lang/Object";
        if (header(first).isInterface || header(second).isInterface) return "java/lang/Object";
        String parent = header(first).parent;
        while (parent != null && !assignable(parent, second)) parent = header(parent).parent;
        if (parent == null) throw new IllegalStateException("No common superclass: " + first + ", " + second);
        return parent;
    }

    @Override protected String getCommonSuperClass(String first, String second) { return commonSuperType(first, second); }

    @Override public void close() throws IOException {
        IOException failure = null;
        for (ZipFile zip : archives.values()) {
            try { zip.close(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure != null) throw failure;
    }
}
