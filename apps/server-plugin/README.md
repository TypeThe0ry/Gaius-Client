# GaiusServerBridge

Optional Paper 1.21.11 plugin that exposes the Gaius WebSocket tunnel protocol
next to a Java server. It forwards only to the same server's local Minecraft
port, so it replaces an external Gaius Bridge without becoming a general TCP
proxy. Normal Java clients continue to use the ordinary Minecraft port.

Build with Java 21:

```sh
GAIUS_VERSION_PROFILE_PATH=versions/1.21.11.json \
  ../../port/mvnw -B -ntp -f pom.xml package
```

Copy `target/gaius-server-plugin-<version>.jar` into the Paper server's
`plugins/` directory. The default WebSocket endpoint is
`ws://server.example:8081/tunnel`. Use a TLS reverse proxy and `wss://` when the
browser page is served over HTTPS. Set `access-token` and restrict
`allowed-origins` for a public deployment.

Configure the browser launcher with the plugin endpoint as its Bridge URL. The
wire protocol is identical to `apps/bridge/dist/main.js`, so the browser can
fall back to an external relay when the plugin is absent.

After accepting a `connect` control message, the plugin immediately returns a
`connecting` message with `targetConnectTimeoutMs`. This lets the browser keep
the direct route alive for the configured TCP budget without making an absent
plugin delay RelayNode fallback. Closing the browser WebSocket also closes a
TCP socket that is still connecting, so a failed direct probe cannot leave a
background connection attempt behind.

`proxy-keepalives` is enabled by default. It replies only to the exact
unencrypted configuration-phase keepalive frame (packet id 4) while the browser is busy applying a server
resource pack, preventing a local Paper backend from timing out the tunnel.
All other frames remain transparent; set it to `false` to disable this behavior.
