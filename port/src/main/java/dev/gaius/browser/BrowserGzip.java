package dev.gaius.browser;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Reads structure NBT without suspending its synchronous resource-loader callback. */
public final class BrowserGzip {
    private BrowserGzip() {
    }

    public static CompoundTag readCompressedNbt(InputStream input) throws IOException {
        // Structure loaders can be invoked from a native JS callback, outside a
        // TeaVM thread. Keep GZIP and parsing synchronous, while replacing the
        // repeated DataInputStream primitive dispatch with direct byte reads.
        Objects.requireNonNull(input, "input");
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        try (GZIPInputStream gzip = new GZIPInputStream(input)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = gzip.read(buffer, 0, buffer.length)) >= 0) {
                if (count != 0) {
                    output.write(buffer, 0, count);
                }
            }
            return NbtIo.read(new ByteArrayDataInput(output.toByteArray()),
                    NbtAccounter.unlimitedHeap());
        }
    }

    private static final class ByteArrayDataInput implements DataInput {
        private final byte[] bytes;
        private int position;

        ByteArrayDataInput(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
        }

        private int require(int length) throws EOFException {
            if (length < 0 || position > bytes.length - length) {
                throw new EOFException("NBT input ended at " + position
                        + " while reading " + length + " bytes");
            }
            int offset = position;
            position += length;
            return offset;
        }

        @Override public void readFully(byte[] target) throws IOException {
            readFully(target, 0, target.length);
        }
        @Override public void readFully(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            System.arraycopy(bytes, require(length), target, offset, length);
        }
        @Override public int skipBytes(int length) throws IOException {
            if (length <= 0) return 0;
            int skipped = Math.min(length, bytes.length - position);
            position += skipped;
            return skipped;
        }
        @Override public boolean readBoolean() throws IOException { return readUnsignedByte() != 0; }
        @Override public byte readByte() throws IOException { return bytes[require(1)]; }
        @Override public int readUnsignedByte() throws IOException { return bytes[require(1)] & 0xff; }
        @Override public short readShort() throws IOException {
            int offset = require(2);
            return (short) ((bytes[offset] & 0xff) << 8 | (bytes[offset + 1] & 0xff));
        }
        @Override public int readUnsignedShort() throws IOException {
            int offset = require(2);
            return (bytes[offset] & 0xff) << 8 | (bytes[offset + 1] & 0xff);
        }
        @Override public char readChar() throws IOException { return (char) readUnsignedShort(); }
        @Override public int readInt() throws IOException {
            int offset = require(4);
            return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16
                    | (bytes[offset + 2] & 0xff) << 8 | (bytes[offset + 3] & 0xff);
        }
        @Override public long readLong() throws IOException {
            return ((long) readInt() << 32) | (readInt() & 0xffffffffL);
        }
        @Override public float readFloat() throws IOException { return Float.intBitsToFloat(readInt()); }
        @Override public double readDouble() throws IOException { return Double.longBitsToDouble(readLong()); }
        @Override public String readLine() throws IOException {
            StringBuilder line = new StringBuilder();
            boolean readAny = false;
            while (position < bytes.length) {
                int value = readUnsignedByte();
                readAny = true;
                if (value == '\n') break;
                if (value == '\r') {
                    if (position < bytes.length && (bytes[position] & 0xff) == '\n') position++;
                    break;
                }
                line.append((char) value);
            }
            return readAny ? line.toString() : null;
        }
        @Override public String readUTF() throws IOException { return DataInputStream.readUTF(this); }
    }
}
