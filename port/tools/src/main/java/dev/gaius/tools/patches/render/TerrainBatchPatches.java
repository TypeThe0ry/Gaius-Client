package dev.gaius.tools.patches.render;

import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.VersionProfile;
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
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Batched terrain draws and asynchronous mesh bookkeeping for Minecraft 26.2 and 26.3
 * (v0.4.0, render workstream). Runs inside Minecraft262BrowserPatcher, which serves both
 * modern profiles; 1.21.11 never reaches it and any other profile is skipped with a log line.
 *
 * <ul>
 *   <li>{@link #patchPrepareChunkRenders}: LevelRenderer.prepareChunkRenders builds one
 *       ChunkSection uniform callback per draw through an invokedynamic lambda; it now gets a
 *       shared BrowserTerrainBatchGlue.ChunkUniform (same upload, plus the section index the
 *       batch needs). The section records are captured before writeChunkSections and bound to
 *       the slice array it returns.</li>
 *   <li>{@link #patchDrawSubmission}: the single RenderPass.drawMultipleIndexed call of the
 *       chunk layer renderer (26.3 ChunkSectionsToRender$DrawSeparate.render, 26.2
 *       ChunkSectionsToRender.renderGroup) goes to BrowserTerrainBatchGlue.drawMultipleIndexed,
 *       which draws runs of sections with one WEBGL_multi_draw call and falls back to the
 *       vanilla call for anything it cannot batch.</li>
 *   <li>{@link #patchRenderSectionCompileState}: adds the public accessor
 *       RenderSection.gaius$hasPendingCompile() (lastCompileTask neither completed nor
 *       cancelled) for BrowserSectionAudit.</li>
 *   <li>{@link #patchMeshEpochs}: LevelRenderer.resetLevelRenderData and LevelExtractor.setLevel
 *       bump BrowserMeshInstallQueue's level epoch, LevelExtractor.onResourceManagerReload its
 *       resource epoch, so asynchronous mesh results of a previous level or resource set are
 *       discarded instead of installed.</li>
 * </ul>
 *
 * <p>Every patch requires its exact bytecode shape and throws when it is missing. Runtime
 * switches (URL gaiusTerrainBatch=0 and friends) keep the vanilla draw path selectable without
 * rebuilding; with batching off, the glue forwards every call unchanged.</p>
 */
public final class TerrainBatchPatches {
    static final String GLUE = "dev/gaius/browser/render/BrowserTerrainBatchGlue";
    static final String MESH_QUEUE = "dev/gaius/browser/render/BrowserMeshInstallQueue";
    static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
    static final String LEVEL_EXTRACTOR = "net/minecraft/client/renderer/extract/LevelExtractor";
    static final String SECTIONS_TO_RENDER =
            "net/minecraft/client/renderer/chunk/ChunkSectionsToRender";
    static final String DRAW_SEPARATE = SECTIONS_TO_RENDER + "$DrawSeparate";
    static final String RENDER_SECTION =
            "net/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection";
    static final String SECTION_TASK = RENDER_SECTION + "$SectionTask";
    static final String COMPILE_TASK = RENDER_SECTION + "$CompileTask";
    static final String PENDING_COMPILE_ACCESSOR = "gaius$hasPendingCompile";

    private TerrainBatchPatches() {
    }

    /** API types of one render backend generation. */
    record Api(
            String renderPass,
            boolean renderPassInterface,
            String gpuBuffer,
            String gpuBufferSlice,
            String indexType,
            String dynamicData,
            String prepareDescriptor,
            String drawOwner,
            String drawMethod) {
        static Api forProfile(boolean renderpearl) {
            if (renderpearl) {
                return new Api(
                        "com/mojang/renderpearl/api/commands/RenderPass",
                        true,
                        "com/mojang/renderpearl/api/buffers/GpuBuffer",
                        "com/mojang/renderpearl/api/buffers/GpuBufferSlice",
                        "com/mojang/renderpearl/api/pipeline/IndexType",
                        "net/minecraft/client/renderer/DynamicGpuData",
                        "(Lorg/joml/Matrix4fc;Z)L" + SECTIONS_TO_RENDER + ";",
                        DRAW_SEPARATE,
                        "render");
            }
            return new Api(
                    "com/mojang/blaze3d/systems/RenderPass",
                    false,
                    "com/mojang/blaze3d/buffers/GpuBuffer",
                    "com/mojang/blaze3d/buffers/GpuBufferSlice",
                    "com/mojang/blaze3d/IndexType",
                    "net/minecraft/client/renderer/DynamicUniforms",
                    "(Lorg/joml/Matrix4fc;)L" + SECTIONS_TO_RENDER + ";",
                    SECTIONS_TO_RENDER,
                    "renderGroup");
        }

        String sliceArray() {
            return "[L" + gpuBufferSlice + ";";
        }

        String sectionInfo() {
            return dynamicData + "$ChunkSectionInfo";
        }

        String drawMultipleDescriptor() {
            return "(Ljava/util/Collection;L" + gpuBuffer + ";L" + indexType
                    + ";Ljava/util/Collection;Ljava/lang/Object;)V";
        }

        String glueDrawDescriptor() {
            return "(L" + renderPass + ";Ljava/util/Collection;L" + gpuBuffer + ";L"
                    + indexType + ";Ljava/util/Collection;" + sliceArray() + ")V";
        }
    }

    /**
     * Entry point from Minecraft262BrowserPatcher. {@code profile} is the build profile; terrain
     * batching covers its modern patch set, and the render API generation probed from the jar
     * must agree with the profile's render seam ({@link VersionProfile#renderpearl()}).
     */
    public static void apply(String jar, Path root, String profile) throws IOException {
        VersionProfile versionProfile = VersionProfile.forId(profile);
        if (!versionProfile.modernPatchSet()) {
            throw new IllegalStateException("Terrain batching requires the modern patch set");
        }
        boolean renderpearl = ModernSymbols.cached(jar).renderpearl();
        if (renderpearl != versionProfile.renderpearl()) {
            throw new IllegalStateException("TerrainBatchPatches: profile " + profile + " (renderSeam "
                    + versionProfile.renderSeam() + ") disagrees with the probed render API (renderpearl="
                    + renderpearl + ")");
        }
        Api api = Api.forProfile(renderpearl);
        patchPrepareChunkRenders(jar, root, api);
        patchDrawSubmission(jar, root, api);
        patchRenderSectionCompileState(jar, root);
        patchMeshEpochs(jar, root);
        System.out.println("TerrainBatchPatches: batched terrain draws, section compile state "
                + "and mesh epochs patched for " + profile);
    }

    static void patchPrepareChunkRenders(String jar, Path root, Api api) throws IOException {
        ClassNode node = readCurrent(jar, root, LEVEL_RENDERER);
        MethodNode prepare = find(node, "prepareChunkRenders", api.prepareDescriptor());
        InvokeDynamicInsnNode uniformFactory = null;
        MethodInsnNode writeSections = null;
        TypeInsnNode sectionArray = null;
        for (AbstractInsnNode instruction : prepare.instructions.toArray()) {
            if (instruction instanceof InvokeDynamicInsnNode indy
                    && indy.name.equals("accept")
                    && indy.desc.equals("(I)Ljava/util/function/BiConsumer;")) {
                if (uniformFactory != null) {
                    throw new IllegalStateException(
                            "prepareChunkRenders has more than one ChunkSection uniform lambda");
                }
                uniformFactory = indy;
            } else if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(api.dynamicData())
                    && call.name.equals("writeChunkSections")
                    && call.desc.equals("([L" + api.sectionInfo() + ";)" + api.sliceArray())) {
                if (writeSections != null) {
                    throw new IllegalStateException(
                            "prepareChunkRenders writes chunk sections more than once");
                }
                writeSections = call;
            } else if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.ANEWARRAY
                    && type.desc.equals(api.sectionInfo())) {
                if (sectionArray != null) {
                    throw new IllegalStateException(
                            "prepareChunkRenders builds more than one ChunkSectionInfo array");
                }
                sectionArray = type;
            }
        }
        if (uniformFactory == null || writeSections == null || sectionArray == null) {
            throw new IllegalStateException("prepareChunkRenders shape changed: uniformLambda="
                    + (uniformFactory != null) + " writeChunkSections=" + (writeSections != null)
                    + " sectionArray=" + (sectionArray != null));
        }
        AbstractInsnNode size = previousOpcode(sectionArray);
        AbstractInsnNode listLoad = size == null ? null : previousOpcode(size);
        if (size == null || size.getOpcode() != Opcodes.ICONST_0
                || !(listLoad instanceof VarInsnNode list)
                || list.getOpcode() != Opcodes.ALOAD) {
            throw new IllegalStateException(
                    "prepareChunkRenders section list no longer feeds List.toArray(new T[0])");
        }
        prepare.instructions.set(uniformFactory, new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                GLUE,
                "chunkUniform",
                "(I)Ljava/util/function/BiConsumer;",
                false));
        InsnList capture = new InsnList();
        capture.add(new VarInsnNode(Opcodes.ALOAD, list.var));
        capture.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, GLUE, "captureSections", "(Ljava/util/List;)V", false));
        prepare.instructions.insertBefore(listLoad, capture);
        prepare.instructions.insert(writeSections, new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                GLUE,
                "bindSections",
                "(" + api.sliceArray() + ")" + api.sliceArray(),
                false));
        prepare.maxStack = Math.max(prepare.maxStack, 2) + 1;
        write(node, root.resolve(LEVEL_RENDERER + ".class"));
        System.out.println("Patched LevelRenderer.prepareChunkRenders for batched terrain draws");
    }

    static void patchDrawSubmission(String jar, Path root, Api api) throws IOException {
        ClassNode node = readCurrent(jar, root, api.drawOwner());
        List<MethodInsnNode> calls = new ArrayList<>();
        MethodNode owner = null;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(api.renderPass())
                        && call.name.equals("drawMultipleIndexed")
                        && call.desc.equals(api.drawMultipleDescriptor())) {
                    calls.add(call);
                    owner = method;
                }
            }
        }
        int expectedOpcode = api.renderPassInterface()
                ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        if (calls.size() != 1 || owner == null || !owner.name.equals(api.drawMethod())
                || calls.get(0).getOpcode() != expectedOpcode) {
            throw new IllegalStateException(api.drawOwner()
                    + " chunk layer draw submission changed: calls=" + calls.size()
                    + " method=" + (owner == null ? "-" : owner.name));
        }
        MethodInsnNode call = calls.get(0);
        owner.instructions.insertBefore(call, new TypeInsnNode(Opcodes.CHECKCAST, api.sliceArray()));
        owner.instructions.set(call, new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                GLUE,
                "drawMultipleIndexed",
                api.glueDrawDescriptor(),
                false));
        write(node, root.resolve(api.drawOwner() + ".class"));
        System.out.println("Routed " + api.drawOwner() + "." + api.drawMethod()
                + " through the batched terrain drawer");
    }

    static void patchRenderSectionCompileState(String jar, Path root) throws IOException {
        ClassNode node = readCurrent(jar, root, RENDER_SECTION);
        String compileTaskDescriptor = "L" + COMPILE_TASK + ";";
        if (findField(node, "lastCompileTask", compileTaskDescriptor) == null) {
            throw new IllegalStateException("RenderSection.lastCompileTask changed");
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals(PENDING_COMPILE_ACCESSOR)) {
                throw new IllegalStateException(
                        "RenderSection already has " + PENDING_COMPILE_ACCESSOR);
            }
        }
        ClassNode task = readCurrent(jar, root, SECTION_TASK);
        if (findField(task, "isCompleted", "Ljava/util/concurrent/atomic/AtomicBoolean;") == null
                || findField(task, "isCancelled", "Ljava/util/concurrent/atomic/AtomicBoolean;")
                        == null) {
            throw new IllegalStateException("SectionTask completion flags changed");
        }
        MethodNode accessor = new MethodNode(
                Opcodes.ACC_PUBLIC, PENDING_COMPILE_ACCESSOR, "()Z", null, null);
        LabelNode notPending = new LabelNode();
        InsnList code = accessor.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, RENDER_SECTION, "lastCompileTask", compileTaskDescriptor));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, notPending));
        for (String flag : new String[] {"isCompleted", "isCancelled"}) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, SECTION_TASK, flag,
                    "Ljava/util/concurrent/atomic/AtomicBoolean;"));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/util/concurrent/atomic/AtomicBoolean", "get", "()Z", false));
            code.add(new JumpInsnNode(Opcodes.IFNE, notPending));
        }
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(notPending);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        accessor.maxStack = 1;
        accessor.maxLocals = 2;
        node.methods.add(accessor);
        writeComputeFrames(node, root.resolve(RENDER_SECTION + ".class"));
        System.out.println("Added RenderSection." + PENDING_COMPILE_ACCESSOR + "()");
    }

    static void patchMeshEpochs(String jar, Path root) throws IOException {
        ClassNode renderer = readCurrent(jar, root, LEVEL_RENDERER);
        insertAtStart(find(renderer, "resetLevelRenderData", "()V"), "bumpLevelEpoch");
        write(renderer, root.resolve(LEVEL_RENDERER + ".class"));
        ClassNode extractor = readCurrent(jar, root, LEVEL_EXTRACTOR);
        insertAtStart(find(extractor, "setLevel",
                "(Lnet/minecraft/client/multiplayer/ClientLevel;)V"), "bumpLevelEpoch");
        insertAtStart(find(extractor, "onResourceManagerReload",
                "(Lnet/minecraft/server/packs/resources/ResourceManager;)V"), "bumpResourceEpoch");
        write(extractor, root.resolve(LEVEL_EXTRACTOR + ".class"));
        System.out.println("Hooked asynchronous mesh level and resource epochs");
    }

    private static void insertAtStart(MethodNode method, String epochMethod) {
        method.instructions.insert(new MethodInsnNode(
                Opcodes.INVOKESTATIC, MESH_QUEUE, epochMethod, "()V", false));
    }

    /** Verifies, on the jar the chain produced, that every hook of this class is in place. */
    public static List<String> verify(String jar, Path root, boolean renderpearl)
            throws IOException {
        Api api = Api.forProfile(renderpearl);
        List<String> problems = new ArrayList<>();
        ClassNode renderer = readCurrent(jar, root, LEVEL_RENDERER);
        MethodNode prepare = find(renderer, "prepareChunkRenders", api.prepareDescriptor());
        expect(problems, "prepareChunkRenders chunkUniform", calls(prepare, GLUE, "chunkUniform"), 1);
        expect(problems, "prepareChunkRenders captureSections",
                calls(prepare, GLUE, "captureSections"), 1);
        expect(problems, "prepareChunkRenders bindSections", calls(prepare, GLUE, "bindSections"), 1);
        expect(problems, "resetLevelRenderData level epoch",
                calls(find(renderer, "resetLevelRenderData", "()V"), MESH_QUEUE, "bumpLevelEpoch"), 1);
        ClassNode draw = readCurrent(jar, root, api.drawOwner());
        int glueDraws = 0;
        int vanillaDraws = 0;
        for (MethodNode method : draw.methods) {
            glueDraws += calls(method, GLUE, "drawMultipleIndexed");
            vanillaDraws += calls(method, api.renderPass(), "drawMultipleIndexed");
        }
        expect(problems, api.drawOwner() + " batched draw submission", glueDraws, 1);
        expect(problems, api.drawOwner() + " vanilla draw submission", vanillaDraws, 0);
        ClassNode section = readCurrent(jar, root, RENDER_SECTION);
        int accessors = 0;
        for (MethodNode method : section.methods) {
            if (method.name.equals(PENDING_COMPILE_ACCESSOR) && method.desc.equals("()Z")
                    && (method.access & Opcodes.ACC_PUBLIC) != 0) {
                accessors++;
            }
        }
        expect(problems, "RenderSection." + PENDING_COMPILE_ACCESSOR, accessors, 1);
        return problems;
    }

    private static void expect(List<String> problems, String what, int actual, int expected) {
        if (actual != expected) {
            problems.add(what + ": expected " + expected + ", found " + actual);
        }
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static FieldNode findField(ClassNode node, String name, String descriptor) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(descriptor)) {
                return field;
            }
        }
        return null;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        MethodNode match = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                if (match != null) {
                    throw new IllegalStateException(node.name + "." + name + " is ambiguous");
                }
                match = method;
            }
        }
        if (match == null) {
            throw new IllegalStateException(node.name + "." + name + descriptor + " was not found");
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

    private static void writeComputeFrames(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
