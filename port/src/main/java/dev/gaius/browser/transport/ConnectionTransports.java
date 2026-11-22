package dev.gaius.browser.transport;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;

/** The legacy bridge still decides the winner on the default relay/plugin path. */
public final class ConnectionTransports {
    private static final AttributeKey<String> ACTIVE = AttributeKey.valueOf("gaius.transport.active");
    private static final AttributeKey<String> EAGLER_URL = AttributeKey.valueOf("gaius.transport.eaglerUrl");
    private static final ConnectionTransport TUNNEL = (channel, address) -> { };
    private static final ConnectionTransport EAGLER = (channel, address) -> {
        channel.attr(EAGLER_URL).set(address == null ? "" : address.trim());
        EaglerHandshakeHandler.install(channel.pipeline());
    };

    private ConnectionTransports() { }

    public static void install(Channel channel, Connection connection) {
        if (!channel.getClass().getName().equals("io.netty.channel.browser.BrowserWebSocketChannel")) return;
        ConnectionTransport transport = switch (TransportKind.fromProtocol(connection.getGaiusTransport())) {
            case EAGLER -> EAGLER;
            case RELAY, PLUGIN, WSPROXY -> TUNNEL;
        };
        transport.install(channel, connection.getGaiusTransportAddress());
    }

    public static String active(Channel channel, String selected) {
        if (channel != null && channel.hasAttr(ACTIVE)) {
            String actual = channel.attr(ACTIVE).get();
            if (actual != null) return actual;
        }
        return selected;
    }
}
