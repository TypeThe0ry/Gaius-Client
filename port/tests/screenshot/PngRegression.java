package dev.gaius.browser.test;

import dev.gaius.browser.BrowserPngWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;

public final class PngRegression {
    private PngRegression() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0], "screenshots");
        Files.createDirectories(directory);
        for (int components = 1; components <= 4; components++) {
            byte[] pixels = new byte[13 * 7 * components];
            new Random(components).nextBytes(pixels);
            ByteBuffer input = ByteBuffer.wrap(pixels);
            ShortChannel channel = new ShortChannel();
            BrowserPngWriter.write(channel, input, 13, 7, components);
            require(channel.isOpen() && input.position() == 0, "channel/input ownership changed");
            verify(channel.bytes.toByteArray(), pixels, 13, 7, components);
        }
        byte[] pixels = new byte[3_160_000];
        new Random(12345).nextBytes(pixels);
        Path target = directory.resolve("readback.png");
        long start = System.nanoTime();
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            BrowserPngWriter.write(channel, ByteBuffer.wrap(pixels), 1000, 790, 4);
        }
        long elapsed = System.nanoTime() - start;
        verify(Files.readAllBytes(target), pixels, 1000, 790, 4);
        require(ImageIO.read(target.toFile()).getWidth() == 1000, "independent PNG decoder failed");
        verifyNativeImage(directory, pixels);
        boolean failed = false;
        try {
            BrowserPngWriter.write(new ShortChannel() {
                @Override
                public int write(ByteBuffer source) throws IOException {
                    throw new IOException("expected disk failure");
                }
            }, ByteBuffer.wrap(pixels), 1000, 790, 4);
        } catch (IOException expected) { failed = true; }
        require(failed, "write error swallowed");
        failed = false;
        try { BrowserPngWriter.write(new ShortChannel(), ByteBuffer.allocate(1), 1, 1, 4); }
        catch (IOException expected) { failed = true; }
        require(failed, "truncated input accepted");
        System.out.println("PNG_REGRESSION PASS input=" + pixels.length + " png=" + Files.size(target)
                + " encodeMs=" + elapsed / 1_000_000.0);
    }

    private static void verifyNativeImage(Path directory, byte[] pixels) throws Exception {
        Class<?> type = Class.forName("com.mojang.blaze3d.platform.NativeImage");
        Object image = type.getConstructor(int.class, int.class, boolean.class).newInstance(1000, 790, false);
        var pointer = type.getDeclaredField("pixels");
        pointer.setAccessible(true);
        MemoryUtil.memByteBuffer(pointer.getLong(image), pixels.length).put(pixels);
        Path file = directory.resolve("native-image.png");
        type.getMethod("writeToFile", Path.class).invoke(image, file);
        type.getMethod("close").invoke(image);
        verify(Files.readAllBytes(file), pixels, 1000, 790, 4);
    }

    private static void verify(byte[] png, byte[] pixels, int width, int height, int components) throws Exception {
        ByteBuffer file = ByteBuffer.wrap(png);
        require(file.getLong() == 0x89504e470d0a1a0aL, "PNG signature");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        boolean ended = false;
        while (file.hasRemaining()) {
            int length = file.getInt();
            int type = file.getInt();
            byte[] bytes = new byte[length];
            file.get(bytes);
            CRC32 checksum = new CRC32();
            checksum.update(ByteBuffer.allocate(4).putInt(type).array());
            checksum.update(bytes);
            require(file.getInt() == (int) checksum.getValue(), "chunk CRC");
            if (type == 0x49484452) {
                ByteBuffer header = ByteBuffer.wrap(bytes);
                require(header.getInt() == width && header.getInt() == height, "PNG size");
                require(header.get() == 8, "PNG bit depth");
                require(header.get() == new int[] {0, 4, 2, 6}[components - 1], "PNG color type");
            } else if (type == 0x49444154) data.write(bytes);
            else if (type == 0x49454e44) { ended = true; require(!file.hasRemaining(), "PNG suffix"); }
        }
        require(ended, "missing IEND");
        byte[] decoded = new byte[height * (width * components + 1)];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data.toByteArray());
            int offset = 0;
            while (!inflater.finished() && offset < decoded.length) {
                int count = inflater.inflate(decoded, offset, decoded.length - offset);
                require(count > 0, "truncated zlib data");
                offset += count;
            }
            require(inflater.finished() && offset == decoded.length, "inflated length");
        } finally { inflater.end(); }
        int rowBytes = width * components;
        for (int row = 0; row < height; row++) {
            int start = row * (rowBytes + 1);
            require(decoded[start] == 0, "unexpected filter");
            require(Arrays.equals(decoded, start + 1, start + rowBytes + 1, pixels, row * rowBytes,
                    (row + 1) * rowBytes), "pixel round trip");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static class ShortChannel implements WritableByteChannel {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean open = true;

        @Override
        public int write(ByteBuffer source) throws IOException {
            int count = Math.min(source.remaining(), 3);
            byte[] chunk = new byte[count];
            source.get(chunk);
            bytes.write(chunk);
            return count;
        }

        @Override
        public boolean isOpen() { return open; }

        @Override
        public void close() { open = false; }
    }
}
