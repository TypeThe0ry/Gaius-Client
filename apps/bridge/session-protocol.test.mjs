import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {mkdirSync, mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";
import test from "node:test";
import {parseConnectRequest} from "./dist/policy.js";
import {MINECRAFT_PROFILES, resolveMinecraftProfile} from "./dist/protocol.js";
import {LOGIN_LAYOUT_VERSION, RELAY_KEYS} from "./dist/protocol-schema.js";
import {createSessionProtocolDeclaration, MAXIMUM_CONNECT_CONTROL_BYTES, MAXIMUM_SESSION_PROTOCOL_BYTES,
    parseSessionProtocolDeclaration, sessionProtocolAcknowledgement} from "./dist/session-protocol.js";
import {loadVersionProfiles} from "./scripts/gen-protocol-ids.mjs";
import {buildRelayProtocolDeclaration, relayProtocolScript, stageRelayProtocol} from "../../port/scripts/build-relay-protocol.mjs";
import {encodeVarInt} from "../../packages/protocol/dist/varint.js";
import {encodeString} from "../../packages/protocol/dist/binary.js";

const root = fileURLToPath(new URL("../../", import.meta.url));
const profiles = loadVersionProfiles(root);
const table = JSON.parse(readFileSync(new URL("./protocols/" + profiles[0].protocolVersion + ".json", import.meta.url), "utf8"));
const declaration = createSessionProtocolDeclaration(table);
const control = protocolTable => JSON.stringify({type: "connect", host: "example.invalid", port: 25565,
    ...(protocolTable === undefined ? {} : {protocolTable})});
const hash = text => createHash("sha256").update(text, "utf8").digest("hex");
const signedText = text => ({formatVersion: 1, sha256: hash(text), table: text});
const signed = value => signedText(JSON.stringify(value));
const clone = () => JSON.parse(JSON.stringify(table));
const scratchRoot = process.env.GAIUS_PROTOGEN_TEST_OUT || tmpdir();
mkdirSync(scratchRoot, {recursive: true});

for (const profile of profiles) {
    test(profile.id + " build-time table roundtrips through the real connect parser", () => {
        const built = buildRelayProtocolDeclaration(root, profile.id);
        const parsed = parseConnectRequest(control(built));
        assert.equal(parsed.protocolTable.profile.protocolVersion, profile.protocolVersion);
        assert.equal(parsed.protocolTable.sha256, hash(built.table));
        assert.ok(Object.isFrozen(parsed.protocolTable));
        assert.ok(Object.isFrozen(parsed.protocolTable.profile.play));
        assert.equal(Object.values(RELAY_KEYS).reduce((sum, keys) => sum + Object.keys(keys).length, 0), 41);
        assert.deepEqual(sessionProtocolAcknowledgement(parsed.protocolTable), {
            formatVersion: 1, sha256: built.sha256, protocolVersion: profile.protocolVersion});
    });
}

test("report provenance rejects coercible non-string digests", () => {
    for (const profile of profiles) {
        const original = JSON.parse(buildRelayProtocolDeclaration(root, profile.id).table);
        for (const field of Object.keys(original.source)) {
            const malformed = JSON.parse(JSON.stringify(original));
            malformed.source[field] = [original.source[field]];
            assert.throws(() => parseSessionProtocolDeclaration(signed(malformed)), /provenance/);
        }
    }
});

test("legacy clients keep their original request shape", () => {
    assert.deepEqual(parseConnectRequest(control()), {type: "connect", host: "example.invalid", port: 25565});
    assert.equal(parseSessionProtocolDeclaration(undefined), undefined);
    assert.equal(sessionProtocolAcknowledgement(undefined), undefined);
});

for (const [label, value, message] of [
    ["null", null, /fields/],
    ["array", [], /fields/],
    ["missing hash", {formatVersion: 1, table: declaration.table}, /fields/],
    ["extra envelope field", {...declaration, extra: true}, /fields/],
    ["unknown envelope layout", {...declaration, formatVersion: 2}, /format/],
    ["non-string table", {...declaration, table: table}, /format/],
    ["malformed hash", {...declaration, sha256: "bad"}, /format/],
    ["hash mismatch", {...declaration, sha256: "0".repeat(64)}, /hash mismatch/],
    ["tampered bytes", {...declaration, table: declaration.table + " "}, /hash mismatch/],
    ["malformed JSON", signedText("{"), /JSON/],
    ["null table", signed(null), /fields/],
    ["array table", signed([]), /fields/],
    ["unknown schema", signed({...table, schemaVersion: 2}), /layout/],
    ["unknown login layout", signed({...table, loginLayoutVersion: LOGIN_LAYOUT_VERSION + 1}), /layout/],
    ["extra table field", signed({...table, extra: true}), /fields/],
    ["oversized name", signed({...table, name: "a".repeat(129)}), /name/],
    ["missing provenance", signed({...table, source: null}), /provenance/],
    ["forged provenance kind", signed({...table, source: {...table.source, kind: "other"}}), /provenance/],
    ["out-of-range protocol", signed({...table, protocolVersion: 0x80000000}), /Invalid relay/],
    ["negative protocol", signed({...table, protocolVersion: -1}), /Invalid relay/],
]) {
    test("connect rejects " + label, () => assert.throws(() => parseConnectRequest(control(value)), message));
}

for (const [label, mutate] of [
    ["missing packet", changed => delete changed.play.clientboundKeepAlive],
    ["extra packet", changed => changed.play.extra = 1],
    ["negative id", changed => changed.play.clientboundKeepAlive = -1],
    ["fractional id", changed => changed.play.clientboundKeepAlive = 1.5],
    ["string id", changed => changed.play.clientboundKeepAlive = "1"],
    ["multi-byte id", changed => changed.play.clientboundKeepAlive = 128],
    ["duplicate directional id", changed => changed.play.clientboundKeepAlive = changed.play.clientboundPing],
]) {
    test("connect rejects " + label, () => {
        const changed = clone();
        mutate(changed);
        assert.throws(() => parseConnectRequest(control(signed(changed))), /Invalid|Unsupported|Duplicate/);
    });
}

test("byte budgets apply before parsing and include UTF-8 and the entire control frame", () => {
    const padded = declaration.table + " ".repeat(MAXIMUM_SESSION_PROTOCOL_BYTES - Buffer.byteLength(declaration.table));
    assert.ok(parseSessionProtocolDeclaration(signedText(padded)));
    assert.throws(() => parseSessionProtocolDeclaration(signedText(padded + " ")), /16 KiB/);
    assert.throws(() => parseSessionProtocolDeclaration(signedText("界".repeat(MAXIMUM_SESSION_PROTOCOL_BYTES / 2))), /16 KiB/);
    assert.throws(() => parseConnectRequest(" ".repeat(MAXIMUM_CONNECT_CONTROL_BYTES + 1)), /64 KiB/);
    assert.throws(() => parseConnectRequest(control(declaration) + " ".repeat(MAXIMUM_CONNECT_CONTROL_BYTES)), /64 KiB/);
    assert.throws(() => parseConnectRequest("界".repeat(MAXIMUM_CONNECT_CONTROL_BYTES / 2)), /64 KiB/);
});

const relaySource = readFileSync(new URL("./dist/main.js", import.meta.url), "utf8");
const helperNames = ["readMinecraftFrame", "decodeMinecraftVarInt", "minecraftPacketId",
    "inspectMinecraftHandshake", "isLoginEncryptionRequest", "proxyVanillaKeepAlive", "isPayloadlessPacket"];
const helperSource = helperNames.map(name => {
    const start = relaySource.indexOf("function " + name + "(");
    const end = relaySource.indexOf("\nfunction ", start + 1);
    assert.ok(start >= 0 && end > start);
    return relaySource.slice(start, end);
}).join("\n");
const relay = vm.createContext({Buffer, resolveMinecraftProfile,
    config: {maximumFrameBytes: 16384, proxyKeepAlives: true}, traceTunnel: false,
    incrementKeepAliveProxyCounter() {}, recordProxiedKeepAlive() {}});
vm.runInContext(helperSource, relay);
const handshake = protocolVersion => {
    const body = Buffer.concat([Buffer.from([0]), encodeVarInt(protocolVersion), encodeString("example.invalid"),
        Buffer.from([0x63, 0xdd, 2])]);
    return Buffer.concat([encodeVarInt(body.length), body]);
};

test("a new protocol works without a bundled table and stays isolated from other sessions", () => {
    const protocolVersion = Math.max(...profiles.map(profile => profile.protocolVersion)) + 1;
    const changed = {...clone(), name: "next-profile", protocolVersion};
    changed.play.clientboundKeepAlive = 126;
    changed.play.serverboundKeepAlive = 127;
    const declared = parseConnectRequest(control(signed(changed))).protocolTable;
    const before = MINECRAFT_PROFILES.length;
    const bytes = handshake(protocolVersion);
    assert.equal(relay.inspectMinecraftHandshake(bytes).handshake.profile, undefined);
    assert.equal(relay.inspectMinecraftHandshake(bytes, declared).handshake.profile, declared.profile);
    for (let split = 1; split < bytes.length; split++) {
        assert.equal(relay.inspectMinecraftHandshake(bytes.subarray(0, split), declared).state, "incomplete");
    }
    const replies = [];
    const packet = Buffer.alloc(11, 7);
    packet[0] = 10;
    packet[1] = 0;
    packet[2] = changed.play.clientboundKeepAlive;
    const original = Buffer.from(packet);
    assert.equal(relay.proxyVanillaKeepAlive({write(bytes) {replies.push(bytes); return true;}},
        packet, "play", declared.profile), true);
    assert.equal(replies[0][2], changed.play.serverboundKeepAlive);
    assert.deepEqual(replies[0].subarray(3), original.subarray(3));
    assert.deepEqual(packet, original);
    assert.equal(relay.isLoginEncryptionRequest(Buffer.from([3, 0, changed.login.clientboundEncryptionRequest, 7]),
        1, "login", declared.profile), true);
    assert.equal(relay.inspectMinecraftHandshake(bytes).handshake.profile, undefined);
    assert.equal(resolveMinecraftProfile(protocolVersion), undefined);
    assert.equal(MINECRAFT_PROFILES.length, before);
    assert.equal(relay.inspectMinecraftHandshake(handshake(table.protocolVersion), declared).handshake.profile, undefined);
});

test("a declaration overrides a bundled table only in its own session", () => {
    const changed = clone();
    changed.play.clientboundKeepAlive = 127;
    const declared = parseSessionProtocolDeclaration(signed(changed));
    const bytes = handshake(table.protocolVersion);
    assert.equal(relay.inspectMinecraftHandshake(bytes, declared).handshake.profile.play.clientboundKeepAlive, 127);
    assert.equal(relay.inspectMinecraftHandshake(bytes).handshake.profile, resolveMinecraftProfile(table.protocolVersion));
    assert.equal(resolveMinecraftProfile(table.protocolVersion).play.clientboundKeepAlive, table.play.clientboundKeepAlive);
    assert.match(relaySource, /inspectMinecraftHandshake\(minecraftHandshakeBuffer, sessionProtocolDeclaration\)/);
    assert.match(relaySource, /sessionProtocolDeclaration = request\.protocolTable/);
    assert.match(relaySource, /protocolTable: sessionProtocolAcknowledgement\(sessionProtocolDeclaration\)/);
});

test("build staging is deterministic, profile-bound and safe inside an inline script", () => {
    const directory = mkdtempSync(join(scratchRoot, "session-build-"));
    const built = stageRelayProtocol(root, profiles[0].id, directory);
    assert.deepEqual(built, declaration);
    const script = readFileSync(join(directory, "relay-protocol.js"), "utf8");
    assert.equal(script, relayProtocolScript(built));
    const context = vm.createContext({});
    vm.runInContext(script, context);
    assert.equal(context.__gaiusRelayProtocolDeclaration.sha256, declaration.sha256);
    assert.ok(Object.isFrozen(context.__gaiusRelayProtocolDeclaration));
    assert.equal(Object.getOwnPropertyDescriptor(context, "__gaiusRelayProtocolDeclaration").writable, false);
    const hostile = {...clone(), name: "</script><script>bad</script>\u2028\u2029"};
    const escaped = relayProtocolScript(createSessionProtocolDeclaration(hostile));
    assert.doesNotMatch(escaped, /<\/script>/);
    const escapedContext = vm.createContext({});
    vm.runInContext(escaped, escapedContext);
    assert.equal(JSON.parse(escapedContext.__gaiusRelayProtocolDeclaration.table).name, hostile.name);
    assert.throws(() => buildRelayProtocolDeclaration(root, "missing-profile"), /Unknown profile/);
    mkdirSync(join(directory, "port", "versions"), {recursive: true});
    mkdirSync(join(directory, "apps", "bridge", "protocols"), {recursive: true});
    writeFileSync(join(directory, "port", "versions", profiles[0].id + ".json"), JSON.stringify(profiles[0]));
    const changed = {...clone(), protocolVersion: table.protocolVersion + 1};
    const path = join(directory, "apps", "bridge", "protocols", table.protocolVersion + ".json");
    writeFileSync(path, JSON.stringify(changed));
    assert.throws(() => buildRelayProtocolDeclaration(directory, profiles[0].id), /selected profile/);
    changed.protocolVersion = table.protocolVersion;
    changed.source[changed.source.kind === "vanilla-client-reports" ? "expectedServerSha1" : "serverSha1"] = "0".repeat(40);
    writeFileSync(path, JSON.stringify(changed));
    assert.throws(() => buildRelayProtocolDeclaration(directory, profiles[0].id), /selected profile/);
});

function relayConnect(text, binary = false) {
    const start = relaySource.indexOf('    webSocket.once("message", (rawData, isBinary) => {');
    const end = relaySource.indexOf('    webSocket.once("close",', start);
    assert.ok(start >= 0 && end > start);
    const opened = [];
    const closed = [];
    let firstMessage;
    const context = vm.createContext({Buffer, MAXIMUM_CONNECT_CONTROL_BYTES, parseConnectRequest,
        sessionProtocolAcknowledgement, sessionProtocolDeclaration: undefined, tunnelRequest: undefined,
        lastActivity: 0, config: {allowedHosts: ["example.invalid"]},
        console: {error() {}}, toBuffer(data) {return Buffer.from(data);},
        tokenMatches() {return true;}, parseLocalTunnelHost() {return undefined;},
        isHostAllowed() {return true;}, tunnelConnectAbortController: {signal: {}},
        webSocket: {once(name, callback) {if (name === "message") firstMessage = callback;}, on() {}, send() {}},
        closeBoth(code, reason) {closed.push({code, reason});},
        openTcpTunnel(request) {opened.push(request); return {then() {}};}});
    vm.runInContext(relaySource.slice(start, end), context);
    firstMessage(Buffer.from(text), binary);
    return {context, opened, closed};
}

test("the real first-frame handler rejects invalid declarations before any target attempt", () => {
    for (const text of [control(null), control({...declaration, sha256: "0".repeat(64)}),
        control(signed({...table, loginLayoutVersion: LOGIN_LAYOUT_VERSION + 1})),
        " ".repeat(MAXIMUM_CONNECT_CONTROL_BYTES + 1)]) {
        const result = relayConnect(text);
        assert.equal(result.opened.length, 0);
        assert.deepEqual(result.closed.map(close => close.code), [1007]);
    }
    assert.equal(relayConnect(control(declaration), true).opened.length, 0);
    const result = relayConnect(control(declaration));
    assert.deepEqual(result.closed, []);
    assert.equal(result.opened.length, 1);
    assert.equal(result.context.sessionProtocolDeclaration.sha256, declaration.sha256);
    const legacy = relayConnect(control());
    assert.equal(legacy.opened.length, 1);
    assert.equal(legacy.context.sessionProtocolDeclaration, undefined);
});

const channel = readFileSync(join(root, "port/overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java"), "utf8");
const onOpenStart = channel.indexOf("ws.onopen = function()");
const onMessageStart = channel.indexOf("ws.onmessage = function(event)", onOpenStart);
const onErrorStart = channel.indexOf("ws.onerror = function()", onMessageStart);
assert.ok(onOpenStart > 0 && onMessageStart > onOpenStart && onErrorStart > onMessageStart);
const browserHandlers = channel.slice(onOpenStart, onErrorStart).replaceAll("\\\\", "\\");

function browserSession({built = declaration, direct = false, local = false} = {}) {
    const controls = [];
    const errors = [];
    const entry = {host: "example.invalid", port: 25565, targetKey: "example.invalid:25565",
        webSocketGeneration: 1, closed: false, connected: false};
    const context = vm.createContext({entry, generation: 1, candidate: {direct, url: "wss://example.invalid/tunnel"},
        ws: {}, queueControl(entry, message) {controls.push(message);},
        localTunnelRole() {return local ? "client" : null;}, localSkinDescriptor() {return undefined;},
        bridgeToken() {return undefined;}, recordConnectPhase() {}, armCandidateTimeout() {},
        relayTunnelConnectTimeout() {return 10000;}, clearCandidateTimeout() {},
        normalizedTargetKey(host, port) {return host + ":" + port;},
        forgetDirectPluginUnavailable() {}, recordRelayNodeSuccess() {}, requestFlush() {},
        state: {stats: {directConnected: 0}, failInbound(entry, error) {errors.push(error); entry.closed = true;}}});
    if (built !== undefined) vm.runInContext(relayProtocolScript(built), context);
    vm.runInContext(browserHandlers, context);
    context.ws.onopen();
    return {controls, errors, context, entry, connected(ack) {
        context.ws.onmessage({data: JSON.stringify({type: "connected", host: entry.host, port: entry.port,
            ...(ack === undefined ? {} : {protocolTable: ack})})});
    }};
}

test("real browser onopen sends the embedded table that the relay validates", () => {
    const session = browserSession();
    const parsed = parseConnectRequest(JSON.stringify(session.controls[0]));
    assert.equal(parsed.protocolTable.sha256, declaration.sha256);
    session.connected(sessionProtocolAcknowledgement(parsed.protocolTable));
    assert.equal(session.entry.connected, true);
    assert.deepEqual(session.errors, []);
    session.context.ws.onopen();
    assert.equal(session.controls[1].protocolTable, session.controls[0].protocolTable);
});

test("old relay acknowledgement and old browser control remain compatible", () => {
    const session = browserSession();
    session.connected();
    assert.equal(session.entry.connected, true);
    const legacy = browserSession({built: null});
    assert.equal(legacy.controls[0].protocolTable, undefined);
    legacy.connected();
    assert.equal(legacy.entry.connected, true);
});

test("direct-plugin and LAN controls do not acquire a session table", () => {
    assert.equal(browserSession({direct: true}).controls[0].protocolTable, undefined);
    assert.equal(browserSession({local: true}).controls[0].protocolTable, undefined);
});

test("browser refuses malformed or mismatched table acknowledgements", () => {
    for (const ack of [null, {}, {formatVersion: 2, sha256: declaration.sha256},
        {formatVersion: 1, sha256: "0".repeat(64)}]) {
        const session = browserSession();
        session.connected(ack);
        assert.equal(session.entry.connected, false);
        assert.equal(session.errors.length, 1);
        assert.match(session.errors[0], /acknowledgement mismatch/);
    }
});

test("tunnel trace lines escape line breaks from declared names and payload channels", () => {
    const start = relaySource.indexOf("function traceCustomPayload(");
    const end = relaySource.indexOf("\nfunction relayRuntimeSnapshot(", start);
    assert.ok(start >= 0 && end > start);
    const lines = [];
    const context = vm.createContext({Buffer, traceTunnel: true, console: {info(line) {lines.push(line);}}});
    vm.runInContext(relaySource.slice(start, end), context);
    const forged = "x\n[Gaius tunnel trace] forged\r\u2028\u2029\u0085\u001b[2J";
    const declared = parseSessionProtocolDeclaration(signed({...clone(), name: forged}));
    assert.equal(declared.profile.name, forged);
    context.traceTunnelEvent(`enabled framed keepalive proxy for Minecraft ${declared.profile.name}`);
    const channel = Buffer.from("a\nb", "utf8");
    const payload = Buffer.concat([Buffer.from([channel.length]), channel, Buffer.from("data")]);
    const frame = Buffer.concat([Buffer.from([payload.length + 1, declared.profile.play.clientboundCustomPayload]), payload]);
    context.traceCustomPayload(frame, 1, "server", true, declared.profile);
    assert.equal(lines.length, 2);
    for (const line of lines) {
        assert.doesNotMatch(line, /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/u);
        assert.equal(line.split("[Gaius tunnel trace]").length - 1, line.includes("forged") ? 2 : 1);
        assert.ok(line.startsWith("[Gaius tunnel trace] "));
    }
    assert.ok(lines[0].endsWith(String.raw`Minecraft x\u000a[Gaius tunnel trace] forged\u000d\u2028\u2029\u0085\u001b[2J`));
    assert.ok(lines[1].endsWith(String.raw`channel=a\u000ab preview=data`));
});
