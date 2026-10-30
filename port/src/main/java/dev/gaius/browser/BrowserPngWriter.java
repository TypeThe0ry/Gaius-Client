package dev.gaius.browser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.lwjgl.system.MemoryUtil;

public final class BrowserPngWriter {
    private BrowserPngWriter() {
    }

    public static boolean write(WritableByteChannel channel, long pixels, int width, int height,
            int components) throws IOException {
        if (pixels == 0) throw new IllegalStateException("Image is not allocated");
        return write(channel, MemoryUtil.memByteBuffer(pixels,
                Math.multiplyExact(Math.multiplyExact(width, height), components)), width, height, components);
    }

    public static boolean write(WritableByteChannel channel, ByteBuffer pixels, int width, int height,
            int components) throws IOException {
        if (width <= 0 || height <= 0 || components < 1 || components > 4) {
            throw new IOException("Invalid PNG dimensions or component count");
        }
        int rowBytes = Math.multiplyExact(width, components);
        int size = Math.multiplyExact(rowBytes, height);
        if (pixels.remaining() < size) throw new IOException("Truncated PNG pixels");
        ByteBuffer input = pixels.duplicate();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater();
        byte[] row = new byte[Math.addExact(rowBytes, 1)];
        byte[] output = new byte[8192];
        try {
            for (int rowIndex = 0; rowIndex < height; rowIndex++) {
                input.get(row, 1, rowBytes);
                deflater.setInput(row);
                while (!deflater.needsInput()) {
                    int count = deflater.deflate(output);
                    if (count == 0 && !deflater.needsInput()) throw new IOException("PNG compressor made no progress");
                    compressed.write(output, 0, count);
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                int count = deflater.deflate(output);
                if (count == 0 && !deflater.finished()) throw new IOException("PNG compressor did not finish");
                compressed.write(output, 0, count);
            }
        } finally {
            deflater.end();
        }
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(headerBytes);
        header.writeInt(width);
        header.writeInt(height);
        header.writeByte(8);
        header.writeByte(switch (components) {
            case 1 -> 0;
            case 2 -> 4;
            case 3 -> 2;
            default -> 6;
        });
        header.write(new byte[3]);
        writeFully(channel, ByteBuffer.wrap(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}));
        writeChunk(channel, 0x49484452, headerBytes.toByteArray());
        writeChunk(channel, 0x49444154, compressed.toByteArray());
        writeChunk(channel, 0x49454e44, new byte[0]);
        return true;
    }

    private static void writeChunk(WritableByteChannel channel, int type, byte[] data) throws IOException {
        ByteBuffer prefix = ByteBuffer.allocate(8).putInt(data.length).putInt(type);
        prefix.flip();
        CRC32 checksum = new CRC32();
        checksum.update(prefix.array(), 4, 4);
        checksum.update(data);
        writeFully(channel, prefix);
        writeFully(channel, ByteBuffer.wrap(data));
        ByteBuffer suffix = ByteBuffer.allocate(4).putInt((int) checksum.getValue());
        suffix.flip();
        writeFully(channel, suffix);
    }

    private static void writeFully(WritableByteChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            if (channel.write(bytes) <= 0) throw new IOException("PNG output channel made no progress");
        }
    }
}
