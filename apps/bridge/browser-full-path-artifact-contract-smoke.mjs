#!/usr/bin/env node

import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {readFile} from "node:fs/promises";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {gunzipSync} from "node:zlib";

const repository = path.resolve(fileURLToPath(new URL("../..", import.meta.url)));

// These are stable JSBody/property strings.  Normal TeaVM method names may be
// minified, so the gate deliberately does not depend on Java symbol spelling.
const REQUIRED_MULTIPLAYER_MARKERS = Object.freeze([
  "__gaiusClientPacketDrainEnabled",
  "clientPacketDrainSession",
  "clientPacketDrainDemandToken",
  "clientPacketDrainDisabledRequests",
]);

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function resolveProfilePath() {
  const configured = process.env.GAIUS_VERSION_PROFILE_PATH || "versions/26.2.json";
  const relative = configured.replaceAll("\\", "/").replace(/^port\//u, "");
  const candidate = path.resolve(repository, "port", relative);
  assert.ok(candidate.startsWith(path.resolve(repository, "port") + path.sep),
    `profile path escaped repository: ${configured}`);
  return candidate;
}

function resolveDistRoot(profileId) {
  const configured = process.env.GAIUS_DIST_DIRECTORY;
  const candidate = configured
    ? path.resolve(repository, configured)
    : path.resolve(repository, "port", "web", "dist", profileId);
  return candidate;
}

function markerCounts(text) {
  return Object.fromEntries(REQUIRED_MULTIPLAYER_MARKERS.map((marker) => [
    marker,
    text.split(marker).length - 1,
  ]));
}

// gaius-b7 (build-portable-html.py): every 8 characters carry 7 bytes as 7-bit groups; the
function decodePayload(text, length) {
  return Buffer.from(text, "base64").subarray(0, length);
}

function embeddedClassesGzip(html) {
  const marker = "const portablePayload = ";
  const start = html.indexOf(marker);
  assert.ok(start >= 0, "portable HTML has no portable payload index");
  // The index JSON holds names, numbers and hashes only (no ';'), and the page
  // may have been written with CRLF line endings on Windows.
  const end = html.indexOf(";", start + marker.length);
  assert.ok(end > start, "portable payload index is truncated");
  const index = JSON.parse(html.slice(start + marker.length, end));
  assert.equal(index?.encoding, "gaius-b64-v1", "portable payload encoding mismatch");
  const info = index?.assets?.classes;
  assert.ok(info && info.gzip === true && info.bytes > 0,
    "portable HTML has no embedded classes asset");
  const pattern = /<script type="text\/x-gaius-b64" data-gaius-asset="classes" data-part="(\d+)">([^<]*)<\/script>/gu;
  const parts = [];
  for (const match of html.matchAll(pattern)) {
    assert.equal(Number(match[1]), parts.length, "portable classes parts are out of order");
    parts.push(match[2]);
  }
  assert.equal(parts.length, info.parts, "portable classes part count mismatch");
  const bytes = decodePayload(parts.join(""), info.bytes);
  assert.equal(sha256(bytes), info.sha256, "portable classes gzip hash mismatch");
  return bytes;
}

async function inspectArtifact() {
  const profilePath = resolveProfilePath();
  const profile = JSON.parse(await readFile(profilePath, "utf8"));
  const profileId = String(profile.id || "");
  assert.ok(profileId, "version profile has no id");
  const distRoot = resolveDistRoot(profileId);
  const classesPath = path.join(distRoot, "classes.js");
  const classesGzipPath = path.join(distRoot, "classes.js.gz");
  const htmlPath = path.join(distRoot, "Gaius.html");
  const manifestPath = path.join(distRoot, "Gaius.manifest.json");
  const [classes, classesGzip, html, manifest] = await Promise.all([
    readFile(classesPath),
    readFile(classesGzipPath),
    readFile(htmlPath, "utf8"),
    readFile(manifestPath, "utf8").then(JSON.parse),
  ]);
  const classesText = classes.toString("utf8");
  const rawMarkers = markerCounts(classesText);
  const embeddedGzip = embeddedClassesGzip(html);
  const embeddedClasses = gunzipSync(embeddedGzip);
  const embeddedMarkers = markerCounts(embeddedClasses.toString("utf8"));
  const inflatedRaw = gunzipSync(classesGzip);
  const nestedProfile = manifest?.buildIdentity?.profile;
  const manifestProtocol = manifest?.buildIdentity?.protocol;

  assert.deepEqual(inflatedRaw, classes,
    "classes.js.gz does not expand to the shipped classes.js");
  assert.deepEqual(embeddedClasses, classes,
    "Gaius.html embedded classes do not match shipped classes.js");
  assert.equal(manifest?.kind, "gaius-portable-artifact",
    "portable manifest kind is missing");
  assert.equal(manifest?.profile, profileId, "manifest profile mismatch");
  assert.equal(manifest?.profilePath, path.relative(
    path.resolve(repository, "port"), profilePath).replaceAll(path.sep, "/"),
  "manifest profile path mismatch");
  assert.equal(nestedProfile?.id, profileId, "nested manifest profile id mismatch");
  assert.equal(nestedProfile?.protocolVersion, profile.protocolVersion,
    "nested manifest protocol mismatch");
  assert.equal(nestedProfile?.worldVersion, profile.worldVersion,
    "nested manifest world mismatch");
  assert.equal(manifestProtocol?.minecraftProtocolVersion, profile.protocolVersion,
    "build identity protocol mismatch");
  assert.equal(manifest?.classesJs?.rawBytes, classes.byteLength,
    "manifest classes raw byte count mismatch");
  assert.equal(manifest?.classesJs?.rawSha256, sha256(classes),
    "manifest classes raw hash mismatch");
  const missingMarkers = REQUIRED_MULTIPLAYER_MARKERS.flatMap((marker) => {
    const missing = [];
    if (!(rawMarkers[marker] > 0)) missing.push(`classes.js:${marker}`);
    if (!(embeddedMarkers[marker] > 0)) {
      missing.push(`Gaius.html:embedded:${marker}`);
    }
    return missing;
  });
  assert.equal(missingMarkers.length, 0,
    `canonical artifact is missing multiplayer markers: ${JSON.stringify({
      missing: missingMarkers,
      rawMarkers,
      embeddedMarkers,
    })}`);

  return {
    schemaVersion: "gaius.browser-full-path-artifact-contract.v1",
    status: "pass",
    profile: {
      id: profileId,
      protocolVersion: profile.protocolVersion,
      worldVersion: profile.worldVersion,
    },
    paths: {
      distRoot,
      classes: classesPath,
      portableHtml: htmlPath,
      manifest: manifestPath,
    },
    artifact: {
      classesBytes: classes.byteLength,
      classesSha256: sha256(classes),
      classesGzipBytes: classesGzip.byteLength,
      embeddedClassesBytes: embeddedClasses.byteLength,
      rawMarkers,
      embeddedMarkers,
    },
    gates: {
      rawGzipRoundTrip: true,
      portableEmbeddingMatchesRaw: true,
      manifestIdentity: true,
      multiplayerMarkersPresent: true,
      strictLatencyAndStallGatesChanged: false,
      teaVmRuntimeProof: false,
      publicRelayRuntimeProof: false,
    },
  };
}

try {
  console.log(JSON.stringify(await inspectArtifact(), null, 2));
} catch (error) {
  const detail = error instanceof Error ? error.stack || error.message : String(error);
  console.error(JSON.stringify({
    schemaVersion: "gaius.browser-full-path-artifact-contract.v1",
    status: "fail",
    strictLatencyAndStallGatesChanged: false,
    teaVmRuntimeProof: false,
    publicRelayRuntimeProof: false,
    error: detail,
  }, null, 2));
  process.exitCode = 1;
}
