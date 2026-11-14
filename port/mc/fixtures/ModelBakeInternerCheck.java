import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import dev.gaius.browser.BrowserStrongInterner;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The real model bake of the vanilla client pack on the JVM, once per interner, for
 * Minecraft262BrowserPatcher.patchModelBakeryInterner.
 *
 * <p>The class path carries {@code ModelBakery$InternerImpl} as the patch writes it, with its two
 * {@code BrowserStrongInterner.create()} call sites pointed at {@link Switch#create()} (the node test
 * does that rewrite and checks that it is the only change): the same constructor then builds
 * Guava's strong interner, the browser one, or a recording Guava one. The vanilla steps of
 * ModelManager.reload up to {@code ModelBakery.bakeModels} run on a direct executor (the browser
 * runs the same tasks one at a time on its main thread): block models, block states, client items,
 * model discovery, the block and item atlases stitched by SpriteLoader from the jar's textures
 * (plus the other atlases for the special models' SpriteGetter). Then:
 *
 * <ol>
 *   <li>RECORD: Guava's interner, recording every sample and the instance intern returned.</li>
 *   <li>replay: the recorded samples, in order, through a fresh Guava strong interner and a fresh
 *       BrowserStrongInterner; both must return, at every call, the same instance as the recorded
 *       run (identity). After the bake every canonical instance must still have the hashCode it
 *       had when it was interned (nothing mutated an interned key).</li>
 *   <li>GUAVA and BROWSER bakes: the identity graph of each BakingResult (every object reachable
 *       from it, numbered in first-visit order, with its class, primitive values and which numbered
 *       object each reference points to; maps by key order) must have the same digest as the
 *       RECORD bake's; GUAVA vs RECORD shows the dump is deterministic, and a NOINTERN bake (intern
 *       returns its sample) must differ, which shows the dump sees the sharing.</li>
 * </ol>
 */
public final class ModelBakeInternerCheck {
    /** The interner the patched InternerImpl constructor gets. */
    public static final class Switch {
        static volatile String mode = "GUAVA";
        static final List<Object[]> RECORDED = new ArrayList<>();
        static final List<int[]> RECORDED_HASHES = new ArrayList<>();
        static int created;

        static long calls;

        public static <E> Interner<E> create() {
            created++;
            Interner<E> interner = switch (mode) {
                case "GUAVA" -> Interners.newStrongInterner();
                case "BROWSER" -> BrowserStrongInterner.create();
                case "NOINTERN" -> sample -> sample;
                case "RECORD" -> {
                    Interner<E> guava = Interners.newStrongInterner();
                    int id = created;
                    yield sample -> {
                        E result = guava.intern(sample);
                        RECORDED.add(new Object[] {id, sample, result});
                        RECORDED_HASHES.add(new int[] {sample.hashCode()});
                        return result;
                    };
                }
                default -> throw new IllegalStateException(mode);
            };
            return sample -> {
                calls++;
                return interner.intern(sample);
            };
        }
    }

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // The client registries the model and atlas codecs read (ClientBootstrap minus dialogs and
        // the Vulkan feature sets, which the bake does not touch).
        for (String type : new String[] {
                "net.minecraft.client.renderer.item.ItemModels",
                "net.minecraft.client.renderer.special.SpecialModelRenderers",
                "net.minecraft.client.color.item.ItemTintSources",
                "net.minecraft.client.renderer.item.properties.select.SelectItemModelProperties",
                "net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperties",
                "net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties",
                "net.minecraft.client.renderer.texture.atlas.SpriteSources"}) {
            Class.forName(type).getMethod("bootstrap").invoke(null);
        }
        stubMinecraftOptions();
        Executor direct = Runnable::run;

        Method createVanilla = ClientPackSource.class.getDeclaredMethod("createVanillaPackSource", Path.class);
        createVanilla.setAccessible(true);
        Path assets = Files.createDirectories(scratch.resolve("assets"));
        VanillaPackResources pack = (VanillaPackResources) createVanilla.invoke(null, assets);
        ResourceManager resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.<PackResources>of(pack.fullResources()));

        Field knownAtlases = AtlasManager.class.getDeclaredField("KNOWN_ATLASES");
        knownAtlases.setAccessible(true);
        Map<Identifier, SpriteLoader.Preparations> atlases = new HashMap<>();
        for (Object config : (List<?>) knownAtlases.get(null)) {
            Identifier texture = (Identifier) config.getClass().getMethod("textureId").invoke(config);
            Identifier definition = (Identifier) config.getClass().getMethod("definitionLocation").invoke(config);
            boolean mipmaps = (boolean) config.getClass().getMethod("createMipmaps").invoke(config);
            @SuppressWarnings("unchecked")
            Set<net.minecraft.server.packs.metadata.MetadataSectionType<?>> metadata =
                    (Set<net.minecraft.server.packs.metadata.MetadataSectionType<?>>) config.getClass()
                            .getMethod("additionalMetadata").invoke(config);
            SpriteLoader.Preparations stitched = new SpriteLoader(texture, 16384)
                    .loadAndStitch(resources, definition, mipmaps ? 4 : 0, direct, metadata).join();
            atlases.put(definition, stitched);
            atlases.put(texture, stitched);
        }
        SpriteLoader.Preparations blocks = atlases.get(AtlasIds.BLOCKS);
        SpriteLoader.Preparations items = atlases.get(AtlasIds.ITEMS);
        SpriteGetter sprites = (SpriteId id) -> {
            SpriteLoader.Preparations atlas = atlases.get(id.atlasLocation());
            if (atlas == null) {
                throw new IllegalArgumentException("Invalid atlas id: " + id.atlasLocation());
            }
            return atlas.getSprite(id.texture());
        };

        EntityModelSet entityModels = EntityModelSet.vanilla();
        Map<String, String> dumps = new HashMap<>();
        Map<String, Long> callCounts = new HashMap<>();
        for (String mode : new String[] {"RECORD", "GUAVA", "BROWSER", "NOINTERN"}) {
            // Fresh unbaked inputs per bake: resolved models and unbaked roots cache what they bake.
            Method loadBlockModels = ModelManager.class.getDeclaredMethod("loadBlockModels", ResourceManager.class, Executor.class);
            loadBlockModels.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Identifier, UnbakedModel> blockModels =
                    ((CompletableFuture<Map<Identifier, UnbakedModel>>) loadBlockModels.invoke(null, resources, direct)).join();
            BlockStateModelLoader.LoadedModels blockStates = BlockStateModelLoader.loadBlockStates(resources, direct).join();
            ClientItemInfoLoader.LoadedClientInfos clientInfos = ClientItemInfoLoader.scheduleLoad(resources, direct).join();
            Method discover = ModelManager.class.getDeclaredMethod("discoverModelDependencies", Map.class,
                    BlockStateModelLoader.LoadedModels.class, ClientItemInfoLoader.LoadedClientInfos.class);
            discover.setAccessible(true);
            Object resolved = discover.invoke(null, blockModels, blockStates, clientInfos);
            Method models = resolved.getClass().getDeclaredMethod("models");
            Method missing = resolved.getClass().getDeclaredMethod("missing");
            models.setAccessible(true);
            missing.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Identifier, ResolvedModel> resolvedModels = (Map<Identifier, ResolvedModel>) models.invoke(resolved);
            ResolvedModel missingModel = (ResolvedModel) missing.invoke(resolved);
            MaterialBaker materials = new MaterialBaker(blocks, items);
            if (mode.equals("RECORD")) {
                System.out.println("BAKE_INPUT blockStates=" + blockStates.models().size() + " items="
                        + clientInfos.contents().size() + " resolvedModels=" + resolvedModels.size()
                        + " blockAtlas=" + blocks.regions().size() + " itemAtlas=" + items.regions().size());
            }

            Switch.mode = mode;
            int before = Switch.created;
            Switch.calls = 0;
            ModelBakery bakery = new ModelBakery(entityModels, sprites, null, blockStates.models(), clientInfos.contents(),
                    resolvedModels, missingModel);
            ModelBakery.BakingResult result = bakery.bakeModels(materials, direct).join();
            if (Switch.created != before + 2) {
                throw new IllegalStateException("InternerImpl did not take its interners from Switch (" + mode + ")");
            }
            callCounts.put(mode, Switch.calls);
            dumps.put(mode, IdentityDump.digest(result));
            System.out.println("BAKE " + mode + " internCalls=" + Switch.calls + " blockStateModels="
                    + result.blockStateModels().size() + " itemModels=" + result.itemStackModels().size() + " "
                    + dumps.get(mode));
        }
        if (callCounts.values().stream().distinct().count() != 1 || callCounts.get("RECORD") == 0) {
            throw new IllegalStateException("the bakes made different numbers of intern calls: " + callCounts);
        }

        // Replay the recorded bake's intern sequence through both interners.
        List<Object[]> recorded = Switch.RECORDED;
        Map<Object, Interner<Object>> guava = new HashMap<>();
        Map<Object, Interner<Object>> browser = new HashMap<>();
        int hits = 0;
        for (int i = 0; i < recorded.size(); i++) {
            Object[] call = recorded.get(i);
            Object sample = call[1];
            Object expected = call[2];
            Object fromGuava = guava.computeIfAbsent(call[0], k -> Interners.newStrongInterner()).intern(sample);
            Object fromBrowser = browser.computeIfAbsent(call[0], k -> BrowserStrongInterner.create()).intern(sample);
            if (fromGuava != expected || fromBrowser != expected) {
                throw new IllegalStateException("intern call " + i + " (" + sample.getClass().getName() + "): recorded "
                        + System.identityHashCode(expected) + " guava " + System.identityHashCode(fromGuava)
                        + " browser " + System.identityHashCode(fromBrowser));
            }
            if (expected != sample) {
                hits++;
            }
            if (sample.hashCode() != Switch.RECORDED_HASHES.get(i)[0]) {
                throw new IllegalStateException("intern call " + i + ": the sample's hashCode changed after intern");
            }
        }
        Map<String, Integer> classes = new java.util.TreeMap<>();
        for (Object[] call : recorded) {
            classes.merge(call[1].getClass().getSimpleName(), 1, Integer::sum);
        }
        System.out.println("INTERN_REPLAY calls=" + recorded.size() + " canonicalHits=" + hits + " interners="
                + guava.size() + " classes=" + classes + " identical=true");
        if (recorded.isEmpty() || guava.size() != 2) {
            throw new IllegalStateException("the recorded bake used " + guava.size() + " interners for "
                    + recorded.size() + " calls");
        }
        if (dumps.get("RECORD").equals(dumps.get("NOINTERN"))) {
            throw new IllegalStateException("the identity graph does not see interning (negative control)");
        }
        if (!dumps.get("RECORD").equals(dumps.get("GUAVA")) || !dumps.get("RECORD").equals(dumps.get("BROWSER"))) {
            throw new IllegalStateException("BakingResult identity graphs differ: " + dumps);
        }
        System.out.println("MODEL_BAKE_INTERNER_OK calls=" + recorded.size() + " graph=" + dumps.get("BROWSER"));
    }

    /**
     * SpriteLoader.stitch reads {@code Minecraft.getInstance().options.textureFiltering()}: a
     * Minecraft and an Options allocated without constructors carry only that option (NONE).
     */
    private static void stubMinecraftOptions() throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
        Class<?> options = Class.forName("net.minecraft.client.Options");
        Class<?> option = Class.forName("net.minecraft.client.OptionInstance");
        Object optionsValue = allocate.invoke(unsafe, options);
        Object filtering = allocate.invoke(unsafe, option);
        Field value = option.getDeclaredField("value");
        value.setAccessible(true);
        value.set(filtering, Enum.valueOf(Class.forName("net.minecraft.client.TextureFilteringMethod")
                .asSubclass(Enum.class), "NONE"));
        set(options, optionsValue, "textureFiltering", filtering);
        Object minecraftValue = allocate.invoke(unsafe, minecraft);
        set(minecraft, minecraftValue, "options", optionsValue);
        Field instance = minecraft.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, minecraftValue);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        Object unsafe = unsafe();
        long offset = (long) unsafe.getClass().getMethod("objectFieldOffset", Field.class).invoke(unsafe, field);
        unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, target, offset, value);
    }

    private static Object unsafe() throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return theUnsafe.get(null);
    }

    /** First-visit numbering of every object reachable from a root, as a SHA-256 digest. */
    static final class IdentityDump {
        private final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        private final ArrayDeque<Object> queue = new ArrayDeque<>();
        private final MessageDigest digest;
        private final Map<Class<?>, List<Field>> fields = new HashMap<>();
        private long references;

        private IdentityDump() throws Exception {
            digest = MessageDigest.getInstance("SHA-256");
        }

        static String digest(Object root) throws Exception {
            IdentityDump dump = new IdentityDump();
            String traceDir = System.getenv("GAIUS_DUMP_TRACE_DIR");
            if (traceDir != null) {
                dump.trace = new java.io.PrintWriter(Files.newBufferedWriter(Path.of(traceDir, "dump-" + (dumpCount++) + ".txt")));
            }
            dump.ref(root);
            while (!dump.queue.isEmpty()) {
                dump.visit(dump.queue.poll());
            }
            if (System.getenv("GAIUS_DUMP_HISTOGRAM") != null) {
                Map<String, Integer> histogram = new HashMap<>();
                for (Object o : dump.ids.keySet()) {
                    histogram.merge(o.getClass().getName() + (leaf(o) ? " (leaf)" : ""), 1, Integer::sum);
                }
                histogram.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40)
                        .forEach(e -> System.out.println("HISTOGRAM " + e.getValue() + " " + e.getKey()));
            }
            if (dump.trace != null) {
                dump.trace.close();
            }
            return "objects=" + dump.ids.size() + " refs=" + dump.references + " sha256="
                    + HexFormat.of().formatHex(dump.digest.digest());
        }

        private java.io.PrintWriter trace;
        private static int dumpCount;
        /** Leaves are input objects shared by every bake: one id for the whole run. */
        private static final IdentityHashMap<Object, Integer> LEAF_IDS = new IdentityHashMap<>();

        private void text(String text) {
            if (trace != null) {
                trace.println(text);
            }
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }

        private void ref(Object value) {
            references++;
            if (value == null) {
                text("null");
                return;
            }
            if (value instanceof String || value instanceof Number && value.getClass().getName().startsWith("java.lang.")
                    || value instanceof Boolean
                    || value instanceof Character || value instanceof Enum<?> || value instanceof Class<?>) {
                text(value.getClass().getName() + ":" + (value instanceof Enum<?> e ? e.name() : String.valueOf(value)));
                return;
            }
            Integer id = ids.get(value);
            if (id == null) {
                id = ids.size();
                ids.put(value, id);
                queue.add(value);
            }
            text("#" + id);
        }

        /** Objects that exist before the bake (game objects, sprites, render state): identity only. */
        private static final List<Class<?>> LEAVES = new ArrayList<>();

        static {
            for (String name : new String[] {
                    "net.minecraft.world.level.block.state.StateHolder",
                    "net.minecraft.world.level.block.Block",
                    "net.minecraft.world.item.Item",
                    "net.minecraft.core.Holder",
                    "net.minecraft.core.Registry",
                    "net.minecraft.resources.ResourceKey",
                    "net.minecraft.client.renderer.texture.TextureAtlasSprite",
                    "net.minecraft.client.renderer.texture.SpriteContents",
                    "net.minecraft.client.renderer.rendertype.RenderType",
                    "com.mojang.blaze3d.pipeline.RenderPipeline",
                    "com.mojang.renderpearl.api.pipeline.RenderPipeline",
                    "net.minecraft.client.model.geom.EntityModelSet",
                    "com.mojang.serialization.Codec",
                    "com.mojang.serialization.MapCodec",
                    "net.minecraft.core.component.DataComponentType",
                    "net.minecraft.client.resources.model.sprite.SpriteGetter"}) {
                try {
                    LEAVES.add(Class.forName(name));
                } catch (ClassNotFoundException e) {
                    // not in this profile (the render pipeline moved between generations)
                }
            }
            if (LEAVES.size() < 14) {
                throw new IllegalStateException("leaf classes resolved: " + LEAVES);
            }
        }

        private static boolean leaf(Object value) {
            for (Class<?> type : LEAVES) {
                if (type.isInstance(value)) {
                    return true;
                }
            }
            return value.getClass().getName().startsWith("ModelBakeInternerCheck");
        }

        private void visit(Object value) throws Exception {
            Class<?> type = value.getClass();
            text("@" + ids.get(value) + " " + (type.isHidden() ? "lambda:" + java.util.Arrays.toString(type.getInterfaces()) : type.getName()));
            if (leaf(value)) {
                Integer leafId = LEAF_IDS.get(value);
                if (leafId == null) {
                    leafId = LEAF_IDS.size();
                    LEAF_IDS.put(value, leafId);
                }
                text("leaf " + leafId);
                return;
            }
            if (type.isArray()) {
                int length = Array.getLength(value);
                text("len " + length);
                Class<?> component = type.getComponentType();
                for (int i = 0; i < length; i++) {
                    Object element = Array.get(value, i);
                    if (component.isPrimitive()) {
                        text(String.valueOf(element));
                    } else {
                        ref(element);
                    }
                }
                return;
            }
            if (value instanceof Map<?, ?> map && type.getName().startsWith("java.")
                    || value instanceof Map<?, ?> && type.getName().startsWith("com.google.common.")
                    || value instanceof Map<?, ?> && type.getName().startsWith("it.unimi.dsi.fastutil.")) {
                Map<?, ?> m = (Map<?, ?>) value;
                text("map " + m.size());
                for (Map.Entry<?, ?> entry : m.entrySet()) {
                    ref(entry.getKey());
                    ref(entry.getValue());
                }
                return;
            }
            if (value instanceof Collection<?> collection && (type.getName().startsWith("java.")
                    || type.getName().startsWith("com.google.common.") || type.getName().startsWith("it.unimi.dsi.fastutil."))) {
                text("collection " + collection.size());
                for (Object element : collection) {
                    ref(element);
                }
                return;
            }
            if (value instanceof java.util.Optional<?> optional) {
                text("optional");
                ref(optional.orElse(null));
                return;
            }
            if (type.getName().startsWith("java.") || type.getName().startsWith("com.ibm.icu.")) {
                // other JDK objects and ICU formatters (they hold the clock time they were made at):
                // class and identity only
                text("opaque");
                return;
            }
            for (Field field : fields(type)) {
                Object fieldValue = field.get(value);
                if (field.getType().isPrimitive()) {
                    if (fieldValue instanceof Float f) {
                        text(field.getName() + "=f" + Float.floatToRawIntBits(f));
                    } else if (fieldValue instanceof Double d) {
                        text(field.getName() + "=d" + Double.doubleToRawLongBits(d));
                    } else {
                        text(field.getName() + "=" + fieldValue);
                    }
                } else {
                    text(field.getName());
                    ref(fieldValue);
                }
            }
        }

        private List<Field> fields(Class<?> type) {
            return fields.computeIfAbsent(type, t -> {
                List<Field> result = new ArrayList<>();
                for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
                    if (c.getName().startsWith("java.") && c != t) {
                        break;
                    }
                    for (Field field : c.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) {
                            continue;
                        }
                        field.setAccessible(true);
                        result.add(field);
                    }
                }
                return result;
            });
        }
    }

    private ModelBakeInternerCheck() {
    }
}
