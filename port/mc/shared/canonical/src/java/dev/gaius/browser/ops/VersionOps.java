package dev.gaius.browser.ops;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.teavm.jso.JSObject;

public interface VersionOps {
    VersionOps INSTANCE = new dev.gaius.browser.ops.Blaze3dVersionOps();

    void uploadGlyph(GpuTexture texture, ByteBuffer pixels, int x, int y, int width, int height);

    default boolean copyVertices(JSObject record, int layer, long pointer, int bytes) {
        return false;
    }

    /** The server's structure template manager (the accessor was renamed between generations). */
    StructureTemplateManager structureTemplateManager(MinecraftServer server);

    /**
     * Decodes a PNG stream. {@code format} is the format the vanilla caller asked for; a
     * generation whose decoder takes no format ignores it (it always decodes the same pixels).
     */
    NativeImage readImage(NativeImage.Format format, InputStream stream) throws IOException;

    /**
     * Pause menu grid columns the Open to LAN button spans. The hook that adds it runs at a
     * different place of the vanilla menu per generation; where it runs before the Options row
     * the button spans both columns, otherwise GridLayout widens column 0 for every row.
     */
    int pauseMenuLanButtonColumns();
}
