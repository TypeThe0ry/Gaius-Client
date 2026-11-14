import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;

/**
 * JVM gate of the unihex glyph table (port/mc/tests/unihex-table/README.md).
 *
 * <pre>java UnihexGate &lt;providers|fontsets&gt; &lt;pack dir&gt; &lt;dump&gt;</pre>
 *
 * Runs in one arm per JVM: the vanilla client (no Gaius class on the class path), or the browser
 * arms with the patched {@code UnihexProvider} classes and the Gaius loader, where
 * {@code -Dgate.table=<file>|disabled} selects the table the stub page offers and
 * {@code -Dgate.tamper=sha|length|header|none} spoils it, {@code -Dgate.archived=false} serves
 * the pack as plain streams and {@code -Dgate.userPack=<dir>} adds a resource pack above it. Everything observable of the providers and the
 * font sets is written to the dump in a fixed order; the runner compares dumps byte for byte.
 * Compiled against the vanilla jar only; the Gaius table is reached by reflection.
 */
public final class UnihexGate {
    private static final int MAX_CODEPOINT = 0x10FFFF;
    private static Capture capture;

    private UnihexGate() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: UnihexGate <providers|fontsets> <pack dir> <dump>");
        }
        String mode = args[0];
        Path pack = Path.of(args[1]);
        Path dump = Path.of(args[2]);
        TableStub stub = TableStub.install(pack);
        installDevice();
        // The browser serves the vanilla pack from its archive (FixedPathPackResources and the
        // VanillaPackResources overrides): a ByteArrayInputStream over the archived bytes that
        // ArchivedStreams.track records. The vanilla arm has no such class and reads the same files.
        boolean archived = Boolean.parseBoolean(System.getProperty("gate.archived", "true"));
        List<PackResources> packs = new ArrayList<>();
        packs.add(new ArchivePack("vanilla", pack, archived ? archiveStream() : null));
        String userPack = System.getProperty("gate.userPack");
        if (userPack != null) {
            // A resource pack above vanilla that ships the same zips: read as a resource pack.
            packs.add(new ArchivePack("file/gate", Path.of(userPack), null));
        }
        ResourceManager manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs);
        try (Dump out = new Dump(dump)) {
            switch (mode) {
                case "providers" -> providers(manager, pack, out);
                case "fontsets" -> fontSets(manager, out);
                default -> throw new IllegalArgumentException(mode);
            }
        }
        if (stub != null) {
            System.out.println("UNIHEX_TABLE hits=" + stub.hits + " fallbacks=" + stub.fallbacks
                    + " reasons=" + stub.reasons);
        }
        System.out.println("UNIHEX_GATE_DUMP mode=" + mode + " sha256=" + Dump.lastDigest
                + " lines=" + Dump.lastLines);
    }

    // --- providers -------------------------------------------------------------------------------

    /**
     * Every unihex provider of the vanilla font definitions, plus synthetic size overrides
     * (overlapping ranges, the extremes of the signed byte the loader keeps, negative and
     * wider-than-the-glyph dimensions, a two-code-point range) over each zip.
     */
    private static void providers(ResourceManager manager, Path pack, Dump out) throws Exception {
        List<String[]> cases = new ArrayList<>();
        for (String file : List.of("include/unifont.json", "include/unifont_pua.json")) {
            JsonObject root = JsonParser.parseString(Files.readString(
                    pack.resolve("assets/minecraft/font/" + file))).getAsJsonObject();
            int index = 0;
            for (JsonElement provider : root.getAsJsonArray("providers")) {
                JsonObject copy = provider.getAsJsonObject().deepCopy();
                copy.remove("filter");
                cases.add(new String[] {file + "#" + index++, copy.toString()});
            }
        }
        for (String zip : List.of("unifont", "unifont_jp", "unifont_pua")) {
            String hex = "\"type\":\"unihex\",\"hex_file\":\"minecraft:font/" + zip + ".zip\"";
            cases.add(new String[] {zip + "#plain", "{" + hex + "}"});
            cases.add(new String[] {zip + "#synthetic", "{" + hex + ",\"size_overrides\":["
                    + "{\"from\":\"\\u0041\",\"to\":\"\\u005a\",\"left\":2,\"right\":5},"
                    + "{\"from\":\"\\u0030\",\"to\":\"\\u0060\",\"left\":0,\"right\":127},"
                    + "{\"from\":\"\\u3400\",\"to\":\"\\u4dbf\",\"left\":-3,\"right\":7},"
                    + "{\"from\":\"\\ue000\",\"to\":\"\\ue0ff\",\"left\":-128,\"right\":40},"
                    + "{\"from\":\"\\u4e00\",\"to\":\"\\u4e01\",\"left\":9,\"right\":9},"
                    + "{\"from\":\"\\ud83d\\ude00\",\"to\":\"\\ud83d\\ude4f\",\"left\":3,\"right\":12}"
                    + "]}"});
        }
        for (String[] entry : cases) {
            GlyphProviderDefinition definition = GlyphProviderDefinition.MAP_CODEC.codec()
                    .parse(JsonOps.INSTANCE, JsonParser.parseString(entry[1])).getOrThrow();
            GlyphProvider provider = definition.unpack().left().orElseThrow().load(manager);
            out.line("provider " + entry[0] + " class=" + provider.getClass().getName());
            dumpProvider(provider, out);
        }
    }

    private static void dumpProvider(GlyphProvider provider, Dump out) throws IOException {
        IntSet supported = provider.getSupportedGlyphs();
        out.line("supported size=" + supported.size());
        StringBuilder order = new StringBuilder();
        int column = 0;
        for (IntIterator iterator = supported.iterator(); iterator.hasNext(); ) {
            order.append(Integer.toHexString(iterator.nextInt())).append(++column % 32 == 0 ? "\n" : " ");
        }
        out.line("order " + order);
        int glyphs = 0;
        for (int codepoint = 0; codepoint <= MAX_CODEPOINT; codepoint++) {
            UnbakedGlyph glyph = provider.getGlyph(codepoint);
            if (glyph == null) {
                if (supported.contains(codepoint)) {
                    out.line("U+" + Integer.toHexString(codepoint) + " supported-but-null");
                }
                continue;
            }
            glyphs++;
            StringBuilder line = new StringBuilder("U+").append(Integer.toHexString(codepoint))
                    .append(supported.contains(codepoint) ? "" : " unsupported")
                    .append(glyph == provider.getGlyph(codepoint) ? "" : " new-glyph-per-get");
            GlyphInfo info = glyph.info();
            line.append(info == glyph.info() ? " shared-info" : "").append(' ').append(info(info));
            Recorder recorder = new Recorder();
            BakedGlyph baked = glyph.bake(recorder);
            line.append(baked == null ? "" : " baked-non-null").append(' ').append(recorder.log);
            out.line(line.toString());
        }
        out.line("glyphs " + glyphs);
    }

    // --- font sets ------------------------------------------------------------------------------

    private static void fontSets(ResourceManager manager, Dump out) throws Exception {
        FontManager fonts = (FontManager) unsafe().allocateInstance(FontManager.class);
        Method prepare = FontManager.class.getDeclaredMethod("prepare", ResourceManager.class, Executor.class);
        prepare.setAccessible(true);
        Executor direct = Runnable::run;
        Object preparation = ((CompletableFuture<?>) prepare.invoke(fonts, manager, direct)).join();
        Method fontSetsAccessor = preparation.getClass().getDeclaredMethod("fontSets");
        Method allProvidersAccessor = preparation.getClass().getDeclaredMethod("allProviders");
        fontSetsAccessor.setAccessible(true);
        allProvidersAccessor.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Identifier, List<GlyphProvider.Conditional>> sets =
                (Map<Identifier, List<GlyphProvider.Conditional>>) fontSetsAccessor.invoke(preparation);
        @SuppressWarnings("unchecked")
        List<GlyphProvider> all = (List<GlyphProvider>) allProvidersAccessor.invoke(preparation);
        Map<GlyphProvider, String> names = new IdentityHashMap<>();
        out.line("allProviders " + all.size());
        for (int index = 0; index < all.size(); index++) {
            GlyphProvider provider = all.get(index);
            names.put(provider, "p" + index + ":" + provider.getClass().getSimpleName());
            out.line("p" + index + " " + provider.getClass().getName() + " glyphs="
                    + provider.getSupportedGlyphs().size());
        }
        Map<String, Identifier> sorted = new TreeMap<>();
        sets.keySet().forEach(id -> sorted.put(id.toString(), id));
        List<Set<FontOption>> optionSets = List.of(
                EnumSet.noneOf(FontOption.class),
                EnumSet.of(FontOption.UNIFORM),
                EnumSet.of(FontOption.JAPANESE_VARIANTS),
                EnumSet.of(FontOption.UNIFORM, FontOption.JAPANESE_VARIANTS));
        Field active = FontSet.class.getDeclaredField("activeProviders");
        Field byWidth = FontSet.class.getDeclaredField("glyphsByWidth");
        active.setAccessible(true);
        byWidth.setAccessible(true);
        for (Map.Entry<String, Identifier> entry : sorted.entrySet()) {
            List<GlyphProvider.Conditional> conditionals = sets.get(entry.getValue());
            StringBuilder listed = new StringBuilder();
            for (GlyphProvider.Conditional conditional : conditionals) {
                listed.append(' ').append(names.getOrDefault(conditional.provider(),
                        "fallback:" + conditional.provider().getClass().getSimpleName()));
            }
            out.line("font " + entry.getKey() + " providers" + listed);
            for (Set<FontOption> options : optionSets) {
                RecordingStitcher stitcher = new RecordingStitcher(entry.getValue());
                FontSet set = new FontSet(stitcher);
                // FontManager.apply: createFontSet(id, Lists.reverse(providers), options).
                set.reload(com.google.common.collect.Lists.reverse(conditionals), options);
                out.line("options " + options);
                StringBuilder providers = new StringBuilder("active");
                @SuppressWarnings("unchecked")
                List<GlyphProvider> activeProviders = (List<GlyphProvider>) active.get(set);
                for (GlyphProvider provider : activeProviders) {
                    providers.append(' ').append(names.getOrDefault(provider,
                            "fallback:" + provider.getClass().getSimpleName()));
                }
                out.line(providers.toString());
                @SuppressWarnings("unchecked")
                Int2ObjectMap<IntList> widths = (Int2ObjectMap<IntList>) byWidth.get(set);
                int maxWidth = 0;
                for (Int2ObjectMap.Entry<IntList> width : widths.int2ObjectEntrySet()) {
                    maxWidth = Math.max(maxWidth, width.getIntKey());
                    StringBuilder list = new StringBuilder("width ").append(width.getIntKey())
                            .append(" n=").append(width.getValue().size()).append(':');
                    IntList codepoints = width.getValue();
                    for (int index = 0; index < codepoints.size(); index++) {
                        list.append(index % 32 == 0 ? "\n " : " ").append(Integer.toHexString(codepoints.getInt(index)));
                    }
                    out.line(list.toString());
                }
                // §k obfuscation draws FontSet.getRandomGlyph(random, ceil(advance)) per glyph.
                RandomSource random = RandomSource.create(0x5EEDL + entry.getKey().hashCode());
                for (int width = 0; width <= maxWidth + 1; width++) {
                    StringBuilder draws = new StringBuilder("random ").append(width).append(':');
                    for (int draw = 0; draw < 48; draw++) {
                        BakedGlyph glyph = set.getRandomGlyph(random, width);
                        draws.append(' ').append(stitcher.describe(glyph));
                    }
                    out.line(draws.toString());
                }
                for (boolean nonFishy : new boolean[] {false, true}) {
                    GlyphSource source = set.source(nonFishy);
                    StringBuilder text = new StringBuilder("source nonFishy=").append(nonFishy).append(':');
                    int column = 0;
                    for (int codepoint : sampleCodepoints(activeProviders)) {
                        BakedGlyph glyph = source.getGlyph(codepoint);
                        text.append(++column % 16 == 0 ? "\n " : " ").append(Integer.toHexString(codepoint))
                                .append('=').append(stitcher.describe(glyph));
                    }
                    out.line(text.toString());
                }
                out.line("stitched " + stitcher.records.size());
                for (int index = 0; index < stitcher.records.size(); index++) {
                    out.line("s" + index + " " + stitcher.records.get(index));
                }
            }
        }
    }

    /** ASCII and Latin-1, then every 61st supported code point, then named scripts and edges. */
    private static int[] sampleCodepoints(List<GlyphProvider> providers) {
        java.util.TreeSet<Integer> sample = new java.util.TreeSet<>();
        for (int codepoint = 0; codepoint < 0x250; codepoint++) {
            sample.add(codepoint);
        }
        int[] named = {0x391, 0x3B1, 0x410, 0x430, 0x5D0, 0x627, 0x905, 0xE01, 0x3042, 0x30A2, 0x3400,
            0x4E00, 0x4E2D, 0x6587, 0x9FA5, 0xAC00, 0xD55C, 0xE000, 0xE0FF, 0xF8FF, 0xFFFD, 0x1F600,
            0x1F64F, 0x20000, 0x2A6D6, 0xF0000, 0x10FFFD, 0x10FFFF};
        for (int codepoint : named) {
            sample.add(codepoint);
        }
        java.util.TreeSet<Integer> union = new java.util.TreeSet<>();
        for (GlyphProvider provider : providers) {
            provider.getSupportedGlyphs().forEach((int codepoint) -> union.add(codepoint));
        }
        int step = 0;
        for (int codepoint : union) {
            if (step++ % 61 == 0) {
                sample.add(codepoint);
            }
        }
        return sample.stream().mapToInt(Integer::intValue).toArray();
    }

    // --- recording stitchers and the GPU stub ----------------------------------------------------

    private static String info(GlyphInfo info) {
        return "adv=" + bits(info.getAdvance()) + "/" + bits(info.getAdvance(true)) + "/"
                + bits(info.getAdvance(false)) + " shadow=" + bits(info.getShadowOffset())
                + " bold=" + bits(info.getBoldOffset());
    }

    private static String bitmap(GlyphBitmap bitmap) {
        StringBuilder text = new StringBuilder("bitmap ").append(bitmap.getPixelWidth()).append('x')
                .append(bitmap.getPixelHeight()).append(" os=").append(bits(bitmap.getOversample()))
                .append(" colored=").append(bitmap.isColored()).append(" lrtb=")
                .append(bits(bitmap.getLeft())).append(',').append(bits(bitmap.getRight())).append(',')
                .append(bits(bitmap.getTop())).append(',').append(bits(bitmap.getBottom()))
                .append(" bearing=").append(bits(bitmap.getBearingLeft())).append(',')
                .append(bits(bitmap.getBearingTop()));
        capture = new Capture();
        try {
            bitmap.upload(3, 5, null);
            text.append(' ').append(capture);
        } catch (RuntimeException failure) {
            text.append(" upload-failed=").append(failure.getClass().getSimpleName());
        } finally {
            capture = null;
        }
        return text.toString();
    }

    private static String bits(float value) {
        return Integer.toHexString(Float.floatToRawIntBits(value));
    }

    /** UnbakedGlyph.Stitcher of the provider dump: records the info and the uploaded pixels. */
    private static final class Recorder implements UnbakedGlyph.Stitcher {
        private String log = "not-stitched";

        @Override
        public BakedGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
            log = "stitch " + info(info) + " " + bitmap(bitmap);
            return null;
        }

        @Override
        public BakedGlyph getMissing() {
            log = "missing";
            return null;
        }
    }

    /** The font set's stitcher without textures: every stitched glyph is one numbered record. */
    private static final class RecordingStitcher extends GlyphStitcher {
        private final List<String> records = new ArrayList<>();
        private final Map<BakedGlyph, Integer> ids = new IdentityHashMap<>();

        RecordingStitcher(Identifier prefix) {
            super(null, prefix);
        }

        @Override
        public void reset() {
        }

        @Override
        public void close() {
        }

        @Override
        public BakedSheetGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
            String record = info(info) + " " + bitmap(bitmap);
            BakedSheetGlyph glyph = new BakedSheetGlyph(info, null, null, 0, 0, 0, 0, 0, 0, 0, 0);
            ids.put(glyph, records.size());
            records.add(record);
            return glyph;
        }

        String describe(BakedGlyph glyph) {
            Integer id = ids.get(glyph);
            if (id != null) {
                return "s" + id;
            }
            return glyph == null ? "null" : glyph.getClass().getSimpleName() + ":" + info(glyph.info());
        }
    }

    /** What a writeToTexture call received. */
    private static final class Capture {
        private final StringBuilder calls = new StringBuilder();

        void record(Object[] args) throws Exception {
            calls.append("write[");
            for (Object arg : args) {
                if (arg instanceof ByteBuffer buffer) {
                    ByteBuffer view = buffer.duplicate();
                    byte[] bytes = new byte[view.remaining()];
                    view.get(bytes);
                    calls.append("bytes=").append(bytes.length).append(':')
                            .append(sha256Hex(bytes), 0, 24).append(',');
                } else if (arg instanceof Integer value) {
                    calls.append(value).append(',');
                } else {
                    calls.append(arg == null ? "null" : arg.getClass().getSimpleName()).append(',');
                }
            }
            calls.append(']');
        }

        @Override
        public String toString() {
            return calls.length() == 0 ? "no-write" : calls.toString();
        }
    }

    private static void installDevice() throws Exception {
        InvocationHandler encoder = (proxy, method, args) -> {
            if (method.getName().equals("writeToTexture") && capture != null) {
                capture.record(args);
                return null;
            }
            throw new UnsupportedOperationException("CommandEncoder." + method.getName());
        };
        CommandEncoder commands = (CommandEncoder) Proxy.newProxyInstance(UnihexGate.class.getClassLoader(),
                new Class<?>[] {CommandEncoder.class}, encoder);
        InvocationHandler device = (proxy, method, args) -> {
            if (method.getName().equals("createCommandEncoder")) {
                return commands;
            }
            if (method.getName().equals("writeToTexture") && capture != null) {
                capture.record(args);
                return null;
            }
            throw new UnsupportedOperationException("GpuDevice." + method.getName());
        };
        GpuDevice gpu = (GpuDevice) Proxy.newProxyInstance(UnihexGate.class.getClassLoader(),
                new Class<?>[] {GpuDevice.class}, device);
        Field field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        field.set(null, gpu);
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The dump text, gzip-compressed on disk; the digest is of the uncompressed text. */
    private static final class Dump implements AutoCloseable {
        static String lastDigest;
        static long lastLines;
        private final MessageDigest digest;
        private final BufferedWriter writer;
        private long lines;

        Dump(Path path) throws IOException {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
            writer = new BufferedWriter(new java.io.OutputStreamWriter(new java.security.DigestOutputStream(
                    new java.util.zip.GZIPOutputStream(Files.newOutputStream(path), 1 << 16), digest),
                    StandardCharsets.UTF_8), 1 << 16);
        }

        void line(String text) throws IOException {
            writer.write(text);
            writer.write('\n');
            lines++;
        }

        @Override
        public void close() throws IOException {
            writer.close();
            lastDigest = HexFormat.of().formatHex(digest.digest());
            lastLines = lines;
        }
    }

    /** ArchivedStreams.track of the browser arms, null in the vanilla arm. */
    private static Method archiveStream() {
        try {
            return Class.forName("dev.gaius.browser.ArchivedStreams")
                    .getMethod("track", String.class, java.io.ByteArrayInputStream.class);
        } catch (ReflectiveOperationException vanilla) {
            return null;
        }
    }

    /**
     * A directory pack; with {@code stream} it hands out what the browser's vanilla pack hands
     * out for an archived resource: {@code ArchivedStreams.track(path, new ByteArrayInputStream(bytes))}
     * (AssetArchive holds entries up to 2 MiB; a larger one would come from the classpath, untracked).
     */
    private static final class ArchivePack extends PathPackResources {
        private final Method stream;

        ArchivePack(String id, Path root, Method stream) {
            super(new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty()), root);
            this.stream = stream;
        }

        @Override
        public net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream> getResource(
                PackType type, Identifier id) {
            net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream> plain = super.getResource(type, id);
            if (plain == null || stream == null) {
                return plain;
            }
            String path = type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath();
            return () -> {
                byte[] bytes;
                try (java.io.InputStream input = plain.get()) {
                    bytes = input.readAllBytes();
                }
                if (bytes.length > 2 * 1024 * 1024) {
                    return new java.io.ByteArrayInputStream(bytes);
                }
                try {
                    return (java.io.InputStream) stream.invoke(null, path, new java.io.ByteArrayInputStream(bytes));
                } catch (ReflectiveOperationException failure) {
                    throw new IOException(failure);
                }
            };
        }
    }

    // --- the page's side of the table, for the browser arms ----------------------------------

    /**
     * Stands in for gaius-boot.js and AssetArchive: offers the table file, verifies each zip by
     * hashing the bytes the pack directory serves for its path (as the page hashes the archive
     * entry), and reports a zip as served from the archive when the pack directory holds it with
     * that length and it fits one archive cache entry (2 MiB).
     */
    static final class TableStub implements InvocationHandler {
        private final String status;
        private final byte[] table;
        private final boolean[] verified;
        private final Path pack;
        int hits;
        int fallbacks;
        final List<String> reasons = new ArrayList<>();

        private TableStub(String status, byte[] table, boolean[] verified, Path pack) {
            this.status = status;
            this.table = table;
            this.verified = verified;
            this.pack = pack;
        }

        static TableStub install(Path pack) throws Exception {
            Class<?> type;
            try {
                type = Class.forName("dev.gaius.browser.UnihexGlyphTable");
            } catch (ClassNotFoundException vanilla) {
                if (System.getProperty("gate.table") != null) {
                    throw new IllegalStateException("gate.table set but the Gaius table class is absent");
                }
                return null;
            }
            String selected = System.getProperty("gate.table", "disabled");
            String tamper = System.getProperty("gate.tamper", "none");
            TableStub stub;
            if (selected.equals("disabled")) {
                stub = new TableStub("disabled", new byte[0], new boolean[0], pack);
            } else {
                byte[] table = Files.readAllBytes(Path.of(selected));
                boolean[] verified = verify(table, pack, tamper);
                if (tamper.equals("header")) {
                    table[8] ^= 0x7F;
                }
                if (tamper.equals("length")) {
                    // The page verified the zips; the table's recorded lengths no longer match.
                    lengths(table, 1);
                }
                stub = new TableStub("verified", table, verified, pack);
            }
            Class<?> source = Class.forName("dev.gaius.browser.UnihexGlyphTable$Source");
            Object proxy = Proxy.newProxyInstance(UnihexGate.class.getClassLoader(), new Class<?>[] {source}, stub);
            type.getMethod("useSourceForJvmGate", source).invoke(null, proxy);
            return stub;
        }

        /** Adds {@code delta} to the zip length of every entry of the header. */
        private static void lengths(byte[] table, int delta) {
            ByteBuffer header = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
            int cursor = 20;
            for (int index = 0; index < header.getInt(12); index++) {
                int pathLength = header.getShort(cursor) & 0xFFFF;
                cursor = (cursor + 2 + pathLength + 3) & ~3;
                header.putInt(cursor, header.getInt(cursor) + delta);
                cursor += 56;
            }
        }

        /** The page's check: SHA-256 of the served bytes of each listed path equals the table's. */
        private static boolean[] verify(byte[] table, Path pack, String tamper) throws Exception {
            ByteBuffer header = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
            int count = header.getInt(12);
            boolean[] verified = new boolean[count];
            int cursor = 20;
            for (int index = 0; index < count; index++) {
                int pathLength = header.getShort(cursor) & 0xFFFF;
                String path = new String(table, cursor + 2, pathLength, StandardCharsets.UTF_8);
                cursor = (cursor + 2 + pathLength + 3) & ~3;
                int length = header.getInt(cursor);
                byte[] expected = java.util.Arrays.copyOfRange(table, cursor + 4, cursor + 36);
                if (tamper.equals("sha")) {
                    expected[0] ^= 1;
                }
                cursor += 56;
                Path file = pack.resolve(path);
                if (Files.isRegularFile(file) && Files.size(file) == length) {
                    verified[index] = MessageDigest.isEqual(expected,
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                }
            }
            return verified;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
            switch (method.getName()) {
                case "status":
                    return status;
                case "length":
                    return table.length;
                case "copy": {
                    int offset = (Integer) args[0];
                    byte[] target = (byte[]) args[1];
                    if (offset < 0 || offset + (long) target.length > table.length) {
                        return false;
                    }
                    System.arraycopy(table, offset, target, 0, target.length);
                    return true;
                }
                case "zipVerified": {
                    int index = (Integer) args[0];
                    return index >= 0 && index < verified.length && verified[index];
                }
                case "publish":
                    hits = (Integer) args[0];
                    fallbacks = (Integer) args[1];
                    if (!((String) args[2]).isEmpty() && reasons.size() < 16) {
                        reasons.add((String) args[2]);
                    }
                    return null;
                case "toString":
                    return "TableStub";
                case "hashCode":
                    return System.identityHashCode(this);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }
    }
}
