import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { copyFile, mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import {
    ensureVerifiedServerJar,
    loadActiveVersionProfile,
    pathInside,
    repository,
    reservePort,
    resolveBuildRoot,
    resolveJavaExecutable as resolveVanillaJavaExecutable,
    resolveRepositoryPath,
    resolveSmokeServerDirectory,
    resolveSmokeServerJar,
    serverProperties as vanillaServerProperties,
    sha1File,
    waitFor,
} from "./vanilla-server.mjs";
const bridgeDirectory = fileURLToPath(new URL(".", import.meta.url));
const smokeScript = path.join(bridgeDirectory, "multiplayer-smoke.mjs");
const profileId = "00000000000040008000000000000002";
const username = "GaiusOnline";
const accessToken = "gaius-online-mode-smoke-token";
const enforceSecureProfile = process.env.GAIUS_SMOKE_ENFORCE_SECURE_PROFILE === "true";

if (process.argv.includes("--self-smoke") || process.argv.includes("--self-test") ||
        process.env.GAIUS_ONLINE_MODE_SELF_SMOKE === "1") {
    await runStaticSelfSmoke();
    process.exit(0);
}

const activeProfile = await loadActiveVersionProfile();
const targetDirectory = resolveBuildRoot(activeProfile.id);
const vanillaDirectory = resolveSmokeServerDirectory(targetDirectory);
const serverJar = resolveSmokeServerJar(vanillaDirectory);
const evidenceDirectory = resolveEvidenceDirectory(targetDirectory);
await mkdir(targetDirectory, {recursive: true});
await mkdir(evidenceDirectory, {recursive: true});
const verifiedServerJar = await ensureVerifiedServerJar(serverJar, activeProfile);
const workDirectory = await mkdtemp(path.join(evidenceDirectory, "run-"));
const runtimeServerJar = path.join(workDirectory, "server.jar");
await copyFile(verifiedServerJar.path, runtimeServerJar);
const runtimeServerJarSha1 = await sha1File(runtimeServerJar);
if (runtimeServerJarSha1 !== activeProfile.official.serverSha1.toLowerCase()) {
    throw new Error("The isolated vanilla server.jar copy failed SHA-1 verification");
}

const sessionState = {
    joins: [],
    hasJoined: [],
    publicKeyRequests: 0,
};
const sessionServer = createServer(async (request, response) => {
    try {
        const requestUrl = new URL(request.url ?? "/", "http://127.0.0.1");
        if (request.method === "GET" && requestUrl.pathname === "/publickeys") {
            sessionState.publicKeyRequests++;
            sendJson(response, 200, {
                profilePropertyKeys: [],
                playerCertificateKeys: [],
            });
            return;
        }
        if (request.method === "POST" &&
                requestUrl.pathname === "/session/minecraft/join") {
            const body = JSON.parse((await readBody(request)).toString("utf8"));
            if (body.accessToken !== accessToken || body.selectedProfile !== profileId ||
                    typeof body.serverId !== "string" || body.serverId.length === 0) {
                sendJson(response, 403, {error: "invalid smoke join"});
                return;
            }
            sessionState.joins.push(body);
            response.writeHead(204);
            response.end();
            return;
        }
        if (request.method === "GET" &&
                requestUrl.pathname === "/session/minecraft/hasJoined") {
            const query = Object.fromEntries(requestUrl.searchParams);
            sessionState.hasJoined.push(query);
            const join = sessionState.joins.at(-1);
            if (query.username !== username || join === undefined ||
                    query.serverId !== join.serverId) {
                response.writeHead(204);
                response.end();
                return;
            }
            sendJson(response, 200, {
                id: profileId,
                properties: [],
                profileActions: [],
            });
            return;
        }
        if (request.method === "GET" &&
                requestUrl.pathname.startsWith("/session/minecraft/profile/")) {
            sendJson(response, 200, {
                id: profileId,
                name: username,
                properties: [],
                profileActions: [],
            });
            return;
        }
        sendJson(response, 404, {error: "not found", path: requestUrl.pathname});
    }
    catch (error) {
        sendJson(response, 500, {error: String(error)});
    }
});
await new Promise((resolve, reject) => {
    sessionServer.once("error", reject);
    sessionServer.listen(0, "127.0.0.1", resolve);
});
const sessionPort = sessionServer.address().port;
const minecraftPort = await reservePort();
const sessionBaseUrl = `http://127.0.0.1:${sessionPort}`;

await Promise.all([
    writeFile(path.join(workDirectory, "eula.txt"), "eula=true\n"),
    writeFile(path.join(workDirectory, "server.properties"), serverProperties(minecraftPort)),
]);

let javaExecutable;
let javaServer;
let javaSpawnError;
let serverOutput = "";

try {
    javaExecutable = await resolveVanillaJavaExecutable(activeProfile.javaVersion, activeProfile.id);
    javaServer = spawn(javaExecutable, [
        `-Dminecraft.api.session.host=${sessionBaseUrl}`,
        `-Dminecraft.api.services.host=${sessionBaseUrl}`,
        `-Dminecraft.api.profiles.host=${sessionBaseUrl}`,
        "-Xms512m",
        "-Xmx1536m",
        "-jar",
        runtimeServerJar,
        "nogui",
    ], {
        cwd: workDirectory,
        stdio: ["pipe", "pipe", "pipe"],
        windowsHide: true,
    });
    javaServer.once("error", (error) => {
        javaSpawnError = error;
        serverOutput += `\nJava process error: ${error.stack || error}\n`;
    });
    for (const stream of [javaServer.stdout, javaServer.stderr]) {
        stream.setEncoding("utf8");
        stream.on("data", (chunk) => {
            serverOutput += chunk;
        });
    }
    await waitFor(
            () => serverOutput.includes("Done (") || javaServer.exitCode !== null ||
                javaSpawnError !== undefined,
            "online-mode vanilla server startup",
            Number.parseInt(process.env.GAIUS_SMOKE_STARTUP_TIMEOUT_MS ?? "180000", 10) || 180000);
    if (javaSpawnError !== undefined || javaServer.exitCode !== null ||
            !serverOutput.includes("Done (")) {
        throw new Error("Vanilla online-mode server failed to start:\n" + serverOutput);
    }

    const smoke = await runSmoke({
        GAIUS_SMOKE_MINECRAFT_HOST: "127.0.0.1",
        GAIUS_SMOKE_MINECRAFT_PORT: String(minecraftPort),
        GAIUS_SMOKE_SESSION_URL: sessionBaseUrl,
        GAIUS_SMOKE_ACCESS_TOKEN: accessToken,
        GAIUS_SMOKE_PROFILE_ID: profileId,
        GAIUS_SMOKE_USERNAME: username,
        GAIUS_SMOKE_MINECRAFT_VERSION: activeProfile.id,
        GAIUS_VERSION_PROFILE_PATH: activeProfile.relativePath,
    }, path.join(workDirectory, "bridge-smoke.log"));
    const login = smoke.minecraftLogin;
    if (!login?.onlineMode || !login.rsa?.requested || !login.rsa.secretEncrypted ||
            !login.rsa.challengeEncrypted || login.rsa.padding !== "RSA_PKCS1_PADDING" ||
            !login.aes?.enabled || login.aes.cipher !== "aes-128-cfb8" ||
            !login.sessionJoin || !login.loginFinished ||
            !login.configurationFinished || login.playLoginPackets < 1 ||
            login.chunkPackets < 1) {
        throw new Error("Online-mode smoke did not prove RSA/AES/session join and PLAY chunk data");
    }
    if (sessionState.joins.length !== 1 || sessionState.hasJoined.length !== 1 ||
            sessionState.hasJoined[0].serverId !== sessionState.joins[0].serverId) {
        throw new Error("Session join/hasJoined authentication did not match");
    }

    const serverJarSha256 = createHash("sha256")
            .update(await readFile(verifiedServerJar.path))
            .digest("hex");
    const result = {
        ok: true,
        profile: {
            id: activeProfile.id,
            protocolVersion: activeProfile.protocolVersion,
            javaVersion: activeProfile.javaVersion,
            profilePath: activeProfile.path,
        },
        serverJar: {
            path: verifiedServerJar.path,
            sha1: verifiedServerJar.sha1,
            expectedSha1: activeProfile.official.serverSha1,
            sha256: serverJarSha256,
            downloaded: verifiedServerJar.downloaded,
        },
        serverJarSha256,
        unmodifiedVanillaServer: true,
        pluginsInstalled: false,
        enforceSecureProfile,
        session: {
            joins: sessionState.joins.length,
            hasJoined: sessionState.hasJoined.length,
            serverHash: sessionState.joins[0].serverId,
            publicKeyRequests: sessionState.publicKeyRequests,
        },
        minecraftLogin: login,
        workDirectory,
        evidenceDirectory,
        javaExecutable,
    };
    await writeFile(path.join(workDirectory, "result.json"),
            JSON.stringify(result, null, 2) + "\n");
    console.log(JSON.stringify(result));
}
finally {
    if (javaServer !== undefined) {
        if (javaServer.exitCode === null && !javaServer.stdin.destroyed) {
            javaServer.stdin.write("stop\n");
        }
        if (javaServer.exitCode === null) {
            await Promise.race([
                new Promise((resolve) => javaServer.once("exit", resolve)),
                delay(15000).then(() => {
                    if (javaServer.exitCode === null) javaServer.kill("SIGTERM");
                }),
            ]);
        }
    }
    await new Promise((resolve) => sessionServer.close(resolve));
    await writeFile(path.join(workDirectory, "server.log"), serverOutput);
}

function sendJson(response, status, value) {
    const body = Buffer.from(JSON.stringify(value));
    response.writeHead(status, {
        "content-type": "application/json",
        "content-length": String(body.byteLength),
    });
    response.end(body);
}

async function readBody(request) {
    const chunks = [];
    let bytes = 0;
    for await (const chunk of request) {
        bytes += chunk.byteLength;
        if (bytes > 1024 * 1024) throw new Error("request body too large");
        chunks.push(chunk);
    }
    return Buffer.concat(chunks, bytes);
}

function resolveEvidenceDirectory(buildRoot) {
    return process.env.GAIUS_SMOKE_EVIDENCE_DIRECTORY?.trim()
        ? resolveRepositoryPath(process.env.GAIUS_SMOKE_EVIDENCE_DIRECTORY)
        : path.join(buildRoot, "online-mode-evidence");
}

async function runStaticSelfSmoke() {
    const source = await readFile(fileURLToPath(import.meta.url), "utf8");
    const forbiddenJavaPath = ["/usr", "bin", "java"].join("/");
    if (source.includes(forbiddenJavaPath) || /^\s*symlink,\s*$/mu.test(source)) {
        throw new Error("online-mode smoke retained a platform-specific Java or symlink dependency");
    }
    const profile = await loadActiveVersionProfile();
    const target = resolveBuildRoot(profile.id);
    const serverDirectory = resolveSmokeServerDirectory(target);
    const serverJarPath = resolveSmokeServerJar(serverDirectory);
    if (!pathInside(target, resolveEvidenceDirectory(target)) &&
            !process.env.GAIUS_SMOKE_EVIDENCE_DIRECTORY) {
        throw new Error("default smoke evidence escaped the profile-scoped build root");
    }
    if (!serverJarPath.toLowerCase().endsWith(`${path.sep}server.jar`)) {
        throw new Error("smoke server jar resolver did not produce server.jar");
    }
    console.log(JSON.stringify({
        ok: true,
        selfSmoke: true,
        profile: profile.id,
        protocolVersion: profile.protocolVersion,
        buildRoot: target,
        serverDirectory,
        serverJar: serverJarPath,
        evidenceDirectory: resolveEvidenceDirectory(target),
    }));
}

async function runSmoke(extraEnvironment, outputPath) {
    const child = spawn(process.execPath, [smokeScript], {
        cwd: repository,
        env: {...process.env, ...extraEnvironment},
        stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => { output += chunk; });
    child.stderr.on("data", (chunk) => { output += chunk; });
    const exitCode = await new Promise((resolve) => child.once("exit", resolve));
    if (outputPath !== undefined) await writeFile(outputPath, output);
    if (exitCode !== 0) throw new Error("Bridge online-mode smoke failed:\n" + output);
    const lines = output.trim().split("\n");
    return JSON.parse(lines.at(-1));
}

function serverProperties(serverPort) {
    return vanillaServerProperties(serverPort, {
        "enforce-secure-profile": String(enforceSecureProfile),
        "level-name": "world-online",
        "motd": "Gaius vanilla online-mode smoke",
        "online-mode": "true",
    });
}
