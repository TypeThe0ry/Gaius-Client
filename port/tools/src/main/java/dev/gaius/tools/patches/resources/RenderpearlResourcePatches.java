package dev.gaius.tools.patches.resources;

import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.find;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.read;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.write;

import java.io.IOException;
import java.nio.file.Path;
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
 * The resources domain of the Minecraft263BrowserPatcher step, the tail of the modern chain for
 * the renderpearl render seam ({@code patches/resources}), work package P8.
 *
 * <ul>
 *   <li>{@link #patchVanillaPackResourcesSingleLayer}: 26.3's VanillaPackResourcesBuilder builds
 *       one "full" FixedPathPackResources plus one per layer (the client pushes a jar layer and an
 *       asset-index layer). The Gaius FixedPathPackResources override serves the whole validated
 *       browser asset archive and ignores the builder's paths, so every layer would expose the
 *       complete vanilla pack again: duplicated resource stacks (fonts, sounds, languages,
 *       atlases) and a resource list parsed once per layer. The browser vanilla pack keeps
 *       26.2's shape instead: {@code build} returns the full resources as the only layer.</li>
 * </ul>
 *
 * <p>{@code Minecraft263BrowserPatcher} registers it under its historical id
 * ({@code UiPatches263.patchVanillaPackResourcesSingleLayer}) with the other patch of that group
 * ({@code RenderpearlAudioPatches}); its profiles come from the spec. Classes are read and
 * written through {@code RenderpearlPatchSupport}.
 */
public final class RenderpearlResourcePatches {
    static final String VANILLA_PACK_BUILDER = "net/minecraft/server/packs/VanillaPackResourcesBuilder";
    static final String VANILLA_PACK = "net/minecraft/server/packs/VanillaPackResources";
    static final String FIXED_PATH_PACK = "net/minecraft/server/packs/FixedPathPackResources";
    static final String FIXED_PATH_BUILDER = FIXED_PATH_PACK + "$Builder";
    static final String PACK_LOCATION = "net/minecraft/server/packs/PackLocationInfo";

    private RenderpearlResourcePatches() {
    }

    /**
     * {@code VanillaPackResourcesBuilder.build(PackLocationInfo)} becomes
     * {@code full = fullBuilder.build(location); return new VanillaPackResources(full, List.of(full));}.
     * The full builder receives every root and per-type path of every layer (vanilla
     * {@code forLastLayer} pushes to it and to the last layer), so the full resources are a
     * superset of each layer; only cross-layer resource stacking is given up. The browser needs
     * the Gaius FixedPathPackResources override in the jar; build-overlays.sh folds the client
     * overrides in before the patchers run, and legacy/quickcheck/profile_263_ui.py and
     * legacy/vanilla-pack-layers-263-smoke.mjs check the result (this patcher also runs on the plain
     * vanilla jar in patch-registry-smoke, so it does not insist on the override itself).
     */
    public static void patchVanillaPackResourcesSingleLayer(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, VANILLA_PACK_BUILDER);
        FieldNode fullBuilder = null;
        boolean layers = false;
        for (FieldNode field : node.fields) {
            if (field.name.equals("fullBuilder") && field.desc.equals("L" + FIXED_PATH_BUILDER + ";")) {
                fullBuilder = field;
            }
            if (field.name.equals("layeredBuilders") && field.desc.equals("Ljava/util/List;")) {
                layers = true;
            }
        }
        if (fullBuilder == null || !layers) {
            throw new IllegalStateException(VANILLA_PACK_BUILDER
                    + " has no fullBuilder/layeredBuilders fields; the 26.3 layer shape changed");
        }
        MethodNode build = find(node, "build",
                "(L" + PACK_LOCATION + ";)L" + VANILLA_PACK + ";");
        int fullBuilds = 0;
        int packs = 0;
        for (AbstractInsnNode instruction = build.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(FIXED_PATH_BUILDER)
                    && call.name.equals("build")) {
                fullBuilds++;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(VANILLA_PACK)
                    && call.name.equals("<init>")
                    && call.desc.equals("(L" + FIXED_PATH_PACK + ";Ljava/util/List;)V")) {
                packs++;
            }
        }
        if (fullBuilds != 1 || packs != 1) {
            throw new IllegalStateException("VanillaPackResourcesBuilder.build shape changed: "
                    + fullBuilds + " FixedPathPackResources$Builder.build calls, " + packs
                    + " VanillaPackResources(FixedPathPackResources, List) constructions");
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, VANILLA_PACK_BUILDER, fullBuilder.name,
                fullBuilder.desc));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FIXED_PATH_BUILDER, "build",
                "(L" + PACK_LOCATION + ";)L" + FIXED_PATH_PACK + ";", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new TypeInsnNode(Opcodes.NEW, VANILLA_PACK));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of",
                "(Ljava/lang/Object;)Ljava/util/List;", true));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, VANILLA_PACK, "<init>",
                "(L" + FIXED_PATH_PACK + ";Ljava/util/List;)V", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        build.instructions = code;
        build.tryCatchBlocks.clear();
        build.localVariables = null;
        build.visibleLocalVariableAnnotations = null;
        build.invisibleLocalVariableAnnotations = null;
        build.maxLocals = Math.max(build.maxLocals, 3);
        build.maxStack = Math.max(build.maxStack, 4);
        write(node, root);
    }
}
