package dev.gaius.browser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryUtil;

/** Bulk transparent-pixel colour fill for RGBA NativeImage backing memory. */
public final class BrowserTextureSolidifier {
    private BrowserTextureSolidifier() {
    }

    public static void solidifyBulk(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int pixels = checkedPixels(width, height);
        if (image.format() != NativeImage.Format.RGBA) {
            throw new IllegalArgumentException("solidify requires RGBA");
        }
        long pointer = image.getPointer();
        if (pointer == 0L) {
            throw new IllegalStateException("NativeImage is not allocated");
        }
        ByteBuffer backing = MemoryUtil.memByteBuffer(pointer, Math.toIntExact((long) pixels * 4L))
                .order(ByteOrder.nativeOrder());
        solidifyFromAbgrBytes(backing, width, height);
    }

    /* Package-private seam used only by the isolated ByteBuffer fixture. */
    static void solidifyFromAbgrBytes(ByteBuffer backing, int width, int height) {
        int pixels = checkedPixels(width, height);
        if (backing.remaining() < (long) pixels * 4L) {
            throw new IllegalArgumentException("pixel backing is too small");
        }
        int[] source = new int[pixels];
        int opaquePixels = 0;
        for (int i = 0; i < pixels; i++) {
            source[i] = backing.getInt(i * 4);
            if ((source[i] >>> 24) != 0) {
                opaquePixels++;
            }
        }
        if (opaquePixels == pixels) {
            return;
        }

        if (opaquePixels == 0) {
            for (int i = 0; i < pixels; i++) {
                backing.putInt(i * 4, 0);
            }
            return;
        }

        int[] color = new int[pixels];
        int[] queue = new int[Math.max(1, pixels)];
        int head = 0;
        int tail = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int index = y * width + x;
                int pixel = source[index];
                if ((pixel >>> 24) != 0) {
                    color[index] = pixel;
                    queue = push(queue, tail, index);
                    tail++;
                }
            }
        }
        while (head < tail) {
            int index = queue[head++];
            int x = index % width;
            int y = index / width;
            if (x + 1 < width) {
                tail = visit(queue, tail, color, index, y * width + x + 1);
            }
            if (x > 0) {
                tail = visit(queue, tail, color, index, y * width + x - 1);
            }
            if (y + 1 < height) {
                tail = visit(queue, tail, color, index, (y + 1) * width + x);
            }
            if (y > 0) {
                tail = visit(queue, tail, color, index, (y - 1) * width + x);
            }
        }
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int index = y * width + x;
                if ((source[index] >>> 24) == 0) {
                    backing.putInt(index * 4, color[index] & 0x00ffffff);
                }
            }
        }
    }

    private static int[] push(int[] queue, int tail, int value) {
        if (tail >= queue.length) {
            throw new IllegalStateException("BFS enqueue exceeded pixel count");
        }
        queue[tail] = value;
        return queue;
    }

    private static int visit(int[] queue, int tail, int[] color, int from, int next) {
        if ((color[next] >>> 24) == 0) {
            color[next] = color[from];
            push(queue, tail, next);
            return tail + 1;
        }
        return tail;
    }

    private static int checkedPixels(int width, int height) {
        if (width < 0 || height < 0 || (long) width * height > Integer.MAX_VALUE / 4L) {
            throw new IllegalArgumentException("invalid image dimensions");
        }
        return width * height;
    }
}

