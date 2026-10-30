#!/usr/bin/env node
// JVM parity and speed check of the WasmGC multi-noise biome lookup patch
// (MinecraftClientPatcher.patchClimateRTreeBrowserFlatIndex -> GaiusClimateIndex).
//
//   node port/scripts/climate-rtree-parity.mjs [--profile 26.3] [--patched <patched client jar>]
//        [--scale N] [--bench-rounds N] [--out <dir>]
//
// Without --patched the real patch chain runs first (patchkit check, wasmgc target, kept scratch
// directory) and its patched client.jar is used. The patched Climate$RTree, Climate$RTree$SubTree
// and Climate$ParameterList, the helpers GaiusClimateIndex and GaiusClimateSubTree compiled from
// the profile's sources, and the JVM pulse shim of worldgen-seed-parity are put in front of the
// vanilla jar; climate-rtree-parity/ClimateRTreeParity.java then runs the same seeded lookups in
// an isolated vanilla class loader and in the patched one and requires every lookup to return the
// same list entry (per leaf, including ties decided by the per-thread lastResult history). It
// also times the overworld chunk-fill workload in both and counts the distance evaluations of
// both searches. Exit status: 0 parity, 1 mismatch, 2 environment.

import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {sourceFile} from "../tools/sourcesets/sourcesets.mjs";
import {RENDERPEARL_PROFILE} from "./verification-profiles.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const nativePath = (value) => process.platform === "win32" && /^\/[A-Za-z]\//.test(value)
  ? `${value[1].toUpperCase()}:${value.slice(2)}` : value;

const options = {profile: RENDERPEARL_PROFILE.id, patched: null, scale: "1", benchRounds: "7", out: null};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  const value = () => {
    if (i + 1 >= argv.length) usage(`${argv[i]} needs a value`);
    return argv[++i];
  };
  switch (argv[i]) {
    case "--profile": options.profile = value(); break;
    case "--patched": options.patched = resolve(nativePath(value())); break;
    case "--scale": options.scale = value(); break;
    case "--bench-rounds": options.benchRounds = value(); break;
    case "--out": options.out = resolve(nativePath(value())); break;
    default: usage(`unknown argument ${argv[i]}`);
  }
}

function usage(message) {
  console.error(`climate-rtree-parity: ${message}`);
  process.exit(2);
}

const PATCHED_CLASSES = [
  "net/minecraft/world/level/biome/Climate$RTree",
  "net/minecraft/world/level/biome/Climate$RTree$SubTree",
  "net/minecraft/world/level/biome/Climate$ParameterList",
];
const HELPERS = [
  "net/minecraft/world/level/biome/GaiusClimateIndex.java",
  "net/minecraft/world/level/biome/GaiusClimateSubTree.java",
];

const workRoot = join(repositoryRoot, "port", "work", options.profile);
const vanillaJar = join(workRoot, "client-named.jar");
if (!existsSync(vanillaJar)) usage(`missing ${vanillaJar}`);
const libraries = readFileSync(join(workRoot, "classpath.txt"), "utf8")
  .trim().split(":").filter(Boolean).map(nativePath);
const vanillaPath = [vanillaJar, ...libraries];

const scratch = mkdtempSync(join(options.out ?? tmpdir(), "gaius-climate-rtree-"));
const run = (tool, args, label, extra = {}) => {
  const result = spawnSync(tool, args, {encoding: "utf8", maxBuffer: 256 * 1024 * 1024, ...extra});
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
    const line = output.split(/\r?\n/).find((entry) => entry.includes("patchClimateRTreeBrowserFlatIndex"));
    console.log(line ?? "patchClimateRTreeBrowserFlatIndex: no PATCHKIT line");
    const kept = output.match(/PATCHKIT_SCRATCH (.+)/);
    if (!kept) usage("patchkit did not keep its scratch directory");
    patchedJar = join(kept[1].trim(), `${options.profile}-wasmgc`, "client.jar");
  }
  const overlay = join(scratch, "patched-classes");
  mkdirSync(overlay, {recursive: true});
  run("jar", ["xf", patchedJar, ...PATCHED_CLASSES.map((owner) => `${owner}.class`)],
    "extract patched Climate classes", {cwd: overlay});
  for (const owner of PATCHED_CLASSES) {
    if (!existsSync(join(overlay, `${owner}.class`))) usage(`${patchedJar} has no ${owner}.class`);
  }

  const helperSources = join(scratch, "helper-sources");
  const helpers = join(scratch, "helper-classes");
  mkdirSync(helpers, {recursive: true});
  const sources = [];
  for (const helper of HELPERS) {
    const source = sourceFile(options.profile, "src", helper, {target: "wasmgc"});
    if (source == null) usage(`profile ${options.profile} has no ${helper}`);
    const target = join(helperSources, helper);
    mkdirSync(dirname(target), {recursive: true});
    writeFileSync(target, source.text);
    sources.push(target);
  }
  // The patched SubTree keeps vanilla's search (the fallback) with its deep pulse.
  sources.push(join(repositoryRoot,
    "port/scripts/worldgen-seed-parity/shims/dev/gaius/browser/BrowserWorldgenDeepCheckpoint.java"));
  run("javac", ["-J-Duser.language=en", "--release", "25", "-proc:none", "-nowarn",
    "-cp", vanillaPath.join(delimiter), "-d", helpers, ...sources], "compile helpers");

  const harness = join(scratch, "harness-classes");
  mkdirSync(harness, {recursive: true});
  run("javac", ["-J-Duser.language=en", "--release", "25", "-proc:none", "-nowarn",
    "-cp", vanillaPath.join(delimiter), "-d", harness,
    join(repositoryRoot, "port/scripts/climate-rtree-parity/ClimateRTreeParity.java")], "compile harness");

  // Three copies of the library class path exceed the Windows command line: use an @argfile.
  const quote = (value) => `"${value.replace(/\\/g, "/")}"`;
  const argfile = join(scratch, "java-args.txt");
  writeFileSync(argfile, ["-Xmx3g", "-Duser.language=en",
    "-cp", quote([harness, ...vanillaPath].join(delimiter)),
    "dev.gaius.parity.ClimateRTreeParity",
    "--harness", quote(harness),
    "--vanilla", quote(vanillaPath.join(delimiter)),
    "--patched", quote([overlay, helpers, ...vanillaPath].join(delimiter)),
    "--scale", options.scale, "--bench-rounds", options.benchRounds].join("\n"));
  const result = spawnSync("java", [`@${argfile}`],
    {encoding: "utf8", maxBuffer: 256 * 1024 * 1024, stdio: ["ignore", "pipe", "inherit"]});
  if (result.error) usage(`java: ${result.error.message}`);
  process.stdout.write(result.stdout ?? "");
  if (options.out) writeFileSync(join(options.out, "climate-rtree-parity.log"), result.stdout ?? "");
  process.exitCode = result.status === 0 ? 0 : 1;
} finally {
  if (!options.out) rmSync(scratch, {recursive: true, force: true});
}
