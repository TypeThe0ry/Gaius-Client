package dev.gaius.browser.ops;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.gaius.browser.JsArrays;
import dev.gaius.browser.kernel.mesh.MeshKernelBridge;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.teavm.jso.JSObject;

final class Blaze3dVersionOps implements VersionOps {
    @Override
    public void uploadGlyph(GpuTexture texture, ByteBuffer pixels, int x, int y, int width, int height) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                texture, pixels, NativeImage.Format.RGBA, 0, 0, x, y, width, height);
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
