package dev.gaius.gpu.webgpu;
public final class CoreTextureDimensionTest {
  public static void main(String[] args) {
    // Dimensions are pure metadata; no live WebGPU device is needed for this check.  CoreDevice's
    // constructor is private and the real factory requires an initialized browser device.
    CoreTexture t = new CoreTexture(null, 0, "test", CoreFormat.RGBA8_UNORM, 1, 1, 1, 4, 7);
    if (t.width(0) != 1 || t.width(3) != 1 || t.height(3) != 1) {
      throw new AssertionError("mip dimensions must stay at least one texel");
    }
    CoreTexture larger = new CoreTexture(null, 0, "test", CoreFormat.RGBA8_UNORM, 8, 4, 1, 4, 8);
    if (larger.width(2) != 2 || larger.height(2) != 1) {
      throw new AssertionError("mip dimensions are wrong");
    }
  }
}
