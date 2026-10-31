package dev.gaius.tools.patches.worldgen;

import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
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
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Routes chunk generation through the Rust worldgen kernel ({@code dev.gaius.browser.kernel.worldgen})
 * on the profiles it mirrors bit for bit: 26.3 and 26.2. 1.21.11 keeps the vanilla path.
 *
 * <p>26.3 ({@code NoiseBasedChunkGenerator.buildTerrain} merges NOISE, SURFACE and CARVERS):
 * <ul>
 *   <li>{@code NoiseBasedChunkGenerator.buildTerrain} becomes {@code gaius$buildTerrainVanilla};
 *       the new {@code buildTerrain} returns {@code WorldgenKernel263.buildTerrain(this, ...)}
 *       when that is not null, otherwise the vanilla method's future;</li>
 *   <li>{@code ChunkGenerator.createBiomes} becomes {@code gaius$createBiomesVanilla}, routed the
 *       same way through {@code WorldgenKernel263.createBiomes};</li>
 *   <li>{@code NoiseBasedChunkGenerator} implements {@code WorldgenKernelHooks263}: bridges to the
 *       private {@code createNoiseChunk} and {@code generateCarvers}, so the carvers run on a
 *       kernel-filled chunk.</li>
 * </ul>
 *
 * <p>26.2 (separate BIOMES / NOISE / SURFACE / CARVERS steps):
 * <ul>
 *   <li>{@code NoiseBasedChunkGenerator.fillFromNoise} and its {@code createBiomes} become
 *       {@code gaius$fillFromNoiseVanilla} / {@code gaius$createBiomesVanilla}, routed through
 *       {@code WorldgenKernel262.fillFromNoise} / {@code createBiomes};</li>
 *   <li>{@code StructureManager} implements {@code WorldgenSeedSource262}
 *       ({@code gaius$worldSeed()} returns {@code worldOptions.seed()});</li>
 *   <li>{@code ChunkStatusTasks.generateSurface} becomes {@code gaius$generateSurfaceVanilla},
 *       routed through {@code WorldgenKernel262.generateSurface} (kernel surface rules on the
 *       noise chunk the kernel kept for that proto chunk).</li>
 * </ul>
 *
 * <p>The kernel classes decide per chunk (runtime switch, eligibility, failures) and return null
 * for the vanilla path. A profile without the 26.2+ block state API is skipped with a log line;
 * on a supported profile a missing method or field shape fails the build.
 */
public final class WorldgenKernelPatches {
    static final String GENERATOR = "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator";
    static final String CHUNK_GENERATOR = "net/minecraft/world/level/chunk/ChunkGenerator";
    static final String STRUCTURE_MANAGER = "net/minecraft/world/level/StructureManager";
    static final String CHUNK_STATUS_TASKS = "net/minecraft/world/level/chunk/status/ChunkStatusTasks";
    static final String WORLD_OPTIONS = "net/minecraft/world/level/levelgen/WorldOptions";
    static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    static final String FUTURE = "java/util/concurrent/CompletableFuture";

    static final String CHUNK = "Lnet/minecraft/world/level/chunk/ChunkAccess;";
    static final String BLENDER = "Lnet/minecraft/world/level/levelgen/blending/Blender;";
    static final String RANDOM_STATE = "Lnet/minecraft/world/level/levelgen/RandomState;";
    static final String STRUCTURES = "Lnet/minecraft/world/level/StructureManager;";
    static final String BIOME_MANAGER = "Lnet/minecraft/world/level/biome/BiomeManager;";
    static final String REGION = "Lnet/minecraft/server/level/WorldGenRegion;";
    static final String NOISE_CHUNK = "Lnet/minecraft/world/level/levelgen/NoiseChunk;";
    static final String NOISE_SETTINGS = "Lnet/minecraft/world/level/levelgen/NoiseSettings;";
    static final String MATERIAL_RULE = "Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;";
    static final String AQUIFER = "Lnet/minecraft/world/level/levelgen/Aquifer;";
    static final String DENSITY_SAMPLER_SET =
            "Lnet/minecraft/world/level/levelgen/densityfunction/DensitySamplerSet;";
    static final String INT_BINARY_OPERATOR = "Ljava/util/function/IntBinaryOperator;";

    static final String BUILD_TERRAIN_263 =
            "(" + CHUNK + BLENDER + RANDOM_STATE + STRUCTURES + BIOME_MANAGER + REGION + "Ljava/util/Set;)L" + FUTURE + ";";
    static final String CREATE_BIOMES = "(" + RANDOM_STATE + BLENDER + STRUCTURES + CHUNK + ")L" + FUTURE + ";";
    static final String FILL_FROM_NOISE_262 = "(" + BLENDER + RANDOM_STATE + STRUCTURES + CHUNK + ")L" + FUTURE + ";";
    static final String GENERATE_SURFACE_262 = "(Lnet/minecraft/world/level/chunk/status/WorldGenContext;"
            + "Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;" + CHUNK
            + ")L" + FUTURE + ";";
    static final String CREATE_NOISE_CHUNK_263 =
            "(" + CHUNK + STRUCTURES + BLENDER + RANDOM_STATE + NOISE_SETTINGS + ")" + NOISE_CHUNK;
    static final String CREATE_NOISE_CHUNK_262 =
            "(" + CHUNK + STRUCTURES + BLENDER + RANDOM_STATE + ")" + NOISE_CHUNK;
    static final String DO_FILL_262 = "(" + BLENDER + STRUCTURES + RANDOM_STATE + CHUNK + "II)" + CHUNK;
    static final String GENERATE_CARVERS_263 =
            "(" + CHUNK + BLENDER + NOISE_CHUNK + RANDOM_STATE + BIOME_MANAGER + REGION + MATERIAL_RULE + ")V";
    static final String DO_FILL_263 = "(" + NOISE_CHUNK + CHUNK + ")V";

    static final String KERNEL_263 = "dev/gaius/browser/kernel/worldgen/WorldgenKernel263";
    static final String HOOKS_263 = "dev/gaius/browser/kernel/worldgen/WorldgenKernelHooks263";
    static final String BIOME_HOOKS_263 = "dev/gaius/browser/kernel/worldgen/ChunkGeneratorKernelHooks263";
    static final String KERNEL_262 = "dev/gaius/browser/kernel/worldgen/WorldgenKernel262";
    static final String HOOKS_262 = "dev/gaius/browser/kernel/worldgen/WorldgenKernelHooks262";
    static final String SEED_SOURCE_262 = "dev/gaius/browser/kernel/worldgen/WorldgenSeedSource262";
    static final String NOISE_CHUNK_CARVER_263 =
            "dev/gaius/browser/kernel/worldgen/CarverNoiseState263";
    static final String NOISE_CHUNK_CARVER_262 =
            "dev/gaius/browser/kernel/worldgen/CarverNoiseState262";

    private WorldgenKernelPatches() {
    }

    /** Patches the generator classes into {@code outputRoot}; false when the profile is skipped. */
    public static boolean patch(Path jar, Path outputRoot) throws IOException {
        ClassNode stateBase = read(jar, outputRoot, STATE_BASE);
        boolean modern = stateBase.methods.stream()
                .anyMatch(method -> method.name.equals("getLightDampening") && method.desc.equals("()I"));
        if (!modern) {
            throw new IllegalStateException("Worldgen kernel dampening seam is absent");
        }
        ClassNode generator = read(jar, outputRoot, GENERATOR);
        if (generator.interfaces.contains(HOOKS_263) || generator.interfaces.contains(HOOKS_262)) {
            throw new IllegalStateException(GENERATOR + " is already patched for the worldgen kernel");
        }
        if (has(generator, "buildTerrain", BUILD_TERRAIN_263)) {
            patch263(jar, outputRoot, generator);
        } else if (has(generator, "fillFromNoise", FILL_FROM_NOISE_262)) {
            patch262(jar, outputRoot, generator);
        } else {
            throw new IllegalStateException(GENERATOR + " has neither the 26.3 buildTerrain nor the 26.2"
                    + " fillFromNoise shape");
        }
        return true;
    }

    private static void patch263(Path jar, Path outputRoot, ClassNode generator) throws IOException {
        find(generator, "createNoiseChunk", CREATE_NOISE_CHUNK_263);
        find(generator, "doFill", DO_FILL_263);
        find(generator, "generateCarvers", GENERATE_CARVERS_263);
        route(generator, find(generator, "buildTerrain", BUILD_TERRAIN_263), "gaius$buildTerrainVanilla",
                KERNEL_263, "buildTerrain");
        generator.methods.add(bridge(generator, "gaius$createNoiseChunk", "createNoiseChunk", CREATE_NOISE_CHUNK_263));
        generator.methods.add(bridge(generator, "gaius$doFill", "doFill", DO_FILL_263));
        generator.methods.add(bridge(generator, "gaius$generateCarvers", "generateCarvers", GENERATE_CARVERS_263));
        generator.interfaces.add(HOOKS_263);
        write(generator, outputRoot);
        patchNoiseStackDispatch(jar, outputRoot);
        patchNoiseChunk263(jar, outputRoot);

        ClassNode chunkGenerator = read(jar, outputRoot, CHUNK_GENERATOR);
        if (chunkGenerator.interfaces.contains(BIOME_HOOKS_263)) {
            throw new IllegalStateException(CHUNK_GENERATOR + " is already patched for the worldgen kernel");
        }
        if (has(generator, "createBiomes", CREATE_BIOMES)) {
            throw new IllegalStateException(GENERATOR + " overrides createBiomes on 26.3; route it as well");
        }
        route(chunkGenerator, find(chunkGenerator, "createBiomes", CREATE_BIOMES), "gaius$createBiomesVanilla",
                KERNEL_263, "createBiomes");
        chunkGenerator.interfaces.add(BIOME_HOOKS_263);
        write(chunkGenerator, outputRoot);
        System.out.println("Routed 26.3 NoiseBasedChunkGenerator.buildTerrain and ChunkGenerator.createBiomes"
                + " through the worldgen kernel (vanilla kept as gaius$buildTerrainVanilla/gaius$createBiomesVanilla)");
    }

    private static void patchNoiseStackDispatch(Path jar, Path outputRoot) throws IOException {
        String owner = "net/minecraft/world/level/levelgen/synth/NoiseStack";
        ClassNode node = read(jar, outputRoot, owner);
        MethodNode method = find(node, "addToVolume",
                "(Lnet/minecraft/world/level/levelgen/densityfunction/DensityBuffer;"
                        + "Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;DDF)V");
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !call.owner.equals("net/minecraft/world/level/levelgen/synth/Noise")
                    || !call.name.equals("addToVolume")) {
                continue;
            }
            method.instructions.set(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserNoiseStack",
                    "addToVolume",
                    "(Lnet/minecraft/world/level/levelgen/synth/Noise;"
                            + "Lnet/minecraft/world/level/levelgen/densityfunction/DensityBuffer;"
                            + "Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;DDF)V",
                    false));
            calls++;
        }
        if (calls != 1) {
            throw new IllegalStateException("NoiseStack.addToVolume dispatch shape changed: " + calls);
        }
        writeComputeFrames(node, outputRoot.resolve(owner + ".class"));
        System.out.println("Patched NoiseStack.addToVolume with vanilla-type dispatch fast path");
    }

    private static void patch262(Path jar, Path outputRoot, ClassNode generator) throws IOException {
        find(generator, "createNoiseChunk", CREATE_NOISE_CHUNK_262);
        find(generator, "doFill", DO_FILL_262);
        route(generator, find(generator, "fillFromNoise", FILL_FROM_NOISE_262), "gaius$fillFromNoiseVanilla",
                KERNEL_262, "fillFromNoise");
        route(generator, find(generator, "createBiomes", CREATE_BIOMES), "gaius$createBiomesVanilla",
                KERNEL_262, "createBiomes");
        generator.methods.add(bridge(generator, "gaius$createNoiseChunk", "createNoiseChunk", CREATE_NOISE_CHUNK_262));
        generator.methods.add(bridge(generator, "gaius$doFill", "doFill", DO_FILL_262));
        generator.interfaces.add(HOOKS_262);
        write(generator, outputRoot);
        patchNoiseChunk262(jar, outputRoot);

        ClassNode structures = read(jar, outputRoot, STRUCTURE_MANAGER);
        if (structures.interfaces.contains(SEED_SOURCE_262)) {
            throw new IllegalStateException(STRUCTURE_MANAGER + " is already patched for the worldgen kernel");
        }
        String optionsDesc = "L" + WORLD_OPTIONS + ";";
        boolean hasOptions = structures.fields.stream()
                .anyMatch(field -> field.name.equals("worldOptions") && field.desc.equals(optionsDesc));
        if (!hasOptions) {
            throw new IllegalStateException(STRUCTURE_MANAGER + ".worldOptions was not found");
        }
        ClassNode options = read(jar, outputRoot, WORLD_OPTIONS);
        find(options, "seed", "()J");
        MethodNode seed = new MethodNode(Opcodes.ACC_PUBLIC, "gaius$worldSeed", "()J", null, null);
        seed.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        seed.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, STRUCTURE_MANAGER, "worldOptions", optionsDesc));
        seed.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, WORLD_OPTIONS, "seed", "()J", false));
        seed.instructions.add(new InsnNode(Opcodes.LRETURN));
        seed.maxStack = 2;
        seed.maxLocals = 1;
        structures.methods.add(seed);
        structures.interfaces.add(SEED_SOURCE_262);
        write(structures, outputRoot);

        ClassNode tasks = read(jar, outputRoot, CHUNK_STATUS_TASKS);
        route(tasks, find(tasks, "generateSurface", GENERATE_SURFACE_262), "gaius$generateSurfaceVanilla",
                KERNEL_262, "generateSurface");
        write(tasks, outputRoot);
        System.out.println("Routed 26.2 NoiseBasedChunkGenerator.fillFromNoise/createBiomes and"
                + " ChunkStatusTasks.generateSurface through the worldgen kernel (vanilla kept as"
                + " gaius$fillFromNoiseVanilla/gaius$createBiomesVanilla/gaius$generateSurfaceVanilla)");
    }

    /** Adds the 26.3 carver-state overrides without changing vanilla's fill implementation. */
    private static void patchNoiseChunk263(Path jar, Path outputRoot) throws IOException {
        ClassNode node = read(jar, outputRoot, "net/minecraft/world/level/levelgen/NoiseChunk");
        String owner = node.name;
        String aquiferField = "gaius$carverAquifer";
        String samplersField = "gaius$carverSamplers";
        if (node.fields.stream().anyMatch(field -> field.name.equals(aquiferField)
                || field.name.equals(samplersField))) {
            throw new IllegalStateException(owner + " already has carver-state fields");
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, aquiferField, AQUIFER, null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, samplersField, DENSITY_SAMPLER_SET, null, null));
        routeNoiseAccessor(node, "aquifer", "()" + AQUIFER, aquiferField, "aquifer");
        routeNoiseAccessor(node, "cachingSamplers", "()" + DENSITY_SAMPLER_SET,
                samplersField, "cachingSamplers");
        node.methods.add(installNoiseState(node, "gaius$installCarverState",
                "(Lnet/minecraft/world/level/levelgen/Aquifer;"
                        + "Lnet/minecraft/world/level/levelgen/densityfunction/DensitySamplerSet;)V",
                aquiferField, samplersField));
        write(node, outputRoot);
    }

    /** Adds the 26.2 aquifer and preliminary-surface overrides. */
    private static void patchNoiseChunk262(Path jar, Path outputRoot) throws IOException {
        ClassNode node = read(jar, outputRoot, "net/minecraft/world/level/levelgen/NoiseChunk");
        String owner = node.name;
        String aquiferField = "gaius$carverAquifer";
        String preliminaryField = "gaius$carverPreliminarySurface";
        if (node.fields.stream().anyMatch(field -> field.name.equals(aquiferField)
                || field.name.equals(preliminaryField))) {
            throw new IllegalStateException(owner + " already has carver-state fields");
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, aquiferField, AQUIFER, null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, preliminaryField, INT_BINARY_OPERATOR, null, null));
        routeNoiseAccessor(node, "aquifer", "()" + AQUIFER, aquiferField, "aquifer");
        MethodNode preliminary = find(node, "preliminarySurfaceLevel", "(II)I");
        if (node.methods.stream().anyMatch(method -> method.name.equals("gaius$preliminarySurfaceLevelVanilla"))) {
            throw new IllegalStateException(owner + " already has the vanilla preliminary-surface copy");
        }
        preliminary.name = "gaius$preliminarySurfaceLevelVanilla";
        preliminary.access = (preliminary.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        node.methods.add(routedPreliminarySurface(owner, preliminaryField));
        node.methods.add(installNoiseState(node, "gaius$installCarverState",
                "(Lnet/minecraft/world/level/levelgen/Aquifer;Ljava/util/function/IntBinaryOperator;)V",
                aquiferField, preliminaryField));
        write(node, outputRoot);
    }

    private static void routeNoiseAccessor(ClassNode node, String methodName, String descriptor,
            String overrideField, String vanillaField) {
        MethodNode method = find(node, methodName, descriptor);
        InsnList code = new InsnList();
        LabelNode useVanilla = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, overrideField, method.desc.substring(2)));
        code.add(new JumpInsnNode(Opcodes.IFNULL, useVanilla));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, overrideField, method.desc.substring(2)));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(useVanilla);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, vanillaField, method.desc.substring(2)));
        code.add(new InsnNode(Opcodes.ARETURN));
        method.instructions = code;
        method.maxStack = 1;
        method.maxLocals = 1;
    }

    private static MethodNode routedPreliminarySurface(String owner, String overrideField) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "preliminarySurfaceLevel", "(II)I", null, null);
        LabelNode useVanilla = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, overrideField, INT_BINARY_OPERATOR));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, useVanilla));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, overrideField, INT_BINARY_OPERATOR));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/util/function/IntBinaryOperator", "applyAsInt", "(II)I", true));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(useVanilla);
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner,
                "gaius$preliminarySurfaceLevelVanilla", "(II)I", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 3;
        method.maxLocals = 3;
        return method;
    }

    private static MethodNode installNoiseState(ClassNode node, String name, String descriptor,
            String firstField, String secondField) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, firstField,
                AQUIFER));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        String secondDescriptor = descriptor.contains("DensitySamplerSet")
                ? DENSITY_SAMPLER_SET : INT_BINARY_OPERATOR;
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, secondField, secondDescriptor));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 3;
        return method;
    }

    /**
     * Renames {@code vanilla} to {@code vanillaName} and adds a method with its old name and
     * descriptor: {@code f = Hook.hookName(this, args...); return f != null ? f : this.vanillaName(args...)}
     * (without {@code this} for a static method).
     */
    private static void route(ClassNode node, MethodNode vanilla, String vanillaName, String hookOwner, String hookName) {
        if (node.methods.stream().anyMatch(method -> method.name.equals(vanillaName))) {
            throw new IllegalStateException(node.name + " already has " + vanillaName);
        }
        if ((vanilla.access & Opcodes.ACC_ABSTRACT) != 0) {
            throw new IllegalStateException(node.name + "." + vanilla.name + " is abstract");
        }
        boolean isStatic = (vanilla.access & Opcodes.ACC_STATIC) != 0;
        Type type = Type.getMethodType(vanilla.desc);
        if (!type.getReturnType().getInternalName().equals(FUTURE)) {
            throw new IllegalStateException(node.name + "." + vanilla.name + " no longer returns " + FUTURE);
        }
        String name = vanilla.name;
        String hookDesc = isStatic ? vanilla.desc : "(L" + node.name + ";" + vanilla.desc.substring(1);
        vanilla.name = vanillaName;
        vanilla.access = (vanilla.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;

        MethodNode routed = new MethodNode(vanilla.access & ~Opcodes.ACC_SYNTHETIC, name, vanilla.desc,
                vanilla.signature, vanilla.exceptions == null ? null : vanilla.exceptions.toArray(new String[0]));
        InsnList code = routed.instructions;
        LabelNode useVanilla = new LabelNode();
        int slots = loadArguments(code, type, isStatic);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc, false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNULL, useVanilla));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(useVanilla);
        code.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {FUTURE}));
        code.add(new InsnNode(Opcodes.POP));
        loadArguments(code, type, isStatic);
        code.add(new MethodInsnNode(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, node.name, vanillaName,
                vanilla.desc, (node.access & Opcodes.ACC_INTERFACE) != 0));
        code.add(new InsnNode(Opcodes.ARETURN));
        routed.maxStack = slots + 1;
        routed.maxLocals = slots;
        node.methods.add(routed);
    }

    /** A public method that forwards its arguments to the private {@code target} of the same class. */
    private static MethodNode bridge(ClassNode node, String name, String target, String desc) {
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        Type type = Type.getMethodType(desc);
        int slots = loadThisAndArguments(bridge.instructions, type);
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, target, desc, false));
        bridge.instructions.add(new InsnNode(type.getReturnType().getOpcode(Opcodes.IRETURN)));
        bridge.maxStack = Math.max(slots, type.getReturnType().getSize());
        bridge.maxLocals = slots;
        return bridge;
    }

    /** Loads {@code this} and every argument; returns the slots used. */
    private static int loadThisAndArguments(InsnList code, Type method) {
        return loadArguments(code, method, false);
    }

    /** Loads every argument, after {@code this} unless static; returns the slots used. */
    private static int loadArguments(InsnList code, Type method, boolean isStatic) {
        int slot = 0;
        if (!isStatic) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            slot = 1;
        }
        for (Type argument : method.getArgumentTypes()) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
        return slot;
    }

    private static boolean has(ClassNode node, String name, String descriptor) {
        return node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor));
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(node.name + "." + name + descriptor + " was not found"));
    }

    /** Reads {@code owner} from {@code outputRoot} when an earlier step wrote it, else the jar. */
    private static ClassNode read(Path jar, Path outputRoot, String owner) throws IOException {
        Path written = outputRoot.resolve(owner + ".class");
        byte[] bytes;
        if (Files.isRegularFile(written)) {
            bytes = Files.readAllBytes(written);
        } else {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                var entry = zip.getEntry(owner + ".class");
                if (entry == null) {
                    throw new IOException("Missing class entry " + owner + ".class in " + jar);
                }
                try (var stream = zip.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Writes without recomputing frames: the added methods supply theirs. */
    private static void write(ClassNode node, Path outputRoot) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = outputRoot.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
