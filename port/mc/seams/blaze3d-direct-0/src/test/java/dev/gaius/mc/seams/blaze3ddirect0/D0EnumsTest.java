package dev.gaius.mc.seams.blaze3ddirect0;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.gaius.gpu.webgpu.CoreFormat;
import dev.gaius.gpu.webgpu.EnumBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.IntUnaryOperator;

/**
 * Completeness of the blaze3d-direct-0 seam's enum and flag mappings against the vanilla jar of a
 * profile (JVM test, run by {@code port/mc/seams/test-seam.sh blaze3d-direct-0}):
 * <ul>
 * <li>every constant of every bridged vanilla enum has a core constant;</li>
 * <li>bridged formats keep vanilla's block size and aspects;</li>
 * <li>every vanilla usage and write-mask bit maps to its own core bit.</li>
 * </ul>
 * Exits with status 1 on any failure.
 */
public final class D0EnumsTest {
    private static int failures;

    private D0EnumsTest() {
    }

    public static void main(String[] args) {
        for (EnumBridge<?, ?> bridge : D0Enums.all()) {
            if (bridge.missing().isEmpty()) {
                System.out.println("ok   " + bridge.name() + (bridge.unused().isEmpty() ? ""
                        : " (core constants without a vanilla counterpart: " + bridge.unused() + ")"));
            } else {
                fail(bridge.name() + ": vanilla constants without a core constant: " + bridge.missing());
            }
        }
        for (TextureFormat format : TextureFormat.values()) {
            CoreFormat core;
            try {
                core = D0Enums.TEXTURE_FORMAT.toCore(format);
            } catch (IllegalArgumentException e) {
                continue; // reported above
            }
            // 1.21.11 reports no colour aspect for the integer RED8I (only its GL backend reads the
            // aspects, render-spi data/textureformat-1.21.11.tsv); the core's R8_SINT is a colour format.
            boolean colorAspect = format == TextureFormat.RED8I || core.hasColorAspect() == format.hasColorAspect();
            if (core.blockSize() != format.pixelSize() || !colorAspect || core.hasDepthAspect() != format.hasDepthAspect()) {
                fail("TextureFormat." + format + ": pixel size or aspects differ from CoreFormat." + core);
            }
        }
        System.out.println("ok   TextureFormat pixel sizes and aspects");
        vertexFormats();
        checkBits("GpuBuffer.USAGE_*", GpuBuffer.class, "USAGE_", D0Enums::bufferUsage);
        checkBits("GpuTexture.USAGE_*", GpuTexture.class, "USAGE_", D0Enums::textureUsage);
        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("all blaze3d-direct-0 mappings complete");
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

    /**
     * Every element of every vanilla vertex format has a WebGPU vertex format, and every format but
     * the one the LineWidth REQUIRED patch realigns is addressable by WebGPU as vanilla lays it out.
     */
    private static void vertexFormats() {
        int formats = 0;
        for (Field field : DefaultVertexFormat.class.getFields()) {
            if (field.getType() != VertexFormat.class) {
                continue;
            }
            VertexFormat format;
            try {
                format = (VertexFormat) field.get(null);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            formats++;
            boolean aligned = (format.getVertexSize() & 3) == 0 || format.getElements().isEmpty();
            for (VertexFormatElement element : format.getElements()) {
                CoreFormat core = D0Enums.vertexFormat(element);
                if (core == null || core.webVertexFormat() == null) {
                    fail(field.getName() + ": no WebGPU vertex format for " + element);
                }
                aligned &= core != null && format.getOffset(element) % Math.min(4, core.blockSize()) == 0;
            }
            boolean lineWidth = field.getName().equals("POSITION_COLOR_NORMAL_LINE_WIDTH");
            if (!aligned && !lineWidth) {
                fail(field.getName() + ": not addressable by WebGPU (stride " + format.getVertexSize() + ")");
            } else if (!aligned) {
                System.out.println("note " + field.getName() + ": stride " + format.getVertexSize()
                        + ", needs the LineWidth alignment patch");
            }
        }
        System.out.println("ok   DefaultVertexFormat (" + formats + " formats)");
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }
}
