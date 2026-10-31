package dev.gaius.tools.patches.render;

import dev.gaius.tools.ModernSymbols;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The render domain of the Minecraft263BrowserPatcher step, the tail of the modern chain for the
 * renderpearl render seam ({@code patches/render}): the terrain draw path, work
 * package P5.
 *
 * <p>The terrain pipeline and frame-loop patches themselves stay where their 26.2 versions live
 * (MinecraftClientPatcher, MinecraftChunkDrawTelemetryPatcher, Minecraft262BrowserPatcher), each
 * with an in-place 26.3 branch, because later steps depend on them (for example the UberGpuBuffer
 * node cleanup needs MinecraftClientPatcher's upload-budget hook). This class holds what only the
 * renderpearl generation needs:
 * <ul>
 *   <li>{@link #forceSeparateTerrainDraws}: validates the 26.3 multi-draw-indirect terrain gate
 *       (prepareChunkRendersIndirect / ChunkSectionsToRender$DrawIndirect) while preserving the
 *       device capability chosen by the renderpearl backend.</li>
 *   <li>{@link #verifyTerrainChain}: checks, on the jar the chain produced, that every terrain
 *       hook that only works as a pair is present on both sides (update requeue and consumption,
 *       deferred pick and its refresh, early and late occlusion updates, pool recycle order,
 *       upload budget and heap cleanup), so that no combination of skipped or partially applied
 *       patches reaches the browser. The chunk-draw telemetry step checks its own pair. It also
 *       checks the v0.4.0 terrain path: 4-vertex aligned vertex heaps, the upload byte budget,
 *       the neighbor readiness gate and every TerrainBatchPatches hook (batched draw
 *       submission, section capture, RenderSection compile state, mesh epochs).</li>
 * </ul>
 *
 * <p>{@code Minecraft263BrowserPatcher} registers these patches under their historical ids
 * ({@code TerrainPatches263.<method>}) after the render and input groups of the step; their
 * profiles come from the specs. {@code jar} is the chain's current client jar, {@code root} the
 * directory whose classes are merged back into it; a class an earlier patch of the step already
 * wrote to {@code root} in this run is read from there.
 */
public final class RenderpearlTerrainPatches {
    static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
    static final String LEVEL_EXTRACTOR = "net/minecraft/client/renderer/extract/LevelExtractor";
    static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String GPU_BUFFER_POOL =
            "net/minecraft/client/renderer/StagedVertexBuffer$GpuBufferPool";
    static final String UBER_GPU_BUFFER = "com/mojang/blaze3d/vertex/UberGpuBuffer";
    static final String SECTION_AUDIT = "dev/gaius/browser/BrowserSectionAudit";
    static final String RENDER_SCHEDULER = "dev/gaius/browser/BrowserRenderScheduler";
    static final String TARGETING = "dev/gaius/browser/BrowserTargeting";
    static final String DRAW_TELEMETRY = "dev/gaius/browser/BrowserChunkDrawTelemetry";
    static final String POOL_CACHE = "dev/gaius/browser/BrowserGpuBufferPoolCache";
    static final String TERRAIN_BATCH = "dev/gaius/browser/render/BrowserTerrainBatch";
    static final String NEIGHBOR_READINESS = "dev/gaius/browser/render/BrowserNeighborReadiness";
    static final String DRAW_GROUP_CACHE_FIELD = "gaius$drawGroupCache";
    static final String INT_DRAW_GROUP_MAP = "Lit/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap;";
    static final String SECTION_RENDER_DISPATCHER =
            "net/minecraft/client/renderer/chunk/SectionRenderDispatcher";
    static final String SECTION_UPDATE_TRACKER = "net/minecraft/client/SectionUpdateTracker";
    static final String CAMERA_STATE = "Lnet/minecraft/client/renderer/state/level/CameraRenderState;";
    static final String LEVEL_RENDERER_RENDER =
            "(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Z" + CAMERA_STATE
                    + "Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V";

    private RenderpearlTerrainPatches() {
    }

    /** Validates the multi-draw-indirect gate without changing the vanilla class. */
    public static void forceSeparateTerrainDraws(String jar, Path root) throws IOException {
        ClassNode node = readCurrent(jar, root, LEVEL_RENDERER);
        List<FieldInsnNode> stores = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(LEVEL_RENDERER)
                        && field.name.equals("multiDrawIndirectAvailable")) {
                    if (!method.name.equals("<init>")) {
                        throw new IllegalStateException(
                                "LevelRenderer.multiDrawIndirectAvailable is written outside <init>");
                    }
                    stores.add(field);
                }
            }
        }
        MethodNode render = find(node, "render", LEVEL_RENDERER_RENDER);
        int availabilityReads = 0;
        int indirectPrepares = 0;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(LEVEL_RENDERER)
                    && field.name.equals("multiDrawIndirectAvailable")) {
                availabilityReads++;
            } else if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(LEVEL_RENDERER)
                    && call.name.equals("prepareChunkRendersIndirect")) {
                indirectPrepares++;
            }
        }
        if (stores.size() != 1 || availabilityReads != 1 || indirectPrepares != 1) {
            throw new IllegalStateException("LevelRenderer multi-draw-indirect gate changed: stores="
                    + stores.size() + " renderReads=" + availabilityReads
                    + " indirectPrepares=" + indirectPrepares);
        }
        System.out.println("Preserved 26.3 LevelRenderer.multiDrawIndirectAvailable capability gate");
    }

    /**
     * Reuses the temporary draw-group lookup table used by
     * {@code extractSectionDrawGroups}.  The table is cleared at the same point where the
     * vanilla method allocates it; all groups and draw records are still created and appended
     * in the original visible-section/layer order.  Only the hash-table object and its backing
     * arrays survive across frames, so this cannot change the submitted vertices or ordering.
     */
    public static void patchReusableDrawGroupCache(String jar, Path root) throws IOException {
        ClassNode node = readCurrent(jar, root, LEVEL_RENDERER);
        MethodNode extract = find(node, "extractSectionDrawGroups",
                "(ZLjava/util/List;Ljava/util/Map;)I");
        FieldNode cache = null;
        for (FieldNode field : node.fields) {
            if (field.name.equals(DRAW_GROUP_CACHE_FIELD)) {
                if (!field.desc.equals(INT_DRAW_GROUP_MAP)) {
                    throw new IllegalStateException("LevelRenderer draw-group cache field descriptor changed");
                }
                cache = field;
                break;
            }
        }
        if (cache == null) {
            cache = new FieldNode(Opcodes.ACC_PRIVATE,
                    DRAW_GROUP_CACHE_FIELD, INT_DRAW_GROUP_MAP, null, null);
            node.fields.add(cache);
        }

        MethodNode constructor = find(node, "<init>", null);
        AbstractInsnNode returnNode = null;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (returnNode != null) {
                    throw new IllegalStateException("LevelRenderer constructor has multiple returns");
                }
                returnNode = instruction;
            }
        }
        if (returnNode == null) {
            throw new IllegalStateException("LevelRenderer constructor return was not found");
        }
        boolean constructorInitialized = false;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(LEVEL_RENDERER)
                    && field.name.equals(DRAW_GROUP_CACHE_FIELD)
                    && field.desc.equals(INT_DRAW_GROUP_MAP)) {
                constructorInitialized = true;
                break;
            }
        }
        if (!constructorInitialized) {
            InsnList init = new InsnList();
            init.add(new VarInsnNode(Opcodes.ALOAD, 0));
            init.add(new TypeInsnNode(Opcodes.NEW,
                    "it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap"));
            init.add(new InsnNode(Opcodes.DUP));
            init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap", "<init>", "()V", false));
            init.add(new FieldInsnNode(Opcodes.PUTFIELD, LEVEL_RENDERER,
                    DRAW_GROUP_CACHE_FIELD, INT_DRAW_GROUP_MAP));
            constructor.instructions.insertBefore(returnNode, init);
        }

        TypeInsnNode allocation = null;
        MethodInsnNode allocationInit = null;
        int allocations = 0;
        for (AbstractInsnNode instruction : extract.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap")) {
                allocation = type;
                allocations++;
            } else if (allocation != null && instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap")
                    && call.name.equals("<init>") && call.desc.equals("()V")) {
                allocationInit = call;
                break;
            }
        }
        if (allocations != 1 || allocation == null || allocationInit == null) {
            throw new IllegalStateException("LevelRenderer.extractSectionDrawGroups cache allocation changed: "
                    + allocations);
        }
        AbstractInsnNode dup = allocation.getNext();
        if (!(dup instanceof InsnNode) || dup.getOpcode() != Opcodes.DUP) {
            throw new IllegalStateException("LevelRenderer draw-group cache NEW/DUP shape changed");
        }
        AbstractInsnNode store = allocationInit.getNext();
        if (!(store instanceof VarInsnNode local) || local.getOpcode() != Opcodes.ASTORE) {
            throw new IllegalStateException("LevelRenderer draw-group cache local store changed");
        }
        InsnList reuse = new InsnList();
        reuse.add(new VarInsnNode(Opcodes.ALOAD, 0));
        reuse.add(new FieldInsnNode(Opcodes.GETFIELD, LEVEL_RENDERER,
                DRAW_GROUP_CACHE_FIELD, INT_DRAW_GROUP_MAP));
        reuse.add(new InsnNode(Opcodes.DUP));
        reuse.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap", "clear", "()V", false));
        reuse.add(new VarInsnNode(Opcodes.ASTORE, local.var));
        extract.instructions.insertBefore(allocation, reuse);
        extract.instructions.remove(allocation);
        extract.instructions.remove(dup);
        extract.instructions.remove(allocationInit);
        extract.instructions.remove(store);
        write(node, root.resolve(LEVEL_RENDERER + ".class"));
        System.out.println("Patched 26.3 reusable LevelRenderer draw-group cache");
    }

    /**
     * Fails the build unless the chain's terrain hooks are present on both sides of every pair.
     * Reads each class as the rest of the chain (and earlier 26.3 domains) left it.
     */
    public static void verifyTerrainChain(String jar, Path root, ModernSymbols symbols)
            throws IOException {
        List<String> problems = new ArrayList<>();

        ClassNode extractor = readCurrent(jar, root, LEVEL_EXTRACTOR);
        MethodNode extract = find(extractor, "extract",
                "(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V");
        expect(problems, "LevelExtractor.extract requeueUnconsumed",
                calls(extract, SECTION_AUDIT, "requeueUnconsumed"), 1);
        expect(problems, "LevelExtractor.extract canScheduleSection",
                calls(extract, RENDER_SCHEDULER, "canScheduleSection"), 1);
        requireAtLeast(problems, "LevelExtractor.extract afterExtract",
                calls(extract, SECTION_AUDIT, "afterExtract"), 1);

        ClassNode renderer = readCurrent(jar, root, LEVEL_RENDERER);
        MethodNode compile = find(renderer, "compileSections", "(" + CAMERA_STATE + ")V");
        int consumptions = 0;
        for (AbstractInsnNode instruction : compile.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field
                    && field.name.equals("sectionUpdateRenderStates")
                    && next(field) instanceof MethodInsnNode clear
                    && clear.owner.equals("java/util/List")
                    && clear.name.equals("clear")) {
                consumptions++;
            }
        }
        expect(problems, "LevelRenderer.compileSections update consumption", consumptions, 1);
        expect(problems, "LevelRenderer.compileSections compileSync branch",
                calls(compile, RENDER_SCHEDULER, "canScheduleSection"), 0);
        MethodNode render = find(renderer, "render", LEVEL_RENDERER_RENDER);
        expect(problems, "LevelRenderer.render occlusion updates (early + late)",
                calls(render, "net/minecraft/client/renderer/SectionOcclusionGraph", "update"), 2);
        ClassNode graph = readCurrent(jar, root, "net/minecraft/client/renderer/SectionOcclusionGraph");
        MethodNode graphUpdate = find(graph, "update",
                "(" + CAMERA_STATE + "ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V");
        expect(problems, "SectionOcclusionGraph.update beforeOcclusionUpdate",
                calls(graphUpdate, SECTION_AUDIT, "beforeOcclusionUpdate"), 1);
        expect(problems, "LevelRenderer.render visible-section refresh clear",
                calls(render, LEVEL_RENDERER, "clearVisibleSections"), 1);
        // MinecraftClientPatcher's hooks, and the registration/draw/commit hooks of the
        // chunk-draw telemetry step (MinecraftChunkDrawTelemetryPatcher, 26.3 profile shape).
        MethodNode extractGroups = find(renderer, "extractSectionDrawGroups",
                "(ZLjava/util/List;Ljava/util/Map;)I");
        expect(problems, "extractSectionDrawGroups shared layer array",
                calls(extractGroups, "dev/gaius/browser/BrowserChunkSectionLayers", "values"), 1);
        expect(problems, "extractSectionDrawGroups chunk-draw telemetry beginPrepare",
                calls(extractGroups, DRAW_TELEMETRY, "beginPrepare"), 1);
        expect(problems, "extractSectionDrawGroups chunk-draw telemetry registerSection",
                calls(extractGroups, DRAW_TELEMETRY, "registerSection"), 1);
        MethodNode prepare = find(renderer, "prepareChunkRenders",
                "(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;");
        expect(problems, "prepareChunkRenders prepare statistics",
                calls(prepare, DRAW_TELEMETRY, "recordPrepareStats"), 1);
        MethodNode uniforms = find(renderer, "lambda$prepareChunkRenders$2",
                "(I[Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
                        + "Lcom/mojang/renderpearl/api/commands/RenderPass$UniformUploader;)V");
        expect(problems, "prepareChunkRenders uniform lambda armUniformIndex",
                calls(uniforms, DRAW_TELEMETRY, "armUniformIndex"), 1);
        ClassNode frontendPass = readCurrent(jar, root, "com/mojang/renderpearl/frontend/FrontendRenderPass");
        MethodNode drawMultiple = find(frontendPass, "drawMultipleIndexed",
                "(Ljava/util/Collection;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"
                        + "Lcom/mojang/renderpearl/api/pipeline/IndexType;Ljava/util/Collection;"
                        + "Ljava/lang/Object;)V");
        expect(problems, "FrontendRenderPass.drawMultipleIndexed chunk-draw telemetry beginDraw",
                calls(drawMultiple, DRAW_TELEMETRY, "beginDraw"), 1);
        expect(problems, "FrontendRenderPass.drawMultipleIndexed chunk-draw telemetry commit",
                calls(drawMultiple, DRAW_TELEMETRY, "commitSuccessfulDraw"), 1);

        ClassNode minecraft = readCurrent(jar, root, MINECRAFT);
        MethodNode renderFrame = find(minecraft, "renderFrame", "(Z)V");
        ClassNode gameRenderer = readCurrent(jar, root, GAME_RENDERER);
        MethodNode gameExtract = find(gameRenderer, "extract",
                "(Lnet/minecraft/client/DeltaTracker;Z)V");
        expect(problems, "Minecraft.renderFrame deferred pick",
                calls(renderFrame, TARGETING, "deferFramePick"), 1);
        expect(problems, "Minecraft.renderFrame vanilla pick",
                calls(renderFrame, MINECRAFT, "pick"), 0);
        expect(problems, "GameRenderer.extract pick refresh",
                calls(gameExtract, TARGETING, "refreshFramePick"), 1);
        expect(problems, "GameRenderer.render frame budget",
                calls(find(gameRenderer, "render", "()V"), RENDER_SCHEDULER, "beginFrame"), 1);

        String device = symbols.renderType("com/mojang/blaze3d/systems/GpuDevice");
        ClassNode pool = readCurrent(jar, root, GPU_BUFFER_POOL);
        MethodNode endFrame = find(pool, "endFrame", "(L" + device + ";)V");
        int helperEnd = index(endFrame, POOL_CACHE, "endFrame");
        int recycle = index(endFrame, GPU_BUFFER_POOL, "tryRecycleBuffers");
        if (helperEnd < 0 || recycle < 0 || helperEnd > recycle) {
            problems.add("GpuBufferPool.endFrame must advance the cache frame before the recycle"
                    + " sweep (endFrame@" + helperEnd + ", tryRecycleBuffers@" + recycle + ")");
        }
        expect(problems, "GpuBufferPool.acquire recycle sweep",
                calls(find(pool, "acquire", null), GPU_BUFFER_POOL, "tryRecycleBuffers"), 0);

        String gpuBuffer = symbols.renderType("com/mojang/blaze3d/buffers/GpuBuffer");
        ClassNode uber = readCurrent(jar, root, UBER_GPU_BUFFER);
        MethodNode upload = find(uber, "uploadStagedAllocations",
                "(L" + device + ";Lcom/mojang/blaze3d/vertex/StagingBuffer$Uploader;)Z");
        expect(problems, "UberGpuBuffer upload budget hook",
                calls(upload, RENDER_SCHEDULER, "finishUploadBuffer"), 1);
        expect(problems, "UberGpuBuffer node cleanup",
                calls(upload, RENDER_SCHEDULER, "finishUberNodeCleanup"), 1);
        expect(problems, "UberGpuBuffer upload byte budget",
                calls(upload, RENDER_SCHEDULER, "noteUploadBytes"), 1);
        int skippedCheck = -1;
        int byteCount = -1;
        int allocate = -1;
        int position = 0;
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call) {
                if (skippedCheck < 0 && call.name.equals("contains")
                        && call.owner.equals("it/unimi/dsi/fastutil/objects/ObjectOpenHashSet")) {
                    skippedCheck = position;
                } else if (call.name.equals("noteUploadBytes")
                        && call.owner.equals(RENDER_SCHEDULER)) {
                    byteCount = position;
                } else if (allocate < 0 && call.name.equals("allocate")
                        && call.owner.equals("com/mojang/blaze3d/vertex/TlsfAllocator")) {
                    allocate = position;
                }
            }
            position++;
        }
        if (skippedCheck < 0 || byteCount < skippedCheck || allocate < byteCount) {
            problems.add("UberGpuBuffer upload byte budget must count after the skipped entry"
                    + " check (contains@" + skippedCheck + ", noteUploadBytes@" + byteCount
                    + ", allocate@" + allocate + ")");
        }

        ClassNode dispatcher = readCurrent(jar, root, SECTION_RENDER_DISPATCHER);
        MethodNode heapFactory = find(dispatcher, "lambda$new$0",
                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)"
                        + "Lnet/minecraft/client/renderer/chunk/"
                        + "SectionRenderDispatcher$SectionUberBuffers;");
        expect(problems, "section vertex heap 4-vertex alignment",
                calls(heapFactory, TERRAIN_BATCH, "vertexHeapAlignment"), 1);
        ClassNode tracker = readCurrent(jar, root, SECTION_UPDATE_TRACKER);
        expect(problems, "SectionUpdateTracker.hasAllNeighbors readiness gate",
                calls(find(tracker, "hasAllNeighbors",
                        "(Lnet/minecraft/client/multiplayer/ClientLevel;J)Z"),
                        NEIGHBOR_READINESS, "ready"), 1);
        problems.addAll(TerrainBatchPatches.verify(jar, root, true));
        for (AbstractInsnNode instruction : upload.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(gpuBuffer)
                    && (call.getOpcode() != Opcodes.INVOKEINTERFACE || !call.itf)) {
                problems.add("UberGpuBuffer calls the " + gpuBuffer + " interface with opcode "
                        + call.getOpcode());
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("26.3 terrain chain is incomplete:\n  "
                    + String.join("\n  ", problems));
        }
        System.out.println("Verified 26.3 terrain chain: requeue/consumption, occlusion refresh, "
                + "prepare statistics, chunk-draw telemetry, frame targeting, pool recycle order, "
                + "heap cleanup, aligned heaps, upload bytes, neighbor gate, batched draws");
    }

    private static void expect(List<String> problems, String what, int actual, int expected) {
        if (actual != expected) {
            problems.add(what + ": expected " + expected + ", found " + actual);
        }
    }

    private static void requireAtLeast(List<String> problems, String what, int actual, int min) {
        if (actual < min) {
            problems.add(what + ": expected at least " + min + ", found " + actual);
        }
    }

    static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static int index(MethodNode method, String owner, String name) {
        int position = 0;
        int found = -1;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)) {
                if (found >= 0) {
                    return -1;
                }
                found = position;
            }
            position++;
        }
        return found;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    /** One method by name and descriptor; a {@code null} descriptor requires a unique name. */
    static MethodNode find(ClassNode node, String name, String descriptor) {
        MethodNode match = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && (descriptor == null || method.desc.equals(descriptor))) {
                if (match != null) {
                    throw new IllegalStateException(node.name + "." + name + " is ambiguous");
                }
                match = method;
            }
        }
        if (match == null) {
            throw new IllegalStateException(node.name + "." + name
                    + (descriptor == null ? "" : descriptor) + " was not found");
        }
        return match;
    }

    /** The class as the chain left it: {@code root} when a patcher wrote it, else the jar. */
    static ClassNode readCurrent(String jar, Path root, String owner) throws IOException {
        Path written = root.resolve(owner + ".class");
        byte[] bytes;
        if (Files.isRegularFile(written)) {
            bytes = Files.readAllBytes(written);
        } else {
            try (ZipFile zip = new ZipFile(jar)) {
                ZipEntry entry = zip.getEntry(owner + ".class");
                if (entry == null) {
                    throw new IllegalStateException(owner + ".class is not in " + jar);
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
