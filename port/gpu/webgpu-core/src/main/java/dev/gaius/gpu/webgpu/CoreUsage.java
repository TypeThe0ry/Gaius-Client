package dev.gaius.gpu.webgpu;

/**
 * Buffer and texture usage bits of the core API, with the meaning of the renderpearl usage
 * flags. A seam translates its SPI's flags bit by bit, so the core does not depend on the values
 * a Minecraft version happens to use.
 */
public final class CoreUsage {
    public static final int BUFFER_MAP_READ = 1;
    public static final int BUFFER_MAP_WRITE = 1 << 1;
    public static final int BUFFER_CLIENT_STORAGE = 1 << 2;
    public static final int BUFFER_COPY_DST = 1 << 3;
    public static final int BUFFER_COPY_SRC = 1 << 4;
    public static final int BUFFER_VERTEX = 1 << 5;
    public static final int BUFFER_INDEX = 1 << 6;
    public static final int BUFFER_UNIFORM = 1 << 7;
    public static final int BUFFER_TEXEL = 1 << 8;
    public static final int BUFFER_INDIRECT = 1 << 9;

    public static final int TEXTURE_COPY_DST = 1;
    public static final int TEXTURE_COPY_SRC = 1 << 1;
    public static final int TEXTURE_BINDING = 1 << 2;
    public static final int TEXTURE_RENDER_ATTACHMENT = 1 << 3;
    public static final int TEXTURE_CUBEMAP = 1 << 4;

    // GPUBufferUsage
    static final int WEB_BUFFER_MAP_READ = 0x0001;
    static final int WEB_BUFFER_MAP_WRITE = 0x0002;
    static final int WEB_BUFFER_COPY_SRC = 0x0004;
    static final int WEB_BUFFER_COPY_DST = 0x0008;
    static final int WEB_BUFFER_INDEX = 0x0010;
    static final int WEB_BUFFER_VERTEX = 0x0020;
    static final int WEB_BUFFER_UNIFORM = 0x0040;
    static final int WEB_BUFFER_STORAGE = 0x0080;
    static final int WEB_BUFFER_INDIRECT = 0x0100;

    // GPUTextureUsage
    static final int WEB_TEXTURE_COPY_SRC = 0x01;
    static final int WEB_TEXTURE_COPY_DST = 0x02;
    static final int WEB_TEXTURE_BINDING = 0x04;
    static final int WEB_TEXTURE_RENDER_ATTACHMENT = 0x10;

    private CoreUsage() {
    }

    /**
     * WebGPU usage for a core buffer usage. Mapped writes are emulated with a staging copy encoded
     * in command order, so every writable buffer is a copy destination; a read-mapped buffer may
     * only be a copy destination in WebGPU. Other buffers also support internal source copies
     * so a compact texture readback can preserve neighboring bytes in a partial word.
     */
    static int webBufferUsage(int usage) {
        if ((usage & BUFFER_MAP_READ) != 0) {
            return WEB_BUFFER_MAP_READ | WEB_BUFFER_COPY_DST;
        }
        // Compact texture readbacks preserve adjacent bytes through an internal aligned copy.
        int result = WEB_BUFFER_COPY_SRC | WEB_BUFFER_COPY_DST;
        if ((usage & BUFFER_VERTEX) != 0) {
            result |= WEB_BUFFER_VERTEX;
        }
        if ((usage & BUFFER_INDEX) != 0) {
            result |= WEB_BUFFER_INDEX;
        }
        if ((usage & BUFFER_UNIFORM) != 0) {
            result |= WEB_BUFFER_UNIFORM;
        }
        if ((usage & BUFFER_TEXEL) != 0) {
            result |= WEB_BUFFER_STORAGE;
        }
        if ((usage & BUFFER_INDIRECT) != 0) {
            result |= WEB_BUFFER_INDIRECT;
        }
        return result;
    }

    static int webTextureUsage(int usage) {
        int result = 0;
        if ((usage & TEXTURE_COPY_DST) != 0) {
            result |= WEB_TEXTURE_COPY_DST;
        }
        if ((usage & TEXTURE_COPY_SRC) != 0) {
            result |= WEB_TEXTURE_COPY_SRC;
        }
        if ((usage & TEXTURE_BINDING) != 0) {
            result |= WEB_TEXTURE_BINDING;
        }
        if ((usage & TEXTURE_RENDER_ATTACHMENT) != 0) {
            result |= WEB_TEXTURE_RENDER_ATTACHMENT;
        }
        return result;
    }
}
