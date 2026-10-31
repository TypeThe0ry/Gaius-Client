package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.teavm.interop.Import;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Imports of the WebGPU glue ({@code port/web/runtime/webgpu/gpu-glue.js}, global
 * {@code GaiusWebGpu}).
 *
 * <p>Every GPU object is an integer handle into the glue's table, sizes and offsets are ints
 * (no {@code long} crosses the boundary). WasmGC data uploads use a linear-memory address and
 * length import; non-linear buffers use the JS fallback. None of the direct imports captures a
 * Java object. Only the core calls the glue; seams go through the core API.</p>
 */
final class WebGpuGlue {
    private WebGpuGlue() {
    }

    // ---- device ------------------------------------------------------------------------------

    @JSBody(script = """
            var g = globalThis.GaiusWebGpu;
            return g && typeof g.state === "function" ? g.state() : 0;
            """)
    static native int state();

    /** Whether the glue still has a live device and queue (false after device.lost resolves). */
    @JSBody(script = "var g = globalThis.GaiusWebGpu; return !!(g && typeof g.deviceUsable === 'function' && g.deviceUsable());")
    static native boolean deviceUsable();

    @JSBody(script = """
            var g = globalThis.GaiusWebGpu;
            return g && g.failure ? String(g.failure) : "WebGPU glue not loaded or not initialised";
            """)
    static native String failure();

    @JSBody(params = "name", script = "return globalThis.GaiusWebGpu.hasFeature(name);")
    static native boolean hasFeature(String name);

    @JSBody(params = "name", script = "return globalThis.GaiusWebGpu.hasCapability(name);")
    static native boolean hasCapability(String name);

    @JSBody(params = "name", script = "return globalThis.GaiusWebGpu.limit(name);")
    static native double limit(String name);

    @JSBody(params = "key", script = "return globalThis.GaiusWebGpu.adapterString(key);")
    static native String adapterString(String key);

    @JSBody(script = "return globalThis.GaiusWebGpu.takeMessage();")
    static native String takeMessage();

    @JSBody(params = "handle", script = "globalThis.GaiusWebGpu.releaseHandle(handle);")
    static native void releaseHandle(int handle);

    @JSBody(params = "key", script = "return globalThis.GaiusWebGpu.stat(key);")
    static native double stat(String key);

    /** Adds {@code delta} to the glue's counter {@code key} (telemetry of the core's downgrades). */
    @JSBody(params = { "key", "delta" }, script = "globalThis.GaiusWebGpu.count(key, delta);")
    static native void count(String key, int delta);

    // ---- buffers -----------------------------------------------------------------------------

    @JSBody(params = { "size", "usage", "label" },
            script = "return globalThis.GaiusWebGpu.createBuffer(size, usage, label);")
    static native int createBuffer(int size, int usage, String label);

    @JSBody(params = "handle", script = "globalThis.GaiusWebGpu.destroyBuffer(handle);")
    static native void destroyBuffer(int handle);

    @Import(module = "gaiusGpu", name = "writeBuffer")
    private static native void writeBufferImport(int handle, int offset, int address, int size);

    @JSBody(params = { "handle", "offset", "data" },
            script = "globalThis.GaiusWebGpu.writeBuffer(handle, offset, data);")
    private static native void writeBufferFallback(int handle, int offset, Int8Array data);

    /**
     * Queues a write of {@code data} from its position to its limit. The length must be a
     * multiple of four.
     */
    static void writeBuffer(int handle, int offset, ByteBuffer data) {
        Int8Array bytes = view(data);
        int address = linearAddress(bytes, data);
        if (address != 0) {
            writeBufferImport(handle, offset, address, data.remaining());
        } else {
            writeBufferFallback(handle, offset, bytes);
        }
    }

    @Import(module = "gaiusGpu", name = "upload")
    private static native void uploadImport(int handle, int offset, int address, int size);

    @JSBody(params = { "handle", "offset", "data" },
            script = "globalThis.GaiusWebGpu.upload(handle, offset, data);")
    private static native void uploadFallback(int handle, int offset, Int8Array data);

    /**
     * Uploads {@code data} (position to limit, a multiple of four bytes) ahead of the commands of
     * the next submit, through the glue's mapped staging ring (or {@code queue.writeBuffer} when
     * the ring is full).
     */
    static void upload(int handle, int offset, ByteBuffer data) {
        Int8Array bytes = view(data);
        int address = linearAddress(bytes, data);
        if (address != 0) {
            uploadImport(handle, offset, address, data.remaining());
        } else {
            uploadFallback(handle, offset, bytes);
        }
    }

    @JSBody(params = { "blockSize", "dedicatedMax", "cap" },
            script = "globalThis.GaiusWebGpu.ringConfigure(blockSize, dedicatedMax, cap);")
    static native void ringConfigure(int blockSize, int dedicatedMax, int cap);

    @Import(module = "gaiusGpu", name = "writeOrdered")
    private static native int writeOrderedImport(int handle, int offset, int address, int dataLength, int size);

    @JSBody(params = { "handle", "offset", "data", "size" },
            script = "return globalThis.GaiusWebGpu.writeOrdered(handle, offset, data, size);")
    private static native int writeOrderedFallback(int handle, int offset, Int8Array data, int size);

    /**
     * Copies {@code data} (position to limit) into the staging ring and encodes its copy into
     * {@code [offset, offset + size)} of the buffer on the frame's encoder; {@code size} is the
     * length rounded up to four. Returns {@code false} when the ring refuses the write.
     */
    static boolean writeOrdered(int handle, int offset, ByteBuffer data, int size) {
        Int8Array bytes = view(data);
        int address = linearAddress(bytes, data);
        return (address != 0
                ? writeOrderedImport(handle, offset, address, data.remaining(), size)
                : writeOrderedFallback(handle, offset, bytes, size)) != 0;
    }

    @Import(module = "gaiusGpu", name = "copyBufferToBuffer")
    static native void copyBufferToBuffer(int source, int sourceOffset, int target, int targetOffset, int size);

    // ---- textures and samplers ---------------------------------------------------------------

    @JSBody(params = { "width", "height", "layers", "mips", "format", "usage", "label" },
            script = "return globalThis.GaiusWebGpu.createTexture(width, height, layers, mips, format, usage, label);")
    static native int createTexture(int width, int height, int layers, int mips, String format, int usage, String label);

    @JSBody(params = "handle", script = "globalThis.GaiusWebGpu.destroyTexture(handle);")
    static native void destroyTexture(int handle);

    @JSBody(params = { "texture", "baseMip", "mipCount", "cube" },
            script = "return globalThis.GaiusWebGpu.createView(texture, baseMip, mipCount, cube);")
    static native int createView(int texture, int baseMip, int mipCount, boolean cube);

    @JSBody(params = { "texture", "mip", "layer", "x", "y", "width", "height", "bytesPerRow", "data" },
            script = "globalThis.GaiusWebGpu.writeTexture(texture, mip, layer, x, y, width, height, bytesPerRow, data);")
    private static native void writeTexture(int texture, int mip, int layer, int x, int y, int width, int height,
            int bytesPerRow, Int8Array data);

    static void writeTexture(int texture, int mip, int layer, int x, int y, int width, int height, int bytesPerRow,
            ByteBuffer data) {
        writeTexture(texture, mip, layer, x, y, width, height, bytesPerRow, view(data));
    }

    @JSBody(params = { "source", "target", "mip", "destX", "destY", "sourceX", "sourceY", "width", "height" },
            script = "globalThis.GaiusWebGpu.copyTextureToTexture(source, target, mip, destX, destY, sourceX, sourceY, width, height);")
    static native void copyTextureToTexture(int source, int target, int mip, int destX, int destY, int sourceX,
            int sourceY, int width, int height);

    @JSBody(params = { "buffer", "offset", "bytesPerRow", "rowsPerImage", "texture", "mip", "layer", "x", "y",
        "width", "height" },
            script = "globalThis.GaiusWebGpu.copyBufferToTexture(buffer, offset, bytesPerRow, rowsPerImage, texture, mip, layer, x, y, width, height);")
    static native void copyBufferToTexture(int buffer, int offset, int bytesPerRow, int rowsPerImage, int texture,
            int mip, int layer, int x, int y, int width, int height);

    // ---- readback and partial clears --------------------------------------------------------

    @JSBody(params = { "texture", "mip", "layer", "x", "y", "width", "height", "texelBytes" },
            script = "return globalThis.GaiusWebGpu.readbackTexture(texture, mip, layer, x, y, width, height, texelBytes);")
    static native int readbackTexture(int texture, int mip, int layer, int x, int y, int width, int height,
            int texelBytes);

    @JSBody(script = "return globalThis.GaiusWebGpu.readbackPoll();")
    static native int readbackPoll();

    @JSBody(params = { "id", "target" }, script = "return globalThis.GaiusWebGpu.readbackFetch(id, target);")
    private static native int readbackFetch(int id, Int8Array target);

    /**
     * Copies a completed readback into {@code target} (position to limit); returns the bytes
     * copied or -1 when the readback failed.
     */
    static int readbackFetch(int id, ByteBuffer target) {
        return readbackFetch(id, view(target));
    }

    @JSBody(params = { "texture", "mip", "layer", "x", "y", "width", "height", "texelBytes", "buffer", "offset" },
            script = "globalThis.GaiusWebGpu.copyTextureToBufferRows(texture, mip, layer, x, y, width, height, texelBytes, "
                    + "buffer, offset);")
    static native void copyTextureToBufferRows(int texture, int mip, int layer, int x, int y, int width, int height, int texelBytes,
            int buffer, int offset);

    @JSBody(params = { "colorView", "colorFormat", "depthView", "depthFormat", "hasStencil", "r", "g", "b", "a",
        "depth", "x", "y", "width", "height" },
            script = "globalThis.GaiusWebGpu.clearRegion(colorView, colorFormat, depthView, depthFormat, hasStencil, "
                    + "r, g, b, a, depth, x, y, width, height);")
    static native void clearRegion(int colorView, String colorFormat, int depthView, String depthFormat,
            boolean hasStencil, float r, float g, float b, float a, double depth, int x, int y, int width, int height);

    @JSBody(params = { "addressU", "addressV", "minFilter", "magFilter", "mipmapFilter", "anisotropy", "maxLod" },
            script = "return globalThis.GaiusWebGpu.createSampler(addressU, addressV, minFilter, magFilter, mipmapFilter, anisotropy, maxLod);")
    static native int createSampler(String addressU, String addressV, String minFilter, String magFilter,
            String mipmapFilter, int anisotropy, double maxLod);

    // ---- shaders, pipelines, bind groups -----------------------------------------------------

    @JSBody(params = { "code", "label" },
            script = "return globalThis.GaiusWebGpu.createShaderModule(code, label);")
    static native int createShaderModule(String code, String label);

    @JSBody(params = { "json", "depthFormat" },
            script = "return globalThis.GaiusWebGpu.createPipeline(json, depthFormat);")
    static native int createPipeline(String json, String depthFormat);

    @Import(module = "gaiusGpu", name = "bindBuffer")
    static native void bindBuffer(int binding, int buffer, int offset, int size);

    /** {@code samplerBinding} -1: the layout has no sampler for this texture. */
    @Import(module = "gaiusGpu", name = "bindTexture")
    static native void bindTexture(int binding, int view, int samplerBinding, int sampler);

    @Import(module = "gaiusGpu", name = "createBindGroup")
    static native int createBindGroup(int pipeline, int group);

    // ---- render passes (hot path) ------------------------------------------------------------

    @Import(module = "gaiusGpu", name = "passColor")
    private static native void passColorImport(int index, int view, int clear, float r, float g, float b, float a);

    static void passColor(int index, int view, boolean clear, float r, float g, float b, float a) {
        passColorImport(index, view, clear ? 1 : 0, r, g, b, a);
    }

    @Import(module = "gaiusGpu", name = "passDepth")
    private static native void passDepthImport(int view, int clear, double depth, int hasStencil);

    static void passDepth(int view, boolean clear, double depth, boolean hasStencil) {
        passDepthImport(view, clear ? 1 : 0, depth, hasStencil ? 1 : 0);
    }

    @Import(module = "gaiusGpu", name = "passBegin")
    static native void passBegin(int colorCount);

    @Import(module = "gaiusGpu", name = "passEnd")
    static native void passEnd();

    @Import(module = "gaiusGpu", name = "passSuspend")
    static native void passSuspend();

    @Import(module = "gaiusGpu", name = "passResume")
    static native void passResume();

    @Import(module = "gaiusGpu", name = "setPipeline")
    static native void setPipeline(int pipeline);

    @Import(module = "gaiusGpu", name = "setVertexBuffer")
    static native void setVertexBuffer(int slot, int buffer, int offset, int size);

    @Import(module = "gaiusGpu", name = "setIndexBuffer")
    private static native void setIndexBufferImport(int buffer, int wide, int offset);

    static void setIndexBuffer(int buffer, boolean wide, int offset) {
        setIndexBufferImport(buffer, wide ? 1 : 0, offset);
    }

    @Import(module = "gaiusGpu", name = "setBindGroup")
    static native void setBindGroup(int group, int bindGroup, int count, int o0, int o1, int o2, int o3);

    @Import(module = "gaiusGpu", name = "stageOffset")
    static native void stageOffset(int index, int value);

    @Import(module = "gaiusGpu", name = "setBindGroupStaged")
    static native void setBindGroupStaged(int group, int bindGroup, int count);

    @Import(module = "gaiusGpu", name = "setScissor")
    static native void setScissor(int x, int y, int width, int height);

    @Import(module = "gaiusGpu", name = "draw")
    static native void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);

    @Import(module = "gaiusGpu", name = "drawIndexed")
    static native void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance);

    @Import(module = "gaiusGpu", name = "drawIndexedWithOffsets")
    static native void drawIndexedWithOffsets(int bindGroup, int count, int o0, int o1, int o2, int o3, int indexCount,
            int instanceCount, int firstIndex, int baseVertex, int firstInstance);

    @Import(module = "gaiusGpu", name = "drawIndexedIndirect")
    static native void drawIndexedIndirect(int buffer, int offset, int drawCount);

    @Import(module = "gaiusGpu", name = "drawIndexedIndirectWithOffsets")
    static native void drawIndexedIndirectWithOffsets(int bindGroup, int count, int o0, int o1, int o2, int o3,
            int buffer, int offset, int drawCount);

    @Import(module = "gaiusGpu", name = "drawWithOffsets")
    static native void drawWithOffsets(int bindGroup, int count, int o0, int o1, int o2, int o3, int vertexCount,
            int instanceCount, int firstVertex, int firstInstance);

    @Import(module = "gaiusGpu", name = "submit")
    static native void submit();

    @Import(module = "gaiusGpu", name = "createFence")
    static native int createFence();

    @Import(module = "gaiusGpu", name = "fenceStatus")
    static native int fenceStatus(int id);

    @Import(module = "gaiusGpu", name = "closeFence")
    static native void closeFence(int id);

    // ---- surface -----------------------------------------------------------------------------

    @Import(module = "gaiusGpu", name = "configureSurface")
    private static native int configureSurfaceImport(int width, int height);

    static boolean configureSurface(int width, int height) {
        return configureSurfaceImport(width, height) != 0;
    }

    @Import(module = "gaiusGpu", name = "acquireSurface")
    private static native int acquireSurfaceImport();

    static boolean acquireSurface() {
        return acquireSurfaceImport() != 0;
    }

    @Import(module = "gaiusGpu", name = "blitToSurface")
    static native void blitToSurface(int view);

    @Import(module = "gaiusGpu", name = "present")
    static native void present();

    // ---- shader translation (D11) ------------------------------------------------------------

    @JSBody(script = "var g = globalThis.GaiusWebGpu; return !!(g && g.hasTranslator());")
    static native boolean hasTranslator();

    @JSBody(params = { "spirv", "texelBuffers", "flags" },
            script = "return globalThis.GaiusWebGpu.translateSpirv(spirv, texelBuffers, flags);")
    private static native int translateSpirv(Int8Array spirv, String texelBuffers, int flags);

    /**
     * Translates the SPIR-V module in {@code spirv} (position to limit) synchronously. Returns the
     * number of binding records, or -1 ({@link #translateError()}).
     */
    static int translateSpirv(ByteBuffer spirv, String texelBuffers, int flags) {
        return translateSpirv(view(spirv), texelBuffers, flags);
    }

    @JSBody(script = "return globalThis.GaiusWebGpu.translatedWgsl();")
    static native String translatedWgsl();

    @JSBody(script = "return globalThis.GaiusWebGpu.translatedStage();")
    static native int translatedStage();

    @JSBody(params = "index", script = "return globalThis.GaiusWebGpu.translatedWord(index);")
    static native int translatedWord(int index);

    @JSBody(script = "return String(globalThis.GaiusWebGpu.translateError);")
    static native String translateError();

    @JSBody(params = { "spirv", "texelBuffers", "flags" },
            script = "return globalThis.GaiusWebGpu.prefetchSpirv(spirv, texelBuffers, flags);")
    private static native boolean prefetchSpirv(Int8Array spirv, String texelBuffers, int flags);

    /** Hands a module to the glue's translator Worker (copied there); false without a Worker. */
    static boolean prefetchSpirv(ByteBuffer spirv, String texelBuffers, int flags) {
        ByteBuffer source = spirv;
        if (!source.isDirect()) {
            source = ByteBuffer.allocateDirect(spirv.remaining()).order(ByteOrder.nativeOrder());
            source.put(spirv.duplicate()).flip();
        }
        return prefetchSpirv(view(source), texelBuffers, flags);
    }

    // ---- byte views --------------------------------------------------------------------------

    @JSBody(params = { "target", "offset", "source" }, script = "target.set(source, offset);")
    private static native void copyBytes(Int8Array target, int offset, Int8Array source);

    /**
     * Copies {@code source} (position to limit) into {@code target} at an absolute offset of its
     * backing view (position and limit of {@code target} are ignored).
     */
    static void copyBytes(ByteBuffer target, int offset, ByteBuffer source) {
        copyBytes(Int8Array.fromJavaBuffer(target), offset, view(source));
    }

    /**
     * A view of {@code data} from its position to its limit. TeaVM exports a buffer's whole
     * backing view and ignores position and limit, so a non-trivial window is sliced first.
     */
    static Int8Array view(ByteBuffer data) {
        if (data.position() == 0 && data.limit() == data.capacity()) {
            return Int8Array.fromJavaBuffer(data);
        }
        return Int8Array.fromJavaBuffer(data.slice());
    }

    /**
     * The linear-memory byte address of {@code bytes} (the {@link #view} of {@code data}), or zero
     * when the buffer does not live in the module's linear memory (a heap buffer) or is empty.
     * {@code Int8Array.fromJavaBuffer} is TeaVM's Java-side conversion, not a JavaScript
     * function, so the view is made here and only the view crosses into the script.
     */
    private static int linearAddress(Int8Array bytes, ByteBuffer data) {
        if (data.remaining() == 0) {
            return 0;
        }
        return linearAddressJs(bytes);
    }

    @JSBody(params = "view", script = "var m = globalThis.__gaiusWasmMemory;"
            + "return view && m && view.buffer === m.buffer ? (view.byteOffset | 0) : 0;")
    private static native int linearAddressJs(Int8Array view);
}
