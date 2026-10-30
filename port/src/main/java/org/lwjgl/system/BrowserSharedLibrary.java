package org.lwjgl.system;

import java.nio.ByteBuffer;

/**
 * Browser stand-in for LWJGL's native shared-library handle.
 *
 * <p>All browser OpenGL, GLFW and OpenAL calls are redirected before their
 * native function pointers are invoked.  LWJGL still creates a library while
 * it discovers capabilities, so expose a harmless non-zero address for every
 * requested symbol and make releasing the synthetic handle a no-op.</p>
 */
public final class BrowserSharedLibrary implements SharedLibrary {
    private static final BrowserSharedLibrary INSTANCE = new BrowserSharedLibrary();

    private BrowserSharedLibrary() {
    }

    public static SharedLibrary open() {
        return INSTANCE;
    }

    @Override
    public long getFunctionAddress(ByteBuffer functionName) {
        return 1L;
    }

    @Override
    public long address() {
        return 1L;
    }

    @Override
    public String getName() {
        return "browser";
    }

    @Override
    public String getPath() {
        return "browser";
    }

    @Override
    public void free() {
        // The singleton owns no native resource.
    }
}
