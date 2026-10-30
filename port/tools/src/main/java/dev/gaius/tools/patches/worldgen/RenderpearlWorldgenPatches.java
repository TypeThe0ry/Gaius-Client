package dev.gaius.tools.patches.worldgen;

import dev.gaius.tools.ArchivePaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The worldgen domain of the Minecraft263BrowserPatcher step, the tail of the modern chain for the
 * renderpearl render seam ({@code patches/worldgen}), work package P6 (migration plan
 * D6).
 *
 * <p>26.3 merged NOISE, SURFACE and CARVERS into one TERRAIN step and compiles each density
 * graph once per RandomState into float samplers that evaluate whole volumes. The 26.2
 * performance patches for the old density runtime are registered as dropped by
 * MinecraftClientPatcher/Minecraft262BrowserPatcher; the cooperation and correctness patches
 * that have a 1:1 target were ported in place there. This class holds the parts only the
 * compiled-sampler generation needs (the Java float semantics of its worldgen classes are
 * {@link WorldgenStrictMath}):
 * <ul>
 *   <li>{@link #patchCarvingMaskDeepPulses}: carved blocks are now applied by walking a
 *       CarvingMask ({@code visit}/{@code visitSegment}) after all carvers ran, so the deep
 *       pulses of the 26.2 carver loop move there;</li>
 *   <li>{@link #patchMaterialRuleContextIntCounters}: int shadows of MaterialRuleContext's long
 *       update counters, read by the two lazy condition classes (TeaVM emulates long);</li>
 *   <li>{@link #verifyNonSuspendingWorldgenKernels}: the compiled density kernels, the noise
 *       synthesizers and the RandomState/DensityFunctionCompiler lock regions must never reach a
 *       cooperative pulse or scheduler call. Gaius' TReentrantLock only counts holds and does
 *       not exclude, so a task suspended inside compileLock or the buffer-pool lock would let
 *       another task corrupt the shared state; a pulse in a kernel would also turn every
 *       sampler into a TeaVM async method.</li>
 * </ul>
 *
 * <p>{@code Minecraft263BrowserPatcher} registers these patches under their historical ids
 * ({@code WorldgenPatches263.<method>}): the two patches after the render, input and terrain
 * groups of the step, the guard after the last domain of the step, so pulses that later domains
 * insert into guarded code are caught too; their profiles come from the specs. {@code jar} is the
 * chain's current client jar, {@code root} the directory whose classes are merged back into it.
 *
 * <p>The guard also runs standalone on a finished client jar:
 * {@code java dev.gaius.tools.patches.worldgen.RenderpearlWorldgenPatches --verify-non-suspending <jar>}.
 */
public final class RenderpearlWorldgenPatches {
    public static final String CARVING_MASK = "net/minecraft/world/level/chunk/CarvingMask";
    public static final String MATERIAL_RULE_CONTEXT =
            "net/minecraft/world/level/levelgen/material/MaterialRuleContext";
    public static final String MATERIAL_SYSTEM =
            "net/minecraft/world/level/levelgen/material/MaterialSystem";
    public static final String CHUNK_GENERATOR = "net/minecraft/world/level/chunk/ChunkGenerator";
    static final String DEEP_CHECKPOINT = "dev/gaius/browser/BrowserWorldgenDeepCheckpoint";

    /** Counter fields added to MaterialRuleContext (incremented with lastUpdateXZ/lastUpdateY). */
    static final String BROWSER_UPDATE_XZ = "browserUpdateXZ";
    static final String BROWSER_UPDATE_Y = "browserUpdateY";
    static final String BROWSER_BIOME_MANAGER = "browserBiomeManager";
    static final String BIOME_MANAGER = "net/minecraft/world/level/biome/BiomeManager";
    static final String BIOME_MANAGER_DESC = "L" + BIOME_MANAGER + ";";
    static final String BIOME_POS_DESC = "Lnet/minecraft/core/BlockPos;";
    static final String BIOME_HOLDER_DESC = "Lnet/minecraft/core/Holder;";
    static final String FUNCTION_BIOME_DESC = "Ljava/util/function/Function;";
    static final String STRUCTURE_REGISTRY_DESC = "Lnet/minecraft/core/Registry;";
    static final String STRUCTURE_CACHE = "browserStructureFeatureCache";
    static final String STRUCTURE_CACHE_DESC = "Ljava/util/concurrent/ConcurrentMap;";
    static final String STRUCTURE_CACHE_METHOD = "gaius$structureFeatures";
    static final String MATERIAL_CONTEXT_CONSTRUCTOR_FUNCTION_DESC =
            "(Lnet/minecraft/world/level/levelgen/material/MaterialSystem;"
                    + "Lnet/minecraft/world/level/levelgen/RandomState;"
                    + "Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;"
                    + "Lnet/minecraft/world/level/levelgen/densityfunction/DensitySamplerSet;"
                    + FUNCTION_BIOME_DESC
                    + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                    + "Ljava/util/Set;)V";
    static final String MATERIAL_CONTEXT_CONSTRUCTOR_DIRECT_DESC =
            MATERIAL_CONTEXT_CONSTRUCTOR_FUNCTION_DESC.replace(FUNCTION_BIOME_DESC, BIOME_MANAGER_DESC);

    /**
     * Owners whose methods suspend the calling task (TeaVM async) or hand control to the
     * cooperative worldgen scheduler. None of them may be called from a guarded class.
     */
    static final List<String> SUSPENDING_OWNERS = List.of(
            "dev/gaius/browser/BrowserWorldgenScheduler",
            DEEP_CHECKPOINT,
            "dev/gaius/browser/BrowserWorldgenDispatcherScheduler",
            "dev/gaius/browser/BrowserChunkGenerationYield",
            "dev/gaius/browser/BrowserFuturePump",
            "dev/gaius/browser/BrowserStartupScheduler",
            "dev/gaius/browser/BrowserRenderScheduler",
            "dev/gaius/browser/BrowserIntegratedServerMain",
            "dev/gaius/browser/BrowserCooperativeExecutor",
            "org/teavm/platform/Platform",
            "dev/gaius/browser/HostLoop",
            "java/util/concurrent/locks/LockSupport");

    /** Package prefixes that are guarded as a whole. */
    static final List<String> GUARDED_PACKAGES = List.of(
            "net/minecraft/world/level/levelgen/densityfunction/",
            "net/minecraft/world/level/levelgen/synth/");

    /**
     * Single classes (with their nested classes) that are guarded as a whole: the Beardifier
     * density sampler, the NoiseChunk holder, and RandomState, whose buffer-pool lock regions
     * (acquire/release/garbageCollect) and sampler lookups must not suspend.
     */
    static final List<String> GUARDED_CLASSES = List.of(
            "net/minecraft/world/level/levelgen/Beardifier",
            "net/minecraft/world/level/levelgen/NoiseChunk",
            "net/minecraft/world/level/levelgen/RandomState");

    /** Guarded classes that must exist in a 26.3 jar, so the guard cannot pass vacuously. */
    static final List<String> REQUIRED_GUARDED_CLASSES = List.of(
            "net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler",
            "net/minecraft/world/level/levelgen/densityfunction/op/InterpolatedFunction$Sampler",
            "net/minecraft/world/level/levelgen/synth/PerlinNoise",
            "net/minecraft/world/level/levelgen/synth/NoiseStack",
            "net/minecraft/world/level/levelgen/Beardifier",
            "net/minecraft/world/level/levelgen/NoiseChunk",
            "net/minecraft/world/level/levelgen/RandomState");

    /** How many calls deep the transitive part of the guard follows calls out of guarded code. */
    static final int TRANSITIVE_DEPTH = 6;

    private RenderpearlWorldgenPatches() {
    }

    /**
     * Adds a deep pulse to the back-edge of {@code CarvingMask.visit} (one per carved segment)
     * and of {@code visitSegment} (one per carved column). Each column runs the aquifer and the
     * material rules for every carved block through NoiseBasedChunkGenerator's
     * {@code lambda$applyCarvingMask$0}; 26.2 pulsed the equivalent loop inside the carvers.
     */
    public static void patchCarvingMaskDeepPulses(String jar, Path root) throws IOException {
        ClassNode node = readCurrent(jar, root, CARVING_MASK);
        String visitor = "L" + CARVING_MASK + "$Visitor;";
        MethodNode visit = find(node, "visit", "(" + visitor + ")V");
        MethodNode visitSegment = find(node, "visitSegment", "(" + visitor + "II)V");
        for (MethodNode method : List.of(visit, visitSegment)) {
            String label = "CarvingMask." + method.name;
            requireNoSuspendingCalls(label, method);
            int pulses = insertDeepPulsesAtBackEdges(method);
            if (pulses != 1) {
                throw new IllegalStateException(
                        label + " back-edges changed: " + pulses + " (expected 1)");
            }
        }
        // A pulse before a back-edge jump neither adds a branch target nor touches the stack, so
        // the vanilla stack map frames stay valid.
        write(node, root.resolve(CARVING_MASK + ".class"));
        System.out.println("Patched 26.3 CarvingMask.visit/visitSegment with deep worldgen pulses");
    }

    /**
     * MaterialRuleContext counts XZ and Y updates in two {@code long} fields that the lazy
     * conditions compare on every rule test; TeaVM emulates {@code long}, so this adds int
     * shadows incremented next to the vanilla counters and replaces
     * {@code LazyXZCondition.test()} and {@code LazyYCondition.test()} with int comparisons
     * over a primitive cached result. The vanilla long counters keep running unchanged for the
     * other readers (the $1/$2 noise suppliers and the surface-depth/min-surface caches).
     *
     * <p>Semantics: a condition recomputes exactly when its counter moved since its last
     * computation, as vanilla does. A failed {@code compute()} leaves the update recorded but
     * the result uninitialized, so the next test of the same update reports vanilla's
     * "Update triggered but the result is null" exception.
     */
    public static void patchMaterialRuleContextIntCounters(String jar, Path root) throws IOException {
        ClassNode context = readCurrent(jar, root, MATERIAL_RULE_CONTEXT);
        requireOnlyCounterWriters(context);
        for (String name : List.of(BROWSER_UPDATE_XZ, BROWSER_UPDATE_Y)) {
            if (context.fields.stream().anyMatch(field -> field.name.equals(name))) {
                throw new IllegalStateException(
                        "MaterialRuleContext." + name + " already exists");
            }
            context.fields.add(new FieldNode(Opcodes.ACC_SYNTHETIC, name, "I", null, null));
        }
        MethodNode updateXZ = find(context, "updateXZ", "(IIII)V");
        requireLongCounterIncrement(updateXZ, "lastUpdateXZ");
        requireLongCounterIncrement(updateXZ, "lastUpdateY");
        InsnList xzCounters = new InsnList();
        appendIntFieldIncrement(xzCounters, MATERIAL_RULE_CONTEXT, BROWSER_UPDATE_XZ);
        appendIntFieldIncrement(xzCounters, MATERIAL_RULE_CONTEXT, BROWSER_UPDATE_Y);
        updateXZ.instructions.insert(xzCounters);

        MethodNode updateY = find(context, "updateY", "(IIII)V");
        requireLongCounterIncrement(updateY, "lastUpdateY");
        InsnList yCounter = new InsnList();
        appendIntFieldIncrement(yCounter, MATERIAL_RULE_CONTEXT, BROWSER_UPDATE_Y);
        updateY.instructions.insert(yCounter);
        write(context, root.resolve(MATERIAL_RULE_CONTEXT + ".class"));

        replaceLazyConditionTest(jar, root, "$LazyXZCondition", "lastUpdateXZ", BROWSER_UPDATE_XZ);
        replaceLazyConditionTest(jar, root, "$LazyYCondition", "lastUpdateY", BROWSER_UPDATE_Y);
        System.out.println("Patched 26.3 MaterialRuleContext lazy conditions with int counters");
    }

    /**
     * Reuses the column height already read by MaterialSystem.buildSurface when the column is not
     * eroded badlands. Vanilla reads WORLD_SURFACE_WG twice per column; between the two reads only
     * erodedBadlandsExtension writes blocks (and so can raise the heightmap). The patch keeps the
     * second read on that branch and skips it on the other branch, where no block was written and
     * the heightmap cannot have changed. Loop order, arithmetic, RNG use and block writes are the
     * vanilla ones; the common column saves one ChunkAccess.getHeight dispatch.
     */
    public static void patchMaterialSystemSurfaceHeightCache(String jar, Path root) throws IOException {
        ClassNode node = readCurrent(jar, root, MATERIAL_SYSTEM);
        MethodNode method = find(
                node,
                "buildSurface",
                "(Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;"
                        + "Ljava/util/Set;)V");
        List<MethodInsnNode> heightReads = new ArrayList<>();
        List<MethodInsnNode> badlandsCalls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/world/level/chunk/ChunkAccess")
                    && call.name.equals("getHeight")
                    && call.desc.equals("(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I")) {
                heightReads.add(call);
            }
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(MATERIAL_SYSTEM)
                    && call.name.equals("erodedBadlandsExtension")) {
                badlandsCalls.add(call);
            }
        }
        if (heightReads.size() != 2 || badlandsCalls.size() != 1) {
            throw new IllegalStateException("MaterialSystem.buildSurface height-read shape changed: reads="
                    + heightReads.size() + ", badlands=" + badlandsCalls.size());
        }
        int firstLocal = storedLocalAfterHeight(heightReads.get(0));
        int secondLocal = storedLocalAfterHeight(heightReads.get(1));
        if (firstLocal < 0 || secondLocal < 0 || firstLocal == secondLocal
                || localStores(method, firstLocal) != 1 || localStores(method, secondLocal) != 1) {
            throw new IllegalStateException(
                    "MaterialSystem.buildSurface height locals changed: " + firstLocal + ", " + secondLocal);
        }
        MethodInsnNode secondRead = heightReads.get(1);
        AbstractInsnNode secondStart = previousReal(previousReal(previousReal(previousReal(secondRead))));
        AbstractInsnNode secondStore = nextReal(nextReal(nextReal(secondRead)));
        MethodInsnNode badlands = badlandsCalls.get(0);
        int order0 = method.instructions.indexOf(heightReads.get(0));
        int orderBadlands = method.instructions.indexOf(badlands);
        if (!(secondStart instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD)
                || nextReal(badlands) != secondStart
                || order0 >= orderBadlands) {
            throw new IllegalStateException("MaterialSystem.buildSurface duplicate height sequence changed");
        }
        // The only branch around erodedBadlandsExtension must land on the second height read.
        JumpInsnNode skipBadlands = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && nextReal(jump.label) == secondStart) {
                if (skipBadlands != null) {
                    throw new IllegalStateException("MaterialSystem.buildSurface has several jumps to the height re-read");
                }
                skipBadlands = jump;
            }
        }
        if (skipBadlands == null || skipBadlands.getOpcode() != Opcodes.IFEQ
                || method.instructions.indexOf(skipBadlands) <= order0
                || method.instructions.indexOf(skipBadlands) >= orderBadlands) {
            throw new IllegalStateException("MaterialSystem.buildSurface eroded-badlands branch changed");
        }
        LabelNode reuse = new LabelNode();
        LabelNode done = new LabelNode();
        skipBadlands.label = reuse;
        InsnList tail = new InsnList();
        tail.add(new JumpInsnNode(Opcodes.GOTO, done));
        tail.add(reuse);
        tail.add(new VarInsnNode(Opcodes.ILOAD, firstLocal));
        tail.add(new VarInsnNode(Opcodes.ISTORE, secondLocal));
        tail.add(done);
        method.instructions.insert(secondStore, tail);
        writeComputeFrames(node, root.resolve(MATERIAL_SYSTEM + ".class"));
        System.out.println("Patched 26.3 MaterialSystem.buildSurface to reuse column height off eroded badlands");
    }

    /** The local an exact {@code getHeight(...) + 1} sequence stores to, or -1. */
    private static int storedLocalAfterHeight(MethodInsnNode call) {
        AbstractInsnNode one = nextReal(call);
        AbstractInsnNode add = one == null ? null : nextReal(one);
        AbstractInsnNode store = add == null ? null : nextReal(add);
        if (one != null && one.getOpcode() == Opcodes.ICONST_1
                && add != null && add.getOpcode() == Opcodes.IADD
                && store instanceof VarInsnNode var && var.getOpcode() == Opcodes.ISTORE) {
            return var.var;
        }
        return -1;
    }

    private static int localStores(MethodNode method, int local) {
        int stores = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof VarInsnNode var && var.getOpcode() == Opcodes.ISTORE && var.var == local) {
                stores++;
            }
            if (instruction instanceof IincInsnNode inc && inc.var == local) {
                stores++;
            }
        }
        return stores;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    /**
     * Keeps the compatibility Function constructor but gives the 26.3 surface path a direct
     * BiomeManager field. The buildSurface caller still performs the vanilla null check; only the
     * per-chunk Function allocation and the Function.apply interface call per biome lookup are removed.
     */
    public static void patchMaterialRuleContextDirectBiomeManager(String jar, Path root) throws IOException {
        ClassNode context = readCurrent(jar, root, MATERIAL_RULE_CONTEXT);
        if (context.fields.stream().anyMatch(field -> field.name.equals(BROWSER_BIOME_MANAGER))) {
            throw new IllegalStateException("MaterialRuleContext." + BROWSER_BIOME_MANAGER + " already exists");
        }
        MethodNode functionConstructor = find(
                context, "<init>", MATERIAL_CONTEXT_CONSTRUCTOR_FUNCTION_DESC);
        MethodNode getBiome = find(context, "getBiome", "()" + BIOME_HOLDER_DESC);
        int functionReads = 0;
        int functionCalls = 0;
        for (AbstractInsnNode instruction : getBiome.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(MATERIAL_RULE_CONTEXT)
                    && field.name.equals("biomeGetter")
                    && field.desc.equals(FUNCTION_BIOME_DESC)) {
                functionReads++;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/function/Function")
                    && call.name.equals("apply")
                    && call.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")) {
                functionCalls++;
            }
        }
        if (functionReads != 1 || functionCalls != 1) {
            throw new IllegalStateException("MaterialRuleContext.getBiome function shape changed: reads="
                    + functionReads + ", calls=" + functionCalls);
        }
        context.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                BROWSER_BIOME_MANAGER, BIOME_MANAGER_DESC, null, null));

        MethodNode directConstructor = new MethodNode(
                functionConstructor.access,
                functionConstructor.name,
                MATERIAL_CONTEXT_CONSTRUCTOR_DIRECT_DESC,
                functionConstructor.signature,
                functionConstructor.exceptions == null
                        ? null
                        : functionConstructor.exceptions.toArray(new String[0]));
        functionConstructor.accept(directConstructor);
        int constructorWrites = 0;
        for (AbstractInsnNode instruction : directConstructor.instructions) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.owner.equals(MATERIAL_RULE_CONTEXT)
                    || !field.name.equals("biomeGetter")) {
                continue;
            }
            field.name = BROWSER_BIOME_MANAGER;
            field.desc = BIOME_MANAGER_DESC;
            constructorWrites++;
        }
        if (constructorWrites != 1) {
            throw new IllegalStateException("MaterialRuleContext direct constructor writes biome manager "
                    + constructorWrites + " times");
        }
        if (directConstructor.localVariables != null) {
            for (var local : directConstructor.localVariables) {
                if (local.index == 5 && FUNCTION_BIOME_DESC.equals(local.desc)) {
                    local.desc = BIOME_MANAGER_DESC;
                    local.signature = null;
                }
            }
        }
        context.methods.add(directConstructor);

        JumpInsnNode existingDone = null;
        for (AbstractInsnNode instruction : getBiome.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNONNULL) {
                if (existingDone != null) {
                    throw new IllegalStateException("MaterialRuleContext.getBiome has multiple cache branches");
                }
                existingDone = jump;
            }
        }
        if (existingDone == null) {
            throw new IllegalStateException("MaterialRuleContext.getBiome cache branch changed");
        }
        LabelNode functionPath = new LabelNode();
        InsnList directLookup = new InsnList();
        directLookup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        directLookup.add(new FieldInsnNode(
                Opcodes.GETFIELD, MATERIAL_RULE_CONTEXT, BROWSER_BIOME_MANAGER, BIOME_MANAGER_DESC));
        directLookup.add(new JumpInsnNode(Opcodes.IFNULL, functionPath));
        directLookup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        directLookup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        directLookup.add(new FieldInsnNode(
                Opcodes.GETFIELD, MATERIAL_RULE_CONTEXT, BROWSER_BIOME_MANAGER, BIOME_MANAGER_DESC));
        appendBiomePosition(directLookup);
        directLookup.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                BIOME_MANAGER,
                "getBiome",
                "(" + BIOME_POS_DESC + ")" + BIOME_HOLDER_DESC,
                false));
        directLookup.add(new FieldInsnNode(
                Opcodes.PUTFIELD, MATERIAL_RULE_CONTEXT, "biome", BIOME_HOLDER_DESC));
        directLookup.add(new JumpInsnNode(Opcodes.GOTO, existingDone.label));
        directLookup.add(functionPath);
        getBiome.instructions.insertBefore(existingDone.getNext(), directLookup);

        writeComputeFrames(context, root.resolve(MATERIAL_RULE_CONTEXT + ".class"));
        patchMaterialSystemDirectBiomeConstructor(jar, root);
        System.out.println("Patched 26.3 MaterialRuleContext with a direct BiomeManager surface path");
    }

    private static void appendBiomePosition(InsnList code) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, MATERIAL_RULE_CONTEXT, "pos", "Lnet/minecraft/core/BlockPos$MutableBlockPos;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MATERIAL_RULE_CONTEXT, "blockX", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MATERIAL_RULE_CONTEXT, "blockY", "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MATERIAL_RULE_CONTEXT, "blockZ", "I"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/core/BlockPos$MutableBlockPos",
                "set",
                "(III)Lnet/minecraft/core/BlockPos$MutableBlockPos;",
                false));
    }

    private static void patchMaterialSystemDirectBiomeConstructor(String jar, Path root) throws IOException {
        ClassNode system = readCurrent(jar, root, MATERIAL_SYSTEM);
        MethodNode buildSurface = find(
                system,
                "buildSurface",
                "(Lnet/minecraft/world/level/levelgen/RandomState;"
                        + "Lnet/minecraft/world/level/biome/BiomeManager;"
                        + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/levelgen/NoiseChunk;"
                        + "Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;"
                        + "Ljava/util/Set;)V");
        List<InvokeDynamicInsnNode> biomeLambdas = new ArrayList<>();
        List<MethodInsnNode> constructors = new ArrayList<>();
        for (AbstractInsnNode instruction : buildSurface.instructions) {
            if (instruction instanceof InvokeDynamicInsnNode dynamic
                    && dynamic.name.equals("apply")
                    && dynamic.desc.equals("(" + BIOME_MANAGER_DESC + ")" + FUNCTION_BIOME_DESC)) {
                biomeLambdas.add(dynamic);
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals(MATERIAL_RULE_CONTEXT)
                    && call.name.equals("<init>")
                    && call.desc.equals(MATERIAL_CONTEXT_CONSTRUCTOR_FUNCTION_DESC)) {
                constructors.add(call);
            }
        }
        if (biomeLambdas.size() != 1 || constructors.size() != 1) {
            throw new IllegalStateException("MaterialSystem.buildSurface biome constructor shape changed: lambdas="
                    + biomeLambdas.size() + ", constructors=" + constructors.size());
        }
        MethodInsnNode constructor = constructors.get(0);
        InvokeDynamicInsnNode biomeLambda = biomeLambdas.get(0);
        MethodInsnNode require = null;
        int requireCalls = 0;
        for (AbstractInsnNode instruction : buildSurface.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/util/Objects")
                    && call.name.equals("requireNonNull")
                    && call.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")) {
                require = call;
                requireCalls++;
            }
        }
        if (requireCalls != 1 || require == null
                || buildSurface.instructions.indexOf(require) >= buildSurface.instructions.indexOf(biomeLambda)) {
            throw new IllegalStateException("MaterialSystem.buildSurface biome null-check shape changed");
        }
        buildSurface.instructions.remove(biomeLambda);
        constructor.desc = MATERIAL_CONTEXT_CONSTRUCTOR_DIRECT_DESC;
        writeComputeFrames(system, root.resolve(MATERIAL_SYSTEM + ".class"));
    }

    /**
     * Caches the stable structure-step grouping used by applyBiomeDecoration. The registry is
     * immutable after data loading; the cache is identity-scoped to the ChunkGenerator and the
     * concurrent map keeps the first-use path safe when chunk decoration overlaps on workers.
     * The grouping operation itself is the vanilla operation, so list/map iteration order and all
     * feature RNG calls remain unchanged.
     */
    public static void patchChunkGeneratorStructureGroupingCache(String jar, Path root) throws IOException {
        ClassNode node = readCurrent(jar, root, CHUNK_GENERATOR);
        if (node.fields.stream().anyMatch(field -> field.name.equals(STRUCTURE_CACHE))) {
            throw new IllegalStateException("ChunkGenerator." + STRUCTURE_CACHE + " already exists");
        }
        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/world/level/biome/BiomeSource;Ljava/util/function/Function;)V");
        AbstractInsnNode objectInit = null;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("java/lang/Object")
                    && call.name.equals("<init>")
                    && call.desc.equals("()V")) {
                if (objectInit != null) {
                    throw new IllegalStateException("ChunkGenerator constructor has multiple Object initializers");
                }
                objectInit = instruction;
            }
        }
        if (objectInit == null) {
            throw new IllegalStateException("ChunkGenerator constructor Object initializer changed");
        }
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                STRUCTURE_CACHE,
                STRUCTURE_CACHE_DESC,
                null,
                null));
        InsnList cacheInit = new InsnList();
        cacheInit.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cacheInit.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap"));
        cacheInit.add(new InsnNode(Opcodes.DUP));
        cacheInit.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/concurrent/ConcurrentHashMap",
                "<init>",
                "()V",
                false));
        cacheInit.add(new FieldInsnNode(
                Opcodes.PUTFIELD, CHUNK_GENERATOR, STRUCTURE_CACHE, STRUCTURE_CACHE_DESC));
        constructor.instructions.insert(objectInit, cacheInit);

        MethodNode decoration = find(
                node,
                "applyBiomeDecoration",
                "(Lnet/minecraft/world/level/WorldGenLevel;"
                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                        + "Lnet/minecraft/world/level/StructureManager;)V");
        MethodInsnNode streamCall = null;
        InvokeDynamicInsnNode classifier = null;
        MethodInsnNode groupingBy = null;
        VarInsnNode mapStore = null;
        for (AbstractInsnNode instruction : decoration.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("net/minecraft/core/Registry")
                    && call.name.equals("stream")
                    && call.desc.equals("()Ljava/util/stream/Stream;")) {
                if (streamCall != null) {
                    throw new IllegalStateException("ChunkGenerator.applyBiomeDecoration has multiple registry streams");
                }
                streamCall = call;
            } else if (streamCall != null && classifier == null && instruction instanceof InvokeDynamicInsnNode dynamic
                    && dynamic.name.equals("apply")
                    && dynamic.desc.equals("()Ljava/util/function/Function;")) {
                classifier = dynamic;
            } else if (streamCall != null && groupingBy == null && instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/util/stream/Collectors")
                    && call.name.equals("groupingBy")) {
                groupingBy = call;
            } else if (groupingBy != null && instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE
                    && store.var == 8) {
                mapStore = store;
                break;
            }
        }
        if (streamCall == null || classifier == null || groupingBy == null || mapStore == null) {
            throw new IllegalStateException("ChunkGenerator.applyBiomeDecoration structure grouping shape changed");
        }
        AbstractInsnNode blockStart = previousReal(streamCall);
        if (!(blockStart instanceof VarInsnNode load
                && load.getOpcode() == Opcodes.ALOAD
                && load.var == 7)) {
            throw new IllegalStateException("ChunkGenerator.applyBiomeDecoration registry local changed");
        }
        AbstractInsnNode afterBlock = nextReal(mapStore);
        if (afterBlock == null) {
            throw new IllegalStateException("ChunkGenerator.applyBiomeDecoration grouping has no successor");
        }
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 7));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                CHUNK_GENERATOR,
                STRUCTURE_CACHE_METHOD,
                "(" + STRUCTURE_REGISTRY_DESC + ")Ljava/util/Map;",
                false));
        replacement.add(new VarInsnNode(Opcodes.ASTORE, 8));
        AbstractInsnNode cursor = blockStart;
        while (true) {
            AbstractInsnNode next = cursor.getNext();
            decoration.instructions.remove(cursor);
            if (cursor == mapStore) {
                break;
            }
            cursor = next;
        }
        decoration.instructions.insertBefore(afterBlock, replacement);

        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE,
                STRUCTURE_CACHE_METHOD,
                "(" + STRUCTURE_REGISTRY_DESC + ")Ljava/util/Map;",
                null,
                null);
        LabelNode build = new LabelNode();
        LabelNode returnComputed = new LabelNode();
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, CHUNK_GENERATOR, STRUCTURE_CACHE, STRUCTURE_CACHE_DESC));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/concurrent/ConcurrentMap",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true));
        helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
        helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFNULL, build));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        helper.instructions.add(new InsnNode(Opcodes.ARETURN));
        helper.instructions.add(build);
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/core/Registry",
                "stream",
                "()Ljava/util/stream/Stream;",
                true));
        helper.instructions.add(new InvokeDynamicInsnNode(
                classifier.name, classifier.desc, classifier.bsm, classifier.bsmArgs));
        helper.instructions.add(new MethodInsnNode(
                groupingBy.getOpcode(), groupingBy.owner, groupingBy.name, groupingBy.desc, groupingBy.itf));
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/stream/Stream",
                "collect",
                "(Ljava/util/stream/Collector;)Ljava/lang/Object;",
                true));
        helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
        helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, CHUNK_GENERATOR, STRUCTURE_CACHE, STRUCTURE_CACHE_DESC));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/concurrent/ConcurrentMap",
                "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true));
        helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
        helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFNULL, returnComputed));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        helper.instructions.add(new InsnNode(Opcodes.ARETURN));
        helper.instructions.add(returnComputed);
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        helper.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(helper);
        writeComputeFrames(node, root.resolve(CHUNK_GENERATOR + ".class"));
        System.out.println("Patched 26.3 ChunkGenerator.applyBiomeDecoration registry grouping cache");
    }

    private static void requireOnlyCounterWriters(ClassNode context) {
        for (MethodNode method : context.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(MATERIAL_RULE_CONTEXT)
                        && (field.name.equals("lastUpdateXZ") || field.name.equals("lastUpdateY"))
                        && !method.name.equals("<init>")
                        && !method.name.equals("updateXZ")
                        && !method.name.equals("updateY")) {
                    throw new IllegalStateException("MaterialRuleContext." + method.name
                            + method.desc + " also writes " + field.name
                            + "; the int shadow counters would drift");
                }
            }
        }
    }

    private static void requireLongCounterIncrement(MethodNode method, String field) {
        int increments = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode put
                    && put.getOpcode() == Opcodes.PUTFIELD
                    && put.owner.equals(MATERIAL_RULE_CONTEXT)
                    && put.name.equals(field)
                    && put.desc.equals("J")
                    && previousReal(put) instanceof InsnNode add
                    && add.getOpcode() == Opcodes.LADD
                    && previousReal(add) instanceof InsnNode one
                    && one.getOpcode() == Opcodes.LCONST_1) {
                increments++;
            }
        }
        if (increments != 1) {
            throw new IllegalStateException("MaterialRuleContext." + method.name
                    + " increments " + field + " " + increments + " times (expected 1)");
        }
    }

    private static void replaceLazyConditionTest(
            String jar, Path root, String suffix, String longCounter, String intCounter)
            throws IOException {
        String owner = MATERIAL_RULE_CONTEXT + suffix;
        ClassNode node = readCurrent(jar, root, owner);
        if ((node.access & Opcodes.ACC_ABSTRACT) == 0) {
            throw new IllegalStateException(owner + " is expected to be abstract");
        }
        MethodNode test = find(node, "test", "()Z");
        int counterReads = 0;
        int computeCalls = 0;
        for (AbstractInsnNode instruction : test.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(MATERIAL_RULE_CONTEXT)
                    && field.name.equals(longCounter)
                    && field.desc.equals("J")) {
                counterReads++;
            } else if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals("compute")
                    && call.desc.equals("()Z")) {
                computeCalls++;
            }
        }
        if (counterReads != 1 || computeCalls != 1) {
            throw new IllegalStateException(owner + ".test() shape changed: counter reads="
                    + counterReads + ", compute calls=" + computeCalls);
        }
        find(node, "compute", "()Z");
        MethodNode constructor = node.methods.stream()
                .filter(method -> method.name.equals("<init>")
                        && method.desc.equals("(L" + MATERIAL_RULE_CONTEXT + ";)V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(owner + " constructor changed"));
        boolean contextField = node.fields.stream().anyMatch(field ->
                field.name.equals("context") && field.desc.equals("L" + MATERIAL_RULE_CONTEXT + ";"));
        if (!contextField) {
            throw new IllegalStateException(owner + ".context field changed");
        }
        String lastUpdate = "browserLastUpdate";
        String result = "browserResult";
        String initialized = "browserResultInitialized";
        for (String name : List.of(lastUpdate, result, initialized)) {
            if (node.fields.stream().anyMatch(field -> field.name.equals(name))) {
                throw new IllegalStateException(owner + "." + name + " already exists");
            }
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                lastUpdate, "I", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                result, "Z", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                initialized, "Z", null, null));

        // Vanilla starts lastUpdate one step behind the context counter.  Keep that state in the
        // int shadow so the first test computes, while a failed compute leaves initialized=false
        // at the current counter and the next test reports vanilla's cached-null exception.
        InsnList initialState = new InsnList();
        initialState.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialState.add(new InsnNode(Opcodes.ICONST_M1));
        initialState.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, lastUpdate, "I"));
        AbstractInsnNode superCall = constructor.instructions.getFirst();
        while (superCall != null && !(superCall instanceof MethodInsnNode call
                && call.name.equals("<init>"))) {
            superCall = superCall.getNext();
        }
        if (superCall == null) {
            throw new IllegalStateException(owner + " constructor has no super call");
        }
        constructor.instructions.insert(superCall, initialState);

        LabelNode refresh = new LabelNode();
        LabelNode cached = new LabelNode();
        LabelNode initializedPath = new LabelNode();
        LabelNode failed = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "context",
                "L" + MATERIAL_RULE_CONTEXT + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MATERIAL_RULE_CONTEXT, intCounter, "I"));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, initialized, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, initializedPath));
        // A failed compute leaves the update number recorded but no result. Vanilla throws on
        // the next read of that same update instead of invoking compute a second time.
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, lastUpdate, "I"));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, failed));
        code.add(new JumpInsnNode(Opcodes.GOTO, refresh));
        code.add(initializedPath);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, lastUpdate, "I"));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, cached));
        code.add(refresh);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, lastUpdate, "I"));
        // Clear the cached result before compute(). If compute throws, the next test at the same
        // update must fail with vanilla's "result is null" exception instead of recomputing.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, initialized, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "compute", "()Z", false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, result, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, initialized, "Z"));
        code.add(cached);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, result, "Z"));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(failed);
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode("Update triggered but the result is null"));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException",
                "<init>", "(Ljava/lang/String;)V", false));
        code.add(new InsnNode(Opcodes.ATHROW));
        test.instructions = code;
        // The replacement has no handlers of its own; retaining the vanilla handlers would leave
        // stale labels after the method body replacement. The cached-null state above preserves
        // the observable exception behavior instead.
        test.tryCatchBlocks.clear();
        if (test.localVariables != null) {
            test.localVariables.clear();
        }
        test.maxStack = 3;
        test.maxLocals = 2;
        writeComputeFrames(node, root.resolve(owner + ".class"));
    }

    /**
     * Fails when a guarded class calls a suspending owner directly, or reaches one within
     * {@link #TRANSITIVE_DEPTH} calls through code of this jar (class-hierarchy analysis for
     * virtual and interface calls, lambda bodies for invokedynamic). Calls into classes outside
     * the jar are not followed. Reads the chain jar with the classes already written to
     * {@code root} taking precedence.
     */
    public static void verifyNonSuspendingWorldgenKernels(String jar, Path root) throws IOException {
        ClassIndex index = ClassIndex.load(jar, root);
        GuardReport report = checkNonSuspending(index);
        report.print(System.out);
        if (!report.violations.isEmpty()) {
            throw new IllegalStateException("Non-suspending worldgen guard failed: "
                    + report.violations.size() + " violation(s), first: "
                    + report.violations.get(0));
        }
    }

    /**
     * Standalone entry point for build scripts and quick-check modules.
     * {@code --verify-non-suspending <jar>} runs the guard; {@code --self-test <jar>} checks
     * that the guard catches a pulse injected into a density kernel and one injected into a
     * method a kernel reaches, and that the unmodified jar passes.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2
                || !(args[0].equals("--verify-non-suspending") || args[0].equals("--self-test"))) {
            System.err.println("usage: RenderpearlWorldgenPatches --verify-non-suspending|--self-test "
                    + "<client.jar>");
            System.exit(2);
        }
        ClassIndex index = ClassIndex.load(args[1], null);
        GuardReport report = checkNonSuspending(index);
        report.print(System.out);
        if (args[0].equals("--self-test")) {
            System.exit(selfTest(index, report) ? 0 : 1);
        }
        System.exit(report.violations.isEmpty() ? 0 : 1);
    }

    private static boolean selfTest(ClassIndex index, GuardReport clean) {
        if (!clean.violations.isEmpty()) {
            System.out.println("WORLDGEN_GUARD_SELF_TEST FAIL unmodified jar has violations");
            return false;
        }
        // Direct: a pulse at the start of the first concrete method of a density sampler.
        MethodNode kernel = null;
        String kernelKey = null;
        for (ClassNode node : index.classes.values()) {
            if (!node.name.startsWith(GUARDED_PACKAGES.get(0) + "op/")) {
                continue;
            }
            for (MethodNode method : node.methods) {
                if (method.name.equals("sampleVolume") && method.instructions.size() > 0) {
                    kernel = method;
                    kernelKey = node.name + "." + method.name + method.desc;
                    break;
                }
            }
            if (kernel != null) {
                break;
            }
        }
        if (kernel == null) {
            System.out.println("WORLDGEN_GUARD_SELF_TEST FAIL no density sampler found");
            return false;
        }
        MethodInsnNode pulse = new MethodInsnNode(
                Opcodes.INVOKESTATIC, DEEP_CHECKPOINT, "pulse", "()V", false);
        kernel.instructions.insert(pulse);
        GuardReport direct = checkNonSuspending(index);
        kernel.instructions.remove(pulse);
        String kernelName = kernelKey;
        boolean directCaught = direct.violations.stream().anyMatch(violation ->
                violation.startsWith("direct: ") && violation.contains(kernelName));
        // Transitive: a pulse in a method outside the guard that a guarded method calls.
        String outsideKey = null;
        for (String candidate : clean.reachedOutside) {
            MethodNode method = index.method(candidate);
            if (method != null && method.instructions.size() > 0) {
                outsideKey = candidate;
                break;
            }
        }
        boolean transitiveCaught = false;
        if (outsideKey != null) {
            MethodNode outside = index.method(outsideKey);
            MethodInsnNode outsidePulse = new MethodInsnNode(
                    Opcodes.INVOKESTATIC, DEEP_CHECKPOINT, "pulse", "()V", false);
            outside.instructions.insert(outsidePulse);
            GuardReport transitive = checkNonSuspending(index);
            outside.instructions.remove(outsidePulse);
            String target = outsideKey;
            transitiveCaught = transitive.violations.stream().anyMatch(violation ->
                    violation.startsWith("transitive: ") && violation.contains(target));
        }
        boolean cleanAgain = checkNonSuspending(index).violations.isEmpty();
        System.out.println("WORLDGEN_GUARD_SELF_TEST direct=" + directCaught + " (" + kernelKey
                + ") transitive=" + transitiveCaught + " (" + outsideKey + ") restored="
                + cleanAgain);
        boolean passed = directCaught && transitiveCaught && cleanAgain;
        System.out.println("WORLDGEN_GUARD_SELF_TEST " + (passed ? "PASS" : "FAIL"));
        return passed;
    }

    static boolean guarded(String className) {
        for (String prefix : GUARDED_PACKAGES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        for (String owner : GUARDED_CLASSES) {
            if (className.equals(owner) || className.startsWith(owner + "$")) {
                return true;
            }
        }
        return false;
    }

    static boolean suspending(String owner) {
        return SUSPENDING_OWNERS.contains(owner);
    }

    static GuardReport checkNonSuspending(ClassIndex index) {
        GuardReport report = new GuardReport();
        for (String required : REQUIRED_GUARDED_CLASSES) {
            if (!index.classes.containsKey(required)) {
                report.violations.add("required guarded class missing: " + required);
            }
        }
        List<String> roots = new ArrayList<>();
        for (ClassNode node : index.classes.values()) {
            if (!guarded(node.name)) {
                continue;
            }
            report.guardedClasses++;
            for (MethodNode method : node.methods) {
                report.guardedMethods++;
                String key = node.name + "." + method.name + method.desc;
                roots.add(key);
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && suspending(call.owner)) {
                        report.violations.add("direct: " + key + " calls "
                                + call.owner + "." + call.name + call.desc);
                    }
                }
            }
        }
        // Transitive part: breadth-first from every guarded method.
        Map<String, String> parent = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String rootKey : roots) {
            parent.put(rootKey, null);
            depth.put(rootKey, 0);
            queue.add(rootKey);
        }
        Set<String> reported = new HashSet<>();
        while (!queue.isEmpty()) {
            String key = queue.removeFirst();
            int level = depth.get(key);
            MethodNode method = index.method(key);
            if (method == null) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                for (String target : index.callTargets(instruction)) {
                    if (target.startsWith("!")) {
                        String owner = target.substring(1);
                        if (level > 0 && reported.add(key)) {
                            report.violations.add("transitive: " + path(parent, key)
                                    + " -> " + owner);
                        }
                        continue;
                    }
                    report.followedCalls++;
                    if (depth.containsKey(target) || level + 1 > TRANSITIVE_DEPTH) {
                        continue;
                    }
                    depth.put(target, level + 1);
                    parent.put(target, key);
                    queue.addLast(target);
                    if (!guarded(target.substring(0, target.indexOf('.')))) {
                        report.reachedOutside.add(target);
                    }
                }
            }
        }
        report.reachedMethods = depth.size();
        return report;
    }

    private static String path(Map<String, String> parent, String key) {
        List<String> chain = new ArrayList<>();
        for (String cursor = key; cursor != null; cursor = parent.get(cursor)) {
            chain.add(cursor);
        }
        Collections.reverse(chain);
        return String.join(" -> ", chain);
    }

    /** Result of {@link #checkNonSuspending}. */
    static final class GuardReport {
        final List<String> violations = new ArrayList<>();
        final Set<String> reachedOutside = new TreeSet<>();
        int guardedClasses;
        int guardedMethods;
        int reachedMethods;
        long followedCalls;

        void print(java.io.PrintStream out) {
            out.println("WORLDGEN_NON_SUSPENDING_GUARD guardedClasses=" + guardedClasses
                    + " guardedMethods=" + guardedMethods + " reachedMethods=" + reachedMethods
                    + " reachedOutsideGuard=" + reachedOutside.size()
                    + " depth=" + TRANSITIVE_DEPTH + " violations=" + violations.size());
            for (String violation : violations) {
                out.println("WORLDGEN_NON_SUSPENDING_VIOLATION " + violation);
            }
        }
    }

    /**
     * The classes of a client jar (optionally overlaid by patched class files), with the
     * subtype relation needed to resolve virtual and interface calls.
     */
    static final class ClassIndex {
        final Map<String, ClassNode> classes = new LinkedHashMap<>();
        final Map<String, List<String>> subtypes = new HashMap<>();
        private final Map<String, MethodNode> methods = new HashMap<>();
        private final Map<String, List<String>> virtualTargets = new HashMap<>();

        static ClassIndex load(String jar, Path overlay) throws IOException {
            ClassIndex index = new ClassIndex();
            try (ZipFile zip = new ZipFile(jar)) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                        continue;
                    }
                    String className = name.substring(0, name.length() - ".class".length());
                    Path patched = overlay == null ? null : ArchivePaths.resolveEntry(overlay, name);
                    byte[] bytes;
                    if (patched != null && Files.isRegularFile(patched)) {
                        bytes = Files.readAllBytes(patched);
                    } else {
                        try (InputStream input = zip.getInputStream(entry)) {
                            bytes = input.readAllBytes();
                        }
                    }
                    ClassNode node = new ClassNode();
                    new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG
                            | ClassReader.SKIP_FRAMES);
                    index.classes.put(className, node);
                }
            }
            for (ClassNode node : index.classes.values()) {
                if (node.superName != null) {
                    index.subtypes.computeIfAbsent(node.superName, key -> new ArrayList<>())
                            .add(node.name);
                }
                for (String iface : node.interfaces) {
                    index.subtypes.computeIfAbsent(iface, key -> new ArrayList<>()).add(node.name);
                }
                for (MethodNode method : node.methods) {
                    index.methods.put(node.name + "." + method.name + method.desc, method);
                }
            }
            return index;
        }

        MethodNode method(String key) {
            return methods.get(key);
        }

        /**
         * The methods of this jar a call instruction may reach; {@code "!owner"} marks a call to
         * a suspending owner.
         */
        List<String> callTargets(AbstractInsnNode instruction) {
            if (instruction instanceof MethodInsnNode call) {
                if (suspending(call.owner)) {
                    return List.of("!" + call.owner + "." + call.name + call.desc);
                }
                if (!classes.containsKey(call.owner)) {
                    return List.of();
                }
                String signature = call.name + call.desc;
                return switch (call.getOpcode()) {
                    case Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL -> {
                        String declared = resolveUp(call.owner, signature);
                        yield declared == null ? List.of() : List.of(declared);
                    }
                    default -> virtualTargets(call.owner, signature);
                };
            }
            if (instruction instanceof InvokeDynamicInsnNode indy) {
                List<String> targets = new ArrayList<>();
                for (Object argument : indy.bsmArgs) {
                    if (argument instanceof Handle handle) {
                        if (suspending(handle.getOwner())) {
                            targets.add("!" + handle.getOwner() + "." + handle.getName()
                                    + handle.getDesc());
                        } else if (methods.containsKey(handle.getOwner() + "."
                                + handle.getName() + handle.getDesc())) {
                            targets.add(handle.getOwner() + "." + handle.getName()
                                    + handle.getDesc());
                        }
                    }
                }
                return targets;
            }
            return List.of();
        }

        private String resolveUp(String owner, String signature) {
            for (String cursor = owner; cursor != null; ) {
                String key = cursor + "." + signature;
                if (methods.containsKey(key)) {
                    return key;
                }
                ClassNode node = classes.get(cursor);
                cursor = node == null ? null : node.superName;
            }
            return null;
        }

        private List<String> virtualTargets(String owner, String signature) {
            if (signature.startsWith("equals(") || signature.startsWith("hashCode(")
                    || signature.startsWith("toString(")) {
                return List.of();
            }
            String cacheKey = owner + "." + signature;
            List<String> cached = virtualTargets.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            Set<String> targets = new TreeSet<>();
            String inherited = resolveUp(owner, signature);
            if (inherited != null) {
                targets.add(inherited);
            }
            Deque<String> pending = new ArrayDeque<>(subtypes.getOrDefault(owner, List.of()));
            Set<String> seen = new HashSet<>();
            while (!pending.isEmpty()) {
                String type = pending.removeFirst();
                if (!seen.add(type)) {
                    continue;
                }
                String key = type + "." + signature;
                MethodNode method = methods.get(key);
                if (method != null && (method.access & Opcodes.ACC_ABSTRACT) == 0) {
                    targets.add(key);
                }
                pending.addAll(subtypes.getOrDefault(type, List.of()));
            }
            List<String> result = List.copyOf(targets);
            virtualTargets.put(cacheKey, result);
            return result;
        }
    }

    private static void requireNoSuspendingCalls(String label, MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && suspending(call.owner)) {
                throw new IllegalStateException(label + " already calls " + call.owner + "."
                        + call.name);
            }
        }
    }

    private static int insertDeepPulsesAtBackEdges(MethodNode method) {
        int pulses = 0;
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) {
            if (instructions[index] instanceof JumpInsnNode jump
                    && method.instructions.indexOf(jump.label) < index) {
                method.instructions.insertBefore(jump, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, DEEP_CHECKPOINT, "pulse", "()V", false));
                pulses++;
            }
        }
        return pulses;
    }

    private static void appendIntFieldIncrement(InsnList code, String owner, String field) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, "I"));
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new IllegalStateException(node.name + "." + name + descriptor + " was not found");
    }

    /** Reads {@code owner} as the chain has patched it so far (root file first, then jar). */
    private static ClassNode readCurrent(String jar, Path root, String owner) throws IOException {
        Path patched = root.resolve(owner + ".class");
        byte[] bytes;
        if (Files.isRegularFile(patched)) {
            bytes = Files.readAllBytes(patched);
        } else {
            try (ZipFile zip = new ZipFile(jar)) {
                ZipEntry entry = zip.getEntry(owner + ".class");
                if (entry == null) {
                    throw new IOException("Missing class entry " + owner + ".class in " + jar);
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
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void writeComputeFrames(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
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
