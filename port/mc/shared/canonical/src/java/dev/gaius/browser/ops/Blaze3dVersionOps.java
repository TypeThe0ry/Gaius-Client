package dev.gaius.browser.ops;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

final class Blaze3dVersionOps implements VersionOps {
    @Override
    public void uploadGlyph(GpuTexture texture, ByteBuffer pixels, int x, int y, int width, int height) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                texture, pixels, NativeImage.Format.RGBA, 0, 0, x, y, width, height);
    }

    @Override
    public StructureTemplateManager structureTemplateManager(MinecraftServer server) {
        return server.getStructureManager();
    }

    @Override
    public NativeImage readImage(NativeImage.Format format, InputStream stream) throws IOException {
        return NativeImage.read(format, stream);
    }

    @Override
    public int pauseMenuLanButtonColumns() {
        // The hook runs right after the vanilla hasSingleplayerServer() call; one column.
        return 1;
    }
}
