package dev.gaius.browser;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class BrowserChunkSectionLayers {
    private static final ChunkSectionLayer[] VALUES = ChunkSectionLayer.values();

    private BrowserChunkSectionLayers() {
    }

    public static ChunkSectionLayer[] values() {
        return VALUES;
    }

}
