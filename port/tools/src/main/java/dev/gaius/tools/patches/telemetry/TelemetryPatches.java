package dev.gaius.tools.patches.telemetry;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.isModernProfile;
import static dev.gaius.tools.patches.support.ClientPatchSupport.minecraftEvent;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.serverStartupPhase;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import dev.gaius.tools.ModernSymbols;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
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
 * The telemetry domain of the MinecraftClientPatcher step ({@code patches/telemetry}):
 * the TELEMETRY-kind diagnostics of startup, resource reloads, GUI rendering, mappable rings,
 * generation spawns, world loading and unloading. A failing body disables the patch and leaves
 * the vanilla code.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically.
 */
public final class TelemetryPatches {
    private TelemetryPatches() {
    }

    /** Chunk-order recording is debug-only and must never enter release patch manifests. */
    public static boolean chunkGenerationOrderRequested() {
        return Boolean.getBoolean("gaius.debug.chunkGenerationOrder")
                || "1".equals(System.getenv("GAIUS_DEBUG_CHUNK_GENERATION_ORDER"));
    }

    /** Instruments the integrated server's generation task with an opt-in bounded order trace. */
    public static void patchChunkGenerationOrderTelemetry(String jar, Path outputRoot)
            throws IOException {
        String owner = "net/minecraft/server/level/ChunkGenerationTask";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "runUntilWait", "()Ljava/util/concurrent/CompletableFuture;");
        InsnList entry = chunkOrderRecord(owner, "begin");
        method.instructions.insertBefore(method.instructions.getFirst(), entry);
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                method.instructions.insertBefore(instruction, chunkOrderRecord(owner, "complete"));
            }
        }
        writeComputeFrames(node, outputRoot.resolve(owner + ".class"));
    }

    /** Logs and bounds the 26.3 section builder pool at the vanilla allocation seam. */
    public static void patchSectionBufferBuilderPoolTelemetry(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/renderer/SectionBufferBuilderPool";
        ClassNode node = read(jar, owner + ".class");
        MethodNode allocate = find(node, "allocate", "(I)L" + owner + ";");
        int injected = 0;
        for (AbstractInsnNode instruction : allocate.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/util/ArrayList")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ILOAD, 2));
                code.add(new FieldInsnNode(Opcodes.GETSTATIC,
                        "net/minecraft/client/renderer/SectionBufferBuilderPack",
                        "TOTAL_BUFFERS_SIZE", "I"));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "org/lwjgl/system/BrowserMemory", "sectionBuilderCount", "(II)I", false));
                code.add(new VarInsnNode(Opcodes.ISTORE, 2));
                allocate.instructions.insertBefore(type, code);
                injected++;
            }
        }
        if (injected != 1) throw new IllegalStateException(owner + ".allocate seam changed");
        writeComputeFrames(node, output);
    }

    private static InsnList chunkOrderRecord(String owner, String phase) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "pos", "Lnet/minecraft/world/level/ChunkPos;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/ChunkPos", "x", "()I", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "pos", "Lnet/minecraft/world/level/ChunkPos;"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/ChunkPos", "z", "()I", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "targetStatus", "Lnet/minecraft/world/level/chunk/status/ChunkStatus;"));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserChunkGenerationOrderTelemetry", phase,
                "(Ljava/lang/Object;IILjava/lang/Object;)V", false));
        return code;
    }

    public static void patchAtlasManagerReloadTaskLabels(String jar, Path outputRoot)
            throws IOException {
        String legacyEntry =
                "net/minecraft/client/resources/model/AtlasManager$AtlasEntry.class";
        String currentEntry =
                "net/minecraft/client/resources/model/sprite/AtlasManager$AtlasEntry.class";
        String entry;
        try (ZipFile input = new ZipFile(jar)) {
            entry = input.getEntry(currentEntry) != null ? currentEntry : legacyEntry;
        }
        ClassNode node = read(jar, entry);
        MethodNode scheduleLoad = find(node, "scheduleLoad", "(Lnet/minecraft/server/packs/"
                + "resources/ResourceManager;Ljava/util/concurrent/Executor;I)"
                + "Ljava/util/concurrent/CompletableFuture;");
        InsnList label = new InsnList();
        label.add(new VarInsnNode(Opcodes.ALOAD, 2));
        label.add(new VarInsnNode(Opcodes.ALOAD, 0));
        label.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserResourceReloadProfiler",
                "labelAtlas",
                "(Ljava/util/concurrent/Executor;Ljava/lang/Object;)Ljava/util/concurrent/Executor;",
                false));
        label.add(new VarInsnNode(Opcodes.ASTORE, 2));
        scheduleLoad.instructions.insert(label);
        scheduleLoad.maxStack = Math.max(scheduleLoad.maxStack, 2);
        write(node, outputRoot.resolve(entry));
    }

    public static void patchWorldSelectionListTelemetry(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/worldselection/WorldSelectionList.class");
        boolean extractedGui = node.methods.stream()
                .anyMatch(method -> method.name.equals("extractWidgetRenderState"));
        MethodNode renderWidget = find(
                node,
                extractedGui ? "extractWidgetRenderState" : "renderWidget",
                extractedGui
                        ? "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"
                        : "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportWorldSelectionList",
                "(Ljava/lang/Object;)V",
                false));
        renderWidget.instructions.insert(code);
        renderWidget.maxStack = Math.max(renderWidget.maxStack, 1);
        write(node, output);
    }

    /** Adds opt-in probes at the real 26.2 inventory -> avatar renderer boundary. */
    public static void patchInventoryAvatarRenderTelemetry(String jar, Path outputRoot)
            throws IOException {
        ClassNode inventory = read(jar, "net/minecraft/client/gui/screens/inventory/InventoryScreen.class");
        MethodNode extract = find(inventory, "extractRenderState",
                "(Lnet/minecraft/world/entity/LivingEntity;)"
                        + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;");
        int rendererCalls = 0;
        int stateCalls = 0;
        for (AbstractInsnNode instruction = extract.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.owner.equals("net/minecraft/client/renderer/entity/EntityRenderDispatcher")
                    && call.name.equals("getRenderer")) {
                InsnList probe = new InsnList();
                probe.add(new InsnNode(Opcodes.DUP));
                probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserGuiEntityTelemetry", "renderer",
                        "(Ljava/lang/Object;)V", false));
                extract.instructions.insert(call, probe);
                rendererCalls++;
            } else if (call.owner.equals("net/minecraft/client/renderer/entity/EntityRenderer")
                    && call.name.equals("createRenderState")) {
                InsnList probe = new InsnList();
                probe.add(new InsnNode(Opcodes.DUP));
                probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserGuiEntityTelemetry", "state",
                        "(Ljava/lang/Object;)V", false));
                extract.instructions.insert(call, probe);
                stateCalls++;
            }
        }
        if (rendererCalls != 1 || stateCalls != 1) {
            throw new IllegalStateException("26.2 inventory avatar telemetry points changed: renderer="
                    + rendererCalls + " state=" + stateCalls);
        }
        writeComputeFrames(inventory, outputRoot.resolve(
                "net/minecraft/client/gui/screens/inventory/InventoryScreen.class"));

        ClassNode avatar = read(jar, "net/minecraft/client/renderer/entity/player/AvatarRenderer.class");
        MethodNode avatarExtract = find(avatar, "extractRenderState",
                "(Lnet/minecraft/world/entity/Avatar;"
                        + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V");
        int skinCalls = 0;
        for (AbstractInsnNode instruction = avatarExtract.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/entity/ClientAvatarEntity")
                    && call.name.equals("getSkin")) {
                InsnList probe = new InsnList();
                probe.add(new InsnNode(Opcodes.DUP));
                probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserGuiEntityTelemetry", "skin",
                        "(Ljava/lang/Object;)V", false));
                avatarExtract.instructions.insert(call, probe);
                skinCalls++;
            }
        }
        if (skinCalls != 1) {
            throw new IllegalStateException("26.2 avatar skin telemetry point changed: " + skinCalls);
        }

        MethodNode texture = find(avatar, "getTextureLocation",
                "(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)"
                        + "Lnet/minecraft/resources/Identifier;");
        InsnList stateProbe = new InsnList();
        stateProbe.add(new VarInsnNode(Opcodes.ALOAD, 1));
        stateProbe.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGuiEntityTelemetry", "avatarState",
                "(Ljava/lang/Object;)V", false));
        texture.instructions.insert(stateProbe);
        int textureReturns = 0;
        for (AbstractInsnNode instruction = texture.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                InsnList probe = new InsnList();
                probe.add(new InsnNode(Opcodes.DUP));
                probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserGuiEntityTelemetry", "texture",
                        "(Ljava/lang/Object;)V", false));
                texture.instructions.insertBefore(instruction, probe);
                textureReturns++;
            }
        }
        if (textureReturns != 1) {
            throw new IllegalStateException("26.2 avatar texture return point changed: " + textureReturns);
        }
        writeComputeFrames(avatar, outputRoot.resolve(
                "net/minecraft/client/renderer/entity/player/AvatarRenderer.class"));
    }

    public static void patchGuiEntitySubmitTelemetry(String jar, Path output)
            throws IOException {
        // patchGuiGraphicsBrowserItemCache runs earlier and writes this same class.
        // Consume that output so the entity probe cannot erase the item-cache patch.
        ClassNode node = Files.exists(output)
                ? read(output)
                : read(jar, "net/minecraft/client/gui/GuiGraphicsExtractor.class");
        MethodNode entity = find(node, "entity",
                "(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F"
                        + "Lorg/joml/Vector3fc;Lorg/joml/Quaternionfc;Lorg/joml/Quaternionfc;IIII)V");
        InsnList probe = new InsnList();
        probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGuiEntityTelemetry", "submit", "()V", false));
        insertAtStart(entity, probe);
        entity.maxStack = Math.max(entity.maxStack, 1);
        writeComputeFrames(node, output);
        ClassNode result = read(output);
        boolean hasEntityProbe = result.methods.stream()
                .filter(method -> method.name.equals("entity") && method.desc.equals(entity.desc))
                .flatMap(method -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(), 0), false))
                .anyMatch(instruction -> instruction instanceof MethodInsnNode call
                        && call.owner.equals("dev/gaius/browser/BrowserGuiEntityTelemetry")
                        && call.name.equals("submit"));
        boolean hasItemCache = result.methods.stream()
                .flatMap(method -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(), 0), false))
                .anyMatch(instruction -> instruction instanceof MethodInsnNode call
                        && call.owner.equals("dev/gaius/browser/BrowserGuiItemCache")
                        && call.name.equals("guiState"));
        if (!hasEntityProbe || !hasItemCache) {
            throw new IllegalStateException("GuiGraphicsExtractor composition lost: entityProbe="
                    + hasEntityProbe + " itemCache=" + hasItemCache);
        }
    }

    public static void patchGuiRenderTelemetry(String jar, Path root) throws IOException {
        patchGuiRenderStateTelemetry(jar, root);
        patchGuiRendererTelemetry(jar, root.resolve(
                "net/minecraft/client/gui/render/GuiRenderer.class"), root);
    }

    private static void patchGuiRenderStateTelemetry(String jar, Path outputRoot) throws IOException {
        String legacyEntry = "net/minecraft/client/gui/render/state/GuiRenderState.class";
        String currentEntry = "net/minecraft/client/renderer/state/gui/GuiRenderState.class";
        String entry;
        try (ZipFile input = new ZipFile(jar)) {
            entry = input.getEntry(currentEntry) != null ? currentEntry : legacyEntry;
        }
        ClassNode node = read(jar, entry);
        insertAtStart(find(node, "reset", "()V"), browserGuiItemPoolResetCode());
        writeComputeFrames(node, outputRoot.resolve(entry));
    }

    private static InsnList browserGuiItemPoolResetCode() {
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGuiItemCache",
                "resetPool",
                "()V",
                false));
        return code;
    }

    private static void patchGuiRendererTelemetry(String jar, Path output, Path outputRoot)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/render/GuiRenderer.class");
        boolean currentGuiPipeline = node.methods.stream()
                .anyMatch(method -> method.name.equals("render") && method.desc.equals("()V"));

        String renderDescriptor = currentGuiPipeline
                ? "()V"
                : "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V";
        MethodNode render = find(node, "render", renderDescriptor);
        insertAtStart(render, staticCall("reportGuiRenderStart", "()V"));

        MethodNode draw = find(node, "draw", renderDescriptor);
        InsnList drawPlan = new InsnList();
        drawPlan.add(new VarInsnNode(Opcodes.ALOAD, 0));
        drawPlan.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/gui/render/GuiRenderer",
                "draws",
                "Ljava/util/List;"));
        drawPlan.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "size",
                "()I",
                true));
        if (currentGuiPipeline) {
            drawPlan.add(new InsnNode(Opcodes.ICONST_0));
        } else {
            drawPlan.add(new VarInsnNode(Opcodes.ALOAD, 0));
            drawPlan.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/gui/render/GuiRenderer",
                    "meshesToDraw",
                    "Ljava/util/List;"));
            drawPlan.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/List",
                    "size",
                    "()I",
                    true));
        }
        drawPlan.add(new VarInsnNode(Opcodes.ALOAD, 0));
        drawPlan.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/gui/render/GuiRenderer",
                "firstDrawIndexAfterBlur",
                "I"));
        drawPlan.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportGuiDrawPlan",
                "(III)V",
                false));
        insertAtStart(draw, drawPlan);

        if (currentGuiPipeline) {
            String itemAtlasEntry = "net/minecraft/client/gui/render/GuiItemAtlas.class";
            ClassNode itemAtlas = read(jar, itemAtlasEntry);
            MethodNode getOrUpdate = find(
                    itemAtlas,
                    "getOrUpdate",
                    "(Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;)"
                            + "Lnet/minecraft/client/gui/render/GuiItemAtlas$SlotView;");
            freezeGuiAnimatedItemAtlasHit(getOrUpdate);
            writeComputeFrames(itemAtlas, outputRoot.resolve(itemAtlasEntry));
        } else {
            MethodNode itemLambda = find(node, "lambda$prepareItemElements$3",
                    "(Lorg/apache/commons/lang3/mutable/MutableBoolean;II"
                            + "Lorg/apache/commons/lang3/mutable/MutableBoolean;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/gui/render/state/GuiItemRenderState;)V");
            freezeGuiAnimatedItemAtlasHit(itemLambda);
        }

        writeComputeFrames(node, output);
    }

    private static void freezeGuiAnimatedItemAtlasHit(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && "net/minecraft/client/renderer/item/TrackingItemStackRenderState".equals(call.owner)
                    && "isAnimated".equals(call.name)
                    && "()Z".equals(call.desc)) {
                method.instructions.insertBefore(instruction, new InsnNode(Opcodes.POP));
                method.instructions.insertBefore(instruction, new InsnNode(Opcodes.ICONST_0));
                method.instructions.remove(instruction);
                return;
            }
        }
        throw new IllegalStateException("GuiRenderer animated item atlas hit branch not found");
    }

    private static InsnList staticCall(String name, String desc) {
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                name,
                desc,
                false));
        return code;
    }

    private static void insertAtStart(MethodNode method, InsnList code) {
        method.instructions.insert(code);
    }

    /**
     * Observes 26.2's ignored MappableRingBuffer fence result without changing the wait,
     * close, slot-clear or buffer-return control flow.
     */
    public static void patchMappableRingBufferTelemetry(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/renderer/MappableRingBuffer";
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String fence = symbols.renderType("com/mojang/blaze3d/buffers/GpuFence");
        String buffer = symbols.renderType("com/mojang/blaze3d/buffers/GpuBuffer");
        // 26.2 waits with Long.MAX_VALUE; 26.3 passes GpuFence.NO_TIMEOUT (-1L). BrowserOpenGL
        // polls every client wait with a zero timeout, so only the telemetry value differs.
        Long awaitTimeout = symbols.renderpearl()
                ? Long.valueOf(-1L) : Long.valueOf(Long.MAX_VALUE);
        String telemetry = "org/lwjgl/opengl/BrowserOpenGL";
        ClassNode node = read(jar, owner + ".class");
        MethodNode currentBuffer = find(node, "currentBuffer", "()L" + buffer + ";");
        java.util.List<AbstractInsnNode> executable = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : currentBuffer.instructions.toArray()) {
            if (instruction.getOpcode() >= 0) {
                executable.add(instruction);
            }
        }
        if (executable.size() != 26
                || !(executable.get(0) instanceof VarInsnNode loadThis)
                || loadThis.getOpcode() != Opcodes.ALOAD
                || loadThis.var != 0
                || !(executable.get(1) instanceof FieldInsnNode fencesField)
                || fencesField.getOpcode() != Opcodes.GETFIELD
                || !fencesField.owner.equals(owner)
                || !fencesField.name.equals("fences")
                || !fencesField.desc.equals("[L" + fence + ";")
                || !(executable.get(2) instanceof VarInsnNode currentOwner)
                || currentOwner.getOpcode() != Opcodes.ALOAD
                || currentOwner.var != 0
                || !(executable.get(3) instanceof FieldInsnNode currentField)
                || currentField.getOpcode() != Opcodes.GETFIELD
                || !currentField.owner.equals(owner)
                || !currentField.name.equals("current")
                || !currentField.desc.equals("I")
                || executable.get(4).getOpcode() != Opcodes.AALOAD
                || !(executable.get(5) instanceof VarInsnNode storeFence)
                || storeFence.getOpcode() != Opcodes.ASTORE
                || storeFence.var != 1
                || !(executable.get(6) instanceof VarInsnNode testFence)
                || testFence.getOpcode() != Opcodes.ALOAD
                || testFence.var != 1
                || !(executable.get(7) instanceof JumpInsnNode nullFence)
                || nullFence.getOpcode() != Opcodes.IFNULL
                || !(executable.get(8) instanceof VarInsnNode awaitOwner)
                || awaitOwner.getOpcode() != Opcodes.ALOAD
                || awaitOwner.var != 1
                || !(executable.get(9) instanceof LdcInsnNode timeout)
                || !awaitTimeout.equals(timeout.cst)
                || !(executable.get(10) instanceof MethodInsnNode awaitCompletion)
                || awaitCompletion.getOpcode() != Opcodes.INVOKEINTERFACE
                || !awaitCompletion.owner.equals(fence)
                || !awaitCompletion.name.equals("awaitCompletion")
                || !awaitCompletion.desc.equals("(J)Z")
                || executable.get(11).getOpcode() != Opcodes.POP
                || !(executable.get(12) instanceof VarInsnNode closeOwner)
                || closeOwner.getOpcode() != Opcodes.ALOAD
                || closeOwner.var != 1
                || !(executable.get(13) instanceof MethodInsnNode close)
                || close.getOpcode() != Opcodes.INVOKEINTERFACE
                || !close.owner.equals(fence)
                || !close.name.equals("close")
                || !close.desc.equals("()V")
                || !(executable.get(14) instanceof VarInsnNode clearOwner)
                || clearOwner.getOpcode() != Opcodes.ALOAD
                || clearOwner.var != 0
                || !(executable.get(15) instanceof FieldInsnNode clearFences)
                || clearFences.getOpcode() != Opcodes.GETFIELD
                || !clearFences.owner.equals(owner)
                || !clearFences.name.equals("fences")
                || !clearFences.desc.equals("[L" + fence + ";")
                || !(executable.get(16) instanceof VarInsnNode clearCurrentOwner)
                || clearCurrentOwner.getOpcode() != Opcodes.ALOAD
                || clearCurrentOwner.var != 0
                || !(executable.get(17) instanceof FieldInsnNode clearCurrent)
                || clearCurrent.getOpcode() != Opcodes.GETFIELD
                || !clearCurrent.owner.equals(owner)
                || !clearCurrent.name.equals("current")
                || !clearCurrent.desc.equals("I")
                || executable.get(18).getOpcode() != Opcodes.ACONST_NULL
                || executable.get(19).getOpcode() != Opcodes.AASTORE
                || nextOpcode(nullFence.label) != executable.get(20)
                || !(executable.get(20) instanceof VarInsnNode returnOwner)
                || returnOwner.getOpcode() != Opcodes.ALOAD
                || returnOwner.var != 0
                || !(executable.get(21) instanceof FieldInsnNode buffersField)
                || buffersField.getOpcode() != Opcodes.GETFIELD
                || !buffersField.owner.equals(owner)
                || !buffersField.name.equals("buffers")
                || !buffersField.desc.equals("[L" + buffer + ";")
                || !(executable.get(22) instanceof VarInsnNode returnCurrentOwner)
                || returnCurrentOwner.getOpcode() != Opcodes.ALOAD
                || returnCurrentOwner.var != 0
                || !(executable.get(23) instanceof FieldInsnNode returnCurrent)
                || returnCurrent.getOpcode() != Opcodes.GETFIELD
                || !returnCurrent.owner.equals(owner)
                || !returnCurrent.name.equals("current")
                || !returnCurrent.desc.equals("I")
                || executable.get(24).getOpcode() != Opcodes.AALOAD
                || executable.get(25).getOpcode() != Opcodes.ARETURN) {
            throw new IllegalStateException(owner + ".currentBuffer exact "
                    + (symbols.renderpearl() ? "renderpearl" : "blaze3d") + " shape changed");
        }
        int awaitCalls = 0;
        int returns = 0;
        for (AbstractInsnNode instruction : currentBuffer.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(fence)
                    && call.name.equals("awaitCompletion")
                    && call.desc.equals("(J)Z")) {
                awaitCalls++;
            }
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                returns++;
            }
        }
        if (awaitCalls != 1 || returns != 1) {
            throw new IllegalStateException(
                    owner + ".currentBuffer await/return count changed: "
                            + awaitCalls + "/" + returns);
        }

        InsnList entryTelemetry = new InsnList();
        entryTelemetry.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                telemetry,
                "noteMappableRingCurrentBuffer",
                "()V",
                false));
        currentBuffer.instructions.insertBefore(executable.get(0), entryTelemetry);

        InsnList resultTelemetry = new InsnList();
        resultTelemetry.add(new InsnNode(Opcodes.DUP));
        resultTelemetry.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                telemetry,
                "noteMappableRingAwaitResult",
                "(Z)V",
                false));
        currentBuffer.instructions.insert(awaitCompletion, resultTelemetry);

        writeComputeFrames(node, output);
        System.out.println("Instrumented " + (symbols.renderpearl() ? "renderpearl" : "blaze3d")
                + " MappableRingBuffer.currentBuffer fence-result telemetry");
    }

    public static void patchWorldUnloadTelemetry(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/telemetry/events/WorldUnloadEvent.class");
        MethodNode method = find(node, "getTimeInSecondsSinceLoad", "(Ljava/time/Instant;)I");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 1, 2);
        write(node, output);
    }

    public static void patchWorldLoaderBrowserStartupTelemetry(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/server/WorldLoader";
        ClassNode node = read(jar, owner + ".class");
        MethodNode load = find(node, "load",
                "(Lnet/minecraft/server/WorldLoader$InitConfig;"
                        + "Lnet/minecraft/server/WorldLoader$WorldDataSupplier;"
                        + "Lnet/minecraft/server/WorldLoader$ResultFactory;"
                        + "Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)"
                        + "Ljava/util/concurrent/CompletableFuture;");
        load.instructions.insert(serverStartupPhase("world-loader-started"));

        boolean worldgenRegistryPatched = false;
        boolean dimensionRegistryPatched = false;
        boolean serverResourcesPatched = false;
        boolean resultPatched = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/resources/RegistryDataLoader")
                        && call.name.equals("load")) {
                    FieldInsnNode registryList = previousFieldInstruction(call,
                            "net/minecraft/resources/RegistryDataLoader");
                    if (registryList == null) {
                        continue;
                    }
                    // 26.3 renamed WORLDGEN_REGISTRIES to WORLD_REGISTRIES (same role, same
                    // load call); the telemetry phase name stays the same for both.
                    if (!worldgenRegistryPatched
                            && (registryList.name.equals("WORLDGEN_REGISTRIES")
                                    || registryList.name.equals("WORLD_REGISTRIES"))) {
                        method.instructions.insertBefore(
                                call,
                                serverStartupPhase("world-loader-worldgen-registries-started"));
                        worldgenRegistryPatched = true;
                    } else if (!dimensionRegistryPatched
                            && registryList.name.equals("DIMENSION_REGISTRIES")) {
                        method.instructions.insertBefore(
                                call,
                                serverStartupPhase("world-loader-dimension-registries-started"));
                        dimensionRegistryPatched = true;
                    }
                } else if (!serverResourcesPatched
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/server/ReloadableServerResources")
                        && call.name.equals("loadResources")) {
                    method.instructions.insertBefore(
                            call,
                            serverStartupPhase("world-loader-server-resources-started"));
                    serverResourcesPatched = true;
                } else if (!resultPatched
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && call.owner.equals("net/minecraft/server/WorldLoader$ResultFactory")
                        && call.name.equals("create")) {
                    method.instructions.insertBefore(
                            call,
                            serverStartupPhase("world-loader-server-resources-ready"));
                    resultPatched = true;
                }
            }
            method.maxStack = Math.max(method.maxStack, 16);
        }
        if (!worldgenRegistryPatched
                || !dimensionRegistryPatched
                || !serverResourcesPatched
                || !resultPatched) {
            throw new IOException("WorldLoader startup telemetry patch points changed: worldgen="
                    + worldgenRegistryPatched + ", dimensions=" + dimensionRegistryPatched
                    + ", resources=" + serverResourcesPatched + ", result=" + resultPatched);
        }
        writeComputeFrames(node, output);
    }

    private static FieldInsnNode previousFieldInstruction(
            AbstractInsnNode start, String owner) {
        int remaining = 12;
        for (AbstractInsnNode instruction = start.getPrevious();
                instruction != null && remaining-- > 0;
                instruction = instruction.getPrevious()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(owner)) {
                return field;
            }
        }
        return null;
    }

    /** Adds diagnostics around each vanilla resource reload listener without reordering it. */
    public static void patchResourceReloadProfiling(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/server/packs/resources/SimpleReloadInstance.class");
        MethodNode method = find(node, "prepareTasks",
                "(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;"
                        + "Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;"
                        + "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;"
                        + "Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;");
        if (method == null) {
            throw new IOException("SimpleReloadInstance prepareTasks patch point was not found");
        }
        boolean wrapped = false;
        int listenerLocal = -1;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof TypeInsnNode cast)
                    || cast.getOpcode() != Opcodes.CHECKCAST
                    || !cast.desc.equals("net/minecraft/server/packs/resources/PreparableReloadListener")) {
                continue;
            }
            AbstractInsnNode next = instruction.getNext();
            while (next != null && next.getOpcode() < 0) {
                next = next.getNext();
            }
            if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
                continue;
            }
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, store.var));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserResourceReloadProfiler",
                    "wrap",
                    "(Lnet/minecraft/server/packs/resources/PreparableReloadListener;)"
                            + "Lnet/minecraft/server/packs/resources/PreparableReloadListener;",
                    false));
            code.add(new VarInsnNode(Opcodes.ASTORE, store.var));
            method.instructions.insert(store, code);
            listenerLocal = store.var;
            wrapped = true;
            break;
        }
        if (!wrapped) {
            throw new IOException("SimpleReloadInstance listener iteration patch point was not found");
        }
        boolean barrierUnwrapped = false;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/server/packs/resources/SimpleReloadInstance")
                    || !call.name.equals("createBarrierForListener")) {
                continue;
            }
            AbstractInsnNode listenerLoad = instruction.getPrevious();
            while (listenerLoad != null) {
                if (listenerLoad instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD
                        && load.var == listenerLocal) {
                    break;
                }
                listenerLoad = listenerLoad.getPrevious();
            }
            if (listenerLoad == null) {
                continue;
            }
            method.instructions.insert(listenerLoad, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserResourceReloadProfiler",
                    "unwrap",
                    "(Lnet/minecraft/server/packs/resources/PreparableReloadListener;)"
                            + "Lnet/minecraft/server/packs/resources/PreparableReloadListener;",
                    false));
            barrierUnwrapped = true;
            break;
        }
        if (!barrierUnwrapped) {
            throw new IOException("SimpleReloadInstance barrier listener patch point was not found");
        }
        method.maxStack = Math.max(method.maxStack, 1);
        writeComputeFrames(node, output);
    }

    public static void patchResourceReloadTaskLabels(String jar, Path modelOutput, Path fontOutput)
            throws IOException {
        ClassNode modelManager = read(jar, "net/minecraft/client/resources/model/ModelManager.class");
        MethodNode modelReload = find(modelManager, "reload", "(Lnet/minecraft/server/packs/resources/"
                + "PreparableReloadListener$SharedState;Ljava/util/concurrent/Executor;"
                + "Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;"
                + "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;");
        if (modelReload == null) {
            throw new IOException("ModelManager reload task label patch point was not found");
        }
        int modelContinuationCount = 0;
        for (AbstractInsnNode instruction = modelReload.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("java/util/concurrent/CompletableFuture")
                    && call.name.equals("thenApplyAsync")) {
                modelContinuationCount++;
            }
        }
        String[] modelLabels;
        if (modelContinuationCount == 3) {
            modelLabels = new String[] {
                    "ModelManager.specialBlockModels",
                    "ModelManager.discoverModelDependencies",
                    "ModelManager.buildModelGroups",
            };
        } else if (modelContinuationCount == 2) {
            modelLabels = new String[] {
                    "ModelManager.discoverModelDependencies",
                    "ModelManager.buildModelGroups",
            };
        } else {
            throw new IOException(
                    "Unsupported ModelManager async continuation count: "
                            + modelContinuationCount);
        }
        int modelContinuation = 0;
        for (AbstractInsnNode instruction = modelReload.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("java/util/concurrent/CompletableFuture")
                    || !call.name.equals("thenApplyAsync")) {
                continue;
            }
            if (modelContinuation >= modelLabels.length) {
                throw new IOException("ModelManager has unexpected async continuation count");
            }
            modelReload.instructions.insertBefore(call, reloadTaskLabel(modelLabels[modelContinuation++]));
        }
        if (modelContinuation != modelLabels.length) {
            throw new IOException("ModelManager async continuation patch point was not found");
        }
        patchModelManagerBrowserDependencyScheduler(modelReload);
        patchModelManagerBrowserDependencyResult(nodeMethod(modelManager, "lambda$reload$4"));
        writeComputeFrames(modelManager, modelOutput);

        ClassNode fontManager = read(jar, "net/minecraft/client/gui/font/FontManager.class");
        MethodNode fontReload = find(fontManager, "reload", "(Lnet/minecraft/server/packs/resources/"
                + "PreparableReloadListener$SharedState;Ljava/util/concurrent/Executor;"
                + "Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;"
                + "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;");
        if (fontReload == null) {
            throw new IOException("FontManager reload task label patch point was not found");
        }
        int fontApply = 0;
        for (AbstractInsnNode instruction = fontReload.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("java/util/concurrent/CompletableFuture")
                    || !call.name.equals("thenAcceptAsync")) {
                continue;
            }
            fontReload.instructions.insertBefore(call, reloadTaskLabel("FontManager.apply"));
            fontApply++;
        }
        if (fontApply != 1) {
            throw new IOException("FontManager apply task label patch point was not found");
        }
        patchFontManagerApplySections(fontManager);
        writeComputeFrames(fontManager, fontOutput);
    }

    /** Records the four synchronous phases of FontManager.apply without changing their order. */
    private static void patchFontManagerApplySections(ClassNode node) throws IOException {
        MethodNode apply = find(node, "apply", "(Lnet/minecraft/client/gui/font/FontManager$Preparation;"
                + "Lnet/minecraft/util/profiling/ProfilerFiller;)V");
        if (apply == null) {
            throw new IOException("FontManager apply section patch point was not found");
        }
        String[] names = {
                "FontManager.apply.closeFontSets",
                "FontManager.apply.closeProviders",
                "FontManager.apply.createFontSets",
                "FontManager.apply.bindAtlasProviders",
        };
        int section = 0;
        for (AbstractInsnNode instruction = apply.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            boolean match = (call.owner.equals("java/util/Collection") && call.name.equals("forEach"))
                    || (call.owner.equals("java/util/List") && call.name.equals("forEach"))
                    || (call.owner.equals("java/util/Map") && call.name.equals("forEach"))
                    || ((call.owner.equals("net/minecraft/client/resources/model/AtlasManager")
                                    || call.owner.equals(
                                            "net/minecraft/client/resources/model/sprite/AtlasManager"))
                            && call.name.equals("forEach"));
            if (!match) {
                continue;
            }
            if (section >= names.length) {
                throw new IOException("FontManager apply has unexpected synchronous sections");
            }
            String name = names[section++];
            apply.instructions.insertBefore(call, reloadSectionEvent("sectionStarted", name));
            apply.instructions.insert(call, reloadSectionEvent("sectionCompleted", name));
        }
        if (section != names.length) {
            throw new IOException("FontManager apply synchronous section patch points were not found");
        }
    }

    private static InsnList reloadTaskLabel(String label) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(label));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserResourceReloadProfiler",
                "label",
                "(Ljava/util/concurrent/Executor;Ljava/lang/String;)Ljava/util/concurrent/Executor;",
                false));
        return code;
    }

    private static InsnList reloadSectionEvent(String method, String name) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(name));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserResourceReloadProfiler",
                method,
                "(Ljava/lang/String;)V",
                false));
        return code;
    }

    /** Replaces vanilla's one-turn dependency discovery continuation with a frame-budgeted stage. */
    private static void patchModelManagerBrowserDependencyScheduler(MethodNode reload)
            throws IOException {
        MethodInsnNode continuation = null;
        for (AbstractInsnNode instruction = reload.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("java/util/concurrent/CompletableFuture")
                    || !call.name.equals("thenApplyAsync")) {
                continue;
            }
            AbstractInsnNode previous = call.getPrevious();
            while (previous != null && previous.getOpcode() < 0) {
                previous = previous.getPrevious();
            }
            if (!(previous instanceof MethodInsnNode label)
                    || !label.owner.equals("dev/gaius/browser/BrowserResourceReloadProfiler")
                    || !label.name.equals("label")) {
                continue;
            }
            AbstractInsnNode labelText = label.getPrevious();
            while (labelText != null && labelText.getOpcode() < 0) {
                labelText = labelText.getPrevious();
            }
            if (labelText instanceof LdcInsnNode text
                    && "ModelManager.discoverModelDependencies".equals(text.cst)) {
                continuation = call;
                break;
            }
        }
        if (continuation == null) {
            throw new IOException("ModelManager dependency continuation patch point was not found");
        }
        AbstractInsnNode factory = continuation.getPrevious();
        while (factory != null) {
            if (factory instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode) {
                break;
            }
            factory = factory.getPrevious();
        }
        if (!(factory instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode)) {
            throw new IOException("ModelManager dependency continuation lambda was not found");
        }
        AbstractInsnNode thirdFuture = previousOpcode(factory);
        AbstractInsnNode secondFuture = previousOpcode(thirdFuture);
        AbstractInsnNode firstFuture = previousOpcode(secondFuture);
        if (!(firstFuture instanceof VarInsnNode first && first.getOpcode() == Opcodes.ALOAD)
                || !(secondFuture instanceof VarInsnNode second && second.getOpcode() == Opcodes.ALOAD)
                || !(thirdFuture instanceof VarInsnNode third && third.getOpcode() == Opcodes.ALOAD)) {
            throw new IOException("ModelManager dependency continuation captures changed");
        }
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, first.var));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, second.var));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, third.var));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/client/resources/model/BrowserModelDependencyScheduler",
                "continuation",
                "(Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/CompletableFuture;"
                        + "Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;)"
                        + "Ljava/util/function/Function;",
                false));
        reload.instructions.insertBefore(firstFuture, replacement);
        reload.instructions.remove(firstFuture);
        reload.instructions.remove(secondFuture);
        reload.instructions.remove(thirdFuture);
        reload.instructions.remove(factory);
        continuation.name = "thenComposeAsync";
    }

    /** Constructs the private ResolvedModels record inside ModelManager after frame-sliced discovery. */
    private static void patchModelManagerBrowserDependencyResult(MethodNode reloadState) throws IOException {
        if (reloadState == null) {
            throw new IOException("ModelManager reload result continuation was not found");
        }
        TypeInsnNode resolvedModelsCast = null;
        for (AbstractInsnNode instruction = reloadState.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode cast
                    && cast.getOpcode() == Opcodes.CHECKCAST
                    && cast.desc.equals("net/minecraft/client/resources/model/ModelManager$ResolvedModels")) {
                resolvedModelsCast = cast;
                break;
            }
        }
        if (resolvedModelsCast == null) {
            throw new IOException("ModelManager resolved-model result cast was not found");
        }
        AbstractInsnNode store = nextOpcode(resolvedModelsCast);
        if (!(store instanceof VarInsnNode resultStore) || resultStore.getOpcode() != Opcodes.ASTORE) {
            throw new IOException("ModelManager resolved-model result storage changed");
        }
        resolvedModelsCast.desc = "net/minecraft/client/resources/model/ModelDiscovery";
        InsnList constructResolvedModels = new InsnList();
        constructResolvedModels.add(new TypeInsnNode(
                Opcodes.NEW, "net/minecraft/client/resources/model/ModelManager$ResolvedModels"));
        constructResolvedModels.add(new InsnNode(Opcodes.DUP));
        constructResolvedModels.add(new VarInsnNode(Opcodes.ALOAD, resultStore.var));
        constructResolvedModels.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/resources/model/ModelDiscovery",
                "missingModel",
                "()Lnet/minecraft/client/resources/model/ResolvedModel;",
                false));
        constructResolvedModels.add(new VarInsnNode(Opcodes.ALOAD, resultStore.var));
        constructResolvedModels.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/resources/model/ModelDiscovery",
                "resolve",
                "()Ljava/util/Map;",
                false));
        constructResolvedModels.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/client/resources/model/ModelManager$ResolvedModels",
                "<init>",
                "(Lnet/minecraft/client/resources/model/ResolvedModel;Ljava/util/Map;)V",
                false));
        constructResolvedModels.add(new VarInsnNode(Opcodes.ASTORE, resultStore.var));
        reloadState.instructions.insert(store, constructResolvedModels);
    }

    private static MethodNode nodeMethod(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name)) {
                return method;
            }
        }
        return null;
    }

    public static void patchClientPacketListenerLoadingDiagnostics(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/multiplayer/ClientPacketListener.class");
        boolean handleLoginHooked = false;
        boolean startWaitingHooked = false;
        boolean levelChunkHooked = false;
        boolean batchStartHooked = false;
        boolean batchStartLoadingPacketsHooked = false;
        boolean batchFinishedHooked = false;
        boolean tickClientLoadHooked = false;
        boolean loadingPacketsHooked = false;
        boolean notifyPlayerLoadedHooked = false;
        boolean closeLoadingScreenHooked = false;
        ClassNode chunkPacket = read(jar,
                "net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket.class");
        boolean chunkPacketCoordinates = chunkPacket.methods.stream().anyMatch(method ->
                (method.name.equals("x") || method.name.equals("z")) && method.desc.equals("()I"));

        for (MethodNode method : node.methods) {
            if (method.name.equals("handleLogin")
                    && method.desc.equals("(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V")) {
                method.instructions.insert(minecraftEvent("client.handleLogin"));
                method.maxStack = Math.max(method.maxStack, 1);
                handleLoginHooked = true;
            } else if (method.name.equals("startWaitingForNewLevel")
                    && method.desc.equals("(Lnet/minecraft/client/player/LocalPlayer;"
                            + "Lnet/minecraft/client/multiplayer/ClientLevel;"
                            + "Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;)V")) {
                method.instructions.insert(minecraftEvent("client.startWaitingForNewLevel"));
                method.maxStack = Math.max(method.maxStack, 1);
                startWaitingHooked = true;
            } else if (method.name.equals("handleLevelChunkWithLight")
                    && method.desc.equals("(Lnet/minecraft/network/protocol/game/"
                            + "ClientboundLevelChunkWithLightPacket;)V")) {
                InsnList code = new InsnList();
                code.add(minecraftEvent("client.handleLevelChunkWithLight"));
                if (chunkPacketCoordinates) {
                    code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                            "net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket",
                            "x", "()I", false));
                    code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                            "net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket",
                            "z", "()I", false));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "org/lwjgl/opengl/BrowserOpenGL", "reportMinecraftChunkPacket", "(II)V", false));
                }
                method.instructions.insert(code);
                method.maxStack = Math.max(method.maxStack, 3);
                levelChunkHooked = true;
            } else if (method.name.equals("handleChunkBatchStart")
                    && method.desc.equals("(Lnet/minecraft/network/protocol/game/"
                            + "ClientboundChunkBatchStartPacket;)V")) {
                InsnList code = new InsnList();
                code.add(minecraftEvent("client.handleChunkBatchStart"));
                code.add(notifyLoadingPacketsReceived("client.loadingPacketsReceived.chunkBatchStart"));
                method.instructions.insert(code);
                method.maxStack = Math.max(method.maxStack, 2);
                batchStartHooked = true;
                batchStartLoadingPacketsHooked = true;
            } else if (method.name.equals("handleChunkBatchFinished")
                    && method.desc.equals("(Lnet/minecraft/network/protocol/game/"
                            + "ClientboundChunkBatchFinishedPacket;)V")) {
                method.instructions.insert(minecraftEvent("client.handleChunkBatchFinished"));
                method.maxStack = Math.max(method.maxStack, 1);
                batchFinishedHooked = true;
            } else if (method.name.equals("notifyPlayerLoaded")
                    && method.desc.equals("()V")) {
                method.instructions.insert(minecraftEvent("client.notifyPlayerLoaded"));
                method.maxStack = Math.max(method.maxStack, 1);
                notifyPlayerLoadedHooked = true;
            }

            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/client/multiplayer/LevelLoadTracker")
                        && call.name.equals("tickClientLoad")
                        && call.desc.equals("()V")) {
                    method.instructions.insertBefore(call, minecraftEvent("client.tickClientLoad"));
                    method.maxStack = Math.max(method.maxStack, 2);
                    tickClientLoadHooked = true;
                } else if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/client/multiplayer/LevelLoadTracker")
                        && call.name.equals("isLevelReady")
                        && call.desc.equals("()Z")) {
                    AbstractInsnNode next = nextOpcode(call);
                    if (!(next instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) {
                        throw new IllegalStateException("ClientPacketListener level-ready branch shape changed");
                    }
                    // Keep the vanilla readiness branch. A ClientLevel instance is not
                    // sufficient evidence that terrain meshes and collision sections are
                    // installed; advancing here makes the browser client spawn into air.
                } else if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/client/multiplayer/ClientPacketListener")
                        && call.name.equals("notifyPlayerLoaded")
                        && call.desc.equals("()V")) {
                    method.instructions.insert(call, closeLevelLoadingScreenIfPresent());
                    method.maxStack = Math.max(method.maxStack, 2);
                    closeLoadingScreenHooked = true;
                } else if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/client/multiplayer/LevelLoadTracker")
                        && call.name.equals("loadingPacketsReceived")
                        && call.desc.equals("()V")) {
                    method.instructions.insertBefore(call, minecraftEvent("client.loadingPacketsReceived"));
                    method.maxStack = Math.max(method.maxStack, 2);
                    loadingPacketsHooked = true;
                }
            }
        }

        if (!handleLoginHooked
                || !startWaitingHooked
                || !levelChunkHooked
                || !batchStartHooked
                || !batchStartLoadingPacketsHooked
                || !batchFinishedHooked
                || !tickClientLoadHooked
                || !loadingPacketsHooked
                || !notifyPlayerLoadedHooked
                || !closeLoadingScreenHooked) {
            throw new IllegalStateException("ClientPacketListener loading diagnostic patch points were not found");
        }
        write(node, output);
    }

    private static InsnList browserGenerationSpawnEntityAddedCode(int tokenLocal) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ILOAD, tokenLocal));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGenerationSpawnTelemetry",
                "entityAdded",
                "(I)V",
                false));
        return code;
    }

    private static InsnList browserGenerationSpawnFinishCode(String method, int tokenLocal) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ILOAD, tokenLocal));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGenerationSpawnTelemetry",
                method,
                "(I)V",
                false));
        return code;
    }

    public static void patchNaturalSpawnerGenerationSpawnTelemetry(
            String jar, Path output, String minecraftVersion) throws IOException {
        String owner = "net/minecraft/world/level/NaturalSpawner";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = findNullable(
                node,
                "spawnMobsForChunkGeneration",
                "(Lnet/minecraft/world/level/ServerLevelAccessor;"
                        + "Lnet/minecraft/core/Holder;"
                        + "Lnet/minecraft/world/level/ChunkPos;"
                        + "Lnet/minecraft/util/RandomSource;)V");
        if (method == null) {
            // 26.3 passes the chunk's BlockPos instead of the biome holder; the ChunkPos stays
            // local 2, so the telemetry code below is unchanged.
            method = find(
                    node,
                    "spawnMobsForChunkGeneration",
                    "(Lnet/minecraft/world/level/ServerLevelAccessor;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/ChunkPos;"
                            + "Lnet/minecraft/util/RandomSource;)V");
        }
        int tokenLocal = method.maxLocals++;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        int throwableLocal = method.maxLocals++;
        InsnList entry = new InsnList();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/world/level/ServerLevelAccessor",
                "getLevel",
                "()Lnet/minecraft/server/level/ServerLevel;",
                true));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 2));
        if (isModernProfile(minecraftVersion)) {
            entry.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/level/ChunkPos", "x", "()I", false));
        } else {
            entry.add(new FieldInsnNode(Opcodes.GETFIELD,
                    "net/minecraft/world/level/ChunkPos", "x", "I"));
        }
        entry.add(new VarInsnNode(Opcodes.ALOAD, 2));
        if (isModernProfile(minecraftVersion)) {
            entry.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/level/ChunkPos", "z", "()I", false));
        } else {
            entry.add(new FieldInsnNode(Opcodes.GETFIELD,
                    "net/minecraft/world/level/ChunkPos", "z", "I"));
        }
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGenerationSpawnTelemetry", "begin",
                "(Ljava/lang/Object;II)I", false));
        entry.add(new VarInsnNode(Opcodes.ISTORE, tokenLocal));
        entry.add(start);
        method.instructions.insertBefore(method.instructions.getFirst(), entry);
        int added = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("net/minecraft/world/level/ServerLevelAccessor")
                    && call.name.equals("addFreshEntityWithPassengers")
                    && call.desc.equals("(Lnet/minecraft/world/entity/Entity;)V")) {
                method.instructions.insert(call, browserGenerationSpawnEntityAddedCode(tokenLocal));
                added++;
            }
        }
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                method.instructions.insertBefore(instruction,
                        browserGenerationSpawnFinishCode("complete", tokenLocal));
                returns++;
            }
        }
        if (returns == 0 || added == 0) {
            throw new IllegalStateException("NaturalSpawner telemetry anchors missing: returns="
                    + returns + ", entityAdds=" + added);
        }
        InsnList cleanup = new InsnList();
        cleanup.add(end);
        cleanup.add(handler);
        cleanup.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        cleanup.add(browserGenerationSpawnFinishCode("failed", tokenLocal));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        cleanup.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(cleanup);
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        writeComputeFrames(node, output);
        System.out.println("Instrumented NaturalSpawner generation spawn telemetry ("
                + minecraftVersion + "): returns=" + returns + ", entityAdds=" + added);
    }

    private static InsnList notifyLoadingPacketsReceived(String event) {
        InsnList code = new InsnList();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientPacketListener",
                "levelLoadTracker",
                "Lnet/minecraft/client/multiplayer/LevelLoadTracker;"));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(minecraftEvent(event));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientPacketListener",
                "levelLoadTracker",
                "Lnet/minecraft/client/multiplayer/LevelLoadTracker;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/LevelLoadTracker",
                "loadingPacketsReceived",
                "()V",
                false));
        code.add(done);
        return code;
    }

    private static InsnList closeLevelLoadingScreenIfPresent() {
        InsnList code = new InsnList();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/multiplayer/ClientPacketListener",
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
                "net/minecraft/client/multiplayer/ClientPacketListener",
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$setScreen",
                "(Lnet/minecraft/client/gui/screens/Screen;)V",
                false));
        code.add(minecraftEvent("client.levelReady.closeLoadingScreen"));
        code.add(done);
        return code;
    }
    public static void patchTextureAnimationCapture(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/renderer/texture/SpriteContents$AnimationState";
        Path output = root.resolve(owner + ".class");
        ClassNode animation = read(jar, owner + ".class");
        for (String field : new String[] {"frame", "subFrame", "isDirty"}) {
            String descriptor = field.equals("isDirty") ? "Z" : "I";
            if (animation.fields.stream().noneMatch(candidate ->
                    candidate.name.equals(field) && candidate.desc.equals(descriptor))) {
                throw new IOException("Texture animation capture field changed: " + field);
            }
        }
        MethodNode vanillaTick = find(animation, "tick", "()V");
        vanillaTick.name = "gaius$tickUnpinned";
        vanillaTick.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        MethodNode tick = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        InsnList code = tick.instructions;
        LabelNode unpinned = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode upload = new LabelNode();
        LabelNode storeDirty = new LabelNode();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/BrowserOpenGL",
                "captureAnimationTick", "()I", false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFLT, unpinned));
        int previousLocal = 2;
        for (String field : new String[] {"frame", "subFrame"}) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "I"));
            code.add(new VarInsnNode(Opcodes.ISTORE, previousLocal++));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, "I"));
        }
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner,
                vanillaTick.name, "()V", false));
        code.add(new IincInsnNode(1, -1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        previousLocal = 2;
        for (String field : new String[] {"frame", "subFrame"}) {
            code.add(new VarInsnNode(Opcodes.ILOAD, previousLocal++));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "I"));
            code.add(new JumpInsnNode(Opcodes.IF_ICMPNE, upload));
        }
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new JumpInsnNode(Opcodes.GOTO, storeDirty));
        code.add(upload);
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(storeDirty);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.SWAP));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "isDirty", "Z"));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(unpinned);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner,
                vanillaTick.name, "()V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        animation.methods.add(tick);
        writeComputeFrames(animation, output);
    }

}
