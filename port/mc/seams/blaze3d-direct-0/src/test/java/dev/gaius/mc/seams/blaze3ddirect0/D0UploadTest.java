package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.TextureFormat;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The texel expansion of {@code D0Encoder.expand} against GL's unpacking (JVM test, run by
 * {@code port/mc/seams/test-seam.sh blaze3d-direct-0}): 1.21.11's GL backend uploads a NativeImage
 * with {@code GlConst.toGl(format)} (LUMINANCE as {@code GL_RED}, LUMINANCE_ALPHA as {@code GL_RG},
 * RGB as {@code GL_RGB}), and GL fills a missing green or blue with 0 and a missing alpha with 1;
 * a {@code RED8} texture keeps the first channel. Also a region (source offset, row length).
 * Exits with status 1 on any failure.
 */
public final class D0UploadTest {
    private static int failures;

    private D0UploadTest() {
    }

    public static void main(String[] args) {
        // Two texels per format: (10, 20, 30, 40) and (50, 60, 70, 80), as many channels as it has.
        check(NativeImage.Format.RGB, TextureFormat.RGBA8, new int[] { 10, 20, 30, 50, 60, 70 },
                new int[] { 10, 20, 30, 255, 50, 60, 70, 255 });
        check(NativeImage.Format.LUMINANCE, TextureFormat.RGBA8, new int[] { 10, 50 },
                new int[] { 10, 0, 0, 255, 50, 0, 0, 255 });
        check(NativeImage.Format.LUMINANCE_ALPHA, TextureFormat.RGBA8, new int[] { 10, 20, 50, 60 },
                new int[] { 10, 20, 0, 255, 50, 60, 0, 255 });
        check(NativeImage.Format.RGBA, TextureFormat.RED8, new int[] { 10, 20, 30, 40, 50, 60, 70, 80 },
                new int[] { 10, 50 });
        check(NativeImage.Format.RGB, TextureFormat.RED8, new int[] { 10, 20, 30, 50, 60, 70 }, new int[] { 10, 50 });
        check(NativeImage.Format.LUMINANCE_ALPHA, TextureFormat.RED8, new int[] { 200, 20, 250, 60 },
                new int[] { 200, 250 });
        // A 1x2 region at (1, 1) of a 3x3 RGB image (row length 9 bytes).
        int[] image = new int[27];
        for (int i = 0; i < image.length; i++) {
            image[i] = i;
        }
        int[] region = expand(image, 9, 3, TextureFormat.RGBA8, 1, 2, 1, 1);
        expect("RGB region (1, 1) 1x2 into RGBA8", region, new int[] { 12, 13, 14, 255, 21, 22, 23, 255 });
        try {
            expand(new int[] { 1, 2, 3 }, 3, 3, TextureFormat.DEPTH32, 1, 1, 0, 0);
            fail("RGB into DEPTH32 was accepted");
        } catch (IllegalArgumentException expected) {
            System.out.println("ok   RGB into DEPTH32 is refused");
        }
        if (failures > 0) {
            System.out.println(failures + " upload check(s) failed");
            System.exit(1);
        }
        System.out.println("all blaze3d-direct-0 upload expansions match GL's unpacking");
    }

    private static void check(NativeImage.Format format, TextureFormat target, int[] source, int[] expected) {
        int components = format.components();
        int width = source.length / components;
        expect(format + " into " + target, expand(source, width * components, components, target, width, 1, 0, 0),
                expected);
    }

    private static int[] expand(int[] source, int row, int components, TextureFormat target, int width, int height,
            int x, int y) {
        ByteBuffer in = ByteBuffer.allocate(source.length);
        for (int value : source) {
            in.put((byte) value);
        }
        in.flip();
        ByteBuffer out = D0Encoder.expand(in, row, components, target, width, height, x, y);
        int[] result = new int[out.remaining()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(out.position() + i) & 0xFF;
        }
        return result;
    }

    private static void expect(String what, int[] actual, int[] expected) {
        if (Arrays.equals(actual, expected)) {
            System.out.println("ok   " + what);
        } else {
            fail(what + ": " + Arrays.toString(actual) + ", expected " + Arrays.toString(expected));
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }
}
