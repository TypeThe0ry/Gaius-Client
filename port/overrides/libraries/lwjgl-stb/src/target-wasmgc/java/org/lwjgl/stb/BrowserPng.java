package org.lwjgl.stb;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import dev.gaius.browser.JsArrays;
import org.lwjgl.system.BrowserMemory;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

final class BrowserPng {
    static final boolean AVAILABLE = true;
    private static final int MAX_INPUT_BYTES = 64 * 1024 * 1024;

    private BrowserPng() {
    }

    static ByteBuffer tryDecode(ByteBuffer input, IntBuffer x, IntBuffer y, IntBuffer channels,
            int desiredChannels, boolean flip) {
        if (!available() || input == null || input.remaining() > MAX_INPUT_BYTES) {
            return null;
        }
        try {
            if (input.isDirect()) {
                return decodeLinear(input, x, y, channels, desiredChannels, flip);
            }
            byte[] encoded = new byte[input.remaining()];
            input.duplicate().get(encoded);
            Int8Array result = decode(JsArrays.int8(encoded), desiredChannels);
            if (result == null || result.getLength() < 16 || result.getLength() > MAX_INPUT_BYTES + 16) {
                return null;
            }
            byte[] payload = new byte[result.getLength()];
            JsArrays.readBack(result, payload);
            return PngPixels.materialize(payload, desiredChannels, flip, x, y, channels);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * The direct-buffer path: the kernel reads the PNG straight from linear memory and its pixels
     * go straight into the output allocation (rows reversed for a flip), without the two Java
     * array round trips of the heap path (about 100 ms of the 26.3 title panorama and atlases).
     * The header checks, the output buffer and the reported sizes are those of
     * {@link PngPixels#materialize}.
     */
    private static ByteBuffer decodeLinear(ByteBuffer input, IntBuffer x, IntBuffer y, IntBuffer channels,
            int desiredChannels, boolean flip) {
        if (desiredChannels < 0 || desiredChannels > 4) {
            return null;
        }
        JsArrays.installLinearViews();
        try {
            int total = decodeLinear((int) BrowserMemory.address(input), input.remaining(), desiredChannels);
            if (total < 16 || total > MAX_INPUT_BYTES + 16) {
                return null;
            }
            int width = resultHeader(0);
            int height = resultHeader(1);
            int sourceChannels = resultHeader(2);
            int outputChannels = resultHeader(3);
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384
                    || sourceChannels < 1 || sourceChannels > 4
                    || outputChannels != (desiredChannels == 0 ? sourceChannels : desiredChannels)) {
                return null;
            }
            long size = (long) width * height * outputChannels;
            if ((long) width * height * 4 > 64 * 1024 * 1024 || size != total - 16) {
                return null;
            }
            ByteBuffer output = MemoryUtil.memAlloc((int) size);
            try {
                BrowserMemory.claimOwner(MemoryUtil.memAddress(output), 3);
                copyResult((int) MemoryUtil.memAddress(output), width * outputChannels, height, flip);
                if (x != null && x.remaining() > 0) {
                    x.put(x.position(), width);
                }
                if (y != null && y.remaining() > 0) {
                    y.put(y.position(), height);
                }
                if (channels != null && channels.remaining() > 0) {
                    channels.put(channels.position(), sourceChannels);
                }
                return output;
            } catch (Throwable throwable) {
                MemoryUtil.memFree(output);
                throw throwable;
            }
        } finally {
            clearResult();
        }
    }

    @JSBody(script = "return !!(globalThis.GaiusPngKernel && globalThis.GaiusPngKernel.enabled && globalThis.GaiusPngKernel.ready);")
    private static native boolean available();

    @JSBody(params = { "encoded", "desiredChannels" }, script =
            "var kernel = globalThis.GaiusPngKernel; if (!kernel) return null;"
            + "var result = kernel.decode(new Uint8Array(encoded.buffer, encoded.byteOffset, encoded.byteLength), desiredChannels);"
            + "return result ? new Int8Array(result.buffer, result.byteOffset, result.byteLength) : null;")
    private static native Int8Array decode(Int8Array encoded, int desiredChannels);

    /** Decodes {@code length} bytes at {@code address}; keeps the result for the calls below. */
    @JSBody(params = { "address", "length", "desiredChannels" }, script =
            "var kernel = globalThis.GaiusPngKernel; globalThis.__gaiusPngResult = null; if (!kernel) return -1;"
            + "var linear = globalThis.__gaiusLinear.refresh();"
            + "var result = kernel.decode(new Uint8Array(linear.buffer, address >>> 0, length), desiredChannels);"
            + "if (!result) return -1;"
            + "globalThis.__gaiusPngResult = new Uint8Array(result.buffer, result.byteOffset, result.byteLength);"
            + "return result.byteLength;")
    private static native int decodeLinear(int address, int length, int desiredChannels);

    @JSBody(params = "index", script =
            "var result = globalThis.__gaiusPngResult;"
            + "return new DataView(result.buffer, result.byteOffset, 16).getInt32(index * 4, true);")
    private static native int resultHeader(int index);

    /** Writes the kept pixels at {@code address}: {@code height} rows of {@code stride} bytes. */
    @JSBody(params = { "address", "stride", "height", "flip" }, script =
            "var result = globalThis.__gaiusPngResult; var target = globalThis.__gaiusLinear.refresh().u8;"
            + "var at = address >>> 0;"
            + "if (!flip) { target.set(result.subarray(16, 16 + stride * height), at); return; }"
            + "for (var row = height - 1; row >= 0; row--, at += stride) {"
            + "  target.set(result.subarray(16 + row * stride, 16 + (row + 1) * stride), at);"
            + "}")
    private static native void copyResult(int address, int stride, int height, boolean flip);

    @JSBody(script = "globalThis.__gaiusPngResult = null;")
    private static native void clearResult();
}
