// Runs an unmodified vanilla Minecraft server in offline mode (online-mode=false,
// white-list=false) for the active version profile, then proves that the RelayNode
// carries a full LOGIN -> CONFIGURATION -> PLAY session to it (multiplayer-smoke.mjs)
// and, when GAIUS_SMOKE_BROWSER_DIST is set, that the browser client joins it in
// headless Chrome through a RelayNode started here
// (port/scripts/minecraft-263-multiplayer-cdp.mjs).
//
//   GAIUS_VERSION_PROFILE_PATH=versions/26.3.json node apps/bridge/offline-server-smoke.mjs
//   GAIUS_SMOKE_SERVER_JAR=D:/path/server.jar GAIUS_SMOKE_BROWSER_DIST=D:/path/dist \
//     GAIUS_VERSION_PROFILE_PATH=versions/26.3.json node apps/bridge/offline-server-smoke.mjs
//
// Environment:
//   GAIUS_SMOKE_SERVER_JAR         verified official server.jar (otherwise downloaded from the
//                                  Mojang manifest into <build root>/multiplayer-smoke-server/)
//   GAIUS_SMOKE_EVIDENCE_DIRECTORY evidence root (default <build root>/offline-server-evidence)
//   GAIUS_SMOKE_STARTUP_TIMEOUT_MS server startup timeout (default 180000)
//   GAIUS_SMOKE_PLAY_SOAK_MS       PLAY soak for the relay smoke (default 20000)
//   GAIUS_SMOKE_COMPRESSION_THRESHOLD  network-compression-threshold (default 256; -1 disables)
//   GAIUS_SMOKE_BROWSER_DIST       dist directory for the browser leg (optional)
//   GAIUS_SMOKE_BROWSER_PORT       static page port for the browser leg (default 8780)
//   GAIUS_SMOKE_BROWSER_REJOIN     "0" skips the disconnect -> rejoin leg
//   GAIUS_SMOKE_KEEP_SERVER        "1" leaves the server running after the smoke (prints port)
//   GAIUS_JAVA / GAIUS_JAVA_HOME / JAVA_HOME  Java 25 for 26.x servers
import { spawn } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
    ensureVerifiedServerJar,
    loadActiveVersionProfile,
    repository,
    reservePort,
    resolveBuildRoot,
    resolveJavaExecutable,
    resolveRepositoryPath,
    resolveSmokeServerDirectory,
    resolveSmokeServerJar,
    serverProperties,
    sha1File,
    startVanillaServer,
    waitFor,
} from "./vanilla-server.mjs";

const bridgeDirectory = fileURLToPath(new URL(".", import.meta.url));
const relaySmokeScript = path.join(bridgeDirectory, "multiplayer-smoke.mjs");
const relayMain = path.join(bridgeDirectory, "dist", "main.js");
const browserHarness = path.join(repository, "port", "scripts", "minecraft-263-multiplayer-cdp.mjs");
const username = "GaiusOffline";
const profileId = "00000000000040008000000000000003";

if (process.argv.includes("--self-smoke") || process.argv.includes("--self-test")) {
    await runStaticSelfSmoke();
    process.exit(0);
}

const activeProfile = await loadActiveVersionProfile();
const targetDirectory = resolveBuildRoot(activeProfile.id);
const serverJar = resolveSmokeServerJar(resolveSmokeServerDirectory(targetDirectory));
const evidenceDirectory = process.env.GAIUS_SMOKE_EVIDENCE_DIRECTORY?.trim()
    ? resolveRepositoryPath(process.env.GAIUS_SMOKE_EVIDENCE_DIRECTORY)
    : path.join(targetDirectory, "offline-server-evidence");
await mkdir(evidenceDirectory, {recursive: true});
const verifiedServerJar = await ensureVerifiedServerJar(serverJar, activeProfile);
const workDirectory = await mkdtemp(path.join(evidenceDirectory, "run-"));
const runtimeServerJar = path.join(workDirectory, "server.jar");
await copyFile(verifiedServerJar.path, runtimeServerJar);
if (await sha1File(runtimeServerJar) !== activeProfile.official.serverSha1) {
    throw new Error("The isolated vanilla server.jar copy failed SHA-1 verification");
}
const minecraftPort = await reservePort();
const compressionThreshold = String(
        Number.parseInt(process.env.GAIUS_SMOKE_COMPRESSION_THRESHOLD ?? "256", 10));
await Promise.all([
    writeFile(path.join(workDirectory, "eula.txt"), "eula=true\n"),
    writeFile(path.join(workDirectory, "server.properties"), serverProperties(minecraftPort, {
        "online-mode": "false",
        "enforce-secure-profile": "false",
        "white-list": "false",
        "enforce-whitelist": "false",
        "level-type": "minecraft:normal",
        "view-distance": "4",
        "simulation-distance": "3",
        "network-compression-threshold": compressionThreshold,
        "motd": `Gaius vanilla ${activeProfile.id} offline smoke`,
    })),
]);

const javaExecutable = await resolveJavaExecutable(activeProfile.javaVersion, activeProfile.id);
const server = await startVanillaServer({
    javaExecutable,
    serverJar: runtimeServerJar,
    workDirectory,
    jvmArguments: ["-Xms256m", "-Xmx1G"],
    startupTimeoutMs: Number.parseInt(process.env.GAIUS_SMOKE_STARTUP_TIMEOUT_MS ?? "180000", 10) || 180000,
    label: `vanilla ${activeProfile.id} offline server`,
});
// A fixed daytime keeps the browser leg's rendered-world evidence comparable between runs.
server.command("gamerule doDaylightCycle false");
server.command("time set day");
const result = {
    ok: false,
    profile: {id: activeProfile.id, protocolVersion: activeProfile.protocolVersion, javaVersion: activeProfile.javaVersion},
    serverJar: {path: verifiedServerJar.path, sha1: verifiedServerJar.sha1, downloaded: verifiedServerJar.downloaded},
    unmodifiedVanillaServer: true,
    onlineMode: false,
    compressionThreshold: Number(compressionThreshold),
    server: `127.0.0.1:${minecraftPort}`,
    workDirectory,
    javaExecutable,
};
let relayProcess;
try {
    const relaySmoke = await runChild(process.execPath, [relaySmokeScript], {
        GAIUS_SMOKE_MINECRAFT_HOST: "127.0.0.1",
        GAIUS_SMOKE_MINECRAFT_PORT: String(minecraftPort),
        GAIUS_SMOKE_USERNAME: username,
        GAIUS_SMOKE_PROFILE_ID: profileId,
        GAIUS_SMOKE_MINECRAFT_VERSION: activeProfile.id,
        GAIUS_VERSION_PROFILE_PATH: activeProfile.relativePath,
        GAIUS_SMOKE_PLAY_SOAK_MS: process.env.GAIUS_SMOKE_PLAY_SOAK_MS ?? "20000",
    }, path.join(workDirectory, "relay-smoke.log"), repository);
    if (relaySmoke.exitCode !== 0) {
        throw new Error("RelayNode offline multiplayer smoke failed:\n" + relaySmoke.output.slice(-4000));
    }
    const login = JSON.parse(relaySmoke.output.trim().split("\n").at(-1)).minecraftLogin;
    if (!login || login.onlineMode !== false || !login.loginFinished || !login.configurationFinished ||
            login.playLoginPackets < 1 || login.chunkPackets < 1 || login.chunkBatch?.countMismatches !== 0) {
        throw new Error("Offline relay smoke did not prove LOGIN/CONFIGURATION/PLAY chunk data: " +
            JSON.stringify(login));
    }
    result.relaySmoke = {
        loginFinished: login.loginFinished,
        configurationFinished: login.configurationFinished,
        playPackets: login.playPackets,
        chunkPackets: login.chunkPackets,
        chunkBatches: login.chunkBatch,
        playSoakMs: login.playSoakMs,
    };
    const output = server.output();
    if (!output.includes(`${username} joined the game`)) {
        throw new Error("Vanilla server never reported the relay smoke player joining");
    }

    const browserDist = process.env.GAIUS_SMOKE_BROWSER_DIST?.trim();
    if (browserDist) {
        const distDirectory = resolveRepositoryPath(browserDist);
        const pagePort = Number.parseInt(process.env.GAIUS_SMOKE_BROWSER_PORT ?? "8780", 10) || 8780;
        const relayPort = await reservePort();
        const relayLog = path.join(workDirectory, "relay.log");
        let relayOutput = "";
        relayProcess = spawn(process.execPath, [relayMain], {
            cwd: bridgeDirectory,
            env: {
                ...process.env,
                GAIUS_BRIDGE_HOST: "127.0.0.1",
                GAIUS_BRIDGE_PORT: String(relayPort),
                GAIUS_ALLOWED_ORIGINS: `http://127.0.0.1:${pagePort}`,
                GAIUS_TRACE_TUNNEL: "1",
            },
            stdio: ["ignore", "pipe", "pipe"],
            windowsHide: true,
        });
        for (const stream of [relayProcess.stdout, relayProcess.stderr]) {
            stream.setEncoding("utf8");
            stream.on("data", (chunk) => { relayOutput += chunk; });
        }
        await waitFor(() => relayOutput.includes("listening on") || relayProcess.exitCode !== null,
                "RelayNode startup", 20000);
        if (relayProcess.exitCode !== null) throw new Error("RelayNode failed to start:\n" + relayOutput);
        const serverLogPath = path.join(workDirectory, "server.log");
        await writeFile(serverLogPath, server.output());
        const logTimer = setInterval(() => { writeFile(serverLogPath, server.output()).catch(() => {}); }, 2000);
        let browser;
        try {
            browser = await runChild(process.execPath, [browserHarness], {
                DIST: distDirectory,
                DIST_PORT: String(pagePort),
                BRIDGE: `http://127.0.0.1:${relayPort}/`,
                SERVER: `127.0.0.1:${minecraftPort}`,
                SERVER_LOG: serverLogPath,
                OUT: path.join(workDirectory, "browser"),
                PLAYER_NAME: "GaiusM5",
                PROTOCOL: String(activeProfile.protocolVersion),
                REJOIN: process.env.GAIUS_SMOKE_BROWSER_REJOIN ?? "1",
            }, path.join(workDirectory, "browser-harness.log"), repository);
        }
        finally {
            clearInterval(logTimer);
            await writeFile(relayLog, relayOutput);
        }
        const summaryLine = browser.output.trim().split("\n").at(-1);
        let summary = null;
        try { summary = JSON.parse(summaryLine); } catch { /* reported below */ }
        result.browser = {exitCode: browser.exitCode, summary, evidence: path.join(workDirectory, "browser")};
        if (browser.exitCode !== 0 || summary?.verdict !== "PASS") {
            throw new Error("Browser multiplayer harness failed: " + (summaryLine ?? "").slice(0, 2000));
        }
    }
    result.ok = true;
}
finally {
    if (relayProcess !== undefined && relayProcess.exitCode === null) relayProcess.kill();
    if (process.env.GAIUS_SMOKE_KEEP_SERVER === "1" && result.ok) {
        console.error(`Leaving the vanilla server running on 127.0.0.1:${minecraftPort} (GAIUS_SMOKE_KEEP_SERVER=1); stop it with 'stop' on its stdin or by PID ${server.process.pid}`);
    }
    else {
        await server.stop();
    }
    await writeFile(path.join(workDirectory, "server.log"), server.output());
    await writeFile(path.join(workDirectory, "result.json"), JSON.stringify(result, null, 2) + "\n");
}
console.log(JSON.stringify(result));
if (process.env.GAIUS_SMOKE_KEEP_SERVER === "1") {
    await new Promise((resolve) => server.process.once("exit", resolve));
}

async function runChild(command, args, extraEnvironment, outputPath, cwd) {
    const child = spawn(command, args, {
        cwd,
        env: {...process.env, ...extraEnvironment},
        stdio: ["ignore", "pipe", "pipe"],
        windowsHide: true,
    });
    let output = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => { output += chunk; });
    child.stderr.on("data", (chunk) => { output += chunk; });
    const exitCode = await new Promise((resolve) => child.once("exit", resolve));
    if (outputPath !== undefined) await writeFile(outputPath, output);
    return {exitCode, output};
}

async function runStaticSelfSmoke() {
    const source = await readFile(fileURLToPath(import.meta.url), "utf8");
    if (source.includes(["/usr", "bin", "java"].join("/"))) {
        throw new Error("offline-server smoke retained a platform-specific Java dependency");
    }
    const profile = await loadActiveVersionProfile();
    const properties = serverProperties(25565, {"online-mode": "false", "white-list": "false"});
    for (const required of ["online-mode=false", "white-list=false", "server-ip=127.0.0.1", "server-port=25565"]) {
        if (!properties.includes(`${required}\n`)) throw new Error(`server.properties override missing ${required}`);
    }
    if (!/^(?:[^\n]+\n)+$/u.test(properties) || properties.split("online-mode=").length !== 2) {
        throw new Error("server.properties overrides must replace, not duplicate, keys");
    }
    console.log(JSON.stringify({
        ok: true,
        selfSmoke: true,
        profile: profile.id,
        protocolVersion: profile.protocolVersion,
        serverJar: resolveSmokeServerJar(resolveSmokeServerDirectory(resolveBuildRoot(profile.id))),
        browserHarness,
    }));
}
