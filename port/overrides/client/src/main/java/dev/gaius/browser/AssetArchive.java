package dev.gaius.browser;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * The validated browser asset archive ({@code globalThis.__gaiusVanillaAssets}: the vanilla
 * pack bytes and a {@code resource -> [offset, length]} index, loaded before the client starts)
 * with a bounded byte cache per pack.
 *
 * <p>The vanilla pack overrides ({@code VanillaPackResources} for 1.21.11 and 26.2,
 * {@code FixedPathPackResources} for 26.3) delegate their archive reads here, so the JS bridge
 * and the typed-array transfer exist once for every profile (maintainability rule D14: whole-class
 * overrides only delegate). No Minecraft types; one instance per pack instance, as the cache was.
 */
public final class AssetArchive {
    private static final int CACHE_MAX_BYTES = configuredCacheBytes();
    private static final int CACHE_MAX_ENTRY_BYTES = 2 * 1024 * 1024;

    private final Map<String, byte[]> cache = new LinkedHashMap<>(128, 0.75f, true);
    private int cacheBytes;

    /** Keep the normal cache contract, but bound retained archive copies on low-memory devices. */
    @JSBody(script = """
            const tier = globalThis.GaiusQuality && globalThis.GaiusQuality.profile
                    ? globalThis.GaiusQuality.profile.resolve().memoryTier
                    : globalThis.__gaiusMemoryTier;
            const low = !!(tier && tier.lowMemory);
            let value = low ? 8 : 32;
            try {
                const query = new URLSearchParams((globalThis.location && globalThis.location.search) || '');
                if (query.has('gaiusAssetCacheMB')) value = Number(query.get('gaiusAssetCacheMB'));
            } catch (ignored) {
                // Keep the tier default when URL parsing is unavailable during startup.
            }
            return Math.floor((Number.isFinite(value) ? Math.max(1, Math.min(32, value)) : (low ? 8 : 32))
                    * 1024 * 1024);
            """)
    private static native int configuredCacheBytes();

    /**
     * The archived bytes of {@code resource}, from the cache or copied out of the archive, or
     * null when the archive does not hold it or it is larger than one cache entry (the caller
     * then falls back to the classpath). The returned array is shared; callers must not modify it.
     */
    public byte[] read(String resource) {
        byte[] cached = cache.get(resource);
        if (cached != null) {
            return cached;
        }
        int length = externalResourceLength(resource);
        if (length < 0 || length > CACHE_MAX_ENTRY_BYTES) {
            return null;
        }
        byte[] output = new byte[length];
        if (!copyExternalResource(resource, output)) {
            return null;
        }
        cache.put(resource, output);
        cacheBytes += output.length;
        trimCache();
        return output;
    }

    private void trimCache() {
        while (cacheBytes > CACHE_MAX_BYTES && !cache.isEmpty()) {
            Iterator<Map.Entry<String, byte[]>> iterator = cache.entrySet().iterator();
            Map.Entry<String, byte[]> eldest = iterator.next();
            byte[] bytes = eldest.getValue();
            cacheBytes -= bytes == null ? 0 : bytes.length;
            iterator.remove();
        }
    }

    /** Byte length of an archived resource, or -1 when the archive does not hold it. */
    @JSBody(params = "resource", script = """
            const packs = [globalThis.__gaiusVanillaAssets, globalThis.__gaiusVanillaSounds];
            for (var i = 0; i < packs.length; i++) {
                var root = packs[i];
                if (!root || !root.bytes || !root.index ||
                    !Object.prototype.hasOwnProperty.call(root.index, resource)) continue;
                const range = root.index[resource];
                if (Array.isArray(range) && range.length === 2) return range[1] | 0;
            }
            return -1;
            """)
    public static native int externalResourceLength(String resource);

    /**
     * Copies an archived resource into {@code output}, whose length must be the resource's
     * length; false (and {@code output} unspecified) when the archive does not hold it.
     */
    public static boolean copyExternalResource(String resource, byte[] output) {
        Int8Array view = JsArrays.int8Output(output);
        boolean copied = copyExternalResourceJs(resource, view);
        if (copied) {
            JsArrays.readBack(view, output);
        }
        return copied;
    }

    @JSBody(params = {"resource", "output"}, script = """
            const packs = [globalThis.__gaiusVanillaAssets, globalThis.__gaiusVanillaSounds];
            const target = output && output.data ? output.data : output;
            if (!target) return false;
            for (var i = 0; i < packs.length; i++) {
                var root = packs[i];
                if (!root || !root.bytes || !root.index ||
                    !Object.prototype.hasOwnProperty.call(root.index, resource)) continue;
                const range = root.index[resource];
                if (!Array.isArray(range) || range.length !== 2) continue;
                const offset = range[0] | 0;
                const length = range[1] | 0;
                if (target.length !== length || offset < 0 || length < 0) return false;
                const start = root.dataOffset + offset;
                const end = start + length;
                if (start < root.dataOffset || end > root.bytes.length) return false;
                target.set(root.bytes.subarray(start, end));
                return true;
            }
            return false;
            """)
    private static native boolean copyExternalResourceJs(String resource, Int8Array output);
}
