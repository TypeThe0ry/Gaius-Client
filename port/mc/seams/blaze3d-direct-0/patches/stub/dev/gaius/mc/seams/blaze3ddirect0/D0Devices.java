package dev.gaius.mc.seams.blaze3ddirect0;

/**
 * JVM stand-in for the seam's {@code D0Devices} in {@code verify-patches.sh}: the patched
 * {@code DefaultVertexFormat.<clinit>} calls {@link #lineWidthPadding()}, whose real version asks
 * the page ({@code ?gpu=webgpu}). Here the system property {@code gaius.d0.webgpu} answers.
 */
public final class D0Devices {
    private D0Devices() {
    }

    public static int lineWidthPadding() {
        return Boolean.getBoolean("gaius.d0.webgpu") ? 1 : 0;
    }
}
