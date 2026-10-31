package dev.gaius.browser;

import com.google.gson.JsonParser;
import net.minecraft.client.renderer.block.model.TextureSlots;

/** Stable smoke surface for texture-slot packages that move between game versions. */
public final class BrowserTextureSlotsCompat {
    private BrowserTextureSlotsCompat() {
    }

    public static boolean acceptsSpriteObject() {
        TextureSlots.Data slots = TextureSlots.parseTextureMap(JsonParser.parseString("""
                {"base":"minecraft:block/stone","particle":{"sprite":"minecraft:block/dirt","force_translucent":true}}
                """).getAsJsonObject());
        return slots.values().size() == 2
                && slots.values().containsKey("base")
                && slots.values().containsKey("particle");
    }
}
