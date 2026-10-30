package dev.gaius.browser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.network.CompressionDecoder;

/** Browser replacement for Minecraft's synchronous zlib decoder. */
public final class BrowserCompressionDecoder extends CompressionDecoder {
    public static final int MAXIMUM_COMPRESSED_LENGTH = 2 * 1024 * 1024;
    public static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8 * 1024 * 1024;
    public static final int OUTPUT_QUANTUM_BYTES = 16 * 1024;
    public static final int TURN_BUDGET_BYTES = 32 * 1024;
    /** Soft high-water mark: keep accepting a burst, but force cooperative draining. */
    public static final int SOFT_QUEUE_FRAMES = 32;
    public static final int SOFT_QUEUE_BYTES = 8 * 1024 * 1024;
    /** Hard admission bound: protects the browser heap without rejecting normal bursts. */
    public static final int MAX_QUEUE_FRAMES = 64;
    public static final int MAX_QUEUE_BYTES = 32 * 1024 * 1024;

    private final Deque<Frame> queue = new ArrayDeque<>();
    private Frame active;
    private Frame deferred;
    private boolean scheduled;
    private boolean closed;
    private boolean failed;
    private boolean readCompletePending;
    private int threshold;
    private boolean validateDecompressed;
    private int retainedBytes;
    private long generation = 1L;
    /**
     * Reusable inflate state. Chunk bursts decode thousands of small packets per second and
     * allocating + destroying a native inflate context per packet churns the browser heap.
     * The pooled instance is only ever touched synchronously on the decode path below, so no
     * ordering or reentrancy hazard exists; reset() returns it to a clean state after each use.
     */
    private Inflater pooledInflater;

    public BrowserCompressionDecoder(int threshold, boolean validateDecompressed) {
        super(threshold, validateDecompressed);
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
    }

    @Override
    public void setThreshold(int threshold, boolean validateDecompressed) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
    }

    /** ByteToMessageDecoder owns cumulation and frame boundaries; this only queues whole packets. */
    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        if (closed || failed) {
            input.skipBytes(input.readableBytes());
            return;
        }
        int frameStart = input.readerIndex();
        int declaredLength = readVarInt(input);
        if (declaredLength < 0) {
            input.readerIndex(frameStart);
            return;
        }
        int payloadLength = input.readableBytes();
        if (declaredLength == 0) {
            if (payloadLength > MAXIMUM_UNCOMPRESSED_LENGTH) {
                fail(context, "uncompressed frame exceeds browser limit: " + payloadLength);
                return;
            }
            output.add(input.readRetainedSlice(payloadLength));
            return;
        }
        if (declaredLength > MAXIMUM_UNCOMPRESSED_LENGTH) {
            fail(context, "declared uncompressed length exceeds browser limit: " + declaredLength);
            return;
        }
        if (validateDecompressed && declaredLength < threshold) {
            fail(context, "decompressed packet below compression threshold: " + declaredLength);
            return;
        }
        if (payloadLength <= 0 || payloadLength > MAXIMUM_COMPRESSED_LENGTH) {
            fail(context, "compressed payload length out of bounds: " + payloadLength);
            return;
        }
        // CompressionDecoder sits immediately after Varint21FrameDecoder.  That decoder
        // delivers one complete network frame per invocation.  Keep the compressed frame
        // synchronous so ByteToMessageDecoder can finish its callDecode/readComplete cycle
        // before PacketDecoder observes the next frame; deferring fireChannelRead through
        // HostLoop.schedule (Platform.schedule) reorders packet boundaries under multiplayer bursts.
        byte[] payload = new byte[payloadLength];
        input.readBytes(payload);
        byte[] decoded = new byte[declaredLength];
        Inflater inflater = pooledInflater;
        if (inflater == null) {
            inflater = new Inflater();
            pooledInflater = inflater;
        } else {
            inflater.reset();
        }
        try {
            inflater.setInput(payload);
            int produced = 0;
            while (produced < declaredLength && !inflater.finished()) {
                int count = inflater.inflate(decoded, produced, declaredLength - produced);
                if (count == 0) {
                    if (inflater.needsDictionary() || inflater.needsInput()) {
                        fail(context, "compressed frame ended before declared output length");
                        return;
                    }
                    fail(context, "inflater made no progress");
                    return;
                }
                produced += count;
            }
            if (produced != declaredLength || !inflater.finished() || inflater.getRemaining() != 0) {
                fail(context, "compressed frame length mismatch");
                return;
            }
        } catch (DataFormatException exception) {
            fail(context, "malformed zlib payload: " + exception.getMessage());
            return;
        } finally {
            // Success keeps the pooled context alive (reset on next use). Failure already
            // went through fail() -> cleanupFrames(), which ended and detached it; the
            // guard below only covers a future fail-less path and never double-ends.
            if (failed && pooledInflater == inflater) {
                inflater.end();
                pooledInflater = null;
            }
        }
        output.add(Unpooled.wrappedBuffer(decoded));
        return;
        /*if (deferred != null) {
            // Keep at most one complete frame outside the FIFO while the hard watermark is
            // active. ByteToMessageDecoder will retry this cumulation after the pump drains.
            input.readerIndex(frameStart);
            return;
        }
        int queuedFrames = queuedFrameCount();
        int nextRetainedBytes = retainedBytes + declaredLength;
        if (queuedFrames >= MAX_QUEUE_FRAMES || nextRetainedBytes > MAX_QUEUE_BYTES) {
            byte[] payload = new byte[payloadLength];
            input.readBytes(payload);
            deferred = new Frame(declaredLength, payload);
            schedule(context, generation);
            return;
        }
        byte[] payload = new byte[payloadLength];
        input.readBytes(payload);
        queue.addLast(new Frame(declaredLength, payload));
        retainedBytes += declaredLength;
        schedule(context, generation);*/
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext context) {
        if (closed || failed) {
            return;
        }
        readCompletePending = true;
        if (active == null && queue.isEmpty()) {
            fireReadComplete(context);
        } else {
            schedule(context, generation);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        cleanup();
        context.fireChannelInactive();
    }

    private static int readVarInt(ByteBuf input) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!input.isReadable()) {
                return -1;
            }
            int next = input.readUnsignedByte();
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        return -1;
    }

    private void schedule(ChannelHandlerContext context, long expectedGeneration) {
        if (scheduled || closed || failed) {
            return;
        }
        scheduled = true;
        HostLoop.schedule(() -> {
            scheduled = false;
            if (closed || failed || expectedGeneration != generation) {
                return;
            }
            pump(context, expectedGeneration);
        }, 0);
    }

    private void pump(ChannelHandlerContext context, long expectedGeneration) {
        if (closed || failed || expectedGeneration != generation) {
            return;
        }
        int turnBytes = 0;
        while (turnBytes < TURN_BUDGET_BYTES) {
            if (active == null) {
                active = queue.pollFirst();
                if (active == null) {
                    if (readCompletePending) {
                        fireReadComplete(context);
                    }
                    return;
                }
                active.inflater = new Inflater();
                active.inflater.setInput(active.compressed);
                active.output = new byte[active.declaredLength];
            }
            int remaining = active.declaredLength - active.produced;
            int quantum = Math.min(Math.min(OUTPUT_QUANTUM_BYTES, remaining),
                    TURN_BUDGET_BYTES - turnBytes);
            int produced;
            try {
                produced = active.inflater.inflate(active.output, active.produced, quantum);
            } catch (DataFormatException exception) {
                fail(context, "malformed zlib payload: " + exception.getMessage());
                return;
            }
            if (produced < 0 || produced > quantum) {
                fail(context, "inflater output overflow");
                return;
            }
            active.produced += produced;
            turnBytes += produced;
            if (produced == 0) {
                fail(context, "inflater made no progress");
                return;
            }
            if (active.produced == active.declaredLength) {
                if (!active.inflater.finished()) {
                    fail(context, "declared output length reached before zlib end");
                    return;
                }
                emit(context, active);
                resumeAdmissionIfReady(context);
            }
        }
        if (active != null || !queue.isEmpty()) {
            schedule(context, expectedGeneration);
        } else if (readCompletePending) {
            fireReadComplete(context);
        }
    }

    private void emit(ChannelHandlerContext context, Frame frame) {
        if (frame.inflater != null) {
            frame.inflater.end();
            frame.inflater = null;
        }
        retainedBytes -= frame.declaredLength;
        if (retainedBytes < 0) {
            retainedBytes = 0;
        }
        context.fireChannelRead(Unpooled.wrappedBuffer(frame.output));
        active = null;
    }

    private void fireReadComplete(ChannelHandlerContext context) {
        readCompletePending = false;
        context.fireChannelReadComplete();
    }

    private int queuedFrameCount() {
        return queue.size() + (active == null ? 0 : 1);
    }

    private void resumeAdmissionIfReady(ChannelHandlerContext context) {
        if (deferred == null || queuedFrameCount() >= SOFT_QUEUE_FRAMES
                || retainedBytes >= SOFT_QUEUE_BYTES || closed || failed) {
            return;
        }
        Frame frame = deferred;
        deferred = null;
        queue.addLast(frame);
        retainedBytes += frame.declaredLength;
        schedule(context, generation);
        context.read();
    }

    private void fail(ChannelHandlerContext context, String message) {
        if (failed || closed) {
            return;
        }
        failed = true;
        cleanupFrames();
        context.fireExceptionCaught(new DecoderException(message));
        context.close();
    }

    private void cleanup() {
        closed = true;
        generation++;
        cleanupFrames();
    }

    private void cleanupFrames() {
        if (pooledInflater != null) {
            pooledInflater.end();
            pooledInflater = null;
        }
        if (active != null && active.inflater != null) {
            active.inflater.end();
        }
        active = null;
        while (!queue.isEmpty()) {
            Frame frame = queue.removeFirst();
            if (frame.inflater != null) {
                frame.inflater.end();
            }
        }
        if (deferred != null && deferred.inflater != null) {
            deferred.inflater.end();
        }
        deferred = null;
        retainedBytes = 0;
        readCompletePending = false;
    }

    private static final class Frame {
        private final int declaredLength;
        private final byte[] compressed;
        private byte[] output;
        private int produced;
        private Inflater inflater;

        private Frame(int declaredLength, byte[] payload) {
            this.declaredLength = declaredLength;
            this.compressed = payload;
        }
    }
}
