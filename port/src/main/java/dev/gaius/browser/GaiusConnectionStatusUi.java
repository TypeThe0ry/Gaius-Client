package dev.gaius.browser;

import dev.gaius.browser.transport.TransportKind;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/** Presentation-only transport label for the vanilla connection status line. */
public final class GaiusConnectionStatusUi {
    private GaiusConnectionStatusUi() {
    }

    public static Component decorate(Connection connection, Component vanillaStatus) {
        try {
            if (connection == null || vanillaStatus == null) return vanillaStatus;
            String protocol = TransportKind.fromProtocol(connection.getGaiusTransport()).protocol();
            Component via = BrowserI18n.text("connection.connecting_via", protocolName(protocol));
            return Component.empty().append(BrowserI18n.text("connection.badge", protocolName(protocol)))
                    .append(Component.literal("  |  ")).append(via).append(Component.literal("  |  "))
                    .append(vanillaStatus);
        } catch (Throwable ignored) {
            return vanillaStatus;
        }
    }

    private static Component protocolName(String protocol) {
        return GaiusServerProtocolUi.protocolLabel(protocol);
    }
}
