package dev.gaius.tools.patches.mesh;

import static dev.gaius.tools.patches.support.ModernPatchSupport.find;
import static dev.gaius.tools.patches.support.ModernPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ModernPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ModernPatchSupport.read;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireOne;
import static dev.gaius.tools.patches.support.ModernPatchSupport.writeComputeFrames;

import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The mesh domain of the Minecraft262BrowserPatcher step, the {@code modern} patch set arm
 * ({@code patches/mesh}): yields instead of render-thread retries in section compile
 * tasks, bounded cancellation, the latest-mesh guard and the emergency upload, and the mesh
 * kernel registration of the modern profiles.
 *
 * <p>{@code Minecraft262BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft262BrowserPatcher.<method>}); the ASM helpers that other
 * domains of that step share live in ModernPatchSupport and are imported statically.
 */
public final class ModernMeshPatches {
    private ModernMeshPatches() {
    }

    /**
     * Section compiles through the mesh kernel (see {@link MeshKernelPatches}). Both modern
     * profiles must take it; the patch class itself refuses a doTask whose shape it does not know.
     */
    public static void meshKernelPatches(String jar, Path root, String minecraftVersion)
            throws IOException {
        if (!MeshKernelPatches.apply(jar, root, minecraftVersion)) {
            throw new IllegalStateException(
                    "Minecraft " + minecraftVersion + " must route section compiles through the mesh kernel");
        }
    }

    public static void patchSectionRenderTaskRetryYields(String jar, Path root)
            throws IOException {
        String prefix = "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher$RenderSection$";
        for (String task : new String[] {"CompileTask", "ResortTransparencyTask"}) {
            String owner = prefix + task;
            ClassNode node = read(jar, owner + ".class");
            MethodNode method = find(node, "doTask",
                    "(Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)"
                            + "Lnet/minecraft/client/renderer/chunk/"
                            + "SectionRenderDispatcher$RenderSection$SectionTask$"
                            + "SectionTaskResult;");
            boolean compile = task.equals("CompileTask");
            int expectedResultLocal = compile ? 12 : 9;
            int patched = replaceRenderThreadRetryWithYield(method, expectedResultLocal);
            int cancelled = replaceSpinWaitWithBoundedCancellation(method, owner, compile);
            if (compile) {
                recordLatestSectionMesh(method, owner);
            }
            int cleared = clearUploadRetryOnReturns(method);
            addUploadRetryExceptionCleanup(method);
            requireOne(owner + " upload retry yield", patched);
            requireOne(owner + " bounded upload retry cancellation", cancelled);
            if (cleared < 2) {
                throw new IllegalStateException(owner + " return cleanup changed: " + cleared);
            }
            method.maxStack = Math.max(method.maxStack, 3);
            writeComputeFrames(node, root.resolve(owner + ".class"));
        }
    }

    private static int replaceRenderThreadRetryWithYield(
            MethodNode method, int expectedResultLocal) {
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")
                    || !call.name.equals("isOnRenderThread")
                    || !call.desc.equals("()Z")
                    || !(nextOpcode(call) instanceof JumpInsnNode retry)
                    || retry.getOpcode() != Opcodes.IFNE) {
                continue;
            }
            AbstractInsnNode uploadResultJump = previousOpcode(call);
            AbstractInsnNode uploadResultLoad = previousOpcode(uploadResultJump);
            int callIndex = method.instructions.indexOf(call);
            int retryIndex = method.instructions.indexOf(retry.label);
            if (!(uploadResultJump instanceof JumpInsnNode uploadSucceeded)
                    || uploadSucceeded.getOpcode() != Opcodes.IFNE
                    || !(uploadResultLoad instanceof VarInsnNode resultLoad)
                    || resultLoad.getOpcode() != Opcodes.ILOAD
                    || resultLoad.var != expectedResultLocal
                    || retryIndex < 0
                    || retryIndex >= callIndex) {
                throw new IllegalStateException(
                        method.name + " upload retry control-flow shape changed");
            }
            method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
            call.owner = "dev/gaius/browser/BrowserRenderScheduler";
            call.name = "awaitUploadRetry";
            call.desc = "(Ljava/lang/Object;)Z";
            patched++;
        }
        return patched;
    }

    private static int replaceSpinWaitWithBoundedCancellation(
            MethodNode method, String owner, boolean requeueSection) {
        int replaced = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/Thread")
                    && call.name.equals("onSpinWait")
                    && call.desc.equals("()V")) {
                InsnList cancel = new InsnList();
                if (requeueSection) {
                    // A compile that never got upload space would otherwise leave its section
                    // without a mesh update: its dirty flag was cleared when it was extracted.
                    cancel.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    cancel.add(new FieldInsnNode(
                            Opcodes.GETFIELD,
                            owner,
                            "this$1",
                            "Lnet/minecraft/client/renderer/chunk/"
                                    + "SectionRenderDispatcher$RenderSection;"));
                    cancel.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    cancel.add(new FieldInsnNode(
                            Opcodes.GETFIELD,
                            owner,
                            "isCancelled",
                            "Ljava/util/concurrent/atomic/AtomicBoolean;"));
                    cancel.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL,
                            "java/util/concurrent/atomic/AtomicBoolean",
                            "get",
                            "()Z",
                            false));
                    cancel.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "dev/gaius/browser/BrowserSectionAudit",
                            "requeueAfterUploadTimeout",
                            "(Lnet/minecraft/client/renderer/chunk/"
                                    + "SectionRenderDispatcher$RenderSection;Z)V",
                            false));
                }
                cancel.add(new VarInsnNode(Opcodes.ALOAD, 0));
                cancel.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        owner,
                        "cancel",
                        "()V",
                        false));
                method.instructions.insertBefore(call, cancel);
                method.instructions.remove(call);
                replaced++;
            }
            instruction = next;
        }
        return replaced;
    }

    /** Stamps each new compile result as its section's latest mesh (see addLatestSectionMeshGuard). */
    private static void recordLatestSectionMesh(MethodNode method, String owner) {
        String section = "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection";
        int recorded = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !constructor.owner.equals(
                            "net/minecraft/client/renderer/chunk/CompiledSectionMesh")
                    || !constructor.name.equals("<init>")) {
                continue;
            }
            if (!(nextOpcode(constructor) instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ASTORE) {
                throw new IllegalStateException("CompileTask mesh local store shape changed");
            }
            InsnList latest = new InsnList();
            latest.add(new VarInsnNode(Opcodes.ALOAD, 0));
            latest.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "this$1", "L" + section + ";"));
            latest.add(new VarInsnNode(Opcodes.ALOAD, store.var));
            latest.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, section, LATEST_MESH_FIELD, "Ljava/lang/Object;"));
            method.instructions.insert(store, latest);
            recorded++;
        }
        requireOne(owner + " latest section mesh record", recorded);
    }

    private static int clearUploadRetryOnReturns(MethodNode method) {
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) {
                continue;
            }
            InsnList clear = new InsnList();
            clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clear.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "clearUploadRetry",
                    "(Ljava/lang/Object;)V",
                    false));
            method.instructions.insertBefore(instruction, clear);
            patched++;
        }
        return patched;
    }

    private static void addUploadRetryExceptionCleanup(MethodNode method) {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.insertBefore(method.instructions.getFirst(), start);
        int throwableLocal = method.maxLocals++;
        InsnList cleanup = new InsnList();
        cleanup.add(end);
        cleanup.add(handler);
        cleanup.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "clearUploadRetry",
                "(Ljava/lang/Object;)V",
                false));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        cleanup.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(cleanup);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                start, end, handler, "java/lang/Throwable"));
        method.maxStack = Math.max(method.maxStack, 1);
    }

    public static void patchSectionRenderEmergencyUpload(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher$RenderSection";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "addSectionBuffersToUberBuffer",
                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"
                        + "Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;"
                        + "Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Z");
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(
                            "net/minecraft/client/renderer/chunk/SectionRenderDispatcher")
                    || !call.name.equals("uploadTerrainBuffersToGpu")
                    || !call.desc.equals("()V")) {
                continue;
            }
            method.instructions.insertBefore(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "requestEmergencyUpload",
                    "()V",
                    false));
            patched++;
        }
        requireOne("RenderSection staging-capacity emergency upload", patched);
        addLatestSectionMeshGuard(node, owner);
        writeComputeFrames(node, root.resolve(owner + ".class"));
        System.out.println("Guarded current section staging retries with one progress upload");
    }

    static final String LATEST_MESH_FIELD = "gaius$latestMesh";

    /**
     * Only the newest compile of a section may install its mesh.
     *
     * <p>The browser uploads staged terrain buffers over several frames in hash order, so an
     * older compile of a section can finish uploading after a newer one. Vanilla's
     * checkSectionMesh installs any fully uploaded mesh, which then replaces the newer mesh with
     * stale geometry (for example a mesh compiled before the chunk's light arrived, which stays
     * black). CompileTask records its mesh as the section's latest; checkSectionMesh drops and
     * releases any other fully uploaded mesh unless it is the one already installed. reset()
     * clears the latest mesh so geometry compiled for a previous section position is dropped
     * too.</p>
     */
    private static void addLatestSectionMeshGuard(ClassNode node, String owner) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(LATEST_MESH_FIELD)) {
                throw new IllegalStateException("RenderSection latest mesh field already exists");
            }
        }
        node.fields.add(new FieldNode(
                Opcodes.ACC_PUBLIC, LATEST_MESH_FIELD, "Ljava/lang/Object;", null, null));

        MethodNode reset = find(node, "reset", "()V");
        InsnList clear = new InsnList();
        clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
        clear.add(new InsnNode(Opcodes.ACONST_NULL));
        clear.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, LATEST_MESH_FIELD, "Ljava/lang/Object;"));
        reset.instructions.insert(clear);
        reset.maxStack = Math.max(reset.maxStack, 2);

        String mesh = "net/minecraft/client/renderer/chunk/CompiledSectionMesh";
        MethodNode check = find(node, "checkSectionMesh", "(L" + mesh + ";)V");
        // The mesh is installed only after every layer uploaded: "if (uploaded && current != mesh)".
        JumpInsnNode allUploaded = null;
        int allUploadedGates = 0;
        for (AbstractInsnNode instruction : check.instructions.toArray()) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFEQ
                    && previousOpcode(jump) instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ILOAD
                    && load.var == 2
                    && nextOpcode(jump) instanceof VarInsnNode self
                    && self.getOpcode() == Opcodes.ALOAD
                    && self.var == 0
                    && nextOpcode(self) instanceof FieldInsnNode current
                    && current.getOpcode() == Opcodes.GETFIELD
                    && current.name.equals("sectionMesh")) {
                allUploaded = jump;
                allUploadedGates++;
            }
        }
        requireOne("RenderSection.checkSectionMesh all-layers-uploaded gate", allUploadedGates);
        LabelNode latest = new LabelNode();
        LabelNode keepInstalled = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, LATEST_MESH_FIELD, "Ljava/lang/Object;"));
        guard.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, latest));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "sectionMesh",
                "Ljava/util/concurrent/atomic/AtomicReference;"));
        guard.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicReference",
                "get", "()Ljava/lang/Object;", false));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, keepInstalled));
        guard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "dev/gaius/browser/BrowserSectionAudit",
                "staleMeshRejected", "()V", false));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "releaseSectionMesh",
                "(Lnet/minecraft/client/renderer/chunk/SectionMesh;)V", false));
        guard.add(keepInstalled);
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(latest);
        check.instructions.insert(allUploaded, guard);
        check.maxStack = Math.max(check.maxStack, 2);
    }
}
