package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Direct BigInt implementations for Minecraft's packed block coordinates. */
public final class BrowserBlockPos {
    private BrowserBlockPos() {
    }

    @JSBody(params = {"packed"},
            script = "return Number(BigInt.asIntN(26, packed >> BigInt(38))) | 0;")
    public static native int getX(long packed);

    @JSBody(params = {"packed"},
            script = "return Number(BigInt.asIntN(12, packed)) | 0;")
    public static native int getY(long packed);

    @JSBody(params = {"packed"},
            script = "return Number(BigInt.asIntN(26, packed >> BigInt(12))) | 0;")
    public static native int getZ(long packed);

    @JSBody(params = {"x", "y", "z"}, script = """
            const packed = (BigInt.asUintN(26, BigInt(x)) << BigInt(38))
              | BigInt.asUintN(12, BigInt(y))
              | (BigInt.asUintN(26, BigInt(z)) << BigInt(12));
            return BigInt.asIntN(64, packed);
            """)
    public static native long asLong(int x, int y, int z);
}
