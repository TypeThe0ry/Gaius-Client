package dev.gaius.gpu.webgpu;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import sun.misc.Unsafe;

/** Exercises the Java bind path without calling the browser's native glue. */
public final class CoreUniformBindingTest {
    public static void main(String[] args) throws Exception {
        CorePass pass = pass(256);
        CoreBuffer buffer = new CoreBuffer(null, CoreUsage.BUFFER_UNIFORM, 1024, 7, false);
        pass.setBuffer(0, buffer, 0, 12);
        equal(16, lengths(pass)[0], "std140 padding");
        rejects(() -> pass.setBuffer(0, buffer, 1, 12), "unaligned initial offset");
        rejects(() -> pass.moveBuffer(0, 4), "unaligned moved offset");
        rejects(() -> pass.moveBuffer(0, -256), "negative moved offset");
        rejects(() -> pass.moveBuffer(0, 1024), "out-of-range moved offset");
        equal(0, offsets(pass)[0], "failed move must preserve the bound offset");
        if (!pass.moveBuffer(0, 768)) {
            throw new AssertionError("valid aligned move must keep the binding");
        }
        equal(768, offsets(pass)[0], "valid moved offset");
        rejects(() -> pass.setBuffer(0, buffer, -256, 12), "negative initial offset");
        rejects(() -> pass.setBuffer(0, buffer, 0, 0), "zero length");
        rejects(() -> pass.setBuffer(0, buffer, 0, -1), "negative length");
        rejects(() -> pass.setBuffer(0, buffer, 768, 257), "slice beyond logical size");
        rejects(() -> pass.setBuffer(0, buffer, 256, Integer.MAX_VALUE), "overflowing range");
        CoreBuffer large = new CoreBuffer(null, CoreUsage.BUFFER_UNIFORM, 131072, 8, false);
        pass.setBuffer(0, large, 65536, 65536);
        rejects(() -> pass.setBuffer(0, large, 0, 65537), "binding over 64 KiB");
        CorePass raisedLimit = pass(256, 131072);
        raisedLimit.setBuffer(0, large, 0, 65537);
        equal(65552, lengths(raisedLimit)[0], "raised-limit std140 binding");
        raisedLimit.moveBuffer(0, 256);
        rejects(() -> raisedLimit.moveBuffer(0, 65536), "raised-limit padded range");
        raisedLimit.setBuffer(0, large, 0, 131072);
        CoreBuffer larger = new CoreBuffer(null, CoreUsage.BUFFER_UNIFORM, 262144, 11, false);
        rejects(() -> raisedLimit.setBuffer(0, larger, 0, 131073), "binding above actual device limit");
        CoreBuffer padded = new CoreBuffer(null, CoreUsage.BUFFER_UNIFORM, 12, 9, false);
        pass.setBuffer(0, padded, 0, 12);
        if (!pass.moveBuffer(0, 0)) {
            throw new AssertionError("logical tail with physical std140 padding must remain valid");
        }
        CoreBuffer transientTail = new CoreBuffer(null, CoreUsage.BUFFER_UNIFORM, 268, 10, true);
        rejects(() -> pass.setBuffer(0, transientTail, 256, 12), "transient tail lacks std140 padding");
        CorePass smallerAlignment = pass(64);
        smallerAlignment.setBuffer(0, buffer, 64, 12);
        rejects(() -> smallerAlignment.moveBuffer(0, 96), "device-specific alignment");
        System.out.println("Core uniform binding validation passed: ranges, padding, alignment and moved slices");
    }

    private static CorePass pass(int alignment) throws Exception {
        return pass(alignment, 65536);
    }

    private static CorePass pass(int alignment, int uniformLimit) throws Exception {
        // Constructors of CoreDevice enter native JS; allocate only that boundary object and
        // inject its real caps. CoreEncoder, CorePass, CoreBuffer and BindingLayout stay real.
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        CoreDevice device = (CoreDevice) ((Unsafe) singleton.get(null)).allocateInstance(CoreDevice.class);
        set(device, "caps", new CoreCaps("", "", "", false, Set.of(), alignment, 8192, 1L << 30, 8, uniformLimit));
        CorePass pass = new CoreEncoder(device, null).pass();
        CorePipeline pipeline = (CorePipeline) ((Unsafe) singleton.get(null)).allocateInstance(CorePipeline.class);
        set(pipeline, "uniformNames", new String[]{"Transform"});
        set(pipeline, "layout", BindingLayout.split(List.of(
                new CorePipelineDesc.Uniform("Transform", CoreUniformType.UNIFORM_BUFFER, null))));
        set(pass, "pipeline", pipeline);
        return pass;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static int[] offsets(CorePass pass) throws Exception {
        return values(pass, "resourceOffset");
    }

    private static int[] lengths(CorePass pass) throws Exception {
        return values(pass, "resourceLength");
    }

    private static int[] values(CorePass pass, String name) throws Exception {
        Field field = CorePass.class.getDeclaredField(name);
        field.setAccessible(true);
        return (int[]) field.get(pass);
    }

    private static void equal(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void rejects(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(label + " must fail before reaching WebGPU");
    }
}
