package dev.gaius.browser.ops;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.gaius.browser.JsArrays;
import dev.gaius.browser.kernel.mesh.MeshKernelBridge;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.teavm.jso.JSObject;

final class RenderpearlVersionOps implements VersionOps {
    @Override
    public void uploadGlyph(GpuTexture texture, ByteBuffer pixels, int x, int y, int width, int height) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                texture, pixels, 0, 0, x, y, width, height);
    }

    @Override
    public StructureTemplateManager structureTemplateManager(MinecraftServer server) {
        return server.getStructureTemplateManager();
    }

    @Override
    public NativeImage readImage(NativeImage.Format format, InputStream stream) throws IOException {
        // This generation removed the format argument: its decoder yields the pixels the
        // vanilla caller (RGBA bitmap sheets) used to request.
        return NativeImage.read(stream);
    }

    @Override
    public int pauseMenuLanButtonColumns() {
        // The hook runs before the level check that guards the Options row; the button spans
        // both columns, like the full-width Return to Game and Disconnect buttons.
        return 2;
    }

    @Override
    public boolean copyVertices(JSObject record, int layer, long pointer, int bytes) {
        if (!JsArrays.LINEAR_MEMORY) {
            return false;
        }
        if (MeshKernelBridge.copyVerticesToLinear(record, layer, pointer, bytes) != bytes) {
            throw new IllegalStateException("kernel vertex bytes do not match the quad count");
        }
        return true;
    }
}
