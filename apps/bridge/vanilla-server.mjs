// Shared helpers for smokes that run an unmodified vanilla Minecraft server:
// version-profile loading, official server.jar download + SHA-1 verification,
// Java executable discovery and server process lifecycle. Used by
// online-mode-server-smoke.mjs and offline-server-smoke.mjs.
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { lstat, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";

export const repository = fileURLToPath(new URL("../../", import.meta.url));

export function nativePath(value) {
    if (value === undefined || value === null) return value;
    const text = String(value).trim().replaceAll("\\", "/");
    if (process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/u.test(text)) {
        return `${text[1].toUpperCase()}:${text.slice(2)}`;
    }
    return text;
}

export function resolveRepositoryPath(value) {
    const normalized = nativePath(value);
    if (!normalized) throw new Error("Configured path must not be empty");
    return path.isAbsolute(normalized)
        ? path.resolve(normalized)
        : path.resolve(repository, normalized);
}

export function pathInside(parent, child) {
    const relative = path.relative(path.resolve(parent), path.resolve(child));
    return relative === "" || (relative !== ".." && !relative.startsWith(`..${path.sep}`) &&
        !path.isAbsolute(relative));
}

export async function reservePort() {
    const server = createServer();
    await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
    });
    const port = server.address().port;
    await new Promise((resolve) => server.close(resolve));
    return port;
}

export async function waitFor(predicate, label, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (!predicate()) {
        if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
        await delay(25);
    }
}

export async function loadActiveVersionProfile() {
    const configPath = path.join(repository, "port", "config.json");
    const config = JSON.parse(await readFile(configPath, "utf8"));
    let selected = String(
            process.env.GAIUS_VERSION_PROFILE_PATH ?? config.versionProfile ?? "").trim();
    if (!selected) throw new Error("GAIUS_VERSION_PROFILE_PATH or port/config.json.versionProfile is required");
    selected = nativePath(selected);
    if (/^\d+(?:\.\d+)+$/u.test(selected)) selected = `versions/${selected}.json`;
    let profilePath;
    if (path.isAbsolute(selected)) {
        profilePath = path.resolve(selected);
    }
    else if (selected.startsWith("port/")) {
        profilePath = path.resolve(repository, selected);
    }
    else if (selected.startsWith("versions/")) {
        profilePath = path.resolve(repository, "port", selected);
    }
    else {
        profilePath = path.resolve(repository, selected);
    }
    const versionsDirectory = path.join(repository, "port", "versions");
    if (!pathInside(versionsDirectory, profilePath) || !profilePath.endsWith(".json")) {
        throw new Error(`Active version profile must be a JSON file inside port/versions: ${profilePath}`);
    }
    const profile = JSON.parse(await readFile(profilePath, "utf8"));
    if (typeof profile.id !== "string" || profile.id.length === 0 ||
            !Number.isInteger(profile.protocolVersion) || !Number.isInteger(profile.javaVersion) ||
            typeof profile.official?.serverSha1 !== "string" ||
            !/^[0-9a-f]{40}$/iu.test(profile.official.serverSha1)) {
        throw new Error(`Active version profile is missing required server smoke fields: ${profilePath}`);
    }
    const relativePath = path.relative(path.join(repository, "port"), profilePath)
            .replaceAll(path.sep, "/");
    return {
        ...profile,
        path: profilePath,
        relativePath,
        official: {
            ...profile.official,
            serverSha1: profile.official.serverSha1.toLowerCase(),
        },
    };
}

export function resolveBuildRoot(profile) {
    return process.env.GAIUS_BUILD_ROOT?.trim()
        ? resolveRepositoryPath(process.env.GAIUS_BUILD_ROOT)
        : path.join(repository, "port", "target", profile);
}

export function resolveSmokeServerDirectory(buildRoot) {
    if (process.env.GAIUS_SMOKE_SERVER_DIRECTORY?.trim()) {
        return resolveRepositoryPath(process.env.GAIUS_SMOKE_SERVER_DIRECTORY);
    }
    if (process.env.GAIUS_SMOKE_SERVER_JAR?.trim()) {
        return path.dirname(resolveRepositoryPath(process.env.GAIUS_SMOKE_SERVER_JAR));
    }
    return path.join(buildRoot, "multiplayer-smoke-server");
}

export function resolveSmokeServerJar(serverDirectory) {
    return process.env.GAIUS_SMOKE_SERVER_JAR?.trim()
        ? resolveRepositoryPath(process.env.GAIUS_SMOKE_SERVER_JAR)
        : path.join(serverDirectory, "server.jar");
}

export async function lstatRegularFile(filePath) {
    let info;
    try {
        info = await lstat(filePath);
    }
    catch (error) {
        if (error?.code === "ENOENT") return false;
        throw error;
    }
    if (info.isSymbolicLink()) {
        throw new Error(`Refusing symlink server.jar; provide the actual file: ${filePath}`);
    }
    if (!info.isFile()) {
        throw new Error(`Configured server.jar is not a regular file: ${filePath}`);
    }
    return true;
}

export async function sha1File(filePath) {
    return createHash("sha1").update(await readFile(filePath)).digest("hex");
}

export async function ensureVerifiedServerJar(filePath, profile) {
    let downloaded = false;
    let exists = await lstatRegularFile(filePath);
    if (!exists) {
        if (process.env.GAIUS_SMOKE_SERVER_JAR?.trim()) {
            throw new Error(`Configured GAIUS_SMOKE_SERVER_JAR does not exist: ${filePath}`);
        }
        await mkdir(path.dirname(filePath), {recursive: true});
        await downloadOfficialServerJar(filePath, profile);
        downloaded = true;
        exists = await lstatRegularFile(filePath);
    }
    if (!exists) throw new Error(`Vanilla server.jar is missing: ${filePath}`);
    const sha1 = await sha1File(filePath);
    if (sha1 !== profile.official.serverSha1.toLowerCase()) {
        throw new Error(`server.jar SHA-1 mismatch for ${profile.id}: ${sha1} != ${profile.official.serverSha1}`);
    }
    return {path: filePath, sha1, downloaded};
}

export async function downloadOfficialServerJar(filePath, profile) {
    const manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    const manifestResponse = await fetch(manifestUrl);
    if (!manifestResponse.ok) {
        throw new Error(`Mojang version manifest returned HTTP ${manifestResponse.status}`);
    }
    const manifest = await manifestResponse.json();
    const entry = manifest.versions?.find((candidate) => candidate.id === profile.id);
    if (!entry?.url) throw new Error(`Mojang version manifest has no ${profile.id} metadata`);
    const metadataResponse = await fetch(entry.url);
    if (!metadataResponse.ok) {
        throw new Error(`Mojang ${profile.id} metadata returned HTTP ${metadataResponse.status}`);
    }
    const metadata = await metadataResponse.json();
    const download = metadata.downloads?.server;
    if (!download?.url || download.sha1?.toLowerCase() !== profile.official.serverSha1.toLowerCase()) {
        throw new Error(`Mojang ${profile.id} server metadata SHA-1 does not match the active profile`);
    }
    const response = await fetch(download.url);
    if (!response.ok) throw new Error(`Mojang ${profile.id} server.jar returned HTTP ${response.status}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    const actualSha1 = createHash("sha1").update(bytes).digest("hex");
    if (actualSha1 !== profile.official.serverSha1.toLowerCase()) {
        throw new Error(`Downloaded ${profile.id} server.jar SHA-1 mismatch: ${actualSha1}`);
    }
    const temporaryPath = `${filePath}.download-${process.pid}-${Date.now()}-${Math.random()
            .toString(16).slice(2)}.tmp`;
    // wx plus a unique name means concurrent smoke runs never overwrite each
    // other. Keep a failed temporary artifact for post-mortem evidence.
    await writeFile(temporaryPath, bytes, {flag: "wx"});
    try {
        await lstat(filePath);
        throw new Error(`Refusing to overwrite an existing server.jar: ${filePath}`);
    }
    catch (error) {
        if (error?.code !== "ENOENT") throw error;
    }
    await rename(temporaryPath, filePath);
}

export async function resolveJavaExecutable(requiredVersion, profileId) {
    const candidates = [];
    const addCandidate = (value) => {
        if (!value) return;
        const candidate = nativePath(value);
        candidates.push(candidate);
        if (path.extname(candidate) === "" && /[\\/]/u.test(candidate)) {
            candidates.push(`${candidate}.exe`);
        }
    };
    addCandidate(process.env.GAIUS_JAVA);
    for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]) {
        if (!home) continue;
        const normalized = nativePath(home);
        addCandidate(path.join(normalized, "bin", "java"));
        addCandidate(path.join(normalized, "bin", "java.exe"));
    }
    candidates.push("java");
    const diagnostics = [];
    for (const candidate of [...new Set(candidates)]) {
        if (candidate !== "java" && candidate !== "java.exe") {
            try {
                const info = await lstat(candidate);
                if (!info.isFile()) continue;
            }
            catch (error) {
                if (error?.code === "ENOENT") continue;
                diagnostics.push(`${candidate}: ${error.message}`);
                continue;
            }
        }
        const result = await probeJava(candidate);
        if (result.error !== undefined) {
            diagnostics.push(`${candidate}: ${result.error}`);
            continue;
        }
        if (result.major >= requiredVersion) return candidate;
        diagnostics.push(`${candidate}: Java ${result.major} is older than ${requiredVersion}`);
    }
    throw new Error(`No compatible Java executable for Minecraft ${profileId}: ${diagnostics.join("; ")}`);
}

export async function probeJava(candidate) {
    return await new Promise((resolve) => {
        let output = "";
        let settled = false;
        let timer;
        const child = spawn(candidate, ["-version"], {
            stdio: ["ignore", "pipe", "pipe"],
            windowsHide: true,
        });
        const finish = (value) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve(value);
        };
        child.stdout.setEncoding("utf8");
        child.stderr.setEncoding("utf8");
        child.stdout.on("data", (chunk) => { output += chunk; });
        child.stderr.on("data", (chunk) => { output += chunk; });
        child.once("error", (error) => finish({error: error.message}));
        child.once("close", (code) => {
            if (code !== 0) {
                finish({error: `exited ${code}: ${output.trim()}`});
                return;
            }
            const match = output.match(/version\s+["']?(\d+)/iu) ||
                output.match(/(?:openjdk|java)\s+(\d+)/iu);
            finish(match ? {major: Number(match[1])} : {error: `could not parse version: ${output.trim()}`});
        });
        timer = setTimeout(() => {
            child.kill();
            finish({error: "timed out probing Java"});
        }, 5000);
    });
}

// server.properties for a small, deterministic smoke server. `overrides` wins
// over the defaults (online-mode, level-type, view-distance, ...).
export function serverProperties(serverPort, overrides = {}) {
    const properties = {
        "accepts-transfers": "false",
        "allow-flight": "true",
        "allow-nether": "false",
        "difficulty": "peaceful",
        "enable-command-block": "false",
        "enable-query": "false",
        "enable-rcon": "false",
        "enable-status": "true",
        "enforce-secure-profile": "false",
        "gamemode": "creative",
        "generate-structures": "false",
        "level-name": "world",
        "level-seed": "1",
        "level-type": "minecraft:flat",
        "log-ips": "false",
        "max-players": "4",
        "motd": "Gaius vanilla smoke",
        "network-compression-threshold": "256",
        "online-mode": "true",
        "pause-when-empty-seconds": "0",
        "player-idle-timeout": "0",
        "prevent-proxy-connections": "false",
        "pvp": "false",
        "rate-limit": "0",
        "server-ip": "127.0.0.1",
        "server-port": String(serverPort),
        "simulation-distance": "2",
        "spawn-animals": "false",
        "spawn-monsters": "false",
        "spawn-npcs": "false",
        "spawn-protection": "0",
        "sync-chunk-writes": "false",
        "use-native-transport": "false",
        "view-distance": "2",
        "white-list": "false",
        ...overrides,
    };
    return Object.entries(properties).map(([key, value]) => `${key}=${value}`).join("\n") + "\n";
}

// Start `java -jar server.jar nogui` in workDirectory and wait for "Done (".
// Returns {process, output(), stop()}; output is accumulated stdout+stderr.
export async function startVanillaServer({javaExecutable, serverJar, workDirectory, jvmArguments = [],
        startupTimeoutMs = 180000, label = "vanilla server"}) {
    let output = "";
    let spawnError;
    const child = spawn(javaExecutable, [...jvmArguments, "-jar", serverJar, "nogui"], {
        cwd: workDirectory,
        stdio: ["pipe", "pipe", "pipe"],
        windowsHide: true,
    });
    child.once("error", (error) => {
        spawnError = error;
        output += `\nJava process error: ${error.stack || error}\n`;
    });
    for (const stream of [child.stdout, child.stderr]) {
        stream.setEncoding("utf8");
        stream.on("data", (chunk) => { output += chunk; });
    }
    const handle = {
        process: child,
        output: () => output,
        /** Writes one console command (for example "time set day") to the server's stdin. */
        command(text) {
            if (child.exitCode !== null || child.stdin.destroyed) return false;
            child.stdin.write(`${text}\n`);
            return true;
        },
        async stop(timeoutMs = 15000) {
            if (child.exitCode === null && !child.stdin.destroyed) {
                try { child.stdin.write("stop\n"); } catch { /* already gone */ }
            }
            if (child.exitCode === null) {
                await Promise.race([
                    new Promise((resolve) => child.once("exit", resolve)),
                    delay(timeoutMs).then(() => {
                        if (child.exitCode === null) child.kill("SIGTERM");
                    }),
                ]);
            }
        },
    };
    try {
        await waitFor(
                () => output.includes("Done (") || child.exitCode !== null || spawnError !== undefined,
                `${label} startup`, startupTimeoutMs);
    }
    catch (error) {
        await handle.stop();
        throw new Error(`${error.message}\n${output}`);
    }
    if (spawnError !== undefined || child.exitCode !== null || !output.includes("Done (")) {
        throw new Error(`${label} failed to start:\n${output}`);
    }
    return handle;
}
