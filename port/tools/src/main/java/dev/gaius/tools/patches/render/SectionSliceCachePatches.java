package dev.gaius.tools.patches.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Memoizes the uber buffer slice lookups of the per-frame terrain draw extraction
 * (LevelRenderer.extractSectionDrawGroups on 26.3, prepareChunkRenders on 26.2).
 *
 * <p>Vanilla calls {@code SectionRenderDispatcher.getRenderSectionSlice(mesh, layer)} for every
 * visible section and every layer each frame: an EnumMap lookup, one or two HashMap lookups and a
 * new record. The result only depends on the dispatcher's final uber buffers, their
 * {@code allocationMap} (keyed by mesh identity) and the final offset and heap of each allocation,
 * so it is memoized per CompiledSectionMesh and layer by dev.gaius.browser.render
 * .BrowserSectionSliceCache and dropped whenever any {@code allocationMap} of any UberGpuBuffer is
 * put, removed or cleared. This patch:
 * <ul>
 *   <li>proves that UberGpuBuffer (and its nest members) touch {@code allocationMap} only through
 *       Map.get/put/remove/clear and calls {@code BrowserSectionSliceCache.invalidate()} after
 *       every put, remove and clear;</li>
 *   <li>gives CompiledSectionMesh a per-mesh memo, created at the start of its constructor, and a
 *       static {@code gaius$renderSectionSlice(dispatcher, mesh, layer)} that uses it for compiled
 *       meshes and calls the vanilla lookup for any other mesh;</li>
 *   <li>redirects the LevelRenderer lookups to that static, after checking that their result is
 *       only null-tested and read through the record accessors (the record is immutable, so
 *       sharing it across frames cannot be observed).</li>
 * </ul>
 * Visited sections, layers, groups, draws and their order are unchanged.
 */
public final class SectionSliceCachePatches {
    static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
    static final String UBER_GPU_BUFFER = "com/mojang/blaze3d/vertex/UberGpuBuffer";
    static final String COMPILED_SECTION_MESH = "net/minecraft/client/renderer/chunk/CompiledSectionMesh";
    static final String DISPATCHER = "net/minecraft/client/renderer/chunk/SectionRenderDispatcher";
    static final String SLICE = DISPATCHER + "$RenderSectionBufferSlice";
    static final String SECTION_MESH = "net/minecraft/client/renderer/chunk/SectionMesh";
    static final String LAYER = "net/minecraft/client/renderer/chunk/ChunkSectionLayer";
    static final String CACHE = "dev/gaius/browser/render/BrowserSectionSliceCache";
    static final String ENTRY = CACHE + "$Entry";
    static final String ENTRY_DESC = "L" + ENTRY + ";";
    static final String MEMO_FIELD = "gaius$sliceCache";
    static final String STATIC_SLICE = "gaius$renderSectionSlice";
    static final String SLICE_DESC = "(L" + SECTION_MESH + ";L" + LAYER + ";)L" + SLICE + ";";
    static final String STATIC_SLICE_DESC = "(L" + DISPATCHER + ";L" + SECTION_MESH + ";L" + LAYER + ";)L" + SLICE + ";";
    static final String ALLOCATION_MAP = "allocationMap";

    private SectionSliceCachePatches() {
    }

    public static void apply(String jar, Path root) throws IOException {
        int hooks = patchUberGpuBufferInvalidation(jar, root);
        patchCompiledSectionMesh(jar, root);
        int redirected = patchLevelRenderer(jar, root);
        System.out.println("Memoized LevelRenderer section slices (" + redirected
                + " lookup, " + hooks + " uber buffer allocation hooks)");
    }

    /** Invalidates the memo after every allocationMap mutation; returns the number of hooks. */
    static int patchUberGpuBufferInvalidation(String jar, Path root) throws IOException {
        ClassNode node = TerrainBatchPatches.readCurrent(jar, root, UBER_GPU_BUFFER);
        boolean fieldFound = false;
        for (FieldNode field : node.fields) {
            if (field.name.equals(ALLOCATION_MAP)) {
                if (!field.desc.equals("Ljava/util/Map;") || (field.access & Opcodes.ACC_PRIVATE) == 0
                        || (field.access & Opcodes.ACC_FINAL) == 0) {
                    throw new IllegalStateException("UberGpuBuffer.allocationMap is no longer a private final Map");
                }
                fieldFound = true;
            }
        }
        if (!fieldFound) {
            throw new IllegalStateException("UberGpuBuffer.allocationMap was not found");
        }
        // No nest member may reach the private map (javac would read it directly since Java 11).
        if (node.nestMembers != null) {
            for (String member : node.nestMembers) {
                ClassNode nested = TerrainBatchPatches.readCurrent(jar, root, member);
                for (MethodNode method : nested.methods) {
                    for (AbstractInsnNode instruction : method.instructions.toArray()) {
                        if (instruction instanceof FieldInsnNode field && field.owner.equals(UBER_GPU_BUFFER)
                                && field.name.equals(ALLOCATION_MAP)) {
                            throw new IllegalStateException(member + "." + method.name
                                    + " reads UberGpuBuffer.allocationMap");
                        }
                    }
                }
            }
        }
        int hooks = 0;
        int alreadyHooked = 0;
        int reads = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode field) || !field.owner.equals(UBER_GPU_BUFFER)
                        || !field.name.equals(ALLOCATION_MAP)) {
                    continue;
                }
                if (field.getOpcode() == Opcodes.PUTFIELD) {
                    if (!method.name.equals("<init>")) {
                        throw new IllegalStateException("UberGpuBuffer.allocationMap is assigned in " + method.name);
                    }
                    continue;
                }
                if (field.getOpcode() != Opcodes.GETFIELD) {
                    throw new IllegalStateException("unexpected UberGpuBuffer.allocationMap access in " + method.name);
                }
                MethodInsnNode call = consumer(field, method.name);
                switch (call.name) {
                    case "get" -> reads++;
                    case "put", "remove", "clear" -> {
                        AbstractInsnNode next = call.getNext();
                        if (next instanceof MethodInsnNode hook && hook.getOpcode() == Opcodes.INVOKESTATIC
                                && hook.owner.equals(CACHE) && hook.name.equals("invalidate")) {
                            alreadyHooked++;
                        } else {
                            method.instructions.insert(call,
                                    new MethodInsnNode(Opcodes.INVOKESTATIC, CACHE, "invalidate", "()V", false));
                            hooks++;
                        }
                    }
                    default -> throw new IllegalStateException("UberGpuBuffer.allocationMap is used through Map."
                            + call.name + " in " + method.name + "; the section slice memo cannot see it");
                }
            }
        }
        if (hooks + alreadyHooked < 3 || reads != 1) {
            throw new IllegalStateException("UberGpuBuffer.allocationMap shape changed: mutations="
                    + (hooks + alreadyHooked) + " reads=" + reads);
        }
        if (hooks > 0) {
            write(node, root.resolve(UBER_GPU_BUFFER + ".class"));
        }
        return hooks + alreadyHooked;
    }

    /**
     * The java/util/Map call that consumes the map pushed by {@code get}, found by following the
     * operand stack through straight-line loads, casts, field reads and calls; anything else fails.
     */
    static MethodInsnNode consumer(FieldInsnNode get, String methodName) {
        int depth = 1; // the map itself
        for (AbstractInsnNode cursor = get.getNext(); cursor != null; cursor = cursor.getNext()) {
            int opcode = cursor.getOpcode();
            if (opcode < 0) {
                if (cursor instanceof LabelNode) {
                    throw new IllegalStateException("UberGpuBuffer.allocationMap crosses a label in " + methodName);
                }
                continue; // line numbers and frames
            }
            switch (opcode) {
                case Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.FLOAD -> depth++;
                case Opcodes.CHECKCAST -> {
                }
                case Opcodes.GETFIELD -> requireSingle(Type.getType(((FieldInsnNode) cursor).desc), methodName);
                case Opcodes.INVOKEINTERFACE, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESTATIC -> {
                    MethodInsnNode call = (MethodInsnNode) cursor;
                    Type type = Type.getMethodType(call.desc);
                    for (Type argument : type.getArgumentTypes()) {
                        requireSingle(argument, methodName);
                    }
                    int pops = type.getArgumentTypes().length + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
                    if (depth - pops < 0) {
                        throw new IllegalStateException("UberGpuBuffer.allocationMap stack walk underflowed in "
                                + methodName);
                    }
                    if (depth - pops == 0) {
                        if (opcode != Opcodes.INVOKEINTERFACE || !call.owner.equals("java/util/Map")) {
                            throw new IllegalStateException("UberGpuBuffer.allocationMap escapes to "
                                    + call.owner + "." + call.name + " in " + methodName);
                        }
                        return call;
                    }
                    depth -= pops;
                    if (type.getReturnType().getSort() != Type.VOID) {
                        requireSingle(type.getReturnType(), methodName);
                        depth++;
                    }
                }
                default -> throw new IllegalStateException("UberGpuBuffer.allocationMap is followed by opcode "
                        + opcode + " in " + methodName);
            }
        }
        throw new IllegalStateException("UberGpuBuffer.allocationMap consumer not found in " + methodName);
    }

    private static void requireSingle(Type type, String methodName) {
        if (type.getSize() != 1) {
            throw new IllegalStateException("UberGpuBuffer.allocationMap stack walk met a wide value in " + methodName);
        }
    }

    /** Adds the per-mesh memo field, its constructor initialization and the static lookup. */
    static void patchCompiledSectionMesh(String jar, Path root) throws IOException {
        ClassNode node = TerrainBatchPatches.readCurrent(jar, root, COMPILED_SECTION_MESH);
        for (MethodNode method : node.methods) {
            if (method.name.equals("equals") && method.desc.equals("(Ljava/lang/Object;)Z")
                    || method.name.equals("hashCode") && method.desc.equals("()I")) {
                throw new IllegalStateException("CompiledSectionMesh overrides " + method.name
                        + "; the uber buffer allocation keys are no longer identities");
            }
        }
        if (!node.superName.equals("java/lang/Object")) {
            throw new IllegalStateException("CompiledSectionMesh superclass changed: " + node.superName);
        }
        for (FieldNode field : node.fields) {
            if (field.name.equals(MEMO_FIELD)) {
                return; // already patched
            }
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, MEMO_FIELD, ENTRY_DESC, null, null));

        List<MethodNode> constructors = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                constructors.add(method);
            }
        }
        if (constructors.size() != 1) {
            throw new IllegalStateException("CompiledSectionMesh has " + constructors.size() + " constructors");
        }
        MethodNode constructor = constructors.get(0);
        MethodInsnNode superCall = null;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("<init>")) {
                if (!call.owner.equals("java/lang/Object") || !call.desc.equals("()V")) {
                    throw new IllegalStateException("CompiledSectionMesh constructor calls " + call.owner + ".<init>"
                            + " before its super constructor");
                }
                superCall = call;
                break;
            }
        }
        if (superCall == null) {
            throw new IllegalStateException("CompiledSectionMesh super constructor call was not found");
        }
        InsnList init = new InsnList();
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new TypeInsnNode(Opcodes.NEW, ENTRY));
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ENTRY, "<init>", "()V", false));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, COMPILED_SECTION_MESH, MEMO_FIELD, ENTRY_DESC));
        constructor.instructions.insert(superCall, init);

        MethodNode lookup = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                STATIC_SLICE, STATIC_SLICE_DESC, null, null);
        LabelNode plain = new LabelNode();
        InsnList code = lookup.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new TypeInsnNode(Opcodes.INSTANCEOF, COMPILED_SECTION_MESH));
        code.add(new JumpInsnNode(Opcodes.IFEQ, plain));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, COMPILED_SECTION_MESH));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, COMPILED_SECTION_MESH, MEMO_FIELD, ENTRY_DESC));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CACHE, "slice",
                "(L" + DISPATCHER + ";L" + SECTION_MESH + ";L" + LAYER + ";" + ENTRY_DESC + ")L" + SLICE + ";",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(plain);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DISPATCHER, "getRenderSectionSlice", SLICE_DESC, false));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(lookup);
        write(node, root.resolve(COMPILED_SECTION_MESH + ".class"));
    }

    /** Redirects the LevelRenderer slice lookups; returns how many there are. */
    static int patchLevelRenderer(String jar, Path root) throws IOException {
        ClassNode node = TerrainBatchPatches.readCurrent(jar, root, LEVEL_RENDERER);
        int redirected = 0;
        int already = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    continue;
                }
                boolean vanilla = call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals(DISPATCHER)
                        && call.name.equals("getRenderSectionSlice") && call.desc.equals(SLICE_DESC);
                boolean patched = call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(COMPILED_SECTION_MESH)
                        && call.name.equals(STATIC_SLICE) && call.desc.equals(STATIC_SLICE_DESC);
                if (!vanilla && !patched) {
                    continue;
                }
                requireRecordReadsOnly(method, call);
                if (patched) {
                    already++;
                    continue;
                }
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, COMPILED_SECTION_MESH,
                        STATIC_SLICE, STATIC_SLICE_DESC, false));
                redirected++;
            }
        }
        if (redirected + already != 1) {
            throw new IllegalStateException("LevelRenderer has " + (redirected + already)
                    + " section slice lookups, expected 1");
        }
        if (redirected > 0) {
            write(node, root.resolve(LEVEL_RENDERER + ".class"));
        }
        return redirected + already;
    }

    /**
     * The lookup result is stored to a local that is only null-tested or read through
     * RenderSectionBufferSlice accessors, so returning the memoized record is unobservable.
     */
    static void requireRecordReadsOnly(MethodNode method, MethodInsnNode call) {
        AbstractInsnNode store = call.getNext();
        while (store != null && store.getOpcode() < 0) {
            store = store.getNext();
        }
        if (!(store instanceof VarInsnNode local) || local.getOpcode() != Opcodes.ASTORE) {
            throw new IllegalStateException("LevelRenderer." + method.name + " does not store its section slice");
        }
        int stores = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof VarInsnNode variable) || variable.var != local.var) {
                continue;
            }
            if (variable.getOpcode() == Opcodes.ASTORE) {
                stores++;
                continue;
            }
            if (variable.getOpcode() != Opcodes.ALOAD) {
                throw new IllegalStateException("LevelRenderer." + method.name + " reuses the section slice local");
            }
            AbstractInsnNode use = variable.getNext();
            while (use != null && use.getOpcode() < 0) {
                use = use.getNext();
            }
            boolean nullTest = use != null && (use.getOpcode() == Opcodes.IFNULL || use.getOpcode() == Opcodes.IFNONNULL);
            boolean accessor = use instanceof MethodInsnNode read && read.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && read.owner.equals(SLICE) && read.desc.startsWith("()")
                    && List.of("vertexBuffer", "vertexBufferOffset", "indexBuffer", "indexBufferOffset")
                    .contains(read.name);
            if (!nullTest && !accessor) {
                throw new IllegalStateException("LevelRenderer." + method.name
                        + " uses its section slice beyond null tests and record accessors");
            }
        }
        if (stores != 1) {
            throw new IllegalStateException("LevelRenderer." + method.name + " stores " + stores
                    + " values in the section slice local");
        }
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
