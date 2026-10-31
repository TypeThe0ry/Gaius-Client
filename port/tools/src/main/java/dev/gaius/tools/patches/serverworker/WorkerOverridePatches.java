package dev.gaius.tools.patches.serverworker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The server-worker domain of the MinecraftServerWorkerPatcher step
 * ({@code patches/server-worker}): the class overrides of the integrated server in its Worker
 * (no JSON-RPC management server, the named worldgen dispatcher executor, budgeted bookkeeping
 * turns of the consecutive executors).
 *
 * <p>{@code MinecraftServerWorkerPatcher.main} registers them under their historical id
 * ({@code MinecraftServerWorkerPatcher.classOverrides}); the light and worldgen kernel routes it
 * registers next are {@code LightKernelPatches} and {@code WorldgenKernelPatches}.
 */
public final class WorkerOverridePatches {
    private WorkerOverridePatches() {
    }

    private static final String JSON_RPC = "net/minecraft/server/jsonrpc/JsonRpc";

    private static final String CREATE_DESCRIPTOR =
            "(Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                    + "Lnet/minecraft/server/notifications/NotificationManager;)"
                    + "Lnet/minecraft/server/jsonrpc/ManagementServer;";

    private static final String ABSTRACT_EXECUTOR =
            "net/minecraft/util/thread/AbstractConsecutiveExecutor";

    private static final String FIXED_PRIORITY_QUEUE =
            "net/minecraft/util/thread/StrictQueue$FixedPriorityQueue";

    private static final String CHUNK_TASK_DISPATCHER =
            "net/minecraft/server/level/ChunkTaskDispatcher";

    private static final String TASK_SCHEDULER = "net/minecraft/util/thread/TaskScheduler";

    private static final String WORLDGEN_EXECUTOR_NAME = "worldgen-dispatcher";

    private static final String WORLDGEN_DISPATCHER_SCHEDULER =
            "dev/gaius/browser/BrowserWorldgenDispatcherScheduler";

    private static final String DEFERRED_REGISTER = "gaius$registerForExecutionDeferred";

    private static final String HEAD_PRIORITY = "gaius$headPriority";

    private static final String TRAMPOLINE_REGISTERING = "gaius$trampolineRegistering";

    private static final String TRAMPOLINE_REQUESTED = "gaius$trampolineRequested";

    // run() local holding a runnable failure until the trampolined backlog has drained.
    private static final int VANILLA_FAILURE_LOCAL = 7;

    // Mirrors BrowserWorldgenDispatcherScheduler.STOP_IDLE for a turn that ran nothing.
    private static final int DISPATCHER_STOP_IDLE = 1;

    public static void patchWorker(Path jar, Path outputRoot) throws IOException {
        boolean jsonRpcPatched = hasEntry(jar, JSON_RPC + ".class");
        if (jsonRpcPatched) {
            ClassNode node = read(jar, JSON_RPC + ".class");
            MethodNode create = find(node, "create", CREATE_DESCRIPTOR);

            // A null result is JsonRpc.create's normal result when the management
            // server is disabled. Browser integrated servers never expose a TCP
            // management endpoint, so make that configuration decision explicit.
            InsnList code = new InsnList();
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new InsnNode(Opcodes.ARETURN));
            replace(create, code);
            write(node, outputRoot.resolve(JSON_RPC + ".class"));
        }
        ClassNode dispatcher = read(jar, CHUNK_TASK_DISPATCHER + ".class");
        patchChunkTaskDispatcher(dispatcher);
        write(dispatcher, outputRoot.resolve(CHUNK_TASK_DISPATCHER + ".class"));
        boolean headPriorityPatched = false;
        if (hasEntry(jar, FIXED_PRIORITY_QUEUE + ".class")) {
            ClassNode queue = read(jar, FIXED_PRIORITY_QUEUE + ".class");
            headPriorityPatched = addFixedQueueHeadPriority(queue);
            if (headPriorityPatched) {
                write(queue, outputRoot.resolve(FIXED_PRIORITY_QUEUE + ".class"));
            }
        }
        ClassNode executor = read(jar, ABSTRACT_EXECUTOR + ".class");
        patchAbstractExecutor(executor, headPriorityPatched);
        write(executor, outputRoot.resolve(ABSTRACT_EXECUTOR + ".class"));
        if (jsonRpcPatched) {
            System.out.println("Disabled the dedicated JSON-RPC management server for the browser Worker");
        }
        System.out.println(headPriorityPatched
                ? "Patched worldgen PriorityConsecutiveExecutor with budgeted bookkeeping turns"
                : "Patched worldgen PriorityConsecutiveExecutor with deferred single-task turns"
                        + " (StrictQueue priority shape not found)");
    }

    private static void patchChunkTaskDispatcher(ClassNode node) {
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")
                    || !method.desc.equals("(Lnet/minecraft/util/thread/TaskScheduler;"
                            + "Ljava/util/concurrent/Executor;)V")) {
                continue;
            }
            if (hasExistingWorldgenNaming(method)) {
                // Accept an existing branch only when both names flow through the same join into
                // PriorityConsecutiveExecutor.<init>; decoy strings elsewhere are insufficient.
                patched = true;
                break;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof LdcInsnNode ldc)
                        || !"dispatcher".equals(ldc.cst)) {
                    continue;
                }
                AbstractInsnNode next = instruction.getNext();
                if (!(next instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !call.owner.equals("net/minecraft/util/thread/PriorityConsecutiveExecutor")
                        || !call.name.equals("<init>")) {
                    continue;
                }
                LabelNode defaultName = new LabelNode();
                LabelNode join = new LabelNode();
                InsnList names = new InsnList();
                names.add(new LdcInsnNode("worldgen"));
                names.add(new VarInsnNode(Opcodes.ALOAD, 1));
                names.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, TASK_SCHEDULER, "name",
                        "()Ljava/lang/String;", true));
                names.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                        "(Ljava/lang/Object;)Z", false));
                names.add(new JumpInsnNode(Opcodes.IFEQ, defaultName));
                names.add(new LdcInsnNode(WORLDGEN_EXECUTOR_NAME));
                names.add(new JumpInsnNode(Opcodes.GOTO, join));
                names.add(defaultName);
                names.add(new LdcInsnNode("dispatcher"));
                names.add(join);
                method.instructions.insertBefore(ldc, names);
                method.instructions.remove(ldc);
                patched = true;
                break;
            }
        }
        if (!patched) {
            throw new IllegalStateException("ChunkTaskDispatcher priority executor constructor not found");
        }
    }

    /**
     * Checks the existing conditional naming branch by its bytecode data flow. Merely finding
     * "worldgen" and "dispatcher" elsewhere in the constructor is insufficient: those strings
     * may belong to diagnostics while the executor still receives the unpatched name.
     */
    private static boolean hasExistingWorldgenNaming(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !constructor.owner.equals("net/minecraft/util/thread/PriorityConsecutiveExecutor")
                    || !constructor.name.equals("<init>")) {
                continue;
            }
            LdcInsnNode worldgenName = null;
            LdcInsnNode dispatcherName = null;
            LabelNode worldgenEntry = null;
            LabelNode dispatcherEntry = null;
            for (AbstractInsnNode candidate : method.instructions.toArray()) {
                if (!(candidate instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String text)) {
                    continue;
                }
                if (WORLDGEN_EXECUTOR_NAME.equals(text)) {
                    LabelNode entry = directStringPathEntry(ldc, constructor);
                    if (entry != null) {
                        worldgenName = ldc;
                        worldgenEntry = entry;
                    }
                } else if ("dispatcher".equals(text)) {
                    LabelNode entry = directStringPathEntry(ldc, constructor);
                    if (entry != null) {
                        dispatcherName = ldc;
                        dispatcherEntry = entry;
                    }
                }
            }
            if (worldgenName != null && dispatcherName != null
                    && worldgenEntry == dispatcherEntry
                    && hasWorldgenPredicate(method, worldgenName)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the label at which a constant's straight-line path joins before the constructor. */
    private static LabelNode directStringPathEntry(LdcInsnNode constant, MethodInsnNode constructor) {
        AbstractInsnNode cursor = constant.getNext();
        LabelNode entry = null;
        while (cursor != null && cursor != constructor) {
            if (cursor.getOpcode() < 0) {
                if (cursor instanceof LabelNode label && entry == null) {
                    entry = label;
                }
                cursor = cursor.getNext();
                continue;
            }
            if (cursor instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) {
                entry = jump.label;
                cursor = jump.label;
                continue;
            }
            return null;
        }
        return cursor == constructor ? entry : null;
    }

    /** Requires the generated scheduler-name predicate before the worldgen constant. */
    private static boolean hasWorldgenPredicate(MethodNode method, LdcInsnNode worldgenName) {
        AbstractInsnNode cursor = method.instructions.getFirst();
        while (cursor != null && cursor != worldgenName) {
            if (cursor instanceof LdcInsnNode ldc && "worldgen".equals(ldc.cst)) {
                AbstractInsnNode loadScheduler = nextOpcode(ldc);
                AbstractInsnNode schedulerName = nextOpcode(loadScheduler);
                AbstractInsnNode equals = nextOpcode(schedulerName);
                AbstractInsnNode branch = nextOpcode(equals);
                if (loadScheduler instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                        && load.var == 1
                        && schedulerName instanceof MethodInsnNode nameCall
                        && nameCall.getOpcode() == Opcodes.INVOKEINTERFACE
                        && nameCall.owner.equals(TASK_SCHEDULER)
                        && nameCall.name.equals("name")
                        && nameCall.desc.equals("()Ljava/lang/String;")
                        && equals instanceof MethodInsnNode equalsCall
                        && equalsCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && equalsCall.owner.equals("java/lang/String")
                        && equalsCall.name.equals("equals")
                        && equalsCall.desc.equals("(Ljava/lang/Object;)Z")
                        && branch instanceof JumpInsnNode jump
                        && (jump.getOpcode() == Opcodes.IFEQ || jump.getOpcode() == Opcodes.IFNE)) {
                    return true;
                }
            }
            cursor = cursor.getNext();
        }
        return false;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction == null ? null : instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static void patchAbstractExecutor(ClassNode node, boolean headPriorityPatched) {
        MethodNode run = find(node, "run", "()V");
        InsnList code = new InsnList();
        LabelNode vanilla = new LabelNode();
        LabelNode worldgenStart = new LabelNode();
        LabelNode worldgenLoop = new LabelNode();
        LabelNode worldgenDone = new LabelNode();
        LabelNode vanillaStart = new LabelNode();
        LabelNode vanillaDone = new LabelNode();
        LabelNode worldgenCatch = new LabelNode();
        LabelNode vanillaCatch = new LabelNode();
        LabelNode vanillaLoop = new LabelNode();
        LabelNode vanillaRegister = new LabelNode();
        LabelNode vanillaInit = new LabelNode();
        LabelNode registerStart = new LabelNode();
        LabelNode registerDone = new LabelNode();
        LabelNode registerCatch = new LabelNode();
        LabelNode end = new LabelNode();

        code.add(new LdcInsnNode(WORLDGEN_EXECUTOR_NAME));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, ABSTRACT_EXECUTOR, "name", "Ljava/lang/String;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));

        // A worldgen runnable can suspend through ChunkGenerationTask.runUntilWait().
        // TeaVM's Worker executor invokes execute() synchronously, so the vanilla
        // registration path would recursively drain the queue in this same JavaScript turn.
        // Only ChunkTaskDispatcher's priority-3 generation poll can suspend: run it solely as
        // the first runnable of a turn and end the turn after it. Priority 0-2 level-change,
        // release and submit bookkeeping keeps draining inline while the queue head stays
        // below priority 3, bounded by the scheduler's wall-clock budget. The deferred path
        // marks the dispatcher running now, then submits it from a fresh Worker turn.
        // Locals: 1 executed, 2 priority of the runnable being run, 3 stop reason,
        // 4-5 turn start nanos, 6 failure.
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));
        code.add(new IntInsnNode(Opcodes.BIPUSH, DISPATCHER_STOP_IDLE));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, WORLDGEN_DISPATCHER_SCHEDULER, "beginTurn", "()J", false));
        code.add(new VarInsnNode(Opcodes.LSTORE, 4));
        code.add(worldgenStart);
        code.add(worldgenLoop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, HEAD_PRIORITY, "()I", false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "pollTask", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, worldgenDone));
        code.add(new IincInsnNode(1, 1));
        code.add(new VarInsnNode(Opcodes.LLOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, HEAD_PRIORITY, "()I", false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, WORLDGEN_DISPATCHER_SCHEDULER, "continueTurn",
                "(JIII)I", false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(new JumpInsnNode(Opcodes.IFEQ, worldgenLoop));
        code.add(worldgenDone);
        code.add(new VarInsnNode(Opcodes.LLOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, WORLDGEN_DISPATCHER_SCHEDULER, "endTurn",
                "(JII)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "setSleeping", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR,
                DEFERRED_REGISTER, "()V", false));
        code.add(new JumpInsnNode(Opcodes.GOTO, end));

        // Every other executor keeps vanilla's one-runnable-per-run() contract, but its
        // re-registration is trampolined. With TeaVM's synchronous Worker executor,
        // registerForExecution() calls run() again inline, so a queue of N runnables used to
        // drain N frames deep (seven JavaScript frames each) and a burst of light or worldgen
        // runnables overflowed the Worker stack. A run() that arrives while this executor is
        // re-registering only records the request; the outer run() then loops, which executes
        // the same runnables in the same order without growing the stack. A failing runnable
        // keeps vanilla's contract without recursion either: the failure is held, the loop
        // re-registers and drains the rest of the backlog exactly as the recursive drain did,
        // and the last failure is rethrown once the executor goes idle (vanilla's innermost,
        // that is last, failure is the one that escaped). A deferred (queued) executor never
        // re-enters during registration and is unaffected.
        code.add(vanilla);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, ABSTRACT_EXECUTOR, TRAMPOLINE_REGISTERING, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, vanillaInit));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, ABSTRACT_EXECUTOR, TRAMPOLINE_REQUESTED, "Z"));
        code.add(new JumpInsnNode(Opcodes.GOTO, end));
        code.add(vanillaInit);
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new VarInsnNode(Opcodes.ASTORE, VANILLA_FAILURE_LOCAL));
        code.add(vanillaLoop);
        code.add(vanillaStart);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "pollTask", "()Z", false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(vanillaDone);
        code.add(vanillaRegister);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "setSleeping", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, ABSTRACT_EXECUTOR, TRAMPOLINE_REQUESTED, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, ABSTRACT_EXECUTOR, TRAMPOLINE_REGISTERING, "Z"));
        code.add(registerStart);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR,
                "registerForExecution", "()V", false));
        code.add(registerDone);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, ABSTRACT_EXECUTOR, TRAMPOLINE_REGISTERING, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, ABSTRACT_EXECUTOR, TRAMPOLINE_REQUESTED, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, vanillaLoop));
        code.add(new VarInsnNode(Opcodes.ALOAD, VANILLA_FAILURE_LOCAL));
        code.add(new JumpInsnNode(Opcodes.IFNULL, end));
        code.add(new VarInsnNode(Opcodes.ALOAD, VANILLA_FAILURE_LOCAL));
        code.add(new InsnNode(Opcodes.ATHROW));

        code.add(registerCatch);
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, ABSTRACT_EXECUTOR, TRAMPOLINE_REGISTERING, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ATHROW));

        code.add(worldgenCatch);
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "setSleeping", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR,
                DEFERRED_REGISTER, "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new InsnNode(Opcodes.ATHROW));

        code.add(vanillaCatch);
        code.add(new VarInsnNode(Opcodes.ASTORE, VANILLA_FAILURE_LOCAL));
        code.add(new JumpInsnNode(Opcodes.GOTO, vanillaRegister));
        code.add(end);
        code.add(new InsnNode(Opcodes.RETURN));

        run.instructions = code;
        run.tryCatchBlocks.clear();
        if (run.localVariables != null) {
            run.localVariables.clear();
        }
        if (run.visibleLocalVariableAnnotations != null) {
            run.visibleLocalVariableAnnotations.clear();
        }
        if (run.invisibleLocalVariableAnnotations != null) {
            run.invisibleLocalVariableAnnotations.clear();
        }
        run.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(
                worldgenStart, worldgenDone, worldgenCatch, null));
        run.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(
                vanillaStart, vanillaDone, vanillaCatch, null));
        run.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(
                registerStart, registerDone, registerCatch, null));
        run.maxStack = 6;
        run.maxLocals = 8;
        addDeferredRegisterMethod(node);
        addExecutorHeadPriorityMethod(node, headPriorityPatched);
        for (String field : new String[] {TRAMPOLINE_REGISTERING, TRAMPOLINE_REQUESTED}) {
            if (node.fields.stream().anyMatch(existing -> existing.name.equals(field))) {
                throw new IllegalStateException(ABSTRACT_EXECUTOR + "." + field + " already exists");
            }
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, field, "Z", null, null));
        }
    }

    /**
     * Adds a read-only peek at the lowest non-empty StrictQueue priority, or -1 when the queue is
     * empty. pop() drains the same queues in index order, so this is the priority of the runnable
     * the next pollTask() executes.
     */
    private static boolean addFixedQueueHeadPriority(ClassNode node) {
        boolean shape = node.fields.stream().anyMatch(field -> field.name.equals("queues")
                && field.desc.equals("[Ljava/util/Queue;"))
                && node.methods.stream().anyMatch(method -> method.name.equals("pop")
                        && method.desc.equals("()Ljava/lang/Runnable;"));
        if (!shape) {
            return false;
        }
        if (node.methods.stream().anyMatch(method -> method.name.equals(HEAD_PRIORITY))) {
            throw new IllegalStateException(FIXED_PRIORITY_QUEUE + "." + HEAD_PRIORITY + " already exists");
        }
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, HEAD_PRIORITY, "()I", null, null);
        LabelNode loop = new LabelNode();
        LabelNode next = new LabelNode();
        LabelNode empty = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, FIXED_PRIORITY_QUEUE, "queues", "[Ljava/util/Queue;"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, empty));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Queue", "isEmpty", "()Z", true));
        code.add(new JumpInsnNode(Opcodes.IFNE, next));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(next);
        code.add(new IincInsnNode(2, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(empty);
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new InsnNode(Opcodes.IRETURN));
        method.instructions = code;
        method.maxStack = 2;
        method.maxLocals = 3;
        node.methods.add(method);
        return true;
    }

    /**
     * Exposes the queue head priority to the patched run(). Without the StrictQueue shape every
     * head is unclassified (-1), which keeps the previous one-runnable-per-turn behavior.
     */
    private static void addExecutorHeadPriorityMethod(ClassNode node, boolean headPriorityPatched) {
        if (node.methods.stream().anyMatch(method -> method.name.equals(HEAD_PRIORITY))) {
            throw new IllegalStateException(ABSTRACT_EXECUTOR + "." + HEAD_PRIORITY + " already exists");
        }
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, HEAD_PRIORITY, "()I", null, null);
        InsnList code = new InsnList();
        if (headPriorityPatched) {
            LabelNode unclassified = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, ABSTRACT_EXECUTOR, "queue",
                    "Lnet/minecraft/util/thread/StrictQueue;"));
            code.add(new TypeInsnNode(Opcodes.INSTANCEOF, FIXED_PRIORITY_QUEUE));
            code.add(new JumpInsnNode(Opcodes.IFEQ, unclassified));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD, ABSTRACT_EXECUTOR, "queue",
                    "Lnet/minecraft/util/thread/StrictQueue;"));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, FIXED_PRIORITY_QUEUE));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, FIXED_PRIORITY_QUEUE, HEAD_PRIORITY, "()I", false));
            code.add(new InsnNode(Opcodes.IRETURN));
            code.add(unclassified);
        }
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new InsnNode(Opcodes.IRETURN));
        method.instructions = code;
        method.maxStack = 1;
        method.maxLocals = 1;
        node.methods.add(method);
    }

    private static void addDeferredRegisterMethod(ClassNode node) {
        if (node.methods.stream().anyMatch(method -> method.name.equals(DEFERRED_REGISTER))) {
            throw new IllegalStateException(
                    ABSTRACT_EXECUTOR + "." + DEFERRED_REGISTER + " already exists");
        }
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE, DEFERRED_REGISTER, "()V", null, null);
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "canBeScheduled", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "setRunning", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, ABSTRACT_EXECUTOR, "executor", "Ljava/util/concurrent/Executor;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, WORLDGEN_DISPATCHER_SCHEDULER, "defer",
                "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V", false));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        method.instructions = code;
        method.maxStack = 2;
        method.maxLocals = 1;
        node.methods.add(method);
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + descriptor + " not found"));
    }

    private static ClassNode read(Path jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found in " + jarPath);
            }
            try (var stream = jar.getInputStream(entry)) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean hasEntry(Path jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            return jar.getEntry(entryName) != null;
        }
    }

    private static void replace(MethodNode method, InsnList code) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = 1;
        method.maxLocals = Math.max(method.maxLocals, 2);
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
