package dev.gaius.browser;

import com.mojang.blaze3d.buffers.GpuBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Float64Array;

/**
 * Retains a bounded set of already-signalled staged GPU buffers across browser frames.
 *
 * <p>The owner is vanilla's {@code GpuBufferPool}, patched per render seam generation, and the
 * generations differ in where the pool sweeps the signalled recycle batches:
 * <ul>
 *   <li>at the start of {@code acquire}: {@link #beforeAcquire}, the owner's sweep with one
 *       {@link #recycleResult} per polled batch, {@link #afterRecycleSweep}, {@link #removeAt} or
 *       {@link #recordCreate}, then {@link #afterAcquire}; {@link #endFrame} runs when the used
 *       list became a fenced pending batch;</li>
 *   <li>at the tail of {@code endFrame}: {@code acquire} is {@link #beforeAcquire},
 *       {@link #afterRecycleSweep} (after rounding the request; it validates the batches adopted
 *       at the previous endFrame and selects the candidate), {@link #removeAt} or
 *       {@link #recordCreate}, then {@link #afterAcquire}; {@code endFrame} is {@link #endFrame}
 *       after the used list became a fenced pending batch and before the tail sweep, then
 *       {@link #recycleResult} once per polled batch. Adopted buffers are therefore stamped
 *       with the new frame, as with the other order where adoption happens during the next
 *       frame's first acquire.</li>
 * </ul>
 * Between an endFrame sweep and the next acquire (or endFrame) the adopted batch may exceed the
 * count/byte budget; the next acquire or endFrame trims it.
 */
public final class BrowserGpuBufferPoolCache {
    public static final int MAX_COUNT = 4;
    public static final long MAX_BYTES = 1024L * 1024L;
    public static final int MAX_IDLE_FRAMES = 3;

    private static final int TELEMETRY_INTERVAL_FRAMES = 4;
    /** Field indices of the packed telemetry snapshot (see publishTelemetryBrowser). */
    private static final int P_POOL_ID = 0;
    private static final int P_USAGE = 1;
    private static final int P_FRAME = 2;
    private static final int P_AVAILABLE_COUNT = 3;
    private static final int P_AVAILABLE_BYTES = 4;
    private static final int P_ADMITTED_AVAILABLE_HIGH_WATER = 5;
    private static final int P_ADMITTED_AVAILABLE_BYTES_HIGH_WATER = 6;
    private static final int P_RETAINED_AVAILABLE_HIGH_WATER = 7;
    private static final int P_RETAINED_AVAILABLE_BYTES_HIGH_WATER = 8;
    private static final int P_PENDING_DEPTH = 9;
    private static final int P_ACQUIRE_CALLS = 10;
    private static final int P_CREATE_CALLS = 11;
    private static final int P_REUSE_CALLS = 12;
    private static final int P_RECYCLE_READY_BATCHES = 13;
    private static final int P_RECYCLE_NOT_READY = 14;
    private static final int P_RECYCLED_BUFFERS = 15;
    private static final int P_CACHE_CLOSE_CALLS = 16;
    private static final int P_CACHE_CLOSE_FAILURES = 17;
    private static final int P_BUDGET_EVICTIONS = 18;
    private static final int P_TTL_EVICTIONS = 19;
    private static final int P_CONTEXT_DROPS = 20;
    private static final int P_ANOMALY_DROPS = 21;
    private static final int P_OVERFLOW_PURGES = 22;
    private static final int P_REQUESTED_BYTES = 23;
    private static final int P_ACQUIRED_BYTES = 24;
    private static final int P_TELEMETRY_PUBLISHES = 25;
    private static final int P_ADOPTION_OPERATIONS = 26;
    private static final int P_SWEEP_VALIDATIONS = 27;
    private static final int P_SWEEP_IDENTITY_COMPARISONS = 28;
    private static final int P_SWEEP_SELECTION_COMPARISONS = 29;
    private static final int P_TRIM_COMPARISONS = 30;
    private static final int P_LIST_TRIM_CALLS = 31;
    private static final int P_STORAGE_COMPACTION_FAILURES = 32;
    private static final int P_OWNER_CLOSED = 33;
    private static final int P_MAX_POOLS = 34;
    private static final int P_COUNT = 35;
    private static final double[] TELEMETRY = new double[P_COUNT];
    private static final int MAX_TELEMETRY_POOLS = 24;
    private static int nextPoolId = 1;

    private final List<GpuBuffer> available;
    private final BooleanSupplier contextProbe;
    private final int usage;
    private final int poolId;
    private Supplier<String> labelSupplier;
    private String resolvedLabel;

    /** Primitive metadata remains index-aligned with {@link #available}. */
    private int[] availableFrames = new int[MAX_COUNT];
    private long[] availableSizes = new long[MAX_COUNT];
    private int stampCount;
    private long availableBytes;
    private int frame;
    private boolean ownerClosed;
    private boolean contextUnavailable;
    private boolean telemetryBridgeAvailable = true;
    /** The JS side holds this pool's label once it was published (see publishTelemetryBrowser). */
    private boolean telemetryLabelPublished;
    private boolean metadataExpanded;
    private boolean listMayNeedTrim;
    private int framesSinceTelemetry;

    /** Candidate computed once after the complete recycle sweep and verified at removeAt. */
    private GpuBuffer expectedCandidate;
    private int expectedCandidateIndex = -1;

    private long acquireCalls;
    private long createCalls;
    private long reuseCalls;
    private long recycleReadyBatches;
    private long recycleNotReady;
    private long recycledBuffers;
    private long cacheCloseCalls;
    private long cacheCloseFailures;
    private long budgetEvictions;
    private long ttlEvictions;
    private long contextDrops;
    private long anomalyDrops;
    private long overflowPurges;
    private long requestedBytes;
    private long acquiredBytes;
    private long admittedAvailableHighWater;
    private long admittedAvailableBytesHighWater;
    private int retainedAvailableHighWater;
    private long retainedAvailableBytesHighWater;
    private int lastPendingDepth;
    private long telemetryPublishes;
    private long adoptionOperations;
    private long sweepValidations;
    private long sweepIdentityComparisons;
    private long sweepSelectionComparisons;
    private long trimComparisons;
    private long listTrimCalls;
    private long storageCompactionFailures;

    public BrowserGpuBufferPoolCache(
            List<GpuBuffer> available,
            Supplier<String> label,
            int usage) {
        this(available, label, usage, BrowserGpuBufferPoolCache::browserContextAvailable);
    }

    BrowserGpuBufferPoolCache(
            List<GpuBuffer> available,
            Supplier<String> label,
            int usage,
            BooleanSupplier contextProbe) {
        if (available == null || contextProbe == null) {
            throw new IllegalArgumentException(
                    "GPU buffer cache requires an available list and context probe");
        }
        this.available = available;
        this.contextProbe = contextProbe;
        this.usage = usage;
        this.poolId = allocatePoolId();
        this.labelSupplier = label;
        if (!available.isEmpty()) {
            rebuildInitialMetadataOrClear();
        }
    }

    /** Called at the start of acquire; where the recycle sweep runs depends on the generation. */
    public void beforeAcquire() {
        acquireCalls++;
        clearExpectedCandidate();
        if (!requireContext()) {
            return;
        }
        if (!validateMetadata(false, 0, false)) {
            failClosedAvailable("before-acquire-metadata");
            return;
        }
        if (!purgeExpired()) {
            failClosedAvailable("before-acquire-ttl");
        }
    }

    /**
     * Adopts one non-null, already-signalled batch in linear time. Duplicate, closed-state and
     * candidate validation is deferred to the next {@link #afterRecycleSweep} (or
     * {@link #endFrame}), which checks all adopted batches at once.
     */
    public void recycleResult(List<GpuBuffer> recycled) {
        if (recycled == null) {
            recycleNotReady++;
            return;
        }
        recycleReadyBatches++;
        GpuBuffer[] incoming = null;
        try {
            // Do not rescan the already-adopted prefix for every ready fence.  The complete
            // owner sweep is validated exactly once by afterRecycleSweep(); on an exceptional
            // transfer failClosedTransfer() snapshots the actual list (including any partially
            // appended prefix) before closing the union of both owners.
            if (available.size() != stampCount) {
                throw new IllegalStateException("GPU buffer metadata count changed");
            }
            incoming = snapshotIncomingExact(recycled);
            int oldCount = stampCount;
            int incomingCount = incoming.length;
            if (incomingCount > Integer.MAX_VALUE - oldCount) {
                throw new IllegalStateException("GPU buffer cache size overflow");
            }
            int nextCount = oldCount + incomingCount;
            ensureMetadataCapacity(nextCount);
            if (nextCount > MAX_COUNT) {
                // Set this before addAll: a non-standard ArrayList can partially expand and
                // throw, and that failure path must still compact its backing storage.
                listMayNeedTrim = true;
            }
            boolean changed = available.addAll(recycled);
            if ((incomingCount != 0 && !changed)
                    || (incomingCount == 0 && changed)
                    || available.size() != nextCount) {
                throw new IllegalStateException("GPU buffer cache adoption size changed");
            }
            long nextBytes = availableBytes;
            for (int index = 0; index < incomingCount; index++) {
                GpuBuffer buffer = incoming[index];
                long size = safeSize(buffer);
                int destination = oldCount + index;
                availableFrames[destination] = frame;
                availableSizes[destination] = size;
                if (size < 0L || nextBytes > Long.MAX_VALUE - size) {
                    throw new IllegalStateException("GPU buffer cache adoption size invalid");
                }
                nextBytes += size;
                adoptionOperations++;
            }
            stampCount = nextCount;
            availableBytes = nextBytes;
            recycledBuffers += incomingCount;
            updateAdmittedHighWater();
        } catch (Throwable failure) {
            failClosedTransfer(incoming, recycled, "recycle-adoption");
        }
    }

    /**
     * Validates all batches adopted since the last check together and selects the candidate
     * before vanilla takeBestAvailable runs.
     */
    public void afterRecycleSweep(int requestedRoundedSize) {
        clearExpectedCandidate();
        if (!requireContext()) {
            return;
        }
        sweepValidations++;
        if (!validateMetadata(true, requestedRoundedSize, true)) {
            failClosedAvailable("after-recycle-sweep");
        }
    }

    /** Removes the candidate chosen by vanilla while keeping primitive metadata in lockstep. */
    public GpuBuffer removeAt(int index) {
        if (!requireContext()) {
            return null;
        }
        try {
            if (available.size() != stampCount
                    || index < 0
                    || index >= stampCount
                    || expectedCandidate == null
                    || index != expectedCandidateIndex
                    || available.get(index) != expectedCandidate) {
                failClosedAvailable("remove-candidate-mismatch");
                return null;
            }
            GpuBuffer buffer = removeAtInternal(index);
            if (buffer == null || safeClosed(buffer)) {
                safeClose(buffer);
                failClosedAvailable("remove-closed-buffer");
                return null;
            }
            reuseCalls++;
            clearExpectedCandidate();
            return buffer;
        } catch (Throwable failure) {
            failClosedAvailable("remove-exception");
            return null;
        }
    }

    /** Records the owner's miss path after it has created a new physical buffer. */
    public void recordCreate(GpuBuffer buffer) {
        createCalls++;
        clearExpectedCandidate();
        if (buffer == null || safeClosed(buffer) || safeSize(buffer) < 0L) {
            failClosedAvailable("created-buffer-invalid");
        }
    }

    /** Trims only after the selected buffer has moved to usedThisFrame. */
    public void afterAcquire(GpuBuffer acquired, int requestedSize) {
        clearExpectedCandidate();
        requestedBytes += Math.max(0, requestedSize);
        long size = safeSize(acquired);
        if (size >= 0L) {
            acquiredBytes += size;
        } else {
            failClosedAvailable("acquired-buffer-invalid");
        }
        if (!requireContext()) {
            return;
        }
        try {
            if (available.size() != stampCount) {
                failClosedAvailable("after-acquire-metadata");
                return;
            }
            if (!purgeExpired() || !trimBudgetLinear()) {
                failClosedAvailable("after-acquire-trim");
                return;
            }
            updateRetainedHighWater();
            compactStorageIfNeeded();
        } catch (Throwable failure) {
            failClosedAvailable("after-acquire-exception");
        }
    }

    /**
     * Called once after the owner has moved usedThisFrame into its fenced pending list and,
     * where the recycle sweep runs at the tail of endFrame, before that sweep adopts signalled
     * batches for the new frame.
     */
    public void endFrame(int pendingDepth) {
        lastPendingDepth = Math.max(0, pendingDepth);
        if (frame == Integer.MAX_VALUE) {
            overflowPurges++;
            closeAndClearAvailableNoThrow();
            frame = 0;
            publishTelemetry(false, "frame-overflow");
        } else {
            frame++;
        }
        if (requireContext()) {
            try {
                if (!validateMetadata(false, 0, false)
                        || !purgeExpired()
                        || !trimBudgetLinear()) {
                    failClosedAvailable("end-frame-validation");
                } else {
                    updateRetainedHighWater();
                    compactStorageIfNeeded();
                }
            } catch (Throwable failure) {
                failClosedAvailable("end-frame-exception");
            }
        }
        publishPeriodicTelemetry();
    }

    /** Called only after the owner has closed and cleared available, used and pending lists. */
    public void ownerClosed() {
        if (ownerClosed) {
            return;
        }
        ownerClosed = true;
        clearExpectedCandidate();
        boolean clean = false;
        try {
            // The owner's close() deliberately closes and clears the real list before this
            // hook.  Non-zero stamps therefore describe the just-closed list and are reset
            // here; only a still-populated owner list is anomalous.
            clean = available.isEmpty();
        } catch (Throwable failure) {
            // Fall through to the fail-closed owner cleanup.
        }
        if (!clean) {
            anomalyDrops++;
            closeAndClearAvailableNoThrow();
        } else {
            resetMetadata();
            compactStorageIfNeeded();
        }
        publishTelemetry(true, "owner-close");
    }

    private boolean validateMetadata(
            boolean countSweepComparisons,
            int requestedRoundedSize,
            boolean selectCandidate) {
        try {
            if (available.size() != stampCount
                    || stampCount < 0
                    || availableFrames.length < stampCount
                    || availableSizes.length < stampCount) {
                return false;
            }
            int maximumSize = requestedRoundedSize * 4;
            long bestSize = (long) (maximumSize + 1);
            GpuBuffer best = null;
            int bestIndex = -1;
            boolean exactFound = false;
            long total = 0L;
            for (int index = 0; index < stampCount; index++) {
                GpuBuffer buffer = available.get(index);
                long size = safeSize(buffer);
                if (buffer == null || safeClosed(buffer) || size < 0L
                        || size != availableSizes[index]) {
                    return false;
                }
                for (int previous = 0; previous < index; previous++) {
                    if (countSweepComparisons) {
                        sweepIdentityComparisons++;
                    }
                    if (available.get(previous) == buffer) {
                        return false;
                    }
                }
                if (total > Long.MAX_VALUE - size) {
                    return false;
                }
                total += size;
                if (selectCandidate) {
                    sweepSelectionComparisons++;
                    if (!exactFound && size == (long) requestedRoundedSize) {
                        exactFound = true;
                        best = buffer;
                        bestIndex = index;
                    } else if (!exactFound && size > (long) requestedRoundedSize
                            && size < bestSize) {
                        bestSize = size;
                        best = buffer;
                        bestIndex = index;
                    }
                }
            }
            if (total != availableBytes) {
                return false;
            }
            if (selectCandidate) {
                expectedCandidate = best;
                expectedCandidateIndex = bestIndex;
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private boolean purgeExpired() {
        try {
            for (int index = stampCount - 1; index >= 0; index--) {
                int stamp = availableFrames[index];
                if (stamp < 0 || stamp > frame) {
                    return false;
                }
                if (frame - stamp > MAX_IDLE_FRAMES) {
                    GpuBuffer expired = removeAtInternal(index);
                    ttlEvictions++;
                    safeClose(expired);
                }
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Selects the least-evictable entries in O(n * MAX_COUNT). */
    private boolean trimBudgetLinear() {
        if (stampCount <= MAX_COUNT && availableBytes <= MAX_BYTES) {
            return true;
        }
        try {
            int[] retained = {-1, -1, -1, -1};
            int retainedCount = 0;
            for (int index = 0; index < stampCount; index++) {
                if (retainedCount < MAX_COUNT) {
                    retained[retainedCount++] = index;
                    continue;
                }
                int mostEvictableSlot = 0;
                for (int slot = 1; slot < retainedCount; slot++) {
                    trimComparisons++;
                    if (evictsBefore(retained[slot], retained[mostEvictableSlot])) {
                        mostEvictableSlot = slot;
                    }
                }
                trimComparisons++;
                if (evictsBefore(retained[mostEvictableSlot], index)) {
                    retained[mostEvictableSlot] = index;
                }
            }
            long retainedBytes = 0L;
            for (int slot = 0; slot < retainedCount; slot++) {
                retainedBytes += availableSizes[retained[slot]];
            }
            while (retainedCount > 0 && retainedBytes > MAX_BYTES) {
                int mostEvictableSlot = -1;
                for (int slot = 0; slot < retained.length; slot++) {
                    if (retained[slot] < 0) continue;
                    if (mostEvictableSlot < 0
                            || evictsBefore(retained[slot], retained[mostEvictableSlot])) {
                        mostEvictableSlot = slot;
                    }
                    trimComparisons++;
                }
                retainedBytes -= availableSizes[retained[mostEvictableSlot]];
                retained[mostEvictableSlot] = -1;
                retainedCount--;
            }
            for (int index = stampCount - 1; index >= 0; index--) {
                boolean keep = false;
                for (int retainedIndex : retained) {
                    trimComparisons++;
                    if (retainedIndex == index) {
                        keep = true;
                        break;
                    }
                }
                if (!keep) {
                    GpuBuffer evicted = removeAtInternal(index);
                    budgetEvictions++;
                    safeClose(evicted);
                }
            }
            return stampCount <= MAX_COUNT && availableBytes <= MAX_BYTES;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Oldest first, then largest, then the lowest stable list index. */
    private boolean evictsBefore(int left, int right) {
        int leftAge = frame - availableFrames[left];
        int rightAge = frame - availableFrames[right];
        if (leftAge != rightAge) return leftAge > rightAge;
        long leftSize = availableSizes[left];
        long rightSize = availableSizes[right];
        if (leftSize != rightSize) return leftSize > rightSize;
        return left < right;
    }

    private GpuBuffer removeAtInternal(int index) {
        GpuBuffer buffer = available.remove(index);
        long size = availableSizes[index];
        int trailing = stampCount - index - 1;
        if (trailing > 0) {
            System.arraycopy(availableFrames, index + 1, availableFrames, index, trailing);
            System.arraycopy(availableSizes, index + 1, availableSizes, index, trailing);
        }
        stampCount--;
        availableFrames[stampCount] = 0;
        availableSizes[stampCount] = 0L;
        availableBytes -= size;
        if (availableBytes < 0L) availableBytes = 0L;
        return buffer;
    }

    private static GpuBuffer[] snapshotIncomingExact(List<GpuBuffer> incoming) {
        Object[] objects = incoming.toArray();
        GpuBuffer[] snapshot = new GpuBuffer[objects.length];
        for (int index = 0; index < objects.length; index++) {
            Object object = objects[index];
            if (object != null && !(object instanceof GpuBuffer)) {
                throw new IllegalArgumentException("Recycle batch contains a non-GpuBuffer value");
            }
            snapshot[index] = (GpuBuffer) object;
        }
        return snapshot;
    }

    private GpuBuffer[] bestEffortAvailableSnapshot() {
        try {
            int count = Math.max(0, available.size());
            GpuBuffer[] snapshot = new GpuBuffer[count];
            int copied = 0;
            try {
                for (; copied < count; copied++) snapshot[copied] = available.get(copied);
                return snapshot;
            } catch (Throwable failure) {
                return Arrays.copyOf(snapshot, copied);
            }
        } catch (Throwable failure) {
            return new GpuBuffer[0];
        }
    }

    private static GpuBuffer[] bestEffortIncomingSnapshot(List<GpuBuffer> incoming) {
        if (incoming == null) return new GpuBuffer[0];
        try {
            return snapshotIncomingExact(incoming);
        } catch (Throwable failure) {
            return new GpuBuffer[0];
        }
    }

    private void failClosedTransfer(
            GpuBuffer[] incoming,
            List<GpuBuffer> recycled,
            String reason) {
        anomalyDrops++;
        GpuBuffer[] actual = bestEffortAvailableSnapshot();
        GpuBuffer[] safeIncoming = incoming == null
                ? bestEffortIncomingSnapshot(recycled) : incoming;
        // actual owns the original available prefix plus any partial addAll suffix; incoming
        // owns every still-unadded batch member.  Identity de-duplication makes the union safe.
        closeUniqueNoThrow(actual, safeIncoming);
        forceClearAvailableNoThrow();
        resetMetadata();
        compactStorageIfNeeded();
        clearExpectedCandidate();
        publishTelemetry(false, reason);
    }

    private void failClosedAvailable(String reason) {
        anomalyDrops++;
        closeAndClearAvailableNoThrow();
        clearExpectedCandidate();
        publishTelemetry(false, reason);
    }

    private void dropAvailableForContext() {
        boolean firstObservation = !contextUnavailable;
        contextUnavailable = true;
        if (firstObservation) contextDrops++;
        closeAndClearAvailableNoThrow();
        clearExpectedCandidate();
        if (firstObservation) publishTelemetry(false, "context-unavailable");
    }

    private void closeAndClearAvailableNoThrow() {
        GpuBuffer[] snapshot = bestEffortAvailableSnapshot();
        closeUniqueNoThrow(snapshot);
        forceClearAvailableNoThrow();
        resetMetadata();
        compactStorageIfNeeded();
    }

    private void closeUniqueNoThrow(GpuBuffer[]... sources) {
        for (int sourceIndex = 0; sourceIndex < sources.length; sourceIndex++) {
            GpuBuffer[] source = sources[sourceIndex];
            if (source == null) continue;
            for (int index = 0; index < source.length; index++) {
                GpuBuffer candidate = source[index];
                if (candidate == null || seenEarlier(sources, sourceIndex, index, candidate)) {
                    continue;
                }
                safeClose(candidate);
            }
        }
    }

    private static boolean seenEarlier(
            GpuBuffer[][] sources, int sourceIndex, int index, GpuBuffer candidate) {
        for (int previousSource = 0; previousSource <= sourceIndex; previousSource++) {
            GpuBuffer[] source = sources[previousSource];
            if (source == null) continue;
            int limit = previousSource == sourceIndex ? index : source.length;
            for (int previous = 0; previous < limit; previous++) {
                if (source[previous] == candidate) return true;
            }
        }
        return false;
    }

    private void forceClearAvailableNoThrow() {
        try {
            available.clear();
            return;
        } catch (Throwable failure) {
            // A non-standard list may reject clear; make bounded best-effort removals.
        }
        int attempts;
        try {
            attempts = Math.max(0, available.size());
        } catch (Throwable failure) {
            return;
        }
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                int size = available.size();
                if (size == 0) return;
                available.remove(size - 1);
            } catch (Throwable failure) {
                return;
            }
        }
    }

    private void resetMetadata() {
        int clearCount = Math.min(Math.max(0, stampCount),
                Math.min(availableFrames.length, availableSizes.length));
        if (clearCount > 0) {
            Arrays.fill(availableFrames, 0, clearCount, 0);
            Arrays.fill(availableSizes, 0, clearCount, 0L);
        }
        stampCount = 0;
        availableBytes = 0L;
    }

    private void rebuildInitialMetadataOrClear() {
        try {
            int count = available.size();
            ensureMetadataCapacity(count);
            stampCount = count;
            availableBytes = 0L;
            for (int index = 0; index < count; index++) {
                availableFrames[index] = frame;
                long size = safeSize(available.get(index));
                availableSizes[index] = size;
                if (size < 0L || availableBytes > Long.MAX_VALUE - size) {
                    throw new IllegalStateException("Initial GPU buffer metadata invalid");
                }
                availableBytes += size;
            }
            if (!validateMetadata(false, 0, false)) {
                throw new IllegalStateException("Initial GPU buffer list invalid");
            }
            updateAdmittedHighWater();
            updateRetainedHighWater();
        } catch (Throwable failure) {
            anomalyDrops++;
            closeAndClearAvailableNoThrow();
        }
    }

    private void ensureMetadataCapacity(int requested) {
        if (requested <= availableFrames.length) return;
        int doubled = availableFrames.length > Integer.MAX_VALUE / 2
                ? Integer.MAX_VALUE : availableFrames.length * 2;
        int capacity = Math.max(requested, Math.max(MAX_COUNT, doubled));
        availableFrames = Arrays.copyOf(availableFrames, capacity);
        availableSizes = Arrays.copyOf(availableSizes, capacity);
        metadataExpanded = true;
    }

    private void compactStorageIfNeeded() {
        if (stampCount > MAX_COUNT) return;
        if (metadataExpanded || availableFrames.length != MAX_COUNT) {
            try {
                availableFrames = Arrays.copyOf(availableFrames, MAX_COUNT);
                availableSizes = Arrays.copyOf(availableSizes, MAX_COUNT);
                metadataExpanded = false;
            } catch (Throwable failure) {
                storageCompactionFailures++;
            }
        }
        if (listMayNeedTrim && available instanceof ArrayList<?> arrayList) {
            try {
                arrayList.trimToSize();
                listTrimCalls++;
                listMayNeedTrim = false;
            } catch (Throwable failure) {
                storageCompactionFailures++;
            }
        }
    }

    private void safeClose(GpuBuffer buffer) {
        if (buffer == null) return;
        boolean closed = false;
        try {
            closed = buffer.isClosed();
        } catch (Throwable failure) {
            cacheCloseFailures++;
        }
        if (closed) return;
        try {
            buffer.close();
            cacheCloseCalls++;
        } catch (Throwable failure) {
            cacheCloseFailures++;
        }
    }

    private static long safeSize(GpuBuffer buffer) {
        if (buffer == null) return -1L;
        try {
            long size = buffer.size();
            return size >= 0L ? size : -1L;
        } catch (Throwable failure) {
            return -1L;
        }
    }

    private static boolean safeClosed(GpuBuffer buffer) {
        if (buffer == null) return true;
        try {
            return buffer.isClosed();
        } catch (Throwable failure) {
            return true;
        }
    }

    private boolean requireContext() {
        if (ownerClosed) return false;
        boolean availableNow;
        try {
            availableNow = contextProbe.getAsBoolean();
        } catch (Throwable failure) {
            availableNow = false;
        }
        if (!availableNow) {
            dropAvailableForContext();
            return false;
        }
        contextUnavailable = false;
        return true;
    }

    private void clearExpectedCandidate() {
        expectedCandidate = null;
        expectedCandidateIndex = -1;
    }

    private void updateAdmittedHighWater() {
        admittedAvailableHighWater = Math.max(admittedAvailableHighWater, stampCount);
        admittedAvailableBytesHighWater = Math.max(admittedAvailableBytesHighWater, availableBytes);
    }

    private void updateRetainedHighWater() {
        retainedAvailableHighWater = Math.max(retainedAvailableHighWater, stampCount);
        retainedAvailableBytesHighWater = Math.max(retainedAvailableBytesHighWater, availableBytes);
    }

    private void publishPeriodicTelemetry() {
        framesSinceTelemetry++;
        if (framesSinceTelemetry >= TELEMETRY_INTERVAL_FRAMES) {
            publishTelemetry(false, "periodic");
        }
    }

    private void publishTelemetry(boolean closed, String reason) {
        telemetryPublishes++;
        framesSinceTelemetry = 0;
        if (!telemetryBridgeAvailable) return;
        String label = labelForPublish();
        try {
            double[] t = TELEMETRY;
            t[P_POOL_ID] = poolId;
            t[P_USAGE] = usage;
            t[P_FRAME] = frame;
            t[P_AVAILABLE_COUNT] = stampCount;
            t[P_AVAILABLE_BYTES] = availableBytes;
            t[P_ADMITTED_AVAILABLE_HIGH_WATER] = admittedAvailableHighWater;
            t[P_ADMITTED_AVAILABLE_BYTES_HIGH_WATER] = admittedAvailableBytesHighWater;
            t[P_RETAINED_AVAILABLE_HIGH_WATER] = retainedAvailableHighWater;
            t[P_RETAINED_AVAILABLE_BYTES_HIGH_WATER] = retainedAvailableBytesHighWater;
            t[P_PENDING_DEPTH] = lastPendingDepth;
            t[P_ACQUIRE_CALLS] = acquireCalls;
            t[P_CREATE_CALLS] = createCalls;
            t[P_REUSE_CALLS] = reuseCalls;
            t[P_RECYCLE_READY_BATCHES] = recycleReadyBatches;
            t[P_RECYCLE_NOT_READY] = recycleNotReady;
            t[P_RECYCLED_BUFFERS] = recycledBuffers;
            t[P_CACHE_CLOSE_CALLS] = cacheCloseCalls;
            t[P_CACHE_CLOSE_FAILURES] = cacheCloseFailures;
            t[P_BUDGET_EVICTIONS] = budgetEvictions;
            t[P_TTL_EVICTIONS] = ttlEvictions;
            t[P_CONTEXT_DROPS] = contextDrops;
            t[P_ANOMALY_DROPS] = anomalyDrops;
            t[P_OVERFLOW_PURGES] = overflowPurges;
            t[P_REQUESTED_BYTES] = requestedBytes;
            t[P_ACQUIRED_BYTES] = acquiredBytes;
            t[P_TELEMETRY_PUBLISHES] = telemetryPublishes;
            t[P_ADOPTION_OPERATIONS] = adoptionOperations;
            t[P_SWEEP_VALIDATIONS] = sweepValidations;
            t[P_SWEEP_IDENTITY_COMPARISONS] = sweepIdentityComparisons;
            t[P_SWEEP_SELECTION_COMPARISONS] = sweepSelectionComparisons;
            t[P_TRIM_COMPARISONS] = trimComparisons;
            t[P_LIST_TRIM_CALLS] = listTrimCalls;
            t[P_STORAGE_COMPACTION_FAILURES] = storageCompactionFailures;
            t[P_OWNER_CLOSED] = closed ? 1 : 0;
            t[P_MAX_POOLS] = MAX_TELEMETRY_POOLS;
            // The label crosses once per pool and the reason only when it is not the periodic
            // one: a Java string becomes a new JS string on every call on WebAssembly GC.
            publishTelemetryBrowser(JsArrays.float64(t), telemetryLabelPublished ? null : label,
                    "periodic".equals(reason) ? null : reason);
            telemetryLabelPublished = true;
        } catch (Throwable failure) {
            telemetryBridgeAvailable = false;
        }
    }

    private String labelForPublish() {
        if (resolvedLabel != null) return resolvedLabel;
        String value;
        try {
            value = labelSupplier == null ? null : labelSupplier.get();
        } catch (Throwable failure) {
            value = null;
        }
        labelSupplier = null;
        if (value == null || value.isBlank()) value = "usage-" + usage;
        resolvedLabel = value.length() <= 96 ? value : value.substring(0, 96);
        return resolvedLabel;
    }

    private static int allocatePoolId() {
        int id = nextPoolId;
        nextPoolId = nextPoolId == Integer.MAX_VALUE ? 1 : nextPoolId + 1;
        return id;
    }

    private static boolean browserContextAvailable() {
        try {
            return browserContextAvailableJs();
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Whether the render backend's context can take work (gate by backend): the
     * WebGPU core's {@code contextAvailable()} when it renders ({@code GaiusWebGpu.active}),
     * otherwise the WebGL 2 context and BrowserOpenGL's loss and submission state. Without the
     * WebGPU branch the pool would be dropped as "no context" under WebGPU.
     */
    @JSBody(script = """
            const gpu=globalThis.GaiusWebGpu;
            if (gpu && gpu.active===true && typeof gpu.contextAvailable==='function') {
              try { return gpu.contextAvailable()===true; } catch (error) { return false; }
            }
            const state=globalThis.__gaiusGL;
            const gl=globalThis.__gaiusWebGL;
            if (!state || !gl || state.gpuContextLost || state.gpuSubmissionBlocked
                    || typeof gl.isContextLost !== 'function') return false;
            try { return !gl.isContextLost(); } catch (error) { return false; }
            """)
    private static native boolean browserContextAvailableJs();

    /**
     * Publishes one pool into {@code __gaiusGLStats.gpuBufferPoolCache} from a packed snapshot:
     * index {@code P_X} holds field {@code x} (ownerClosed as 0/1). One typed array instead of
     * 37 arguments keeps the call under TeaVM's WebAssembly GC limit of 32 JSBody parameters, so
     * both targets publish the same object. {@code label} is null once JS holds the pool's label
     * (kept in {@code globalThis.__gaiusGpuPoolLabels}, outside the published object) and
     * {@code reason} is null for periodic publishes.
     */
    @JSBody(params = {"v", "label", "reason"}, script = """
            const stats=globalThis.__gaiusGLStats || (globalThis.__gaiusGLStats={});
            let root=stats.gpuBufferPoolCache;
            if (!root || root.version!==2 || !Array.isArray(root.pools)) {
              root={version:2,pools:[],overflowPublishes:0,totalPublishes:0,
                    closedPools:0,lastClosedPoolId:0};
              stats.gpuBufferPoolCache=root;
            }
            const boundedIncrement=(value)=>Math.min(2147483647,
                    Math.max(0,Number(value)||0)+1);
            const poolId=v[0]|0;
            const labels=globalThis.__gaiusGpuPoolLabels || (globalThis.__gaiusGpuPoolLabels=new Map());
            if (typeof label==='string') labels.set(poolId,label.slice(0,96));
            root.totalPublishes=boundedIncrement(root.totalPublishes);
            let slotIndex=-1;
            for (let index=0;index<root.pools.length;index++) {
              if ((root.pools[index].poolId|0)===poolId) {
                slotIndex=index;
                break;
              }
            }
            if (v[33]) {
              labels.delete(poolId);
              if (slotIndex>=0) root.pools.splice(slotIndex,1);
              root.closedPools=boundedIncrement(root.closedPools);
              root.lastClosedPoolId=poolId;
              root.lastCloseReason=String(reason||'owner-close').slice(0,48);
              return;
            }
            let slot=slotIndex>=0?root.pools[slotIndex]:null;
            if (!slot && root.pools.length<(v[34]|0)) {
              slot={poolId:poolId};
              root.pools.push(slot);
            }
            if (!slot) {
              root.overflowPublishes=boundedIncrement(root.overflowPublishes);
              root.lastOverflowPoolId=poolId;
              root.lastOverflowUsage=v[1]|0;
              return;
            }
            slot.label=labels.get(poolId)||'';
            slot.usage=v[1]|0;
            slot.frame=v[2]|0;
            slot.availableCount=v[3]|0;
            slot.availableBytes=Number(v[4]);
            slot.admittedAvailableHighWater=Number(v[5]);
            slot.admittedAvailableBytesHighWater=Number(v[6]);
            slot.retainedAvailableHighWater=v[7]|0;
            slot.retainedAvailableBytesHighWater=Number(v[8]);
            slot.pendingDepth=v[9]|0;
            slot.acquireCalls=Number(v[10]);
            slot.createCalls=Number(v[11]);
            slot.reuseCalls=Number(v[12]);
            slot.recycleReadyBatches=Number(v[13]);
            slot.recycleNotReady=Number(v[14]);
            slot.recycledBuffers=Number(v[15]);
            slot.cacheCloseCalls=Number(v[16]);
            slot.cacheCloseFailures=Number(v[17]);
            slot.budgetEvictions=Number(v[18]);
            slot.ttlEvictions=Number(v[19]);
            slot.contextDrops=Number(v[20]);
            slot.anomalyDrops=Number(v[21]);
            slot.overflowPurges=Number(v[22]);
            slot.requestedBytes=Number(v[23]);
            slot.acquiredBytes=Number(v[24]);
            slot.telemetryPublishes=Number(v[25]);
            slot.adoptionOperations=Number(v[26]);
            slot.sweepValidations=Number(v[27]);
            slot.sweepIdentityComparisons=Number(v[28]);
            slot.sweepSelectionComparisons=Number(v[29]);
            slot.trimComparisons=Number(v[30]);
            slot.listTrimCalls=Number(v[31]);
            slot.storageCompactionFailures=Number(v[32]);
            slot.lastPublishReason=String(reason||'periodic').slice(0,48);
            """)
    private static native void publishTelemetryBrowser(
            Float64Array values, String label, String reason);

    int availableCountForTesting() { return stampCount; }
    long availableBytesForTesting() { return availableBytes; }
    int metadataCapacityForTesting() {
        return Math.min(availableFrames.length, availableSizes.length);
    }
    long createCallsForTesting() { return createCalls; }
    long reuseCallsForTesting() { return reuseCalls; }
    long recycleNotReadyForTesting() { return recycleNotReady; }
    long anomalyDropsForTesting() { return anomalyDrops; }
    long budgetEvictionsForTesting() { return budgetEvictions; }
    long ttlEvictionsForTesting() { return ttlEvictions; }
    long contextDropsForTesting() { return contextDrops; }
    long overflowPurgesForTesting() { return overflowPurges; }
    long telemetryPublishesForTesting() { return telemetryPublishes; }
    long adoptionOperationsForTesting() { return adoptionOperations; }
    long sweepIdentityComparisonsForTesting() { return sweepIdentityComparisons; }
    long sweepSelectionComparisonsForTesting() { return sweepSelectionComparisons; }
    long trimComparisonsForTesting() { return trimComparisons; }
    long listTrimCallsForTesting() { return listTrimCalls; }
    long admittedHighWaterForTesting() { return admittedAvailableHighWater; }
    int retainedHighWaterForTesting() { return retainedAvailableHighWater; }
    int poolIdForTesting() { return poolId; }
    void setFrameForTesting(int value) { frame = value; }
}
