package dev.gaius.tools.atlasprebake;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.zip.GZIPInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

/**
 * Runs the vanilla atlas classes of the profile's client jar on the JVM: the atlas list
 * ({@code AtlasManager.KNOWN_ATLASES}), the sprite sources and loaders, {@code SpriteLoader.stitch}
 * (with the two options it reads) and the sprite mip chains, over the browser's vanilla asset pack
 * (GAIUSVP1). Nothing here re-implements a vanilla step; it only calls them.
 */
final class VanillaAtlases {
    static final Executor DIRECT = Runnable::run;

    /** One {@code AtlasManager.AtlasConfig}, read by record component order. */
    record Atlas(Identifier textureId, Identifier definition, boolean mipmaps, Set<?> metadata) {
    }

    private VanillaAtlases() {
    }

    /** The pack's textures, atlas definitions and metadata under {@code directory} (once per pack hash). */
    static String extract(Path pack, Path directory) throws IOException {
        String digest = sha256(Files.readAllBytes(pack));
        Path marker = directory.resolve(".gaius-pack-sha256");
        if (Files.isRegularFile(marker) && Files.readString(marker).trim().equals(digest)) {
            return digest;
        }
        if (Files.exists(directory)) {
            try (var walk = Files.walk(directory)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
        if (pack.getFileName().toString().endsWith(".jar")) {
            // A client jar (gate runs of a profile without a built pack): its own assets.
            try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(pack.toFile())) {
                for (var entry : java.util.Collections.list(jar.entries())) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.matches("assets/[a-z0-9_.-]+/(textures|atlases)/.*")
                            || name.contains("..")) {
                        continue;
                    }
                    Path target = directory.resolve(name);
                    Files.createDirectories(target.getParent());
                    try (var stream = jar.getInputStream(entry)) {
                        Files.write(target, stream.readAllBytes());
                    }
                }
            }
            Files.writeString(marker, digest + "\n");
            return digest;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(pack), 1 << 16), 1 << 16))) {
            byte[] magic = new byte[8];
            in.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("GAIUSVP1")) {
                throw new IOException(pack + " is not a GAIUSVP1 pack");
            }
            int length = Integer.reverseBytes(in.readInt());
            byte[] indexBytes = new byte[length];
            in.readFully(indexBytes);
            JsonObject index = JsonParser.parseString(new String(indexBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            List<Map.Entry<String, long[]>> entries = new ArrayList<>();
            for (var entry : index.entrySet()) {
                var range = entry.getValue().getAsJsonArray();
                entries.add(Map.entry(entry.getKey(), new long[] {range.get(0).getAsLong(), range.get(1).getAsLong()}));
            }
            entries.sort(Comparator.comparingLong(entry -> entry.getValue()[0]));
            long position = 0;
            for (var entry : entries) {
                long offset = entry.getValue()[0];
                int size = Math.toIntExact(entry.getValue()[1]);
                if (offset < position) {
                    throw new IOException("overlapping pack entries at " + entry.getKey());
                }
                in.skipNBytes(offset - position);
                byte[] content = new byte[size];
                in.readFully(content);
                position = offset + size;
                String name = entry.getKey();
                if (!name.matches("assets/[a-z0-9_.-]+/(textures|atlases)/.*") || name.contains("..")) {
                    continue;
                }
                Path target = directory.resolve(name);
                Files.createDirectories(target.getParent());
                Files.write(target, content);
            }
        }
        Files.writeString(marker, digest + "\n");
        return digest;
    }

    static MultiPackResourceManager resources(Path directory) {
        PackResources pack = new PathPackResources(
                new PackLocationInfo("vanilla", Component.literal("vanilla"), PackSource.BUILT_IN, Optional.empty()),
                directory);
        return new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack));
    }

    static void bootstrap() throws ReflectiveOperationException {
        Class.forName("net.minecraft.client.renderer.texture.atlas.SpriteSources").getMethod("bootstrap").invoke(null);
    }

    static List<Atlas> atlases() throws ReflectiveOperationException {
        Field field = AtlasManager.class.getDeclaredField("KNOWN_ATLASES");
        field.setAccessible(true);
        List<Atlas> atlases = new ArrayList<>();
        for (Object config : (List<?>) field.get(null)) {
            RecordComponent[] components = config.getClass().getRecordComponents();
            List<Identifier> ids = new ArrayList<>();
            Boolean mipmaps = null;
            Set<?> metadata = null;
            for (RecordComponent component : components) {
                Object value = component.getAccessor().invoke(config);
                if (value instanceof Identifier id) {
                    ids.add(id);
                } else if (value instanceof Boolean flag) {
                    mipmaps = flag;
                } else if (value instanceof Set<?> set) {
                    metadata = set;
                }
            }
            if (ids.size() != 2 || mipmaps == null || metadata == null) {
                throw new IllegalStateException("unexpected AtlasConfig shape: " + config);
            }
            atlases.add(new Atlas(ids.get(0), ids.get(1), mipmaps, metadata));
        }
        return atlases;
    }

    /** The sprite contents of one atlas in vanilla's loader order (fresh images on every call). */
    @SuppressWarnings("unchecked")
    static List<SpriteContents> sprites(MultiPackResourceManager resources, Atlas atlas) throws ReflectiveOperationException {
        List<SpriteSource.Loader> loaders = SpriteSourceList.load(resources, atlas.definition()).list(resources);
        Method run = SpriteLoader.class.getDeclaredMethod("runSpriteSuppliers", SpriteResourceLoader.class, List.class,
                Executor.class);
        run.setAccessible(true);
        SpriteResourceLoader loader = SpriteResourceLoader.create((Set) atlas.metadata());
        return ((CompletableFuture<List<SpriteContents>>) run.invoke(null, loader, loaders, DIRECT)).join();
    }

    /** Points {@code Minecraft.getInstance().options} at the two options {@code SpriteLoader.stitch} reads. */
    static void options(TextureFilteringMethod filtering, int anisotropyBit) throws ReflectiveOperationException {
        sun.misc.Unsafe unsafe = unsafe();
        Object minecraft = unsafe.allocateInstance(Minecraft.class);
        Object options = unsafe.allocateInstance(Options.class);
        Object filteringOption = unsafe.allocateInstance(OptionInstance.class);
        Object anisotropyOption = unsafe.allocateInstance(OptionInstance.class);
        Field value = OptionInstance.class.getDeclaredField("value");
        value.setAccessible(true);
        value.set(filteringOption, filtering);
        value.set(anisotropyOption, anisotropyBit);
        set(Options.class, options, "textureFiltering", filteringOption);
        set(Options.class, options, "maxAnisotropyBit", anisotropyOption);
        set(Minecraft.class, minecraft, "options", options);
        Field instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, minecraft);
    }

    /** Vanilla {@code SpriteLoader.stitch}: layout, sprites and (with {@link #DIRECT}) the mip chains. */
    static SpriteLoader.Preparations stitch(Atlas atlas, int maxTextureSize, List<SpriteContents> sprites, int mipLevel,
            Executor executor) throws ReflectiveOperationException {
        SpriteLoader loader = new SpriteLoader(atlas.textureId(), maxTextureSize);
        Method stitch = SpriteLoader.class.getDeclaredMethod("stitch", List.class, int.class, Executor.class);
        stitch.setAccessible(true);
        return (SpriteLoader.Preparations) stitch.invoke(loader, sprites, atlas.mipmaps() ? mipLevel : 0, executor);
    }

    static Object field(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static NativeImage copy(NativeImage image) {
        NativeImage copy = new NativeImage(image.format(), image.getWidth(), image.getHeight(), false);
        copy.copyFrom(image);
        return copy;
    }

    static byte[] bytes(NativeImage image) {
        int size = image.getWidth() * image.getHeight() * image.format().components();
        byte[] bytes = new byte[size];
        org.lwjgl.system.MemoryUtil.memByteBuffer(image.getPointer(), size).get(bytes);
        return bytes;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
