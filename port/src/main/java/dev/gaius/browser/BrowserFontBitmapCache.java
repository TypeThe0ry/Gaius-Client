package dev.gaius.browser;

import com.mojang.blaze3d.platform.NativeImage;
import dev.gaius.browser.ops.VersionOps;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Shares immutable bitmap font pixels only within one resource-manager generation.
 *
 * <p>MinecraftClientPatcher's patchBitmapFontImageSharing redirects the vanilla bitmap sheet read to
 * the entry point that matches the vanilla call shape: the format-aware {@code read(Format,
 * InputStream, Object, Object)} for generations that decode with {@code NativeImage.read(Format,
 * InputStream)}, and {@code read(InputStream, Object, Object)} for generations whose decoder
 * takes no format. Decoding itself goes through {@link VersionOps#readImage}.
 */
public final class BrowserFontBitmapCache {
    private static final Map<Object, Map<Object, Entry>> GENERATIONS = new IdentityHashMap<>();
    private static final Map<NativeImage, Entry> IMAGES = new IdentityHashMap<>();

    private BrowserFontBitmapCache() {}

    public static synchronized NativeImage read(NativeImage.Format format, InputStream stream,
            Object resources, Object location) throws IOException {
        Map<Object, Entry> images = GENERATIONS.get(resources);
        Entry entry = images == null ? null : images.get(location);
        if (entry != null) {
            entry.references++;
            return entry.image;
        }
        // A failed decode must not leave a generation or a partially acquired entry behind.
        NativeImage image = VersionOps.INSTANCE.readImage(format, stream);
        if (images == null) {
            images = new HashMap<>();
            GENERATIONS.put(resources, images);
        }
        entry = new Entry(resources, location, image);
        images.put(location, entry);
        IMAGES.put(image, entry);
        return image;
    }

    /** The entry point for vanilla bitmap sheet reads that pass no format (RGBA sheets). */
    public static synchronized NativeImage read(InputStream stream, Object resources,
            Object location) throws IOException {
        return read(NativeImage.Format.RGBA, stream, resources, location);
    }

    /** 26.2 holders also own lazily allocated GPU staging data; share that owner as well. */
    public static synchronized Object shareHolder(Object candidate, NativeImage image) {
        Entry entry = IMAGES.get(image);
        if (entry == null) return candidate;
        if (entry.holder == null) entry.holder = candidate;
        return entry.holder;
    }

    /** Returns true only when the caller should close both pixels and its staging buffer. */
    public static synchronized boolean releaseOwnership(NativeImage image) {
        Entry entry = IMAGES.get(image);
        if (entry == null) return true;
        if (--entry.references > 0) return false;
        IMAGES.remove(image);
        Map<Object, Entry> images = GENERATIONS.get(entry.resources);
        images.remove(entry.location);
        if (images.isEmpty()) GENERATIONS.remove(entry.resources);
        return true;
    }

    /** Releases a failed load that never transferred its acquisition to a provider. */
    public static void release(NativeImage image) {
        if (image != null && releaseOwnership(image)) image.close();
    }

    private static final class Entry {
        final Object resources;
        final Object location;
        final NativeImage image;
        int references = 1;
        Object holder;
        Entry(Object resources, Object location, NativeImage image) {
            this.resources = resources;
            this.location = location;
            this.image = image;
        }
    }
}
