package dev.gaius.tools.patches.render;

import static dev.gaius.tools.patches.support.ModernPatchSupport.JDK_COMPAT;
import static dev.gaius.tools.patches.support.ModernPatchSupport.fieldBefore;
import static dev.gaius.tools.patches.support.ModernPatchSupport.find;
import static dev.gaius.tools.patches.support.ModernPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ModernPatchSupport.read;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replace;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replaceCall;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replaceVoid;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireOne;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireReceiverLoad;
import static dev.gaius.tools.patches.support.ModernPatchSupport.write;
import static dev.gaius.tools.patches.support.ModernPatchSupport.writeComputeFrames;

import dev.gaius.tools.BuildTarget;
import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import dev.gaius.tools.VersionProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The render domain of the Minecraft262BrowserPatcher step, the {@code modern} patch set arm
 * ({@code patches/render}): the preferred graphics API and the Vulkan backend stubs,
 * GL device capabilities and mapped buffer views, the framerate limiter, live frame targeting,
 * the staging and uber GPU buffers, and the terrain batch registration.
 *
 * <p>{@code Minecraft262BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft262BrowserPatcher.<method>}); the ASM helpers that other
 * domains of that step share live in ModernPatchSupport and are imported statically.
 */
public final class ModernRenderPatches {
    private ModernRenderPatches() {
    }

    /** Batched terrain draws, aligned heaps and the neighbor gate (see {@link TerrainBatchPatches}). */
    public static void terrainBatchPatches(String jar, Path root, String minecraftVersion)
            throws IOException {
        TerrainBatchPatches.apply(jar, root, minecraftVersion);
    }

    /** Memoized uber buffer slices of the terrain draw extraction (see {@link SectionSliceCachePatches}). */
    public static void patchSectionSliceCache(String jar, Path root) throws IOException {
        SectionSliceCachePatches.apply(jar, root);
    }

    public static void patchUberGpuBufferNodeCleanup(String jar, Path root)
            throws IOException {
        String owner = "com/mojang/blaze3d/vertex/UberGpuBuffer";
        String pair = "com/mojang/datafixers/util/Pair";
        String allocator = "com/mojang/blaze3d/vertex/TlsfAllocator";
        String heap = owner + "$UberGpuBufferHeap";
        // 26.3: GpuDevice and GpuBuffer are renderpearl interfaces, so the emitted
        // GpuBuffer.close() must be INVOKEINTERFACE (26.2: INVOKEVIRTUAL on the class).
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String gpuBufferKey = "com/mojang/blaze3d/buffers/GpuBuffer";
        String gpuBuffer = symbols.renderType(gpuBufferKey);
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "uploadStagedAllocations",
                "(L" + symbols.renderType("com/mojang/blaze3d/systems/GpuDevice") + ";"
                        + "Lcom/mojang/blaze3d/vertex/StagingBuffer$Uploader;)Z");
        ClassNode heapNode = read(jar, heap + ".class");
        if (heapNode.fields.stream().noneMatch(field -> field.name.equals("gpuBuffer")
                && field.desc.equals("L" + gpuBuffer + ";"))) {
            throw new IllegalStateException(heap + ".gpuBuffer is no longer a " + gpuBuffer);
        }

        MethodInsnNode finishUpload = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("dev/gaius/browser/BrowserRenderScheduler")
                    && call.name.equals("finishUploadBuffer")
                    && call.desc.equals("(Ljava/lang/Object;Ljava/util/Map;Ljava/util/Set;)V")) {
                finishUpload = call;
                break;
            }
        }
        if (finishUpload == null) {
            throw new IllegalStateException(
                    "UberGpuBuffer upload budget patch must run before node cleanup patch");
        }

        MethodInsnNode cleanupIterator = null;
        for (AbstractInsnNode instruction = finishUpload.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("iterator")
                    && call.desc.equals("()Ljava/util/Iterator;")) {
                cleanupIterator = call;
            }
        }
        if (cleanupIterator == null) {
            throw new IllegalStateException("UberGpuBuffer free-heap iterator was not found");
        }
        FieldInsnNode nodesField = fieldBefore(cleanupIterator);
        AbstractInsnNode cleanupStart = previousOpcode(nodesField);
        requireReceiverLoad("UberGpuBuffer free-heap iterator", cleanupStart, nodesField);

        VarInsnNode resultLoad = null;
        for (AbstractInsnNode instruction = cleanupIterator.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ILOAD
                    && load.var == 3) {
                resultLoad = load;
            }
        }
        if (resultLoad == null) {
            throw new IllegalStateException("UberGpuBuffer upload result load was not found");
        }

        int nodeCountLocal = method.maxLocals++;
        int cursorLocal = method.maxLocals++;
        int scannedLocal = method.maxLocals++;
        int releasedLocal = method.maxLocals++;
        int pairLocal = method.maxLocals++;
        int allocatorLocal = method.maxLocals++;
        LabelNode loop = new LabelNode();
        LabelNode retain = new LabelNode();
        LabelNode normalized = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode unchangedResult = new LabelNode();
        InsnList cleanup = new InsnList();
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "nodes", "Ljava/util/List;"));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, nodeCountLocal));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, nodeCountLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "beginUberNodeCleanup",
                "(Ljava/lang/Object;I)I",
                false));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, cursorLocal));
        cleanup.add(new InsnNode(Opcodes.ICONST_0));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, scannedLocal));
        cleanup.add(new InsnNode(Opcodes.ICONST_0));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, releasedLocal));
        cleanup.add(loop);
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, nodeCountLocal));
        cleanup.add(new JumpInsnNode(Opcodes.IFEQ, done));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "shouldCleanUberNode",
                "(Ljava/lang/Object;)Z",
                false));
        cleanup.add(new JumpInsnNode(Opcodes.IFEQ, done));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "nodes", "Ljava/util/List;"));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "get",
                "(I)Ljava/lang/Object;",
                true));
        cleanup.add(new TypeInsnNode(Opcodes.CHECKCAST, pair));
        cleanup.add(new VarInsnNode(Opcodes.ASTORE, pairLocal));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, pairLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, pair, "getFirst", "()Ljava/lang/Object;", false));
        cleanup.add(new TypeInsnNode(Opcodes.CHECKCAST, allocator));
        cleanup.add(new VarInsnNode(Opcodes.ASTORE, allocatorLocal));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, allocatorLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, allocator, "isCompletelyFree", "()Z", false));
        cleanup.add(new JumpInsnNode(Opcodes.IFEQ, retain));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, pairLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, pair, "getSecond", "()Ljava/lang/Object;", false));
        cleanup.add(new TypeInsnNode(Opcodes.CHECKCAST, heap));
        cleanup.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                heap,
                "gpuBuffer",
                "L" + gpuBuffer + ";"));
        cleanup.add(new MethodInsnNode(
                symbols.invokeOpcode(gpuBufferKey),
                gpuBuffer,
                "close",
                "()V",
                symbols.isInterface(gpuBufferKey)));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "nodes", "Ljava/util/List;"));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "remove",
                "(I)Ljava/lang/Object;",
                true));
        cleanup.add(new InsnNode(Opcodes.POP));
        cleanup.add(new org.objectweb.asm.tree.IincInsnNode(nodeCountLocal, -1));
        cleanup.add(new org.objectweb.asm.tree.IincInsnNode(releasedLocal, 1));
        cleanup.add(new JumpInsnNode(Opcodes.GOTO, normalized));
        cleanup.add(retain);
        cleanup.add(new org.objectweb.asm.tree.IincInsnNode(cursorLocal, 1));
        cleanup.add(normalized);
        cleanup.add(new org.objectweb.asm.tree.IincInsnNode(scannedLocal, 1));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, nodeCountLocal));
        cleanup.add(new JumpInsnNode(Opcodes.IFEQ, done));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, nodeCountLocal));
        cleanup.add(new JumpInsnNode(Opcodes.IF_ICMPLT, loop));
        cleanup.add(new InsnNode(Opcodes.ICONST_0));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, cursorLocal));
        cleanup.add(new JumpInsnNode(Opcodes.GOTO, loop));
        cleanup.add(done);
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, releasedLocal));
        cleanup.add(new JumpInsnNode(Opcodes.IFEQ, unchangedResult));
        cleanup.add(new InsnNode(Opcodes.ICONST_1));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, 3));
        cleanup.add(unchangedResult);
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, nodeCountLocal));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, scannedLocal));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, releasedLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "finishUberNodeCleanup",
                "(Ljava/lang/Object;IIII)V",
                false));
        method.instructions.insertBefore(cleanupStart, cleanup);
        for (AbstractInsnNode instruction = cleanupStart; instruction != resultLoad;) {
            AbstractInsnNode next = instruction.getNext();
            method.instructions.remove(instruction);
            instruction = next;
        }
        writeComputeFrames(node, root.resolve(owner + ".class"));
        System.out.println("Bounded Minecraft " + (symbols.renderpearl() ? "renderpearl" : "blaze3d")
                + " UberGpuBuffer heap cleanup");
    }

    public static void patchPreferredGraphicsApi(String jar, Path root) throws IOException {
        // 26.3 moved both types to renderpearl (GpuBackend is an interface there); ModernSymbols
        // returns the 26.2 names unchanged on 26.2.
        ModernSymbols symbols = ModernSymbols.cached(jar);
        String gpuBackend = symbols.renderType("com/mojang/blaze3d/systems/GpuBackend");
        String glBackend = symbols.renderType("com/mojang/blaze3d/opengl/GlBackend");
        String owner = "net/minecraft/client/PreferredGraphicsApi";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "getBackendsToTry",
                "()[L" + gpuBackend + ";");
        RenderSeamEntry seam = RenderSeamEntry.resolve();
        if (seam != null) {
            // The profile's render seam (seam.json "entry.backendsToTry") decides:
            // WebGPU first when the page asks for it (?gpu=webgpu) and its device is ready, then
            // OpenGL; vanilla's backend loop falls back on a BackendCreationException. The seam
            // classes join the client overlay after the patchers (build-overlays.sh).
            InsnList delegate = new InsnList();
            delegate.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    seam.owner(),
                    seam.method(),
                    "()[L" + gpuBackend + ";",
                    false));
            delegate.add(new InsnNode(Opcodes.ARETURN));
            replace(method, delegate, 1, 1);
            write(node, root.resolve(owner + ".class"));
            System.out.println("Made Minecraft " + PatchRegistry.profile()
                    + " take its graphics backends from render seam " + seam.generation()
                    + " (" + seam.owner().replace('/', '.') + "." + seam.method() + ")");
            return;
        }
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new TypeInsnNode(
                Opcodes.ANEWARRAY, gpuBackend));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new TypeInsnNode(Opcodes.NEW, glBackend));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                glBackend,
                "<init>",
                "()V",
                false));
        code.add(new InsnNode(Opcodes.AASTORE));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 5, 1);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Forced Minecraft 26.2 browser graphics backend to OpenGL");
    }

    /**
     * The backend list of the profile's render seam: {@code renderSeam} of the version profile
     * ({@link VersionProfile}) and {@code entry.backendsToTry} of
     * {@code port/mc/seams/<renderSeam>/seam.json}. {@code null} when the profile selects no seam,
     * or when the seam's {@code registration.targets} leave out the TeaVM target of this build: the
     * GL-only list then stays. 26.3 takes WebGPU on both internal build targets
     * (renderpearl-1 lists js and wasmgc); the JavaScript target of the older SPI generations
     * keeps its internal GL backend, so blaze3d-backend-1 (26.2) lists wasmgc only for release.
     */
    private record RenderSeamEntry(String generation, String owner, String method) {
        private static final Pattern SEAM = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
        private static final Pattern ENTRY =
                Pattern.compile("((?:[a-z][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_$]*)\\.([a-z][A-Za-z0-9_]*)");

        static RenderSeamEntry resolve() {
            VersionProfile profile = VersionProfile.forId(PatchRegistry.profile());
            String generation = profile.renderSeam();
            if (generation.equals("none")) {
                return null;
            }
            if (!SEAM.matcher(generation).matches()) {
                throw new IllegalStateException(profile.path() + " has no valid renderSeam: " + generation);
            }
            Path manifest = profile.path().getParent().getParent()
                    .resolve("mc/seams/" + generation + "/seam.json");
            if (!Files.isRegularFile(manifest)) {
                throw new IllegalStateException("render seam " + generation + " has no " + manifest);
            }
            if (!(VersionProfile.readJson(manifest) instanceof java.util.Map<?, ?> json)
                    || !(json.get("entry") instanceof java.util.Map<?, ?> entry)
                    || !(json.get("registration") instanceof java.util.Map<?, ?> registration)) {
                throw new IllegalStateException(manifest + " needs the objects entry and registration");
            }
            Matcher matcher = entry.get("backendsToTry") instanceof String backendsToTry
                    ? ENTRY.matcher(backendsToTry) : null;
            if (matcher == null || !matcher.matches()) {
                throw new IllegalStateException(manifest + " has no valid entry.backendsToTry: "
                        + entry.get("backendsToTry"));
            }
            if (!(registration.get("targets") instanceof java.util.List<?> targets) || targets.isEmpty()
                    || !targets.stream().allMatch(target -> BuildTarget.JS.equals(target)
                            || BuildTarget.WASM_GC.equals(target))) {
                throw new IllegalStateException(manifest + " needs registration.targets, a list of "
                        + BuildTarget.JS + " and/or " + BuildTarget.WASM_GC);
            }
            if (!targets.contains(BuildTarget.name())) {
                System.out.println("Render seam " + generation + " is not registered on the "
                        + BuildTarget.name() + " target (registration.targets " + targets
                        + "); keeping the GL backend list");
                return null;
            }
            return new RenderSeamEntry(generation, matcher.group(1).replace('.', '/'), matcher.group(2));
        }
    }

    public static void patchVulkanBackend(String jar, Path root) throws IOException {
        if (ModernSymbols.cached(jar).renderpearl()) {
            // 26.3 moved VulkanBackend and changed its API (no setWindowHints, a static
            // checkBackendAvailable, SDL windows); RenderPatches263.patchVulkanBackend stubs it.
            PatchRegistry.dropped("Minecraft262BrowserPatcher.patchVulkanBackend", jar,
                    "com/mojang/blaze3d/vulkan/VulkanBackend.class");
            return;
        }
        String owner = "com/mojang/blaze3d/vulkan/VulkanBackend";
        String exception = "com/mojang/blaze3d/systems/BackendCreationException";
        String reason = exception + "$Reason";
        ClassNode node = read(jar, owner + ".class");
        replaceVoid(find(node, "<clinit>", "()V"));
        replaceVoid(find(node, "setWindowHints", "()V"));

        MethodNode check = find(node, "checkBackendAvailable", "()L" + exception + ";");
        InsnList unavailable = new InsnList();
        unavailable.add(new TypeInsnNode(Opcodes.NEW, exception));
        unavailable.add(new InsnNode(Opcodes.DUP));
        unavailable.add(new org.objectweb.asm.tree.LdcInsnNode(
                "Vulkan is unavailable in the browser runtime"));
        unavailable.add(new FieldInsnNode(
                Opcodes.GETSTATIC, reason, "VULKAN_LOADER_MISSING", "L" + reason + ";"));
        unavailable.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                exception,
                "<init>",
                "(Ljava/lang/String;L" + reason + ";)V",
                false));
        unavailable.add(new InsnNode(Opcodes.ARETURN));
        replace(check, unavailable, 4, 0);

        MethodNode create = find(node, "createDevice",
                "(JLcom/mojang/blaze3d/shaders/ShaderSource;"
                        + "Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)"
                        + "Lcom/mojang/blaze3d/systems/GpuDevice;");
        InsnList reject = new InsnList();
        reject.add(new TypeInsnNode(Opcodes.NEW, exception));
        reject.add(new InsnNode(Opcodes.DUP));
        reject.add(new org.objectweb.asm.tree.LdcInsnNode(
                "Vulkan is unavailable in the browser runtime"));
        reject.add(new FieldInsnNode(
                Opcodes.GETSTATIC, reason, "VULKAN_LOADER_MISSING", "L" + reason + ";"));
        reject.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                exception,
                "<init>",
                "(Ljava/lang/String;L" + reason + ";)V",
                false));
        reject.add(new InsnNode(Opcodes.ATHROW));
        replace(create, reject, 4, create.maxLocals);
        write(node, root.resolve(owner + ".class"));
    }

    public static void patchGlDeviceCapabilities(String jar, Path root) throws IOException {
        String owner = ModernSymbols.cached(jar).renderType("com/mojang/blaze3d/opengl/GlDevice");
        ClassNode node = read(jar, owner + ".class");
        MethodNode initializer = find(node, "<clinit>", "()V");
        int returns = 0;
        for (AbstractInsnNode instruction = initializer.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList disable = new InsnList();
            for (String field : new String[] {
                    "USE_GL_ARB_base_instance",
                    "USE_GL_ARB_draw_indirect",
                    "USE_GL_ARB_multi_draw_indirect",
                    "USE_GL_ARB_shader_draw_parameters"
            }) {
                disable.add(new InsnNode(Opcodes.ICONST_0));
                disable.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field, "Z"));
            }
            initializer.instructions.insertBefore(instruction, disable);
            returns++;
        }
        if (returns == 0) {
            throw new IllegalStateException("GlDevice initializer return was not found");
        }
        initializer.maxStack = Math.max(initializer.maxStack, 1);
        write(node, root.resolve(owner + ".class"));
    }

    /** Registers the browser terrain UBO with the 26.2 Blaze3D program layout. */
    public static void patchTerrainSectionUniformBlock(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/opengl/GlProgram";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "setupBindGroupLayouts",
                "(Ljava/util/List;)V");
        int programLocal = method.maxLocals;
        int blockLocal = programLocal + 1;
        int bindingLocal = programLocal + 2;
        method.maxLocals += 3;

        LabelNode done = new LabelNode();
        InsnList register = new InsnList();
        register.add(new VarInsnNode(Opcodes.ALOAD, 0));
        register.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "programId", "I"));
        register.add(new VarInsnNode(Opcodes.ISTORE, programLocal));
        register.add(new VarInsnNode(Opcodes.ILOAD, programLocal));
        register.add(new LdcInsnNode("GaiusChunkSections"));
        register.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL33C",
                "glGetUniformBlockIndex",
                "(ILjava/lang/CharSequence;)I",
                false));
        register.add(new VarInsnNode(Opcodes.ISTORE, blockLocal));
        register.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        register.add(new InsnNode(Opcodes.ICONST_M1));
        register.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, done));
        register.add(new LdcInsnNode(35375));
        register.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL33C", "glGetInteger", "(I)I", false));
        register.add(new InsnNode(Opcodes.ICONST_1));
        register.add(new InsnNode(Opcodes.ISUB));
        register.add(new VarInsnNode(Opcodes.ISTORE, bindingLocal));
        register.add(new VarInsnNode(Opcodes.ILOAD, programLocal));
        register.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        register.add(new VarInsnNode(Opcodes.ILOAD, bindingLocal));
        register.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/GL33C",
                "glUniformBlockBinding",
                "(III)V",
                false));
        register.add(new VarInsnNode(Opcodes.ALOAD, 0));
        register.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "uniformsByName", "Ljava/util/Map;"));
        register.add(new LdcInsnNode("GaiusChunkSections"));
        register.add(new TypeInsnNode(Opcodes.NEW, "com/mojang/blaze3d/opengl/Uniform$Ubo"));
        register.add(new InsnNode(Opcodes.DUP));
        register.add(new VarInsnNode(Opcodes.ILOAD, bindingLocal));
        register.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "com/mojang/blaze3d/opengl/Uniform$Ubo",
                "<init>",
                "(I)V",
                false));
        register.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true));
        register.add(new InsnNode(Opcodes.POP));
        register.add(done);
        method.instructions.insert(register);
        writeComputeFrames(node, root.resolve(owner + ".class"));
    }

    public static void patchFramerateLimiter(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/FramerateLimiter";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "limitDisplayFPS", "(I)V");
        int removed = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/Thread")
                    && call.name.equals("onSpinWait")
                    && call.desc.equals("()V")) {
                method.instructions.remove(call);
                removed++;
            }
            instruction = next;
        }
        if (removed == 0) {
            throw new IllegalStateException("FramerateLimiter spin call was not found");
        }
        write(node, root.resolve(owner + ".class"));
    }

    public static void patchGlBufferMappedViewRanges(String jar, Path root) throws IOException {
        if (ModernSymbols.cached(jar).renderpearl()) {
            // 26.3 no longer sets GL_MAP_FLUSH_EXPLICIT_BIT, so this 26.2 patch would never
            // flush; RenderPatches263.patchGlBufferExplicitFlush adds the bit and the ranges.
            PatchRegistry.dropped("Minecraft262BrowserPatcher.patchGlBufferMappedViewRanges", jar,
                    "com/mojang/blaze3d/opengl/GlBuffer$Direct$1.class");
            return;
        }
        String directOwner = "com/mojang/blaze3d/opengl/GlBuffer$Direct";
        String closeOwner = directOwner + "$1";
        String mappedView = "com/mojang/blaze3d/buffers/GpuBufferSlice$MappedView";

        ClassNode direct = read(jar, directOwner + ".class");
        MethodNode map = find(direct, "map", "(JJZZ)L" + mappedView + ";");
        int constructors = 0;
        for (AbstractInsnNode instruction : map.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals(closeOwner)
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(L" + directOwner + ";)V")) {
                continue;
            }
            InsnList range = new InsnList();
            range.add(new VarInsnNode(Opcodes.LLOAD, 1));
            range.add(new VarInsnNode(Opcodes.LLOAD, 3));
            map.instructions.insertBefore(call, range);
            call.desc = "(L" + directOwner + ";JJ)V";
            constructors++;
        }
        requireOne("GlBuffer.Direct mapped-view close constructor", constructors);
        map.maxStack = Math.max(map.maxStack, 8);

        ClassNode close = read(jar, closeOwner + ".class");
        close.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mappedOffset", "J", null, null));
        close.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mappedLength", "J", null, null));

        MethodNode constructor = find(close, "<init>", "(L" + directOwner + ";)V");
        constructor.desc = "(L" + directOwner + ";JJ)V";
        InsnList init = new InsnList();
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new VarInsnNode(Opcodes.ALOAD, 1));
        init.add(new FieldInsnNode(
                Opcodes.PUTFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new InsnNode(Opcodes.ICONST_0));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, closeOwner, "closed", "Z"));
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new VarInsnNode(Opcodes.LLOAD, 2));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, closeOwner, "mappedOffset", "J"));
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new VarInsnNode(Opcodes.LLOAD, 4));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, closeOwner, "mappedLength", "J"));
        init.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, init, 3, 6);

        MethodNode run = find(close, "run", "()V");
        LabelNode active = new LabelNode();
        LabelNode unmap = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, closeOwner, "closed", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, active));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(active);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, closeOwner, "closed", "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, directOwner, "mappingFlags", "I"));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 16));
        code.add(new InsnNode(Opcodes.IAND));
        code.add(new JumpInsnNode(Opcodes.IFEQ, unmap));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                directOwner,
                "dsa",
                "Lcom/mojang/blaze3d/opengl/DirectStateAccess;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, directOwner, "handle", "()I", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, closeOwner, "mappedOffset", "J"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, closeOwner, "mappedLength", "J"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, directOwner, "usage", "()I", false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/DirectStateAccess",
                "flushMappedBufferRange",
                "(IJJI)V",
                false));
        code.add(unmap);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, directOwner, "unmap", "()V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(run, code, 7, 1);

        writeComputeFrames(direct, root.resolve(directOwner + ".class"));
        writeComputeFrames(close, root.resolve(closeOwner + ".class"));
        System.out.println("Patched GlBuffer mapped views to flush only their written ranges");
    }

    public static void patchLiveFrameTargeting(String jar, Path root)
            throws IOException {
        String minecraftOwner = "net/minecraft/client/Minecraft";
        ClassNode minecraft = read(jar, minecraftOwner + ".class");
        MethodNode renderFrame = find(minecraft, "renderFrame", "(Z)V");
        int deferred = 0;
        for (AbstractInsnNode instruction = renderFrame.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(minecraftOwner)
                    && call.name.equals("pick")
                    && call.desc.equals("(F)V")) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = "dev/gaius/browser/BrowserTargeting";
                call.name = "deferFramePick";
                call.desc = "(Lnet/minecraft/client/Minecraft;F)V";
                call.itf = false;
                deferred++;
            }
        }
        if (deferred != 1) {
            throw new IllegalStateException(
                    "Minecraft.renderFrame targeting deferral changed: " + deferred);
        }
        // The deferred pick is only refreshed by the GameRenderer half below, so Minecraft.class
        // is written only after that half has been validated and patched (both or neither).

        String owner = "net/minecraft/client/renderer/GameRenderer";
        ClassNode node = read(jar, owner + ".class");
        MethodNode extract = find(
                node,
                "extract",
                "(Lnet/minecraft/client/DeltaTracker;Z)V");
        // 26.2 passes a separate camera partial tick: extractCamera(DeltaTracker;FF)V. 26.3
        // extracts the camera with the world partial tick only, extractCamera(DeltaTracker;F)V,
        // and computes the camera entity's partial tick inside Camera.extractRenderState.
        boolean singlePartialTick = ModernSymbols.cached(jar).renderpearl();
        String extractCameraDescriptor = singlePartialTick
                ? "(Lnet/minecraft/client/DeltaTracker;F)V"
                : "(Lnet/minecraft/client/DeltaTracker;FF)V";
        MethodInsnNode extractCamera = null;
        MethodInsnNode extractLevel = null;
        for (AbstractInsnNode instruction = extract.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(owner)
                    && call.name.equals("extractCamera")
                    && call.desc.equals(extractCameraDescriptor)) {
                if (extractCamera != null) {
                    throw new IllegalStateException(
                            "GameRenderer.extract has multiple extractCamera calls");
                }
                extractCamera = call;
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/renderer/extract/LevelExtractor")
                    && call.name.equals("extract")
                    && call.desc.equals("(Lnet/minecraft/client/DeltaTracker;"
                            + "Lnet/minecraft/client/Camera;F)V")) {
                if (extractLevel != null) {
                    throw new IllegalStateException(
                            "GameRenderer.extract has multiple level extraction calls");
                }
                extractLevel = call;
            }
        }
        if (extractCamera == null) {
            throw new IllegalStateException(
                    "GameRenderer.extract current camera observation point was not found");
        }
        if (extractLevel == null) {
            throw new IllegalStateException(
                    "GameRenderer.extract current level extraction point was not found");
        }
        AbstractInsnNode worldPartialTick = extractLevel.getPrevious();
        while (worldPartialTick != null && worldPartialTick.getOpcode() < 0) {
            worldPartialTick = worldPartialTick.getPrevious();
        }
        if (!(worldPartialTick instanceof VarInsnNode worldPartialTickLoad)
                || worldPartialTickLoad.getOpcode() != Opcodes.FLOAD) {
            throw new IllegalStateException(
                    "GameRenderer.extract world partial tick load was not found");
        }
        AbstractInsnNode cameraPartialTick = extractCamera.getPrevious();
        while (cameraPartialTick != null && cameraPartialTick.getOpcode() < 0) {
            cameraPartialTick = cameraPartialTick.getPrevious();
        }
        if (singlePartialTick) {
            patchCurrentLiveFrameTargetingRefresh(jar, extract, extractCamera,
                    cameraPartialTick, worldPartialTickLoad);
            write(minecraft, root.resolve(minecraftOwner + ".class"));
            write(node, root.resolve(owner + ".class"));
            System.out.println("Moved Minecraft 26.3 frame targeting after camera extraction");
            return;
        }
        if (!(cameraPartialTick instanceof VarInsnNode cameraPartialTickLoad)
                || cameraPartialTickLoad.getOpcode() != Opcodes.FLOAD
                || cameraPartialTickLoad.var == worldPartialTickLoad.var) {
            throw new IllegalStateException(
                    "GameRenderer.extract camera partial tick load was not found");
        }

        InsnList refresh = new InsnList();
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "mainCamera",
                "Lnet/minecraft/client/Camera;"));
        refresh.add(new VarInsnNode(Opcodes.FLOAD, cameraPartialTickLoad.var));
        refresh.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserTargeting",
                "refreshFramePick",
                "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/Camera;F)V",
                false));
        extract.instructions.insert(extractCamera, refresh);
        extract.maxStack = Math.max(extract.maxStack, 3);
        write(minecraft, root.resolve(minecraftOwner + ".class"));
        write(node, root.resolve(owner + ".class"));
        System.out.println("Moved Minecraft 26.2 frame targeting after camera extraction");
    }

    /**
     * 26.3 GameRenderer half of patchLiveFrameTargeting: extractCamera receives the world
     * partial tick, so the camera entity's partial tick is recomputed inline with the public
     * Camera.getCameraEntityPartialTicks(DeltaTracker) (extract's local 1), inside the same
     * shouldRenderLevel block that 26.2 computed it in. Only validates and edits {@code extract}.
     */
    private static void patchCurrentLiveFrameTargetingRefresh(String jar, MethodNode extract,
            MethodInsnNode extractCamera, AbstractInsnNode cameraPartialTick,
            VarInsnNode worldPartialTickLoad) throws IOException {
        String owner = "net/minecraft/client/renderer/GameRenderer";
        String camera = "net/minecraft/client/Camera";
        String partialTicksDescriptor = "(Lnet/minecraft/client/DeltaTracker;)F";
        AbstractInsnNode trackerArgument = cameraPartialTick == null
                ? null : previousOpcode(cameraPartialTick);
        if (!(cameraPartialTick instanceof VarInsnNode partialLoad)
                || partialLoad.getOpcode() != Opcodes.FLOAD
                || partialLoad.var != worldPartialTickLoad.var
                || !(trackerArgument instanceof VarInsnNode trackerLoad)
                || trackerLoad.getOpcode() != Opcodes.ALOAD
                || trackerLoad.var != 1) {
            throw new IllegalStateException(
                    "GameRenderer.extract 26.3 camera extraction arguments changed");
        }
        MethodNode cameraPartialTicks = find(read(jar, camera + ".class"),
                "getCameraEntityPartialTicks", partialTicksDescriptor);
        if ((cameraPartialTicks.access & Opcodes.ACC_PUBLIC) == 0
                || (cameraPartialTicks.access & Opcodes.ACC_STATIC) != 0) {
            throw new IllegalStateException(
                    "Camera.getCameraEntityPartialTicks is no longer a public instance method");
        }
        InsnList refresh = new InsnList();
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "minecraft", "Lnet/minecraft/client/Minecraft;"));
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "mainCamera", "L" + camera + ";"));
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "mainCamera", "L" + camera + ";"));
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 1));
        refresh.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                camera, "getCameraEntityPartialTicks", partialTicksDescriptor, false));
        refresh.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserTargeting",
                "refreshFramePick",
                "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/Camera;F)V",
                false));
        extract.instructions.insert(extractCamera, refresh);
        extract.maxStack = Math.max(extract.maxStack, 4);
    }

    public static void patchStagingBuffer(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/vertex/StagingBuffer$Cpu";
        ClassNode node = read(jar, owner + ".class");
        ModernSymbols symbols = ModernSymbols.cached(jar);
        MethodNode method = find(node, "copyTo",
                "(L" + symbols.renderType("com/mojang/blaze3d/systems/CommandEncoder") + ";"
                        + "L" + symbols.renderType("com/mojang/blaze3d/buffers/GpuBuffer")
                        + ";JJJ)V");
        int replaced = replaceCall(
                method,
                Opcodes.INVOKEVIRTUAL,
                "java/nio/ByteBuffer",
                "slice",
                "(II)Ljava/nio/ByteBuffer;",
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "slice",
                "(Ljava/nio/ByteBuffer;II)Ljava/nio/ByteBuffer;");
        requireOne("StagingBuffer ByteBuffer.slice", replaced);
        write(node, root.resolve(owner + ".class"));
    }

    public static void patchVulkanDebug(String jar, Path root) throws IOException {
        if (ModernSymbols.cached(jar).renderpearl()) {
            // 26.3 VulkanDebug is only created by VulkanInstance, which only VulkanBackend
            // constructs; RenderPatches263.patchVulkanBackend makes it unreachable.
            PatchRegistry.dropped("Minecraft262BrowserPatcher.patchVulkanDebug", jar,
                    "com/mojang/blaze3d/vulkan/VulkanDebug.class");
            return;
        }
        String owner = "com/mojang/blaze3d/vulkan/VulkanDebug";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "create",
                "(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/blaze3d/vulkan/VulkanDebug;");
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, owner + "$Disabled"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner + "$Disabled", "<init>", "()V", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2, 4);
        write(node, root.resolve(owner + ".class"));
    }
}
