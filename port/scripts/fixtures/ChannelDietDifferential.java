import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import org.teavm.classlib.java.nio.TByteBuffer;
import org.teavm.classlib.java.nio.channels.TChannels;
import org.teavm.classlib.java.nio.channels.TReadableByteChannel;

/**
 * Runs every scenario twice, on TChannels.newChannel(InputStream) of port/src and on {@link Reference}
 * (the read path as it was before the allocation diet), and compares SHA-256 traces of everything a
 * caller can observe: per read the target's position, limit and capacity before and after, the
 * return value, the target's whole content, and exceptions; then close/isOpen and a read after
 * close. Any difference is a MISMATCH line and exit status 1.
 *
 * <ul>
 *   <li>readResource: TextureUtil.readResource(InputStream) on a non-seekable channel (initial
 *       8192 bytes, read until -1, double the capacity whenever the buffer is full, keeping the
 *       position as MemoryUtil.memRealloc does), for every resource of the validated vanilla pack
 *       and for synthetic sizes around the doubling points.</li>
 *   <li>edge: random target windows (position/limit inside larger buffers, slices, zero remaining,
 *       read-only targets, reads after end of stream, shrinking and growing requests) over
 *       ByteArrayInputStream and streams that keep the exact-size array (subclass, short reads,
 *       a read(byte[]) of its own, a failing stream).</li>
 * </ul>
 *   java ChannelDietDifferential [pack=<vanilla-assets.pack.gz>]
 */
public final class ChannelDietDifferential {
    private static long checks;

    interface Factory {
        TReadableByteChannel open(InputStream input);
    }

    private static final Factory CURRENT = TChannels::newChannel;
    private static final Factory REFERENCE = Reference::new;
    private static int mismatches;

    public static void main(String[] args) throws Exception {
        MessageDigest all = MessageDigest.getInstance("SHA-256");
        int resources = 0;
        long bytes = 0;
        for (String arg : args) {
            if (!arg.startsWith("pack=")) {
                throw new IllegalArgumentException(arg);
            }
            for (var input : ReaderDietDifferential.packEntries(new File(arg.substring(5)))) {
                resources++;
                bytes += input.bytes().length;
                compare(all, input.name() + " readResource",
                        f -> readResource(f, new ByteArrayInputStream(input.bytes())));
            }
        }
        Random random = new Random(20261113);
        int synthetic = 0;
        for (int size : new int[] {0, 1, 2, 63, 64, 4095, 4096, 8191, 8192, 8193, 12288, 16383, 16384, 16385,
                24576, 32767, 32768, 32769, 65536, 65537, 100000, 262144, 262145, 1 << 20}) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            for (int kind = 0; kind < Streams.KINDS; kind++) {
                synthetic++;
                int k = kind;
                compare(all, "size " + size + " kind " + kind + " readResource",
                        f -> readResource(f, Streams.of(data, k)));
                compare(all, "size " + size + " kind " + kind + " edge", f -> edge(f, data, k, size * 31 + k));
            }
        }
        System.out.println("CHANNEL_DIET resources=" + resources + " bytes=" + bytes + " synthetic=" + synthetic
                + " checks=" + checks + " mismatches=" + mismatches + " sha256=" + HexFormat.of().formatHex(all.digest()));
        if (mismatches != 0) {
            System.exit(1);
        }
    }

    interface Scenario {
        String run(Factory factory) throws Exception;
    }

    private static void compare(MessageDigest all, String name, Scenario scenario) throws Exception {
        String current = scenario.run(CURRENT);
        String reference = scenario.run(REFERENCE);
        all.update((name + " " + current + "\n").getBytes(StandardCharsets.UTF_8));
        if (!current.equals(reference)) {
            mismatches++;
            System.out.println("MISMATCH " + name + " current=" + current + " reference=" + reference);
        }
    }

    /** TChannels.newChannel(InputStream) before the allocation diet: a new byte[remaining] per read. */
    static final class Reference implements TReadableByteChannel {
        private final InputStream input;
        private boolean open = true;

        Reference(InputStream input) {
            this.input = input;
        }

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
    }

    private static String readResource(Factory factory, InputStream input) throws Exception {
        Trace trace = new Trace();
        TReadableByteChannel channel = factory.open(input);
        TByteBuffer buffer = TByteBuffer.allocate(8192);
        try {
            while (true) {
                trace.window(buffer);
                int count = channel.read(buffer);
                trace.value(count);
                trace.window(buffer);
                if (count == -1) {
                    break;
                }
                if (!buffer.hasRemaining()) {
                    TByteBuffer grown = TByteBuffer.allocate(buffer.capacity() * 2);
                    int position = buffer.position();
                    buffer.flip();
                    grown.put(buffer);
                    grown.position(position);
                    buffer = grown;
                }
            }
            buffer.flip();
            trace.content(buffer);
            channel.close();
            trace.value(channel.isOpen() ? 1 : 0);
        } catch (Exception e) {
            trace.text(e.getClass().getName());
        }
        return trace.digest();
    }

    private static String edge(Factory factory, byte[] data, int kind, int seed) throws Exception {
        Trace trace = new Trace();
        Random random = new Random(seed);
        TReadableByteChannel channel = factory.open(Streams.of(data, kind));
        try {
            for (int step = 0; step < 400; step++) {
                int capacity = random.nextInt(4) == 0 ? random.nextInt(4) : random.nextInt(70000);
                TByteBuffer buffer = TByteBuffer.allocate(capacity);
                for (int i = 0; i < capacity; i++) {
                    buffer.put(i, (byte) (0x5a ^ i));
                }
                int position = capacity == 0 ? 0 : random.nextInt(capacity + 1);
                int limit = position + random.nextInt(capacity - position + 1);
                buffer.limit(limit);
                buffer.position(position);
                TByteBuffer target = switch (random.nextInt(6)) {
                    case 0 -> buffer.slice();
                    case 1 -> buffer.asReadOnlyBuffer();
                    case 2 -> TByteBuffer.wrap(buffer.array(), position, limit - position);
                    default -> buffer;
                };
                trace.window(target);
                try {
                    trace.value(channel.read(target));
                } catch (Exception e) {
                    trace.text(e.getClass().getName());
                }
                trace.window(target);
                TByteBuffer whole = buffer.duplicate();
                whole.clear();
                trace.content(whole);
            }
            channel.close();
            trace.value(channel.isOpen() ? 1 : 0);
            try {
                trace.value(channel.read(TByteBuffer.allocate(5)));
            } catch (Exception e) {
                trace.text(e.getClass().getName());
            }
        } catch (Exception e) {
            trace.text(e.getClass().getName());
        }
        return trace.digest();
    }

    static final class Streams {
        static final int KINDS = 6;

        static InputStream of(byte[] data, int kind) {
            return switch (kind) {
                case 0 -> new ByteArrayInputStream(data);
                case 1 -> {
                    byte[] padded = new byte[data.length + 9];
                    System.arraycopy(data, 0, padded, 4, data.length);
                    ByteArrayInputStream in = new ByteArrayInputStream(padded, 2, data.length + 3);
                    in.skip(2);
                    yield in;
                }
                case 2 -> new ByteArrayInputStream(data) {
                };
                case 3 -> new Short(data);
                case 4 -> new OwnArrayRead(data);
                default -> new Failing(data);
            };
        }
    }

    /** Returns at most 1000 bytes per read. */
    static class Short extends InputStream {
        final byte[] data;
        int pos;

        Short(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 255 : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            int count = Math.min(Math.min(len, 1000), data.length - pos);
            if (len == 0) {
                return 0;
            }
            if (count <= 0) {
                return -1;
            }
            System.arraycopy(data, pos, b, off, count);
            pos += count;
            return count;
        }
    }

    /** read(byte[]) differs from read(b, 0, b.length): it fills the whole array or nothing. */
    static final class OwnArrayRead extends Short {
        OwnArrayRead(byte[] data) {
            super(data);
        }

        @Override
        public int read(byte[] b) {
            if (data.length - pos < b.length) {
                return super.read(b, 0, b.length);
            }
            System.arraycopy(data, pos, b, 0, b.length);
            pos += b.length;
            return b.length;
        }
    }

    /** Fails on the third read. */
    static final class Failing extends Short {
        int reads;

        Failing(byte[] data) {
            super(data);
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (++reads == 3) {
                throw new IllegalStateException("third read");
            }
            return super.read(b, off, len);
        }
    }

    static final class Trace {
        private final MessageDigest digest;

        Trace() throws Exception {
            digest = MessageDigest.getInstance("SHA-256");
        }

        void value(int value) {
            checks++;
            digest.update(new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value, ';'});
        }

        void window(TByteBuffer buffer) {
            value(buffer.position());
            value(buffer.limit());
            value(buffer.capacity());
        }

        void content(TByteBuffer buffer) {
            TByteBuffer view = buffer.duplicate();
            byte[] bytes = new byte[view.remaining()];
            view.get(bytes);
            value(bytes.length);
            digest.update(bytes);
        }

        void text(String text) {
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
        }

        String digest() {
            return HexFormat.of().formatHex(digest.digest(), 0, 12);
        }
    }

    private ChannelDietDifferential() {
    }
}
