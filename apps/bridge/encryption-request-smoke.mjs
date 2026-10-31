import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { once } from "node:events";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { WebSocket } from "./node_modules/ws/wrapper.mjs";
import {
  MINECRAFT_PROFILES,
} from "./dist/protocol.js";
import { encodePacket } from "../../packages/protocol/dist/framing.js";
import { encodeVarInt } from "../../packages/protocol/dist/varint.js";
import { encodeString } from "../../packages/protocol/dist/binary.js";

// This is deliberately a local RelayNode/fixture smoke.  It never contacts a
// public endpoint or starts a Minecraft server; the fixture only exercises the
// framing and phase transitions which protect the transparent encrypted path.
const directory = fileURLToPath(new URL(".", import.meta.url));
const origin = "http://127.0.0.1:8781";
const token = "encryption-request-smoke-token";
const profiles = [...MINECRAFT_PROFILES];
assert.ok(profiles.length > 0, "the smoke needs at least one supported RelayNode profile");
const bridgeSource = await readFile(new URL("./dist/main.js", import.meta.url), "utf8");

assert.match(
  bridgeSource,
  /isLoginEncryptionRequest\(frame, headerBytes, protocolPhase, profile\)/,
  "encryption detector must receive phase and negotiated profile",
);
assert.match(
  bridgeSource,
  /protocolPhase !== "login"/,
  "encryption detector must be LOGIN-only",
);
assert.match(
  bridgeSource,
  /profile\.login\.clientboundEncryptionRequest/,
  "encryption detector must use the profile login packet id",
);
assert.match(
  bridgeSource,
  /complete\.remainder\.byteLength !== 0/,
  "encryption detector must reject a partial outer frame",
);

const fixture = createServer();
const fixtureStates = new Set();
let latestFixtureState;
fixture.on("connection", (socket) => {
  socket.setNoDelay(true);
  const state = {
    socket,
    data: Buffer.alloc(0),
    closed: false,
  };
  latestFixtureState = state;
  fixtureStates.add(state);
  socket.on("data", (chunk) => {
    state.data = Buffer.concat([state.data, chunk]);
  });
  socket.on("close", () => {
    state.closed = true;
  });
});
await once(fixture.listen(0, "127.0.0.1"), "listening");
const fixturePort = fixture.address().port;

const bridgePort = await reservePort();
const bridge = spawn(process.execPath, ["dist/main.js"], {
  cwd: directory,
  env: {
    ...process.env,
    NODE_ENV: "test",
    GAIUS_BRIDGE_HOST: "127.0.0.1",
    GAIUS_BRIDGE_PORT: String(bridgePort),
    GAIUS_ALLOWED_ORIGINS: origin,
    GAIUS_ALLOWED_HOSTS: "127.0.0.1",
    GAIUS_BRIDGE_TOKEN: token,
    GAIUS_PROXY_KEEPALIVES: "1",
    GAIUS_IDLE_TIMEOUT_MS: "60000",
  },
  stdio: ["ignore", "pipe", "pipe"],
  windowsHide: true,
});
let bridgeOutput = "";
bridge.stdout.setEncoding("utf8");
bridge.stderr.setEncoding("utf8");
bridge.stdout.on("data", (chunk) => { bridgeOutput += chunk; });
bridge.stderr.on("data", (chunk) => { bridgeOutput += chunk; });

try {
  await waitFor(
    () => bridgeOutput.includes("Gaius translator node listening"),
    "RelayNode startup",
  );
  const cases = [];
  for (const profile of profiles) {
    cases.push(await runLoginEncryptionCase(profile));
    cases.push(await runPlayPacketIdOneCase(profile));
    cases.push(await runMalformedCase(profile));
  }
  console.log("RelayNode encryption-request smoke passed", JSON.stringify({
    profiles: profiles.map((profile) => ({
      name: profile.name,
      protocolVersion: profile.protocolVersion,
      clientboundEncryptionRequest: profile.login.clientboundEncryptionRequest,
    })),
    cases,
    guarantees: [
      "LOGIN + complete outer frame + profile packet id only",
      "PLAY packet id 0x01 remains transparent",
      "fragmented and malformed input fail closed",
    ],
  }));
}
finally {
  for (const state of fixtureStates) {
    state.socket.destroy();
  }
  await new Promise((resolve) => fixture.close(resolve));
  if (bridge.exitCode === null) {
    bridge.kill();
    await once(bridge, "exit").catch(() => {});
  }
}

async function runLoginEncryptionCase(profile) {
  const tunnel = await openTunnel(profile);
  const { socket, serverFrames, fixtureState } = tunnel;
  try {
    const request = encodeEncryptionRequest(profile);
    const beforeRuntime = await fetchRuntime();
    const beforeTransitions = beforeRuntime.runtime.keepAliveProxy
      .encryptionOpaqueTransitions;
    const split = Math.max(1, Math.floor(request.byteLength / 2));
    // The first half is intentionally an incomplete outer frame.  It must not
    // arm the opaque transition or be forwarded as a guessed packet.
    fixtureState.socket.write(request.subarray(0, split));
    await delay(35);
    assert.equal(
      serverFrames.some((frame) => frame.equals(request)),
      false,
      `${profile.name} incomplete encryption request was classified early`,
    );
    fixtureState.socket.write(request.subarray(split));
    await waitFor(
      () => serverFrames.some((frame) => frame.equals(request)),
      `${profile.name} complete fragmented encryption request forwarding`,
    );

    const opaqueResponse = Buffer.from([
      0xa5, 0x13, 0x00, 0xff, 0x7e, 0x44, 0x91, 0x02,
    ]);
    const beforeOpaque = fixtureState.data.byteLength;
    socket.send(opaqueResponse);
    await waitFor(
      () => indexOfFrom(fixtureState.data, opaqueResponse, beforeOpaque) >= 0,
      `${profile.name} opaque encryption response forwarding`,
    );
    const afterRuntime = await fetchRuntime();
    const delta = afterRuntime.runtime.keepAliveProxy.encryptionOpaqueTransitions -
      beforeTransitions;
    assert.equal(delta, 1, `${profile.name} valid LOGIN encryption request did not arm opaque mode`);
    return {
      profile: profile.name,
      requestBytes: request.byteLength,
      fragmented: true,
      encryptionOpaqueTransitionDelta: delta,
    };
  }
  finally {
    await closeTunnel(tunnel);
  }
}

async function runPlayPacketIdOneCase(profile) {
  const tunnel = await openTunnel(profile);
  const { socket, serverFrames, fixtureState } = tunnel;
  try {
    await enterPlay(tunnel, profile);
    // This is an ordinary uncompressed PLAY packet whose id happens to be 1.
    // The old first-byte detector treated it as an encryption request.
    const playPacketIdOne = Buffer.from(encodePacket(1, Buffer.from([0x42, 0x26])));
    fixtureState.socket.write(playPacketIdOne);
    await waitFor(
      () => serverFrames.some((frame) => frame.equals(playPacketIdOne)),
      `${profile.name} PLAY packet-id=0x01 forwarding`,
    );

    const beforeRuntime = await fetchRuntime();
    const beforeTransitions = beforeRuntime.runtime.keepAliveProxy
      .encryptionOpaqueTransitions;
    // A normal client tick after the PLAY packet must remain parsed.  A false
    // encryption classification would clear minecraftProfile here.
    const tick = encodeZeroCompressedPacket(profile.play.serverboundClientTickEnd);
    const beforeTick = fixtureState.data.byteLength;
    socket.send(tick);
    await waitFor(
      () => indexOfFrom(fixtureState.data, tick, beforeTick) >= 0,
      `${profile.name} PLAY tick forwarding after packet-id=0x01`,
    );
    await delay(35);

    // KeepAlive proxying is the observable proof that the profile/parser was
    // not disabled by the unrelated PLAY id 1.
    const keepAlive = encodeZeroCompressedPacket(
      profile.play.clientboundKeepAlive,
      Buffer.from("0000000000000001", "hex"),
    );
    const beforeKeepAlive = fixtureState.data.byteLength;
    fixtureState.socket.write(keepAlive);
    const responsePrefix = Buffer.from([
      0x0a,
      0x00,
      profile.play.serverboundKeepAlive,
    ]);
    await waitFor(
      () => indexOfFrom(fixtureState.data, responsePrefix, beforeKeepAlive) >= 0,
      `${profile.name} PLAY keepalive remained proxied after packet-id=0x01`,
    );
    const afterRuntime = await fetchRuntime();
    const delta = afterRuntime.runtime.keepAliveProxy.encryptionOpaqueTransitions -
      beforeTransitions;
    assert.equal(delta, 0, `${profile.name} PLAY packet-id=0x01 falsely entered encryption opaque mode`);
    return {
      profile: profile.name,
      playPacketId: 1,
      encryptionOpaqueTransitionDelta: delta,
      keepAliveResponse: true,
    };
  }
  finally {
    await closeTunnel(tunnel);
  }
}

async function runMalformedCase(profile) {
  const tunnel = await openTunnel(profile);
  const { socket, serverFrames, fixtureState } = tunnel;
  try {
    // Five continuation bytes are a malformed outer VarInt.  Send it in two
    // TCP chunks to exercise the accumulator's incomplete->opaque transition.
    const malformedOuter = Buffer.from([0x80, 0x80, 0x80, 0x80, 0x80]);
    const beforeRuntime = await fetchRuntime();
    const beforeTransitions = beforeRuntime.runtime.keepAliveProxy
      .encryptionOpaqueTransitions;
    fixtureState.socket.write(malformedOuter.subarray(0, 2));
    await delay(20);
    fixtureState.socket.write(malformedOuter.subarray(2));
    await waitFor(
      () => containsConcatenated(serverFrames, malformedOuter),
      `${profile.name} malformed outer frame forwarding`,
    );
    const opaque = Buffer.from([0x01, 0x90, 0x00, 0xde, 0xad]);
    const beforeOpaque = fixtureState.data.byteLength;
    socket.send(opaque);
    await waitFor(
      () => indexOfFrom(fixtureState.data, opaque, beforeOpaque) >= 0,
      `${profile.name} malformed path transparent client forwarding`,
    );
    const afterRuntime = await fetchRuntime();
    const delta = afterRuntime.runtime.keepAliveProxy.encryptionOpaqueTransitions -
      beforeTransitions;
    assert.equal(delta, 0, `${profile.name} malformed input armed encryption opaque mode`);
    return {
      profile: profile.name,
      malformedOuterBytes: malformedOuter.byteLength,
      encryptionOpaqueTransitionDelta: delta,
      transparentForwarding: true,
    };
  }
  finally {
    await closeTunnel(tunnel);
  }
}

async function openTunnel(profile) {
  const stateBefore = latestFixtureState;
  const socket = new WebSocket(`ws://127.0.0.1:${bridgePort}/tunnel`, {
    headers: { origin },
  });
  const controls = [];
  const serverFrames = [];
  socket.on("message", (data, binary) => {
    if (binary) {
      serverFrames.push(Buffer.from(data));
    }
    else {
      controls.push(JSON.parse(data.toString("utf8")));
    }
  });
  await once(socket, "open");
  socket.send(JSON.stringify({
    type: "connect",
    host: "127.0.0.1",
    port: fixturePort,
    token,
  }));
  await waitFor(
    () => controls.some((message) => message.type === "connected"),
    `${profile.name} RelayNode TCP connection`,
  );
  await waitFor(
    () => latestFixtureState !== undefined && latestFixtureState !== stateBefore,
    `${profile.name} fixture TCP connection`,
  );
  const fixtureState = latestFixtureState;
  const handshake = encodeHandshake(profile, fixturePort);
  socket.send(handshake);
  await waitFor(
    () => indexOfFrom(fixtureState.data, handshake, 0) >= 0,
    `${profile.name} handshake forwarding`,
  );
  return { socket, controls, serverFrames, fixtureState };
}

async function enterPlay(tunnel, profile) {
  const loginAcknowledged = Buffer.from(encodePacket(
    profile.login.serverboundLoginAcknowledged,
  ));
  const configurationFinished = Buffer.from(encodePacket(
    profile.configuration.serverboundFinish,
  ));
  tunnel.socket.send(loginAcknowledged);
  await waitFor(
    () => indexOfFrom(tunnel.fixtureState.data, loginAcknowledged, 0) >= 0,
    `${profile.name} login acknowledgement forwarding`,
  );
  await delay(40);
  tunnel.socket.send(configurationFinished);
  await waitFor(
    () => indexOfFrom(tunnel.fixtureState.data, configurationFinished, 0) >= 0,
    `${profile.name} configuration finish forwarding`,
  );
  // The parser continuation is intentionally asynchronous and process-wide;
  // leave one bounded turn for the phase watermark before server PLAY bytes.
  await delay(60);
}

async function closeTunnel(tunnel) {
  if (tunnel.socket.readyState === WebSocket.OPEN ||
      tunnel.socket.readyState === WebSocket.CONNECTING) {
    tunnel.socket.close(1000, "encryption request smoke complete");
    await once(tunnel.socket, "close").catch(() => {});
  }
  tunnel.fixtureState.socket.destroy();
  await delay(25);
}

function encodeHandshake(profile, port) {
  return Buffer.from(encodePacket(0, Buffer.concat([
    Buffer.from(encodeVarInt(profile.protocolVersion)),
    Buffer.from(encodeString("encryption-request-smoke.test")),
    Buffer.from([(port >>> 8) & 0xff, port & 0xff]),
    Buffer.from(encodeVarInt(2)),
  ])));
}

function encodeEncryptionRequest(profile) {
  // ClientboundHello/Encryption Request fields: server id string, DER public
  // key byte-array, verify-token byte-array, shouldAuthenticate boolean. The
  // detector intentionally does not retain or parse these opaque bytes.
  const payload = Buffer.concat([
    Buffer.from(encodeVarInt(0)),
    Buffer.from(encodeVarInt(1)),
    Buffer.from([0x01]),
    Buffer.from(encodeVarInt(1)),
    Buffer.from([0x02]),
    Buffer.from([0x01]),
  ]);
  return Buffer.from(encodePacket(profile.login.clientboundEncryptionRequest, payload));
}

function encodeZeroCompressedPacket(packetId, payload = Buffer.alloc(0)) {
  const packet = Buffer.concat([
    Buffer.from([0x00]),
    Buffer.from(encodeVarInt(packetId)),
    Buffer.from(payload),
  ]);
  return Buffer.concat([
    Buffer.from(encodeVarInt(packet.byteLength)),
    packet,
  ]);
}

async function fetchRuntime() {
  const response = await fetch(`http://127.0.0.1:${bridgePort}/relay-node/v1.runtime`, {
    headers: { origin },
  });
  assert.equal(response.status, 200, "RelayNode runtime endpoint unavailable");
  const body = await response.json();
  assert.equal(body.ok, true);
  return body;
}

function containsConcatenated(frames, expected) {
  return frames.some((frame) => frame.equals(expected)) ||
    Buffer.concat(frames).includes(expected);
}

function indexOfFrom(buffer, needle, offset) {
  return buffer.indexOf(needle, offset);
}

async function reservePort() {
  const server = createServer();
  await once(server.listen(0, "127.0.0.1"), "listening");
  const port = server.address().port;
  await once(server.close(), "close");
  return port;
}

async function waitFor(predicate, label, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (!(await predicate())) {
    if (Date.now() >= deadline) {
      throw new Error(`${label} timed out`);
    }
    await delay(10);
  }
}
