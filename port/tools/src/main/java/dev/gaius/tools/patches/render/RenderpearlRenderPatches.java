package dev.gaius.tools.patches.render;

import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.find;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.readClass;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.replace;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.writeClass;

import dev.gaius.tools.ModernSymbols;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
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
 * The render domain of the Minecraft263BrowserPatcher step, the tail of the modern chain for the
 * renderpearl render seam ({@code patches/render}): the renderpearl GL and Vulkan
 * backends on BrowserOpenGL (WebGL2), work package P3 (migration plan D4). The rename-only ports
 * of the 26.2 GL patches stay in the render domain classes of MinecraftClientPatcher and
 * Minecraft262BrowserPatcher; the graphics quality tiers are {@link QualityTierPatches}, the
 * terrain pin and chain check {@link RenderpearlTerrainPatches}.
 * <ul>
 *   <li>{@link #patchGlBackendLibrary}: {@code GlBackend.loadLibrary()} marks the library loaded
 *       instead of calling {@code SDL_GL_LoadLibrary(GL.getFunctionProvider().getPath())} (the
 *       browser GL override has no function provider, so vanilla throws a NullPointerException);
 *       {@code unloadLibrary()} returns.</li>
 *   <li>{@link #patchVulkanBackend}: {@code checkBackendAvailable()} returns a
 *       VULKAN_LOADER_MISSING exception, {@code loadLibrary()} and {@code createDevice(...)}
 *       throw it, {@code unloadLibrary()} returns and {@code createWindow(...)} returns 0, so no
 *       Vulkan, VMA or SDLVulkan code is reachable. Replaces the dropped 26.2
 *       {@code Minecraft262BrowserPatcher.patchVulkanBackend}.</li>
 *   <li>{@link #patchVulkanFeatureSetsBootstrap}: {@code ClientBootstrap.bootstrap()} no longer
 *       calls the empty {@code VulkanFeatureSets.bootstrap()}, whose only purpose is to run that
 *       class's static initializer. The initializer builds {@code VulkanPNextStruct}s, which look
 *       up {@code SIZEOF}/{@code STYPE} fields of LWJGL Vulkan structs by reflection; TeaVM has
 *       no such reflection, so the client crashed in Bootstrap with "Struct class
 *       VkPhysicalDeviceFeatures2 does not have required member null". The feature sets are only
 *       read by the stubbed VulkanBackend paths.</li>
 *   <li>{@link #patchGlBufferExplicitFlush}: 26.3 no longer sets GL_MAP_FLUSH_EXPLICIT_BIT on
 *       write mappings, and BrowserOpenGL uploads the whole (unseeded) shadow buffer when such a
 *       mapping is unmapped, which corrupts every byte the caller did not write. The patch ORs
 *       FLUSH_EXPLICIT (16) into the write flags, records each mapped view's range and flushes
 *       exactly that range before {@code unmap()} (the semantics of the dropped 26.2
 *       {@code Minecraft262BrowserPatcher.patchGlBufferMappedViewRanges}).</li>
 *   <li>{@link #patchSetupDrawUseProgramBeforeVertexArrayBind}: 26.3's
 *       {@code GlCommandEncoder.setupDraw} binds the pipeline's vertex array (attribute pointers)
 *       before {@code GlRenderPipeline.bind()} switches the GL program. BrowserOpenGL decides
 *       integer vs float attribute pointers from the program that is current when the pointer
 *       is set, so a float attribute (e.g. LineWidth) bound while the previous pipeline's
 *       program (an ivec2 at the same location) is still current became
 *       {@code vertexAttribIPointer(..., GL_FLOAT)}: GL_INVALID_ENUM on every draw. The
 *       pipeline's program is now made current before the vertex array binds.</li>
 * </ul>
 *
 * <p>{@code Minecraft263BrowserPatcher} registers these patches in the chain order under their
 * historical ids ({@code RenderPatches263.<method>}); their profiles ({@code >=26.3}) come from
 * the specs. Classes are read and written through {@code RenderpearlPatchSupport}: from
 * {@code root} when an earlier patch of the step already wrote them, otherwise from {@code jar}
 * (the chain's current client jar), so patches of several domains on one class compose.
 */
public final class RenderpearlRenderPatches {
    static final String GL_BACKEND = "com/mojang/blaze3d/opengl/GlBackend";
    static final String GL_BUFFER = "com/mojang/blaze3d/opengl/GlBuffer";
    static final String GL_COMMAND_ENCODER = "com/mojang/blaze3d/opengl/GlCommandEncoder";
    static final String GL_RENDER_PASS = "com/mojang/blaze3d/opengl/GlRenderPass";
    static final String GL_RENDER_PIPELINE = "com/mojang/blaze3d/opengl/GlRenderPipeline";
    static final String GL_PROGRAM = "com/mojang/blaze3d/opengl/GlProgram";
    static final String GL_STATE_MANAGER = "com/mojang/blaze3d/opengl/GlStateManager";
    static final String DIRECT_STATE_ACCESS = "com/mojang/blaze3d/opengl/DirectStateAccess";
    static final String VULKAN_BACKEND = "com/mojang/blaze3d/vulkan/VulkanBackend";
    static final String BACKEND_CREATION_EXCEPTION =
            "com/mojang/blaze3d/systems/BackendCreationException";
    static final String GPU_DEBUG_OPTIONS = "com/mojang/blaze3d/shaders/GpuDebugOptions";
    static final String GPU_DEVICE = "com/mojang/blaze3d/systems/GpuDevice";
    static final String GPU_BUFFER_SLICE = "com/mojang/blaze3d/buffers/GpuBufferSlice";
    static final String CLIENT_BOOTSTRAP = "net/minecraft/client/ClientBootstrap";
    /** 26.3-only renderpearl class (no 26.2 counterpart, so not a ModernSymbols render key). */
    static final String VULKAN_FEATURE_SETS =
            "com/mojang/renderpearl/backend/vulkan/VulkanFeatureSets";
    static final String VULKAN_UNAVAILABLE = "Vulkan is unavailable in the browser runtime";
    static final int GL_MAP_WRITE_BIT = 2;
    static final int GL_MAP_FLUSH_EXPLICIT_BIT = 16;

    private RenderpearlRenderPatches() {
    }

    public static void patchGlBackendLibrary(String jar, Path root, ModernSymbols symbols)
            throws IOException {
        String owner = symbols.renderType(GL_BACKEND);
        ClassNode node = readClass(jar, root, owner);
        requireField(node, "libraryLoaded", "Z");
        MethodNode load = find(node, "loadLibrary", "()V");
        requireCall(load, Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL", "getFunctionProvider",
                "()Lorg/lwjgl/system/FunctionProvider;");
        requireCall(load, Opcodes.INVOKESTATIC, "org/lwjgl/sdl/SDLVideo", "SDL_GL_LoadLibrary",
                "(Ljava/lang/CharSequence;)Z");
        InsnList loaded = new InsnList();
        loaded.add(new VarInsnNode(Opcodes.ALOAD, 0));
        loaded.add(new InsnNode(Opcodes.ICONST_1));
        loaded.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "libraryLoaded", "Z"));
        loaded.add(new InsnNode(Opcodes.RETURN));
        replace(load, loaded);

        MethodNode unload = find(node, "unloadLibrary", "()V");
        requireCall(unload, Opcodes.INVOKESTATIC, "org/lwjgl/sdl/SDLVideo",
                "SDL_GL_UnloadLibrary", "()V");
        InsnList done = new InsnList();
        done.add(new InsnNode(Opcodes.RETURN));
        replace(unload, done);
        writeClass(node, root, false);
        System.out.println("Stubbed 26.3 GlBackend library loading for the browser GL context");
    }

    public static void patchVulkanBackend(String jar, Path root, ModernSymbols symbols)
            throws IOException {
        String owner = symbols.renderType(VULKAN_BACKEND);
        String exception = symbols.renderType(BACKEND_CREATION_EXCEPTION);
        String reason = exception + "$Reason";
        ClassNode reasonNode = readClass(jar, root, reason);
        requireField(reasonNode, "VULKAN_LOADER_MISSING", "L" + reason + ";");
        ClassNode node = readClass(jar, root, owner);

        MethodNode check = find(node, "checkBackendAvailable", "()L" + exception + ";");
        if ((check.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException(owner + ".checkBackendAvailable is no longer static");
        }
        InsnList unavailable = newUnavailable(exception, reason);
        unavailable.add(new InsnNode(Opcodes.ARETURN));
        replace(check, unavailable);

        MethodNode load = find(node, "loadLibrary", "()V");
        InsnList loadThrows = newUnavailable(exception, reason);
        loadThrows.add(new InsnNode(Opcodes.ATHROW));
        replace(load, loadThrows);

        MethodNode unload = find(node, "unloadLibrary", "()V");
        InsnList unloadCode = new InsnList();
        unloadCode.add(new InsnNode(Opcodes.RETURN));
        replace(unload, unloadCode);

        MethodNode create = find(node, "createDevice",
                "(L" + symbols.renderType(GPU_DEBUG_OPTIONS) + ";)L"
                        + symbols.renderType(GPU_DEVICE) + ";");
        InsnList createThrows = newUnavailable(exception, reason);
        createThrows.add(new InsnNode(Opcodes.ATHROW));
        replace(create, createThrows);

        MethodNode window = find(node, "createWindow", "(Ljava/lang/String;IIJ)J");
        InsnList noWindow = new InsnList();
        noWindow.add(new InsnNode(Opcodes.LCONST_0));
        noWindow.add(new InsnNode(Opcodes.LRETURN));
        replace(window, noWindow);
        writeClass(node, root, false);
        System.out.println("Stubbed 26.3 VulkanBackend: " + VULKAN_UNAVAILABLE);
    }

    public static void patchVulkanFeatureSetsBootstrap(String jar, Path root) throws IOException {
        ClassNode featureSets = readClass(jar, root, VULKAN_FEATURE_SETS);
        MethodNode empty = find(featureSets, "bootstrap", "()V");
        for (AbstractInsnNode instruction : empty.instructions.toArray()) {
            if (instruction.getOpcode() >= 0 && instruction.getOpcode() != Opcodes.RETURN) {
                throw new IllegalStateException(VULKAN_FEATURE_SETS
                        + ".bootstrap() is no longer empty; skipping it would skip real work");
            }
        }
        ClassNode node = readClass(jar, root, CLIENT_BOOTSTRAP);
        MethodNode bootstrap = find(node, "bootstrap", "()V");
        int removed = 0;
        for (AbstractInsnNode instruction : bootstrap.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(VULKAN_FEATURE_SETS) && call.name.equals("bootstrap")
                    && call.desc.equals("()V")) {
                bootstrap.instructions.remove(call);
                removed++;
            }
        }
        if (removed != 1) {
            throw new IllegalStateException(CLIENT_BOOTSTRAP + ".bootstrap() calls "
                    + VULKAN_FEATURE_SETS + ".bootstrap() " + removed + " times, expected 1");
        }
        writeClass(node, root, false);
        System.out.println("Removed VulkanFeatureSets.bootstrap() from 26.3 ClientBootstrap");
    }

    public static void patchGlBufferExplicitFlush(String jar, Path root, ModernSymbols symbols)
            throws IOException {
        String direct = symbols.renderType(GL_BUFFER + "$Direct");
        String close = direct + "$1";
        String dsa = symbols.renderType(DIRECT_STATE_ACCESS);
        String mappedView = symbols.renderType(GPU_BUFFER_SLICE + "$MappedView");

        // Write mappings: OR GL_MAP_FLUSH_EXPLICIT_BIT into the flags the constructor builds.
        ClassNode directNode = readClass(jar, root, direct);
        requireField(directNode, "mappingFlags", "I");
        requireField(directNode, "dsa", "L" + dsa + ";");
        MethodNode constructor = findConstructorStoring(directNode, "mappingFlags");
        int flagsLocal = flagsLocal(constructor, direct);
        List<VarInsnNode> writeStores = new ArrayList<>();
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD
                    && load.var == flagsLocal
                    && next(load) != null && next(load).getOpcode() == Opcodes.ICONST_2
                    && next(next(load)) != null && next(next(load)).getOpcode() == Opcodes.IOR
                    && next(next(next(load))) instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ISTORE && store.var == flagsLocal) {
                writeStores.add(store);
            }
        }
        if (writeStores.size() != 1) {
            throw new IllegalStateException(direct + " constructor: expected one write-mapping flag"
                    + " assignment (flags |= GL_MAP_WRITE_BIT), found " + writeStores.size());
        }
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof IntInsnNode push && push.getOpcode() == Opcodes.BIPUSH
                    && push.operand == GL_MAP_FLUSH_EXPLICIT_BIT) {
                throw new IllegalStateException(direct
                        + " constructor already uses GL_MAP_FLUSH_EXPLICIT_BIT; re-check the"
                        + " 26.3 explicit-flush port");
            }
        }
        InsnList explicitFlush = new InsnList();
        explicitFlush.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        explicitFlush.add(new IntInsnNode(Opcodes.BIPUSH, GL_MAP_FLUSH_EXPLICIT_BIT));
        explicitFlush.add(new InsnNode(Opcodes.IOR));
        explicitFlush.add(new VarInsnNode(Opcodes.ISTORE, flagsLocal));
        constructor.instructions.insert(writeStores.get(0), explicitFlush);

        // map(): pass the mapped view's (offset, length) to its close action.
        MethodNode map = find(directNode, "map", "(JJZZ)L" + mappedView + ";");
        int constructors = 0;
        for (AbstractInsnNode instruction : map.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals(close) && call.name.equals("<init>")
                    && call.desc.equals("(L" + direct + ";)V")) {
                InsnList range = new InsnList();
                range.add(new VarInsnNode(Opcodes.LLOAD, 1));
                range.add(new VarInsnNode(Opcodes.LLOAD, 3));
                map.instructions.insertBefore(call, range);
                call.desc = "(L" + direct + ";JJ)V";
                constructors++;
            }
        }
        if (constructors != 1) {
            throw new IllegalStateException(direct + ".map: expected one " + close
                    + " constructor call, found " + constructors);
        }
        find(directNode, "unmap", "()V");
        // Straight-line insertions only: keep the vanilla stack map frames.
        writeClass(directNode, root, false);

        // The close action: flush the view's own range before unmap().
        ClassNode closeNode = readClass(jar, root, close);
        requireField(closeNode, "closed", "Z");
        requireField(closeNode, "this$0", "L" + direct + ";");
        MethodNode run = find(closeNode, "run", "()V");
        requireCall(run, Opcodes.INVOKEVIRTUAL, direct, "unmap", "()V");
        for (AbstractInsnNode instruction : run.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("flushMappedBufferRange")) {
                throw new IllegalStateException(close
                        + ".run already flushes; re-check the 26.3 explicit-flush port");
            }
        }
        closeNode.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mappedOffset", "J", null, null));
        closeNode.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mappedLength", "J", null, null));
        MethodNode init = find(closeNode, "<init>", "(L" + direct + ";)V");
        init.desc = "(L" + direct + ";JJ)V";
        InsnList initCode = new InsnList();
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, close, "this$0", "L" + direct + ";"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new InsnNode(Opcodes.ICONST_0));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, close, "closed", "Z"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new VarInsnNode(Opcodes.LLOAD, 2));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, close, "mappedOffset", "J"));
        initCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initCode.add(new VarInsnNode(Opcodes.LLOAD, 4));
        initCode.add(new FieldInsnNode(Opcodes.PUTFIELD, close, "mappedLength", "J"));
        initCode.add(new InsnNode(Opcodes.RETURN));
        replace(init, initCode);

        LabelNode active = new LabelNode();
        LabelNode unmap = new LabelNode();
        InsnList runCode = new InsnList();
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "closed", "Z"));
        runCode.add(new JumpInsnNode(Opcodes.IFEQ, active));
        runCode.add(new InsnNode(Opcodes.RETURN));
        runCode.add(active);
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new InsnNode(Opcodes.ICONST_1));
        runCode.add(new FieldInsnNode(Opcodes.PUTFIELD, close, "closed", "Z"));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "this$0", "L" + direct + ";"));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, direct, "mappingFlags", "I"));
        runCode.add(new IntInsnNode(Opcodes.BIPUSH, GL_MAP_FLUSH_EXPLICIT_BIT));
        runCode.add(new InsnNode(Opcodes.IAND));
        runCode.add(new JumpInsnNode(Opcodes.IFEQ, unmap));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "this$0", "L" + direct + ";"));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, direct, "dsa", "L" + dsa + ";"));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "this$0", "L" + direct + ";"));
        runCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, direct, "handle", "()I", false));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "mappedOffset", "J"));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "mappedLength", "J"));
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "this$0", "L" + direct + ";"));
        runCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, direct, "usage", "()I", false));
        runCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, dsa, "flushMappedBufferRange", "(IJJI)V", false));
        runCode.add(unmap);
        runCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        runCode.add(new FieldInsnNode(Opcodes.GETFIELD, close, "this$0", "L" + direct + ";"));
        runCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, direct, "unmap", "()V", false));
        runCode.add(new InsnNode(Opcodes.RETURN));
        replace(run, runCode);
        requireMethod(readClass(jar, root, dsa), "flushMappedBufferRange", "(IJJI)V");
        writeClass(closeNode, root, true);
        System.out.println("Patched 26.3 GlBuffer write mappings to flush only their mapped"
                + " view ranges (GL_MAP_FLUSH_EXPLICIT_BIT)");
    }

    public static void patchSetupDrawUseProgramBeforeVertexArrayBind(String jar, Path root,
            ModernSymbols symbols) throws IOException {
        String encoder = symbols.renderType(GL_COMMAND_ENCODER);
        String renderPass = symbols.renderType(GL_RENDER_PASS);
        String pipeline = symbols.renderType(GL_RENDER_PIPELINE);
        String program = symbols.renderType(GL_PROGRAM);
        String stateManager = symbols.renderType(GL_STATE_MANAGER);
        String slice = symbols.renderType(GPU_BUFFER_SLICE);
        ClassNode node = readClass(jar, root, encoder);
        MethodNode setupDraw = find(node, "setupDraw", "(L" + renderPass + ";)V");
        MethodInsnNode bind = null;
        for (AbstractInsnNode instruction : setupDraw.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(stateManager)
                    && call.name.equals("_glUseProgram")) {
                throw new IllegalStateException(encoder + ".setupDraw already switches the program");
            }
            if (instruction instanceof MethodInsnNode call && call.name.equals("bind")
                    && call.desc.equals("([L" + slice + ";)V") && call.owner.endsWith("/VertexArray")) {
                if (bind != null) {
                    throw new IllegalStateException(encoder + ".setupDraw binds the vertex array twice");
                }
                bind = call;
            }
        }
        if (bind == null) {
            throw new IllegalStateException(encoder + ".setupDraw no longer binds the vertex array");
        }
        // aload_1; getfield pipeline; invokevirtual vertexArray(); aload_1; getfield vertexBuffers; bind
        AbstractInsnNode buffers = previousOpcode(bind);
        AbstractInsnNode passForBuffers = previousOpcode(buffers);
        AbstractInsnNode vertexArray = previousOpcode(passForBuffers);
        AbstractInsnNode pipelineField = previousOpcode(vertexArray);
        AbstractInsnNode passForPipeline = previousOpcode(pipelineField);
        if (!(vertexArray instanceof MethodInsnNode va) || !va.owner.equals(pipeline)
                || !va.name.equals("vertexArray")
                || !(pipelineField instanceof FieldInsnNode field) || !field.owner.equals(renderPass)
                || !field.name.equals("pipeline") || field.getOpcode() != Opcodes.GETFIELD
                || !(passForPipeline instanceof VarInsnNode pass) || pass.getOpcode() != Opcodes.ALOAD
                || pass.var != 1) {
            throw new IllegalStateException(encoder
                    + ".setupDraw: the vertex array bind is no longer pass.pipeline.vertexArray().bind(pass.vertexBuffers)");
        }
        InsnList useProgram = new InsnList();
        useProgram.add(new VarInsnNode(Opcodes.ALOAD, 1));
        useProgram.add(new FieldInsnNode(Opcodes.GETFIELD, renderPass, "pipeline", "L" + pipeline + ";"));
        useProgram.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, pipeline, "program",
                "()L" + program + ";", false));
        useProgram.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, program, "getProgramId", "()I", false));
        useProgram.add(new MethodInsnNode(Opcodes.INVOKESTATIC, stateManager, "_glUseProgram", "(I)V", false));
        // Straight-line code inserted at a fall-through (no frame there); one stack slot, below max.
        setupDraw.instructions.insertBefore(passForPipeline, useProgram);
        writeClass(node, root, false);
        System.out.println("Made the 26.3 pipeline program current before its vertex array binds"
                + " (GlCommandEncoder.setupDraw)");
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static InsnList newUnavailable(String exception, String reason) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, exception));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode(VULKAN_UNAVAILABLE));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, reason, "VULKAN_LOADER_MISSING", "L" + reason + ";"));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, exception, "<init>",
                "(Ljava/lang/String;L" + reason + ";)V", false));
        return code;
    }

    private static MethodNode findConstructorStoring(ClassNode node, String field) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode store
                        && store.getOpcode() == Opcodes.PUTFIELD && store.owner.equals(node.name)
                        && store.name.equals(field)) {
                    if (found != null && found != method) {
                        throw new IllegalStateException(node.name
                                + ": several constructors assign " + field);
                    }
                    found = method;
                }
            }
        }
        if (found == null) {
            throw new IllegalStateException(node.name + ": no constructor assigns " + field);
        }
        return found;
    }

    /** The local that {@code this.mappingFlags = <local>} stores, asserted to be unique. */
    private static int flagsLocal(MethodNode constructor, String owner) {
        int local = -1;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD
                    && store.owner.equals(owner) && store.name.equals("mappingFlags")) {
                AbstractInsnNode value = previous(store);
                if (!(value instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD
                        || local >= 0) {
                    throw new IllegalStateException(owner
                            + " constructor: mappingFlags is not stored once from a local");
                }
                local = load.var;
            }
        }
        if (local < 0) {
            throw new IllegalStateException(owner + " constructor: mappingFlags store not found");
        }
        return local;
    }

    /** The next real instruction (labels, line numbers and frames skipped). */
    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static void requireField(ClassNode node, String name, String desc) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return;
            }
        }
        throw new IllegalStateException(node.name + "." + name + ":" + desc + " was not found");
    }

    private static void requireMethod(ClassNode node, String name, String desc) {
        find(node, name, desc);
    }

    private static void requireCall(
            MethodNode method, int opcode, String owner, String name, String desc) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) {
                return;
            }
        }
        throw new IllegalStateException(method.name + method.desc + " no longer calls "
                + owner + "." + name + desc);
    }
}
