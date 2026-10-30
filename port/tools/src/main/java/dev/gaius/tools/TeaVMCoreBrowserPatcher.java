package dev.gaius.tools;

import dev.gaius.tools.runtime.GaiusTeaVMOptions;
import dev.gaius.tools.runtime.GaiusWasmMath;
import dev.gaius.tools.runtime.GaiusWasmResourceChunks;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Patches TeaVM 0.15 core for the browser build.
 *
 * <ul>
 * <li>ClassInfoGenerator: reflection metadata generation skips a class-value type that is
 * absent.</li>
 * <li>InMemoryVirtualFileSystem.getFile restores persisted files on demand.</li>
 * <li>Role-level compiler options ({@link GaiusTeaVMOptions}): the TeaVM plugin
 * {@code <properties>} written by generate-pom.sh reach the compiler through the patched
 * {@code TeaVM.getPlatformTags} and {@code Renderer.setProperties}. They drive
 * syncClinits (class-initialization edges stay synchronous), syncMonitors (the
 * java.lang.Object monitor primitives are compiled synchronously), the async family barrier
 * (virtual families such as toString/equals/hashCode stop propagating async-ness), runtime
 * name pinning (TeaVM runtime functions keep top-level bindings past maxTopLevelNames) and
 * the telemetry platform tag.</li>
 * <li>thread.js: a suspension inside a method that those options compiled synchronously
 * throws instead of corrupting the coroutine stack.</li>
 * <li>JSPI mode (gaius.teavm.jspi, WebAssembly GC only): AsyncMethodFinder.find stops with
 * an empty async set, so no method is compiled as a coroutine, and each dynamic class
 * initializer gets a guard with the JVM's class initialization semantics (another thread
 * waits while the initializing one is suspended in it).</li>
 * <li>WebAssembly GC code generation (TeaVM 0.15 emits invalid wasm otherwise): catch clauses
 * of classes the dependency analysis never reached are removed from the program, and a value-returning body that
 * ends with a block without a result gets a closing {@code unreachable}; class-path resource
 * reads honour the array offset (WasmGCResources.readBytes) and the InputStream contract for
 * the range and for an empty read (ResourceInputStream.read).</li>
 * <li>Heap.alloc (Wasm GC off-heap memory) also grows by the record header when the last
 * record is allocated (TeaVM 0.16 fix; skipped when the TeaVM version already has it).</li>
 * <li>TypeSet (WebAssembly GC only): the dependency nodes of a shared type set iterate in
 * insertion order instead of identity-hash order, so two compiles of the same sources write
 * the same module.</li>
 * <li>WasmGCIntrinsics (WebAssembly GC only): Math and StrictMath floor, ceil, sqrt, rint, abs,
 * min and max become single WebAssembly instructions ({@link GaiusWasmMath}) instead of calls
 * into JavaScript's Math or branchy Java formulas.</li>
 * <li>Async census (gaius.teavm.asyncDump, reporting only): AsyncMethodFinder records why each
 * method became async and writes the census when the analysis ends; it is shared by the
 * JavaScript and WebAssembly GC backends.</li>
 * </ul>
 *
 * <p>Every patch locates its bytecode or text anchor exactly and throws when the TeaVM shape
 * differs; the patched jar only exists for TeaVM 0.15. Where TeaVM 0.16 carries the same fix
 * (the ClassInfoGenerator null guard, Heap.alloc), the patch detects it and leaves the class
 * as it is.
 */
public final class TeaVMCoreBrowserPatcher {
    private static final String CLASS =
            "org/teavm/backend/javascript/intrinsics/reflection/ClassInfoGenerator.class";
    private static final String VFS_CLASS =
            "org/teavm/runtime/fs/memory/InMemoryVirtualFileSystem.class";
    private static final String ASYNC_FINDER_CLASS = "org/teavm/model/util/AsyncMethodFinder";
    private static final String RENDERER_CLASS = "org/teavm/backend/javascript/rendering/Renderer";
    private static final String WASM_GC_VISITOR =
            "org/teavm/backend/wasm/generate/methods/WasmGCInstructionGenerationVisitor";
    private static final String WASM_GC_METHOD_GENERATOR =
            "org/teavm/backend/wasm/generate/methods/WasmGCMethodGenerator";
    private static final String WASM_GC_RESOURCES = "org/teavm/backend/wasm/runtime/WasmGCResources";
    private static final String WASM_GC_RESOURCES_INTRINSIC =
            "org/teavm/backend/wasm/intrinsics/WasmGCResourcesIntrinsic";
    private static final String WASM_GC_RESOURCE_STREAM = WASM_GC_RESOURCES + "$ResourceInputStream";
    private static final String SOURCE_WRITER = "org/teavm/backend/javascript/codegen/SourceWriter";
    private static final String TEAVM_CLASS = "org/teavm/vm/TeaVM";
    private static final String MINIFYING_ALIASES =
            "org/teavm/backend/javascript/codegen/MinifyingAliasProvider";
    private static final String DEFAULT_ALIASES =
            "org/teavm/backend/javascript/codegen/DefaultAliasProvider";
    private static final String SCOPED_NAME = "org/teavm/backend/javascript/codegen/ScopedName";
    private static final String RENDERING_UTIL =
            "org/teavm/backend/javascript/rendering/RenderingUtil";
    private static final String THREAD_JS = "org/teavm/backend/javascript/thread.js";
    private static final String OPTIONS = "dev/gaius/tools/runtime/GaiusTeaVMOptions";
    private static final String HEAP_CLASS = "org/teavm/runtime/heap/Heap";
    private static final String HEAP_RECORD = "org/teavm/runtime/heap/HeapRecord";
    private static final String STRUCTURE = "org/teavm/interop/Structure";
    private static final String TYPE_SET = "org/teavm/dependency/TypeSet";
    private static final String WASM_GC_INTRINSICS = "org/teavm/backend/wasm/intrinsics/WasmGCIntrinsics";
    private static final String WASM_MATH = "dev/gaius/tools/runtime/GaiusWasmMath";
    /** Helper classes the patched TeaVM classes call; copied into the patched jar. */
    private static final List<String> RUNTIME_HELPERS = List.of(OPTIONS);
    /** Helpers only the WebAssembly GC patches call (the JavaScript jar stays without them). */
    private static final List<String> WASM_GC_RUNTIME_HELPERS =
            List.of(WASM_MATH, GaiusWasmResourceChunks.class.getName().replace('.', '/'));
    private static final String PINNED_NAME_METHOD = "gaiusPinnedTopLevelName";
    private static final String PINNED_SUFFIX_FIELD = "gaiusPinnedSuffix";

    /**
     * Role option (POM property, see {@link GaiusTeaVMOptions#SYNC_CLINITS}): a class
     * initializer that TeaVM compiles as async no longer makes every method that triggers the
     * class initialization async. In the Worker that edge alone (Mth.<clinit> reaches
     * Util.make, and Util.<clinit> is async) turned 36k of 83k methods, including every
     * worldgen kernel, into coroutines. The class initializer itself stays async; the
     * rendered call is wrapped in the globalThis.__gaiusClinitDepth counter so that
     * TModernRuntimeSupport.yieldToEventLoop and TLockSupport do not suspend inside it,
     * because its caller may now be synchronous.
     */
    static final String SYNC_CLINITS_PROPERTY = GaiusTeaVMOptions.SYNC_CLINITS;
    static final String CLINIT_DEPTH_OPEN =
            "globalThis.__gaiusClinitDepth=(globalThis.__gaiusClinitDepth|0)+1;try{";
    static final String CLINIT_DEPTH_CLOSE = "}finally{globalThis.__gaiusClinitDepth--;}";

    static final String THREAD_SUSPEND_ANCHOR = """
            TeaVMThread.prototype.suspend = function(callback) {
                this.suspendCallback = callback;
                this.status = 1;
            };""";
    /**
     * A method that the async barrier or syncMonitors compiled synchronously keeps
     * globalThis.__gaiusNoSuspendDepth positive while it runs. Reaching a real suspension
     * there would leave its frame out of the saved coroutine stack, so the suspension fails
     * fast with the stack of the offending call instead.
     *
     * <p>TeaVM wraps the thrown JS Error into a java.lang.RuntimeException, so Java code that
     * catches Exception or Throwable (CompletableFuture, tick and reload loops) can swallow
     * it. The guard therefore also counts the violation in
     * globalThis.__gaiusNoSuspendViolations, keeps the last stack in
     * __gaiusLastNoSuspendViolation and logs the first ones to the console; the browser
     * acceptance scripts fail on either (tools/teavm-runtime-guards.mjs).
     *
     * <p>A suspension inside a class initializer that syncClinits rendered behind the
     * globalThis.__gaiusClinitDepth counter is only counted
     * (__gaiusClinitSuspensions, first stack in __gaiusFirstClinitSuspension) and logged once
     * as a warning: it is valid when the initializer was triggered from an async caller and
     * corrupts the coroutine stack ("Invalid recorded state") when the caller is synchronous,
     * which the runtime cannot tell apart.
     */
    static final String THREAD_SUSPEND_GUARDED = """
            TeaVMThread.prototype.suspend = function(callback) {
                if ((globalThis.__gaiusNoSuspendDepth | 0) > 0) {
                    let gaiusError = new teavm_globals.Error("Gaius: a TeaVM suspension was "
                        + "reached inside a method that the role build options (async barrier "
                        + "or syncMonitors) compiled synchronously. Under syncMonitors this "
                        + "means a monitor was held across a suspension and another green "
                        + "thread waited for it. Remove the method's family from "
                        + "gaius.teavm.asyncBarrier or disable gaius.teavm.syncMonitors for "
                        + "this role.");
                    globalThis.__gaiusNoSuspendViolations =
                        (globalThis.__gaiusNoSuspendViolations | 0) + 1;
                    globalThis.__gaiusLastNoSuspendViolation =
                        teavm_globals.String(gaiusError.stack || gaiusError);
                    if (globalThis.__gaiusNoSuspendViolations <= 8
                            && typeof teavm_globals.console === "object") {
                        teavm_globals.console.error(gaiusError);
                    }
                    throw gaiusError;
                }
                if ((globalThis.__gaiusClinitDepth | 0) > 0) {
                    globalThis.__gaiusClinitSuspensions =
                        (globalThis.__gaiusClinitSuspensions | 0) + 1;
                    if (globalThis.__gaiusClinitSuspensions === 1) {
                        let gaiusNotice = new teavm_globals.Error("Gaius: a TeaVM suspension "
                            + "was reached inside a class initializer. It is only safe when "
                            + "the initializer was triggered from an async caller "
                            + "(gaius.teavm.syncClinits); an 'Invalid recorded state' error "
                            + "or a half-initialized class after this points here.");
                        globalThis.__gaiusFirstClinitSuspension =
                            teavm_globals.String(gaiusNotice.stack || gaiusNotice);
                        if (typeof teavm_globals.console === "object") {
                            teavm_globals.console.warn(gaiusNotice);
                        }
                    }
                }
                this.suspendCallback = callback;
                this.status = 1;
            };""";

    private TeaVMCoreBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path jarPath = Path.of(args[0]);
        Path output = Path.of(args[1]);
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            try (var stream = jar.getInputStream(jar.getEntry(CLASS))) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("writeSimpleConstructors"))
                .findFirst()
                .orElseThrow();

        VarInsnNode classLoad = null;
        LabelNode continueLabel = null;
        boolean sawClassLookup = false;
        boolean alreadyGuarded = false;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("org/teavm/model/ListableClassReaderSource")
                    && call.name.equals("get")) {
                sawClassLookup = true;
            } else if (sawClassLookup
                    && instruction instanceof VarInsnNode variable
                    && variable.getOpcode() == Opcodes.ALOAD
                    && variable.var == 10) {
                classLoad = variable;
                sawClassLookup = false;
                // TeaVM 0.16 skips a missing class itself (if (cls == null || ...) continue).
                alreadyGuarded = nextReal(variable) instanceof JumpInsnNode jump
                        && jump.getOpcode() == Opcodes.IFNULL;
            } else if (instruction instanceof IincInsnNode increment
                    && increment.var == 7
                    && increment.incr == 1) {
                continueLabel = new LabelNode();
                method.instructions.insertBefore(increment, continueLabel);
                break;
            }
        }
        if (classLoad == null || continueLabel == null) {
            throw new IllegalStateException("TeaVM ClassInfoGenerator patch point not found");
        }
        if (!alreadyGuarded) {
            InsnList nullGuard = new InsnList();
            nullGuard.add(new VarInsnNode(Opcodes.ALOAD, 10));
            nullGuard.add(new JumpInsnNode(Opcodes.IFNULL, continueLabel));
            method.instructions.insertBefore(classLoad, nullGuard);
        }

        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());

        Path patchRoot = output;
        for (int index = 0; index < Path.of(CLASS).getNameCount(); index++) {
            patchRoot = patchRoot.getParent();
        }
        byte[] vfsBytes;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            try (var stream = jar.getInputStream(jar.getEntry(VFS_CLASS))) {
                vfsBytes = stream.readAllBytes();
            }
        }
        ClassNode vfsNode = new ClassNode();
        new ClassReader(vfsBytes).accept(vfsNode, 0);
        MethodNode getFile = vfsNode.methods.stream()
                .filter(candidate -> candidate.name.equals("getFile")
                        && candidate.desc.equals("(Ljava/lang/String;)Lorg/teavm/runtime/fs/VirtualFile;"))
                .findFirst()
                .orElseThrow();
        InsnList restoreHook = new InsnList();
        restoreHook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        restoreHook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserFilePersistence",
                "restoreOnDemand",
                "(Ljava/lang/String;)Z",
                false));
        restoreHook.add(new InsnNode(Opcodes.POP));
        getFile.instructions.insert(restoreHook);

        ClassWriter vfsWriter = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        vfsNode.accept(vfsWriter);
        Path vfsOutput = patchRoot.resolve(VFS_CLASS);
        Files.createDirectories(vfsOutput.getParent());
        Files.write(vfsOutput, vfsWriter.toByteArray());

        writePatched(patchRoot, ASYNC_FINDER_CLASS,
                patchAsyncMethodFinder(readClass(jarPath, ASYNC_FINDER_CLASS)));
        ClassNode renderer = readClass(jarPath, RENDERER_CLASS);
        patchRendererClinitDepth(renderer);
        patchRendererOptions(renderer);
        patchRendererSyncGuard(renderer);
        writePatched(patchRoot, RENDERER_CLASS, renderer);
        writePatched(patchRoot, TEAVM_CLASS,
                patchTeaVMPlatformTags(readClass(jarPath, TEAVM_CLASS)));
        writePatched(patchRoot, MINIFYING_ALIASES,
                patchMinifyingAliasProvider(readClass(jarPath, MINIFYING_ALIASES)));
        writePatched(patchRoot, DEFAULT_ALIASES,
                patchDefaultAliasProvider(readClass(jarPath, DEFAULT_ALIASES)));
        writeText(patchRoot, THREAD_JS, patchThreadRuntime(readText(jarPath, THREAD_JS)));
        writePatchedKeepFrames(patchRoot, WASM_GC_VISITOR,
                patchWasmGcCatchBranch(readClass(jarPath, WASM_GC_VISITOR)));
        writePatchedKeepFrames(patchRoot, WASM_GC_METHOD_GENERATOR,
                patchWasmGcClinitGuard(patchWasmGcFunctionSeal(readClass(jarPath, WASM_GC_METHOD_GENERATOR))));
        ClassNode resources = patchWasmGcResourceRead(readClass(jarPath, WASM_GC_RESOURCES));
        if (BuildTarget.wasmGc()) {
            writeResourcePatch(jarPath, patchRoot, WASM_GC_RESOURCES, patchWasmGcResourceRuntime(resources));
        } else {
            writePatchedKeepFrames(patchRoot, WASM_GC_RESOURCES, resources);
        }
        writePatchedKeepFrames(patchRoot, WASM_GC_RESOURCE_STREAM,
                patchWasmGcResourceStreamRead(readClass(jarPath, WASM_GC_RESOURCE_STREAM)));
        ClassNode heap = readClass(jarPath, HEAP_CLASS);
        if (patchHeapAllocGrowth(heap)) {
            writePatched(patchRoot, HEAP_CLASS, heap);
        }
        if (BuildTarget.wasmGc()) {
            writeResourcePatch(jarPath, patchRoot, WASM_GC_RESOURCES_INTRINSIC,
                    patchWasmGcResourceIntrinsic(readClass(jarPath, WASM_GC_RESOURCES_INTRINSIC)));
            String dependency = "org/teavm/backend/wasm/dependencies/WasmGCResourceDependency";
            writeResourcePatch(jarPath, patchRoot, dependency,
                    patchWasmGcResourceDependency(readClass(jarPath, dependency)));
            ClassNode typeSet = readClass(jarPath, TYPE_SET);
            if (patchTypeSetDomainOrder(typeSet)) {
                writePatched(patchRoot, TYPE_SET, typeSet);
            }
            writePatchedKeepFrames(patchRoot, WASM_GC_INTRINSICS,
                    patchWasmGcMathIntrinsics(readClass(jarPath, WASM_GC_INTRINSICS)));
        }
        copyRuntimeHelpers(patchRoot);
    }

    private static ClassNode readClass(Path jarPath, String internalName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(internalName + ".class");
            if (entry == null) {
                throw new IllegalStateException(internalName + " not found in " + jarPath);
            }
            try (var stream = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(stream.readAllBytes()).accept(node, 0);
                return node;
            }
        }
    }

    private static String readText(Path jarPath, String name) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(name);
            if (entry == null) {
                throw new IllegalStateException(name + " not found in " + jarPath);
            }
            try (var stream = jar.getInputStream(entry)) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static void writePatched(Path patchRoot, String internalName, ClassNode node)
            throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path output = patchRoot.resolve(internalName + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    /** Rewritten control flow needs fresh frames resolved against the input TeaVM jar. */
    private static void writeResourcePatch(Path jarPath, Path patchRoot, String name, ClassNode node)
            throws IOException {
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {jarPath.toUri().toURL()},
                TeaVMCoreBrowserPatcher.class.getClassLoader())) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String left, String right) {
                    try {
                        Class<?> a = Class.forName(left.replace('/', '.'), false, loader);
                        Class<?> b = Class.forName(right.replace('/', '.'), false, loader);
                        if (a.isAssignableFrom(b)) return left;
                        if (b.isAssignableFrom(a)) return right;
                        if (a.isInterface() || b.isInterface()) return "java/lang/Object";
                        do {
                            a = a.getSuperclass();
                        } while (!a.isAssignableFrom(b));
                        return a.getName().replace('.', '/');
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Cannot resolve TeaVM frame types " + left + ", " + right, e);
                    }
                }
            };
            node.accept(writer);
            Path output = patchRoot.resolve(name + ".class");
            Files.createDirectories(output.getParent());
            Files.write(output, writer.toByteArray());
        }
    }

    private static void clearRewrittenMetadata(MethodNode method) {
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
    }

    /**
     * For patches that keep the stack shape at every instruction (a call replaced by a call with
     * the same stack effect, stack-neutral insertions): the original stack map frames stay valid,
     * so they are kept instead of recomputed (recomputing would load TeaVM classes).
     */
    private static void writePatchedKeepFrames(Path patchRoot, String internalName, ClassNode node)
            throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path output = patchRoot.resolve(internalName + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    /**
     * WebAssembly GC (TeaVM 0.15): the {@code br_on_cast} of each catch clause in
     * {@code visit(TryCatchStatement)} goes through {@link GaiusTeaVMOptions#wasmCatchBranch},
     * which drops the clauses of classes the dependency analysis never reached (their struct
     * type has no supertype chain, so the cast from Throwable would not validate) and fails the
     * build when the class is reachable. The call also passes the visitor (its generation context
     * holds the reachable class source) and the local that holds the clause's class name: the
     * one {@code getExceptionType()} is stored to (java.lang.Throwable when null), which is also
     * the argument of the {@code getClassInfo} call that yields the cast target type.
     */
    static ClassNode patchWasmGcCatchBranch(ClassNode node) {
        MethodNode visit = method(node, "visit", "(Lorg/teavm/ast/TryCatchStatement;)V");
        String builder = "org/teavm/backend/wasm/model/instruction/WasmInstructionBuilder";
        String desc = "(Lorg/teavm/backend/wasm/model/instruction/WasmCastCondition;"
                + "Lorg/teavm/backend/wasm/model/WasmType$Reference;"
                + "Lorg/teavm/backend/wasm/model/WasmType$Reference;"
                + "Lorg/teavm/backend/wasm/model/instruction/WasmInstructionList;)"
                + "Lorg/teavm/backend/wasm/model/instruction/WasmInstructionBuilder;";
        MethodInsnNode castBranch = null;
        for (AbstractInsnNode instruction : visit.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(builder)
                    && call.name.equals("castBranch") && call.desc.equals(desc)) {
                if (castBranch != null) {
                    throw new IllegalStateException("TeaVM try/catch castBranch found twice");
                }
                castBranch = call;
            }
        }
        if (castBranch == null || !(castBranch.getNext() instanceof InsnNode pop)
                || pop.getOpcode() != Opcodes.POP) {
            throw new IllegalStateException("TeaVM try/catch castBranch patch point not found");
        }
        int exceptionType = catchTypeLocal(visit, castBranch);
        InsnList context = new InsnList();
        context.add(new VarInsnNode(Opcodes.ALOAD, 0));
        context.add(new VarInsnNode(Opcodes.ALOAD, exceptionType));
        visit.instructions.insertBefore(castBranch, context);
        visit.instructions.set(castBranch, options("wasmCatchBranch",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"));
        return node;
    }

    /**
     * The local that holds the catch clause's class name at {@code castBranch}: the single
     * {@code TryCatchStatement.getExceptionType()} result stored by the next ASTORE, read by the
     * single {@code WasmGCClassInfoProvider.getClassInfo(String)} call between that store and
     * {@code castBranch}; the only other store to it in between is the java.lang.Throwable
     * default.
     */
    private static int catchTypeLocal(MethodNode visit, AbstractInsnNode castBranch) {
        AbstractInsnNode typeCall = single(visit, "TryCatchStatement.getExceptionType call",
                insn -> insn instanceof MethodInsnNode call && call.owner.equals("org/teavm/ast/TryCatchStatement")
                        && call.name.equals("getExceptionType"));
        if (!(nextReal(typeCall) instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
            throw new IllegalStateException("TeaVM try/catch exception type store not found");
        }
        int local = store.var;
        boolean typeLookup = false;
        for (AbstractInsnNode insn = store.getNext(); insn != castBranch; insn = insn.getNext()) {
            if (insn == null) {
                throw new IllegalStateException("TeaVM try/catch castBranch precedes the exception type");
            }
            if (insn instanceof VarInsnNode other && other.var == local && other.getOpcode() == Opcodes.ASTORE
                    && !(previousReal(other) instanceof LdcInsnNode constant
                            && "java.lang.Throwable".equals(constant.cst))) {
                throw new IllegalStateException("TeaVM try/catch exception type local is reassigned");
            }
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("org/teavm/backend/wasm/generate/classes/WasmGCClassInfoProvider")
                    && call.name.equals("getClassInfo") && call.desc.equals(
                            "(Ljava/lang/String;)Lorg/teavm/backend/wasm/generate/classes/WasmGCClassInfo;")) {
                if (typeLookup || !(previousReal(call) instanceof VarInsnNode argument)
                        || argument.getOpcode() != Opcodes.ALOAD || argument.var != local) {
                    throw new IllegalStateException("TeaVM try/catch class info lookup does not match");
                }
                typeLookup = true;
            }
        }
        if (!typeLookup) {
            throw new IllegalStateException("TeaVM try/catch class info lookup not found");
        }
        return local;
    }

    /**
     * WebAssembly GC (TeaVM 0.15), {@code generateRegularMethodBody}: first
     * {@link GaiusTeaVMOptions#wasmDropUnreachableCatches} removes the catch clauses of classes
     * the dependency analysis never reached (their handlers do not validate); after the body is
     * generated, {@link GaiusTeaVMOptions#wasmSealFunctionBody} appends {@code unreachable} when
     * a value-returning body ends with a block without a result (invalid wasm otherwise).
     */
    static ClassNode patchWasmGcFunctionSeal(ClassNode node) {
        MethodNode generate = method(node, "generateRegularMethodBody",
                "(Lorg/teavm/model/MethodHolder;Lorg/teavm/backend/wasm/model/WasmFunction;)V");
        MethodInsnNode wrap = null;
        for (AbstractInsnNode instruction : generate.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(WASM_GC_METHOD_GENERATOR)
                    && call.name.equals("wrapSynchronizedMethod")) {
                if (wrap != null) {
                    throw new IllegalStateException("TeaVM wrapSynchronizedMethod call found twice");
                }
                wrap = call;
            }
        }
        if (wrap == null) {
            throw new IllegalStateException("TeaVM generateRegularMethodBody patch point not found");
        }
        InsnList seal = new InsnList();
        seal.add(new VarInsnNode(Opcodes.ALOAD, 2));
        seal.add(options("wasmSealFunctionBody", "(Ljava/lang/Object;)V"));
        generate.instructions.insert(wrap, seal);
        // Before anything reads the program: drop the catch clauses of unreachable classes.
        InsnList prune = new InsnList();
        prune.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prune.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prune.add(options("wasmDropUnreachableCatches", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        generate.instructions.insert(prune);
        return node;
    }

    /**
     * WebAssembly GC (TeaVM 0.15), {@code generateRegularMethodBody}: its
     * {@code wrapSynchronizedMethod(method, visitor, function, node)} call goes through
     * {@link GaiusTeaVMOptions#wasmWrapMethodBody} instead, which runs the stock method for
     * every method but the dynamic class initializers of a JSPI build: those get the guard of
     * the JVM's class initialization semantics (design D3). The static call takes the receiver
     * and the four arguments as Objects, so the stack shape and the frames stay as they are.
     * Applied after {@link #patchWasmGcFunctionSeal}, which anchors on the same call.
     */
    static ClassNode patchWasmGcClinitGuard(ClassNode node) {
        MethodNode generate = method(node, "generateRegularMethodBody",
                "(Lorg/teavm/model/MethodHolder;Lorg/teavm/backend/wasm/model/WasmFunction;)V");
        String desc = "(Lorg/teavm/model/MethodHolder;"
                + "Lorg/teavm/backend/wasm/generate/methods/WasmGCInstructionGenerationVisitor;"
                + "Lorg/teavm/backend/wasm/model/WasmFunction;Lorg/teavm/ast/RegularMethodNode;)V";
        AbstractInsnNode wrap = single(generate, "wrapSynchronizedMethod call",
                insn -> insn instanceof MethodInsnNode call && call.owner.equals(WASM_GC_METHOD_GENERATOR)
                        && call.name.equals("wrapSynchronizedMethod") && call.desc.equals(desc));
        if (wrap.getOpcode() != Opcodes.INVOKESPECIAL && wrap.getOpcode() != Opcodes.INVOKEVIRTUAL) {
            throw new IllegalStateException("TeaVM wrapSynchronizedMethod is not an instance call");
        }
        method(node, "addInitializerErase",
                "(Lorg/teavm/model/MethodReader;Lorg/teavm/backend/wasm/model/WasmFunction;)V");
        requireField(node, "classInitInfo", "Lorg/teavm/model/analysis/ClassInitializerInfo;");
        generate.instructions.set(wrap, options("wasmWrapMethodBody",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/Object;)V"));
        return node;
    }

    /** Populate the original resource map from bounded descriptor arrays. */
    static ClassNode patchWasmGcResourceRuntime(ClassNode node) {
        MethodNode acquire = method(node, "acquireResources", "()[L"
                + WASM_GC_RESOURCES + "$Resource;");
        acquire.desc = "()[[L" + WASM_GC_RESOURCES + "$Resource;";
        MethodNode clinit = method(node, "<clinit>", "()V");
        String resource = WASM_GC_RESOURCES + "$Resource";
        String map = "Ljava/util/Map;";
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, WASM_GC_RESOURCES, "resources", map));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WASM_GC_RESOURCES, "acquireResources",
                "()[[L" + resource + ";", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));
        LabelNode outerTest = new LabelNode();
        LabelNode outerEnd = new LabelNode();
        LabelNode innerTest = new LabelNode();
        LabelNode innerEnd = new LabelNode();
        code.add(outerTest);
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, outerEnd));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(innerTest);
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, innerEnd));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, WASM_GC_RESOURCES, "resources", map));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, resource, "name", "Ljava/lang/String;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new IincInsnNode(5, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, innerTest));
        code.add(innerEnd);
        code.add(new IincInsnNode(2, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, outerTest));
        code.add(outerEnd);
        code.add(new InsnNode(Opcodes.RETURN));
        clearRewrittenMetadata(clinit);
        clinit.instructions = code;
        return node;
    }

    static ClassNode patchWasmGcResourceIntrinsic(ClassNode node) {
        String resource = "L" + WASM_GC_RESOURCES + "$Resource;";
        MethodNode acquire = method(node, "acquireResources", "(Lorg/teavm/backend/wasm/model/instruction/WasmInstructionBuilder;)V");
        int patchedTypes = 0;
        for (AbstractInsnNode insn = acquire.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof org.objectweb.asm.Type type
                    && type.getDescriptor().equals(resource)
                    && nextReal(ldc) instanceof MethodInsnNode call
                    && call.owner.equals("org/teavm/model/ValueType") && call.name.equals("parse")) {
                // allocateArray takes the element type; create(String, int, int) still returns Resource.
                ldc.cst = org.objectweb.asm.Type.getType("[" + resource);
                patchedTypes++;
            }
        }
        if (patchedTypes != 1) {
            throw new IllegalStateException("TeaVM resource array element type patch point count: " + patchedTypes);
        }
        MethodNode lambda = method(node, "lambda$acquireResources$0",
                "(Ljava/util/ArrayList;Lorg/teavm/backend/wasm/model/WasmFunction;"
                        + "Lorg/teavm/backend/wasm/model/WasmArray;"
                        + "Lorg/teavm/backend/wasm/model/instruction/WasmInstructionBuilder;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "genContext",
                "Lorg/teavm/backend/wasm/intrinsics/WasmGCCodeGenContext;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "org/teavm/backend/wasm/intrinsics/WasmGCCodeGenContext", "classInfoProvider",
                "()Lorg/teavm/backend/wasm/generate/classes/WasmGCClassInfoProvider;", true));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "genContext",
                "Lorg/teavm/backend/wasm/intrinsics/WasmGCCodeGenContext;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "org/teavm/backend/wasm/intrinsics/WasmGCCodeGenContext", "strings",
                "()Lorg/teavm/backend/wasm/generate/strings/WasmGCStringProvider;", true));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "baseGlobal",
                "Lorg/teavm/backend/wasm/model/WasmGlobal;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/tools/runtime/GaiusWasmResourceChunks", "emit",
                "(Lorg/teavm/backend/wasm/generate/classes/WasmGCClassInfoProvider;"
                        + "Lorg/teavm/backend/wasm/generate/strings/WasmGCStringProvider;"
                        + "Lorg/teavm/backend/wasm/model/WasmGlobal;Ljava/util/ArrayList;"
                        + "Lorg/teavm/backend/wasm/model/WasmFunction;Lorg/teavm/backend/wasm/model/WasmArray;"
                        + "Lorg/teavm/backend/wasm/model/instruction/WasmInstructionBuilder;)V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        clearRewrittenMetadata(lambda);
        lambda.instructions = code;
        return node;
    }

    /** Keep create reachable and propagate both dimensions of the chunked resource table. */
    static ClassNode patchWasmGcResourceDependency(ClassNode node) {
        String resource = "L" + WASM_GC_RESOURCES + "$Resource;";
        MethodNode clinit = method(node, "<clinit>", "()V");
        int signatures = 0;
        for (AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof org.objectweb.asm.Type type
                    && type.getDescriptor().equals("[" + resource)) {
                ldc.cst = org.objectweb.asm.Type.getType("[[" + resource);
                signatures++;
            }
        }
        MethodNode reached = method(node, "methodReached", "(Lorg/teavm/dependency/DependencyAgent;"
                + "Lorg/teavm/dependency/MethodDependency;)V");
        AbstractInsnNode arrayOf = single(reached, "resource array type",
                insn -> insn instanceof MethodInsnNode call && call.owner.equals("org/teavm/model/ValueType")
                        && call.name.equals("arrayOf"));
        reached.instructions.insert(arrayOf, new MethodInsnNode(Opcodes.INVOKESTATIC,
                "org/teavm/model/ValueType", "arrayOf", "(Lorg/teavm/model/ValueType;)"
                        + "Lorg/teavm/model/ValueType$Array;", false));
        AbstractInsnNode item = single(reached, "resource array item",
                insn -> insn instanceof MethodInsnNode call && call.name.equals("getArrayItem"));
        InsnList inner = new InsnList();
        inner.add(new InsnNode(Opcodes.DUP));
        inner.add(new VarInsnNode(Opcodes.ALOAD, 1));
        inner.add(new LdcInsnNode(WASM_GC_RESOURCES.replace('/', '.') + "$Resource"));
        inner.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/teavm/model/ValueType", "object",
                "(Ljava/lang/String;)Lorg/teavm/model/ValueType$Object;", false));
        inner.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/teavm/model/ValueType", "arrayOf",
                "(Lorg/teavm/model/ValueType;)Lorg/teavm/model/ValueType$Array;", false));
        inner.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/teavm/dependency/DependencyAgent", "getType",
                "(Lorg/teavm/model/ValueType;)Lorg/teavm/dependency/DependencyType;", false));
        inner.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/teavm/dependency/DependencyNode", "propagate",
                "(Lorg/teavm/dependency/DependencyType;)V", false));
        inner.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/teavm/dependency/DependencyNode", "getArrayItem",
                "()Lorg/teavm/dependency/DependencyNode;", false));
        reached.instructions.insert(item, inner);
        if (signatures != 1) throw new IllegalStateException("TeaVM resource dependency signature count: " + signatures);
        return node;
    }

    /** Correct TeaVM's resource read destination index from i to off + i. */
    static ClassNode patchWasmGcResourceRead(ClassNode node) {
        MethodNode read = method(node, "readBytes", "([BIII)V");
        for (AbstractInsnNode instruction : read.instructions) {
            if (instruction instanceof VarInsnNode array && array.getOpcode() == Opcodes.ALOAD
                    && array.var == 0 && array.getNext() instanceof VarInsnNode index
                    && index.getOpcode() == Opcodes.ILOAD && index.var == 4) {
                InsnList offset = new InsnList();
                offset.add(new VarInsnNode(Opcodes.ILOAD, 1));
                offset.add(new InsnNode(Opcodes.IADD));
                read.instructions.insert(index, offset);
                return node;
            }
        }
        throw new IllegalStateException("TeaVM WasmGCResources.readBytes patch point not found");
    }

    /**
     * WebAssembly GC runtime (TeaVM 0.15): {@code ResourceInputStream.read(byte[], off, len)}
     * returns -1 for {@code len == 0} at the end of the resource (InputStream returns 0) and
     * checks no range, so {@code off + len > b.length} wrote past the array: a wasm trap
     * instead of IndexOutOfBoundsException. The method gets the JDK's prologue:
     * {@code Objects.checkFromIndexSize(off, len, b.length); if (len == 0) return 0;} (a null
     * array fails at {@code b.length}, as every null dereference does in the build's null-check
     * mode). The new branch target carries a frame equal to the entry frame.
     */
    static ClassNode patchWasmGcResourceStreamRead(ClassNode node) {
        MethodNode read = method(node, "read", "([BII)I");
        for (AbstractInsnNode insn = read.instructions.getFirst(); insn != null && insn.getOpcode() < 0;
                insn = insn.getNext()) {
            if (insn instanceof FrameNode) {
                throw new IllegalStateException("ResourceInputStream.read starts at a branch target");
            }
        }
        InsnList prologue = new InsnList();
        prologue.add(new VarInsnNode(Opcodes.ILOAD, 2));
        prologue.add(new VarInsnNode(Opcodes.ILOAD, 3));
        prologue.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prologue.add(new InsnNode(Opcodes.ARRAYLENGTH));
        prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "checkFromIndexSize",
                "(III)I", false));
        prologue.add(new InsnNode(Opcodes.POP));
        LabelNode nonEmpty = new LabelNode();
        prologue.add(new VarInsnNode(Opcodes.ILOAD, 3));
        prologue.add(new JumpInsnNode(Opcodes.IFNE, nonEmpty));
        prologue.add(new InsnNode(Opcodes.ICONST_0));
        prologue.add(new InsnNode(Opcodes.IRETURN));
        prologue.add(nonEmpty);
        prologue.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        read.instructions.insert(prologue);
        return node;
    }

    private static void writeText(Path patchRoot, String name, String text) throws IOException {
        Path output = patchRoot.resolve(name);
        Files.createDirectories(output.getParent());
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    /**
     * The patched TeaVM classes call {@link GaiusTeaVMOptions}, so the helper must be inside
     * the patched jar that the TeaVM Maven plugin loads.
     */
    private static void copyRuntimeHelpers(Path patchRoot) throws IOException {
        ClassLoader loader = TeaVMCoreBrowserPatcher.class.getClassLoader();
        // Keeps javac compiling the helper with the patchers (build-overlays.sh compiles the
        // top-level patcher sources and resolves referenced classes from the source path).
        if (GaiusTeaVMOptions.class.getClassLoader() != loader) {
            throw new IllegalStateException("GaiusTeaVMOptions is not on the patcher classpath");
        }
        if (GaiusWasmMath.class.getClassLoader() != loader) {
            throw new IllegalStateException("GaiusWasmMath is not on the patcher classpath");
        }
        if (GaiusWasmResourceChunks.class.getClassLoader() != loader) {
            throw new IllegalStateException("GaiusWasmResourceChunks is not on the patcher classpath");
        }
        List<String> helpers = new ArrayList<>(RUNTIME_HELPERS);
        if (BuildTarget.wasmGc()) {
            helpers.addAll(WASM_GC_RUNTIME_HELPERS);
        }
        for (String helper : helpers) {
            try (InputStream stream = loader.getResourceAsStream(helper + ".class")) {
                if (stream == null) {
                    throw new IllegalStateException(helper + ".class is not on the patcher classpath");
                }
                Path output = patchRoot.resolve(helper + ".class");
                Files.createDirectories(output.getParent());
                Files.write(output, stream.readAllBytes());
            }
        }
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + desc + " not found"));
    }

    private static void requireField(ClassNode node, String name, String desc) {
        boolean present = node.fields.stream()
                .anyMatch(field -> field.name.equals(name) && field.desc.equals(desc));
        if (!present) {
            throw new IllegalStateException(node.name + "." + name + " " + desc + " not found");
        }
    }

    private static AbstractInsnNode single(MethodNode method, String what,
            java.util.function.Predicate<AbstractInsnNode> filter) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (filter.test(insn)) {
                if (found != null) {
                    throw new IllegalStateException(method.name + ": several " + what);
                }
                found = insn;
            }
        }
        if (found == null) {
            throw new IllegalStateException(method.name + ": " + what + " not found");
        }
        return found;
    }

    /** The next instruction that is not a label, line number or frame. */
    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    /** The previous instruction that is not a label, line number or frame. */
    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static MethodInsnNode options(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, OPTIONS, name, desc, false);
    }

    /**
     * AsyncMethodFinder:
     * <ul>
     * <li>find() resets and finally reports the methods the options compiled synchronously;</li>
     * <li>add() first asks {@link GaiusTeaVMOptions#cutAsync}: a monitor primitive (syncMonitors)
     * or an async-barrier family member is neither marked async nor propagated to its callers
     * (its body is guarded at render time, see {@link #patchRendererSyncGuard});</li>
     * <li>with syncClinits, a class initializer is still marked async but its callers are
     * not.</li>
     * </ul>
     */
    static ClassNode patchAsyncMethodFinder(ClassNode node) {
        requireField(node, "classSource", "Lorg/teavm/model/ListableClassReaderSource;");
        MethodNode find = method(node, "find", "(Lorg/teavm/model/ListableClassReaderSource;)V");
        AbstractInsnNode findReturn = single(find, "RETURN",
                insn -> insn.getOpcode() == Opcodes.RETURN);
        find.instructions.insertBefore(findReturn, options("reportCuts", "()V"));
        InsnList census = new InsnList();
        census.add(new VarInsnNode(Opcodes.ALOAD, 0));
        census.add(options("dumpAsync", "(Ljava/lang/Object;)V"));
        find.instructions.insertBefore(findReturn, census);
        find.instructions.insert(options("beginAsyncAnalysis", "()V"));
        // JSPI mode (gaius.teavm.jspi): stop with an empty async set, so no method becomes a
        // coroutine. The class source is stored first, as find() does, for later queries.
        requireField(node, "dependency", "Lorg/teavm/dependency/DependencyInfo;");
        LabelNode analyse = new LabelNode();
        InsnList jspi = new InsnList();
        jspi.add(new VarInsnNode(Opcodes.ALOAD, 0));
        jspi.add(new VarInsnNode(Opcodes.ALOAD, 1));
        jspi.add(new FieldInsnNode(Opcodes.PUTFIELD, ASYNC_FINDER_CLASS, "classSource",
                "Lorg/teavm/model/ListableClassReaderSource;"));
        jspi.add(new VarInsnNode(Opcodes.ALOAD, 1));
        jspi.add(new VarInsnNode(Opcodes.ALOAD, 0));
        jspi.add(new FieldInsnNode(Opcodes.GETFIELD, ASYNC_FINDER_CLASS, "dependency",
                "Lorg/teavm/dependency/DependencyInfo;"));
        jspi.add(options("jspiSkipAsyncAnalysis", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));
        jspi.add(new JumpInsnNode(Opcodes.IFEQ, analyse));
        jspi.add(new InsnNode(Opcodes.RETURN));
        jspi.add(analyse);
        find.instructions.insert(jspi);

        MethodNode add = method(node, "add",
                "(Lorg/teavm/model/MethodReference;Lorg/teavm/model/util/AsyncMethodFinder$CallStack;)V");
        AbstractInsnNode setAdd = single(add, "asyncMethods.add check",
                insn -> insn instanceof MethodInsnNode call && call.owner.equals("java/util/Set")
                        && call.name.equals("add")
                        && call.getNext() instanceof JumpInsnNode jump
                        && jump.getOpcode() == Opcodes.IFNE);
        LabelNode added = ((JumpInsnNode) setAdd.getNext()).label;
        LabelNode propagate = new LabelNode();
        InsnList barrier = new InsnList();
        barrier.add(options("syncClinits", "()Z"));
        barrier.add(new JumpInsnNode(Opcodes.IFEQ, propagate));
        barrier.add(new LdcInsnNode("<clinit>"));
        barrier.add(new VarInsnNode(Opcodes.ALOAD, 1));
        barrier.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/teavm/model/MethodReference",
                "getName", "()Ljava/lang/String;", false));
        barrier.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false));
        barrier.add(new JumpInsnNode(Opcodes.IFEQ, propagate));
        barrier.add(new InsnNode(Opcodes.RETURN));
        barrier.add(propagate);
        add.instructions.insert(added, barrier);
        InsnList record = new InsnList();
        record.add(new VarInsnNode(Opcodes.ALOAD, 1));
        record.add(new VarInsnNode(Opcodes.ALOAD, 2));
        record.add(options("recordAsync", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        add.instructions.insert(added, record);

        LabelNode notCut = new LabelNode();
        InsnList cut = new InsnList();
        cut.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cut.add(new FieldInsnNode(Opcodes.GETFIELD, ASYNC_FINDER_CLASS, "classSource",
                "Lorg/teavm/model/ListableClassReaderSource;"));
        cut.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cut.add(options("cutAsync", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));
        cut.add(new JumpInsnNode(Opcodes.IFEQ, notCut));
        cut.add(new InsnNode(Opcodes.RETURN));
        cut.add(notCut);
        add.instructions.insert(cut);
        return node;
    }

    /**
     * Renderer.renderCallClinit writes the class initializer call of X_$callClinit. For an
     * async initializer (local 3) and syncClinits, the call is wrapped in the
     * globalThis.__gaiusClinitDepth counter. The counter is also correct across a suspension
     * of an async caller: the finally block runs when the initializer unwinds, and the resumed
     * case re-enters the try block.
     */
    static ClassNode patchRendererClinitDepth(ClassNode node) {
        MethodNode render = method(node, "renderCallClinit",
                "(Lorg/teavm/model/MethodReader;Lorg/teavm/model/ClassReader;)V");
        TypeInsnNode newReference = null;
        for (AbstractInsnNode insn = render.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("org/teavm/model/MethodReference")) {
                if (newReference != null) {
                    throw new IllegalStateException("renderCallClinit has several MethodReference allocations");
                }
                newReference = type;
            }
        }
        if (newReference == null
                || !(newReference.getPrevious() instanceof FieldInsnNode writerField)
                || !writerField.name.equals("writer")
                || !(writerField.getPrevious() instanceof VarInsnNode self)
                || self.var != 0) {
            throw new IllegalStateException("renderCallClinit: clinit call rendering not found");
        }
        AbstractInsnNode end = newReference;
        String[] expected = {"appendMethod", "append", "softNewLine"};
        int matched = 0;
        while (matched < expected.length) {
            end = end.getNext();
            if (end == null) {
                throw new IllegalStateException("renderCallClinit: clinit call statement not found");
            }
            if (end instanceof MethodInsnNode call && call.owner.equals(SOURCE_WRITER)) {
                if (!call.name.equals(expected[matched])) {
                    throw new IllegalStateException("renderCallClinit: unexpected " + call.name);
                }
                matched++;
            }
        }
        if (!(end.getNext() instanceof InsnNode pop) || pop.getOpcode() != Opcodes.POP) {
            throw new IllegalStateException("renderCallClinit: clinit call statement end not found");
        }
        render.instructions.insertBefore(self, clinitDepthText(CLINIT_DEPTH_OPEN));
        render.instructions.insert(pop, clinitDepthText(CLINIT_DEPTH_CLOSE));
        return node;
    }

    private static InsnList clinitDepthText(String text) {
        LabelNode skip = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        code.add(options("syncClinits", "()Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS, "writer", "L" + SOURCE_WRITER + ";"));
        code.add(new LdcInsnNode(text));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SOURCE_WRITER, "append",
                "(Ljava/lang/String;)L" + SOURCE_WRITER + ";", false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(skip);
        return code;
    }

    /**
     * Renderer.setProperties receives the TeaVM compiler properties before rendering, the
     * async analysis and name allocation; it passes them to {@link GaiusTeaVMOptions}.
     */
    static ClassNode patchRendererOptions(ClassNode node) {
        MethodNode setProperties = method(node, "setProperties", "(Ljava/util/Properties;)V");
        InsnList configure = new InsnList();
        configure.add(new VarInsnNode(Opcodes.ALOAD, 1));
        configure.add(options("configure", "(Ljava/util/Properties;)V"));
        setProperties.instructions.insert(configure);
        return node;
    }

    /**
     * Renderer.renderRegularBody renders a synchronous or coroutine method body between the
     * prologue ("(params) => {") and the closing brace of renderBody. For a method that the
     * options compiled synchronously, the body is wrapped in the no-suspend guard
     * ({@link GaiusTeaVMOptions#guardOpen}), which thread.js checks on every suspension.
     */
    static ClassNode patchRendererSyncGuard(ClassNode node) {
        MethodNode body = method(node, "renderRegularBody",
                "(Lorg/teavm/model/MethodHolder;Lorg/teavm/ast/decompilation/Decompiler;Z)V");
        AbstractInsnNode prologue = single(body, "renderMethodPrologue call",
                insn -> insn instanceof MethodInsnNode call && call.owner.equals(RENDERER_CLASS)
                        && call.name.equals("renderMethodPrologue"));
        single(body, "MethodBodyRenderer.render call",
                insn -> insn instanceof MethodInsnNode call
                        && call.owner.equals("org/teavm/backend/javascript/rendering/MethodBodyRenderer")
                        && call.name.equals("render"));
        AbstractInsnNode end = single(body, "RETURN", insn -> insn.getOpcode() == Opcodes.RETURN);
        body.instructions.insert(prologue, guardText("guardOpen"));
        body.instructions.insertBefore(end, guardText("guardClose"));
        return node;
    }

    private static InsnList guardText(String helper) {
        LabelNode none = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/teavm/model/MethodHolder",
                "getReference", "()Lorg/teavm/model/MethodReference;", false));
        code.add(options(helper, "(Ljava/lang/Object;)Ljava/lang/String;"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNULL, none));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS, "writer", "L" + SOURCE_WRITER + ";"));
        code.add(new InsnNode(Opcodes.SWAP));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SOURCE_WRITER, "append",
                "(Ljava/lang/String;)L" + SOURCE_WRITER + ";", false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new JumpInsnNode(Opcodes.GOTO, done));
        code.add(none);
        code.add(new InsnNode(Opcodes.POP));
        code.add(done);
        return code;
    }

    /**
     * TeaVM.getPlatformTags is what the class library plugin reads when TeaVM installs plugins,
     * after TeaVMTool has set the compiler properties (the target controller only exists from
     * build() on, later). The patched method configures {@link GaiusTeaVMOptions} from the
     * live properties and appends the role tags, which drive {@code @PlatformMarker}
     * constants such as the telemetry tag.
     */
    static ClassNode patchTeaVMPlatformTags(ClassNode node) {
        requireField(node, "properties", "Ljava/util/Properties;");
        MethodNode tags = method(node, "getPlatformTags", "()[Ljava/lang/String;");
        AbstractInsnNode result = single(tags, "ARETURN", insn -> insn.getOpcode() == Opcodes.ARETURN);
        InsnList extend = new InsnList();
        extend.add(new VarInsnNode(Opcodes.ALOAD, 0));
        extend.add(new FieldInsnNode(Opcodes.GETFIELD, TEAVM_CLASS, "properties",
                "Ljava/util/Properties;"));
        extend.add(options("platformTags",
                "([Ljava/lang/String;Ljava/util/Properties;)[Ljava/lang/String;"));
        tags.instructions.insertBefore(result, extend);
        return node;
    }

    /**
     * MinifyingAliasProvider puts every top-level name past maxTopLevelNames on the
     * additional scope object ("A.x"), which V8 keeps in dictionary mode. Names are assigned
     * by textual frequency, so TeaVM runtime functions that are referenced from only a few
     * places but executed on every coroutine check (the current-thread slot) end up there.
     * The patch records the top-level counter when the scope switch happens and gives a
     * pinned runtime function ({@link GaiusTeaVMOptions#isPinnedRuntimeName}) a fresh
     * top-level name from that counter.
     *
     * <p>The switch happens inside createTopLevelName, so a pinned name requested before it
     * still takes the regular path; when that very call switched to the scope (the returned
     * name is scoped), the scoped name is dropped and the pinned allocation runs instead.
     * The dropped scope suffix is never rendered.
     */
    static ClassNode patchMinifyingAliasProvider(ClassNode node) {
        requireField(node, "lastSuffix", "I");
        requireField(node, "topLevelNames", "I");
        requireField(node, "additionalScopeStarted", "Z");
        requireField(node, "usedAliases", "Ljava/util/Set;");
        String startLetters = node.fields.stream()
                .filter(field -> field.name.equals("startLetters") && field.value instanceof String)
                .map(field -> (String) field.value)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "MinifyingAliasProvider.startLetters constant not found"));
        if (node.fields.stream().anyMatch(field -> field.name.equals(PINNED_SUFFIX_FIELD))) {
            throw new IllegalStateException("MinifyingAliasProvider is already patched");
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, PINNED_SUFFIX_FIELD, "I", null, null));

        MethodNode create = method(node, "createTopLevelName", "()L" + SCOPED_NAME + ";");
        AbstractInsnNode scopeSwitch = single(create, "additional scope switch",
                insn -> insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.name.equals("additionalScopeStarted"));
        if (!(nextReal(scopeSwitch) instanceof VarInsnNode self && self.var == 0
                && nextReal(self) instanceof InsnNode zero && zero.getOpcode() == Opcodes.ICONST_0
                && nextReal(zero) instanceof FieldInsnNode reset
                && reset.getOpcode() == Opcodes.PUTFIELD && reset.name.equals("lastSuffix"))) {
            throw new IllegalStateException(
                    "MinifyingAliasProvider.createTopLevelName: lastSuffix reset not found");
        }
        InsnList freeze = new InsnList();
        freeze.add(new VarInsnNode(Opcodes.ALOAD, 0));
        freeze.add(new VarInsnNode(Opcodes.ALOAD, 0));
        freeze.add(new FieldInsnNode(Opcodes.GETFIELD, MINIFYING_ALIASES, "lastSuffix", "I"));
        freeze.add(new FieldInsnNode(Opcodes.PUTFIELD, MINIFYING_ALIASES, PINNED_SUFFIX_FIELD, "I"));
        create.instructions.insert(scopeSwitch, freeze);

        MethodNode pinned = new MethodNode(Opcodes.ACC_PRIVATE, PINNED_NAME_METHOD,
                "()L" + SCOPED_NAME + ";", null, null);
        LabelNode next = new LabelNode();
        InsnList code = pinned.instructions;
        code.add(next);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MINIFYING_ALIASES, PINNED_SUFFIX_FIELD, "I"));
        code.add(new InsnNode(Opcodes.DUP_X1));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, MINIFYING_ALIASES, PINNED_SUFFIX_FIELD, "I"));
        code.add(new LdcInsnNode(startLetters));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDERING_UTIL, "indexToId",
                "(ILjava/lang/String;)Ljava/lang/String;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MINIFYING_ALIASES, "usedAliases", "Ljava/util/Set;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains",
                "(Ljava/lang/Object;)Z", true));
        code.add(new JumpInsnNode(Opcodes.IFNE, next));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, RENDERING_UTIL, "KEYWORDS", "Ljava/util/Set;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains",
                "(Ljava/lang/Object;)Z", true));
        code.add(new JumpInsnNode(Opcodes.IFNE, next));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MINIFYING_ALIASES, "topLevelNames", "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, MINIFYING_ALIASES, "topLevelNames", "I"));
        code.add(new TypeInsnNode(Opcodes.NEW, SCOPED_NAME));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SCOPED_NAME, "<init>",
                "(Ljava/lang/String;Z)V", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(pinned);

        MethodNode functionAlias = method(node, "getFunctionAlias",
                "(Ljava/lang/String;)L" + SCOPED_NAME + ";");
        requireCall(functionAlias, MINIFYING_ALIASES, "createTopLevelName", "()L" + SCOPED_NAME + ";");
        InsnList redirect = new InsnList();
        LabelNode regular = new LabelNode();
        LabelNode pinnedName = new LabelNode();
        LabelNode dropScoped = new LabelNode();
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 1));
        redirect.add(options("isPinnedRuntimeName", "(Ljava/lang/String;)Z"));
        redirect.add(new JumpInsnNode(Opcodes.IFEQ, regular));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        redirect.add(new FieldInsnNode(Opcodes.GETFIELD, MINIFYING_ALIASES, "additionalScopeStarted", "Z"));
        redirect.add(new JumpInsnNode(Opcodes.IFNE, pinnedName));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        redirect.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MINIFYING_ALIASES, "createTopLevelName",
                "()L" + SCOPED_NAME + ";", false));
        redirect.add(dropScopedName(dropScoped));
        redirect.add(pinnedName);
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        redirect.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MINIFYING_ALIASES, PINNED_NAME_METHOD,
                "()L" + SCOPED_NAME + ";", false));
        redirect.add(new InsnNode(Opcodes.ARETURN));
        redirect.add(regular);
        functionAlias.instructions.insert(redirect);
        return node;
    }

    /**
     * Stack: the ScopedName of the regular allocation. Returns it when it is top-level;
     * otherwise pops it and falls through at {@code dropScoped} (placed at the end of the
     * returned list) to the pinned allocation that follows.
     */
    private static InsnList dropScopedName(LabelNode dropScoped) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, SCOPED_NAME, "scoped", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, dropScoped));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(dropScoped);
        code.add(new InsnNode(Opcodes.POP));
        return code;
    }

    /** The regular alias allocation the redirect falls back to must still be this call. */
    private static void requireCall(MethodNode method, String owner, String name, String desc) {
        single(method, owner + "." + name + " call",
                insn -> insn instanceof MethodInsnNode call && call.owner.equals(owner)
                        && call.name.equals(name) && call.desc.equals(desc));
    }

    /**
     * DefaultAliasProvider (readable names) has the same top-level budget. A pinned runtime
     * function past the budget keeps its own name as a top-level binding: runtime function
     * names are unique, valid identifiers and no Java member alias starts with "$rt_" or
     * "Long_". As in {@link #patchMinifyingAliasProvider}, the pinned name requested by the
     * call that switches to the scope (inside makeUnique) drops the scoped result.
     */
    static ClassNode patchDefaultAliasProvider(ClassNode node) {
        requireField(node, "additionalScopeStarted", "Z");
        String makeUniqueDesc = "(Ljava/lang/String;)L" + SCOPED_NAME + ";";
        MethodNode functionAlias = method(node, "getFunctionAlias", makeUniqueDesc);
        requireCall(functionAlias, DEFAULT_ALIASES, "makeUnique", makeUniqueDesc);
        InsnList redirect = new InsnList();
        LabelNode regular = new LabelNode();
        LabelNode pinnedName = new LabelNode();
        LabelNode dropScoped = new LabelNode();
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 1));
        redirect.add(options("isPinnedRuntimeName", "(Ljava/lang/String;)Z"));
        redirect.add(new JumpInsnNode(Opcodes.IFEQ, regular));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        redirect.add(new FieldInsnNode(Opcodes.GETFIELD, DEFAULT_ALIASES, "additionalScopeStarted", "Z"));
        redirect.add(new JumpInsnNode(Opcodes.IFNE, pinnedName));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 1));
        redirect.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, DEFAULT_ALIASES, "makeUnique",
                makeUniqueDesc, false));
        redirect.add(dropScopedName(dropScoped));
        redirect.add(pinnedName);
        redirect.add(new TypeInsnNode(Opcodes.NEW, SCOPED_NAME));
        redirect.add(new InsnNode(Opcodes.DUP));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 1));
        redirect.add(new InsnNode(Opcodes.ICONST_0));
        redirect.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SCOPED_NAME, "<init>",
                "(Ljava/lang/String;Z)V", false));
        redirect.add(new InsnNode(Opcodes.ARETURN));
        redirect.add(regular);
        functionAlias.instructions.insert(redirect);
        return node;
    }

    /**
     * WebAssembly GC only (reproducible output): a TypeSet that several dependency nodes share
     * keeps them in its {@code domain}, a {@code java.util.HashSet} of DependencyNode, whose
     * hash is the identity hash. getTransitions and getConsumers iterate that set, so the order
     * in which the dependency analysis propagates types, and with it the order in which it
     * reaches classes and methods, followed the identity hashes of the nodes. That order is the
     * class list of the link step, and the WebAssembly GC backend derives the class tags, the
     * type, function and global order and the string constant numbering from it: two compiles
     * of the same sources differed whenever the JVM handed out other identity hashes (another
     * heap size, anything the build hashed before TeaVM started). Both HashSet allocations of
     * TypeSet ({@code addDomain} of one node and of a collection) become LinkedHashSet, so the
     * domain iterates in insertion order, which the analysis itself makes deterministic. The set
     * is only used as a set and through {@code Set} methods; the field is an Object. TeaVM 0.16
     * has the same code; the JavaScript build keeps the stock class (its output stays as it
     * is).
     *
     * @return false when the class allocates no HashSet (a TeaVM version that changed it)
     */
    static boolean patchTypeSetDomainOrder(ClassNode node) {
        if (node.fields.stream().noneMatch(field -> field.name.equals("domain")
                && field.desc.equals("Ljava/lang/Object;"))) {
            throw new IllegalStateException("TeaVM TypeSet.domain (Object) not found");
        }
        int allocations = 0;
        int constructors = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null;
                    insn = insn.getNext()) {
                if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                        && type.desc.equals("java/util/HashSet")) {
                    type.desc = "java/util/LinkedHashSet";
                    allocations++;
                } else if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.owner.equals("java/util/HashSet") && call.name.equals("<init>")) {
                    call.owner = "java/util/LinkedHashSet";
                    constructors++;
                }
            }
        }
        if (allocations != constructors) {
            throw new IllegalStateException("TeaVM TypeSet: " + allocations
                    + " HashSet allocations but " + constructors + " constructor calls");
        }
        return allocations > 0;
    }

    /**
     * WebAssembly GC only: {@code WasmGCIntrinsics.apply}, which fills the inline intrinsic
     * registry of the code generation, first calls {@code GaiusWasmMath.register(inline)}, so
     * Math and StrictMath floor, ceil, sqrt, rint, abs, min and max compile to single
     * WebAssembly instructions (see {@link GaiusWasmMath} for why each one equals Java's
     * result). TeaVM 0.15 registers no intrinsic of either class; the patch fails when a TeaVM
     * version does, instead of silently shadowing or being shadowed by it. The call is
     * stack-neutral at the method entry, so the stack map frames stay valid.
     */
    static ClassNode patchWasmGcMathIntrinsics(ClassNode node) {
        MethodNode apply = method(node, "apply", "(Lorg/teavm/reflection/ReflectionDependencyListener;"
                + "Lorg/teavm/backend/wasm/intrinsics/WasmGCCodeGenContext;"
                + "Lorg/teavm/vm/intrinsic/IntrinsicRegistry;Lorg/teavm/vm/intrinsic/IntrinsicRegistry;)V");
        if ((apply.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("TeaVM WasmGCIntrinsics.apply is not static");
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof org.objectweb.asm.Type type
                        && (type.getInternalName().equals("java/lang/Math")
                        || type.getInternalName().equals("java/lang/StrictMath"))) {
                    throw new IllegalStateException("TeaVM WasmGCIntrinsics registers "
                            + type.getClassName() + " intrinsics itself");
                }
                if (insn instanceof MethodInsnNode call && call.owner.equals(WASM_MATH)) {
                    throw new IllegalStateException("TeaVM WasmGCIntrinsics is already patched");
                }
            }
        }
        InsnList register = new InsnList();
        // apply(listener, context, inline, body): local 2 is the inline intrinsic registry.
        register.add(new VarInsnNode(Opcodes.ALOAD, 2));
        register.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WASM_MATH, "register",
                "(Ljava/lang/Object;)V", false));
        apply.instructions.insert(register);
        return node;
    }

    /**
     * Heap.alloc (the Wasm GC off-heap allocator) grows the memory by {@code bytes} when the
     * last heap record is allocated, but the new free record also needs a HeapRecord header,
     * so a request of a multiple of the wasm page size made while the heap is full fails and
     * returns null. TeaVM 0.16 grows by {@code bytes + sizeof(HeapRecord)} instead; the patch
     * applies the same fix to TeaVM 0.15: the allocated branch of
     * {@code bytes - (allocated ? 0 : size(last))} becomes {@code -sizeof(HeapRecord)}.
     *
     * @return false when the TeaVM version already grows by the header size
     */
    static boolean patchHeapAllocGrowth(ClassNode node) {
        MethodNode alloc = method(node, "alloc", "(I)Lorg/teavm/interop/Address;");
        for (AbstractInsnNode insn = alloc.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(STRUCTURE)
                    && call.name.equals("sizeOf")) {
                return false;
            }
        }
        single(alloc, "HeapRecord.isAllocated call",
                insn -> insn instanceof MethodInsnNode call && call.owner.equals(HEAP_RECORD)
                        && call.name.equals("isAllocated"));
        AbstractInsnNode zero = single(alloc, "allocated-branch ICONST_0 before ISUB",
                insn -> insn.getOpcode() == Opcodes.ICONST_0
                        && nextReal(insn) != null && nextReal(insn).getOpcode() == Opcodes.ISUB);
        InsnList header = new InsnList();
        header.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(HEAP_RECORD)));
        header.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STRUCTURE, "sizeOf",
                "(Ljava/lang/Class;)I", false));
        header.add(new InsnNode(Opcodes.INEG));
        alloc.instructions.insert(zero, header);
        alloc.instructions.remove(zero);
        return true;
    }

    /** thread.js: the suspension guard for methods the options compiled synchronously. */
    static String patchThreadRuntime(String source) {
        String normalized = source.replace("\r\n", "\n");
        int first = normalized.indexOf(THREAD_SUSPEND_ANCHOR);
        if (first < 0 || normalized.indexOf(THREAD_SUSPEND_ANCHOR, first + 1) >= 0) {
            throw new IllegalStateException("thread.js: TeaVMThread.prototype.suspend anchor not"
                    + " found exactly once");
        }
        List<String> required = new ArrayList<>(List.of(
                "$rt_currentNativeThread = this;", "let $rt_currentNativeThread = null;",
                "let $rt_nativeThread = () => $rt_currentNativeThread;"));
        for (String text : required) {
            if (!normalized.contains(text)) {
                throw new IllegalStateException("thread.js: expected runtime text missing: " + text);
            }
        }
        return normalized.replace(THREAD_SUSPEND_ANCHOR, THREAD_SUSPEND_GUARDED);
    }
}
