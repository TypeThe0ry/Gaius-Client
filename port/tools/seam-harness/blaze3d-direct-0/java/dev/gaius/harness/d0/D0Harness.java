package dev.gaius.harness.d0;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.gaius.harness.common.Glyphs;
import dev.gaius.harness.common.Page;
import dev.gaius.mc.seams.blaze3ddirect0.D0Device;
import dev.gaius.mc.seams.blaze3ddirect0.D0Devices;
import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * The blaze3d-direct-0 seam harness: vanilla 1.21.11's {@code GpuDevice} API,
 * pipelines and shaders, on the seam's device ({@code mode=webgpu}) or on vanilla's
 * {@code GlDevice} over the WebGL 2 shim ({@code mode=webgl2}), both created by
 * {@code RenderSystem.initRenderer} as patched in the client jar (the REQUIRED registration patch
 * calls {@link D0Devices#create}). 1.21.11 has no frontend: the frame calls the device directly,
 * as vanilla 1.21.11 code does, and presents with {@code CommandEncoder.presentTexture}.
 *
 * <p>The frame: the panorama ({@code RenderPipelines.PANORAMA}, a cube map, depth-tested) and GUI
 * quads and sprites ({@code GUI}, {@code GUI_TEXTURED}) in GL's clip conventions (1.21.11 builds
 * GL projection matrices whatever the device; the seam's GLSL front-end remaps depth). Between
 * them: two triangle fans of {@code SUNRISE_SUNSET} (one without indices, as the sky draws them,
 * one through the shared sequential index buffer at an index and vertex offset, as immediate-mode
 * fans are), and two overlapping depth-tested quads in the near half of GL's depth range (clip z
 * -0.8 and -0.5, the far one drawn second): WebGPU's 0..1 clip range drops both unless the
 * translator remaps GL's depth, and the depth test must keep the near one in front.</p>
 */
public final class D0Harness {
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;

    private D0Harness() {
    }

    public static void main(String[] args) {
        try {
            run(Page.mode());
            Page.status("ok");
        } catch (Throwable t) {
            Page.status("error: " + t);
            t.printStackTrace();
        }
    }

    private static void run(String mode) {
        ShaderSource shaders = (id, type) -> Page.shader(id + "|" + type.name());
        RenderSystem.initRenderThread();
        double start = Page.now();
        // Both modes go through RenderSystem.initRenderer, which the REQUIRED registration patch
        // of the client jar points at D0Devices.create: WebGPU when the page asks for it, else
        // vanilla's GlDevice (it makes the window's GL context current itself).
        long window = 0L;
        if (!mode.equals("webgpu")) {
            GLFW.glfwInit();
            window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "blaze3d-direct-0 harness", 0L, 0L);
        }
        RenderSystem.initRenderer(window, 0, false, shaders, mode.equals("webgpu"));
        GpuDevice device = RenderSystem.getDevice();
        if (mode.equals("webgpu") != device instanceof D0Device) {
            throw new IllegalStateException("RenderSystem.initRenderer created " + device.getClass().getName()
                    + " for mode " + mode);
        }
        Page.info("deviceClass", device.getClass().getName());
        Page.info("backend", device.getBackendName());
        Page.info("device", device.getRenderer());
        double ready = Page.now();
        int draws = frame(device);
        Page.info("draws", draws);
        Page.info("deviceMs", ready - start);
        Page.info("frameMs", Page.now() - ready);
        Page.info("debugMessages", String.join("\n", device.getLastDebugMessages()));
        if (device instanceof D0Device seam) {
            Page.info("skippedDraws", seam.core().encoder().pass().drawsSkipped());
            Page.info("fanDraws", seam.fanDraws());
            // Every static vanilla pipeline through the lenient front-end and pipeline creation.
            int valid = 0;
            StringBuilder invalid = new StringBuilder();
            for (RenderPipeline pipeline : RenderPipelines.getStaticPipelines()) {
                if (device.precompilePipeline(pipeline).isValid()) {
                    valid++;
                } else {
                    invalid.append(pipeline.getLocation()).append(' ');
                }
            }
            Page.info("staticPipelines", RenderPipelines.getStaticPipelines().size());
            Page.info("staticPipelinesValid", valid);
            Page.info("staticPipelinesInvalid", invalid.toString().trim());
            Page.info("precompileMessages", String.join(System.lineSeparator(), device.getLastDebugMessages()));
        }
    }

    private static int frame(GpuDevice device) {
        CommandEncoder encoder = device.createCommandEncoder();
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_COPY_DST;
        GpuTexture color = device.createTexture("main", usage, TextureFormat.RGBA8, WIDTH, HEIGHT, 1, 1);
        GpuTexture depth = device.createTexture("main depth", usage, TextureFormat.DEPTH32, WIDTH, HEIGHT, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        GpuTextureView depthView = device.createTextureView(depth);
        int draws = 0;

        GpuTexture cube = device.createTexture("panorama", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST
                | GpuTexture.USAGE_CUBEMAP_COMPATIBLE, TextureFormat.RGBA8, 128, 128, 6, 1);
        for (int face = 0; face < 6; face++) {
            encoder.writeToTexture(cube, face(face, 128), NativeImage.Format.RGBA, 0, face,
                    0, 0, 128, 128);
        }
        GpuTexture logo = device.createTexture("logo", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8, 128, 64, 1, 1);
        encoder.writeToTexture(logo, Glyphs.atlas(), NativeImage.Format.RGBA, 0, 0, 0, 0,
                128, 64);
        GpuTexture mipped = device.createTexture("mip boundary", GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_DST, TextureFormat.RGBA8, 16, 16, 1, 6);
        for (int mip = 0; mip < mipped.getMipLevels(); mip++) {
            int size = mipped.getWidth(mip);
            ByteBuffer mipPixels = Glyphs.buffer(size * size * 4);
            for (int texel = 0; texel < size * size; texel++) {
                mipPixels.putInt(0xFF40A0E0 + mip);
            }
            mipPixels.flip();
            encoder.writeToTexture(mipped, mipPixels, NativeImage.Format.RGBA, mip, 0, 0, 0, size, size);
        }
        Page.info("logicalMipLevels", mipped.getMipLevels());
        if (mipped instanceof dev.gaius.mc.seams.blaze3ddirect0.D0Texture seamTexture) {
            Page.info("physicalMipLevels", seamTexture.core().mipLevels());
        }
        GpuSampler linear = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR,
                FilterMode.LINEAR, 1, OptionalDouble.empty());
        GpuSampler nearest = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());

        Matrix4f perspective = new Matrix4f().setPerspective(85.0F * (float) Math.PI / 180.0F, WIDTH / (float) HEIGHT,
                0.05F, 10.0F);
        Matrix4f panoramaView = new Matrix4f().rotationX((float) Math.PI).rotateX(0.38F).rotateY(2.4F);
        Matrix4f ortho = new Matrix4f().setOrtho(0.0F, WIDTH / 2.0F, HEIGHT / 2.0F, 0.0F, 1000.0F, 21000.0F);
        Matrix4f guiView = new Matrix4f().translation(0.0F, 0.0F, -11000.0F);

        try (RenderPass pass = encoder.createRenderPass(() -> "frame", colorView, OptionalInt.of(0xFF102030), depthView,
                OptionalDouble.of(1.0))) {
            // Panorama.
            pass.setPipeline(RenderPipelines.PANORAMA);
            pass.setUniform("Projection", ubo(device, perspective));
            pass.setUniform("DynamicTransforms", transform(device, panoramaView));
            pass.bindTexture("Sampler0", device.createTextureView(cube), linear);
            draws += quads(device, pass, cubeVertices(), 6);
            // GUI: a translucent band, the atlas as a sprite sheet, buttons.
            pass.setUniform("Projection", ubo(device, ortho));
            pass.setUniform("DynamicTransforms", transform(device, guiView));
            pass.setPipeline(RenderPipelines.GUI);
            ByteBuffer band = Glyphs.buffer(3 * 4 * 16);
            colorQuad(band, 0, 0, 640, 90, 0x60000000);
            colorQuad(band, 220, 130, 420, 150, 0xFF707070);
            colorQuad(band, 220, 160, 420, 180, 0xFF909090);
            band.flip();
            draws += quads(device, pass, band, 3);
            draws += fans(device, pass);
            draws += nearDepthQuads(device, pass);
            pass.setUniform("DynamicTransforms", transform(device, guiView));
            pass.setPipeline(RenderPipelines.GUI_TEXTURED);
            pass.bindTexture("Sampler0", device.createTextureView(logo), nearest);
            ByteBuffer sprite = Glyphs.buffer(4 * 24);
            texVertex(sprite, 192, 10, 0, 0, 0xFFFFFFFF);
            texVertex(sprite, 192, 74, 0, 1, 0xFFFFFFFF);
            texVertex(sprite, 448, 74, 1, 1, 0xFFFFFFFF);
            texVertex(sprite, 448, 10, 1, 0, 0xFFFFFFFF);
            sprite.flip();
            draws += quads(device, pass, sprite, 1);
            pass.bindTexture("Sampler0", device.createTextureView(mipped, 4, 2), nearest);
            ByteBuffer mipSprite = Glyphs.buffer(4 * 24);
            texVertex(mipSprite, 540, 300, 0, 0, 0xFFFFFFFF);
            texVertex(mipSprite, 540, 332, 0, 1, 0xFFFFFFFF);
            texVertex(mipSprite, 572, 332, 1, 1, 0xFFFFFFFF);
            texVertex(mipSprite, 572, 300, 1, 0, 0xFFFFFFFF);
            mipSprite.flip();
            draws += quads(device, pass, mipSprite, 1);
        }
        encoder.presentTexture(colorView);
        return draws;
    }

    /**
     * {@code SUNRISE_SUNSET} (TRIANGLE_FAN): a glow disc drawn without indices (18 vertices, like
     * {@code SkyRenderer}'s sunrise) and a star drawn through the sequential index buffer from
     * index 1 with base vertex 3, behind four unused vertices, so its first vertex is vertex 4.
     */
    private static int fans(GpuDevice device, RenderPass pass) {
        pass.setPipeline(RenderPipelines.SUNRISE_SUNSET);
        ByteBuffer glow = Glyphs.buffer(18 * 16);
        colorVertex(glow, 92.0F, 205.0F, 0xE0FFB040);
        for (int i = 0; i <= 16; i++) {
            double angle = i * Math.PI * 2.0 / 16.0;
            colorVertex(glow, 92.0F + (float) Math.sin(angle) * 70.0F, 205.0F + (float) Math.cos(angle) * 55.0F,
                    0x00FF4020 | ((i % 4) * 0x30 << 24));
        }
        glow.flip();
        pass.setVertexBuffer(0, device.createBuffer(() -> "glow fan", GpuBuffer.USAGE_VERTEX, glow));
        pass.draw(0, 18);

        int points = 12;
        ByteBuffer star = Glyphs.buffer((4 + points + 2) * 16);
        for (int i = 0; i < 4; i++) {
            colorVertex(star, 0.0F, 0.0F, 0xFFFF00FF);
        }
        colorVertex(star, 548.0F, 205.0F, 0xF0FFFFFF);
        for (int i = 0; i <= points; i++) {
            double angle = i * Math.PI * 2.0 / points;
            float radius = i % 2 == 0 ? 64.0F : 30.0F;
            colorVertex(star, 548.0F + (float) Math.sin(angle) * radius, 205.0F + (float) Math.cos(angle) * radius,
                    i % 2 == 0 ? 0xC03080FF : 0xC0FF3080);
        }
        star.flip();
        int count = points + 2;
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.TRIANGLE_FAN);
        pass.setVertexBuffer(0, device.createBuffer(() -> "star fan", GpuBuffer.USAGE_VERTEX, star));
        pass.setIndexBuffer(indices.getBuffer(1 + count), indices.type());
        pass.drawIndexed(3, 1, count, 1);
        return 2;
    }

    /** {@code core/gui} with a depth test and depth writes (vanilla's GUI pipeline has neither). */
    private static final RenderPipeline DEPTH_QUADS = RenderPipeline.builder()
            .withLocation("pipeline/gaius_harness_depth_quads")
            .withVertexShader("core/gui")
            .withFragmentShader("core/gui")
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(true)
            .build();

    /**
     * A green quad at eye z -3000 (clip z -0.8 through the GUI's 1000..21000 orthographic
     * projection), then an overlapping red one at eye z -6000 (clip z -0.5): GL draws both and its
     * depth test hides the red one where they overlap.
     */
    private static int nearDepthQuads(GpuDevice device, RenderPass pass) {
        pass.setPipeline(DEPTH_QUADS);
        ByteBuffer near = Glyphs.buffer(4 * 16);
        colorQuad(near, 470, 260, 560, 330, 0xFF30C040);
        near.flip();
        pass.setUniform("DynamicTransforms", transform(device, new Matrix4f().translation(0.0F, 0.0F, -3000.0F)));
        quads(device, pass, near, 1);
        ByteBuffer far = Glyphs.buffer(4 * 16);
        colorQuad(far, 520, 290, 610, 350, 0xFFD03030);
        far.flip();
        pass.setUniform("DynamicTransforms", transform(device, new Matrix4f().translation(0.0F, 0.0F, -6000.0F)));
        quads(device, pass, far, 1);
        return 2;
    }

    private static int quads(GpuDevice device, RenderPass pass, ByteBuffer vertices, int quads) {
        GpuBuffer vertexBuffer = device.createBuffer(() -> "vertices", GpuBuffer.USAGE_VERTEX, vertices);
        ByteBuffer indices = Glyphs.buffer(quads * 6 * 2);
        for (int q = 0; q < quads; q++) {
            int base = q * 4;
            for (int index : new int[] { 0, 1, 2, 2, 3, 0 }) {
                indices.putShort((short) (base + index));
            }
        }
        indices.flip();
        GpuBuffer indexBuffer = device.createBuffer(() -> "indices", GpuBuffer.USAGE_INDEX, indices);
        pass.setVertexBuffer(0, vertexBuffer);
        pass.setIndexBuffer(indexBuffer, VertexFormat.IndexType.SHORT);
        pass.drawIndexed(0, 0, quads * 6, 1);
        return 1;
    }

    private static GpuBuffer ubo(GpuDevice device, Matrix4f matrix) {
        ByteBuffer data = Std140Builder.intoBuffer(Glyphs.buffer(64)).putMat4f(matrix).get();
        return device.createBuffer(() -> "Projection", GpuBuffer.USAGE_UNIFORM, copy(data));
    }

    private static GpuBuffer transform(GpuDevice device, Matrix4f modelView) {
        ByteBuffer data = Std140Builder.intoBuffer(Glyphs.buffer(160)).putMat4f(modelView).putVec4(1, 1, 1, 1)
                .putVec3(0, 0, 0).putMat4f(new Matrix4f()).get();
        return device.createBuffer(() -> "DynamicTransforms", GpuBuffer.USAGE_UNIFORM, copy(data));
    }

    private static ByteBuffer copy(ByteBuffer data) {
        data.rewind();
        ByteBuffer out = Glyphs.buffer(data.remaining());
        out.put(data).flip();
        return out;
    }

    private static ByteBuffer cubeVertices() {
        float[][] faces = {
            { -1, -1, 1, -1, 1, 1, 1, 1, 1, 1, -1, 1 },
            { 1, -1, 1, 1, 1, 1, 1, 1, -1, 1, -1, -1 },
            { 1, -1, -1, 1, 1, -1, -1, 1, -1, -1, -1, -1 },
            { -1, -1, -1, -1, 1, -1, -1, 1, 1, -1, -1, 1 },
            { -1, -1, -1, -1, -1, 1, 1, -1, 1, 1, -1, -1 },
            { -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, 1, 1 }
        };
        ByteBuffer out = Glyphs.buffer(6 * 4 * 12);
        for (float[] face : faces) {
            for (float value : face) {
                out.putFloat(value);
            }
        }
        out.flip();
        return out;
    }

    private static ByteBuffer face(int face, int size) {
        int[][] colors = { { 200, 90, 60 }, { 70, 160, 90 }, { 60, 100, 200 }, { 210, 190, 70 }, { 120, 80, 50 },
            { 150, 200, 240 } };
        ByteBuffer data = Glyphs.buffer(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float grid = (x % 16 < 1 || y % 16 < 1) ? 0.55F : 1.0F;
                for (int c = 0; c < 3; c++) {
                    data.put((byte) Math.round(colors[face][c] * (0.6F + 0.4F * y / (size - 1)) * grid));
                }
                data.put((byte) 255);
            }
        }
        data.flip();
        return data;
    }

    private static void colorQuad(ByteBuffer out, float x0, float y0, float x1, float y1, int argb) {
        colorVertex(out, x0, y0, argb);
        colorVertex(out, x0, y1, argb);
        colorVertex(out, x1, y1, argb);
        colorVertex(out, x1, y0, argb);
    }

    private static void colorVertex(ByteBuffer out, float x, float y, int argb) {
        out.putFloat(x).putFloat(y).putFloat(0.0F);
        out.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
    }

    private static void texVertex(ByteBuffer out, float x, float y, float u, float v, int argb) {
        out.putFloat(x).putFloat(y).putFloat(0.0F).putFloat(u).putFloat(v);
        out.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
    }
}
