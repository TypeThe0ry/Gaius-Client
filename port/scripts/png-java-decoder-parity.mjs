#!/usr/bin/env node
// Browser STBImage override parity: the PNG decoder of port/overrides/libraries/lwjgl-stb in the
// working tree against the same files at a reference commit, on the JVM.
//
// Both builds compile with JVM heap shims of MemoryUtil and BrowserMemory
// (png-java-decoder-parity/shims) and run in separate class loaders. PngDecoderParity decodes
// every PNG of the profile's client-named.jar plus synthetic PNGs (every color type and bit
// depth, every scanline filter and random filter mixes, tRNS, split IDAT chunks, malformed
// data) for desired channels 0 to 4 and both vertical orientations, and compares width,
// height, channels, the input position, every output byte (SHA-256) and the failure reason.
//
//   node port/scripts/png-java-decoder-parity.mjs [--profile 26.3] [--reference-ref <commit>]
//        [--out-dir <dir>]
//
// The default reference is e657295d5 (the decoder before the unfilter and 8-bit row fast paths).
// Exit status: 0 parity, 1 a difference or a failed run, 2 usage.

import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, readdirSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const here = join(root, "port/scripts/png-java-decoder-parity");
const STB = "port/overrides/libraries/lwjgl-stb/src/main/java/org/lwjgl/stb";
const FILES = ["STBImage.java", "PngPixels.java", "BrowserPng.java"];

function usage(message) {
  console.error(`png-java-decoder-parity: ${message}`);
  process.exit(2);
}

const options = {profile: "26.3", reference: "e657295d5", outDir: join(tmpdir(), "gaius-png-java-decoder-parity")};
for (let index = 2; index < process.argv.length; index++) {
  const argument = process.argv[index];
  const value = () => process.argv[++index] ?? usage(`${argument} needs a value`);
  switch (argument) {
    case "--profile": options.profile = value(); break;
    case "--reference-ref": options.reference = value(); break;
    case "--out-dir": options.outDir = resolve(value()); break;
    default: usage(`unknown argument ${argument}`);
  }
}

function nativePath(value) {
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z]\//.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
}

function javaTool(name) {
  const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
  const suffix = process.platform === "win32" ? ".exe" : "";
  return home ? join(nativePath(home), "bin", `${name}${suffix}`) : `${name}${suffix}`;
}

function run(command, args, label, extra = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 * 1024 * 1024, ...extra});
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${label} failed (exit ${result.status})\n${`${result.stdout || ""}${result.stderr || ""}`.slice(-8000)}`);
  }
  return result.stdout || "";
}

function javaSources(directory) {
  const sources = [];
  for (const entry of readdirSync(directory, {withFileTypes: true})) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) sources.push(...javaSources(path));
    else if (entry.name.endsWith(".java")) sources.push(path);
  }
  return sources;
}

const jar = join(root, "port/work", options.profile, "client-named.jar");
if (!existsSync(jar)) usage(`missing ${jar} (run fetch-version.sh and remap-client.sh)`);
rmSync(options.outDir, {recursive: true, force: true});
const shims = javaSources(join(here, "shims"));

const referenceSources = join(options.outDir, "reference-src");
mkdirSync(referenceSources, {recursive: true});
for (const file of FILES) {
  const text = run("git", ["-C", root, "show", `${options.reference}:${STB}/${file}`], `git show ${file}`);
  writeFileSync(join(referenceSources, file), text);
}
const builds = {
  reference: FILES.map((file) => join(referenceSources, file)),
  candidate: FILES.map((file) => join(root, STB, file)),
};
for (const [name, sources] of Object.entries(builds)) {
  run(javaTool("javac"), ["-J-Duser.language=en", "--release", "21", "-proc:none", "-nowarn",
    "-d", join(options.outDir, name), ...shims, ...sources], `compile ${name}`);
}
const harness = join(options.outDir, "harness");
run(javaTool("javac"), ["-J-Duser.language=en", "--release", "21", "-proc:none", "-d", harness,
  join(here, "PngDecoderParity.java")], "compile PngDecoderParity");
const result = spawnSync(javaTool("java"), ["-Xmx3g", "-cp", harness, "PngDecoderParity",
  join(options.outDir, "reference"), join(options.outDir, "candidate"), jar], {encoding: "utf8", maxBuffer: 64 * 1024 * 1024});
process.stdout.write(result.stdout || "");
process.stderr.write(result.stderr || "");
const line = (result.stdout || "").split(/\r?\n/).find((text) => text.startsWith("PNG_PARITY "));
const pass = result.status === 0 && line && / mismatches=0$/.test(line);
console.log(`PNG_JAVA_DECODER_PARITY ${options.profile} reference=${options.reference} ${pass ? "PASS" : "FAIL"}`);
process.exit(pass ? 0 : 1);
