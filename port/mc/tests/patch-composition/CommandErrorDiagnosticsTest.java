import dev.gaius.tools.PatchTargets;
import dev.gaius.tools.patches.network.NetworkPatches;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import sun.misc.Unsafe;

/** Executes the actual vanilla/patched performCommand body with explicit boundary doubles. */
public final class CommandErrorDiagnosticsTest implements Opcodes {
    private static final String OWNER = "net/minecraft/commands/Commands";
    private static final String SOURCE = "net/minecraft/commands/CommandSourceStack";
    private static final String HOOK = "dev/gaius/browser/BrowserIntegratedServerMain";
    private static final String SELF = "CommandErrorDiagnosticsTest";
    private static final List<String> trace = new ArrayList<>();
    private static final List<Throwable> reports = new ArrayList<>();
    private static final List<String> failures = new ArrayList<>();
    private static Throwable executeFailure, reportFailure, conversionFailure, popFailure, parseFailure;
    private static Throwable thrownDuringExecute;
    private static boolean freshFailure;
    private static Object chain, profiler;
    private static boolean debug;
    public static boolean verbose, ide;
    private static int checks;

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
    public static Object context() throws Throwable {
        trace.add("parse");
        if (parseFailure != null) throw parseFailure;
        return chain;
    }
    public static Object profiler() { return profiler; }
    public static void execute(Object source, Consumer<?> callback) throws Throwable {
        trace.add("execute");
        try {
            thrownDuringExecute = freshFailure ? new IllegalStateException("fresh in callee") : executeFailure;
            if (thrownDuringExecute != null) throw thrownDuringExecute;
        }
        finally { trace.add("execute-finally"); }
    }
    public static void report(Throwable failure) throws Throwable {
        trace.add("report"); reports.add(failure);
        if (reportFailure != null) throw reportFailure;
    }
    public static void failure(Object component) throws Throwable {
        trace.add("convert"); failures.add(component.toString());
        if (conversionFailure != null) throw conversionFailure;
    }
    private static ClassNode read(String jar) throws Exception {
        try (var zip = new ZipFile(jar); var in = zip.getInputStream(zip.getEntry(OWNER + ".class"))) {
            var node = new ClassNode();
            new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static byte[] bytes(ClassNode node) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer); return writer.toByteArray();
    }
    private static ClassNode empty(String name, int version) {
        var node = new ClassNode(); node.version = version; node.access = ACC_PUBLIC | ACC_SUPER;
        node.name = name; node.superName = "java/lang/Object"; return node;
    }
    private static MethodNode simple(ClassNode node, String name, String desc) {
        var method = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        node.methods.add(method); return method;
    }
    private static void constructor(ClassNode node) {
        var init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(RETURN)); node.methods.add(init);
    }

    private static final class FixtureLoader extends ClassLoader {
        final Map<String, byte[]> definitions = new HashMap<>();
        FixtureLoader(ClassNode input) {
            super(CommandErrorDiagnosticsTest.class.getClassLoader());
            var node = empty(OWNER, input.version); constructor(node);
            node.sourceFile = input.sourceFile;
            node.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "LOGGER", "Lorg/slf4j/Logger;", null, null));
            for (var original : input.methods) {
                if (!original.name.equals("performCommand")
                        && !original.name.startsWith("lambda$performCommand$")) continue;
                var copy = new MethodNode(original.access, original.name, original.desc,
                        original.signature, original.exceptions.toArray(String[]::new));
                original.accept(copy);
                // The execution boundary deliberately never invokes the command callback.
                if (copy.name.startsWith("lambda$") && Type.getReturnType(copy.desc).equals(Type.VOID_TYPE)) {
                    copy.instructions.clear(); copy.tryCatchBlocks.clear(); copy.localVariables = null;
                    copy.instructions.add(new InsnNode(RETURN));
                }
                for (var instruction : copy.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/util/profiling/Profiler")
                            && call.name.equals("get")) call.owner = "gaius/tests/CommandProfiler";
                    if (instruction instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC
                            && (field.name.equals("DEBUG_VERBOSE_COMMAND_ERRORS") || field.name.equals("IS_RUNNING_IN_IDE"))) {
                        field.owner = SELF; field.name = field.name.equals("IS_RUNNING_IN_IDE") ? "ide" : "verbose";
                    }
                }
                node.methods.add(copy);
            }
            var execute = method(input, "executeCommandInContext");
            var fakeExecute = simple(node, execute.name, execute.desc);
            fakeExecute.instructions.add(new VarInsnNode(ALOAD, 0));
            fakeExecute.instructions.add(new VarInsnNode(ALOAD, 1));
            fakeExecute.instructions.add(new MethodInsnNode(INVOKESTATIC, SELF, "execute", "(Ljava/lang/Object;Ljava/util/function/Consumer;)V", false));
            fakeExecute.instructions.add(new InsnNode(RETURN));
            var parse = method(input, "finishParsing");
            var fakeParse = simple(node, parse.name, parse.desc);
            fakeParse.instructions.add(new MethodInsnNode(INVOKESTATIC, SELF, "context", "()Ljava/lang/Object;", false));
            fakeParse.instructions.add(new TypeInsnNode(CHECKCAST, Type.getReturnType(parse.desc).getInternalName()));
            fakeParse.instructions.add(new InsnNode(ARETURN));
            add(node);
            var source = empty(SOURCE, input.version); constructor(source);
            var send = new MethodNode(ACC_PUBLIC, "sendFailure", "(Lnet/minecraft/network/chat/Component;)V", null, null);
            send.instructions.add(new VarInsnNode(ALOAD, 1));
            send.instructions.add(new MethodInsnNode(INVOKESTATIC, SELF, "failure", "(Ljava/lang/Object;)V", false));
            send.instructions.add(new InsnNode(RETURN)); source.methods.add(send); add(source);
            var hook = empty(HOOK, input.version);
            var report = simple(hook, "reportCommandFailure", "(Ljava/lang/Throwable;)V");
            report.instructions.add(new VarInsnNode(ALOAD, 0));
            report.instructions.add(new MethodInsnNode(INVOKESTATIC, SELF, "report", report.desc, false));
            report.instructions.add(new InsnNode(RETURN)); add(hook);
            var clock = empty("gaius/tests/CommandProfiler", input.version);
            var get = simple(clock, "get", "()Lnet/minecraft/util/profiling/ProfilerFiller;");
            get.instructions.add(new MethodInsnNode(INVOKESTATIC, SELF, "profiler", "()Ljava/lang/Object;", false));
            get.instructions.add(new TypeInsnNode(CHECKCAST, "net/minecraft/util/profiling/ProfilerFiller"));
            get.instructions.add(new InsnNode(ARETURN)); add(clock);
        }
        void add(ClassNode node) { definitions.put(node.name.replace('/', '.'), bytes(node)); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                if (loaded == null && definitions.containsKey(name)) {
                    byte[] bytes = definitions.get(name); loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (loaded == null) loaded = super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    private record Outcome(List<String> trace, List<String> failures, List<Throwable> reports, Throwable escaped) {}
    private static Outcome run(ClassNode input) throws Exception {
        trace.clear(); failures.clear(); reports.clear();
        var loader = new FixtureLoader(input);
        var type = loader.loadClass(OWNER.replace('/', '.'));
        var loggerType = Class.forName("org.slf4j.Logger");
        type.getField("LOGGER").set(null, Proxy.newProxyInstance(loggerType.getClassLoader(), new Class<?>[]{loggerType}, (p, m, a) -> {
            if (m.getName().equals("isDebugEnabled")) return debug;
            if (m.getName().equals("error")) trace.add("logger.error");
            return m.getReturnType() == boolean.class ? false : null;
        }));
        var filler = Class.forName("net.minecraft.util.profiling.ProfilerFiller");
        profiler = Proxy.newProxyInstance(filler.getClassLoader(), new Class<?>[]{filler}, (p, m, a) -> {
            if (m.getName().equals("push")) { trace.add("push"); if (a[0] instanceof java.util.function.Supplier<?> s) s.get(); }
            if (m.getName().equals("pop")) { trace.add("pop"); if (popFailure != null) throw popFailure; }
            return null;
        });
        var source = loader.loadClass(SOURCE.replace('/', '.')).getConstructor().newInstance();
        var dispatcher = Class.forName("com.mojang.brigadier.CommandDispatcher").getConstructor().newInstance();
        var root = Class.forName("com.mojang.brigadier.tree.RootCommandNode").getConstructor().newInstance();
        var builderType = Class.forName("com.mojang.brigadier.context.CommandContextBuilder");
        var builder = builderType.getConstructors()[0].newInstance(dispatcher, source, root, 0);
        var readerType = Class.forName("com.mojang.brigadier.StringReader");
        var reader = readerType.getConstructor(String.class).newInstance("fixture");
        var parsedType = Class.forName("com.mojang.brigadier.ParseResults");
        var parsedConstructor = java.util.Arrays.stream(parsedType.getConstructors())
                .filter(c -> c.getParameterCount() == 3).findFirst().orElseThrow();
        var parsed = parsedConstructor.newInstance(builder, reader, Map.of());
        Throwable escaped = null;
        try { type.getMethod("performCommand", parsedType, String.class).invoke(type.getConstructor().newInstance(), parsed, "fixture"); }
        catch (InvocationTargetException failure) { escaped = failure.getCause(); }
        return new Outcome(List.copyOf(trace), List.copyOf(failures), List.copyOf(reports), escaped);
    }

    private static void compare(ClassNode raw, ClassNode patched, String label, boolean reportsExpected) throws Exception {
        var before = run(raw); var after = run(patched);
        check(after.trace.stream().filter(s -> !s.equals("report")).toList().equals(before.trace), label + " vanilla trace/finally/pop");
        check(after.failures.equals(before.failures), label + " vanilla Exception conversion");
        check(after.escaped == before.escaped, label + " escaped Throwable identity");
        check(after.reports.size() == (reportsExpected ? 1 : 0), label + " report count");
        if (reportsExpected) check(after.reports.getFirst() == thrownDuringExecute, label + " reported identity");
        System.out.println("PASS " + label + " trace=" + after.trace + " reports=" + after.reports.size());
    }

    private static void structural(ClassNode raw, ClassNode patched) {
        var outer = method(patched, "performCommand");
        var original = method(raw, "performCommand");
        check(outer.tryCatchBlocks.size() == original.tryCatchBlocks.size() + 2, "only two narrow diagnostic handlers added");
        var inner = outer.tryCatchBlocks.removeFirst();
        var reporting = outer.tryCatchBlocks.removeFirst();
        check("java/lang/Throwable".equals(inner.type) && "java/lang/Throwable".equals(reporting.type), "diagnostics catch execution and reporting failures first");
        var execute = inner.start.getNext();
        while (execute.getOpcode() < 0) execute = execute.getNext();
        check(execute instanceof MethodInsnNode call && call.name.equals("executeCommandInContext"), "original execution call is unchanged");
        var jump = inner.end.getNext();
        while (jump.getOpcode() < 0) jump = jump.getNext();
        var normal = ((JumpInsnNode) jump).label;
        var rethrow = normal.getPrevious();
        while (rethrow.getOpcode() < 0) rethrow = rethrow.getPrevious();
        check(rethrow.getOpcode() == ATHROW, "inline handler rethrows the saved failure");
        for (var block : outer.tryCatchBlocks) {
            int start = outer.instructions.indexOf(block.start), end = outer.instructions.indexOf(block.end);
            int callAt = outer.instructions.indexOf(execute), throwAt = outer.instructions.indexOf(rethrow);
            check((start <= callAt && callAt < end) == (start <= throwAt && throwAt < end), "original handler coverage is identical for invocation and rethrow");
        }
        // ASM may merge the new normal label with vanilla's original range-end label.
        // Keep boundary labels; only executable instrumentation contributes to the hash.
        for (AbstractInsnNode cursor = inner.end; cursor != normal;) {
            var next = cursor.getNext(); outer.instructions.remove(cursor);
            cursor = next;
        }
        check(PatchTargets.methodHash(original).equals(PatchTargets.methodHash(outer)), "removing only instrumentation restores exact vanilla body and exception table");
        for (var method : raw.methods) if (!method.name.equals("performCommand")) {
            var target = patched.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc)).findFirst().orElseThrow();
            check(PatchTargets.methodHash(method).equals(PatchTargets.methodHash(target)), "other method unchanged " + method.name);
        }
    }

    private static void rejectedShapes(String jar, Path root) throws Exception {
        for (String shape : List.of("missing", "duplicate", "nonstatic", "already-instrumented", "unprotected")) {
            var node = read(jar);
            var outer = method(node, "performCommand");
            var call = java.util.Arrays.stream(outer.instructions.toArray())
                    .filter(i -> i instanceof MethodInsnNode m && m.name.equals("executeCommandInContext"))
                    .map(i -> (MethodInsnNode) i).findFirst().orElseThrow();
            switch (shape) {
                case "missing" -> outer.instructions.remove(call);
                case "duplicate" -> outer.instructions.insert(call, new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
                case "nonstatic" -> call.setOpcode(INVOKEVIRTUAL);
                case "already-instrumented" -> outer.instructions.insertBefore(call,
                        new MethodInsnNode(INVOKESTATIC, HOOK, "reportCommandFailure", "(Ljava/lang/Throwable;)V", false));
                case "unprotected" -> outer.tryCatchBlocks.clear();
                default -> throw new AssertionError(shape);
            }
            Path fixture = root.resolve(shape + ".jar"), output = root.resolve(shape + ".class");
            // These deliberately invalid bytecodes must be rejected before frame computation.
            var writer = new ClassWriter(0); node.accept(writer);
            try (var zip = new ZipOutputStream(Files.newOutputStream(fixture))) {
                zip.putNextEntry(new ZipEntry(OWNER + ".class")); zip.write(writer.toByteArray()); zip.closeEntry();
            }
            boolean rejected = false;
            try { NetworkPatches.patchCommandErrorDiagnostics(fixture.toString(), output); }
            catch (java.io.IOException expected) { rejected = true; }
            check(rejected && !Files.exists(output), "fail closed before output: " + shape);
        }
    }

    public static void main(String[] args) throws Exception {
        var root = Path.of(args[1]); Files.createDirectories(root);
        var file = root.resolve("Commands.class");
        NetworkPatches.patchCommandErrorDiagnostics(args[0], file);
        ClassNode raw = read(args[0]);
        ClassNode patched = dev.gaius.tools.patches.support.ClientPatchSupport.read(file);
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Object nonNull = ((Unsafe) field.get(null)).allocateInstance(Class.forName("com.mojang.brigadier.context.ContextChain"));
        chain = nonNull;
        compare(raw, patched, "success", false);
        chain = null; compare(raw, patched, "no-context", false); chain = nonNull;
        for (Throwable throwable : List.of(new IllegalStateException("command failure"), new RuntimeException(),
                new Exception("checked"), new AssertionError("error"), new LinkageError("linkage"), new Throwable("bare"))) {
            executeFailure = throwable;
            compare(raw, patched, throwable.getClass().getSimpleName(), true);
            for (Throwable diagnostic : List.of(new RuntimeException("report fails"), new AssertionError("report fails"))) {
                reportFailure = diagnostic; compare(raw, patched, throwable.getClass().getSimpleName() + "/broken-report", true);
            }
            reportFailure = null;
        }
        executeFailure = new IllegalArgumentException("conversion");
        debug = true; verbose = true; ide = true; compare(raw, patched, "verbose-debug-conversion", true);
        executeFailure = null; freshFailure = true;
        compare(raw, patched, "fresh-callee-exception-stack", true);
        freshFailure = false; executeFailure = new IllegalArgumentException("conversion");
        debug = false; verbose = false; ide = false;
        conversionFailure = new AssertionError("conversion failed"); compare(raw, patched, "conversion-finally", true); conversionFailure = null;
        for (Throwable pop : List.of(new RuntimeException("pop"), new AssertionError("pop"))) {
            popFailure = pop; compare(raw, patched, "pop-masks-command", true);
            executeFailure = null; compare(raw, patched, "pop-after-success", false);
            executeFailure = new AssertionError("original");
        }
        popFailure = null; executeFailure = null;
        parseFailure = new IllegalArgumentException("parse before vanilla try"); compare(raw, patched, "parse-outside-try", false); parseFailure = null;
        structural(read(args[0]), PatchTargets.read(Files.readAllBytes(file)));
        rejectedShapes(args[0], root);
        System.out.println("COMMAND_DIAGNOSTICS_COMPOSITION_PASS checks=" + checks + " jar=" + args[0]);
    }
}
