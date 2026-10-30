package dev.gaius.parity;

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Executable check of dev.gaius.browser.BrowserHostConnection, the login compression gate of the
 * WasmGC integrated server (MinecraftClientPatcher.patchSingleplayerHostCompression). The real
 * net.minecraft.network.Connection of the profile's jar runs over an EmbeddedChannel whose remote
 * address is the one under test, so the gate sees what the server Worker sees: the address that
 * channelActive stored in the Connection.
 *
 * <p>Host tunnel (startup listener, BrowserIntegratedServerMain.tunnelAddress): no login
 * compression. Open to LAN guests (lan-server-...), other unresolved hosts and real sockets keep
 * the dedicated server's threshold.
 *
 * <p>The harness is started with the patched ServerLoginPacketListenerImpl in front of the class
 * path and -Xverify:all; loading that class here links and verifies the patched bytecode.
 */
public final class HostLoginCompressionCheck {
    private static final String LOGIN = "net.minecraft.server.network.ServerLoginPacketListenerImpl";
    private static final String SESSION = "0123456789abcdef0123456789abcdef";
    private static int checks;

    private static void fail(String message) {
        throw new AssertionError(message);
    }

    private static boolean gate(SocketAddress remote) {
        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override
            protected SocketAddress remoteAddress0() {
                return remote;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channel.pipeline().addLast(connection);
        try {
            connection.channelActive(channel.pipeline().context(connection));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        if (connection.isMemoryConnection()) {
            fail("an EmbeddedChannel connection must not be a vanilla memory connection");
        }
        boolean skips = dev.gaius.browser.BrowserHostConnection.skipsLoginCompression(connection);
        if (skips != dev.gaius.browser.BrowserHostConnection.isHostTunnel(connection.getRemoteAddress())) {
            fail("skipsLoginCompression and isHostTunnel disagree for " + connection.getRemoteAddress());
        }
        channel.finishAndReleaseAll();
        return skips;
    }

    private static void expect(String label, SocketAddress remote, boolean skips) {
        checks++;
        boolean actual = gate(remote);
        if (actual != skips) {
            fail(label + ": skipsLoginCompression=" + actual + " for " + remote);
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> login = Class.forName(LOGIN, true, HostLoginCompressionCheck.class.getClassLoader());
        String source = String.valueOf(login.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("login listener loaded and verified from " + source);

        expect("host tunnel", InetSocketAddress.createUnresolved("server-" + SESSION + ".gaius-local", 25565), true);
        expect("host tunnel on another port", InetSocketAddress.createUnresolved("server-" + SESSION + ".gaius-local", 1), true);
        expect("Open to LAN guest", InetSocketAddress.createUnresolved("lan-server-" + SESSION + ".gaius-local", 25565), false);
        expect("client tunnel host", InetSocketAddress.createUnresolved("client-" + SESSION + ".gaius-local", 25565), false);
        expect("short session id", InetSocketAddress.createUnresolved("server-" + SESSION.substring(1) + ".gaius-local", 25565), false);
        expect("long session id", InetSocketAddress.createUnresolved("server-" + SESSION + "0.gaius-local", 25565), false);
        expect("upper-case session id", InetSocketAddress.createUnresolved("server-" + SESSION.toUpperCase() + ".gaius-local", 25565), false);
        expect("non-hex session id", InetSocketAddress.createUnresolved("server-" + "g".repeat(32) + ".gaius-local", 25565), false);
        expect("other suffix", InetSocketAddress.createUnresolved("server-" + SESSION + ".example.com", 25565), false);
        expect("prefix only", InetSocketAddress.createUnresolved("server-" + SESSION, 25565), false);
        expect("loopback socket", new InetSocketAddress("127.0.0.1", 25565), false);
        expect("any address", new InetSocketAddress(25565), false);
        expect("no address", null, false);
        System.out.println("HOST_LOGIN_COMPRESSION_OK checks=" + checks);
    }
}
