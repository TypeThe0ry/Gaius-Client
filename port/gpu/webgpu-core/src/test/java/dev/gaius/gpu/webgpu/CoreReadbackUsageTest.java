package dev.gaius.gpu.webgpu;

/** Internal compact readbacks need COPY_SRC without violating mapped-buffer usage restrictions. */
public final class CoreReadbackUsageTest {
    public static void main(String[] args) {
        for (int usage : new int[] {0, CoreUsage.BUFFER_COPY_DST, CoreUsage.BUFFER_VERTEX,
                CoreUsage.BUFFER_UNIFORM, CoreUsage.BUFFER_INDEX, CoreUsage.BUFFER_MAP_WRITE}) {
            int web = CoreUsage.webBufferUsage(usage);
            if ((web & (CoreUsage.WEB_BUFFER_COPY_SRC | CoreUsage.WEB_BUFFER_COPY_DST))
                    != (CoreUsage.WEB_BUFFER_COPY_SRC | CoreUsage.WEB_BUFFER_COPY_DST)) {
                throw new AssertionError("Compact readback requires internal source/destination copies: " + usage);
            }
        }
        int mapped = CoreUsage.webBufferUsage(CoreUsage.BUFFER_MAP_READ | CoreUsage.BUFFER_COPY_SRC);
        if (mapped != (CoreUsage.WEB_BUFFER_MAP_READ | CoreUsage.WEB_BUFFER_COPY_DST)) {
            throw new AssertionError("MAP_READ buffers may only combine COPY_DST");
        }
        System.out.println("Core readback usage checks passed: 7");
    }
}
