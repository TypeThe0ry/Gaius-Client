package dev.gaius.tools.patches.light;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Routes the first light of generated chunks in the integrated server through the Rust light
 * kernel ({@code dev.gaius.browser.kernel.light.BrowserLightKernel}). Every profile's light
 * engine is the one the kernel mirrors (its light kernel mirror lock gates the kernel at run
 * time); the member names that differ between generations are the bridge's
 * {@code LightVersionOps}, so the patch itself is the same everywhere. A profile (or one TeaVM
 * target of it) that keeps the vanilla engine declares that in its
 * {@code patchkitNotApplicable}.
 *
 * <p>{@code ThreadedLevelLightEngine}:
 * <ul>
 *   <li>{@code lightChunk(ChunkAccess, boolean)} becomes {@code gaius$lightChunkVanilla}; the new
 *       {@code lightChunk} returns {@code BrowserLightKernel.lightChunk(this, chunk, lighted)}
 *       when that is not null (kernel on and the chunk not lit yet), otherwise the vanilla
 *       method's future;</li>
 *   <li>{@code checkBlock(BlockPos)} first reports the position to
 *       {@code BrowserLightKernel.noteCheckBlock}, which re-checks block changes made while a
 *       column's job was in flight;</li>
 *   <li>it implements {@code BrowserLightEngineHooks}: {@code gaius$addLightTask} calls the
 *       private {@code addTask(x, z, TaskType.values()[type], task)} and
 *       {@code gaius$propagateLightSourcesVanilla} calls
 *       {@code LevelLightEngine.propagateLightSources}, which the kernel path falls back to.</li>
 * </ul>
 *
 * <p>Incremental lighting ({@code checkBlock} itself, {@code initializeLight}) stays vanilla. A
 * missing method or a changed shape fails the patch.
 */
public final class LightKernelPatches {
    static final String ENGINE = "net/minecraft/server/level/ThreadedLevelLightEngine";
    static final String LEVEL_ENGINE = "net/minecraft/world/level/lighting/LevelLightEngine";
    static final String TASK_TYPE = ENGINE + "$TaskType";
    static final String CHUNK_ACCESS = "net/minecraft/world/level/chunk/ChunkAccess";
    static final String CHUNK_POS = "net/minecraft/world/level/ChunkPos";
    static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    static final String FUTURE = "java/util/concurrent/CompletableFuture";
    static final String LIGHT_CHUNK_DESCRIPTOR = "(L" + CHUNK_ACCESS + ";Z)L" + FUTURE + ";";
    static final String CHECK_BLOCK_DESCRIPTOR = "(L" + BLOCK_POS + ";)V";
    static final String ADD_TASK_DESCRIPTOR = "(IIL" + TASK_TYPE + ";Ljava/lang/Runnable;)V";
    static final String PROPAGATE_DESCRIPTOR = "(L" + CHUNK_POS + ";)V";
    static final String HOOKS = "dev/gaius/browser/kernel/light/BrowserLightEngineHooks";
    static final String KERNEL = "dev/gaius/browser/kernel/light/BrowserLightKernel";
    static final String VANILLA_LIGHT_CHUNK = "gaius$lightChunkVanilla";
    static final String ADD_LIGHT_TASK = "gaius$addLightTask";
    static final String PROPAGATE_VANILLA = "gaius$propagateLightSourcesVanilla";

    private LightKernelPatches() {
    }

    /**
     * Patches {@code ThreadedLevelLightEngine} from {@code jar} (or from {@code outputRoot} when
     * an earlier step already wrote it) into {@code outputRoot}.
     */
    public static boolean patch(Path jar, Path outputRoot) throws IOException {
        ClassNode node = read(jar, outputRoot, ENGINE);
        if (!LEVEL_ENGINE.equals(node.superName)) {
            throw new IllegalStateException(ENGINE + " no longer extends " + LEVEL_ENGINE);
        }
        if (node.interfaces.contains(HOOKS)) {
            throw new IllegalStateException(ENGINE + " is already patched for the light kernel");
        }
        requireTaskTypeOrder(read(jar, outputRoot, TASK_TYPE));
        MethodNode vanilla = find(node, "lightChunk", LIGHT_CHUNK_DESCRIPTOR);
        MethodNode checkBlock = find(node, "checkBlock", CHECK_BLOCK_DESCRIPTOR);
        MethodNode addTask = find(node, "addTask", ADD_TASK_DESCRIPTOR);
        if ((addTask.access & Opcodes.ACC_STATIC) != 0) {
            throw new IllegalStateException(ENGINE + ".addTask" + ADD_TASK_DESCRIPTOR + " became static");
        }
        requireVanillaLightChunk(vanilla);
        for (String added : new String[] {VANILLA_LIGHT_CHUNK, ADD_LIGHT_TASK, PROPAGATE_VANILLA}) {
            if (node.methods.stream().anyMatch(method -> method.name.equals(added))) {
                throw new IllegalStateException(ENGINE + " already has " + added);
            }
        }

        vanilla.name = VANILLA_LIGHT_CHUNK;
        vanilla.access = (vanilla.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;
        node.methods.add(lightChunk(vanilla));
        hookCheckBlock(checkBlock);
        node.methods.add(addLightTask());
        node.methods.add(propagateVanilla());
        node.interfaces.add(HOOKS);
        write(node, outputRoot);
        System.out.println("Routed ThreadedLevelLightEngine.lightChunk through the light kernel"
                + " (vanilla kept as " + VANILLA_LIGHT_CHUNK + ", checkBlock reports in-flight changes)");
        return true;
    }

    static final String WAKE_OWNER = "dev/gaius/browser/BrowserIntegratedServerMain";
    static final String WAKE_METHOD = "wakeServerForPoll";
    static final String LIGHT_TASK_LIST = "it/unimi/dsi/fastutil/objects/ObjectList";

    /**
     * Wakes the parked server thread when a light task is queued. {@code ThreadedLevelLightEngine.addTask}
     * hands a lambda to the chunk task dispatcher that appends the task to {@code lightTasks} (and runs
     * {@code runUpdate} itself from 1000 queued tasks); the update proper is only scheduled by
     * {@code ServerChunkCache$MainThreadExecutor.pollTask} on the server thread, which sleeps until its next
     * tick when nothing else wakes it. The patch adds one call to
     * {@code BrowserIntegratedServerMain.wakeServerForPoll()} right after the append (straight-line code, empty
     * operand stack: no frame changes). The call does nothing unless the page asked for it
     * ({@code ?gaiusServerWake=1}) and only wakes a parked thread, so the vanilla poll, the queue, the update
     * and its batching are unchanged; only when the poll runs changes.
     *
     * <p>The lambda is found by shape (an instance synthetic taking the task type and the runnable, which adds
     * a pair to the {@code ObjectList} field and calls {@code runUpdate}), not by its number. A changed shape
     * fails the patch.
     */
    public static boolean patchServerWake(Path jar, Path outputRoot) throws IOException {
        ClassNode node = read(jar, outputRoot, ENGINE);
        String lambdaDescriptor = "(L" + TASK_TYPE + ";Ljava/lang/Runnable;)V";
        MethodNode queueLambda = null;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_SYNTHETIC) == 0 || (method.access & Opcodes.ACC_STATIC) != 0
                    || !method.name.startsWith("lambda$addTask$") || !method.desc.equals(lambdaDescriptor)) {
                continue;
            }
            boolean runsUpdate = false;
            boolean appends = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(ENGINE) && call.name.equals("runUpdate") && call.desc.equals("()V")) {
                        runsUpdate = true;
                    }
                    if (call.owner.equals(LIGHT_TASK_LIST) && call.name.equals("add")
                            && call.desc.equals("(Ljava/lang/Object;)Z")) {
                        appends = true;
                    }
                    if (call.owner.equals(WAKE_OWNER) && call.name.equals(WAKE_METHOD)) {
                        throw new IllegalStateException(ENGINE + "." + method.name + " is already patched for the wake");
                    }
                }
            }
            if (runsUpdate && appends) {
                if (queueLambda != null) {
                    throw new IllegalStateException(ENGINE + " has more than one light task queue lambda");
                }
                queueLambda = method;
            }
        }
        if (queueLambda == null) {
            throw new IllegalStateException(ENGINE + ".addTask no longer queues through a lambda that appends to"
                    + " lightTasks and calls runUpdate");
        }
        AbstractInsnNode append = null;
        for (AbstractInsnNode instruction : queueLambda.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(LIGHT_TASK_LIST)
                    && call.name.equals("add") && call.desc.equals("(Ljava/lang/Object;)Z")) {
                append = instruction;
                break;
            }
        }
        AbstractInsnNode discard = append.getNext();
        if (discard == null || discard.getOpcode() != Opcodes.POP) {
            throw new IllegalStateException(ENGINE + "." + queueLambda.name + " no longer discards the append result");
        }
        InsnList hook = new InsnList();
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WAKE_OWNER, WAKE_METHOD, "()V", false));
        queueLambda.instructions.insert(discard, hook);
        write(node, outputRoot);
        System.out.println("Hooked " + ENGINE + "." + queueLambda.name + " to wake the parked server thread");
        return true;
    }

    /** {@code lightChunk}: vanilla sets the chunk light-incorrect and queues propagateLightSources. */
    private static void requireVanillaLightChunk(MethodNode method) {
        boolean propagates = false;
        boolean setsIncorrect = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(CHUNK_ACCESS) && call.name.equals("setLightCorrect") && call.desc.equals("(Z)V")) {
                    setsIncorrect = true;
                }
                if (call.owner.equals(ENGINE) && call.name.equals("addTask") && call.desc.equals(ADD_TASK_DESCRIPTOR)) {
                    propagates = true;
                }
            }
        }
        if (!propagates || !setsIncorrect) {
            throw new IllegalStateException(ENGINE + ".lightChunk changed shape (setLightCorrect=" + setsIncorrect
                    + ", addTask=" + propagates + ")");
        }
    }

    /** BrowserLightEngineHooks.PRE_UPDATE/POST_UPDATE are TaskType ordinals 0 and 1. */
    private static void requireTaskTypeOrder(ClassNode taskType) {
        MethodNode clinit = find(taskType, "<clinit>", "()V");
        int found = 0;
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String name) {
                AbstractInsnNode next = ldc.getNext();
                int ordinal = next != null && next.getOpcode() >= Opcodes.ICONST_0 && next.getOpcode() <= Opcodes.ICONST_5
                        ? next.getOpcode() - Opcodes.ICONST_0 : -1;
                if (name.equals("PRE_UPDATE") && ordinal == 0 || name.equals("POST_UPDATE") && ordinal == 1) {
                    found++;
                }
            }
        }
        boolean fields = taskType.fields.stream().anyMatch(field -> field.name.equals("PRE_UPDATE"))
                && taskType.fields.stream().anyMatch(field -> field.name.equals("POST_UPDATE"));
        if (found != 2 || !fields) {
            throw new IllegalStateException(TASK_TYPE + " no longer declares PRE_UPDATE=0, POST_UPDATE=1");
        }
    }

    private static MethodNode lightChunk(MethodNode vanilla) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "lightChunk", LIGHT_CHUNK_DESCRIPTOR,
                vanilla.signature, null);
        LabelNode useVanilla = new LabelNode();
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL, "lightChunk",
                "(L" + ENGINE + ";L" + CHUNK_ACCESS + ";Z)L" + FUTURE + ";", false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNULL, useVanilla));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(useVanilla);
        code.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {FUTURE}));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ENGINE, VANILLA_LIGHT_CHUNK, LIGHT_CHUNK_DESCRIPTOR,
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 3;
        method.maxLocals = 3;
        return method;
    }

    /** Head of {@code checkBlock}: {@code BrowserLightKernel.noteCheckBlock(this, pos)}. */
    private static void hookCheckBlock(MethodNode checkBlock) {
        for (AbstractInsnNode instruction : checkBlock.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(KERNEL)) {
                throw new IllegalStateException(ENGINE + ".checkBlock is already hooked");
            }
        }
        boolean queues = false;
        for (AbstractInsnNode instruction : checkBlock.instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals(TASK_TYPE)
                    && field.name.equals("PRE_UPDATE")) {
                queues = true;
            }
        }
        if (!queues) {
            throw new IllegalStateException(ENGINE + ".checkBlock no longer queues a PRE_UPDATE task");
        }
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL, "noteCheckBlock",
                "(L" + ENGINE + ";L" + BLOCK_POS + ";)V", false));
        checkBlock.instructions.insert(hook);
        checkBlock.maxStack = Math.max(checkBlock.maxStack, 2);
    }

    private static MethodNode addLightTask() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, ADD_LIGHT_TASK, "(IIILjava/lang/Runnable;)V", null,
                null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TASK_TYPE, "values", "()[L" + TASK_TYPE + ";", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ENGINE, "addTask", ADD_TASK_DESCRIPTOR, false));
        code.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 5;
        method.maxLocals = 5;
        return method;
    }

    private static MethodNode propagateVanilla() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, PROPAGATE_VANILLA, PROPAGATE_DESCRIPTOR, null, null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LEVEL_ENGINE, "propagateLightSources",
                PROPAGATE_DESCRIPTOR, false));
        code.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        return method;
    }

    /** Reads {@code owner} from {@code outputRoot} when an earlier step wrote it, else the jar. */
    private static ClassNode read(Path jar, Path outputRoot, String owner) throws IOException {
        Path written = outputRoot.resolve(owner + ".class");
        byte[] bytes;
        if (Files.isRegularFile(written)) {
            bytes = Files.readAllBytes(written);
        } else {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(owner + ".class");
                if (entry == null) {
                    throw new IOException("Missing class entry " + owner + ".class in " + jar);
                }
                try (var stream = zip.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(node.name + "." + name + descriptor + " was not found"));
    }

    /**
     * Writes without recomputing frames: the added methods supply theirs, and the checkBlock
     * hook only adds straight-line code at the head (no frame there changes).
     */
    private static void write(ClassNode node, Path outputRoot) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = outputRoot.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
