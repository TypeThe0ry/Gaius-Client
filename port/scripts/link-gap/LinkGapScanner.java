import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * G3 link-gap scanner, run by minecraft-link-gap-smoke.mjs through the JDK
 * source launcher with ASM on the class path.
 *
 * <p>Every class of the subject roots (the patched client jar and the compiled
 * Gaius runtime classes) is scanned.  Each class, field and method reference
 * is resolved against subjects, then the class path roots in order, then the
 * running JDK, following the JVM resolution rules (JVMS 5.4.3).  The opcode
 * and the invoke {@code itf} flag must match the owner's kind, and static
 * accesses must hit static members.
 *
 * <p>With {@code --teavm-classlib JAR} every reference that resolves on the
 * JVM is resolved a second time the way TeaVM 0.15 links it: {@code java/**}
 * and {@code javax/xml/**} only through the TeaVM class library
 * ({@code java/time/**} as {@code org/threeten/bp/**}, everything else as
 * {@code org/teavm/classlib/java/**}{@code /T<Name>}, the substitution rules
 * of {@code org.teavm.classlib.impl.ClasslibSubstitutionPolicy}, honouring the
 * {@code @Rename}, {@code @Remove} and {@code @Superclass} interop
 * annotations), every other package only through the subjects and the class
 * path, never the running JDK; invokedynamic bootstraps must be one of the
 * bootstrap substitutors that TeaVM's JCLPlugin registers.  A reference that
 * resolves on the JVM but not there is reported with a {@code teavm-} kind:
 * a JDK API that TeaVM's class library does not implement.  The scan covers
 * the whole jar, so unreachable references are reported too; the smoke keeps
 * a per-profile known-issue list for those.
 *
 * <p>Issues are written as tab-separated lines:
 * {@code key<TAB>count<TAB>referrer...}, where key is {@code <kind> <target>}.
 *
 * <pre>
 * java -cp asm.jar LinkGapScanner.java --out issues.tsv
 *     --subject a.jar --subject classesDir --classpath lib.jar ...
 *     [--teavm-classlib teavm-classlib.jar]
 * </pre>
 */
public final class LinkGapScanner {
    private static final int MAX_REFERRERS = 3;

    /**
     * The invokedynamic bootstraps TeaVM 0.15 links itself (JCLPlugin registers a
     * BootstrapMethodSubstitutor for each); any other bootstrap fails to link.
     */
    private static final Set<String> TEAVM_BOOTSTRAPS = Set.of(
            "java/lang/invoke/LambdaMetafactory.metafactory",
            "java/lang/invoke/LambdaMetafactory.altMetafactory",
            "java/lang/runtime/ObjectMethods.bootstrap",
            "java/lang/invoke/StringConcatFactory.makeConcat",
            "java/lang/invoke/StringConcatFactory.makeConcatWithConstants",
            "java/lang/runtime/SwitchBootstraps.typeSwitch",
            "java/lang/runtime/SwitchBootstraps.enumSwitch");

    private final List<Root> subjects = new ArrayList<>();
    private final List<Root> classpath = new ArrayList<>();
    private final Resolver jvm = new Resolver(null);
    private Resolver teavm;
    private final Map<String, Issue> issues = new TreeMap<>();
    private int scannedClasses;
    private int checkedReferences;

    public static void main(String[] args) throws IOException {
        LinkGapScanner scanner = new LinkGapScanner();
        Path out = null;
        Path suggestions = null;
        Root classlib = null;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--subject" -> scanner.subjects.add(Root.open(Path.of(args[++index])));
                case "--classpath" -> scanner.classpath.add(Root.open(Path.of(args[++index])));
                case "--teavm-classlib" -> classlib = Root.open(Path.of(args[++index]));
                case "--out" -> out = Path.of(args[++index]);
                case "--suggestions" -> suggestions = Path.of(args[++index]);
                default -> throw new IllegalArgumentException("unknown argument " + argument);
            }
        }
        if (out == null || scanner.subjects.isEmpty()) {
            throw new IllegalArgumentException("usage: LinkGapScanner --out FILE --subject ROOT..."
                    + " [--classpath ROOT...] [--teavm-classlib JAR]");
        }
        if (classlib != null) {
            scanner.teavm = scanner.new Resolver(classlib);
            if (scanner.teavm.info("java/lang/Object") == null) {
                throw new IllegalArgumentException("--teavm-classlib has no TObject: " + classlib);
            }
        }
        try {
            scanner.scan();
            if (suggestions != null) {
                Files.writeString(suggestions, scanner.suggestions(), StandardCharsets.UTF_8);
            }
        } finally {
            for (Root root : scanner.subjects) root.close();
            for (Root root : scanner.classpath) root.close();
            if (classlib != null) classlib.close();
        }
        StringBuilder text = new StringBuilder();
        for (Issue issue : scanner.issues.values()) {
            text.append(issue.key).append('\t').append(issue.count);
            for (String referrer : issue.referrers) text.append('\t').append(referrer);
            text.append('\n');
        }
        Files.writeString(out, text, StandardCharsets.UTF_8);
        System.out.println("LINK_GAP_SCANNED classes=" + scanner.scannedClasses
                + " references=" + scanner.checkedReferences
                + " issues=" + scanner.issues.size()
                + " teavmClasslib=" + (classlib != null));
    }

    private String suggestions() throws IOException {
        StringBuilder text = new StringBuilder();
        List<String> names = new ArrayList<>();
        if (issues.keySet().stream().anyMatch(key -> key.startsWith("missing-class "))) {
            for (Root root : classpath) names.addAll(root.classNames());
        }
        for (String key : issues.keySet()) {
            String target = key.substring(key.indexOf(' ') + 1);
            List<String> candidates = new ArrayList<>();
            if (key.startsWith("missing-class ")) {
                String simple = target.substring(target.lastIndexOf('/') + 1);
                for (String name : names) {
                    if (!name.equals(target) && name.endsWith("/" + simple)) {
                        candidates.add("relocation candidate: " + target.replace('/', '.') + " -> "
                                + name.replace('/', '.'));
                    }
                }
            } else {
                int dot = target.indexOf('.');
                if (dot > 0) {
                    ClassInfo owner = jvm.info(target.substring(0, dot));
                    String member = target.substring(dot + 1);
                    int split = member.indexOf('(');
                    boolean method = split >= 0;
                    if (!method) split = member.indexOf(':');
                    if (owner != null && split >= 0) {
                        String name = member.substring(0, split);
                        String descriptor = member.substring(split);
                        for (String candidate : (method ? owner.methods : owner.fields).keySet()) {
                            if (method && !name.startsWith("<") && candidate.startsWith("<")) continue;
                            if (candidate.startsWith(name + (method ? "(" : ":"))
                                    || candidate.endsWith(descriptor)) {
                                candidates.add("member candidate (verify semantics): "
                                        + owner.name + "." + candidate);
                            }
                        }
                    }
                }
            }
            if (candidates.isEmpty()) candidates.add("inspect the selected vanilla ABI; add a relocation or VersionOps adapter");
            text.append(key).append('\t')
                    .append(String.join(" | ", candidates.stream().distinct().sorted().limit(8).toList()))
                    .append('\n');
        }
        return text.toString();
    }

    private void scan() throws IOException {
        Set<String> seen = new HashSet<>();
        for (Root root : subjects) {
            for (String name : root.classNames()) {
                if (!seen.add(name)) continue;
                byte[] bytes = root.read(name);
                if (bytes == null) continue;
                scannedClasses++;
                reader(bytes).accept(new SubjectVisitor(), ClassReader.SKIP_FRAMES);
            }
        }
    }

    // ASM 9.8 reads class files up to major 69 (Java 25). The scanner also
    // reads the running JDK's platform classes, which are newer on a JDK 26
    // runner (major 70). Only symbolic references are needed, so parse a copy
    // with the major version lowered to the newest one ASM accepts.
    private static final int MAX_ASM_MAJOR = 69;

    private static ClassReader reader(byte[] bytes) {
        int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        if (major <= MAX_ASM_MAJOR) {
            return new ClassReader(bytes);
        }
        byte[] copy = bytes.clone();
        copy[6] = (byte) (MAX_ASM_MAJOR >>> 8);
        copy[7] = (byte) MAX_ASM_MAJOR;
        return new ClassReader(copy);
    }

    private static byte[] readJdk(String name) {
        try (InputStream stream = ClassLoader.getPlatformClassLoader()
                .getResourceAsStream(name + ".class")) {
            return stream == null ? null : stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // ------------------------------------------------------------- resolvers

    /**
     * Class lookup and member resolution for one link model: the JVM (subjects,
     * class path, running JDK) or TeaVM (subjects and class path for everything
     * but the JDK packages, the TeaVM class library for those).
     */
    private final class Resolver {
        private final Root classlib;
        private final Map<String, ClassInfo> classes = new HashMap<>();
        private final Set<String> missing = new HashSet<>();

        Resolver(Root classlib) {
            this.classlib = classlib;
        }

        boolean isTeaVM() {
            return classlib != null;
        }

        ClassInfo info(String name) {
            if (name.startsWith("[")) {
                return info("java/lang/Object");
            }
            ClassInfo cached = classes.get(name);
            if (cached != null || missing.contains(name)) return cached;
            byte[] bytes = null;
            if (isTeaVM() && teavmSubstituted(name)) {
                // ClasslibSubstitutionPolicy.dontFallbackWhenNoSubstitution(): a JDK class
                // without a substitute is missing, whatever the class path holds.  The
                // substitute (org/teavm/classlib/java/**/T<Name>) may come from any class
                // path root: the class library jar or the Gaius runtime classes
                // (port/src/main/java/org/teavm/classlib/**).
                List<Root> roots = new ArrayList<>(subjects);
                roots.addAll(classpath);
                roots.add(classlib);
                candidates:
                for (String candidate : teavmCandidates(name)) {
                    for (Root root : roots) {
                        if ((bytes = root.read(candidate)) != null) break candidates;
                    }
                }
            } else {
                for (Root root : subjects) {
                    if ((bytes = root.read(name)) != null) break;
                }
                if (bytes == null) {
                    for (Root root : classpath) {
                        if ((bytes = root.read(name)) != null) break;
                    }
                }
                if (bytes == null && !isTeaVM()) bytes = readJdk(name);
            }
            if (bytes == null) {
                missing.add(name);
                return null;
            }
            ClassInfo info = new ClassInfo();
            reader(bytes).accept(new InfoVisitor(info, isTeaVM()), ClassReader.SKIP_CODE
                    | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            // A class library class stands for the JDK class it was looked up as.
            info.name = name;
            if (isTeaVM() && name.equals("java/lang/Object")) info.superName = null;
            classes.put(name, info);
            return info;
        }

        /** The class and every supertype must be loadable for a decision. */
        String missingSupertype(ClassInfo start) {
            Deque<ClassInfo> queue = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                ClassInfo current = queue.poll();
                if (!visited.add(current.name)) continue;
                List<String> parents = new ArrayList<>(current.interfaces);
                if (current.superName != null) parents.add(current.superName);
                for (String parent : parents) {
                    ClassInfo parentInfo = info(parent);
                    if (parentInfo == null) return parent;
                    queue.add(parentInfo);
                }
            }
            return null;
        }

        Member findField(ClassInfo owner, String name, String desc) {
            Member declared = owner.fields.get(name + ":" + desc);
            if (declared != null) return declared;
            for (String parent : owner.interfaces) {
                ClassInfo parentInfo = info(parent);
                if (parentInfo == null) continue;
                Member found = findField(parentInfo, name, desc);
                if (found != null) return found;
            }
            ClassInfo superInfo = owner.superName == null ? null : info(owner.superName);
            return superInfo == null ? null : findField(superInfo, name, desc);
        }

        Member findClassMethod(ClassInfo owner, String name, String desc) {
            for (ClassInfo current = owner; current != null;
                    current = current.superName == null ? null : info(current.superName)) {
                Member declared = current.methods.get(name + desc);
                if (declared != null) return declared;
                if (current.signaturePolymorphic(name)) return current.methodsByName.get(name);
            }
            return findSuperinterfaceMethod(owner, name, desc, new HashSet<>());
        }

        Member findInterfaceMethod(ClassInfo owner, String name, String desc) {
            Member declared = owner.methods.get(name + desc);
            if (declared != null) return declared;
            ClassInfo object = info("java/lang/Object");
            if (object != null) {
                Member objectMethod = object.methods.get(name + desc);
                if (objectMethod != null && (objectMethod.access & Opcodes.ACC_PUBLIC) != 0
                        && (objectMethod.access & Opcodes.ACC_STATIC) == 0) {
                    return objectMethod;
                }
            }
            return findSuperinterfaceMethod(owner, name, desc, new HashSet<>());
        }

        private Member findSuperinterfaceMethod(
                ClassInfo owner, String name, String desc, Set<String> visited) {
            for (ClassInfo current = owner; current != null;
                    current = current.superName == null ? null : info(current.superName)) {
                for (String parent : current.interfaces) {
                    if (!visited.add(parent)) continue;
                    ClassInfo parentInfo = info(parent);
                    if (parentInfo == null) continue;
                    Member declared = parentInfo.methods.get(name + desc);
                    if (declared != null && (declared.access
                            & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                        return declared;
                    }
                    Member inherited = findSuperinterfaceMethod(parentInfo, name, desc, visited);
                    if (inherited != null) return inherited;
                }
            }
            return null;
        }
    }

    /** The packages TeaVM never takes from the class path (ClasslibSubstitutionPolicy). */
    private static boolean teavmSubstituted(String name) {
        return name.startsWith("java/") || name.startsWith("javax/xml/");
    }

    /** Class library entries that stand for a JDK class, in TeaVM's lookup order. */
    private static List<String> teavmCandidates(String name) {
        List<String> candidates = new ArrayList<>(2);
        if (name.startsWith("java/time/")) {
            candidates.add("org/threeten/bp/" + name.substring("java/time/".length()));
        }
        if (name.startsWith("java/")) {
            int slash = name.lastIndexOf('/');
            candidates.add("org/teavm/classlib/" + name.substring(0, slash + 1) + "T"
                    + name.substring(slash + 1));
        }
        return candidates;
    }

    /** Maps a class library internal name back to the JDK name it implements. */
    private static String teavmJdkName(String name) {
        if (name.startsWith("org/threeten/bp/")) {
            return "java/time/" + name.substring("org/threeten/bp/".length());
        }
        if (name.startsWith("org/teavm/classlib/java/")) {
            int slash = name.lastIndexOf('/');
            String simple = name.substring(slash + 1);
            if (simple.length() > 1 && simple.charAt(0) == 'T') {
                return name.substring("org/teavm/classlib/".length(), slash + 1)
                        + simple.substring(1);
            }
        }
        return name;
    }

    private static String teavmJdkDescriptor(String descriptor) {
        StringBuilder mapped = new StringBuilder(descriptor.length());
        int index = 0;
        while (index < descriptor.length()) {
            char c = descriptor.charAt(index);
            mapped.append(c);
            index++;
            if (c == 'L') {
                int end = descriptor.indexOf(';', index);
                mapped.append(teavmJdkName(descriptor.substring(index, end))).append(';');
                index = end + 1;
            }
        }
        return mapped.toString();
    }

    // ---------------------------------------------------------------- checks

    private record Problem(String kind, String target) {
    }

    private void report(String kind, String target, String referrer) {
        String key = kind + " " + target;
        Issue issue = issues.computeIfAbsent(key, Issue::new);
        issue.count++;
        if (issue.referrers.size() < MAX_REFERRERS && !issue.referrers.contains(referrer)) {
            issue.referrers.add(referrer);
        }
    }

    /**
     * Reports what the JVM check finds; when the reference links on the JVM,
     * also what the TeaVM check finds, with a {@code teavm-} kind.
     */
    private void check(String referrer, java.util.function.Function<Resolver, List<Problem>> check) {
        checkedReferences++;
        List<Problem> problems = check.apply(jvm);
        for (Problem problem : problems) report(problem.kind(), problem.target(), referrer);
        if (problems.isEmpty() && teavm != null) {
            for (Problem problem : check.apply(teavm)) {
                report("teavm-" + problem.kind(), problem.target(), referrer);
            }
        }
    }

    private ClassInfo requireClass(Resolver resolver, String name, List<Problem> problems) {
        if (name.startsWith("[")) {
            Type element = Type.getType(name).getElementType();
            if (element.getSort() == Type.OBJECT) {
                requireClass(resolver, element.getInternalName(), problems);
            }
            return resolver.info("java/lang/Object");
        }
        // TeaVM renames references to class library classes (org/teavm/classlib/java/**/T*,
        // org/threeten/bp/**) in the Gaius runtime substitutes back to the JDK names.
        String linked = resolver.isTeaVM() ? teavmJdkName(name) : name;
        ClassInfo info = resolver.info(linked);
        if (info == null) problems.add(new Problem("missing-class", linked));
        return info;
    }

    private List<Problem> classProblems(Resolver resolver, String name) {
        List<Problem> problems = new ArrayList<>(1);
        requireClass(resolver, name, problems);
        return problems;
    }

    private List<Problem> fieldProblems(Resolver resolver, int opcode, String owner, String name,
            String desc) {
        if (resolver.isTeaVM()) {
            owner = teavmJdkName(owner);
            desc = teavmJdkDescriptor(desc);
        }
        List<Problem> problems = new ArrayList<>(1);
        ClassInfo ownerInfo = requireClass(resolver, owner, problems);
        if (ownerInfo == null) return problems;
        String target = owner + "." + name + ":" + desc;
        if (resolver.isTeaVM() && opcode == Opcodes.GETSTATIC && owner.equals("java/lang/System")
                && (name.equals("out") || name.equals("err") || name.equals("in"))) {
            // SystemClassTransformer reads System.out/err/in through TSystem.out()/err()/in().
            Member accessor = ownerInfo.methods.get(name + "()" + desc);
            if (accessor == null || (accessor.access & Opcodes.ACC_STATIC) == 0) {
                problems.add(new Problem("missing-method", owner + "." + name + "()" + desc));
            }
            return problems;
        }
        Member field = resolver.findField(ownerInfo, name, desc);
        if (field == null) {
            String unknown = resolver.missingSupertype(ownerInfo);
            problems.add(new Problem(unknown == null ? "missing-field" : "unresolved-field", target
                    + (unknown == null ? "" : " (missing supertype " + unknown + ")")));
            return problems;
        }
        boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
        boolean wantsStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
        if (isStatic != wantsStatic) {
            problems.add(new Problem("static-mismatch", target + " accessed with "
                    + opcodeName(opcode)));
        }
        return problems;
    }

    private List<Problem> methodProblems(Resolver resolver, int opcode, String owner, String name,
            String desc, boolean itf) {
        if (resolver.isTeaVM()) {
            owner = owner.startsWith("[") ? owner : teavmJdkName(owner);
            desc = teavmJdkDescriptor(desc);
        }
        List<Problem> problems = new ArrayList<>(1);
        ClassInfo ownerInfo = requireClass(resolver, owner, problems);
        if (ownerInfo == null || owner.startsWith("[")) return problems;
        String target = owner + "." + name + desc;
        boolean ownerIsInterface = ownerInfo.isInterface();
        if (opcode == Opcodes.INVOKEINTERFACE && !ownerIsInterface) {
            problems.add(new Problem("itf-mismatch", target + " invokeinterface on a class"));
        } else if (opcode == Opcodes.INVOKEVIRTUAL && ownerIsInterface) {
            problems.add(new Problem("itf-mismatch", target + " invokevirtual on an interface"));
        } else if (itf != ownerIsInterface) {
            problems.add(new Problem("itf-mismatch", target + " " + opcodeName(opcode) + " itf="
                    + itf + " but owner is " + (ownerIsInterface ? "an interface" : "a class")));
        }
        Member method;
        if (name.equals("<init>") || name.equals("<clinit>")) {
            method = ownerInfo.methods.get(name + desc);
        } else if (ownerIsInterface) {
            method = resolver.findInterfaceMethod(ownerInfo, name, desc);
        } else {
            method = resolver.findClassMethod(ownerInfo, name, desc);
        }
        if (method == null) {
            String unknown = name.startsWith("<") ? null : resolver.missingSupertype(ownerInfo);
            problems.add(new Problem(unknown == null ? "missing-method" : "unresolved-method",
                    target + (unknown == null ? "" : " (missing supertype " + unknown + ")")));
            return problems;
        }
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        boolean wantsStatic = opcode == Opcodes.INVOKESTATIC;
        if (isStatic != wantsStatic) {
            problems.add(new Problem("static-mismatch", target + " invoked with "
                    + opcodeName(opcode)));
        }
        return problems;
    }

    private List<Problem> handleProblems(Resolver resolver, Handle handle) {
        String owner = handle.getOwner();
        return switch (handle.getTag()) {
            case Opcodes.H_GETFIELD -> fieldProblems(resolver, Opcodes.GETFIELD, owner,
                    handle.getName(), handle.getDesc());
            case Opcodes.H_GETSTATIC -> fieldProblems(resolver, Opcodes.GETSTATIC, owner,
                    handle.getName(), handle.getDesc());
            case Opcodes.H_PUTFIELD -> fieldProblems(resolver, Opcodes.PUTFIELD, owner,
                    handle.getName(), handle.getDesc());
            case Opcodes.H_PUTSTATIC -> fieldProblems(resolver, Opcodes.PUTSTATIC, owner,
                    handle.getName(), handle.getDesc());
            case Opcodes.H_INVOKEVIRTUAL -> methodProblems(resolver, Opcodes.INVOKEVIRTUAL, owner,
                    handle.getName(), handle.getDesc(), handle.isInterface());
            case Opcodes.H_INVOKESTATIC -> methodProblems(resolver, Opcodes.INVOKESTATIC, owner,
                    handle.getName(), handle.getDesc(), handle.isInterface());
            case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> methodProblems(resolver,
                    Opcodes.INVOKESPECIAL, owner, handle.getName(), handle.getDesc(),
                    handle.isInterface());
            case Opcodes.H_INVOKEINTERFACE -> methodProblems(resolver, Opcodes.INVOKEINTERFACE,
                    owner, handle.getName(), handle.getDesc(), handle.isInterface());
            default -> List.of(new Problem("unknown-handle", owner + "." + handle.getName()));
        };
    }

    /** A bootstrap method handle: TeaVM links only its registered bootstrap substitutors. */
    private List<Problem> bootstrapProblems(Resolver resolver, Handle bootstrap) {
        if (resolver.isTeaVM()) {
            String method = bootstrap.getOwner() + "." + bootstrap.getName();
            return TEAVM_BOOTSTRAPS.contains(method) ? List.of()
                    : List.of(new Problem("unsupported-bootstrap", method + bootstrap.getDesc()));
        }
        return handleProblems(resolver, bootstrap);
    }

    private void checkConstant(Object value, String referrer) {
        if (value instanceof Type type) {
            if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                String name = type.getInternalName();
                check(referrer, resolver -> classProblems(resolver, name));
            }
        } else if (value instanceof Handle handle) {
            check(referrer, resolver -> handleProblems(resolver, handle));
        } else if (value instanceof ConstantDynamic dynamic) {
            check(referrer, resolver -> bootstrapProblems(resolver, dynamic.getBootstrapMethod()));
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                checkConstant(dynamic.getBootstrapMethodArgument(index), referrer);
            }
        }
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case Opcodes.GETSTATIC -> "getstatic";
            case Opcodes.PUTSTATIC -> "putstatic";
            case Opcodes.GETFIELD -> "getfield";
            case Opcodes.PUTFIELD -> "putfield";
            case Opcodes.INVOKEVIRTUAL -> "invokevirtual";
            case Opcodes.INVOKESPECIAL -> "invokespecial";
            case Opcodes.INVOKESTATIC -> "invokestatic";
            case Opcodes.INVOKEINTERFACE -> "invokeinterface";
            default -> "opcode " + opcode;
        };
    }

    // -------------------------------------------------------------- visitors

    private final class SubjectVisitor extends ClassVisitor {
        private String className;

        SubjectVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            className = name;
            if (superName != null) check(name, resolver -> classProblems(resolver, superName));
            if (interfaces != null) {
                for (String parent : interfaces) {
                    check(name, resolver -> classProblems(resolver, parent));
                }
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            String referrer = className + "." + name + descriptor;
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitFieldInsn(int opcode, String owner, String fieldName,
                        String fieldDescriptor) {
                    check(referrer, resolver -> fieldProblems(resolver, opcode, owner, fieldName,
                            fieldDescriptor));
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName,
                        String methodDescriptor, boolean isInterface) {
                    check(referrer, resolver -> methodProblems(resolver, opcode, owner, methodName,
                            methodDescriptor, isInterface));
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    check(referrer, resolver -> {
                        List<Problem> problems = new ArrayList<>(1);
                        ClassInfo info = requireClass(resolver, type, problems);
                        if (opcode == Opcodes.NEW && info != null && (info.isInterface()
                                || (info.access & Opcodes.ACC_ABSTRACT) != 0)) {
                            problems.add(new Problem("abstract-new", type));
                        }
                        return problems;
                    });
                }

                @Override
                public void visitLdcInsn(Object value) {
                    checkConstant(value, referrer);
                }

                @Override
                public void visitMultiANewArrayInsn(String arrayDescriptor, int dimensions) {
                    check(referrer, resolver -> classProblems(resolver, arrayDescriptor));
                }

                @Override
                public void visitInvokeDynamicInsn(String dynamicName, String dynamicDescriptor,
                        Handle bootstrap, Object... arguments) {
                    check(referrer, resolver -> bootstrapProblems(resolver, bootstrap));
                    for (Object argument : arguments) checkConstant(argument, referrer);
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start,
                        org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) {
                    if (type != null) check(referrer, resolver -> classProblems(resolver, type));
                }
            };
        }
    }

    /**
     * Records a class's kind, supertypes and members.  For the TeaVM model the
     * class library names are mapped back to JDK names and the TeaVM interop
     * annotations are applied: {@code @Rename("x")} gives a member its linked
     * name, {@code @Remove} drops it, {@code @Superclass("a.B")} replaces the
     * superclass ({@code ""} for none).
     */
    private static final class InfoVisitor extends ClassVisitor {
        private static final String RENAME = "Lorg/teavm/interop/Rename;";
        private static final String REMOVE = "Lorg/teavm/interop/Remove;";
        private static final String SUPERCLASS = "Lorg/teavm/interop/Superclass;";

        private final ClassInfo info;
        private final boolean teavm;

        InfoVisitor(ClassInfo info, boolean teavm) {
            super(Opcodes.ASM9);
            this.info = info;
            this.teavm = teavm;
        }

        private String name(String internalName) {
            return teavm ? teavmJdkName(internalName) : internalName;
        }

        private String descriptor(String descriptor) {
            return teavm ? teavmJdkDescriptor(descriptor) : descriptor;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            info.name = name(name);
            info.access = access;
            info.superName = superName == null ? null : name(superName);
            if (interfaces != null) {
                for (String parent : interfaces) info.interfaces.add(name(parent));
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!teavm || !descriptor.equals(SUPERCLASS)) return null;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String key, Object value) {
                    if (key.equals("value")) {
                        String superclass = ((String) value).replace('.', '/');
                        info.superName = superclass.isEmpty() ? null : superclass;
                    }
                }
            };
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                String signature, Object value) {
            info.fields.put(name + ":" + descriptor(descriptor), new Member(access));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            String linkedDescriptor = descriptor(descriptor);
            if (!teavm) {
                add(name, linkedDescriptor, access);
                return null;
            }
            return new MethodVisitor(Opcodes.ASM9) {
                private String linkedName = name;
                private boolean removed;

                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    if (annotation.equals(REMOVE)) {
                        removed = true;
                    } else if (annotation.equals(RENAME)) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String key, Object value) {
                                if (key.equals("value")) linkedName = (String) value;
                            }
                        };
                    }
                    return null;
                }

                @Override
                public void visitEnd() {
                    if (!removed) add(linkedName, linkedDescriptor, access);
                }
            };
        }

        private void add(String name, String descriptor, int access) {
            Member member = new Member(access);
            info.methods.put(name + descriptor, member);
            info.methodsByName.putIfAbsent(name, member);
        }
    }

    // ----------------------------------------------------------------- model

    private static final class ClassInfo {
        String name;
        int access;
        String superName;
        final List<String> interfaces = new ArrayList<>();
        final Map<String, Member> fields = new HashMap<>();
        final Map<String, Member> methods = new HashMap<>();
        final Map<String, Member> methodsByName = new HashMap<>();

        boolean isInterface() {
            return (access & Opcodes.ACC_INTERFACE) != 0;
        }

        /** JVMS 2.9.3: MethodHandle/VarHandle native varargs methods accept any descriptor. */
        boolean signaturePolymorphic(String methodName) {
            if (!name.equals("java/lang/invoke/MethodHandle")
                    && !name.equals("java/lang/invoke/VarHandle")) {
                return false;
            }
            Member member = methodsByName.get(methodName);
            return member != null && (member.access & Opcodes.ACC_NATIVE) != 0
                    && (member.access & Opcodes.ACC_VARARGS) != 0;
        }
    }

    private record Member(int access) {
    }

    private static final class Issue {
        final String key;
        int count;
        final List<String> referrers = new ArrayList<>();

        Issue(String key) {
            this.key = key;
        }
    }

    private interface Root extends AutoCloseable {
        List<String> classNames() throws IOException;

        byte[] read(String internalName);

        @Override
        void close() throws IOException;

        static Root open(Path path) throws IOException {
            if (Files.isDirectory(path)) return new DirectoryRoot(path);
            if (Files.isRegularFile(path)) return new JarRoot(new ZipFile(path.toFile()));
            throw new IOException("class root does not exist: " + path);
        }
    }

    private record JarRoot(ZipFile jar) implements Root {
        @Override
        public List<String> classNames() {
            List<String> names = new ArrayList<>();
            jar.stream().map(ZipEntry::getName)
                    .filter(name -> name.endsWith(".class") && !name.startsWith("META-INF/")
                            && !name.endsWith("module-info.class")
                            && !name.endsWith("package-info.class"))
                    .sorted()
                    .forEach(name -> names.add(name.substring(0, name.length() - 6)));
            return names;
        }

        @Override
        public byte[] read(String internalName) {
            ZipEntry entry = jar.getEntry(internalName + ".class");
            if (entry == null) return null;
            try (InputStream stream = jar.getInputStream(entry)) {
                return stream.readAllBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }

    private record DirectoryRoot(Path directory) implements Root {
        @Override
        public List<String> classNames() throws IOException {
            try (Stream<Path> files = Files.walk(directory)) {
                return files.filter(file -> file.toString().endsWith(".class"))
                        .map(file -> directory.relativize(file).toString().replace('\\', '/'))
                        .filter(name -> !name.startsWith("META-INF/")
                                && !name.endsWith("module-info.class")
                                && !name.endsWith("package-info.class"))
                        .map(name -> name.substring(0, name.length() - 6))
                        .sorted()
                        .toList();
            }
        }

        @Override
        public byte[] read(String internalName) {
            Path file = directory.resolve(internalName + ".class");
            try {
                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Override
        public void close() {
        }
    }

    private LinkGapScanner() {
    }
}
