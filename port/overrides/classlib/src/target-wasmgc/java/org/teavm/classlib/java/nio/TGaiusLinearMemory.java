package org.teavm.classlib.java.nio;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import org.teavm.classlib.java.nio.file.TAddressBasedBuffer;
import org.teavm.interop.Address;

/**
 * {@code java.nio.GaiusLinearMemory} of the WebAssembly GC class library: direct-buffer views
 * of TeaVM linear memory for the LWJGL memory backend (design D6).
 *
 * <p>Overlay code calls the javac-visible stand-in {@code dev.gaius.browser.wasm.LinearMemory};
 * {@code dev.gaius.tools.WasmGcAliasPatcher} renames those calls to this class.
 */
public final class TGaiusLinearMemory {
    private TGaiusLinearMemory() {
    }

    /**
     * A direct buffer over {@code [address, address + capacity)} that does not own the memory:
     * it is never released by the buffer finalization registry, the allocator frees it.
     */
    public static ByteBuffer wrap(int address, int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Negative capacity: " + capacity);
        }
        TByteBufferWasmGC result = new TByteBufferWasmGC(null, null, 0, null,
                Address.fromInt(address), capacity, false);
        result.limit = capacity;
        return (ByteBuffer) (Object) result;
    }

    /**
     * The linear-memory address of element 0 of a direct buffer (allocateDirect, a
     * {@link #wrap} view or a view derived from one), or 0 for a heap buffer.
     */
    public static int address(Buffer buffer) {
        Object value = buffer;
        if (value instanceof TAddressBasedBuffer based) {
            return based.getDataAddress().toInt();
        }
        return 0;
    }
}
