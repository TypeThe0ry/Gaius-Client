package org.teavm.classlib.java.nio.file;

import java.io.IOException;
import java.util.Set;
import org.teavm.classlib.java.nio.channels.TFileChannel;
import org.teavm.classlib.java.nio.channels.TSeekableByteChannel;
import org.teavm.classlib.java.nio.file.attribute.TFileAttribute;
import org.teavm.classlib.java.nio.file.attribute.TFileTime;

public final class TFilesModernSupport {
    private TFilesModernSupport() {
    }

    public static TPath setLastModifiedTime(TPath path, TFileTime time) {
        path.toFile().setLastModified(time.toMillis());
        return path;
    }

    public static TFileStore getFileStore(TPath path) {
        return new BrowserFileStore();
    }

    public static TSeekableByteChannel newByteChannel(
            TPath path, Set<? extends TOpenOption> options, TFileAttribute<?>... attributes)
            throws IOException {
        return TFileChannel.open(path, options, attributes);
    }

    private static final class BrowserFileStore extends TFileStore {
        @Override public String name() { return "browser"; }
        @Override public String type() { return "virtual"; }
        @Override public boolean isReadOnly() { return false; }
        @Override public long getTotalSpace() { return 1024L * 1024L * 1024L; }
        @Override public long getUsableSpace() { return 768L * 1024L * 1024L; }
        @Override public long getUnallocatedSpace() { return getUsableSpace(); }
        @Override public boolean supportsFileAttributeView(Class type) { return false; }
        @Override public boolean supportsFileAttributeView(String name) { return false; }
        @Override public <V extends
                org.teavm.classlib.java.nio.file.attribute.TFileStoreAttributeView>
                V getFileStoreAttributeView(Class<V> type) { return null; }
        @Override public Object getAttribute(String attribute) { return null; }
    }
}
