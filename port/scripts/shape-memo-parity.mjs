#!/usr/bin/env node
// Block shape memo parity (MinecraftClientPatcher.patchBlockShapeFullMemo and
// patchWallBlockShapeMemo), on the JVM.
//
// The first patch keeps the first vanilla answer of Block.isShapeFullBlock for each VoxelShape
// instance in a new VoxelShape byte field; the second shares the shapes of equal wall states
// between wall blocks (dev/gaius/browser/BrowserWallShapes.java). Per profile:
//   1. the client patcher chain of the selected TeaVM target runs on a scratch copy of the vanilla
//      jar (patchkit.mjs check --keep, no TeaVM), or --patched-jar names an already patched jar;
//      its Block, VoxelShape and WallBlock classes, with the BrowserWallShapes helper, go in front
//      of the vanilla jar;
//   2. shape-memo-parity/ShapeFullBlockParity.java bootstraps the registries with vanilla classes,
//      then with the two patched classes, and prints digests of: the block state cache values
//      built from the answers during bootstrap (collision full block, solid render, skylight,
//      face sturdiness for every direction and support type), the boxes of every state's
//      outline, collision and occlusion shape (wall states separately), three rounds of
//      isShapeFullBlock over every shape of every block state plus fresh shapes, and the count of
//      answers that differ from the cache-free vanilla expression;
//   3. exact check: every compared line is equal, and no answer differs from the expression.
//
//   node port/scripts/shape-memo-parity.mjs [--profile <id>]... [--target wasmgc|js]
//        [--patched-jar <jar>] [--out-dir <dir>]
//
// Exit status: 0 parity, 1 a difference or a failed run, 2 usage.

import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, readFileSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const harnessSource = join(root, "port/scripts/shape-memo-parity/ShapeFullBlockParity.java");
const wallHelper = join(root, "port/src/main/java/dev/gaius/browser/BrowserWallShapes.java");
const PATCHED_CLASSES = ["net/minecraft/world/level/block/Block.class",
  "net/minecraft/world/phys/shapes/VoxelShape.class", "net/minecraft/world/level/block/WallBlock.class"];
const PATCH_IDS = ["MinecraftClientPatcher.patchBlockShapeFullMemo", "MinecraftClientPatcher.patchWallBlockShapeMemo"];

function usage(message) {
  console.error(`shape-memo-parity: ${message}`);
  process.exit(2);
}

function parseArgs(argv) {
  const options = {profiles: [], target: "wasmgc", patchedJar: null,
    outDir: join(tmpdir(), "gaius-shape-memo-parity")};
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    const value = () => argv[++index] ?? usage(`${argument} needs a value`);
    switch (argument) {
      case "--profile": options.profiles.push(value()); break;
      case "--target": options.target = value(); break;
      case "--patched-jar": options.patchedJar = resolve(value()); break;
      case "--out-dir": options.outDir = resolve(value()); break;
      default: usage(`unknown argument ${argument}`);
    }
  }
  if (!["js", "wasmgc"].includes(options.target)) usage("--target must be js or wasmgc");
  if (options.patchedJar && options.profiles.length !== 1) usage("--patched-jar needs exactly one --profile");
  if (options.profiles.length === 0) options.profiles.push("26.3");
  return options;
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

/** port/work/<id>/classpath.txt without the client jars (fetch-version.sh writes a ':' list of /d/... paths). */
function libraries(work) {
  const text = readFileSync(join(work, "classpath.txt"), "utf8").trim();
  const entries = text.startsWith("/") ? text.split(":") : text.split(delimiter);
  return entries.filter(Boolean).map(nativePath).filter((entry) => !/client-(named|original)\.jar$/.test(entry));
}

function patchedJar(profile, target, outDir) {
  const scratch = join(outDir, "patchkit");
  mkdirSync(scratch, {recursive: true});
  const stdout = run(process.execPath, [join(root, "port/tools/patchkit/patchkit.mjs"), "check",
    "--profile", profile, "--target", target, "--keep", "--scratch", scratch,
    "--out", join(scratch, "manifests"), "--compare", "--warn"], `patchkit check ${profile} ${target}`, {cwd: root});
  const line = stdout.split(/\r?\n/).find((text) => text.startsWith("PATCHKIT_SCRATCH "));
  if (!line) throw new Error(`patchkit check ${profile} ${target}: no PATCHKIT_SCRATCH line`);
  const directory = line.slice("PATCHKIT_SCRATCH ".length).trim();
  const manifest = JSON.parse(readFileSync(join(scratch, "manifests", profile,
    `patch-manifest.${target}.json`), "utf8"));
  for (const id of PATCH_IDS) {
    const entry = manifest.patches.find((patch) => patch.id === id);
    console.log(`SHAPE_MEMO_PATCH ${profile} ${target} ${id} ${entry ? entry.outcome : "absent"}`);
    if (!entry || entry.outcome !== "APPLIED") throw new Error(`${id} is not APPLIED for ${profile} ${target}`);
  }
  return join(directory, `${profile}-${target}`, "client.jar");
}

const COMPARED = /^(states|stateCache|stateShapes|wallShapes|shapes|isShapeFullBlock\.round\d|expressionMismatches) /;

/** The harness lines; after Bootstrap, System.out goes through the logger ("[...]: [STDOUT]: "). */
function lines(stdout) {
  return stdout.split(/\r?\n/).map((line) => {
    const index = line.indexOf("[STDOUT]: ");
    return index < 0 ? line : line.slice(index + "[STDOUT]: ".length);
  });
}

function compared(stdout) {
  return lines(stdout).filter((line) => COMPARED.test(line));
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  let failed = false;
  for (const profile of options.profiles) {
    const work = join(root, "port/work", profile);
    const vanillaJar = join(work, "client-named.jar");
    if (!existsSync(vanillaJar)) usage(`missing ${vanillaJar} (run fetch-version.sh and remap-client.sh)`);
    const outDir = join(options.outDir, `${profile}-${options.target}`);
    rmSync(outDir, {recursive: true, force: true});
    mkdirSync(outDir, {recursive: true});
    const jar = options.patchedJar ?? patchedJar(profile, options.target, outDir);
    const patched = join(outDir, "patched-classes");
    mkdirSync(patched, {recursive: true});
    run(javaTool("jar"), ["--extract", "--file", jar, ...PATCHED_CLASSES], `extract patched classes`, {cwd: patched});
    for (const entry of PATCHED_CLASSES) {
      if (!existsSync(join(patched, entry))) throw new Error(`${jar} has no ${entry}`);
    }
    const vanillaClasspath = [vanillaJar, ...libraries(work)];
    const harness = join(outDir, "harness");
    const release = String(JSON.parse(readFileSync(join(root, "port/versions", `${profile}.json`), "utf8")).javaVersion);
    run(javaTool("javac"), ["-J-Xmx3g", "-J-Duser.language=en", "--release", release, "-proc:none",
      "-cp", vanillaClasspath.join(delimiter), "-d", harness, harnessSource], "compile ShapeFullBlockParity");
    // The runtime helper of the wall patch, as both TeaVM targets compile it from the shared sources.
    run(javaTool("javac"), ["-J-Xmx3g", "-J-Duser.language=en", "--release", release, "-proc:none",
      "-cp", vanillaClasspath.join(delimiter), "-d", patched, wallHelper], "compile BrowserWallShapes");
    const runs = {};
    for (const [name, classpath] of [["vanilla", [harness, ...vanillaClasspath]],
      ["patched", [harness, patched, ...vanillaClasspath]]]) {
      runs[name] = run(javaTool("java"), ["-Xmx3g", "-Dmax.bg.threads=1", "-cp", classpath.join(delimiter),
        "ShapeFullBlockParity"], `ShapeFullBlockParity ${name}`, {cwd: outDir});
      for (const line of lines(runs[name]).filter((text) => COMPARED.test(text) || text.startsWith("# "))) {
        console.log(`${profile} ${name}: ${line}`);
      }
    }
    const vanilla = compared(runs.vanilla);
    const patchedLines = compared(runs.patched);
    const equal = vanilla.length > 0 && vanilla.length === patchedLines.length
      && vanilla.every((line, index) => line === patchedLines[index]);
    const exact = patchedLines.includes("expressionMismatches 0") && vanilla.includes("expressionMismatches 0");
    const verdict = equal && exact ? "PASS" : "FAIL";
    console.log(`SHAPE_MEMO_PARITY ${profile} ${options.target} ${verdict}`);
    if (verdict !== "PASS") failed = true;
  }
  process.exit(failed ? 1 : 0);
}

main();
