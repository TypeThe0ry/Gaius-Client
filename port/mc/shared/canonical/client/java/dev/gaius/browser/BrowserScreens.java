package dev.gaius.browser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Opens a screen on the generation's screen owner: {@code Gui.setScreen}. The legacy
 * compat set overrides this class (port/mc/shared/legacy-12111/client), where {@code Minecraft.setScreen}
 * still owns the current screen.
 */
public final class BrowserScreens {
    private BrowserScreens() {
    }

    public static void show(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }
}
