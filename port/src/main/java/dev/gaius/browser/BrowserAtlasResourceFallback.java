package dev.gaius.browser;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Narrow visual fallback for malformed server atlas entries. */
public final class BrowserAtlasResourceFallback {
    private BrowserAtlasResourceFallback() {
    }

    public static Optional<Resource> getResource(ResourceManager manager, Identifier textureId) {
        Optional<Resource> direct = manager.getResource(textureId);
        if (direct.isPresent()) {
            return direct;
        }
        Identifier fallback = vanillaEntityFallback(textureId);
        return fallback == null ? direct : manager.getResource(fallback);
    }

    static Identifier vanillaEntityFallback(Identifier textureId) {
        if ("minecraft".equals(textureId.getNamespace())
                || !textureId.getPath().startsWith("entity/")) {
            return null;
        }
        return Identifier.withDefaultNamespace(textureId.getPath());
    }
}
