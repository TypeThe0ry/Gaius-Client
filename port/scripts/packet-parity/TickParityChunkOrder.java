package dev.gaius.parity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Separate diagnostic stream so chunk task scheduling cannot change packet parity events. */
public final class TickParityChunkOrder {
    private static Writer sink;
    private static long sequence;

    private TickParityChunkOrder() {
    }

    public static void configure(Writer writer) {
        sink = writer;
    }

    public static synchronized void begin(Object task, int x, int z, Object status) {
        record(x, z, (ChunkStatus) status, "begin");
    }

    public static synchronized void complete(Object task, int x, int z, Object status) {
        record(x, z, (ChunkStatus) status, "complete");
    }

    private static void record(int x, int z, ChunkStatus status, String phase) {
        if (sink == null) return;
        try {
            sink.write("{\"seq\":" + (++sequence) + ",\"x\":" + x + ",\"z\":" + z
                    + ",\"statusOrdinal\":" + status.getIndex() + ",\"status\":\""
                    + status.getName() + "\",\"phase\":\"" + phase + "\"}\n");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
