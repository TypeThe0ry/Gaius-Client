import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Two-level fingerprint of a render seam's SPI closure, offline
 * against a profile's vanilla jar ({@code port/work/<profile>/client-named.jar}).
 *
 * <p>Level 1, the signature fingerprint: the transitive closure of the types that appear in the
 * signatures of the SPI types the seam implements, the vanilla classes it extends or instantiates
 * and the types it watches, restricted to the seam's {@code closurePackages}; for each type its
 * kind, supertypes, enum constants, record components and non-private fields (with constant
 * values) and methods. A change here means the adapter has to change (or a new generation
 * starts).</p>
 *
 * <p>Level 2, the body fingerprint: the normalised bytecode of the closure methods the seam calls,
 * of every concrete method of the classes it extends (they run on the seam's objects) and of the
 * watched types. A change only here needs a review, not an adapter change.</p>
 *
 * <p>The seam's own classes are read from their compiled form ({@code --seam-classes}): the tool
 * derives which SPI interfaces they implement, which vanilla classes they extend and instantiate
 * and which vanilla methods they call, and fails when that differs from {@code seam.json}.</p>
 *
 * <pre>
 * java -cp asm.jar:asm-tree.jar SeamProbe.java --jar client-named.jar --seam port/mc/seams/renderpearl-1
 *     --seam-classes DIR --profile 26.3 (--write FILE | --check FILE | --print)
 * </pre>
 *
 * Exit status: 0 equal (or written), 1 signature changed, 2 only bodies changed, 3 seam.json out of
 * date or bad input. {@code port/tools/seamprobe/seamprobe.sh} compiles the seam and runs this.
 */
public final class SeamProbe {
    private static final int VISIBLE = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED;

    private final ZipFile jar;
    private final Map<String, ClassNode> classes = new HashMap<>();

    private SeamProbe(ZipFile jar) {
        this.jar = jar;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--print")) {
                options.put("print", "true");
            } else if (arg.startsWith("--") && i + 1 < args.length) {
                options.put(arg.substring(2), args[++i]);
            } else {
                usage("unexpected argument " + arg);
            }
        }
        for (String required : List.of("jar", "seam", "seam-classes", "profile")) {
            if (!options.containsKey(required)) {
                usage("missing --" + required);
            }
        }
        Path seamDir = Path.of(options.get("seam"));
        Map<String, Object> seam = asMap(Json.parse(Files.readString(seamDir.resolve("seam.json"))));
        Map<String, Object> fingerprint = asMap(seam.get("fingerprint"));
        List<String> packages = strings(fingerprint.get("closurePackages"));
        try (ZipFile jar = new ZipFile(options.get("jar"))) {
            SeamProbe probe = new SeamProbe(jar);
            Usage used = probe.seamUsage(Path.of(options.get("seam-classes")), packages);
            int status = probe.checkManifest(used, fingerprint);
            if (status != 0) {
                System.exit(status);
            }
            Result result = probe.fingerprint(String.valueOf(seam.get("generation")), options.get("profile"), used,
                    strings(fingerprint.get("watch")), packages);
            String text = result.render();
            if (options.containsKey("write")) {
                Path out = Path.of(options.get("write"));
                Files.createDirectories(out.toAbsolutePath().getParent());
                Files.writeString(out, text, StandardCharsets.UTF_8);
                System.out.println("wrote " + out + ": " + result.summary());
            } else if (options.containsKey("check")) {
                System.exit(compare(Path.of(options.get("check")), result));
            } else if (options.containsKey("print")) {
                System.out.print(text);
            } else {
                System.out.println(result.summary());
            }
        }
    }

    private static void usage(String message) {
        System.err.println("SeamProbe: " + message);
        System.err.println("usage: SeamProbe --jar JAR --seam DIR --seam-classes DIR --profile ID"
                + " [--write FILE | --check FILE | --print]");
        System.exit(3);
    }

    // ---- the vanilla jar -------------------------------------------------------------------------

    private ClassNode load(String name) {
        if (this.classes.containsKey(name)) {
            return this.classes.get(name);
        }
        ClassNode node = null;
        ZipEntry entry = this.jar.getEntry(name + ".class");
        if (entry != null) {
            try (InputStream in = this.jar.getInputStream(entry)) {
                node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + name, e);
            }
        }
        this.classes.put(name, node);
        return node;
    }

    private static boolean inPackages(String name, List<String> packages) {
        for (String prefix : packages) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ---- what the seam's bytecode uses -------------------------------------------------------------

    /** Vanilla types and methods the compiled seam references. */
    private static final class Usage {
        final Set<String> implementsTypes = new TreeSet<>();
        final Set<String> extendsTypes = new TreeSet<>();
        final Set<String> instantiates = new TreeSet<>();
        /** owner.name+desc of vanilla methods the seam invokes (as written in its bytecode). */
        final Set<String> calls = new TreeSet<>();
    }

    private Usage seamUsage(Path seamClasses, List<String> packages) throws IOException {
        Usage usage = new Usage();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(seamClasses)) {
            files = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        if (files.isEmpty()) {
            usage("no classes under " + seamClasses);
        }
        for (Path file : files) {
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_FRAMES);
            if (node.superName != null && this.load(node.superName) != null) {
                usage.extendsTypes.add(node.superName);
            }
            for (String itf : node.interfaces) {
                if (this.load(itf) != null && inPackages(itf, packages)) {
                    usage.implementsTypes.add(itf);
                }
            }
            for (MethodNode method : node.methods) {
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.NEW
                            && this.load(type.desc) != null) {
                        usage.instantiates.add(type.desc);
                    } else if (insn instanceof MethodInsnNode call && this.load(call.owner) != null) {
                        usage.calls.add(call.owner + "." + call.name + call.desc);
                    } else if (insn instanceof InvokeDynamicInsnNode lambda
                            && lambda.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                        // A lambda implements the functional interface its call site returns.
                        String itf = Type.getReturnType(lambda.desc).getInternalName();
                        if (this.load(itf) != null && inPackages(itf, packages)) {
                            usage.implementsTypes.add(itf);
                        }
                    }
                }
            }
        }
        return usage;
    }

    private int checkManifest(Usage used, Map<String, Object> fingerprint) {
        int status = 0;
        status |= sameSet("implements", used.implementsTypes, strings(fingerprint.get("implements")));
        status |= sameSet("extends", used.extendsTypes, strings(fingerprint.get("extends")));
        status |= sameSet("instantiates", used.instantiates, strings(fingerprint.get("instantiates")));
        return status;
    }

    private static int sameSet(String key, Set<String> derived, List<String> declared) {
        Set<String> declaredSet = new TreeSet<>(declared);
        if (derived.equals(declaredSet)) {
            return 0;
        }
        Set<String> missing = new TreeSet<>(derived);
        missing.removeAll(declaredSet);
        Set<String> extra = new TreeSet<>(declaredSet);
        extra.removeAll(derived);
        System.err.println("seam.json fingerprint." + key + " is out of date:");
        for (String name : missing) {
            System.err.println("  + " + name + "   (used by the seam, not declared)");
        }
        for (String name : extra) {
            System.err.println("  - " + name + "   (declared, not used by the seam)");
        }
        return 3;
    }

    // ---- the closure and its fingerprints ------------------------------------------------------------

    private record Result(String generation, String profile, Usage used, List<String> watch,
            Map<String, String> typeHashes, Map<String, String> bodyHashes, Set<String> external, int fields,
            int methods) {
        String signature() {
            return sha256(String.join("\n", lines(this.typeHashes)));
        }

        String body() {
            return sha256(String.join("\n", lines(this.bodyHashes)));
        }

        String summary() {
            return "signature " + this.signature().substring(0, 16) + " (" + this.typeHashes.size() + " types, "
                    + this.methods + " methods, " + this.fields + " fields), body " + this.body().substring(0, 16)
                    + " (" + this.bodyHashes.size() + " methods)";
        }

        String render() {
            StringBuilder out = new StringBuilder();
            out.append("# Render seam SPI fingerprint. Generated by port/tools/seamprobe; do not edit.\n");
            out.append("# Regenerate: bash port/tools/seamprobe/seamprobe.sh ").append(this.profile)
                    .append(" --write\n");
            out.append("# signature: the SPI type closure (a change means the adapter changes);\n");
            out.append("# body: the closure methods the seam runs (a change needs a review only).\n");
            out.append("seam ").append(this.generation).append('\n');
            out.append("profile ").append(this.profile).append('\n');
            out.append("signature ").append(this.signature()).append('\n');
            out.append("body ").append(this.body()).append('\n');
            out.append("types ").append(this.typeHashes.size()).append('\n');
            out.append("methods ").append(this.methods).append('\n');
            out.append("fields ").append(this.fields).append('\n');
            out.append("bodies ").append(this.bodyHashes.size()).append('\n');
            list(out, "implements", this.used.implementsTypes);
            list(out, "extends", this.used.extendsTypes);
            list(out, "instantiates", this.used.instantiates);
            list(out, "watch", this.watch);
            list(out, "external", this.external);
            out.append("[types]\n");
            for (String line : lines(this.typeHashes)) {
                out.append(line).append('\n');
            }
            out.append("[bodies]\n");
            for (String line : lines(this.bodyHashes)) {
                out.append(line).append('\n');
            }
            return out.toString();
        }

        private static void list(StringBuilder out, String key, Collection<String> values) {
            for (String value : new TreeSet<>(values)) {
                out.append(key).append(' ').append(value).append('\n');
            }
        }

        private static List<String> lines(Map<String, String> hashes) {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> entry : new TreeMap<>(hashes).entrySet()) {
                lines.add(entry.getValue() + " " + entry.getKey());
            }
            return lines;
        }
    }

    private Result fingerprint(String generation, String profile, Usage used, List<String> watch,
            List<String> packages) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> closure = new TreeSet<>();
        Set<String> external = new TreeSet<>();
        List<String> roots = new ArrayList<>();
        roots.addAll(used.implementsTypes);
        roots.addAll(used.extendsTypes);
        roots.addAll(used.instantiates);
        roots.addAll(watch);
        for (String root : roots) {
            if (this.load(root) == null) {
                throw new IllegalStateException(root + " is not in the jar");
            }
            if (closure.add(root)) {
                queue.add(root);
            }
        }
        while (!queue.isEmpty()) {
            ClassNode node = this.load(queue.poll());
            for (String referenced : signatureTypes(node)) {
                if (this.load(referenced) != null && inPackages(referenced, packages)) {
                    if (closure.add(referenced)) {
                        queue.add(referenced);
                    }
                } else if (!referenced.startsWith("java/")) {
                    external.add(referenced);
                }
            }
        }
        external.removeAll(closure);

        Map<String, String> typeHashes = new LinkedHashMap<>();
        int fields = 0;
        int methods = 0;
        for (String name : closure) {
            ClassNode node = this.load(name);
            List<String> lines = describe(node);
            typeHashes.put(name, sha256(String.join("\n", lines)).substring(0, 16));
            for (String line : lines) {
                if (line.startsWith("field ")) {
                    fields++;
                } else if (line.startsWith("method ")) {
                    methods++;
                }
            }
        }

        Map<String, String> bodyHashes = new LinkedHashMap<>();
        for (String call : used.calls) {
            int dot = call.indexOf('.');
            int paren = call.indexOf('(');
            String owner = call.substring(0, dot);
            String methodName = call.substring(dot + 1, paren);
            String desc = call.substring(paren);
            String[] found = new String[1];
            MethodNode method = this.resolve(owner, methodName, desc, found);
            if (method == null || !closure.contains(found[0])) {
                continue;
            }
            bodyHashes.put(found[0] + "." + methodName + desc, bodyHash(method));
        }
        List<String> bodyOwners = new ArrayList<>(used.extendsTypes);
        bodyOwners.addAll(watch);
        for (String owner : bodyOwners) {
            for (MethodNode method : this.load(owner).methods) {
                if ((method.access & Opcodes.ACC_ABSTRACT) == 0) {
                    bodyHashes.put(owner + "." + method.name + method.desc, bodyHash(method));
                }
            }
        }
        return new Result(generation, profile, used, watch, typeHashes, bodyHashes, external, fields, methods);
    }

    /** The method a call resolves to (declaring class in {@code found[0]}), searching supertypes. */
    private MethodNode resolve(String owner, String name, String desc, String[] found) {
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new TreeSet<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            ClassNode node = this.load(current);
            if (node == null) {
                continue;
            }
            for (MethodNode method : node.methods) {
                if (method.name.equals(name) && method.desc.equals(desc)) {
                    found[0] = current;
                    return method;
                }
            }
            if (node.superName != null) {
                queue.add(node.superName);
            }
            queue.addAll(node.interfaces);
        }
        return null;
    }

    /** Class names in a type's visible signature: supertypes, fields, methods, generics, throws. */
    private static Set<String> signatureTypes(ClassNode node) {
        Set<String> out = new TreeSet<>();
        if (node.superName != null) {
            out.add(node.superName);
        }
        out.addAll(node.interfaces);
        genericTypes(node.signature, out);
        for (FieldNode field : node.fields) {
            if ((field.access & VISIBLE) == 0) {
                continue;
            }
            descriptorTypes(Type.getType(field.desc), out);
            genericTypes(field.signature, out);
        }
        for (MethodNode method : node.methods) {
            if ((method.access & VISIBLE) == 0 || (method.access & Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }
            Type type = Type.getMethodType(method.desc);
            descriptorTypes(type.getReturnType(), out);
            for (Type argument : type.getArgumentTypes()) {
                descriptorTypes(argument, out);
            }
            genericTypes(method.signature, out);
            if (method.exceptions != null) {
                out.addAll(method.exceptions);
            }
        }
        if (node.recordComponents != null) {
            for (RecordComponentNode component : node.recordComponents) {
                descriptorTypes(Type.getType(component.descriptor), out);
                genericTypes(component.signature, out);
            }
        }
        out.remove(node.name);
        return out;
    }

    private static void descriptorTypes(Type type, Set<String> out) {
        while (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        if (type.getSort() == Type.OBJECT) {
            out.add(type.getInternalName());
        }
    }

    private static void genericTypes(String signature, Set<String> out) {
        if (signature == null) {
            return;
        }
        new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitClassType(String name) {
                out.add(name);
            }

            @Override
            public void visitInnerClassType(String name) {
                // Inner class names are relative to the outer class visited before.
            }
        });
    }

    /** The visible surface of a type, one line per element, in a stable order. */
    private static List<String> describe(ClassNode node) {
        List<String> lines = new ArrayList<>();
        int classAccess = node.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE
                | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ENUM | Opcodes.ACC_RECORD | Opcodes.ACC_ANNOTATION);
        lines.add("class " + node.name + " access=" + classAccess + " super=" + node.superName + " interfaces="
                + new TreeSet<>(node.interfaces) + " signature=" + node.signature);
        if ((node.access & Opcodes.ACC_ENUM) != 0) {
            List<String> constants = new ArrayList<>();
            for (FieldNode field : node.fields) {
                if ((field.access & Opcodes.ACC_ENUM) != 0) {
                    constants.add(field.name);
                }
            }
            lines.add("enum " + constants);
        }
        if (node.recordComponents != null) {
            List<String> components = new ArrayList<>();
            for (RecordComponentNode component : node.recordComponents) {
                components.add(component.name + ":" + component.descriptor);
            }
            lines.add("record " + components);
        }
        Set<String> sorted = new TreeSet<>();
        for (FieldNode field : node.fields) {
            if ((field.access & VISIBLE) == 0) {
                continue;
            }
            int access = field.access & (VISIBLE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
            sorted.add("field " + field.name + " " + field.desc + " access=" + access
                    + (field.value != null ? " value=" + field.value : "")
                    + (field.signature != null ? " signature=" + field.signature : ""));
        }
        for (MethodNode method : node.methods) {
            if ((method.access & VISIBLE) == 0 || (method.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
                continue;
            }
            int access = method.access & (VISIBLE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT);
            sorted.add("method " + method.name + method.desc + " access=" + access
                    + (method.signature != null ? " signature=" + method.signature : "")
                    + (method.exceptions != null && !method.exceptions.isEmpty()
                            ? " throws=" + new TreeSet<>(method.exceptions) : ""));
        }
        lines.addAll(sorted);
        return lines;
    }

    /** SHA-256 (first 16 hex digits) of a method's normalised instructions and handlers. */
    private static String bodyHash(MethodNode method) {
        StringBuilder out = new StringBuilder();
        out.append(method.access & ~Opcodes.ACC_SYNTHETIC).append(' ').append(method.name).append(method.desc).append('\n');
        if (method.instructions == null || method.instructions.size() == 0) {
            out.append("no code\n");
            return sha256(out.toString()).substring(0, 16);
        }
        Map<LabelNode, Integer> labels = new HashMap<>();
        int index = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode label) {
                labels.put(label, index);
            } else if (!(insn instanceof LineNumberNode) && !(insn instanceof FrameNode)) {
                index++;
            }
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) {
                continue;
            }
            out.append(insn.getOpcode());
            if (insn instanceof IntInsnNode i) {
                out.append(' ').append(i.operand);
            } else if (insn instanceof VarInsnNode v) {
                out.append(' ').append(v.var);
            } else if (insn instanceof TypeInsnNode t) {
                out.append(' ').append(t.desc);
            } else if (insn instanceof FieldInsnNode f) {
                out.append(' ').append(f.owner).append('.').append(f.name).append(' ').append(f.desc);
            } else if (insn instanceof MethodInsnNode m) {
                out.append(' ').append(m.owner).append('.').append(m.name).append(m.desc).append(m.itf ? " itf" : "");
            } else if (insn instanceof InvokeDynamicInsnNode d) {
                out.append(' ').append(d.name).append(d.desc).append(' ').append(handle(d.bsm));
                for (Object argument : d.bsmArgs) {
                    out.append(' ').append(argument instanceof Handle h ? handle(h) : String.valueOf(argument));
                }
            } else if (insn instanceof JumpInsnNode j) {
                out.append(" @").append(labels.get(j.label));
            } else if (insn instanceof LdcInsnNode l) {
                out.append(' ').append(l.cst.getClass().getSimpleName()).append(':').append(l.cst);
            } else if (insn instanceof IincInsnNode i) {
                out.append(' ').append(i.var).append(' ').append(i.incr);
            } else if (insn instanceof TableSwitchInsnNode t) {
                out.append(' ').append(t.min).append(' ').append(t.max).append(" @").append(labels.get(t.dflt));
                for (LabelNode label : t.labels) {
                    out.append(" @").append(labels.get(label));
                }
            } else if (insn instanceof LookupSwitchInsnNode s) {
                out.append(" @").append(labels.get(s.dflt));
                for (int k = 0; k < s.keys.size(); k++) {
                    out.append(' ').append(s.keys.get(k)).append(" @").append(labels.get(s.labels.get(k)));
                }
            } else if (insn instanceof MultiANewArrayInsnNode m) {
                out.append(' ').append(m.desc).append(' ').append(m.dims);
            }
            out.append('\n');
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            out.append("try @").append(labels.get(block.start)).append(" @").append(labels.get(block.end)).append(" @")
                    .append(labels.get(block.handler)).append(' ').append(block.type).append('\n');
        }
        return sha256(out.toString()).substring(0, 16);
    }

    private static String handle(Handle handle) {
        return handle.getTag() + ":" + handle.getOwner() + "." + handle.getName() + handle.getDesc();
    }

    // ---- check mode ----------------------------------------------------------------------------------

    private static int compare(Path file, Result result) throws IOException {
        if (!Files.isRegularFile(file)) {
            System.err.println("no fingerprint at " + file + "; write one with --write");
            return 3;
        }
        Map<String, String> oldTypes = new TreeMap<>();
        Map<String, String> oldBodies = new TreeMap<>();
        String oldSignature = "";
        String oldBody = "";
        String section = "";
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            if (line.startsWith("[")) {
                section = line;
            } else if (section.equals("[types]") || section.equals("[bodies]")) {
                int space = line.indexOf(' ');
                (section.equals("[types]") ? oldTypes : oldBodies).put(line.substring(space + 1),
                        line.substring(0, space));
            } else if (line.startsWith("signature ")) {
                oldSignature = line.substring(10);
            } else if (line.startsWith("body ")) {
                oldBody = line.substring(5);
            }
        }
        boolean signatureSame = oldSignature.equals(result.signature());
        boolean bodySame = oldBody.equals(result.body());
        if (signatureSame && bodySame) {
            System.out.println("fingerprint unchanged: " + result.summary());
            return 0;
        }
        if (!signatureSame) {
            System.out.println("SIGNATURE CHANGED: the seam adapter must be updated (or a new generation started).");
            diff("type", oldTypes, result.typeHashes());
        }
        if (!bodySame) {
            System.out.println("BODY CHANGED: review these methods against the adapter's assumptions.");
            diff("method", oldBodies, result.bodyHashes());
        }
        return signatureSame ? 2 : 1;
    }

    private static void diff(String what, Map<String, String> before, Map<String, String> after) {
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            String a = before.get(name);
            String b = after.get(name);
            if (a == null) {
                System.out.println("  + " + what + " " + name);
            } else if (b == null) {
                System.out.println("  - " + what + " " + name);
            } else if (!a.equals(b)) {
                System.out.println("  ~ " + what + " " + name);
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("seam.json: expected an object");
        }
        return (Map<String, Object>) value;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    /** A minimal JSON reader for seam.json (objects, arrays, strings, numbers, booleans, null). */
    static final class Json {
        private final String text;
        private int at;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json json = new Json(text);
            Object value = json.value();
            json.space();
            if (json.at != text.length()) {
                throw json.error("trailing characters");
            }
            return value;
        }

        private Object value() {
            this.space();
            char c = this.text.charAt(this.at);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>();
                this.at++;
                this.space();
                if (this.peek('}')) {
                    return map;
                }
                do {
                    this.space();
                    String key = this.string();
                    this.space();
                    this.expect(':');
                    map.put(key, this.value());
                    this.space();
                } while (this.peek(','));
                this.expect('}');
                return map;
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>();
                this.at++;
                this.space();
                if (this.peek(']')) {
                    return list;
                }
                do {
                    list.add(this.value());
                    this.space();
                } while (this.peek(','));
                this.expect(']');
                return list;
            }
            if (c == '"') {
                return this.string();
            }
            for (String literal : List.of("true", "false", "null")) {
                if (this.text.startsWith(literal, this.at)) {
                    this.at += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            int start = this.at;
            while (this.at < this.text.length() && "+-0123456789.eE".indexOf(this.text.charAt(this.at)) >= 0) {
                this.at++;
            }
            if (start == this.at) {
                throw this.error("unexpected character");
            }
            return Double.valueOf(this.text.substring(start, this.at));
        }

        private String string() {
            this.expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = this.text.charAt(this.at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char e = this.text.charAt(this.at++);
                    switch (e) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(this.text.substring(this.at, this.at + 4), 16));
                            this.at += 4;
                        }
                        default -> out.append(e);
                    }
                } else {
                    out.append(c);
                }
            }
        }

        private void space() {
            while (this.at < this.text.length() && Character.isWhitespace(this.text.charAt(this.at))) {
                this.at++;
            }
        }

        private boolean peek(char c) {
            if (this.at < this.text.length() && this.text.charAt(this.at) == c) {
                this.at++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!this.peek(c)) {
                throw this.error("expected '" + c + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("seam.json: " + message + " at offset " + this.at);
        }
    }
}
