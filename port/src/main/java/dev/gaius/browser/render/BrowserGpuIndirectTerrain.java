package dev.gaius.browser.render;

import java.util.Arrays;
import dev.gaius.gpu.webgpu.CorePass;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.teavm.jso.JSBody;

/**
 * Version-independent inputs for the opt-in GPU terrain culler.
 *
 * <p>The section table remains in vanilla order. Origins are absolute block coordinates because
 * that is what Dynamic*GpuData writes; bounds are derived in camera-relative space exactly once.
 * Plane extraction uses the same projection/view product as the vanilla frustum. This class only
 * owns CPU-side frame data; buffer allocation and device-loss fallback stay in WebGPU runtime.</p>
 */
public final class BrowserGpuIndirectTerrain {
    private static final int BOUND_WORDS = 8;
    private static final int PLANE_WORDS = 24;
    private static Matrix4f clip;
    private static float[] bounds;
    private static float[] projected;
    private static float[] planes;
    private static int sectionCount;
    private static boolean frameReady;
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static final boolean ENABLED = readEnabledSafe();

    private BrowserGpuIndirectTerrain() {
    }

    /** Starts a frame using the matrices and camera origin vanilla used for its cull frustum. */
    public static void beginFrame(Matrix4fc projection, Matrix4fc viewRotation,
            double x, double y, double z) {
        frameReady = false;
        sectionCount = 0;
        if (!ENABLED) {
            return;
        }
        if (clip == null) {
            clip = new Matrix4f();
            bounds = new float[BOUND_WORDS * 512];
            projected = new float[BOUND_WORDS * 512];
            planes = new float[PLANE_WORDS];
        }
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        setTerrainCamera(x, y, z);
        if (projection == null || viewRotation == null
                || !finite(x) || !finite(y) || !finite(z)) {
            return;
        }
        // JOML's matrix multiplication order matches Frustum's calculateFrustum path.
        clip.set(projection).mul(viewRotation);
        extractPlane(0, clip.m03() + clip.m00(), clip.m13() + clip.m10(),
                clip.m23() + clip.m20(), clip.m33() + clip.m30());
        extractPlane(1, clip.m03() - clip.m00(), clip.m13() - clip.m10(),
                clip.m23() - clip.m20(), clip.m33() - clip.m30());
        extractPlane(2, clip.m03() + clip.m01(), clip.m13() + clip.m11(),
                clip.m23() + clip.m21(), clip.m33() + clip.m31());
        extractPlane(3, clip.m03() - clip.m01(), clip.m13() - clip.m11(),
                clip.m23() - clip.m21(), clip.m33() - clip.m31());
        // WebGPU and vanilla browser projection use the 0..1 depth convention.
        extractPlane(4, clip.m02(), clip.m12(), clip.m22(), clip.m32());
        extractPlane(5, clip.m03() - clip.m02(), clip.m13() - clip.m12(),
                clip.m23() - clip.m22(), clip.m33() - clip.m32());
        frameReady = finitePlanes();
    }

    /** Captures the absolute origin/visibility table from Dynamic*GpuData. */
    public static void beginSections(int expected) {
        if (!frameReady) {
            sectionCount = 0;
            return;
        }
        int needed = Math.max(0, expected) * BOUND_WORDS;
        if (bounds.length < needed || projected.length < needed) {
            bounds = Arrays.copyOf(bounds, Math.max(needed, bounds.length * 2));
            projected = Arrays.copyOf(projected, Math.max(needed, projected.length * 2));
        }
        sectionCount = 0;
    }

    @JSBody(script = "return !!(globalThis.__gaiusSwitches && globalThis.__gaiusSwitches.gaiusGpuIndirect === true);")
    private static native boolean readEnabled();

    private static boolean readEnabledSafe() {
        try {
            return readEnabled();
        } catch (LinkageError unavailable) {
            return false;
        }
    }

    @JSBody(params = {"x", "y", "z"},
            script = "if (globalThis.GaiusWebGpu && globalThis.GaiusWebGpu.gpuIndirectSetTerrainCamera) globalThis.GaiusWebGpu.gpuIndirectSetTerrainCamera(x, y, z);")
    private static native void setTerrainCamera(double x, double y, double z);

    public static void addSection(int x, int y, int z, float visibility) {
        if (!frameReady || sectionCount * BOUND_WORDS + BOUND_WORDS > bounds.length) {
            return;
        }
        int at = sectionCount * BOUND_WORDS;
        bounds[at] = (float) (x + 8.0 - cameraX);
        bounds[at + 1] = (float) (y + 8.0 - cameraY);
        bounds[at + 2] = (float) (z + 8.0 - cameraZ);
        bounds[at + 3] = 0.0F;
        bounds[at + 4] = 8.0F;
        bounds[at + 5] = 8.0F;
        bounds[at + 6] = 8.0F;
        // Non-zero visibility marks a conservative/uncertain section for the shader.
        bounds[at + 7] = visibility == 0.0F ? 0.0F : 1.0F;
        projectSection(at, bounds[at + 7]);
        sectionCount++;
    }

    public static int sectionCount() {
        CorePass.publishGpuIndirectTerrain(ENABLED && frameReady, sectionCount, bounds, planes, projected);
        return sectionCount;
    }

    public static boolean requested() {
        return ENABLED;
    }

    public static float[] bounds() {
        return bounds;
    }

    public static float[] planes() {
        return planes;
    }

    /** Projected uv rectangle and zero-to-one depth interval, two vec4 values per section. */
    public static float[] projected() {
        return projected;
    }

    private static void projectSection(int at, float flags) {
        float cx = bounds[at], cy = bounds[at + 1], cz = bounds[at + 2];
        float ex = bounds[at + 4], ey = bounds[at + 5], ez = bounds[at + 6];
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        boolean valid = true;
        for (int ix = -1; ix <= 1; ix += 2) {
            for (int iy = -1; iy <= 1; iy += 2) {
                for (int iz = -1; iz <= 1; iz += 2) {
                    float x = cx + ix * ex, y = cy + iy * ey, z = cz + iz * ez;
                    float w = clip.m03() * x + clip.m13() * y + clip.m23() * z + clip.m33();
                    float qx = clip.m00() * x + clip.m10() * y + clip.m20() * z + clip.m30();
                    float qy = clip.m01() * x + clip.m11() * y + clip.m21() * z + clip.m31();
                    float qz = clip.m02() * x + clip.m12() * y + clip.m22() * z + clip.m32();
                    if (!finite(w) || w <= 1.0e-6F || !finite(qx) || !finite(qy) || !finite(qz)) {
                        valid = false;
                        continue;
                    }
                    float nx = qx / w, ny = qy / w, nz = qz / w;
                    float u = nx * 0.5F + 0.5F, v = ny * 0.5F + 0.5F;
                    minX = Math.min(minX, u); maxX = Math.max(maxX, u);
                    minY = Math.min(minY, v); maxY = Math.max(maxY, v);
                    minZ = Math.min(minZ, nz); maxZ = Math.max(maxZ, nz);
                }
            }
        }
        int out = at;
        if (!valid || !finite(minX) || !finite(minY) || !finite(maxX) || !finite(maxY)
                || !finite(minZ) || !finite(maxZ)) {
            Arrays.fill(projected, out, out + 8, Float.NaN);
            projected[out + 7] = 1.0F;
            return;
        }
        // Expand by a small clip-space safety margin. The shader also keeps rectangles outside
        // the viewport visible, so this never turns an uncertain/off-screen section invisible.
        float margin = 1.0e-4F;
        projected[out] = minX - margin;
        projected[out + 1] = minY - margin;
        projected[out + 2] = minZ;
        projected[out + 3] = 0.0F;
        projected[out + 4] = maxX + margin;
        projected[out + 5] = maxY + margin;
        projected[out + 6] = maxZ;
        projected[out + 7] = flags;
    }

    private static void extractPlane(int plane, float x, float y, float z, float w) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        int at = plane * 4;
        if (!finite(length) || length < 1.0e-6F) {
            Arrays.fill(planes, at, at + 4, Float.NaN);
            return;
        }
        planes[at] = x / length;
        planes[at + 1] = y / length;
        planes[at + 2] = z / length;
        planes[at + 3] = w / length;
    }

    private static boolean finitePlanes() {
        for (float plane : planes) {
            if (!finite(plane)) return false;
        }
        return true;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
