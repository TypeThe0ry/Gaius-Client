package dev.gaius.harness.extract;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Writes the vanilla shader sources a seam harness hands to its {@code ShaderSource} (26.2 and
 * 1.21.11 share the API used here): every
 * {@code assets/minecraft/shaders/**}{@code .vsh|.fsh} of the client jar through
 * {@code ShaderManager}'s preprocessor (the {@code moj_import} includes resolved), as the game's
 * {@code ShaderManager} provides them, keyed {@code "<shader id>|VERTEX|FRAGMENT"}. JVM only; the
 * output is vanilla content and stays in the harness's output directory.
 *
 * <p>usage: HarnessShaders CLIENT_JAR OUT_JSON</p>
 */
public final class HarnessShaders {
    private HarnessShaders() {
    }

    public static void main(String[] args) throws Exception {
        Map<Identifier, Resource> files = new HashMap<>();
        List<String> shaders = new ArrayList<>();
        try (ZipFile zip = new ZipFile(args[0])) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (!name.startsWith("assets/minecraft/shaders/") || name.endsWith("/")) {
                    continue;
                }
                byte[] bytes = zip.getInputStream(zip.getEntry(name)).readAllBytes();
                files.put(Identifier.fromNamespaceAndPath("minecraft", name.substring("assets/minecraft/".length())),
                        new Resource(null, () -> new ByteArrayInputStream(bytes)));
                if (name.endsWith(".vsh") || name.endsWith(".fsh")) {
                    shaders.add(name);
                }
            }
        }
        Method createPreprocessor = net.minecraft.client.renderer.ShaderManager.class
                .getDeclaredMethod("createPreprocessor", Map.class, Identifier.class);
        createPreprocessor.setAccessible(true);
        Map<String, String> out = new TreeMap<>();
        for (String name : shaders) {
            String path = name.substring("assets/minecraft/".length());
            Identifier file = Identifier.fromNamespaceAndPath("minecraft", path);
            String raw = new String(files.get(file).open().readAllBytes(), StandardCharsets.UTF_8);
            GlslPreprocessor preprocessor = (GlslPreprocessor) createPreprocessor.invoke(null, files, file);
            String id = "minecraft:" + path.substring("shaders/".length(), path.length() - 4);
            out.put(id + (name.endsWith(".vsh") ? "|VERTEX" : "|FRAGMENT"), String.join("", preprocessor.process(raw)));
        }
        StringBuilder json = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> entry : out.entrySet()) {
            json.append(i++ == 0 ? "" : ",\n").append(quote(entry.getKey())).append(": ").append(quote(entry.getValue()));
        }
        json.append("\n}\n");
        Files.writeString(Path.of(args[1]), json.toString());
        System.out.println("harness shaders: " + out.size() + " stages -> " + args[1]);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
