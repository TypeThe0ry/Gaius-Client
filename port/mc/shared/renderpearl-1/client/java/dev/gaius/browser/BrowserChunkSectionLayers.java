package dev.gaius.browser;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** RenderPearl-only section metadata cached outside the per-frame terrain loop. */
public final class BrowserChunkSectionLayers {
    private static final ChunkSectionLayer[] VALUES = ChunkSectionLayer.values();
    private static final VertexFormat[] VERTEX_FORMATS = createVertexFormats();
    private static final boolean[] TRANSLUCENT = createTranslucentFlags();

    private BrowserChunkSectionLayers() {
    }

    public static ChunkSectionLayer[] values() {
        return VALUES;
    }

    public static VertexFormat vertexFormat(int index) {
        return VERTEX_FORMATS[index];
    }

    public static boolean translucent(int index) {
        return TRANSLUCENT[index];
    }

    private static VertexFormat[] createVertexFormats() {
        VertexFormat[] formats = new VertexFormat[VALUES.length];
        for (int i = 0; i < VALUES.length; i++) {
            formats[i] = VALUES[i].pipeline(false).getVertexFormatBinding(0);
        }
        return formats;
    }

    private static boolean[] createTranslucentFlags() {
        boolean[] flags = new boolean[VALUES.length];
        for (int i = 0; i < VALUES.length; i++) {
            flags[i] = VALUES[i].translucent();
        }
        return flags;
    }
}
