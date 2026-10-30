package org.lwjgl.system;

import java.nio.ByteBuffer;

/**
 * Browser-side LWJGL module handle.
 *
 * <p>Function addresses are stable symbolic ids. Browser backend bindings
 * consume these ids instead of passing them to JNI.</p>
 */
public final class BrowserSharedLibrary implements SharedLibrary {
    private static final BrowserSharedLibrary INSTANCE = new BrowserSharedLibrary();

    private BrowserSharedLibrary() {
    }

    public static SharedLibrary open() {
        return INSTANCE;
    }

    @Override
    public String getName() {
        return "gaius-browser";
    }

    @Override
    public String getPath() {
        return "browser://gaius";
    }

    @Override
    public long getFunctionAddress(ByteBuffer functionName) {
        long hash = 0xcbf29ce484222325L;
        ByteBuffer name = functionName.duplicate();
        while (name.hasRemaining() && name.get(name.position()) != 0) {
            hash ^= name.get() & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash == 0 ? 1 : hash;
    }

    @Override
    public long address() {
        return 1;
    }

    @Override
    public void free() {
    }
}
