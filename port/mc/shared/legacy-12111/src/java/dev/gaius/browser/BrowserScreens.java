package dev.gaius.browser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** 1.21.11 override of the shared BrowserScreens: Minecraft.setScreen owns the current screen. */
public final class BrowserScreens {
    private BrowserScreens() {
    }

    public static void show(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }
}
