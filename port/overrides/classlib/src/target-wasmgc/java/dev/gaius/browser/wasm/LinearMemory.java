package dev.gaius.browser.wasm;

import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * javac-visible stand-in for {@code java.nio.GaiusLinearMemory} (the class library's
 * {@code TGaiusLinearMemory}). {@code dev.gaius.tools.WasmGcAliasPatcher} renames every call of
 * this class in the WebAssembly GC overlays, so these bodies never run.
 */
public final class LinearMemory {
    private LinearMemory() {
    }

    public static ByteBuffer wrap(int address, int capacity) {
        throw new UnsupportedOperationException("WasmGcAliasPatcher did not rename this call");
    }

    public static int address(Buffer buffer) {
        throw new UnsupportedOperationException("WasmGcAliasPatcher did not rename this call");
    }
}
