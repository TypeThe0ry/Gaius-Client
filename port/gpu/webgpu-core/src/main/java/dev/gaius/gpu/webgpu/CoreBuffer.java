package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;

/**
 * A WebGPU buffer. WebGPU has no synchronous mapping, so a write mapping hands out CPU scratch
 * memory whose contents are written, in command order, when the mapping is committed
 * ({@link CoreEncoder#writeBuffer}).
 *
 * <p>A seam wraps a buffer in its SPI's buffer type and may keep that wrapper in
 * {@link #setFacade(Object)}, so core results (transient slices) map back to SPI objects without
 * a lookup.</p>
 */
public final class CoreBuffer {
    private final CoreDevice device;
    final int handle;
    private final long size;
    private final int usage;
    private final boolean transientBlock;
    private boolean closed;
    private Object facade;
    /**
     * How many leading 16-bit and 32-bit elements of an index buffer created with data are the
     * sequence 0, 1, 2, ... (vanilla's shared sequential index buffer); 0 once anything writes the
     * buffer again. Fan draws through such a buffer are converted like non-indexed ones.
     */
    private int identity16;
    private int identity32;
    /** What texture readbacks copied into this buffer ({@link CoreEncoder#copyTextureToBuffer}). */
    private ByteBuffer readData;

    CoreBuffer(CoreDevice device, int usage, long size, int handle, boolean transientBlock) {
        this.device = device;
        this.usage = usage;
        this.size = size;
        this.handle = handle;
        this.transientBlock = transientBlock;
    }

    public long size() {
        return this.size;
    }

    /** {@link CoreUsage} buffer bits. */
    public int usage() {
        return this.usage;
    }

    /** The glue handle (diagnostics and harnesses). */
    public int handle() {
        return this.handle;
    }

    /** A block of transient memory: never closed by its users and not mappable. */
    public boolean isTransient() {
        return this.transientBlock;
    }

    public boolean isClosed() {
        return this.closed;
    }

    /** Destroys the buffer after the submit that may still reference it. */
    public void close() {
        if (!this.closed && !this.transientBlock) {
            this.closed = true;
            this.device.encoder().destroyBufferAfterSubmit(this.handle);
        }
    }

    /**
     * The buffer's contents as texture readbacks left them, little-endian, {@link #size()} bytes
     * (zeros where no readback wrote). WebGPU cannot map a buffer synchronously, so read mappings
     * see this CPU copy, which is complete once the readback's callback runs.
     */
    public ByteBuffer readData() {
        if (this.readData == null) {
            this.readData = ByteBuffer.allocateDirect((int) this.size).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        }
        return this.readData;
    }

    /** Records the identity prefixes of an index buffer's initial contents (position to limit). */
    void scanIdentity(ByteBuffer data) {
        ByteBuffer bytes = data.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int start = bytes.position();
        int shorts = bytes.remaining() >> 1;
        int n = 0;
        while (n < shorts && (bytes.getShort(start + 2 * n) & 0xFFFF) == n) {
            n++;
        }
        this.identity16 = n;
        int ints = bytes.remaining() >> 2;
        n = 0;
        while (n < ints && bytes.getInt(start + 4 * n) == n) {
            n++;
        }
        this.identity32 = n;
    }

    /** Something wrote the buffer after its creation: its identity prefixes are unknown. */
    void contentsChanged() {
        this.identity16 = 0;
        this.identity32 = 0;
    }

    /**
     * Whether elements {@code [first, first + count)} of this buffer, read as 16-bit
     * ({@code wide} false) or 32-bit indices, are the identity sequence.
     */
    boolean isIdentity(boolean wide, int first, int count) {
        return first >= 0 && count >= 0 && (long) first + count <= (wide ? this.identity32 : this.identity16);
    }

    public Object facade() {
        return this.facade;
    }

    public void setFacade(Object facade) {
        this.facade = facade;
    }

    /**
     * Maps {@code [offset, offset + length)} for writing. The returned range's data is CPU scratch
     * memory valid until the next submit; {@link MappedRange#commit()} writes it into this buffer
     * at the current position of the command stream (through the staging ring), also while a
     * render pass is open.
     */
    public MappedRange mapWrite(long offset, long length) {
        if (this.transientBlock) {
            throw new IllegalStateException("Cannot map transient buffer");
        }
        if (this.closed) {
            throw new IllegalStateException("Buffer already closed");
        }
        CoreEncoder encoder = this.device.encoder();
        ByteBuffer scratch = encoder.transientMemory().allocateCpu(length, 4L, length, 1L);
        return new MappedRange(encoder, scratch, this, offset);
    }

    /** A pending write mapping. */
    public static final class MappedRange {
        private final CoreEncoder encoder;
        private final ByteBuffer scratch;
        private final CoreBuffer target;
        private final long offset;

        MappedRange(CoreEncoder encoder, ByteBuffer scratch, CoreBuffer target, long offset) {
            this.encoder = encoder;
            this.scratch = scratch;
            this.target = target;
            this.offset = offset;
        }

        public ByteBuffer data() {
            return this.scratch;
        }

        /** Writes the mapped range into the target buffer in command order. */
        public void commit() {
            ByteBuffer whole = this.scratch.duplicate();
            whole.clear();
            this.encoder.writeBuffer(this.target, this.offset, whole);
        }
    }
}
