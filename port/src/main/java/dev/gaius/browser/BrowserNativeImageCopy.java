package dev.gaius.browser;

import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryUtil;

/**
 * Row-copy fast path for {@code NativeImage.copyRect(NativeImage, ...)}, patched in by
 * {@code MinecraftClientPatcher.patchNativeImageBulkCopyRect}.
 *
 * <p>Vanilla copies one pixel at a time through {@code getPixelABGR}/{@code setPixelABGR}. In the
 * browser every pixel then costs long (BigInt) address arithmetic plus a memory-region lookup, and
 * the title screen's panorama alone spent about 2.5 s in that loop at startup. When both images are
 * RGBA, nothing is flipped, the images differ (no overlapping self-copy) and the rectangle lies
 * inside both, each row is the same contiguous run of bytes, so it is copied with one
 * {@code memCopy}. Every other case returns {@code false} and the vanilla loop runs unchanged,
 * including its exceptions.
 */
public final class BrowserNativeImageCopy {
    private BrowserNativeImageCopy() {
    }

    public static boolean copyRect(NativeImage source, NativeImage target, int sourceX, int sourceY,
            int targetX, int targetY, int sizeX, int sizeY, boolean swapX, boolean swapY) {
        if (swapX || swapY || source == target || target == null || sizeX <= 0 || sizeY <= 0) {
            return false;
        }
        if (source.format() != NativeImage.Format.RGBA || target.format() != NativeImage.Format.RGBA) {
            return false;
        }
        long sourcePixels = source.getPointer();
        long targetPixels = target.getPointer();
        if (sourcePixels == 0L || targetPixels == 0L) {
            return false;
        }
        int sourceWidth = source.getWidth();
        int targetWidth = target.getWidth();
        if (sourceX < 0 || sourceY < 0 || targetX < 0 || targetY < 0
                || sourceX > sourceWidth - sizeX || sourceY > source.getHeight() - sizeY
                || targetX > targetWidth - sizeX || targetY > target.getHeight() - sizeY) {
            return false;
        }
        long rowBytes = (long) sizeX * 4L;
        for (int y = 0; y < sizeY; y++) {
            long from = sourcePixels + ((long) (sourceY + y) * sourceWidth + sourceX) * 4L;
            long to = targetPixels + ((long) (targetY + y) * targetWidth + targetX) * 4L;
            MemoryUtil.memCopy(from, to, rowBytes);
        }
        return true;
    }
}
