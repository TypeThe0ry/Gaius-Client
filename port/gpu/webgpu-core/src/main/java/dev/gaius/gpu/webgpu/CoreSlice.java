package dev.gaius.gpu.webgpu;

/** A byte range of a core buffer. */
public record CoreSlice(CoreBuffer buffer, long offset, long length) {
}
