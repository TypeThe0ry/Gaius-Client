package dev.gaius.gpu.webgpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded persistent storage for section resources.
 *
 * <p>A domain is deliberately keyed by every property that can affect a GPU binding. A vertex
 * slice can therefore never be handed to an index, indirect, layer or pipeline user. Releasing a
 * lease does not immediately make its range available: the range is retired at the next queue
 * epoch and is merged back only after the WebGPU queue completion epoch reaches that value.</p>
 *
 * <p>This class is an opt-in primitive. The caller keeps the ordinary upload path when an
 * allocation returns {@code null}, when the device is lost, or when a completion promise fails.
 * It owns no Minecraft types and is consequently shared by all profile seams.</p>
 */
public final class RegionArena {
    /** The low-end GPU vertex/index/indirect budget from the performance plan. */
    public static final long DEFAULT_BUDGET_BYTES = 304L * 1024L * 1024L;
    private static final int DEFAULT_SEGMENT_BYTES = 4 * 1024 * 1024;

    private final CoreDevice device;
    private final long budgetBytes;
    private final Map<DomainKey, Domain> domains = new HashMap<>();
    private long reservedBytes;
    private long liveBytes;
    private long retiredBytes;
    private int lastReclaimedEpoch;
    private boolean hasReclaimedEpoch;
    private boolean invalidated;
    private int testSubmittedEpoch;
    private int testCompletedEpoch;

    RegionArena(CoreDevice device, long budgetBytes) {
        if (budgetBytes <= 0 || budgetBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid region arena budget: " + budgetBytes);
        }
        this.device = device;
        this.budgetBytes = budgetBytes;
    }

    /** Package-private constructor used by the allocator-only JVM smoke. */
    RegionArena(long budgetBytes) {
        this(null, budgetBytes);
    }

    /** All properties which must not alias a persistent GPU allocation. */
    public record DomainKey(int layer, int pipeline, int indexType, int usage) {
    }

    /** Returns a stable domain handle. Callers should cache it beside their pipeline/layer state. */
    public Domain domain(int layer, int pipeline, int indexType, int usage) {
        if (this.invalidated) {
            throw new IllegalStateException("region arena belongs to a lost device");
        }
        DomainKey key = new DomainKey(layer, pipeline, indexType, usage);
        Domain domain = this.domains.get(key);
        if (domain == null) {
            domain = new Domain(this, key);
            this.domains.put(key, domain);
        }
        return domain;
    }

    /** Polls GPU completion and makes safe retired ranges available for reuse. */
    public void reclaim() {
        if (this.invalidated) {
            return;
        }
        int completed = this.completedEpoch();
        if (this.hasReclaimedEpoch && completed == this.lastReclaimedEpoch) {
            return;
        }
        this.lastReclaimedEpoch = completed;
        this.hasReclaimedEpoch = true;
        for (Domain domain : this.domains.values()) {
            domain.reclaim(completed);
        }
    }

    /** Drops every allocation after device loss; the old physical buffers are no longer valid. */
    void discardAfterDeviceLoss() {
        if (this.invalidated) {
            return;
        }
        this.invalidated = true;
        for (Domain domain : this.domains.values()) {
            for (Segment segment : domain.segments) {
                for (Allocation allocation : segment.allocations) {
                    allocation.active = false;
                }
                segment.allocations.clear();
                segment.retired.clear();
                segment.free.clear();
            }
        }
        this.domains.clear();
        this.reservedBytes = 0;
        this.liveBytes = 0;
        this.retiredBytes = 0;
        this.hasReclaimedEpoch = false;
    }

    public long budgetBytes() {
        return this.budgetBytes;
    }

    /** Capacity reserved in physical WebGPU buffers, including currently free ranges. */
    public long reservedBytes() {
        return this.reservedBytes;
    }

    public long liveBytes() {
        return this.liveBytes;
    }

    /** Bytes waiting for a GPU completion epoch. */
    public long retiredBytes() {
        return this.retiredBytes;
    }

    private int nextRetireEpoch() {
        return this.device == null ? this.testSubmittedEpoch + 1 : WebGpuGlue.regionArenaNextRetireEpoch();
    }

    private int completedEpoch() {
        return this.device == null ? this.testCompletedEpoch : WebGpuGlue.regionArenaCompletedEpoch();
    }

    static boolean reached(int completed, int target) {
        return completed - target >= 0;
    }

    private Segment newSegment(Domain domain, long required) {
        long remaining = this.budgetBytes - this.reservedBytes;
        if (remaining < required) {
            return null;
        }
        long capacity = Math.max(DEFAULT_SEGMENT_BYTES, nextPowerOfTwo(required));
        capacity = Math.min(capacity, remaining);
        if (this.device != null && this.device.caps().maxBufferSize() > 0) {
            capacity = Math.min(capacity, this.device.caps().maxBufferSize());
        }
        capacity &= ~3L;
        if (capacity < required || capacity > Integer.MAX_VALUE) {
            return null;
        }
        int size = (int) capacity;
        CoreBuffer buffer = this.device == null ? null
                : this.device.createRawBuffer("gaius region " + domain.key, domain.key.usage()
                        | CoreUsage.BUFFER_COPY_DST | CoreUsage.BUFFER_COPY_SRC, size);
        Segment segment = new Segment(domain, buffer, size);
        this.reservedBytes += size;
        domain.segments.add(segment);
        return segment;
    }

    private static long nextPowerOfTwo(long value) {
        long result = 1L;
        while (result < value && result < (1L << 62)) {
            result <<= 1;
        }
        return result < value ? value : result;
    }

    /** A domain with a separate buffer usage and independent free list. */
    public static final class Domain {
        private final RegionArena owner;
        private final DomainKey key;
        private final List<Segment> segments = new ArrayList<>();

        private Domain(RegionArena owner, DomainKey key) {
            this.owner = owner;
            this.key = key;
        }

        public DomainKey key() {
            return this.key;
        }

        /** Allocates an aligned range, or {@code null} when the bounded budget is exhausted. */
        public Lease allocate(long size, long alignment) {
            if (this.owner.invalidated) {
                return null;
            }
            if (this.owner.device != null && !this.owner.device.isUsable()) {
                return null;
            }
            if (size <= 0 || size > Integer.MAX_VALUE) {
                return null;
            }
            int requested = alignSize(size);
            if (requested <= 0) {
                return null;
            }
            int align = alignment <= 0 || alignment > Integer.MAX_VALUE ? 4 : (int) alignment;
            this.owner.reclaim();
            for (Segment segment : this.segments) {
                Allocation allocation = segment.allocate(requested, align);
                if (allocation != null) {
                    return this.activate(segment, allocation);
                }
            }
            Segment segment = this.owner.newSegment(this, requested);
            if (segment == null) {
                return null;
            }
            Allocation allocation = segment.allocate(requested, align);
            if (allocation == null) {
                throw new AssertionError("new region segment could not satisfy its allocation");
            }
            return this.activate(segment, allocation);
        }

        private Lease activate(Segment segment, Allocation allocation) {
            Allocation active = new Allocation(segment, allocation.offset, allocation.size,
                    segment.nextGeneration++);
            segment.allocations.add(active);
            this.owner.liveBytes += active.size;
            return new Lease(this, active);
        }

        private void release(Allocation allocation) {
            if (!allocation.active) {
                throw new IllegalStateException("region lease is already released");
            }
            allocation.active = false;
            allocation.retireEpoch = this.owner.nextRetireEpoch();
            allocation.segment.retired.add(allocation);
            this.owner.liveBytes -= allocation.size;
            this.owner.retiredBytes += allocation.size;
        }

        private void reclaim(int completedEpoch) {
            for (Segment segment : this.segments) {
                for (int i = segment.retired.size() - 1; i >= 0; i--) {
                    Allocation allocation = segment.retired.get(i);
                    if (reached(completedEpoch, allocation.retireEpoch)) {
                        segment.retired.remove(i);
                        segment.addFree(allocation.offset, allocation.size);
                        this.owner.retiredBytes -= allocation.size;
                    }
                }
            }
        }
    }

    /** A persistent range and its generation token. */
    public static final class Lease implements AutoCloseable {
        private final Domain domain;
        private final Allocation allocation;

        private Lease(Domain domain, Allocation allocation) {
            this.domain = domain;
            this.allocation = allocation;
        }

        public DomainKey domain() {
            return this.domain.key;
        }

        public CoreBuffer buffer() {
            this.requireActive();
            return this.allocation.segment.buffer;
        }

        public long offset() {
            this.requireActive();
            return this.allocation.offset;
        }

        public long size() {
            this.requireActive();
            return this.allocation.size;
        }

        public long generation() {
            return this.allocation.generation;
        }

        public boolean isReleased() {
            return !this.allocation.active;
        }

        public CoreSlice slice() {
            return new CoreSlice(this.buffer(), this.offset(), this.size());
        }

        private void requireActive() {
            if (this.domain.owner.invalidated || !this.allocation.active) {
                throw new IllegalStateException("region lease is released");
            }
        }

        @Override
        public void close() {
            this.domain.release(this.allocation);
        }
    }

    private static final class Allocation {
        final Segment segment;
        final int offset;
        final int size;
        final long generation;
        int retireEpoch;
        boolean active = true;

        Allocation(Segment segment, int offset, int size, long generation) {
            this.segment = segment;
            this.offset = offset;
            this.size = size;
            this.generation = generation;
        }
    }

    private static final class FreeRange {
        int offset;
        int size;

        FreeRange(int offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }

    private static final class Segment {
        final Domain domain;
        final CoreBuffer buffer;
        final int capacity;
        final List<FreeRange> free = new ArrayList<>();
        final List<Allocation> allocations = new ArrayList<>();
        final List<Allocation> retired = new ArrayList<>();
        long nextGeneration = 1L;

        Segment(Domain domain, CoreBuffer buffer, int capacity) {
            this.domain = domain;
            this.buffer = buffer;
            this.capacity = capacity;
            this.free.add(new FreeRange(0, capacity));
        }

        Allocation allocate(int size, int alignment) {
            for (int i = 0; i < this.free.size(); i++) {
                FreeRange range = this.free.get(i);
                int aligned = alignOffset(range.offset, alignment);
                long end = (long) aligned + size;
                long rangeEnd = (long) range.offset + range.size;
                if (end > rangeEnd) {
                    continue;
                }
                int prefix = aligned - range.offset;
                int suffix = (int) (rangeEnd - end);
                this.free.remove(i);
                if (suffix > 0) {
                    this.free.add(i, new FreeRange((int) end, suffix));
                }
                if (prefix > 0) {
                    this.free.add(i, new FreeRange(range.offset, prefix));
                }
                return new Allocation(this, aligned, size, 0L);
            }
            return null;
        }

        void addFree(int offset, int size) {
            int index = 0;
            while (index < this.free.size() && this.free.get(index).offset < offset) {
                index++;
            }
            if (index > 0) {
                FreeRange previous = this.free.get(index - 1);
                if ((long) previous.offset + previous.size >= offset) {
                    previous.size = Math.max(previous.size, offset + size - previous.offset);
                    if (index < this.free.size()) {
                        FreeRange next = this.free.get(index);
                        if ((long) previous.offset + previous.size >= next.offset) {
                            previous.size = Math.max(previous.size,
                                    next.offset + next.size - previous.offset);
                            this.free.remove(index);
                        }
                    }
                    return;
                }
            }
            if (index < this.free.size()) {
                FreeRange next = this.free.get(index);
                if ((long) offset + size >= next.offset) {
                    int nextEnd = next.offset + next.size;
                    next.offset = offset;
                    next.size = Math.max(nextEnd, offset + size) - offset;
                    return;
                }
            }
            this.free.add(index, new FreeRange(offset, size));
        }
    }

    private static int alignSize(long size) {
        long aligned = (size + 3L) & ~3L;
        if (aligned <= 0 || aligned > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) aligned;
    }

    private static int alignOffset(int offset, int alignment) {
        long aligned = ((long) offset + alignment - 1L) / alignment * alignment;
        return aligned > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) aligned;
    }

    // The following hooks are package-private and exist only for the allocator JVM smoke. The
    // production path reads these values from WebGPU glue and never calls them.
    void testSubmit() {
        this.testSubmittedEpoch++;
    }

    void testComplete(int epoch) {
        this.testCompletedEpoch = epoch;
    }
}
