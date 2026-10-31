package dev.gaius.browser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Retains safe atlas definitions supplied by servers that were built for a newer
 * resource-pack format than the browser client.
 */
public final class BrowserPackOverlayCompat {
    private BrowserPackOverlayCompat() {
    }

    /**
     * Keeps Minecraft's selected overlays and appends only overlays whose files are
     * exclusively atlas JSON definitions. Models, shaders, textures, and every
     * other future-format resource remain governed by vanilla's format checks.
     */
    public static List<String> mergeSafeAtlasOverlays(File archive, List<String> selected) {
        LinkedHashSet<String> effective = new LinkedHashSet<>(selected == null ? List.of() : selected);
        if (archive == null || !archive.isFile()) {
            return List.copyOf(effective);
        }

        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry metadataEntry = zip.getEntry("pack.mcmeta");
            if (metadataEntry == null) {
                return List.copyOf(effective);
            }
            JsonObject metadata;
            try (InputStreamReader input = new InputStreamReader(
                    zip.getInputStream(metadataEntry), StandardCharsets.UTF_8)) {
                metadata = JsonParser.parseReader(input).getAsJsonObject();
            }
            JsonObject overlays = objectMember(metadata, "overlays");
            JsonArray entries = overlays == null ? null : arrayMember(overlays, "entries");
            if (entries == null) {
                return List.copyOf(effective);
            }

            Set<String> candidates = new LinkedHashSet<>();
            for (JsonElement entry : entries) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                String directory = stringMember(entry.getAsJsonObject(), "directory");
                if (isSafeDirectory(directory) && !effective.contains(directory)) {
                    candidates.add(directory);
                }
            }
            if (candidates.isEmpty()) {
                return List.copyOf(effective);
            }

            Set<String> atlasOnly = atlasOnlyDirectories(zip, candidates);
            for (String directory : candidates) {
                if (atlasOnly.contains(directory)) {
                    effective.add(directory);
                }
            }
        } catch (Exception ignored) {
            // A broken optional compatibility pass must never reject a server pack.
        }
        return List.copyOf(effective);
    }

    private static Set<String> atlasOnlyDirectories(ZipFile zip, Set<String> candidates) {
        Set<String> pending = new LinkedHashSet<>(candidates);
        Set<String> atlasOnly = new LinkedHashSet<>();
        Set<String> rejected = new LinkedHashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements() && !pending.isEmpty()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            int separator = name.indexOf('/');
            if (separator <= 0) {
                continue;
            }
            String directory = name.substring(0, separator);
            if (!pending.contains(directory)) {
                continue;
            }
            String relativeName = name.substring(separator + 1);
            if (isAtlasJson(relativeName)) {
                atlasOnly.add(directory);
            } else {
                rejected.add(directory);
                pending.remove(directory);
            }
        }
        atlasOnly.removeAll(rejected);
        return atlasOnly;
    }

    private static boolean isAtlasJson(String path) {
        String[] parts = path.split("/");
        return parts.length >= 4
                && "assets".equals(parts[0])
                && "atlases".equals(parts[2])
                && path.endsWith(".json");
    }

    private static boolean isSafeDirectory(String directory) {
        return directory != null
                && !directory.isBlank()
                && directory.indexOf('/') < 0
                && directory.indexOf('\\') < 0
                && !directory.equals(".")
                && !directory.equals("..");
    }

    private static JsonObject objectMember(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray arrayMember(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String stringMember(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
}
