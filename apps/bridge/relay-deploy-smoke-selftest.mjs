#!/usr/bin/env node
// Self-test for relay-deploy-smoke.mjs. It starts this checkout's relay
// (dist/main.js) on 127.0.0.1 with throw-away ports, runs the smoke against it
// and proves the smoke also FAILS for the misconfigurations it is meant to
// catch. No remote host is contacted and every child process is stopped before
// the script exits.
//
//   node apps/bridge/relay-deploy-smoke-selftest.mjs
//
// Optional: GAIUS_SMOKE_LEGACY_BRIDGE_DIR=<an extracted v0.3.3 apps/bridge with
// node_modules> additionally proves that the smoke rejects the previous relay.

import {spawn} from "node:child_process";
import {createServer} from "node:net";
import {copyFileSync, existsSync, mkdtempSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";
import {runDeploySmoke, parseArguments} from "./relay-deploy-smoke.mjs";

const bridgeDirectory = fileURLToPath(new URL("./", import.meta.url));
const children = new Set();
const pagesOrigin = "https://typethe0ry.github.io";
let failures = 0;

function expect(condition, message) {
    if (condition) console.log(`  ok   ${message}`);
    else { failures++; console.log(`  FAIL ${message}`); }
}

function freePort() {
    return new Promise((resolve, reject) => {
        const server = createServer();
        server.once("error", reject);
        server.listen(0, "127.0.0.1", () => {
            const {port} = server.address();
            server.close(() => resolve(port));
        });
    });
}

async function startRelay(environment, directory = bridgeDirectory) {
    const port = await freePort();
    const clean = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith("GAIUS_")));
    // The self-test deliberately supplies the same explicit null-origin opt-in
    // required by a portable deployment; keep it after removing other GAIUS_*
    // variables so scenarios cannot inherit unrelated machine configuration.
    const nullOriginOptIn = process.env.GAIUS_ALLOW_NULL_ORIGIN;
    const child = spawn(process.execPath, [join(directory, "dist", "main.js")], {
        cwd: directory,
        env: {...clean, GAIUS_BRIDGE_HOST: "127.0.0.1", GAIUS_BRIDGE_PORT: String(port),
            ...(nullOriginOptIn === undefined ? {} : {GAIUS_ALLOW_NULL_ORIGIN: nullOriginOptIn}), ...environment},
        stdio: ["ignore", "pipe", "pipe"],
    });
    children.add(child);
    let output = "";
    child.stdout.on("data", chunk => { output += chunk; });
    child.stderr.on("data", chunk => { output += chunk; });
    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(`relay did not start: ${output}`)), 10000);
        const poll = setInterval(() => {
            if (/listening on/u.test(output)) { clearTimeout(timer); clearInterval(poll); resolve(); }
        }, 50);
        child.once("exit", code => { clearTimeout(timer); clearInterval(poll); reject(new Error(`relay exited ${code}: ${output}`)); });
    });
    return {port, url: `http://127.0.0.1:${port}`, child};
}

async function stopRelay(relay) {
    if (relay.child.exitCode !== null) return;
    await new Promise(resolve => { relay.child.once("exit", resolve); relay.child.kill(); });
    children.delete(relay.child);
}

function smoke(relay, extra = []) {
    return runDeploySmoke(parseArguments([relay.url, "--timeout-ms", "4000", ...extra]));
}

const statusOf = (summary, name) => summary.results.find(item => item.name === name)?.status;
const productionOrigins = `${pagesOrigin},null`;

async function scenario(title, environment, extra, assertions, directory) {
    console.log(`\n== ${title}`);
    const relay = await startRelay(environment, directory);
    try {
        const summary = await smoke(relay, extra(relay));
        assertions(summary, relay);
    } finally {
        await stopRelay(relay);
    }
}

async function main() {
    // Loopback stand-in for an outbound target; only used to prove --target.
    const echo = createServer(socket => socket.on("data", chunk => socket.write(chunk)));
    await new Promise(resolve => echo.listen(0, "127.0.0.1", resolve));
    const echoTarget = `127.0.0.1:${echo.address().port}`;

    await scenario("full pass: trusted-LAN policy, default target (the relay's own listener)",
        {GAIUS_ALLOWED_ORIGINS: productionOrigins, GAIUS_ALLOW_NULL_ORIGIN: "1", GAIUS_ALLOWED_HOSTS: "*", GAIUS_ALLOW_PRIVATE_TARGETS: "1"},
        () => ["--expect-private-targets", "true"],
        summary => {
            expect(summary.ok, "smoke passes");
            for (const protocol of [774, 776, 777]) expect(statusOf(summary, `ws.receipt[${protocol}]`) === "PASS", `receipt for ${protocol}`);
            expect(Object.keys(summary.receipts).length === 3, "three accepted declaration hashes recorded");
            expect(statusOf(summary, "ws.legacy-connect") === "PASS", "legacy connect (no declaration) gets no receipt");
            expect(statusOf(summary, "lan.tunnel-pair") === "PASS", "LAN tunnel pair forwards bytes");
            expect(statusOf(summary, "ws.tampered-declaration") === "PASS", "tampered declaration closes 1007");
        });

    await scenario("full pass: explicit --target",
        {GAIUS_ALLOWED_ORIGINS: productionOrigins, GAIUS_ALLOW_NULL_ORIGIN: "1", GAIUS_ALLOW_PRIVATE_TARGETS: "1"},
        () => ["--expect-private-targets", "true", "--target", echoTarget],
        summary => expect(summary.ok && statusOf(summary, "ws.receipt[777]") === "PASS", "smoke passes against an explicit target"));

    await scenario("public policy: private targets refused, receipts skipped",
        {GAIUS_ALLOWED_ORIGINS: productionOrigins, GAIUS_ALLOW_NULL_ORIGIN: "1", GAIUS_ALLOWED_HOSTS: "*", GAIUS_ALLOW_PRIVATE_TARGETS: "0"},
        relay => ["--skip-receipt", "--private-probe", `127.0.0.1:${relay.port}`],
        summary => {
            expect(summary.ok, "smoke passes");
            expect(statusOf(summary, "health.private-target-policy") === "PASS", "allowsPrivateTargets=false confirmed");
            expect(statusOf(summary, "private.loopback-probe") === "PASS", "loopback dial refused");
        });

    await scenario("wildcard origin policy: all browser origins accepted",
        {GAIUS_ALLOWED_ORIGINS: "*", GAIUS_ALLOW_PRIVATE_TARGETS: "0"},
        () => ["--expect-wildcard", "--skip-receipt"],
        summary => {
            expect(summary.ok, "wildcard smoke passes");
            for (const origin of ["https://gaius.fun", pagesOrigin, "https://random-origin.invalid", "null", "file://"])
                expect(statusOf(summary, `cors.allowed[${origin}]`) === "PASS", `HTTP CORS accepts ${origin}`);
            expect(statusOf(summary, "ws.wildcard-random-origin") === "PASS", "WebSocket accepts random origin");
            expect(statusOf(summary, "http.missing-origin") === "PASS" && statusOf(summary, "ws.missing-origin") === "PASS",
                "missing Origin behavior is explicit");
        });

    await scenario("negative: Pages origin missing from the allow-list",
        {GAIUS_ALLOWED_ORIGINS: "null", GAIUS_ALLOW_NULL_ORIGIN: "1", GAIUS_ALLOW_PRIVATE_TARGETS: "0"},
        () => ["--skip-receipt"],
        summary => {
            expect(!summary.ok, "smoke fails");
            expect(statusOf(summary, "health.ok") === "FAIL" || statusOf(summary, `cors.allowed[${pagesOrigin}]`) === "FAIL",
                "the missing Pages origin is reported");
        });

    await scenario("negative: private targets enabled on a public node",
        {GAIUS_ALLOWED_ORIGINS: productionOrigins, GAIUS_ALLOW_NULL_ORIGIN: "1", GAIUS_ALLOW_PRIVATE_TARGETS: "1"},
        relay => ["--skip-receipt", "--private-probe", `127.0.0.1:${relay.port}`],
        summary => {
            expect(statusOf(summary, "health.private-target-policy") === "FAIL", "policy flag mismatch reported");
            expect(statusOf(summary, "private.loopback-probe") === "FAIL", "loopback dial detected");
        });

    await scenario("negative: LAN host policy without *.gaius-local",
        {GAIUS_ALLOWED_ORIGINS: productionOrigins, GAIUS_ALLOW_NULL_ORIGIN: "1", GAIUS_ALLOWED_HOSTS: "mc.example.org", GAIUS_ALLOW_PRIVATE_TARGETS: "0"},
        () => ["--skip-receipt"],
        summary => expect(statusOf(summary, "lan.host-policy") === "FAIL", "restricted GAIUS_ALLOWED_HOSTS is reported"));

    const partialTables = mkdtempSync(join(tmpdir(), "gaius-partial-tables-"));
    copyFileSync(join(bridgeDirectory, "protocols", "777.json"), join(partialTables, "777.json"));
    try {
        await scenario("negative: only one numeric protocol table loaded",
            {GAIUS_ALLOWED_ORIGINS: productionOrigins, GAIUS_ALLOW_NULL_ORIGIN: "1", GAIUS_ALLOW_PRIVATE_TARGETS: "0",
                GAIUS_BRIDGE_PROTOCOL_DIRECTORY: partialTables},
            () => ["--skip-receipt"],
            summary => {
                expect(statusOf(summary, "health.protocol-tables") === "FAIL", "missing 774/776 tables are reported");
                expect(!summary.ok, "smoke fails");
            });
    } finally {
        rmSync(partialTables, {recursive: true, force: true});
    }

    const legacy = process.env.GAIUS_SMOKE_LEGACY_BRIDGE_DIR;
    if (legacy && existsSync(join(legacy, "dist", "main.js"))) {
        await scenario("negative: previous (v0.3.3) relay bundle",
            {GAIUS_ALLOWED_ORIGINS: productionOrigins, GAIUS_ALLOW_NULL_ORIGIN: "1", GAIUS_ALLOW_PRIVATE_TARGETS: "1"},
            () => ["--expect-private-targets", "true"],
            summary => {
                expect(!summary.ok, "smoke fails");
                expect(statusOf(summary, "health.capabilities") === "FAIL", "session-protocol-table-v1 missing is reported");
                expect(statusOf(summary, "ws.tampered-declaration") === "FAIL", "tampered declaration not rejected by the old relay");
                expect(statusOf(summary, "ws.receipt[777]") === "FAIL", "no receipt from the old relay");
                expect(statusOf(summary, "ws.legacy-connect") === "PASS", "legacy connect still works on the old relay");
            }, legacy);
    } else {
        console.log("\n== previous relay scenario skipped (GAIUS_SMOKE_LEGACY_BRIDGE_DIR not set)");
    }
    echo.close();
}

try {
    await main();
} catch (error) {
    failures++;
    console.error(`self-test error: ${error.stack ?? error}`);
} finally {
    for (const child of children) child.kill();
}
console.log(failures === 0 ? "\nrelay-deploy-smoke self-test: PASS" : `\nrelay-deploy-smoke self-test: FAIL (${failures})`);
process.exitCode = failures === 0 ? 0 : 1;
