package org.lwjgl.system;

/** Browser-backed allocator exposed through LWJGL's public allocator API. */
public final class BrowserMemoryAllocator implements MemoryUtil.MemoryAllocator {
    private static final BrowserMemoryAllocator INSTANCE = new BrowserMemoryAllocator();

    private BrowserMemoryAllocator() {
    }

    public static MemoryUtil.MemoryAllocator instance() {
        return INSTANCE;
    }

    @Override
    public long getMalloc() {
        return 1L;
    }

    @Override
    public long getCalloc() {
        return 1L;
    }

    @Override
    public long getRealloc() {
        return 1L;
    }

    @Override
    public long getFree() {
        return 1L;
    }

    @Override
    public long getAlignedAlloc() {
        return 1L;
    }

    @Override
    public long getAlignedFree() {
        return 1L;
    }

    @Override
    public long malloc(long byteCount) {
        return BrowserMemory.allocate(byteCount);
    }

    @Override
    public long calloc(long count, long size) {
        return BrowserMemory.calloc(count, size);
    }

    @Override
    public long realloc(long address, long byteCount) {
        return BrowserMemory.reallocate(address, byteCount);
    }

    @Override
    public void free(long address) {
        BrowserMemory.free(address);
    }

    @Override
    public long aligned_alloc(long alignment, long byteCount) {
        return BrowserMemory.alignedAllocate(alignment, byteCount);
    }

    @Override
    public void aligned_free(long address) {
        BrowserMemory.free(address);
    }
}
