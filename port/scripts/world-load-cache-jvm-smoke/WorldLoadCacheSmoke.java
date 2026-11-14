package dev.gaius.browser;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.util.Util;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.validation.DirectoryValidator;

/**
 * JVM check of the world load cache on the real vanilla 26.x classes: one vanilla world load of a
 * default or a custom data pack selection, with or without BrowserWorldLoadOps.prewarm before it,
 * then a digest of everything the load produced that does not depend on the seed: every
 * datapack registry entry (encoded with its codec, in raw id order), every tag of every registry,
 * the advancement, recipe and function ids and the pack list. Run once per mode in its own JVM
 * (the load binds tags and components into the static registries).
 *
 * args: <prewarm 0|1|miss> <default|custom> <scratch dir>
 */
public final class WorldLoadCacheSmoke {
    private WorldLoadCacheSmoke() {
    }

    private static final class Result {
        final List<String> packs;
        final LayeredRegistryAccess<RegistryLayer> layers;
        final ReloadableServerResources resources;

        Result(List<String> packs, LayeredRegistryAccess<RegistryLayer> layers,
                ReloadableServerResources resources) {
            this.packs = packs;
            this.layers = layers;
            this.resources = resources;
        }
    }

    public static void main(String[] args) throws Exception {
        boolean prewarm = !args[0].equals("0");
        boolean forceMiss = args[0].equals("miss");
        boolean custom = args[1].equals("custom");
        Path scratch = Path.of(args[2]);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
        long prewarmMillis = -1;
        String prewarmOutcome = "off";
        if (prewarm) {
            long start = System.nanoTime();
            prewarmOutcome = BrowserWorldLoadOps.prewarm(forceMiss);
            prewarmMillis = (System.nanoTime() - start) / 1_000_000;
        }
        Path packs = scratch.resolve("datapacks");
        if (custom) {
            Path pack = packs.resolve("srvinit_pack");
            Files.createDirectories(pack.resolve("data/minecraft/tags/block/mineable"));
            Files.createDirectories(pack.resolve("data/srvinit/function"));
            Files.writeString(pack.resolve("pack.mcmeta"),
                    "{\"pack\":{\"description\":\"srvinit\",\"min_format\":[104,0],\"max_format\":[104,0]}}");
            Files.writeString(pack.resolve("data/minecraft/tags/block/mineable/pickaxe.json"),
                    "{\"values\":[\"minecraft:dirt\"]}");
            Files.writeString(pack.resolve("data/srvinit/function/hello.mcfunction"), "say hello\n");
        } else {
            Files.createDirectories(packs);
        }
        PackRepository repository = ServerPacksSource.createPackRepository(packs,
                new DirectoryValidator(path -> true));
        DataPackConfig selection = custom
                ? new DataPackConfig(List.of("vanilla", "file/srvinit_pack"), List.of())
                : DataPackConfig.DEFAULT;
        WorldLoader.InitConfig config = new WorldLoader.InitConfig(
                new WorldLoader.PackConfig(repository,
                        new WorldDataConfiguration(selection, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS),
                        false, !custom),
                Commands.CommandSelection.DEDICATED,
                new DedicatedServerSettings(scratch.resolve("server.properties")).getProperties().functionPermissions);
        WorldLoader.WorldDataSupplier<Object> supplier = context ->
                new WorldLoader.DataLoadOutput<>(null, context.datapackDimensions());
        WorldLoader.ResultFactory<Object, Result> factory = (resourceManager, reloadable, layers, cookie) ->
                new Result(resourceManager.listPacks().map(pack -> pack.packId()).toList(), layers, reloadable);
        long start = System.nanoTime();
        Result result;
        try {
            result = Util.blockUntilDone(executor ->
                    WorldLoader.load(config, supplier, factory, executor, executor)).get();
        } catch (ExecutionException failure) {
            throw new RuntimeException(failure.getCause());
        }
        long loadMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("TIMING prewarm=" + prewarmOutcome + " " + prewarmMillis + "ms load=" + loadMillis + "ms");
        digest(result);
        System.exit(0);
    }

    private static void digest(Result result) throws Exception {
        RegistryAccess.Frozen composite = result.layers.compositeAccess();
        List<RegistryDataLoader.RegistryData<?>> dynamic = new ArrayList<>(RegistryDataLoader.WORLD_REGISTRIES);
        dynamic.addAll(RegistryDataLoader.DIMENSION_REGISTRIES);
        TreeMap<String, String> sections = new TreeMap<>();
        sections.put("packs", String.join(",", result.packs));
        for (RegistryDataLoader.RegistryData<?> data : dynamic) {
            sections.put("registry:" + data.key().identifier(), registryDigest(composite, data));
        }
        List<RegistryAccess.RegistryEntry<?>> all = composite.registries()
                .sorted(Comparator.comparing(entry -> entry.key().identifier().toString())).toList();
        for (RegistryAccess.RegistryEntry<?> entry : all) {
            sections.put("tags:" + entry.key().identifier(), tagDigest(entry.value()));
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, composite);
        StringBuilder advancements = new StringBuilder();
        result.resources.getAdvancements().getAllAdvancements().stream()
                .sorted(Comparator.comparing(holder -> holder.id().toString()))
                .forEach(holder -> advancements.append(holder.id()).append(' ')
                        .append(net.minecraft.advancements.Advancement.CODEC.encodeStart(ops, holder.value())
                                .result().map(WorldLoadCacheSmoke::canonical).orElse("ENCODE-ERROR")).append('\n'));
        sections.put("advancement-content", sha(advancements.toString()));
        sections.put("advancement-encode-errors", String.valueOf(advancements.toString().split("ENCODE-ERROR", -1).length - 1));
        StringBuilder recipes = new StringBuilder();
        result.resources.getRecipeManager().getRecipes().stream()
                .sorted(Comparator.comparing(holder -> holder.id().identifier().toString()))
                .forEach(holder -> recipes.append(holder.id().identifier()).append(' ')
                        .append(net.minecraft.world.item.crafting.Recipe.DIRECT_CODEC.encodeStart(ops, holder.value())
                                .result().map(WorldLoadCacheSmoke::canonical).orElse("ENCODE-ERROR")).append('\n'));
        sections.put("recipe-content", sha(recipes.toString()));
        var lootRegistry = result.resources.fullRegistries().lookup()
                .lookupOrThrow(net.minecraft.core.registries.Registries.LOOT_TABLE);
        StringBuilder loot = new StringBuilder();
        lootRegistry.listElements().sorted(Comparator.comparing(reference -> reference.key().identifier().toString()))
                .forEach(reference -> loot.append(reference.key().identifier()).append(' ')
                        .append(net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.encodeStart(ops, reference.value())
                                .result().map(WorldLoadCacheSmoke::canonical).orElse("ENCODE-ERROR")).append('\n'));
        sections.put("loot-tables", lootRegistry.listElements().count() + ":" + sha(loot.toString()));
        StringBuilder commands = new StringBuilder();
        commandTree(result.resources.getCommands().getDispatcher().getRoot(), "", commands);
        sections.put("commands", sha(commands.toString()));
        sections.put("advancements", ids(result.resources.getAdvancements().getAllAdvancements().stream()
                .map(holder -> holder.id().toString()).toList()));
        sections.put("recipes", ids(result.resources.getRecipeManager().getRecipes().stream()
                .map(holder -> holder.id().identifier().toString()).toList()));
        sections.put("functions", ids(result.resources.getFunctionLibrary().getFunctions().keySet().stream()
                .map(Object::toString).toList()));
        MessageDigest total = MessageDigest.getInstance("SHA-256");
        for (var entry : sections.entrySet()) {
            System.out.println("SECTION " + entry.getKey() + " " + entry.getValue());
            total.update((entry.getKey() + "=" + entry.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("DIGEST " + HexFormat.of().formatHex(total.digest()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void commandTree(com.mojang.brigadier.tree.CommandNode node, String path, StringBuilder out) {
        java.util.List<com.mojang.brigadier.tree.CommandNode> children = new ArrayList<>(node.getChildren());
        children.sort(Comparator.comparing(com.mojang.brigadier.tree.CommandNode::getName));
        for (com.mojang.brigadier.tree.CommandNode child : children) {
            String here = path + "/" + child.getName();
            out.append(here).append(' ').append(child.getClass().getSimpleName()).append(' ')
                    .append(child.getUsageText()).append(' ').append(child.getCommand() != null)
                    .append(' ').append(child.getRedirect() != null ? child.getRedirect().getName() : "-");
            if (child instanceof com.mojang.brigadier.tree.ArgumentCommandNode argument) {
                out.append(' ').append(argument.getType().getClass().getName());
            }
            out.append('\n');
            commandTree(child, here, out);
        }
    }

    private static String ids(List<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.size() + ":" + sha(String.join("\n", sorted));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String registryDigest(RegistryAccess composite, RegistryDataLoader.RegistryData data) throws Exception {
        Registry registry = composite.lookupOrThrow(data.key());
        Codec codec = data.elementCodec();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, composite);
        StringBuilder text = new StringBuilder();
        for (int id = 0; id < registry.size(); id++) {
            Object value = registry.byId(id);
            text.append(id).append(' ').append(registry.getKey(value)).append(' ');
            var encoded = codec.encodeStart(ops, value);
            text.append(encoded.result().map(json -> canonical((JsonElement) json)).orElse("ENCODE-ERROR")).append('\n');
        }
        return registry.size() + ":" + sha(text.toString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String tagDigest(Registry registry) throws Exception {
        List<String> tags = new ArrayList<>();
        ((java.util.stream.Stream<HolderSet.Named>) registry.getTags()).forEach(named -> {
            StringBuilder text = new StringBuilder(named.key().location().toString())
                    .append(named.isBound() ? " bound" : " unbound").append(':');
            if (named.isBound()) {
                named.stream().forEach(holder -> text.append(((Holder) holder).unwrapKey()
                        .map(key -> ((ResourceKey) key).identifier().toString()).orElse("?")).append(','));
            }
            tags.add(text.toString());
        });
        tags.sort(null);
        return tags.size() + ":" + sha(String.join("\n", tags));
    }

    /** JSON text with the members of every object sorted: map-backed fields iterate in identity-hash order. */
    private static String canonical(JsonElement element) {
        StringBuilder out = new StringBuilder();
        canonical(element, out);
        return out.toString();
    }

    private static void canonical(JsonElement element, StringBuilder out) {
        if (element.isJsonObject()) {
            List<String> names = new ArrayList<>(element.getAsJsonObject().keySet());
            names.sort(null);
            out.append('{');
            for (String name : names) {
                out.append(name).append(':');
                canonical(element.getAsJsonObject().get(name), out);
                out.append(',');
            }
            out.append('}');
        } else if (element.isJsonArray()) {
            out.append('[');
            for (JsonElement item : element.getAsJsonArray()) {
                canonical(item, out);
                out.append(',');
            }
            out.append(']');
        } else {
            out.append(element);
        }
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
