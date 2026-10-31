package dev.gaius.gpu.webgpu;

/**
 * A seam's texture type that wraps a core texture. Code outside the seam that only sees vanilla's
 * texture interface (the 26.3 quality hooks patched into GameRenderer and LevelRenderer) finds
 * the core texture, and with it the glue handle the page-side passes resolve, through it.
 */
public interface CoreTextureOwner {
    CoreTexture coreTexture();
}
