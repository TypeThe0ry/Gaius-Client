package dev.gaius.harness.b1;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.gaius.harness.common.Glyphs;
import dev.gaius.harness.common.Page;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * A title-screen-like frame through vanilla 26.2's frontend with vanilla pipelines and shaders,
 * the same draw sequence the title screen issues: the panorama ({@code CubeMap.render}:
 * {@code RenderPipelines.PANORAMA}, a cube map, 85 degree perspective), the menu blur (the
 * {@code post_effect/blur} passes, {@code core/screenquad} + {@code post/box_blur}, main to swap
 * and back), then the GUI on top (solid quads, the logo and button sprites, text) in the GUI's
 * orthographic projection, with two triangle fans of {@code RenderPipelines.SUNRISE_SUNSET} (the
 * sky's topology) between the header band and the sprites: one drawn without indices, as the sky
 * is, and one through the shared sequential index buffer at an index and vertex offset, as
 * immediate-mode fans are. Textures are generated (no game assets), every draw goes through
 * {@link GpuDevice} / {@link CommandEncoder} / {@link RenderPass}, so the WebGPU seam and the
 * vanilla GL backend run the identical sequence.
 */
final class TitleScene {
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;
    static final int GUI_SCALE = 2;
    private static final int GUI_WIDTH = WIDTH / GUI_SCALE;
    private static final int GUI_HEIGHT = HEIGHT / GUI_SCALE;

    private final GpuDevice device;
    private final TextureTarget main;
    private final TextureTarget swap;
    private int draws;

    TitleScene(GpuDevice device) {
        this.device = device;
        this.main = new TextureTarget("main", WIDTH, HEIGHT, true, GpuFormat.RGBA8_UNORM);
        this.swap = new TextureTarget("swap", WIDTH, HEIGHT, false, GpuFormat.RGBA8_UNORM);
    }

    GpuTextureView output() {
        return this.main.getColorTextureView();
    }

    GpuTexture outputTexture() {
        return this.main.getColorTexture();
    }

    int draws() {
        return this.draws;
    }

    void render() {
        CommandEncoder encoder = this.device.createCommandEncoder();
        encoder.clearColorAndDepthTextures(this.main.getColorTexture(), new Vector4f(0.0F, 0.0F, 0.0F, 1.0F),
                this.main.getDepthTexture(), 1.0);
        this.panorama(encoder, 22.0F, 135.0F);
        this.blur(encoder);
        this.gui(encoder);
        encoder.submit();
    }

    // ---- panorama -----------------------------------------------------------------------------

    private void panorama(CommandEncoder encoder, float xRot, float yRot) {
        GpuTexture cube = this.device.createTexture("panorama", GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_CUBEMAP_COMPATIBLE, GpuFormat.RGBA8_UNORM, 256, 256, 6, 1);
        for (int face = 0; face < 6; face++) {
            encoder.writeToTexture(cube, panoramaFace(face, 256), 0, face, 0, 0, 256, 256);
        }
        GpuTextureView view = this.device.createTextureView(cube);
        // CubeMap.initializeVertices: six inward quads of the unit cube.
        float[][] faces = {
            { -1, -1, 1, -1, 1, 1, 1, 1, 1, 1, -1, 1 },
            { 1, -1, 1, 1, 1, 1, 1, 1, -1, 1, -1, -1 },
            { 1, -1, -1, 1, 1, -1, -1, 1, -1, -1, -1, -1 },
            { -1, -1, -1, -1, 1, -1, -1, 1, 1, -1, -1, 1 },
            { -1, -1, -1, -1, -1, 1, 1, -1, 1, 1, -1, -1 },
            { -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, 1, 1 }
        };
        ByteBuffer vertices = buffer(6 * 4 * 12);
        for (float[] quad : faces) {
            for (float value : quad) {
                vertices.putFloat(value);
            }
        }
        vertices.flip();
        GpuBuffer vertexBuffer = this.device.createBuffer(() -> "panorama", GpuBuffer.USAGE_VERTEX, vertices);
        Projection projection = new Projection();
        projection.setupPerspective(0.05F, 10.0F, 85.0F, WIDTH, HEIGHT);
        GpuBufferSlice projectionSlice = new ProjectionMatrixBuffer("panorama").getBuffer(projection);
        Matrix4f modelView = new Matrix4f().rotationX((float) Math.PI).rotateX(xRot * ((float) Math.PI / 180.0F))
                .rotateY(yRot * ((float) Math.PI / 180.0F));
        GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(modelView);
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(36);
        try (RenderPass pass = encoder.createRenderPass(() -> "panorama", this.main.getColorTextureView(),
                Optional.empty(), this.main.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderPipelines.PANORAMA);
            pass.setUniform("Projection", projectionSlice);
            pass.setUniform("DynamicTransforms", transform);
            pass.setVertexBuffer(0, vertexBuffer.slice());
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.bindTexture("Sampler0", view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.drawIndexed(36, 1, 0, 0, 0);
            this.draws++;
        }
    }

    /** A smooth face: a per-face colour, a vertical gradient, a soft grid and a disc. */
    private static ByteBuffer panoramaFace(int face, int size) {
        int[][] colors = { { 200, 90, 60 }, { 70, 160, 90 }, { 60, 100, 200 }, { 210, 190, 70 }, { 120, 80, 50 },
            { 150, 200, 240 } };
        ByteBuffer data = buffer(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float t = y / (float) (size - 1);
                float grid = (x % 32 < 2 || y % 32 < 2) ? 0.55F : 1.0F;
                float dx = x - size / 2.0F;
                float dy = y - size / 2.0F;
                float disc = dx * dx + dy * dy < 40 * 40 ? 1.25F : 1.0F;
                for (int c = 0; c < 3; c++) {
                    float value = colors[face][c] * (0.55F + 0.45F * t) * grid * disc;
                    data.put((byte) Math.min(255, Math.round(value)));
                }
                data.put((byte) 255);
            }
        }
        data.flip();
        return data;
    }

    // ---- menu blur (post_effect/blur.json, first pass pair) ------------------------------------

    private RenderPipeline blurPipeline;

    /** The menu blur pass's pipeline: what {@code PostChain.createPass} builds for post_effect/blur. */
    RenderPipeline blurPipeline() {
        if (this.blurPipeline == null) {
            this.blurPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation("harness/blur")
                    .withVertexShader("core/screenquad")
                    .withFragmentShader("post/box_blur")
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withSampler("InSampler")
                            .withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
                            .withUniform("BlurConfig", UniformType.UNIFORM_BUFFER)
                            .build())
                    .build();
        }
        return this.blurPipeline;
    }

    private void blur(CommandEncoder encoder) {
        RenderPipeline pipeline = this.blurPipeline();
        GpuBufferSlice globals = this.globals();
        GpuBufferSlice samplerInfo = this.ubo("SamplerInfo", Std140Builder.intoBuffer(buffer(16))
                .putVec2(WIDTH, HEIGHT).putVec2(WIDTH, HEIGHT).get());
        GpuSampler bilinear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        float[][] directions = { { 1.0F, 0.0F }, { 0.0F, 1.0F } };
        TextureTarget[] inputs = { this.main, this.swap };
        TextureTarget[] outputs = { this.swap, this.main };
        for (int i = 0; i < 2; i++) {
            GpuBufferSlice config = this.ubo("BlurConfig", Std140Builder.intoBuffer(buffer(16))
                    .putVec2(directions[i][0], directions[i][1]).putFloat(0.0F).get());
            try (RenderPass pass = encoder.createRenderPass(() -> "blur", outputs[i].getColorTextureView(),
                    Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.setUniform("Globals", globals);
                pass.setUniform("SamplerInfo", samplerInfo);
                pass.setUniform("BlurConfig", config);
                pass.bindTexture("InSampler", inputs[i].getColorTextureView(), bilinear);
                pass.draw(3, 1, 0, 0);
                this.draws++;
            }
        }
    }

    private GpuBufferSlice ubo(String label, ByteBuffer data) {
        data.rewind();
        ByteBuffer copy = buffer(data.remaining());
        copy.put(data).flip();
        return this.device.createBuffer(() -> label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, copy).slice();
    }

    // ---- GUI ------------------------------------------------------------------------------------

    private void gui(CommandEncoder encoder) {
        Projection projection = new Projection();
        projection.setupOrtho(1000.0F, 11000.0F, GUI_WIDTH, GUI_HEIGHT, true);
        GpuBufferSlice projectionSlice = new ProjectionMatrixBuffer("gui").getBuffer(projection);
        Matrix4f modelView = new Matrix4f().translation(0.0F, 0.0F, -2000.0F);
        GpuBufferSlice identity = RenderSystem.getDynamicUniforms().writeTransform(modelView);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        GpuTexture logo = this.texture("logo", 128, 32, logoPixels());
        GpuTexture button = this.texture("button", 200, 20, buttonPixels(false));
        GpuTexture buttonHover = this.texture("button_hover", 200, 20, buttonPixels(true));
        GpuTexture font = this.texture("font", 128, 64, Glyphs.atlas());

        try (RenderPass pass = encoder.createRenderPass(() -> "gui", this.main.getColorTextureView(), Optional.empty(),
                this.main.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setUniform("Projection", projectionSlice);
            pass.setUniform("DynamicTransforms", identity);
            // Darkened header band and a translucent footer.
            this.colorQuads(pass, new float[][] { { 0, 0, GUI_WIDTH, 92 }, { 0, GUI_HEIGHT - 22, GUI_WIDTH, GUI_HEIGHT } },
                    new int[] { 0x50000000, 0x80000000 });
            this.fans(pass);
            // The logo, twice the texture size.
            this.texturedQuad(pass, logo, nearest, GUI_WIDTH / 2.0F - 128, 24, 256, 64, 0xFFFFFFFF);
            String[] labels = { "SINGLEPLAYER", "MULTIPLAYER", "MINECRAFT REALMS", "OPTIONS" };
            for (int i = 0; i < labels.length; i++) {
                float y = 120 + i * 26;
                this.texturedQuad(pass, i == 1 ? buttonHover : button, nearest, GUI_WIDTH / 2.0F - 100, y, 200, 20,
                        0xFFFFFFFF);
                this.text(pass, font, nearest, labels[i], GUI_WIDTH / 2.0F, y + 6, 0xFFFFFFFF, true, 1.0F);
            }
            this.text(pass, font, nearest, "GAIUS SEAM HARNESS 26.2", 2, GUI_HEIGHT - 15, 0xFFFFFFFF, false, 1.0F);
            this.text(pass, font, nearest, "WEBGPU VS WEBGL 2", GUI_WIDTH - 104, GUI_HEIGHT - 15, 0xFFC0C0C0, false,
                    1.0F);
            // The splash: rotated and scaled through the model-view matrix, as the title screen does.
            Matrix4f splashView = new Matrix4f(modelView).translate(GUI_WIDTH / 2.0F + 110, 80, 0)
                    .rotateZ(-20.0F * ((float) Math.PI / 180.0F)).scale(1.4F);
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(splashView,
                    new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new Matrix4f()));
            this.text(pass, font, nearest, "ALSO TRY WEBGPU!", 0, -4, 0xFFFFFF00, true, 1.0F);
        }
    }

    /**
     * Two fans with vanilla's {@code SUNRISE_SUNSET} pipeline (TRIANGLE_FAN, translucent): a
     * glow disc drawn without indices (18 vertices, like {@code SkyRenderer}'s sunrise), and a
     * star-shaped fan drawn through the sequential index buffer from index 1 with vertex offset 3,
     * behind four unused vertices, so its first vertex is vertex 4.
     */
    private void fans(RenderPass pass) {
        pass.setPipeline(RenderPipelines.SUNRISE_SUNSET);
        pass.setUniform("Globals", this.globals());
        ByteBuffer glow = buffer(18 * 16);
        this.colorVertex(glow, 92.0F, 205.0F, 0xE0FFB040);
        for (int i = 0; i <= 16; i++) {
            double angle = i * Math.PI * 2.0 / 16.0;
            this.colorVertex(glow, 92.0F + (float) Math.sin(angle) * 70.0F, 205.0F + (float) Math.cos(angle) * 55.0F,
                    0x00FF4020 | ((i % 4) * 0x30 << 24));
        }
        glow.flip();
        pass.setVertexBuffer(0, this.device.createBuffer(() -> "glow fan", GpuBuffer.USAGE_VERTEX, glow).slice());
        pass.draw(18, 1, 0, 0);
        this.draws++;

        int points = 12;
        ByteBuffer star = buffer((4 + points + 2) * 16);
        for (int i = 0; i < 4; i++) {
            this.colorVertex(star, 0.0F, 0.0F, 0xFFFF00FF);
        }
        this.colorVertex(star, GUI_WIDTH - 92.0F, 205.0F, 0xF0FFFFFF);
        for (int i = 0; i <= points; i++) {
            double angle = i * Math.PI * 2.0 / points;
            float radius = i % 2 == 0 ? 64.0F : 30.0F;
            // The glow's winding (SUNRISE_SUNSET culls back faces).
            this.colorVertex(star, GUI_WIDTH - 92.0F + (float) Math.sin(angle) * radius,
                    205.0F + (float) Math.cos(angle) * radius, i % 2 == 0 ? 0xC03080FF : 0xC0FF3080);
        }
        star.flip();
        int count = points + 2;
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.TRIANGLE_FAN);
        pass.setVertexBuffer(0, this.device.createBuffer(() -> "star fan", GpuBuffer.USAGE_VERTEX, star).slice());
        pass.setIndexBuffer(indices.getBuffer(1 + count), indices.type());
        pass.drawIndexed(count, 1, 1, 3, 0);
        this.draws++;
    }

    private GpuBufferSlice globals;

    /** The Globals block of the blur and the fans (screen size, menu blur radius). */
    private GpuBufferSlice globals() {
        if (this.globals == null) {
            this.globals = this.ubo("Globals", Std140Builder.intoBuffer(buffer(64))
                    .putIVec3(0, 0, 0).putVec3(0.0F, 0.0F, 0.0F).putVec2(WIDTH, HEIGHT).putFloat(1.0F).putFloat(0.0F)
                    .putInt(5).putInt(0).get());
        }
        return this.globals;
    }

    private GpuTexture texture(String label, int width, int height, ByteBuffer pixels) {
        GpuTexture texture = this.device.createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        this.device.createCommandEncoder().writeToTexture(texture, pixels, 0, 0, 0, 0, width, height);
        return texture;
    }

    /** Quads {x0, y0, x1, y1} in the given colours with RenderPipelines.GUI. */
    private void colorQuads(RenderPass pass, float[][] quads, int[] colors) {
        ByteBuffer vertices = buffer(quads.length * 4 * 16);
        for (int i = 0; i < quads.length; i++) {
            float[] q = quads[i];
            int argb = colors[i];
            this.colorVertex(vertices, q[0], q[1], argb);
            this.colorVertex(vertices, q[0], q[3], argb);
            this.colorVertex(vertices, q[2], q[3], argb);
            this.colorVertex(vertices, q[2], q[1], argb);
        }
        vertices.flip();
        pass.setPipeline(RenderPipelines.GUI);
        this.drawQuads(pass, vertices, quads.length);
    }

    private void colorVertex(ByteBuffer out, float x, float y, int argb) {
        out.putFloat(x).putFloat(y).putFloat(0.0F);
        putColor(out, argb);
    }

    private void texturedQuad(RenderPass pass, GpuTexture texture, GpuSampler sampler, float x, float y, float width,
            float height, int argb) {
        ByteBuffer vertices = buffer(4 * 24);
        texVertex(vertices, x, y, 0.0F, 0.0F, argb);
        texVertex(vertices, x, y + height, 0.0F, 1.0F, argb);
        texVertex(vertices, x + width, y + height, 1.0F, 1.0F, argb);
        texVertex(vertices, x + width, y, 1.0F, 0.0F, argb);
        vertices.flip();
        pass.setPipeline(RenderPipelines.GUI_TEXTURED);
        pass.bindTexture("Sampler0", this.device.createTextureView(texture), sampler);
        this.drawQuads(pass, vertices, 1);
    }

    /** Text with RenderPipelines.GUI_TEXT from the generated 8x8 glyph atlas (shadowed like vanilla). */
    private void text(RenderPass pass, GpuTexture font, GpuSampler sampler, String text, float x, float y, int argb,
            boolean centered, float scale) {
        float width = text.length() * Glyphs.ADVANCE * scale;
        float left = centered ? x - width / 2.0F : x;
        ByteBuffer vertices = buffer(text.length() * 2 * 4 * 24);
        int quads = 0;
        int shadow = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
        for (int pass2 = 0; pass2 < 2; pass2++) {
            float offset = pass2 == 0 ? scale : 0.0F;
            int color = pass2 == 0 ? shadow : argb;
            for (int i = 0; i < text.length(); i++) {
                int cell = Glyphs.cell(text.charAt(i));
                if (cell < 0) {
                    continue;
                }
                float u0 = (cell % 16) * 8 / 128.0F;
                float v0 = (cell / 16) * 8 / 64.0F;
                float u1 = u0 + 8 / 128.0F;
                float v1 = v0 + 8 / 64.0F;
                float gx = left + i * Glyphs.ADVANCE * scale + offset;
                float gy = y + offset;
                float size = 8 * scale;
                texVertex(vertices, gx, gy, u0, v0, color);
                texVertex(vertices, gx, gy + size, u0, v1, color);
                texVertex(vertices, gx + size, gy + size, u1, v1, color);
                texVertex(vertices, gx + size, gy, u1, v0, color);
                quads++;
            }
        }
        vertices.flip();
        pass.setPipeline(RenderPipelines.GUI_TEXT);
        pass.bindTexture("Sampler0", this.device.createTextureView(font), sampler);
        this.drawQuads(pass, vertices, quads);
    }

    private static void texVertex(ByteBuffer out, float x, float y, float u, float v, int argb) {
        out.putFloat(x).putFloat(y).putFloat(0.0F).putFloat(u).putFloat(v);
        putColor(out, argb);
    }

    /** Color as vanilla's vertex consumers write it: RGBA bytes. */
    private static void putColor(ByteBuffer out, int argb) {
        out.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
    }

    private void drawQuads(RenderPass pass, ByteBuffer vertices, int quads) {
        if (quads == 0) {
            return;
        }
        GpuBuffer vertexBuffer = this.device.createBuffer(() -> "gui", GpuBuffer.USAGE_VERTEX, vertices);
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        int count = quads * 6;
        pass.setVertexBuffer(0, vertexBuffer.slice());
        pass.setIndexBuffer(indices.getBuffer(count), indices.type());
        pass.drawIndexed(count, 1, 0, 0, 0);
        this.draws++;
    }

    // ---- generated sprites ------------------------------------------------------------------

    private static ByteBuffer logoPixels() {
        int width = 128;
        int height = 32;
        ByteBuffer data = buffer(width * height * 4);
        String word = "GAIUS";
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Letters at 3x scale (5x7 glyphs, 8 px advance), a dark outline around them.
                boolean on = Glyphs.letterPixel(word, (x - 4) / 3, (y - 5) / 3);
                boolean outline = !on && (Glyphs.letterPixel(word, (x - 3) / 3, (y - 5) / 3)
                        || Glyphs.letterPixel(word, (x - 5) / 3, (y - 5) / 3)
                        || Glyphs.letterPixel(word, (x - 4) / 3, (y - 4) / 3)
                        || Glyphs.letterPixel(word, (x - 4) / 3, (y - 6) / 3));
                if (on) {
                    int shade = 150 + (y * 90) / height;
                    data.put((byte) shade).put((byte) shade).put((byte) (shade - 40)).put((byte) 255);
                } else if (outline) {
                    data.put((byte) 30).put((byte) 30).put((byte) 30).put((byte) 255);
                } else {
                    data.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
                }
            }
        }
        data.flip();
        return data;
    }

    private static ByteBuffer buttonPixels(boolean hover) {
        int width = 200;
        int height = 20;
        ByteBuffer data = buffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean border = x == 0 || y == 0 || x == width - 1 || y == height - 1;
                int base = hover ? 130 : 110;
                int value = border ? (hover ? 255 : 0) : base + (height - y) * 2;
                data.put((byte) value).put((byte) value).put((byte) (hover ? value : value + 8)).put((byte) 255);
            }
        }
        data.flip();
        return data;
    }

    static ByteBuffer buffer(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }
}
