package dev.gaius.browser.quality;

import org.joml.Matrix4fc;
import org.teavm.jso.JSBody;

/**
 * Per-frame hooks of the 26.3 graphics quality tiers, installed by
 * {@code dev.gaius.tools.patches.render.QualityTierPatches}. The work happens in the page's quality
 * runtime ({@code port/web/runtime/quality/quality-runtime.js}); every call degrades to the
 * vanilla behaviour when that runtime is not loaded.
 *
 * <ul>
 *   <li>World render scale: {@link #beginLevel} at {@code GameRenderer.renderLevel} entry asks
 *       the runtime for the scale (quantized, 1 by default, {@code ?gaiusRenderScale=}). While
 *       it is below 1, {@code GlCommandEncoder.createRenderPass} passes its viewport through
 *       {@link #levelViewportWidth}/{@link #levelViewportHeight}, which shrink viewports of
 *       passes whose attachments are the main-target size, so the world renders into the
 *       bottom-left sub-rectangle of the unchanged (never reallocated) targets.</li>
 *   <li>{@link #endLevel} right after {@code LevelRenderer.render}: ends the scaled section and
 *       lets the runtime run the post-processing chain and upscale the sub-rectangle to the full
 *       target, before the hand, the entity-outline blit, post effects and the GUI.</li>
 *   <li>Inventory screens (every profile: {@code QualityTierPatches} on 26.3,
 *       {@code MinecraftClientPatcher.patchGameRendererBrowserInventoryWorldRenderThrottle} on
 *       26.2 and 1.21.11): {@link #shouldSkipWorldRender} replaces
 *       {@code BrowserOpenGL.shouldSkipWorldRenderForScreen}. Instead of skipping the world
 *       after the first frames, the world renders at a reduced rate per tier
 *       ({@code ?gaiusInventoryWorldFps=}); on skipped frames {@link #worldFrameDone} has the
 *       runtime restore the last finished world image, because every profile clears the main
 *       target before the world section of each frame (26.2/26.3 in
 *       {@code GameRenderer.render}, 1.21.11 in {@code Minecraft.runTick}).</li>
 * </ul>
 */
public final class BrowserQualityFrame {
    private static final String INVENTORY_PACKAGE = "net.minecraft.client.gui.screens.inventory.";
    /** Lowest per-mille scale accepted from the runtime. */
    private static final int MIN_SCALE_PER_MILLE = 250;

    private static boolean levelScaled;
    private static int levelWidth;
    private static int levelHeight;
    private static int scaledWidth;
    private static int scaledHeight;
    private static boolean viewportWidthScaled;

    private static boolean inventoryThrottled;
    private static boolean worldSkipped;
    private static String inventoryScreen;
    private static int inventoryFrames;

    private BrowserQualityFrame() {
    }

    /**
     * {@code fullResolution} is set when this frame uses improved transparency or renders entity
     * outlines: both composite main-target-sized targets with full-texture coordinates inside
     * {@code LevelRenderer.render}, which a scaled viewport would distort.
     */
    public static void beginLevel(int width, int height, boolean fullResolution) {
        levelScaled = false;
        viewportWidthScaled = false;
        levelWidth = width;
        levelHeight = height;
        scaledWidth = width;
        scaledHeight = height;
        if (width <= 0 || height <= 0) {
            return;
        }
        int perMille = beginLevelScale(width, height, fullResolution);
        if (perMille >= 1000 || perMille <= 0) {
            return;
        }
        perMille = Math.max(MIN_SCALE_PER_MILLE, perMille);
        scaledWidth = Math.max(1, (int) (((long) width * perMille + 500) / 1000));
        scaledHeight = Math.max(1, (int) (((long) height * perMille + 500) / 1000));
        levelScaled = scaledWidth < width || scaledHeight < height;
    }

    /** Width argument of the render-pass viewport; always called right before the height. */
    public static int levelViewportWidth(int width) {
        viewportWidthScaled = levelScaled && width == levelWidth;
        return viewportWidthScaled ? scaledWidth : width;
    }

    public static int levelViewportHeight(int height) {
        boolean scale = viewportWidthScaled && height == levelHeight;
        viewportWidthScaled = false;
        return scale ? scaledHeight : height;
    }

    /**
     * Ends the world section. {@code projection} is the camera projection matrix
     * ({@code org.joml.Matrix4fc}); the post chain reconstructs view-space positions from the
     * depth texture with it.
     */
    public static void endLevel(int colorTexture, int depthTexture, int outlineTexture,
            Object projection) {
        boolean scaled = levelScaled;
        levelScaled = false;
        viewportWidthScaled = false;
        int width = levelWidth;
        int height = levelHeight;
        if (width <= 0 || height <= 0 || colorTexture == 0) {
            return;
        }
        float m00 = 0;
        float m11 = 0;
        float m20 = 0;
        float m21 = 0;
        float m22 = 0;
        float m23 = 0;
        float m32 = 0;
        float m33 = 0;
        if (projection instanceof Matrix4fc matrix) {
            m00 = matrix.m00();
            m11 = matrix.m11();
            m20 = matrix.m20();
            m21 = matrix.m21();
            m22 = matrix.m22();
            m23 = matrix.m23();
            m32 = matrix.m32();
            m33 = matrix.m33();
        }
        endLevelJs(colorTexture, depthTexture, outlineTexture, width, height,
                scaled ? scaledWidth : width, scaled ? scaledHeight : height,
                m00, m11, m20, m21, m22, m23, m32, m33);
    }

    /**
     * Whether the world render is skipped this frame. Outside inventory screens: never. Inside:
     * the first frame of a screen always renders; afterwards the runtime decides by the tier's
     * refresh rate. Without the runtime the previous behaviour is kept (render the first two
     * frames, then skip).
     */
    public static boolean shouldSkipWorldRender(Object screen) {
        worldSkipped = false;
        if (screen == null) {
            resetInventory();
            return false;
        }
        String name = screen.getClass().getName();
        if (!name.startsWith(INVENTORY_PACKAGE)) {
            resetInventory();
            return false;
        }
        inventoryThrottled = true;
        boolean newScreen = !name.equals(inventoryScreen);
        if (newScreen) {
            inventoryScreen = name;
            inventoryFrames = 0;
        } else {
            inventoryFrames++;
        }
        int decision = inventoryDecision(newScreen);
        if (decision < 0) {
            worldSkipped = !newScreen && inventoryFrames > 1;
        } else {
            worldSkipped = decision == 1;
        }
        return worldSkipped;
    }

    /** After the world section of the frame (rendered or skipped), before the GUI. */
    public static void worldFrameDone(int colorTexture, int width, int height) {
        levelScaled = false;
        viewportWidthScaled = false;
        boolean skipped = worldSkipped;
        worldSkipped = false;
        worldFrameDoneJs(colorTexture, width, height, skipped, inventoryThrottled);
    }

    private static void resetInventory() {
        inventoryThrottled = false;
        inventoryScreen = null;
        inventoryFrames = 0;
    }

    @JSBody(params = {"width", "height", "fullResolution"}, script = """
            var quality = globalThis.GaiusQuality;
            if (!quality || !quality.runtime) return 1000;
            try {
              return quality.runtime.beginLevel(width, height, fullResolution) | 0;
            } catch (e) {
              return 1000;
            }
            """)
    private static native int beginLevelScale(int width, int height, boolean fullResolution);

    @JSBody(params = {"color", "depth", "outline", "width", "height", "rectWidth", "rectHeight",
            "m00", "m11", "m20", "m21", "m22", "m23", "m32", "m33"}, script = """
            var quality = globalThis.GaiusQuality;
            if (!quality || !quality.runtime) return;
            try {
              quality.runtime.endLevel(color, depth, outline, width, height, rectWidth,
                  rectHeight, m00, m11, m20, m21, m22, m23, m32, m33);
            } catch (e) {
              // The runtime counts and reports its own failures.
            }
            """)
    private static native void endLevelJs(int color, int depth, int outline, int width,
            int height, int rectWidth, int rectHeight, float m00, float m11, float m20,
            float m21, float m22, float m23, float m32, float m33);

    @JSBody(params = "newScreen", script = """
            var quality = globalThis.GaiusQuality;
            if (!quality || !quality.runtime) return -1;
            try {
              return quality.runtime.inventoryDecision(newScreen) | 0;
            } catch (e) {
              return -1;
            }
            """)
    private static native int inventoryDecision(boolean newScreen);

    @JSBody(params = {"color", "width", "height", "skipped", "throttled"}, script = """
            var quality = globalThis.GaiusQuality;
            if (!quality || !quality.runtime) return;
            try {
              quality.runtime.worldFrameDone(color, width, height, skipped, throttled);
            } catch (e) {
              // Telemetry and recovery live in the runtime.
            }
            """)
    private static native void worldFrameDoneJs(int color, int width, int height,
            boolean skipped, boolean throttled);
}
