# Multiplayer acceptance harnesses

`minecraft-263-multiplayer-cdp.mjs` drives one browser page through the local
RelayNode and covers join, PLAY soak, rendering, keepalive telemetry, and
leave/rejoin.

`minecraft-263-two-client-cdp.mjs` is the two-client acceptance. It starts, all on
127.0.0.1, a vanilla 26.3 server (from `SERVER_JAR`, own directory under `OUT`), the
local RelayNode (`apps/bridge/dist/main.js`), a static server for `DIST`, and one
headless Chrome with two isolated browser contexts (`Target.createBrowserContext`),
one page and one player name each. It checks: both boot and join, chat A->B and B->A
(received as the client's `[CHAT]` console line and in the server log), A places and
breaks a block while B's crosshair state (`__gaiusMinecraftState.hit`) shows both
changes, both move for `SOAK_MS` (default 60 s, positions compared with the server's
`data get entity`), A leaves while B stays connected, A rejoins and chats, relay
write-error counters, uncaught exceptions, loopback-only traffic, and screenshots.

```
DIST=<site dir> SERVER_JAR=<server.jar> OUT=<evidence dir> GAIUS_CHROME_BIN=<chrome> \
  node port/scripts/minecraft-263-two-client-cdp.mjs
```

`GAIUS_CHROME_BIN` is the Chrome executable and `JAVA` the java command for the vanilla
server (default `java`). If your setup has a machine job runner that limits concurrent
browser runs, start the same command through it.

Server-console commands (`fill`, `give`, `tp`) are used only to set the scene (a
platform high above the terrain, a gold block in A's hand, both players aimed at the
same block from opposite sides). The place/break are real mouse clicks and the chat is
typed through the real chat screen. The driver never contacts public servers (Chrome runs
with a closed proxy and the report lists every blocked non-loopback attempt the page made).

`lan-invite-two-context-cdp.mjs` checks Open to LAN the way two people use it: in one
headless Chrome with two isolated browser contexts, A creates a world and opens it to LAN,
B opens the invite link A was shown. It checks chat both ways, each player's movement as the
other side's server sees it, a block A places and B breaks (real clicks, each side sees the
other's change), B leaving with Disconnect and rejoining with Multiplayer > Direct Connect
and the invite address, B's tab closing without leaving and a new tab rejoining through the
link, and B being told when A saves and quits. `PAGE=portable PORTABLE=<Gaius-<profile>.html>`
tests the single-file page over `file://`, `PAGE=site DIST=<site dir>` the site over
`http://127.0.0.1:8781`. By default (`RELAY=local`) it starts the RelayNode from
`apps/bridge` on loopback (run `npm ci` there once), offers it as the page's built-in relay
and keeps Chrome off every other host; `RELAY=public` uses the page's own relay list.

```
PAGE=portable PORTABLE=<Gaius-26.3.html> OUT=<evidence dir> node port/scripts/lan-invite-two-context-cdp.mjs
PAGE=site DIST=<site dir> OUT=<evidence dir> node port/scripts/lan-invite-two-context-cdp.mjs
```
