package dev.gaius.browser;

import io.netty.buffer.Unpooled;
import io.netty.channel.browser.BrowserWebSocketChannel;
import com.mojang.blaze3d.platform.MonitorManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.systems.RenderSystem;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.BrowserMemory;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/** Fast TeaVM/browser verification for the platform layer used by Minecraft. */
public final class PlatformSmoke {
    private static int keyEvents;
    private static int cursorEvents;
    private static int mouseEvents;
    private static int scrollEvents;
    private static String smokeStage = "startup";

    private PlatformSmoke() {
    }

    public static void main(String[] args) {
        try {
            smokeStage = "random access file";
            testRandomAccessFile();
            smokeStage = "browser ZIP pack";
            testBrowserZipPack();
            smokeStage = "multiplayer cold-pack recovery policy";
            testMultiplayerRecoveryPolicy();
            smokeStage = "browser atlas overlay compatibility";
            testBrowserAtlasOverlayCompatibility();
            smokeStage = "browser atlas resource fallback";
            testBrowserAtlasResourceFallback();
            smokeStage = "resource-pack texture slots";
            testSpriteTextureSlotCompatibility();
            smokeStage = "network compression";
            testNetworkCompression();
            smokeStage = "network packed longs";
            testNetworkPackedLongs();
            smokeStage = "improved noise";
            testImprovedNoiseHotPath();
            smokeStage = "Perlin wrap";
            testPerlinNoiseWrapHotPath();
            smokeStage = "Perlin amplitudes";
            testPerlinNoiseAmplitudeHotPath();
            smokeStage = "packed BlockPos";
            testBlockPosPackedCoordinates();
            smokeStage = "aquifer centers";
            testAquiferNearestCentersHotPath();
            smokeStage = "beardifier terrain blending";
            testBeardifierPackedComputeHotPath();
            smokeStage = "biome corners";
            testBiomeNearestCornerHotPath();
            smokeStage = "noise interpolation";
            testNoiseInterpolatorHotPath();
            smokeStage = "bit storage";
            testBitStorageHotPath();
            smokeStage = "climate distance";
            testClimateDistanceHotPath();
            smokeStage = "density transforms";
            testDensityTransformersHotPath();
            smokeStage = "surface biome supplier";
            testSurfaceBiomeSupplier();
            smokeStage = "floating point fma";
            testFloatingPointFma();
            smokeStage = "managed memory";
            testManagedMemory();
            smokeStage = "Minecraft backend initialization";
            testBackendInitialization();
            smokeStage = "window and WebGL";
            testWindowAndCallbacks();
            smokeStage = "browser audio";
            testBrowserAudio();
            smokeStage = "Unicode font fallback";
            testUnicodeFontFallbackAssets();
            smokeStage = "bitmap font decode";
            testBitmapFontAssetDecode();
            smokeStage = "browser crypto";
            testBrowserCrypto();
            smokeStage = "HTTP proxy";
            testBrowserHttpProxy();
            smokeStage = "browser network";
            testBrowserNetwork();
            smokeStage = "complete";
            report(true, "Gaius platform smoke passed");
        } catch (Throwable failure) {
            failure.printStackTrace();
            StackTraceElement[] stack = failure.getStackTrace();
            String location = stack.length == 0 ? "" : " @ " + stack[0];
            report(false, smokeStage + ": "
                    + failure.getClass().getName() + ": " + failure.getMessage() + location);
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new RuntimeException(failure);
        }
    }

    private static void testRandomAccessFile() throws Exception {
        Path path = Path.of("/gaius-smoke/region-test.mca");
        byte[] expected = "official-1.21.11".getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(8192);
            channel.write(ByteBuffer.wrap(expected));
            channel.force(false);
            if (channel.size() != 8192L + expected.length) {
                throw new AssertionError("FileChannel size mismatch: " + channel.size());
            }
            ByteBuffer read = ByteBuffer.allocate(expected.length);
            channel.position(8192);
            while (read.hasRemaining() && channel.read(read) >= 0) {
                // Keep reading until the requested region is complete.
            }
            for (int index = 0; index < expected.length; index++) {
                if (read.array()[index] != expected[index]) {
                    throw new AssertionError("FileChannel data mismatch at " + index);
                }
            }
        }

        byte marker = 0x5A;
        try (FileChannel reopened = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            if (reopened.size() != 8192L + expected.length) {
                throw new AssertionError(
                        "READ+WRITE reopen truncated an existing region file: "
                                + reopened.size());
            }
            ByteBuffer preserved = ByteBuffer.allocate(expected.length);
            reopened.position(8192);
            while (preserved.hasRemaining() && reopened.read(preserved) >= 0) {
                // Preserve the existing chunk payload while opening it for updates.
            }
            if (!Arrays.equals(expected, preserved.array())) {
                throw new AssertionError("READ+WRITE reopen changed existing region data");
            }
            reopened.position(0);
            reopened.write(ByteBuffer.wrap(new byte[] {marker}));
        }

        try (FileChannel verified = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer first = ByteBuffer.allocate(1);
            verified.read(first);
            if (first.get(0) != marker || verified.size() != 8192L + expected.length) {
                throw new AssertionError("Region update was not preserved across a second reopen");
            }
            ByteBuffer preserved = ByteBuffer.allocate(expected.length);
            verified.position(8192);
            while (preserved.hasRemaining() && verified.read(preserved) >= 0) {
                // Verify both the header update and the prior chunk payload survived.
            }
            if (!Arrays.equals(expected, preserved.array())) {
                throw new AssertionError("Region payload was lost after update and reopen");
            }
        }
    }

    private static void testFloatingPointFma() {
        float left = 1.25f;
        float right = -3.5f;
        float addend = 0.75f;
        float expected = (float) ((double) left * right + addend);
        float actual = Math.fma(left, right, addend);
        if (Float.floatToRawIntBits(actual) != Float.floatToRawIntBits(expected)) {
            throw new IllegalStateException("Browser float fma lost single-rounding semantics");
        }
        if (!Float.isNaN(Math.fma(Float.NaN, 1.0f, 2.0f))
                || !Float.isNaN(Math.fma(Float.POSITIVE_INFINITY, 1.0f, Float.NEGATIVE_INFINITY))) {
            throw new IllegalStateException("Browser float fma lost non-finite semantics");
        }
        if (Float.floatToRawIntBits(Math.fma(-0.0f, 1.0f, -0.0f))
                != Float.floatToRawIntBits(-0.0f)) {
            throw new IllegalStateException("Browser float fma lost negative zero");
        }

        double doubleActual = Math.fma(1.25d, -3.5d, 0.75d);
        if (Double.doubleToRawLongBits(doubleActual)
                != Double.doubleToRawLongBits(1.25d * -3.5d + 0.75d)) {
            throw new IllegalStateException("Browser double fma changed runtime semantics");
        }

        float jomlActual = org.joml.Math.fma(left, right, addend);
        float jomlExpected = left * right + addend;
        if (Float.floatToRawIntBits(jomlActual) != Float.floatToRawIntBits(jomlExpected)) {
            throw new IllegalStateException("JOML browser fma did not use direct multiply-add fallback");
        }
    }

    private static void testBrowserZipPack() throws Exception {
        Path path = Path.of("/gaius-smoke/server-pack.zip");
        byte[] metadata = "{\"pack\":{\"pack_format\":75,\"description\":\"Gaius smoke\"}}"
                .getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("pack.mcmeta"));
            output.write(metadata);
            output.closeEntry();
        }
        byte[] archiveBytes = Files.readAllBytes(path);
        int endOfCentralDirectory = -1;
        for (int index = archiveBytes.length - 22; index >= 0; index--) {
            if ((archiveBytes[index] & 0xff) == 0x50
                    && (archiveBytes[index + 1] & 0xff) == 0x4b
                    && (archiveBytes[index + 2] & 0xff) == 0x05
                    && (archiveBytes[index + 3] & 0xff) == 0x06) {
                endOfCentralDirectory = index;
                break;
            }
        }
        smokeStage = "browser ZIP pack open bytes=" + archiveBytes.length
                + " eocd=" + endOfCentralDirectory;
        try (ZipFile archive = new ZipFile(path.toFile())) {
            smokeStage = "browser ZIP pack entry lookup";
            ZipEntry entry = archive.getEntry("pack.mcmeta");
            if (entry == null) {
                throw new AssertionError("Browser server-pack ZIP entry was missing");
            }
            smokeStage = "browser ZIP pack entry content compressed=" + entry.getCompressedSize()
                    + " size=" + entry.getSize();
            if (!Arrays.equals(metadata, archive.getInputStream(entry).readAllBytes())) {
                throw new AssertionError("Browser server-pack ZIP could not be read");
            }
        }
        rewriteLocalZipEntryName(path, archiveBytes, endOfCentralDirectory, "pack.png");
        try (ZipFile archive = new ZipFile(path.toFile())) {
            smokeStage = "browser ZIP mismatched local-name entry lookup";
            ZipEntry entry = archive.getEntry("pack.mcmeta");
            if (entry == null || !Arrays.equals(metadata, archive.getInputStream(entry).readAllBytes())) {
                throw new AssertionError("Browser server-pack ZIP ignored the local-header name length");
            }
        }
        byte[] shorter = "short".getBytes(StandardCharsets.UTF_8);
        Files.write(path, shorter);
        if (!Arrays.equals(Files.readAllBytes(path), shorter)) {
            throw new AssertionError("Browser output stream did not truncate an existing file");
        }
    }

    private static void testMultiplayerRecoveryPolicy() {
        if (!BrowserMultiplayerRecovery.isTransientTimeoutReason(
                "You were kicked from spawn: Internal Exception: "
                        + "io.netty.handler.timeout.ReadTimeoutException")) {
            throw new AssertionError("ReadTimeoutException was not recoverable");
        }
        if (!BrowserMultiplayerRecovery.isTransientTimeoutReason("Connection timed out")) {
            throw new AssertionError("Network timeout was not recoverable");
        }
        if (BrowserMultiplayerRecovery.isTransientTimeoutReason("Login timed out")) {
            throw new AssertionError("Login timeout must not auto-reconnect");
        }
        if (BrowserMultiplayerRecovery.isTransientTimeoutReason(
                "You were kicked from spawn: \u7ed9\u60a8\u767b\u5f55\u7684\u65f6\u95f4\u5df2\u7ecf\u8fc7\u4e86")) {
            throw new AssertionError("Server login deadline must not auto-reconnect");
        }
        if (BrowserMultiplayerRecovery.isTransientTimeoutReason("You are banned")) {
            throw new AssertionError("Server policy disconnect must not auto-reconnect");
        }
        if (!BrowserMultiplayerRecovery.isRemoteServerAddress("mc.example.net")
                || BrowserMultiplayerRecovery.isRemoteServerAddress(
                        "client-0123456789abcdef0123456789abcdef.gaius-local:25565")) {
            throw new AssertionError("Singleplayer MessagePort address entered multiplayer recovery");
        }
    }

    private static void rewriteLocalZipEntryName(
            Path path, byte[] archive, int endOfCentralDirectory, String replacementName) throws Exception {
        if (readIntLE(archive, 0) != 0x04034b50) {
            throw new AssertionError("Browser server-pack ZIP local header was missing");
        }
        int originalNameLength = readUnsignedShortLE(archive, 26);
        byte[] replacement = replacementName.getBytes(StandardCharsets.UTF_8);
        if (replacement.length >= originalNameLength) {
            throw new AssertionError("Browser server-pack ZIP smoke replacement name must be shorter");
        }
        int removed = originalNameLength - replacement.length;
        byte[] rewritten = new byte[archive.length - removed];
        System.arraycopy(archive, 0, rewritten, 0, 30);
        writeShortLE(rewritten, 26, replacement.length);
        System.arraycopy(replacement, 0, rewritten, 30, replacement.length);
        System.arraycopy(
                archive,
                30 + originalNameLength,
                rewritten,
                30 + replacement.length,
                archive.length - 30 - originalNameLength);

        int rewrittenEndOfCentralDirectory = endOfCentralDirectory - removed;
        int centralDirectoryOffset = readIntLE(archive, endOfCentralDirectory + 16);
        writeIntLE(rewritten, rewrittenEndOfCentralDirectory + 16, centralDirectoryOffset - removed);
        Files.write(path, rewritten);
    }

    private static int readUnsignedShortLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int readIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static void writeShortLE(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void writeIntLE(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static void testSpriteTextureSlotCompatibility() {
        if (!BrowserTextureSlotsCompat.acceptsSpriteObject()) {
            throw new AssertionError("Browser texture-slot sprite compatibility failed");
        }
    }

    private static void testBrowserAtlasOverlayCompatibility() throws Exception {
        Path path = Path.of("/gaius-smoke/atlas-overlay-pack.zip");
        String metadata = """
                {"pack":{"pack_format":88,"description":"Gaius smoke"},"overlays":{"entries":[
                  {"directory":"safe_future","formats":[88,88]},
                  {"directory":"unsafe_future","formats":[88,88]}
                ]}}
                """;
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            writeZipEntry(output, "pack.mcmeta", metadata.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(output, "safe_future/assets/minecraft/atlases/items.json",
                    "{\"sources\":[]}".getBytes(StandardCharsets.UTF_8));
            writeZipEntry(output, "unsafe_future/assets/minecraft/shaders/core/entity.fsh",
                    "#version 150".getBytes(StandardCharsets.UTF_8));
        }
        List<String> selected = BrowserPackOverlayCompat.mergeSafeAtlasOverlays(
                path.toFile(), List.of("already_selected"));
        if (!selected.equals(List.of("already_selected", "safe_future"))) {
            throw new AssertionError("Browser atlas overlay selection was unsafe: " + selected);
        }
    }

    private static void testBrowserAtlasResourceFallback() {
        Identifier expected = Identifier.withDefaultNamespace("entity/trident");
        Identifier fallback = BrowserAtlasResourceFallback.vanillaEntityFallback(
                Identifier.fromNamespaceAndPath("elitefantasy", "entity/trident"));
        if (!expected.equals(fallback)
                || BrowserAtlasResourceFallback.vanillaEntityFallback(
                        Identifier.fromNamespaceAndPath("elitefantasy", "item/trident")) != null
                || BrowserAtlasResourceFallback.vanillaEntityFallback(expected) != null) {
            throw new AssertionError("Browser atlas resource fallback widened beyond entity textures");
        }
    }

    private static void writeZipEntry(ZipOutputStream output, String name, byte[] contents)
            throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(contents);
        output.closeEntry();
    }

    private static void testNetworkCompression() throws Exception {
        Deflater deflater = new Deflater();
        Inflater inflater = new Inflater();
        try {
            for (int round = 0; round < 2; round++) {
                int length = round == 0 ? 32 * 1024 + 137 : 9 * 1024 + 29;
                byte[] input = new byte[length];
                for (int index = 0; index < input.length; index++) {
                    input[index] = (byte) ((index * 31 + (index >>> 4) + round * 17) & 0xff);
                }

                deflater.setInput(input);
                deflater.finish();
                byte[] compressed = new byte[input.length + 1024];
                int compressedLength = 0;
                while (!deflater.finished()) {
                    int written = deflater.deflate(
                            compressed,
                            compressedLength,
                            Math.min(8192, compressed.length - compressedLength));
                    if (written <= 0) {
                        throw new AssertionError("Browser network deflater made no progress");
                    }
                    compressedLength += written;
                }

                byte[] baseline = inflateNetworkArrayBaseline(
                        compressed,
                        compressedLength,
                        input.length);
                byte[] actual;
                if (round == 0) {
                    actual = inflateNetworkHeapSlices(
                            inflater,
                            compressed,
                            compressedLength,
                            input.length);
                } else {
                    actual = inflateNetworkDirectBuffers(
                            inflater,
                            compressed,
                            compressedLength,
                            input.length);
                }
                if (!Arrays.equals(input, baseline) || !Arrays.equals(baseline, actual)) {
                    throw new AssertionError(
                            "Browser network ByteBuffer output diverged from the JVM byte-array "
                                    + "baseline in round " + round);
                }

                deflater.reset();
                inflater.reset();
            }

            Inflater malformedInflater = new Inflater();
            try {
                byte[] malformedBacking = new byte[12];
                int malformedOffset = 5;
                Arrays.fill(malformedBacking, malformedOffset, malformedOffset + 6, (byte) 0);
                ByteBuffer malformedRoot = ByteBuffer.wrap(malformedBacking);
                malformedRoot.position(3);
                malformedRoot.limit(11);
                ByteBuffer malformedInput = malformedRoot.slice();
                malformedInput.position(2);
                malformedInput.limit(8);
                malformedInflater.setInput(malformedInput);
                if (malformedInput.position() != malformedInput.limit()) {
                    throw new AssertionError("Malformed heap input did not advance to its limit");
                }

                ByteBuffer malformedOutput = ByteBuffer.allocate(32);
                malformedOutput.position(7);
                int outputStart = malformedOutput.position();
                try {
                    malformedInflater.inflate(malformedOutput);
                    throw new AssertionError("Malformed browser network stream was accepted");
                } catch (DataFormatException expected) {
                    if (malformedOutput.position() != outputStart) {
                        throw new AssertionError(
                                "Malformed inflate advanced output without producing bytes");
                    }
                }
            } finally {
                malformedInflater.end();
            }
        } finally {
            deflater.end();
            inflater.end();
        }
    }

    private static byte[] inflateNetworkArrayBaseline(
            byte[] compressed,
            int compressedLength,
            int expectedLength) throws Exception {
        Inflater baselineInflater = new Inflater();
        try {
            baselineInflater.setInput(compressed, 0, compressedLength);
            byte[] output = new byte[expectedLength];
            int inflated = baselineInflater.inflate(output, 0, output.length);
            if (inflated != expectedLength) {
                throw new AssertionError(
                        "JVM byte-array inflater baseline length mismatch: "
                                + inflated + " != " + expectedLength);
            }
            return output;
        } finally {
            baselineInflater.end();
        }
    }

    private static byte[] inflateNetworkHeapSlices(
            Inflater inflater,
            byte[] compressed,
            int compressedLength,
            int expectedLength) throws Exception {
        int inputRootOffset = 3;
        int inputPosition = 2;
        byte[] inputBacking = new byte[inputRootOffset + inputPosition + compressedLength + 4];
        System.arraycopy(
                compressed,
                0,
                inputBacking,
                inputRootOffset + inputPosition,
                compressedLength);
        ByteBuffer inputRoot = ByteBuffer.wrap(inputBacking);
        inputRoot.position(inputRootOffset);
        inputRoot.limit(inputBacking.length - 4);
        ByteBuffer compressedBuffer = inputRoot.slice();
        compressedBuffer.position(inputPosition);
        compressedBuffer.limit(inputPosition + compressedLength);
        int compressedLimit = compressedBuffer.limit();
        inflater.setInput(compressedBuffer);
        if (compressedBuffer.position() != compressedLimit) {
            throw new AssertionError("Heap sliced input did not advance to its limit");
        }

        int outputRootOffset = 4;
        int outputPosition = 7;
        byte[] outputBacking = new byte[outputRootOffset + outputPosition + expectedLength + 5];
        ByteBuffer outputRoot = ByteBuffer.wrap(outputBacking);
        outputRoot.position(outputRootOffset);
        outputRoot.limit(outputBacking.length - 5);
        ByteBuffer output = outputRoot.slice();
        output.position(outputPosition);
        output.limit(outputPosition + expectedLength);
        int inflated = inflater.inflate(output);
        if (inflated != expectedLength || output.position() != outputPosition + inflated) {
            throw new AssertionError(
                    "Heap sliced output position/count mismatch: "
                            + output.position() + "/" + inflated);
        }
        int outputOffset = output.arrayOffset() + outputPosition;
        return Arrays.copyOfRange(outputBacking, outputOffset, outputOffset + inflated);
    }

    private static byte[] inflateNetworkDirectBuffers(
            Inflater inflater,
            byte[] compressed,
            int compressedLength,
            int expectedLength) throws Exception {
        int inputPosition = 5;
        ByteBuffer compressedBuffer = ByteBuffer.allocateDirect(
                inputPosition + compressedLength + 3);
        compressedBuffer.position(inputPosition);
        compressedBuffer.put(compressed, 0, compressedLength);
        compressedBuffer.limit(inputPosition + compressedLength);
        compressedBuffer.position(inputPosition);
        int compressedLimit = compressedBuffer.limit();
        inflater.setInput(compressedBuffer);
        if (compressedBuffer.position() != compressedLimit) {
            throw new AssertionError("Direct input fallback did not advance to its limit");
        }

        int outputPosition = 6;
        ByteBuffer output = ByteBuffer.allocateDirect(outputPosition + expectedLength + 3);
        output.position(outputPosition);
        output.limit(outputPosition + expectedLength);
        int inflated = inflater.inflate(output);
        if (inflated != expectedLength || output.position() != outputPosition + inflated) {
            throw new AssertionError(
                    "Direct output fallback position/count mismatch: "
                            + output.position() + "/" + inflated);
        }

        byte[] actual = new byte[inflated];
        ByteBuffer verification = output.duplicate();
        verification.position(outputPosition);
        verification.limit(outputPosition + inflated);
        verification.get(actual);
        return actual;
    }

    private static void testNetworkPackedLongs() {
        long[] expected = {0L, 1L, -1L, 0x0123456789ABCDEFL, Long.MIN_VALUE, Long.MAX_VALUE};
        var buffer = Unpooled.buffer(expected.length * Long.BYTES);
        for (long value : expected) {
            buffer.writeLong(value);
        }
        long[] actual = BrowserLongArrayCodec.readFixedSizeLongArray(buffer, new long[expected.length]);
        if (!Arrays.equals(expected, actual) || buffer.isReadable()) {
            throw new AssertionError("Browser packed-long network decode changed packet bytes");
        }
    }

    @SuppressWarnings("deprecation")
    private static void testImprovedNoiseHotPath() {
        ImprovedNoise noise = new ImprovedNoise(new LegacyRandomSource(123456789L));
        assertRawDouble("ImprovedNoise v0", noise.noise(0.0, 0.0, 0.0), 0x3faa1e06e47d7800L);
        assertRawDouble(
                "ImprovedNoise v1", noise.noise(1.25, -2.5, 3.75), 0x3fa0a78a5fb438c0L);
        assertRawDouble(
                "ImprovedNoise v2",
                noise.noise(100.125, 64.5, -200.25),
                0x3fa961cfdc73d678L);
        assertRawDouble(
                "ImprovedNoise scaled",
                noise.noise(100.125, 64.5, -200.25, 0.125, 0.5),
                0x3fdabaf51dc7af1eL);
    }

    private static void assertRawDouble(String name, double actual, long expectedBits) {
        long actualBits = Double.doubleToRawLongBits(actual);
        if (actualBits != expectedBits) {
            throw new AssertionError(
                    name + " changed: " + Long.toHexString(actualBits)
                            + " != " + Long.toHexString(expectedBits));
        }
    }

    private static void testPerlinNoiseWrapHotPath() {
        double period = 3.3554432E7d;
        double[] values = {
                0.0d,
                1.25d,
                period - 0.5d,
                period + 0.5d,
                -period - 0.5d,
                1.25E12d,
                -1.25E12d,
                period * (9.007199254740992E15d + 1024.0d),
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY
        };
        for (double value : values) {
            double expected = Double.isFinite(value)
                    ? value - (double) Mth.lfloor(value / period + 0.5d) * period
                    : value;
            double actual = PerlinNoise.wrap(value);
            if (Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(expected)) {
                throw new AssertionError(
                        "Browser Perlin wrap changed for " + value + ": "
                                + actual + " != " + expected);
            }
        }
    }

    private static void testPerlinNoiseAmplitudeHotPath() {
        PerlinNoise first = PerlinNoise.create(
                new LegacyRandomSource(123456789L),
                -3,
                1.0,
                0.5,
                0.25,
                0.125);
        assertRawDouble("Perlin amplitudes p0", first.getValue(0.0, 0.0, 0.0), 0x3fc106c1816ca5b1L);
        assertRawDouble("Perlin amplitudes p1", first.getValue(1.25, -2.5, 3.75), 0x3fd082f7f82e058aL);
        assertRawDouble(
                "Perlin amplitudes p2",
                first.getValue(100.125, 64.5, -200.25),
                0x3fcc596b40d4a2ddL);
        assertRawDouble(
                "Perlin amplitudes p3",
                first.gaius$getValue(100.125, 64.5, -200.25, 0.125, 0.5, false),
                0x3fd0353a3c9fb177L);
        assertRawDouble(
                "Perlin amplitudes p4",
                first.gaius$getValue(100.125, 64.5, -200.25, 0.125, 0.5, true),
                PerlinNoise.gaius$hasOriginY()
                        ? 0xbfc0cf7defd90d30L
                        : 0x3fd0353a3c9fb177L);

        PerlinNoise sparse = PerlinNoise.create(
                new LegacyRandomSource(987654321L),
                -7,
                0.0,
                1.0,
                0.0,
                0.5,
                0.25,
                0.0,
                0.125);
        assertRawDouble(
                "Perlin amplitudes q0",
                sparse.getValue(-12345.75, 31.125, 6789.5),
                0x3fb2371a90529044L);
        assertRawDouble(
                "Perlin amplitudes q1",
                sparse.gaius$getValue(3.5e7, -6.75e7, 1.25e8, 0.0625, 1.75, false),
                0xbf7a07e97df9e5c3L);
    }

    private static void testBlockPosPackedCoordinates() {
        int[][] coordinates = {
                {0, 0, 0},
                {1, 1, 1},
                {-1, -1, -1},
                {33_554_431, 2_047, 33_554_431},
                {-33_554_432, -2_048, -33_554_432},
                {30_000_000, -64, -30_000_000},
                {-12_345_678, 319, 23_456_789}
        };
        for (int[] coordinate : coordinates) {
            int x = coordinate[0];
            int y = coordinate[1];
            int z = coordinate[2];
            long expected = ((long) x & 0x3ffffffL) << 38
                    | ((long) y & 0xfffL)
                    | ((long) z & 0x3ffffffL) << 12;
            long actual = BrowserBlockPos.asLong(x, y, z);
            long patched = BlockPos.asLong(x, y, z);
            if (actual != expected || patched != expected) {
                throw new AssertionError(
                        "Browser BlockPos packing changed for " + x + "/" + y + "/" + z);
            }
            if (BrowserBlockPos.getX(actual) != x
                    || BrowserBlockPos.getY(actual) != y
                    || BrowserBlockPos.getZ(actual) != z
                    || BlockPos.getX(actual) != x
                    || BlockPos.getY(actual) != y
                    || BlockPos.getZ(actual) != z) {
                throw new AssertionError(
                        "Browser BlockPos unpacking changed for " + x + "/" + y + "/" + z);
            }
        }
    }

    private static void testAquiferNearestCentersHotPath() {
        int minGridX = -4;
        int minGridY = -8;
        int minGridZ = 5;
        int gridSizeX = 4;
        int gridSizeZ = 4;
        int gridSizeY = 8;
        long[] cache = new long[gridSizeX * gridSizeY * gridSizeZ];
        for (int gridY = minGridY; gridY < minGridY + gridSizeY; gridY++) {
            for (int gridZ = minGridZ; gridZ < minGridZ + gridSizeZ; gridZ++) {
                for (int gridX = minGridX; gridX < minGridX + gridSizeX; gridX++) {
                    int hash = gridX * 73_428_767 ^ gridY * 912_367 ^ gridZ * 42_331;
                    int centerX = (gridX << 4) + Math.floorMod(hash, 10);
                    int centerY = gridY * 12 + Math.floorMod(hash >>> 5, 9);
                    int centerZ = (gridZ << 4) + Math.floorMod(hash >>> 11, 10);
                    int index = aquiferIndex(
                            minGridX,
                            minGridY,
                            minGridZ,
                            gridSizeX,
                            gridSizeZ,
                            gridX,
                            gridY,
                            gridZ);
                    cache[index] = BlockPos.asLong(centerX, centerY, centerZ);
                }
            }
        }

        int[] actual = new int[8];
        for (int sample = 0; sample < 96; sample++) {
            int baseGridX = minGridX + sample % 3;
            int baseGridY = minGridY + 1 + sample % (gridSizeY - 2);
            int baseGridZ = minGridZ + sample / 3 % 3;
            int blockX = (baseGridX << 4) + 5 + sample % 16;
            int blockY = baseGridY * 12 - 1 + sample % 12;
            int blockZ = (baseGridZ << 4) + 5 + sample * 7 % 16;
            int[] expected = referenceAquiferNearestCenters(
                    cache,
                    minGridX,
                    minGridY,
                    minGridZ,
                    gridSizeX,
                    gridSizeZ,
                    blockX,
                    blockY,
                    blockZ);
            Arrays.fill(actual, -1);
            if (!BrowserAquifer.selectNearestCached(
                            cache,
                            minGridX,
                            minGridY,
                            minGridZ,
                            gridSizeX,
                            gridSizeZ,
                            blockX,
                            blockY,
                            blockZ,
                            actual)
                    || !Arrays.equals(expected, actual)) {
                throw new AssertionError(
                        "Browser aquifer nearest centers changed at " + sample + ": "
                                + Arrays.toString(actual) + " != " + Arrays.toString(expected));
            }
        }

        int missIndex = aquiferIndex(
                minGridX,
                minGridY,
                minGridZ,
                gridSizeX,
                gridSizeZ,
                minGridX,
                minGridY,
                minGridZ);
        long cached = cache[missIndex];
        cache[missIndex] = Long.MAX_VALUE;
        if (BrowserAquifer.selectNearestCached(
                cache,
                minGridX,
                minGridY,
                minGridZ,
                gridSizeX,
                gridSizeZ,
                (minGridX << 4) + 5,
                (minGridY + 1) * 12 - 1,
                (minGridZ << 4) + 5,
                actual)) {
            throw new AssertionError("Browser aquifer cache miss did not preserve the vanilla path");
        }
        cache[missIndex] = cached;
    }

    private static int[] referenceAquiferNearestCenters(
            long[] cache,
            int minGridX,
            int minGridY,
            int minGridZ,
            int gridSizeX,
            int gridSizeZ,
            int blockX,
            int blockY,
            int blockZ) {
        int[] result = {
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                0,
                0,
                0,
                0
        };
        int baseGridX = blockX - 5 >> 4;
        int baseGridY = Math.floorDiv(blockY + 1, 12);
        int baseGridZ = blockZ - 5 >> 4;
        for (int offsetX = 0; offsetX <= 1; offsetX++) {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetZ = 0; offsetZ <= 1; offsetZ++) {
                    int index = aquiferIndex(
                            minGridX,
                            minGridY,
                            minGridZ,
                            gridSizeX,
                            gridSizeZ,
                            baseGridX + offsetX,
                            baseGridY + offsetY,
                            baseGridZ + offsetZ);
                    long position = cache[index];
                    int dx = BlockPos.getX(position) - blockX;
                    int dy = BlockPos.getY(position) - blockY;
                    int dz = BlockPos.getZ(position) - blockZ;
                    int distance = dx * dx + dy * dy + dz * dz;
                    int insertion = distance <= result[0]
                            ? 0
                            : distance <= result[1]
                                    ? 1
                                    : distance <= result[2] ? 2 : distance <= result[3] ? 3 : -1;
                    if (insertion < 0) {
                        continue;
                    }
                    for (int slot = 3; slot > insertion; slot--) {
                        result[slot] = result[slot - 1];
                        result[slot + 4] = result[slot + 3];
                    }
                    result[insertion] = distance;
                    result[insertion + 4] = index;
                }
            }
        }
        return result;
    }

    private static void testBeardifierPackedComputeHotPath() {
        Beardifier.Rigid rigid = new Beardifier.Rigid(
                new BoundingBox(-9, 47, 3, 7, 62, 19),
                TerrainAdjustment.BEARD_BOX,
                3);
        int[] packedRigid = BrowserBeardifier.packPieces(List.of(rigid));
        int[] expectedRigid = {-9, 47, 3, 7, 62, 19, 3, 3};
        if (!Arrays.equals(packedRigid, expectedRigid)) {
            throw new AssertionError("Browser beardifier piece packing changed");
        }
        JigsawJunction junction = new JigsawJunction(
                5, 71, -13, 4, StructureTemplatePool.Projection.RIGID);
        int[] packedJunction = BrowserBeardifier.packJunctions(List.of(junction));
        if (!Arrays.equals(packedJunction, new int[] {5, 71, -13})) {
            throw new AssertionError("Browser beardifier junction packing changed");
        }

        int[] pieces = {
                -8, 48, -8, 8, 60, 8, 0, 0,
                -15, 52, -4, -5, 68, 7, 1, -2,
                2, 57, -14, 16, 73, -2, 2, 4,
                -3, 43, 5, 12, 64, 18, 3, 3,
                -12, 50, -11, 4, 70, 6, 4, -1
        };
        int[] junctions = {
                -7, 58, 4,
                11, 66, -9,
                2, 47, 13
        };
        float[] kernel = new float[24 * 24 * 24];
        for (int index = 0; index < kernel.length; index++) {
            kernel[index] = (float) (Math.sin(index * 0.013) * 0.75 + 0.25);
        }
        for (int sample = 0; sample < 768; sample++) {
            int x = -24 + sample * 17 % 49;
            int y = 35 + sample * 29 % 48;
            int z = -25 + sample * 37 % 51;
            double expected = referenceBeardifier(pieces, junctions, kernel, x, y, z);
            double actual = BrowserBeardifier.compute(pieces, junctions, kernel, x, y, z);
            if (Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(expected)) {
                throw new AssertionError(
                        "Browser beardifier changed at " + sample + ": "
                                + Long.toHexString(Double.doubleToRawLongBits(actual)) + " != "
                                + Long.toHexString(Double.doubleToRawLongBits(expected)));
            }
        }
        if (BrowserBeardifier.compute(new int[0], new int[0], kernel, 0, 64, 0) != 0.0) {
            throw new AssertionError("Browser beardifier empty input changed");
        }
    }

    private static double referenceBeardifier(
            int[] pieces, int[] junctions, float[] kernel, int x, int y, int z) {
        double total = 0.0;
        for (int offset = 0; offset < pieces.length; offset += 8) {
            int minX = pieces[offset];
            int minY = pieces[offset + 1];
            int minZ = pieces[offset + 2];
            int maxX = pieces[offset + 3];
            int maxY = pieces[offset + 4];
            int maxZ = pieces[offset + 5];
            int adjustment = pieces[offset + 6];
            int groundY = minY + pieces[offset + 7];
            int distanceX = Math.max(0, Math.max(minX - x, x - maxX));
            int distanceZ = Math.max(0, Math.max(minZ - z, z - maxZ));
            int deltaY = y - groundY;
            int distanceY = switch (adjustment) {
                case 0 -> 0;
                case 1, 2 -> deltaY;
                case 3 -> Math.max(0, Math.max(groundY - y, y - maxY));
                case 4 -> Math.max(0, Math.max(minY - y, y - maxY));
                default -> throw new AssertionError("Unknown terrain adjustment " + adjustment);
            };
            total += switch (adjustment) {
                case 0 -> 0.0;
                case 1 -> referenceBuryContribution(distanceX, distanceY / 2.0, distanceZ);
                case 2, 3 -> referenceBeardContribution(
                        distanceX, distanceY, distanceZ, deltaY, kernel) * 0.8;
                case 4 -> referenceBuryContribution(
                        distanceX / 2.0, distanceY / 2.0, distanceZ / 2.0) * 0.8;
                default -> throw new AssertionError("Unknown terrain adjustment " + adjustment);
            };
        }
        for (int offset = 0; offset < junctions.length; offset += 3) {
            int deltaX = x - junctions[offset];
            int deltaY = y - junctions[offset + 1];
            int deltaZ = z - junctions[offset + 2];
            total += referenceBeardContribution(
                    deltaX, deltaY, deltaZ, deltaY, kernel) * 0.4;
        }
        return total;
    }

    private static double referenceBuryContribution(double x, double y, double z) {
        return Mth.clampedMap(Mth.length(x, y, z), 0.0, 6.0, 1.0, 0.0);
    }

    private static double referenceBeardContribution(
            int x, int y, int z, int deltaY, float[] kernel) {
        int kernelX = x + 12;
        int kernelY = y + 12;
        int kernelZ = z + 12;
        if (kernelX < 0 || kernelX >= 24
                || kernelY < 0 || kernelY >= 24
                || kernelZ < 0 || kernelZ >= 24) {
            return 0.0;
        }
        double vertical = deltaY + 0.5;
        double distanceSquared = Mth.lengthSquared(x, vertical, z);
        double scale = -vertical * Mth.fastInvSqrt(distanceSquared / 2.0) / 2.0;
        return scale * kernel[kernelZ * 24 * 24 + kernelX * 24 + kernelY];
    }

    private static int aquiferIndex(
            int minGridX,
            int minGridY,
            int minGridZ,
            int gridSizeX,
            int gridSizeZ,
            int gridX,
            int gridY,
            int gridZ) {
        return ((gridY - minGridY) * gridSizeZ + gridZ - minGridZ) * gridSizeX
                + gridX - minGridX;
    }

    private static void testNoiseInterpolatorHotPath() {
        double expected = Mth.lerp3(
                0.375, 0.625, 0.25,
                -10.5, 2.25, 9.75, -4.125,
                80.0, -3.5, 0.125, 16.875);
        double actual = BrowserNoiseInterpolator.lerp3(
                0.375, 0.625, 0.25,
                -10.5, 2.25, 9.75, -4.125,
                80.0, -3.5, 0.125, 16.875);
        if (Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(expected)) {
            throw new AssertionError("Browser noise interpolation changed: " + actual + " != " + expected);
        }
    }

    private static void testBitStorageHotPath() {
        int[] expected = new int[12];
        long packed = 0L;
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (index * 7 + 3) & 31;
            packed |= (long) expected[index] << (index * 5);
        }
        SimpleBitStorage storage = new SimpleBitStorage(5, expected.length, new long[] {packed});
        for (int index = 0; index < expected.length; index++) {
            if (storage.get(index) != expected[index]) {
                throw new AssertionError("Browser bit-storage get changed at " + index);
            }
        }
        int previous = storage.getAndSet(3, 17);
        if (previous != expected[3] || storage.get(3) != 17) {
            throw new AssertionError("Browser bit-storage getAndSet changed");
        }
        expected[3] = 17;
        storage.set(4, 19);
        expected[4] = 19;
        if (storage.get(4) != 19) {
            throw new AssertionError("Browser bit-storage set changed");
        }
        int[] actual = new int[expected.length];
        storage.unpack(actual);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("Browser bit-storage unpack did not update the Java array");
        }

        int[] bitWidths = {1, 2, 3, 5, 7, 8, 10, 16, 31, 32};
        for (int bits : bitWidths) {
            int valuesPerLong = 64 / bits;
            int size = valuesPerLong * 2;
            long[] raw = {
                    0x89ABCDEF01234567L ^ bits,
                    0xFEDCBA9876543210L ^ ((long) bits << 32)
            };
            long mask = (1L << bits) - 1L;
            SimpleBitStorage boundaryStorage = new SimpleBitStorage(bits, size, raw.clone());
            int[] boundaryExpected = new int[size];
            for (int index = 0; index < size; index++) {
                int cell = index / valuesPerLong;
                int shift = (index - cell * valuesPerLong) * bits;
                boundaryExpected[index] = (int) ((raw[cell] >>> shift) & mask);
                if (boundaryStorage.get(index) != boundaryExpected[index]) {
                    throw new AssertionError(
                            "Browser bit-storage boundary get changed for " + bits + " bits at " + index);
                }
            }
            for (int index = 0; index < size; index++) {
                int replacement = (int) ((0x5DEECE66DL * (index + 1L) + bits) & mask);
                int oldValue = boundaryStorage.getAndSet(index, replacement);
                if (oldValue != boundaryExpected[index] || boundaryStorage.get(index) != replacement) {
                    throw new AssertionError(
                            "Browser bit-storage boundary getAndSet changed for " + bits + " bits at " + index);
                }
                boundaryExpected[index] = replacement;
                if ((index & 1) != 0) {
                    int setReplacement = replacement ^ (int) (mask >>> 1);
                    boundaryStorage.set(index, setReplacement);
                    if (boundaryStorage.get(index) != setReplacement) {
                        throw new AssertionError(
                                "Browser bit-storage boundary set changed for " + bits + " bits at " + index);
                    }
                    boundaryExpected[index] = setReplacement;
                }
            }
            boundaryStorage.unpack(actual = new int[size]);
            if (!Arrays.equals(boundaryExpected, actual)) {
                throw new AssertionError("Browser bit-storage boundary unpack changed for " + bits + " bits");
            }
        }
    }

    private static void testClimateDistanceHotPath() {
        Climate.Parameter[] parameters = {
                new Climate.Parameter(-10L, 10L),
                new Climate.Parameter(20L, 30L),
                new Climate.Parameter(-2L, -2L),
                new Climate.Parameter(0L, 100L),
                new Climate.Parameter(-50L, -20L),
                new Climate.Parameter(7L, 9L),
                new Climate.Parameter(1000L, 2000L)
        };
        long[] target = {-15L, 20L, 35L, 101L, -3L, 8L, 5000L};
        long expected = 0L;
        for (int index = 0; index < parameters.length; index++) {
            long component = parameters[index].distance(target[index]);
            expected += component * component;
        }
        double[] bounds = BrowserClimate.prepareBounds(parameters);
        long actual = BrowserClimate.distance(bounds, target);
        long cached = BrowserClimate.distance(bounds, target);
        if (actual != expected || cached != expected) {
            throw new AssertionError(
                    "Browser climate distance changed: "
                            + actual + "/" + cached + " != " + expected);
        }
    }

    private static void testDensityTransformersHotPath() {
        double[] values = {
                Double.NaN, Double.NEGATIVE_INFINITY, -2.0, -1.0, -0.0,
                0.0, 0.25, 1.0, 2.0, Double.POSITIVE_INFINITY
        };
        for (double value : values) {
            assertSameDouble(
                    BrowserDensityFunctions.clamp(value, -1.0, 1.0),
                    Mth.clamp(value, -1.0, 1.0),
                    "clamp");
            assertSameDouble(
                    BrowserDensityFunctions.transformMulOrAdd(value, 0, 0.25),
                    value * 0.25,
                    "multiply");
            assertSameDouble(
                    BrowserDensityFunctions.transformMulOrAdd(value, 1, 0.25),
                    value + 0.25,
                    "add");
            for (int type = 0; type <= 6; type++) {
                double expected = switch (type) {
                    case 0 -> Math.abs(value);
                    case 1 -> value * value;
                    case 2 -> value * value * value;
                    case 3 -> value > 0.0 ? value : value * 0.5;
                    case 4 -> value > 0.0 ? value : value * 0.25;
                    case 5 -> 1.0 / value;
                    case 6 -> {
                        double clamped = Mth.clamp(value, -1.0, 1.0);
                        yield clamped / 2.0 - clamped * clamped * clamped / 24.0;
                    }
                    default -> throw new AssertionError("Unreachable mapped density type");
                };
                assertSameDouble(
                        BrowserDensityFunctions.transformMapped(value, type),
                        expected,
                        "mapped " + type);
            }
        }
    }

    private static void testSurfaceBiomeSupplier() {
        int[] calls = {0};
        int[] coordinates = new int[3];
        BrowserSurfaceBiomeSupplier supplier = new BrowserSurfaceBiomeSupplier(
                pos -> {
                    calls[0]++;
                    coordinates[0] = pos.getX();
                    coordinates[1] = pos.getY();
                    coordinates[2] = pos.getZ();
                    return null;
                },
                new BlockPos.MutableBlockPos());
        supplier.reset(3, 4, 5);
        supplier.get();
        supplier.get();
        if (calls[0] != 1
                || coordinates[0] != 3
                || coordinates[1] != 4
                || coordinates[2] != 5) {
            throw new AssertionError("Browser surface biome supplier did not cache its lookup");
        }
        supplier.reset(-7, 8, 9);
        supplier.get();
        if (calls[0] != 2
                || coordinates[0] != -7
                || coordinates[1] != 8
                || coordinates[2] != 9) {
            throw new AssertionError("Browser surface biome supplier did not reset coordinates");
        }
    }

    private static void assertSameDouble(double actual, double expected, String label) {
        if (Double.doubleToLongBits(actual) != Double.doubleToLongBits(expected)) {
            throw new AssertionError(
                    "Browser density " + label + " changed: " + actual + " != " + expected);
        }
    }

    private static void testBiomeNearestCornerHotPath() {
        long state = 0x243F6A8885A308D3L;
        for (int index = 0; index < 512; index++) {
            state = LinearCongruentialGenerator.next(state, index * 31L - 7001L);
            int x = (int) (state >>> 32);
            state = LinearCongruentialGenerator.next(state, x);
            int y = (int) state;
            state = LinearCongruentialGenerator.next(state, y);
            int z = (int) (state >>> 17);
            int expected = referenceNearestBiomeCorner(state, x, y, z);
            int actual = BrowserBiomeManager.nearestCorner(state, x - 2, y - 2, z - 2);
            if (actual != expected) {
                throw new AssertionError(
                        "Browser biome nearest corner changed at " + index + ": "
                                + actual + " != " + expected);
            }
        }
    }

    private static int referenceNearestBiomeCorner(long seed, int blockX, int blockY, int blockZ) {
        int shiftedX = blockX - 2;
        int shiftedY = blockY - 2;
        int shiftedZ = blockZ - 2;
        int baseX = shiftedX >> 2;
        int baseY = shiftedY >> 2;
        int baseZ = shiftedZ >> 2;
        double fractionX = (double) (shiftedX & 3) / 4.0;
        double fractionY = (double) (shiftedY & 3) / 4.0;
        double fractionZ = (double) (shiftedZ & 3) / 4.0;
        int nearest = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            boolean lowX = (corner & 4) == 0;
            boolean lowY = (corner & 2) == 0;
            boolean lowZ = (corner & 1) == 0;
            int quartX = lowX ? baseX : baseX + 1;
            int quartY = lowY ? baseY : baseY + 1;
            int quartZ = lowZ ? baseZ : baseZ + 1;
            long value = LinearCongruentialGenerator.next(seed, quartX);
            value = LinearCongruentialGenerator.next(value, quartY);
            value = LinearCongruentialGenerator.next(value, quartZ);
            value = LinearCongruentialGenerator.next(value, quartX);
            value = LinearCongruentialGenerator.next(value, quartY);
            value = LinearCongruentialGenerator.next(value, quartZ);
            double distanceX = (lowX ? fractionX : fractionX - 1.0) + biomeFiddle(value);
            value = LinearCongruentialGenerator.next(value, seed);
            double distanceY = (lowY ? fractionY : fractionY - 1.0) + biomeFiddle(value);
            value = LinearCongruentialGenerator.next(value, seed);
            double distanceZ = (lowZ ? fractionZ : fractionZ - 1.0) + biomeFiddle(value);
            double distance = distanceZ * distanceZ
                    + distanceY * distanceY + distanceX * distanceX;
            if (nearestDistance > distance) {
                nearest = corner;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static double biomeFiddle(long value) {
        return (Math.floorMod(value >> 24, 1024) / 1024.0 - 0.5) * 0.9;
    }

    private static void testManagedMemory() {
        long address = MemoryUtil.nmemAllocChecked(96);
        try {
            MemoryUtil.memPutInt(address + 4, 0x12111);
            if (MemoryUtil.memGetInt(address + 4) != 0x12111) {
                throw new AssertionError("Managed LWJGL memory mismatch");
            }

            long fullVertex = address + 16;
            BrowserMemory.putFastVertex(
                    fullVertex,
                    1.25f,
                    -2.5f,
                    3.75f,
                    0xA1B2C3D4,
                    0.125f,
                    0.875f,
                    0x11223344,
                    0x55667788,
                    -1.0f,
                    0.5f,
                    2.0f,
                    true);
            ByteBuffer full = MemoryUtil.memByteBuffer(fullVertex, 35)
                    .order(java.nio.ByteOrder.nativeOrder());
            if (full.getFloat(0) != 1.25f
                    || full.getFloat(4) != -2.5f
                    || full.getFloat(8) != 3.75f
                    || (full.get(12) & 0xff) != 0xB2
                    || (full.get(13) & 0xff) != 0xC3
                    || (full.get(14) & 0xff) != 0xD4
                    || (full.get(15) & 0xff) != 0xA1
                    || full.getFloat(16) != 0.125f
                    || full.getFloat(20) != 0.875f
                    || full.getInt(24) != 0x11223344
                    || full.getInt(28) != 0x55667788
                    || full.get(32) != (byte) -127
                    || full.get(33) != (byte) 63
                    || full.get(34) != (byte) 127) {
                throw new AssertionError("Browser fast full vertex layout mismatch");
            }

            long compactVertex = address + 56;
            BrowserMemory.putFastVertex(
                    compactVertex,
                    -4.0f,
                    5.0f,
                    6.0f,
                    0x10203040,
                    0.25f,
                    0.75f,
                    0,
                    0x01020304,
                    0.0f,
                    0.0f,
                    0.0f,
                    false);
            ByteBuffer compact = MemoryUtil.memByteBuffer(compactVertex, 28)
                    .order(java.nio.ByteOrder.nativeOrder());
            if (compact.getFloat(0) != -4.0f
                    || compact.getFloat(4) != 5.0f
                    || compact.getFloat(8) != 6.0f
                    || (compact.get(12) & 0xff) != 0x20
                    || (compact.get(13) & 0xff) != 0x30
                    || (compact.get(14) & 0xff) != 0x40
                    || (compact.get(15) & 0xff) != 0x10
                    || compact.getFloat(16) != 0.25f
                    || compact.getFloat(20) != 0.75f
                    || compact.getInt(24) != 0x01020304) {
                throw new AssertionError("Browser fast compact vertex layout mismatch");
            }
        } finally {
            MemoryUtil.nmemFree(address);
        }
    }

    private static void testWindowAndCallbacks() {
        GLFWErrorCallback callback = GLFWErrorCallback.create((error, description) -> {
        });
        if (GLFW.glfwSetErrorCallback(callback) != null) {
            throw new AssertionError("GLFW browser error callback did not start empty");
        }
        GLFWErrorCallback scopedCallback = GLFWErrorCallback.create((error, description) -> {
        });
        if (GLFW.glfwSetErrorCallback(scopedCallback) != callback) {
            throw new AssertionError("GLFW browser error callback did not return the previous callback");
        }
        GLFWErrorCallback replaced = GLFW.glfwSetErrorCallback(callback);
        if (replaced != scopedCallback || replaced.address() != scopedCallback.address()) {
            throw new AssertionError("GLFW browser error callback identity was not preserved");
        }
        replaced.close();
        if (!GLFW.glfwInit()) {
            throw new AssertionError("GLFW browser initialization failed");
        }
        long window = GLFW.glfwCreateWindow(960, 540, "Gaius platform smoke", 0L, 0L);
        if (window == 0L) {
            throw new AssertionError("Browser window was not created");
        }
        GLFW.glfwSetKeyCallback(window, (handle, key, scancode, action, modifiers) -> {
            keyEvents++;
        });
        GLFW.glfwSetCursorPosCallback(window, (handle, x, y) -> {
            cursorEvents++;
        });
        GLFW.glfwSetMouseButtonCallback(window, (handle, button, action, modifiers) -> {
            mouseEvents++;
        });
        GLFW.glfwSetScrollCallback(window, (handle, x, y) -> {
            scrollEvents++;
        });
        GLFWVidMode mode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        if (mode == null || mode.width() <= 0 || mode.height() <= 0) {
            throw new AssertionError("Browser video mode is invalid");
        }
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetFramebufferSize(window, width, height);
        if (width[0] <= 0 || height[0] <= 0) {
            throw new AssertionError("Browser framebuffer is invalid");
        }
        enqueueSyntheticInput();
        GLFW.glfwPollEvents();
        GLFW.glfwWaitEventsTimeout(0.0001);
        if (keyEvents != 1 || cursorEvents < 1 || mouseEvents != 1 || scrollEvents != 1) {
            throw new AssertionError(
                    "GLFW event bridge mismatch: "
                            + keyEvents + "/" + cursorEvents + "/" + mouseEvents + "/" + scrollEvents);
        }
        testWebGlRenderingSurface();
        callback.free();
    }

    private static void testBackendInitialization() throws Exception {
        RenderSystem.initRenderThread();
        var timeSource = RenderSystem.initBackendSystem();
        if (timeSource == null) {
            throw new AssertionError("Minecraft backend time source was not initialized");
        }
        Util.setTimeSource(timeSource);
        smokeStage = "Minecraft monitor initialization";
        try (MonitorManager manager = new MonitorManager()) {
            if (manager.getMonitor(GLFW.glfwGetPrimaryMonitor()) == null) {
                throw new AssertionError("Minecraft primary monitor was not initialized");
            }
            GlBackend backend = new GlBackend();
            smokeStage = "Minecraft GLFW window creation";
            long window = Window.createGlfwWindow(960, 540, "Gaius 26.2 backend smoke", 0L, backend);
            if (window == 0L) {
                throw new AssertionError("Minecraft OpenGL backend did not create a browser window");
            }
            smokeStage = "Minecraft GPU device creation";
            var device = backend.createDevice(
                    window,
                    (identifier, shaderType) -> "",
                    new GpuDebugOptions(0, false, false, false),
                    () -> {
                    });
            if (device.getDeviceInfo() == null) {
                throw new AssertionError("Minecraft OpenGL device was not initialized");
            }
            smokeStage = "Minecraft GPU surface creation";
            if (device.createSurface(window) == null) {
                throw new AssertionError("Minecraft OpenGL surface was not initialized");
            }
            smokeStage = "Minecraft renderer initialization";
            RenderSystem.initRenderer(device);
            smokeStage = "Minecraft GL33C core delegation";
            testGl33CoreDelegation();
        }
    }

    private static void testGl33CoreDelegation() {
        GL33C.glActiveTexture(GL33C.GL_TEXTURE1);
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);

        int buffer = GL33C.glGenBuffers();
        if (buffer == 0) {
            throw new AssertionError("GL33C buffer creation did not reach WebGL");
        }
        try {
            GL33C.glBindBuffer(GL33C.GL_COPY_WRITE_BUFFER, buffer);
            GL33C.glBufferData(GL33C.GL_COPY_WRITE_BUFFER, 64L, GL33C.GL_DYNAMIC_DRAW);
            ByteBuffer mapped = GL33C.glMapBufferRange(
                    GL33C.GL_COPY_WRITE_BUFFER,
                    0L,
                    64L,
                    GL33C.GL_MAP_WRITE_BIT);
            if (mapped == null || mapped.capacity() != 64) {
                throw new AssertionError("GL33C mapped buffer did not reach the browser allocator");
            }
            mapped.putInt(0, 0x47414955);
            if (!GL33C.glUnmapBuffer(GL33C.GL_COPY_WRITE_BUFFER)) {
                throw new AssertionError("GL33C mapped buffer did not unmap");
            }
            long mappedAddress = GL33C.nglMapBufferRange(
                    GL33C.GL_COPY_WRITE_BUFFER,
                    0L,
                    64L,
                    GL33C.GL_MAP_WRITE_BIT);
            if (mappedAddress == 0L) {
                throw new AssertionError("GL33C address mapped buffer did not reach the browser allocator");
            }
            MemoryUtil.memPutInt(mappedAddress, 0x53495541);
            if (!GL33C.glUnmapBuffer(GL33C.GL_COPY_WRITE_BUFFER)) {
                throw new AssertionError("GL33C address mapped buffer did not unmap");
            }
        } finally {
            GL33C.glBindBuffer(GL33C.GL_COPY_WRITE_BUFFER, 0);
            GL33C.glDeleteBuffers(buffer);
        }

        int vertexArray = GL33C.glGenVertexArrays();
        if (vertexArray == 0) {
            throw new AssertionError("GL33C vertex array creation did not reach WebGL");
        }
        GL33C.glBindVertexArray(vertexArray);
        GL33C.glBindVertexArray(0);
        GL33C.glDeleteVertexArrays(vertexArray);

        int framebuffer = GL33C.glGenFramebuffers();
        if (framebuffer == 0) {
            throw new AssertionError("GL33C framebuffer creation did not reach WebGL");
        }
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, framebuffer);
        GL33C.glColorMaski(0, true, true, true, true);
        GL33C.glDrawBuffer(GL33C.GL_NONE);
        GL33C.glDrawBuffer(GL33C.GL_COLOR_ATTACHMENT0);
        GL33C.glScissor(3, 4, 5, 6);
        GL33C.glBlendEquationSeparate(GL33C.GL_FUNC_ADD, GL33C.GL_FUNC_REVERSE_SUBTRACT);
        if (!gl33StateDelegationPassed()) {
            throw new AssertionError("GL33C draw-buffer, scissor, or blend state did not reach WebGL");
        }
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
        GL33C.glDeleteFramebuffers(framebuffer);

        int pixelBuffer = GL33C.glGenBuffers();
        GL33C.glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, pixelBuffer);
        GL33C.glBufferData(GL33C.GL_PIXEL_PACK_BUFFER, 4L, GL33C.GL_STREAM_READ);
        GL33C.glReadPixels(0, 0, 1, 1, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, 0L);
        GL33C.glBindBuffer(GL33C.GL_PIXEL_PACK_BUFFER, 0);
        GL33C.glDeleteBuffers(pixelBuffer);
        if (!pboReadbackDelegationPassed()) {
            throw new AssertionError("GL33C pixel-buffer readback did not reach WebGL");
        }

        long sync = GL33C.glFenceSync(GL33C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (sync == 0L) {
            throw new AssertionError("GL33C fence creation did not reach WebGL");
        }
        GL33C.glClientWaitSync(sync, 0, 0L);
        GL33C.glDeleteSync(sync);
    }

    @JSBody(script = """
            const state=window.__gaiusGL;
            return !!state && Array.isArray(state.lastDrawBuffers) &&
              state.lastDrawBuffers.length===1 &&
              (state.lastDrawBuffers[0]|0)===0x8CE0 &&
              (state.scissorX|0)===3 && (state.scissorY|0)===4 &&
              (state.scissorWidth|0)===5 && (state.scissorHeight|0)===6 &&
              (state.blendEquationRgb|0)===0x8006 &&
              (state.blendEquationAlpha|0)===0x800B;
            """)
    private static native boolean gl33StateDelegationPassed();

    @JSBody(script = """
            return (window.__gaiusGLStats && (window.__gaiusGLStats.readPixelsCalls|0)>0) || false;
            """)
    private static native boolean pboReadbackDelegationPassed();

    private static void testWebGlRenderingSurface() {
        GL.createCapabilities();
        GL11.glClearColor(0.08F, 0.10F, 0.14F, 1.0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        testMappedPixelBufferTextureUpload();
        testMappedR8PixelBufferTextureUpload();

        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);

        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(
                GL15.GL_ARRAY_BUFFER,
                ByteBuffer.wrap(new byte[] {0, 1, 2, 3}),
                GL15.GL_STATIC_DRAW);

        int shader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(
                shader,
                "#version 300 es\n"
                        + "void main(){gl_Position=vec4(0.0,0.0,0.0,1.0);}");
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new AssertionError("WebGL shader compile failed: " + GL20.glGetShaderInfoLog(shader));
        }
        GL20.glDeleteShader(shader);

        int modelEngineVertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(
                modelEngineVertexShader,
                "#version 330\n"
                        + "#define SKINRES 64\n"
                        + "#define SPACING 1024.0\n"
                        + "in vec2 UV0;\n"
                        + "void main(){int partId=1;vec2 uv=UV0 * SKINRES;"
                        + "float y=SPACING * (partId + 1);gl_Position=vec4(uv,y,1.0);}");
        GL20.glCompileShader(modelEngineVertexShader);
        if (GL20.glGetShaderi(modelEngineVertexShader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new AssertionError(
                    "WebGL ModelEngine vertex shader compatibility failed: "
                            + GL20.glGetShaderInfoLog(modelEngineVertexShader));
        }
        GL20.glDeleteShader(modelEngineVertexShader);

        int modelEngineFragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(
                modelEngineFragmentShader,
                "#version 330\n"
                        + "out vec4 fragColor;\n"
                        + "void main(){float fade=0.5;fragColor=vec4(1 - fade);}");
        GL20.glCompileShader(modelEngineFragmentShader);
        if (GL20.glGetShaderi(modelEngineFragmentShader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new AssertionError(
                    "WebGL ModelEngine fragment shader compatibility failed: "
                            + GL20.glGetShaderInfoLog(modelEngineFragmentShader));
        }
        GL20.glDeleteShader(modelEngineFragmentShader);
        GL15.glDeleteBuffers(buffer);
        GL11.glDeleteTextures(texture);
    }

    /** Matches the 26.2 bitmap-font path: mapped staging buffer, PBO upload, then sampling. */
    private static void testMappedPixelBufferTextureUpload() {
        int fontBufferBytes = 128 * 128 * 4;
        int pixelBuffer = GL33C.glGenBuffers();
        int texture = GL11.glGenTextures();
        int framebuffer = GL33C.glGenFramebuffers();
        ByteBuffer source = MemoryUtil.memAlloc(fontBufferBytes);
        ByteBuffer readback = MemoryUtil.memAlloc(4);
        try {
            source.put(0, (byte) 0x21);
            source.put(1, (byte) 0x43);
            source.put(2, (byte) 0x65);
            source.put(3, (byte) 0xFF);
            source.put(fontBufferBytes - 1, (byte) 0x7D);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, pixelBuffer);
            GL33C.glBufferData(
                    GL33C.GL_PIXEL_UNPACK_BUFFER, fontBufferBytes, GL33C.GL_STREAM_DRAW);
            ByteBuffer mapped = GL33C.glMapBufferRange(
                    GL33C.GL_PIXEL_UNPACK_BUFFER,
                    0L,
                    fontBufferBytes,
                    GL33C.GL_MAP_WRITE_BIT | GL33C.GL_MAP_FLUSH_EXPLICIT_BIT);
            ByteBuffer mappedView = MemoryUtil.memSlice(mapped, 0, fontBufferBytes);
            MemoryUtil.memCopy(source, mappedView);
            GL33C.glFlushMappedBufferRange(
                    GL33C.GL_PIXEL_UNPACK_BUFFER, 0L, fontBufferBytes);
            int stagedRgba = readBoundPixelUnpackBufferRgba();
            int expectedRgba = 0x21 | (0x43 << 8) | (0x65 << 16) | (0xFF << 24);
            if (stagedRgba != expectedRgba) {
                throw new AssertionError(
                        "Mapped pixel-buffer flush changed RGBA bytes: "
                                + Integer.toUnsignedString(stagedRgba, 16));
            }
            if (readBoundPixelUnpackBufferByte(fontBufferBytes - 1) != 0x7D) {
                throw new AssertionError("Mapped font-sized buffer copy truncated its tail");
            }
            if (!GL33C.glUnmapBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER)) {
                throw new AssertionError("Mapped pixel buffer could not be unmapped");
            }

            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL33C.GL_RGBA8,
                    1,
                    1,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, pixelBuffer);
            GL11.glTexSubImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    1,
                    1,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    0L);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);

            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, framebuffer);
            GL33C.glFramebufferTexture2D(
                    GL33C.GL_FRAMEBUFFER,
                    GL33C.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D,
                    texture,
                    0);
            int framebufferStatus = GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER);
            if (framebufferStatus != GL33C.GL_FRAMEBUFFER_COMPLETE) {
                throw new AssertionError(
                        "Mapped pixel-buffer smoke framebuffer is incomplete: "
                                + framebufferStatus);
            }
            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readback);
            int red = readback.get(0) & 0xFF;
            int green = readback.get(1) & 0xFF;
            int blue = readback.get(2) & 0xFF;
            int alpha = readback.get(3) & 0xFF;
            if (red != 0x21 || green != 0x43 || blue != 0x65 || alpha != 0xFF) {
                throw new AssertionError(
                        "Mapped pixel-buffer texture upload changed RGBA bytes: "
                                + red + "/" + green + "/" + blue + "/" + alpha);
            }

            // Minecraft 26.2 persistently maps each dynamic-uniform ring buffer
            // and explicitly flushes one aligned block at a non-zero offset.  A
            // ByteBuffer duplicate with only position/limit adjusted used to be
            // exported by TeaVM as the entire mapped allocation, turning this
            // four-byte flush into an out-of-bounds 32-byte WebGL upload.
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, pixelBuffer);
            GL33C.glBufferData(GL33C.GL_PIXEL_UNPACK_BUFFER, 32L, GL33C.GL_STREAM_DRAW);
            ByteBuffer subrangeMapped = GL33C.glMapBufferRange(
                    GL33C.GL_PIXEL_UNPACK_BUFFER,
                    0L,
                    32L,
                    GL33C.GL_MAP_WRITE_BIT | GL33C.GL_MAP_FLUSH_EXPLICIT_BIT);
            ByteBuffer subrangeView = MemoryUtil.memSlice(subrangeMapped, 4, 4);
            subrangeView.put(0, (byte) 0x12);
            subrangeView.put(1, (byte) 0x34);
            subrangeView.put(2, (byte) 0x56);
            subrangeView.put(3, (byte) 0x78);
            GL33C.glFlushMappedBufferRange(GL33C.GL_PIXEL_UNPACK_BUFFER, 4L, 4L);
            int subrangeRgba = readBoundPixelUnpackBufferRgba(4);
            int expectedSubrangeRgba = 0x12 | (0x34 << 8) | (0x56 << 16) | (0x78 << 24);
            if (subrangeRgba != expectedSubrangeRgba) {
                throw new AssertionError(
                        "Mapped pixel-buffer sub-range flush changed RGBA bytes: "
                                + Integer.toUnsignedString(subrangeRgba, 16));
            }
            if (!GL33C.glUnmapBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER)) {
                throw new AssertionError("Mapped pixel-buffer sub-range could not be unmapped");
            }
        } finally {
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
            GL33C.glDeleteFramebuffers(framebuffer);
            GL11.glDeleteTextures(texture);
            GL33C.glDeleteBuffers(pixelBuffer);
            MemoryUtil.memFree(source);
            MemoryUtil.memFree(readback);
        }
    }

    /** Matches the single-channel atlas used by uncolored 26.2 font glyphs. */
    private static void testMappedR8PixelBufferTextureUpload() {
        int pixelBuffer = GL33C.glGenBuffers();
        int texture = GL11.glGenTextures();
        int framebuffer = GL33C.glGenFramebuffers();
        ByteBuffer source = MemoryUtil.memAlloc(1);
        ByteBuffer readback = MemoryUtil.memAlloc(4);
        try {
            source.put(0, (byte) 0xCC);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, pixelBuffer);
            GL33C.glBufferData(GL33C.GL_PIXEL_UNPACK_BUFFER, 1L, GL33C.GL_STREAM_DRAW);
            ByteBuffer mapped = GL33C.glMapBufferRange(
                    GL33C.GL_PIXEL_UNPACK_BUFFER,
                    0L,
                    1L,
                    GL33C.GL_MAP_WRITE_BIT | GL33C.GL_MAP_FLUSH_EXPLICIT_BIT);
            ByteBuffer mappedView = MemoryUtil.memSlice(mapped, 0, 1);
            MemoryUtil.memCopy(source, mappedView);
            GL33C.glFlushMappedBufferRange(GL33C.GL_PIXEL_UNPACK_BUFFER, 0L, 1L);
            if (!GL33C.glUnmapBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER)) {
                throw new AssertionError("Mapped R8 pixel buffer could not be unmapped");
            }
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL33C.GL_R8,
                    1,
                    1,
                    0,
                    GL11.GL_RED,
                    GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, pixelBuffer);
            GL11.glTexSubImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    1,
                    1,
                    GL11.GL_RED,
                    GL11.GL_UNSIGNED_BYTE,
                    0L);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, framebuffer);
            GL33C.glFramebufferTexture2D(
                    GL33C.GL_FRAMEBUFFER,
                    GL33C.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D,
                    texture,
                    0);
            int framebufferStatus = GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER);
            if (framebufferStatus != GL33C.GL_FRAMEBUFFER_COMPLETE) {
                throw new AssertionError(
                        "Mapped R8 pixel-buffer smoke framebuffer is incomplete: "
                                + framebufferStatus);
            }
            GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readback);
            int red = readback.get(0) & 0xFF;
            int green = readback.get(1) & 0xFF;
            int blue = readback.get(2) & 0xFF;
            int alpha = readback.get(3) & 0xFF;
            if (red != 0xCC || green != 0 || blue != 0 || alpha != 0xFF) {
                throw new AssertionError(
                        "Mapped R8 pixel-buffer texture upload changed RGBA bytes: "
                                + red + "/" + green + "/" + blue + "/" + alpha);
            }
        } finally {
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
            GL33C.glDeleteFramebuffers(framebuffer);
            GL11.glDeleteTextures(texture);
            GL33C.glDeleteBuffers(pixelBuffer);
            MemoryUtil.memFree(source);
            MemoryUtil.memFree(readback);
        }
    }

    @JSBody(script = """
            const gl=window.__gaiusWebGL;
            const rgba=new Uint8Array(4);
            gl.getBufferSubData(gl.PIXEL_UNPACK_BUFFER,0,rgba);
            return (rgba[0]|(rgba[1]<<8)|(rgba[2]<<16)|(rgba[3]<<24))|0;
            """)
    private static native int readBoundPixelUnpackBufferRgba();

    @JSBody(params = "offset", script = """
            const gl=window.__gaiusWebGL;
            const rgba=new Uint8Array(4);
            gl.getBufferSubData(gl.PIXEL_UNPACK_BUFFER,offset|0,rgba);
            return (rgba[0]|(rgba[1]<<8)|(rgba[2]<<16)|(rgba[3]<<24))|0;
            """)
    private static native int readBoundPixelUnpackBufferRgba(int offset);

    @JSBody(params = "offset", script = """
            const gl=window.__gaiusWebGL;
            const value=new Uint8Array(1);
            gl.getBufferSubData(gl.PIXEL_UNPACK_BUFFER,offset|0,value);
            return value[0]|0;
            """)
    private static native int readBoundPixelUnpackBufferByte(int offset);

    private static void testBrowserAudio() throws Exception {
        long device = ALC10.alcOpenDevice((CharSequence) null);
        long context = ALC10.alcCreateContext(device, (int[]) null);
        if (device == 0L || context == 0L || !ALC10.alcMakeContextCurrent(context)) {
            throw new AssertionError("Browser OpenAL context was not created");
        }
        if (ALC10.alcIsExtensionPresent(device, "ALC_EXT_disconnect")) {
            throw new AssertionError("Browser OpenAL advertised unsupported device disconnect events");
        }
        if (ALC10.alcGetInteger(device, 0x0313) == 0) {
            throw new AssertionError("Browser OpenAL reported its active device as disconnected");
        }
        if (ALC10.alcGetInteger(device, ALC10.ALC_ATTRIBUTES_SIZE) < 3) {
            throw new AssertionError("Browser OpenAL did not expose a valid device attribute list");
        }

        int buffer = AL10.alGenBuffers();
        int source = AL10.alGenSources();
        ByteBuffer pcm = ByteBuffer.allocate(32);
        for (int sample = 0; sample < 16; sample++) {
            short value = (short) (sample % 2 == 0 ? 6000 : -6000);
            pcm.put((byte) value);
            pcm.put((byte) (value >>> 8));
        }
        pcm.flip();
        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, pcm, 44100);
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        AL10.alSourcePlay(source);
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (state != AL10.AL_PLAYING && state != AL10.AL_STOPPED) {
            throw new AssertionError("Browser OpenAL source state was not updated: " + state);
        }
        AL10.alSourceStop(source);
        AL10.alDeleteSources(source);
        AL10.alDeleteBuffers(buffer);
        testEatingSoundAsset();
    }

    private static void testEatingSoundAsset() throws Exception {
        String resource = "assets/minecraft/sounds/random/eat1.ogg";
        try (InputStream encoded = openPackagedAsset(resource)) {
            if (encoded == null) {
                throw new AssertionError("Eating sound asset was not packaged: " + resource);
            }
            try (JOrbisAudioStream decoder = new JOrbisAudioStream(encoded)) {
                AudioFormat audioFormat = decoder.getFormat();
                ByteBuffer pcm = decoder.readAll();
                int channels = audioFormat.getChannels();
                int alFormat = channels == 1
                        ? AL10.AL_FORMAT_MONO16
                        : channels == 2 ? AL10.AL_FORMAT_STEREO16 : 0;
                if (alFormat == 0 || audioFormat.getSampleSizeInBits() != 16
                        || audioFormat.getSampleRate() <= 0.0F || !pcm.hasRemaining()) {
                    throw new AssertionError("Eating sound did not decode to playable PCM");
                }

                int buffer = AL10.alGenBuffers();
                int source = AL10.alGenSources();
                AL10.alBufferData(buffer, alFormat, pcm, (int) audioFormat.getSampleRate());
                AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
                AL10.alSourcePlay(source);
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (state != AL10.AL_PLAYING && state != AL10.AL_STOPPED) {
                    throw new AssertionError("Eating sound source did not start: " + state);
                }
                AL10.alSourceStop(source);
                AL10.alDeleteSources(source);
                AL10.alDeleteBuffers(buffer);
            }
        }
    }

    private static void testUnicodeFontFallbackAssets() throws Exception {
        String definitionResource = "assets/minecraft/font/include/unifont.json";
        String zipResource = "assets/minecraft/font/unifont.zip";
        try (InputStream definition = openPackagedAsset(definitionResource)) {
            if (definition == null) {
                throw new AssertionError("Unicode font definition was not packaged: " + definitionResource);
            }
            String json = new String(definition.readAllBytes(), StandardCharsets.UTF_8);
            if (!json.contains("\"type\": \"unihex\"")
                    || !json.contains("minecraft:font/unifont.zip")) {
                throw new AssertionError("Unicode font definition does not reference the bundled unihex fallback");
            }
        }

        try (InputStream encoded = openPackagedAsset(zipResource)) {
            if (encoded == null) {
                throw new AssertionError("Unicode font archive was not packaged: " + zipResource);
            }
            boolean foundHexFile = false;
            try (ZipInputStream zip = new ZipInputStream(encoded)) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
                    if (!entry.isDirectory() && entry.getName().endsWith(".hex")) {
                        foundHexFile = true;
                        break;
                    }
                }
            }
            if (!foundHexFile) {
                throw new AssertionError("Unicode font archive has no unihex glyph data");
            }
        }
    }

    private static void testBitmapFontAssetDecode() throws Exception {
        String resource = "assets/minecraft/textures/font/ascii.png";
        try (InputStream encoded = openPackagedAsset(resource)) {
            if (encoded == null) {
                throw new AssertionError("Bitmap font texture was not packaged: " + resource);
            }
            try (NativeImage image = NativeImage.read(encoded)) {
                if (image.getWidth() != 128 || image.getHeight() != 128) {
                    throw new AssertionError(
                            "Bitmap font texture dimensions changed: "
                                    + image.getWidth() + "x" + image.getHeight());
                }
                ByteBuffer pixels = image.getPixelBytes();
                int nonZero = 0;
                for (int index = pixels.position(); index < pixels.limit(); index++) {
                    if (pixels.get(index) != 0) {
                        nonZero++;
                    }
                }
                if (nonZero < 512) {
                    throw new AssertionError(
                            "Bitmap font texture decoded as empty: nonZero=" + nonZero);
                }
            }
        }
    }

    private static InputStream openPackagedAsset(String resource) {
        int length = externalAssetLength(resource);
        if (length >= 0) {
            byte[] contents = new byte[length];
            if (copyExternalAsset(resource, contents)) {
                return new ByteArrayInputStream(contents);
            }
        }
        return PlatformSmoke.class.getClassLoader().getResourceAsStream(resource);
    }

    @JSBody(params = "resource", script = """
            const packs = [globalThis.__gaiusVanillaAssets, globalThis.__gaiusVanillaSounds];
            for (var i = 0; i < packs.length; i++) {
                var root = packs[i];
                if (!root || !root.bytes || !root.index ||
                    !Object.prototype.hasOwnProperty.call(root.index, resource)) continue;
                const range = root.index[resource];
                if (Array.isArray(range) && range.length === 2) return range[1] | 0;
            }
            return -1;
            """)
    private static native int externalAssetLength(String resource);

    @JSBody(params = {"resource", "output"}, script = """
            const packs = [globalThis.__gaiusVanillaAssets, globalThis.__gaiusVanillaSounds];
            const target = output && output.data ? output.data : output;
            if (!target) return false;
            for (var i = 0; i < packs.length; i++) {
                var root = packs[i];
                if (!root || !root.bytes || !root.index ||
                    !Object.prototype.hasOwnProperty.call(root.index, resource)) continue;
                const range = root.index[resource];
                if (!Array.isArray(range) || range.length !== 2) continue;
                const offset = range[0] | 0;
                const length = range[1] | 0;
                if (target.length !== length || offset < 0 || length < 0) return false;
                const start = root.dataOffset + offset;
                const end = start + length;
                if (start < root.dataOffset || end > root.bytes.length) return false;
                target.set(root.bytes.subarray(start, end));
                return true;
            }
            return false;
            """)
    private static native boolean copyExternalAsset(String resource, @JSByRef byte[] output);

    private static void testBrowserNetwork() {
        new BrowserWebSocketChannel();
        if (!runLocalNetworkFrameSmoke()) {
            throw new AssertionError("Browser local Netty bridge frame smoke did not start");
        }
        if (!runNettyNetworkFrameSmoke()) {
            throw new AssertionError("Browser Netty MessagePort smoke did not start");
        }
        scheduleNetworkRoundTripCheck();
    }

    private static void testBrowserCrypto() throws Exception {
        byte[] key = decodeHex("2b7e151628aed2a6abf7158809cf4f3c");
        byte[] iv = decodeHex("000102030405060708090a0b0c0d0e0f");
        byte[] plain = decodeHex("6bc1bee22e409f96e93d7e117393172a");
        byte[] expected = decodeHex("3b79424c9c0dd436bace9e0ed4586a4f");
        byte[] direct = new BrowserAesCfb8(Cipher.ENCRYPT_MODE, key, iv).update(plain);
        if (!Arrays.equals(expected, direct)) {
            throw new AssertionError("Browser AES/CFB8 does not match the NIST vector");
        }

        SecretKeySpec minecraftKey = new SecretKeySpec(key, "AES");
        Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, minecraftKey, new IvParameterSpec(key));
        byte[] encrypted = encrypt.doFinal(plain);
        Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, minecraftKey, new IvParameterSpec(key));
        if (!Arrays.equals(plain, decrypt.doFinal(encrypted))) {
            throw new AssertionError("Browser packet cipher did not round-trip");
        }

        byte[] sha1 = BrowserCrypto.sha1("abc".getBytes(StandardCharsets.US_ASCII));
        if (!Arrays.equals(sha1, decodeHex("a9993e364706816aba3e25717850c26c9cd0d89d"))) {
            throw new AssertionError("Browser SHA-1 digest mismatch");
        }
        byte[] sha256 = BrowserCrypto.sha256("abc".getBytes(StandardCharsets.US_ASCII));
        if (!Arrays.equals(sha256, decodeHex(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))) {
            throw new AssertionError("Browser SHA-256 digest mismatch");
        }
        SecretKey generated = BrowserCrypto.generateSecretKey();
        if (generated.getEncoded().length != 16 || allZero(generated.getEncoded())) {
            throw new AssertionError("Browser AES session key is invalid");
        }

        PublicKey publicKey = BrowserCrypto.parseRsaPublicKey(decodeHex(
                "30819f300d06092a864886f70d010101050003818d0030818902818100"
                        + "c29271ff44875afb2d12b068f397a7cb4f672418d372cb34d23bfbc9fe857e1e"
                        + "8dbec6597bd40df1f8f597c206d384e3063c342ae0bd413ed41b63958ea0ac79"
                        + "e93613fb677ba946ad98f50ddf9da21ca6b740719b9153c6a09ae24dd8ee27da"
                        + "044b7bd360168c415340fe28e33d486a183336a19dc982ec696577280c13bc55"
                        + "0203010001"));
        byte[] rsa = BrowserCrypto.encryptUsingKey(publicKey, generated.getEncoded());
        if (rsa.length != 128 || Arrays.equals(rsa, generated.getEncoded())) {
            throw new AssertionError("Browser RSA/PKCS1 encryption is invalid");
        }

        PrivateKey privateKey = BrowserCrypto.parseRsaPrivateKey(Base64.getDecoder().decode(
                "MIICdAIBADANBgkqhkiG9w0BAQEFAASCAl4wggJaAgEAAoGBALExD7W21qDf07F+JIwz"
                        + "ea7Pb0Br2JWVYXczNzajNyTjbrgT07FrYp7K0EwViey5Y1hZa4eQCaGwKAvnPNeTxSg"
                        + "2bkVvVZvYpAA+A6XQVLD+iOBRdBs9SN4pQ3r+r5ihQ8aGgbIqI6Lx/APjCFSgZDwNz7"
                        + "UyJtDfSJ208YcGfDWrAgMBAAECf0umTAwe1wLW08MpkLKkMSlcJmESfuZFQH7gJ7poa"
                        + "CVTuWJ8qOTY5aSCKvS3PrqrOzJ2lySoB5Qvx1TaIzDQh464iR/bH9X4PpEgIRtXy3KJ"
                        + "ncH2MkUC4GD5K4zcmJGaoYDgBHepq7X2ZYUr0Dz52NmtJIY8nhaxQZvl4SxZW4ECQQD"
                        + "oCrNmFJOYvaQHQjR+Eu1P2fJstYJzQQ+sBsQoZ5EM2BZ3EtCeul4hTXR9iYqQwAWssZ"
                        + "nHfrjynxzxbdXfHubrAkEAw3yRfcVaAeaqM+AwN9idXkkmQT6HkivmesMoPyWtdTc7/F"
                        + "ojyr+MhIrGy/z+6Yuu/191sdo5RmWCScmEFG28QQJAR8k/tP36p49L1p8JxFMwrbp8g"
                        + "FsrD2L6aTrypplby4ByesYMWn7Hrj/bIRdTEfMGDmYncAtpRk4pUxuqxcs2nwJBAJ/+"
                        + "Zf1v9liz9v16MTyj1ziB2gNwL/kcYQh8jYYRSkQzLq88/ypDV7hq9IWjzOZMYq+z61"
                        + "ni5xmMnvPRMH6fyYECQCHelQhxFP3651cJQo63j5iF8K8+KNNmiow9igg4ki/rFzqKo"
                        + "cDgq3I7IP1IoIYPSakPsKtJSx+HbQSXN34pPrc="));
        byte[] signature = BrowserCrypto.signUsingKey(
                privateKey,
                "minecraft-secure-profile-smoke".getBytes(StandardCharsets.UTF_8));
        if (!Arrays.equals(signature, decodeHex(
                "1b0dbb92e6a5c2e165537d73f035f1ee2f30809f582b0c22d12459beef84a732"
                        + "21aca5dd7747b5e1c8a3f3a985b299c55708a95cfdae395c4f2fef8e1ce9984f"
                        + "6d180ea06c5713a379b7967d5dbfa49bb34579db7dae4094c5f95fecfe765a944"
                        + "15a3878cb8f549b850b94a39364fd289768fce224a76caaf5b036918a2168da"))) {
            throw new AssertionError("Browser RSA/SHA256 profile signature mismatch");
        }
    }

    private static void testBrowserHttpProxy() throws Exception {
        URL resourcePack = BrowserHttpProxy.proxyResourcePack(
                new URL("https://packs.example.invalid/server-pack.zip"));
        String resourcePackUrl = resourcePack.toExternalForm();
        if (!resourcePackUrl.contains("/proxy/resource-pack?")
                || !resourcePackUrl.contains("packs.example.invalid")) {
            throw new AssertionError("Browser resource-pack proxy URL is invalid");
        }
        URL pinnedResourcePack = BrowserHttpProxy.proxyResourcePack(new URL(
                "https://jihulab.com/-/project/356228/uploads/"
                        + "e409655d230380173547e68c5ef026d4/resource_pack.zip"));
        if (!pinnedResourcePack.toExternalForm().contains("/proxy/resource-pack?")
                || !pinnedResourcePack.toExternalForm().contains("jihulab.com")) {
            throw new AssertionError("Server resource-pack relay route is invalid");
        }
        URL authentication = BrowserHttpProxy.proxyAuthentication(
                new URL("https://sessionserver.mojang.com/session/minecraft/join"));
        String authenticationUrl = authentication.toExternalForm();
        if (!authenticationUrl.contains("/proxy/auth?")
                || !authenticationUrl.contains("sessionserver.mojang.com")) {
            throw new AssertionError("Browser authentication proxy URL is invalid");
        }
        String texture = BrowserHttpProxy.proxyTexture(
                "https://textures.minecraft.net/texture/0123456789abcdef");
        if (!texture.contains("/proxy/texture?")
                || !texture.contains("textures.minecraft.net")) {
            throw new AssertionError("Browser player-texture proxy URL is invalid");
        }
        String localSkin = "data:image/png;base64,iVBORw0KGgo=";
        if (!localSkin.equals(BrowserHttpProxy.proxyTexture(localSkin))) {
            throw new AssertionError("Browser uploaded skin data URL was rewritten");
        }
        String realms = BrowserHttpProxy.proxyRealms(
                "https://pc.realms.minecraft.net/worlds");
        if (!realms.contains("/proxy/realms?")
                || !realms.contains("pc.realms.minecraft.net")) {
            throw new AssertionError("Browser Realms proxy URL is invalid");
        }
        // Any version header value passes through; it is not a profile id.
        String versionHeader = "platform-smoke-version";
        String smokeToken = "platform-smoke-bridge-token";
        String previousToken = BrowserHttpProxy.bridgeToken();
        BrowserHttpProxy.setBridgeTokenForSmoke(smokeToken);
        Map<String, String> emptyHeaders = BrowserHttpProxy.browserSafeHeaders(Map.of());
        if (!smokeToken.equals(emptyHeaders.get("X-Gaius-Bridge-Token"))) {
            throw new AssertionError("Bridge token was omitted when proxied HTTP headers were empty");
        }
        BrowserHttpProxy.setBridgeTokenForSmoke(previousToken);
        Map<String, String> headers = BrowserHttpProxy.browserSafeHeaders(Map.of(
                "User-Agent", "Minecraft Java/1.21.11",
                "Host", "packs.example.invalid",
                "X-Minecraft-Version", versionHeader));
        if (headers.containsKey("User-Agent") || headers.containsKey("Host")
                || !versionHeader.equals(headers.get("X-Minecraft-Version"))) {
            throw new AssertionError("Browser HTTP forbidden-header filtering is invalid");
        }
        Map<String, String> mirrorHeaders = BrowserHttpProxy.browserSafeResourcePackHeaders(
                pinnedResourcePack,
                Map.of(
                        "Accept", "application/octet-stream",
                        "X-Minecraft-Version", versionHeader,
                        "X-Minecraft-UUID", "00000000-0000-0000-0000-000000000000"));
        if (!"application/octet-stream".equals(mirrorHeaders.get("Accept"))
                || !versionHeader.equals(mirrorHeaders.get("X-Minecraft-Version"))
                || !mirrorHeaders.containsKey("X-Minecraft-UUID")) {
            throw new AssertionError("Resource-pack relay metadata headers are missing");
        }
    }

    private static byte[] decodeHex(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex value has an odd length");
        }
        byte[] decoded = new byte[value.length() / 2];
        for (int index = 0; index < decoded.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Hex value is invalid");
            }
            decoded[index] = (byte) ((high << 4) | low);
        }
        return decoded;
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

    @JSBody(script = """
            window.__gaiusGlfwEvents.push([1,87,87,1,0,0,0]);
            window.__gaiusGlfwEvents.push([4,0,0,0,0,32,48]);
            window.__gaiusGlfwEvents.push([3,0,1,0,0,0,0]);
            window.__gaiusGlfwEvents.push([5,0,0,0,0,0,1]);
            """)
    private static native void enqueueSyntheticInput();

    @JSBody(script = """
            const sessionId = '0123456789abcdef0123456789abcdef';
            const channel = new MessageChannel();
            const ports = window.__gaiusLocalServerPorts ||
              (window.__gaiusLocalServerPorts = new Map());
            ports.set(sessionId, channel.port1);
            const smoke = window.__gaiusLocalNetworkSmoke = {frames: 0, bytes: 0};
            channel.port2.onmessage = function(event) {
              const message = event.data;
              if (!(message instanceof ArrayBuffer) && !ArrayBuffer.isView(message)) return;
              const bytes = message instanceof ArrayBuffer
                ? new Uint8Array(message)
                : new Uint8Array(message.buffer, message.byteOffset || 0, message.byteLength || 0);
              smoke.frames++;
              smoke.bytes += bytes.byteLength;
              const copy = new Uint8Array(bytes.byteLength);
              copy.set(bytes);
              channel.port2.postMessage(copy.buffer, [copy.buffer]);
            };
            if (typeof channel.port2.start === 'function') channel.port2.start();
            const bridge = window.__gaiusNettyBridge;
            if (!bridge) return false;
            const socketId = 0x6A1A5;
            bridge.open(socketId, 'client-' + sessionId + '.gaius-local', 25565);
            return bridge.send(socketId, new Uint8Array([0x01])) &&
              bridge.send(socketId, new Uint8Array([0x02, 0x03])) &&
              bridge.send(socketId, new Uint8Array([0x04, 0x05, 0x06]));
            """)
    private static native boolean runLocalNetworkFrameSmoke();

    @JSBody(script = """
            const sessionId = 'fedcba9876543210fedcba9876543210';
            const channel = new MessageChannel();
            const ports = window.__gaiusLocalServerPorts ||
              (window.__gaiusLocalServerPorts = new Map());
            ports.set(sessionId, channel.port1);
            const smoke = window.__gaiusNettyNetworkSmoke = {frames: 0, bytes: 0};
            channel.port2.onmessage = function(event) {
              const message = event.data;
              if (!(message instanceof ArrayBuffer) && !ArrayBuffer.isView(message)) return;
              const bytes = message instanceof ArrayBuffer
                ? new Uint8Array(message)
                : new Uint8Array(message.buffer, message.byteOffset || 0, message.byteLength || 0);
              smoke.frames++;
              smoke.bytes += bytes.byteLength;
            };
            if (typeof channel.port2.start === 'function') channel.port2.start();
            const bridge = window.__gaiusNettyBridge;
            if (!bridge) return false;
            const socketId = 0x6A1A6;
            bridge.open(socketId, 'client-' + sessionId + '.gaius-local', 25565);
            return bridge.send(socketId, new Uint8Array([
              0x10, 0x00, 0x86, 0x06, 0x09,
              0x31, 0x32, 0x37, 0x2e, 0x30, 0x2e, 0x30, 0x2e, 0x31,
              0x63, 0xdd, 0x01, 0x01, 0x00
            ]));
            """)
    private static native boolean runNettyNetworkFrameSmoke();

    @JSBody(script = """
            globalThis.__gaiusPlatformNetworkPending = true;
            setTimeout(function() {
              const stats = window.__gaiusNetworkStats;
              const local = window.__gaiusLocalNetworkSmoke;
              const netty = window.__gaiusNettyNetworkSmoke;
              const output = document.getElementById('status');
              const passed = !!local && (local.frames|0) === 1 && (local.bytes|0) === 6 &&
                  !!netty && (netty.frames|0) === 1 && (netty.bytes|0) === 19 &&
                  !!stats && (stats.localFlushes|0) >= 2 &&
                  (stats.localFlushFrames|0) === 4 &&
                  (stats.localFlushBytes|0) === 25 &&
                  (stats.peakLocalFlushFrames|0) >= 3 &&
                  (stats.localReceivedFrames|0) === 1 &&
                  (stats.localReceivedBytes|0) === 6;
              globalThis.__gaiusPlatformNetworkPending = false;
              if (!passed) {
                if (output) {
                  output.textContent = 'Browser Netty local batching failed';
                  output.dataset.success = 'false';
                }
                console.error(
                  'Browser Netty local batching failed',
                  local || null,
                  netty || null,
                  stats || null
                );
                return;
              }
              const message = globalThis.__gaiusPlatformDeferredSuccessMessage ||
                'Gaius platform smoke passed';
              if (output) {
                output.textContent = message;
                output.dataset.success = 'true';
              }
              console.info(message);
            }, 2000);
            """)
    private static native void scheduleNetworkRoundTripCheck();

    @JSBody(params = {"success", "message"}, script = """
            const output=document.getElementById('status');
            if (success && globalThis.__gaiusPlatformNetworkPending) {
              globalThis.__gaiusPlatformDeferredSuccessMessage = message;
              if (output) {
                output.textContent = 'Waiting for browser network verification...';
                delete output.dataset.success;
              }
              return;
            }
            if (output) {
              output.textContent=message;
              output.dataset.success=success?'true':'false';
            }
            console[success?'info':'error'](message);
            """)
    private static native void report(boolean success, String message);
}
