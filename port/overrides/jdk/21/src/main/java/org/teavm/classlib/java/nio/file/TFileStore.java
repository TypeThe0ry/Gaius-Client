package org.teavm.classlib.java.nio.file;

import java.io.IOException;
import org.teavm.classlib.java.nio.file.attribute.TFileAttributeView;
import org.teavm.classlib.java.nio.file.attribute.TFileStoreAttributeView;

public abstract class TFileStore {
    protected TFileStore() {
    }

    public abstract String name();
    public abstract String type();
    public abstract boolean isReadOnly();
    public abstract long getTotalSpace() throws IOException;
    public abstract long getUsableSpace() throws IOException;
    public abstract long getUnallocatedSpace() throws IOException;
    public abstract boolean supportsFileAttributeView(
            Class<? extends TFileAttributeView> type);
    public abstract boolean supportsFileAttributeView(String name);
    public abstract <V extends TFileStoreAttributeView> V getFileStoreAttributeView(
            Class<V> type);
    public abstract Object getAttribute(String attribute) throws IOException;
}
