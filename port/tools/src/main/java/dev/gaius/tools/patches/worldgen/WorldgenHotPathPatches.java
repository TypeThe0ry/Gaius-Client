package dev.gaius.tools.patches.worldgen;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
 * Exact hot-path caches of the chunk-generation thread: the worldgen domain of the
 * MinecraftClientPatcher step ({@code patches/worldgen}), WasmGC target. Each patch remembers a
 * value that vanilla recomputes on every call but that cannot change while the cache is valid, so
 * the generated world is byte for byte the vanilla one. They are matched by class and method
 * shape, not by version, and each one proves the shape it relies on before it rewrites anything
 * (a profile whose shape differs is declared in {@code versions/<id>.json patchkitNotApplicable}
 * or, if it was not, the PERF patch is disabled and the vanilla code runs).
 *
 * <ul>
 *   <li>{@link #patchTagKeyBrowserHashCodeCache}: {@code TagKey.hashCode} is the record hash of
 *       an interned {@code ResourceKey} (identity hash) and an immutable {@code Identifier};
 *       {@code Holder.Reference.is(TagKey)} hashes the key on every block tag test.</li>
 *   <li>{@link #patchWorldGenRegionBrowserCachedHeight}: {@code WorldGenRegion.getMinY/getHeight}
 *       read {@code DimensionType.minY()/height()} through {@code ServerLevel} and the dimension
 *       type holder; the region already holds the {@code DimensionType} it took from the level at
 *       construction, and the ore and tree features ask for the height several times per
 *       block.</li>
 *   <li>{@link #patchWorldGenRegionBrowserLastChunk}: {@code WorldGenRegion.getChunk(x, z)} is a
 *       pure function of (x, z) once the region exists (its chunk table is filled in the
 *       constructor and never written again); the last answer is kept.</li>
 *   <li>{@link #patchMultiNoiseBiomeSourceBrowserCachedParameters}:
 *       {@code MultiNoiseBiomeSource.parameters()} unwraps a final {@code Either} on every
 *       biome lookup.</li>
 * </ul>
 *
 * <p>The assumptions that are not visible in bytecode are the thread-confinement of a
 * {@code WorldGenRegion} (one generation task at a time, as in vanilla) and, for the dimension
 * type and the biome parameter holders, that a bound registry holder is not rebound while a level
 * or biome source is in use. The per-patch notes are in {@code port/native/docs/worldgen-correctness.md}.
 */
public final class WorldgenHotPathPatches {
    private WorldgenHotPathPatches() {
    }

    private static final String TAG_KEY = "net/minecraft/tags/TagKey";

    private static final String REGION = "net/minecraft/server/level/WorldGenRegion";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String LEVEL_READER = "net/minecraft/world/level/LevelReader";
    private static final String DIMENSION_TYPE = "net/minecraft/world/level/dimension/DimensionType";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String CHUNK_ACCESS = "net/minecraft/world/level/chunk/ChunkAccess";
    private static final String CHUNK_STATUS = "net/minecraft/world/level/chunk/status/ChunkStatus";
    private static final String STATIC_CACHE = "net/minecraft/util/StaticCache2D";

    private static final String MULTI_NOISE = "net/minecraft/world/level/biome/MultiNoiseBiomeSource";
    private static final String MULTI_NOISE_LIST =
            "net/minecraft/world/level/biome/MultiNoiseBiomeSourceParameterList";
    private static final String PARAMETER_LIST = "net/minecraft/world/level/biome/Climate$ParameterList";
    private static final String EITHER = "com/mojang/datafixers/util/Either";

    // ------------------------------------------------------------------------------ TagKey

    /**
     * TagKey is a record of a ResourceKey (interned, no hashCode of its own: the identity hash)
     * and an Identifier (two final strings), so its hash is a constant of the instance.
     * Reuses the record hash cache of the worldgen density records: a final immutable class, a
     * synthetic field pair, the vanilla body run once.
     */
    public static void patchTagKeyBrowserHashCodeCache(String jar, Path output) throws IOException {
        ClassNode node = readClass(jar, TAG_KEY, output);
        if ((node.access & Opcodes.ACC_RECORD) == 0) {
            throw new IllegalStateException("TagKey is not a record: its hashCode is not a pure function of final fields");
        }
        WorldgenPatches.cacheImmutableRecordHashCode(node, TAG_KEY);
        writeComputeFrames(node, output);
    }

    // ---------------------------------------------------------- WorldGenRegion height

    /**
     * {@code WorldGenRegion.getMinY()} is {@code level.getMinY()}, which is the
     * {@code LevelReader} default {@code dimensionType().minY()} with
     * {@code Level.dimensionType() = dimensionTypeRegistration.value()}; {@code getHeight()} is
     * the same with {@code height()}. The region holds {@code dimensionType}, copied from
     * {@code level.dimensionType()} in its constructor (and returned by its own
     * {@code dimensionType()}), so both read it directly. {@code DimensionType} is a record of
     * immutable values and the level's holder is bound before any chunk generates.
     *
     * <p>The patch first proves the chain (no override of {@code getMinY/getHeight} in the
     * supertypes of ServerLevel other than the LevelReader defaults, the bodies of those
     * defaults and of {@code Level.dimensionType}) and that the constructor stores exactly
     * {@code level.dimensionType()}.
     */
    public static void patchWorldGenRegionBrowserCachedHeight(String jar, Path output) throws IOException {
        String dimensionDescriptor = "()L" + DIMENSION_TYPE + ";";
        requireDeclaringTypes(jar, "getMinY", "()I", Set.of(LEVEL_READER));
        requireDeclaringTypes(jar, "getHeight", "()I", Set.of(LEVEL_READER));
        requireDeclaringTypes(jar, "dimensionType", dimensionDescriptor, Set.of(LEVEL));
        ClassNode levelReader = read(jar, LEVEL_READER + ".class");
        requireShape(find(levelReader, "getMinY", "()I"), LEVEL_READER + ".getMinY",
                new VarInsnNode(Opcodes.ALOAD, 0),
                new MethodInsnNode(Opcodes.INVOKEINTERFACE, LEVEL_READER, "dimensionType", dimensionDescriptor, true),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DIMENSION_TYPE, "minY", "()I", false),
                new InsnNode(Opcodes.IRETURN));
        requireShape(find(levelReader, "getHeight", "()I"), LEVEL_READER + ".getHeight",
                new VarInsnNode(Opcodes.ALOAD, 0),
                new MethodInsnNode(Opcodes.INVOKEINTERFACE, LEVEL_READER, "dimensionType", dimensionDescriptor, true),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DIMENSION_TYPE, "height", "()I", false),
                new InsnNode(Opcodes.IRETURN));
        ClassNode level = read(jar, LEVEL + ".class");
        requireShape(find(level, "dimensionType", dimensionDescriptor), LEVEL + ".dimensionType",
                new VarInsnNode(Opcodes.ALOAD, 0),
                new FieldInsnNode(Opcodes.GETFIELD, LEVEL, "dimensionTypeRegistration", "L" + HOLDER + ";"),
                new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true),
                new TypeInsnNode(Opcodes.CHECKCAST, DIMENSION_TYPE),
                new InsnNode(Opcodes.ARETURN));

        ClassNode node = readClass(jar, REGION, output);
        FieldNode dimension = field(node, "dimensionType", "L" + DIMENSION_TYPE + ";");
        if ((dimension.access & Opcodes.ACC_FINAL) == 0 || (dimension.access & Opcodes.ACC_STATIC) != 0) {
            throw new IllegalStateException(REGION + ".dimensionType is not a final instance field");
        }
        requireDimensionTypeFromLevel(node);

        patchHeightAccessor(node, "getMinY", "minY");
        patchHeightAccessor(node, "getHeight", "height");
        writeComputeFrames(node, output);
    }

    private static void patchHeightAccessor(ClassNode node, String method, String accessor) {
        MethodNode target = find(node, method, "()I");
        requireShape(target, REGION + "." + method,
                new VarInsnNode(Opcodes.ALOAD, 0),
                new FieldInsnNode(Opcodes.GETFIELD, REGION, "level", "L" + SERVER_LEVEL + ";"),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL, method, "()I", false),
                new InsnNode(Opcodes.IRETURN));
        // return this.dimensionType.minY();   (height() for getHeight)
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, REGION, "dimensionType", "L" + DIMENSION_TYPE + ";"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DIMENSION_TYPE, accessor, "()I", false));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(target, code, 1, 1);
    }

    /** The only write of {@code dimensionType} is {@code this.dimensionType = level.dimensionType()}. */
    private static void requireDimensionTypeFromLevel(ClassNode node) {
        int writes = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(REGION) && field.name.equals("dimensionType")) {
                    AbstractInsnNode before = previousReal(instruction);
                    if (!method.name.equals("<init>")
                            || !(before instanceof MethodInsnNode call)
                            || !call.owner.equals(SERVER_LEVEL)
                            || !call.name.equals("dimensionType")
                            || !call.desc.equals("()L" + DIMENSION_TYPE + ";")) {
                        throw new IllegalStateException(
                                REGION + ".dimensionType is written other than from ServerLevel.dimensionType()");
                    }
                    writes++;
                }
            }
        }
        if (writes != 1) {
            throw new IllegalStateException(REGION + ".dimensionType writes: " + writes);
        }
    }

    // ------------------------------------------------------------ WorldGenRegion chunk

    /**
     * {@code getChunk(x, z)} is {@code getChunk(x, z, ChunkStatus.EMPTY)} with the checks of the
     * 4-argument method: the chessboard distance to the centre selects the status the generating
     * step may read and the chunk comes from the region's {@code StaticCache2D<ChunkAccess>},
     * which the constructor fills with {@code holders.map(...)} and nothing writes again. A call
     * that returns is therefore a function of (x, z) alone: the patch keeps the last (x, z) and
     * answer in three fields and returns them on a repeat, a call that throws stores nothing.
     * Ore veins, trees and the carver ask for the same chunk for each of their blocks.
     *
     * <p>Applies to a region whose table holds the chunks (26.3). Where the table holds
     * {@code GenerationChunkHolder}s and the chunk is read from the holder on every call
     * (26.2, 1.21.11) the answer can change while the region lives, so the patch refuses
     * ({@code patchkitNotApplicable} in those profiles).
     */
    public static void patchWorldGenRegionBrowserLastChunk(String jar, Path output) throws IOException {
        ClassNode node = readClass(jar, REGION, output);
        FieldNode cache = field(node, "cache", "L" + STATIC_CACHE + ";");
        if (cache.signature == null || !cache.signature.contains("<L" + CHUNK_ACCESS + ";>")
                || (cache.access & Opcodes.ACC_FINAL) == 0) {
            throw new IllegalStateException(
                    REGION + ".cache does not hold resolved chunks (signature " + cache.signature + ")");
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(REGION) && field.name.equals("cache")
                        && !method.name.equals("<init>")) {
                    throw new IllegalStateException(REGION + ".cache is written outside the constructor: "
                            + method.name + method.desc);
                }
            }
        }
        MethodNode four = find(node, "getChunk", "(IIL" + CHUNK_STATUS + ";Z)L" + CHUNK_ACCESS + ";");
        boolean tableReads = false;
        AbstractInsnNode[] fourCode = four.instructions.toArray();
        for (int index = 0; index < fourCode.length; index++) {
            if (fourCode[index] instanceof MethodInsnNode call && call.owner.equals(STATIC_CACHE)
                    && call.name.equals("get")) {
                AbstractInsnNode after = nextReal(fourCode[index]);
                if (!(after instanceof TypeInsnNode cast) || !cast.desc.equals(CHUNK_ACCESS)) {
                    throw new IllegalStateException(REGION + ".getChunk reads the table as "
                            + (after instanceof TypeInsnNode other ? other.desc : "?"));
                }
                tableReads = true;
            }
        }
        if (!tableReads) {
            throw new IllegalStateException(REGION + ".getChunk does not read the chunk table");
        }

        MethodNode two = find(node, "getChunk", "(II)L" + CHUNK_ACCESS + ";");
        List<AbstractInsnNode> body = realInstructions(two);
        requireShape(two, REGION + ".getChunk(II)",
                new VarInsnNode(Opcodes.ALOAD, 0),
                new VarInsnNode(Opcodes.ILOAD, 1),
                new VarInsnNode(Opcodes.ILOAD, 2),
                new FieldInsnNode(Opcodes.GETSTATIC, CHUNK_STATUS, "EMPTY", "L" + CHUNK_STATUS + ";"),
                new MethodInsnNode(Opcodes.INVOKEVIRTUAL, REGION, "getChunk",
                        "(IIL" + CHUNK_STATUS + ";)L" + CHUNK_ACCESS + ";", false),
                new InsnNode(Opcodes.ARETURN));
        MethodInsnNode original = (MethodInsnNode) body.get(4);

        int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        requireAbsent(node, "browserLastChunk");
        node.fields.add(new FieldNode(access, "browserLastChunk", "L" + CHUNK_ACCESS + ";", null, null));
        node.fields.add(new FieldNode(access, "browserLastChunkX", "I", null, null));
        node.fields.add(new FieldNode(access, "browserLastChunkZ", "I", null, null));

        // ChunkAccess last = this.browserLastChunk;
        // if (last != null && this.browserLastChunkX == x && this.browserLastChunkZ == z) return last;
        // last = getChunk(x, z, ChunkStatus.EMPTY);
        // this.browserLastChunkX = x; this.browserLastChunkZ = z; this.browserLastChunk = last;
        // return last;
        LabelNode slow = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, REGION, "browserLastChunk", "L" + CHUNK_ACCESS + ";"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFNULL, slow));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, REGION, "browserLastChunkX", "I"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPNE, slow));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, REGION, "browserLastChunkZ", "I"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPNE, slow));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(slow);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, CHUNK_STATUS, "EMPTY", "L" + CHUNK_STATUS + ";"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, original.owner, original.name, original.desc, false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, REGION, "browserLastChunkX", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, REGION, "browserLastChunkZ", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, REGION, "browserLastChunk", "L" + CHUNK_ACCESS + ";"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(two, code, 3, 4);
        writeComputeFrames(node, output);
    }

    // --------------------------------------------------------------- MultiNoiseBiomeSource

    /**
     * {@code parameters()} is {@code either.map(list -> list, holder -> holder.value().parameters())}
     * of the final {@code Either} field. The result is a final field of an immutable parameter
     * list (or of the bound preset the holder names), so it is computed once per biome source: a
     * call that throws (an unbound preset holder) stores nothing.
     */
    public static void patchMultiNoiseBiomeSourceBrowserCachedParameters(String jar, Path output)
            throws IOException {
        String listDescriptor = "L" + PARAMETER_LIST + ";";
        ClassNode preset = read(jar, MULTI_NOISE_LIST + ".class");
        FieldNode presetField = field(preset, "parameters", listDescriptor);
        if ((presetField.access & Opcodes.ACC_FINAL) == 0) {
            throw new IllegalStateException(MULTI_NOISE_LIST + ".parameters is not final");
        }
        requireShape(find(preset, "parameters", "()" + listDescriptor), MULTI_NOISE_LIST + ".parameters",
                new VarInsnNode(Opcodes.ALOAD, 0),
                new FieldInsnNode(Opcodes.GETFIELD, MULTI_NOISE_LIST, "parameters", listDescriptor),
                new InsnNode(Opcodes.ARETURN));

        ClassNode node = readClass(jar, MULTI_NOISE, output);
        FieldNode either = field(node, "parameters", "L" + EITHER + ";");
        if ((either.access & Opcodes.ACC_FINAL) == 0 || (either.access & Opcodes.ACC_STATIC) != 0) {
            throw new IllegalStateException(MULTI_NOISE + ".parameters is not a final instance field");
        }
        MethodNode method = find(node, "parameters", "()" + listDescriptor);
        List<AbstractInsnNode> body = realInstructions(method);
        if (body.size() != 7
                || !sig(body.get(0)).equals(sig(new VarInsnNode(Opcodes.ALOAD, 0)))
                || !sig(body.get(1)).equals(sig(new FieldInsnNode(
                        Opcodes.GETFIELD, MULTI_NOISE, "parameters", "L" + EITHER + ";")))
                || !(body.get(2) instanceof InvokeDynamicInsnNode left) || !left.desc.equals("()Ljava/util/function/Function;")
                || !(body.get(3) instanceof InvokeDynamicInsnNode right) || !right.desc.equals("()Ljava/util/function/Function;")
                || !sig(body.get(4)).equals(sig(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EITHER, "map",
                        "(Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/lang/Object;", false)))
                || !sig(body.get(5)).equals(sig(new TypeInsnNode(Opcodes.CHECKCAST, PARAMETER_LIST)))
                || !sig(body.get(6)).equals(sig(new InsnNode(Opcodes.ARETURN)))) {
            throw new IllegalStateException(MULTI_NOISE + ".parameters() shape changed");
        }
        // The two lambdas are the identity and Holder.value().parameters(): nothing else is read.
        int lambdas = 0;
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.startsWith("lambda$parameters$")) {
                continue;
            }
            lambdas++;
            if (candidate.desc.equals("(" + listDescriptor + ")" + listDescriptor)) {
                requireShape(candidate, MULTI_NOISE + "." + candidate.name,
                        new VarInsnNode(Opcodes.ALOAD, 0), new InsnNode(Opcodes.ARETURN));
            } else {
                requireShape(candidate, MULTI_NOISE + "." + candidate.name,
                        new VarInsnNode(Opcodes.ALOAD, 0),
                        new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true),
                        new TypeInsnNode(Opcodes.CHECKCAST, MULTI_NOISE_LIST),
                        new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MULTI_NOISE_LIST, "parameters",
                                "()" + listDescriptor, false),
                        new InsnNode(Opcodes.ARETURN));
            }
        }
        if (lambdas != 2) {
            throw new IllegalStateException(MULTI_NOISE + " parameters lambdas: " + lambdas);
        }

        requireAbsent(node, "browserParameters");
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                "browserParameters", listDescriptor, null, null));

        // ParameterList cached = this.browserParameters; if (cached != null) return cached;
        LabelNode compute = new LabelNode();
        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD, 0));
        head.add(new FieldInsnNode(Opcodes.GETFIELD, MULTI_NOISE, "browserParameters", listDescriptor));
        head.add(new VarInsnNode(Opcodes.ASTORE, 1));
        head.add(new VarInsnNode(Opcodes.ALOAD, 1));
        head.add(new JumpInsnNode(Opcodes.IFNULL, compute));
        head.add(new VarInsnNode(Opcodes.ALOAD, 1));
        head.add(new InsnNode(Opcodes.ARETURN));
        head.add(compute);
        // ... vanilla body; this.browserParameters = result; return result;
        InsnList tail = new InsnList();
        tail.add(new VarInsnNode(Opcodes.ASTORE, 1));
        tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tail.add(new VarInsnNode(Opcodes.ALOAD, 1));
        tail.add(new FieldInsnNode(Opcodes.PUTFIELD, MULTI_NOISE, "browserParameters", listDescriptor));
        tail.add(new VarInsnNode(Opcodes.ALOAD, 1));
        tail.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.insertBefore(body.get(6), tail);
        method.instructions.remove(body.get(6));
        method.instructions.insert(head);
        method.maxLocals = Math.max(method.maxLocals, 2);
        method.maxStack = Math.max(method.maxStack, 2);
        writeComputeFrames(node, output);
    }

    // ------------------------------------------------------------------------- helpers

    private static ClassNode readClass(String jar, String owner, Path output) throws IOException {
        return Files.isRegularFile(output) ? read(output) : read(jar, owner + ".class");
    }

    private static FieldNode field(ClassNode node, String name, String descriptor) {
        return node.fields.stream()
                .filter(field -> field.name.equals(name) && field.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + " " + descriptor + " was not found"));
    }

    private static void requireAbsent(ClassNode node, String field) {
        if (node.fields.stream().anyMatch(candidate -> candidate.name.equals(field))) {
            throw new IllegalStateException(node.name + "." + field + " already exists");
        }
    }

    /** The instructions of a method without labels, line numbers and frames. */
    private static List<AbstractInsnNode> realInstructions(MethodNode method) {
        List<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() >= 0) {
                real.add(instruction);
            }
        }
        return real;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private static String sig(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (instruction instanceof VarInsnNode var) {
            return opcode + " " + var.var;
        }
        if (instruction instanceof FieldInsnNode field) {
            return opcode + " " + field.owner + "." + field.name + " " + field.desc;
        }
        if (instruction instanceof MethodInsnNode call) {
            return opcode + " " + call.owner + "." + call.name + call.desc + " " + call.itf;
        }
        if (instruction instanceof TypeInsnNode type) {
            return opcode + " " + type.desc;
        }
        if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            return opcode + " " + dynamic.name + dynamic.desc;
        }
        return Integer.toString(opcode);
    }

    private static void requireShape(MethodNode method, String what, AbstractInsnNode... expected) {
        List<AbstractInsnNode> actual = realInstructions(method);
        boolean same = actual.size() == expected.length;
        for (int index = 0; same && index < expected.length; index++) {
            same = sig(actual.get(index)).equals(sig(expected[index]));
        }
        if (!same) {
            List<String> found = new ArrayList<>();
            actual.forEach(instruction -> found.add(sig(instruction)));
            throw new IllegalStateException(what + " shape changed: " + found);
        }
    }

    /**
     * The classes of the jar that implement {@code name+descriptor} (declare it with a body)
     * among ServerLevel and all its super types (classes and interfaces under net/minecraft)
     * must be exactly {@code expected}; abstract declarations (the interface methods they
     * implement) do not count. With the JVM's method resolution this makes the one implementation
     * the one the patch has read.
     */
    private static void requireDeclaringTypes(String jar, String name, String descriptor, Set<String> expected)
            throws IOException {
        Set<String> declaring = new TreeSet<>();
        Set<String> seen = new TreeSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(SERVER_LEVEL);
        while (!queue.isEmpty()) {
            String owner = queue.poll();
            if (!owner.startsWith("net/minecraft/") || !seen.add(owner)) {
                continue;
            }
            ClassNode type = read(jar, owner + ".class");
            if (type.methods.stream().anyMatch(method -> method.name.equals(name)
                    && method.desc.equals(descriptor) && (method.access & Opcodes.ACC_ABSTRACT) == 0)) {
                declaring.add(owner);
            }
            if (type.superName != null) {
                queue.add(type.superName);
            }
            queue.addAll(type.interfaces);
        }
        if (!declaring.equals(new TreeSet<>(expected))) {
            throw new IllegalStateException(SERVER_LEVEL + " resolves " + name + descriptor
                    + " through " + declaring + ", expected " + new TreeSet<>(expected));
        }
    }
}
