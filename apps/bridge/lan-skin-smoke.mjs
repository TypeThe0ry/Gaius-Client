import { readFile } from "node:fs/promises";
import { parseConnectRequest } from "./dist/policy.js";

const descriptor = {
  uuid: "00000000000040008000000000000001",
  username: "GuestSkin",
  value: Buffer.from(JSON.stringify({textures:{SKIN:{url:"data:image/png;base64,AA=="}}})).toString("base64"),
  signature: "",
};

const parsed = parseConnectRequest(JSON.stringify({
  type: "connect",
  host: "client-0123456789abcdef0123456789abcdef.gaius-local",
  port: 25565,
  skinDescriptor: descriptor,
}));
if (parsed.skinDescriptor?.uuid !== descriptor.uuid ||
    parsed.skinDescriptor?.username !== descriptor.username ||
    parsed.skinDescriptor?.value !== descriptor.value) {
  throw new Error("LAN connect policy did not preserve the bounded skin descriptor");
}
const hostParsed = parseConnectRequest(JSON.stringify({
  type: "connect",
  host: "server-0123456789abcdef0123456789abcdef.gaius-local",
  port: 25565,
  skinDescriptor: {...descriptor, username: "HostSkin"},
}));
if (hostParsed.skinDescriptor?.username !== "HostSkin") {
  throw new Error("LAN connect policy rejected the host skin descriptor");
}

for (const invalid of [
  {...descriptor, uuid: "bad"},
  {...descriptor, username: "x".repeat(17)},
  {...descriptor, value: "x".repeat(16385)},
  {...descriptor, signature: "x".repeat(16385)},
]) {
  let rejected = false;
  try {
    parseConnectRequest(JSON.stringify({
      type: "connect",
      host: "client-0123456789abcdef0123456789abcdef.gaius-local",
      port: 25565,
      skinDescriptor: invalid,
    }));
  } catch {
    rejected = true;
  }
  if (!rejected) throw new Error("Invalid LAN skin descriptor was accepted");
}

const bridge = await readFile(new URL("./dist/main.js", import.meta.url), "utf8");
const channel = await readFile(new URL("../../port/overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java", import.meta.url), "utf8");
const profile = await readFile(new URL("../../port/src/main/java/dev/gaius/browser/BrowserSkinProfile.java", import.meta.url), "utf8");
const client = await readFile(new URL("../../port/src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java", import.meta.url), "utf8");
const worker = await readFile(new URL("../../port/web/singleplayer/server-worker-bootstrap.js", import.meta.url), "utf8");
for (const marker of [
  "skinDescriptor: request.skinDescriptor",
  'type: "skin"',
  "session.client.skinDescriptor",
  "session.server.skinDescriptor",
]) {
  if (!bridge.includes(marker)) throw new Error(`Relay LAN skin forwarding marker missing: ${marker}`);
}
for (const marker of [
  "if (skin) control.skinDescriptor = skin",
  "acceptRemoteSkinDescriptor(message.skinDescriptor)",
  "__gaiusRemoteSkinDescriptors",
  "localSkinDescriptor(role)",
  "role === 'server'",
  "localTunnelRole(entry.host) === 'client'",
]) {
  if (!channel.includes(marker)) throw new Error(`Browser LAN skin marker missing: ${marker}`);
}
if (!profile.includes("__gaiusRemoteSkinDescriptors") ||
    !profile.includes("remoteKey = profileUuid + ':' + profileName") ||
    !profile.includes("descriptorValue(profileUuid, profileName)")) {
  throw new Error("Server profile lookup does not consume remote LAN skins");
}
if (!client.includes("skinDescriptor: descriptor") ||
    !worker.includes("__gaiusLanSkinDescriptor")) {
  throw new Error("Host LAN skin descriptor was not propagated into the server worker");
}

console.log(JSON.stringify({
  ok: true,
  descriptorBytes: descriptor.value.length,
  invalidCases: 4,
  relayForwarding: true,
  hostSkinForwarding: true,
  serverUuidLookup: true,
}));
