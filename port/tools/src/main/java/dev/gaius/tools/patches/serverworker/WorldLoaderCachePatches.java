package dev.gaius.tools.patches.serverworker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The server-worker domain of the MinecraftServerWorkerPatcher step: the world loader cache
 * ({@code BrowserWorldLoadCache}). It routes the three calls of {@code WorldLoader.load} that
 * depend only on the selected data packs (the tag lists of the static registries, the two
 * {@code RegistryDataLoader.load} calls and {@code ReloadableServerResources.loadResources}) through the cache, which answers them from a record made
 * while an idle Worker waited for its world, and otherwise calls vanilla with the same arguments.
 * The calls keep their descriptors, so no frame, stack or local changes.
 */
public final class WorldLoaderCachePatches {
    private WorldLoaderCachePatches() {
    }

    private static final String WORLD_LOADER = "net/minecraft/server/WorldLoader";

    private static final String TAG_LOADER = "net/minecraft/tags/TagLoader";

    private static final String TAGS_DESCRIPTOR =
            "(Lnet/minecraft/server/packs/resources/ResourceManager;"
                    + "Lnet/minecraft/core/RegistryAccess;)Ljava/util/List;";

    private static final String REGISTRY_LOADER = "net/minecraft/resources/RegistryDataLoader";

    private static final String LOAD_DESCRIPTOR =
            "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;"
                    + "Ljava/util/List;Ljava/util/concurrent/Executor;)"
                    + "Ljava/util/concurrent/CompletableFuture;";

    private static final String RESOURCES = "net/minecraft/server/ReloadableServerResources";

    private static final String RESOURCES_DESCRIPTOR =
            "(Lnet/minecraft/server/packs/resources/ResourceManager;"
                    + "Lnet/minecraft/core/LayeredRegistryAccess;Ljava/util/List;"
                    + "Lnet/minecraft/world/flag/FeatureFlagSet;"
                    + "Lnet/minecraft/commands/Commands$CommandSelection;"
                    + "Lnet/minecraft/server/permissions/PermissionSet;"
                    + "Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)"
                    + "Ljava/util/concurrent/CompletableFuture;";

    private static final String CACHE = "dev/gaius/browser/BrowserWorldLoadCache";

    public static void patch(Path jar, Path outputRoot) throws IOException {
        ClassNode node = read(jar, WORLD_LOADER + ".class");
        int tags = 0;
        int loads = 0;
        int resources = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC) {
                    continue;
                }
                if (call.owner.equals(TAG_LOADER)
                        && call.name.equals("loadTagsForExistingRegistries")
                        && call.desc.equals(TAGS_DESCRIPTOR)) {
                    call.owner = CACHE;
                    call.name = "loadTags";
                    tags++;
                } else if (call.owner.equals(REGISTRY_LOADER)
                        && call.name.equals("load")
                        && call.desc.equals(LOAD_DESCRIPTOR)) {
                    call.owner = CACHE;
                    call.name = "loadRegistries";
                    loads++;
                } else if (call.owner.equals(RESOURCES)
                        && call.name.equals("loadResources")
                        && call.desc.equals(RESOURCES_DESCRIPTOR)) {
                    call.owner = CACHE;
                    call.name = "loadResources";
                    resources++;
                }
            }
        }
        // Exactly the shape the cache was written for: one tag list, the worldgen and dimension
        // registry loads and the reloadable resources. Anything else is a different loader;
        // leave vanilla alone.
        if (tags != 1 || loads != 2 || resources != 1) {
            throw new IllegalStateException("WorldLoader cache patch points changed: tags="
                    + tags + ", registryLoads=" + loads + ", resources=" + resources);
        }
        write(node, outputRoot.resolve(WORLD_LOADER + ".class"));
        System.out.println("Routed the data pack stages of WorldLoader.load through the world load cache");
    }

    private static ClassNode read(Path jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found in " + jarPath);
            }
            try (var stream = jar.getInputStream(entry)) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return dev.gaius.tools.HierarchyClassWriter.source(node, jarPath.toString());
    }

    private static void write(ClassNode node, Path output) throws IOException {
        try (var writer = dev.gaius.tools.HierarchyClassWriter.create(node, output)) {
            node.accept(writer);
            Files.createDirectories(output.getParent());
            Files.write(output, writer.toByteArray());
        }
    }
}
