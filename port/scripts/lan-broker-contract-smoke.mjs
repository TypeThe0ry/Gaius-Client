import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";
import {patcherSource} from "./patcher-sources.mjs";
import {loadProfiles, sourceFile} from "../tools/sourcesets/sourcesets.mjs";

const root = new URL("../..", import.meta.url);
const source = async relative => readFile(new URL(relative, root), "utf8");

// MinecraftClientPatcher with its domain classes (port/tools/patchkit/README.md, "Domains").
const listenerPatcher = patcherSource("MinecraftClientPatcher");
const nettyPatcher = await source("port/tools/src/main/java/dev/gaius/tools/NettyBrowserPatcher.java");
const server = await source("port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java");
const lan = await source("port/src/main/java/dev/gaius/browser/BrowserLanSession.java");
const singleplayer = await source("port/src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java");
const launcher = await source("port/web/launcher/index.template.html");
const channel = await source("port/overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java");
const worker = await source("port/web/singleplayer/server-worker-bootstrap.js");
const skin = await source("port/src/main/java/dev/gaius/browser/BrowserSkinProfile.java");
const relayPolicy = await source("apps/bridge/dist/policy.js");
const relay = await source("apps/bridge/dist/main.js");

assert.match(listenerPatcher, /openAdditionalBrowserConnection/);
assert.match(listenerPatcher, /openAdditionalBrowserConnection",\s*"\(Ljava\/lang\/String;\)V/);
assert.match(listenerPatcher, /browserInstance/);
assert.match(listenerPatcher, /BrowserWebSocketChannel/);
// The injected LAN opener intentionally uses the standard Bootstrap.group path;
// gaiusGroup remains an optional Netty patch for other call sites.
assert.match(nettyPatcher, /gaiusGroup/);
assert.match(server, /openLanServerConnection/);
assert.doesNotMatch(server, /Class\.forName\(/);
assert.match(server, /ServerConnectionListener\.openAdditionalBrowserConnection/);
assert.match(server, /ServerConnectionListener\.openAdditionalBrowserConnection\(host\)/);
assert.match(server, /@JSExport\s+public static boolean openLanServerConnection/);
assert.match(server, /BrowserWebSocketChannel\.beginRelayOnlySession\(sessionId\)/);
assert.match(server, /BrowserWebSocketChannel\.endRelayOnlySession\(sessionId\)/);
assert.match(server, /max-players=8/);
assert.match(lan, /UUID\.randomUUID\(\)/);
assert.match(lan, /BrowserSingleplayerClient\.requestLanServerConnection\(brokerSessionId\)/);
// BrowserLanSession is one source for every profile (no copy in a source set): the invite's profile
// fallback is the running client's own version id, never a version literal, and the pause-menu
// column span comes from the profile's VersionOps.
assert.match(lan, /publishLanInvite\(brokerSessionId, clientVersionId\(\)\)/);
assert.match(lan, /private static native void publishLanInvite\(String brokerSessionId, String clientVersionId\)/);
assert.match(lan, /SharedConstants\.getCurrentVersion\(\)\.id\(\)/);
assert.match(lan, /VersionOps\.INSTANCE\.pauseMenuLanButtonColumns\(\)/);
assert.doesNotMatch(lan, /__gaiusProfileId \|\| '\d/, "no fixed profile id as the invite fallback");
assert.match(lan, /__gaiusOpenToLan/);
assert.match(lan, /brokerSessionId/);
for (const profile of loadProfiles()) {
  assert.equal(sourceFile(profile, "src", "dev/gaius/browser/BrowserLanSession.java")?.origin, "shared",
    `profile ${profile.id} compiles the shared BrowserLanSession, not a source set copy`);
}
assert.match(launcher, /brokerSessionId/);
assert.match(launcher, /client-" \+ brokerSessionId \+ "\.gaius-local:25565/);
// A LAN invite is handed to the Worker through its dedicated field. Normal multiplayer must
// never receive a LAN-only relay through __gaiusBridgeUrl.
assert.match(launcher, /const lanGuest = \/\^client-\[a-f0-9\]\{32\}/);
assert.match(launcher, /window\.__gaiusRelays && typeof window\.__gaiusRelays\.lanInviteRelay === "function"/);
assert.match(launcher, /window\.__gaiusLanRelay = String\(lanRelay\)\.trim\(\)/);
assert.doesNotMatch(launcher, /window\.__gaiusBridgeUrl = String\(linkRelay\)\.trim\(\)/);
assert.match(channel, /continue through the relay/);
assert.match(channel, /localGeneration = sessionId === null \? '' : localWorkerGeneration\(sessionId\)/);
assert.match(channel, /__gaiusRelayOnlySessions/);
assert.match(channel, /!relayOnly &&\s+ownsLocalWorkerSession\(sessionId\)/);
assert.match(channel, /bridgeUrls\(\[\], entry\)/);
assert.match(channel, /globalThis\.__gaiusLanRelay/);
assert.match(worker, /message\.type === "lan-open"/);
assert.match(worker, /root\.__gaiusLanRelay = normalizeLanRelay\(message\.lanRelay\)/);
assert.match(worker, /openLanServerConnection\(brokerSessionId\)/);
assert.match(singleplayer, /lanRelay:/);
assert.match(singleplayer, /hasReadyWorker/);
assert.match(singleplayer, /__gaiusServerReady/);
// Host-side handoff: the Open-to-LAN action must send the selected LAN relay to the Worker
// when it opens the dedicated lan-server channel.
{
  const method = "postLanServerConnectionRequest";
  const methodIndex = singleplayer.indexOf(`private static native boolean ${method}(`);
  const bodyStart = singleplayer.lastIndexOf("script = \"\"\"", methodIndex);
  const bodyEnd = singleplayer.indexOf("\"\"\")", bodyStart);
  const body = singleplayer.slice(bodyStart + "script = \"\"\"".length, bodyEnd);
  let posted;
  const worker = {
    __gaiusServerReady: true,
    __gaiusClientAttached: true,
    postMessage(message) { posted = message; }
  };
  const context = vm.createContext({
    __gaiusSingleplayerWorkers: new Map([["worker", worker]]),
    __gaiusRelays: {lanInviteRelay: () => "wss://lan.example/tunnel"},
    __gaiusSkinDescriptor: null,
  });
  const ok = vm.runInContext(`(function(brokerSessionId, workerSessionId) {${body}\n})`, context)(
    "a".repeat(32), "worker");
  assert.equal(ok, true);
  assert.equal(posted.type, "lan-open");
  assert.equal(posted.lanRelay, "wss://lan.example/tunnel");
}
// The LAN relay is the user's LAN relay, else the page's best enabled built-in relay. The host's
// server Worker cannot read window.__gaiusBridgeUrls or localStorage, so an empty answer left a
// portable page's LAN server on the local :8080 default and no guest could pair with it.
{
  const start = launcher.indexOf("window.__gaiusRelays = (function () {");
  const end = launcher.indexOf("})();", start);
  assert.ok(start > 0 && end > start, "launcher __gaiusRelays block");
  const block = launcher.slice(start, end + "})();".length);
  const relaysFor = ({builtIns, saved = [], disabled = []}) => {
    const store = {"gaius.bridgeNodes": JSON.stringify(saved), "gaius.disabledRelays": JSON.stringify(disabled)};
    const window = {__gaiusBridgeUrls: builtIns};
    const context = vm.createContext({
      window, URL, location: {href: "file:///Users/someone/Gaius-26.3.html"},
      localStorage: {getItem: key => store[key] ?? null, setItem: (key, value) => { store[key] = String(value); }},
    });
    vm.runInContext(block, context);
    return window.__gaiusRelays;
  };
  const shipped = [{url: "wss://low.example/tunnel", priority: 10}, {url: "wss://high.example/tunnel", priority: 100}];
  assert.equal(relaysFor({builtIns: shipped}).lanInviteRelay(), "wss://high.example/tunnel");
  assert.equal(relaysFor({builtIns: shipped, disabled: ["wss://high.example/tunnel"]}).lanInviteRelay(),
    "wss://low.example/tunnel");
  assert.equal(relaysFor({builtIns: shipped, saved: [{url: "wss://mine.example/tunnel", use: "lan"}]}).lanInviteRelay(),
    "wss://mine.example/tunnel");
  assert.equal(relaysFor({builtIns: shipped, saved: [{url: "wss://mp.example/tunnel", use: "multiplayer"}]}).lanInviteRelay(),
    "wss://high.example/tunnel");
  assert.equal(relaysFor({builtIns: undefined}).lanInviteRelay(), "");
}
assert.match(relayPolicy, /parseSkinDescriptor/);
assert.match(relayPolicy, /16384/);
assert.match(relayPolicy, /client\|server\|lan-server/);
assert.match(relay, /session\.client\.skinDescriptor/);
assert.match(relay, /session\.server\.skinDescriptor/);
assert.match(relay, /type: "skin"/);
assert.match(relay, /host: `client-\$\{request\.sessionId\}\.gaius-local`/);
assert.match(relay, /host: session\.server\.host/);
assert.match(channel, /control\.skinDescriptor = skin/);
assert.match(channel, /acceptRemoteSkinDescriptor\(message\.skinDescriptor\)/);
assert.match(channel, /localSkinDescriptor\(role\)/);
assert.match(channel, /role === 'server'/);
assert.match(skin, /__gaiusRemoteSkinDescriptors/);
assert.match(skin, /remoteKey = profileUuid \+ ':' \+ profileName/);
assert.match(skin, /descriptorValue\(profileUuid, profileName\)/);
assert.match(skin, /class MutablePropertyMap extends PropertyMap/);
assert.match(skin, /protected Multimap<String, Property> delegate/);
assert.match(skin, /new GameProfile\(profile\.id\(\), profile\.name\(\), properties\)/);
assert.match(worker, /__gaiusLanSkinDescriptor/);

// Relay candidates must come from bridgeUrls(); an empty list left LAN joiners
// with relayAttempts=0 and stuck on the connect screen.
assert.match(channel, /appendRelayCandidates\(entry, bridgeUrls\(\[\], entry\)\)/);
assert.match(channel, /appendRelayCandidates\(entry, bridgeUrls\(discovered, entry\)\)/);
// Offline players need distinct UUIDs; a shared placeholder made every joiner
// kick the host ("logged in from another location").
assert.match(launcher, /function offlineGaiusPlayerUuid\(name\)/);
assert.match(launcher, /"OfflinePlayer:" \+ name/);
assert.match(launcher, /uuid === LEGACY_OFFLINE_GAIUS_UUID/);
// Uploaded data:image/png skins must survive authlib's domain check and be
// rendered for remote players, not only for the local player.
const authlibPatcher = await source("port/tools/src/main/java/dev/gaius/tools/AuthlibBrowserPatcher.java");
const uploadedSkin = await source("port/src/main/java/dev/gaius/browser/BrowserUploadedSkin.java");
assert.match(authlibPatcher, /allowUploadedSkinTextures\(sessionNode\)/);
assert.match(authlibPatcher, /"gaiusAllowedTextureUrl"/);
assert.match(listenerPatcher, /patchSkinManagerUploadedSkinSecurity\(args\[0\]/);
assert.match(listenerPatcher, /"skinSignatureState"/);
assert.match(uploadedSkin, /textures\.cape\(\) == null && textures\.elytra\(\) == null/);

console.log("relay-backed LAN broker contract passed");
