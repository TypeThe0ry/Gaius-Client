package dev.gaius.browser;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Shared access to the Gaius namespace in Minecraft's merged language manager. */
final class BrowserI18n {
    private BrowserI18n() {
    }

    static MutableComponent text(String key, Object... args) {
        return Component.translatable("gaius." + key, args);
    }

    static String string(String key, Object... args) {
        return text(key, args).getString();
    }
}
