package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.gaius.gpu.webgpu.CoreFormat;
import dev.gaius.gpu.webgpu.EnumBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.IntUnaryOperator;

/**
 * Completeness of the renderpearl-1 seam's enum and flag mappings against the vanilla jar of a
 * profile (JVM test, run by {@code port/mc/seams/test-seam.sh renderpearl-1}):
 * <ul>
 * <li>every constant of every bridged vanilla enum has a core constant;</li>
 * <li>bridged formats keep vanilla's block size and aspects;</li>
 * <li>every vanilla usage and write-mask bit maps to its own core bit.</li>
 * </ul>
 * Exits with status 1 on any failure.
 */
public final class RpEnumsTest {
    private static int failures;

    private RpEnumsTest() {
    }

    public static void main(String[] args) {
        for (EnumBridge<?, ?> bridge : RpEnums.all()) {
            if (bridge.missing().isEmpty()) {
                System.out.println("ok   " + bridge.name() + (bridge.unused().isEmpty() ? ""
                        : " (core constants without a vanilla counterpart: " + bridge.unused() + ")"));
            } else {
                fail(bridge.name() + ": vanilla constants without a core constant: " + bridge.missing());
            }
        }
        for (GpuFormat format : GpuFormat.values()) {
            CoreFormat core;
            try {
                core = RpEnums.FORMAT.toCore(format);
            } catch (IllegalArgumentException e) {
                continue; // reported above
            }
            if (core.blockSize() != format.blockSize() || core.hasColorAspect() != format.hasColorAspect()
                    || core.hasDepthAspect() != format.hasDepthAspect()
                    || core.hasStencilAspect() != format.hasStencilAspect()) {
                fail("GpuFormat." + format + ": block size or aspects differ from CoreFormat." + core);
            }
        }
        System.out.println("ok   GpuFormat block sizes and aspects");
        checkBits("GpuBuffer.USAGE_*", GpuBuffer.class, "USAGE_", RpEnums::bufferUsage);
        checkBits("GpuTexture.USAGE_*", GpuTexture.class, "USAGE_", RpEnums::textureUsage);
        checkBits("ColorTargetState.WRITE_*", ColorTargetState.class, "WRITE_", RpEnums::writeMask);
        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("all renderpearl-1 mappings complete");
    }

    /**
     * Every single-bit {@code public static final int} constant with the prefix maps to exactly
     * one core bit, and no two vanilla bits map to the same core bit.
     */
    private static void checkBits(String name, Class<?> owner, String prefix, IntUnaryOperator mapping) {
        int seen = 0;
        int bits = 0;
        for (Field field : owner.getFields()) {
            int modifiers = field.getModifiers();
            if (!field.getName().startsWith(prefix) || field.getType() != int.class || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers)) {
                continue;
            }
            int value;
            try {
                value = field.getInt(null);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            if (Integer.bitCount(value) != 1) {
                continue; // combinations such as WRITE_ALL
            }
            bits++;
            int mapped = mapping.applyAsInt(value);
            if (Integer.bitCount(mapped) != 1) {
                fail(name + ": " + field.getName() + " maps to " + Integer.toBinaryString(mapped));
            } else if ((seen & mapped) != 0) {
                fail(name + ": " + field.getName() + " maps to a core bit another constant already uses");
            }
            seen |= mapped;
        }
        if (bits == 0) {
            fail(name + ": no single-bit constants found");
        } else {
            System.out.println("ok   " + name + " (" + bits + " bits)");
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }
}
