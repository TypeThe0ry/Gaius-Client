#!/usr/bin/env node
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {copyFileSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const outputIndex = process.argv.indexOf("--out");
assert.ok(outputIndex >= 0 && process.argv[outputIndex + 1], "usage: --out <scratch directory>");
const output = resolve(process.argv[outputIndex + 1]);
mkdirSync(output, {recursive: true});
const profileName = readdirSync(join(root, "port/versions")).filter(name => name.endsWith(".json")).sort()[0];
const originalProfile = JSON.parse(readFileSync(join(root, "port/versions", profileName), "utf8"));
const originalIndex = Buffer.from('{"objects":{}}\n');
const replacementIndex = Buffer.from('{"objects":{"replacement":{"hash":"example","size":1}}}\n');
const sha1 = bytes => createHash("sha1").update(bytes).digest("hex");

function run(name, {metadataBytes = originalIndex, cachedBytes = metadataBytes, pinnedBytes = originalIndex,
    alternateBytes = null} = {}) {
    const fixture = mkdtempSync(join(output, `${name}-`));
    const profile = structuredClone(originalProfile);
    profile.official.assetIndexSha1 = sha1(pinnedBytes);
    profile.official.assetIndexSha1Alternates = alternateBytes === null ? [] : [sha1(alternateBytes)];
    const work = join(fixture, "port/work", profile.id);
    for (const directory of ["port/scripts", "port/versions", `port/work/${profile.id}/assets/indexes`]) {
        mkdirSync(join(fixture, directory), {recursive: true});
    }
    copyFileSync(join(root, "port/scripts/check-version-profile.mjs"), join(fixture, "port/scripts/check-version-profile.mjs"));
    writeFileSync(join(fixture, "port/config.json"), JSON.stringify({versionProfile: `versions/${profileName}`}));
    writeFileSync(join(fixture, "port/versions", profileName), JSON.stringify(profile));
    if (profile.renderSeam && profile.renderSeam !== "none") {
        const seam = `port/mc/seams/${profile.renderSeam}`;
        mkdirSync(join(fixture, seam), {recursive: true});
        copyFileSync(join(root, seam, "seam.json"), join(fixture, seam, "seam.json"));
    }
    writeFileSync(join(work, "version.json"), JSON.stringify({
        id: profile.id, type: profile.releaseType, releaseTime: profile.official.releaseTime,
        javaVersion: {majorVersion: profile.javaVersion},
        downloads: {
            client: {sha1: profile.official.clientSha1},
            server: {sha1: profile.official.serverSha1},
            client_mappings: {sha1: profile.official.clientMappingsSha1},
        },
        assetIndex: {id: profile.official.assetIndexId, sha1: sha1(metadataBytes), size: metadataBytes.length},
    }));
    writeFileSync(join(work, "client-version.json"), JSON.stringify({
        id: profile.id, protocol_version: profile.protocolVersion, world_version: profile.worldVersion,
        java_version: profile.javaVersion,
        pack_version: {
            resource_major: profile.packVersions.resource.major, resource_minor: profile.packVersions.resource.minor,
            data_major: profile.packVersions.data.major, data_minor: profile.packVersions.data.minor,
        },
    }));
    if (cachedBytes !== null) {
        writeFileSync(join(work, "assets/indexes", `${profile.official.assetIndexId}.json`), cachedBytes);
    }
    const result = spawnSync(process.execPath, [join(fixture, "port/scripts/check-version-profile.mjs"), "--all", "--require-local"], {
        encoding: "utf8", windowsHide: true, timeout: 10000,
        env: {...process.env, GAIUS_VERSION_PROFILE_PATH: ""},
    });
    assert.ifError(result.error);
    const text = result.stdout + result.stderr;
    writeFileSync(join(fixture, "check.log"), text);
    return {status: result.status, text};
}

const matching = run("matching");
assert.equal(matching.status, 0, matching.text);
assert.equal(JSON.parse(matching.text).profiles[0].checkedLocalAssetIndex, true);

const corrupt = run("corrupt", {metadataBytes: replacementIndex, cachedBytes: originalIndex});
assert.equal(corrupt.status, 1, corrupt.text);
assert.match(corrupt.text, /cache is corrupt or mixed/);
assert.match(corrupt.text, /do not change the profile pin/);

const replacement = run("replacement", {metadataBytes: replacementIndex});
assert.equal(replacement.status, 1, replacement.text);
for (const expected of [sha1(originalIndex), sha1(replacementIndex), "version.json", "assets", "self-consistency is not proof", "publisher"]) {
    assert.ok(replacement.text.includes(expected), replacement.text);
}

const adopted = run("adopted", {metadataBytes: replacementIndex, pinnedBytes: replacementIndex});
assert.equal(adopted.status, 0, adopted.text);

const missingReplacement = run("missing-replacement", {metadataBytes: replacementIndex, cachedBytes: null});
assert.equal(missingReplacement.status, 1, missingReplacement.text);
assert.match(missingReplacement.text, /cached SHA-1: not present/);

const missingMatching = run("missing-matching", {cachedBytes: null});
assert.equal(missingMatching.status, 0, missingMatching.text);
assert.equal(JSON.parse(missingMatching.text).profiles[0].checkedLocalAssetIndex, false);
const alternate = run("alternate", {metadataBytes: replacementIndex, alternateBytes: replacementIndex});
assert.equal(alternate.status, 0, alternate.text);

const unknown = run("unknown", {metadataBytes: replacementIndex, pinnedBytes: originalIndex,
    alternateBytes: Buffer.from('{"objects":{"other":{}}}\n')});
assert.equal(unknown.status, 1, unknown.text);
assert.match(unknown.text, /does not match/);

console.log("VERSION_PROFILE_SMOKE_OK 8 offline cases");
