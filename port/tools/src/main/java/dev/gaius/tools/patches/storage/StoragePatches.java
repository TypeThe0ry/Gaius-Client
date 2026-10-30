package dev.gaius.tools.patches.storage;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The storage domain of the MinecraftClientPatcher step ({@code patches/storage}):
 * the browser storage mount of the client main, world stem saves, region file compression,
 * gzip structure templates and the jar file system of
 * {@code Util}.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically.
 */
public final class StoragePatches {
    private StoragePatches() {
    }

    public static void patchMainBrowserStorageMount(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/main/Main.class");
        MethodNode main = find(node, "main", "([Ljava/lang/String;)V");
        boolean lazyDataFixerOptimizationHooked = false;
        boolean bootstrapPhaseHooked = false;
        boolean clientBootstrapPhaseHooked = false;
        boolean bootstrapValidationPhaseHooked = false;
        boolean dataFixerJoinPhaseHooked = false;
        boolean renderThreadPhaseHooked = false;
        AbstractInsnNode startupCompletionPoint = null;
        for (AbstractInsnNode instruction : main.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            if (call.owner.equals("com/mojang/blaze3d/TracyBootstrap")
                    && call.name.equals("setup") && call.desc.equals("()V")) {
                main.instructions.set(call, new InsnNode(Opcodes.NOP));
            } else if (call.owner.equals("com/mojang/jtracy/TracyClient")
                    && call.name.equals("reportAppInfo") && call.desc.equals("(Ljava/lang/String;)V")) {
                main.instructions.set(call, new InsnNode(Opcodes.POP));
            } else if (call.owner.equals("net/minecraft/util/datafix/DataFixers")
                    && call.name.equals("optimize")
                    && call.desc.equals("(Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;")) {
                call.owner = "dev/gaius/browser/BrowserLazyDataFixer";
                call.name = "skipEagerOptimization";
                lazyDataFixerOptimizationHooked = true;
            } else if (call.owner.equals("net/minecraft/server/Bootstrap")
                    && call.name.equals("bootStrap") && call.desc.equals("()V")) {
                main.instructions.insert(call, browserStartupPhase("bootstrap-complete"));
                bootstrapPhaseHooked = true;
            } else if (call.owner.equals("net/minecraft/client/ClientBootstrap")
                    && call.name.equals("bootstrap") && call.desc.equals("()V")) {
                main.instructions.insert(call, browserStartupPhase("client-bootstrap-complete"));
                clientBootstrapPhaseHooked = true;
            } else if (call.owner.equals("net/minecraft/server/Bootstrap")
                    && call.name.equals("validate") && call.desc.equals("()V")) {
                main.instructions.insert(call, browserStartupPhase("bootstrap-validated"));
                bootstrapValidationPhaseHooked = true;
            } else if (call.owner.equals("java/util/concurrent/CompletableFuture")
                    && call.name.equals("join") && call.desc.equals("()Ljava/lang/Object;")) {
                main.instructions.insert(
                        call, browserStartupPhase("datafixer-optimization-complete"));
                dataFixerJoinPhaseHooked = true;
            } else if (call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")
                    && call.name.equals("initRenderThread") && call.desc.equals("()V")) {
                main.instructions.insert(call, browserStartupPhase("render-thread-ready"));
                renderThreadPhaseHooked = true;
            } else if (call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("net/minecraft/client/Minecraft")
                    && call.name.equals("<init>")
                    && call.desc.equals("(Lnet/minecraft/client/main/GameConfig;)V")) {
                AbstractInsnNode store = nextRealInstruction(call);
                if (!(store instanceof VarInsnNode variable)
                        || variable.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException(
                            "Minecraft constructor result store changed");
                }
                startupCompletionPoint = store;
            }
        }
        if (!lazyDataFixerOptimizationHooked) {
            throw new IllegalStateException("Main eager data fixer optimization hook point was not found");
        }
        if (!bootstrapPhaseHooked || !clientBootstrapPhaseHooked
                || !bootstrapValidationPhaseHooked || !dataFixerJoinPhaseHooked
                || !renderThreadPhaseHooked) {
            throw new IllegalStateException("Main browser startup phase hook points were not found");
        }
        if (startupCompletionPoint == null) {
            throw new IllegalStateException(
                    "Minecraft browser startup completion point was not found");
        }
        main.instructions.insert(startupCompletionPoint, new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserStartupScheduler",
                "complete",
                "()V",
                false));
        main.instructions.insert(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserFilePersistence",
                "mount",
                "()V",
                false));
        write(node, output);
    }

    private static InsnList browserStartupPhase(String phase) {
        InsnList checkpoint = new InsnList();
        checkpoint.add(new LdcInsnNode(phase));
        checkpoint.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserStartupScheduler",
                "phase",
                "(Ljava/lang/String;)V",
                false));
        return checkpoint;
    }

    public static void patchUtilJarFileSystem(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/Util.class");
        MethodNode clinit = find(node, "<clinit>", "()V");
        var start = clinit.instructions.getFirst();
        while (start != null) {
            if (start instanceof MethodInsnNode call
                    && call.owner.equals("java/nio/file/spi/FileSystemProvider")
                    && call.name.equals("installedProviders")
                    && call.desc.equals("()Ljava/util/List;")) {
                break;
            }
            start = start.getNext();
        }
        if (start == null) {
            throw new IllegalStateException("Util jar provider lookup was not found");
        }

        var end = start;
        while (end != null) {
            if (end instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.owner.equals("net/minecraft/util/Util")
                    && field.name.equals("ZIP_FILE_SYSTEM_PROVIDER")) {
                break;
            }
            end = end.getNext();
        }
        if (end == null) {
            throw new IllegalStateException("Util ZIP_FILE_SYSTEM_PROVIDER write was not found");
        }

        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                "net/minecraft/util/Util",
                "ZIP_FILE_SYSTEM_PROVIDER",
                "Ljava/nio/file/spi/FileSystemProvider;"));
        clinit.instructions.insertBefore(start, code);

        var current = start;
        while (current != null) {
            var next = current.getNext();
            clinit.instructions.remove(current);
            if (current == end) {
                break;
            }
            current = next;
        }
        replace(clinit, clinit.instructions, clinit.maxStack, clinit.maxLocals);

        MethodNode timerHack = find(node, "startTimerHackThread", "()V");
        InsnList timerCode = new InsnList();
        timerCode.add(new InsnNode(Opcodes.RETURN));
        replace(timerHack, timerCode, 0, 0);
        write(node, output);
    }

    public static void patchWorldStemBrowserSave(String jar, Path output) throws IOException {
        String owner = "net/minecraft/server/WorldStem";
        String storage = "net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess";
        ClassNode node = read(jar, owner + ".class");
        String descriptor = "(L" + storage + ";)V";
        node.methods.removeIf(method -> method.name.equals("gaius$saveDataTag")
                && method.desc.equals(descriptor));
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$saveDataTag",
                descriptor,
                null,
                null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        MethodNode currentWorldData = findNullable(
                node,
                "worldDataAndGenSettings",
                "()Lnet/minecraft/world/level/storage/LevelDataAndDimensions$WorldDataAndGenSettings;");
        if (currentWorldData != null) {
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    currentWorldData.name,
                    currentWorldData.desc,
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/level/storage/LevelDataAndDimensions$WorldDataAndGenSettings",
                    "data",
                    "()Lnet/minecraft/world/level/storage/WorldData;",
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    storage,
                    "saveDataTag",
                    "(Lnet/minecraft/world/level/storage/WorldData;)V",
                    false));
            // 26.2 splits world gen settings into data/minecraft/world_gen_settings.dat,
            // which the server Worker reads back when loading the level. Write it too,
            // otherwise the Worker falls back to random settings and the dimension
            // registry lacks the overworld stem.
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    "registries",
                    "()Lnet/minecraft/core/LayeredRegistryAccess;",
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/core/LayeredRegistryAccess",
                    "compositeAccess",
                    "()Lnet/minecraft/core/RegistryAccess$Frozen;",
                    false));
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            bridge.instructions.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "net/minecraft/world/level/storage/LevelResource",
                    "ROOT",
                    "Lnet/minecraft/world/level/storage/LevelResource;"));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    storage,
                    "getLevelPath",
                    "(Lnet/minecraft/world/level/storage/LevelResource;)Ljava/nio/file/Path;",
                    false));
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    currentWorldData.name,
                    currentWorldData.desc,
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/level/storage/LevelDataAndDimensions$WorldDataAndGenSettings",
                    "genSettings",
                    "()Lnet/minecraft/world/level/levelgen/WorldGenSettings;",
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "net/minecraft/world/level/storage/LevelStorageSource",
                    "writeWorldGenSettings",
                    "(Lnet/minecraft/core/RegistryAccess;Ljava/nio/file/Path;"
                            + "Lnet/minecraft/world/level/levelgen/WorldGenSettings;)V",
                    false));
        } else {
            MethodNode legacyWorldData = findNullable(
                    node,
                    "worldData",
                    "()Lnet/minecraft/world/level/storage/WorldData;");
            if (legacyWorldData == null) {
                throw new IOException("WorldStem world-data accessor was not found");
            }
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    "registries",
                    "()Lnet/minecraft/core/LayeredRegistryAccess;",
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/core/LayeredRegistryAccess",
                    "compositeAccess",
                    "()Lnet/minecraft/core/RegistryAccess$Frozen;",
                    false));
            bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    legacyWorldData.name,
                    legacyWorldData.desc,
                    false));
            bridge.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    storage,
                    "saveDataTag",
                    "(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;)V",
                    false));
        }
        bridge.instructions.add(new InsnNode(Opcodes.RETURN));
        bridge.maxStack = 4;
        bridge.maxLocals = 2;
        node.methods.add(bridge);
        writeComputeFrames(node, output);
    }

    /** Hooks vanilla world deletion so browser records are removed with the level directory. */
    public static void patchLevelStorageAccessBrowserDelete(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess";
        ClassNode node = read(jar, owner + ".class");
        MethodNode delete = find(node, "deleteLevel", "()V");
        MethodNode getLevelId = find(node, "getLevelId", "()Ljava/lang/String;");
        MethodInsnNode checkLock = null;
        for (AbstractInsnNode instruction : delete.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals("checkLock")
                    && call.desc.equals("()V")) {
                checkLock = call;
                break;
            }
        }
        if (checkLock == null) {
            throw new IOException("LevelStorageAccess.deleteLevel lock check was not found");
        }
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, getLevelId.name, getLevelId.desc, false));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserFilePersistence",
                "deleteWorld",
                "(Ljava/lang/String;)V",
                false));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, getLevelId.name, getLevelId.desc, false));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserFilePersistence",
                "prepareWorldDirectoryForVanillaDelete",
                "(Ljava/lang/String;)V",
                false));
        delete.instructions.insert(checkLock, hook);
        write(node, output);
    }

    public static void patchRegionFileVersionBrowserNoCompression(String jar, Path output) throws IOException {
        String owner = "net/minecraft/world/level/chunk/storage/RegionFileVersion";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "getSelected", "()L" + owner + ";");
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                owner,
                "VERSION_NONE",
                "L" + owner + ";"));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 0);
        write(node, output);
    }

    public static void patchStructureTemplateManagerBrowserGzip(String jar, Path outputRoot)
            throws IOException {
        String legacyOwner =
                "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager";
        String currentOwner =
                "net/minecraft/world/level/levelgen/structure/templatesystem/loader/TemplateSource";
        boolean currentLoader;
        try (ZipFile input = new ZipFile(jar)) {
            currentLoader = input.getEntry(currentOwner + ".class") != null;
        }
        String owner = currentLoader ? currentOwner : legacyOwner;
        String template = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
        ClassNode node = read(jar, owner + ".class");
        if (currentLoader) {
            MethodNode method = find(
                    node,
                    "readStructure",
                    "(Ljava/io/InputStream;)Lnet/minecraft/nbt/CompoundTag;");
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserGzip",
                    "readCompressedNbt",
                    "(Ljava/io/InputStream;)Lnet/minecraft/nbt/CompoundTag;",
                    false));
            code.add(new InsnNode(Opcodes.ARETURN));
            replace(method, code, 1, 1);
            write(node, outputRoot.resolve(owner + ".class"));
            return;
        }
        MethodNode method = find(
                node,
                "readStructure",
                "(Ljava/io/InputStream;)L" + template + ";");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGzip",
                "readCompressedNbt",
                "(Ljava/io/InputStream;)Lnet/minecraft/nbt/CompoundTag;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "readStructure",
                "(Lnet/minecraft/nbt/CompoundTag;)L" + template + ";",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2, 2);
        write(node, outputRoot.resolve(owner + ".class"));
    }
}
