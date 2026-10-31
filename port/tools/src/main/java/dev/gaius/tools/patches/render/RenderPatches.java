package dev.gaius.tools.patches.render;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.getter;
import static dev.gaius.tools.patches.support.ClientPatchSupport.jarHasClass;
import static dev.gaius.tools.patches.support.ClientPatchSupport.minecraftEvent;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.renderName;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import dev.gaius.tools.BuildTarget;
import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The render domain of the MinecraftClientPatcher step ({@code patches/render}): the
 * blaze3d GL backend on WebGL (device, constants, state manager, pipelines, command encoder,
 * GLSL preprocessor), textures and images, frame timing, the level renderer's section graph and
 * block-break paths, particle and entity render safety, and GPU buffer pools.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically.
 */
public final class RenderPatches {
    private RenderPatches() {
    }

    private static final int BROWSER_GPU_RETIRE_SLOTS = 8;

    /** 26.2 browser texture path: keep TextureUtil's public ABI and forward to the bulk helper. */
    public static void patchTextureUtilBrowserSolidify(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/TextureUtil.class");
        MethodNode method = find(
                node,
                "solidify",
                "(Lcom/mojang/blaze3d/platform/NativeImage;)V");
        if (method == null || (method.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("TextureUtil.solidify ABI changed");
        }
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserTextureSolidifier",
                "solidifyBulk",
                "(Lcom/mojang/blaze3d/platform/NativeImage;)V",
                false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        writeComputeFrames(node, output);
    }

    public static void patchFramerateLimitTrackerBrowserNoThrottle(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/FramerateLimitTracker.class");
        MethodNode method = find(
                node,
                "getThrottleReason",
                "()Lcom/mojang/blaze3d/platform/FramerateLimitTracker$FramerateThrottleReason;");
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "com/mojang/blaze3d/platform/FramerateLimitTracker$FramerateThrottleReason",
                "NONE",
                "Lcom/mojang/blaze3d/platform/FramerateLimitTracker$FramerateThrottleReason;"));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 1);
        write(node, output);
    }

    public static void patchRenderSystemBrowserDeadlineCompensation(String jar, Path outputRoot)
            throws IOException {
        String currentEntry = "net/minecraft/client/FramerateLimiter.class";
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(currentEntry) != null) {
                patchCurrentFramerateLimiter(
                        jar, outputRoot.resolve(currentEntry));
                return;
            }
        }
        String owner = "com/mojang/blaze3d/systems/RenderSystem";
        String helperName = "browserCompensateFrameTime";
        ClassNode node = read(jar, owner + ".class");
        MethodNode limit = find(node, "limitDisplayFPS", "(I)V");
        FieldInsnNode finalTimestampStore = null;
        for (AbstractInsnNode instruction = limit.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.owner.equals(owner)
                    && field.name.equals("lastDrawTime")
                    && field.desc.equals("D")) {
                if (finalTimestampStore != null) {
                    throw new IllegalStateException(
                            "RenderSystem.limitDisplayFPS has multiple final timestamp stores");
                }
                finalTimestampStore = field;
            }
        }
        if (finalTimestampStore == null) {
            throw new IllegalStateException(
                    "RenderSystem.limitDisplayFPS final timestamp store was not found");
        }
        AbstractInsnNode nowLoad = previousOpcode(finalTimestampStore);
        if (!(nowLoad instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.DLOAD
                || load.var != 3) {
            throw new IllegalStateException(
                    "RenderSystem.limitDisplayFPS final current-time load changed");
        }
        InsnList compensate = new InsnList();
        compensate.add(new VarInsnNode(Opcodes.DLOAD, 1));
        compensate.add(new VarInsnNode(Opcodes.DLOAD, 3));
        compensate.add(new VarInsnNode(Opcodes.ILOAD, 0));
        compensate.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                helperName,
                "(DDI)D",
                false));
        limit.instructions.insertBefore(nowLoad, compensate);
        limit.instructions.remove(nowLoad);
        limit.maxStack = Math.max(limit.maxStack, 5);

        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                helperName,
                "(DDI)D",
                null,
                null);
        LabelNode returnNow = new LabelNode();
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFLE, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 2));
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.DCMPL));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFLT, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 2));
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.DSUB));
        helper.instructions.add(new InsnNode(Opcodes.DCONST_1));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        helper.instructions.add(new InsnNode(Opcodes.I2D));
        helper.instructions.add(new InsnNode(Opcodes.DDIV));
        helper.instructions.add(new InsnNode(Opcodes.DCMPL));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFGE, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.DRETURN));
        helper.instructions.add(returnNow);
        helper.instructions.add(new VarInsnNode(Opcodes.DLOAD, 2));
        helper.instructions.add(new InsnNode(Opcodes.DRETURN));
        helper.maxStack = 6;
        helper.maxLocals = 5;
        node.methods.add(helper);
        writeComputeFrames(node, outputRoot.resolve(owner + ".class"));
    }

    private static void patchCurrentFramerateLimiter(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/FramerateLimiter";
        String helperName = "browserCompensateFrameTime";
        ClassNode node = read(jar, owner + ".class");
        MethodNode limit = find(node, "limitDisplayFPS", "(I)V");
        FieldInsnNode finalTimestampStore = null;
        for (AbstractInsnNode instruction = limit.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.owner.equals(owner)
                    && field.name.equals("lastFrameTime")
                    && field.desc.equals("J")) {
                finalTimestampStore = field;
            }
        }
        if (finalTimestampStore == null) {
            throw new IllegalStateException(
                    "FramerateLimiter final timestamp store was not found");
        }
        AbstractInsnNode nowCall = previousOpcode(finalTimestampStore);
        if (!(nowCall instanceof MethodInsnNode call)
                || call.getOpcode() != Opcodes.INVOKESTATIC
                || !call.owner.equals("java/lang/System")
                || !call.name.equals("nanoTime")
                || !call.desc.equals("()J")) {
            throw new IllegalStateException(
                    "FramerateLimiter final current-time call changed");
        }
        InsnList compensate = new InsnList();
        compensate.add(new VarInsnNode(Opcodes.LLOAD, 3));
        compensate.add(new VarInsnNode(Opcodes.ILOAD, 0));
        compensate.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                helperName,
                "(JI)J",
                false));
        limit.instructions.insertBefore(nowCall, compensate);
        limit.instructions.remove(nowCall);
        limit.maxStack = Math.max(limit.maxStack, 4);

        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                helperName,
                "(JI)J",
                null,
                null);
        LabelNode returnNow = new LabelNode();
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "nanoTime",
                "()J",
                false));
        helper.instructions.add(new VarInsnNode(Opcodes.LSTORE, 3));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFLE, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 3));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.LCMP));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFLT, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 3));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.LSUB));
        helper.instructions.add(new LdcInsnNode(1_000_000_000L));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        helper.instructions.add(new InsnNode(Opcodes.I2L));
        helper.instructions.add(new InsnNode(Opcodes.LDIV));
        helper.instructions.add(new InsnNode(Opcodes.LCMP));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFGE, returnNow));
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        helper.instructions.add(new InsnNode(Opcodes.LRETURN));
        helper.instructions.add(returnNow);
        helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, 3));
        helper.instructions.add(new InsnNode(Opcodes.LRETURN));
        helper.maxStack = 6;
        helper.maxLocals = 5;
        node.methods.add(helper);
        writeComputeFrames(node, output);
    }

    public static void patchFaceBakeryBrowserFloatTolerance(String jar, Path root)
            throws IOException {
        String oldEntry = "net/minecraft/client/renderer/block/model/FaceBakery.class";
        String currentEntry = "net/minecraft/client/resources/model/cuboid/FaceBakery.class";
        String entry;
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(oldEntry) != null) {
                entry = oldEntry;
            } else if (input.getEntry(currentEntry) != null) {
                entry = currentEntry;
            } else {
                throw new IllegalStateException("FaceBakery browser target was not found");
            }
        }
        ClassNode node = read(jar, entry);
        MethodNode method = find(node, "findVertex", "([Lorg/joml/Vector3fc;IFFF)I");

        InsnList code = new InsnList();
        LabelNode loop = new LabelNode();
        LabelNode next = new LabelNode();
        LabelNode end = new LabelNode();

        // Vanilla compares the baked winding vertex coordinates with exact fcmpl checks.
        // In the TeaVM/WebGL path tiny float differences can appear during model baking,
        // which turns valid quads into "Can't find vertex to swap" warnings and stalls
        // startup. Keep the same search order, but accept a tight browser-safe epsilon.
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));

        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));

        addFloatAbsGreaterThanEpsilonJump(code, 2, "x", next);
        addFloatAbsGreaterThanEpsilonJump(code, 3, "y", next);
        addFloatAbsGreaterThanEpsilonJump(code, 4, "z", next);

        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new InsnNode(Opcodes.IRETURN));

        code.add(next);
        code.add(new IincInsnNode(5, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));

        code.add(end);
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new InsnNode(Opcodes.IRETURN));

        replace(method, code, 4, 7);
        write(node, root.resolve(entry));
    }

    private static void addFloatAbsGreaterThanEpsilonJump(
            InsnList code, int expectedLocal, String accessor, LabelNode jumpTarget) {
        code.add(new VarInsnNode(Opcodes.FLOAD, expectedLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "org/joml/Vector3fc",
                accessor,
                "()F",
                true));
        code.add(new InsnNode(Opcodes.FSUB));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Math",
                "abs",
                "(F)F",
                false));
        code.add(new LdcInsnNode(Float.valueOf(1.0E-4f)));
        code.add(new InsnNode(Opcodes.FCMPG));
        code.add(new JumpInsnNode(Opcodes.IFGT, jumpTarget));
    }

    public static void patchNativeImageBrowserPng(String jar, Path output) throws IOException {
        String owner = "com/mojang/blaze3d/platform/NativeImage";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        MethodNode method = find(node, "writeToChannel", "(Ljava/nio/channels/WritableByteChannel;)Z");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "pixels", "J"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "width", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "height", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "format", "L" + owner + "$Format;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner + "$Format", "components", "()I", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/gaius/browser/BrowserPngWriter", "write",
                "(Ljava/nio/channels/WritableByteChannel;JIII)Z", false));
        code.add(new InsnNode(Opcodes.IRETURN));
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        writeComputeFrames(node, output);
        System.out.println("Replaced NativeImage PNG callback with browser channel encoding");
    }

    /**
     * Gives {@code NativeImage.copyRect(NativeImage, ...)} a row-copy fast path
     * ({@code BrowserNativeImageCopy}): unflipped RGBA rectangles between two images are copied
     * with one memCopy per row instead of a getPixel/setPixel pair per pixel. Any other call falls
     * through to the vanilla loop.
     */
    public static void patchNativeImageBulkCopyRect(String jar, Path output) throws IOException {
        String owner = "com/mojang/blaze3d/platform/NativeImage";
        if (!jarHasClass(jar, owner)) {
            throw new IllegalStateException("NativeImage class is absent");
        }
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        String descriptor = "(L" + owner + ";IIIIIIZZ)V";
        MethodNode copyRect = find(node, "copyRect", descriptor);
        LabelNode vanilla = new LabelNode();
        InsnList fast = new InsnList();
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        for (int local = 2; local <= 7; local++) {
            fast.add(new VarInsnNode(Opcodes.ILOAD, local));
        }
        fast.add(new VarInsnNode(Opcodes.ILOAD, 8));
        fast.add(new VarInsnNode(Opcodes.ILOAD, 9));
        fast.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserNativeImageCopy",
                "copyRect",
                "(L" + owner + ";L" + owner + ";IIIIIIZZ)Z",
                false));
        fast.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        fast.add(new InsnNode(Opcodes.RETURN));
        fast.add(vanilla);
        copyRect.instructions.insert(fast);
        writeComputeFrames(node, output);
        System.out.println("Added the NativeImage.copyRect row-copy fast path");
    }

    public static void patchDynamicUniformsBrowserInitialCapacity(String jar, Path output)
            throws IOException {
        if (ModernSymbols.cached(jar).renderpearl()) {
            // 26.3 renamed the class; main() still names the 26.2 entry, so write the sibling.
            patchDynamicGpuDataBrowserInitialCapacity(
                    jar, output.resolveSibling("DynamicGpuData.class"));
            return;
        }
        ClassNode node = read(jar, "net/minecraft/client/renderer/DynamicUniforms.class");
        MethodNode constructor = find(node, "<init>", "()V");
        // Multiplayer terrain can expose tens of thousands of section records during
        // the first render burst. Starting at 128 made the browser reallocate and copy
        // the UBO repeatedly on the render thread (128 -> 256 -> ... -> 65536), which
        // presents as an FPS collapse while chunks are arriving. Reserve a moderate
        // initial slab so the first burst stays on the GPU upload path without the
        // resize storm; use the largest positive SIPUSH literal so the initial
        // browser slab stays valid in bytecode and covers almost the full burst.
        int[] browserCapacities = {32767, 32767};
        int storageConstructors = 0;
        for (var instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("net/minecraft/client/renderer/DynamicUniformStorage")
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(Ljava/lang/String;II)V")) {
                continue;
            }
            if (storageConstructors >= browserCapacities.length) {
                throw new IllegalStateException(
                        "Unexpected extra DynamicUniformStorage constructor in DynamicUniforms");
            }
            AbstractInsnNode capacity = previousRealInstruction(call);
            if (capacity == null || capacity.getOpcode() != Opcodes.ICONST_2) {
                throw new IllegalStateException(
                        "DynamicUniforms initial capacity instruction was not iconst_2 before constructor "
                                + storageConstructors);
            }
            replaceDynamicGpuDataCapacity(constructor, capacity,
                    "DynamicUniforms storage " + storageConstructors,
                    browserCapacities[storageConstructors]);
            storageConstructors++;
        }
        if (storageConstructors != browserCapacities.length) {
            throw new IllegalStateException(
                    "Expected 2 DynamicUniformStorage constructors in DynamicUniforms, got "
                            + storageConstructors);
        }
        writeComputeFrames(node, output);
    }

    /**
     * 26.3 shape of {@link #patchDynamicUniformsBrowserInitialCapacity}: DynamicUniforms became
     * DynamicGpuData and DynamicUniformStorage(String;II) became
     * DynamicGpuDataStorageMapped(String;III) (label, block size, usage, capacity). The
     * constructor builds only the transforms UBO and the once-per-frame terrain transform UBO;
     * the chunk-sections UBO is created lazily in writeChunkSections. Both hot UBOs get the same
     * 32767-entry browser slab as on 26.2; each storage is identified by its ldc label, and the
     * terrain transform and MDI instanced storages stay vanilla. On the WebAssembly GC build the
     * slab is the default of a run-time choice ({@code ?gaiusUboCap=}, see
     * {@link #replaceDynamicGpuDataCapacity}).
     */
    private static void patchDynamicGpuDataBrowserInitialCapacity(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/renderer/DynamicGpuData";
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry("net/minecraft/client/renderer/DynamicUniforms.class") != null) {
                throw new IllegalStateException(
                        "renderpearl client still contains the 26.2 DynamicUniforms class");
            }
        }
        ClassNode node = read(jar, owner + ".class");
        MethodNode constructor = find(node, "<init>", "()V");
        MethodNode writeChunkSections = find(node, "writeChunkSections",
                "([L" + owner + "$ChunkSectionInfo;)"
                        + "[Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;");
        raiseDynamicGpuDataCapacity(constructor, "Dynamic Transforms UBO", 2, "Terrain UBO");
        raiseDynamicGpuDataCapacity(writeChunkSections, "Chunk Sections UBO", 1, null);
        writeComputeFrames(node, output);
        System.out.println("Raised 26.3 DynamicGpuData transforms and chunk-section UBO capacity");
    }

    /**
     * Replaces the {@code iconst_2} capacity of the one DynamicGpuDataStorageMapped constructor
     * labelled {@code label} with the browser slab; {@code expectedStorages} counts every
     * mapped-storage constructor of the method, and {@code untouchedLabel} names the only other
     * one allowed.
     */
    private static void raiseDynamicGpuDataCapacity(MethodNode method, String label,
            int expectedStorages, String untouchedLabel) {
        String storage = "net/minecraft/client/renderer/DynamicGpuDataStorageMapped";
        int storages = 0;
        int raised = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals(storage)
                    || !call.name.equals("<init>")) {
                continue;
            }
            storages++;
            AbstractInsnNode capacity = previousOpcode(call);
            AbstractInsnNode usage = capacity == null ? null : previousOpcode(capacity);
            AbstractInsnNode blockSize = usage == null ? null : previousOpcode(usage);
            AbstractInsnNode labelLoad = blockSize == null ? null : previousOpcode(blockSize);
            if (!call.desc.equals("(Ljava/lang/String;III)V")
                    || !(labelLoad instanceof LdcInsnNode constant)
                    || !(constant.cst instanceof String storageLabel)
                    || !(blockSize instanceof FieldInsnNode sizeField)
                    || sizeField.getOpcode() != Opcodes.GETSTATIC
                    || !(usage instanceof IntInsnNode usageValue)
                    || usageValue.getOpcode() != Opcodes.SIPUSH
                    || usageValue.operand != 128) {
                throw new IllegalStateException(method.name
                        + " DynamicGpuDataStorageMapped constructor shape changed");
            }
            if (storageLabel.equals(label)) {
                if (capacity.getOpcode() != Opcodes.ICONST_2) {
                    throw new IllegalStateException(label
                            + " initial capacity instruction was not iconst_2");
                }
                replaceDynamicGpuDataCapacity(method, capacity, label, 32767);
                raised++;
            } else if (!storageLabel.equals(untouchedLabel)) {
                throw new IllegalStateException(method.name
                        + " has an unexpected mapped storage: " + storageLabel);
            }
        }
        if (storages != expectedStorages || raised != 1) {
            throw new IllegalStateException(method.name + " expected " + expectedStorages
                    + " mapped storages with one " + label + ", got " + storages + "/" + raised);
        }
    }

    /**
     * Replaces the vanilla {@code iconst_2} initial capacity {@code capacity} of a uniform storage
     * constructor. The internal JavaScript build gets the fixed browser slab as before; the
     * WebAssembly GC build asks
     * {@code BrowserGpuDataCapacity.capacity(label, 2, browserCapacity)}, which returns the slab
     * unless {@code ?gaiusUboCap=} selects another capacity for an A/B run.
     */
    private static void replaceDynamicGpuDataCapacity(MethodNode method, AbstractInsnNode capacity,
            String label, int browserCapacity) {
        if (!BuildTarget.wasmGc()) {
            method.instructions.set(capacity, new IntInsnNode(Opcodes.SIPUSH, browserCapacity));
            return;
        }
        InsnList choose = new InsnList();
        choose.add(new LdcInsnNode(label));
        choose.add(new InsnNode(Opcodes.ICONST_2));
        choose.add(new IntInsnNode(Opcodes.SIPUSH, browserCapacity));
        choose.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/quality/BrowserGpuDataCapacity", "capacity",
                "(Ljava/lang/String;II)I", false));
        method.instructions.insertBefore(capacity, choose);
        method.instructions.remove(capacity);
    }

    /**
     * Retains only signalled StagedVertexBuffer pool entries across browser frames. The vanilla
     * used-to-fence-to-pending path remains byte-for-byte intact; the helper owns only the
     * available list and applies its bounded count, byte and idle-frame policy after selection.
     */
    public static void patchStagedVertexBufferGpuPoolCache(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool";
        ModernSymbols symbols = ModernSymbols.cached(jar);
        // 26.3 moved tryRecycleBuffers from the start of acquire to the tail of endFrame and
        // uses the renderpearl GpuBuffer/GpuFence/GpuDevice interfaces; everything else matches.
        boolean recycleAtEndFrame = symbols.renderpearl();
        String profileLabel = recycleAtEndFrame ? "renderpearl" : "blaze3d";
        String buffer = symbols.renderType("com/mojang/blaze3d/buffers/GpuBuffer");
        String fence = symbols.renderType("com/mojang/blaze3d/buffers/GpuFence");
        String device = symbols.renderType("com/mojang/blaze3d/systems/GpuDevice");
        String helper = "dev/gaius/browser/BrowserGpuBufferPoolCache";
        String helperDescriptor = "L" + helper + ";";
        String cacheField = "gaius$browserCache";
        ClassNode node = read(jar, owner + ".class");
        String[] expectedFieldNames = {
            "BUFFER_SIZE_INCREMENT", "MAX_REUSE_SIZE_FACTOR", "label", "usage",
            "available", "usedThisFrame", "pendingRecycle"
        };
        String[] expectedFieldDescriptors = {
            "I", "I", "Ljava/util/function/Supplier;", "I",
            "Ljava/util/List;", "Ljava/util/List;", "Ljava/util/List;"
        };
        int[] expectedFieldAccess = {
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL
        };
        if (node.fields.size() != expectedFieldNames.length) {
            throw new IllegalStateException(
                    owner + " raw field count changed: " + node.fields.size());
        }
        for (int index = 0; index < expectedFieldNames.length; index++) {
            FieldNode field = node.fields.get(index);
            if (!field.name.equals(expectedFieldNames[index])
                    || !field.desc.equals(expectedFieldDescriptors[index])
                    || field.access != expectedFieldAccess[index]) {
                throw new IllegalStateException(
                        owner + " raw field shape changed at " + index + ": "
                                + field.name + field.desc + " access=" + field.access);
            }
        }
        if (!Integer.valueOf(262144).equals(node.fields.get(0).value)
                || !Integer.valueOf(4).equals(node.fields.get(1).value)) {
            throw new IllegalStateException(owner + " raw pool constants changed");
        }

        String pendingOwner = owner + "$PendingRecycle";
        ClassNode pendingNode = read(jar, pendingOwner + ".class");
        if ((pendingNode.access & Opcodes.ACC_FINAL) == 0 || pendingNode.fields.size() != 2) {
            throw new IllegalStateException(pendingOwner + " raw class/field shape changed");
        }
        FieldNode pendingBuffers = pendingNode.fields.get(0);
        FieldNode pendingFence = pendingNode.fields.get(1);
        int privateFinal = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
        if (!pendingBuffers.name.equals("buffers")
                || !pendingBuffers.desc.equals("Ljava/util/List;")
                || pendingBuffers.access != privateFinal
                || !pendingFence.name.equals("fence")
                || !pendingFence.desc.equals("L" + fence + ";")
                || pendingFence.access != privateFinal) {
            throw new IllegalStateException(pendingOwner + " raw fields changed");
        }
        MethodNode pendingTryRecycle = find(
                pendingNode, "tryRecycle", "()Ljava/util/List;");
        MethodInsnNode pendingAwait = null;
        for (AbstractInsnNode instruction : pendingTryRecycle.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals(fence)
                    && call.name.equals("awaitCompletion")
                    && call.desc.equals("(J)Z")) {
                if (pendingAwait != null) {
                    throw new IllegalStateException(pendingOwner + " has multiple fence waits");
                }
                pendingAwait = call;
            }
        }
        AbstractInsnNode awaitTimeout = pendingAwait == null
                ? null : previousOpcode(pendingAwait);
        AbstractInsnNode awaitFence = awaitTimeout == null
                ? null : previousOpcode(awaitTimeout);
        AbstractInsnNode awaitOwner = awaitFence == null
                ? null : previousOpcode(awaitFence);
        AbstractInsnNode awaitBranch = pendingAwait == null ? null : nextOpcode(pendingAwait);
        if (pendingAwait == null
                || awaitTimeout == null
                || awaitTimeout.getOpcode() != Opcodes.LCONST_0
                || !(awaitFence instanceof FieldInsnNode fenceField)
                || fenceField.getOpcode() != Opcodes.GETFIELD
                || !fenceField.owner.equals(pendingOwner)
                || !fenceField.name.equals("fence")
                || !(awaitOwner instanceof VarInsnNode ownerLoad)
                || ownerLoad.getOpcode() != Opcodes.ALOAD
                || ownerLoad.var != 0
                || !(awaitBranch instanceof JumpInsnNode branch)
                || branch.getOpcode() != Opcodes.IFEQ) {
            throw new IllegalStateException(pendingOwner + " awaitCompletion(0) gate changed");
        }
        AbstractInsnNode falseNull = nextOpcode(branch.label);
        AbstractInsnNode falseReturn = falseNull == null ? null : nextOpcode(falseNull);
        AbstractInsnNode trueOwner = nextOpcode(branch);
        AbstractInsnNode trueFence = trueOwner == null ? null : nextOpcode(trueOwner);
        AbstractInsnNode trueClose = trueFence == null ? null : nextOpcode(trueFence);
        AbstractInsnNode trueBufferOwner = trueClose == null ? null : nextOpcode(trueClose);
        AbstractInsnNode trueBuffers = trueBufferOwner == null
                ? null : nextOpcode(trueBufferOwner);
        AbstractInsnNode trueReturn = trueBuffers == null ? null : nextOpcode(trueBuffers);
        if (falseNull == null || falseNull.getOpcode() != Opcodes.ACONST_NULL
                || falseReturn == null || falseReturn.getOpcode() != Opcodes.ARETURN
                || !(trueOwner instanceof VarInsnNode trueOwnerLoad)
                || trueOwnerLoad.getOpcode() != Opcodes.ALOAD || trueOwnerLoad.var != 0
                || !(trueFence instanceof FieldInsnNode trueFenceField)
                || !trueFenceField.owner.equals(pendingOwner)
                || !trueFenceField.name.equals("fence")
                || !(trueClose instanceof MethodInsnNode closeCall)
                || !closeCall.owner.equals(fence)
                || !closeCall.name.equals("close") || !closeCall.desc.equals("()V")
                || !(trueBufferOwner instanceof VarInsnNode trueBufferOwnerLoad)
                || trueBufferOwnerLoad.getOpcode() != Opcodes.ALOAD
                || trueBufferOwnerLoad.var != 0
                || !(trueBuffers instanceof FieldInsnNode trueBuffersField)
                || !trueBuffersField.owner.equals(pendingOwner)
                || !trueBuffersField.name.equals("buffers")
                || trueReturn == null || trueReturn.getOpcode() != Opcodes.ARETURN
                || nextOpcode(falseReturn) != null) {
            throw new IllegalStateException(
                    pendingOwner + " false-null/true-close-buffer CFG changed");
        }
        MethodNode pendingClose = find(pendingNode, "close", "()V");
        int pendingCloseForEach = 0;
        int pendingFenceCloses = 0;
        int pendingCloseReturns = 0;
        java.util.List<String> pendingCloseOwnerFields = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : pendingClose.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode closeField
                    && closeField.getOpcode() == Opcodes.GETFIELD
                    && closeField.owner.equals(pendingOwner)) {
                pendingCloseOwnerFields.add(closeField.name);
            }
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("java/util/List")
                    && call.name.equals("forEach")
                    && call.desc.equals("(Ljava/util/function/Consumer;)V")) {
                pendingCloseForEach++;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(fence)
                    && call.name.equals("close") && call.desc.equals("()V")) {
                pendingFenceCloses++;
            }
            if (instruction.getOpcode() == Opcodes.RETURN) {
                pendingCloseReturns++;
            }
        }
        if (pendingCloseForEach != 1 || pendingFenceCloses != 1
                || pendingCloseReturns != 1
                || !pendingCloseOwnerFields.equals(java.util.List.of("buffers", "fence"))) {
            throw new IllegalStateException(pendingOwner + ".close owner cleanup changed");
        }
        if (node.fields.stream().anyMatch(field -> field.name.equals(cacheField))) {
            throw new IllegalStateException(owner + " already contains " + cacheField);
        }
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                cacheField,
                helperDescriptor,
                null,
                null));

        MethodNode constructor = find(node, "<init>", "(Ljava/util/function/Supplier;I)V");
        AbstractInsnNode constructorReturn = null;
        int constructorReturns = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                constructorReturns++;
            }
        }
        if (constructorReturns != 1) {
            throw new IllegalStateException(
                    owner + " constructor return shape changed: " + constructorReturns);
        }
        InsnList initializeCache = new InsnList();
        initializeCache.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializeCache.add(new TypeInsnNode(Opcodes.NEW, helper));
        initializeCache.add(new InsnNode(Opcodes.DUP));
        initializeCache.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializeCache.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "available", "Ljava/util/List;"));
        initializeCache.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializeCache.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "label", "Ljava/util/function/Supplier;"));
        initializeCache.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initializeCache.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "usage", "I"));
        initializeCache.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                helper,
                "<init>",
                "(Ljava/util/List;Ljava/util/function/Supplier;I)V",
                false));
        initializeCache.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, cacheField, helperDescriptor));
        constructor.instructions.insertBefore(constructorReturn, initializeCache);

        MethodNode acquire = find(
                node,
                "acquire",
                "(L" + device + ";I)L" + buffer + ";");
        requireStagedPoolRecycleSite(acquire, owner, !recycleAtEndFrame);
        MethodInsnNode roundToward = null;
        MethodInsnNode rawTakeBest = null;
        for (AbstractInsnNode instruction : acquire.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/util/Mth")
                    && call.name.equals("roundToward")
                    && call.desc.equals("(II)I")) {
                if (roundToward != null) {
                    throw new IllegalStateException(owner + ".acquire has multiple roundToward calls");
                }
                roundToward = call;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(owner)
                    && call.name.equals("takeBestAvailable")
                    && call.desc.equals("(II)L" + buffer + ";")) {
                if (rawTakeBest != null) {
                    throw new IllegalStateException(owner + ".acquire has multiple takeBest calls");
                }
                rawTakeBest = call;
            }
        }
        AbstractInsnNode roundIncrement = roundToward == null
                ? null : previousOpcode(roundToward);
        AbstractInsnNode roundRequest = roundIncrement == null
                ? null : previousOpcode(roundIncrement);
        AbstractInsnNode roundedStore = roundToward == null ? null : nextOpcode(roundToward);
        AbstractInsnNode maximumMultiply = rawTakeBest == null
                ? null : previousOpcode(rawTakeBest);
        AbstractInsnNode maximumFactor = maximumMultiply == null
                ? null : previousOpcode(maximumMultiply);
        AbstractInsnNode maximumMinimum = maximumFactor == null
                ? null : previousOpcode(maximumFactor);
        AbstractInsnNode minimumLoad = maximumMinimum == null
                ? null : previousOpcode(maximumMinimum);
        AbstractInsnNode takeBestOwner = minimumLoad == null
                ? null : previousOpcode(minimumLoad);
        if (!(roundIncrement instanceof LdcInsnNode increment)
                || !Integer.valueOf(262144).equals(increment.cst)
                || !(roundRequest instanceof VarInsnNode requestLoad)
                || requestLoad.getOpcode() != Opcodes.ILOAD || requestLoad.var != 2
                || !(roundedStore instanceof VarInsnNode rounded)
                || rounded.getOpcode() != Opcodes.ISTORE || rounded.var != 3
                || maximumMultiply == null || maximumMultiply.getOpcode() != Opcodes.IMUL
                || maximumFactor == null || maximumFactor.getOpcode() != Opcodes.ICONST_4
                || !(maximumMinimum instanceof VarInsnNode maximumMinimumLoad)
                || maximumMinimumLoad.getOpcode() != Opcodes.ILOAD
                || maximumMinimumLoad.var != 3
                || !(minimumLoad instanceof VarInsnNode minimumValueLoad)
                || minimumValueLoad.getOpcode() != Opcodes.ILOAD || minimumValueLoad.var != 3
                || !(takeBestOwner instanceof VarInsnNode takeBestOwnerLoad)
                || takeBestOwnerLoad.getOpcode() != Opcodes.ALOAD || takeBestOwnerLoad.var != 0) {
            throw new IllegalStateException(
                    owner + ".acquire 262144 rounding/minimum*4 comparator shape changed");
        }
        AbstractInsnNode acquireStart = acquire.instructions.getFirst();
        while (acquireStart != null && acquireStart.getOpcode() < 0) {
            acquireStart = acquireStart.getNext();
        }
        if (acquireStart == null) {
            throw new IllegalStateException(owner + ".acquire has no executable instructions");
        }
        InsnList beforeAcquire = new InsnList();
        beforeAcquire.add(new VarInsnNode(Opcodes.ALOAD, 0));
        beforeAcquire.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, cacheField, helperDescriptor));
        beforeAcquire.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, helper, "beforeAcquire", "()V", false));
        acquire.instructions.insertBefore(acquireStart, beforeAcquire);

        InsnList afterRecycleSweep = new InsnList();
        afterRecycleSweep.add(new VarInsnNode(Opcodes.ALOAD, 0));
        afterRecycleSweep.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, cacheField, helperDescriptor));
        afterRecycleSweep.add(new VarInsnNode(Opcodes.ILOAD, 3));
        afterRecycleSweep.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                helper,
                "afterRecycleSweep",
                "(I)V",
                false));
        acquire.instructions.insert(roundedStore, afterRecycleSweep);

        MethodInsnNode createBuffer = null;
        MethodInsnNode usedAdd = null;
        for (AbstractInsnNode instruction : acquire.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(device)
                    && call.name.equals("createBuffer")
                    && call.desc.equals(
                    "(Ljava/util/function/Supplier;IJ)L" + buffer + ";")) {
                if (createBuffer != null) {
                    throw new IllegalStateException(owner + ".acquire has multiple createBuffer calls");
                }
                createBuffer = call;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("add")
                    && call.desc.equals("(Ljava/lang/Object;)Z")) {
                AbstractInsnNode value = previousOpcode(call);
                AbstractInsnNode field = value == null ? null : previousOpcode(value);
                if (field instanceof FieldInsnNode ownerField
                        && ownerField.owner.equals(owner)
                        && ownerField.name.equals("usedThisFrame")) {
                    if (usedAdd != null) {
                        throw new IllegalStateException(owner + ".acquire has multiple used-list adds");
                    }
                    usedAdd = call;
                }
            }
        }
        if (createBuffer == null || usedAdd == null) {
            throw new IllegalStateException(owner + ".acquire creation/use shape changed");
        }
        AbstractInsnNode createdStore = nextOpcode(createBuffer);
        if (!(createdStore instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE
                || store.var != 4) {
            throw new IllegalStateException(owner + ".acquire create result local changed");
        }
        InsnList recordCreate = new InsnList();
        recordCreate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        recordCreate.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, cacheField, helperDescriptor));
        recordCreate.add(new VarInsnNode(Opcodes.ALOAD, 4));
        recordCreate.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                helper,
                "recordCreate",
                "(L" + buffer + ";)V",
                false));
        acquire.instructions.insert(createdStore, recordCreate);

        AbstractInsnNode usedAddPop = nextOpcode(usedAdd);
        if (usedAddPop == null || usedAddPop.getOpcode() != Opcodes.POP) {
            throw new IllegalStateException(owner + ".acquire used-list add no longer discards boolean");
        }
        AbstractInsnNode acquireReturn = nextOpcode(usedAddPop);
        if (!(acquireReturn instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 4
                || nextOpcode(acquireReturn) == null
                || nextOpcode(acquireReturn).getOpcode() != Opcodes.ARETURN) {
            throw new IllegalStateException(owner + ".acquire return shape changed");
        }
        InsnList afterAcquire = new InsnList();
        afterAcquire.add(new VarInsnNode(Opcodes.ALOAD, 0));
        afterAcquire.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, cacheField, helperDescriptor));
        afterAcquire.add(new VarInsnNode(Opcodes.ALOAD, 4));
        afterAcquire.add(new VarInsnNode(Opcodes.ILOAD, 2));
        afterAcquire.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                helper,
                "afterAcquire",
                "(L" + buffer + ";I)V",
                false));
        acquire.instructions.insert(usedAddPop, afterAcquire);

        MethodNode recycleLambda = null;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(owner + "$PendingRecycle")
                        && call.name.equals("tryRecycle")
                        && call.desc.equals("()Ljava/util/List;")) {
                    if (recycleLambda != null && recycleLambda != method) {
                        throw new IllegalStateException(owner + " has multiple recycle lambdas");
                    }
                    recycleLambda = method;
                }
            }
        }
        if (recycleLambda == null || !recycleLambda.desc.endsWith(")Z")) {
            throw new IllegalStateException(owner + " recycle lambda shape changed");
        }
        VarInsnNode recycledStore = null;
        MethodInsnNode addAll = null;
        for (AbstractInsnNode instruction : recycleLambda.instructions.toArray()) {
            if (instruction instanceof VarInsnNode variable
                    && variable.getOpcode() == Opcodes.ASTORE
                    && variable.var == 2) {
                if (recycledStore != null) {
                    throw new IllegalStateException(owner + " recycle result local stored twice");
                }
                recycledStore = variable;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("addAll")
                    && call.desc.equals("(Ljava/util/Collection;)Z")) {
                if (addAll != null) {
                    throw new IllegalStateException(owner + " recycle lambda has multiple addAll calls");
                }
                addAll = call;
            }
        }
        if (recycledStore == null || addAll == null) {
            throw new IllegalStateException(owner + " recycle lambda result/addAll shape changed");
        }
        InsnList recycleResult = new InsnList();
        recycleResult.add(new VarInsnNode(Opcodes.ALOAD, 0));
        recycleResult.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, cacheField, helperDescriptor));
        recycleResult.add(new VarInsnNode(Opcodes.ALOAD, 2));
        recycleResult.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                helper,
                "recycleResult",
                "(Ljava/util/List;)V",
                false));
        recycleLambda.instructions.insert(recycledStore, recycleResult);

        AbstractInsnNode addAllValue = previousOpcode(addAll);
        AbstractInsnNode addAllField = addAllValue == null ? null : previousOpcode(addAllValue);
        AbstractInsnNode addAllOwner = addAllField == null ? null : previousOpcode(addAllField);
        AbstractInsnNode addAllPop = nextOpcode(addAll);
        if (!(addAllValue instanceof VarInsnNode valueLoad)
                || valueLoad.getOpcode() != Opcodes.ALOAD
                || valueLoad.var != 2
                || !(addAllField instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETFIELD
                || !field.owner.equals(owner)
                || !field.name.equals("available")
                || !(addAllOwner instanceof VarInsnNode addAllOwnerLoad)
                || addAllOwnerLoad.getOpcode() != Opcodes.ALOAD
                || addAllOwnerLoad.var != 0
                || addAllPop == null
                || addAllPop.getOpcode() != Opcodes.POP) {
            throw new IllegalStateException(owner + " recycle available.addAll stack shape changed");
        }
        recycleLambda.instructions.remove(addAllOwner);
        recycleLambda.instructions.remove(addAllField);
        recycleLambda.instructions.remove(addAllValue);
        recycleLambda.instructions.remove(addAll);
        recycleLambda.instructions.remove(addAllPop);

        MethodNode takeBest = find(node, "takeBestAvailable", "(II)L" + buffer + ";");
        int removePatches = 0;
        for (AbstractInsnNode instruction : takeBest.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !call.owner.equals("java/util/List")
                    || !call.name.equals("remove")
                    || !call.desc.equals("(I)Ljava/lang/Object;")) {
                continue;
            }
            AbstractInsnNode indexLoad = previousOpcode(call);
            AbstractInsnNode listField = indexLoad == null ? null : previousOpcode(indexLoad);
            if (!(listField instanceof FieldInsnNode takeBestField)
                    || takeBestField.getOpcode() != Opcodes.GETFIELD
                    || !takeBestField.owner.equals(owner)
                    || !takeBestField.name.equals("available")) {
                throw new IllegalStateException(owner + ".takeBestAvailable remove receiver changed");
            }
            takeBestField.name = cacheField;
            takeBestField.desc = helperDescriptor;
            call.setOpcode(Opcodes.INVOKEVIRTUAL);
            call.owner = helper;
            call.name = "removeAt";
            call.desc = "(I)L" + buffer + ";";
            call.itf = false;
            removePatches++;
        }
        if (removePatches != 2) {
            throw new IllegalStateException(
                    owner + ".takeBestAvailable remove count changed: " + removePatches);
        }

        MethodNode endFrame = find(
                node, "endFrame", "(L" + device + ";)V");
        requireStagedPoolRecycleSite(endFrame, owner, recycleAtEndFrame);
        java.util.List<FieldInsnNode> endFrameAvailable = new java.util.ArrayList<>();
        java.util.List<String> endFrameOwnerFields = new java.util.ArrayList<>();
        int pendingAdds = 0;
        int usedClears = 0;
        int rawEndFrameReturns = 0;
        for (AbstractInsnNode instruction : endFrame.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode endFrameField
                    && endFrameField.getOpcode() == Opcodes.GETFIELD
                    && endFrameField.owner.equals(owner)) {
                endFrameOwnerFields.add(endFrameField.name);
                if (endFrameField.name.equals("available")) {
                    endFrameAvailable.add(endFrameField);
                }
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("add")
                    && call.desc.equals("(Ljava/lang/Object;)Z")) {
                pendingAdds++;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("clear")
                    && call.desc.equals("()V")) {
                usedClears++;
            }
            if (instruction.getOpcode() == Opcodes.RETURN) {
                rawEndFrameReturns++;
            }
        }
        java.util.List<String> expectedEndFrameFields = java.util.List.of(
                "usedThisFrame", "pendingRecycle", "usedThisFrame", "usedThisFrame",
                "available", "available", "available");
        if (endFrameAvailable.size() != 3
                || pendingAdds != 1
                || usedClears != 2
                || rawEndFrameReturns != 1
                || !endFrameOwnerFields.equals(expectedEndFrameFields)) {
            throw new IllegalStateException(
                    owner + ".endFrame available/pending shape changed: available="
                            + endFrameAvailable.size() + ", adds=" + pendingAdds
                            + ", clears=" + usedClears + ", fields=" + endFrameOwnerFields
                            + ", returns=" + rawEndFrameReturns);
        }
        AbstractInsnNode availableBlockStart = previousOpcode(endFrameAvailable.get(0));
        AbstractInsnNode availableIsEmpty = nextOpcode(endFrameAvailable.get(0));
        AbstractInsnNode availableBranch = availableIsEmpty == null
                ? null : nextOpcode(availableIsEmpty);
        AbstractInsnNode availableForEachOwner = availableBranch == null
                ? null : nextOpcode(availableBranch);
        AbstractInsnNode availableForEachField = availableForEachOwner == null
                ? null : nextOpcode(availableForEachOwner);
        AbstractInsnNode availableConsumer = availableForEachField == null
                ? null : nextOpcode(availableForEachField);
        AbstractInsnNode availableForEach = availableConsumer == null
                ? null : nextOpcode(availableConsumer);
        AbstractInsnNode availableClearOwner = availableForEach == null
                ? null : nextOpcode(availableForEach);
        FieldInsnNode finalAvailableField = endFrameAvailable.get(endFrameAvailable.size() - 1);
        AbstractInsnNode availableClear = nextOpcode(finalAvailableField);
        AbstractInsnNode afterAvailableClear = availableClear == null
                ? null : nextOpcode(availableClear);
        if (!(availableBlockStart instanceof VarInsnNode startLoad)
                || startLoad.getOpcode() != Opcodes.ALOAD
                || startLoad.var != 0
                || !(availableIsEmpty instanceof MethodInsnNode isEmptyCall)
                || isEmptyCall.getOpcode() != Opcodes.INVOKEINTERFACE
                || !isEmptyCall.owner.equals("java/util/List")
                || !isEmptyCall.name.equals("isEmpty")
                || !isEmptyCall.desc.equals("()Z")
                || !(availableBranch instanceof JumpInsnNode emptyBranch)
                || emptyBranch.getOpcode() != Opcodes.IFNE
                || !(availableForEachOwner instanceof VarInsnNode forEachOwnerLoad)
                || forEachOwnerLoad.getOpcode() != Opcodes.ALOAD
                || forEachOwnerLoad.var != 0
                || availableForEachField != endFrameAvailable.get(1)
                || !(availableConsumer instanceof InvokeDynamicInsnNode)
                || !(availableForEach instanceof MethodInsnNode forEachCall)
                || forEachCall.getOpcode() != Opcodes.INVOKEINTERFACE
                || !forEachCall.owner.equals("java/util/List")
                || !forEachCall.name.equals("forEach")
                || !forEachCall.desc.equals("(Ljava/util/function/Consumer;)V")
                || !(availableClearOwner instanceof VarInsnNode clearOwnerLoad)
                || clearOwnerLoad.getOpcode() != Opcodes.ALOAD
                || clearOwnerLoad.var != 0
                || finalAvailableField != nextOpcode(availableClearOwner)
                || !(availableClear instanceof MethodInsnNode clearCall)
                || clearCall.getOpcode() != Opcodes.INVOKEINTERFACE
                || !clearCall.owner.equals("java/util/List")
                || !clearCall.name.equals("clear")
                || !clearCall.desc.equals("()V")
                || !isStagedPoolEndFrameTail(afterAvailableClear, owner, recycleAtEndFrame)
                || nextOpcode(emptyBranch.label) != afterAvailableClear) {
            throw new IllegalStateException(owner + ".endFrame available close/clear block changed");
        }
        AbstractInsnNode afterAvailableBlock = availableClear.getNext();
        for (AbstractInsnNode cursor = availableBlockStart; cursor != afterAvailableBlock; ) {
            AbstractInsnNode next = cursor.getNext();
            endFrame.instructions.remove(cursor);
            cursor = next;
        }
        AbstractInsnNode endFrameReturn = null;
        int endFrameReturns = 0;
        for (AbstractInsnNode instruction : endFrame.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                endFrameReturn = instruction;
                endFrameReturns++;
            }
        }
        if (endFrameReturns != 1) {
            throw new IllegalStateException(owner + ".endFrame return shape changed");
        }
        InsnList retainAvailable = new InsnList();
        retainAvailable.add(new VarInsnNode(Opcodes.ALOAD, 0));
        retainAvailable.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, cacheField, helperDescriptor));
        retainAvailable.add(new VarInsnNode(Opcodes.ALOAD, 0));
        retainAvailable.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "pendingRecycle", "Ljava/util/List;"));
        retainAvailable.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        retainAvailable.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, helper, "endFrame", "(I)V", false));
        // 26.3 adopts the signalled batches at the endFrame tail: advance the helper frame
        // (TTL purge, trim) before that adoption so the adopted buffers are stamped with the
        // new frame, the same order as 26.2's adoption on the next frame's first acquire.
        endFrame.instructions.insertBefore(
                recycleAtEndFrame ? afterAvailableClear : endFrameReturn, retainAvailable);

        MethodNode close = find(node, "close", "()V");
        int closeForEach = 0;
        int closeClears = 0;
        AbstractInsnNode closeReturn = null;
        java.util.List<String> closeOwnerFields = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : close.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode closeField
                    && closeField.getOpcode() == Opcodes.GETFIELD
                    && closeField.owner.equals(owner)) {
                closeOwnerFields.add(closeField.name);
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("forEach")
                    && call.desc.equals("(Ljava/util/function/Consumer;)V")) {
                closeForEach++;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("clear")
                    && call.desc.equals("()V")) {
                closeClears++;
            }
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (closeReturn != null) {
                    throw new IllegalStateException(owner + ".close has multiple returns");
                }
                closeReturn = instruction;
            }
        }
        java.util.List<String> expectedCloseFields = java.util.List.of(
                "available", "usedThisFrame", "pendingRecycle",
                "available", "usedThisFrame", "pendingRecycle");
        if (closeForEach != 3
                || closeClears != 3
                || closeReturn == null
                || !closeOwnerFields.equals(expectedCloseFields)) {
            throw new IllegalStateException(
                    owner + ".close owner cleanup shape changed: forEach=" + closeForEach
                            + ", clears=" + closeClears + ", fields=" + closeOwnerFields);
        }
        InsnList ownerClosed = new InsnList();
        ownerClosed.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ownerClosed.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, cacheField, helperDescriptor));
        ownerClosed.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, helper, "ownerClosed", "()V", false));
        close.instructions.insertBefore(closeReturn, ownerClosed);

        writeComputeFrames(node, output);
        System.out.println("Instrumented " + profileLabel
                + " StagedVertexBuffer GPU pool cache: count=4 bytes=1048576 idle=3");
    }

    /**
     * The signalled-batch sweep {@code tryRecycleBuffers()} runs once, as the first statement of
     * {@code acquire} (26.2) or as the tail of {@code endFrame} (26.3); {@code expected} says
     * whether {@code method} must be that site. Every other place is rejected.
     */
    private static void requireStagedPoolRecycleSite(
            MethodNode method, String owner, boolean expected) {
        int sweeps = 0;
        MethodInsnNode sweep = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals("tryRecycleBuffers")
                    && call.desc.equals("()V")) {
                sweeps++;
                sweep = call;
            }
        }
        if (sweeps != (expected ? 1 : 0)) {
            throw new IllegalStateException(owner + "." + method.name
                    + " recycle sweep count changed: " + sweeps);
        }
        if (!expected) {
            return;
        }
        AbstractInsnNode receiver = previousOpcode(sweep);
        boolean atStart = receiver != null && previousOpcode(receiver) == null;
        boolean atTail = nextOpcode(sweep) != null
                && nextOpcode(sweep).getOpcode() == Opcodes.RETURN
                && nextOpcode(nextOpcode(sweep)) == null;
        if (!(receiver instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 0
                || sweep.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !(method.name.equals("acquire") ? atStart : atTail)) {
            throw new IllegalStateException(owner + "." + method.name
                    + " recycle sweep position changed");
        }
    }

    /** What follows {@code available.clear()} in endFrame: RETURN, or 26.3's recycle tail. */
    private static boolean isStagedPoolEndFrameTail(
            AbstractInsnNode instruction, String owner, boolean recycleAtEndFrame) {
        if (instruction == null) {
            return false;
        }
        if (!recycleAtEndFrame) {
            return instruction.getOpcode() == Opcodes.RETURN;
        }
        AbstractInsnNode sweep = nextOpcode(instruction);
        AbstractInsnNode tailReturn = sweep == null ? null : nextOpcode(sweep);
        return instruction instanceof VarInsnNode load
                && load.getOpcode() == Opcodes.ALOAD
                && load.var == 0
                && sweep instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && call.owner.equals(owner)
                && call.name.equals("tryRecycleBuffers")
                && call.desc.equals("()V")
                && tailReturn != null
                && tailReturn.getOpcode() == Opcodes.RETURN;
    }

    public static void patchGlDevice(String jar, Path outputRoot) throws IOException {
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String owner = renderName(symbols, "com/mojang/blaze3d/opengl/GlDevice");
        ClassNode node = read(jar, owner + ".class");
        boolean patchedCapability = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        owner,
                        "USE_GL_ARB_vertex_attrib_binding",
                        "Z"));
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        method.instructions.insertBefore(instruction, code);
                        method.maxStack = Math.max(method.maxStack, 1);
                        patchedCapability = true;
                        break;
                    }
                }
            }
        }
        MethodNode maxTextureSize = node.methods.stream()
                .filter(method -> method.name.equals("getMaxSupportedTextureSize")
                        && method.desc.equals("()I"))
                .findFirst()
                .orElse(null);
        ClassNode maxTextureOwner = node;
        String maxTextureEntry = owner + ".class";
        if (maxTextureSize == null) {
            String heuristicsOwner = renderName(symbols, "com/mojang/blaze3d/opengl/GlHeuristics");
            maxTextureOwner = read(jar, heuristicsOwner + ".class");
            maxTextureSize = find(maxTextureOwner, "getMaxSupportedTextureSize", "()I");
            maxTextureEntry = heuristicsOwner + ".class";
        }
        InsnList code = new InsnList();
        code.add(new IntInsnNode(Opcodes.SIPUSH, 3379));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                renderName(symbols, "com/mojang/blaze3d/opengl/GlStateManager"),
                "_getInteger",
                "(I)I",
                false));
        code.add(new IntInsnNode(Opcodes.SIPUSH, 1024));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Math",
                "max",
                "(II)I",
                false));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(maxTextureSize, code, 2, 0);
        if (!patchedCapability) {
            throw new IllegalStateException("GlDevice capability patch point was not found");
        }
        write(node, outputRoot.resolve(owner + ".class"));
        if (maxTextureOwner != node) {
            write(maxTextureOwner, outputRoot.resolve(maxTextureEntry));
        }
    }

    public static void patchGlConstWebGLTextureFormats(String jar, Path output) throws IOException {
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String legacyOwner = "com/mojang/blaze3d/opengl/GlConst";
        ClassNode node = read(jar, renderName(symbols, legacyOwner) + ".class");
        String gpuFormatDescriptor =
                "(L" + renderName(symbols, "com/mojang/blaze3d/GpuFormat") + ";)I";
        boolean gpuFormat = node.methods.stream()
                .anyMatch(candidate -> candidate.name.equals("toGlInternalId")
                        && candidate.desc.equals(gpuFormatDescriptor));
        MethodNode method = find(
                node,
                "toGlInternalId",
                gpuFormat
                        ? gpuFormatDescriptor
                        : "(Lcom/mojang/blaze3d/textures/TextureFormat;)I");
        boolean patched = false;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof LdcInsnNode ldc
                    && Integer.valueOf(33329).equals(ldc.cst)) {
                // RED8I is GL_R8I on desktop. WebGL rejects sampling an integer
                // texture through Minecraft's ordinary sampler2D pipelines, so
                // normalize it to GL_R8 for the browser build.
                ldc.cst = 33321;
                patched = true;
            }
        }
        if (!patched) {
            throw new IllegalStateException("GlConst RED8I internal format patch point was not found");
        }
        write(node, renderEntryOutput(symbols, output, legacyOwner));
    }

    public static void patchTextureFormatWebGLColorAspect(String jar, Path root) throws IOException {
        String oldEntry = "com/mojang/blaze3d/textures/TextureFormat.class";
        try (ZipFile input = new ZipFile(jar)) {
            if (input.getEntry(oldEntry) == null) {
                String currentOwner = renderName(
                        ModernSymbols.cached(jar), "com/mojang/blaze3d/GpuFormat");
                String currentEntry = currentOwner + ".class";
                if (input.getEntry(currentEntry) == null) {
                    throw new IllegalStateException("Neither TextureFormat nor GpuFormat was found");
                }
                ClassNode current = read(jar, currentEntry);
                MethodNode hasColorAspect = find(current, "hasColorAspect", "()Z");
                boolean checksDepth = false;
                boolean checksStencil = false;
                for (AbstractInsnNode instruction : hasColorAspect.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals(currentOwner)
                            && call.desc.equals("()Z")) {
                        checksDepth |= call.name.equals("hasDepthAspect");
                        checksStencil |= call.name.equals("hasStencilAspect");
                    }
                }
                if (!checksDepth || !checksStencil) {
                    throw new IllegalStateException(
                            "GpuFormat.hasColorAspect no longer excludes depth and stencil formats");
                }
                System.out.println(
                        "Verified current GpuFormat color/depth/stencil aspect semantics");
                return;
            }
        }

        ClassNode node = read(jar, oldEntry);
        MethodNode method = find(node, "hasColorAspect", "()Z");
        InsnList code = new InsnList();
        LabelNode depth = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "com/mojang/blaze3d/textures/TextureFormat",
                "DEPTH32",
                "Lcom/mojang/blaze3d/textures/TextureFormat;"));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, depth));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(depth);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 2, 1);
        writeComputeFrames(node, root.resolve(oldEntry));
    }

    public static void patchGlStateManagerTextureBinding(String jar, Path output) throws IOException {
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String legacyOwner = "com/mojang/blaze3d/opengl/GlStateManager";
        String owner = renderName(symbols, legacyOwner);
        ClassNode node = read(jar, owner + ".class");
        boolean patchedBindTexture = false;
        boolean patchedActiveTexture = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("_bindTexture") && method.desc.equals("(I)V")) {
                InsnList code = new InsnList();
                code.add(new LdcInsnNode(33984));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        owner,
                        "activeTexture",
                        "I"));
                code.add(new InsnNode(Opcodes.IADD));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/opengl/GL13",
                        "glActiveTexture",
                        "(I)V",
                        false));
                code.add(new IntInsnNode(Opcodes.SIPUSH, 3553));
                code.add(new VarInsnNode(Opcodes.ILOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/opengl/GL11",
                        "glBindTexture",
                        "(II)V",
                        false));
                insertAfterFirstRenderThreadAssert(method, code);
                method.maxStack = Math.max(method.maxStack, 2);
                patchedBindTexture = true;
            } else if (method.name.equals("_activeTexture") && method.desc.equals("(I)V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ILOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/opengl/GL13",
                        "glActiveTexture",
                        "(I)V",
                        false));
                insertAfterFirstRenderThreadAssert(method, code);
                method.maxStack = Math.max(method.maxStack, 1);
                patchedActiveTexture = true;
            }
        }
        if (!patchedBindTexture || !patchedActiveTexture) {
            throw new IllegalStateException("GlStateManager texture binding patch points were not found");
        }
        writeComputeFrames(node, renderEntryOutput(symbols, output, legacyOwner));
    }

    private static void insertAfterFirstRenderThreadAssert(MethodNode method, InsnList code) {
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC
                    && instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")
                    && call.name.equals("assertOnRenderThread")
                    && call.desc.equals("()V")) {
                method.instructions.insert(instruction, code);
                return;
            }
        }
        throw new IllegalStateException("RenderSystem.assertOnRenderThread patch point was not found in "
                + method.name + method.desc);
    }

    public static void patchGlRenderPipelineDrawMetadata(String jar, Path output) throws IOException {
        ModernSymbols symbols = ModernSymbols.cached(jar);
        if (symbols.renderpearl()) {
            // 26.3 GlRenderPipeline already caches the GL primitive topology
            // (primitiveTopology()I), and patchGlCommandEncoder keeps the vanilla draw path.
            find(read(jar, symbols.renderType("com/mojang/blaze3d/opengl/GlRenderPipeline")
                    + ".class"), "primitiveTopology", "()I");
            PatchRegistry.dropped("MinecraftClientPatcher.patchGlRenderPipelineDrawMetadata", jar,
                    "com/mojang/blaze3d/opengl/GlRenderPipeline.class");
            return;
        }
        String owner = "com/mojang/blaze3d/opengl/GlRenderPipeline";
        String pipeline = "com/mojang/blaze3d/pipeline/RenderPipeline";
        String vertexFormat = "com/mojang/blaze3d/vertex/VertexFormat";
        String mode = "com/mojang/blaze3d/vertex/VertexFormat$Mode";
        String primitiveTopology = "com/mojang/blaze3d/PrimitiveTopology";
        ClassNode node = read(jar, owner + ".class");
        ClassNode pipelineNode = read(jar, pipeline + ".class");
        boolean multiBufferPipeline = pipelineNode.methods.stream()
                .anyMatch(method -> method.name.equals("getVertexFormatBindings")
                        && method.desc.equals("()[L" + vertexFormat + ";"));
        if (multiBufferPipeline) {
            find(node, "info", "()L" + pipeline + ";");
            find(pipelineNode, "getPrimitiveTopology", "()L" + primitiveTopology + ";");
            node.fields.add(new FieldNode(
                    Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                    "gaius$primitiveTopology",
                    "I",
                    null,
                    null));

            MethodNode constructor = find(
                    node,
                    "<init>",
                    "(L" + pipeline + ";Lcom/mojang/blaze3d/opengl/GlProgram;)V");
            AbstractInsnNode constructorReturn = null;
            for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction.getOpcode() == Opcodes.RETURN) {
                    if (constructorReturn != null) {
                        throw new IllegalStateException(
                                "GlRenderPipeline constructor has multiple returns");
                    }
                    constructorReturn = instruction;
                }
            }
            if (constructorReturn == null) {
                throw new IllegalStateException("GlRenderPipeline constructor return was not found");
            }

            InsnList initialize = new InsnList();
            initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
            initialize.add(new VarInsnNode(Opcodes.ALOAD, 1));
            initialize.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    pipeline,
                    "getPrimitiveTopology",
                    "()L" + primitiveTopology + ";",
                    false));
            initialize.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/mojang/blaze3d/opengl/GlConst",
                    "toGl",
                    "(L" + primitiveTopology + ";)I",
                    false));
            initialize.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "gaius$primitiveTopology",
                    "I"));
            constructor.instructions.insertBefore(constructorReturn, initialize);
            constructor.maxStack = Math.max(constructor.maxStack, 2);
            writeComputeFrames(node, output);
            System.out.println("Cached current multi-buffer render pipeline topology");
            return;
        }
        node.fields.add(new FieldNode(
                Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "gaius$vertexFormat",
                "L" + vertexFormat + ";",
                null,
                null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "gaius$drawMode",
                "I",
                null,
                null));

        MethodNode constructor = find(
                node,
                "<init>",
                "(L" + pipeline + ";Lcom/mojang/blaze3d/opengl/GlProgram;)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (constructorReturn != null) {
                    throw new IllegalStateException("GlRenderPipeline constructor has multiple returns");
                }
                constructorReturn = instruction;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException("GlRenderPipeline constructor return was not found");
        }

        InsnList initialize = new InsnList();
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 1));
        initialize.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                pipeline,
                "getVertexFormat",
                "()L" + vertexFormat + ";",
                false));
        initialize.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "gaius$vertexFormat",
                "L" + vertexFormat + ";"));
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 1));
        initialize.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                pipeline,
                "getVertexFormatMode",
                "()L" + mode + ";",
                false));
        initialize.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/mojang/blaze3d/opengl/GlConst",
                "toGl",
                "(L" + mode + ";)I",
                false));
        initialize.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                owner,
                "gaius$drawMode",
                "I"));
        constructor.instructions.insertBefore(constructorReturn, initialize);
        constructor.maxStack = Math.max(constructor.maxStack, 2);
        writeComputeFrames(node, output);
    }

    public static void patchGlCommandEncoder(String jar, Path outputRoot) throws IOException {
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String owner = renderName(symbols, "com/mojang/blaze3d/opengl/GlCommandEncoder");
        ClassNode node = read(jar, owner + ".class");
        String pass = "com/mojang/blaze3d/opengl/GlRenderPass";
        String renderSystem = "com/mojang/blaze3d/systems/RenderSystem";
        String slice = "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;";
        String buffer = "Lcom/mojang/blaze3d/buffers/GpuBuffer;";
        Path output = outputRoot.resolve(owner + ".class");

        if (findNullable(node, "submit", "()V") != null) {
            patchCurrentGlCommandEncoderGpuRetire(jar, node, owner, outputRoot);
        }
        if (symbols.renderpearl()) {
            // 26.3 (renderpearl GL backend): only the GPU retire and transient-memory rotations
            // above carry over. trySetup/drawFromBuffers are gone; the vanilla setupDraw +
            // executeDraw path runs on the delegated GL entry points, and the vanilla
            // RenderSystem.bindDefaultUniforms binds Projection, Fog, Globals and Lighting, so
            // gaius$bindDefaultUniforms and the drawFromBuffers replacement are dropped.
            if (findNullable(node, "submit", "()V") == null) {
                throw new IllegalStateException(owner + ".submit()V was not found");
            }
            String renderPass = symbols.renderType(pass);
            for (MethodNode method : node.methods) {
                if (method.name.equals("trySetup") || method.name.equals("drawFromBuffers")) {
                    throw new IllegalStateException(owner + "." + method.name
                            + " exists again; re-check the 26.3 draw path");
                }
            }
            find(node, "setupDraw", "(L" + renderPass + ";)V");
            find(node, "executeDraw", "(L" + renderPass + ";IIIL"
                    + symbols.renderType("com/mojang/blaze3d/IndexType") + ";II)V");
            find(read(jar, renderSystem + ".class"), "bindDefaultUniforms",
                    "(L" + symbols.renderType("com/mojang/blaze3d/systems/RenderPass") + ";)V");
            writeComputeFrames(node, output);
            return;
        }

        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "gaius$bindDefaultUniforms",
                "(L" + pass + ";)V",
                null,
                null);
        bindDefaultSlice(helper, renderSystem, pass, "getProjectionMatrixBuffer", "Projection", slice);
        bindDefaultSlice(helper, renderSystem, pass, "getShaderFog", "Fog", slice);
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                renderSystem,
                "getGlobalSettingsUniform",
                "()" + buffer,
                false));
        helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        org.objectweb.asm.tree.LabelNode noGlobals = new org.objectweb.asm.tree.LabelNode();
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        helper.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNULL, noGlobals));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new LdcInsnNode("Globals"));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                pass,
                "setUniform",
                "(Ljava/lang/String;" + buffer + ")V",
                false));
        helper.instructions.add(noGlobals);
        bindDefaultSlice(helper, renderSystem, pass, "getShaderLights", "Lighting", slice);
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        helper.maxStack = 3;
        helper.maxLocals = 2;
        node.methods.add(helper);

        MethodNode trySetup = find(
                node,
                "trySetup",
                "(Lcom/mojang/blaze3d/opengl/GlRenderPass;Ljava/util/Collection;)Z");
        InsnList call = new InsnList();
        call.add(new VarInsnNode(Opcodes.ALOAD, 1));
        call.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                owner,
                "gaius$bindDefaultUniforms",
                "(L" + pass + ";)V",
                false));
        trySetup.instructions.insert(call);
        trySetup.maxStack = Math.max(trySetup.maxStack, 3);

        String currentDrawDescriptor =
                "(L" + pass + ";IIILcom/mojang/blaze3d/IndexType;"
                        + "Lcom/mojang/blaze3d/opengl/GlRenderPipeline;II)V";
        MethodNode currentDrawFromBuffers = node.methods.stream()
                .filter(method -> method.name.equals("drawFromBuffers")
                        && method.desc.equals(currentDrawDescriptor))
                .findFirst()
                .orElse(null);
        if (currentDrawFromBuffers != null) {
            replaceCurrentGlCommandDraw(currentDrawFromBuffers, owner, pass);
            writeComputeFrames(node, output);
            return;
        }

        MethodNode drawFromBuffers = find(
                node,
                "drawFromBuffers",
                "(L" + pass + ";IIILcom/mojang/blaze3d/vertex/VertexFormat$IndexType;"
                        + "Lcom/mojang/blaze3d/opengl/GlRenderPipeline;I)V");
        drawFromBuffers.instructions.clear();
        drawFromBuffers.tryCatchBlocks.clear();
        if (drawFromBuffers.localVariables != null) {
            drawFromBuffers.localVariables.clear();
        }
        InsnList draw = drawFromBuffers.instructions;
        draw.add(new VarInsnNode(Opcodes.ALOAD, 0));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "device",
                "Lcom/mojang/blaze3d/opengl/GlDevice;"));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/GlDevice",
                "vertexArrayCache",
                "()Lcom/mojang/blaze3d/opengl/VertexArrayCache;",
                false));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 6));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/opengl/GlRenderPipeline",
                "gaius$vertexFormat",
                "Lcom/mojang/blaze3d/vertex/VertexFormat;"));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 1));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                pass,
                "vertexBuffers",
                "[Lcom/mojang/blaze3d/buffers/GpuBuffer;"));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new InsnNode(Opcodes.AALOAD));
        draw.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "com/mojang/blaze3d/opengl/GlBuffer"));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/VertexArrayCache",
                "bindVertexArray",
                "(Lcom/mojang/blaze3d/vertex/VertexFormat;"
                        + "Lcom/mojang/blaze3d/opengl/GlBuffer;)V",
                false));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 6));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/opengl/GlRenderPipeline",
                "gaius$drawMode",
                "I"));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 9));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 10));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 11));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 12));
        LabelNode nonIndexed = new LabelNode();
        draw.add(new VarInsnNode(Opcodes.ALOAD, 5));
        draw.add(new JumpInsnNode(Opcodes.IFNULL, nonIndexed));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 1));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                pass,
                "indexBuffer",
                "Lcom/mojang/blaze3d/buffers/GpuBuffer;"));
        draw.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "com/mojang/blaze3d/opengl/GlBuffer"));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/opengl/GlBuffer",
                "handle",
                "I"));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 12));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 5));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/vertex/VertexFormat$IndexType",
                "bytes",
                "I"));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 11));
        draw.add(new IntInsnNode(Opcodes.SIPUSH, 5121));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 11));
        draw.add(new InsnNode(Opcodes.IADD));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 10));
        draw.add(nonIndexed);
        draw.add(new VarInsnNode(Opcodes.ILOAD, 9));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 2));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 3));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 4));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 10));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 11));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 7));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 12));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "drawFromBuffers",
                "(IIIIIIII)V",
                false));
        draw.add(new InsnNode(Opcodes.RETURN));
        drawFromBuffers.maxStack = 8;
        drawFromBuffers.maxLocals = 13;
        writeComputeFrames(node, output);
    }

    private static void patchCurrentGlCommandEncoderGpuRetire(
            String jar, ClassNode node, String owner, Path outputRoot) throws IOException {
        FieldNode maxSubmits = node.fields.stream()
                .filter(field -> field.name.equals("MAX_SUBMITS_IN_FLIGHT")
                        && field.desc.equals("I"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Current GlCommandEncoder submit limit field was not found"));
        if (maxSubmits.value != null
                && (!(maxSubmits.value instanceof Integer value) || value != 2)) {
            throw new IllegalStateException(
                    "Current GlCommandEncoder submit limit changed: " + maxSubmits.value);
        }
        maxSubmits.value = BROWSER_GPU_RETIRE_SLOTS;
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String transientMemoryOwner =
                renderName(symbols, "com/mojang/blaze3d/opengl/GlTransientMemory");

        MethodNode constructor = find(
                node,
                "<init>",
                "(L" + renderName(symbols, "com/mojang/blaze3d/opengl/GlDevice") + ";)V");
        FieldInsnNode fencesStore = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("fences")
                    && field.desc.equals("[J")) {
                fencesStore = field;
                break;
            }
        }
        if (fencesStore == null) {
            throw new IllegalStateException(
                    "Current GlCommandEncoder fences initialization was not found");
        }
        AbstractInsnNode newArray = previousOpcode(fencesStore);
        AbstractInsnNode oldSize = newArray == null ? null : previousOpcode(newArray);
        if (newArray == null || newArray.getOpcode() != Opcodes.NEWARRAY
                || !(newArray instanceof IntInsnNode array)
                || array.operand != Opcodes.T_LONG
                || oldSize == null || oldSize.getOpcode() != Opcodes.ICONST_2) {
            throw new IllegalStateException(
                    "Current GlCommandEncoder fences array shape changed");
        }
        constructor.instructions.set(
                oldSize, new IntInsnNode(Opcodes.BIPUSH, BROWSER_GPU_RETIRE_SLOTS));
        FieldInsnNode transientMemoryStore = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("transientMemory")
                    && field.desc.equals("L" + transientMemoryOwner + ";")) {
                transientMemoryStore = field;
                break;
            }
        }
        if (transientMemoryStore == null) {
            throw new IllegalStateException(
                    "Current GlCommandEncoder transient memory initialization was not found");
        }
        constructor.instructions.insertBefore(
                transientMemoryStore,
                new TypeInsnNode(
                        Opcodes.CHECKCAST,
                        transientMemoryOwner));

        MethodNode currentSubmitSlot = find(node, "currentSubmitSlot", "()I");
        InsnList currentSlotCode = new InsnList();
        currentSlotCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        currentSlotCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "currentSubmitIndex", "J"));
        currentSlotCode.add(new LdcInsnNode((long) BROWSER_GPU_RETIRE_SLOTS));
        currentSlotCode.add(new InsnNode(Opcodes.LREM));
        currentSlotCode.add(new InsnNode(Opcodes.L2I));
        currentSlotCode.add(new InsnNode(Opcodes.IRETURN));
        replace(currentSubmitSlot, currentSlotCode, 4, 1);

        MethodNode backlog = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "gaius$retireBacklog",
                "()I",
                null,
                null);
        backlog.instructions.add(new InsnNode(Opcodes.ICONST_0));
        backlog.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        for (int slot = 0; slot < BROWSER_GPU_RETIRE_SLOTS; slot++) {
            LabelNode empty = new LabelNode();
            backlog.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            backlog.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, "fences", "[J"));
            backlog.instructions.add(new IntInsnNode(Opcodes.BIPUSH, slot));
            backlog.instructions.add(new InsnNode(Opcodes.LALOAD));
            backlog.instructions.add(new InsnNode(Opcodes.LCONST_0));
            backlog.instructions.add(new InsnNode(Opcodes.LCMP));
            backlog.instructions.add(new JumpInsnNode(Opcodes.IFEQ, empty));
            backlog.instructions.add(new IincInsnNode(1, 1));
            backlog.instructions.add(empty);
        }
        backlog.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        backlog.instructions.add(new InsnNode(Opcodes.IRETURN));
        backlog.maxStack = 4;
        backlog.maxLocals = 2;
        node.methods.add(backlog);

        MethodNode poll = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "gaius$pollRetireSlot",
                "(IJ)Z",
                null,
                null);
        LabelNode pollFence = new LabelNode();
        LabelNode signaled = new LabelNode();
        LabelNode pending = new LabelNode();
        poll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        poll.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "fences", "[J"));
        poll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        poll.instructions.add(new InsnNode(Opcodes.LALOAD));
        poll.instructions.add(new VarInsnNode(Opcodes.LSTORE, 4));
        poll.instructions.add(new VarInsnNode(Opcodes.LLOAD, 4));
        poll.instructions.add(new InsnNode(Opcodes.LCONST_0));
        poll.instructions.add(new InsnNode(Opcodes.LCMP));
        poll.instructions.add(new JumpInsnNode(Opcodes.IFNE, pollFence));
        poll.instructions.add(new InsnNode(Opcodes.ICONST_1));
        poll.instructions.add(new InsnNode(Opcodes.IRETURN));
        poll.instructions.add(pollFence);
        poll.instructions.add(new VarInsnNode(Opcodes.LLOAD, 4));
        poll.instructions.add(new InsnNode(Opcodes.ICONST_1));
        poll.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
        poll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                renderName(symbols, "com/mojang/blaze3d/opengl/GlStateManager"),
                "_glClientWaitSync",
                "(JIJ)I",
                false));
        poll.instructions.add(new VarInsnNode(Opcodes.ISTORE, 6));
        poll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        poll.instructions.add(new LdcInsnNode(37146));
        poll.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, signaled));
        poll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        poll.instructions.add(new LdcInsnNode(37148));
        poll.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, signaled));
        poll.instructions.add(new JumpInsnNode(Opcodes.GOTO, pending));
        poll.instructions.add(signaled);
        poll.instructions.add(new VarInsnNode(Opcodes.LLOAD, 4));
        poll.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL33C",
                "glDeleteSync",
                "(J)V",
                false));
        poll.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        poll.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "fences", "[J"));
        poll.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        poll.instructions.add(new InsnNode(Opcodes.LCONST_0));
        poll.instructions.add(new InsnNode(Opcodes.LASTORE));
        poll.instructions.add(new InsnNode(Opcodes.ICONST_1));
        poll.instructions.add(new InsnNode(Opcodes.IRETURN));
        poll.instructions.add(pending);
        poll.instructions.add(new InsnNode(Opcodes.ICONST_0));
        poll.instructions.add(new InsnNode(Opcodes.IRETURN));
        poll.maxStack = 5;
        poll.maxLocals = 7;
        node.methods.add(poll);

        MethodNode submit = find(node, "submit", "()V");
        InsnList submitCode = new InsnList();
        LabelNode slotAvailable = new LabelNode();
        LabelNode fenceCreated = new LabelNode();
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "gaius$retireBacklog", "()I", false));
        submitCode.add(new IntInsnNode(Opcodes.BIPUSH, BROWSER_GPU_RETIRE_SLOTS));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "beginGpuRetireFrame",
                "(II)V",
                false));
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "currentSubmitSlot", "()I", false));
        submitCode.add(new VarInsnNode(Opcodes.ISTORE, 1));
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "fences", "[J"));
        submitCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        submitCode.add(new InsnNode(Opcodes.LALOAD));
        submitCode.add(new InsnNode(Opcodes.LCONST_0));
        submitCode.add(new InsnNode(Opcodes.LCMP));
        submitCode.add(new JumpInsnNode(Opcodes.IFEQ, slotAvailable));
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        submitCode.add(new InsnNode(Opcodes.LCONST_0));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "gaius$pollRetireSlot", "(IJ)Z", false));
        submitCode.add(new JumpInsnNode(Opcodes.IFNE, slotAvailable));
        addGpuRetireFrameEnd(submitCode, owner, true);
        submitCode.add(new InsnNode(Opcodes.RETURN));
        submitCode.add(slotAvailable);
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "markNextFenceRetireOwned",
                "()V",
                false));
        submitCode.add(new LdcInsnNode(37143));
        submitCode.add(new InsnNode(Opcodes.ICONST_0));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL33C",
                "glFenceSync",
                "(II)J",
                false));
        submitCode.add(new VarInsnNode(Opcodes.LSTORE, 2));
        submitCode.add(new VarInsnNode(Opcodes.LLOAD, 2));
        submitCode.add(new InsnNode(Opcodes.LCONST_0));
        submitCode.add(new InsnNode(Opcodes.LCMP));
        submitCode.add(new JumpInsnNode(Opcodes.IFNE, fenceCreated));
        addGpuRetireFrameEnd(submitCode, owner, true);
        submitCode.add(new InsnNode(Opcodes.RETURN));
        submitCode.add(fenceCreated);
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "fences", "[J"));
        submitCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        submitCode.add(new VarInsnNode(Opcodes.LLOAD, 2));
        submitCode.add(new InsnNode(Opcodes.LASTORE));
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "transientMemory",
                "L" + transientMemoryOwner + ";"));
        submitCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                transientMemoryOwner,
                "rotate",
                "()V",
                false));
        submitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        submitCode.add(new InsnNode(Opcodes.DUP));
        submitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "currentSubmitIndex", "J"));
        submitCode.add(new InsnNode(Opcodes.LCONST_1));
        submitCode.add(new InsnNode(Opcodes.LADD));
        submitCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "currentSubmitIndex", "J"));
        addGpuRetireFrameEnd(submitCode, owner, false);
        submitCode.add(new InsnNode(Opcodes.RETURN));
        replace(submit, submitCode, 5, 4);

        MethodNode awaitSubmit = find(node, "awaitSubmit", "(JJ)Z");
        InsnList awaitCode = new InsnList();
        LabelNode submitted = new LabelNode();
        LabelNode inRetireWindow = new LabelNode();
        awaitCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        awaitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        awaitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "currentSubmitIndex", "J"));
        awaitCode.add(new InsnNode(Opcodes.LCMP));
        awaitCode.add(new JumpInsnNode(Opcodes.IFLT, submitted));
        awaitCode.add(new InsnNode(Opcodes.ICONST_0));
        awaitCode.add(new InsnNode(Opcodes.IRETURN));
        awaitCode.add(submitted);
        awaitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        awaitCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "currentSubmitIndex", "J"));
        awaitCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        awaitCode.add(new InsnNode(Opcodes.LSUB));
        awaitCode.add(new LdcInsnNode((long) BROWSER_GPU_RETIRE_SLOTS));
        awaitCode.add(new InsnNode(Opcodes.LCMP));
        awaitCode.add(new JumpInsnNode(Opcodes.IFLE, inRetireWindow));
        awaitCode.add(new InsnNode(Opcodes.ICONST_1));
        awaitCode.add(new InsnNode(Opcodes.IRETURN));
        awaitCode.add(inRetireWindow);
        awaitCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        awaitCode.add(new LdcInsnNode((long) BROWSER_GPU_RETIRE_SLOTS));
        awaitCode.add(new InsnNode(Opcodes.LREM));
        awaitCode.add(new InsnNode(Opcodes.L2I));
        awaitCode.add(new VarInsnNode(Opcodes.ISTORE, 5));
        awaitCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        awaitCode.add(new VarInsnNode(Opcodes.ILOAD, 5));
        awaitCode.add(new VarInsnNode(Opcodes.LLOAD, 3));
        awaitCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "gaius$pollRetireSlot", "(IJ)Z", false));
        awaitCode.add(new InsnNode(Opcodes.IRETURN));
        replace(awaitSubmit, awaitCode, 4, 6);

        MethodNode close = find(node, "close", "()V");
        InsnList closeCode = new InsnList();
        closeCode.add(new InsnNode(Opcodes.ICONST_1));
        closeCode.add(new VarInsnNode(Opcodes.ISTORE, 1));
        for (int slot = 0; slot < BROWSER_GPU_RETIRE_SLOTS; slot++) {
            LabelNode retired = new LabelNode();
            closeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            closeCode.add(new IntInsnNode(Opcodes.BIPUSH, slot));
            closeCode.add(new InsnNode(Opcodes.LCONST_0));
            closeCode.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, owner, "gaius$pollRetireSlot", "(IJ)Z", false));
            closeCode.add(new JumpInsnNode(Opcodes.IFNE, retired));
            closeCode.add(new InsnNode(Opcodes.ICONST_0));
            closeCode.add(new VarInsnNode(Opcodes.ISTORE, 1));
            closeCode.add(retired);
        }
        LabelNode closeDeferred = new LabelNode();
        LabelNode closeDone = new LabelNode();
        closeCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        closeCode.add(new JumpInsnNode(Opcodes.IFEQ, closeDeferred));
        closeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        closeCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "transientMemory",
                "L" + transientMemoryOwner + ";"));
        closeCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                transientMemoryOwner,
                "close",
                "()V",
                false));
        closeCode.add(new InsnNode(Opcodes.ICONST_0));
        closeCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "gpuRetireClose",
                "(I)V",
                false));
        closeCode.add(new JumpInsnNode(Opcodes.GOTO, closeDone));
        closeCode.add(closeDeferred);
        closeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        closeCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "gaius$retireBacklog", "()I", false));
        closeCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "gpuRetireClose",
                "(I)V",
                false));
        closeCode.add(closeDone);
        closeCode.add(new InsnNode(Opcodes.RETURN));
        replace(close, closeCode, 4, 2);

        patchCurrentGlTransientMemoryRotations(jar, outputRoot);
        System.out.println(
                "Patched current GlCommandEncoder with " + BROWSER_GPU_RETIRE_SLOTS
                        + "-slot asynchronous GPU retire");
    }

    private static void addGpuRetireFrameEnd(
            InsnList code, String owner, boolean backpressure) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "gaius$retireBacklog", "()I", false));
        code.add(new InsnNode(backpressure ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "endGpuRetireFrame",
                "(IZ)V",
                false));
    }

    private static void patchCurrentGlTransientMemoryRotations(
            String jar, Path outputRoot) throws IOException {
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String owner = renderName(
                symbols, "com/mojang/blaze3d/opengl/GlTransientMemory$PersistentMapping");
        ClassNode node = read(jar, owner + ".class");
        MethodNode constructor = find(
                node,
                "<init>",
                "(L" + renderName(symbols, "com/mojang/blaze3d/opengl/GlDevice") + ";"
                        + "L" + renderName(symbols, "com/mojang/blaze3d/opengl/GlCommandEncoder")
                        + ";)V");
        FieldInsnNode rotationsStore = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(owner)
                    && field.name.equals("rotations")
                    && field.desc.equals("[L" + owner + "$Rotation;")) {
                rotationsStore = field;
                break;
            }
        }
        if (rotationsStore == null) {
            throw new IllegalStateException(
                    "Current persistent transient rotation array was not found");
        }
        AbstractInsnNode newArray = previousOpcode(rotationsStore);
        AbstractInsnNode oldSize = newArray == null ? null : previousOpcode(newArray);
        if (newArray == null || newArray.getOpcode() != Opcodes.ANEWARRAY
                || oldSize == null || oldSize.getOpcode() != Opcodes.ICONST_2) {
            throw new IllegalStateException(
                    "Current persistent transient rotation array shape changed");
        }
        constructor.instructions.set(
                oldSize, new IntInsnNode(Opcodes.BIPUSH, BROWSER_GPU_RETIRE_SLOTS));
        writeComputeFrames(node, outputRoot.resolve(owner + ".class"));
        patchCurrentGlTransientMemoryFallback(jar, outputRoot);
    }

    private static void patchCurrentGlTransientMemoryFallback(
            String jar, Path outputRoot) throws IOException {
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String owner = renderName(
                symbols, "com/mojang/blaze3d/opengl/GlTransientMemory$Fallback");
        String base = renderName(symbols, "com/mojang/blaze3d/opengl/GlTransientMemory");
        String device = renderName(symbols, "com/mojang/blaze3d/opengl/GlDevice");
        String encoder = renderName(symbols, "com/mojang/blaze3d/opengl/GlCommandEncoder");
        String blockAllocator =
                renderName(symbols, "com/mojang/blaze3d/util/TransientBlockAllocator");
        String rotationsField = "gaius$retireRotations";
        ClassNode node = read(jar, owner + ".class");
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                rotationsField,
                "[Ljava/lang/Runnable;",
                null,
                null));

        MethodNode constructor = find(
                node,
                "<init>",
                "(L" + device + ";L" + encoder + ";)V");
        AbstractInsnNode constructorReturn = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                constructorReturn = instruction;
                break;
            }
        }
        if (constructorReturn == null) {
            throw new IllegalStateException(
                    "Current fallback transient constructor return was not found");
        }
        InsnList initialize = new InsnList();
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialize.add(new IntInsnNode(Opcodes.BIPUSH, BROWSER_GPU_RETIRE_SLOTS));
        initialize.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Runnable"));
        initialize.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
        constructor.instructions.insertBefore(constructorReturn, initialize);

        MethodNode rotate = find(node, "rotate", "()V");
        InsnList rotateCode = new InsnList();
        LabelNode noPrevious = new LabelNode();
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                base,
                "encoder",
                "L" + encoder + ";"));
        rotateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                encoder,
                "currentSubmitSlot",
                "()I",
                false));
        rotateCode.add(new VarInsnNode(Opcodes.ISTORE, 1));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
        rotateCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        rotateCode.add(new InsnNode(Opcodes.AALOAD));
        rotateCode.add(new VarInsnNode(Opcodes.ASTORE, 2));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
        rotateCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        rotateCode.add(new InsnNode(Opcodes.ACONST_NULL));
        rotateCode.add(new InsnNode(Opcodes.AASTORE));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        rotateCode.add(new JumpInsnNode(Opcodes.IFNULL, noPrevious));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        rotateCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/lang/Runnable",
                "run",
                "()V",
                true));
        rotateCode.add(noPrevious);
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
        rotateCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "blockAllocator",
                "L" + blockAllocator + ";"));
        rotateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                blockAllocator,
                "rotate",
                "()Ljava/lang/Runnable;",
                false));
        rotateCode.add(new InsnNode(Opcodes.AASTORE));
        rotateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rotateCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, base, "rotate", "()V", false));
        rotateCode.add(new InsnNode(Opcodes.RETURN));
        replace(rotate, rotateCode, 4, 3);

        MethodNode close = find(node, "close", "()V");
        InsnList closeCode = new InsnList();
        for (int slot = 0; slot < BROWSER_GPU_RETIRE_SLOTS; slot++) {
            LabelNode empty = new LabelNode();
            closeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            closeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
            closeCode.add(new IntInsnNode(Opcodes.BIPUSH, slot));
            closeCode.add(new InsnNode(Opcodes.AALOAD));
            closeCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
            closeCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
            closeCode.add(new FieldInsnNode(
                    Opcodes.GETFIELD, owner, rotationsField, "[Ljava/lang/Runnable;"));
            closeCode.add(new IntInsnNode(Opcodes.BIPUSH, slot));
            closeCode.add(new InsnNode(Opcodes.ACONST_NULL));
            closeCode.add(new InsnNode(Opcodes.AASTORE));
            closeCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            closeCode.add(new JumpInsnNode(Opcodes.IFNULL, empty));
            closeCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
            closeCode.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/lang/Runnable",
                    "run",
                    "()V",
                    true));
            closeCode.add(empty);
        }
        closeCode.add(new InsnNode(Opcodes.RETURN));
        replace(close, closeCode, 4, 2);
        writeComputeFrames(node, outputRoot.resolve(owner + ".class"));
    }

    private static void replaceCurrentGlCommandDraw(
            MethodNode method, String owner, String pass) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }

        InsnList draw = method.instructions;
        draw.add(new VarInsnNode(Opcodes.ALOAD, 6));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/opengl/GlRenderPipeline",
                "gaius$primitiveTopology",
                "I"));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 9));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 10));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 11));
        draw.add(new InsnNode(Opcodes.ICONST_0));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 12));

        LabelNode nonIndexed = new LabelNode();
        draw.add(new VarInsnNode(Opcodes.ALOAD, 5));
        draw.add(new JumpInsnNode(Opcodes.IFNULL, nonIndexed));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 1));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                pass,
                "indexBuffer",
                "Lcom/mojang/blaze3d/buffers/GpuBuffer;"));
        draw.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "com/mojang/blaze3d/opengl/GlBuffer"));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/GlBuffer",
                "handle",
                "()I",
                false));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 12));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 5));
        draw.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "com/mojang/blaze3d/IndexType",
                "bytes",
                "I"));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 11));
        draw.add(new VarInsnNode(Opcodes.ALOAD, 5));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/mojang/blaze3d/opengl/GlConst",
                "toGl",
                "(Lcom/mojang/blaze3d/IndexType;)I",
                false));
        draw.add(new VarInsnNode(Opcodes.ISTORE, 10));
        draw.add(nonIndexed);

        draw.add(new VarInsnNode(Opcodes.ILOAD, 9));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 2));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 3));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 4));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 10));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 11));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 7));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 12));
        draw.add(new VarInsnNode(Opcodes.ILOAD, 8));
        draw.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "drawFromBuffers",
                "(IIIIIIIII)V",
                false));
        draw.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 9;
        method.maxLocals = 13;
        System.out.println("Patched current GlCommandEncoder draw path for WebGL");
    }

    private static void bindDefaultSlice(
            MethodNode method, String renderSystem, String pass,
            String getter, String uniformName, String sliceDescriptor) {
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                renderSystem,
                getter,
                "()" + sliceDescriptor,
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        org.objectweb.asm.tree.LabelNode missing = new org.objectweb.asm.tree.LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNULL, missing));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new LdcInsnNode(uniformName));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                pass,
                "setUniform",
                "(Ljava/lang/String;" + sliceDescriptor + ")V",
                false));
        method.instructions.add(missing);
    }

    public static void patchGlslPreprocessor(String jar, Path output) throws IOException {
        if (ModernSymbols.cached(jar).renderTypeRemoved(
                "com/mojang/blaze3d/preprocessor/GlslPreprocessor")) {
            // 26.3 compiles shaders through shaderc (includes via its callbacks, D5/P4).
            PatchRegistry.dropped("MinecraftClientPatcher.patchGlslPreprocessor", jar,
                    "com/mojang/blaze3d/preprocessor/GlslPreprocessor.class");
            return;
        }
        ClassNode node = read(jar, "com/mojang/blaze3d/preprocessor/GlslPreprocessor.class");
        MethodNode clinit = find(node, "<clinit>", "()V");
        boolean changed = false;
        for (var instruction = clinit.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String text)) {
                continue;
            }
            String replacement = switch (text) {
                case "(#(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*moj_import(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*(?:\"(.*)\"|<(.*)>))" ->
                        "(#(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|[ \\t\\f])*moj_import(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|[ \\t\\f])*(?:\"(.*)\"|<(.*)>))";
                case "(#(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*version(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*(\\d+))\\b" ->
                        "(#(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|[ \\t\\f])*version(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|[ \\t\\f])*(\\d+))\\b";
                case "(?:^|\\v)(?:\\s|/\\*(?:[^*]|\\*+[^*/])*\\*+/(//[^\\v]*))*\\z" ->
                        null;
                case "(?:^|\\v)(?:\\s|/\\*(?:[^*]|\\*+[^*/])*\\*+/|(//[^\\v]*))*\\z" ->
                        "(?:^|\\n)(?:\\s|/\\*(?:[^*]|\\*+[^*/])*\\*+/|(//[^\\n]*))*$";
                default -> null;
            };
            if (replacement != null) {
                ldc.cst = replacement;
                changed = true;
            }
        }
        if (!changed) {
            throw new IllegalStateException("GlslPreprocessor regex constants were not found");
        }
        write(node, output);
    }

    public static void patchGameRendererBrowserAutoScreenshot(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/renderer/GameRenderer.class");

        MethodNode method = find(node, "tryTakeScreenshotIfNeeded", "()V");
        InsnList code = new InsnList();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "hasWorldScreenshot",
                "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "hasWorldScreenshot",
                "Z"));
        code.add(new LdcInsnNode("browser.autoWorldScreenshot.disabled"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportMinecraftEvent",
                "(Ljava/lang/String;)V",
                false));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 2, 1);
        boolean deltaFreeFrame = gameRendererDeltaFreeFrame(node, jar);
        patchGameRendererBrowserFrameBudget(node, deltaFreeFrame);
        patchGameRendererBrowserInventoryWorldRenderThrottle(node, deltaFreeFrame, jar);
        patchGameRendererBrowserTargetingAfterCamera(node);
        writeComputeFrames(node, output);
    }

    /**
     * 26.3 dropped the DeltaTracker parameters of GameRenderer.render/renderLevel (render()V,
     * renderLevel()V). Exactly one frame-loop shape must be present, and it must agree with the
     * probed render API, so a mixed jar fails instead of patching half a frame loop.
     */
    private static boolean gameRendererDeltaFreeFrame(ClassNode node, String jar)
            throws IOException {
        boolean legacy = findNullable(
                node, "render", "(Lnet/minecraft/client/DeltaTracker;Z)V") != null;
        boolean deltaFree = findNullable(node, "render", "()V") != null
                && findNullable(node, "renderLevel", "()V") != null;
        if (legacy == deltaFree || deltaFree != ModernSymbols.cached(jar).renderpearl()) {
            throw new IllegalStateException("GameRenderer frame-loop shape is ambiguous: "
                    + "render(DeltaTracker;Z)=" + legacy + " render()/renderLevel()=" + deltaFree);
        }
        return deltaFree;
    }

    private static void patchGameRendererBrowserFrameBudget(
            ClassNode node, boolean deltaFreeFrame) {
        MethodNode render = find(
                node,
                "render",
                deltaFreeFrame ? "()V" : "(Lnet/minecraft/client/DeltaTracker;Z)V");
        render.instructions.insert(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "beginFrame",
                "()V",
                false));
        System.out.println("Patched GameRenderer frame-scoped browser upload budget");
    }

    private static void patchGameRendererBrowserTargetingAfterCamera(ClassNode node) {
        MethodNode method = null;
        MethodInsnNode cameraExtraction = null;
        for (MethodNode candidate : node.methods) {
            for (AbstractInsnNode instruction = candidate.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals("net/minecraft/client/renderer/GameRenderer")
                        || !call.name.equals("extractCamera")
                        || !(call.desc.equals("(F)V")
                                || call.desc.equals("(Lnet/minecraft/client/DeltaTracker;FF)V")
                                || call.desc.equals("(Lnet/minecraft/client/DeltaTracker;F)V"))) {
                    continue;
                }
                if (cameraExtraction != null) {
                    throw new IllegalStateException(
                            "GameRenderer has multiple camera extraction patch points");
                }
                method = candidate;
                cameraExtraction = call;
            }
        }
        if (method == null || cameraExtraction == null) {
            throw new IllegalStateException(
                    "GameRenderer post-camera block targeting patch point was not found");
        }

        if (cameraExtraction.desc.equals("(Lnet/minecraft/client/DeltaTracker;FF)V")
                || cameraExtraction.desc.equals("(Lnet/minecraft/client/DeltaTracker;F)V")) {
            // Current Minecraft already runs GameRenderer.update -> Minecraft.pick ->
            // GameRenderer.extract once per rendered frame. A second post-camera raycast
            // duplicates the hottest targeting work and can desynchronize
            // crosshairPickEntity from hitResult. 26.3's (DeltaTracker;F) computes the camera
            // partial tick inside Camera.extractRenderState; the stabilize branch below reads a
            // float from local 2 and must never run for it.
            System.out.println("Verified current vanilla single-raycast block targeting");
            return;
        }

        InsnList stabilize = new InsnList();
        stabilize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        stabilize.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        stabilize.add(new InsnNode(Opcodes.DUP));
        stabilize.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "hitResult",
                "Lnet/minecraft/world/phys/HitResult;"));
        stabilize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        stabilize.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        stabilize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        stabilize.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "mainCamera",
                "Lnet/minecraft/client/Camera;"));
        stabilize.add(new VarInsnNode(
                Opcodes.FLOAD,
                2));
        stabilize.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserTargeting",
                "stabilizeBlockHit",
                "(Lnet/minecraft/world/phys/HitResult;"
                        + "Lnet/minecraft/client/Minecraft;"
                        + "Lnet/minecraft/client/Camera;F)"
                        + "Lnet/minecraft/world/phys/HitResult;",
                false));
        stabilize.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                "net/minecraft/client/Minecraft",
                "hitResult",
                "Lnet/minecraft/world/phys/HitResult;"));
        method.instructions.insert(cameraExtraction, stabilize);
        System.out.println("Patched block targeting after " + method.name + method.desc);
    }

    /**
     * Throttles the world render behind inventory screens.
     *
     * <p>26.3 ({@code deltaFreeFrame}) keeps the
     * {@code BrowserOpenGL.shouldSkipWorldRenderForScreen} shape here;
     * {@code QualityTierPatches.patchInventoryWorldRenderThrottle} rewrites it to the quality
     * hooks later in the chain. 26.2 and 1.21.11 get those hooks directly:
     * {@code BrowserQualityFrame.shouldSkipWorldRender(Object)Z} decides per frame (the tier's
     * reduced world refresh rate; without the quality runtime the old render-two-frames-then-skip
     * rule), and {@code BrowserQualityFrame.worldFrameDone(III)V} at the merge point after the
     * world section snapshots rendered frames and restores the snapshot on skipped ones. The
     * restore is required: both profiles clear the main target's colour and depth every frame
     * before this world section (26.2 at the top of {@code GameRenderer.render}, 1.21.11 in
     * {@code Minecraft.runTick} right before {@code GameRenderer.render}), so a skipped world
     * section otherwise leaves the clear colour behind the inventory.</p>
     */
    private static void patchGameRendererBrowserInventoryWorldRenderThrottle(
            ClassNode node, boolean deltaFreeFrame, String jar) throws IOException {
        // 26.3: render()V calls renderLevel()V with only the receiver on the stack, and the
        // profiler lives in local 1 instead of local 3.
        MethodNode method = find(node, "render",
                deltaFreeFrame ? "()V" : "(Lnet/minecraft/client/DeltaTracker;Z)V");
        String renderLevelDescriptor = deltaFreeFrame
                ? "()V" : "(Lnet/minecraft/client/DeltaTracker;)V";
        int profilerLocal = deltaFreeFrame ? 1 : 3;
        MethodInsnNode renderLevelCall = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/renderer/GameRenderer")
                    && call.name.equals("renderLevel")
                    && call.desc.equals(renderLevelDescriptor)) {
                renderLevelCall = call;
                break;
            }
        }
        if (renderLevelCall == null) {
            throw new IllegalStateException("GameRenderer.renderLevel call was not found");
        }

        AbstractInsnNode renderLevelThis = deltaFreeFrame
                ? previousRealInstruction(renderLevelCall)
                : previousRealInstruction(previousRealInstruction(renderLevelCall));
        if (!(renderLevelThis instanceof VarInsnNode loadThis)
                || loadThis.getOpcode() != Opcodes.ALOAD
                || loadThis.var != 0) {
            throw new IllegalStateException("Unexpected GameRenderer.renderLevel receiver sequence");
        }

        MethodInsnNode profilerPop = null;
        for (var instruction = renderLevelCall.getNext();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("net/minecraft/util/profiling/ProfilerFiller")
                    && call.name.equals("pop")
                    && call.desc.equals("()V")) {
                profilerPop = call;
                break;
            }
        }
        if (profilerPop == null || profilerPop.getNext() == null) {
            throw new IllegalStateException("GameRenderer world profiler pop was not found");
        }
        if (!(previousRealInstruction(profilerPop) instanceof VarInsnNode popProfiler)
                || popProfiler.getOpcode() != Opcodes.ALOAD
                || popProfiler.var != profilerLocal) {
            throw new IllegalStateException(
                    "GameRenderer world profiler pop no longer reads local " + profilerLocal);
        }

        LabelNode continueWorld = new LabelNode();
        LabelNode afterWorld = new LabelNode();
        method.instructions.insertBefore(profilerPop.getNext(), afterWorld);

        method.instructions.insertBefore(renderLevelThis, closeLevelLoadingScreenBeforeWorldRender());

        InsnList throttle = new InsnList();
        throttle.add(new VarInsnNode(Opcodes.ALOAD, 0));
        throttle.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        throttle.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$getScreen",
                "()Lnet/minecraft/client/gui/screens/Screen;",
                false));
        if (deltaFreeFrame) {
            throttle.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/opengl/BrowserOpenGL",
                    "shouldSkipWorldRenderForScreen",
                    "(Lnet/minecraft/client/gui/screens/Screen;)Z",
                    false));
        } else {
            throttle.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    BROWSER_QUALITY_FRAME,
                    "shouldSkipWorldRender",
                    "(Ljava/lang/Object;)Z",
                    false));
        }
        throttle.add(new JumpInsnNode(Opcodes.IFEQ, continueWorld));
        throttle.add(new VarInsnNode(Opcodes.ALOAD, profilerLocal));
        throttle.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/util/profiling/ProfilerFiller",
                "pop",
                "()V",
                true));
        throttle.add(new JumpInsnNode(Opcodes.GOTO, afterWorld));
        throttle.add(continueWorld);
        method.instructions.insertBefore(renderLevelThis, throttle);
        if (deltaFreeFrame) {
            return;
        }

        // worldFrameDone(colorTextureGlId, width, height) right after the merge label: the
        // rendered and the skipped world section both reach it with an empty stack, while the
        // no-level path still jumps to the original label after it.
        InsnList done = new InsnList();
        done.add(gameRendererMainTarget(node, method));
        done.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                BLAZE3D_RENDER_TARGET,
                "getColorTexture",
                "()L" + BLAZE3D_GPU_TEXTURE + ";",
                false));
        done.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/client/renderer/GameRenderer",
                GAME_RENDERER_GL_TEXTURE_ID,
                "(L" + BLAZE3D_GPU_TEXTURE + ";)I",
                false));
        done.add(gameRendererMainTarget(node, method));
        done.add(new FieldInsnNode(Opcodes.GETFIELD, BLAZE3D_RENDER_TARGET, "width", "I"));
        done.add(gameRendererMainTarget(node, method));
        done.add(new FieldInsnNode(Opcodes.GETFIELD, BLAZE3D_RENDER_TARGET, "height", "I"));
        done.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_QUALITY_FRAME,
                "worldFrameDone",
                "(III)V",
                false));
        method.instructions.insert(afterWorld, done);
        addGameRendererGlTextureIdHelper(node, jar);
        System.out.println("Patched GameRenderer inventory world throttle to the quality hooks"
                + " (BrowserQualityFrame.shouldSkipWorldRender/worldFrameDone)");
    }

    private static final String BROWSER_QUALITY_FRAME =
            "dev/gaius/browser/quality/BrowserQualityFrame";

    private static final String BLAZE3D_RENDER_TARGET = "com/mojang/blaze3d/pipeline/RenderTarget";

    private static final String BLAZE3D_GPU_TEXTURE = "com/mojang/blaze3d/textures/GpuTexture";

    private static final String BLAZE3D_GL_TEXTURE = "com/mojang/blaze3d/opengl/GlTexture";

    private static final String GAME_RENDERER_GL_TEXTURE_ID = "gaius$glTextureId";

    /**
     * Loads the main render target inside the blaze3d {@code GameRenderer.render}: the
     * {@code mainRenderTarget} field (26.2), or {@code minecraft.getMainRenderTarget()}
     * (1.21.11, whose render method already reads it that way). Any other shape fails.
     */
    private static InsnList gameRendererMainTarget(ClassNode node, MethodNode render) {
        String targetDesc = "L" + BLAZE3D_RENDER_TARGET + ";";
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (FieldNode field : node.fields) {
            if (field.name.equals("mainRenderTarget") && field.desc.equals(targetDesc)
                    && (field.access & Opcodes.ACC_STATIC) == 0) {
                code.add(new FieldInsnNode(Opcodes.GETFIELD,
                        "net/minecraft/client/renderer/GameRenderer", "mainRenderTarget",
                        targetDesc));
                return code;
            }
        }
        boolean readsMinecraftTarget = false;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            readsMinecraftTarget |= instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/Minecraft")
                    && call.name.equals("getMainRenderTarget")
                    && call.desc.equals("()" + targetDesc);
        }
        if (!readsMinecraftTarget) {
            throw new IllegalStateException("GameRenderer.render has neither a mainRenderTarget"
                    + " field nor a Minecraft.getMainRenderTarget() call");
        }
        code.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/client/renderer/GameRenderer",
                "minecraft", "Lnet/minecraft/client/Minecraft;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/Minecraft",
                "getMainRenderTarget", "()" + targetDesc, false));
        return code;
    }

    /**
     * Adds {@code private static int gaius$glTextureId(GpuTexture texture)} to the blaze3d
     * GameRenderer: 0 for null, else {@code ((GlTexture) texture).glId()}, the BrowserOpenGL
     * texture name the quality runtime resolves. Checks the RenderTarget and GlTexture members
     * it relies on first.
     *
     * <p>On WebAssembly GC the render seam of the profile (26.2 blaze3d-backend-1, 1.21.11
     * blaze3d-direct-0) can render with WebGPU, whose textures are not GlTextures: there the
     * helper tests {@code instanceof GlTexture} instead of null and returns 0 for any other
     * texture (the quality runtime ignores the id under WebGPU). The JavaScript build keeps the
     * null check and the cast, so its output does not change; for a GlTexture or null both shapes
     * return the same value.</p>
     */
    private static void addGameRendererGlTextureIdHelper(ClassNode node, String jar)
            throws IOException {
        String desc = "(L" + BLAZE3D_GPU_TEXTURE + ";)I";
        if (findNullable(node, GAME_RENDERER_GL_TEXTURE_ID, desc) != null) {
            throw new IllegalStateException(
                    "GameRenderer." + GAME_RENDERER_GL_TEXTURE_ID + " already exists");
        }
        ClassNode target = read(jar, BLAZE3D_RENDER_TARGET + ".class");
        long sizeFields = target.fields.stream()
                .filter(field -> field.desc.equals("I")
                        && (field.name.equals("width") || field.name.equals("height"))
                        && (field.access & Opcodes.ACC_PUBLIC) != 0
                        && (field.access & Opcodes.ACC_STATIC) == 0)
                .count();
        if (findNullable(target, "getColorTexture", "()L" + BLAZE3D_GPU_TEXTURE + ";") == null
                || sizeFields != 2) {
            throw new IllegalStateException(BLAZE3D_RENDER_TARGET
                    + " no longer has getColorTexture() and public int width/height");
        }
        ClassNode glTexture = read(jar, BLAZE3D_GL_TEXTURE + ".class");
        if (!BLAZE3D_GPU_TEXTURE.equals(glTexture.superName)
                || findNullable(glTexture, "glId", "()I") == null) {
            throw new IllegalStateException(BLAZE3D_GL_TEXTURE
                    + " is no longer a GpuTexture with glId()I");
        }
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                GAME_RENDERER_GL_TEXTURE_ID, desc, null, null);
        LabelNode present = new LabelNode();
        InsnList code = helper.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (BuildTarget.WASM_GC.equals(BuildTarget.name())) {
            code.add(new TypeInsnNode(Opcodes.INSTANCEOF, BLAZE3D_GL_TEXTURE));
            code.add(new JumpInsnNode(Opcodes.IFNE, present));
        } else {
            code.add(new JumpInsnNode(Opcodes.IFNONNULL, present));
        }
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(present);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, BLAZE3D_GL_TEXTURE));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, BLAZE3D_GL_TEXTURE, "glId", "()I", false));
        code.add(new InsnNode(Opcodes.IRETURN));
        helper.maxLocals = 1;
        helper.maxStack = 1;
        node.methods.add(helper);
    }

    private static InsnList closeLevelLoadingScreenBeforeWorldRender() {
        InsnList code = new InsnList();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$getScreen",
                "()Lnet/minecraft/client/gui/screens/Screen;",
                false));
        code.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/gui/screens/LevelLoadingScreen"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "level",
                "Lnet/minecraft/client/multiplayer/ClientLevel;"));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "player",
                "Lnet/minecraft/client/player/LocalPlayer;"));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/renderer/GameRenderer",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$setScreen",
                "(Lnet/minecraft/client/gui/screens/Screen;)V",
                false));
        code.add(minecraftEvent("client.levelReady.closeLoadingScreenFromWorldRender"));
        code.add(done);
        return code;
    }

    public static void patchParticleGroupBrowserTickSafety(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/particle/ParticleGroup.class");
        MethodNode method = find(node, "tickParticle",
                "(Lnet/minecraft/client/particle/Particle;)V");
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/Particle",
                "tick",
                "()V",
                false));
        code.add(end);
        code.add(new JumpInsnNode(Opcodes.GOTO, done));
        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new LdcInsnNode("particle.tick"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportMinecraftThrowable",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/Particle",
                "remove",
                "()V",
                false));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 2, 3);
        method.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(
                start, end, handler, "java/lang/Throwable"));
        patchParticleGroupTickParticlesLoop(node);
        patchParticleGroupAddNullGuard(node);
        writeComputeFrames(node, output);
    }

    private static void patchParticleGroupTickParticlesLoop(ClassNode node) {
        MethodNode method = find(node, "tickParticles", "()V");
        InsnList code = new InsnList();
        LabelNode loop = new LabelNode();
        LabelNode tick = new LabelNode();
        LabelNode alive = new LabelNode();
        LabelNode remove = new LabelNode();
        LabelNode done = new LabelNode();

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/particle/ParticleGroup",
                "particles",
                "Ljava/util/Queue;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Queue",
                "isEmpty",
                "()Z",
                true));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/particle/ParticleGroup",
                "particles",
                "Ljava/util/Queue;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Queue",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/particle/Particle"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, tick));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "remove",
                "()V",
                true));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(tick);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/ParticleGroup",
                "tickParticle",
                "(Lnet/minecraft/client/particle/Particle;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/Particle",
                "isAlive",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFNE, alive));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/Particle",
                "getParticleLimit",
                "()Ljava/util/Optional;",
                false));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/Optional",
                "orElse",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/core/particles/ParticleLimit"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFNULL, remove));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/particle/ParticleGroup",
                "engine",
                "Lnet/minecraft/client/particle/ParticleEngine;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/particle/ParticleEngine",
                "updateCount",
                "(Lnet/minecraft/core/particles/ParticleLimit;I)V",
                false));
        code.add(remove);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "remove",
                "()V",
                true));
        code.add(alive);
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 3, 4);
    }

    private static void patchParticleGroupAddNullGuard(ClassNode node) {
        String voidDescriptor = "(Lnet/minecraft/client/particle/Particle;)V";
        String booleanDescriptor = "(Lnet/minecraft/client/particle/Particle;)Z";
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("add")
                        && (candidate.desc.equals(voidDescriptor)
                                || candidate.desc.equals(booleanDescriptor)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ParticleGroup.add browser null-guard target was not found"));
        LabelNode nonNull = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new JumpInsnNode(Opcodes.IFNONNULL, nonNull));
        if (method.desc.equals(booleanDescriptor)) {
            guard.add(new InsnNode(Opcodes.ICONST_0));
            guard.add(new InsnNode(Opcodes.IRETURN));
        } else {
            guard.add(new InsnNode(Opcodes.RETURN));
        }
        guard.add(nonNull);
        method.instructions.insert(guard);
        method.maxStack = Math.max(method.maxStack, 1);
    }

    public static void patchClientLevelBrowserBlockBreakEffects(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/multiplayer/ClientLevel.class");
        patchClientLevelBrowserAnimateTickBudget(node);
        patchClientLevelBrowserBlockBreakingTelemetry(node);
        write(node, output);
    }

    private static void patchClientLevelBrowserBlockBreakingTelemetry(ClassNode node) {
        MethodNode method = find(node, "destroyBlockProgress",
                "(ILnet/minecraft/core/BlockPos;I)V");
        InsnList call = new InsnList();
        call.add(new VarInsnNode(Opcodes.ILOAD, 3));
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBlockBreakingTelemetry",
                "recordDestroyProgress", "(I)V", false));
        method.instructions.insert(call);
    }

    private static void patchClientLevelBrowserAnimateTickBudget(ClassNode node) {
        MethodNode method = find(node, "animateTick", "(III)V");
        int patched = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof IntInsnNode push
                    && push.getOpcode() == Opcodes.SIPUSH
                    && push.operand == 667) {
                method.instructions.set(push, new IntInsnNode(Opcodes.BIPUSH, 64));
                patched++;
            }
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "ClientLevel.animateTick browser budget patch point was not found");
        }
    }

    public static void patchLevelRendererBrowserBlockBreakProgress(String jar, Path output,
            boolean refreshVisibleSections)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/renderer/LevelRenderer.class");
        // 26.3 (renderpearl) moved the per-section loop into extractSectionDrawGroups, dropped
        // the DeltaTracker and Matrix4fc render parameters (camera state is local 3, not 4)
        // and passes a boolean to submitBreakingBlockModel. Every sub-patch below is required
        // there: no 26.3 shape may fall back to a skip.
        boolean current = ModernSymbols.cached(jar).renderpearl();
        if (current) {
            patchLevelRendererBrowserExtractSectionDrawGroups(node);
        } else {
            patchLevelRendererBrowserPrepareChunkRenders(node);
        }
        PatchRegistry.runPart("MinecraftClientPatcher.prepareAfterOcclusionUpdate",
                "REQUIRED NOT_APPLICABLE js wasmgc", node,
                () -> patchLevelRendererBrowserPrepareAfterOcclusionUpdate(node, current));
        // Consume the graph publication flag after update() so the next frame's
        // draw list includes sections from the newly published graph.
        PatchRegistry.runPart("MinecraftClientPatcher.refreshVisibleSections",
                "REQUIRED NOT_APPLICABLE js wasmgc", node,
                () -> patchLevelRendererBrowserRefreshVisibleSections(node, current));
        patchLevelRendererBrowserSectionCompileThrottle(node);
        patchLevelRendererBrowserBlockOutlineOpacity(node);
        patchLevelRendererBrowserBlockBreakingTelemetry(node, current);
        writeComputeFrames(node, output);
    }

    /** LevelRenderer.render of 26.3; its camera state parameter is local 3. */
    private static final String LEVEL_RENDERER_RENDER_263 =
            "(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Z"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                    + "Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
                    + "Lorg/joml/Vector4f;ZZ)V";

    /**
     * 26.3 shape of {@link #patchLevelRendererBrowserPrepareChunkRenders}. Vanilla already
     * copies the terrain matrix once per frame (in render) and reads the clock once before the
     * section loop, which now lives in extractSectionDrawGroups and serves both prepare variants.
     * What remains: the per-section {@code ChunkSectionLayer.values()} clone (redirected to the
     * shared array, also in prepareChunkRenders) and the prepare statistics, which now report
     * the real largest shared-index count (prepareChunkRenders local 8) instead of 0.
     * prepareChunkRendersIndirect stays vanilla: the backend capability gate selects the native
     * indirect path where it is supported, while direct terrain remains the fallback path.
     */
    private static void patchLevelRendererBrowserExtractSectionDrawGroups(ClassNode node) {
        String owner = "net/minecraft/client/renderer/LevelRenderer";
        String returnType = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;";
        String extractDescriptor = "(ZLjava/util/List;Ljava/util/Map;)I";
        MethodNode extract = find(node, "extractSectionDrawGroups", extractDescriptor);
        MethodNode prepare = find(node, "prepareChunkRenders", "(Lorg/joml/Matrix4fc;Z)" + returnType);
        find(node, "prepareChunkRendersIndirect", "(Lorg/joml/Matrix4fc;Z)" + returnType);
        if (findNullable(node, "prepareChunkRenders", "(Lorg/joml/Matrix4fc;)" + returnType) != null) {
            throw new IllegalStateException("LevelRenderer has both 26.2 and 26.3 prepare shapes");
        }
        int clockReads = 0;
        for (AbstractInsnNode instruction : extract.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/util/Util")
                    && call.name.equals("getMillis")) {
                clockReads++;
            }
        }
        if (clockReads != 1 || countTypeInsns(prepare, "org/joml/Matrix4f") != 0
                || countTypeInsns(extract, "org/joml/Matrix4f") != 0) {
            throw new IllegalStateException(
                    "LevelRenderer 26.3 prepare no longer hoists its clock and matrix copies");
        }
        redirectChunkSectionLayerValues(extract, 1);
        redirectChunkSectionLayerValues(prepare, 1);
        redirectChunkSectionVertexFormat(extract);
        redirectChunkSectionTranslucent(extract);
        redirectRepeatedSectionDrawFlag(extract);

        MethodInsnNode extractCall = null;
        for (AbstractInsnNode instruction : prepare.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals("extractSectionDrawGroups")
                    && call.desc.equals(extractDescriptor)) {
                if (extractCall != null) {
                    throw new IllegalStateException(
                            "LevelRenderer.prepareChunkRenders extracts sections twice");
                }
                extractCall = call;
            }
        }
        if (extractCall == null
                || !(nextOpcode(extractCall) instanceof VarInsnNode largestStore)
                || largestStore.getOpcode() != Opcodes.ISTORE) {
            throw new IllegalStateException(
                    "LevelRenderer.prepareChunkRenders largest index count store changed");
        }
        int largestIndexLocal = largestStore.var;
        AbstractInsnNode returnNode = null;
        for (AbstractInsnNode instruction : prepare.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                if (returnNode != null) {
                    throw new IllegalStateException(
                            "LevelRenderer.prepareChunkRenders has multiple returns");
                }
                returnNode = instruction;
            } else if (instruction instanceof VarInsnNode store
                    && store != largestStore
                    && store.var == largestIndexLocal
                    && store.getOpcode() >= Opcodes.ISTORE
                    && store.getOpcode() <= Opcodes.ASTORE) {
                throw new IllegalStateException(
                        "LevelRenderer.prepareChunkRenders reuses the largest index count local");
            }
        }
        if (returnNode == null) {
            throw new IllegalStateException(
                    "LevelRenderer.prepareChunkRenders return was not found");
        }
        InsnList emitStats = new InsnList();
        emitStats.add(new VarInsnNode(Opcodes.ALOAD, 0));
        emitStats.add(new FieldInsnNode(Opcodes.GETFIELD, owner,
                "visibleSections", "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"));
        emitStats.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "it/unimi/dsi/fastutil/objects/ObjectArrayList", "size", "()I", false));
        emitStats.add(new VarInsnNode(Opcodes.ILOAD, largestIndexLocal));
        emitStats.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserChunkDrawTelemetry",
                "recordPrepareStats", "(II)V", false));
        prepare.instructions.insertBefore(returnNode, emitStats);
        System.out.println("Patched 26.3 section draw-group extraction and prepare statistics");
    }

    private static int countTypeInsns(MethodNode method, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode typeInsn && typeInsn.desc.equals(type)) {
                count++;
            }
        }
        return count;
    }

    /** Points exactly {@code expected} ChunkSectionLayer.values() clones at the shared array. */
    private static void redirectChunkSectionLayerValues(MethodNode method, int expected) {
        int redirected = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("net/minecraft/client/renderer/chunk/ChunkSectionLayer")
                    && call.name.equals("values")
                    && call.desc.equals(
                            "()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;")) {
                call.owner = "dev/gaius/browser/BrowserChunkSectionLayers";
                redirected++;
            }
        }
        if (redirected != expected) {
            throw new IllegalStateException("LevelRenderer." + method.name
                    + " render-layer reads changed: " + redirected);
        }
    }

    private static void redirectChunkSectionVertexFormat(MethodNode method) {
        int redirected = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode formatCall)
                    || formatCall.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !formatCall.owner.equals("com/mojang/renderpearl/api/pipeline/RenderPipeline")
                    || !formatCall.name.equals("getVertexFormatBinding")
                    || !formatCall.desc.equals("(I)Lcom/mojang/renderpearl/api/vertex/VertexFormat;")) {
                continue;
            }
            AbstractInsnNode zero = previousOpcode(formatCall);
            AbstractInsnNode pipelineCall = previousOpcode(zero);
            AbstractInsnNode zeroBeforePipeline = previousOpcode(pipelineCall);
            AbstractInsnNode layerLoad = previousOpcode(zeroBeforePipeline);
            if (!(zero instanceof InsnNode zeroInsn) || zeroInsn.getOpcode() != Opcodes.ICONST_0
                    || !(pipelineCall instanceof MethodInsnNode pipeline)
                    || pipeline.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !pipeline.owner.equals("net/minecraft/client/renderer/chunk/ChunkSectionLayer")
                    || !pipeline.name.equals("pipeline")
                    || !pipeline.desc.equals("(Z)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;")
                    || !(zeroBeforePipeline instanceof InsnNode pipelineArg)
                    || pipelineArg.getOpcode() != Opcodes.ICONST_0
                    || !(layerLoad instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD
                    || load.var != 19) {
                throw new IllegalStateException(
                        "LevelRenderer.extractSectionDrawGroups vertex format shape changed");
            }
            InsnList replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.ILOAD, 18));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserChunkSectionLayers", "vertexFormat",
                    "(I)Lcom/mojang/renderpearl/api/vertex/VertexFormat;", false));
            method.instructions.insertBefore(layerLoad, replacement);
            method.instructions.remove(layerLoad);
            method.instructions.remove(zeroBeforePipeline);
            method.instructions.remove(pipelineCall);
            method.instructions.remove(zero);
            method.instructions.remove(formatCall);
            redirected++;
        }
        if (redirected != 1) {
            throw new IllegalStateException(
                    "LevelRenderer.extractSectionDrawGroups vertex format call count changed: "
                            + redirected);
        }
    }

    private static void redirectChunkSectionTranslucent(MethodNode method) {
        int redirected = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/client/renderer/chunk/ChunkSectionLayer")
                    || !call.name.equals("translucent")
                    || !call.desc.equals("()Z")) {
                continue;
            }
            AbstractInsnNode layerLoad = previousOpcode(call);
            if (!(layerLoad instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD || load.var != 19) {
                throw new IllegalStateException(
                        "LevelRenderer.extractSectionDrawGroups translucent shape changed");
            }
            method.instructions.set(layerLoad, new VarInsnNode(Opcodes.ILOAD, 18));
            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserChunkSectionLayers", "translucent",
                    "(I)Z", false));
            redirected++;
        }
        if (redirected != 1) {
            throw new IllegalStateException(
                    "LevelRenderer.extractSectionDrawGroups translucent call count changed: "
                            + redirected);
        }
    }

    private static void redirectRepeatedSectionDrawFlag(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/renderer/chunk/SectionMesh$SectionDraw")
                    && call.name.equals("hasCustomIndexBuffer")
                    && call.desc.equals("()Z")) {
                calls.add(call);
            }
        }
        if (calls.size() != 2) {
            throw new IllegalStateException(
                    "LevelRenderer.extractSectionDrawGroups custom-index flag call count changed: "
                            + calls.size());
        }
        MethodInsnNode first = calls.get(0);
        MethodInsnNode second = calls.get(1);
        AbstractInsnNode firstBranch = nextOpcode(first);
        AbstractInsnNode secondLoad = previousOpcode(second);
        if (!(firstBranch instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ
                || !(secondLoad instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD || load.var != 20) {
            throw new IllegalStateException(
                    "LevelRenderer.extractSectionDrawGroups custom-index flag shape changed");
        }
        int flagLocal = method.maxLocals++;
        method.instructions.insertBefore(firstBranch, new VarInsnNode(Opcodes.ISTORE, flagLocal));
        method.instructions.insertBefore(firstBranch, new VarInsnNode(Opcodes.ILOAD, flagLocal));
        method.instructions.set(secondLoad, new VarInsnNode(Opcodes.ILOAD, flagLocal));
        method.instructions.remove(second);
    }

    /**
     * 26.2's render method prepares the chunk draw list before it advances the
     * occlusion graph.  Advance the graph once immediately before that prepare
     * call so a frame can consume a graph state published by an earlier async
     * update; the original update later in the method remains intact for the
     * normal profiler/lifecycle path.
     */
    public static void patchLevelRendererBrowserPrepareAfterOcclusionUpdate(
            ClassNode node, boolean current) {
        if (current) {
            patchCurrentLevelRendererPrepareAfterOcclusionUpdate(node);
            return;
        }
        MethodNode render = node.methods.stream()
                .filter(candidate -> candidate.name.equals("render")
                        && candidate.desc.equals("(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
                                + "Lnet/minecraft/client/DeltaTracker;Z"
                                + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                                + "Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                                + "Lorg/joml/Vector4f;Z)V"))
                .findFirst()
                .orElse(null);
        if (render == null) {
            throw new IllegalStateException("Early occlusion update render shape is absent");
        }
        MethodInsnNode prepare = null;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/renderer/LevelRenderer")
                    && call.name.equals("prepareChunkRenders")
                    && call.desc.equals("(Lorg/joml/Matrix4fc;)"
                            + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;")) {
                if (prepare != null) {
                    throw new IllegalStateException(
                            "LevelRenderer.render has multiple prepareChunkRenders calls");
                }
                prepare = call;
            }
        }
        if (prepare == null) {
            throw new IllegalStateException("Early occlusion update prepareChunkRenders is absent");
        }
        AbstractInsnNode anchor = previousOpcode(previousOpcode(previousOpcode(
                previousOpcode(prepare))));
        if (!(anchor instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 0) {
            throw new IllegalStateException(
                    "LevelRenderer.render prepareChunkRenders receiver shape changed");
        }
        InsnList early = new InsnList();
        early.add(new VarInsnNode(Opcodes.ALOAD, 0));
        early.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/LevelRenderer",
                "sectionOcclusionGraph",
                "Lnet/minecraft/client/renderer/SectionOcclusionGraph;"));
        early.add(new VarInsnNode(Opcodes.ALOAD, 4));
        early.add(new VarInsnNode(Opcodes.ALOAD, 0));
        early.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/LevelRenderer",
                "optionsRenderState",
                "Lnet/minecraft/client/renderer/state/OptionsRenderState;"));
        early.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/state/OptionsRenderState",
                "fov", "I"));
        early.add(new VarInsnNode(Opcodes.ALOAD, 0));
        early.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/LevelRenderer",
                "levelRenderState",
                "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"));
        early.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/state/level/LevelRenderState",
                "chunkLoadingRenderState",
                "Lnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;"));
        early.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/SectionOcclusionGraph",
                "update",
                "(Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                        + "ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V",
                false));
        render.instructions.insertBefore(anchor, early);
        System.out.println("Patched 26.2 early occlusion update before chunk prepare");
    }

    /**
     * 26.3 shape: render prepares through prepareChunkRenders or prepareChunkRendersIndirect.
     * Both branches follow the single per-frame {@code new Matrix4f(viewRotationMatrix)}, which
     * is also the target of the skipped-sky jump, so the early update goes right before that
     * allocation (after its label) and runs on every path. The camera state is local 3.
     */
    private static void patchCurrentLevelRendererPrepareAfterOcclusionUpdate(ClassNode node) {
        String owner = "net/minecraft/client/renderer/LevelRenderer";
        String returnType = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;";
        MethodNode render = find(node, "render", LEVEL_RENDERER_RENDER_263);
        TypeInsnNode terrainMatrix = null;
        int prepares = 0;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW
                    && allocation.desc.equals("org/joml/Matrix4f")) {
                if (terrainMatrix != null) {
                    throw new IllegalStateException(
                            "LevelRenderer.render has multiple terrain matrix copies");
                }
                terrainMatrix = allocation;
            } else if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && (call.name.equals("prepareChunkRenders")
                            || call.name.equals("prepareChunkRendersIndirect"))
                    && call.desc.equals("(Lorg/joml/Matrix4fc;Z)" + returnType)) {
                if (terrainMatrix == null || !precedesInstruction(terrainMatrix, call)) {
                    throw new IllegalStateException(
                            "LevelRenderer.render prepares chunks before its terrain matrix");
                }
                prepares++;
            }
        }
        AbstractInsnNode copyDup = terrainMatrix == null ? null : nextOpcode(terrainMatrix);
        AbstractInsnNode copyThis = copyDup == null ? null : nextOpcode(copyDup);
        AbstractInsnNode copyState = copyThis == null ? null : nextOpcode(copyThis);
        if (prepares != 2
                || copyDup == null || copyDup.getOpcode() != Opcodes.DUP
                || !(copyThis instanceof VarInsnNode thisLoad)
                || thisLoad.getOpcode() != Opcodes.ALOAD || thisLoad.var != 0
                || !(copyState instanceof FieldInsnNode stateField)
                || !stateField.name.equals("levelRenderState")) {
            throw new IllegalStateException(
                    "LevelRenderer.render terrain matrix/prepare anchor changed: prepares="
                            + prepares);
        }
        InsnList early = new InsnList();
        early.add(new VarInsnNode(Opcodes.ALOAD, 0));
        early.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "sectionOcclusionGraph",
                "Lnet/minecraft/client/renderer/SectionOcclusionGraph;"));
        early.add(new VarInsnNode(Opcodes.ALOAD, 3));
        early.add(new VarInsnNode(Opcodes.ALOAD, 0));
        early.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "optionsRenderState",
                "Lnet/minecraft/client/renderer/state/OptionsRenderState;"));
        early.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/state/OptionsRenderState", "fov", "I"));
        early.add(new VarInsnNode(Opcodes.ALOAD, 0));
        early.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "levelRenderState",
                "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"));
        early.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/state/level/LevelRenderState",
                "chunkLoadingRenderState",
                "Lnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;"));
        early.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/SectionOcclusionGraph", "update",
                "(Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                        + "ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V",
                false));
        render.instructions.insertBefore(terrainMatrix, early);
        System.out.println("Patched 26.3 early occlusion update before both chunk prepares");
    }

    private static boolean precedesInstruction(AbstractInsnNode first, AbstractInsnNode second) {
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor == second) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuild the render-visible section lists after the occlusion graph publishes a
     * frustum update.  The 26.2 browser render method updates the graph after preparing
     * chunk draws, but the vanilla list refresh was absent from the transformed method;
     * consequently visibleSections stayed at the initial seven columns while the player
     * moved through newly loaded chunks.
     */
    public static void patchLevelRendererBrowserRefreshVisibleSections(
            ClassNode node, boolean current) {
        MethodNode render = current
                ? find(node, "render", LEVEL_RENDERER_RENDER_263)
                : node.methods.stream()
                .filter(candidate -> candidate.name.equals("render")
                        && candidate.desc.equals("(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
                                + "Lnet/minecraft/client/DeltaTracker;Z"
                                + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                                + "Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                                + "Lorg/joml/Vector4f;Z)V"))
                .findFirst()
                .orElse(null);
        if (render == null) {
            throw new IllegalStateException("Visible section refresh render shape is absent");
        }
        MethodInsnNode update = null;
        int updates = 0;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/renderer/SectionOcclusionGraph")
                    && call.name.equals("update")
                    && call.desc.equals("(Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                            + "ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V")) {
                update = call;
                updates++;
            }
        }
        if (update == null) {
            throw new IllegalStateException(
                    "LevelRenderer.render SectionOcclusionGraph.update call was not found");
        }
        AbstractInsnNode cameraArgument = update;
        for (int step = 0; step < 7 && cameraArgument != null; step++) {
            // update(camera, fov, chunkLoading): camera is 7 instructions before the call.
            cameraArgument = previousOpcode(cameraArgument);
        }
        if (current && (updates != 2
                || !(cameraArgument instanceof VarInsnNode camera)
                || camera.getOpcode() != Opcodes.ALOAD
                || camera.var != 3)) {
            // The early update (patched above) plus vanilla's late one, which reads local 3.
            throw new IllegalStateException(
                    "LevelRenderer.render 26.3 late occlusion update shape changed: " + updates);
        }
        InsnList refresh = browserVisibleSectionRefreshInstructions(current ? 3 : 4);
        render.instructions.insert(update, refresh);
        System.out.println("Patched " + (current ? "renderpearl" : "blaze3d")
                + " visible section refresh after late occlusion update");
    }

    private static InsnList browserVisibleSectionRefreshInstructions(int cameraStateLocal) {
        LabelNode done = new LabelNode();
        InsnList refresh = new InsnList();
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/LevelRenderer",
                "sectionOcclusionGraph",
                "Lnet/minecraft/client/renderer/SectionOcclusionGraph;"));
        refresh.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/SectionOcclusionGraph",
                "consumeFrustumUpdate", "()Z", false));
        refresh.add(new JumpInsnNode(Opcodes.IFEQ, done));
        refresh.add(new VarInsnNode(Opcodes.ALOAD, cameraStateLocal));
        refresh.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/state/level/CameraRenderState",
                "cullFrustum",
                "Lnet/minecraft/client/renderer/culling/Frustum;"));
        refresh.add(new JumpInsnNode(Opcodes.IFNULL, done));
        // Replace the lists like LevelExtractor.applyFrustum does. This refresh consumes the
        // graph's frustum-update flag, so applyFrustum then only runs when the camera turns:
        // appending without clearing re-added every visible section on each graph update while
        // the camera stood still, growing the draw list to tens of thousands of duplicates.
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/LevelRenderer",
                "clearVisibleSections", "()V", false));
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/LevelRenderer",
                "sectionOcclusionGraph",
                "Lnet/minecraft/client/renderer/SectionOcclusionGraph;"));
        refresh.add(new VarInsnNode(Opcodes.ALOAD, cameraStateLocal));
        refresh.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/state/level/CameraRenderState",
                "cullFrustum",
                "Lnet/minecraft/client/renderer/culling/Frustum;"));
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/LevelRenderer",
                "visibleSections",
                "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"));
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/LevelRenderer",
                "nearbyVisibleSections",
                "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"));
        refresh.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/SectionOcclusionGraph",
                "addSectionsInFrustum",
                "(Lnet/minecraft/client/renderer/culling/Frustum;Ljava/util/List;Ljava/util/List;)V",
                false));
        refresh.add(done);
        return refresh;
    }

    private static void patchLevelRendererBrowserBlockBreakingTelemetry(
            ClassNode node, boolean current) {
        // 26.3 added a boolean to SubmitNodeCollector.submitBreakingBlockModel.
        String submitDescriptor = "(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;"
                + (current ? "IZ)V" : "I)V");
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("submitBlockDestroyAnimation")
                        && candidate.desc.equals("(Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"))
                .findFirst()
                .orElseGet(() -> node.methods.stream()
                        .filter(candidate -> candidate.name.equals("destroyBlockProgress")
                                && candidate.desc.equals("(ILnet/minecraft/core/BlockPos;I)V"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "LevelRenderer block-breaking render method was not found")));
        InsnList call = new InsnList();
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBlockBreakingTelemetry",
                "recordSubmitPass", "()V", false));
        method.instructions.insert(call);
        int actualSubmits = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode submit
                    && submit.getOpcode() == Opcodes.INVOKEINTERFACE
                    && submit.owner.equals("net/minecraft/client/renderer/SubmitNodeCollector")
                    && submit.name.equals("submitBreakingBlockModel")
                    && submit.desc.equals(submitDescriptor)) {
                InsnList actual = new InsnList();
                actual.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserBlockBreakingTelemetry",
                        "recordActualSubmit", "()V", false));
                method.instructions.insertBefore(submit, actual);
                actualSubmits++;
            }
        }
        if ((current || method.name.equals("submitBlockDestroyAnimation")) && actualSubmits != 1) {
            throw new IllegalStateException(
                    "LevelRenderer breaking-model submit shape changed: " + actualSubmits);
        }
    }

    /** Keep the occlusion graph advancing while browser camera/frustum state is captured. */
    public static void patchSectionOcclusionGraphBrowserMovementRefresh(
            String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/renderer/SectionOcclusionGraph";
        ClassNode node = read(jar, owner + ".class");
        MethodNode update = find(node, "update",
                "(Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                        + "ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V");
        int patched = 0;
        for (AbstractInsnNode instruction : update.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(
                            "net/minecraft/client/renderer/state/level/CameraRenderState")
                    || !field.name.equals("isFrustumCaptured")
                    || !field.desc.equals("Z")) {
                continue;
            }
            AbstractInsnNode receiver = previousOpcode(instruction);
            if (!(receiver instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD
                    || load.var != 1) {
                throw new IOException(
                        "SectionOcclusionGraph.update frustum receiver shape changed");
            }
            update.instructions.remove(receiver);
            update.instructions.set(instruction, new InsnNode(Opcodes.ICONST_0));
            patched++;
        }
        if (patched != 1) {
            throw new IOException(
                    "SectionOcclusionGraph.update frustum refresh patch point changed: "
                            + patched);
        }
        // The frame's loaded-chunk and empty-section changes reach the graph only here; the
        // section audit carries them over frames whose world render was skipped (inventory
        // throttle) and re-adds expected chunks the client already holds.
        InsnList consumed = new InsnList();
        consumed.add(new VarInsnNode(Opcodes.ALOAD, 0));
        consumed.add(new VarInsnNode(Opcodes.ALOAD, 3));
        consumed.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserSectionAudit", "beforeOcclusionUpdate",
                "(L" + owner + ";"
                        + "Lnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V",
                false));
        update.instructions.insert(consumed);
        update.maxStack = Math.max(update.maxStack, 2);
        // LevelExtractor only rebuilds LevelRenderer.visibleSections when it consumes
        // this flag.  In the browser client the camera usually translates while its
        // rotation remains unchanged, so the old captured-frustum path skips
        // applyFrustum and leaves the initial seven chunk columns in the draw list.
        patchSectionOcclusionGraphBrowserLoadedChunkRebuild(node);
        writeComputeFrames(node, output);
        System.out.println("Patched 26.2 movement occlusion refresh + frustum notify");
    }

    /** Rebuild the graph when either loaded-column mutation changes the set. */
    private static void patchSectionOcclusionGraphBrowserLoadedChunkRebuild(ClassNode node) {
        MethodNode method = find(node, "updateLoadedChunks",
                "(Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;"
                        + "Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;)V");
        MethodInsnNode addAll = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("it/unimi/dsi/fastutil/longs/LongOpenHashSet")
                    && call.name.equals("addAll")
                    && call.desc.equals("(Lit/unimi/dsi/fastutil/longs/LongCollection;)Z")) {
                if (addAll != null) {
                    throw new IllegalStateException("SectionOcclusionGraph has multiple loaded-chunk additions");
                }
                addAll = call;
            }
        }
        if (addAll == null || addAll.getNext() == null || addAll.getNext().getOpcode() != Opcodes.POP) {
            throw new IllegalStateException("SectionOcclusionGraph loaded-chunk addition shape changed");
        }
        // Keep the vanilla add-then-remove order, but retain the result of both
        // mutations.  A replacement can report add=false and remove=true when
        // the position was already loaded; checking addAll alone misses that
        // rebuild.  A single post-update invalidate matches the vanilla
        // one-rebuild behavior for any changed update, including mixed batches.
        int changedLocal = method.maxLocals;
        method.maxLocals = changedLocal + 1;
        AbstractInsnNode addDiscard = addAll.getNext();
        method.instructions.set(addDiscard, new VarInsnNode(Opcodes.ISTORE, changedLocal));

        MethodInsnNode removeAll = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("it/unimi/dsi/fastutil/longs/LongOpenHashSet")
                    && call.name.equals("removeAll")
                    && call.desc.equals("(Lit/unimi/dsi/fastutil/longs/LongCollection;)Z")) {
                if (removeAll != null) {
                    throw new IllegalStateException("SectionOcclusionGraph has multiple loaded-chunk removals");
                }
                removeAll = call;
            }
        }
        if (removeAll == null || removeAll.getNext() == null || removeAll.getNext().getOpcode() != Opcodes.POP) {
            throw new IllegalStateException("SectionOcclusionGraph loaded-chunk removal shape changed");
        }
        AbstractInsnNode removeDiscard = removeAll.getNext();
        InsnList merge = new InsnList();
        LabelNode noRemoval = new LabelNode();
        LabelNode unchanged = new LabelNode();
        merge.add(new JumpInsnNode(Opcodes.IFEQ, noRemoval));
        merge.add(new InsnNode(Opcodes.ICONST_1));
        merge.add(new VarInsnNode(Opcodes.ISTORE, changedLocal));
        merge.add(noRemoval);
        merge.add(new VarInsnNode(Opcodes.ILOAD, changedLocal));
        merge.add(new JumpInsnNode(Opcodes.IFEQ, unchanged));
        merge.add(new VarInsnNode(Opcodes.ALOAD, 0));
        merge.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                node.name, "invalidate", "()V", false));
        merge.add(unchanged);
        method.instructions.insert(removeDiscard, merge);
        method.instructions.remove(removeDiscard);
        System.out.println("Patched 26.2 loaded-chunk graph rebuild");
    }

    public static void patchEntityRenderDispatcherBrowserNullEntityGuard(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/renderer/entity/EntityRenderDispatcher.class");
        // 26.3 appends the partial tick: shouldRender(Entity, Frustum, double x3, float).
        MethodNode method = find(node, "shouldRender",
                "(Lnet/minecraft/world/entity/Entity;"
                        + "Lnet/minecraft/client/renderer/culling/Frustum;"
                        + (ModernSymbols.cached(jar).renderpearl() ? "DDDF)Z" : "DDD)Z"));
        LabelNode render = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new JumpInsnNode(Opcodes.IFNONNULL, render));
        guard.add(new InsnNode(Opcodes.ICONST_0));
        guard.add(new InsnNode(Opcodes.IRETURN));
        guard.add(render);
        method.instructions.insert(guard);
        writeComputeFrames(node, output);
    }

    /** Replaces PreparedFrame's predicate allocation with the same iterator semantics. */
    public static void patchFeatureRenderDispatcherPreparedFrameRemoveIf(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "clearGroups", "(Ljava/util/Collection;)V");
        replaceCollectionRemoveIf(method, "clearGroups", "(Ljava/util/Collection;)V");
        writeComputeFrames(node, output);
    }

    /** Replaces FeatureSubmits' predicate allocation with the same iterator semantics. */
    public static void patchFeatureSubmitsRemoveIf(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase$FeatureSubmits";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "clear", "()V");
        replaceCollectionRemoveIf(method, "clear", "(Ljava/util/Collection;)V");
        writeComputeFrames(node, output);
    }

    /** Replaces SubmitNodeStorage.drainPhases' predicate allocation with a direct iterator. */
    public static void patchSubmitNodeStorageDrainRemoveIf(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/renderer/SubmitNodeStorage";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "drainPhases",
                "(Ljava/util/function/Consumer;)V");
        int replacements = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !call.owner.equals("it/unimi/dsi/fastutil/objects/ObjectCollection")
                    || !call.name.equals("removeIf")
                    || !call.desc.equals("(Ljava/util/function/Predicate;)Z")) {
                continue;
            }
            AbstractInsnNode predicate = previousOpcode(call);
            AbstractInsnNode consumer = previousOpcode(predicate);
            if (!(predicate instanceof InvokeDynamicInsnNode)
                    || !(consumer instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD
                    || load.var != 1) {
                throw new IllegalStateException(owner + ".drainPhases removeIf shape changed");
            }
            AbstractInsnNode pop = nextOpcode(call);
            if (pop == null || pop.getOpcode() != Opcodes.POP) {
                throw new IllegalStateException(owner + ".drainPhases removeIf result shape changed");
            }
            method.instructions.remove(predicate);
            method.instructions.set(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderCollections",
                    "drainSubmitNodeCollections",
                    "(Lit/unimi/dsi/fastutil/objects/ObjectCollection;Ljava/util/function/Consumer;)V",
                    false));
            method.instructions.remove(pop);
            replacements++;
        }
        if (replacements != 1) {
            throw new IllegalStateException(owner + ".drainPhases removeIf count changed: " + replacements);
        }
        writeComputeFrames(node, output);
    }

    private static void replaceCollectionRemoveIf(MethodNode method, String methodName, String helperDescriptor) {
        int replacements = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !call.owner.equals("java/util/Collection")
                    || !call.name.equals("removeIf")
                    || !call.desc.equals("(Ljava/util/function/Predicate;)Z")) {
                continue;
            }
            AbstractInsnNode predicate = previousOpcode(call);
            if (!(predicate instanceof InvokeDynamicInsnNode)) {
                throw new IllegalStateException(methodName + " removeIf predicate shape changed");
            }
            AbstractInsnNode pop = nextOpcode(call);
            if (pop == null || pop.getOpcode() != Opcodes.POP) {
                throw new IllegalStateException(methodName + " removeIf result shape changed");
            }
            method.instructions.remove(predicate);
            method.instructions.set(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderCollections",
                    "clearGroups",
                    helperDescriptor,
                    false));
            method.instructions.remove(pop);
            replacements++;
        }
        if (replacements != 1) {
            throw new IllegalStateException(methodName + " removeIf count changed: " + replacements);
        }
    }

    private static void patchLevelRendererBrowserPrepareChunkRenders(ClassNode node) {
        String returnType = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;";
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("prepareChunkRenders")
                        && (candidate.desc.equals("(Lorg/joml/Matrix4fc;DDD)" + returnType)
                                || candidate.desc.equals("(Lorg/joml/Matrix4fc;)" + returnType)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "LevelRenderer.prepareChunkRenders browser target was not found"));
        MethodInsnNode millisCall = null;
        MethodInsnNode[] layerValuesCalls = new MethodInsnNode[2];
        int layerValuesCallCount = 0;
        TypeInsnNode matrixCopy = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW
                    && allocation.desc.equals("org/joml/Matrix4f")) {
                if (matrixCopy != null) {
                    throw new IllegalStateException(
                            "LevelRenderer.prepareChunkRenders has multiple matrix copies");
                }
                matrixCopy = allocation;
                continue;
            }
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC) {
                continue;
            }
            if (call.owner.equals("net/minecraft/util/Util")
                    && call.name.equals("getMillis")
                    && call.desc.equals("()J")) {
                if (millisCall != null) {
                    throw new IllegalStateException(
                            "LevelRenderer.prepareChunkRenders has multiple clock reads");
                }
                millisCall = call;
                continue;
            }
            if (call.owner.equals("net/minecraft/client/renderer/chunk/ChunkSectionLayer")
                    && call.name.equals("values")
                    && call.desc.equals("()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;")) {
                if (layerValuesCallCount >= layerValuesCalls.length) {
                    throw new IllegalStateException(
                            "LevelRenderer.prepareChunkRenders has extra render-layer reads");
                }
                layerValuesCalls[layerValuesCallCount++] = call;
            }
        }
        if (millisCall == null
                || layerValuesCallCount != layerValuesCalls.length
                || matrixCopy == null) {
            throw new IllegalStateException(
                    "LevelRenderer.prepareChunkRenders browser hot-path patch points were not found");
        }

        AbstractInsnNode matrixDup = nextOpcode(matrixCopy);
        AbstractInsnNode matrixLoad = nextOpcode(matrixDup);
        AbstractInsnNode matrixConstructor = nextOpcode(matrixLoad);
        if (!(matrixDup instanceof InsnNode)
                || matrixDup.getOpcode() != Opcodes.DUP
                || !(matrixLoad instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 1
                || !(matrixConstructor instanceof MethodInsnNode constructor)
                || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                || !constructor.owner.equals("org/joml/Matrix4f")
                || !constructor.name.equals("<init>")
                || !constructor.desc.equals("(Lorg/joml/Matrix4fc;)V")) {
            throw new IllegalStateException(
                    "LevelRenderer.prepareChunkRenders matrix copy shape changed");
        }
        method.instructions.set(matrixCopy, new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.remove(matrixDup);
        method.instructions.remove(matrixLoad);
        method.instructions.remove(matrixConstructor);

        int frameLayersLocal = method.maxLocals;
        int frameMillisLocal = frameLayersLocal + 1;
        method.maxLocals += 3;
        InsnList frameState = new InsnList();
        frameState.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserChunkSectionLayers",
                "values",
                "()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;",
                false));
        frameState.add(new VarInsnNode(Opcodes.ASTORE, frameLayersLocal));
        frameState.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/util/Util",
                "getMillis",
                "()J",
                false));
        frameState.add(new VarInsnNode(Opcodes.LSTORE, frameMillisLocal));
        method.instructions.insert(frameState);
        method.instructions.set(millisCall, new VarInsnNode(Opcodes.LLOAD, frameMillisLocal));

        // Capture the renderer-side inputs that determine whether the snapshot
        // contains any terrain draws. This is diagnostic only and does not alter
        // render distance, texture quality, or the section selection algorithm.
        int prepareVisibleLocal = method.maxLocals;
        int prepareMaxIndexLocal = prepareVisibleLocal + 1;
        method.maxLocals += 2;
        InsnList prepareStats = new InsnList();
        prepareStats.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepareStats.add(new FieldInsnNode(Opcodes.GETFIELD,
                "net/minecraft/client/renderer/LevelRenderer",
                "visibleSections",
                "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"));
        prepareStats.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "it/unimi/dsi/fastutil/objects/ObjectArrayList",
                "size", "()I", false));
        prepareStats.add(new VarInsnNode(Opcodes.ISTORE, prepareVisibleLocal));
        prepareStats.add(new InsnNode(Opcodes.ICONST_0));
        prepareStats.add(new VarInsnNode(Opcodes.ISTORE, prepareMaxIndexLocal));
        method.instructions.insert(prepareStats);
        AbstractInsnNode returnNode = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                if (returnNode != null) {
                    throw new IllegalStateException(
                            "LevelRenderer.prepareChunkRenders has multiple returns");
                }
                returnNode = instruction;
            }
        }
        if (returnNode == null) {
            throw new IllegalStateException(
                    "LevelRenderer.prepareChunkRenders return was not found");
        }
        InsnList emitStats = new InsnList();
        emitStats.add(new VarInsnNode(Opcodes.ILOAD, prepareVisibleLocal));
        emitStats.add(new VarInsnNode(Opcodes.ILOAD, prepareMaxIndexLocal));
        emitStats.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserChunkDrawTelemetry",
                "recordPrepareStats", "(II)V", false));
        method.instructions.insertBefore(returnNode, emitStats);
        for (MethodInsnNode layerValuesCall : layerValuesCalls) {
            method.instructions.set(
                    layerValuesCall, new VarInsnNode(Opcodes.ALOAD, frameLayersLocal));
        }
    }

    private static void patchLevelRendererBrowserBlockOutlineOpacity(ClassNode node) {
        MethodNode method = node.methods.stream()
                .filter(candidate -> (candidate.name.equals("renderBlockOutline")
                                && candidate.desc.equals(
                                        "(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
                                                + "Lcom/mojang/blaze3d/vertex/PoseStack;Z"
                                                + "Lnet/minecraft/client/renderer/state/LevelRenderState;)V"))
                        || (candidate.name.equals("submitBlockOutline")
                                && candidate.desc.equals(
                                        "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                                                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                                                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "LevelRenderer block outline opacity target was not found"));
        boolean patchedOpacity = false;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof IntInsnNode alpha
                    && alpha.getOpcode() == Opcodes.BIPUSH
                    && alpha.operand == 102
                    && nextOpcode(instruction) instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/util/ARGB")
                    && call.name.equals("black")
                    && call.desc.equals("(I)I")) {
                method.instructions.set(alpha, new IntInsnNode(Opcodes.SIPUSH, 180));
                patchedOpacity = true;
                break;
            }
        }
        if (!patchedOpacity) {
            throw new IllegalStateException(
                    "LevelRenderer browser block outline opacity patch point was not found");
        }
    }

    private static void patchLevelRendererBrowserSectionCompileThrottle(ClassNode node) {
        MethodNode currentMethod = node.methods.stream()
                .filter(candidate -> candidate.name.equals("compileSections")
                        && candidate.desc.equals(
                                "(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
                .findFirst()
                .orElse(null);
        if (currentMethod != null) {
            patchCurrentLevelRendererSectionCompileThrottle(currentMethod);
            return;
        }

        MethodNode method = find(node, "compileSections", "(Lnet/minecraft/client/Camera;)V");
        boolean disabledSyncCompile = false;
        boolean throttledAsyncSchedule = false;

        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode syncCall
                    && syncCall.owner.equals("net/minecraft/client/renderer/chunk/SectionRenderDispatcher")
                    && syncCall.name.equals("rebuildSectionSync")
                    && syncCall.desc.equals("(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;"
                            + "Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V")) {
                AbstractInsnNode cursor = syncCall;
                while ((cursor = previousOpcode(cursor)) != null) {
                    if (cursor instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
                        AbstractInsnNode condition = previousOpcode(jump);
                        if (condition instanceof VarInsnNode load
                                && load.getOpcode() == Opcodes.ILOAD) {
                            method.instructions.set(load, new InsnNode(Opcodes.ICONST_0));
                            disabledSyncCompile = true;
                            break;
                        }
                    }
                }
                if (!disabledSyncCompile) {
                    throw new IllegalStateException(
                            "LevelRenderer.compileSections sync rebuild branch patch point was not found");
                }
                continue;
            }

            if (instruction instanceof MethodInsnNode addCall
                    && addCall.getOpcode() == Opcodes.INVOKEINTERFACE
                    && addCall.owner.equals("java/util/List")
                    && addCall.name.equals("add")
                    && addCall.desc.equals("(Ljava/lang/Object;)Z")) {
                AbstractInsnNode loadSection = previousOpcode(addCall);
                AbstractInsnNode loadList = previousOpcode(loadSection);
                if (!(loadSection instanceof VarInsnNode sectionLoad)
                        || sectionLoad.getOpcode() != Opcodes.ALOAD
                        || !(loadList instanceof VarInsnNode listLoad)
                        || listLoad.getOpcode() != Opcodes.ALOAD) {
                    continue;
                }
                AbstractInsnNode pop = nextOpcode(addCall);
                if (!(pop instanceof InsnNode) || pop.getOpcode() != Opcodes.POP) {
                    throw new IllegalStateException(
                            "LevelRenderer.compileSections List.add POP patch point was not found");
                }
                LabelNode allowAdd = new LabelNode();
                LabelNode afterAdd = new LabelNode();
                InsnList guard = new InsnList();
                guard.add(new VarInsnNode(Opcodes.ALOAD, listLoad.var));
                guard.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/List",
                        "size",
                        "()I",
                        true));
                guard.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserRenderScheduler",
                        "canScheduleSection",
                        "(I)Z",
                        false));
                guard.add(new JumpInsnNode(Opcodes.IFEQ, afterAdd));
                guard.add(allowAdd);
                method.instructions.insertBefore(loadList, guard);
                method.instructions.insert(pop, afterAdd);
                throttledAsyncSchedule = true;
                break;
            }

        }

        if (!disabledSyncCompile || !throttledAsyncSchedule) {
            throw new IllegalStateException(
                    "LevelRenderer browser section compile throttle patch points were not found");
        }
        method.maxStack = Math.max(method.maxStack, 3);
    }

    private static void patchCurrentLevelRendererSectionCompileThrottle(MethodNode method) {
        MethodInsnNode syncCall = null;
        MethodInsnNode asyncCall = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals(
                            "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection")) {
                continue;
            }
            if (call.name.equals("compileSync")
                    && call.desc.equals(
                            "(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V")) {
                syncCall = call;
            } else if (call.name.equals("compileAsync")
                    && call.desc.equals(
                            "(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V")) {
                asyncCall = call;
            }
        }
        if (syncCall == null || asyncCall == null) {
            throw new IllegalStateException(
                    "Current LevelRenderer section compile calls were not found");
        }

        boolean disabledSyncCompile = false;
        for (AbstractInsnNode cursor = syncCall;
                (cursor = previousOpcode(cursor)) != null;) {
            if (!(cursor instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) {
                continue;
            }
            AbstractInsnNode condition = previousOpcode(jump);
            if (condition instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD) {
                method.instructions.set(load, new InsnNode(Opcodes.ICONST_0));
                disabledSyncCompile = true;
                break;
            }
        }
        if (!disabledSyncCompile) {
            throw new IllegalStateException(
                    "Current LevelRenderer synchronous compile branch was not found");
        }

        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("dev/gaius/browser/BrowserRenderScheduler")
                    && call.name.equals("canScheduleSection")) {
                throw new IllegalStateException(
                        "Current compileSections must not drop an extracted dirty section");
            }
        }

        // Mark the extracted updates consumed once every one has become a compile task.
        // LevelExtractor re-dirties whatever is still listed at the next extraction (a frame
        // whose world render was skipped or threw before reaching this loop).
        int consumed = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof LdcInsnNode constant)
                    || !"scheduleTranslucentResort".equals(constant.cst)) {
                continue;
            }
            AbstractInsnNode profilerLoad = previousOpcode(constant);
            if (!(profilerLoad instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) {
                throw new IllegalStateException(
                        "Current compileSections resort profiler load shape changed");
            }
            InsnList clear = new InsnList();
            clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clear.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/renderer/LevelRenderer",
                    "levelRenderState",
                    "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"));
            clear.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/renderer/state/level/LevelRenderState",
                    "sectionUpdateRenderStates",
                    "Ljava/util/List;"));
            clear.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/List",
                    "clear",
                    "()V",
                    true));
            method.instructions.insertBefore(load, clear);
            consumed++;
        }
        if (consumed != 1) {
            throw new IllegalStateException(
                    "Current compileSections update consumption point changed: " + consumed);
        }
        method.maxStack = Math.max(method.maxStack, 2);
        System.out.println("Patched current section compiles to remain asynchronous");
    }

    public static void patchUberGpuBufferBrowserTelemetry(String jar, Path output)
            throws IOException {
        String entry = "com/mojang/blaze3d/vertex/UberGpuBuffer.class";
        boolean present;
        try (ZipFile input = new ZipFile(jar)) {
            present = input.getEntry(entry) != null;
        }
        if (!present) {
            if (ModernSymbols.cached(jar).renderpearl()) {
                throw new IllegalStateException("26.3 client has no " + entry);
            }
            return;
        }
        ClassNode node = read(jar, entry);
        String uploadDescriptor = uberGpuBufferUploadDescriptor(node);
        if (!uploadDescriptor.equals("(L"
                + ModernSymbols.cached(jar).renderType("com/mojang/blaze3d/systems/GpuDevice")
                + ";Lcom/mojang/blaze3d/vertex/StagingBuffer$Uploader;)Z")) {
            throw new IllegalStateException(
                    "UberGpuBuffer upload device type disagrees with the probed render API");
        }
        patchCurrentUberGpuBufferUploadBudget(node);
        patchUberGpuBufferBrowserLifecycle(node);
        MethodNode addAllocation = find(
                node,
                "addAllocation",
                "(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/"
                        + "UberGpuBuffer$UploadCallback;Ljava/nio/ByteBuffer;)Z");
        MethodNode uploadAllocations = find(node, "uploadStagedAllocations", uploadDescriptor);
        int addReturns = instrumentUberGpuBufferBacklogReturns(node, addAllocation);
        int uploadReturns = instrumentUberGpuBufferBacklogReturns(node, uploadAllocations);
        if (addReturns != 2 || uploadReturns != 1) {
            throw new IllegalStateException(
                    "UberGpuBuffer telemetry return shape changed: add="
                            + addReturns + ", upload=" + uploadReturns);
        }
        writeComputeFrames(node, output);
        System.out.println("Instrumented staged terrain upload backlog telemetry");
    }

    /**
     * uploadStagedAllocations takes the blaze3d GpuDevice class on 26.2 and the renderpearl
     * GpuDevice interface on 26.3; exactly one of the two must exist.
     */
    private static String uberGpuBufferUploadDescriptor(ClassNode node) {
        String legacy = "(Lcom/mojang/blaze3d/systems/GpuDevice;"
                + "Lcom/mojang/blaze3d/vertex/StagingBuffer$Uploader;)Z";
        String current = "(Lcom/mojang/renderpearl/api/device/GpuDevice;"
                + "Lcom/mojang/blaze3d/vertex/StagingBuffer$Uploader;)Z";
        boolean hasLegacy = findNullable(node, "uploadStagedAllocations", legacy) != null;
        boolean hasCurrent = findNullable(node, "uploadStagedAllocations", current) != null;
        if (hasLegacy == hasCurrent) {
            throw new IllegalStateException("UberGpuBuffer.uploadStagedAllocations shape changed");
        }
        return hasCurrent ? current : legacy;
    }

    private static void patchCurrentUberGpuBufferUploadBudget(ClassNode node) {
        MethodNode upload = find(node, "uploadStagedAllocations", uberGpuBufferUploadDescriptor(node));
        MethodInsnNode initialFree = null;
        MethodInsnNode keySet = null;
        MethodInsnNode entrySet = null;
        MethodInsnNode stagedClear = null;
        MethodInsnNode skippedClear = null;
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.owner.equals(node.name)
                    && call.name.equals("freeAllocation")
                    && call.desc.equals("(Ljava/lang/Object;)V")) {
                if (initialFree == null) {
                    initialFree = call;
                }
            } else if (call.owner.equals(
                            "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap")
                    && call.name.equals("keySet")) {
                keySet = call;
            } else if (call.owner.equals(
                            "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap")
                    && call.name.equals("entrySet")) {
                entrySet = call;
            } else if (call.owner.equals(
                            "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap")
                    && call.name.equals("clear")) {
                stagedClear = call;
            } else if (call.owner.equals(
                            "it/unimi/dsi/fastutil/objects/ObjectOpenHashSet")
                    && call.name.equals("clear")) {
                skippedClear = call;
            }
        }
        if (initialFree == null || keySet == null || entrySet == null
                || stagedClear == null || skippedClear == null) {
            throw new IllegalStateException("Current UberGpuBuffer upload structure changed");
        }

        JumpInsnNode initialLoopExit = null;
        for (AbstractInsnNode instruction = keySet;
                instruction != null && instruction != initialFree;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("hasNext")
                    && call.desc.equals("()Z")
                    && nextOpcode(call) instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFEQ) {
                initialLoopExit = jump;
                break;
            }
        }
        AbstractInsnNode initialMapLoad = previousOpcode(previousOpcode(keySet));
        if (initialLoopExit == null
                || !(initialMapLoad instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 0) {
            throw new IllegalStateException("Current UberGpuBuffer initial free loop changed");
        }
        upload.instructions.insertBefore(
                initialMapLoad,
                new JumpInsnNode(Opcodes.GOTO, initialLoopExit.label));

        VarInsnNode entryIteratorStore = null;
        MethodInsnNode entryHasNext = null;
        for (AbstractInsnNode instruction = entrySet.getNext();
                instruction != null;
                instruction = instruction.getNext()) {
            if (entryIteratorStore == null
                    && instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE) {
                entryIteratorStore = store;
                continue;
            }
            if (entryIteratorStore != null
                    && instruction instanceof MethodInsnNode call
                    && call.name.equals("hasNext")
                    && call.desc.equals("()Z")) {
                entryHasNext = call;
                break;
            }
        }
        if (entryIteratorStore == null
                || entryHasNext == null
                || !(nextOpcode(entryHasNext) instanceof JumpInsnNode entryLoopExit)
                || entryLoopExit.getOpcode() != Opcodes.IFEQ) {
            throw new IllegalStateException("Current UberGpuBuffer staged entry loop changed");
        }

        LabelNode entryLoop = null;
        AbstractInsnNode iteratorLoad = previousOpcode(entryHasNext);
        for (AbstractInsnNode cursor = iteratorLoad.getPrevious(); cursor != null;
                cursor = cursor.getPrevious()) {
            if (cursor instanceof LabelNode label) {
                entryLoop = label;
                break;
            }
            if (cursor.getOpcode() >= 0) {
                break;
            }
        }
        if (entryLoop == null) {
            throw new IllegalStateException("Current UberGpuBuffer staged loop label changed");
        }

        InsnList budgetGuard = new InsnList();
        budgetGuard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        budgetGuard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "shouldUploadNext",
                "(Ljava/lang/Object;)Z",
                false));
        budgetGuard.add(new JumpInsnNode(Opcodes.IFEQ, entryLoopExit.label));
        upload.instructions.insert(entryLoopExit, budgetGuard);

        VarInsnNode entryStore = null;
        for (AbstractInsnNode instruction = entryHasNext.getNext();
                instruction != null && instruction != entryLoopExit.label;
                instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE) {
                entryStore = store;
                break;
            }
        }
        if (entryStore == null) {
            throw new IllegalStateException("Current UberGpuBuffer staged entry local changed");
        }
        InsnList freeSelected = new InsnList();
        freeSelected.add(new VarInsnNode(Opcodes.ALOAD, 0));
        freeSelected.add(new VarInsnNode(Opcodes.ALOAD, entryStore.var));
        freeSelected.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map$Entry",
                "getKey",
                "()Ljava/lang/Object;",
                true));
        freeSelected.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                node.name,
                "freeAllocation",
                "(Ljava/lang/Object;)V",
                false));
        upload.instructions.insert(entryStore, freeSelected);

        VarInsnNode sizeStore = null;
        for (AbstractInsnNode instruction = entryStore.getNext();
                instruction != null && instruction != entryLoopExit.label;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/blaze3d/vertex/StagingBuffer$BufferHandle")
                    && call.name.equals("size")
                    && call.desc.equals("()J")
                    && nextOpcode(call) instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.LSTORE) {
                if (sizeStore != null) {
                    throw new IllegalStateException(
                            "Current UberGpuBuffer staged entry size is read twice");
                }
                sizeStore = store;
            }
        }
        if (sizeStore == null) {
            throw new IllegalStateException("Current UberGpuBuffer staged entry size changed");
        }
        InsnList countBytes = new InsnList();
        countBytes.add(new VarInsnNode(Opcodes.ALOAD, 0));
        countBytes.add(new VarInsnNode(Opcodes.LLOAD, sizeStore.var));
        countBytes.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "noteUploadBytes",
                "(Ljava/lang/Object;J)V",
                false));
        JumpInsnNode skippedEntryCheck = null;
        for (AbstractInsnNode instruction = sizeStore.getNext();
                instruction != null && instruction != entryLoopExit.label;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/blaze3d/vertex/TlsfAllocator")
                    && call.name.equals("allocate")) {
                break;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("it/unimi/dsi/fastutil/objects/ObjectOpenHashSet")
                    && call.name.equals("contains")
                    && nextOpcode(call) instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFEQ) {
                if (skippedEntryCheck != null) {
                    throw new IllegalStateException(
                            "Current UberGpuBuffer skipped entry check is repeated");
                }
                skippedEntryCheck = jump;
            }
        }
        if (skippedEntryCheck == null) {
            throw new IllegalStateException("Current UberGpuBuffer skipped entry check changed");
        }
        // Count only entries that are really uploaded; skipped (removed) entries close
        // without an upload and must not use up the frame's upload byte budget.
        upload.instructions.insertBefore(nextOpcode(skippedEntryCheck.label), countBytes);

        LabelNode processedEntry = new LabelNode();
        InsnList removeProcessed = new InsnList();
        removeProcessed.add(processedEntry);
        removeProcessed.add(new VarInsnNode(Opcodes.ALOAD, entryIteratorStore.var));
        removeProcessed.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "remove",
                "()V",
                true));
        removeProcessed.add(new JumpInsnNode(Opcodes.GOTO, entryLoop));
        upload.instructions.insertBefore(entryLoopExit.label, removeProcessed);

        int redirectedCompletions = 0;
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.label == entryLoop
                    && upload.instructions.indexOf(jump) > upload.instructions.indexOf(entryStore)
                    && upload.instructions.indexOf(jump) < upload.instructions.indexOf(processedEntry)) {
                jump.label = processedEntry;
                redirectedCompletions++;
            }
        }
        if (redirectedCompletions != 5) {
            throw new IllegalStateException(
                    "Current UberGpuBuffer staged completion shape changed: "
                            + redirectedCompletions);
        }

        AbstractInsnNode stagedClearReceiver = previousOpcode(previousOpcode(stagedClear));
        if (!(stagedClearReceiver instanceof VarInsnNode clearReceiverLoad)
                || clearReceiverLoad.getOpcode() != Opcodes.ALOAD
                || clearReceiverLoad.var != 0) {
            throw new IllegalStateException("Current UberGpuBuffer staged clear receiver changed");
        }
        InsnList preservePending = new InsnList();
        preservePending.add(new VarInsnNode(Opcodes.ALOAD, 0));
        preservePending.add(new VarInsnNode(Opcodes.ALOAD, 0));
        preservePending.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                node.name,
                "stagedAllocations",
                "Lit/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap;"));
        preservePending.add(new VarInsnNode(Opcodes.ALOAD, 0));
        preservePending.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                node.name,
                "skippedStagedAllocations",
                "Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;"));
        preservePending.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "finishUploadBuffer",
                "(Ljava/lang/Object;Ljava/util/Map;Ljava/util/Set;)V",
                false));
        upload.instructions.insertBefore(stagedClearReceiver, preservePending);
        upload.instructions.set(stagedClear, new InsnNode(Opcodes.POP));
        upload.instructions.set(skippedClear, new InsnNode(Opcodes.POP));
        upload.maxStack = Math.max(upload.maxStack, 4);
        System.out.println("Patched current staged terrain uploads with a resumable frame budget");
    }

    private static void patchUberGpuBufferBrowserLifecycle(ClassNode node) {
        MethodNode close = find(node, "close", "()V");
        InsnList release = new InsnList();
        release.add(new VarInsnNode(Opcodes.ALOAD, 0));
        release.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "releaseUploadBuffer",
                "(Ljava/lang/Object;)V",
                false));
        close.instructions.insertBefore(close.instructions.getFirst(), release);
    }

    private static int instrumentUberGpuBufferBacklogReturns(
            ClassNode owner,
            MethodNode method) {
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.IRETURN) {
                continue;
            }
            InsnList telemetry = new InsnList();
            telemetry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            telemetry.add(new VarInsnNode(Opcodes.ALOAD, 0));
            telemetry.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner.name,
                    "stagedAllocations",
                    "Lit/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap;"));
            telemetry.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap",
                    "size",
                    "()I",
                    false));
            telemetry.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "recordUploadBacklogResult",
                    "(ZLjava/lang/Object;I)Z",
                    false));
            method.instructions.insertBefore(instruction, telemetry);
            patched++;
        }
        method.maxStack = Math.max(method.maxStack, 3);
        return patched;
    }

    public static void patchGlx(String jar, Path output) throws IOException {
        if (ModernSymbols.cached(jar).renderTypeRemoved("com/mojang/blaze3d/platform/GLX")) {
            // 26.3 removed GLX; RenderPatches263.patchSystemSpecsCpuInfo patches its successor
            // DebugEntrySystemSpecs.getCpuInfo, and nothing is written at the GLX entry.
            PatchRegistry.dropped("MinecraftClientPatcher.patchGlx", jar,
                    "com/mojang/blaze3d/platform/GLX.class");
            return;
        }
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/GLX.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("_getCpuInfo") && method.desc.equals("()Ljava/lang/String;")) {
                // "Browser runtime" plus the graphics quality tier on the F3 CPU line.
                InsnList code = new InsnList();
                code.add(new LdcInsnNode("Browser runtime"));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/quality/BrowserQualityOptions", "debugCpuInfo",
                        "(Ljava/lang/String;)Ljava/lang/String;", false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 0);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("GLX._getCpuInfo was not found");
        }
        write(node, output);
    }

    public static void patchGlDebugBrowserNoCallback(String jar, Path output) throws IOException {
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String legacyOwner = "com/mojang/blaze3d/opengl/GlDebug";
        String owner = renderName(symbols, legacyOwner);
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "enableDebugCallback",
                "(IZLjava/util/Set;)L" + owner + ";");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 3);
        write(node, renderEntryOutput(symbols, output, legacyOwner));
    }

    /**
     * The output entry for a render class that main() resolved against the output root under its
     * 26.2 name ({@code legacyClass}, without {@code .class}). On a jar that moved the class
     * (26.3 renderpearl) this is the moved entry; otherwise it is {@code output} unchanged.
     */
    private static Path renderEntryOutput(ModernSymbols symbols, Path output, String legacyClass) {
        Path legacy = Path.of(legacyClass + ".class");
        if (!output.endsWith(legacy)) {
            throw new IllegalArgumentException(output + " is not the output entry of " + legacyClass);
        }
        String current = renderName(symbols, legacyClass);
        if (current.equals(legacyClass)) {
            return output;
        }
        Path root = output;
        for (int index = 0; index < legacy.getNameCount(); index++) {
            root = root.getParent();
        }
        return root.resolve(current + ".class");
    }

    public static void patchCommandEncoderLegacyTextureUpload(String jar, Path output)
            throws IOException {
        ModernSymbols symbols = ModernSymbols.cached(jar);
        if (symbols.renderpearl()) {
            // 26.3 CommandEncoder is a renderpearl interface; the only consumer of the legacy
            // bridge, BrowserUnihexLoader, has a 26.3 copy that calls writeToTexture directly.
            String encoder = symbols.renderType("com/mojang/blaze3d/systems/CommandEncoder");
            String texture = symbols.renderType("com/mojang/blaze3d/textures/GpuTexture");
            find(read(jar, encoder + ".class"), "writeToTexture",
                    "(L" + texture + ";Ljava/nio/ByteBuffer;IIIIII)V");
            PatchRegistry.dropped("MinecraftClientPatcher.patchCommandEncoderLegacyTextureUpload",
                    jar, "com/mojang/blaze3d/systems/CommandEncoder.class");
            return;
        }
        String owner = "com/mojang/blaze3d/systems/CommandEncoder";
        ClassNode node = read(jar, owner + ".class");
        String legacyDescriptor = "(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;"
                + "Lcom/mojang/blaze3d/platform/NativeImage$Format;IIIIII)V";
        if (findNullable(node, "writeToTexture", legacyDescriptor) != null) {
            write(node, output);
            return;
        }
        String currentDescriptor = "(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;"
                + "IIIIII)V";
        if (findNullable(node, "writeToTexture", currentDescriptor) == null) {
            throw new IOException("CommandEncoder byte-buffer texture upload was not found");
        }
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "writeToTexture",
                legacyDescriptor,
                null,
                null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        for (int local = 4; local <= 9; local++) {
            bridge.instructions.add(new VarInsnNode(Opcodes.ILOAD, local));
        }
        bridge.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "writeToTexture",
                currentDescriptor,
                false));
        bridge.instructions.add(new InsnNode(Opcodes.RETURN));
        bridge.maxStack = 9;
        bridge.maxLocals = 10;
        node.methods.add(bridge);
        writeComputeFrames(node, output);
    }

    /**
     * Releases decoded static sprite pixels as soon as the 26.2 atlas upload has consumed them.
     *
     * <p>Desktop Minecraft can leave every {@code SpriteContents.byMipLevel} image reachable for
     * the complete atlas lifetime. In the browser those {@code NativeImage} instances own explicit
     * {@code BrowserMemory} regions, so a large server pack can retain more than the 2 GiB native
     * budget while later reload listeners are still preparing. Animated sprites keep their source
     * images because animation uploads need them; static sprites have already been copied to GPU
     * textures when {@code TextureAtlas.uploadInitialContents()} returns.</p>
     */
    public static void patchTextureAtlasBrowserReleaseStaticImages(String jar, Path root)
            throws IOException {
        String spriteOwner = "net/minecraft/client/renderer/texture/SpriteContents";
        String imageOwner = "com/mojang/blaze3d/platform/NativeImage";
        ClassNode sprite = read(jar, spriteOwner + ".class");
        if (findNullable(sprite, "browserReleaseStaticImagesAfterUpload", "()V") != null) {
            throw new IOException("SpriteContents static-image release helper already exists");
        }

        MethodNode release = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "browserReleaseStaticImagesAfterUpload",
                "()V",
                null,
                null);
        LabelNode returnLabel = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode next = new LabelNode();
        InsnList releaseCode = release.instructions;
        releaseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        releaseCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                spriteOwner,
                "animatedTexture",
                "Lnet/minecraft/client/renderer/texture/SpriteContents$AnimatedTexture;"));
        releaseCode.add(new JumpInsnNode(Opcodes.IFNONNULL, returnLabel));
        releaseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        releaseCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                spriteOwner,
                "byMipLevel",
                "[Lcom/mojang/blaze3d/platform/NativeImage;"));
        releaseCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        releaseCode.add(new InsnNode(Opcodes.ICONST_0));
        releaseCode.add(new VarInsnNode(Opcodes.ISTORE, 2));
        releaseCode.add(loop);
        releaseCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        releaseCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        releaseCode.add(new InsnNode(Opcodes.ARRAYLENGTH));
        releaseCode.add(new JumpInsnNode(Opcodes.IF_ICMPGE, next));
        releaseCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        releaseCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        releaseCode.add(new InsnNode(Opcodes.AALOAD));
        releaseCode.add(new VarInsnNode(Opcodes.ASTORE, 3));
        LabelNode skipNull = new LabelNode();
        releaseCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        releaseCode.add(new JumpInsnNode(Opcodes.IFNULL, skipNull));
        releaseCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        releaseCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, imageOwner, "close", "()V", false));
        releaseCode.add(skipNull);
        releaseCode.add(new IincInsnNode(2, 1));
        releaseCode.add(new JumpInsnNode(Opcodes.GOTO, loop));
        releaseCode.add(next);
        releaseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        releaseCode.add(new InsnNode(Opcodes.ICONST_0));
        releaseCode.add(new TypeInsnNode(Opcodes.ANEWARRAY, imageOwner));
        releaseCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                spriteOwner,
                "byMipLevel",
                "[Lcom/mojang/blaze3d/platform/NativeImage;"));
        releaseCode.add(returnLabel);
        releaseCode.add(new InsnNode(Opcodes.RETURN));
        release.maxStack = 2;
        release.maxLocals = 4;
        sprite.methods.add(release);

        MethodNode releaseAllImages = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "browserReleaseAllImagesBeforeReload",
                "()V",
                null,
                null);
        LabelNode releaseAllLoop = new LabelNode();
        LabelNode releaseAllNext = new LabelNode();
        InsnList releaseAllImagesCode = releaseAllImages.instructions;
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        releaseAllImagesCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                spriteOwner,
                "byMipLevel",
                "[Lcom/mojang/blaze3d/platform/NativeImage;"));
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        releaseAllImagesCode.add(new InsnNode(Opcodes.ICONST_0));
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ISTORE, 2));
        releaseAllImagesCode.add(releaseAllLoop);
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        releaseAllImagesCode.add(new InsnNode(Opcodes.ARRAYLENGTH));
        releaseAllImagesCode.add(new JumpInsnNode(Opcodes.IF_ICMPGE, releaseAllNext));
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        releaseAllImagesCode.add(new InsnNode(Opcodes.AALOAD));
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ASTORE, 3));
        LabelNode releaseAllSkipNull = new LabelNode();
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        releaseAllImagesCode.add(new JumpInsnNode(Opcodes.IFNULL, releaseAllSkipNull));
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ALOAD, 3));
        releaseAllImagesCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, imageOwner, "close", "()V", false));
        releaseAllImagesCode.add(releaseAllSkipNull);
        releaseAllImagesCode.add(new IincInsnNode(2, 1));
        releaseAllImagesCode.add(new JumpInsnNode(Opcodes.GOTO, releaseAllLoop));
        releaseAllImagesCode.add(releaseAllNext);
        releaseAllImagesCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        releaseAllImagesCode.add(new InsnNode(Opcodes.ICONST_0));
        releaseAllImagesCode.add(new TypeInsnNode(Opcodes.ANEWARRAY, imageOwner));
        releaseAllImagesCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                spriteOwner,
                "byMipLevel",
                "[Lcom/mojang/blaze3d/platform/NativeImage;"));
        releaseAllImagesCode.add(new InsnNode(Opcodes.RETURN));
        releaseAllImages.maxStack = 2;
        releaseAllImages.maxLocals = 4;
        sprite.methods.add(releaseAllImages);
        writeComputeFrames(sprite, root.resolve(spriteOwner + ".class"));

        String atlasOwner = "net/minecraft/client/renderer/texture/TextureAtlas";
        ClassNode atlas = read(jar, atlasOwner + ".class");
        if (findNullable(atlas, "browserReleaseStaticSpriteImages", "()V") != null) {
            throw new IOException("TextureAtlas static-image release helper already exists");
        }
        MethodNode releaseAll = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserReleaseStaticSpriteImages",
                "()V",
                null,
                null);
        LabelNode allLoop = new LabelNode();
        LabelNode allDone = new LabelNode();
        InsnList allCode = releaseAll.instructions;
        allCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        allCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, atlasOwner, "sprites", "Ljava/util/List;"));
        allCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        allCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        allCode.add(allLoop);
        allCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        allCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        allCode.add(new JumpInsnNode(Opcodes.IFEQ, allDone));
        allCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        allCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        allCode.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/renderer/texture/TextureAtlasSprite"));
        allCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/texture/TextureAtlasSprite",
                "contents",
                "()Lnet/minecraft/client/renderer/texture/SpriteContents;",
                false));
        allCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                spriteOwner,
                "browserReleaseStaticImagesAfterUpload",
                "()V",
                false));
        allCode.add(new JumpInsnNode(Opcodes.GOTO, allLoop));
        allCode.add(allDone);
        allCode.add(new InsnNode(Opcodes.RETURN));
        releaseAll.maxStack = 1;
        releaseAll.maxLocals = 2;
        atlas.methods.add(releaseAll);

        MethodNode releaseOld = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserReleaseOldSpriteImagesBeforeReload",
                "()V",
                null,
                null);
        LabelNode oldLoop = new LabelNode();
        LabelNode oldDone = new LabelNode();
        InsnList oldCode = releaseOld.instructions;
        oldCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        oldCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, atlasOwner, "sprites", "Ljava/util/List;"));
        oldCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        oldCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        oldCode.add(oldLoop);
        oldCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        oldCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        oldCode.add(new JumpInsnNode(Opcodes.IFEQ, oldDone));
        oldCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        oldCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        oldCode.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/renderer/texture/TextureAtlasSprite"));
        oldCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/texture/TextureAtlasSprite",
                "contents",
                "()Lnet/minecraft/client/renderer/texture/SpriteContents;",
                false));
        oldCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                spriteOwner,
                "browserReleaseAllImagesBeforeReload",
                "()V",
                false));
        oldCode.add(new JumpInsnNode(Opcodes.GOTO, oldLoop));
        oldCode.add(oldDone);
        oldCode.add(new InsnNode(Opcodes.RETURN));
        releaseOld.maxStack = 1;
        releaseOld.maxLocals = 2;
        atlas.methods.add(releaseOld);

        if (findNullable(atlas, "browserPrepareForSpriteReload", "()V") != null) {
            throw new IOException("TextureAtlas pre-reload release helper already exists");
        }
        MethodNode prepareForReload = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "browserPrepareForSpriteReload",
                "()V",
                null,
                null);
        LabelNode animationLoop = new LabelNode();
        LabelNode animationsDone = new LabelNode();
        LabelNode spriteUboDone = new LabelNode();
        // 26.3 moved GpuBuffer to renderpearl and made it an interface: the field type and the
        // close() call follow the probed jar (INVOKEVIRTUAL on the 26.2 abstract class).
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String gpuBufferKey = "com/mojang/blaze3d/buffers/GpuBuffer";
        String gpuBuffer = symbols.renderType(gpuBufferKey);
        int gpuBufferInvoke = symbols.invokeOpcode(gpuBufferKey);
        InsnList prepareCode = prepareForReload.instructions;
        // Detach the ticker list before closing its GPU state.  The TeaVM
        // browser runtime cannot then observe a partially closed animation
        // list if a reload begins between rendered frames.
        prepareCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepareCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                atlasOwner,
                "animatedTexturesStates",
                "Ljava/util/List;"));
        prepareCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        prepareCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepareCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/List",
                "of",
                "()Ljava/util/List;",
                true));
        prepareCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                atlasOwner,
                "animatedTexturesStates",
                "Ljava/util/List;"));
        prepareCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prepareCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        prepareCode.add(new VarInsnNode(Opcodes.ASTORE, 2));
        prepareCode.add(animationLoop);
        prepareCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        prepareCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        prepareCode.add(new JumpInsnNode(Opcodes.IFEQ, animationsDone));
        prepareCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        prepareCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        prepareCode.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/renderer/texture/SpriteContents$AnimationState"));
        prepareCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/renderer/texture/SpriteContents$AnimationState",
                "close",
                "()V",
                false));
        prepareCode.add(new JumpInsnNode(Opcodes.GOTO, animationLoop));
        prepareCode.add(animationsDone);
        prepareCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepareCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                atlasOwner,
                "spriteUbos",
                "L" + gpuBuffer + ";"));
        prepareCode.add(new JumpInsnNode(Opcodes.IFNULL, spriteUboDone));
        prepareCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepareCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                atlasOwner,
                "spriteUbos",
                "L" + gpuBuffer + ";"));
        prepareCode.add(new MethodInsnNode(
                gpuBufferInvoke,
                gpuBuffer,
                "close",
                "()V",
                gpuBufferInvoke == Opcodes.INVOKEINTERFACE));
        prepareCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepareCode.add(new InsnNode(Opcodes.ACONST_NULL));
        prepareCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                atlasOwner,
                "spriteUbos",
                "L" + gpuBuffer + ";"));
        prepareCode.add(spriteUboDone);
        prepareCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prepareCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                atlasOwner,
                "browserReleaseOldSpriteImagesBeforeReload",
                "()V",
                false));
        prepareCode.add(new InsnNode(Opcodes.RETURN));
        prepareForReload.maxStack = 1;
        prepareForReload.maxLocals = 3;
        atlas.methods.add(prepareForReload);

        MethodNode atlasUpload = find(
                atlas,
                "upload",
                "(Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;)V");
        InsnList releaseOldCall = new InsnList();
        releaseOldCall.add(new VarInsnNode(Opcodes.ALOAD, 0));
        releaseOldCall.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                atlasOwner,
                "browserReleaseOldSpriteImagesBeforeReload",
                "()V",
                false));
        atlasUpload.instructions.insertBefore(atlasUpload.instructions.getFirst(), releaseOldCall);
        atlasUpload.maxStack = Math.max(atlasUpload.maxStack, 1);

        MethodNode upload = find(atlas, "uploadInitialContents", "()V");
        int returns = 0;
        for (AbstractInsnNode instruction = upload.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    atlasOwner,
                    "browserReleaseStaticSpriteImages",
                    "()V",
                    false));
            upload.instructions.insertBefore(instruction, call);
            returns++;
        }
        if (returns != 1) {
            throw new IOException(
                    "TextureAtlas uploadInitialContents return shape changed: " + returns);
        }
        writeComputeFrames(atlas, root.resolve(atlasOwner + ".class"));

        String atlasManagerOwner = "net/minecraft/client/resources/model/sprite/AtlasManager";
        ClassNode atlasManager = read(jar, atlasManagerOwner + ".class");
        if (findNullable(atlasManager, "browserReleaseAtlasImagesBeforeReload", "()V") != null) {
            throw new IOException("AtlasManager pre-reload image release helper already exists");
        }
        MethodNode releaseAtlasImages = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "browserReleaseAtlasImagesBeforeReload",
                "()V",
                null,
                null);
        LabelNode atlasManagerLoop = new LabelNode();
        LabelNode atlasManagerDone = new LabelNode();
        InsnList atlasManagerReleaseCode = releaseAtlasImages.instructions;
        atlasManagerReleaseCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        atlasManagerReleaseCode.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                atlasManagerOwner,
                "atlasById",
                "Ljava/util/Map;"));
        atlasManagerReleaseCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "values",
                "()Ljava/util/Collection;",
                true));
        atlasManagerReleaseCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Collection",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        atlasManagerReleaseCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        atlasManagerReleaseCode.add(atlasManagerLoop);
        atlasManagerReleaseCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        atlasManagerReleaseCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        atlasManagerReleaseCode.add(new JumpInsnNode(Opcodes.IFEQ, atlasManagerDone));
        atlasManagerReleaseCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        atlasManagerReleaseCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        atlasManagerReleaseCode.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                atlasManagerOwner + "$AtlasEntry"));
        atlasManagerReleaseCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                atlasManagerOwner + "$AtlasEntry",
                "atlas",
                "()Lnet/minecraft/client/renderer/texture/TextureAtlas;",
                false));
        atlasManagerReleaseCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                atlasOwner,
                "browserPrepareForSpriteReload",
                "()V",
                false));
        atlasManagerReleaseCode.add(new JumpInsnNode(Opcodes.GOTO, atlasManagerLoop));
        atlasManagerReleaseCode.add(atlasManagerDone);
        atlasManagerReleaseCode.add(new InsnNode(Opcodes.RETURN));
        releaseAtlasImages.maxStack = 1;
        releaseAtlasImages.maxLocals = 2;
        atlasManager.methods.add(releaseAtlasImages);

        MethodNode atlasManagerReload = find(
                atlasManager,
                "reload",
                "(Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;"
                        + "Ljava/util/concurrent/Executor;"
                        + "Lnet/minecraft/server/packs/resources/"
                        + "PreparableReloadListener$PreparationBarrier;"
                        + "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;");
        InsnList releaseBeforePreparation = new InsnList();
        releaseBeforePreparation.add(new VarInsnNode(Opcodes.ALOAD, 0));
        releaseBeforePreparation.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                atlasManagerOwner,
                "browserReleaseAtlasImagesBeforeReload",
                "()V",
                false));
        atlasManagerReload.instructions.insertBefore(
                atlasManagerReload.instructions.getFirst(), releaseBeforePreparation);
        atlasManagerReload.maxStack = Math.max(atlasManagerReload.maxStack, 1);
        writeComputeFrames(atlasManager, root.resolve(atlasManagerOwner + ".class"));
        System.out.println("Released 26.2 static sprite NativeImages after atlas upload");
    }
}
