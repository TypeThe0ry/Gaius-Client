package dev.gaius.browser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.teavm.classlib.ResourceSupplier;
import org.teavm.classlib.ResourceSupplierContext;

/**
 * Supplies classpath resources that the official Minecraft client accesses through
 * {@code Class#getResourceAsStream}. TeaVM's JavaScript backend only embeds resources
 * named by {@link ResourceSupplier}s, so plain entries inside the client jar are not
 * visible in the browser unless they are listed here.
 */
public final class MinecraftResourceSupplier implements ResourceSupplier {
    private static final String RESOURCE_LIST = "/dev/gaius/browser/minecraft-resources.txt";
    private static final String EMBEDDED_RESOURCE_LIST =
            "/dev/gaius/browser/minecraft-embedded-resources.txt";
    private static final String[] FALLBACK_RESOURCES = {
            "assets/minecraft/lang/deprecated.json",
            "assets/minecraft/lang/en_us.json",
            "assets/minecraft/lang/zh_cn.json",
            "assets/minecraft/lang/es_es.json",
            "assets/gaius/lang/en_us.json",
            "assets/gaius/lang/zh_cn.json",
            "assets/gaius/lang/es_es.json"
    };

    @Override
    public String[] supplyResources(ResourceSupplierContext context) {
        String text = readResourceList(EMBEDDED_RESOURCE_LIST);
        if (text == null) {
            text = readResourceList(RESOURCE_LIST);
        }
        if (text == null) {
            return FALLBACK_RESOURCES.clone();
        }
        try {
            String requiredResources = String.join("\n", FALLBACK_RESOURCES);
            return Arrays.stream((RESOURCE_LIST.substring(1)
                            + "\n" + requiredResources + "\n" + text).split("\\R"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .distinct()
                    .toArray(String[]::new);
        } catch (RuntimeException ignored) {
            return FALLBACK_RESOURCES.clone();
        }
    }

    private static String readResourceList(String resource) {
        try (InputStream input = MinecraftResourceSupplier.class.getResourceAsStream(resource)) {
            return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }
}
