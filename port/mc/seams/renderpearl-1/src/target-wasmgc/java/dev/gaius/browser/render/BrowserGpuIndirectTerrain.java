package dev.gaius.browser.render;

import org.joml.Matrix4fc;

/** WasmGC seam placeholder; the indirect terrain experiment is JS-target only. */
public final class BrowserGpuIndirectTerrain {
    private BrowserGpuIndirectTerrain() {
    }

    public static void beginFrame(Matrix4fc projection, Matrix4fc viewRotation,
            double x, double y, double z) {
    }

    public static void beginSections(int expected) {
    }

    public static void addSection(int x, int y, int z, float visibility) {
    }

    public static boolean requested() {
        return false;
    }

    public static int sectionCount() {
        return 0;
    }

    public static float[] bounds() {
        return new float[0];
    }

    public static float[] planes() {
        return new float[0];
    }

    public static float[] projected() {
        return new float[0];
    }
}
