package dev.gaius.gpu.webgpu;

/** Verifies GPU-indirect inputs fail closed before a browser device or glue is required. */
public final class CoreGpuIndirectValidationTest {
    public static void main(String[] args) {
        CoreEncoder encoder = new CoreEncoder(null, null);
        CorePass pass = encoder.pass();
        check(!encoder.encodeGpuIndirectFrustum(null, null, null, null, 1), "null frustum buffers");
        check(!pass.encodeGpuIndirectFrustum(null, null, null, null, 1), "pass delegates null frustum buffers");
        check(!pass.encodeGpuIndirectHiZ(null, null, null, null, null, null, 1), "null Hi-Z resources");

        int storage = CoreUsage.BUFFER_TEXEL;
        CoreBuffer input = buffer(storage, 20L * 64L, 1);
        CoreBuffer bounds = buffer(storage, 32L * 64L, 2);
        CoreBuffer planes = buffer(storage, 96L, 3);
        CoreBuffer output = buffer(storage | CoreUsage.BUFFER_INDIRECT, 20L * 64L, 4);

        check(!encoder.encodeGpuIndirectFrustum(input, bounds, planes, output, 0), "zero records");
        check(!encoder.encodeGpuIndirectFrustum(input, bounds, planes, input, 64), "aliased output");
        check(!encoder.encodeGpuIndirectFrustum(buffer(CoreUsage.BUFFER_VERTEX, 1280, 5), bounds,
                planes, output, 64), "input without storage usage");
        check(!encoder.encodeGpuIndirectFrustum(input, buffer(storage, 2047, 6), planes, output, 64),
                "short bounds buffer");
        check(!encoder.encodeGpuIndirectFrustum(input, bounds, planes, buffer(storage, 1280, 7), 64),
                "output without indirect usage");
        check(!encoder.encodeGpuIndirectFrustum(input, bounds, planes, output, 64),
                "valid resources still require an enabled device capability");

        pass.drawIndexedIndirectGpu(null, null, 0, 1);
        pass.drawIndexedIndirectGpu(output, input, -4, 1);
        System.out.println("GPU indirect core validation passed: invalid inputs fail closed");
    }

    private static CoreBuffer buffer(int usage, long size, int handle) {
        return new CoreBuffer(null, usage, size, handle, false);
    }

    private static void check(boolean value, String name) {
        if (!value) {
            throw new AssertionError(name);
        }
    }
}
