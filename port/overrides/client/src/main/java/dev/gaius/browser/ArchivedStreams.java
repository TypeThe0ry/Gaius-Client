package dev.gaius.browser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Remembers which archive resource a stream of the vanilla pack overrides reads (no JS, no
 * Minecraft types). Separate from {@link AssetArchive}, whose class initialisation reads the page.
 */
public final class ArchivedStreams {
    private ArchivedStreams() {
    }

    /**
     * The last streams the vanilla pack overrides opened over {@link AssetArchive} bytes, with the
     * resource and byte count of each. A reader can then tell the archive's copy of a resource
     * from a resource pack's by the stream's identity (the unihex glyph table relies on it); the
     * stream stays exactly a {@code ByteArrayInputStream}. Vanilla opens a font zip and reads it without yielding in
     * between, so a short ring suffices; an evicted stream is only read the ordinary way.
     */
    private static final int TRACKED = 8;
    private static final InputStream[] TRACKED_STREAMS = new InputStream[TRACKED];
    private static final String[] TRACKED_RESOURCES = new String[TRACKED];
    private static final int[] TRACKED_LENGTHS = new int[TRACKED];
    private static int trackedNext;

    /** Records that {@code stream} reads all bytes of the archived {@code resource}; returns it. */
    public static synchronized ByteArrayInputStream track(String resource, ByteArrayInputStream stream) {
        int slot = trackedNext;
        trackedNext = (slot + 1) % TRACKED;
        TRACKED_STREAMS[slot] = stream;
        TRACKED_RESOURCES[slot] = resource;
        TRACKED_LENGTHS[slot] = stream.available();
        return stream;
    }

    /** The archived resource {@code stream} was opened over by {@link #track}, or null. */
    public static synchronized String trackedResource(InputStream stream) {
        for (int slot = 0; slot < TRACKED; slot++) {
            if (stream != null && TRACKED_STREAMS[slot] == stream) {
                return TRACKED_RESOURCES[slot];
            }
        }
        return null;
    }

    /** The byte count of the archived bytes behind {@code stream}, or -1. */
    public static synchronized int trackedLength(InputStream stream) {
        for (int slot = 0; slot < TRACKED; slot++) {
            if (stream != null && TRACKED_STREAMS[slot] == stream) {
                return TRACKED_LENGTHS[slot];
            }
        }
        return -1;
    }
}
