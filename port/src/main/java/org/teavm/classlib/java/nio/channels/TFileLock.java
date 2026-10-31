package org.teavm.classlib.java.nio.channels;

import java.io.Closeable;
import java.io.IOException;

public class TFileLock implements Closeable {
    private final TFileChannel channel;
    private final long position;
    private final long size;
    private final boolean shared;
    private boolean valid = true;

    TFileLock(TFileChannel channel, long position, long size, boolean shared) {
        this.channel = channel;
        this.position = position;
        this.size = size;
        this.shared = shared;
    }

    public TFileChannel channel() {
        return channel;
    }

    public long position() {
        return position;
    }

    public long size() {
        return size;
    }

    public boolean isShared() {
        return shared;
    }

    public boolean overlaps(long position, long size) {
        return this.position < position + size && position < this.position + this.size;
    }

    public boolean isValid() {
        return valid && channel.isOpen();
    }

    public void release() throws IOException {
        valid = false;
    }

    @Override
    public void close() throws IOException {
        release();
    }
}
