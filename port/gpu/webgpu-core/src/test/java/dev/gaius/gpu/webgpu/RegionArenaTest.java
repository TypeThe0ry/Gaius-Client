package dev.gaius.gpu.webgpu;

/** JVM-only checks for persistent range ownership and GPU-safe reclamation. */
public final class RegionArenaTest {
    public static void main(String[] args) {
        RegionArena arena = new RegionArena(128);
        RegionArena.Domain vertex = arena.domain(1, 7, 0, CoreUsage.BUFFER_VERTEX);

        RegionArena.Lease first = vertex.allocate(128, 16);
        check(first != null && first.offset() == 0 && first.size() == 128, "first allocation");
        long generation = first.generation();
        first.close();
        boolean staleRejected = false;
        try {
            first.slice();
        } catch (IllegalStateException expected) {
            staleRejected = true;
        }
        check(staleRejected, "released leases reject stale slice access");
        check(arena.retiredBytes() == 128, "release is retired");
        check(vertex.allocate(1, 4) == null, "retired range is not reused before completion");

        arena.testSubmit();
        arena.testComplete(1);
        arena.reclaim();
        RegionArena.Lease reused = vertex.allocate(128, 16);
        check(reused != null && reused.offset() == 0, "completed range is reused");
        check(reused.generation() > generation, "generation changes on reuse");
        reused.close();
        checkThrows(reused::close, "duplicate release is rejected");
        arena.testSubmit();
        arena.testComplete(2);
        arena.reclaim();

        RegionArena fragments = new RegionArena(128);
        RegionArena.Domain fragmented = fragments.domain(0, 1, 0, CoreUsage.BUFFER_VERTEX);
        RegionArena.Lease a = fragmented.allocate(16, 4);
        RegionArena.Lease b = fragmented.allocate(16, 4);
        RegionArena.Lease c = fragmented.allocate(16, 4);
        check(a != null && b != null && a.offset() != b.offset(), "live ranges do not overlap");
        a.close();
        b.close();
        fragments.testSubmit();
        fragments.testComplete(1);
        fragments.reclaim();
        RegionArena.Lease merged = fragmented.allocate(32, 4);
        check(merged != null && merged.offset() == 0, "adjacent completed ranges merge");
        c.close();
        merged.close();
        fragments.testSubmit();
        fragments.testComplete(2);
        fragments.reclaim();

        RegionArena isolated = new RegionArena(8 * 1024 * 1024);
        RegionArena.Lease vertexLease = isolated.domain(1, 7, 0, CoreUsage.BUFFER_VERTEX).allocate(16, 16);
        RegionArena.Lease indexLease = isolated.domain(1, 7, 1, CoreUsage.BUFFER_INDEX).allocate(16, 16);
        check(vertexLease != null && indexLease != null && vertexLease.offset() == indexLease.offset(),
                "different domains may use equal offsets without aliasing buffers");
        check(isolated.reservedBytes() <= isolated.budgetBytes(), "domain buffers share the global budget");

        check(RegionArena.reached(0, -1), "epoch comparison handles completion wraparound");
        check(!RegionArena.reached(0, 1), "epoch comparison does not pass a future epoch");

        RegionArena lost = new RegionArena(128);
        RegionArena.Domain lostDomain = lost.domain(0, 1, 0, CoreUsage.BUFFER_VERTEX);
        RegionArena.Lease inFlight = lostDomain.allocate(64, 4);
        lost.testSubmit();
        inFlight.close();
        check(lost.retiredBytes() == 64, "in-flight range waits for completion");
        lost.discardAfterDeviceLoss();
        check(lost.reservedBytes() == 0 && lost.liveBytes() == 0 && lost.retiredBytes() == 0,
                "device loss drops all old arena state without waiting for queue completion");
        checkThrows(inFlight::slice, "device loss invalidates a retired lease");
        check(lostDomain.allocate(16, 4) == null, "old domains fail closed after device loss");
        RegionArena rebuilt = new RegionArena(128);
        RegionArena.Lease afterLoss = rebuilt.domain(0, 1, 0, CoreUsage.BUFFER_VERTEX).allocate(64, 4);
        check(afterLoss != null && afterLoss.offset() == 0,
                "a lazily rebuilt arena starts with fresh domain and epoch state");
        System.out.println("region arena allocator passed");
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void checkThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
