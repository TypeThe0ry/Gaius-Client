package dev.gaius.tools;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Negative bytecode fixtures for patcher idempotence guards. */
public final class PatcherNegativeBytecodeFixture {
    private static final String TELEMETRY = "dev.gaius.tools.MinecraftChunkDrawTelemetryPatcher";
    private static final String DRAW_TELEMETRY = "dev/gaius/browser/BrowserChunkDrawTelemetry";
    private static final String DRAW_OWNER = "com/mojang/renderpearl/api/commands/RenderPass$Draw";
    private static final String DRAW_BACKEND = "com/mojang/renderpearl/backend/api/RenderPassBackend";
    private static final String DRAW_DESCRIPTOR = "(IIIII)V";

    private PatcherNegativeBytecodeFixture() {
    }

    public static void main(String[] args) throws Exception {
        Method drawShape = Class.forName(TELEMETRY + "$ProfileShape")
                .getDeclaredMethod("forId", String.class);
        drawShape.setAccessible(true);
        Object shape = drawShape.invoke(null, "26.3");
        Method patchDraw = Class.forName(TELEMETRY).getDeclaredMethod(
                "patchGlCommandEncoder", byte[].class, shape.getClass());
        patchDraw.setAccessible(true);

        byte[] drawPatched = (byte[]) patchDraw.invoke(null, bytes(drawFixture(true)), shape);
        ClassNode drawClass = read(drawPatched);
        MethodNode drawMethod = drawMethod(drawClass);
        int commits = count(drawMethod, DRAW_TELEMETRY, "commitSuccessfulDraw");
        check(commits == 2, "a commit for another draw local suppressed the target injection");
        check(hasTargetCommit(drawMethod), "target draw path has no commit producer");
        check(count(drawMethod, DRAW_TELEMETRY, "beginDraw") == 1, "first pass must inject one beginDraw");

        // Re-entering the chain on an already instrumented class must not add a second commit
        // for either draw path (the decoy path keeps its own single commit).
        MethodNode drawTwice = drawMethod(read((byte[]) patchDraw.invoke(null, drawPatched, shape)));
        check(count(drawTwice, DRAW_TELEMETRY, "commitSuccessfulDraw") == 2,
                "second pass injected another commitSuccessfulDraw");
        check(count(drawTwice, DRAW_TELEMETRY, "beginDraw") == 1, "second pass injected another beginDraw");
        check(hasTargetCommit(drawTwice), "second pass lost the target commit producer");

        // A vanilla-shaped method without the decoy commit: two passes still yield exactly one
        // commit on the single draw path.
        byte[] vanillaOnce = (byte[]) patchDraw.invoke(null, bytes(drawFixture(false)), shape);
        byte[] vanillaTwice = (byte[]) patchDraw.invoke(null, vanillaOnce, shape);
        MethodNode vanillaOnceMethod = drawMethod(read(vanillaOnce));
        MethodNode vanillaTwiceMethod = drawMethod(read(vanillaTwice));
        check(count(vanillaOnceMethod, DRAW_TELEMETRY, "commitSuccessfulDraw") == 1,
                "first pass on a vanilla shape must inject exactly one commit");
        check(count(vanillaTwiceMethod, DRAW_TELEMETRY, "commitSuccessfulDraw") == 1,
                "second pass on a vanilla shape injected another commit");
        check(count(vanillaTwiceMethod, DRAW_TELEMETRY, "beginDraw") == 1,
                "second pass on a vanilla shape injected another beginDraw");
        check(hasTargetCommit(vanillaTwiceMethod), "vanilla shape lost the target commit producer");

        Method patchWorker = Class.forName("dev.gaius.tools.patches.serverworker.WorkerOverridePatches")
                .getDeclaredMethod("patchChunkTaskDispatcher", ClassNode.class);
        patchWorker.setAccessible(true);
        expectIllegalState(patchWorker, decoyDispatcher(),
                "decoy worldgen/dispatcher strings were accepted as an existing patch");
        patchWorker.invoke(null, existingDispatcher());
        System.out.println("PATCHER_NEGATIVE_BYTECODE_PASS draw-decoy-local draw-idempotent-rerun worker-decoy-strings");
    }

    private static MethodNode drawMethod(ClassNode node) {
        return node.methods.stream()
                .filter(method -> method.name.equals("drawMultipleIndexed"))
                .findFirst().orElseThrow();
    }

    private static ClassNode drawFixture(boolean decoyCommit) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "com/mojang/renderpearl/frontend/FrontendRenderPass";
        node.superName = "java/lang/Object";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "drawMultipleIndexed",
                "(Ljava/util/Collection;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"
                        + "Lcom/mojang/renderpearl/api/pipeline/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V",
                null, null);
        InsnList code = method.instructions;
        if (decoyCommit) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 7));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DRAW_OWNER, "indexCount", "()I", false));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DRAW_TELEMETRY,
                    "commitSuccessfulDraw", "(I)V", false));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 9));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DRAW_OWNER, "uniformUploaderConsumer",
                "()Ljava/util/function/BiConsumer;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 10));
        code.add(new VarInsnNode(Opcodes.ALOAD, 10));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/BiConsumer", "accept",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", true));
        code.add(new VarInsnNode(Opcodes.ALOAD, 9));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, DRAW_BACKEND, "drawIndexed",
                DRAW_DESCRIPTOR, true));
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        return node;
    }

    private static ClassNode decoyDispatcher() {
        ClassNode node = dispatcherBase();
        MethodNode method = node.methods.get(0);
        method.instructions.add(new LdcInsnNode("worldgen"));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new LdcInsnNode("dispatcher"));
        method.instructions.add(new InsnNode(Opcodes.POP));
        addConstructorCall(method);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return node;
    }

    private static ClassNode existingDispatcher() {
        ClassNode node = dispatcherBase();
        MethodNode method = node.methods.get(0);
        LabelNode defaultName = new LabelNode();
        LabelNode join = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new LdcInsnNode("worldgen"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "net/minecraft/util/thread/TaskScheduler", "name", "()Ljava/lang/String;", true));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, defaultName));
        method.instructions.add(new LdcInsnNode("worldgen-dispatcher"));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
        method.instructions.add(defaultName);
        method.instructions.add(new LdcInsnNode("dispatcher"));
        method.instructions.add(join);
        addConstructorCall(method);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return node;
    }

    private static ClassNode dispatcherBase() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "net/minecraft/server/level/ChunkTaskDispatcher";
        node.superName = "java/lang/Object";
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/util/thread/TaskScheduler;Ljava/util/concurrent/Executor;)V",
                null, null));
        return node;
    }

    private static void addConstructorCall(MethodNode method) {
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "net/minecraft/util/thread/PriorityConsecutiveExecutor", "<init>", "(ILjava/lang/Runnable;Ljava/lang/String;)V", false));
    }

    private static void expectIllegalState(Method method, ClassNode node, String message) throws Exception {
        try {
            method.invoke(null, node);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof IllegalStateException) {
                return;
            }
            throw error;
        }
        throw new AssertionError(message);
    }

    private static int count(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name)) count++;
        }
        return count;
    }

    private static boolean hasTargetCommit(MethodNode method) {
        for (var instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals(DRAW_BACKEND) || !call.name.equals("drawIndexed")) continue;
            var load = nextOpcode(instruction);
            var index = nextOpcode(load);
            var commit = nextOpcode(index);
            return commit instanceof MethodInsnNode c && c.name.equals("commitSuccessfulDraw")
                    && index instanceof MethodInsnNode i && i.name.equals("indexCount")
                    && load instanceof VarInsnNode v && v.var == 9;
        }
        return false;
    }

    private static org.objectweb.asm.tree.AbstractInsnNode nextOpcode(
            org.objectweb.asm.tree.AbstractInsnNode instruction) {
        var next = instruction == null ? null : instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
