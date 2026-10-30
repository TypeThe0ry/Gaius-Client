import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";
import test from "node:test";
import {decodeClientboundLoginDistances, loadMinecraftProfiles, MINECRAFT_PROFILES,
    resolveMinecraftProfile} from "./dist/protocol.js";
import {LOGIN_LAYOUT_VERSION, RELAY_KEYS, validateProtocolTable} from "./dist/protocol-schema.js";
import {canonicalJson, loadVersionProfiles, parseOptions, runGenerator, serializeTable,
    tableFromReport} from "./scripts/gen-protocol-ids.mjs";
import {encodeVarInt} from "../../packages/protocol/dist/varint.js";
import {encodeString} from "../../packages/protocol/dist/binary.js";

const root = fileURLToPath(new URL("../../", import.meta.url));
const baseline = JSON.parse(readFileSync(new URL("./test-fixtures/protocol-tables.json", import.meta.url), "utf8"));
const profiles = loadVersionProfiles(root);
const scratchRoot = process.env.GAIUS_PROTOGEN_TEST_OUT || tmpdir();
mkdirSync(scratchRoot, {recursive: true});
const tableFor = profile => JSON.parse(readFileSync(new URL(
    "./protocols/" + profile.protocolVersion + ".json", import.meta.url), "utf8"));
const scratch = () => mkdtempSync(join(scratchRoot, "protocol-unit-"));

function fixtureReport(contract) {
    const report = {};
    for (const [state, keys] of Object.entries(RELAY_KEYS)) {
        for (const [key, [phase, direction, identifier]] of Object.entries(keys)) {
            report[phase] ??= {};
            report[phase][direction] ??= {};
            report[phase][direction][identifier] = {protocol_id: contract[state][key]};
        }
    }
    return report;
}

for (const contract of baseline) {
    test(contract.name + " report table equals the independent pre-migration relay baseline", () => {
        const profile = profiles.find(candidate => candidate.protocolVersion === contract.protocolVersion);
        assert.ok(profile);
        const table = tableFor(profile);
        const runtime = resolveMinecraftProfile(profile.protocolVersion);
        assert.equal(table.name, contract.name);
        assert.equal(table.loginLayoutVersion, LOGIN_LAYOUT_VERSION);
        assert.equal(runtime.name, contract.name);
        assert.equal(runtime.loginLayoutVersion, profile.relayProtocol.loginLayoutVersion);
        for (const state of Object.keys(RELAY_KEYS)) {
            assert.deepEqual(table[state], contract[state]);
            assert.deepEqual(runtime[state], contract[state]);
            assert.ok(Object.isFrozen(runtime[state]));
        }
        assert.ok(Object.isFrozen(runtime));
        assert.equal(readFileSync(new URL("./protocols/" + profile.protocolVersion + ".json", import.meta.url), "utf8"),
            serializeTable(table));
        const source = table.source;
        if (source.kind === "vanilla-server-reports") assert.equal(source.serverSha1, profile.official.serverSha1);
        else {
            assert.equal(source.kind, "vanilla-client-reports");
            assert.equal(source.clientSha1, profile.official.clientSha1);
            assert.equal(source.expectedServerSha1, profile.official.serverSha1);
        }
    });
}

test("1.21.11 server-report provenance preserves the audited packet hash and wire ids", () => {
  const profile = profiles.find(candidate => candidate.clientDistribution === "obfuscated-with-mappings");
    const table = tableFor(profile);
    assert.deepEqual(table.source, {
        kind: "vanilla-server-reports",
        serverSha1: profile.official.serverSha1,
        packetsSha256: "602f772e07ed74266ad3f38161c0702224f362936d193b48c4295a6d15116479",
    });
    const contract = baseline.find(candidate => candidate.protocolVersion === 774);
    for (const state of Object.keys(RELAY_KEYS)) assert.deepEqual(table[state], contract[state]);
});

test("every manifest protocol has a table, and reports are order-independent and fail closed", () => {
    assert.deepEqual(MINECRAFT_PROFILES.map(profile => profile.protocolVersion),
        profiles.map(profile => profile.protocolVersion).sort((left, right) => left - right));
    const contract = baseline[0];
    const profile = profiles.find(candidate => candidate.protocolVersion === contract.protocolVersion);
    const report = fixtureReport(contract);
    const table = tableFromReport(profile, report, profile.official.serverSha1);
    const reordered = Object.fromEntries(Object.entries(report).reverse());
    assert.deepEqual(tableFromReport(profile, reordered, profile.official.serverSha1), table);
    assert.deepEqual(table.login, tableFor(profile).login);
    assert.deepEqual(canonicalJson({z: 1, a: {y: 3, b: 2}}), {a: {b: 2, y: 3}, z: 1});
    delete report.login.serverbound["minecraft:hello"];
    assert.throws(() => tableFromReport(profile, report, profile.official.serverSha1), /Missing or invalid packet/);
});

test("unknown protocols and unsupported layouts or multi-byte ids remain opaque", () => {
    const unknown = Math.max(...profiles.map(profile => profile.protocolVersion)) + 1;
    assert.equal(resolveMinecraftProfile(unknown), undefined);
    const table = tableFor(profiles[0]);
    assert.equal(validateProtocolTable({...table, loginLayoutVersion: LOGIN_LAYOUT_VERSION + 1}, table.protocolVersion), undefined);
    const wide = structuredClone(table);
    wide.play.clientboundLogin = 128;
    assert.equal(validateProtocolTable(wide, wide.protocolVersion), undefined);
    const directory = scratch();
    writeFileSync(join(directory, "README.md"), "Not a protocol table");
    writeFileSync(join(directory, table.protocolVersion + ".json"), serializeTable({...table, loginLayoutVersion: 2}));
    assert.deepEqual(loadMinecraftProfiles(directory), []);
});

test("table validation rejects mismatched filenames, missing ids, duplicates and corrupt provenance", () => {
    const table = tableFor(profiles[0]);
    assert.throws(() => validateProtocolTable(table, table.protocolVersion + 1), /Invalid relay protocol/);
    for (const mutation of [
        candidate => delete candidate.login.serverboundHello,
        candidate => candidate.play.clientboundKeepAlive = -1,
        candidate => candidate.play.clientboundKeepAlive = candidate.play.clientboundLogin,
        candidate => candidate.configuration.extra = 1,
        candidate => candidate.source.packetsSha256 = "bad",
    ]) {
        const candidate = structuredClone(table);
        mutation(candidate);
        assert.throws(() => validateProtocolTable(candidate, candidate.protocolVersion), /Invalid|Duplicate/);
    }
    const directory = scratch();
    writeFileSync(join(directory, table.protocolVersion + ".json"), "{");
    assert.throws(() => loadMinecraftProfiles(directory), SyntaxError);
});

test("a newly supplied compatible protocol works without a relay code change", () => {
    const directory = scratch();
    const table = tableFor(profiles[0]);
    const protocolVersion = Math.max(...profiles.map(profile => profile.protocolVersion)) + 1;
    writeFileSync(join(directory, protocolVersion + ".json"), serializeTable({...table,
        name: "next-profile", protocolVersion}));
    const command = "import {MINECRAFT_PROFILES,resolveMinecraftProfile} from './apps/bridge/dist/protocol.js';" +
        "console.log(JSON.stringify({count:MINECRAFT_PROFILES.length,profile:resolveMinecraftProfile(" + protocolVersion + ")}));";
    const result = spawnSync(process.execPath, ["--input-type=module", "-e", command], {cwd: root,
        env: {...process.env, GAIUS_BRIDGE_PROTOCOL_DIRECTORY: directory}, encoding: "utf8", windowsHide: true});
    assert.equal(result.status, 0, result.stderr);
    const loaded = JSON.parse(result.stdout);
    assert.equal(loaded.count, 1);
    assert.equal(loaded.profile.name, "next-profile");
    assert.equal(loaded.profile.protocolVersion, protocolVersion);
});

function isolatedRepository() {
    const directory = scratch();
    mkdirSync(join(directory, "port", "versions"), {recursive: true});
    mkdirSync(join(directory, "apps", "bridge", "protocols"), {recursive: true});
    for (const profile of profiles) {
        writeFileSync(join(directory, "port", "versions", profile.id + ".json"), JSON.stringify(profile));
        writeFileSync(join(directory, "apps", "bridge", "protocols", profile.protocolVersion + ".json"), serializeTable(tableFor(profile)));
    }
    return directory;
}

test("CI skips only absent jars, still checks tables, and never downloads or launches Java for a skip", () => {
    const directory = isolatedRepository();
    const args = ["--all", "--check", "--skip-missing", "--java", "not-installed",
        "--server-root", join(directory, "absent"), "--out", scratch()];
    assert.deepEqual(runGenerator(args, directory), {checked: 0, skipped: profiles.length});
    const tablePath = join(directory, "apps", "bridge", "protocols", profiles[0].protocolVersion + ".json");
    const bad = tableFor(profiles[0]);
    bad.play.clientboundKeepAlive = -1;
    writeFileSync(tablePath, serializeTable(bad));
    assert.throws(() => runGenerator(args, directory), /Invalid packet id/);
});

test("jar hash mismatch and missing explicit inputs are errors even in skip mode", () => {
    const directory = isolatedRepository();
    const profile = profiles[0];
    mkdirSync(join(directory, "jars", profile.id), {recursive: true});
    writeFileSync(join(directory, "jars", profile.id, "server.jar"), "wrong jar");
    const args = ["--profile", profile.id, "--check", "--skip-missing",
        "--server-root", join(directory, "jars"), "--out", scratch()];
    assert.throws(() => runGenerator(args, directory), /SHA-1 mismatch/);
    assert.throws(() => runGenerator(["--profile", profile.id, "--check", "--skip-missing",
        "--server-jar", join(directory, "missing.jar"), "--out", scratch()], directory), /Explicit server jar/);
    assert.throws(() => runGenerator(["--all", "--out", scratch()], directory), /no download attempted/);
    assert.throws(() => runGenerator(["--all", "--out", join(directory, "port", "work", "scratch")], directory), /inside port\/work/);
});

test("scratch output cannot overwrite tables or write through a shared-work junction", () => {
    const directory = isolatedRepository();
    const tables = join(directory, "apps", "bridge", "protocols");
    const tablePath = join(tables, profiles[0].protocolVersion + ".json");
    const before = readFileSync(tablePath, "utf8");
    assert.throws(() => runGenerator(["--all", "--check", "--skip-missing", "--out", tables], directory), /outside the repository/);
    assert.equal(readFileSync(tablePath, "utf8"), before);
    const work = join(directory, "port", "work");
    mkdirSync(work);
    const alias = join(scratch(), "work-alias");
    symlinkSync(work, alias, "junction");
    assert.throws(() => runGenerator(["--all", "--out", join(alias, "report")], directory), /inside port\/work/);
});

test("CI rejects orphaned table files even when all jars are absent", () => {
    const directory = isolatedRepository();
    const protocolVersion = Math.max(...profiles.map(profile => profile.protocolVersion)) + 1;
    writeFileSync(join(directory, "apps", "bridge", "protocols", protocolVersion + ".json"),
        serializeTable({...tableFor(profiles[0]), protocolVersion}));
    assert.throws(() => runGenerator(["--all", "--check", "--skip-missing", "--out", scratch()], directory), /file set drifted/);
});

test("CLI rejects ambiguous, unsafe and misspelled requests", () => {
    for (const args of [[], ["--all"], ["--all", "--profile", "x", "--out", "x"],
        ["--all", "--skip-missing", "--out", "x"], ["--all", "--all", "--out", "x"],
        ["--all", "--server-jar", "x", "--out", "x"], ["--profile", "../x", "--out", "x"],
        ["--all", "--client-work", "x", "--out", "x"], ["--all", "--unknown", "x"]]) {
        assert.throws(() => parseOptions(args));
    }
});

test("manifest protocol collisions and unaudited login layouts fail before generation", () => {
    const directory = isolatedRepository();
    const changed = {...profiles[1], protocolVersion: profiles[0].protocolVersion};
    writeFileSync(join(directory, "port", "versions", changed.id + ".json"), JSON.stringify(changed));
    assert.throws(() => loadVersionProfiles(directory), /unique protocol numbers/);
    assert.throws(() => loadVersionProfiles(directory, changed.id), /unique protocol numbers/);
    changed.relayProtocol = {loginLayoutVersion: 2};
    writeFileSync(join(directory, "port", "versions", changed.id + ".json"), JSON.stringify(changed));
    assert.throws(() => loadVersionProfiles(directory), /audit the login layout/);
});

test("single-profile selection validates the whole manifest set without changing the historical baseline", () => {
    const directory = isolatedRepository();
    const added = {...profiles[0], id: "0-future-profile",
        protocolVersion: Math.max(...profiles.map(profile => profile.protocolVersion)) + 1};
    writeFileSync(join(directory, "port", "versions", added.id + ".json"), JSON.stringify(added));
    assert.equal(loadVersionProfiles(directory)[0].id, added.id);
    assert.deepEqual(loadVersionProfiles(directory, profiles[0].id), [profiles[0]]);
    assert.throws(() => loadVersionProfiles(directory, "unavailable-profile"), /Unknown profile/);
});

test("real relay handshake, encryption and keepalive helpers use loaded tables without any socket connections", () => {
    const source = readFileSync(new URL("./dist/main.js", import.meta.url), "utf8");
    const names = ["readMinecraftFrame", "decodeMinecraftVarInt", "minecraftPacketId",
        "inspectMinecraftHandshake", "isLoginEncryptionRequest", "proxyVanillaKeepAlive", "isPayloadlessPacket"];
    const functions = names.map(name => {
        const start = source.indexOf("function " + name + "(");
        assert.notEqual(start, -1);
        const end = source.indexOf("\nfunction ", start + 1);
        assert.notEqual(end, -1);
        return source.slice(start, end);
    }).join("\n");
    const context = vm.createContext({Buffer, resolveMinecraftProfile,
        config: {maximumFrameBytes: 16384, proxyKeepAlives: true}, traceTunnel: false,
        incrementKeepAliveProxyCounter() {}, recordProxiedKeepAlive() {}});
    vm.runInContext(functions, context);
    const unknown = Math.max(...profiles.map(profile => profile.protocolVersion)) + 1;
    for (const protocolVersion of [...profiles.map(profile => profile.protocolVersion), unknown]) {
        const body = Buffer.concat([Buffer.from([0]), encodeVarInt(protocolVersion),
            encodeString("example.invalid"), Buffer.from([0, 0, 2])]);
        const frame = Buffer.concat([encodeVarInt(body.length), body]);
        const before = Buffer.from(frame);
        const result = context.inspectMinecraftHandshake(frame);
        assert.equal(result.state, "complete");
        const profile = resolveMinecraftProfile(protocolVersion);
        assert.equal(result.handshake.profile, profile);
        assert.deepEqual(frame, before);
        const replies = [];
        const socket = {write(bytes) {replies.push(bytes); return true;}};
        const keepalive = Buffer.alloc(11, 7);
        keepalive[0] = 10;
        keepalive[1] = 0;
        keepalive[2] = profile?.play.clientboundKeepAlive ?? 1;
        const original = Buffer.from(keepalive);
        assert.equal(context.proxyVanillaKeepAlive(socket, keepalive, "play", profile), profile !== undefined);
        assert.deepEqual(keepalive, original);
        if (profile) {
            assert.equal(replies.length, 1);
            assert.equal(replies[0][2], profile.play.serverboundKeepAlive);
            assert.deepEqual(replies[0].subarray(3), keepalive.subarray(3));
            const encryption = Buffer.from([3, 0, profile.login.clientboundEncryptionRequest, 7]);
            assert.equal(context.isLoginEncryptionRequest(encryption, 1, "login", profile), true);
            assert.equal(context.isLoginEncryptionRequest(encryption, 1, "play", profile), false);
            assert.equal(context.isLoginEncryptionRequest(encryption, 1, "login", undefined), false);
            const finish = Buffer.from([2, 0, profile.configuration.serverboundFinish]);
            assert.equal(context.isPayloadlessPacket(finish, 1, profile.configuration.serverboundFinish), true);
        } else assert.deepEqual(replies, []);
    }
});

test("audited login-distance prefix keeps the existing field layout and rejects truncation", () => {
    const prefix = Buffer.concat([Buffer.from([0, 0, 0, 42, 0]), encodeVarInt(2),
        encodeString("minecraft:overworld"), encodeString("minecraft:the_nether"),
        encodeVarInt(20), encodeVarInt(8), encodeVarInt(4)]);
    assert.deepEqual(decodeClientboundLoginDistances(prefix), {
        chunkRadius: 8, simulationDistance: 4, prefixBytesRead: prefix.length,
    });
    assert.throws(() => decodeClientboundLoginDistances(prefix.subarray(0, prefix.length - 1)), /truncated/);
});
