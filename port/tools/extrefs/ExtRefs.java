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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * External API surface of vanilla Minecraft, offline:
 * the vanilla client jar, the profile's library jars and the running JDK's {@code ct.sym}. No
 * TeaVM build. Usually run through {@code extrefs.sh}, which resolves the inputs of a profile.
 *
 * <ul>
 *   <li>{@code usage}: the members and classes that vanilla references in the JDK and in every
 *       native library (a library that declares native methods, every LWJGL module), written to
 *       {@code port/mc/profiles/<profile>/usage.lock} or compared with it. A reference whose
 *       symbolic owner is a vanilla class but that resolves into a JDK or library supertype
 *       (an inherited {@code getMessage()}, {@code start()}) is recorded against the declaring
 *       external class.</li>
 *   <li>{@code jdk}: every JDK member and class of the usage that TeaVM's class library, as Gaius
 *       extends it (the classlib overlay, {@code port/overrides/jdk/<release>} and the class
 *       library substitutes of {@code port/src}), does not provide. Resolution follows TeaVM 0.15
 *       ({@code ClasslibSubstitutionPolicy}: {@code java.**} only through
 *       {@code org.teavm.classlib.**.T<Name>}, {@code java.time} through {@code org.threeten.bp},
 *       no fallback; the {@code @Rename}, {@code @Remove} and {@code @Superclass} interop
 *       annotations; {@code @SupportedOn}/{@code @UnsupportedOn} per target; the bootstrap
 *       substitutors of JCLPlugin), the same rules as {@code port/scripts/link-gap}. Each gap
 *       names the oldest Java SE release (of {@code --releases}) whose API has the member, which
 *       is the {@code port/overrides/jdk/<release>} directory a substitute belongs in.</li>
 *   <li>{@code apilock}: the normalized API (javap-like, non-private members) of every library
 *       class that a library override references or replaces, written to the
 *       {@code api.lock} of an override version directory or compared with another version of
 *       the library jar.</li>
 * </ul>
 */
public final class ExtRefs {
    static final String JDK = "jdk";
    static final String UNRESOLVED = "unresolved";
    static final String LIBRARY = "library ";

    /** The invokedynamic bootstraps TeaVM 0.15 links itself (JCLPlugin's substitutors). */
    static final Set<String> TEAVM_BOOTSTRAPS = Set.of(
            "java/lang/invoke/LambdaMetafactory.metafactory",
            "java/lang/invoke/LambdaMetafactory.altMetafactory",
            "java/lang/runtime/ObjectMethods.bootstrap",
            "java/lang/invoke/StringConcatFactory.makeConcat",
            "java/lang/invoke/StringConcatFactory.makeConcatWithConstants",
            "java/lang/runtime/SwitchBootstraps.typeSwitch",
            "java/lang/runtime/SwitchBootstraps.enumSwitch");

    private ExtRefs() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usageAndExit();
        }
        Options options = Options.parse(args);
        int status = switch (args[0]) {
            case "usage" -> usageCommand(options);
            case "jdk" -> jdkCommand(options);
            case "apilock" -> apiLockCommand(options);
            default -> {
                usageAndExit();
                yield 2;
            }
        };
        System.exit(status);
    }

    private static void usageAndExit() {
        System.err.println("usage: ExtRefs usage --profile ID --release N --client JAR --libraries TSV"
                + " [--write LOCK | --check LOCK | --print]\n"
                + "       ExtRefs jdk --profile ID --release N --client JAR --libraries TSV --classlib ROOT..."
                + " [--target js|wasmgc] [--releases N,M] [--patched JAR] [--known JSON] [--strict]\n"
                + "       ExtRefs apilock --jar LIBRARY_JAR (--write LOCK --coordinate C --range R"
                + " --verified COORDINATE --origin TEXT --classes DIR... | --check LOCK [--version V])\n"
                + "       ExtRefs apilock --matches RANGE --version V");
        System.exit(2);
    }

    // ------------------------------------------------------------------------- options

    static final class Options {
        final Map<String, List<String>> values = new HashMap<>();
        final Set<String> flags = new HashSet<>();

        static Options parse(String[] args) {
            Options options = new Options();
            for (int index = 1; index < args.length; index++) {
                String argument = args[index];
                if (!argument.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument " + argument);
                }
                String key = argument.substring(2);
                if (Set.of("print", "strict").contains(key)) {
                    options.flags.add(key);
                } else {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException(argument + " needs a value");
                    }
                    options.values.computeIfAbsent(key, k -> new ArrayList<>()).add(args[++index]);
                }
            }
            return options;
        }

        String get(String key) {
            List<String> list = values.get(key);
            return list == null ? null : list.get(list.size() - 1);
        }

        String require(String key) {
            String value = get(key);
            if (value == null) {
                throw new IllegalArgumentException("--" + key + " is required");
            }
            return value;
        }

        List<String> all(String key) {
            return values.getOrDefault(key, List.of());
        }

        boolean flag(String key) {
            return flags.contains(key);
        }
    }

    // ------------------------------------------------------------------------- usage

    private static int usageCommand(Options options) throws IOException {
        String profile = options.require("profile");
        int release = Integer.parseInt(options.require("release"));
        try (Libraries libraries = Libraries.load(Path.of(options.require("libraries")));
                Root client = Root.open(Path.of(options.require("client")));
                CtSym ctSym = CtSym.open(release)) {
            Usage usage = Usage.scan(client, libraries, ctSym);
            String text = usage.render(profile, release);
            if (options.get("write") != null) {
                Path lock = Path.of(options.get("write"));
                Files.createDirectories(lock.toAbsolutePath().getParent());
                Files.writeString(lock, text, StandardCharsets.UTF_8);
                System.out.println("Wrote " + lock + ": " + usage.summary());
                return 0;
            }
            if (options.get("check") != null) {
                Path lock = Path.of(options.get("check"));
                if (!Files.isRegularFile(lock)) {
                    System.err.println("No usage lock " + lock + "; write it with --write");
                    return 3;
                }
                Map<String, TreeSet<String>> committed = Usage.parseSections(Files.readString(lock));
                List<String> diff = Usage.diff(committed, usage.sections);
                if (diff.isEmpty()) {
                    System.out.println("usage.lock of " + profile + " is current: " + usage.summary());
                    return 0;
                }
                System.out.println("usage.lock of " + profile + " differs from vanilla ("
                        + diff.size() + " lines; + new in vanilla, - gone from vanilla):");
                diff.forEach(line -> System.out.println("  " + line));
                return 1;
            }
            if (options.flag("print")) {
                System.out.print(text);
            } else {
                System.out.println(profile + ": " + usage.summary());
            }
            return 0;
        }
    }

    /** The external surface vanilla references, by section ("jdk", "library <coordinate>"). */
    static class Usage {
        final TreeMap<String, TreeSet<String>> sections = new TreeMap<>(Usage::compareSections);

        static int compareSections(String left, String right) {
            return Integer.compare(rank(left), rank(right)) != 0
                    ? Integer.compare(rank(left), rank(right)) : left.compareTo(right);
        }

        private static int rank(String section) {
            return section.equals(JDK) ? 0 : section.equals(UNRESOLVED) ? 2 : 1;
        }

        static Usage scan(Root client, Libraries libraries, CtSym ctSym) throws IOException {
            Set<String> own = new HashSet<>(client.classNames());
            World world = new World(client, own, libraries, ctSym);
            Usage usage = new Usage();
            Recorder recorder = new Recorder(world, usage, false);
            for (String name : new TreeSet<>(own)) {
                byte[] bytes = client.read(name);
                reader(bytes).accept(recorder.visitor(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            usage.sections.keySet().removeIf(section -> usage.sections.get(section).isEmpty());
            return usage;
        }

        /** Usage entry -> the vanilla-owner forms it was referenced as (not written to the lock). */
        final Map<String, Set<String>> aliases = new HashMap<>();

        void add(String section, String entry) {
            sections.computeIfAbsent(section, key -> new TreeSet<>()).add(entry);
        }

        void alias(String entry, String vanillaForm) {
            aliases.computeIfAbsent(entry, key -> new TreeSet<>()).add(vanillaForm);
        }

        String summary() {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, TreeSet<String>> section : sections.entrySet()) {
                parts.add(section.getKey() + "=" + section.getValue().size());
            }
            return String.join(", ", parts);
        }

        String render(String profile, int release) {
            StringBuilder text = new StringBuilder();
            text.append("# External API usage of vanilla Minecraft ").append(profile)
                    .append(" (port/work/").append(profile).append("/client-named.jar): what it references\n")
                    .append("# in the JDK and in each native library.\n")
                    .append("# Generated by port/tools/extrefs; do not edit. Regenerate:\n")
                    .append("#   bash port/tools/extrefs/extrefs.sh usage ").append(profile).append(" --write\n")
                    .append("# C class, M method owner.name(descriptor), F field owner.name:descriptor. A member\n")
                    .append("# inherited by a vanilla class is listed under the JDK or library class declaring it.\n")
                    .append("# [unresolved] lists references to classes that neither the JDK nor a library defines.\n")
                    .append("profile ").append(profile).append('\n')
                    .append("java ").append(release).append('\n');
            for (Map.Entry<String, TreeSet<String>> section : sections.entrySet()) {
                text.append('\n').append('[').append(section.getKey()).append("] ")
                        .append(section.getValue().size()).append('\n');
                for (String entry : section.getValue()) {
                    text.append(entry).append('\n');
                }
            }
            return text.toString();
        }

        static Map<String, TreeSet<String>> parseSections(String text) {
            Map<String, TreeSet<String>> sections = new TreeMap<>(Usage::compareSections);
            TreeSet<String> current = null;
            for (String raw : text.split("\n")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[")) {
                    int end = line.indexOf(']');
                    current = sections.computeIfAbsent(line.substring(1, end), key -> new TreeSet<>());
                } else if (current != null) {
                    current.add(line);
                }
            }
            return sections;
        }

        static List<String> diff(Map<String, TreeSet<String>> before, Map<String, TreeSet<String>> after) {
            List<String> lines = new ArrayList<>();
            Set<String> names = new TreeSet<>(Usage::compareSections);
            names.addAll(before.keySet());
            names.addAll(after.keySet());
            for (String name : names) {
                TreeSet<String> old = before.getOrDefault(name, new TreeSet<>());
                TreeSet<String> now = after.getOrDefault(name, new TreeSet<>());
                for (String entry : now) {
                    if (!old.contains(entry)) {
                        lines.add("+ [" + name + "] " + entry);
                    }
                }
                for (String entry : old) {
                    if (!now.contains(entry)) {
                        lines.add("- [" + name + "] " + entry);
                    }
                }
            }
            return lines;
        }
    }

    /** Records the external references of the vanilla classes into a {@link Usage}. */
    static final class Recorder {
        final World world;
        final Usage usage;
        /**
         * Whether the classes of descriptors count. The usage records only the classes that
         * linking loads (supertypes, type instructions, constants, catch types, member owners):
         * a class that only appears in a signature is never loaded for it.
         */
        final boolean descriptorTypes;

        Recorder(World world, Usage usage, boolean descriptorTypes) {
            this.world = world;
            this.usage = usage;
            this.descriptorTypes = descriptorTypes;
        }

        ClassVisitor visitor() {
            return new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                        String superName, String[] interfaces) {
                    if (superName != null) {
                        type(superName);
                    }
                    if (interfaces != null) {
                        for (String parent : interfaces) {
                            type(parent);
                        }
                    }
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                        String signature, Object value) {
                    descriptor(descriptor);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    descriptor(descriptor);
                    if (exceptions != null && descriptorTypes) {
                        // A throws clause is a signature: never loaded for linking.
                        for (String exception : exceptions) {
                            type(exception);
                        }
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                String methodDescriptor, boolean isInterface) {
                            member(false, owner, methodName, methodDescriptor);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fieldName,
                                String fieldDescriptor) {
                            member(true, owner, fieldName, fieldDescriptor);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            type(type);
                        }

                        @Override
                        public void visitMultiANewArrayInsn(String arrayDescriptor, int dimensions) {
                            descriptorTypes(arrayDescriptor);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            constant(value);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String dynamicName, String dynamicDescriptor,
                                Handle bootstrap, Object... arguments) {
                            descriptor(dynamicDescriptor);
                            bootstrap(bootstrap);
                            for (Object argument : arguments) {
                                constant(argument);
                            }
                        }

                        @Override
                        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                            if (type != null) {
                                type(type);
                            }
                        }
                    };
                }
            };
        }

        void constant(Object value) {
            if (value instanceof Type type) {
                if (type.getSort() == Type.METHOD) {
                    descriptor(type.getDescriptor());
                } else if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                    descriptorTypes(type.getDescriptor());
                }
            } else if (value instanceof Handle handle) {
                handle(handle);
            } else if (value instanceof ConstantDynamic dynamic) {
                descriptor(dynamic.getDescriptor());
                bootstrap(dynamic.getBootstrapMethod());
                for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                    constant(dynamic.getBootstrapMethodArgument(index));
                }
            }
        }

        /**
         * A bootstrap method: its member and class, but not the types of its descriptor
         * (MethodHandles.Lookup, TypeDescriptor), which only the JVM's linkage uses.
         */
        void bootstrap(Handle handle) {
            String owner = handle.getOwner();
            if (world.own(owner)) {
                return;
            }
            type(owner);
            String section = world.section(owner);
            if (section != null) {
                usage.add(section, "M " + owner + "." + handle.getName() + handle.getDesc());
            }
        }

        void handle(Handle handle) {
            boolean field = handle.getTag() <= Opcodes.H_PUTSTATIC;
            member(field, handle.getOwner(), handle.getName(), handle.getDesc());
        }

        void descriptor(String descriptor) {
            if (descriptorTypes) {
                descriptorTypes(descriptor);
            }
        }

        /** Every class of a field, method or array descriptor. */
        void descriptorTypes(String descriptor) {
            if (descriptor.startsWith("(")) {
                for (Type argument : Type.getArgumentTypes(descriptor)) {
                    descriptorTypes(argument.getDescriptor());
                }
                descriptorTypes(Type.getReturnType(descriptor).getDescriptor());
                return;
            }
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.ARRAY) {
                type = type.getElementType();
            }
            if (type.getSort() == Type.OBJECT) {
                type(type.getInternalName());
            }
        }

        /** A class name or an array descriptor (TypeInsn operands may be either). */
        void type(String name) {
            if (name.startsWith("[")) {
                descriptorTypes(name);
                return;
            }
            if (world.own(name)) {
                return;
            }
            String section = world.section(name);
            if (section != null) {
                usage.add(section, "C " + name);
            }
        }

        void member(boolean field, String owner, String name, String descriptor) {
            descriptor(descriptor);
            if (owner.startsWith("[")) {
                descriptorTypes(owner);
                return;
            }
            String external = owner;
            if (world.own(owner)) {
                // Inherited from a JDK or library class, which vanilla itself never names.
                external = world.inheritedFrom(owner, name, descriptor, field);
                if (external == null) {
                    return;
                }
            } else {
                type(owner);
            }
            String section = world.section(external);
            if (section != null) {
                String entry = field ? "F " + external + "." + name + ":" + descriptor
                        : "M " + external + "." + name + descriptor;
                usage.add(section, entry);
                if (!external.equals(owner)) {
                    usage.alias(entry, (field ? "F " : "M ") + owner + "." + name + (field ? ":" : "") + descriptor);
                }
            }
        }
    }

    // ------------------------------------------------------------------------- JVM world

    /** Class lookup for the usage scan: vanilla, then the libraries, then the JDK (ct.sym). */
    static class World {
        final Root client;
        final Set<String> own;
        final Libraries libraries;
        final CtSym ctSym;
        final Map<String, ClassModel> models = new HashMap<>();

        World(Root client, Set<String> own, Libraries libraries, CtSym ctSym) {
            this.client = client;
            this.own = own;
            this.libraries = libraries;
            this.ctSym = ctSym;
        }

        boolean own(String name) {
            return own.contains(name);
        }

        String section(String name) {
            String coordinate = libraries.coordinateOf(name);
            if (coordinate != null) {
                return libraries.isNative(coordinate) ? LIBRARY + coordinate : null;
            }
            if (ctSym.has(name)) {
                return JDK;
            }
            return UNRESOLVED;
        }

        ClassModel model(String name) {
            if (models.containsKey(name)) {
                return models.get(name);
            }
            byte[] bytes = null;
            try {
                if (own.contains(name)) {
                    bytes = client.read(name);
                } else {
                    bytes = libraries.read(name);
                    if (bytes == null) {
                        bytes = ctSym.read(name);
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            ClassModel model = bytes == null ? null : ClassModel.read(bytes, null, null);
            models.put(name, model);
            return model;
        }

        /**
         * The external class that declares a member referenced through a vanilla owner, or
         * {@code null} when a vanilla class declares it (or nothing resolves it, then the first
         * external supertype is returned).
         */
        String inheritedFrom(String owner, String name, String descriptor, boolean field) {
            ClassModel start = model(owner);
            if (start == null) {
                return null;
            }
            Resolution resolution = field ? Resolution.findField(this::model, start, name, descriptor)
                    : name.startsWith("<") ? Resolution.direct(start, name + descriptor)
                    : start.isInterface() ? Resolution.findInterfaceMethod(this::model, start, name, descriptor)
                    : Resolution.findClassMethod(this::model, start, name, descriptor);
            if (resolution != null) {
                return own(resolution.owner.name) ? null : resolution.owner.name;
            }
            // Unresolvable (a missing supertype): blame the first external supertype.
            Deque<ClassModel> queue = new ArrayDeque<>(List.of(start));
            Set<String> seen = new HashSet<>();
            while (!queue.isEmpty()) {
                ClassModel current = queue.poll();
                if (!seen.add(current.name)) {
                    continue;
                }
                for (String parent : current.parents()) {
                    if (!own(parent)) {
                        return parent;
                    }
                    ClassModel parentModel = model(parent);
                    if (parentModel != null) {
                        queue.add(parentModel);
                    }
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------------- class model

    static final class Member {
        final String owner;
        final int access;
        final Set<String> unsupportedOn;
        final Set<String> supportedOn;

        Member(String owner, int access, Set<String> unsupportedOn, Set<String> supportedOn) {
            this.owner = owner;
            this.access = access;
            this.unsupportedOn = unsupportedOn;
            this.supportedOn = supportedOn;
        }

        boolean availableOn(String platform) {
            return platform == null || (!unsupportedOn.contains(platform)
                    && (supportedOn == null || supportedOn.contains(platform)));
        }
    }

    /** A class reduced to what linking needs; TeaVM mode maps class library names back to JDK names. */
    static final class ClassModel {
        private static final String RENAME = "Lorg/teavm/interop/Rename;";
        private static final String REMOVE = "Lorg/teavm/interop/Remove;";
        private static final String SUPERCLASS = "Lorg/teavm/interop/Superclass;";
        private static final String UNSUPPORTED_ON = "Lorg/teavm/interop/UnsupportedOn;";
        private static final String SUPPORTED_ON = "Lorg/teavm/interop/SupportedOn;";
        // javac's preview marker, and the form ct.sym records it in.
        private static final Set<String> PREVIEW = Set.of("Ljdk/internal/javac/PreviewFeature;",
                "Ljdk/internal/PreviewFeature+Annotation;");

        String name;
        int access;
        String superName;
        final List<String> interfaces = new ArrayList<>();
        final Map<String, Member> fields = new HashMap<>();
        final Map<String, Member> methods = new HashMap<>();
        final Map<String, Member> methodsByName = new HashMap<>();
        Set<String> unsupportedOn = new HashSet<>();
        Set<String> supportedOn;
        boolean preview;

        boolean isInterface() {
            return (access & Opcodes.ACC_INTERFACE) != 0;
        }

        List<String> parents() {
            List<String> parents = new ArrayList<>();
            if (superName != null) {
                parents.add(superName);
            }
            parents.addAll(interfaces);
            return parents;
        }

        boolean availableOn(String platform) {
            return platform == null || (!unsupportedOn.contains(platform)
                    && (supportedOn == null || supportedOn.contains(platform)));
        }

        /** JVMS 2.9.3: MethodHandle/VarHandle native varargs methods accept any descriptor. */
        Member signaturePolymorphic(String methodName) {
            if (!name.equals("java/lang/invoke/MethodHandle") && !name.equals("java/lang/invoke/VarHandle")) {
                return null;
            }
            Member member = methodsByName.get(methodName);
            return member != null && (member.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_VARARGS))
                    == (Opcodes.ACC_NATIVE | Opcodes.ACC_VARARGS) ? member : null;
        }

        /**
         * Reads a class. With a {@code mapper} (TeaVM class library mode) every class name is
         * mapped and the TeaVM interop annotations apply; preview members (ct.sym) are dropped.
         */
        static ClassModel read(byte[] bytes, java.util.function.UnaryOperator<String> mapper, String asName) {
            ClassModel model = new ClassModel();
            boolean teavm = mapper != null;
            java.util.function.UnaryOperator<String> names = teavm ? mapper : name -> name;
            reader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                        String superName, String[] interfaces) {
                    model.name = asName != null ? asName : names.apply(name);
                    model.access = access;
                    model.superName = superName == null ? null : names.apply(superName);
                    if (interfaces != null) {
                        for (String parent : interfaces) {
                            model.interfaces.add(names.apply(parent));
                        }
                    }
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (PREVIEW.contains(descriptor)) {
                        model.preview = true;
                        return null;
                    }
                    if (!teavm) {
                        return null;
                    }
                    if (descriptor.equals(SUPERCLASS)) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String key, Object value) {
                                if (key.equals("value")) {
                                    String superclass = ((String) value).replace('.', '/');
                                    model.superName = superclass.isEmpty() ? null : superclass;
                                }
                            }
                        };
                    }
                    if (descriptor.equals(UNSUPPORTED_ON)) {
                        return platforms(model.unsupportedOn);
                    }
                    if (descriptor.equals(SUPPORTED_ON)) {
                        model.supportedOn = new HashSet<>();
                        return platforms(model.supportedOn);
                    }
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                        String signature, Object value) {
                    String mapped = teavm ? mapDescriptor(descriptor, names) : descriptor;
                    return new FieldVisitor(Opcodes.ASM9) {
                        boolean preview;

                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (PREVIEW.contains(annotation)) {
                                preview = true;
                            }
                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            if (!preview) {
                                model.fields.put(name + ":" + mapped,
                                        new Member(model.name, access, Set.of(), null));
                            }
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    String mapped = teavm ? mapDescriptor(descriptor, names) : descriptor;
                    return new MethodVisitor(Opcodes.ASM9) {
                        String linkedName = name;
                        boolean removed;
                        final Set<String> unsupported = new HashSet<>();
                        Set<String> supported;

                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (PREVIEW.contains(annotation)) {
                                removed = true;
                                return null;
                            }
                            if (!teavm) {
                                return null;
                            }
                            switch (annotation) {
                                case REMOVE -> removed = true;
                                case RENAME -> {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visit(String key, Object value) {
                                            if (key.equals("value")) {
                                                linkedName = (String) value;
                                            }
                                        }
                                    };
                                }
                                case UNSUPPORTED_ON -> {
                                    return platforms(unsupported);
                                }
                                case SUPPORTED_ON -> {
                                    supported = new HashSet<>();
                                    return platforms(supported);
                                }
                                default -> {
                                }
                            }
                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            if (removed) {
                                return;
                            }
                            Member member = new Member(model.name, access, unsupported, supported);
                            model.methods.put(linkedName + mapped, member);
                            model.methodsByName.putIfAbsent(linkedName, member);
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return model;
        }

        private static AnnotationVisitor platforms(Set<String> into) {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitArray(String key) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String ignored, Object value) {
                            into.add(String.valueOf(value));
                        }
                    };
                }
            };
        }
    }

    static String mapDescriptor(String descriptor, java.util.function.UnaryOperator<String> names) {
        StringBuilder mapped = new StringBuilder(descriptor.length());
        int index = 0;
        while (index < descriptor.length()) {
            char c = descriptor.charAt(index++);
            mapped.append(c);
            if (c == 'L') {
                int end = descriptor.indexOf(';', index);
                mapped.append(names.apply(descriptor.substring(index, end))).append(';');
                index = end + 1;
            }
        }
        return mapped.toString();
    }

    /** JVM member resolution (JVMS 5.4.3) over a class lookup. */
    record Resolution(ClassModel owner, Member member) {
        interface Lookup {
            ClassModel model(String name);
        }

        static Resolution direct(ClassModel owner, String key) {
            Member member = owner.methods.get(key);
            return member == null ? null : new Resolution(owner, member);
        }

        static Resolution findField(Lookup lookup, ClassModel owner, String name, String descriptor) {
            Member declared = owner.fields.get(name + ":" + descriptor);
            if (declared != null) {
                return new Resolution(owner, declared);
            }
            for (String parent : owner.interfaces) {
                ClassModel model = lookup.model(parent);
                if (model != null) {
                    Resolution found = findField(lookup, model, name, descriptor);
                    if (found != null) {
                        return found;
                    }
                }
            }
            ClassModel superModel = owner.superName == null ? null : lookup.model(owner.superName);
            return superModel == null ? null : findField(lookup, superModel, name, descriptor);
        }

        static Resolution findClassMethod(Lookup lookup, ClassModel owner, String name, String descriptor) {
            for (ClassModel current = owner; current != null;
                    current = current.superName == null ? null : lookup.model(current.superName)) {
                Member declared = current.methods.get(name + descriptor);
                if (declared == null) {
                    declared = current.signaturePolymorphic(name);
                }
                if (declared != null) {
                    return new Resolution(current, declared);
                }
            }
            return findSuperinterfaceMethod(lookup, owner, name, descriptor, new HashSet<>());
        }

        static Resolution findInterfaceMethod(Lookup lookup, ClassModel owner, String name, String descriptor) {
            Member declared = owner.methods.get(name + descriptor);
            if (declared != null) {
                return new Resolution(owner, declared);
            }
            ClassModel object = lookup.model("java/lang/Object");
            if (object != null) {
                Member objectMethod = object.methods.get(name + descriptor);
                if (objectMethod != null && (objectMethod.access & Opcodes.ACC_PUBLIC) != 0
                        && (objectMethod.access & Opcodes.ACC_STATIC) == 0) {
                    return new Resolution(object, objectMethod);
                }
            }
            return findSuperinterfaceMethod(lookup, owner, name, descriptor, new HashSet<>());
        }

        private static Resolution findSuperinterfaceMethod(Lookup lookup, ClassModel owner, String name,
                String descriptor, Set<String> visited) {
            for (ClassModel current = owner; current != null;
                    current = current.superName == null ? null : lookup.model(current.superName)) {
                for (String parent : current.interfaces) {
                    if (!visited.add(parent)) {
                        continue;
                    }
                    ClassModel model = lookup.model(parent);
                    if (model == null) {
                        continue;
                    }
                    Member declared = model.methods.get(name + descriptor);
                    if (declared != null && (declared.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                        return new Resolution(model, declared);
                    }
                    Resolution inherited = findSuperinterfaceMethod(lookup, model, name, descriptor, visited);
                    if (inherited != null) {
                        return inherited;
                    }
                }
            }
            return null;
        }

        /** The first supertype that cannot be loaded, or {@code null}. */
        static String missingSupertype(Lookup lookup, ClassModel start) {
            Deque<ClassModel> queue = new ArrayDeque<>(List.of(start));
            Set<String> visited = new HashSet<>();
            while (!queue.isEmpty()) {
                ClassModel current = queue.poll();
                if (!visited.add(current.name)) {
                    continue;
                }
                for (String parent : current.parents()) {
                    ClassModel model = lookup.model(parent);
                    if (model == null) {
                        return parent;
                    }
                    queue.add(model);
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------------- JDK coverage

    private static int jdkCommand(Options options) throws IOException {
        String profile = options.require("profile");
        int release = Integer.parseInt(options.require("release"));
        String target = options.get("target") == null ? "js" : options.get("target");
        String platform = switch (target) {
            case "js" -> "javascript";
            case "wasmgc" -> "webassembly-gc";
            default -> throw new IllegalArgumentException("--target must be js or wasmgc");
        };
        List<Integer> releases = new ArrayList<>();
        String releaseList = options.get("releases") == null ? String.valueOf(release) : options.get("releases");
        for (String value : releaseList.split(",")) {
            if (!value.isBlank()) {
                releases.add(Integer.parseInt(value.strip()));
            }
        }
        releases.sort(null);
        Map<String, String> known = options.get("known") == null ? Map.of()
                : KnownIssues.load(Path.of(options.get("known")));
        List<Root> classlibRoots = new ArrayList<>();
        try (Libraries libraries = Libraries.load(Path.of(options.require("libraries")));
                Root client = Root.open(Path.of(options.require("client")));
                CtSym ctSym = CtSym.open(release)) {
            for (String root : options.all("classlib")) {
                classlibRoots.add(Root.open(Path.of(root)));
            }
            if (classlibRoots.isEmpty()) {
                throw new IllegalArgumentException("--classlib is required");
            }
            Usage usage = Usage.scan(client, libraries, ctSym);
            TreeSet<String> jdkUsage = usage.sections.getOrDefault(JDK, new TreeSet<>());
            Set<String> remaining = null;
            if (options.get("patched") != null) {
                try (Root patched = Root.open(Path.of(options.get("patched")))) {
                    remaining = Usage.scan(patched, libraries, ctSym).sections.getOrDefault(JDK, new TreeSet<>());
                }
            }
            TeaVMView teavm = new TeaVMView(classlibRoots);
            if (teavm.model("java/lang/Object") == null) {
                throw new IllegalArgumentException("the class library roots have no TObject");
            }
            Map<Integer, CtSym> apis = new LinkedHashMap<>();
            for (int candidate : releases) {
                apis.put(candidate, candidate == release ? ctSym : CtSym.open(candidate));
            }
            List<String[]> gaps = new ArrayList<>();
            int unexpected = 0;
            int knownCount = 0;
            int patchedCount = 0;
            for (String entry : jdkUsage) {
                String problem = teavm.check(entry, platform);
                if (problem == null) {
                    continue;
                }
                String state;
                if (remaining != null && !remaining.contains(entry)) {
                    state = "patched";
                    patchedCount++;
                } else if (KnownIssues.contains(known, problem, entry,
                        usage.aliases.getOrDefault(entry, Set.of()))) {
                    state = "known";
                    knownCount++;
                } else {
                    state = "GAP";
                    unexpected++;
                }
                String home = "-";
                for (Map.Entry<Integer, CtSym> api : apis.entrySet()) {
                    if (api.getValue().hasApi(entry)) {
                        home = "jdk/" + api.getKey();
                        break;
                    }
                }
                if (home.equals("-")) {
                    home = ctSym.has(entryOwner(entry)) ? "not-in-java-se" : "-";
                }
                gaps.add(new String[] {state, problem, entry, home});
            }
            for (Map.Entry<Integer, CtSym> api : apis.entrySet()) {
                if (api.getKey() != release) {
                    api.getValue().close();
                }
            }
            for (Root root : classlibRoots) {
                root.close();
            }
            System.out.println("# JDK members and classes that vanilla " + profile + " references and the TeaVM"
                    + " class library (" + target + ") does not provide");
            System.out.println("# state: GAP (new), known (port/scripts/link-gap known list), patched (no longer"
                    + " referenced by the patched client jar); home: the oldest release of "
                    + releases + " whose Java SE API has it (port/overrides/jdk/<release>)");
            for (String[] gap : gaps) {
                System.out.println(String.join("\t", gap));
            }
            System.out.println("JDK_USAGE profile=" + profile + " target=" + target + " references=" + jdkUsage.size()
                    + " missing=" + gaps.size() + " gap=" + unexpected + " known=" + knownCount
                    + " patched=" + patchedCount);
            return options.flag("strict") && unexpected > 0 ? 1 : 0;
        }
    }

    static String entryOwner(String entry) {
        String body = entry.substring(2);
        if (entry.startsWith("C ")) {
            return body;
        }
        int paren = body.indexOf('(');
        int colon = body.indexOf(':');
        int end = entry.startsWith("M ") ? paren : colon;
        String qualified = body.substring(0, end);
        return qualified.substring(0, qualified.lastIndexOf('.'));
    }

    /** Link-gap known-issue list ({@code port/scripts/link-gap/known-<profile>.json}): key -> reason. */
    static final class KnownIssues {
        static Map<String, String> load(Path file) throws IOException {
            Map<String, String> known = new HashMap<>();
            if (!Files.isRegularFile(file)) {
                return known;
            }
            String text = Files.readString(file);
            // A minimal reader for the fixed format: "reason": "...", "issues": [ "...", ... ].
            Matcher group = Pattern.compile("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"issues\"\\s*:\\s*\\[([^\\]]*)\\]")
                    .matcher(text);
            while (group.find()) {
                String reason = group.group(1);
                Matcher issue = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(group.group(2));
                while (issue.find()) {
                    known.put(issue.group(1), reason);
                }
            }
            return known;
        }

        /**
         * Whether the link-gap list knows the TeaVM problem of a usage entry, as the entry or
         * as one of the vanilla-owner forms of an inherited member (link-gap reports those on
         * the vanilla owner: {@code teavm-unresolved-method Owner.m()V (missing supertype X)}).
         */
        static boolean contains(Map<String, String> known, String problem, String entry, Set<String> aliases) {
            List<String> keys = new ArrayList<>();
            keys.add(key(problem, entry));
            for (String alias : aliases) {
                String body = alias.substring(2);
                String member = alias.startsWith("F ") ? "field" : "method";
                keys.add("teavm-missing-" + member + " " + body);
                keys.add("teavm-unresolved-" + member + " " + body);
            }
            for (String key : keys) {
                if (known.containsKey(key)) {
                    return true;
                }
                for (String candidate : known.keySet()) {
                    if (candidate.startsWith(key + " (")) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** The link-gap key of a TeaVM problem for a usage entry. */
        static String key(String problem, String entry) {
            String body = entry.substring(2);
            return switch (problem) {
                case "missing-class", "unsubstituted-class" -> "teavm-missing-class " + entryOwner(entry);
                case "missing-method" -> "teavm-missing-method " + body;
                case "missing-field" -> "teavm-missing-field " + body;
                case "unsupported-bootstrap" -> "teavm-unsupported-bootstrap " + body;
                default -> "teavm-" + problem + " " + body;
            };
        }
    }

    /** The JDK as TeaVM 0.15 links it: class library substitutes only. */
    static final class TeaVMView implements Resolution.Lookup {
        /** Classes TeaVM never loads for their bootstrap methods (JCLPlugin substitutes them). */
        static final Set<String> BOOTSTRAP_OWNERS = Set.of("java/lang/invoke/LambdaMetafactory",
                "java/lang/runtime/ObjectMethods", "java/lang/invoke/StringConcatFactory",
                "java/lang/runtime/SwitchBootstraps");

        final List<Root> roots;
        final Map<String, ClassModel> models = new HashMap<>();
        final Set<String> missing = new HashSet<>();

        TeaVMView(List<Root> roots) {
            this.roots = roots;
        }

        static boolean substituted(String name) {
            return name.startsWith("java/") || name.startsWith("javax/xml/");
        }

        static List<String> candidates(String name) {
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

        static String jdkName(String name) {
            if (name.startsWith("org/threeten/bp/")) {
                return "java/time/" + name.substring("org/threeten/bp/".length());
            }
            if (name.startsWith("org/teavm/classlib/java/")) {
                int slash = name.lastIndexOf('/');
                String simple = name.substring(slash + 1);
                if (simple.length() > 1 && simple.charAt(0) == 'T') {
                    return name.substring("org/teavm/classlib/".length(), slash + 1) + simple.substring(1);
                }
            }
            return name;
        }

        @Override
        public ClassModel model(String name) {
            if (name.startsWith("[")) {
                return model("java/lang/Object");
            }
            ClassModel cached = models.get(name);
            if (cached != null || missing.contains(name)) {
                return cached;
            }
            byte[] bytes = null;
            if (substituted(name)) {
                search:
                for (String candidate : candidates(name)) {
                    for (Root root : roots) {
                        try {
                            bytes = root.read(candidate);
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                        if (bytes != null) {
                            break search;
                        }
                    }
                }
            }
            if (bytes == null) {
                missing.add(name);
                return null;
            }
            ClassModel model = ClassModel.read(bytes, TeaVMView::jdkName, name);
            if (name.equals("java/lang/Object")) {
                model.superName = null;
            }
            models.put(name, model);
            return model;
        }

        /** The problem TeaVM has with one usage entry, or {@code null} when it links. */
        String check(String entry, String platform) {
            char kind = entry.charAt(0);
            String body = entry.substring(2);
            if (kind == 'C') {
                if (BOOTSTRAP_OWNERS.contains(body)) {
                    return null;
                }
                if (!substituted(body)) {
                    return "unsubstituted-class";
                }
                ClassModel model = model(body);
                if (model == null) {
                    return "missing-class";
                }
                return model.availableOn(platform) ? null : "unsupported-class";
            }
            String owner = entryOwner(entry);
            String rest = body.substring(owner.length() + 1);
            if (kind == 'M' && TEAVM_BOOTSTRAPS.contains(owner + "." + rest.substring(0, rest.indexOf('(')))) {
                return null;
            }
            if (!substituted(owner)) {
                return "unsubstituted-class";
            }
            ClassModel model = model(owner);
            if (model == null) {
                return "missing-class";
            }
            if (!model.availableOn(platform)) {
                return "unsupported-class";
            }
            if (kind == 'F') {
                int colon = rest.indexOf(':');
                String name = rest.substring(0, colon);
                String descriptor = rest.substring(colon + 1);
                if (owner.equals("java/lang/System") && Set.of("out", "err", "in").contains(name)) {
                    // SystemClassTransformer reads System.out/err/in through TSystem.out()/err()/in().
                    Member accessor = model.methods.get(name + "()" + descriptor);
                    return accessor != null && (accessor.access & Opcodes.ACC_STATIC) != 0 ? null : "missing-method";
                }
                Resolution field = Resolution.findField(this, model, name, descriptor);
                if (field == null) {
                    return Resolution.missingSupertype(this, model) == null ? "missing-field" : "unresolved-field";
                }
                return availability(field, platform);
            }
            int paren = rest.indexOf('(');
            String name = rest.substring(0, paren);
            String descriptor = rest.substring(paren);
            Resolution method = name.startsWith("<") ? Resolution.direct(model, name + descriptor)
                    : model.isInterface() ? Resolution.findInterfaceMethod(this, model, name, descriptor)
                    : Resolution.findClassMethod(this, model, name, descriptor);
            if (method == null) {
                return name.startsWith("<") || Resolution.missingSupertype(this, model) == null
                        ? "missing-method" : "unresolved-method";
            }
            return availability(method, platform);
        }

        private String availability(Resolution resolution, String platform) {
            if (!resolution.member().availableOn(platform)) {
                return "unsupported-member";
            }
            return resolution.owner().availableOn(platform) ? null : "unsupported-class";
        }
    }

    // ------------------------------------------------------------------------- ct.sym

    /**
     * The Java SE API of one release from the running JDK's {@code lib/ct.sym} (the data behind
     * {@code javac --release}): entries {@code <releases>/<module>/<class>.sig}, where
     * {@code <releases>} lists the release digits (base 36) the signature file applies to.
     */
    static final class CtSym implements AutoCloseable, Resolution.Lookup {
        final int release;
        final ZipFile zip;
        final Map<String, ZipEntry> entries = new HashMap<>();
        final Map<String, ClassModel> models = new HashMap<>();
        final Set<String> missingModels = new HashSet<>();

        private CtSym(int release, ZipFile zip) {
            this.release = release;
            this.zip = zip;
            char code = Character.toUpperCase(Character.forDigit(release, 36));
            var all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String path = entry.getName();
                if (!path.endsWith(".sig")) {
                    continue;
                }
                int first = path.indexOf('/');
                if (first < 0 || path.substring(0, first).indexOf(code) < 0) {
                    continue;
                }
                String rest = path.substring(first + 1);
                // Release 8 entries have no module directory.
                if (release > 8) {
                    int second = rest.indexOf('/');
                    rest = rest.substring(second + 1);
                }
                entries.put(rest.substring(0, rest.length() - ".sig".length()), entry);
            }
            if (entries.isEmpty()) {
                throw new IllegalStateException("ct.sym of " + System.getProperty("java.home")
                        + " has no API for Java " + release);
            }
        }

        static CtSym open(int release) throws IOException {
            Path file = Path.of(System.getProperty("java.home"), "lib", "ct.sym");
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("No " + file + "; run with a full JDK");
            }
            return new CtSym(release, new ZipFile(file.toFile()));
        }

        boolean has(String className) {
            return entries.containsKey(className);
        }

        byte[] read(String className) throws IOException {
            ZipEntry entry = entries.get(className);
            if (entry == null) {
                return null;
            }
            try (InputStream stream = zip.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }

        @Override
        public ClassModel model(String name) {
            if (models.containsKey(name) || missingModels.contains(name)) {
                return models.get(name);
            }
            byte[] bytes;
            try {
                bytes = read(name);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            if (bytes == null) {
                missingModels.add(name);
                return null;
            }
            ClassModel model = ClassModel.read(bytes, null, null);
            int nested = name.lastIndexOf('$');
            if (model.preview || (nested > 0 && model(name.substring(0, nested)) == null)) {
                // A preview API (or a member class of one) is not part of that release's API.
                missingModels.add(name);
                return null;
            }
            models.put(name, model);
            return model;
        }

        /** Whether this release's (non-preview) API has the usage entry. */
        boolean hasApi(String entry) {
            char kind = entry.charAt(0);
            String body = entry.substring(2);
            if (kind == 'C') {
                return model(body) != null;
            }
            String owner = entryOwner(entry);
            ClassModel model = model(owner);
            if (model == null) {
                return false;
            }
            String rest = body.substring(owner.length() + 1);
            if (kind == 'F') {
                int colon = rest.indexOf(':');
                return Resolution.findField(this, model, rest.substring(0, colon), rest.substring(colon + 1)) != null;
            }
            int paren = rest.indexOf('(');
            String name = rest.substring(0, paren);
            String descriptor = rest.substring(paren);
            Resolution resolution = name.startsWith("<") ? Resolution.direct(model, name + descriptor)
                    : model.isInterface() ? Resolution.findInterfaceMethod(this, model, name, descriptor)
                    : Resolution.findClassMethod(this, model, name, descriptor);
            return resolution != null;
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    // ------------------------------------------------------------------------- libraries

    /** The profile's library jars ({@code coordinate<TAB>path} lines), indexed by class. */
    static final class Libraries implements AutoCloseable {
        final Map<String, Root> roots = new LinkedHashMap<>();
        final Map<String, String> classes = new HashMap<>();
        final Map<String, Boolean> natives = new HashMap<>();

        static Libraries load(Path list) throws IOException {
            Libraries libraries = new Libraries();
            for (String line : Files.readAllLines(list)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                String coordinate = parts[0].strip();
                Root root = Root.open(Path.of(parts[1].strip()));
                libraries.roots.put(coordinate, root);
                for (String name : root.classNames()) {
                    libraries.classes.putIfAbsent(name, coordinate);
                }
            }
            return libraries;
        }

        String coordinateOf(String className) {
            return classes.get(className);
        }

        byte[] read(String className) throws IOException {
            String coordinate = classes.get(className);
            return coordinate == null ? null : roots.get(coordinate).read(className);
        }

        /** A native library: an LWJGL module, or a jar that declares native methods. */
        boolean isNative(String coordinate) {
            return natives.computeIfAbsent(coordinate, key -> {
                if (key.startsWith("org.lwjgl:")) {
                    return true;
                }
                Root root = roots.get(key);
                try {
                    for (String name : root.classNames()) {
                        boolean[] found = {false};
                        reader(root.read(name)).accept(new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                    String signature, String[] exceptions) {
                                if ((access & Opcodes.ACC_NATIVE) != 0) {
                                    found[0] = true;
                                }
                                return null;
                            }
                        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        if (found[0]) {
                            return true;
                        }
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                return false;
            });
        }

        @Override
        public void close() throws IOException {
            for (Root root : roots.values()) {
                root.close();
            }
        }
    }

    // ------------------------------------------------------------------------- api.lock

    private static int apiLockCommand(Options options) throws IOException {
        if (options.get("matches") != null) {
            return Range.matches(options.get("matches"), options.require("version")) ? 0 : 1;
        }
        Path jar = Path.of(options.require("jar"));
        try (Root library = Root.open(jar)) {
            if (options.get("write") != null) {
                Path lock = Path.of(options.get("write"));
                Set<String> classes = new TreeSet<>();
                for (String directory : options.all("classes")) {
                    try (Root overrides = Root.open(Path.of(directory))) {
                        classes.addAll(referencedLibraryClasses(overrides, library));
                    }
                }
                if (classes.isEmpty()) {
                    throw new IllegalStateException("the override classes reference no class of " + jar);
                }
                List<String> api = ApiSummary.of(library, classes);
                StringBuilder text = new StringBuilder();
                text.append("# Library API that ").append(options.require("origin"))
                        .append(" depends on: every class of the\n")
                        .append("# library that its override classes reference or replace, with its non-private"
                                + " members.\n")
                        .append("# Generated by port/tools/extrefs; do not edit.\n")
                        .append("# The range (the directory name) may select a library version only while that"
                                + " version's\n")
                        .append("# API of these classes is identical (extrefs.sh apilock <profile> --check);"
                                + " otherwise review\n")
                        .append("# the diff, adapt the override and regenerate (extrefs.sh apilock <profile>"
                                + " --write).\n")
                        .append("library ").append(options.require("coordinate")).append('\n')
                        .append("range ").append(options.require("range")).append('\n')
                        .append("verified ").append(options.require("verified")).append('\n')
                        .append("classes ").append(classes.size()).append('\n');
                for (String line : api) {
                    text.append(line).append('\n');
                }
                Files.writeString(lock, text, StandardCharsets.UTF_8);
                System.out.println("Wrote " + lock + ": " + classes.size() + " classes, " + api.size() + " lines");
                return 0;
            }
            Path lock = Path.of(options.require("check"));
            List<String> committed = new ArrayList<>();
            Set<String> classes = new TreeSet<>();
            String range = null;
            for (String line : Files.readAllLines(lock)) {
                if (line.startsWith("class ") || line.startsWith("  ")) {
                    committed.add(line);
                    if (line.startsWith("class ")) {
                        classes.add(line.split(" ")[1]);
                    }
                } else if (line.startsWith("range ")) {
                    range = line.substring("range ".length()).strip();
                }
            }
            if (range == null) {
                throw new IllegalStateException(lock + " has no range line");
            }
            String version = options.get("version");
            boolean selected = version == null || Range.matches(range, version);
            List<String> current = ApiSummary.of(library, classes);
            List<String> removed = new ArrayList<>(committed);
            removed.removeAll(new HashSet<>(current));
            List<String> added = new ArrayList<>(current);
            added.removeAll(new HashSet<>(committed));
            String subject = lock + " (range " + range + ", " + classes.size() + " classes) vs "
                    + jar.getFileName();
            if (removed.isEmpty() && added.isEmpty()) {
                System.out.println((selected ? "API_LOCK_OK " : "API_LOCK_UNSELECTED ") + subject
                        + (selected ? ": identical" : ": identical API, the range does not select " + version));
                return 0;
            }
            System.out.println((selected ? "API_LOCK_DIFF " : "API_LOCK_UNSELECTED ") + subject + ": "
                    + removed.size() + " lines gone, " + added.size() + " lines new"
                    + (selected ? "" : " (not selected: " + version + " is outside the range)"));
            if (selected) {
                for (String line : ApiSummary.mergeForDisplay(committed, current)) {
                    System.out.println(line);
                }
            }
            return selected ? 1 : 0;
        }
    }

    /**
     * Version ranges of override directories: an exact version ({@code 3.4.3}), a wildcard
     * ({@code 3.4.x} or {@code 3.4.*}) or a Maven interval ({@code [3.4.1,3.5)}, either end open).
     */
    static final class Range {
        static boolean matches(String range, String version) {
            String text = range.strip();
            if (text.startsWith("[") || text.startsWith("(")) {
                boolean lowInclusive = text.startsWith("[");
                boolean highInclusive = text.endsWith("]");
                String[] ends = text.substring(1, text.length() - 1).split(",", -1);
                if (ends.length != 2) {
                    throw new IllegalArgumentException("bad version range " + range);
                }
                String low = ends[0].strip();
                String high = ends[1].strip();
                if (!low.isEmpty()) {
                    int order = compare(version, low);
                    if (order < 0 || (order == 0 && !lowInclusive)) {
                        return false;
                    }
                }
                if (!high.isEmpty()) {
                    int order = compare(version, high);
                    if (order > 0 || (order == 0 && !highInclusive)) {
                        return false;
                    }
                }
                return true;
            }
            if (text.endsWith(".x") || text.endsWith(".*")) {
                return version.startsWith(text.substring(0, text.length() - 1));
            }
            return text.equals(version);
        }

        static int compare(String left, String right) {
            String[] a = left.split("[.-]");
            String[] b = right.split("[.-]");
            for (int index = 0; index < Math.max(a.length, b.length); index++) {
                String x = index < a.length ? a[index] : "0";
                String y = index < b.length ? b[index] : "0";
                int order = x.matches("\\d+") && y.matches("\\d+")
                        ? Long.compare(Long.parseLong(x), Long.parseLong(y)) : x.compareTo(y);
                if (order != 0) {
                    return order;
                }
            }
            return 0;
        }
    }

    /** Classes of {@code library} that the override classes name (or replace). */
    static Set<String> referencedLibraryClasses(Root overrides, Root library) throws IOException {
        Set<String> libraryClasses = new HashSet<>(library.classNames());
        Set<String> referenced = new TreeSet<>();
        for (String name : overrides.classNames()) {
            if (libraryClasses.contains(name)) {
                referenced.add(name);
            }
            for (String candidate : classNames(overrides.read(name))) {
                if (libraryClasses.contains(candidate)) {
                    referenced.add(candidate);
                }
            }
        }
        return referenced;
    }

    /** Every class a class file names: supertypes, descriptors, instructions, handles. */
    static Set<String> classNames(byte[] bytes) {
        Set<String> names = new HashSet<>();
        Usage sink = new Usage() {
            @Override
            void add(String section, String entry) {
                names.add(entryOwner(entry));
            }
        };
        World all = new World(null, Set.of(), null, null) {
            @Override
            boolean own(String name) {
                return false;
            }

            @Override
            String section(String name) {
                return JDK;
            }
        };
        reader(bytes).accept(new Recorder(all, sink, true).visitor(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return names;
    }

    /** Normalized, javap-like API lines of library classes. */
    static final class ApiSummary {
        static List<String> of(Root library, Set<String> classes) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String name : new TreeSet<>(classes)) {
                byte[] bytes = library.read(name);
                if (bytes == null) {
                    lines.add("class " + name + " absent");
                    continue;
                }
                List<String> members = new ArrayList<>();
                String[] header = new String[1];
                reader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String className, String signature,
                            String superName, String[] interfaces) {
                        List<String> parents = interfaces == null ? List.of() : List.of(interfaces);
                        header[0] = "class " + className + " " + flags(access, true)
                                + " extends " + superName
                                + (parents.isEmpty() ? "" : " implements " + String.join(",", new TreeSet<>(parents)));
                    }

                    @Override
                    public FieldVisitor visitField(int access, String fieldName, String descriptor,
                            String signature, Object value) {
                        if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == 0) {
                            members.add("  field " + flags(access, false) + " " + fieldName + ":" + descriptor
                                    + (value == null ? "" : " = " + value));
                        }
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                            String signature, String[] exceptions) {
                        if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0) {
                            members.add("  method " + flags(access, false) + " " + methodName + descriptor);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                lines.add(header[0]);
                members.sort(null);
                lines.addAll(members);
            }
            return lines;
        }

        static String flags(int access, boolean type) {
            List<String> flags = new ArrayList<>();
            flags.add((access & Opcodes.ACC_PUBLIC) != 0 ? "public"
                    : (access & Opcodes.ACC_PROTECTED) != 0 ? "protected" : "package");
            if (type) {
                flags.add((access & Opcodes.ACC_ANNOTATION) != 0 ? "@interface"
                        : (access & Opcodes.ACC_INTERFACE) != 0 ? "interface"
                        : (access & Opcodes.ACC_ENUM) != 0 ? "enum"
                        : (access & Opcodes.ACC_RECORD) != 0 ? "record" : "class");
                if ((access & Opcodes.ACC_ABSTRACT) != 0 && (access & Opcodes.ACC_INTERFACE) == 0) {
                    flags.add("abstract");
                }
                if ((access & Opcodes.ACC_FINAL) != 0) {
                    flags.add("final");
                }
                return String.join(" ", flags);
            }
            if ((access & Opcodes.ACC_STATIC) != 0) {
                flags.add("static");
            }
            if ((access & Opcodes.ACC_FINAL) != 0) {
                flags.add("final");
            }
            if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                flags.add("abstract");
            }
            if ((access & Opcodes.ACC_NATIVE) != 0) {
                flags.add("native");
            }
            return String.join(" ", flags);
        }

        /** The committed lines with "- " for lines gone and "+ " for new ones, grouped by class. */
        static List<String> mergeForDisplay(List<String> committed, List<String> current) {
            Map<String, List<String>> before = byClass(committed);
            Map<String, List<String>> after = byClass(current);
            Set<String> names = new TreeSet<>(before.keySet());
            names.addAll(after.keySet());
            List<String> out = new ArrayList<>();
            for (String name : names) {
                List<String> old = before.getOrDefault(name, List.of());
                List<String> now = after.getOrDefault(name, List.of());
                if (old.equals(now)) {
                    continue;
                }
                out.add("  " + name);
                for (String line : old) {
                    if (!now.contains(line)) {
                        out.add("    - " + line.strip());
                    }
                }
                for (String line : now) {
                    if (!old.contains(line)) {
                        out.add("    + " + line.strip());
                    }
                }
            }
            return out;
        }

        private static Map<String, List<String>> byClass(List<String> lines) {
            Map<String, List<String>> map = new TreeMap<>();
            String current = null;
            for (String line : lines) {
                if (line.startsWith("class ")) {
                    current = line.split(" ")[1];
                }
                map.computeIfAbsent(current, key -> new ArrayList<>()).add(line);
            }
            return map;
        }
    }

    // ------------------------------------------------------------------------- class roots

    /** A jar or a class directory. */
    abstract static class Root implements AutoCloseable {
        static Root open(Path path) throws IOException {
            if (Files.isDirectory(path)) {
                return new DirectoryRoot(path);
            }
            if (Files.isRegularFile(path)) {
                return new JarRoot(new ZipFile(path.toFile()));
            }
            throw new IOException("No such jar or directory: " + path);
        }

        abstract List<String> classNames() throws IOException;

        abstract byte[] read(String className) throws IOException;

        @Override
        public void close() throws IOException {
        }
    }

    static final class JarRoot extends Root {
        final ZipFile zip;

        JarRoot(ZipFile zip) {
            this.zip = zip;
        }

        @Override
        List<String> classNames() {
            List<String> names = new ArrayList<>();
            var all = zip.entries();
            while (all.hasMoreElements()) {
                String name = all.nextElement().getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class")) {
                    names.add(name.substring(0, name.length() - ".class".length()));
                }
            }
            return names;
        }

        @Override
        byte[] read(String className) throws IOException {
            ZipEntry entry = zip.getEntry(className + ".class");
            if (entry == null) {
                return null;
            }
            try (InputStream stream = zip.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    static final class DirectoryRoot extends Root {
        final Path directory;

        DirectoryRoot(Path directory) {
            this.directory = directory;
        }

        @Override
        List<String> classNames() throws IOException {
            List<String> names = new ArrayList<>();
            try (Stream<Path> files = Files.walk(directory)) {
                files.filter(file -> file.toString().endsWith(".class")).forEach(file -> {
                    String relative = directory.relativize(file).toString().replace('\\', '/');
                    if (!relative.startsWith("META-INF/") && !relative.endsWith("module-info.class")) {
                        names.add(relative.substring(0, relative.length() - ".class".length()));
                    }
                });
            }
            names.sort(null);
            return names;
        }

        @Override
        byte[] read(String className) throws IOException {
            Path file = directory.resolve(className + ".class");
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
    }

    // ASM 9.8 reads class files up to major 69 (Java 25); ct.sym of a newer JDK and newer class
    // files only need symbolic information, so parse a copy with the major version lowered.
    private static final int MAX_ASM_MAJOR = 69;

    static ClassReader reader(byte[] bytes) {
        int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        if (major <= MAX_ASM_MAJOR) {
            return new ClassReader(bytes);
        }
        byte[] copy = bytes.clone();
        copy[6] = (byte) (MAX_ASM_MAJOR >>> 8);
        copy[7] = (byte) MAX_ASM_MAJOR;
        return new ClassReader(copy);
    }
}
