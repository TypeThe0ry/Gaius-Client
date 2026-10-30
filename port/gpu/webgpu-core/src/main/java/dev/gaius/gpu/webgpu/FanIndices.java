package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * TRIANGLE_FAN draws as triangle lists, the TRIANGLE_FAN row of the degrade table:
 * WebGPU has no fan topology. A fan of {@code n} vertices {@code v0 .. v(n-1)} is the
 * {@code n - 2} triangles {@code (v0, vk, vk+1)}, in GL's order and with the fan's winding, so
 * culling is unchanged. A seam compiles the fan pipeline as a triangle list and draws each fan
 * through this device-wide index buffer ({@code 0, k, k+1} as 32-bit indices) with the fan's first
 * vertex as the base vertex.
 *
 * <p>That is exact when the fan's vertices are addressed in sequence: a non-indexed draw, or an
 * indexed one through the SPI's sequential index buffer (index {@code i} is {@code i}), the only
 * index buffer blaze3d draws fans with. The seam checks which one it has; a fan through any other
 * index buffer needs the indices on the CPU and is not converted here.</p>
 *
 * <p>The draw leaves this buffer bound as the pass's index buffer: the seam binds its own again
 * before its next indexed draw.</p>
 */
public final class FanIndices {
    /** The smallest buffer: fans of up to 64 vertices (vanilla's sky disc has 10, the sunrise 18). */
    private static final int MIN_FAN_VERTICES = 64;

    private final CoreDevice device;
    private CoreBuffer buffer;
    private int fanVertices;
    private long fans;

    public FanIndices(CoreDevice device) {
        this.device = device;
    }

    /** The number of triangle list indices of a fan of {@code fanVertices} vertices. */
    public static int indexCount(int fanVertices) {
        return fanVertices >= 3 ? (fanVertices - 2) * 3 : 0;
    }

    /**
     * The triangle list indices of a fan of {@code fanVertices} vertices, starting at vertex 0:
     * {@code 0, 1, 2, 0, 2, 3, ...} (tests and harnesses compare against it).
     */
    public static int[] indices(int fanVertices) {
        int[] indices = new int[indexCount(fanVertices)];
        for (int k = 1, at = 0; k + 1 < fanVertices; k++) {
            indices[at++] = 0;
            indices[at++] = k;
            indices[at++] = k + 1;
        }
        return indices;
    }

    /**
     * Draws a fan of {@code vertexCount} vertices that starts at vertex {@code firstVertex} as a
     * triangle list on a pipeline compiled with the TRIANGLES topology. Fewer than three vertices
     * draw nothing, as in GL.
     */
    public void draw(CorePass pass, int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        int count = indexCount(vertexCount);
        if (count == 0 || instanceCount <= 0) {
            return;
        }
        this.ensure(vertexCount);
        pass.setIndexBuffer(this.buffer, CoreIndexType.INT, 0);
        pass.drawIndexed(count, instanceCount, 0, firstVertex, firstInstance);
        this.fans++;
    }

    /** Fans drawn so far (diagnostics). */
    public long fans() {
        return this.fans;
    }

    /** The index buffer's capacity in fan vertices (diagnostics; 0 before the first fan). */
    public int capacity() {
        return this.fanVertices;
    }

    private void ensure(int vertexCount) {
        if (this.buffer != null && vertexCount <= this.fanVertices) {
            return;
        }
        int capacity = Math.max(MIN_FAN_VERTICES, this.fanVertices);
        while (capacity < vertexCount) {
            capacity *= 2;
        }
        int count = indexCount(capacity);
        ByteBuffer data = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int k = 1; k + 1 < capacity; k++) {
            data.putInt(0).putInt(k).putInt(k + 1);
        }
        data.flip();
        CoreBuffer next = this.device.createBuffer(this.device.debug() ? "Gaius triangle fan indices" : null,
                CoreUsage.BUFFER_INDEX | CoreUsage.BUFFER_COPY_DST, data);
        if (this.buffer != null) {
            // Draws recorded earlier in this submit may still use it.
            this.buffer.close();
        }
        this.buffer = next;
        this.fanVertices = capacity;
    }
}
