package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;

/**
 * A writable CPU window onto a slice of transient memory; what is written into {@link #data()}
 * reaches the GPU before the commands of the current submit run.
 */
public record CoreWindow(CoreSlice slice, ByteBuffer data) {
}
