#!/usr/bin/env node

import {readFile} from "node:fs/promises";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const failures = [];

function fail(message) {
    failures.push(message);
}

async function readText(relativePath) {
    try {
        return await readFile(resolve(root, relativePath), "utf8");
    } catch (error) {
        fail(`${relativePath}: ${error.code === "ENOENT" ? "file is missing" : error.message}`);
        return null;
    }
}

const versionText = await readText("VERSION");
const version = versionText?.trim();
if (version !== undefined && !/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/u.test(version)) {
    fail(`VERSION: expected a semantic version, got ${JSON.stringify(version)}`);
}

let bridgePackage;
try {
    bridgePackage = JSON.parse(await readFile(resolve(root, "apps/bridge/package.json"), "utf8"));
} catch (error) {
    fail(`apps/bridge/package.json: ${error.message}`);
}

let bridgeLock;
try {
    bridgeLock = JSON.parse(await readFile(resolve(root, "apps/bridge/package-lock.json"), "utf8"));
} catch (error) {
    fail(`apps/bridge/package-lock.json: ${error.message}`);
}

if (version && bridgePackage?.version !== version) {
    fail(`apps/bridge/package.json: version ${JSON.stringify(bridgePackage?.version)} does not match VERSION ${version}`);
}
if (version && bridgeLock?.version !== version) {
    fail(`apps/bridge/package-lock.json: version ${JSON.stringify(bridgeLock?.version)} does not match VERSION ${version}`);
}
if (version && bridgeLock?.packages?.[""]?.version !== version) {
    fail(`apps/bridge/package-lock.json packages[""].version does not match VERSION ${version}`);
}

const pluginPom = await readText("apps/server-plugin/pom.xml");
const pluginVersion = pluginPom?.match(/<project\b[\s\S]*?<version>\s*([^<\s]+)\s*<\/version>/u)?.[1];
if (!pluginVersion) {
    fail("apps/server-plugin/pom.xml: project version is missing");
} else if (version && pluginVersion !== version) {
    fail(`apps/server-plugin/pom.xml: project version ${JSON.stringify(pluginVersion)} does not match VERSION ${version}`);
}

const readme = await readText("README.md");
const releasing = await readText("docs/releasing.md");
const pagesWorkflow = await readText(".github/workflows/pages.yml");
const enabledProfiles = [];
for (const profile of ["26.3", "26.2", "1.21.11"]) {
    try {
        const metadata = JSON.parse(await readFile(resolve(root, `port/versions/${profile}.json`), "utf8"));
        if (metadata.pages?.enabled !== true || JSON.stringify(metadata.pages?.runtimes) !== JSON.stringify(["wasm"])) {
            fail(`port/versions/${profile}.json: release pages must declare WasmGC-only runtimes`);
        }
        enabledProfiles.push(profile);
    } catch (error) {
        fail(`port/versions/${profile}.json: ${error.message}`);
    }
}
if (releasing) {
    for (const phrase of [
        "GAIUS_TEAVM_MAX_HEAP=8g",
        "GAIUS_TEAVM_MAX_HEAP=14g",
        "--skip-pass=memory-packing",
        "WebAssembly.validate",
        "wasm-data-check",
        "900,000,000",
        "Gaius-site-<profile>.tar.gz",
        "SHA256SUMS",
        "30 distinct passing cases",
        "runtimePolicy: \"wasm-only\"",
        "--no-js-fallback",
    ]) {
        if (!releasing.includes(phrase)) fail(`docs/releasing.md: missing v0.4 release requirement ${phrase}`);
    }
}
for (const phrase of [
    "Gaius-pages-matrix.json",
    "Gaius-site-*.tar.gz",
    "build-pages-site.py --publish-release",
    "pages-publish-size.json",
    "path: pages-publish",
]) {
    if (pagesWorkflow && !pagesWorkflow.includes(phrase)) fail(`.github/workflows/pages.yml: missing release assembly requirement ${phrase}`);
}
const releaseAssembler = await readText("port/scripts/gaius_pages_release.py");
if (releaseAssembler && (!releaseAssembler.includes("LIMIT_BYTES = 900_000_000")
    || !releaseAssembler.includes("total <= LIMIT_BYTES"))) {
    fail("port/scripts/gaius_pages_release.py: missing 900 MB release gate");
}
const releaseTrain = await readText("port/scripts/release-train.sh");
if (releaseTrain && (!releaseTrain.includes("--no-js-fallback") && !releaseTrain.includes("release-train.py"))) {
    fail("port/scripts/release-train.sh: missing WasmGC release entry point");
}
const requiredScreenshots = [
    "docs/images/gaius-main-menu.png",
    "docs/images/gaius-singleplayer.png",
    "docs/images/gaius-multiplayer.png",
    "docs/images/gaius-player-name.png",
];
const pngSignature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
for (const relativePath of requiredScreenshots) {
    if (readme && !readme.includes(relativePath)) {
        fail(`README.md: required screenshot is not referenced: ${relativePath}`);
    }
    try {
        const bytes = await readFile(resolve(root, relativePath));
        if (bytes.length < pngSignature.length || !bytes.subarray(0, pngSignature.length).equals(pngSignature)) {
            fail(`${relativePath}: file is not a PNG (invalid magic bytes)`);
        }
    } catch (error) {
        fail(`${relativePath}: ${error.code === "ENOENT" ? "file is missing" : error.message}`);
    }
}

if (failures.length > 0) {
    console.error("Release metadata check failed:");
    for (const failure of failures) console.error(`- ${failure}`);
    process.exitCode = 1;
} else {
    console.log(JSON.stringify({
        ok: true,
        version,
        pluginVersion,
        screenshots: requiredScreenshots,
        profiles: enabledProfiles,
    }, null, 2));
}
