package dev.gaius.tools.atlasprebake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import dev.gaius.browser.BrowserAtlasPrebake;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.StitcherException;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

/**
 * The atlas prebake builder and its JVM gate driver (port/tools/atlasprebake/atlasprebake.sh).
 *
 * <pre>
 * build --pack P --profile ID --scratch DIR --out FILE.bin.gz [--summary JSON] [--max-mip N]
 *       [--mips 0,1,2,3,4] [--anisotropy 0] [--tamper mip|layout (gate controls only)]
 * dump  --pack P --profile ID --scratch DIR --out DUMP [--payload FILE.bin.gz --mode on|verify|off]
 *       [--counters JSON] [--mip N] [--anisotropy B] [--max-size S] [--mutate-pixel SPRITE]
 *       [--drop-sprite SPRITE] [--corrupt-payload]
 * </pre>
 *
 * {@code build} runs the vanilla classes only: every sprite's vanilla mip chain (level 0 after the
 * in-place changes, levels 1..max, and the levels for which a shorter vanilla chain is the same
 * prefix), and per atlas and option set the vanilla Stitcher layout for every power-of-two maximum
 * size, kept for the range of maxima that gives the layout of the largest one. {@code dump} loads
 * and stitches every atlas through vanilla {@code SpriteLoader.stitch} (mip chains included) with
 * whatever MipmapGenerator and Stitcher the classpath has, and writes sizes, the region map in
 * iteration order, placements, UV bits, padding and the SHA-256 of every mip level of every sprite
 * and of every composed atlas level.
 */
public final class AtlasPrebakeTool {
    private AtlasPrebakeTool() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("usage: build|dump ...");
        }
        Map<String, String> options = new HashMap<>();
        for (int index = 1; index < arguments.length; index++) {
            String name = arguments[index];
            if (!name.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + name);
            }
            if (name.equals("--corrupt-payload")) {
                options.put(name, "1");
            } else {
                options.put(name, arguments[++index]);
            }
        }
        switch (arguments[0]) {
            case "build" -> build(options);
            case "dump" -> dump(options);
            default -> throw new IllegalArgumentException("unknown command " + arguments[0]);
        }
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing " + name);
        }
        return value;
    }

    private static int[] ints(String text) {
        return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).mapToInt(Integer::parseInt)
                .toArray();
    }

    // --- build ----------------------------------------------------------------------------------

    private record MipEntry(String key, int width, int height, long hashLo, long hashHi, int maxLevel, int mask,
            boolean level0Changed, byte[][] levels) {
    }

    private record ListKey(String[] names, int[] sizes, long hashLo, long hashHi) {
    }

    private record StitchRecord(int key, int mip, int padding, int minLog2, int maxLog2, int[] layout) {
    }

    private static void build(Map<String, String> options) throws Exception {
        Path pack = Path.of(required(options, "--pack"));
        String profile = required(options, "--profile");
        Path scratch = Path.of(required(options, "--scratch"));
        Path out = Path.of(required(options, "--out"));
        int maxMip = Integer.parseInt(options.getOrDefault("--max-mip", "4"));
        int[] mipOptions = ints(options.getOrDefault("--mips", "0,1,2,3,4"));
        int[] anisotropy = ints(options.getOrDefault("--anisotropy", "0"));
        long started = System.nanoTime();
        String packSha = VanillaAtlases.extract(pack, scratch.resolve("pack"));
        VanillaAtlases.bootstrap();
        MultiPackResourceManager resources = VanillaAtlases.resources(scratch.resolve("pack"));

        List<MipEntry> mips = new ArrayList<>();
        Map<String, MipEntry> mipByKey = new HashMap<>();
        List<ListKey> keys = new ArrayList<>();
        List<StitchRecord> records = new ArrayList<>();
        Map<String, Object> atlasSummary = new LinkedHashMap<>();
        int skippedMips = 0;
        for (VanillaAtlases.Atlas atlas : VanillaAtlases.atlases()) {
            List<SpriteContents> sprites = VanillaAtlases.sprites(resources, atlas);
            String[] names = new String[sprites.size()];
            int[] sizes = new int[sprites.size() * 2];
            for (int index = 0; index < names.length; index++) {
                names[index] = sprites.get(index).name().toString();
                sizes[index * 2] = sprites.get(index).width();
                sizes[index * 2 + 1] = sprites.get(index).height();
            }
            long[] listHash = BrowserAtlasPrebake.listHash(names, sizes, names.length);
            int key = keys.size();
            keys.add(new ListKey(names, sizes, listHash[0], listHash[1]));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("sprites", names.length);
            summary.put("mipmaps", atlas.mipmaps());
            List<Object> layouts = new ArrayList<>();
            for (int mipOption : atlas.mipmaps() ? mipOptions : new int[] {0}) {
                for (int bit : anisotropy) {
                    TextureFilteringMethod filtering = bit == 0 ? TextureFilteringMethod.NONE
                            : TextureFilteringMethod.ANISOTROPIC;
                    VanillaAtlases.options(filtering, bit);
                    // Vanilla's mip level for this atlas (it lowers it for small sprites); the mip
                    // chains are not run here (the executor drops them).
                    SpriteLoader.Preparations preparations = VanillaAtlases.stitch(atlas, Integer.MAX_VALUE, sprites,
                            mipOption, task -> { });
                    int mip = preparations.mipLevel();
                    // SpriteLoader.stitch passes the bit only with anisotropic filtering.
                    int anisotropyArgument = filtering == TextureFilteringMethod.ANISOTROPIC ? bit : 0;
                    int[] largest = null;
                    int minLog2 = 30;
                    int padding = -1;
                    for (int log2 = 30; log2 >= 0; log2--) {
                        Stitcher<SpriteContents> stitcher = new Stitcher<>(1 << log2, 1 << log2, mip,
                                anisotropyArgument);
                        padding = (int) VanillaAtlases.field(Stitcher.class, stitcher, "padding");
                        int[] layout = layout(stitcher, sprites, padding);
                        if (largest == null) {
                            largest = layout;
                        }
                        if (layout == null || !Arrays.equals(layout, largest)) {
                            break;
                        }
                        minLog2 = log2;
                    }
                    if (largest == null) {
                        continue;
                    }
                    int finalPadding = padding;
                    boolean duplicate = records.stream().anyMatch(record -> record.key() == key
                            && record.mip() == mip && record.padding() == finalPadding);
                    if (!duplicate) {
                        records.add(new StitchRecord(key, mip, finalPadding, minLog2, 30, largest));
                        layouts.add(Map.of("mip", mip, "padding", finalPadding, "size", largest[0] + "x" + largest[1],
                                "maxSizeLog2", minLog2 + ".." + 30));
                    }
                }
            }
            summary.put("layouts", layouts);
            if (atlas.mipmaps()) {
                int entries = 0;
                for (SpriteContents sprite : sprites) {
                    MipEntry entry = mipEntry(sprite, maxMip);
                    if (entry == null) {
                        skippedMips++;
                        continue;
                    }
                    MipEntry previous = mipByKey.get(entry.key());
                    if (previous != null) {
                        if (previous.hashLo() != entry.hashLo() || previous.hashHi() != entry.hashHi()) {
                            throw new IllegalStateException("two inputs for mip key " + entry.key());
                        }
                        continue;
                    }
                    mipByKey.put(entry.key(), entry);
                    mips.add(entry);
                    entries++;
                }
                summary.put("mipEntries", entries);
            }
            atlasSummary.put(atlas.textureId().toString(), summary);
            for (SpriteContents sprite : sprites) {
                sprite.close();
            }
        }

        // Gate controls only: a well-formed payload with one wrong mip byte or one moved sprite,
        // which the dumps and verify mode must catch.
        String tamper = options.get("--tamper");
        if ("mip".equals(tamper)) {
            mips.get(0).levels()[1][0] ^= 0x01;
        } else if ("layout".equals(tamper)) {
            records.get(0).layout()[4] += 1;
        } else if (tamper != null) {
            throw new IllegalArgumentException("--tamper " + tamper);
        }

        // index
        ByteArrayOutputStream index = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        long mipBytes = 0;
        long level0Bytes = 0;
        writeInt(index, mips.size());
        for (MipEntry entry : mips) {
            writeString(index, entry.key());
            writeInt(index, entry.width());
            writeInt(index, entry.height());
            writeLong(index, entry.hashLo());
            writeLong(index, entry.hashHi());
            index.write(entry.maxLevel());
            index.write(entry.mask());
            index.write(entry.level0Changed() ? 1 : 0);
            index.write(0);
            writeInt(index, data.size());
            for (int level = entry.level0Changed() ? 0 : 1; level <= entry.maxLevel(); level++) {
                data.write(entry.levels()[level]);
                if (level == 0) {
                    level0Bytes += entry.levels()[level].length;
                } else {
                    mipBytes += entry.levels()[level].length;
                }
            }
        }
        writeInt(index, keys.size());
        for (ListKey key : keys) {
            writeInt(index, key.names().length);
            writeLong(index, key.hashLo());
            writeLong(index, key.hashHi());
            for (int sprite = 0; sprite < key.names().length; sprite++) {
                writeString(index, key.names()[sprite]);
                writeInt(index, key.sizes()[sprite * 2]);
                writeInt(index, key.sizes()[sprite * 2 + 1]);
            }
        }
        writeInt(index, records.size());
        for (StitchRecord record : records) {
            writeInt(index, record.key());
            writeInt(index, record.mip());
            writeInt(index, record.padding());
            index.write(record.minLog2());
            index.write(record.maxLog2());
            index.write(0);
            index.write(0);
            int[] layout = record.layout();
            writeInt(index, layout[0]);
            writeInt(index, layout[1]);
            writeInt(index, layout[2]);
            for (int at = 3; at < layout.length; at++) {
                writeInt(index, layout[at]);
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeInt(body, index.size());
        index.writeTo(body);
        data.writeTo(body);
        byte[] bodyBytes = body.toByteArray();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool", "port/tools/atlasprebake");
        meta.put("profile", profile);
        meta.put("packSha256", packSha);
        meta.put("clientJarSha256", VanillaAtlases.sha256(Files.readAllBytes(Path.of(
                MipmapGenerator.class.getProtectionDomain().getCodeSource().getLocation().toURI()))));
        meta.put("mipOptions", Arrays.toString(mipOptions));
        meta.put("anisotropy", Arrays.toString(anisotropy));
        meta.put("maxMip", maxMip);
        String metaText = new Gson().toJson(meta);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeLong(header, 0x3150415355494147L);
        writeInt(header, 1);
        byte[] profileBytes = profile.getBytes(StandardCharsets.US_ASCII);
        byte[] metaBytes = metaText.getBytes(StandardCharsets.US_ASCII);
        int headerLength = 52 + 2 + profileBytes.length + 2 + metaBytes.length;
        writeInt(header, headerLength);
        writeInt(header, bodyBytes.length);
        header.write(MessageDigest.getInstance("SHA-256").digest(bodyBytes));
        writeString(header, profile);
        writeString(header, metaText);
        if (header.size() != headerLength) {
            throw new IllegalStateException("header length");
        }
        header.write(bodyBytes);
        byte[] payload = header.toByteArray();
        byte[] compressed = gzip(payload);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, compressed);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("profile", profile);
        summary.put("output", out.toString());
        summary.put("payloadBytes", payload.length);
        summary.put("gzipBytes", compressed.length);
        summary.put("gzipSha256", VanillaAtlases.sha256(compressed));
        summary.put("indexBytes", index.size());
        summary.put("mipLevelBytes", mipBytes);
        summary.put("level0Bytes", level0Bytes);
        summary.put("mipEntries", mips.size());
        summary.put("mipEntriesLevel0Changed", mips.stream().filter(MipEntry::level0Changed).count());
        summary.put("mipEntriesSkipped", skippedMips);
        summary.put("stitchKeys", keys.size());
        summary.put("stitchRecords", records.size());
        summary.put("atlases", atlasSummary);
        summary.put("meta", meta);
        summary.put("seconds", (System.nanoTime() - started) / 1e9);
        String text = new GsonBuilder().setPrettyPrinting().create().toJson(summary) + "\n";
        if (options.containsKey("--summary")) {
            Files.writeString(Path.of(options.get("--summary")), text);
        }
        System.out.print(text);
    }

    /** The vanilla walk of a stitched layout: {@code [storageX, storageY, n, (index, x, y) * n]}, or null. */
    private static int[] layout(Stitcher<SpriteContents> stitcher, List<SpriteContents> sprites, int padding) {
        for (SpriteContents sprite : sprites) {
            stitcher.registerSprite(sprite);
        }
        try {
            stitcher.stitch();
        } catch (StitcherException tooLarge) {
            return null;
        }
        IdentityHashMap<SpriteContents, Integer> indices = new IdentityHashMap<>();
        for (int index = 0; index < sprites.size(); index++) {
            if (indices.put(sprites.get(index), index) != null) {
                throw new IllegalStateException("a sprite is registered twice");
            }
        }
        int[] layout = new int[3 + sprites.size() * 3];
        int[] count = {0};
        stitcher.gatherSprites((sprite, x, y, spritePadding) -> {
            if (spritePadding != padding || count[0] >= sprites.size()) {
                throw new IllegalStateException("unexpected walk");
            }
            int at = 3 + count[0] * 3;
            layout[at] = indices.get(sprite);
            layout[at + 1] = x;
            layout[at + 2] = y;
            count[0]++;
        });
        if (count[0] != sprites.size()) {
            throw new IllegalStateException("walk placed " + count[0] + " of " + sprites.size());
        }
        layout[0] = stitcher.getWidth();
        layout[1] = stitcher.getHeight();
        layout[2] = count[0];
        return layout;
    }

    private static MipEntry mipEntry(SpriteContents sprite, int maxMip) throws ReflectiveOperationException {
        NativeImage original = (NativeImage) VanillaAtlases.field(SpriteContents.class, sprite, "originalImage");
        NativeImage[] byMipLevel = (NativeImage[]) VanillaAtlases.field(SpriteContents.class, sprite, "byMipLevel");
        if (byMipLevel.length != 1 || byMipLevel[0] != original || original.format() != NativeImage.Format.RGBA) {
            return null;
        }
        MipmapStrategy strategy = (MipmapStrategy) VanillaAtlases.field(SpriteContents.class, sprite, "mipmapStrategy");
        float bias = (float) VanillaAtlases.field(SpriteContents.class, sprite, "alphaCutoffBias");
        Transparency transparency = (Transparency) VanillaAtlases.field(SpriteContents.class, sprite, "transparency");
        Identifier name = sprite.name();
        int width = original.getWidth();
        int height = original.getHeight();
        int maxLevel = maxMip;
        while (maxLevel > 0 && ((width >> maxLevel) == 0 || (height >> maxLevel) == 0)) {
            maxLevel--;
        }
        if (maxLevel < 1) {
            return null;
        }
        byte[] input = VanillaAtlases.bytes(original);
        long[] hash = BrowserAtlasPrebake.hash128(original.getPointer(), input.length);
        byte[][] levels = generate(name, original, maxLevel, strategy, bias, transparency);
        int mask = 0;
        for (int level = 1; level <= maxLevel; level++) {
            byte[][] shorter = level == maxLevel ? levels : generate(name, original, level, strategy, bias, transparency);
            boolean prefix = shorter.length == level + 1;
            for (int at = 0; prefix && at <= level; at++) {
                prefix = Arrays.equals(shorter[at], levels[at]);
            }
            if (prefix) {
                mask |= 1 << level;
            }
        }
        if (!Arrays.equals(VanillaAtlases.bytes(original), input)) {
            throw new IllegalStateException("the builder changed a sprite image");
        }
        boolean level0Changed = !Arrays.equals(levels[0], input);
        String key = BrowserAtlasPrebake.mipKey(name.toString(), 1, width, height, original.format().ordinal(),
                strategy.ordinal(), Float.floatToRawIntBits(bias), transparency.hasTransparent(),
                transparency.hasTranslucent());
        return new MipEntry(key, width, height, hash[0], hash[1], maxLevel, mask, level0Changed, levels);
    }

    /** Vanilla generateMipLevels on a copy of the image; the bytes of each returned level. */
    private static byte[][] generate(Identifier name, NativeImage original, int level, MipmapStrategy strategy,
            float bias, Transparency transparency) {
        NativeImage copy = VanillaAtlases.copy(original);
        NativeImage[] result = MipmapGenerator.generateMipLevels(name, new NativeImage[] {copy}, level, strategy, bias,
                transparency);
        byte[][] levels = new byte[result.length][];
        for (int index = 0; index < result.length; index++) {
            if (result[index].getWidth() != original.getWidth() >> index
                    || result[index].getHeight() != original.getHeight() >> index
                    || result[index].format() != NativeImage.Format.RGBA) {
                throw new IllegalStateException("unexpected mip level shape for " + name);
            }
            levels[index] = VanillaAtlases.bytes(result[index]);
            result[index].close();
        }
        return levels;
    }

    // --- dump (JVM gates) -----------------------------------------------------------------------

    private static void dump(Map<String, String> options) throws Exception {
        Path pack = Path.of(required(options, "--pack"));
        String profile = required(options, "--profile");
        Path scratch = Path.of(required(options, "--scratch"));
        Path out = Path.of(required(options, "--out"));
        int mipOption = Integer.parseInt(options.getOrDefault("--mip", "2"));
        int bit = Integer.parseInt(options.getOrDefault("--anisotropy", "0"));
        int maxSize = Integer.parseInt(options.getOrDefault("--max-size", "8192"));
        String mutate = options.get("--mutate-pixel");
        String drop = options.get("--drop-sprite");
        JvmPayloadSource source = null;
        if (options.containsKey("--payload")) {
            byte[] payload = gunzip(Files.readAllBytes(Path.of(options.get("--payload"))));
            if (options.containsKey("--corrupt-payload")) {
                payload[payload.length - 1] ^= 0x5a;
            }
            String mode = options.getOrDefault("--mode", "on");
            int value = switch (mode) {
                case "off" -> BrowserAtlasPrebake.MODE_OFF;
                case "verify" -> BrowserAtlasPrebake.MODE_VERIFY;
                case "on" -> BrowserAtlasPrebake.MODE_ON;
                default -> throw new IllegalArgumentException("--mode " + mode);
            };
            source = new JvmPayloadSource(payload, value, profile);
            BrowserAtlasPrebake.install(source);
        }
        VanillaAtlases.extract(pack, scratch.resolve("pack"));
        VanillaAtlases.bootstrap();
        MultiPackResourceManager resources = VanillaAtlases.resources(scratch.resolve("pack"));
        VanillaAtlases.options(bit == 0 ? TextureFilteringMethod.NONE : TextureFilteringMethod.ANISOTROPIC, bit);
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        int mutated = 0;
        int dropped = 0;
        long stitchNanos = 0;
        for (VanillaAtlases.Atlas atlas : VanillaAtlases.atlases()) {
            List<SpriteContents> sprites = new ArrayList<>(VanillaAtlases.sprites(resources, atlas));
            if (drop != null && sprites.removeIf(sprite -> sprite.name().toString().equals(drop))) {
                dropped++;
            }
            if (mutate != null) {
                for (SpriteContents sprite : sprites) {
                    if (sprite.name().toString().equals(mutate)) {
                        NativeImage image = (NativeImage) VanillaAtlases.field(SpriteContents.class, sprite,
                                "originalImage");
                        image.setPixel(0, 0, image.getPixel(0, 0) ^ 0x00010101);
                        mutated++;
                    }
                }
            }
            writer.printf("atlas %s definition=%s mipmaps=%s sprites=%d%n", atlas.textureId(), atlas.definition(),
                    atlas.mipmaps(), sprites.size());
            SpriteLoader.Preparations preparations;
            long started = System.nanoTime();
            try {
                preparations = VanillaAtlases.stitch(atlas, maxSize, sprites, mipOption, VanillaAtlases.DIRECT);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                writer.printf("  stitch-failed %s%n", failure.getCause().getClass().getName());
                continue;
            }
            preparations.readyForUpload().join();
            stitchNanos += System.nanoTime() - started;
            int mip = preparations.mipLevel();
            writer.printf("  size %dx%d mip=%d missing=%s%n", preparations.width(), preparations.height(), mip,
                    preparations.missing().contents().name());
            byte[][] composed = new byte[mip + 1][];
            for (int level = 0; level <= mip; level++) {
                composed[level] = new byte[(preparations.width() >> level) * (preparations.height() >> level) * 4];
            }
            for (Map.Entry<Identifier, TextureAtlasSprite> region : preparations.regions().entrySet()) {
                TextureAtlasSprite sprite = region.getValue();
                SpriteContents contents = sprite.contents();
                writer.printf("  region %s x=%d y=%d w=%d h=%d u=%08x,%08x v=%08x,%08x padding=%s animated=%s%n",
                        region.getKey(), sprite.getX(), sprite.getY(), contents.width(), contents.height(),
                        Float.floatToRawIntBits(sprite.getU0()), Float.floatToRawIntBits(sprite.getU1()),
                        Float.floatToRawIntBits(sprite.getV0()), Float.floatToRawIntBits(sprite.getV1()),
                        VanillaAtlases.field(TextureAtlasSprite.class, sprite, "padding"), sprite.isAnimated());
                NativeImage[] levels = (NativeImage[]) VanillaAtlases.field(SpriteContents.class, contents,
                        "byMipLevel");
                writer.printf("    levels=%d%n", levels.length);
                for (int level = 0; level < levels.length; level++) {
                    byte[] bytes = VanillaAtlases.bytes(levels[level]);
                    writer.printf("    level %d %dx%d %s%n", level, levels[level].getWidth(), levels[level].getHeight(),
                            VanillaAtlases.sha256(bytes));
                    if (level <= mip && !sprite.isAnimated()) {
                        blit(composed[level], preparations.width() >> level, bytes, levels[level].getWidth(),
                                levels[level].getHeight(), sprite.getX() >> level, sprite.getY() >> level);
                    }
                }
            }
            for (int level = 0; level <= mip; level++) {
                writer.printf("  atlas-level %d %s%n", level, VanillaAtlases.sha256(composed[level]));
            }
            for (TextureAtlasSprite sprite : preparations.regions().values()) {
                sprite.contents().close();
            }
        }
        writer.flush();
        Files.writeString(out, text.toString());
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("mutated", mutated);
        counters.put("dropped", dropped);
        counters.put("stitchAndMipMs", stitchNanos / 1e6);
        if (source != null) {
            int[] values = BrowserAtlasPrebake.counters();
            String[] names = {"mipHits", "mipMissKey", "mipMissHash", "mipMissLevel", "mipUnchecked", "stitchHits",
                    "stitchMisses", "verifyChecks", "verifyMismatches", "hitMicros"};
            for (int index = 0; index < names.length; index++) {
                counters.put(names[index], values[index]);
            }
            counters.put("status", BrowserAtlasPrebake.status());
            counters.put("source", source.status());
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(counters) + "\n";
        if (options.containsKey("--counters")) {
            Files.writeString(Path.of(options.get("--counters")), json);
        }
        System.out.print(json);
    }

    private static void blit(byte[] target, int targetWidth, byte[] source, int width, int height, int x, int y) {
        int targetHeight = target.length / 4 / Math.max(targetWidth, 1);
        for (int row = 0; row < height; row++) {
            if (y + row >= targetHeight) {
                break;
            }
            int copy = Math.min(width, targetWidth - x);
            if (copy > 0) {
                System.arraycopy(source, row * width * 4, target, ((y + row) * targetWidth + x) * 4, copy * 4);
            }
        }
    }

    // --- bytes ----------------------------------------------------------------------------------

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        writeInt(out, (int) value);
        writeInt(out, (int) (value >>> 32));
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > 0xffff || !new String(bytes, StandardCharsets.US_ASCII).equals(value)) {
            throw new IllegalArgumentException("not a short ASCII string: " + value);
        }
        out.write(bytes.length);
        out.write(bytes.length >>> 8);
        out.writeBytes(bytes);
    }

    static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Deterministic: no name, mtime 0 (GZIPOutputStream writes both as zero).
        try (GZIPOutputStream gzip = new GZIPOutputStream(out, 1 << 16) {
            {
                def.setLevel(java.util.zip.Deflater.BEST_COMPRESSION);
            }
        }) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }

    static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unused")
    private static ByteBuffer little(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
