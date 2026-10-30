package dev.gaius.browser;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.network.Connection;

/**
 * Tells the integrated server Worker which login connection is the host player's own.
 *
 * <p>Vanilla singleplayer never compresses the host's connection: ServerLoginPacketListenerImpl
 * sends ClientboundLoginCompressionPacket only when {@code !connection.isMemoryConnection()},
 * and the integrated server's client talks to it over a Netty LocalChannel. In the browser the
 * host reaches the Worker through the MessagePort-backed BrowserWebSocketChannel, whose
 * connection is not a memory connection, so every packet was deflated in the Worker and
 * inflated on the page. The host's channel is the startup listener channel of
 * {@code BrowserIntegratedServerMain.tunnelAddress()} (host {@code server-<session>.gaius-local});
 * Open to LAN guests use {@code lan-server-<session>.gaius-local} channels and keep the
 * dedicated server's compression threshold.
 */
public final class BrowserHostConnection {
    private static final String HOST_PREFIX = "server-";
    private static final String HOST_SUFFIX = ".gaius-local";
    private static final int SESSION_ID_LENGTH = 32;

    private BrowserHostConnection() {
    }

    /**
     * The login compression gate of ServerLoginPacketListenerImpl: true for vanilla's memory
     * connection and for the host player's tunnel channel.
     */
    public static boolean skipsLoginCompression(Connection connection) {
        return connection.isMemoryConnection() || isHostTunnel(connection.getRemoteAddress());
    }

    /** True for the remote address of the Worker's startup listener channel. */
    public static boolean isHostTunnel(SocketAddress address) {
        if (!(address instanceof InetSocketAddress socket)) {
            return false;
        }
        String host = socket.getHostString();
        if (host == null
                || host.length() != HOST_PREFIX.length() + SESSION_ID_LENGTH + HOST_SUFFIX.length()
                || !host.startsWith(HOST_PREFIX)
                || !host.endsWith(HOST_SUFFIX)) {
            return false;
        }
        for (int index = HOST_PREFIX.length(); index < HOST_PREFIX.length() + SESSION_ID_LENGTH; index++) {
            char c = host.charAt(index);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }
}
