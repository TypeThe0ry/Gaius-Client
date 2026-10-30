package dev.gaius.tools.patches.render;

import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import dev.gaius.tools.patches.support.QualityClasses;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Graphics quality tier patches of the renderpearl generation (v0.4.0 workstream C): the render
 * domain of the Minecraft263BrowserPatcher step ({@code patches/render}). The step
 * registers them with its render group under their historical ids
 * ({@code RenderPatches263.<method>}); their profiles come from the specs. Every patch registers
 * itself as dropped ({@code PATCH_DROPPED}) on a jar without the renderpearl OpenGL backend and
 * fails the build when a 26.3 shape it relies on changed. Class access and shape assertions:
 * {@link QualityClasses}.
 *
 * <ul>
 *   <li>{@link #patchImprovedTransparencyByTier} (replaces the former
 *       {@code RenderPatches263.patchImprovedTransparencyOff}): {@code
 *       GameRenderer.useImprovedTransparency()} keeps its vanilla body as
 *       {@code gaius$vanillaUseImprovedTransparency} and the public method returns
 *       {@code BrowserQualityCaps.filterImprovedTransparency(vanilla)}, so order-independent
 *       transparency runs on high/ultra GPUs whose context renders and blends float targets.
 *       {@code VideoSettingsScreen.qualityOptions} passes its array through
 *       {@code BrowserQualityOptions.filterQualityOptions}, hiding the option elsewhere.</li>
 *   <li>{@link #patchLevelQualityHooks}: world render scale and post-processing hook points.
 *       {@code GameRenderer.renderLevel} calls {@code BrowserQualityFrame.beginLevel(width,
 *       height, useImprovedTransparency() | levelRenderState.shouldShowEntityOutlines)} on entry
 *       (either keeps the frame at full resolution) and {@code endLevel(color, depth, outline,
 *       projection)} right after {@code LevelRenderer.render(...)}, before the hand renders;
 *       {@code GlCommandEncoder.createRenderPass} sends the width and height of its
 *       {@code GlStateManager._viewport(0, 0, w, h)} through {@code levelViewportWidth/Height};
 *       {@code LevelRenderer} gains {@code gaius$entityOutlineTextureId()}.</li>
 *   <li>{@link #patchInventoryWorldRenderThrottle}: the inventory world-render throttle that
 *       {@code MinecraftClientPatcher} injected into {@code GameRenderer.render()} calls
 *       {@code BrowserQualityFrame.shouldSkipWorldRender} instead of
 *       {@code BrowserOpenGL.shouldSkipWorldRenderForScreen}, and the merge point after the
 *       world section calls {@code BrowserQualityFrame.worldFrameDone(color, width, height)}.</li>
 * </ul>
 */
public final class QualityTierPatches {
    static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String VIDEO_SETTINGS =
            "net/minecraft/client/gui/screens/options/VideoSettingsScreen";
    static final String OPTIONS = "net/minecraft/client/Options";
    static final String OPTION_INSTANCE = "net/minecraft/client/OptionInstance";
    static final String RENDER_TARGET = "com/mojang/blaze3d/pipeline/RenderTarget";
    static final String GAME_RENDER_STATE = "net/minecraft/client/renderer/state/GameRenderState";
    static final String LEVEL_RENDER_STATE =
            "net/minecraft/client/renderer/state/level/LevelRenderState";
    static final String CAMERA_RENDER_STATE =
            "net/minecraft/client/renderer/state/level/CameraRenderState";
    static final String GRAPHICS_RESOURCE_ALLOCATOR =
            "com/mojang/blaze3d/resource/GraphicsResourceAllocator";
    static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    static final String BROWSER_OPENGL = "org/lwjgl/opengl/BrowserOpenGL";
    static final String PROFILER_FILLER = "net/minecraft/util/profiling/ProfilerFiller";
    /** 26.2 render keys, mapped to the renderpearl names through {@link ModernSymbols}. */
    static final String GL_TEXTURE_KEY = "com/mojang/blaze3d/opengl/GlTexture";
    static final String GPU_TEXTURE_KEY = "com/mojang/blaze3d/textures/GpuTexture";
    static final String GL_COMMAND_ENCODER_KEY = "com/mojang/blaze3d/opengl/GlCommandEncoder";
    static final String GL_STATE_MANAGER_KEY = "com/mojang/blaze3d/opengl/GlStateManager";
    static final String RENDER_PASS_DESCRIPTOR_KEY = "com/mojang/blaze3d/systems/RenderPassDescriptor";
    static final String RENDER_PASS_BACKEND = "com/mojang/renderpearl/backend/api/RenderPassBackend";

    static final String QUALITY_FRAME = "dev/gaius/browser/quality/BrowserQualityFrame";
    static final String QUALITY_CAPS = "dev/gaius/browser/quality/BrowserQualityCaps";
    static final String QUALITY_OPTIONS = "dev/gaius/browser/quality/BrowserQualityOptions";

    static final String VANILLA_USE_OIT = "gaius$vanillaUseImprovedTransparency";
    static final String VANILLA_QUALITY_OPTIONS = "gaius$vanillaQualityOptions";
    static final String GL_TEXTURE_ID = "gaius$glTextureId";
    static final String OUTLINE_TEXTURE_ID = "gaius$entityOutlineTextureId";

    private QualityTierPatches() {
    }

    /**
     * True on a renderpearl (26.3+) jar. Otherwise registers {@code patchId} as dropped (its
     * renderpearl GL texture class is absent) and returns false.
     */
    private static boolean renderpearl(String patchId, String jar, ModernSymbols symbols)
            throws IOException {
        if (symbols.renderpearl()) {
            return true;
        }
        System.out.println(patchId + ": not applicable, " + jar
                + " has no renderpearl OpenGL backend");
        PatchRegistry.dropped(patchId, jar, "com/mojang/renderpearl/backend/opengl/GlTexture");
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // Improved transparency (OIT) by capability and tier
    // ---------------------------------------------------------------------------------------

    public static void patchImprovedTransparencyByTier(String jar, Path root,
            ModernSymbols symbols) throws IOException {
        if (!renderpearl("RenderPatches263.patchImprovedTransparencyByTier", jar, symbols)) {
            return;
        }
        ClassNode renderer = QualityClasses.read(jar, root, GAME_RENDERER);
        MethodNode use = QualityClasses.find(renderer, "useImprovedTransparency", "()Z");
        QualityClasses.requireAbsent(renderer, VANILLA_USE_OIT, "()Z");
        if (QualityClasses.isStatic(use)) {
            throw new IllegalStateException(GAME_RENDERER + ".useImprovedTransparency is static");
        }
        boolean readsOption = false;
        for (AbstractInsnNode instruction : use.instructions.toArray()) {
            readsOption |= instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("improvedTransparency") && field.desc.equals("Z");
        }
        if (!readsOption) {
            throw new IllegalStateException(GAME_RENDERER
                    + ".useImprovedTransparency no longer reads the improvedTransparency option");
        }
        // Keep the vanilla body under a private name; the public method filters it.
        use.name = VANILLA_USE_OIT;
        use.access = (use.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PRIVATE;
        MethodNode filtered = new MethodNode(Opcodes.ACC_PUBLIC, "useImprovedTransparency", "()Z",
                null, null);
        filtered.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        filtered.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, GAME_RENDERER,
                VANILLA_USE_OIT, "()Z", false));
        filtered.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUALITY_CAPS,
                "filterImprovedTransparency", "(Z)Z", false));
        filtered.instructions.add(new InsnNode(Opcodes.IRETURN));
        filtered.maxLocals = 1;
        filtered.maxStack = 1;
        renderer.methods.add(filtered);
        QualityClasses.write(renderer, root);

        // Video settings: list Improved Transparency only where it can run.
        ClassNode screen = QualityClasses.read(jar, root, VIDEO_SETTINGS);
        String arrayDesc = "(L" + OPTIONS + ";)[L" + OPTION_INSTANCE + ";";
        MethodNode quality = QualityClasses.find(screen, "qualityOptions", arrayDesc);
        QualityClasses.requireAbsent(screen, VANILLA_QUALITY_OPTIONS, arrayDesc);
        if (!QualityClasses.isStatic(quality)) {
            throw new IllegalStateException(VIDEO_SETTINGS + ".qualityOptions is no longer static");
        }
        int transparency = 0;
        int getters = 0;
        for (AbstractInsnNode instruction : quality.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !call.owner.equals(OPTIONS)
                        || !call.desc.equals("()L" + OPTION_INSTANCE + ";")) {
                    throw new IllegalStateException(VIDEO_SETTINGS
                            + ".qualityOptions is no longer a plain array of Options getters: "
                            + call.owner + "." + call.name + call.desc);
                }
                getters++;
                if (call.name.equals("improvedTransparency")) {
                    transparency++;
                }
            } else if (instruction.getOpcode() >= 0
                    && instruction.getOpcode() != Opcodes.DUP
                    && instruction.getOpcode() != Opcodes.ALOAD
                    && instruction.getOpcode() != Opcodes.AASTORE
                    && instruction.getOpcode() != Opcodes.ANEWARRAY
                    && instruction.getOpcode() != Opcodes.ARETURN
                    && !isIntPush(instruction)) {
                throw new IllegalStateException(VIDEO_SETTINGS
                        + ".qualityOptions has an unexpected instruction (opcode "
                        + instruction.getOpcode() + ")");
            }
        }
        if (transparency != 1 || getters < 2) {
            throw new IllegalStateException(VIDEO_SETTINGS + ".qualityOptions: expected one"
                    + " improvedTransparency option among " + getters + " getters, found "
                    + transparency);
        }
        QualityClasses.requireMethod(QualityClasses.read(jar, root, OPTIONS),
                "improvedTransparency", "()L" + OPTION_INSTANCE + ";");
        quality.name = VANILLA_QUALITY_OPTIONS;
        MethodNode wrapper = new MethodNode(quality.access, "qualityOptions", arrayDesc,
                quality.signature, null);
        InsnList code = wrapper.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, VIDEO_SETTINGS,
                VANILLA_QUALITY_OPTIONS, arrayDesc, false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OPTIONS, "improvedTransparency",
                "()L" + OPTION_INSTANCE + ";", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUALITY_OPTIONS,
                "filterQualityOptions", "([Ljava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "[L" + OPTION_INSTANCE + ";"));
        code.add(new InsnNode(Opcodes.ARETURN));
        wrapper.maxLocals = 1;
        wrapper.maxStack = 2;
        screen.methods.add(wrapper);
        QualityClasses.write(screen, root);
        System.out.println("26.3 improved transparency (OIT) now follows the GPU capability"
                + " and tier gate (BrowserQualityCaps; ?gaiusOit=0|1)");
    }

    // ---------------------------------------------------------------------------------------
    // World render scale and post-processing hooks
    // ---------------------------------------------------------------------------------------

    public static void patchLevelQualityHooks(String jar, Path root, ModernSymbols symbols)
            throws IOException {
        if (!renderpearl("RenderPatches263.patchLevelQualityHooks", jar, symbols)) {
            return;
        }
        String glTexture = symbols.renderType(GL_TEXTURE_KEY);
        String gpuTexture = symbols.renderType(GPU_TEXTURE_KEY);
        String encoder = symbols.renderType(GL_COMMAND_ENCODER_KEY);
        String stateManager = symbols.renderType(GL_STATE_MANAGER_KEY);
        String descriptor = symbols.renderType(RENDER_PASS_DESCRIPTOR_KEY);
        QualityClasses.requireMethod(QualityClasses.read(jar, root, glTexture), "glId", "()I");
        ClassNode target = QualityClasses.read(jar, root, RENDER_TARGET);
        QualityClasses.requireField(target, "width", "I");
        QualityClasses.requireField(target, "height", "I");
        QualityClasses.requireMethod(target, "getColorTexture", "()L" + gpuTexture + ";");
        QualityClasses.requireMethod(target, "getDepthTexture", "()L" + gpuTexture + ";");
        QualityClasses.requireField(QualityClasses.read(jar, root, GAME_RENDER_STATE),
                "levelRenderState", "L" + LEVEL_RENDER_STATE + ";");
        ClassNode levelState = QualityClasses.read(jar, root, LEVEL_RENDER_STATE);
        QualityClasses.requireField(levelState, "cameraRenderState",
                "L" + CAMERA_RENDER_STATE + ";");
        QualityClasses.requireField(levelState, "shouldShowEntityOutlines", "Z");
        QualityClasses.requireField(QualityClasses.read(jar, root, CAMERA_RENDER_STATE),
                "projectionMatrix", "Lorg/joml/Matrix4f;");
        QualityClasses.requireField(QualityClasses.read(jar, root, MINECRAFT),
                "levelRenderer", "L" + LEVEL_RENDERER + ";");

        // 1. GlCommandEncoder.createRenderPass: _viewport(0, 0, width, height).
        ClassNode encoderNode = QualityClasses.read(jar, root, encoder);
        MethodNode create = QualityClasses.find(encoderNode, "createRenderPass",
                "(L" + descriptor + ";)L" + RENDER_PASS_BACKEND + ";");
        MethodInsnNode viewport = QualityClasses.single(QualityClasses.calls(create,
                Opcodes.INVOKESTATIC, stateManager, "_viewport", "(IIII)V"),
                encoder + ".createRenderPass viewport");
        AbstractInsnNode heightLoad = QualityClasses.previous(viewport);
        AbstractInsnNode widthLoad = QualityClasses.previous(heightLoad);
        AbstractInsnNode y = QualityClasses.previous(widthLoad);
        AbstractInsnNode x = QualityClasses.previous(y);
        if (!(heightLoad instanceof VarInsnNode hl) || hl.getOpcode() != Opcodes.ILOAD
                || !(widthLoad instanceof VarInsnNode wl) || wl.getOpcode() != Opcodes.ILOAD
                || hl.var == wl.var
                || y == null || y.getOpcode() != Opcodes.ICONST_0
                || x == null || x.getOpcode() != Opcodes.ICONST_0) {
            throw new IllegalStateException(encoder + ".createRenderPass no longer sets"
                    + " _viewport(0, 0, width, height) from two locals");
        }
        create.instructions.insert(widthLoad, new MethodInsnNode(Opcodes.INVOKESTATIC,
                QUALITY_FRAME, "levelViewportWidth", "(I)I", false));
        create.instructions.insert(heightLoad, new MethodInsnNode(Opcodes.INVOKESTATIC,
                QUALITY_FRAME, "levelViewportHeight", "(I)I", false));
        QualityClasses.write(encoderNode, root);

        // 2. LevelRenderer.gaius$entityOutlineTextureId(): the outline colour texture name when
        //    this frame renders entity outlines, else 0.
        ClassNode level = QualityClasses.read(jar, root, LEVEL_RENDERER);
        QualityClasses.requireField(level, "currentFrameRendersEntityOutline", "Z");
        QualityClasses.requireField(level, "entityOutlineTarget", "L" + RENDER_TARGET + ";");
        QualityClasses.requireMethod(level, "blitEntityOutline", "()V");
        QualityClasses.requireAbsent(level, OUTLINE_TEXTURE_ID, "()I");
        addGlTextureIdHelper(level, gpuTexture, glTexture);
        MethodNode outline = new MethodNode(Opcodes.ACC_PUBLIC, OUTLINE_TEXTURE_ID, "()I", null,
                null);
        LabelNode noOutline = new LabelNode();
        InsnList outlineCode = outline.instructions;
        outlineCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        outlineCode.add(new FieldInsnNode(Opcodes.GETFIELD, LEVEL_RENDERER,
                "currentFrameRendersEntityOutline", "Z"));
        outlineCode.add(new JumpInsnNode(Opcodes.IFEQ, noOutline));
        outlineCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        outlineCode.add(new FieldInsnNode(Opcodes.GETFIELD, LEVEL_RENDERER, "entityOutlineTarget",
                "L" + RENDER_TARGET + ";"));
        outlineCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDER_TARGET,
                "getColorTexture", "()L" + gpuTexture + ";", false));
        outlineCode.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LEVEL_RENDERER, GL_TEXTURE_ID,
                "(L" + gpuTexture + ";)I", false));
        outlineCode.add(new InsnNode(Opcodes.IRETURN));
        outlineCode.add(noOutline);
        outlineCode.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        outlineCode.add(new InsnNode(Opcodes.ICONST_0));
        outlineCode.add(new InsnNode(Opcodes.IRETURN));
        outline.maxLocals = 1;
        outline.maxStack = 1;
        level.methods.add(outline);
        QualityClasses.write(level, root);

        // 3. GameRenderer.renderLevel: beginLevel on entry, endLevel after LevelRenderer.render.
        ClassNode renderer = QualityClasses.read(jar, root, GAME_RENDERER);
        QualityClasses.requireField(renderer, "mainRenderTarget", "L" + RENDER_TARGET + ";");
        QualityClasses.requireField(renderer, "minecraft", "L" + MINECRAFT + ";");
        QualityClasses.requireField(renderer, "gameRenderState", "L" + GAME_RENDER_STATE + ";");
        QualityClasses.requireMethod(renderer, "useImprovedTransparency", "()Z");
        addGlTextureIdHelper(renderer, gpuTexture, glTexture);
        MethodNode renderLevel = QualityClasses.find(renderer, "renderLevel", "()V");
        if (!QualityClasses.calls(renderLevel, Opcodes.INVOKESTATIC, QUALITY_FRAME, null, null)
                .isEmpty()) {
            throw new IllegalStateException(GAME_RENDERER + ".renderLevel already has quality hooks");
        }
        List<MethodInsnNode> levelRenders = new ArrayList<>();
        for (MethodInsnNode call : QualityClasses.calls(renderLevel, Opcodes.INVOKEVIRTUAL,
                LEVEL_RENDERER, "render", null)) {
            if (call.desc.startsWith("(L" + GRAPHICS_RESOURCE_ALLOCATOR + ";")
                    && call.desc.endsWith(")V")) {
                levelRenders.add(call);
            }
        }
        MethodInsnNode levelRender = QualityClasses.single(levelRenders,
                GAME_RENDERER + ".renderLevel LevelRenderer.render(GraphicsResourceAllocator, ...)");

        InsnList begin = new InsnList();
        begin.add(mainTarget());
        begin.add(new FieldInsnNode(Opcodes.GETFIELD, RENDER_TARGET, "width", "I"));
        begin.add(mainTarget());
        begin.add(new FieldInsnNode(Opcodes.GETFIELD, RENDER_TARGET, "height", "I"));
        begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
        begin.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, GAME_RENDERER,
                "useImprovedTransparency", "()Z", false));
        // The entity_outline post chain runs inside LevelRenderer.render on main-target-sized
        // targets with full-texture screen-quad UVs, so a scaled viewport would shrink the
        // outline once per pass. LevelExtractor sets this flag during extraction, before
        // renderLevel; it is wider than LevelRenderer.currentFrameRendersEntityOutline.
        begin.add(new VarInsnNode(Opcodes.ALOAD, 0));
        begin.add(new FieldInsnNode(Opcodes.GETFIELD, GAME_RENDERER, "gameRenderState",
                "L" + GAME_RENDER_STATE + ";"));
        begin.add(new FieldInsnNode(Opcodes.GETFIELD, GAME_RENDER_STATE, "levelRenderState",
                "L" + LEVEL_RENDER_STATE + ";"));
        begin.add(new FieldInsnNode(Opcodes.GETFIELD, LEVEL_RENDER_STATE,
                "shouldShowEntityOutlines", "Z"));
        begin.add(new InsnNode(Opcodes.IOR));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUALITY_FRAME, "beginLevel", "(IIZ)V",
                false));
        insertAtEntry(renderLevel, begin);

        InsnList end = new InsnList();
        end.add(mainTargetTextureId(gpuTexture, "getColorTexture"));
        end.add(mainTargetTextureId(gpuTexture, "getDepthTexture"));
        end.add(new VarInsnNode(Opcodes.ALOAD, 0));
        end.add(new FieldInsnNode(Opcodes.GETFIELD, GAME_RENDERER, "minecraft",
                "L" + MINECRAFT + ";"));
        end.add(new FieldInsnNode(Opcodes.GETFIELD, MINECRAFT, "levelRenderer",
                "L" + LEVEL_RENDERER + ";"));
        end.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL_RENDERER, OUTLINE_TEXTURE_ID,
                "()I", false));
        end.add(new VarInsnNode(Opcodes.ALOAD, 0));
        end.add(new FieldInsnNode(Opcodes.GETFIELD, GAME_RENDERER, "gameRenderState",
                "L" + GAME_RENDER_STATE + ";"));
        end.add(new FieldInsnNode(Opcodes.GETFIELD, GAME_RENDER_STATE, "levelRenderState",
                "L" + LEVEL_RENDER_STATE + ";"));
        end.add(new FieldInsnNode(Opcodes.GETFIELD, LEVEL_RENDER_STATE, "cameraRenderState",
                "L" + CAMERA_RENDER_STATE + ";"));
        end.add(new FieldInsnNode(Opcodes.GETFIELD, CAMERA_RENDER_STATE, "projectionMatrix",
                "Lorg/joml/Matrix4f;"));
        end.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUALITY_FRAME, "endLevel",
                "(IIILjava/lang/Object;)V", false));
        // LevelRenderer.render returns void as a statement: the stack is empty after it.
        renderLevel.instructions.insert(levelRender, end);
        QualityClasses.write(renderer, root);
        System.out.println("Installed 26.3 world render-scale and post-processing hooks"
                + " (GameRenderer.renderLevel, GlCommandEncoder.createRenderPass viewport,"
                + " LevelRenderer." + OUTLINE_TEXTURE_ID + ")");
    }

    // ---------------------------------------------------------------------------------------
    // Inventory screens: reduced-rate world render instead of a frozen frame
    // ---------------------------------------------------------------------------------------

    public static void patchInventoryWorldRenderThrottle(String jar, Path root,
            ModernSymbols symbols) throws IOException {
        if (!renderpearl("RenderPatches263.patchInventoryWorldRenderThrottle", jar, symbols)) {
            return;
        }
        String glTexture = symbols.renderType(GL_TEXTURE_KEY);
        String gpuTexture = symbols.renderType(GPU_TEXTURE_KEY);
        ClassNode renderer = QualityClasses.read(jar, root, GAME_RENDERER);
        MethodNode render = QualityClasses.find(renderer, "render", "()V");
        MethodInsnNode skip = QualityClasses.single(QualityClasses.calls(render,
                Opcodes.INVOKESTATIC, BROWSER_OPENGL, "shouldSkipWorldRenderForScreen",
                "(L" + SCREEN + ";)Z"),
                GAME_RENDERER + ".render inventory throttle (MinecraftClientPatcher"
                        + ".patchGameRendererBrowserInventoryWorldRenderThrottle)");
        // Shape injected by MinecraftClientPatcher:
        //   invokestatic shouldSkip; ifeq continueWorld; aload profiler; pop; goto afterWorld
        AbstractInsnNode branch = QualityClasses.next(skip);
        AbstractInsnNode profiler = QualityClasses.next(branch);
        AbstractInsnNode pop = QualityClasses.next(profiler);
        AbstractInsnNode jump = QualityClasses.next(pop);
        if (!(branch instanceof JumpInsnNode ifeq) || ifeq.getOpcode() != Opcodes.IFEQ
                || !(profiler instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                || !(pop instanceof MethodInsnNode popCall) || !popCall.owner.equals(PROFILER_FILLER)
                || !popCall.name.equals("pop")
                || !(jump instanceof JumpInsnNode go) || go.getOpcode() != Opcodes.GOTO) {
            throw new IllegalStateException(GAME_RENDERER + ".render: the inventory throttle is no"
                    + " longer 'if (skip) { profiler.pop(); goto afterWorld; }'");
        }
        LabelNode afterWorld = ((JumpInsnNode) jump).label;
        // The merge point must sit right after the world section's profiler pop.
        AbstractInsnNode beforeMerge = QualityClasses.previous(afterWorld);
        if (!(beforeMerge instanceof MethodInsnNode mergePop)
                || !mergePop.owner.equals(PROFILER_FILLER) || !mergePop.name.equals("pop")) {
            throw new IllegalStateException(GAME_RENDERER + ".render: the inventory throttle's"
                    + " merge label no longer follows the world profiler pop");
        }
        if (!QualityClasses.calls(render, Opcodes.INVOKESTATIC, QUALITY_FRAME, null, null)
                .isEmpty()) {
            throw new IllegalStateException(GAME_RENDERER + ".render already has quality hooks");
        }
        if (!QualityClasses.has(renderer, GL_TEXTURE_ID, "(L" + gpuTexture + ";)I")) {
            addGlTextureIdHelper(renderer, gpuTexture, glTexture);
        }

        skip.owner = QUALITY_FRAME;
        skip.name = "shouldSkipWorldRender";
        skip.desc = "(Ljava/lang/Object;)Z";

        InsnList done = new InsnList();
        done.add(mainTargetTextureId(gpuTexture, "getColorTexture"));
        done.add(mainTarget());
        done.add(new FieldInsnNode(Opcodes.GETFIELD, RENDER_TARGET, "width", "I"));
        done.add(mainTarget());
        done.add(new FieldInsnNode(Opcodes.GETFIELD, RENDER_TARGET, "height", "I"));
        done.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUALITY_FRAME, "worldFrameDone",
                "(III)V", false));
        // Insert after the merge label and its frame (and line number), where both paths meet
        // with an empty stack.
        AbstractInsnNode anchor = afterWorld;
        while (anchor.getNext() != null && (anchor.getNext() instanceof FrameNode
                || anchor.getNext() instanceof LineNumberNode)) {
            anchor = anchor.getNext();
        }
        render.instructions.insert(anchor, done);
        QualityClasses.write(renderer, root);
        System.out.println("26.3 inventory screens render the world at the tier's reduced rate"
                + " (BrowserQualityFrame.shouldSkipWorldRender/worldFrameDone)");
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** {@code this.mainRenderTarget} of the GameRenderer. */
    private static InsnList mainTarget() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, GAME_RENDERER, "mainRenderTarget",
                "L" + RENDER_TARGET + ";"));
        return code;
    }

    /** {@code gaius$glTextureId(this.mainRenderTarget.<getter>())}. */
    private static InsnList mainTargetTextureId(String gpuTexture, String getter) {
        InsnList code = mainTarget();
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RENDER_TARGET, getter,
                "()L" + gpuTexture + ";", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GAME_RENDERER, GL_TEXTURE_ID,
                "(L" + gpuTexture + ";)I", false));
        return code;
    }

    /** The WebGPU core's texture owner interface (port/gpu/webgpu-core, RpTexture implements it). */
    static final String CORE_TEXTURE_OWNER = "dev/gaius/gpu/webgpu/CoreTextureOwner";
    static final String CORE_TEXTURE = "dev/gaius/gpu/webgpu/CoreTexture";

    /**
     * Adds {@code private static int gaius$glTextureId(GpuTexture texture)}: the BrowserOpenGL
     * texture name {@code ((GlTexture) texture).glId()}; for a texture of the WebGPU backend the
     * negated glue handle of its core texture ({@code -((CoreTextureOwner) texture).coreTexture()
     * .handle()}), which the quality runtime resolves under WebGPU; 0 for null and anything else.
     */
    private static void addGlTextureIdHelper(ClassNode node, String gpuTexture, String glTexture) {
        String desc = "(L" + gpuTexture + ";)I";
        QualityClasses.requireAbsent(node, GL_TEXTURE_ID, desc);
        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                | Opcodes.ACC_SYNTHETIC, GL_TEXTURE_ID, desc, null, null);
        LabelNode present = new LabelNode();
        LabelNode notWebGpu = new LabelNode();
        InsnList code = helper.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF, glTexture));
        code.add(new JumpInsnNode(Opcodes.IFNE, present));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF, CORE_TEXTURE_OWNER));
        code.add(new JumpInsnNode(Opcodes.IFEQ, notWebGpu));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, CORE_TEXTURE_OWNER));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, CORE_TEXTURE_OWNER, "coreTexture",
                "()L" + CORE_TEXTURE + ";", true));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CORE_TEXTURE, "handle", "()I", false));
        code.add(new InsnNode(Opcodes.INEG));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(notWebGpu);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(present);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, glTexture));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, glTexture, "glId", "()I", false));
        code.add(new InsnNode(Opcodes.IRETURN));
        helper.maxLocals = 1;
        helper.maxStack = 1;
        node.methods.add(helper);
    }

    private static boolean isIntPush(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
                || opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH;
    }

    /**
     * Inserts straight-line code at the very start of {@code method}. Existing labels (and their
     * frames) stay after the new code, so a jump to the old entry label skips it, which is what
     * a once-per-call hook wants.
     */
    private static void insertAtEntry(MethodNode method, InsnList code) {
        method.instructions.insert(code);
    }
}
