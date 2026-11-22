package dev.gaius.browser;

import java.util.List;
import dev.gaius.browser.transport.TransportKind;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/** Additive protocol controls for the vanilla Add/Edit Server screen. */
public final class GaiusServerProtocolUi {
    private GaiusServerProtocolUi() {
    }

    public static CycleButton<String> createSelector(ServerData data, int screenWidth, int screenHeight) {
        try {
            String current = TransportKind.fromProtocol(data.gaiusProtocol).protocol();
            int x = screenWidth / 2 - 100;
            int y = screenHeight / 4 + 94;
            return CycleButton.<String>builder(
                    GaiusServerProtocolUi::protocolLabel, current)
                    .withValues(List.of("relay", "plugin", "eagler"))
                    .create(x, y, 200, 20, BrowserI18n.text("server.protocol"),
                            (button, value) -> data.gaiusProtocol = value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void applyAddressHint(EditBox address) {
        try {
            address.setSuggestion(BrowserI18n.string("server.protocol.websocket_hint"));
        } catch (Throwable ignored) {
            // Address hints must never prevent vanilla server editing.
        }
    }

    public static Component protocolLabel(String protocol) {
        return switch (TransportKind.fromProtocol(protocol)) {
            case RELAY -> BrowserI18n.text("server.protocol.relay");
            case PLUGIN -> BrowserI18n.text("server.protocol.plugin");
            case EAGLER -> BrowserI18n.text("server.protocol.eagler");
            case WSPROXY -> BrowserI18n.text("server.protocol.wsproxy");
        };
    }
}
