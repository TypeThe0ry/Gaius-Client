package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;

/**
 * The upload staging ring (the upload rules, measured on real devices): {@code MAP_WRITE |
 * COPY_SRC} staging buffers that the frame's writes are copied into and then copied out of with
 * {@code copyBufferToBuffer}.
 *
 * <ul>
 * <li>{@link #BLOCK_SIZE 512 KiB} blocks shared by the small writes of a submit (vanilla's own
 * transient block size); a write larger than a block gets a dedicated power-of-two block of at
 * most {@link #DEDICATED_MAX 4 MiB};</li>
 * <li>every used block is unmapped before the submit and mapped again right after it; a block is
 * reused once its mapping is back. {@code mapAsync} costs grow with the mapped size, so there are
 * no large fixed blocks;</li>
 * <li>the ring holds at most {@link #CAP 32 MiB}. When it is full, or a write is larger than
 * 4 MiB, nothing waits: per-submit uploads of transient memory fall back to
 * {@code queue.writeBuffer} (ordered correctly, the whole submit may read them) and ordered writes
 * ({@link #writeOrdered}) are refused, so the encoder stages them in transient memory.</li>
 * </ul>
 *
 * <p>The mapped buffers live in the glue ({@code port/web/runtime/webgpu/gpu-glue.js}): only
 * JavaScript can write into a mapped range and see its mapping come back. This class owns the
 * configuration and the core side of the telemetry ({@link CoreUploadStats}).</p>
 */
public final class StagingRing {
    public static final int BLOCK_SIZE = 512 * 1024;
    public static final int DEDICATED_MAX = 4 * 1024 * 1024;
    public static final int CAP = 32 * 1024 * 1024;

    long orderedWrites;
    long orderedBytes;
    long orderedStaged;

    StagingRing() {
        WebGpuGlue.ringConfigure(BLOCK_SIZE, DEDICATED_MAX, CAP);
    }

    /**
     * Copies {@code data} (position to limit) into the ring and encodes its copy into
     * {@code [offset, offset + size)} of {@code destination} on the frame's encoder (no render pass
     * may be open). Returns {@code false} when the ring refuses the write.
     */
    boolean writeOrdered(CoreBuffer destination, int offset, ByteBuffer data, int size) {
        this.orderedWrites++;
        this.orderedBytes += size;
        if (WebGpuGlue.writeOrdered(destination.handle, offset, data, size)) {
            return true;
        }
        this.orderedStaged++;
        return false;
    }
}
