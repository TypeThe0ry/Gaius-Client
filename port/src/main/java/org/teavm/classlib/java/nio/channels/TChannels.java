package org.teavm.classlib.java.nio.channels;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import org.teavm.classlib.java.nio.TByteBuffer;

public final class TChannels {
    private TChannels() {
    }

    public static InputStream newInputStream(TReadableByteChannel channel) {
        return new InputStream() {
            private final TByteBuffer single = TByteBuffer.allocate(1);

            @Override
            public int read() throws IOException {
                single.clear();
                int count = channel.read(single);
                return count < 0 ? -1 : single.get(0) & 255;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                TByteBuffer buffer = TByteBuffer.wrap(bytes, offset, length);
                return channel.read(buffer);
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }

    public static OutputStream newOutputStream(TWritableByteChannel channel) {
        return new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value});
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                TByteBuffer buffer = TByteBuffer.wrap(bytes, offset, length);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }

    public static TReadableByteChannel newChannel(InputStream input) {
        return new TReadableByteChannel() {
            private boolean open = true;

            @Override
            public int read(TByteBuffer target) throws IOException {
                int requested = target.remaining();
                if (requested == 0) {
                    return 0;
                }
                byte[] bytes = new byte[requested];
                int count = input.read(bytes);
                if (count < 0) {
                    return -1;
                }
                target.put(bytes, 0, count);
                return count;
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() throws IOException {
                if (open) {
                    open = false;
                    input.close();
                }
            }
        };
    }

    public static TWritableByteChannel newChannel(OutputStream output) {
        return new TWritableByteChannel() {
            private boolean open = true;

            @Override
            public int write(TByteBuffer source) throws IOException {
                int count = source.remaining();
                byte[] bytes = new byte[count];
                source.get(bytes);
                output.write(bytes);
                return count;
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() throws IOException {
                if (open) {
                    open = false;
                    output.close();
                }
            }
        };
    }

    public static Writer newWriter(TWritableByteChannel channel, Charset charset) {
        return new OutputStreamWriter(newOutputStream(channel), charset);
    }
}
