package dev.gaius.tools.patches.platform;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.getter;
import static dev.gaius.tools.patches.support.ClientPatchSupport.jarHasClass;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.pumpBrowserChannels;
import static dev.gaius.tools.patches.support.ClientPatchSupport.putConstructorField;
import static dev.gaius.tools.patches.support.ClientPatchSupport.putField;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replaceLocalServerCheck;
import static dev.gaius.tools.patches.support.ClientPatchSupport.verifyBrowserUuidCalls;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeBytes;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import dev.gaius.tools.PatchRegistry;
import dev.gaius.tools.BuildTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The platform domain of the MinecraftClientPatcher step ({@code patches/platform}):
 * JVM, OS and threading services in the browser: native libraries and module listing,
 * profilers, executors and event loops, startup yields, the client main loop and frame
 * boundary, JDK API stubs (crypto, Unsafe), {@code Util}, version detection, registry and entity
 * data initialization, and debug memory sources.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically.
 */
public final class PlatformPatches {
    private PlatformPatches() {
    }

    public static void patchSynchedEntityDataClassInitialization(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/network/syncher/SynchedEntityData.class");
        MethodNode defineId = find(node, "defineId",
                "(Ljava/lang/Class;Lnet/minecraft/network/syncher/EntityDataSerializer;)"
                        + "Lnet/minecraft/network/syncher/EntityDataAccessor;");
        boolean patched = false;
        for (var instruction = defineId.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals("net/minecraft/network/syncher/SynchedEntityData")
                    && field.name.equals("ID_REGISTRY")
                    && field.desc.equals("Lnet/minecraft/util/ClassTreeIdRegistry;")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "net/minecraft/network/syncher/SynchedEntityData",
                        "gaius$initializeSynchedDataSuperclasses",
                        "(Ljava/lang/Class;)V",
                        false));
                defineId.instructions.insertBefore(instruction, code);
                patched = true;
                break;
            }
        }
        if (!patched) {
            throw new IllegalStateException("SynchedEntityData.defineId registry patch point was not found");
        }
        addInitializeSynchedDataSuperclassesHelper(node);
        writeComputeFrames(node, output);
    }

    public static void patchEntityBrowserUuidUsesGlobalRandom(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/entity/Entity.class");
        MethodNode constructor = find(node, "<init>",
                "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V");
        int replacements = 0;
        for (var instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("net/minecraft/util/Mth")
                    || !call.name.equals("createInsecureUUID")
                    || !call.desc.equals("(Lnet/minecraft/util/RandomSource;)Ljava/util/UUID;")) {
                continue;
            }
            AbstractInsnNode randomField = previousRealInstruction(call);
            AbstractInsnNode randomOwner = previousRealInstruction(randomField);
            if (!(randomField instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals("net/minecraft/world/entity/Entity")
                    || !field.name.equals("random")
                    || !field.desc.equals("Lnet/minecraft/util/RandomSource;")
                    || !(randomOwner instanceof VarInsnNode ownerLoad)
                    || ownerLoad.getOpcode() != Opcodes.ALOAD
                    || ownerLoad.var != 0) {
                throw new IllegalStateException("Entity UUID random source patch point changed");
            }
            InsnList replacement = new InsnList();
            replacement.add(new InsnNode(Opcodes.POP));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/UUID",
                    "randomUUID", "()Ljava/util/UUID;", false));
            constructor.instructions.insert(call, replacement);
            replacements++;
        }
        if (replacements != 1) {
            throw new IllegalStateException(
                    "Expected one Entity constructor UUID random replacement, got " + replacements);
        }
        verifyBrowserUuidCalls(constructor, 1);
        writeComputeFrames(node, output);
    }

    /**
     * Routes the face-sturdiness loop of {@code BlockStateBase.Cache.<init>} through
     * {@code BrowserBlockSupportMemo}, which memoizes {@code SupportType.isSupporting} per block
     * support shape. The call keeps its operands (type, state, level, pos, direction).
     */
    public static void patchBlockCacheSupportMemo(String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache";
        if (!jarHasClass(jar, owner)) {
            throw new IllegalStateException("BlockStateBase.Cache class is absent");
        }
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        String supportType = "net/minecraft/world/level/block/SupportType";
        String descriptor = "(Lnet/minecraft/world/level/block/state/BlockState;"
                + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
                + "Lnet/minecraft/core/Direction;)Z";
        int replaced = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(supportType)
                        && call.name.equals("isSupporting")
                        && call.desc.equals(descriptor)) {
                    method.instructions.set(call, new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "dev/gaius/browser/BrowserBlockSupportMemo",
                            "isSupporting",
                            "(L" + supportType + ";" + descriptor.substring(1),
                            false));
                    replaced++;
                }
            }
        }
        if (replaced != 1) {
            throw new IllegalStateException("BlockStateBase.Cache face-sturdiness call changed: " + replaced);
        }
        writeComputeFrames(node, output);
        System.out.println("Memoized BlockStateBase.Cache face sturdiness per support shape");
    }

    /** The per-shape answer of {@code Block.isShapeFullBlock}: 0 unknown, 1 false, 2 true. */
    public static final String SHAPE_FULL_BLOCK_FIELD = "gaius$fullBlock";

    /**
     * Remembers {@code Block.isShapeFullBlock} on the shape itself. Vanilla answers it through a
     * Guava LoadingCache with weak keys and at most 512 entries; Blocks static initialization
     * asks it for every block state's collision, occlusion and face shapes, more distinct
     * shapes than the cache holds, so entries are evicted and joined again, and each lookup pays
     * the cache's weak references, clock reads and eviction (about 240 ms sampled in the 26.3
     * constructor window). The vanilla body still computes the first answer for each shape
     * instance; a byte field on VoxelShape keeps it, so later calls for the same instance return
     * the same value without the cache. VoxelShapes are immutable and the answer is a function
     * of the instance, so every result equals vanilla's.
     */
    public static void patchBlockShapeFullMemo(String jar, Path root) throws IOException {
        String blockOwner = "net/minecraft/world/level/block/Block";
        String shapeOwner = "net/minecraft/world/phys/shapes/VoxelShape";
        String shapeDesc = "L" + shapeOwner + ";";
        Path blockOutput = root.resolve(blockOwner + ".class");
        Path shapeOutput = root.resolve(shapeOwner + ".class");
        ClassNode block = Files.exists(blockOutput) ? read(blockOutput) : read(jar, blockOwner + ".class");
        ClassNode shape = Files.exists(shapeOutput) ? read(shapeOutput) : read(jar, shapeOwner + ".class");
        MethodNode method = find(block, "isShapeFullBlock", "(" + shapeDesc + ")Z");
        verifyShapeFullBlockBody(method);
        if (shape.fields.stream().anyMatch(field -> field.name.equals(SHAPE_FULL_BLOCK_FIELD))) {
            throw new IllegalStateException(shapeOwner + "." + SHAPE_FULL_BLOCK_FIELD + " already exists");
        }
        if ((shape.access & Opcodes.ACC_INTERFACE) != 0) {
            throw new IllegalStateException(shapeOwner + " is no longer a class");
        }
        shape.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC,
                SHAPE_FULL_BLOCK_FIELD, "B", null, null));

        // byte known = shape.gaius$fullBlock; if (known != 0) return known == 2;
        // boolean full = <vanilla body>; shape.gaius$fullBlock = full ? 2 : 1; return full;
        InsnList prologue = new InsnList();
        LabelNode compute = new LabelNode();
        LabelNode knownFalse = new LabelNode();
        prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prologue.add(new FieldInsnNode(Opcodes.GETFIELD, shapeOwner, SHAPE_FULL_BLOCK_FIELD, "B"));
        prologue.add(new InsnNode(Opcodes.DUP));
        prologue.add(new JumpInsnNode(Opcodes.IFEQ, compute));
        prologue.add(new InsnNode(Opcodes.ICONST_2));
        prologue.add(new JumpInsnNode(Opcodes.IF_ICMPNE, knownFalse));
        prologue.add(new InsnNode(Opcodes.ICONST_1));
        prologue.add(new InsnNode(Opcodes.IRETURN));
        prologue.add(knownFalse);
        prologue.add(new InsnNode(Opcodes.ICONST_0));
        prologue.add(new InsnNode(Opcodes.IRETURN));
        prologue.add(compute);
        prologue.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(prologue);
        int stores = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.IRETURN || instruction.getPrevious() == null
                    || instruction.getPrevious().getOpcode() != Opcodes.INVOKEVIRTUAL) {
                continue;
            }
            // full on the stack: store it in local 1, record 1 or 2 on the shape, return it.
            InsnList epilogue = new InsnList();
            LabelNode recordFalse = new LabelNode();
            LabelNode recorded = new LabelNode();
            epilogue.add(new VarInsnNode(Opcodes.ISTORE, 1));
            epilogue.add(new VarInsnNode(Opcodes.ALOAD, 0));
            epilogue.add(new VarInsnNode(Opcodes.ILOAD, 1));
            epilogue.add(new JumpInsnNode(Opcodes.IFEQ, recordFalse));
            epilogue.add(new InsnNode(Opcodes.ICONST_2));
            epilogue.add(new JumpInsnNode(Opcodes.GOTO, recorded));
            epilogue.add(recordFalse);
            epilogue.add(new InsnNode(Opcodes.ICONST_1));
            epilogue.add(recorded);
            epilogue.add(new FieldInsnNode(Opcodes.PUTFIELD, shapeOwner, SHAPE_FULL_BLOCK_FIELD, "B"));
            epilogue.add(new VarInsnNode(Opcodes.ILOAD, 1));
            method.instructions.insertBefore(instruction, epilogue);
            stores++;
        }
        if (stores != 1) {
            throw new IllegalStateException("Block.isShapeFullBlock return changed: " + stores);
        }
        method.maxLocals = Math.max(method.maxLocals, 2);
        writeComputeFrames(block, blockOutput);
        write(shape, shapeOutput);
        System.out.println("Memoized Block.isShapeFullBlock per VoxelShape instance");
    }

    /**
     * Caches the immutable hash of every Identifier and rejects unequal hashes before comparing
     * namespace/path strings. Resource reload performs a large number of map lookups with the
     * same identifiers; the cached value is exactly the vanilla expression (31 * namespace hash
     * plus path hash), so this only removes repeated string hash work and failed equals scans.
     */
    public static void patchIdentifierHashMemo(String jar, Path output) throws IOException {
        String owner = "net/minecraft/resources/Identifier";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        String hashField = "gaius$hash";
        if (node.fields.stream().anyMatch(field -> field.name.equals(hashField))) {
            throw new IllegalStateException(owner + "." + hashField + " already exists");
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_TRANSIENT
                | Opcodes.ACC_SYNTHETIC, hashField, "I", null, null));

        MethodNode constructor = find(node, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
        int returns = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList hash = new InsnList();
                hash.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hash.add(new IntInsnNode(Opcodes.BIPUSH, 31));
                hash.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hash.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "namespace", "Ljava/lang/String;"));
                hash.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
                hash.add(new InsnNode(Opcodes.IMUL));
                hash.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hash.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "path", "Ljava/lang/String;"));
                hash.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
                hash.add(new InsnNode(Opcodes.IADD));
                hash.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, hashField, "I"));
                constructor.instructions.insertBefore(instruction, hash);
                returns++;
            }
        }
        if (returns != 1) {
            throw new IllegalStateException("Identifier constructor return count changed: " + returns);
        }

        MethodNode equals = find(node, "equals", "(Ljava/lang/Object;)Z");
        InsnList equalsBody = new InsnList();
        LabelNode different = new LabelNode();
        LabelNode falseResult = new LabelNode();
        equalsBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        equalsBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
        equalsBody.add(new JumpInsnNode(Opcodes.IF_ACMPNE, different));
        equalsBody.add(new InsnNode(Opcodes.ICONST_1));
        equalsBody.add(new InsnNode(Opcodes.IRETURN));
        equalsBody.add(different);
        equalsBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
        equalsBody.add(new TypeInsnNode(Opcodes.INSTANCEOF, owner));
        equalsBody.add(new JumpInsnNode(Opcodes.IFEQ, falseResult));
        equalsBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
        equalsBody.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
        equalsBody.add(new VarInsnNode(Opcodes.ASTORE, 2));
        equalsBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        equalsBody.add(new FieldInsnNode(Opcodes.GETFIELD, owner, hashField, "I"));
        equalsBody.add(new VarInsnNode(Opcodes.ALOAD, 2));
        equalsBody.add(new FieldInsnNode(Opcodes.GETFIELD, owner, hashField, "I"));
        equalsBody.add(new JumpInsnNode(Opcodes.IF_ICMPNE, falseResult));
        equalsBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        equalsBody.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "namespace", "Ljava/lang/String;"));
        equalsBody.add(new VarInsnNode(Opcodes.ALOAD, 2));
        equalsBody.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "namespace", "Ljava/lang/String;"));
        equalsBody.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        equalsBody.add(new JumpInsnNode(Opcodes.IFEQ, falseResult));
        equalsBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        equalsBody.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "path", "Ljava/lang/String;"));
        equalsBody.add(new VarInsnNode(Opcodes.ALOAD, 2));
        equalsBody.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "path", "Ljava/lang/String;"));
        equalsBody.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        equalsBody.add(new InsnNode(Opcodes.IRETURN));
        equalsBody.add(falseResult);
        equalsBody.add(new InsnNode(Opcodes.ICONST_0));
        equalsBody.add(new InsnNode(Opcodes.IRETURN));
        replace(equals, equalsBody, 3, 3);

        MethodNode hashCode = find(node, "hashCode", "()I");
        InsnList hashCodeBody = new InsnList();
        hashCodeBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hashCodeBody.add(new FieldInsnNode(Opcodes.GETFIELD, owner, hashField, "I"));
        hashCodeBody.add(new InsnNode(Opcodes.IRETURN));
        replace(hashCode, hashCodeBody, 1, 1);
        writeComputeFrames(node, output);
        System.out.println("Memoized Identifier hashCode and rejected unequal hashes before string equals");
    }

    /**
     * Shares wall shapes between wall blocks ({@code BrowserWallShapes}): the per-state function
     * that {@code WallBlock.makeShapes(float, float)} hands to {@code getShapeForEachState} is
     * wrapped so that each (heights, up, sides) shape is joined once for all walls instead of
     * once per wall (about 125 ms of Shapes.or in the 26.3 Blocks initialization). The patch
     * checks that the function reads only {@code UP} and the {@code PROPERTY_BY_DIRECTION} sides
     * of the state and that makeShapes builds its captured shapes from the two heights alone,
     * which is what makes the shared shape equal to each wall's own.
     */
    public static void patchWallBlockShapeMemo(String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/block/WallBlock";
        String shape = "Lnet/minecraft/world/phys/shapes/VoxelShape;";
        String state = "net/minecraft/world/level/block/state/BlockState";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        MethodNode makeShapes = find(node, "makeShapes", "(FF)Ljava/util/function/Function;");
        String lambdaDesc = "(" + shape + "Ljava/util/Map;Ljava/util/Map;L" + state + ";)" + shape;
        MethodNode lambda = find(node, "lambda$makeShapes$0", lambdaDesc);

        for (AbstractInsnNode instruction : lambda.instructions) {
            if (instruction instanceof FieldInsnNode field) {
                boolean allowed = field.getOpcode() == Opcodes.GETSTATIC
                        && (field.owner.equals(owner) && (field.name.equals("UP")
                                || field.name.equals("PROPERTY_BY_DIRECTION"))
                        || field.name.startsWith("$SwitchMap$"));
                if (!allowed) {
                    throw new IllegalStateException("WallBlock shape function reads " + field.owner + "." + field.name);
                }
            } else if (instruction instanceof MethodInsnNode call && call.owner.equals(state)
                    && !call.name.equals("getValue")) {
                throw new IllegalStateException("WallBlock shape function calls BlockState." + call.name);
            } else if (instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var > 6) {
                throw new IllegalStateException("WallBlock shape function has unexpected locals");
            }
        }

        InvokeDynamicInsnNode factory = null;
        for (AbstractInsnNode instruction : makeShapes.instructions) {
            if (instruction instanceof FieldInsnNode field && !(field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(owner) && field.name.equals("WATERLOGGED"))) {
                throw new IllegalStateException("WallBlock.makeShapes reads " + field.owner + "." + field.name);
            }
            if (instruction instanceof InvokeDynamicInsnNode indy
                    && indy.desc.equals("(" + shape + "Ljava/util/Map;Ljava/util/Map;)Ljava/util/function/Function;")) {
                if (factory != null) {
                    throw new IllegalStateException("WallBlock.makeShapes creates more than one shape function");
                }
                boolean targetsLambda = false;
                for (Object argument : indy.bsmArgs) {
                    if (argument instanceof org.objectweb.asm.Handle handle && handle.getName().equals(lambda.name)
                            && handle.getDesc().equals(lambda.desc)) {
                        targetsLambda = true;
                    }
                }
                if (!targetsLambda) {
                    throw new IllegalStateException("WallBlock.makeShapes function is not lambda$makeShapes$0");
                }
                factory = indy;
            }
        }
        if (factory == null) {
            throw new IllegalStateException("WallBlock.makeShapes shape function not found");
        }
        InsnList wrap = new InsnList();
        wrap.add(new VarInsnNode(Opcodes.FLOAD, 1));
        wrap.add(new VarInsnNode(Opcodes.FLOAD, 2));
        wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/gaius/browser/BrowserWallShapes", "memo",
                "(Ljava/util/function/Function;FF)Ljava/util/function/Function;", false));
        makeShapes.instructions.insert(factory, wrap);
        makeShapes.maxStack = Math.max(makeShapes.maxStack, 4);
        writeComputeFrames(node, output);
        System.out.println("Shared WallBlock shapes between wall blocks");
    }

    /** The 26.x and 1.21.11 body: SHAPE_FULL_BLOCK_CACHE.getUnchecked(shape).booleanValue(). */
    private static void verifyShapeFullBlockBody(MethodNode method) {
        java.util.List<AbstractInsnNode> real = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) {
                real.add(instruction);
            }
        }
        boolean matches = real.size() == 6
                && real.get(0) instanceof FieldInsnNode cache && cache.getOpcode() == Opcodes.GETSTATIC
                && cache.desc.equals("Lcom/google/common/cache/LoadingCache;")
                && real.get(1) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0
                && real.get(2) instanceof MethodInsnNode get && get.name.equals("getUnchecked")
                && real.get(3) instanceof TypeInsnNode cast && cast.desc.equals("java/lang/Boolean")
                && real.get(4) instanceof MethodInsnNode unbox && unbox.name.equals("booleanValue")
                && real.get(5).getOpcode() == Opcodes.IRETURN;
        if (!matches || !method.tryCatchBlocks.isEmpty()) {
            throw new IllegalStateException("Block.isShapeFullBlock no longer reads the shape cache");
        }
    }

    private static void addInitializeSynchedDataSuperclassesHelper(ClassNode node) {
        node.methods.removeIf(method -> method.name.equals("gaius$initializeSynchedDataSuperclasses")
                && method.desc.equals("(Ljava/lang/Class;)V"));
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "gaius$initializeSynchedDataSuperclasses",
                "(Ljava/lang/Class;)V",
                null,
                null);
        InsnList code = method.instructions;
        LabelNode done = new LabelNode();

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getSuperclass",
                "()Ljava/lang/Class;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new LdcInsnNode(Type.getObjectType("java/lang/Object")));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/network/syncher/SynchedEntityData",
                "gaius$initializeSynchedDataSuperclasses",
                "(Ljava/lang/Class;)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserClassInitialization",
                "initialize",
                "(Ljava/lang/Class;)V",
                false));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        node.methods.add(method);
    }

    public static void patchBrowserClassInitialization(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "dev/gaius/browser/BrowserClassInitialization.class");
        MethodNode method = find(node, "initialize", "(Ljava/lang/Class;)V");
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "initialize",
                "()V",
                false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        writeComputeFrames(node, output);
    }

    /** Replaces EnderDragonPhase's reflective phase factory with a checked direct dispatch. */
    public static void patchEnderDragonPhaseFactory(String jar, Path output) throws IOException {
        final String owner = "net/minecraft/world/entity/boss/enderdragon/phases/EnderDragonPhase";
        final String[] phases = {
            "DragonHoldingPatternPhase", "DragonStrafePlayerPhase", "DragonLandingApproachPhase",
            "DragonLandingPhase", "DragonTakeoffPhase", "DragonSittingFlamingPhase",
            "DragonSittingScanningPhase", "DragonSittingAttackingPhase", "DragonChargePlayerPhase",
            "DragonDeathPhase", "DragonHoverPhase"
        };
        ClassNode node = read(jar, owner + ".class");
        MethodNode initializer = find(node, "<clinit>", "()V");
        ArrayList<String> registered = new ArrayList<>();
        String pendingPhase = null;
        for (AbstractInsnNode instruction : initializer.instructions) {
            if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Type type
                    && type.getSort() == Type.OBJECT) {
                pendingPhase = type.getInternalName();
            } else if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals("create")) {
                if (pendingPhase == null) {
                    throw new IOException("EnderDragon phase registry create call lost its class literal");
                }
                registered.add(pendingPhase);
                pendingPhase = null;
            }
        }
        ArrayList<String> expected = new ArrayList<>();
        for (String phase : phases) {
            expected.add("net/minecraft/world/entity/boss/enderdragon/phases/" + phase);
        }
        if (!registered.equals(expected)) {
            throw new IOException("EnderDragon phase registry changed: expected " + expected + " but found " + registered);
        }
        MethodNode method = find(node, "createInstance",
                "(Lnet/minecraft/world/entity/boss/enderdragon/EnderDragon;)"
                        + "Lnet/minecraft/world/entity/boss/enderdragon/phases/DragonPhaseInstance;");
        boolean reflective = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("java/lang/reflect/Constructor")
                    && call.name.equals("newInstance")) {
                reflective = true;
            }
        }
        if (!reflective) {
            throw new IOException("EnderDragonPhase.createInstance no longer has the expected reflective factory");
        }
        InsnList code = method.instructions;
        code.clear();
        LabelNode[] labels = new LabelNode[phases.length];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = new LabelNode();
        }
        LabelNode invalid = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "getId", "()I", false));
        code.add(new TableSwitchInsnNode(0, phases.length - 1, invalid, labels));
        for (int index = 0; index < phases.length; index++) {
            code.add(labels[index]);
            String phaseOwner = "net/minecraft/world/entity/boss/enderdragon/phases/" + phases[index];
            code.add(new TypeInsnNode(Opcodes.NEW, phaseOwner));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, phaseOwner, "<init>",
                    "(Lnet/minecraft/world/entity/boss/enderdragon/EnderDragon;)V", false));
            code.add(new InsnNode(Opcodes.ARETURN));
        }
        code.add(invalid);
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Error"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode("Unknown EnderDragon phase id"));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Error", "<init>",
                "(Ljava/lang/String;)V", false));
        code.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.clear();
        writeComputeFrames(node, output);
    }

    public static void patchClassTreeIdRegistry(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/ClassTreeIdRegistry.class");
        MethodNode method = find(node, "getLastIdFor", "(Ljava/lang/Class;)I");
        InsnList code = new InsnList();
        LabelNode scanAssignable = new LabelNode();
        LabelNode superclassLoop = new LabelNode();
        LabelNode scanLoop = new LabelNode();
        LabelNode returnBest = new LabelNode();
        LabelNode superclassNameLookup = new LabelNode();

        // int exact = classToLastIdCache.getInt(clazz);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/util/ClassTreeIdRegistry",
                "classToLastIdCache",
                "Lit/unimi/dsi/fastutil/objects/Object2IntMap;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap",
                "getInt",
                "(Ljava/lang/Object;)I",
                true));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, superclassLoop));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new InsnNode(Opcodes.IRETURN));

        // Keep vanilla behavior first: walk Class.getSuperclass().
        code.add(superclassLoop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        LabelNode superclassNext = new LabelNode();
        code.add(superclassNext);
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getSuperclass",
                "()Ljava/lang/Class;",
                false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new JumpInsnNode(Opcodes.IFNULL, scanAssignable));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new LdcInsnNode(Type.getObjectType("java/lang/Object")));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, scanAssignable));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/util/ClassTreeIdRegistry",
                "classToLastIdCache",
                "Lit/unimi/dsi/fastutil/objects/Object2IntMap;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap",
                "getInt",
                "(Ljava/lang/Object;)I",
                true));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, superclassNameLookup));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(superclassNameLookup);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/util/ClassTreeIdRegistry",
                "gaius$getCachedIdByName",
                "(Ljava/lang/Class;)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, superclassNext));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.IRETURN));

        // Browser fallback: Class objects produced by getSuperclass() can miss
        // map identity matches after TeaVM translation. Scan known registered
        // classes and use assignability to find the deepest inherited data id.
        code.add(scanAssignable);
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/util/ClassTreeIdRegistry",
                "classToLastIdCache",
                "Lit/unimi/dsi/fastutil/objects/Object2IntMap;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap",
                "object2IntEntrySet",
                "()Lit/unimi/dsi/fastutil/objects/ObjectSet;",
                true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/lang/Iterable",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(scanLoop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, returnBest));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "it/unimi/dsi/fastutil/objects/Object2IntMap$Entry"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map$Entry",
                "getKey",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Class"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 7));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, scanLoop));
        code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "isAssignableFrom",
                "(Ljava/lang/Class;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, scanLoop));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap$Entry",
                "getIntValue",
                "()I",
                true));
        code.add(new VarInsnNode(Opcodes.ISTORE, 8));
        code.add(new VarInsnNode(Opcodes.ILOAD, 8));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPLE, scanLoop));
        code.add(new VarInsnNode(Opcodes.ILOAD, 8));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new JumpInsnNode(Opcodes.GOTO, scanLoop));
        code.add(returnBest);
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.IRETURN));

        replace(method, code, 4, 9);
        addCachedIdByNameHelper(node);
        writeComputeFrames(node, output);
    }

    private static void addCachedIdByNameHelper(ClassNode node) {
        node.methods.removeIf(method -> method.name.equals("gaius$getCachedIdByName")
                && method.desc.equals("(Ljava/lang/Class;)I"));
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                "gaius$getCachedIdByName",
                "(Ljava/lang/Class;)I",
                null,
                null);
        InsnList code = method.instructions;
        LabelNode loop = new LabelNode();
        LabelNode missing = new LabelNode();

        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getName",
                "()Ljava/lang/String;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/util/ClassTreeIdRegistry",
                "classToLastIdCache",
                "Lit/unimi/dsi/fastutil/objects/Object2IntMap;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap",
                "object2IntEntrySet",
                "()Lit/unimi/dsi/fastutil/objects/ObjectSet;",
                true));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/lang/Iterable",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, missing));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "it/unimi/dsi/fastutil/objects/Object2IntMap$Entry"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map$Entry",
                "getKey",
                "()Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Class"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getName",
                "()Ljava/lang/String;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "equals",
                "(Ljava/lang/Object;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, loop));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/objects/Object2IntMap$Entry",
                "getIntValue",
                "()I",
                true));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(missing);
        code.add(new InsnNode(Opcodes.ICONST_M1));
        code.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 2;
        method.maxLocals = 5;
        node.methods.add(method);
    }

    public static void patchClientShutdownWatchdog(String jar, Path output) throws IOException {
        ClassNode node = read(
                jar, "com/mojang/blaze3d/platform/ClientShutdownWatchdog.class");
        MethodNode start = null;
        for (MethodNode candidate : node.methods) {
            if (!candidate.name.equals("startShutdownWatchdog")
                    || Type.getReturnType(candidate.desc).getSort() != Type.VOID) {
                continue;
            }
            if (start != null) {
                throw new IllegalStateException("Multiple client shutdown watchdog methods found");
            }
            start = candidate;
        }
        if (start == null) {
            throw new IllegalStateException("Client shutdown watchdog method was not found");
        }
        int maxLocals = start.maxLocals;
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(start, code, 0, maxLocals);
        write(node, output);
    }

    public static void patchResourceKeyRegistryRoot(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/resources/ResourceKey.class");
        MethodNode createRegistryKey = find(
                node,
                "createRegistryKey",
                "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/resources/ResourceKey;");
        InsnList code = new InsnList();
        code.add(new LdcInsnNode("root"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/resources/Identifier",
                "withDefaultNamespace",
                "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/resources/ResourceKey",
                "create",
                "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/resources/Identifier;)"
                        + "Lnet/minecraft/resources/ResourceKey;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(createRegistryKey, code, 2, 1);

        MethodNode clinit = find(node, "<clinit>", "()V");
        // VALUES = new MapMaker().weakValues().makeMap() -> new ConcurrentHashMap() (no weak
        // references in the browser). 26.2's <clinit> holds nothing else and keeps the original
        // whole-body rewrite; 26.3 first assigns REGISTRY_STREAM_CODEC (used by every packet that
        // carries a registry key, e.g. clientbound update_tags), so there only the MapMaker
        // sequence is swapped and the rest of the initializer stays vanilla.
        java.util.List<AbstractInsnNode> mapMaker = resourceKeyValuesMapMaker(clinit);
        int opcodes = 0;
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (instruction.getOpcode() >= 0) {
                opcodes++;
            }
        }
        if (opcodes == mapMaker.size() + 2) {
            InsnList init = new InsnList();
            init.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap"));
            init.add(new InsnNode(Opcodes.DUP));
            init.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "java/util/concurrent/ConcurrentHashMap",
                    "<init>",
                    "()V",
                    false));
            init.add(new FieldInsnNode(
                    Opcodes.PUTSTATIC,
                    "net/minecraft/resources/ResourceKey",
                    "VALUES",
                    "Ljava/util/concurrent/ConcurrentMap;"));
            init.add(new InsnNode(Opcodes.RETURN));
            replace(clinit, init, 2, 0);
        } else {
            AbstractInsnNode anchor = mapMaker.get(0);
            clinit.instructions.insertBefore(anchor,
                    new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap"));
            clinit.instructions.insertBefore(anchor, new InsnNode(Opcodes.DUP));
            clinit.instructions.insertBefore(anchor, new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "java/util/concurrent/ConcurrentHashMap",
                    "<init>",
                    "()V",
                    false));
            for (AbstractInsnNode instruction : mapMaker) {
                clinit.instructions.remove(instruction);
            }
            // new/dup need two stack slots, exactly what the MapMaker sequence needed.
            clinit.maxStack = Math.max(clinit.maxStack, 2);
        }
        write(node, output);
    }

    /**
     * The five instructions {@code new MapMaker; dup; invokespecial <init>; invokevirtual
     * weakValues; invokevirtual makeMap} that feed {@code putstatic ResourceKey.VALUES} in the
     * vanilla static initializer; fails when the shape changed.
     */
    private static java.util.List<AbstractInsnNode> resourceKeyValuesMapMaker(MethodNode clinit) {
        String mapMaker = "com/google/common/collect/MapMaker";
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !field.owner.equals("net/minecraft/resources/ResourceKey")
                    || !field.name.equals("VALUES")) {
                continue;
            }
            java.util.List<AbstractInsnNode> sequence = new java.util.ArrayList<>();
            AbstractInsnNode cursor = field;
            for (int i = 0; i < 5; i++) {
                cursor = cursor.getPrevious();
                while (cursor != null && cursor.getOpcode() < 0) {
                    cursor = cursor.getPrevious();
                }
                if (cursor == null) {
                    break;
                }
                sequence.add(0, cursor);
            }
            boolean shape = sequence.size() == 5
                    && sequence.get(0) instanceof TypeInsnNode create
                    && create.getOpcode() == Opcodes.NEW && create.desc.equals(mapMaker)
                    && sequence.get(1).getOpcode() == Opcodes.DUP
                    && sequence.get(2) instanceof MethodInsnNode init
                    && init.getOpcode() == Opcodes.INVOKESPECIAL && init.owner.equals(mapMaker)
                    && init.name.equals("<init>")
                    && sequence.get(3) instanceof MethodInsnNode weak
                    && weak.owner.equals(mapMaker) && weak.name.equals("weakValues")
                    && sequence.get(4) instanceof MethodInsnNode make
                    && make.owner.equals(mapMaker) && make.name.equals("makeMap");
            if (!shape) {
                throw new IllegalStateException(
                        "ResourceKey.<clinit> no longer builds VALUES with MapMaker.weakValues().makeMap()");
            }
            return sequence;
        }
        throw new IllegalStateException("ResourceKey.<clinit> does not assign VALUES");
    }

    public static void patchReflectivePatternArray(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/world/level/block/state/pattern/BlockPatternBuilder.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("java/lang/reflect/Array")
                        && call.name.equals("newInstance")
                        && call.desc.equals("(Ljava/lang/Class;[I)Ljava/lang/Object;")) {
                    call.owner = "dev/gaius/browser/BrowserArrays";
                    found = true;
                }
            }
        }
        if (!found) {
            throw new IllegalStateException("BlockPatternBuilder reflective array call not found");
        }
        write(node, output);
    }

    public static void patchChunkPosSpliterator(String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/ChunkPos$2";
        ClassNode node = read(jar, owner + ".class");
        node.superName = "java/lang/Object";
        if (!node.interfaces.contains("java/util/Spliterator")) {
            node.interfaces.add("java/util/Spliterator");
        }
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "browserRemaining", "J", null, null));
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "browserCharacteristics", "I", null, null));

        MethodNode constructor = find(node, "<init>",
                "(JILnet/minecraft/world/level/ChunkPos;"
                        + "Lnet/minecraft/world/level/ChunkPos;II)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        putField(code, owner, "val$from", "Lnet/minecraft/world/level/ChunkPos;",
                Opcodes.ALOAD, 4);
        putField(code, owner, "val$to", "Lnet/minecraft/world/level/ChunkPos;",
                Opcodes.ALOAD, 5);
        putField(code, owner, "val$zDiff", "I", Opcodes.ILOAD, 6);
        putField(code, owner, "val$xDiff", "I", Opcodes.ILOAD, 7);
        putField(code, owner, "browserRemaining", "J", Opcodes.LLOAD, 1);
        putField(code, owner, "browserCharacteristics", "I", Opcodes.ILOAD, 3);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, code, 3, 8);

        MethodNode split = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "trySplit",
                "()Ljava/util/Spliterator;",
                null,
                null);
        split.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        split.instructions.add(new InsnNode(Opcodes.ARETURN));
        split.maxStack = 1;
        split.maxLocals = 1;
        node.methods.add(split);

        MethodNode size = new MethodNode(
                Opcodes.ACC_PUBLIC, "estimateSize", "()J", null, null);
        size.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        size.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserRemaining", "J"));
        size.instructions.add(new InsnNode(Opcodes.LRETURN));
        size.maxStack = 2;
        size.maxLocals = 1;
        node.methods.add(size);

        MethodNode characteristics = new MethodNode(
                Opcodes.ACC_PUBLIC, "characteristics", "()I", null, null);
        characteristics.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        characteristics.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "browserCharacteristics", "I"));
        characteristics.instructions.add(new InsnNode(Opcodes.IRETURN));
        characteristics.maxStack = 1;
        characteristics.maxLocals = 1;
        node.methods.add(characteristics);
        write(node, output);
    }

    public static void patchDetectedVersion(String jar, Path output, String minecraftVersion)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/DetectedVersion.class");
        MethodNode detect = find(
                node, "tryDetectVersion", "()Lnet/minecraft/WorldVersion;");
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(minecraftVersion));
        code.add(new LdcInsnNode(minecraftVersion));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/DetectedVersion",
                "createBuiltIn",
                "(Ljava/lang/String;Ljava/lang/String;Z)Lnet/minecraft/WorldVersion;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(detect, code, 3, 0);
        write(node, output);
    }

    public static void patchBlocksBrowserStartupYield(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/block/Blocks.class");
        MethodNode register = find(
                node,
                "register",
                "(Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Function;"
                        + "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)"
                        + "Lnet/minecraft/world/level/block/Block;");
        int patchedReturns = 0;
        for (AbstractInsnNode instruction = register.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) {
                continue;
            }
            InsnList checkpoint = new InsnList();
            checkpoint.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserStartupScheduler",
                    "blockRegistered",
                    "()V",
                    false));
            register.instructions.insertBefore(instruction, checkpoint);
            patchedReturns++;
        }
        if (patchedReturns != 1) {
            throw new IllegalStateException("Blocks register return shape changed");
        }
        write(node, output);
    }

    public static void patchBlockStateBaseBrowserStartupYield(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
        ClassNode node = read(jar, owner + ".class");
        MethodNode initCache = find(node, "initCache", "()V");
        int patchedReturns = 0;
        for (AbstractInsnNode instruction = initCache.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList checkpoint = new InsnList();
            checkpoint.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserStartupScheduler",
                    "blockStateInitialized",
                    "()V",
                    false));
            initCache.instructions.insertBefore(instruction, checkpoint);
            patchedReturns++;
        }
        if (patchedReturns != 1) {
            throw new IllegalStateException("Block state cache return shape changed");
        }
        write(node, output);
    }

    public static void patchBuiltInRegistriesBrowserStartupYield(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/core/registries/BuiltInRegistries";
        ClassNode node = read(jar, owner + ".class");
        String entryDescriptor =
                "(Lnet/minecraft/resources/Identifier;Ljava/util/function/Supplier;)V";
        MethodNode createContentsEntry = null;
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$createContents$")
                    || !method.desc.equals(entryDescriptor)) {
                continue;
            }
            if (createContentsEntry != null) {
                throw new IllegalStateException(
                        "Multiple BuiltInRegistries createContents entry lambdas found");
            }
            createContentsEntry = method;
        }
        if (createContentsEntry == null) {
            throw new IllegalStateException(
                    "BuiltInRegistries createContents entry lambda was not found");
        }
        int patchedReturns = 0;
        for (AbstractInsnNode instruction = createContentsEntry.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            createContentsEntry.instructions.insertBefore(instruction, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserStartupScheduler",
                    "registryBootstrapCompleted",
                    "()V",
                    false));
            patchedReturns++;
        }
        if (patchedReturns != 1) {
            throw new IllegalStateException(
                    "BuiltInRegistries createContents entry return shape changed");
        }
        write(node, output);
    }

    public static void patchSimpleJsonResourceReloadListenerBrowserStartupYield(
            String jar,
            Path output) throws IOException {
        String owner = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
        ClassNode node = read(jar, owner + ".class");
        String scanDirectoryDescriptor = "(Lnet/minecraft/server/packs/resources/ResourceManager;"
                + "Lnet/minecraft/resources/FileToIdConverter;"
                + "Lcom/mojang/serialization/DynamicOps;"
                + "Lcom/mojang/serialization/Codec;Ljava/util/Map;)V";
        MethodNode scanDirectory = findNullable(node, "scanDirectory", scanDirectoryDescriptor);
        if (scanDirectory == null) {
            // Minecraft 26.3 removed both static scanDirectory overloads and inlined the same
            // listMatchingResources/parse loop into prepare(ResourceManager, ProfilerFiller)Map.
            // SimpleJsonResourceReloadListener is client-only there (WaypointStyleManager,
            // EquipmentAssetManager); the server Worker's datapack JSON (world, dimension,
            // recipe and loot registries) now decodes through RegistryLoadTask, whose yield is
            // ServerPatches263.patchRegistryLoadTaskBrowserStartupYield. Keep the 26.2 client
            // behaviour by yielding in the inlined loop; fail closed if neither shape exists.
            if (node.methods.stream().anyMatch(method -> method.name.equals("scanDirectory"))) {
                throw new IllegalStateException(
                        "SimpleJsonResourceReloadListener.scanDirectory changed its descriptor");
            }
            scanDirectory = find(
                    node,
                    "prepare",
                    "(Lnet/minecraft/server/packs/resources/ResourceManager;"
                            + "Lnet/minecraft/util/profiling/ProfilerFiller;)Ljava/util/Map;");
        }
        MethodInsnNode hasNext = null;
        for (AbstractInsnNode instruction = scanDirectory.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/Iterator")
                    && call.name.equals("hasNext")
                    && call.desc.equals("()Z")) {
                hasNext = call;
                break;
            }
        }
        if (hasNext == null) {
            throw new IllegalStateException("Datapack resource loop condition was not found");
        }
        LabelNode loopStart = null;
        for (AbstractInsnNode instruction = hasNext.getPrevious();
                instruction != null;
                instruction = instruction.getPrevious()) {
            if (instruction instanceof LabelNode label) {
                loopStart = label;
                break;
            }
        }
        JumpInsnNode loopBack = null;
        for (AbstractInsnNode instruction = hasNext.getNext();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO
                    && jump.label == loopStart) {
                loopBack = jump;
                break;
            }
        }
        if (loopStart == null || loopBack == null) {
            throw new IllegalStateException("Datapack resource loop back edge was not found");
        }
        InsnList checkpoint = new InsnList();
        checkpoint.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserStartupScheduler",
                "datapackResourceDecoded",
                "()V",
                false));
        scanDirectory.instructions.insertBefore(loopBack, checkpoint);
        write(node, output);
    }

    public static void patchNativeModuleLister(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/NativeModuleLister.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("listModules") && method.desc.equals("()Ljava/util/List;")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
                        "()Ljava/util/List;", false));
                code.add(new InsnNode(Opcodes.ARETURN));
                method.instructions = code;
                method.tryCatchBlocks.clear();
                method.maxStack = 1;
                method.maxLocals = 0;
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("NativeModuleLister.listModules was not found");
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    public static void patchJvmProfiler(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/profiling/jfr/JvmProfiler.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new TypeInsnNode(
                        Opcodes.NEW,
                        "net/minecraft/util/profiling/jfr/JvmProfiler$NoOpProfiler"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "net/minecraft/util/profiling/jfr/JvmProfiler$NoOpProfiler",
                        "<init>",
                        "()V",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/util/profiling/jfr/JvmProfiler",
                        "INSTANCE",
                        "Lnet/minecraft/util/profiling/jfr/JvmProfiler;"));
                code.add(new InsnNode(Opcodes.RETURN));
                method.instructions = code;
                method.tryCatchBlocks.clear();
                method.maxStack = 2;
                method.maxLocals = 0;
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("JvmProfiler.<clinit> was not found");
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    public static void patchUtilRunNamedBrowserOutput(Path output) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(output)).accept(node, 0);
        MethodNode method = find(node, "runNamed", "(Ljava/lang/Runnable;Ljava/lang/String;)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 1, 2);
        writeComputeFrames(node, output);
    }

    /** Replaces Minecraft's browser-incompatible timed queue spin with a cooperative pump. */
    public static void patchUtilBlockUntilDoneBrowserOutput(Path output) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(output)).accept(node, 0);
        MethodNode method = find(node, "blockUntilDone",
                "(Ljava/util/function/Function;Ljava/util/function/Predicate;)Ljava/lang/Object;");
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !call.owner.equals("java/util/concurrent/BlockingQueue")
                    || !call.name.equals("poll")
                    || !call.desc.equals("(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;")) {
                continue;
            }
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = "dev/gaius/browser/BrowserFuturePump";
            call.name = "poll";
            call.desc = "(Ljava/util/concurrent/BlockingQueue;JLjava/util/concurrent/TimeUnit;)"
                    + "Ljava/lang/Object;";
            call.itf = false;
            patched++;
        }
        if (patched != 1) {
            throw new IOException("Util.blockUntilDone timed queue poll patch count changed: "
                    + patched);
        }
        writeComputeFrames(node, output);
    }

    public static void patchTracyZoneFiller(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/profiling/TracyZoneFiller.class");
        boolean pushFound = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("push") && method.desc.equals("(Ljava/lang/String;)V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 2);
                pushFound = true;
            } else if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "com/mojang/logging/LogUtils",
                        "getLogger",
                        "()Lorg/slf4j/Logger;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/util/profiling/TracyZoneFiller",
                        "LOGGER",
                        "Lorg/slf4j/Logger;"));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/util/profiling/TracyZoneFiller",
                        "STACK_WALKER",
                        "Ljava/lang/StackWalker;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            }
        }
        if (!pushFound) {
            throw new IllegalStateException("TracyZoneFiller.push was not found");
        }
        write(node, output);
    }

    /** Removes Tracy's StackWalker/native loader path from browser executors. */
    public static void patchTracingExecutorBrowser(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/TracingExecutor.class");
        MethodNode wrap = find(node, "wrapUnnamed", "(Ljava/lang/Runnable;)Ljava/lang/Runnable;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(wrap, code, 1, 1);
        MethodNode forName = find(node, "forName", "(Ljava/lang/String;)Ljava/util/concurrent/Executor;");
        InsnList directExecutor = new InsnList();
        directExecutor.add(new VarInsnNode(Opcodes.ALOAD, 0));
        directExecutor.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/TracingExecutor",
                "service",
                "Ljava/util/concurrent/ExecutorService;"));
        directExecutor.add(new InsnNode(Opcodes.ARETURN));
        replace(forName, directExecutor, 1, 2);
        write(node, output);
    }

    public static void patchBlockableEventLoopBrowser(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/thread/BlockableEventLoop.class");
        if (BuildTarget.wasmGc()) {
            MethodNode managedBlock = find(node, "managedBlock",
                    "(Ljava/util/function/BooleanSupplier;)V");
            int lastYieldLocal = managedBlock.maxLocals;
            int predicateLocal = lastYieldLocal + 2;
            InsnList initialTime = new InsnList();
            initialTime.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
            initialTime.add(new VarInsnNode(Opcodes.LSTORE, lastYieldLocal));
            managedBlock.instructions.insert(initialTime);
            boolean predicatePatched = false;
            for (AbstractInsnNode instruction : managedBlock.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && call.owner.equals("java/util/function/BooleanSupplier")
                        && call.name.equals("getAsBoolean")
                        && call.desc.equals("()Z")) {
                    LabelNode predicateTrue = new LabelNode();
                    InsnList yield = new InsnList();
                    yield.add(call.clone(null));
                    yield.add(new VarInsnNode(Opcodes.ISTORE, predicateLocal));
                    yield.add(new VarInsnNode(Opcodes.ILOAD, predicateLocal));
                    yield.add(new JumpInsnNode(Opcodes.IFNE, predicateTrue));
                    yield.add(new VarInsnNode(Opcodes.ILOAD, predicateLocal));
                    yield.add(new VarInsnNode(Opcodes.LLOAD, lastYieldLocal));
                    yield.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "dev/gaius/browser/HostLoop",
                            "managedBlockYield",
                            "(ZJ)J",
                            false));
                    yield.add(new VarInsnNode(Opcodes.LSTORE, lastYieldLocal));
                    yield.add(predicateTrue);
                    yield.add(new VarInsnNode(Opcodes.ILOAD, predicateLocal));
                    managedBlock.instructions.insertBefore(instruction, yield);
                    managedBlock.instructions.remove(instruction);
                    predicatePatched = true;
                    break;
                }
            }
            if (!predicatePatched) {
                throw new IllegalStateException("BlockableEventLoop.managedBlock predicate shape changed");
            }
            boolean idlePatched = false;
            for (AbstractInsnNode instruction : managedBlock.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals("net/minecraft/util/thread/BlockableEventLoop")
                        && call.name.equals("waitForTasks")
                        && call.desc.equals("()V")) {
                    InsnList idle = new InsnList();
                    idle.add(new VarInsnNode(Opcodes.LLOAD, lastYieldLocal));
                    idle.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "dev/gaius/browser/HostLoop",
                            "managedBlockIdle",
                            "(J)J",
                            false));
                    idle.add(new VarInsnNode(Opcodes.LSTORE, lastYieldLocal));
                    managedBlock.instructions.insertBefore(instruction, idle);
                    idlePatched = true;
                    break;
                }
            }
            if (!idlePatched) {
                throw new IllegalStateException("BlockableEventLoop.managedBlock wait shape changed");
            }
            managedBlock.maxLocals = Math.max(managedBlock.maxLocals, predicateLocal + 1);
            managedBlock.maxStack = Math.max(managedBlock.maxStack, 3);
        }
        MethodNode method = find(node, "doRunTask", "(Ljava/lang/Runnable;)V");
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        int enteredLocal = 2;
        int throwableLocal = 3;
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "beginScheduledNetworkInputTask",
                "(Ljava/lang/Runnable;)Z",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, enteredLocal));
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        code.add(end);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, enteredLocal));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "endScheduledNetworkInputTask",
                "(Ljava/lang/Runnable;Z)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, enteredLocal));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserIntegratedServerMain",
                "endScheduledNetworkInputTask",
                "(Ljava/lang/Runnable;Z)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        code.add(new InsnNode(Opcodes.ATHROW));
        replace(method, code, 2, 4);
        method.tryCatchBlocks.clear();
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        method.maxStack = Math.max(method.maxStack, 2);
        writeComputeFrames(node, output);
    }

    public static void patchMacosUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/MacosUtil.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/MacosUtil",
                        "IS_MACOS",
                        "Z"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            } else if ((method.name.equals("exitNativeFullscreen")
                    || method.name.equals("clearResizableBit")
                    || method.name.equals("loadIcon"))
                    && (method.access & Opcodes.ACC_STATIC) != 0) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    public static void patchMemoryDebug(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/components/debug/"
                        + "DebugEntryMemory$AllocationRateCalculator.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/util/Collections",
                        "emptyList",
                        "()Ljava/util/List;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/client/gui/components/debug/"
                                + "DebugEntryMemory$AllocationRateCalculator",
                        "GC_MBEANS",
                        "Ljava/util/List;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            }
        }
        write(node, output);
    }

    public static void patchMinecraft(String jar, Path outputRoot) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/Minecraft.class");
        boolean hasNoRender = node.fields.stream().anyMatch(field ->
                field.name.equals("noRender") && field.desc.equals("Z"));
        boolean found = false;
        boolean stateHooked = false;
        boolean throwableHooked = false;
        boolean browserChannelPumpHooked = false;
        boolean clientPacketFrameBoundaryHooked = false;
        boolean singleplayerWorkerHooked = false;
        boolean singleplayerWorkerStopHooked = false;
        boolean singleplayerWorkerReturnHooked = false;
        boolean browserCrashHooked = false;
        boolean lazyDataFixerHooked = false;
        boolean foregroundResourceReloadHooked = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals("net/minecraft/util/datafix/DataFixers")
                            && call.name.equals("getDataFixer")
                            && call.desc.equals("()Lcom/mojang/datafixers/DataFixer;")) {
                        call.owner = "dev/gaius/browser/BrowserLazyDataFixer";
                        call.name = "instance";
                        lazyDataFixerHooked = true;
                    }
                }
            }
            if (method.name.equals("run") && method.desc.equals("()V")) {
                throwableHooked = hookMinecraftRunCatchDiagnostics(method);
            } else if (isJvmUptimeLambda(method)) {
                InsnList code = new InsnList();
                code.add(new LdcInsnNode("Browser runtime"));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 0);
                found = true;
            } else if (method.name.equals("runTick") && method.desc.equals("(Z)V")) {
                InsnList browserPackets = new InsnList();
                MethodInsnNode beginClientPacketFrame = new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserClientNetwork",
                        "beginClientPacketFrame",
                        "()V",
                        false);
                MethodInsnNode installClientNetwork = new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserClientNetwork",
                        "install",
                        "()V",
                        false);
                MethodInsnNode pumpClientChannels = pumpBrowserChannels();
                browserPackets.add(beginClientPacketFrame);
                browserPackets.add(installClientNetwork);
                browserPackets.add(pumpClientChannels);
                method.instructions.insert(browserPackets);
                if (nextOpcode(beginClientPacketFrame) != installClientNetwork
                        || nextOpcode(installClientNetwork) != pumpClientChannels) {
                    throw new IllegalStateException(
                            "Minecraft runTick browser packet frame/pump order changed");
                }
                int scheduledPacketBoundaries = 0;
                MethodInsnNode scheduledPacketBoundary = null;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && call.owner.equals("net/minecraft/network/PacketProcessor")
                            && call.name.equals("processQueuedPackets")
                            && call.desc.equals("()V")) {
                        scheduledPacketBoundaries++;
                        scheduledPacketBoundary = call;
                    }
                }
                if (scheduledPacketBoundaries != 1 || scheduledPacketBoundary == null) {
                    throw new IllegalStateException(
                            "Minecraft runTick scheduled PacketProcessor boundary changed: "
                                    + scheduledPacketBoundaries);
                }
                MethodInsnNode frameBoundaryWrapper = new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserClientNetwork",
                        "processClientPacketsAtScheduledFrameBoundary",
                        "(Lnet/minecraft/network/PacketProcessor;)V",
                        false);
                method.instructions.set(scheduledPacketBoundary, frameBoundaryWrapper);
                int wrapperCalls = 0;
                int directPacketProcessorCalls = 0;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode call)) {
                        continue;
                    }
                    if (call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals("dev/gaius/browser/BrowserClientNetwork")
                            && call.name.equals("processClientPacketsAtScheduledFrameBoundary")
                            && call.desc.equals("(Lnet/minecraft/network/PacketProcessor;)V")) {
                        wrapperCalls++;
                    } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && call.owner.equals("net/minecraft/network/PacketProcessor")
                            && call.name.equals("processQueuedPackets")
                            && call.desc.equals("()V")) {
                        directPacketProcessorCalls++;
                    }
                }
                if (wrapperCalls != 1 || directPacketProcessorCalls != 0) {
                    throw new IllegalStateException(
                            "Minecraft runTick packet boundary replacement changed: wrapper="
                                    + wrapperCalls + ", direct=" + directPacketProcessorCalls);
                }
                browserChannelPumpHooked = true;
                clientPacketFrameBoundaryHooked = true;
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        method.instructions.insertBefore(
                                instruction, minecraftStateReport(hasNoRender));
                        stateHooked = true;
                    }
                }
                method.maxStack = Math.max(method.maxStack, 9);
            } else if (method.name.equals("debugClientMetricsStart")
                    && method.desc.equals("(Ljava/util/function/Consumer;)Z")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, 2);
            } else if (method.name.equals("doWorldLoad")
                    && (method.desc.equals(
                                    "(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                            + "Lnet/minecraft/server/packs/repository/PackRepository;"
                                            + "Lnet/minecraft/server/WorldStem;Z)V")
                            || method.desc.equals(
                                    "(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                            + "Lnet/minecraft/server/packs/repository/PackRepository;"
                                            + "Lnet/minecraft/server/WorldStem;Ljava/util/Optional;Z)V"))) {
                // The modern doWorldLoad also receives the game rules chosen on the Create World
                // screen (local 4) and hands them to the IntegratedServer constructor. They are not
                // part of the saved level, so they have to travel to the Worker explicitly.
                boolean initialGameRules = method.desc.contains("Ljava/util/Optional;");
                int newWorldLocal = initialGameRules ? 5 : 4;
                LabelNode vanillaIntegratedServer = new LabelNode();
                InsnList worker = new InsnList();
                worker.add(new VarInsnNode(Opcodes.ALOAD, 0));
                worker.add(new VarInsnNode(Opcodes.ALOAD, 1));
                worker.add(new VarInsnNode(Opcodes.ALOAD, 3));
                if (initialGameRules) {
                    worker.add(new VarInsnNode(Opcodes.ALOAD, 4));
                }
                worker.add(new VarInsnNode(Opcodes.ILOAD, newWorldLocal));
                worker.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserSingleplayerClient",
                        "open",
                        "(Lnet/minecraft/client/Minecraft;"
                                + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
                                + "Lnet/minecraft/server/WorldStem;"
                                + (initialGameRules ? "Ljava/util/Optional;" : "")
                                + "Z)Z",
                        false));
                worker.add(new JumpInsnNode(Opcodes.IFEQ, vanillaIntegratedServer));
                worker.add(new InsnNode(Opcodes.RETURN));
                worker.add(vanillaIntegratedServer);
                method.instructions.insert(worker);
                method.maxStack = Math.max(method.maxStack, initialGameRules ? 5 : 4);
                singleplayerWorkerHooked = true;
            } else if (method.name.equals("disconnect")
                    && method.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;ZZ)V")) {
                method.instructions.insert(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserSingleplayerClient",
                        "stop",
                        "()V",
                        false));
                singleplayerWorkerStopHooked = true;
            } else if (method.name.equals("disconnectFromWorld")
                    && method.desc.equals("(Lnet/minecraft/network/chat/Component;)V")) {
                singleplayerWorkerReturnHooked = replaceLocalServerCheck(method);
            } else if (method.name.equals("crash")
                    && (method.desc.equals(
                                    "(Lnet/minecraft/client/Minecraft;Ljava/io/File;"
                                            + "Lnet/minecraft/CrashReport;)V")
                            || method.desc.equals(
                                    "(Lnet/minecraft/client/Minecraft;Ljava/io/File;"
                                            + "Lnet/minecraft/CrashReport;I)V"))) {
                boolean hasExitCode = method.desc.endsWith(";I)V");
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                if (hasExitCode) {
                    code.add(new VarInsnNode(Opcodes.ILOAD, 3));
                }
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "net/minecraft/client/Minecraft",
                        "saveReport",
                        hasExitCode
                                ? "(Ljava/io/File;Lnet/minecraft/CrashReport;I)I"
                                : "(Ljava/io/File;Lnet/minecraft/CrashReport;)I",
                        false));
                code.add(new InsnNode(Opcodes.POP));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, hasExitCode ? 3 : 2, hasExitCode ? 4 : 3);
                browserCrashHooked = true;
            } else if (method.name.equals("getOverlay")
                    && method.desc.equals("()Lnet/minecraft/client/gui/screens/Overlay;")) {
                LabelNode returnOverlay = new LabelNode();
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "overlay",
                        "Lnet/minecraft/client/gui/screens/Overlay;"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new TypeInsnNode(
                        Opcodes.INSTANCEOF,
                        "net/minecraft/client/gui/screens/LoadingOverlay"));
                code.add(new JumpInsnNode(Opcodes.IFEQ, returnOverlay));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/Minecraft",
                        "level",
                        "Lnet/minecraft/client/multiplayer/ClientLevel;"));
                code.add(new JumpInsnNode(Opcodes.IFNULL, returnOverlay));
                code.add(new InsnNode(Opcodes.POP));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(returnOverlay);
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 2, 1);
                foregroundResourceReloadHooked = true;
            }
        }
        if (!foregroundResourceReloadHooked) {
            patchGuiForegroundResourceReload(
                    jar, outputRoot.resolve("net/minecraft/client/gui/Gui.class"));
            foregroundResourceReloadHooked = true;
        }
        if (!found) {
            throw new IllegalStateException("Minecraft uptime lambda was not found");
        }
        if (!stateHooked) {
            throw new IllegalStateException("Minecraft runTick hook point was not found");
        }
        if (!browserChannelPumpHooked) {
            throw new IllegalStateException("Minecraft browser channel pump hook point was not found");
        }
        if (!clientPacketFrameBoundaryHooked) {
            throw new IllegalStateException(
                    "Minecraft client packet frame-boundary hook point was not found");
        }
        if (!throwableHooked) {
            throw new IllegalStateException("Minecraft run throwable diagnostic hook point was not found");
        }
        if (!singleplayerWorkerHooked) {
            throw new IllegalStateException("Minecraft singleplayer worker hook point was not found");
        }
        if (!singleplayerWorkerStopHooked) {
            throw new IllegalStateException("Minecraft singleplayer worker stop hook point was not found");
        }
        if (!singleplayerWorkerReturnHooked) {
            throw new IllegalStateException(
                    "Minecraft singleplayer worker return-screen hook point was not found");
        }
        if (!browserCrashHooked) {
            throw new IllegalStateException("Minecraft browser crash hook point was not found");
        }
        if (!lazyDataFixerHooked) {
            throw new IllegalStateException("Minecraft lazy data fixer hook point was not found");
        }
        if (!foregroundResourceReloadHooked) {
            throw new IllegalStateException(
                    "Minecraft foreground resource reload overlay hook point was not found");
        }
        addMinecraftUiBridges(node);
        write(node, outputRoot.resolve("net/minecraft/client/Minecraft.class"));
    }

    /**
     * Lets the browser profile editor swap the offline identity without a page reload: the
     * user/profileFuture fields become non-final and gaius$replaceIdentity replaces both. Every
     * later getUser()/getGameProfile()/getProfileResult() read (login hello, singleplayer owner)
     * sees the new name and UUID.
     */
    private static void addMinecraftIdentityBridge(ClassNode node) {
        String owner = "net/minecraft/client/Minecraft";
        FieldNode userField = null;
        FieldNode profileField = null;
        for (FieldNode field : node.fields) {
            if (field.name.equals("user") && field.desc.equals("Lnet/minecraft/client/User;")) {
                userField = field;
            } else if (field.name.equals("profileFuture")
                    && field.desc.equals("Ljava/util/concurrent/CompletableFuture;")) {
                profileField = field;
            }
        }
        if (userField == null || profileField == null) {
            // Every supported profile (1.21.11, 26.2, 26.3) declares both fields; BrowserProfile
            // links against the bridge, so a missing field must fail the build, not skip it.
            throw new IllegalStateException(
                    "Minecraft identity bridge fields user/profileFuture were not found");
        }
        userField.access &= ~Opcodes.ACC_FINAL;
        profileField.access &= ~Opcodes.ACC_FINAL;
        String descriptor = "(Lnet/minecraft/client/User;L"
                + identityProfileResultType(profileField) + ";)V";
        node.methods.removeIf(method -> method.name.equals("gaius$replaceIdentity")
                && method.desc.equals(descriptor));
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC, "gaius$replaceIdentity", descriptor, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "user", "Lnet/minecraft/client/User;"));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        bridge.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/concurrent/CompletableFuture",
                "completedFuture",
                "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;",
                false));
        bridge.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "profileFuture",
                "Ljava/util/concurrent/CompletableFuture;"));
        bridge.instructions.add(new InsnNode(Opcodes.RETURN));
        bridge.maxStack = 2;
        bridge.maxLocals = 3;
        node.methods.add(bridge);
    }

    /**
     * The authlib ProfileResult that {@code Minecraft.profileFuture} completes with: authlib 9
     * {@code com/mojang/authlib/yggdrasil/ProfileResult} (1.21.11, 26.2) or authlib 10
     * {@code com/mojang/authlib/services/ProfileResult} (26.3). It is read from the field's own
     * generic signature (this method only sees the ClassNode, not the jar a ModernSymbols
     * probe needs). It is the same yggdrasil &rarr; services mapping as
     * {@code ModernSymbols.authlibDesc}: modern-symbols-smoke asserts that mapping for this
     * descriptor, and legacy/quickcheck/profile_263_server.py asserts the patched 26.3 bridge.
     * BrowserProfile of each profile's source set builds the matching record.
     */
    private static String identityProfileResultType(FieldNode profileField) {
        String yggdrasil = "com/mojang/authlib/yggdrasil/ProfileResult";
        String services = "com/mojang/authlib/services/ProfileResult";
        String signature = profileField.signature;
        String type = null;
        if (signature != null) {
            if (signature.equals("Ljava/util/concurrent/CompletableFuture<L" + yggdrasil + ";>;")) {
                type = yggdrasil;
            } else if (signature.equals(
                    "Ljava/util/concurrent/CompletableFuture<L" + services + ";>;")) {
                type = services;
            }
        }
        if (type == null) {
            throw new IllegalStateException("Minecraft.profileFuture has an unknown ProfileResult"
                    + " signature: " + signature);
        }
        return type;
    }

    private static void addMinecraftUiBridges(ClassNode node) {
        addMinecraftIdentityBridge(node);
        String owner = "net/minecraft/client/Minecraft";
        String screenDescriptor = "()Lnet/minecraft/client/gui/screens/Screen;";
        node.methods.removeIf(method -> method.name.equals("gaius$getScreen")
                && method.desc.equals(screenDescriptor));
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$getScreen",
                screenDescriptor,
                null,
                null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        boolean directScreen = node.fields.stream().anyMatch(field -> field.name.equals("screen")
                && field.desc.equals("Lnet/minecraft/client/gui/screens/Screen;"));
        if (directScreen) {
            bridge.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "screen",
                    "Lnet/minecraft/client/gui/screens/Screen;"));
        } else {
            bridge.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "gui",
                    "Lnet/minecraft/client/gui/Gui;"));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/gui/Gui",
                    "screen",
                    screenDescriptor,
                    false));
        }
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
        bridge.maxStack = 1;
        bridge.maxLocals = 1;
        node.methods.add(bridge);

        String setScreenDescriptor = "(Lnet/minecraft/client/gui/screens/Screen;)V";
        node.methods.removeIf(method -> method.name.equals("gaius$setScreen")
                && method.desc.equals(setScreenDescriptor));
        MethodNode setScreen = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$setScreen",
                setScreenDescriptor,
                null,
                null);
        setScreen.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (directScreen) {
            setScreen.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            setScreen.instructions.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "screen",
                    "Lnet/minecraft/client/gui/screens/Screen;"));
        } else {
            setScreen.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "gui",
                    "Lnet/minecraft/client/gui/Gui;"));
            setScreen.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            setScreen.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/gui/Gui",
                    "setScreen",
                    setScreenDescriptor,
                    false));
        }
        setScreen.instructions.add(new InsnNode(Opcodes.RETURN));
        setScreen.maxStack = 2;
        setScreen.maxLocals = 2;
        node.methods.add(setScreen);

        boolean directOverlay = node.fields.stream().anyMatch(field -> field.name.equals("overlay")
                && field.desc.equals("Lnet/minecraft/client/gui/screens/Overlay;"));
        String overlayDescriptor = "()Lnet/minecraft/client/gui/screens/Overlay;";
        node.methods.removeIf(method -> method.name.equals("gaius$getOverlay")
                && method.desc.equals(overlayDescriptor));
        MethodNode getOverlay = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$getOverlay",
                overlayDescriptor,
                null,
                null);
        getOverlay.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (directOverlay) {
            getOverlay.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "overlay",
                    "Lnet/minecraft/client/gui/screens/Overlay;"));
        } else {
            getOverlay.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "gui",
                    "Lnet/minecraft/client/gui/Gui;"));
            getOverlay.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/gui/Gui",
                    "overlay",
                    overlayDescriptor,
                    false));
        }
        getOverlay.instructions.add(new InsnNode(Opcodes.ARETURN));
        getOverlay.maxStack = 1;
        getOverlay.maxLocals = 1;
        node.methods.add(getOverlay);

        String setOverlayDescriptor = "(Lnet/minecraft/client/gui/screens/Overlay;)V";
        node.methods.removeIf(method -> method.name.equals("gaius$setOverlay")
                && method.desc.equals(setOverlayDescriptor));
        MethodNode setOverlay = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$setOverlay",
                setOverlayDescriptor,
                null,
                null);
        setOverlay.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (directOverlay) {
            setOverlay.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            setOverlay.instructions.add(new FieldInsnNode(
                    Opcodes.PUTFIELD,
                    owner,
                    "overlay",
                    "Lnet/minecraft/client/gui/screens/Overlay;"));
        } else {
            setOverlay.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "gui",
                    "Lnet/minecraft/client/gui/Gui;"));
            setOverlay.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            setOverlay.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/gui/Gui",
                    "setOverlay",
                    setOverlayDescriptor,
                    false));
        }
        setOverlay.instructions.add(new InsnNode(Opcodes.RETURN));
        setOverlay.maxStack = 2;
        setOverlay.maxLocals = 2;
        node.methods.add(setOverlay);
    }

    private static boolean isJvmUptimeLambda(MethodNode method) {
        if (!method.name.startsWith("lambda$fillUptime$")
                || !method.desc.equals("()Ljava/lang/String;")) {
            return false;
        }
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/management/ManagementFactory")
                    && call.name.equals("getRuntimeMXBean")) {
                return true;
            }
        }
        return false;
    }

    private static void patchGuiForegroundResourceReload(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/gui/Gui";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node, "overlay", "()Lnet/minecraft/client/gui/screens/Overlay;");
        LabelNode returnOverlay = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "overlay",
                "Lnet/minecraft/client/gui/screens/Overlay;"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new TypeInsnNode(
                Opcodes.INSTANCEOF,
                "net/minecraft/client/gui/screens/LoadingOverlay"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, returnOverlay));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "minecraft", "Lnet/minecraft/client/Minecraft;"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "level",
                "Lnet/minecraft/client/multiplayer/ClientLevel;"));
        code.add(new JumpInsnNode(Opcodes.IFNULL, returnOverlay));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(returnOverlay);
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2, 1);
        write(node, output);
    }

    private static InsnList minecraftStateReport(boolean hasNoRender) {
        InsnList code = new InsnList();
        LabelNode skipped = new LabelNode();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "shouldReportMinecraftState",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, skipped));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$getScreen",
                "()Lnet/minecraft/client/gui/screens/Screen;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/Minecraft",
                "gaius$getOverlay",
                "()Lnet/minecraft/client/gui/screens/Overlay;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "level",
                "Lnet/minecraft/client/multiplayer/ClientLevel;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "fallbackClientLevel",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "player",
                "Lnet/minecraft/client/player/LocalPlayer;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "gameMode",
                "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "hitResult",
                "Lnet/minecraft/world/phys/HitResult;"));
        if (hasNoRender) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/Minecraft",
                    "noRender",
                    "Z"));
        } else {
            code.add(new InsnNode(Opcodes.ICONST_0));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "running",
                "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "pause",
                "Z"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/lwjgl/opengl/BrowserOpenGL",
                "reportMinecraftState",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/Object;Ljava/lang/Object;ZZZ)V",
                false));
        code.add(skipped);
        return code;
    }

    private static boolean hookMinecraftRunCatchDiagnostics(MethodNode method) {
        boolean reportedHooked = false;
        boolean unreportedHooked = false;
        for (var block : method.tryCatchBlocks) {
            String phase;
            if ("net/minecraft/ReportedException".equals(block.type)) {
                phase = "run.reported";
            } else if ("java/lang/Throwable".equals(block.type)) {
                phase = "run.unreported";
            } else {
                continue;
            }
            var instruction = block.handler.getNext();
            while (instruction != null && instruction.getOpcode() < 0) {
                instruction = instruction.getNext();
            }
            if (!(instruction instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ASTORE) {
                continue;
            }
            InsnList code = new InsnList();
            code.add(new LdcInsnNode(phase));
            code.add(new VarInsnNode(Opcodes.ALOAD, store.var));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "org/lwjgl/opengl/BrowserOpenGL",
                    "reportMinecraftThrowable",
                    "(Ljava/lang/String;Ljava/lang/Throwable;)V",
                    false));
            method.instructions.insert(instruction, code);
            method.maxStack = Math.max(method.maxStack, 2);
            if ("run.reported".equals(phase)) {
                reportedHooked = true;
            } else {
                unreportedHooked = true;
            }
        }
        return reportedHooked && unreportedHooked;
    }

    public static void patchBlockPosBrowserPackedCoordinates(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/core/BlockPos.class");
        for (String name : new String[] {"getX", "getY", "getZ"}) {
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.LLOAD, 0));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserBlockPos",
                    name,
                    "(J)I",
                    false));
            code.add(new InsnNode(Opcodes.IRETURN));
            replace(find(node, name, "(J)I"), code, 2, 2);
        }

        InsnList asLong = new InsnList();
        asLong.add(new VarInsnNode(Opcodes.ILOAD, 0));
        asLong.add(new VarInsnNode(Opcodes.ILOAD, 1));
        asLong.add(new VarInsnNode(Opcodes.ILOAD, 2));
        asLong.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBlockPos",
                "asLong",
                "(III)J",
                false));
        asLong.add(new InsnNode(Opcodes.LRETURN));
        replace(find(node, "asLong", "(III)J"), asLong, 3, 3);
        write(node, output);
    }

    public static void patchSimpleBitStorageBrowserUnpack(String jar, Path output) throws IOException {
        String owner = "net/minecraft/util/SimpleBitStorage";
        ClassNode node = read(jar, owner + ".class");
        MethodNode get = find(node, "get", "(I)I");
        InsnList getCode = new InsnList();
        getCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "data", "[J"));
        getCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        getCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "valuesPerLong", "I"));
        getCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "bits", "I"));
        getCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "get",
                "([JIII)I",
                false));
        getCode.add(new InsnNode(Opcodes.IRETURN));
        replace(get, getCode, 4, 2);

        MethodNode getAndSet = find(node, "getAndSet", "(II)I");
        InsnList getAndSetCode = new InsnList();
        getAndSetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getAndSetCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "data", "[J"));
        getAndSetCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        getAndSetCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        getAndSetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getAndSetCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "valuesPerLong", "I"));
        getAndSetCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getAndSetCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "bits", "I"));
        getAndSetCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "getAndSet",
                "([JIIII)I",
                false));
        getAndSetCode.add(new InsnNode(Opcodes.IRETURN));
        replace(getAndSet, getAndSetCode, 5, 3);

        MethodNode set = find(node, "set", "(II)V");
        InsnList setCode = new InsnList();
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "data", "[J"));
        setCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        setCode.add(new VarInsnNode(Opcodes.ILOAD, 2));
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "valuesPerLong", "I"));
        setCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "bits", "I"));
        setCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "getAndSet",
                "([JIIII)I",
                false));
        setCode.add(new InsnNode(Opcodes.POP));
        setCode.add(new InsnNode(Opcodes.RETURN));
        replace(set, setCode, 5, 3);

        MethodNode method = find(node, "unpack", "([I)V");
        LabelNode fallback = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "data", "[J"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "size", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "bits", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "valuesPerLong", "I"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBitStorage",
                "unpack",
                "([J[IIII)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(fallback);
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 5);
        writeComputeFrames(node, output);
    }

    public static void patchLocalTimeItemModelProperty(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/renderer/item/properties/select/LocalTime";
        String data = "net/minecraft/client/renderer/item/properties/select/LocalTime$Data";
        ClassNode node = read(jar, owner + ".class");

        MethodNode create = find(node, "create",
                "(L" + data + ";)Lcom/mojang/serialization/DataResult;");
        InsnList createCode = new InsnList();
        createCode.add(new TypeInsnNode(Opcodes.NEW, owner));
        createCode.add(new InsnNode(Opcodes.DUP));
        createCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        createCode.add(new InsnNode(Opcodes.ACONST_NULL));
        createCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "<init>",
                "(L" + data + ";Lcom/ibm/icu/text/DateFormat;)V",
                false));
        createCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/mojang/serialization/DataResult",
                "success",
                "(Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;",
                true));
        createCode.add(new InsnNode(Opcodes.ARETURN));
        replace(create, createCode, 4, 1);

        MethodNode update = find(node, "update", "()Ljava/lang/String;");
        InsnList updateCode = new InsnList();
        LabelNode monthDay = new LabelNode();

        updateCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        updateCode.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "data", "L" + data + ";"));
        updateCode.add(new FieldInsnNode(Opcodes.GETFIELD, data, "format", "Ljava/lang/String;"));
        updateCode.add(new LdcInsnNode("MM-dd"));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "equals",
                "(Ljava/lang/Object;)Z",
                false));
        updateCode.add(new JumpInsnNode(Opcodes.IFNE, monthDay));
        updateCode.add(new LdcInsnNode(""));
        updateCode.add(new InsnNode(Opcodes.ARETURN));

        updateCode.add(monthDay);
        updateCode.add(new TypeInsnNode(Opcodes.NEW, "java/util/Date"));
        updateCode.add(new InsnNode(Opcodes.DUP));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/Date",
                "<init>",
                "()V",
                false));
        updateCode.add(new VarInsnNode(Opcodes.ASTORE, 1));
        updateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/Date",
                "getMonth",
                "()I",
                false));
        updateCode.add(new InsnNode(Opcodes.ICONST_1));
        updateCode.add(new InsnNode(Opcodes.IADD));
        updateCode.add(new VarInsnNode(Opcodes.ISTORE, 2));
        updateCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/Date",
                "getDate",
                "()I",
                false));
        updateCode.add(new VarInsnNode(Opcodes.ISTORE, 3));
        updateCode.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        updateCode.add(new InsnNode(Opcodes.DUP));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder",
                "<init>",
                "()V",
                false));
        addAppendTwoDigit(updateCode, 2);
        updateCode.add(new LdcInsnNode("-"));
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false));
        addAppendTwoDigit(updateCode, 3);
        updateCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()Ljava/lang/String;",
                false));
        updateCode.add(new InsnNode(Opcodes.ARETURN));
        replace(update, updateCode, 4, 4);

        write(node, output);
    }

    private static void addAppendTwoDigit(InsnList code, int local) {
        LabelNode noZero = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ILOAD, local));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 10));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, noZero));
        code.add(new LdcInsnNode("0"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false));
        code.add(noZero);
        code.add(new VarInsnNode(Opcodes.ILOAD, local));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(I)Ljava/lang/StringBuilder;",
                false));
    }

    public static void patchDebugMemoryUntracker(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/DebugMemoryUntracker.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/DebugMemoryUntracker",
                        "UNTRACK", "Ljava/lang/invoke/MethodHandle;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            } else if (method.name.equals("untrack")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    public static void patchOpenUri(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/Util$OS.class");
        if (node.methods.stream().noneMatch(method ->
                method.name.equals("openUri") && method.desc.equals("(Ljava/net/URI;)V"))) {
            // Minecraft 26.3 opens links through Blaze3D.openUri -> SDL_OpenURL, which
            // InputPatches263 redirects; Util$OS no longer has openUri.
            PatchRegistry.dropped("MinecraftClientPatcher.patchOpenUri.utilOs", jar,
                    "net/minecraft/util/Util$OS#openUri");
            return;
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals("openUri") && method.desc.equals("(Ljava/net/URI;)V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    public static void generateUnsafeStub(Path root) throws IOException {
        ClassWriter unsafe = new ClassWriter(0);
        unsafe.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                "sun/misc/Unsafe", null, "java/lang/Object", null);
        var constructor = unsafe.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        emptyMethod(unsafe, "putByte", "(Ljava/lang/Object;JB)V", 5);
        unsafe.visitEnd();
        writeBytes(root.resolve("sun/misc/Unsafe.class"), unsafe.toByteArray());
    }

    public static void generateCryptoApiStubs(Path root) throws IOException {
        ClassWriter secretKey = new ClassWriter(0);
        secretKey.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "javax/crypto/SecretKey", null, "java/lang/Object",
                new String[] {"java/security/Key"});
        secretKey.visitEnd();
        writeBytes(root.resolve("javax/crypto/SecretKey.class"), secretKey.toByteArray());

        ClassWriter cipher = new ClassWriter(0);
        cipher.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "javax/crypto/Cipher", null, "java/lang/Object", null);
        cipher.visitField(
                Opcodes.ACC_PRIVATE,
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;",
                null,
                null).visitEnd();
        cipher.visitField(
                Opcodes.ACC_PRIVATE,
                "transformation",
                "Ljava/lang/String;",
                null,
                null).visitEnd();
        for (String field : new String[] {"ENCRYPT_MODE", "DECRYPT_MODE", "WRAP_MODE", "UNWRAP_MODE"}) {
            cipher.visitField(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    field, "I", null, switch (field) {
                        case "ENCRYPT_MODE" -> 1;
                        case "DECRYPT_MODE" -> 2;
                        case "WRAP_MODE" -> 3;
                        default -> 4;
                    }).visitEnd();
        }
        simpleConstructor(cipher, "javax/crypto/Cipher", Opcodes.ACC_PUBLIC, "()V");
        var getInstance = cipher.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getInstance", "(Ljava/lang/String;)Ljavax/crypto/Cipher;", null, null);
        getInstance.visitCode();
        getInstance.visitTypeInsn(Opcodes.NEW, "javax/crypto/Cipher");
        getInstance.visitInsn(Opcodes.DUP);
        getInstance.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "javax/crypto/Cipher", "<init>", "()V", false);
        getInstance.visitInsn(Opcodes.DUP);
        getInstance.visitVarInsn(Opcodes.ALOAD, 0);
        getInstance.visitFieldInsn(
                Opcodes.PUTFIELD,
                "javax/crypto/Cipher",
                "transformation",
                "Ljava/lang/String;");
        getInstance.visitInsn(Opcodes.ARETURN);
        getInstance.visitMaxs(3, 1);
        getInstance.visitEnd();
        getter(
                cipher,
                "javax/crypto/Cipher",
                "getAlgorithm",
                "()Ljava/lang/String;",
                "transformation",
                "Ljava/lang/String;",
                Opcodes.ARETURN);

        var init = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "init", "(ILjava/security/Key;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "createAesCfb8",
                "(ILjava/security/Key;)Ldev/gaius/browser/BrowserAesCfb8;",
                false);
        init.visitFieldInsn(
                Opcodes.PUTFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(3, 3);
        init.visitEnd();

        var initWithParameters = cipher.visitMethod(
                Opcodes.ACC_PUBLIC,
                "init",
                "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V",
                null,
                null);
        initWithParameters.visitCode();
        initWithParameters.visitVarInsn(Opcodes.ALOAD, 0);
        initWithParameters.visitVarInsn(Opcodes.ILOAD, 1);
        initWithParameters.visitVarInsn(Opcodes.ALOAD, 2);
        initWithParameters.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "createAesCfb8",
                "(ILjava/security/Key;)Ldev/gaius/browser/BrowserAesCfb8;",
                false);
        initWithParameters.visitFieldInsn(
                Opcodes.PUTFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        initWithParameters.visitInsn(Opcodes.RETURN);
        initWithParameters.visitMaxs(3, 4);
        initWithParameters.visitEnd();

        var doFinal = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "doFinal", "([B)[B", null, null);
        doFinal.visitCode();
        doFinal.visitVarInsn(Opcodes.ALOAD, 0);
        doFinal.visitFieldInsn(
                Opcodes.GETFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        doFinal.visitVarInsn(Opcodes.ALOAD, 1);
        doFinal.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "dev/gaius/browser/BrowserAesCfb8",
                "update",
                "([B)[B",
                false);
        doFinal.visitInsn(Opcodes.ARETURN);
        doFinal.visitMaxs(2, 2);
        doFinal.visitEnd();
        var getOutputSize = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "getOutputSize", "(I)I", null, null);
        getOutputSize.visitCode();
        getOutputSize.visitVarInsn(Opcodes.ALOAD, 0);
        getOutputSize.visitFieldInsn(
                Opcodes.GETFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        getOutputSize.visitVarInsn(Opcodes.ILOAD, 1);
        getOutputSize.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "dev/gaius/browser/BrowserAesCfb8",
                "getOutputSize",
                "(I)I",
                false);
        getOutputSize.visitInsn(Opcodes.IRETURN);
        getOutputSize.visitMaxs(2, 2);
        getOutputSize.visitEnd();
        var update = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "update", "([BII[B)I", null, null);
        update.visitCode();
        update.visitVarInsn(Opcodes.ALOAD, 0);
        update.visitFieldInsn(
                Opcodes.GETFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        update.visitVarInsn(Opcodes.ALOAD, 1);
        update.visitVarInsn(Opcodes.ILOAD, 2);
        update.visitVarInsn(Opcodes.ILOAD, 3);
        update.visitVarInsn(Opcodes.ALOAD, 4);
        update.visitInsn(Opcodes.ICONST_0);
        update.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "dev/gaius/browser/BrowserAesCfb8",
                "update",
                "([BII[BI)I",
                false);
        update.visitInsn(Opcodes.IRETURN);
        update.visitMaxs(6, 5);
        update.visitEnd();
        var updateWithOffset = cipher.visitMethod(
                Opcodes.ACC_PUBLIC, "update", "([BII[BI)I", null, null);
        updateWithOffset.visitCode();
        updateWithOffset.visitVarInsn(Opcodes.ALOAD, 0);
        updateWithOffset.visitFieldInsn(
                Opcodes.GETFIELD,
                "javax/crypto/Cipher",
                "state",
                "Ldev/gaius/browser/BrowserAesCfb8;");
        updateWithOffset.visitVarInsn(Opcodes.ALOAD, 1);
        updateWithOffset.visitVarInsn(Opcodes.ILOAD, 2);
        updateWithOffset.visitVarInsn(Opcodes.ILOAD, 3);
        updateWithOffset.visitVarInsn(Opcodes.ALOAD, 4);
        updateWithOffset.visitVarInsn(Opcodes.ILOAD, 5);
        updateWithOffset.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "dev/gaius/browser/BrowserAesCfb8",
                "update",
                "([BII[BI)I",
                false);
        updateWithOffset.visitInsn(Opcodes.IRETURN);
        updateWithOffset.visitMaxs(6, 6);
        updateWithOffset.visitEnd();
        cipher.visitEnd();
        writeBytes(root.resolve("javax/crypto/Cipher.class"), cipher.toByteArray());

        ClassWriter keyGenerator = new ClassWriter(0);
        keyGenerator.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "javax/crypto/KeyGenerator", null, "java/lang/Object", null);
        keyGenerator.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "algorithm", "Ljava/lang/String;", null, null).visitEnd();
        var kgConstructor = keyGenerator.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        kgConstructor.visitCode();
        kgConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        kgConstructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        kgConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        kgConstructor.visitVarInsn(Opcodes.ALOAD, 1);
        kgConstructor.visitFieldInsn(
                Opcodes.PUTFIELD, "javax/crypto/KeyGenerator",
                "algorithm", "Ljava/lang/String;");
        kgConstructor.visitInsn(Opcodes.RETURN);
        kgConstructor.visitMaxs(2, 2);
        kgConstructor.visitEnd();
        var kgGetInstance = keyGenerator.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getInstance", "(Ljava/lang/String;)Ljavax/crypto/KeyGenerator;", null, null);
        kgGetInstance.visitCode();
        kgGetInstance.visitTypeInsn(Opcodes.NEW, "javax/crypto/KeyGenerator");
        kgGetInstance.visitInsn(Opcodes.DUP);
        kgGetInstance.visitVarInsn(Opcodes.ALOAD, 0);
        kgGetInstance.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "javax/crypto/KeyGenerator",
                "<init>",
                "(Ljava/lang/String;)V",
                false);
        kgGetInstance.visitInsn(Opcodes.ARETURN);
        kgGetInstance.visitMaxs(3, 1);
        kgGetInstance.visitEnd();
        emptyMethod(keyGenerator, "init", "(I)V", 2);
        var generateKey = keyGenerator.visitMethod(
                Opcodes.ACC_PUBLIC, "generateKey", "()Ljavax/crypto/SecretKey;", null, null);
        generateKey.visitCode();
        generateKey.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "generateSecretKey",
                "()Ljavax/crypto/SecretKey;",
                false);
        generateKey.visitInsn(Opcodes.ARETURN);
        generateKey.visitMaxs(1, 1);
        generateKey.visitEnd();
        keyGenerator.visitEnd();
        writeBytes(root.resolve("javax/crypto/KeyGenerator.class"), keyGenerator.toByteArray());

        generateSecretKeySpecStub(root);
        generateIvParameterSpecStub(root);
    }

    private static void generateSecretKeySpecStub(Path root) throws IOException {
        ClassWriter spec = new ClassWriter(0);
        String owner = "javax/crypto/spec/SecretKeySpec";
        spec.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                owner, null, "java/lang/Object", new String[] {"javax/crypto/SecretKey"});
        spec.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "key", "[B", null, null).visitEnd();
        spec.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "algorithm", "Ljava/lang/String;", null, null).visitEnd();
        var constructor = spec.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "([BLjava/lang/String;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        putConstructorField(constructor, owner, "key", "[B", Opcodes.ALOAD, 1);
        putConstructorField(constructor, owner, "algorithm", "Ljava/lang/String;", Opcodes.ALOAD, 2);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 3);
        constructor.visitEnd();
        var rangedConstructor = spec.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "([BIILjava/lang/String;)V", null, null);
        rangedConstructor.visitCode();
        rangedConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        rangedConstructor.visitVarInsn(Opcodes.ALOAD, 1);
        rangedConstructor.visitVarInsn(Opcodes.ALOAD, 4);
        rangedConstructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, owner, "<init>", "([BLjava/lang/String;)V", false);
        rangedConstructor.visitInsn(Opcodes.RETURN);
        rangedConstructor.visitMaxs(3, 5);
        rangedConstructor.visitEnd();
        getter(spec, owner, "getAlgorithm", "()Ljava/lang/String;",
                "algorithm", "Ljava/lang/String;", Opcodes.ARETURN);
        constantStringMethod(spec, "getFormat", "RAW");
        getter(spec, owner, "getEncoded", "()[B", "key", "[B", Opcodes.ARETURN);
        spec.visitEnd();
        writeBytes(root.resolve("javax/crypto/spec/SecretKeySpec.class"), spec.toByteArray());
    }

    private static void generateIvParameterSpecStub(Path root) throws IOException {
        ClassWriter spec = new ClassWriter(0);
        String owner = "javax/crypto/spec/IvParameterSpec";
        spec.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                owner, null, "java/lang/Object",
                new String[] {"java/security/spec/AlgorithmParameterSpec"});
        spec.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "iv", "[B", null, null).visitEnd();
        var constructor = spec.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([B)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        putConstructorField(constructor, owner, "iv", "[B", Opcodes.ALOAD, 1);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 2);
        constructor.visitEnd();
        var rangedConstructor = spec.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "([BII)V", null, null);
        rangedConstructor.visitCode();
        rangedConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        rangedConstructor.visitVarInsn(Opcodes.ALOAD, 1);
        rangedConstructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, owner, "<init>", "([B)V", false);
        rangedConstructor.visitInsn(Opcodes.RETURN);
        rangedConstructor.visitMaxs(2, 4);
        rangedConstructor.visitEnd();
        getter(spec, owner, "getIV", "()[B", "iv", "[B", Opcodes.ARETURN);
        spec.visitEnd();
        writeBytes(root.resolve("javax/crypto/spec/IvParameterSpec.class"), spec.toByteArray());
    }

    private static void simpleConstructor(
            ClassWriter writer, String owner, int access, String descriptor) {
        var method = writer.visitMethod(access, "<init>", descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    private static void emptyMethod(
            ClassWriter writer, String name, String descriptor, int maxLocals) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, maxLocals);
        method.visitEnd();
    }

    private static void constantStringMethod(ClassWriter writer, String name, String value) {
        var method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, name, "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    /**
     * WebAssembly GC only (design D4, spec {@code "REQUIRED wasmgc"}): {@code Minecraft.run}
     * calls {@code HostLoop.awaitAnimationFrame()} at the head of its {@code while (running)}
     * loop. The JSPI main thread then suspends once per frame, between two frames, until the
     * browser is ready for the next one (gaius-jspi.js awaitFrame); presents no longer yield
     * (BrowserGlfw/BrowserSdlDom presentYield only record the swap interval) and the loop keeps
     * vanilla's shape. The JavaScript build keeps its per-present yield and this class as it is.
     *
     * <p>The loop head is the {@code aload 0} before the only {@code getfield running} of
     * {@code run()}, the target of the loop's back edges (the normal one and the out-of-memory
     * recovery); the call goes right after the label and its frame, so it runs before the first
     * iteration and on every later one, and the frames stay valid (the call takes and leaves
     * nothing on the stack). It patches the class that patchMinecraft wrote.
     */
    public static void patchMinecraftRunFrameBoundary(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/Minecraft";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        MethodNode run = find(node, "run", "()V");
        FieldInsnNode running = null;
        for (AbstractInsnNode instruction : run.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("dev/gaius/browser/HostLoop")
                    && call.name.equals("awaitAnimationFrame")) {
                throw new IllegalStateException("Minecraft.run already has the frame boundary");
            }
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(owner) && field.name.equals("running") && field.desc.equals("Z")) {
                if (running != null) {
                    throw new IllegalStateException("Minecraft.run reads running more than once");
                }
                running = field;
            }
        }
        if (running == null) {
            throw new IllegalStateException("Minecraft.run loop condition (getfield running) not found");
        }
        AbstractInsnNode self = previousOpcode(running);
        AbstractInsnNode exit = nextOpcode(running);
        if (!(self instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0
                || exit == null || exit.getOpcode() != Opcodes.IFEQ) {
            throw new IllegalStateException("Minecraft.run loop head is not aload 0; getfield running; ifeq");
        }
        LabelNode head = null;
        for (AbstractInsnNode cursor = self.getPrevious(); cursor != null && cursor.getOpcode() < 0;
                cursor = cursor.getPrevious()) {
            if (cursor instanceof LabelNode label) {
                head = label;
                break;
            }
        }
        int backEdges = 0;
        for (AbstractInsnNode instruction : run.instructions.toArray()) {
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO
                    && jump.label == head) {
                backEdges++;
            }
        }
        if (head == null || backEdges == 0) {
            throw new IllegalStateException("Minecraft.run loop head is not the target of a back edge");
        }
        run.instructions.insertBefore(self, new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/HostLoop", "awaitAnimationFrame", "()V", false));
        write(node, output);
    }
}
