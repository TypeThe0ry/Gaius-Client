package dev.gaius.browser;

import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteBuffer;

/** Reuses the vertex view within one browser section-mesh upload. */
public final class BrowserMeshUpload {
    private static MeshData activeMesh;
    private static ByteBuffer activeVertexBuffer;

    private BrowserMeshUpload() {
    }

    public static void begin(MeshData mesh) {
        activeMesh = mesh;
        activeVertexBuffer = null;
    }

    public static ByteBuffer vertexBuffer(MeshData mesh) {
        if (activeMesh != mesh) {
            begin(mesh);
        }
        if (activeVertexBuffer == null) {
            activeVertexBuffer = mesh.vertexBuffer();
        }
        return activeVertexBuffer;
    }

    public static void end(MeshData mesh) {
        if (activeMesh == mesh) {
            activeMesh = null;
            activeVertexBuffer = null;
        }
    }
}
