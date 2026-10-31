package dev.gaius.tools.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.teavm.backend.wasm.TeaVMWasmGCHost;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmFunctionType;
import org.teavm.backend.wasm.model.WasmStructure;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.backend.wasm.model.instruction.WasmBlock;
import org.teavm.backend.wasm.model.instruction.WasmCastBranch;
import org.teavm.backend.wasm.model.instruction.WasmCastCondition;
import org.teavm.backend.wasm.model.instruction.WasmConditional;
import org.teavm.backend.wasm.model.instruction.WasmInstruction;
import org.teavm.backend.wasm.model.instruction.WasmInstructionBuilder;
import org.teavm.backend.wasm.model.instruction.WasmInstructionList;
import org.teavm.backend.wasm.model.instruction.WasmInt32Constant;
import org.teavm.backend.wasm.model.instruction.WasmReturn;
import org.teavm.backend.wasm.model.instruction.WasmTry;
import org.teavm.backend.wasm.model.instruction.WasmUnreachable;
import org.teavm.diagnostics.Problem;
import org.teavm.model.ClassHierarchy;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassReaderSource;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/**
 * JVM test of the TeaVM 0.15 WebAssembly GC code generation fixes in {@link GaiusTeaVMOptions}
 * (v0.4.0 M0 decisions 5.3, WP0.3).
 *
 * <ul>
 *   <li>{@link GaiusTeaVMOptions#wasmCatchBranch} is fail-closed: a catch clause whose struct
 *       lost its supertype chain is only dropped when its class is absent from the reachable
 *       class source; a reachable class fails the build.</li>
 *   <li>{@link GaiusTeaVMOptions#wasmSealFunctionBody} only appends {@code unreachable} to a
 *       value-returning body that ends with a block, if or try without a result.</li>
 *   <li>{@link GaiusTeaVMOptions#wasmDropUnreachableCatches}, end to end through a TeaVM
 *       WEBASSEMBLY_GC compile of a small probe: the class source of the hierarchy that
 *       WasmGCMethodGenerator holds is the reachable class set after the dependency analysis
 *       (a class file on the class path that nothing references reads as null), a catch of a
 *       class missing from the class path is dropped, and a catch of a class that is on the
 *       class path but only named by the catch clause is kept (TeaVM links catch types, so the
 *       class is reachable). The probe module is written to {@code <work>/wasm/classes.wasm} and
 *       its JVM output to {@code <work>/expected.txt}, for a node run of the module.</li>
 * </ul>
 *
 * <p>Classpath: the compiled port/tools classes, the teavm-core 0.15 jar patched by
 * TeaVMCoreBrowserPatcher (stock jar plus the patcher output), teavm-classlib 0.15 and the
 * TeaVM dependencies (teavm-tooling, teavm-interop, the relocated libraries, ...). Usage:
 * {@code java -Xmx3g GaiusTeaVMOptionsWasmTest <work-dir>}; the test recreates the probe-*
 * and wasm subdirectories of the work directory. Exits with status 1 on the first failed
 * check.
 */
public final class GaiusTeaVMOptionsWasmTest {
    private static final String PROBE_PACKAGE = "wasmprobe";
    private static final String PROBE_SOURCE = """
            package wasmprobe;

            public final class Probe {
                static int calls;

                /** Its class file is deleted before TeaVM runs (as UnsatisfiedLinkError is missing). */
                static final class MissingError extends RuntimeException {
                }

                /** On the class path, but only named by a catch clause. */
                static final class CatchOnlyError extends RuntimeException {
                }

                static final class ThrownError extends RuntimeException {
                    ThrownError(String message) {
                        super(message);
                    }
                }

                private Probe() {
                }

                static void catchMissing(String event) {
                    try {
                        calls += event.length();
                    } catch (MissingError e) {
                        calls -= 100;
                    }
                }

                static void catchOnly(String event) {
                    try {
                        calls += event.length();
                    } catch (CatchOnlyError e) {
                        calls -= 1000;
                    }
                }

                static String catchThrown(int k) {
                    try {
                        if (k > 1) {
                            throw new ThrownError("t" + k);
                        }
                        return "none";
                    } catch (ThrownError e) {
                        return "caught " + e.getMessage();
                    }
                }

                public static void main(String[] args) {
                    catchMissing("ab");
                    catchOnly("cde");
                    System.out.println("calls=" + calls);
                    System.out.println(catchThrown(1));
                    System.out.println(catchThrown(2));
                }
            }

            /** On the class path, never referenced. */
            final class Unreferenced {
            }
            """;

    private static int checks;
    /** The hierarchy of the WebAssembly GC code generation, captured by {@link HierarchySpy}. */
    static volatile ClassHierarchy capturedHierarchy;

    private GaiusTeaVMOptionsWasmTest() {
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        catchBranchFailClosed();
        sealFunctionBody();
        dropUnreachableCatches(work);
        System.out.println("GaiusTeaVMOptionsWasmTest passed: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            System.err.println("FAILED: " + message);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ wasmCatchBranch

    /** Stands in for WasmGCInstructionGenerationVisitor: wasmCatchBranch reads its context. */
    static final class StubVisitor {
        private final StubContext context;

        StubVisitor(Function<String, ClassHolder> classes) {
            ClassReaderSource source = classes::apply;
            context = new StubContext(new ClassHierarchy(source));
        }
    }

    /** Stands in for WasmGCGenerationContext. */
    public static final class StubContext {
        private final ClassHierarchy hierarchy;

        StubContext(ClassHierarchy hierarchy) {
            this.hierarchy = hierarchy;
        }

        public ClassHierarchy hierarchy() {
            return hierarchy;
        }
    }

    private static void catchBranchFailClosed() {
        WasmStructure object = new WasmStructure("java.lang.Object");
        WasmStructure throwable = new WasmStructure("java.lang.Throwable");
        throwable.setSupertype(object);
        WasmBlock handler = new WasmBlock(false);
        StubVisitor unreachable = new StubVisitor(name -> null);
        StubVisitor reachable = new StubVisitor(ClassHolder::new);

        // A class missing from the reachable class source: the clause is dropped.
        WasmStructure missing = new WasmStructure("test.MissingError");
        WasmInstructionList list = new WasmInstructionList();
        WasmInstructionBuilder builder = list.builder();
        Object result = GaiusTeaVMOptions.wasmCatchBranch(builder, WasmCastCondition.SUCCESS,
                throwable.getReference(), missing.getReference(), handler.getBody(), unreachable,
                "test.MissingError");
        check(result == builder, "dropped clause returns the builder");
        check(list.isEmpty(), "dropped clause emits no br_on_cast");
        check(missing.getSupertype() == object, "dropped clause struct becomes an Object subtype");

        // A reachable class whose struct lost its supertype chain: the build fails.
        WasmStructure broken = new WasmStructure("test.ReachableError");
        WasmInstructionList brokenList = new WasmInstructionList();
        String failure = null;
        try {
            GaiusTeaVMOptions.wasmCatchBranch(brokenList.builder(), WasmCastCondition.SUCCESS,
                    throwable.getReference(), broken.getReference(), handler.getBody(), reachable,
                    "test.ReachableError");
        } catch (IllegalStateException e) {
            failure = e.getMessage();
        }
        check(failure != null && failure.contains("test.ReachableError"),
                "reachable class without a supertype chain fails the build: " + failure);
        check(brokenList.isEmpty(), "failed clause emits nothing");
        check(broken.getSupertype() == null, "failed clause leaves the struct unchanged");

        // No class name: treated as reachable (fail-closed).
        boolean failedWithoutName = false;
        try {
            GaiusTeaVMOptions.wasmCatchBranch(new WasmInstructionList().builder(), WasmCastCondition.SUCCESS,
                    throwable.getReference(), new WasmStructure("test.Unnamed").getReference(),
                    handler.getBody(), unreachable, null);
        } catch (IllegalStateException e) {
            failedWithoutName = true;
        }
        check(failedWithoutName, "clause without a class name fails the build");

        // A regular clause (the struct extends Throwable): the br_on_cast is emitted, whatever
        // the class source says.
        WasmStructure regular = new WasmStructure("test.RegularError");
        regular.setSupertype(throwable);
        WasmInstructionList regularList = new WasmInstructionList();
        WasmInstructionBuilder regularBuilder = regularList.builder();
        regularBuilder.typeInference.typeStack.add(throwable.getReference());
        Object regularResult = GaiusTeaVMOptions.wasmCatchBranch(regularBuilder, WasmCastCondition.SUCCESS,
                throwable.getReference(), regular.getReference(), handler.getBody(), reachable,
                "test.RegularError");
        check(regularResult == regularBuilder, "regular clause returns the builder");
        check(regularList.getLast() instanceof WasmCastBranch cast
                && cast.getTargetType() == regular.getReference(), "regular clause emits br_on_cast");
    }

    // ------------------------------------------------------------------ wasmSealFunctionBody

    private static int size(WasmInstructionList list) {
        int count = 0;
        for (WasmInstruction ignored : list) {
            count++;
        }
        return count;
    }

    private static WasmFunction function(WasmType result, WasmInstruction... body) {
        WasmFunction function = new WasmFunction(new WasmFunctionType("f", result, List.of()));
        for (WasmInstruction instruction : body) {
            function.getBody().add(instruction);
        }
        return function;
    }

    private static void sealFunctionBody() {
        WasmFunction block = function(WasmType.INT32, new WasmBlock(false));
        GaiusTeaVMOptions.wasmSealFunctionBody(block);
        check(size(block.getBody()) == 2 && block.getBody().getLast() instanceof WasmUnreachable,
                "value function ending with a block without a result is sealed");

        WasmFunction conditional = function(WasmType.INT32, new WasmInt32Constant(1), new WasmConditional());
        GaiusTeaVMOptions.wasmSealFunctionBody(conditional);
        check(conditional.getBody().getLast() instanceof WasmUnreachable,
                "value function ending with an if without a result is sealed");

        WasmFunction legacyTry = function(WasmType.INT32, new WasmTry());
        GaiusTeaVMOptions.wasmSealFunctionBody(legacyTry);
        check(legacyTry.getBody().getLast() instanceof WasmUnreachable,
                "value function ending with a try without a result is sealed");

        WasmFunction returns = function(WasmType.INT32, new WasmBlock(false), new WasmReturn());
        GaiusTeaVMOptions.wasmSealFunctionBody(returns);
        check(size(returns.getBody()) == 2 && returns.getBody().getLast() instanceof WasmReturn,
                "value function ending with return is left as it is");

        WasmFunction value = function(WasmType.INT32, new WasmInt32Constant(7));
        GaiusTeaVMOptions.wasmSealFunctionBody(value);
        check(size(value.getBody()) == 1, "value function ending with a value is left as it is");

        WasmBlock typed = new WasmBlock(false);
        typed.setType(WasmType.INT32.asBlock());
        WasmFunction typedBlock = function(WasmType.INT32, typed);
        GaiusTeaVMOptions.wasmSealFunctionBody(typedBlock);
        check(size(typedBlock.getBody()) == 1, "value function ending with a block with a result is left as it is");

        WasmTry typedTry = new WasmTry();
        typedTry.setType(WasmType.INT32);
        WasmFunction typedTryFunction = function(WasmType.INT32, typedTry);
        GaiusTeaVMOptions.wasmSealFunctionBody(typedTryFunction);
        check(size(typedTryFunction.getBody()) == 1, "value function ending with a try with a result is left as it is");

        WasmFunction voidBlock = function(null, new WasmBlock(false));
        GaiusTeaVMOptions.wasmSealFunctionBody(voidBlock);
        check(size(voidBlock.getBody()) == 1, "void function is left as it is");

        WasmFunction empty = function(WasmType.INT32);
        GaiusTeaVMOptions.wasmSealFunctionBody(empty);
        check(empty.getBody().isEmpty(), "empty body is left as it is");
    }

    // ------------------------------------------------------------------ wasmDropUnreachableCatches

    /**
     * Captures the hierarchy of the WebAssembly GC code generation context. WasmGCTarget builds
     * that context from WasmGCDeclarationsGenerator.hierarchy, the ClassHierarchy object it also
     * passes to WasmGCMethodGenerator (whose field wasmDropUnreachableCatches reads).
     */
    public static final class HierarchySpy implements TeaVMPlugin {
        @Override
        public void install(TeaVMHost host) {
            TeaVMWasmGCHost wasm = host.getExtension(TeaVMWasmGCHost.class);
            if (wasm != null) {
                wasm.contributeToCodeGen((context, registry) -> capturedHierarchy = context.hierarchy());
            }
        }
    }

    private static void dropUnreachableCatches(Path work) throws Exception {
        Path sources = work.resolve("probe-src");
        Path full = work.resolve("probe-classes-full");
        Path classes = work.resolve("probe-classes");
        Path services = work.resolve("probe-services");
        Path wasm = work.resolve("wasm");
        for (Path dir : List.of(sources, full, classes, services, wasm)) {
            deleteTree(dir);
            Files.createDirectories(dir);
        }
        Path source = sources.resolve(PROBE_PACKAGE).resolve("Probe.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, PROBE_SOURCE, StandardCharsets.UTF_8);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        check(javac != null, "a JDK compiler is available");
        for (Path output : List.of(full, classes)) {
            int status = javac.run(null, null, null, "--release", "21", "-proc:none", "-d",
                    output.toString(), source.toString());
            check(status == 0, "probe compiles");
        }
        Path missing = classes.resolve(PROBE_PACKAGE).resolve("Probe$MissingError.class");
        Files.delete(missing);
        check(Files.isRegularFile(classes.resolve(PROBE_PACKAGE).resolve("Unreferenced.class")),
                "Unreferenced.class is on the class path");

        // JVM reference output (with every class present).
        String expected;
        try (URLClassLoader jvm = new URLClassLoader(new URL[] {full.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            expected = captureOut(() -> {
                try {
                    jvm.loadClass(PROBE_PACKAGE + ".Probe").getMethod("main", String[].class)
                            .invoke(null, (Object) new String[0]);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        Files.writeString(work.resolve("expected.txt"), expected.replace("\r\n", "\n"),
                StandardCharsets.UTF_8);

        Path serviceFile = services.resolve("META-INF/services/" + TeaVMPlugin.class.getName());
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, HierarchySpy.class.getName() + "\n", StandardCharsets.UTF_8);
        capturedHierarchy = null;
        List<Problem> problems = new ArrayList<>();
        String log;
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL(), services.toUri().toURL()},
                GaiusTeaVMOptionsWasmTest.class.getClassLoader())) {
            check(loader.getResource(PROBE_PACKAGE + "/Probe$MissingError.class") == null,
                    "MissingError is missing from the TeaVM class path");
            check(loader.getResource(PROBE_PACKAGE + "/Unreferenced.class") != null,
                    "Unreferenced is on the TeaVM class path");
            TeaVMTool tool = new TeaVMTool();
            tool.setTargetType(TeaVMTargetType.WEBASSEMBLY_GC);
            tool.setTargetDirectory(wasm.toFile());
            tool.setTargetFileName("classes.wasm");
            tool.setMainClass(PROBE_PACKAGE + ".Probe");
            tool.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE);
            tool.setObfuscated(false);
            tool.setStrict(false);
            tool.setIncremental(false);
            tool.setClassLoader(loader);
            log = captureOut(() -> {
                try {
                    tool.generate();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            problems.addAll(tool.getProblemProvider().getSevereProblems());
        }
        for (Problem problem : problems) {
            System.err.println("TeaVM: " + problem.getText() + " " + java.util.Arrays.toString(problem.getParams()));
        }
        check(problems.isEmpty(), "probe compiles to WebAssembly GC without errors");
        check(Files.size(wasm.resolve("classes.wasm")) > 0, "classes.wasm written");

        check(log.contains("[gaius-teavm] wasm: dropped catch clauses of unreachable classes in "
                + PROBE_PACKAGE + ".Probe.catchMissing(Ljava/lang/String;)V"),
                "the catch of the missing class is dropped from the program");
        check(!log.contains(".Probe.catchOnly("), "the catch of the class on the class path is kept");
        check(!log.contains(".Probe.catchThrown("), "the catch of the thrown class is kept");
        check(!log.contains("dropped the catch clause of unreachable class"),
                "the visitor fallback (wasmCatchBranch) is not reached");

        ClassHierarchy hierarchy = capturedHierarchy;
        check(hierarchy != null, "the code generation hierarchy was captured");
        ClassReaderSource reachable = hierarchy.getClassSource();
        check(reachable.get(PROBE_PACKAGE + ".Probe") != null, "the probe is in the reachable class source");
        check(reachable.get(PROBE_PACKAGE + ".Probe$CatchOnlyError") != null,
                "a class only named by a catch clause is reachable (TeaVM links catch types)");
        check(reachable.get(PROBE_PACKAGE + ".Probe$MissingError") == null,
                "a class missing from the class path reads as null");
        check(reachable.get(PROBE_PACKAGE + ".Unreferenced") == null,
                "a class on the class path that nothing reaches reads as null: the class source is "
                        + "the reachable set after the dependency analysis, not the class path");
        System.out.println("probe module: " + wasm.resolve("classes.wasm") + ", expected output: "
                + work.resolve("expected.txt"));
    }

    private static String captureOut(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream tee = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                captured.write(b);
                original.write(b);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) {
                captured.write(bytes, offset, length);
                original.write(bytes, offset, length);
            }
        }, true, StandardCharsets.UTF_8);
        System.setOut(tee);
        try {
            body.run();
        } finally {
            tee.flush();
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(path);
            }
        }
    }
}
