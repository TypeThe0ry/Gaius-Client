import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import fs from "node:fs";
import vm from "node:vm";

// Exercise tracked launcher source even in pointer-only CI checkouts.
const htmlPath = new URL("../web/launcher/index.template.html", import.meta.url);
const html = fs.readFileSync(htmlPath, "utf8");
const blockStart = html.indexOf("    function createGaiusProxyUrl(target, kind)");
const blockEnd = html.indexOf("\n\n    const gaiusDiagMode", blockStart);
assert.ok(blockStart >= 0 && blockEnd > blockStart, "session launcher block was not found");
const launcher = html.slice(blockStart, blockEnd);

// Independent reference for Java's UUID.nameUUIDFromBytes("OfflinePlayer:" + name).
function vanillaOfflineUuid(name) {
  const digest = createHash("md5").update("OfflinePlayer:" + name, "latin1").digest();
  digest[6] = (digest[6] & 0x0f) | 0x30;
  digest[8] = (digest[8] & 0x3f) | 0x80;
  return digest.toString("hex");
}

async function runScenario({
  search = "",
  stored,
  injected,
  fetchImpl,
  rememberedName,
  customSkin,
  customSkinModel,
  page = "http://127.0.0.1:8781/dist/index.html",
  bridgeUrls,
  bridgeUrl,
  bridgeToken,
  confirmAnswer,
  savedRelays,
}) {
  const pageUrl = new URL(page);
  const confirms = [];
  const storage = new Map();
  if (stored !== undefined) storage.set("gaius.session", JSON.stringify(stored));
  const remembered = new Map();
  if (rememberedName !== undefined) remembered.set("gaius.playerName", rememberedName);
  if (customSkin !== undefined) remembered.set("gaius.customSkin", customSkin);
  if (customSkinModel !== undefined) remembered.set("gaius.customSkinModel", customSkinModel);
  if (savedRelays !== undefined) remembered.set("gaius.bridgeNodes", JSON.stringify(savedRelays));
  const historyCalls = [];
  let replacedLocation;
  const location = {
    href: `${page}${search}`,
    origin: pageUrl.origin,
    protocol: pageUrl.protocol,
    hostname: pageUrl.hostname,
    search,
    replace(value) {
      replacedLocation = String(value);
    },
    reload() {
      replacedLocation = "reload";
    },
  };
  const window = {
    innerWidth: 1280,
    innerHeight: 720,
    __gaiusSession: injected,
    __gaiusBridgeUrls: bridgeUrls,
    __gaiusBridgeUrl: bridgeUrl,
    __gaiusBridgeToken: bridgeToken,
    addEventListener() {},
  };
  if (confirmAnswer !== undefined) {
    window.confirm = message => {
      confirms.push(String(message));
      return confirmAnswer;
    };
  }
  const context = {
    URL,
    URLSearchParams,
    Uint8Array,
    Uint32Array,
    DataView,
    performance,
    crypto: {
      getRandomValues(array) {
        array[0] = 4242;
        return array;
      },
    },
    btoa(value) { return Buffer.from(value, "binary").toString("base64"); },
    console,
    performance,
    setTimeout,
    fetch: fetchImpl ?? (() => {
      throw new Error("unexpected fetch");
    }),
    history: {
      state: null,
      replaceState(...args) {
        historyCalls.push(args);
      },
    },
    location,
    sessionStorage: {
      getItem(key) {
        return storage.get(key) ?? null;
      },
      setItem(key, value) {
        storage.set(key, String(value));
      },
      removeItem(key) {
        storage.delete(key);
      },
    },
    localStorage: {
      getItem(key) {
        return remembered.get(key) ?? null;
      },
      setItem(key, value) {
        remembered.set(key, String(value));
      },
      removeItem(key) {
        remembered.delete(key);
      },
    },
    bootBrand: {hidden: false},
    bootProgress: {hidden: false},
    bootProgressText: {hidden: false},
    statusBox: {hidden: false},
    showLauncherDetails: false,
    GAIUS_CUSTOM_SKIN_KEY: "gaius.customSkin",
    GAIUS_CUSTOM_SKIN_MODEL_KEY: "gaius.customSkinModel",
    GAIUS_CUSTOM_SKIN_MAX_DATA_URL: 12000,
    bootProgressValue: 0,
    setBootProgress() {},
    requestAnimationFrame(callback) {
      callback(0);
      return 1;
    },
    setInterval(callback) {
      callback();
      return 1;
    },
    window,
    document: {
      activeElement: null,
      contains() { return false; },
    },
  };
  window.window = window;
  vm.runInNewContext(
    // The page defines urlParams and unsupportedBrowser before this block.
    "const urlParams = new URLSearchParams(location.search);\n"
      + "const unsupportedBrowser = window.__gaiusUnsupportedBrowser;\n" + launcher,
    context,
    {filename: "gaius-session-launcher.js"},
  );
  const args = await window.__gaiusDefaultArgsPromise;
  return {
    args,
    window,
    context,
    confirms,
    historyCalls,
    remembered,
    mode: window.__gaiusSessionMode,
    firstRun: window.__gaiusProfile.firstRun,
    get rememberedName() { return remembered.get("gaius.playerName"); },
    get customSkin() { return remembered.get("gaius.customSkin"); },
    get customSkinModel() { return remembered.get("gaius.customSkinModel"); },
    get stored() {
      return storage.has("gaius.session") ? JSON.parse(storage.get("gaius.session")) : undefined;
    },
    get skinDescriptor() { return window.__gaiusSkinDescriptor; },
    get replacedLocation() { return replacedLocation; },
  };
}

const argValue = (args, flag) => args[args.indexOf(flag) + 1];
// Results created inside the vm context have that realm's prototypes.
const plain = value => JSON.parse(JSON.stringify(value));
const texturesOf = descriptor =>
  JSON.parse(Buffer.from(descriptor.value, "base64").toString("utf8")).textures;

// First launch: no HTML gate. The game starts with a default name and the
// title screen opens the in-game Edit Profile screen once (firstRun).
const firstLaunch = await runScenario({});
assert.equal(firstLaunch.mode, "offline");
assert.equal(firstLaunch.firstRun, true);
assert.equal(argValue(firstLaunch.args, "--username"), "Player5242");
assert.equal(firstLaunch.rememberedName, "Player5242");
assert.equal(argValue(firstLaunch.args, "--uuid"), vanillaOfflineUuid("Player5242"));
assert.ok(firstLaunch.args.includes("--offlineDeveloperMode"));

// Later launches reuse the remembered name without prompting.
const offline = await runScenario({rememberedName: "GaiusPlayer"});
assert.equal(offline.mode, "offline");
assert.equal(offline.firstRun, false);
assert.equal(offline.rememberedName, "GaiusPlayer");
assert.equal(argValue(offline.args, "--username"), "GaiusPlayer");
// Vanilla offline UUID: UUID.nameUUIDFromBytes("OfflinePlayer:GaiusPlayer").
assert.equal(argValue(offline.args, "--uuid"), "9ced09a81fbd32dc90098ded575e01e2");
assert.equal(vanillaOfflineUuid("GaiusPlayer"), "9ced09a81fbd32dc90098ded575e01e2");

// Sessions stored by older launchers carry the shared placeholder UUID, which
// made LAN joiners collide with the host. It must be replaced by the name UUID.
const legacyOffline = await runScenario({
  stored: {username: "GaiusPlayer", uuid: "00000000000040008000000000000001"},
});
assert.equal(legacyOffline.firstRun, false);
assert.equal(argValue(legacyOffline.args, "--uuid"), "9ced09a81fbd32dc90098ded575e01e2");
assert.equal(legacyOffline.stored.uuid, "9ced09a81fbd32dc90098ded575e01e2");

const skinDataUrl = "data:image/png;base64,iVBORw0KGgo=";
const customSkin = await runScenario({rememberedName: "SkinPlayer", customSkin: skinDataUrl});
assert.equal(customSkin.skinDescriptor.username, "SkinPlayer");
assert.equal(customSkin.skinDescriptor.uuid, "1019b3f952e938e2a60f305b66bffab5");
assert.equal(customSkin.skinDescriptor.signature, "");
assert.deepEqual(texturesOf(customSkin.skinDescriptor).SKIN, {url: skinDataUrl});

const slimSkin = await runScenario({
  rememberedName: "SkinPlayer",
  customSkin: skinDataUrl,
  customSkinModel: "slim",
});
assert.deepEqual(texturesOf(slimSkin.skinDescriptor).SKIN,
  {url: skinDataUrl, metadata: {model: "slim"}});

// The in-game Edit Profile screen (BrowserProfile.apply) switches name and
// skin in the running page: no reload, updated session and LAN skin descriptor.
const profile = offline.window.__gaiusProfile;
assert.deepEqual(plain(profile.apply("NewName_1", skinDataUrl, true)),
  {ok: true, uuid: vanillaOfflineUuid("NewName_1")});
assert.equal(offline.rememberedName, "NewName_1");
assert.equal(offline.customSkin, skinDataUrl);
assert.equal(offline.customSkinModel, "slim");
assert.equal(offline.stored.username, "NewName_1");
assert.equal(offline.stored.uuid, vanillaOfflineUuid("NewName_1"));
assert.equal(offline.skinDescriptor.username, "NewName_1");
assert.equal(offline.skinDescriptor.uuid, vanillaOfflineUuid("NewName_1"));
assert.deepEqual(texturesOf(offline.skinDescriptor).SKIN,
  {url: skinDataUrl, metadata: {model: "slim"}});
assert.equal(profile.savedSkin(), skinDataUrl);
assert.equal(profile.savedSkinModel(), "slim");
// Choosing the default skin clears the stored skin and the descriptor.
assert.equal(profile.apply("NewName_1", "", false).ok, true);
assert.equal(offline.customSkin, undefined);
assert.equal(offline.customSkinModel, undefined);
assert.equal(offline.skinDescriptor, null);
// Invalid names and non-PNG skins are rejected without touching the identity.
assert.equal(profile.apply("bad name!", "", false).ok, false);
assert.equal(profile.apply("Name", "data:text/html;base64,PGI+", false).ok, false);
assert.equal(offline.rememberedName, "NewName_1");
assert.equal(offline.stored.username, "NewName_1");
assert.equal(offline.replacedLocation, undefined);
assert.equal(profile.texturesValue("", false), "");

let profileRequests = 0;
const online = await runScenario({
  search: "?accessToken=secret-token&server=example.org",
  async fetchImpl(url, init) {
    profileRequests++;
    const proxy = new URL(url);
    assert.equal(proxy.pathname, "/proxy/auth");
    const target = proxy.searchParams.get("url");
    if (target === "https://api.minecraftservices.com/minecraft/profile") {
      assert.equal(init.headers.authorization, "Bearer secret-token");
    } else {
      assert.equal(
        target,
        "https://sessionserver.mojang.com/session/minecraft/profile/00112233445566778899aabbccddeeff?unsigned=false",
      );
      assert.equal(init.credentials, "omit");
    }
    return {
      ok: true,
      async json() {
        return target === "https://api.minecraftservices.com/minecraft/profile"
          ? {name: "OnlinePlayer", id: "00112233445566778899aabbccddeeff"}
          : {
              id: "00112233445566778899aabbccddeeff",
              name: "OnlinePlayer",
              properties: [{name: "textures", value: "signed-textures", signature: "sig"}],
            };
      },
    };
  },
});
assert.equal(profileRequests, 2);
assert.equal(online.mode, "online");
assert.equal(online.firstRun, false);
assert.ok(!online.args.includes("--offlineDeveloperMode"));
assert.equal(argValue(online.args, "--username"), "OnlinePlayer");
assert.equal(argValue(online.args, "--uuid"), "00112233445566778899aabbccddeeff");
assert.equal(argValue(online.args, "--accessToken"), "secret-token");
assert.equal(argValue(online.args, "--quickPlayMultiplayer"), "example.org");
assert.equal(online.stored.username, "OnlinePlayer");
assert.equal(online.stored.uuid, "00112233445566778899aabbccddeeff");
assert.equal(online.stored.skinDescriptor.username, "OnlinePlayer");
assert.equal(online.stored.skinDescriptor.uuid, "00112233445566778899aabbccddeeff");
assert.equal(online.historyCalls.length, 1);
assert.ok(!String(online.historyCalls[0][2]).includes("accessToken"));
// Online accounts keep their account name; only the skin may change.
assert.deepEqual(plain(online.window.__gaiusProfile.apply("Renamed", skinDataUrl, false)),
  {ok: true, uuid: "00112233445566778899aabbccddeeff"});
assert.equal(online.stored.username, "OnlinePlayer");
assert.equal(online.skinDescriptor.username, "OnlinePlayer");
assert.equal(online.rememberedName, undefined);
assert.equal(online.window.__gaiusProfile.apply("Renamed", "", false).ok, true);
assert.equal(online.skinDescriptor.value, "signed-textures");

let unexpectedFetches = 0;
const complete = await runScenario({
  stored: {
    accessToken: "stored-token",
    username: "StoredPlayer",
    uuid: "ffeeddccbbaa99887766554433221100",
    skinDescriptor: {
      uuid: "ffeeddccbbaa99887766554433221100",
      username: "StoredPlayer",
      value: "cached-textures",
      signature: "cached-signature",
    },
  },
  fetchImpl() {
    unexpectedFetches++;
    throw new Error("complete sessions must not request the profile");
  },
});
assert.equal(unexpectedFetches, 0);
assert.equal(complete.mode, "online");
assert.equal(complete.firstRun, false);
assert.equal(argValue(complete.args, "--username"), "StoredPlayer");

const queriedIdentity = await runScenario({
  search: "?username=QueryPlayer&uuid=00112233445566778899aabbccddeeff" +
    "&accessToken=query-token&xuid=42&clientId=gaius-client&server=example.org",
});
assert.equal(queriedIdentity.mode, "online");
assert.equal(argValue(queriedIdentity.args, "--username"), "QueryPlayer");
assert.equal(queriedIdentity.historyCalls.length, 1);
const scrubbedIdentityUrl = new URL(
  String(queriedIdentity.historyCalls[0][2]),
  "http://127.0.0.1:8781",
);
assert.equal(scrubbedIdentityUrl.searchParams.get("server"), "example.org");
for (const key of ["username", "uuid", "accessToken", "xuid", "clientId"]) {
  assert.equal(scrubbedIdentityUrl.searchParams.has(key), false);
}

await assert.rejects(
  runScenario({
    search: "?accessToken=invalid-profile-token",
    async fetchImpl() {
      return {
        ok: true,
        async json() {
          return {name: "invalid name", id: "not-a-uuid"};
        },
      };
    },
  }),
  /valid Java profile/,
);

// ?bridge= / ?relay= come from a link anyone can craft. On a hosted page, a bridge the site
// does not name itself must not receive the Minecraft access token (or the page's own bridge
// token) unless the player confirms that host; declining drops the link's bridge.
const hostedPage = "https://play.example/Gaius/26.3/";
const registry = [{url: "wss://relay.example/tunnel", priority: 10}];
function profileServer(seen) {
  return async (url, init) => {
    const proxy = new URL(url);
    seen.push({origin: proxy.origin, authorization: init && init.headers && init.headers.authorization,
      token: proxy.searchParams.get("token")});
    const target = proxy.searchParams.get("url");
    return {
      ok: true,
      async json() {
        return target === "https://api.minecraftservices.com/minecraft/profile"
          ? {name: "OnlinePlayer", id: "00112233445566778899aabbccddeeff"}
          : {id: "00112233445566778899aabbccddeeff", name: "OnlinePlayer", properties: []};
      },
    };
  };
}
const bearerOrigins = seen => seen.filter(entry => entry.authorization).map(entry => entry.origin);

const declinedSeen = [];
const declined = await runScenario({
  page: hostedPage, bridgeUrls: registry, confirmAnswer: false,
  search: "?accessToken=secret-token&bridge=https://collector.example/&bridgeToken=link-token",
  fetchImpl: profileServer(declinedSeen),
});
assert.equal(declined.confirms.length, 1);
assert.match(declined.confirms[0], /collector\.example/);
assert.deepEqual(bearerOrigins(declinedSeen), ["https://relay.example"],
  "a declined link bridge never sees the access token");
assert.ok(declinedSeen.every(entry => entry.origin !== "https://collector.example"));
const declinedUrl = new URL(String(declined.historyCalls[0][2]), hostedPage);
for (const key of ["bridge", "relay", "bridgeToken", "relayToken"]) {
  assert.equal(declinedUrl.searchParams.has(key), false, `${key} is removed for the game runtime`);
}
assert.equal(argValue(declined.args, "--accessToken"), "secret-token");

const acceptedSeen = [];
const accepted = await runScenario({
  page: hostedPage, bridgeUrls: registry, confirmAnswer: true,
  search: "?accessToken=secret-token&bridge=https://collector.example/",
  fetchImpl: profileServer(acceptedSeen),
});
assert.equal(accepted.confirms.length, 1);
assert.deepEqual(bearerOrigins(acceptedSeen), ["https://collector.example"], "the player chose this bridge");

// Without a confirm dialog the link bridge is dropped as well.
const silentSeen = [];
await runScenario({
  page: hostedPage, bridgeUrls: registry,
  search: "?accessToken=secret-token&relay=wss://collector.example/tunnel",
  fetchImpl: profileServer(silentSeen),
});
assert.deepEqual(bearerOrigins(silentSeen), ["https://relay.example"]);

// Bridges the site names itself, and a bridge on the player's own machine, need no question.
for (const [bridge, expected, extra] of [
  ["https://play.example/relay/", "https://play.example", {}],
  ["wss://relay.example/other", "https://relay.example", {}],
  ["https://own.example/", "https://own.example", {bridgeUrl: "https://own.example/"}],
  ["http://127.0.0.1:8080/", "http://127.0.0.1:8080", {}],
  ["http://localhost:8080/", "http://localhost:8080", {}],
  ["wss://mine.example/tunnel", "https://mine.example", {savedRelays: [{url: "wss://mine.example/tunnel", name: "Mine"}]}],
]) {
  const seen = [];
  const trusted = await runScenario({
    page: hostedPage, bridgeUrls: registry, confirmAnswer: false, ...extra,
    search: `?accessToken=secret-token&bridge=${encodeURIComponent(bridge)}`,
    fetchImpl: profileServer(seen),
  });
  assert.equal(trusted.confirms.length, 0, `${bridge} is not questioned`);
  assert.deepEqual(bearerOrigins(seen), [expected]);
}

// An offline player carries no credentials: a link bridge is used without a question.
const offlineLink = await runScenario({
  page: hostedPage, bridgeUrls: registry, confirmAnswer: false, rememberedName: "LinkPlayer",
  search: "?bridge=https://collector.example/",
});
assert.equal(offlineLink.confirms.length, 0);
assert.equal(new URL(offlineLink.context.createGaiusProxyUrl("https://example.org/", "auth")).origin,
  "https://collector.example");

// A relay token in an untrusted offline link is still a credential. It must be
// confirmed before use; declining drops both the relay and token before any
// request can be issued.
const offlineLinkTokenDeclined = await runScenario({
  page: hostedPage, bridgeUrls: registry, confirmAnswer: false, rememberedName: "LinkPlayer",
  search: "?relay=https://collector.example/&relayToken=link-secret",
});
assert.equal(offlineLinkTokenDeclined.confirms.length, 1);
assert.match(offlineLinkTokenDeclined.confirms[0], /relay credential carried by this link/);
assert.equal(new URL(offlineLinkTokenDeclined.context.createGaiusProxyUrl("https://example.org/", "auth")).origin,
  "https://relay.example");
assert.deepEqual(plain(offlineLinkTokenDeclined.context.createGaiusProxyHeaders()), {});
const declinedTokenUrl = new URL(String(offlineLinkTokenDeclined.historyCalls.at(-1)[2]), hostedPage);
for (const key of ["relay", "bridge", "relayToken", "bridgeToken"]) {
  assert.equal(declinedTokenUrl.searchParams.has(key), false, `${key} is removed for a declined token link`);
}

// Acceptance keeps the selected relay but moves the token to the header path
// and removes it from the address before the session can make a request.
const offlineLinkTokenAccepted = await runScenario({
  page: hostedPage, bridgeUrls: registry, confirmAnswer: true, rememberedName: "LinkPlayer",
  search: "?relay=https://collector.example/&relayToken=link-secret",
});
assert.equal(offlineLinkTokenAccepted.confirms.length, 1);
assert.equal(new URL(offlineLinkTokenAccepted.context.createGaiusProxyUrl("https://example.org/", "auth")).origin,
  "https://collector.example");
assert.deepEqual(plain(offlineLinkTokenAccepted.context.createGaiusProxyHeaders()),
  {"X-Gaius-Bridge-Token": "link-secret"});
const acceptedTokenUrl = new URL(String(offlineLinkTokenAccepted.historyCalls.at(-1)[2]), hostedPage);
assert.equal(acceptedTokenUrl.searchParams.has("relayToken"), false);
assert.equal(acceptedTokenUrl.searchParams.has("relay"), false);
// The page's own bridge token is a credential too.
const pageToken = await runScenario({
  page: hostedPage, bridgeUrls: registry, confirmAnswer: false, rememberedName: "LinkPlayer",
  bridgeToken: "page-secret", search: "?bridge=https://collector.example/",
});
assert.equal(pageToken.confirms.length, 1);
const pageTokenProxy = new URL(pageToken.context.createGaiusProxyUrl("https://example.org/", "auth"));
assert.equal(pageTokenProxy.origin, "https://relay.example");

// Fast is the default graphics preset: saved options still carrying the old
// Fancy default move to Fast exactly once, and a later Fancy choice sticks.
const presetStart = html.indexOf("    function migrateGaiusFastGraphicsDefault()");
const presetEnd = html.indexOf("\n\n    if (!unsupportedBrowser) (async () => {", presetStart);
assert.ok(presetStart >= 0 && presetEnd > presetStart, "graphics preset migration was not found");
function runPresetMigration(files, flags = new Map()) {
  const puts = [];
  const context = {
    atob: value => Buffer.from(value, "base64").toString("latin1"),
    btoa: value => Buffer.from(value, "latin1").toString("base64"),
    console,
    Uint8Array,
    localStorage: {
      getItem: key => flags.get(key) ?? null,
      setItem: (key, value) => flags.set(key, String(value)),
    },
    window: {
      __gaiusPersistentFiles: files,
      __gaiusFsPut(path, value) {
        files[path] = value;
        puts.push(path);
        return true;
      },
    },
  };
  const migrated = vm.runInNewContext(
    `${html.slice(presetStart, presetEnd)}\nmigrateGaiusFastGraphicsDefault();`, context);
  return {migrated, puts, flags};
}
const optionsText = text => Buffer.from(text, "latin1").toString("base64");
const fancyFiles = {"/gaius/options.txt": optionsText('version:4671\ngraphicsPreset:"fancy"\nrenderDistance:8\n')};
const presetRun = runPresetMigration(fancyFiles);
assert.equal(presetRun.migrated, true);
assert.equal(Buffer.from(fancyFiles["/gaius/options.txt"], "base64").toString("latin1"),
  'version:4671\ngraphicsPreset:"fast"\nrenderDistance:8\n');
const byteFiles = {"/gaius/options.txt": new Uint8Array(Buffer.from('graphicsPreset:"fancy"\r\n', "latin1"))};
assert.equal(runPresetMigration(byteFiles).migrated, true);
assert.equal(Buffer.from(byteFiles["/gaius/options.txt"], "base64").toString("latin1"), 'graphicsPreset:"fast"\n');
const chosenFancy = {"/gaius/options.txt": optionsText('graphicsPreset:"fancy"\n')};
assert.equal(runPresetMigration(chosenFancy, presetRun.flags).migrated, false);
assert.equal(Buffer.from(chosenFancy["/gaius/options.txt"], "base64").toString("latin1"), 'graphicsPreset:"fancy"\n');
const customPreset = {"/gaius/options.txt": optionsText('graphicsPreset:"custom"\n')};
assert.equal(runPresetMigration(customPreset).migrated, false);
const freshProfile = runPresetMigration({});
assert.equal(freshProfile.migrated, false);
assert.equal(freshProfile.flags.get("gaius.fastGraphicsDefault"), "1");

// Run the launcher's actual rAF observer: long gameplay stalls must contribute
// to the same samples used for average FPS, 1% low, and the longest frame.
const fpsStart = html.indexOf("requestAnimationFrame(function gaiusFpsTick(now)");
const fpsEnd = html.indexOf("\n\n    if (!unsupportedBrowser) setInterval(() => {", fpsStart);
assert.ok(fpsStart >= 0 && fpsEnd > fpsStart, "launcher FPS observer was not found");
let nextFrame;
const fpsWindow = {
  __gaiusFps: {frames: 0, lastSampleAt: 0},
  __gaiusMinecraftState: {level: true},
};
vm.runInNewContext(html.slice(fpsStart, fpsEnd), {
  window: fpsWindow,
  requestAnimationFrame(callback) { nextFrame = callback; },
  maybeDegradeResolutionForFps() {},
  Float32Array,
});
for (const at of [10, 26, 42, 6042]) nextFrame(at);
const fps = fpsWindow.__gaiusFps;
assert.deepEqual(Array.from(fps.rafFrameTimes.subarray(0, fps.rafFrameCount)), [16, 16, 6000]);
assert.equal(fps.rafLongestFrameMs, 6000);
assert.equal(fps.rafAverageFps, 0.5);
assert.equal(fps.rafOnePercentLow, 0.2);

// Menu time must not turn into a gameplay stall when another world starts.
fpsWindow.__gaiusMinecraftState.level = false;
nextFrame(12042);
assert.equal(fps.rafFrameCount, 3);
fpsWindow.__gaiusMinecraftState.level = true;
nextFrame(18042);
assert.equal(fps.rafFrameCount, 0);
assert.equal(fps.rafLongestFrameMs, 0);
for (let index = 1; index <= 4200; index++) nextFrame(18042 + index * 16);
assert.equal(fps.rafFrameCount, 4096);
assert.equal(fps.rafLongestFrameMs, 16);
assert.equal(fps.rafAverageFps, 62.5);
assert.equal(fps.rafOnePercentLow, 62.5);

console.log(JSON.stringify({
  multiSecondGameplayStallRetained: true,
  worldFrameSamplesReset: true,
  frameSampleStorageBounded: true,
  firstLaunchDefaultName: true,
  rememberedNameReused: true,
  profileResolution: true,
  completeSessionBypassesFetch: true,
  profileAppliedWithoutReload: true,
  slimSkinModel: true,
  fastGraphicsDefaultMigratedOnce: true,
  identityQueryScrubbedOnLaunch: true,
  invalidProfileRejected: true,
  accessTokenScrubbed: true,
  linkBridgeCredentialsGuarded: true,
}));
