package dev.gaius.tools.patches.render;

import static dev.gaius.tools.patches.support.LegacyPatchSupport.find;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.read;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.replace;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.requireOne;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.write;

import dev.gaius.tools.BuildTarget;
import dev.gaius.tools.PatchRegistry;
import dev.gaius.tools.VersionProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The render domain of the Minecraft12111BrowserPatcher step, the {@code legacy-12111} patch set
 * arm ({@code patches/render}): on WebAssembly GC, the render device factory of the
 * blaze3d-direct-0 seam and the line width vertex format alignment.
 *
 * <p>{@code Minecraft12111BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft12111BrowserPatcher.<method>}); the ASM helpers that
 * other domains of that step share live in LegacyPatchSupport and are imported
 * statically.
 */
public final class LegacyRenderPatches {
    private LegacyRenderPatches() {
    }

    /**
     * The render seam's registration (REQUIRED): 1.21.11 has no backend loop, and
     * {@code RenderSystem.initRenderer(JIZLShaderSource;Z)V} constructs its one device at bytecode
     * 0-11:
     * <pre>new GlDevice; dup; lload_0; iload_2; iload_3; aload 4; iload 5;
     * invokespecial GlDevice.&lt;init&gt;(JIZLShaderSource;Z)V; putstatic DEVICE</pre>
     * The allocation and the {@code dup} go and the constructor call becomes an
     * {@code invokestatic} of the seam's {@code entry.deviceFactory} with the same arguments in
     * the same order, which leaves one {@code GpuDevice} for {@code putstatic DEVICE}: no branch
     * changes, the stack map stays valid and the stack only shrinks. The factory
     * ({@code D0Devices.create}) returns the WebGPU device when the page asks for it and has one,
     * else {@code new GlDevice(...)} with the original arguments. The match fails closed. A profile
     * without a render seam keeps {@code GlDevice}.
     */
    public static void patchInitRendererDeviceFactory(String jar, Path root) throws IOException {
        SeamEntry factory = SeamEntry.resolve("deviceFactory");
        if (factory == null) {
            return;
        }
        String owner = "com/mojang/blaze3d/systems/RenderSystem";
        String glDevice = "com/mojang/blaze3d/opengl/GlDevice";
        String shaderSource = "Lcom/mojang/blaze3d/shaders/ShaderSource;";
        String constructor = "(JIZ" + shaderSource + "Z)V";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "initRenderer", constructor);
        TypeInsnNode allocation = null;
        MethodInsnNode call = null;
        int allocations = 0;
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals(glDevice)) {
                allocation = type;
                allocations++;
            } else if (instruction instanceof MethodInsnNode invoke && invoke.getOpcode() == Opcodes.INVOKESPECIAL
                    && invoke.owner.equals(glDevice) && invoke.name.equals("<init>")) {
                call = invoke;
                calls++;
            }
        }
        requireOne("RenderSystem.initRenderer GlDevice allocation", allocations);
        requireOne("RenderSystem.initRenderer GlDevice constructor call", calls);
        if (!call.desc.equals(constructor)) {
            throw new IllegalStateException("RenderSystem.initRenderer constructs GlDevice" + call.desc);
        }
        AbstractInsnNode dup = nextOpcode(allocation);
        if (dup == null || dup.getOpcode() != Opcodes.DUP) {
            throw new IllegalStateException("RenderSystem.initRenderer: no dup after new GlDevice");
        }
        // The arguments between dup and the call: lload_0, iload_2, iload_3, aload 4, iload 5.
        int[] opcodes = {Opcodes.LLOAD, Opcodes.ILOAD, Opcodes.ILOAD, Opcodes.ALOAD, Opcodes.ILOAD};
        int[] slots = {0, 2, 3, 4, 5};
        AbstractInsnNode argument = nextOpcode(dup);
        for (int i = 0; i < opcodes.length; i++, argument = nextOpcode(argument)) {
            if (!(argument instanceof VarInsnNode load) || load.getOpcode() != opcodes[i] || load.var != slots[i]) {
                throw new IllegalStateException("RenderSystem.initRenderer: unexpected GlDevice arguments");
            }
        }
        AbstractInsnNode store = nextOpcode(call);
        if (argument != call || !(store instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                || !field.owner.equals(owner) || !field.name.equals("DEVICE")
                || !field.desc.equals("Lcom/mojang/blaze3d/systems/GpuDevice;")) {
            throw new IllegalStateException("RenderSystem.initRenderer: the GlDevice is not stored in DEVICE");
        }
        method.instructions.remove(allocation);
        method.instructions.remove(dup);
        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, factory.owner(), factory.method(),
                "(JIZ" + shaderSource + "Z)Lcom/mojang/blaze3d/systems/GpuDevice;", false));
        write(node, root.resolve(owner + ".class"));
        System.out.println("Made RenderSystem.initRenderer create its device through render seam "
                + factory.generation() + " (" + factory.owner().replace('/', '.') + "." + factory.method() + ")");
    }

    /**
     * The vertex layout the seam needs (REQUIRED):
     * {@code DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH} is Position (3 floats), Color (4
     * ubytes), Normal (3 bytes), LineWidth (float): LineWidth at offset 19, a stride of 23, which
     * WebGPU cannot address (attribute offsets and strides in whole 4-byte words). Before the
     * {@code ldc "LineWidth"} of the builder chain whose previous element is Normal the patch puts
     * <pre>invokestatic &lt;entry.lineWidthPadding&gt;()I; invokevirtual VertexFormat$Builder.padding(I)</pre>
     * The seam returns 1 when the page asks for WebGPU (LineWidth at 20, stride 24, the layout of
     * vanilla's other formats with a 3-byte normal) and 0 otherwise, which leaves vanilla's layout
     * ({@code padding(0)} adds nothing). Vertex writers and the GL backend take offsets and stride
     * from the format; no vertex data of this format is persisted. A profile without a render seam
     * keeps the format as it is.
     */
    public static void patchLineWidthVertexFormatAlignment(String jar, Path root) throws IOException {
        SeamEntry padding = SeamEntry.resolve("lineWidthPadding");
        if (padding == null) {
            return;
        }
        String owner = "com/mojang/blaze3d/vertex/DefaultVertexFormat";
        String builder = "com/mojang/blaze3d/vertex/VertexFormat$Builder";
        ClassNode node = read(jar, owner + ".class");
        MethodNode clinit = find(node, "<clinit>", "()V");
        AbstractInsnNode target = null;
        int matches = 0;
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (!(instruction instanceof LdcInsnNode ldc) || !"LineWidth".equals(ldc.cst)) {
                continue;
            }
            // ... ldc "Normal"; getstatic NORMAL; invokevirtual add; [here] ldc "LineWidth"
            AbstractInsnNode add = previousOpcode(instruction);
            AbstractInsnNode element = previousOpcode(add);
            AbstractInsnNode name = previousOpcode(element);
            if (add instanceof MethodInsnNode invoke && invoke.owner.equals(builder) && invoke.name.equals("add")
                    && element instanceof FieldInsnNode field && field.name.equals("NORMAL")
                    && name instanceof LdcInsnNode normal && "Normal".equals(normal.cst)) {
                target = instruction;
                matches++;
            }
        }
        requireOne("DefaultVertexFormat Normal + LineWidth format", matches);
        InsnList insert = new InsnList();
        insert.add(new MethodInsnNode(Opcodes.INVOKESTATIC, padding.owner(), padding.method(), "()I", false));
        insert.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, builder, "padding", "(I)L" + builder + ";", false));
        clinit.instructions.insertBefore(target, insert);
        // builder + padding is two slots; the add calls of the chain already need three.
        clinit.maxStack = Math.max(clinit.maxStack, 3);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Aligned DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH through render seam "
                + padding.generation() + " (" + padding.owner().replace('/', '.') + "." + padding.method() + ")");
    }

    /**
     * An entry of the profile's render seam: {@code renderSeam} of the version profile and
     * {@code entry.<name>} of {@code port/mc/seams/<renderSeam>/seam.json}, a static method as
     * {@code package.Class.method}. {@code null} when the profile selects no seam or the seam's
     * {@code registration.targets} leave out this build's target.
     */
    private record SeamEntry(String generation, String owner, String method) {
        private static final Pattern SEAM = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
        private static final Pattern ENTRY =
                Pattern.compile("((?:[a-z][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_$]*)\\.([a-z][A-Za-z0-9_]*)");

        static SeamEntry resolve(String name) {
            VersionProfile profile = VersionProfile.forId(PatchRegistry.profile());
            String generation = profile.renderSeam();
            if (generation.equals("none")) {
                System.out.println("Minecraft " + PatchRegistry.profile() + " selects no render seam; "
                        + "keeping vanilla's GL device");
                return null;
            }
            if (!SEAM.matcher(generation).matches()) {
                throw new IllegalStateException(profile.path() + " has no valid renderSeam: " + generation);
            }
            Path manifest = profile.path().getParent().getParent().resolve("mc/seams/" + generation + "/seam.json");
            if (!Files.isRegularFile(manifest)) {
                throw new IllegalStateException("render seam " + generation + " has no " + manifest);
            }
            if (!(VersionProfile.readJson(manifest) instanceof Map<?, ?> json)
                    || !(json.get("entry") instanceof Map<?, ?> entry)
                    || !(json.get("registration") instanceof Map<?, ?> registration)) {
                throw new IllegalStateException(manifest + " needs the objects entry and registration");
            }
            Matcher matcher = entry.get(name) instanceof String value ? ENTRY.matcher(value) : null;
            if (matcher == null || !matcher.matches()) {
                throw new IllegalStateException(manifest + " has no valid entry." + name + ": " + entry.get(name));
            }
            if (!(registration.get("targets") instanceof List<?> targets) || !targets.contains(BuildTarget.name())) {
                System.out.println("Render seam " + generation + " is not registered on the " + BuildTarget.name()
                        + " target (registration.targets " + registration.get("targets") + "); keeping vanilla");
                return null;
            }
            return new SeamEntry(generation, matcher.group(1).replace('.', '/'), matcher.group(2));
        }
    }
}
