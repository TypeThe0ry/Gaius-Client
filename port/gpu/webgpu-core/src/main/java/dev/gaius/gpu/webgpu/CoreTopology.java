package dev.gaius.gpu.webgpu;

/**
 * Primitive topologies (renderpearl {@code PrimitiveTopology} names) and the core's downgrade
 * table for what WebGPU cannot draw directly (shared by every seam):
 *
 * <ul>
 * <li>QUADS and LINES: vanilla indexes them itself (its shared quad and line index buffers;
 * lines are expanded to quads in the vertex shader), so they arrive as triangle lists.</li>
 * <li>TRIANGLE_FAN: a triangle list. {@link CorePass} draws a fan of n vertices through the
 * device's fan index buffer (0, k, k + 1 for k = 1 .. n - 2: the same triangles, with the same
 * winding, as OpenGL and Vulkan fans); an indexed fan is converted the same way when its index
 * buffer is an identity sequence (vanilla's shared sequential buffer), other indexed fans are
 * skipped and counted.</li>
 * <li>POINTS: a point list. WebGPU points are one pixel; vanilla's only point pipeline is the
 * debug renderer's, whose point size is therefore ignored (counted when it is created).</li>
 * </ul>
 */
public enum CoreTopology {
    LINES("triangle-list", false, false),
    DEBUG_LINES("line-list", true, false),
    DEBUG_LINE_STRIP("line-strip", true, false),
    POINTS("point-list", true, false),
    TRIANGLES("triangle-list", false, false),
    TRIANGLE_STRIP("triangle-strip", false, false),
    TRIANGLE_FAN("triangle-list", false, true),
    QUADS("triangle-list", false, false);

    private final String web;
    private final boolean lineOrPoint;
    private final boolean fan;

    CoreTopology(String web, boolean lineOrPoint, boolean fan) {
        this.web = web;
        this.lineOrPoint = lineOrPoint;
        this.fan = fan;
    }

    /** The WebGPU primitive topology the pipeline is created with. */
    public String webName() {
        return this.web;
    }

    /** Line and point topologies must not carry a depth bias in WebGPU. */
    public boolean isLineOrPoint() {
        return this.lineOrPoint;
    }

    /** Draws are converted to triangle lists through a fan index buffer ({@link CorePass}). */
    public boolean isFan() {
        return this.fan;
    }
}
