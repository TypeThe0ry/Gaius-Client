package dev.gaius.browser.quality;

import org.teavm.jso.JSBody;

/**
 * Initial capacity of the hot uniform storages ({@code DynamicGpuData}'s "Dynamic Transforms
 * UBO" and "Chunk Sections UBO", or {@code DynamicUniforms}' two storages), chosen at run time
 * on the WebAssembly GC build. {@code MinecraftClientPatcher.patchDynamicGpuDataBrowserInitialCapacity}
 * (and its alternate shape) replaces the vanilla initial capacity 2 with a call to {@link #capacity};
 * the JavaScript build keeps the fixed 32767-entry slab.
 *
 * <p>The browser slab exists so that the first multiplayer terrain burst does not reallocate the
 * storage over and over on the render thread. It also made every slab 8-16 MiB, and on ANGLE's
 * D3D11 backend a write into a buffer that big while earlier draws still read it costs a copy of
 * the whole buffer; BrowserOpenGL's buffer layer now keeps such buffers in 256 KiB segments, so
 * the slab size no longer matters on the GPU side. {@code ?gaiusUboCap=} selects the capacity for
 * A/B runs: a number (1 to 65536 entries, rounded up to a power of two by the storage), or
 * {@code vanilla} for the vanilla initial capacity. Without it the browser slab stays.
 */
public final class BrowserGpuDataCapacity {
    private static final int MAX_CAPACITY = 65536;
    /** Parsed {@code ?gaiusUboCap=}: 0 not read yet, -1 absent or invalid, -2 vanilla. */
    private static int requested;

    private BrowserGpuDataCapacity() {
    }

    /**
     * Capacity for the storage {@code label}: {@code browserCapacity} unless
     * {@code ?gaiusUboCap=} asks for another one.
     */
    public static int capacity(String label, int vanillaCapacity, int browserCapacity) {
        if (requested == 0) {
            int value = queryRequestedCapacity();
            requested = value == -2 || (value >= 1 && value <= MAX_CAPACITY) ? value : -1;
        }
        int capacity = requested == -2 ? vanillaCapacity : requested > 0 ? requested : browserCapacity;
        reportCapacity(label, capacity);
        return capacity;
    }

    @JSBody(script = """
            try {
              var search = globalThis.location ? String(globalThis.location.search || '') : '';
              var value = new URLSearchParams(search).get('gaiusUboCap');
              if (value === null || value === '') return -1;
              if (String(value).toLowerCase() === 'vanilla') return -2;
              var number = Number(value);
              return Number.isInteger(number) && number >= 1 && number <= 65536 ? number : -1;
            } catch (e) {
              return -1;
            }
            """)
    private static native int queryRequestedCapacity();

    @JSBody(params = {"label", "capacity"}, script = """
            try {
              var stats = globalThis.__gaiusGLStats || (globalThis.__gaiusGLStats = {});
              var capacities = stats.gpuDataCapacity || (stats.gpuDataCapacity = {});
              capacities[String(label)] = capacity | 0;
            } catch (e) {
              // Telemetry only.
            }
            """)
    private static native void reportCapacity(String label, int capacity);
}
