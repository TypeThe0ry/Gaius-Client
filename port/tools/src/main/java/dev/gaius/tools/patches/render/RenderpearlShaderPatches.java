package dev.gaius.tools.patches.render;

import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.find;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.readClass;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.writeClass;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The shader domain of the renderpearl render seam: when the render pipelines are compiled.
 *
 * <p>{@link #patchShaderPipelineWarmup} moves the compilation of the render pipelines that the
 * title screen does not draw from {@code ShaderManager}'s resource reload to the frames after the
 * title screen ({@code dev.gaius.browser.BrowserPipelineWarmup} holds the policy). Nothing about
 * what is compiled changes: the registered list, the shader sources and the compile path
 * ({@code PipelineCache.get}) are the vanilla ones; only the moment differs. The patch is five
 * small insertions that do not change control flow:
 * <ul>
 *   <li>{@code ShaderManager.loadConfigs} and every {@code load*(Identifier, Resource, ...)} loader
 *       report which pack provides each shader file, so that a reload with a shader from a
 *       non-vanilla pack keeps the vanilla behaviour (every pipeline compiled before the reload
 *       completes);</li>
 *   <li>the two {@code RenderPipelines.requiredPipelines()/optionalPipelines()} results pass
 *       through {@code BrowserPipelineWarmup.eager}, which returns the pipelines compiled now;</li>
 *   <li>{@code ShaderManager.apply} hands the cache to {@code BrowserPipelineWarmup.attach}
 *       right before {@code RenderSystem.setCurrentPipelineCache};</li>
 *   <li>{@code PipelineCache} implements {@code PipelineWarmTarget} (a bridge to its own
 *       {@code get}) and reports a miss to {@code BrowserPipelineWarmup.onMiss}.</li>
 * </ul>
 */
public final class RenderpearlShaderPatches {
    static final String SHADER_MANAGER = "net/minecraft/client/renderer/ShaderManager";
    static final String RENDER_PIPELINES = "net/minecraft/client/renderer/RenderPipelines";
    static final String PIPELINE_CACHE = "com/mojang/blaze3d/pipeline/PipelineCache";
    static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    static final String RESOURCE = "net/minecraft/server/packs/resources/Resource";
    static final String WARMUP = "dev/gaius/browser/BrowserPipelineWarmup";
    static final String WARM_TARGET = "dev/gaius/browser/PipelineWarmTarget";
    static final String LIST = "java/util/List";

    private RenderpearlShaderPatches() {
    }

    public static void patchShaderPipelineWarmup(String jar, Path root) throws IOException {
        ClassNode shaderManager = readClass(jar, root, SHADER_MANAGER);
        ClassNode cache = readClass(jar, root, PIPELINE_CACHE);
        patchShaderManager(shaderManager);
        patchPipelineCache(cache);
        writeClass(shaderManager, root, false);
        writeClass(cache, root, false);
        System.out.println("Deferred the render pipelines the title screen does not draw out of"
                + " ShaderManager's reload (dev.gaius.browser.BrowserPipelineWarmup)");
    }

    private static void patchShaderManager(ClassNode node) {
        // 1. Which pack provides each shader file of the reload.
        MethodNode loadConfigs = find(node, "loadConfigs", configsDescriptor(node));
        requireStatic(loadConfigs);
        InsnList begin = new InsnList();
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WARMUP, "beginConfigLoad", "()V", false));
        loadConfigs.instructions.insert(begin);
        int loaders = 0;
        for (MethodNode method : node.methods) {
            if (!isResourceLoader(method)) {
                continue;
            }
            InsnList note = new InsnList();
            note.add(new VarInsnNode(Opcodes.ALOAD, 1));
            note.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RESOURCE, "sourcePackId",
                    "()Ljava/lang/String;", false));
            note.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WARMUP, "noteShaderPack",
                    "(Ljava/lang/String;)V", false));
            method.instructions.insert(note);
            method.maxStack = Math.max(method.maxStack, 2);
            loaders++;
        }
        if (loaders < 2) {
            throw new IllegalStateException(node.name + ": expected the shader and include loaders,"
                    + " found " + loaders + " load(Identifier, Resource, ...) methods");
        }

        // 2. The two registered lists of the reload.
        int required = 0;
        int optional = 0;
        int attached = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC) {
                    continue;
                }
                boolean isRequired = call.owner.equals(RENDER_PIPELINES)
                        && call.name.equals("requiredPipelines") && call.desc.equals("()Ljava/util/List;");
                boolean isOptional = call.owner.equals(RENDER_PIPELINES)
                        && call.name.equals("optionalPipelines") && call.desc.equals("()Ljava/util/List;");
                if (isRequired || isOptional) {
                    InsnList filter = new InsnList();
                    filter.add(new InsnNode(isRequired ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                    filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WARMUP, "eager",
                            "(Ljava/util/List;Z)Ljava/util/List;", false));
                    method.instructions.insert(call, filter);
                    method.maxStack = Math.max(method.maxStack, 3);
                    if (isRequired) {
                        required++;
                    } else {
                        optional++;
                    }
                } else if (call.owner.equals(RENDER_SYSTEM) && call.name.equals("setCurrentPipelineCache")) {
                    // The new cache is on the stack: attach a copy of the reference.
                    InsnList attach = new InsnList();
                    attach.add(new InsnNode(Opcodes.DUP));
                    attach.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WARMUP, "attach",
                            "(Ljava/lang/Object;)V", false));
                    method.instructions.insertBefore(call, attach);
                    method.maxStack = Math.max(method.maxStack, 3);
                    attached++;
                }
            }
        }
        if (required != 1 || optional != 1 || attached != 1) {
            throw new IllegalStateException(node.name + ": expected one requiredPipelines, one"
                    + " optionalPipelines and one setCurrentPipelineCache call, found " + required + ", "
                    + optional + ", " + attached);
        }
    }

    private static void patchPipelineCache(ClassNode node) {
        MethodNode get = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("get") && method.desc.startsWith("(L") && (method.access & Opcodes.ACC_STATIC) == 0
                    && Type.getArgumentTypes(method.desc).length == 1
                    && Type.getReturnType(method.desc).getSort() == Type.OBJECT) {
                if (get != null) {
                    throw new IllegalStateException(node.name + ": several get(pipeline) methods");
                }
                get = method;
            }
        }
        if (get == null) {
            throw new IllegalStateException(node.name + ".get(pipeline) was not found");
        }
        String pipeline = Type.getArgumentTypes(get.desc)[0].getInternalName();

        // The miss path: the first branch of get() is `if (cached != null) return cached;`.
        JumpInsnNode hit = null;
        for (AbstractInsnNode instruction : get.instructions.toArray()) {
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNULL) {
                hit = jump;
                break;
            }
        }
        if (hit == null) {
            throw new IllegalStateException(node.name + ".get no longer tests the cached pipeline");
        }
        LabelNode miss = hit.label;
        AbstractInsnNode cursor = miss;
        while (cursor.getNext() != null && cursor.getNext().getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        InsnList onMiss = new InsnList();
        onMiss.add(new VarInsnNode(Opcodes.ALOAD, 1));
        onMiss.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WARMUP, "onMiss", "(Ljava/lang/Object;)V", false));
        get.instructions.insert(cursor, onMiss);
        get.maxStack = Math.max(get.maxStack, 2);

        // The bridge the warm-up calls.
        if (node.interfaces == null) {
            node.interfaces = new ArrayList<>();
        }
        if (!node.interfaces.contains(WARM_TARGET)) {
            node.interfaces.add(WARM_TARGET);
        }
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, "gaiusWarm",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, pipeline));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, get.name, get.desc, false));
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
        bridge.maxStack = 2;
        bridge.maxLocals = 2;
        node.methods.add(bridge);
    }

    /** {@code loadConfigs(ResourceManager)}: the only static method taking one ResourceManager. */
    private static String configsDescriptor(ClassNode node) {
        String found = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("loadConfigs") && (method.access & Opcodes.ACC_STATIC) != 0) {
                if (found != null) {
                    throw new IllegalStateException(node.name + ": several loadConfigs methods");
                }
                found = method.desc;
            }
        }
        if (found == null) {
            throw new IllegalStateException(node.name + ".loadConfigs was not found");
        }
        return found;
    }

    private static boolean isResourceLoader(MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) == 0 || !method.name.startsWith("load")
                || method.name.startsWith("lambda$") || method.name.equals("loadConfigs")) {
            return false;
        }
        Type[] arguments = Type.getArgumentTypes(method.desc);
        return arguments.length >= 2 && arguments[1].getSort() == Type.OBJECT
                && arguments[1].getInternalName().equals(RESOURCE)
                && Type.getReturnType(method.desc).getSort() == Type.VOID;
    }

    private static void requireStatic(MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException(method.name + " is no longer static");
        }
    }
}
