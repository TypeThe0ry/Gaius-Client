package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * JVM checks of the core's downgrade table and depth-texture layouts that need no browser (run by
 * {@code port/mc/seams/test-seam.sh renderpearl-1}; the browser side is covered by the WebGPU
 * harnesses):
 * <ul>
 * <li>every topology has a WebGPU topology; TRIANGLE_FAN is the only fan and draws as a triangle
 * list, POINTS and the debug lines stay line/point topologies;</li>
 * <li>index buffers created with data know their sequential (identity) prefixes, 16-bit and
 * 32-bit, and forget them when written again;</li>
 * <li>the depth-texture variant of a layout declares exactly the masked filtered textures
 * "unfilterable-float" with "non-filtering" samplers and leaves everything else alone.</li>
 * </ul>
 * Exits with status 1 on any failure.
 */
public final class CoreDowngradeTest {
    private static int failures;

    private CoreDowngradeTest() {
    }

    public static void main(String[] args) {
        topologies();
        identity();
        depthLayouts();
        capabilities();
        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("all core downgrade checks passed");
    }

    /** multi-draw-indirect never survives without draw-indirect, whatever the glue reported. */
    private static void capabilities() {
        java.util.Set<String> legacy = CoreDevice.consistentCapabilities(
                new java.util.HashSet<>(List.of("multi-draw-indirect", "non-zero-first-instance")));
        check(!legacy.contains("multi-draw-indirect") && legacy.contains("non-zero-first-instance"),
                "multi-draw-indirect dropped without draw-indirect");
        java.util.Set<String> full = CoreDevice.consistentCapabilities(
                new java.util.HashSet<>(List.of("multi-draw-indirect", "draw-indirect")));
        check(full.contains("multi-draw-indirect") && full.contains("draw-indirect"),
                "multi-draw-indirect kept with draw-indirect");
        System.out.println("ok   indirect capability consistency");
    }

    private static void topologies() {
        for (CoreTopology topology : CoreTopology.values()) {
            check(topology.webName() != null, topology + " has a WebGPU topology");
            check(topology.isFan() == (topology == CoreTopology.TRIANGLE_FAN), topology + " fan flag");
        }
        check("triangle-list".equals(CoreTopology.TRIANGLE_FAN.webName()), "TRIANGLE_FAN draws as a triangle list");
        check("point-list".equals(CoreTopology.POINTS.webName()) && CoreTopology.POINTS.isLineOrPoint(),
                "POINTS stays a point list");
        check("triangle-list".equals(CoreTopology.QUADS.webName()), "QUADS arrive indexed as triangles");
        System.out.println("ok   topology downgrade table");
    }

    private static ByteBuffer shorts(int... values) {
        ByteBuffer out = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            out.putShort((short) value);
        }
        return out.flip();
    }

    private static ByteBuffer ints(int... values) {
        ByteBuffer out = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            out.putInt(value);
        }
        return out.flip();
    }

    private static void identity() {
        CoreBuffer sequential16 = new CoreBuffer(null, CoreUsage.BUFFER_INDEX, 16, 1, false);
        sequential16.scanIdentity(shorts(0, 1, 2, 3, 4, 5, 6, 7));
        check(sequential16.isIdentity(false, 0, 8), "16-bit sequence is an identity");
        check(sequential16.isIdentity(false, 2, 6), "a window of it too");
        check(!sequential16.isIdentity(false, 2, 7), "but not past its end");
        check(!sequential16.isIdentity(true, 0, 2), "read as 32-bit it is not");

        CoreBuffer sequential32 = new CoreBuffer(null, CoreUsage.BUFFER_INDEX, 16, 2, false);
        sequential32.scanIdentity(ints(0, 1, 2, 3));
        check(sequential32.isIdentity(true, 0, 4), "32-bit sequence is an identity");
        check(!sequential32.isIdentity(false, 0, 2), "read as 16-bit it is not (0, 0, 1, 0)");

        CoreBuffer quads = new CoreBuffer(null, CoreUsage.BUFFER_INDEX, 12, 3, false);
        quads.scanIdentity(shorts(0, 1, 2, 2, 3, 0));
        check(quads.isIdentity(false, 0, 3) && !quads.isIdentity(false, 0, 4), "a quad index buffer: prefix of 3");

        ByteBuffer offset = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        offset.putShort((short) 9).putShort((short) 9).putShort((short) 0).putShort((short) 1).putShort((short) 2)
                .putShort((short) 3);
        offset.position(4);
        CoreBuffer positioned = new CoreBuffer(null, CoreUsage.BUFFER_INDEX, 8, 4, false);
        positioned.scanIdentity(offset);
        check(positioned.isIdentity(false, 0, 4), "the scan starts at the data's position");

        sequential16.contentsChanged();
        check(!sequential16.isIdentity(false, 0, 1), "a written buffer forgets its identity prefix");
        System.out.println("ok   sequential index buffers");
    }

    private static void depthLayouts() {
        List<CorePipelineDesc.Uniform> uniforms = List.of(
                new CorePipelineDesc.Uniform("Globals", CoreUniformType.UNIFORM_BUFFER, null),
                new CorePipelineDesc.Uniform("InSampler", CoreUniformType.COMBINED_IMAGE_SAMPLER, null),
                new CorePipelineDesc.Uniform("Other", CoreUniformType.COMBINED_IMAGE_SAMPLER, null));
        BindingLayout layout = BindingLayout.split(uniforms);
        check(!layout.filtered[0] && layout.filtered[1] && layout.filtered[2], "split textures are filtered");
        BindingLayout variant = layout.unfilterable(1L << 1);
        String expected = "[[0,0,\"ubo\"],[1,2,\"texture\",\"unfilterable-float\",\"2d\",false],"
                + "[1,3,\"sampler\",\"non-filtering\"],[1,4,\"texture\",\"float\",\"2d\",false],"
                + "[1,5,\"sampler\",\"filtering\"]]";
        check(expected.equals(variant.json), "variant layout: " + variant.json);
        check(variant.depthSampled[1] && !variant.depthSampled[2] && !variant.depthSampled[0],
                "only the masked texture samples a depth texture");
        check(layout.json.equals("[[0,0,\"ubo\"],[1,2,\"texture\",\"float\",\"2d\",false],[1,3,\"sampler\",\"filtering\"],"
                + "[1,4,\"texture\",\"float\",\"2d\",false],[1,5,\"sampler\",\"filtering\"]]"), "base layout unchanged");
        check(layout.unfilterable(1L).json.equals(layout.json), "a mask on a uniform buffer changes nothing");
        System.out.println("ok   depth-texture layout variants");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
