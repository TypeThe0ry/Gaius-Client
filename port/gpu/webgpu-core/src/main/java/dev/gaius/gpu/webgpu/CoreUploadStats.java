package dev.gaius.gpu.webgpu;

/**
 * Upload and pipeline telemetry of a device (bytes uploaded per frame and the ring
 * state). Counters are cumulative since the device was created; per-frame values are differences
 * of two snapshots, except the ring state and {@code lastSubmitUploadBytes}.
 *
 * @param submits command buffer submits
 * @param ringUploads per-submit uploads of transient memory copied through the ring
 * @param ringUploadBytes their bytes
 * @param orderedWrites command-ordered writes ({@code writeToBuffer}, write mappings)
 * @param orderedBytes their bytes (rounded up to 4)
 * @param orderedStaged ordered writes the ring refused, staged in transient memory instead
 * @param ringFallbacks per-submit uploads that fell back to {@code queue.writeBuffer} (ring full)
 * @param ringFallbackBytes their bytes
 * @param largeWrites per-submit uploads above the dedicated block limit, sent with
 *        {@code queue.writeBuffer}
 * @param largeWriteBytes their bytes
 * @param ringBlocks staging buffers that exist (mapped, in flight or being mapped)
 * @param ringBytes their total size
 * @param ringPending staging buffers waiting for their mapping
 * @param ringMapP95Ms 95th percentile of the last 64 mapping round trips, in milliseconds
 * @param lastSubmitUploadBytes bytes uploaded for the last submit (ring, fallback and large)
 * @param passSplits render passes ended and resumed to encode a write in command order
 * @param pipelines pipeline variants created
 * @param pipelineNanos time spent creating them (the synchronous part)
 * @param translations synchronous SPIR-V translations ({@code finishCompile} cache misses)
 * @param translationHits translations served from the cache
 * @param translateNanos time spent in synchronous translations
 */
public record CoreUploadStats(long submits, long ringUploads, long ringUploadBytes, long orderedWrites, long orderedBytes,
        long orderedStaged, long ringFallbacks, long ringFallbackBytes, long largeWrites, long largeWriteBytes,
        long ringBlocks, long ringBytes, long ringPending, double ringMapP95Ms, long lastSubmitUploadBytes,
        long passSplits, long pipelines, long pipelineNanos, long translations, long translationHits,
        long translateNanos) {
}
