#!/usr/bin/env node
// JVM equivalence check of the chunk-generation hot-path patches of
// port/tools/src/main/java/dev/gaius/tools/patches/worldgen/WorldgenHotPathPatches.java:
//   MinecraftClientPatcher.patchTagKeyBrowserHashCodeCache
//   MinecraftClientPatcher.patchWorldGenRegionBrowserCachedHeight
//   MinecraftClientPatcher.patchWorldGenRegionBrowserLastChunk      (not applicable to 26.2, 1.21.11)
//   MinecraftClientPatcher.patchMultiNoiseBiomeSourceBrowserCachedParameters
//
//   node port/scripts/mc1-hotpath-parity.mjs [--profile <id>] [--patched <patched client jar>]
//        [--scale N] [--bench ROUNDS] [--out <dir>]
//
// Without --patched the real patch chain runs first (patchkit check, wasmgc target, kept scratch
// directory) and its patched client.jar is used. The patched TagKey, WorldGenRegion and
// MultiNoiseBiomeSource classes are put in front of the vanilla jar; mc1-hotpath-parity/
// Mc1HotPathParity.java then (1) digests a block/item tag membership table in a vanilla and a
// patched class-loader universe and (2) feeds the vanilla and the patched variant of each class
// the same objects (same keys, level, chunk table, parameter list) and requires identical results.
// For the whole-world proof run port/scripts/worldgen-seed-parity.mjs --patched with the same jar.
// Exit status: 0 parity, 1 mismatch, 2 environment.

import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {RENDERPEARL_PROFILE} from "./verification-profiles.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const options = {profile: RENDERPEARL_PROFILE.id, patched: null, scale: "1", bench: "0", benchOnly: false, out: null};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  const value = () => {
    if (i + 1 >= argv.length) usage(`${argv[i]} needs a value`);
    return argv[++i];
  };
  switch (argv[i]) {
    case "--profile": options.profile = value(); break;
    case "--patched": options.patched = resolve(value()); break;
    case "--scale": options.scale = value(); break;
    case "--bench": options.bench = value(); break;
    case "--bench-only": options.benchOnly = true; break;
    case "--out": options.out = resolve(value()); break;
    default: usage(`unknown argument ${argv[i]}`);
  }
}

function usage(message) {
  console.error(`mc1-hotpath-parity: ${message}`);
  process.exit(2);
}

const PATCHED_CLASSES = [
  "net/minecraft/tags/TagKey",
  "net/minecraft/server/level/WorldGenRegion",
  "net/minecraft/world/level/biome/MultiNoiseBiomeSource",
];

const workRoot = join(repositoryRoot, "port", "work", options.profile);
const vanillaJar = join(workRoot, "client-named.jar");
if (!existsSync(vanillaJar)) usage(`missing ${vanillaJar}`);
const libraries = readFileSync(join(workRoot, "classpath.txt"), "utf8")
  .trim().split(":").filter(Boolean);
const vanillaPath = [vanillaJar, ...libraries];
const javaHome = process.env.GAIUS_JAVA_HOME;
const tool = (name) => javaHome ? join(javaHome, "bin", name) : name;

const scratch = mkdtempSync(join(options.out ?? tmpdir(), "gaius-mc1-parity-"));
const run = (command, args, label, extra = {}) => {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 * 1024 * 1024, ...extra});
  if (result.status !== 0) {
    console.error(`${label} failed (${result.status})\n${result.stdout}\n${result.stderr}`);
    process.exit(2);
  }
  return result.stdout;
};

try {
  let patchedJar = options.patched;
  if (!patchedJar) {
    const kit = join(scratch, "patchkit");
    mkdirSync(kit, {recursive: true});
    const output = run(process.execPath, [join(repositoryRoot, "port/tools/patchkit/patchkit.mjs"), "check",
      "--profile", options.profile, "--target", "wasmgc", "--keep", "--scratch", kit,
      "--out", join(kit, "manifests")], "patchkit check");
    for (const line of output.split(/\r?\n/)) {
      if (/TagKey|WorldGenRegion|MultiNoiseBiomeSource/.test(line)) console.log(line);
    }
    const kept = output.match(/PATCHKIT_SCRATCH (.+)/);
    if (!kept) usage("patchkit did not keep its scratch directory");
    patchedJar = join(kept[1].trim(), `${options.profile}-wasmgc`, "client.jar");
  }
  const overlay = join(scratch, "patched-classes");
  mkdirSync(overlay, {recursive: true});
  run(tool("jar"), ["xf", patchedJar, ...PATCHED_CLASSES.map((owner) => `${owner}.class`)],
    "extract patched classes", {cwd: overlay});
  for (const owner of PATCHED_CLASSES) {
    if (!existsSync(join(overlay, `${owner}.class`))) usage(`${patchedJar} has no ${owner}.class`);
  }

  const harness = join(scratch, "harness-classes");
  mkdirSync(harness, {recursive: true});
  run(tool("javac"), ["-J-Duser.language=en", "--release", "25", "-proc:none", "-nowarn",
    "-cp", vanillaPath.join(delimiter), "-d", harness,
    join(repositoryRoot, "port/scripts/mc1-hotpath-parity/Mc1HotPathParity.java")], "compile harness");

  const quote = (value) => `"${value.replace(/\\/g, "/")}"`;
  const argfile = join(scratch, "java-args.txt");
  writeFileSync(argfile, ["-Xmx3g", "-Duser.language=en", "-Xss16m",
    "-cp", quote([harness, ...vanillaPath].join(delimiter)),
    "dev.gaius.parity.Mc1HotPathParity",
    "--harness", quote(harness),
    "--vanilla", quote(vanillaPath.join(delimiter)),
    "--patched", quote([overlay, ...vanillaPath].join(delimiter)),
    "--vanilla-jar", quote(vanillaJar),
    "--scale", options.scale, "--bench", options.bench, ...(options.benchOnly ? ["--bench-only"] : [])].join("\n"));
  const result = spawnSync(tool("java"), [`@${argfile}`],
    {encoding: "utf8", maxBuffer: 256 * 1024 * 1024, stdio: ["ignore", "pipe", "inherit"]});
  if (result.error) usage(`java: ${result.error.message}`);
  process.stdout.write(result.stdout ?? "");
  if (options.out) writeFileSync(join(options.out, "mc1-hotpath-parity.log"), result.stdout ?? "");
  process.exitCode = result.status === 0 ? 0 : 1;
} finally {
  if (!options.out) rmSync(scratch, {recursive: true, force: true});
}
