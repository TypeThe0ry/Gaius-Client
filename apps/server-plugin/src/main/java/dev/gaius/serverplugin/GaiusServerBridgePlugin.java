package dev.gaius.serverplugin;

import java.net.InetSocketAddress;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

public final class GaiusServerBridgePlugin extends JavaPlugin {
    private GaiusWebSocketGateway gateway;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String listenAddress = getConfig().getString("listen-address", "0.0.0.0");
        int webSocketPort = getConfig().getInt("websocket-port", 8081);
        String minecraftHost = getConfig().getString("minecraft-host", "127.0.0.1");
        int connectTimeoutMillis = getConfig().getInt("connect-timeout-millis", 5000);
        int maximumConnections = getConfig().getInt("maximum-connections", 256);
        int maximumFrameBytes = getConfig().getInt("maximum-frame-bytes", 16 * 1024 * 1024);
        boolean proxyKeepAlives = getConfig().getBoolean("proxy-keepalives", true);
        String accessToken = getConfig().getString("access-token", "");
        List<String> allowedOrigins = getConfig().getStringList("allowed-origins");

        if (webSocketPort < 1 || webSocketPort > 65535) {
            throw new IllegalArgumentException("websocket-port must be between 1 and 65535");
        }
        gateway = new GaiusWebSocketGateway(
                new InetSocketAddress(listenAddress, webSocketPort),
                minecraftHost,
                getServer().getPort(),
                Math.max(250, connectTimeoutMillis),
                Math.max(1, maximumConnections),
                Math.max(1024, maximumFrameBytes),
                proxyKeepAlives,
                accessToken == null ? "" : accessToken,
                allowedOrigins,
                getLogger());
        try {
            gateway.start();
            gateway.awaitReady();
        } catch (RuntimeException | Error exception) {
            GaiusWebSocketGateway failedGateway = gateway;
            gateway = null;
            try {
                failedGateway.shutdown();
            } catch (Throwable cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        getLogger().info(
                "Gaius WebSocket transport listening on " + listenAddress + ":" + gateway.getPort()
                        + " and forwarding only to " + minecraftHost + ":" + getServer().getPort());
    }

    @Override
    public void onDisable() {
        if (gateway == null) {
            return;
        }
        gateway.shutdown();
        gateway = null;
    }
}
