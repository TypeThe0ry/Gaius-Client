package org.lwjgl.system;

/** JVM stand-in: the owner accounting of the browser allocator is not part of the decoded pixels. */
public final class BrowserMemory {
    private BrowserMemory() {
    }

    public static void claimOwner(long address, int owner) {
    }
}
