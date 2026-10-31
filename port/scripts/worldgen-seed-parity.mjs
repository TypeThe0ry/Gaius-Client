#!/usr/bin/env node
// Worldgen seed-parity harness (migration plan P6, acceptance "seed parity").
//
// Generates the same chunks of a fresh default world ("minecraft:normal", structures on,
// vanilla data pack) on the JVM with vanilla Minecraft and hashes their block states and
// biomes, so a browser run or a run of the patched worldgen classes can be compared with it.
// The JVM side is port/scripts/worldgen-seed-parity/WorldgenSeedParity.java: a headless
// server built like vanilla's GameTestServer (no dedicated-server EULA prompt is involved).
//
// Per seed it runs two modes (see WorldgenSeedParity.java):
//   terrain  chunks up to ChunkStatus.TERRAIN (noise, surface, aquifers, carvers) and biomes,
//            hashed before any feature ran: independent of generation order;
//   full     vanilla setInitialSpawn, then FULL chunks, hashed before the first level tick.
// Chunks: a side x side square centred on ChunkGenerator.getOrigin (default 8 x 8 = 64), or the
// chunks listed in --positions <file> (one "x,z" per line).
// The worldgen executor runs with one thread (-Dmax.bg.threads=1) so full hashes repeat.
//
//   node port/scripts/worldgen-seed-parity.mjs [--profile 26.3] [--seeds 12345,20260930,...]
//        [--side 8 | --positions <file>] [--modes terrain,full] [--out-dir <dir>]
//        [--vanilla client | --server-jar <server.jar> | --download-server]
//        [--patched <client-named-26.3-gaius.jar>]
//   node port/scripts/worldgen-seed-parity.mjs --compare <reference.json> <candidate.json>
//   node port/scripts/worldgen-seed-parity.mjs --helpers [--helper-count 4000]
//   node port/scripts/worldgen-seed-parity.mjs --self-test   (the comparison logic on synthetic results)
//
// --helpers evaluates the JavaScript bodies (@JSBody) of the browser helpers the P6 patches
// call (BrowserBiomeManager.nearestCorner, BrowserAquifer.selectNearestCached,
// BrowserClimate.distance, BrowserBitStorage.get/getAndSet) in Node against vectors from
// worldgen-seed-parity/HelperVectors.java: vanilla BiomeManager.getBiome(III) and
// SimpleBitStorage, and the JVM transcriptions in shims/ for the rest. Together with the
// --patched run this ties the browser helpers to vanilla worldgen.
//
// Vanilla input: by default the profile's client-original.jar, SHA-1 checked against
// port/work/<id>/version.json (downloads.client) and run with the libraries of
// port/work/<id>/classpath.txt; the client jar holds the same worldgen and server classes as
// the dedicated server. --server-jar uses a local vanilla server.jar instead and
// --download-server fetches downloads.server.url; both are SHA-1 checked against
// downloads.server and the bundled jars against the bundler's SHA-256 lists.
//
// World spawn: the browser does not run the vanilla spawn search. Its setInitialSpawn (the
// "fast initial spawn", MinecraftClientPatcher.replaceInitialSpawnForBrowser) puts the world
// spawn at the middle column of the spawn chunk ChunkGenerator.getOrigin returns, at the top of
// the level, and lets PlayerSpawnFinder settle the player later. So the spawn acceptance is:
// the browser spawn chunk equals the vanilla spawn chunk (getOrigin). --patched runs check it
// on the real patched method (BrowserSpawnProbe copies it out of the patched MinecraftServer
// and runs it on the harness level with recording proxies); "spawn" in the result files is the
// harness server's own vanilla spawn search (full mode), compared only between two runs that
// both did that search.
//
// --patched: after the vanilla runs, runs the P6-patched worldgen classes taken from the given
// patched client jar (P6_WORLDGEN_CLASSES) in front of an unsigned copy of the vanilla jar,
// with JVM transcriptions of the browser helpers (port/scripts/worldgen-seed-parity/shims),
// and requires identical hashes. Helpers without JavaScript bodies (REAL_HELPERS, among them
// the kernel interfaces the patched classes implement) are compiled from port/src; the JVM twin
// of BrowserStrictMath, which StrictMath263 calls, is generated from its source with the two
// Math.fround natives as float casts (JVM_TWINS); the worldgen kernel is a shim that is never
// available, so the vanilla generation path runs. This proves that the browser bytecode patches keep vanilla
// worldgen semantics; TeaVM arithmetic (no Math.fround for float) is only covered by a browser
// run compared with --compare.
//
// Browser runs (M2 seed parity, JVM vs js vs wasm):
//   node port/scripts/worldgen-seed-parity.mjs [--seeds ...] [--out-dir <dir>]
//        [--browser <name>=<dist>[,runtime=js|wasm][,serverRuntime=js|wasm]]...
//        [--saved <name>=<captures dir>]... [--patched <client-named-26.3-gaius.jar>]
// A browser build generates a world for each seed and saves it (worldgen-seed-parity/
// browser-world.mjs: --browser runs it for every seed into <out-dir>/browser-<name>/<seed>;
// --saved takes such captures made earlier, <captures dir>/<seed>/). The JVM hashes the saved
// chunks with the vanilla codecs (saved mode of WorldgenSeedParity.java) and generates every
// position the browser saved at BIOMES or later with vanilla code in terrain mode (--patched
// also with the patched worldgen classes). Exact checks:
//   biomes   every chunk saved at BIOMES or later (biomes do not change after BIOMES);
//   bedrock  every bedrock block of every chunk saved at TERRAIN or later, FULL ones included:
//            no overworld feature or tick places or removes bedrock, so it equals vanilla terrain
//            block for block (surface rules and their positional random);
//   terrain  a chunk saved at TERRAIN (the last status before features) with no neighbour at
//            FEATURES or later is pure terrain and hashes like the vanilla terrain chunk;
//   spawn    the world spawn in the saved level.dat is the middle column of the vanilla spawn
//            chunk (getOrigin), as the browser's fast initial spawn writes it.
// Blocks of the other chunks the browser saved between TERRAIN and SPAWN (never ticked) are
// compared one by one with the same area decorated on the JVM (WorldgenSeedParity.diffBlocks).
// That cannot be exact: the FEATURES step of a chunk writes into its neighbours, and which
// features fit depends on the order in which neighbouring chunks were decorated, which vanilla
// does not fix. --control <side>[,shuffle|spiral] adds "jvm-control": vanilla itself loads a
// side x side square to FULL (in a shuffled order by default, or centre-out), saves it (capture
// mode) and goes through the same comparison; its share of differing blocks is what the order
// alone causes in a correct build, and a browser run above max(3 x that, that + 0.1 %) fails.
// Browser runs of one seed are also compared with each other (biomes; TERRAIN chunks both saved).
//
// Exit status: 0 parity (or results written), 1 mismatch or failed run, 2 usage/environment.

import {createHash} from "node:crypto";
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync,
  statSync, writeFileSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {delimiter, dirname, join, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {loadProfile, sourceFile} from "../tools/sourcesets/sourcesets.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const harnessDir = join(root, "port/scripts/worldgen-seed-parity");
const DEFAULT_SEEDS = ["12345", "20260930", "-7327290349473744307"];

/**
 * Classes whose browser patches can change generated blocks or biomes, or that pulse inside
 * generation: every MinecraftClientPatcher/Minecraft262BrowserPatcher/WorldgenPatches263
 * worldgen target that the P6 work package owns. Their Gaius helpers are the shims.
 */
export const P6_WORLDGEN_CLASSES = [
  "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator",
  "net/minecraft/world/level/levelgen/material/MaterialSystem",
  "net/minecraft/world/level/levelgen/material/MaterialRuleContext",
  "net/minecraft/world/level/levelgen/material/MaterialRuleContext$LazyXZCondition",
  "net/minecraft/world/level/levelgen/material/MaterialRuleContext$LazyYCondition",
  "net/minecraft/world/level/chunk/CarvingMask",
  "net/minecraft/world/level/levelgen/Aquifer$NoiseBasedAquifer",
  "net/minecraft/world/level/biome/BiomeManager",
  "net/minecraft/world/level/chunk/ChunkGenerator",
  "net/minecraft/world/level/levelgen/carver/WorldCarver",
  "net/minecraft/world/level/chunk/LevelChunkSection",
  "net/minecraft/world/level/NaturalSpawner",
  "net/minecraft/world/level/biome/Climate$RTree",
  "net/minecraft/world/level/biome/Climate$RTree$SubTree",
  "net/minecraft/world/level/biome/Climate$RTree$Node",
  "net/minecraft/world/level/biome/Climate$ParameterList",
  "net/minecraft/world/level/lighting/LightEngine",
  "net/minecraft/world/level/levelgen/structure/pools/JigsawPlacement$Placer",
  "net/minecraft/world/level/chunk/ChunkGeneratorStructureState",
  "net/minecraft/util/CubicSpline$Constant",
  "net/minecraft/util/CubicSpline$Multipoint",
  "net/minecraft/world/level/chunk/ProtoChunk",
  "net/minecraft/world/level/levelgen/Heightmap",
  "net/minecraft/world/level/chunk/storage/RegionFileStorage",
];

/**
 * Runtime helpers without JavaScript bodies, compiled from port/src for the patched run (the
 * profile's versions/ copy first). Interfaces the kernel patches add to patched classes must be
 * on the class path or the patched classes do not load; a profile that lacks a helper skips it.
 */
const REAL_HELPERS = [
  "dev/gaius/browser/BrowserProtoChunk.java",
  "dev/gaius/browser/BrowserRingPositions.java",
  "net/minecraft/world/level/biome/GaiusClimateIndex.java",
  "net/minecraft/world/level/biome/GaiusClimateSubTree.java",
  "dev/gaius/browser/kernel/mesh/MeshBiomeAccess.java",
  "dev/gaius/browser/kernel/worldgen/ChunkGeneratorKernelHooks263.java",
  "dev/gaius/browser/kernel/worldgen/WorldgenKernelHooks263.java",
];

/**
 * Helpers whose JavaScript bodies have a JVM equivalent: the source is copied from port/src with
 * each listed native replaced by its body. StrictMathCheck proves the same twin equal to the JVM's
 * casts. A native that is not listed fails the run, so the twin cannot fall behind its source.
 */
const JVM_TWINS = [{
  helper: "dev/gaius/browser/BrowserStrictMath.java",
  natives: {
    "public static native float round(float value);": "public static float round(float value) { return value; }",
    "public static native float fromDouble(double value);":
      "public static float fromDouble(double value) { return (float) value; }",
  },
}];

/** The helper's text as the profile compiles it (its port/mc/shared source sets over port/src/main). */
function helperSource(profile, helper) {
  return sourceFile(profile, "src", helper, {root})?.text ?? null;
}

/** Writes the JVM twins under directory and returns their paths. */
export function writeJvmTwins(profile, directory) {
  const written = [];
  for (const {helper, natives} of JVM_TWINS) {
    const source = helperSource(profile, helper);
    if (source === null) throw new Error(`JVM twin source ${helper} is missing`);
    let text = source.replace(/\r\n/g, "\n");
    text = text.replace(/^import org\.teavm\.jso\.JSBody;\n/m, "");
    text = text.replace(/^[ \t]*@JSBody\(.*\)[ \t]*\n/gm, "");
    for (const [declaration, body] of Object.entries(natives)) {
      if (!text.includes(declaration)) throw new Error(`JVM twin ${helper}: "${declaration}" not found`);
      text = text.replace(declaration, body);
    }
    if (/\bnative\b|@JSBody/.test(text.replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, ""))) {
      throw new Error(`JVM twin ${helper}: a native or @JSBody is left; add its JVM body to JVM_TWINS`);
    }
    const target = join(directory, helper);
    mkdirSync(dirname(target), {recursive: true});
    writeFileSync(target, text);
    written.push(target);
  }
  return written;
}

function usage(message) {
  console.error(`worldgen-seed-parity: ${message}`);
  process.exit(2);
}

function splitNamed(text, flag) {
  const at = text.indexOf("=");
  const name = text.slice(0, at);
  if (at < 1 || !/^[a-z0-9][a-z0-9-]*$/.test(name)) usage(`${flag} needs <name>=<path>, not ${text}`);
  return [name, text.slice(at + 1)];
}

/** --browser <name>=<dist>[,runtime=js|wasm][,serverRuntime=js|wasm] (runtime defaults to the name). */
function parseBrowser(text) {
  const [name, rest] = splitNamed(text, "--browser");
  const [dist, ...settings] = rest.split(",");
  const entry = {name, dist: resolve(dist), runtime: name === "wasm" ? "wasm" : "js",
    serverRuntime: name === "wasm" ? "wasm" : "js"};
  for (const setting of settings) {
    const [key, value] = setting.split("=");
    if (!["runtime", "serverRuntime"].includes(key) || !["js", "wasm"].includes(value)) {
      usage(`bad --browser setting ${setting}`);
    }
    entry[key] = value;
  }
  return entry;
}

function parseArgs(argv) {
  const options = {profile: null, seeds: DEFAULT_SEEDS, side: 8, modes: ["terrain", "full"],
    vanilla: "client", helperCount: 4000, browsers: [], saved: [], controlSide: 0, controlOrder: "shuffle", controlRuns: []};
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    const value = () => argv[++index] ?? usage(`${argument} needs a value`);
    switch (argument) {
      case "--profile": options.profile = value(); break;
      case "--seeds": options.seeds = value().split(",").map((seed) => seed.trim()).filter(Boolean); break;
      case "--side": options.side = Number(value()); break;
      case "--positions": options.positions = resolve(value()); break;
      case "--modes": options.modes = value().split(",").map((mode) => mode.trim()); break;
      case "--out-dir": options.outDir = resolve(value()); break;
      case "--vanilla": options.vanilla = value(); break;
      case "--server-jar": options.vanilla = "server"; options.serverJar = resolve(value()); break;
      case "--download-server": options.vanilla = "server"; options.downloadServer = true; break;
      case "--patched": options.patched = resolve(value()); break;
      case "--java": options.java = value(); break;
      case "--compare": options.compare = [resolve(value()), resolve(value())]; break;
      case "--helpers": options.helpers = true; break;
      case "--self-test": options.selfTest = true; break;
      case "--helper-count": options.helperCount = Number(value()); break;
      case "--browser": options.browsers.push(parseBrowser(value())); break;
      case "--control": {
        const [side, order = "shuffle"] = value().split(",");
        options.controlSide = Number(side);
        options.controlOrder = order;
        options.controlRuns.push({side: Number(side), order});
        break;
      }
      case "--saved": {
        const [name, directory] = splitNamed(value(), "--saved");
        options.saved.push({name, directory: resolve(directory)});
        break;
      }
      default: usage(`unknown argument ${argument}`);
    }
  }
  if (options.selfTest) return options;
  if (options.profile === null) {
    const reference = process.env.GAIUS_VERSION_PROFILE_PATH
      || JSON.parse(readFileSync(join(root, "port/config.json"), "utf8")).versionProfile;
    const profilePath = reference.endsWith(".json") ? resolve(root, "port", nativePath(reference)) : reference;
    options.profile = loadProfile(profilePath, root).id;
  }
  const names = [...options.browsers, ...options.saved].map((entry) => entry.name);
  if (new Set(names).size !== names.length) usage(`browser run names must differ: ${names.join(", ")}`);
  if (names.includes("vanilla") || names.includes("patched-jvm")) usage("vanilla and patched-jvm are reserved names");
  if (!/^\d+(?:\.\d+)+$/.test(options.profile)) usage(`bad profile ${options.profile}`);
  if (!Number.isInteger(options.side) || options.side < 1 || options.side > 32) usage("--side must be 1..32");
  if (options.controlRuns.some(({side, order}) => !Number.isInteger(side) || side < 1 || side > 32
      || !["spiral", "shuffle"].includes(order))) {
    usage("--control must be <side of 1..32 chunks>[,spiral|shuffle]");
  }
  for (const seed of options.seeds) if (!/^-?\d+$/.test(seed)) usage(`bad seed ${seed}`);
  for (const mode of options.modes) if (mode !== "terrain" && mode !== "full") usage(`bad mode ${mode}`);
  if (!["client", "server"].includes(options.vanilla)) usage("--vanilla must be client or server");
  return options;
}

function nativePath(value) {
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z]\//.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
}

function javaTool(options, name) {
  if (name === "java" && options.java) return options.java;
  const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
  const suffix = process.platform === "win32" ? ".exe" : "";
  return home ? join(nativePath(home), "bin", `${name}${suffix}`) : `${name}${suffix}`;
}

function run(command, args, label, extra = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 * 1024 * 1024, ...extra});
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const output = `${result.stdout || ""}${result.stderr || ""}`;
    throw new Error(`${label} failed (exit ${result.status})\n${output.slice(-8000)}`);
  }
  return result.stdout || "";
}

function sha(algorithm, file) {
  return createHash(algorithm).update(readFileSync(file)).digest("hex");
}

function readClasspathFile(file) {
  const text = readFileSync(file, "utf8").trim();
  // fetch-version.sh writes a POSIX ':' list of /d/... paths.
  const entries = text.startsWith("/") ? text.split(":") : text.split(delimiter);
  return entries.filter(Boolean).map(nativePath)
    .filter((entry) => !/client-(named|original)\.jar$/.test(entry));
}

async function vanillaInputs(options, work, outDir) {
  const version = JSON.parse(readFileSync(join(work, "version.json"), "utf8"));
  if (options.vanilla === "client") {
    const original = join(work, "client-original.jar");
    if (!existsSync(original)) usage(`missing ${original} (run the profile fetch first)`);
    const expected = version.downloads?.client?.sha1;
    const actual = sha("sha1", original);
    if (!expected || expected !== actual) {
      throw new Error(`client-original.jar SHA-1 ${actual} does not match version.json ${expected}`);
    }
    // An obfuscated profile (versions/<id>.json clientDistribution "obfuscated-with-mappings",
    // 1.21.11) runs on client-named.jar: the checked original remapped with the official
    // mappings by the profile fetch, still unpatched.
    const profileJson = JSON.parse(readFileSync(join(root, "port/versions", `${options.profile}.json`), "utf8"));
    const obfuscated = profileJson.clientDistribution === "obfuscated-with-mappings";
    const jar = obfuscated ? join(work, "client-named.jar") : original;
    if (!existsSync(jar)) usage(`missing ${jar} (run the profile fetch first)`);
    console.log(`WORLDGEN_SEED_PARITY_VANILLA client ${relative(root, jar)} original-sha1=${actual}`
      + (obfuscated ? ` named-sha1=${sha("sha1", jar)}` : ""));
    return {jar, libraries: readClasspathFile(join(work, "classpath.txt")), kind: "client", sha1: actual};
  }
  const expected = version.downloads?.server?.sha1;
  const url = version.downloads?.server?.url;
  if (!expected) throw new Error("version.json has no downloads.server.sha1");
  let serverJar = options.serverJar;
  if (!serverJar) {
    serverJar = join(outDir, "cache", `server-${expected}.jar`);
    if (!existsSync(serverJar) || sha("sha1", serverJar) !== expected) {
      if (!url?.startsWith("https://")) throw new Error("version.json has no https downloads.server.url");
      console.log(`WORLDGEN_SEED_PARITY_DOWNLOAD ${url}`);
      const response = await fetch(url);
      if (!response.ok) throw new Error(`download failed: HTTP ${response.status}`);
      mkdirSync(dirname(serverJar), {recursive: true});
      writeFileSync(serverJar, Buffer.from(await response.arrayBuffer()));
    }
  }
  const actual = sha("sha1", serverJar);
  if (actual !== expected) {
    throw new Error(`server jar SHA-1 ${actual} does not match version.json ${expected}`);
  }
  // The server jar is a bundler: the game jar and its libraries are nested entries listed,
  // with SHA-256, in META-INF/versions.list and META-INF/libraries.list.
  const unpacked = join(outDir, "cache", `server-${expected}`);
  mkdirSync(unpacked, {recursive: true});
  const listed = [];
  for (const [listName, folder] of [["versions.list", "versions"], ["libraries.list", "libraries"]]) {
    run(javaTool(options, "jar"), ["xf", serverJar, `META-INF/${listName}`],
      `extract ${listName}`, {cwd: unpacked});
    const text = readFileSync(join(unpacked, "META-INF", listName), "utf8");
    for (const line of text.split(/\r?\n/).filter(Boolean)) {
      const [sha256, id, path] = line.split("\t");
      if (!sha256 || !id || !path) throw new Error(`bad ${listName} line: ${line}`);
      listed.push({sha256, entry: `META-INF/${folder}/${path}`, folder});
    }
  }
  run(javaTool(options, "jar"), ["xf", serverJar, ...listed.map((item) => item.entry)],
    "extract bundled jars", {cwd: unpacked});
  for (const item of listed) {
    const file = join(unpacked, item.entry);
    if (sha("sha256", file) !== item.sha256) throw new Error(`bundled ${item.entry} SHA-256 mismatch`);
  }
  const game = listed.filter((item) => item.folder === "versions").map((item) => join(unpacked, item.entry));
  if (game.length !== 1) throw new Error(`expected one bundled game jar, found ${game.length}`);
  console.log(`WORLDGEN_SEED_PARITY_VANILLA server sha1=${actual} game=${relative(unpacked, game[0])}`);
  return {jar: game[0], kind: "server", sha1: actual,
    libraries: listed.filter((item) => item.folder === "libraries").map((item) => join(unpacked, item.entry))};
}

/** The version layer of the JVM harness for a profile (compat/<profile>/ParityCompat.java). */
export function compatSource(profile) {
  const source = join(harnessDir, "compat", profile, "ParityCompat.java");
  if (!existsSync(source)) {
    usage(`no JVM compat layer for profile ${profile} (port/scripts/worldgen-seed-parity/compat/${profile})`);
  }
  return source;
}

function compileHarness(options, classpath, output) {
  mkdirSync(output, {recursive: true});
  const extraSources = [];
  if (process.env.GAIUS_PARITY_LIGHT_FIXTURES) {
    const api = run(javaTool(options, "javap"), ["-classpath", classpath.join(delimiter),
      "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase"], "inspect light table API");
    if (!api.includes("getLightDampening()")) throw new Error("this vanilla light API is not supported by the light kernel");
    extraSources.push(join(root, "port/src/main/java/dev/gaius/browser/kernel/light/BrowserLightStateTable.java"));
  }
  run(javaTool(options, "javac"), ["-J-Xmx3g", "-J-Duser.language=en", "--release", "25", "-proc:none",
    "-cp", classpath.join(delimiter), "-d", output, join(harnessDir, "WorldgenSeedParity.java"),
    join(harnessDir, "LightFixtureWriter.java"), compatSource(options.profile), ...extraSources],
  "compile WorldgenSeedParity");
}

function runSeed(options, classpath, {seed, mode, label, outFile, universe, serverClass, extra = [],
  side = options.side}) {
  const started = Date.now();
  const probe = serverClass ? [`-Dgaius.parity.patchedServerClass=${serverClass}`] : [];
  const stdout = run(javaTool(options, "java"), ["-Xmx3g", "-Dmax.bg.threads=1", ...probe,
    "-cp", classpath.join(delimiter), "dev.gaius.parity.WorldgenSeedParity",
    "--seed", seed, "--mode", mode, "--side", String(side), "--universe", universe,
    "--out", outFile, "--label", label, ...extra], `${label} ${mode} seed ${seed}`,
  {timeout: 30 * 60 * 1000});
  const line = stdout.split(/\r?\n/).find((text) => text.includes("WORLDGEN_SEED_PARITY_OK"));
  if (!line) throw new Error(`${label} ${mode} seed ${seed}: no result line\n${stdout.slice(-4000)}`);
  console.log(`${line.slice(line.indexOf("WORLDGEN_SEED_PARITY_OK"))} label=${label} wallMillis=${Date.now() - started}`);
  return JSON.parse(readFileSync(outFile, "utf8"));
}

/** Differences between two result files; an empty list means parity. */
export function compareResults(reference, candidate) {
  const problems = [];
  for (const key of ["seed", "mode", "side", "preset"]) {
    if (String(reference[key]) !== String(candidate[key])) {
      problems.push(`${key} differs: ${reference[key]} vs ${candidate[key]}`);
    }
  }
  if (problems.length) return problems;
  if (reference.origin.x !== candidate.origin.x || reference.origin.z !== candidate.origin.z) {
    problems.push(`spawn chunk (getOrigin) differs: ${JSON.stringify(reference.origin)} vs ${JSON.stringify(candidate.origin)}`);
  }
  if (reference.spawn?.vanillaSpawnSearch && candidate.spawn?.vanillaSpawnSearch
      && (reference.spawn.x !== candidate.spawn.x || reference.spawn.y !== candidate.spawn.y
        || reference.spawn.z !== candidate.spawn.z)) {
    problems.push(`vanilla spawn search result differs: ${JSON.stringify(reference.spawn)} vs ${JSON.stringify(candidate.spawn)}`);
  }
  if (candidate.browserSpawn) problems.push(...checkBrowserSpawn(reference, candidate));
  const byPosition = new Map(candidate.chunks.map((chunk) => [`${chunk.x},${chunk.z}`, chunk]));
  const blockDiffs = [];
  const biomeDiffs = [];
  const heightmapDiffs = [];
  for (const chunk of reference.chunks) {
    const other = byPosition.get(`${chunk.x},${chunk.z}`);
    if (!other) {
      problems.push(`chunk ${chunk.x},${chunk.z} missing from candidate`);
      continue;
    }
    if (chunk.blocks !== other.blocks) blockDiffs.push(`${chunk.x},${chunk.z}`);
    if (chunk.biomes !== other.biomes) biomeDiffs.push(`${chunk.x},${chunk.z}`);
    if (reference.mode === "terrain" && JSON.stringify(worldgenHeightmaps(chunk.heightmaps) ?? null)
        !== JSON.stringify(worldgenHeightmaps(other.heightmaps) ?? null)) {
      heightmapDiffs.push(`${chunk.x},${chunk.z}`);
    }
  }
  if (blockDiffs.length) problems.push(`block hashes differ in ${blockDiffs.length} chunk(s): ${blockDiffs.join(" ")}`);
  if (biomeDiffs.length) problems.push(`biome hashes differ in ${biomeDiffs.length} chunk(s): ${biomeDiffs.join(" ")}`);
  if (heightmapDiffs.length) problems.push(`heightmap hashes differ in ${heightmapDiffs.length} chunk(s): ${heightmapDiffs.join(" ")}`);
    if (reference.mode === "terrain" && reference.chunks.some((chunk) => !worldgenHeightmaps(chunk.heightmaps)
        || Object.keys(worldgenHeightmaps(chunk.heightmaps)).length < 2)) {
    problems.push("terrain reference has no heightmap hashes; rerun with an updated harness");
  }
  return problems;
}

/**
 * Chunk statuses in generation order (ChunkStatus) of every supported profile: 26.3 merged
 * noise, surface and carvers into TERRAIN, so a save holds either the three or TERRAIN. The last
 * status before features (BLOCKS_STATUSES: TERRAIN, before 26.3 CARVERS) is pure terrain.
 */
export const STATUS_ORDER = ["minecraft:empty", "minecraft:structure_starts", "minecraft:structure_references",
  "minecraft:biomes", "minecraft:noise", "minecraft:surface", "minecraft:carvers", "minecraft:terrain",
  "minecraft:features", "minecraft:initialize_light", "minecraft:light", "minecraft:spawn", "minecraft:full"];
const BLOCKS_STATUSES = new Set(["minecraft:terrain", "minecraft:carvers"]);
const REQUIRED_WORLDGEN_HEIGHTMAPS = ["WORLD_SURFACE_WG", "OCEAN_FLOOR_WG"];
const statusRank = (status) => STATUS_ORDER.indexOf(status);
const hasBiomes = (chunk) => statusRank(chunk.status) >= statusRank("minecraft:biomes");
const worldgenHeightmaps = (value) => value
  ? Object.fromEntries(Object.entries(value).filter(([name]) => name.endsWith("_WG")).sort(([a], [b]) => a.localeCompare(b)))
  : null;

/** Positions of a saved-mode result whose biomes (and, at TERRAIN, blocks) can be compared. */
export function savedPositions(saved) {
  return saved.chunks.filter(hasBiomes).map((chunk) => `${chunk.x},${chunk.z}`);
}

/**
 * A saved browser world (saved mode) against a terrain-mode reference of the same positions.
 * Exact checks (problems): biomes of every chunk saved at BIOMES or later; blocks of every chunk
 * saved at TERRAIN with no neighbour at FEATURES or later (pure terrain, nothing wrote into it);
 * the saved world spawn against the reference spawn chunk (getOrigin). The block-by-block
 * comparison of the other unticked chunks (blockDiff) goes to notes: feature placement depends
 * on the order of decoration, so it is judged against the vanilla control (floor), when given:
 * a share of differing blocks above max(3 x floor, floor + 0.1 %) is a problem. Returns the
 * counts, problems and notes; no problem means parity.
 */
export function compareSaved(reference, saved, {requireSpawn = true, floor = null, positions = null} = {}) {
  const problems = [];
  const notes = [];
  const selectedPositions = positions ? new Set(positions) : null;
  const selectedChunks = saved.chunks.filter((chunk) => !selectedPositions || selectedPositions.has(`${chunk.x},${chunk.z}`));
  const counts = {saved: selectedChunks.length, unknownStatus: 0, blocksCompared: 0, blocksMatched: 0,
    isolatedTerrain: 0, isolatedTerrainMatched: 0, biomesCompared: 0, biomesMatched: 0,
    heightmapsCompared: 0, heightmapsMatched: 0, blockEntitiesCompared: 0, blockEntitiesMatched: 0,
    structuresCompared: 0, structuresMatched: 0, statuses: {}};
  const statusAt = new Map(selectedChunks.map((chunk) => [`${chunk.x},${chunk.z}`, chunk.status]));
  const decoratedNeighbour = (chunk) => {
    for (let dz = -1; dz <= 1; dz++) {
      for (let dx = -1; dx <= 1; dx++) {
        if (statusRank(statusAt.get(`${chunk.x + dx},${chunk.z + dz}`)) >= statusRank("minecraft:features")) return true;
      }
    }
    return false;
  };
  const isolatedDiffs = [];
  if (String(reference.seed) !== String(saved.seed)) problems.push(`seed differs: ${reference.seed} vs ${saved.seed}`);
  if (reference.mode !== "terrain") problems.push(`the reference must be a terrain-mode run, not ${reference.mode}`);
  const byPosition = new Map(reference.chunks.map((chunk) => [`${chunk.x},${chunk.z}`, chunk]));
  const blockDiffs = [];
  const biomeDiffs = [];
  const missing = [];
  for (const chunk of selectedChunks) {
    counts.statuses[chunk.status] = (counts.statuses[chunk.status] || 0) + 1;
    if (statusRank(chunk.status) < 0) { counts.unknownStatus++; continue; }
    if (!hasBiomes(chunk)) continue;
    const position = `${chunk.x},${chunk.z}`;
    if (selectedPositions && !selectedPositions.has(position)) continue;
    const vanilla = byPosition.get(position);
    if (!vanilla) { missing.push(position); continue; }
    counts.biomesCompared++;
    if (vanilla.biomes === chunk.biomes) counts.biomesMatched++; else biomeDiffs.push(position);
    // Heightmaps are serialized for FULL chunks too. Compare them whenever both
    // sides provide the worldgen maps; restricting this to TERRAIN silently left
    // the normal browser save path unchecked.
    const expectedHeightmaps = worldgenHeightmaps(vanilla.heightmaps);
    const actualHeightmaps = worldgenHeightmaps(chunk.heightmaps);
    if (expectedHeightmaps || actualHeightmaps) {
      const missingExpected = REQUIRED_WORLDGEN_HEIGHTMAPS.filter((name) => !expectedHeightmaps || !Object.hasOwn(expectedHeightmaps, name));
      const missingActual = REQUIRED_WORLDGEN_HEIGHTMAPS.filter((name) => !actualHeightmaps || !Object.hasOwn(actualHeightmaps, name));
      if (missingExpected.length || missingActual.length) {
        const missing = [...new Set([
          ...missingExpected.map((name) => `reference ${name}`),
          ...missingActual.map((name) => `saved ${name}`),
        ])];
        problems.push(`worldgen heightmaps missing for chunk ${position}: ${missing.join(", ")}`);
      } else {
        counts.heightmapsCompared++;
        const expected = REQUIRED_WORLDGEN_HEIGHTMAPS.map((name) => [name, expectedHeightmaps[name]]);
        const actual = REQUIRED_WORLDGEN_HEIGHTMAPS.map((name) => [name, actualHeightmaps[name]]);
        if (JSON.stringify(expected) === JSON.stringify(actual)) counts.heightmapsMatched++;
        else problems.push(`worldgen heightmaps differ for chunk ${position}`);
      }
    }
    if (vanilla.blockEntities !== undefined || chunk.blockEntities !== undefined) {
      counts.blockEntitiesCompared++;
      if (vanilla.blockEntities === chunk.blockEntities) counts.blockEntitiesMatched++;
      else problems.push(`block entities differ for chunk ${position}`);
    }
    if (vanilla.structures !== undefined || chunk.structures !== undefined) {
      counts.structuresCompared++;
      if (vanilla.structures === chunk.structures) counts.structuresMatched++;
      else problems.push(`structure starts/references differ for chunk ${position}`);
    }
    if (BLOCKS_STATUSES.has(chunk.status)) {
      counts.blocksCompared++;
      if (vanilla.blocks === chunk.blocks) counts.blocksMatched++; else blockDiffs.push(position);
      if (!decoratedNeighbour(chunk)) {
        counts.isolatedTerrain++;
        if (vanilla.blocks === chunk.blocks) counts.isolatedTerrainMatched++; else isolatedDiffs.push(position);
      }
    }
  }
  if (counts.unknownStatus) problems.push(`${counts.unknownStatus} chunk(s) with a status this harness does not know`);
  if (missing.length) problems.push(`${missing.length} saved chunk(s) missing from the reference: ${missing.slice(0, 20).join(" ")}`);
  // A fully promoted save has no persisted TERRAIN chunks; its blockDiff below still compares
  // the saved blocks with a vanilla terrain/decorated control, so do not reject that shape.
  if (counts.blocksCompared === 0 && !saved.blockDiff) {
    problems.push("no chunk was saved at TERRAIN: nothing to compare blocks with");
  }
  if (selectedPositions && selectedPositions.size !== selectedChunks.length) {
    const missingPositions = [...selectedPositions].filter((position) => !selectedChunks.some((chunk) => `${chunk.x},${chunk.z}` === position));
    problems.push(`${missingPositions.length} fixed chunk position(s) were not saved: ${missingPositions.join(" ")}`);
  }
  // A TERRAIN chunk next to a decorated one also holds that neighbour's features; only one
  // without such a neighbour is pure terrain and must hash like the vanilla terrain chunk.
  if (isolatedDiffs.length) {
    problems.push(`block hashes differ in ${isolatedDiffs.length} TERRAIN chunk(s) without decorated neighbours: `
      + isolatedDiffs.slice(0, 40).join(" "));
  }
  // The other unticked chunks: the saved-mode blockDiff (WorldgenSeedParity.diffBlocks), block
  // by block against the same area decorated on the JVM.
  const diff = saved.blockDiff;
  counts.blockDiff = diff ? {decoratedChunks: diff.decoratedChunks, terrain: diff.terrain, decorated: diff.decorated,
    hard: diff.hard && {chunks: diff.hard.chunks, blocks: diff.hard.blocks, differences: diff.hard.differences},
    extraKinds: diff.extraKinds, differingChunks: diff.differingChunks.length} : null;
  // Exact: the bedrock of every saved chunk (FULL ones too) against pure terrain; no feature and
  // no tick places or removes bedrock.
  if (diff?.hard?.differences) {
    problems.push(`${diff.hard.differences} block(s) where the save or vanilla terrain has bedrock differ in `
      + `${diff.hard.chunks} chunks, e.g. ${diff.hard.samples.slice(0, 3).map((sample) => `${sample.x},${sample.y},`
        + `${sample.z} ${sample.saved} vs ${sample.terrain}`).join("; ")}`);
  }
  if (diff) {
    const terrainKinds = terrainOverTerrain(diff.extraKinds);
    if (terrainKinds.length) notes.push(`terrain blocks over vanilla terrain blocks: ${terrainKinds.join(", ")}`);
    for (const group of ["terrain", "decorated"]) {
      const total = diff[group];
      const differing = total.missing + total.extra + total.other;
      const share = total.blocks ? differing / total.blocks : 0;
      notes.push(`${group} chunks: ${differing} of ${total.blocks} blocks differ (${(share * 100).toFixed(3)} %; `
        + `${total.missing} missing, ${total.extra} extra, ${total.other} other) in ${total.chunks - total.exact} `
        + `of ${total.chunks} chunks`);
      const reference = floor?.[group];
      if (reference && reference.blocks) {
        const floorShare = (reference.missing + reference.extra + reference.other) / reference.blocks;
        if (share > Math.max(3 * floorShare, floorShare + 0.001)) {
          problems.push(`${group} chunks: ${(share * 100).toFixed(3)} % of the blocks differ, above the vanilla `
            + `order-effect floor of ${(floorShare * 100).toFixed(3)} %`);
        }
      } else if (differing > 0) {
        problems.push(`${group} chunks differ but no vanilla control baseline was supplied: ${differing}/${total.blocks} blocks`);
      }
    }
  }
  if (counts.heightmapsCompared > 0 && counts.heightmapsCompared !== counts.heightmapsMatched) {
    problems.push(`worldgen heightmaps match in ${counts.heightmapsMatched}/${counts.heightmapsCompared} selected chunks`);
  }
  if (counts.blockEntitiesCompared > 0 && counts.blockEntitiesCompared !== counts.blockEntitiesMatched) {
    problems.push(`block entity hashes match in ${counts.blockEntitiesMatched}/${counts.blockEntitiesCompared} selected chunks`);
  }
  if (counts.structuresCompared > 0 && counts.structuresCompared !== counts.structuresMatched) {
    problems.push(`structure hashes match in ${counts.structuresMatched}/${counts.structuresCompared} selected chunks`);
  }
  if (biomeDiffs.length) problems.push(`biome hashes differ in ${biomeDiffs.length} chunk(s): ${biomeDiffs.slice(0, 40).join(" ")}`);
  const spawn = saved.savedSpawn;
  const origin = reference.origin;
  counts.spawn = spawn ? {x: spawn.x, y: spawn.y, z: spawn.z, chunkX: Math.floor(spawn.x / 16), chunkZ: Math.floor(spawn.z / 16),
    vanillaOrigin: origin} : null;
  if (!spawn) {
    if (requireSpawn) problems.push("the saved level.dat has no world spawn");
  }
  else if (spawn.x !== origin.x * 16 + 8 || spawn.z !== origin.z * 16 + 8) {
    problems.push(`saved world spawn ${spawn.x},${spawn.y},${spawn.z} is not the middle column of the vanilla spawn chunk `
      + `(getOrigin) ${origin.x},${origin.z}`);
  }
  return {counts, problems, notes};
}

/**
 * Blocks that terrain generation itself writes (noise, aquifers, surface rules, carvers). One of
 * them over another in blockDiff.extraKinds ("<saved> over <vanilla terrain>") is a terrain
 * difference; features (ores, patches, plants, sculk...) write other blocks.
 */
export const TERRAIN_BLOCKS = new Set(["air", "cave_air", "stone", "deepslate", "water", "lava", "bedrock",
  "dirt", "grass_block", "sand", "red_sand", "sandstone", "red_sandstone", "gravel", "snow_block", "powder_snow",
  "ice", "packed_ice", "blue_ice", "terracotta", "white_terracotta", "orange_terracotta", "yellow_terracotta",
  "brown_terracotta", "red_terracotta", "light_gray_terracotta", "mud", "mycelium", "podzol", "coarse_dirt",
  "calcite", "netherrack", "end_stone"].map((name) => `Block{minecraft:${name}}`));

/** The "<saved> over <vanilla>" entries of blockDiff.extraKinds where both are terrain blocks. */
export function terrainOverTerrain(extraKinds = {}) {
  return Object.entries(extraKinds).filter(([kind]) => {
    const [savedBlock, terrainBlock] = kind.split(" over ");
    return TERRAIN_BLOCKS.has(savedBlock) && TERRAIN_BLOCKS.has(terrainBlock);
  }).map(([kind, count]) => `${kind} x${count}`);
}

/**
 * Light of two saved worlds of one seed (saved-mode "light": the effective block and sky light of
 * a lit chunk, see WorldgenSeedParity.hashLight). The light of a chunk is a function of the blocks
 * of its 3 x 3 neighbourhood once all nine chunks are lit (no light travels farther than 15
 * blocks, and a chunk's sources reach its neighbours when it is lit), so it is compared exactly
 * where both saves hold the nine chunks at LIGHT or later with equal block hashes: a FULL chunk
 * that was ticked differently in one save drops out by its blocks. This compares the light the
 * build computed (on 26.2 and 26.3 the Rust light kernel of the build's wasm module plus vanilla
 * propagation into the neighbours) with the vanilla light engine of the JVM control.
 */
export function compareSavedLight(first, second) {
  const at = (saved) => new Map(saved.chunks.map((chunk) => [`${chunk.x},${chunk.z}`, chunk]));
  const one = at(first);
  const two = at(second);
  const lit = (chunk) => chunk && statusRank(chunk.status) >= statusRank("minecraft:light");
  const counts = {lightCompared: 0, lightMatched: 0, lightUnlitNeighbourhood: 0, lightDifferentBlocks: 0};
  const differences = [];
  for (const chunk of first.chunks) {
    const other = two.get(`${chunk.x},${chunk.z}`);
    if (!chunk.light || !other?.light) continue;
    let comparable = true;
    let sameBlocks = true;
    for (let dz = -1; dz <= 1 && comparable; dz++) {
      for (let dx = -1; dx <= 1; dx++) {
        const key = `${chunk.x + dx},${chunk.z + dz}`;
        const a = one.get(key);
        const b = two.get(key);
        if (!lit(a) || !lit(b)) { comparable = false; break; }
        if (a.blocks !== b.blocks) sameBlocks = false;
      }
    }
    if (!comparable) { counts.lightUnlitNeighbourhood++; continue; }
    if (!sameBlocks) { counts.lightDifferentBlocks++; continue; }
    counts.lightCompared++;
    if (chunk.light === other.light) counts.lightMatched++; else differences.push(`${chunk.x},${chunk.z}`);
  }
  return {counts, differences};
}

/**
 * The light a save stored against the light the vanilla light engine computes for the same saved
 * blocks on the JVM (saved mode "relight", WorldgenSeedParity.relight): exact for every chunk
 * whose 3 x 3 neighbourhood the save holds at LIGHT or later (the stored light is complete there
 * and the relight saw every block that can reach it). On 26.2 and 26.3 the browser light of a new
 * chunk is the light kernel's (the build's wasm module) plus vanilla propagation. Block light is
 * exact ("differences"). Sky light is not: the vanilla engine lights chunks one by one as they are
 * generated, and a chunk lit while a neighbour is not yet lit keeps sky light 14-15 that entered
 * through that border down to the bottom of the world, which a relight of the finished area does not
 * reproduce; the JVM control save shows it at the same chunk positions as the browser saves, so sky
 * differences ("skyDifferences") are reported, with the control's share as their baseline.
 */
export function compareRelight(saved) {
  const at = new Map(saved.chunks.map((chunk) => [`${chunk.x},${chunk.z}`, chunk]));
  const lit = (chunk) => chunk && statusRank(chunk.status) >= statusRank("minecraft:light");
  const counts = {relightCompared: 0, relightMatched: 0, relightBlockMatched: 0, relightSkyMatched: 0,
    relightIncomplete: 0, relightNoStoredLight: 0};
  const differences = [];
  const skyDifferences = [];
  for (const chunk of saved.chunks) {
    if (!chunk.light || !chunk.relight) continue;
    if (chunk.light === "none") { counts.relightNoStoredLight++; continue; }
    let complete = true;
    for (let dz = -1; dz <= 1 && complete; dz++) {
      for (let dx = -1; dx <= 1; dx++) {
        if (!lit(at.get(`${chunk.x + dx},${chunk.z + dz}`))) { complete = false; break; }
      }
    }
    if (!complete) { counts.relightIncomplete++; continue; }
    counts.relightCompared++;
    const [block, sky] = chunk.light.split(":");
    const [relitBlock, relitSky] = chunk.relight.split(":");
    if (chunk.light === chunk.relight) counts.relightMatched++;
    if (block === relitBlock) counts.relightBlockMatched++; else differences.push(`${chunk.x},${chunk.z}`);
    if (sky === relitSky) counts.relightSkyMatched++; else skyDifferences.push(`${chunk.x},${chunk.z}`);
  }
  return {counts, differences, skyDifferences};
}

/** Two saved browser worlds of one seed against each other, on the chunks both saved at TERRAIN. */
export function compareSavedPair(first, second) {
  const byPosition = new Map(second.chunks.map((chunk) => [`${chunk.x},${chunk.z}`, chunk]));
  const counts = {blocksCompared: 0, blocksMatched: 0, biomesCompared: 0, biomesMatched: 0};
  const differences = [];
  for (const chunk of first.chunks) {
    const other = byPosition.get(`${chunk.x},${chunk.z}`);
    if (!other || !hasBiomes(chunk) || !hasBiomes(other)) continue;
    counts.biomesCompared++;
    if (chunk.biomes === other.biomes) counts.biomesMatched++; else differences.push(`biomes ${chunk.x},${chunk.z}`);
    if (BLOCKS_STATUSES.has(chunk.status) && BLOCKS_STATUSES.has(other.status)) {
      counts.blocksCompared++;
      if (chunk.blocks === other.blocks) counts.blocksMatched++; else differences.push(`blocks ${chunk.x},${chunk.z}`);
    }
  }
  const light = compareSavedLight(first, second);
  Object.assign(counts, light.counts);
  differences.push(...light.differences.map((position) => `light ${position}`));
  return {counts, problems: differences.length ? [`${differences.length} difference(s): ${differences.slice(0, 40).join(" ")}`] : []};
}

/**
 * The browser fast initial spawn (see the file header) against the vanilla spawn chunk: same
 * chunk as reference getOrigin, middle column, top of the level, and the load-listener stage
 * and telemetry event the browser method reports.
 */
export function checkBrowserSpawn(reference, candidate) {
  const spawn = candidate.browserSpawn;
  if (!spawn) return ["the browser setInitialSpawn did not run (no browserSpawn in the candidate)"];
  const problems = [];
  const origin = reference.origin;
  if (spawn.chunkX !== origin.x || spawn.chunkZ !== origin.z) {
    problems.push(`browser spawn chunk ${spawn.chunkX},${spawn.chunkZ} is not the vanilla spawn chunk `
      + `(getOrigin) ${origin.x},${origin.z}`);
  }
  if (spawn.x !== origin.x * 16 + 8 || spawn.z !== origin.z * 16 + 8 || spawn.y !== spawn.levelMaxY) {
    problems.push(`browser spawn ${spawn.x},${spawn.y},${spawn.z} is not the middle column of chunk `
      + `${origin.x},${origin.z} at the top of the level (maxY ${spawn.levelMaxY})`);
  }
  if (!spawn.events?.includes("server.browserFastInitialSpawn")) {
    problems.push(`browser spawn did not report server.browserFastInitialSpawn: ${JSON.stringify(spawn.events)}`);
  }
  const calls = (spawn.listener || []).map((entry) => entry.split(" ")[0]);
  if (calls.join() !== "start,updateFocus,finish"
      || !spawn.listener[0].includes("PREPARE_GLOBAL_SPAWN") || !spawn.listener[2].includes("PREPARE_GLOBAL_SPAWN")) {
    problems.push(`browser spawn load-listener calls are not start/updateFocus/finish of PREPARE_GLOBAL_SPAWN: `
      + JSON.stringify(spawn.listener));
  }
  return problems;
}

function report(label, problems) {
  if (problems.length === 0) {
    console.log(`WORLDGEN_SEED_PARITY_MATCH ${label}`);
  } else {
    for (const problem of problems) console.log(`WORLDGEN_SEED_PARITY_MISMATCH ${label}: ${problem}`);
  }
  return problems.length === 0;
}

function preparePatched(options, work, vanilla, harnessClasses, temp) {
  const overlay = join(temp, "patched-classes");
  const shims = join(temp, "shim-classes");
  mkdirSync(overlay, {recursive: true});
  run(javaTool(options, "jar"), ["xf", options.patched,
    ...P6_WORLDGEN_CLASSES.map((owner) => `${owner}.class`)], "extract patched worldgen classes",
  {cwd: overlay});
  for (const owner of P6_WORLDGEN_CLASSES) {
    if (!existsSync(join(overlay, `${owner}.class`))) {
      throw new Error(`patched jar ${options.patched} has no ${owner}.class`);
    }
  }
  const unsigned = join(temp, "vanilla-unsigned.jar");
  run(javaTool(options, "java"), ["-cp", harnessClasses, "dev.gaius.parity.WorldgenSeedParity",
    "--strip-signature", vanilla.jar, unsigned], "strip vanilla jar signature");
  const shimSources = [];
  const collect = (dir) => {
    for (const name of readdirSync(dir)) {
      const path = join(dir, name);
      if (statSync(path).isDirectory()) collect(path);
      else if (name.endsWith(".java")) shimSources.push(path);
    }
  };
  collect(join(harnessDir, "shims"));
  for (const helper of REAL_HELPERS) {
    const source = helperSource(options.profile, helper);
    if (source) {
      const target = join(temp, "real-helpers", helper);
      mkdirSync(dirname(target), {recursive: true});
      writeFileSync(target, source);
      shimSources.push(target);
    }
  }
  shimSources.push(...writeJvmTwins(options.profile, join(temp, "twin-sources")));
  mkdirSync(shims, {recursive: true});
  run(javaTool(options, "javac"), ["-J-Duser.language=en", "--release", "25", "-proc:none",
    "-cp", [unsigned, ...vanilla.libraries].join(delimiter), "-d", shims, ...shimSources],
  "compile helper shims");
  // The browser setInitialSpawn: its MinecraftServer class stays off the class path (the
  // harness server is vanilla); BrowserSpawnProbe copies the one static method out of it.
  const server = join(temp, "patched-server");
  mkdirSync(server, {recursive: true});
  run(javaTool(options, "jar"), ["xf", options.patched, "net/minecraft/server/MinecraftServer.class"],
    "extract the patched MinecraftServer", {cwd: server});
  options.patchedServerClass = join(server, "net/minecraft/server/MinecraftServer.class");
  if (!existsSync(options.patchedServerClass)) throw new Error(`patched jar ${options.patched} has no MinecraftServer`);
  const maven = nativePath(process.env.GAIUS_MAVEN_REPOSITORY || join(homedir(), ".m2/repository"));
  const asm = ["asm", "asm-tree", "asm-commons"].map((artifact) =>
    join(maven, "org/ow2/asm", artifact, "9.8", `${artifact}-9.8.jar`));
  for (const jar of asm) if (!existsSync(jar)) throw new Error(`${jar} is missing (ASM 9.8 is needed for --patched)`);
  const probe = join(temp, "probe-classes");
  mkdirSync(probe, {recursive: true});
  run(javaTool(options, "javac"), ["-J-Duser.language=en", "--release", "25", "-proc:none",
    "-cp", [unsigned, ...vanilla.libraries, ...asm].join(delimiter), "-d", probe,
    join(harnessDir, "BrowserSpawnProbe.java")], "compile BrowserSpawnProbe");
  return [shims, overlay, probe, harnessClasses, unsigned, ...vanilla.libraries, ...asm];
}

/**
 * The JavaScript bodies (@JSBody) of the browser helpers the P6 patches call, keyed by
 * method name, read from the Java sources the browser build compiles.
 */
function readJsBodies(file) {
  const source = readFileSync(file, "utf8");
  const bodies = {};
  const pattern = /@JSBody\(\s*params\s*=\s*(\{[^}]*\}|"[^"]*")\s*,\s*script\s*=\s*"""\r?\n([\s\S]*?)"""\s*\)[\s\S]*?public\s+static\s+native\s+\S+\s+(\w+)\s*\(/g;
  for (const match of source.matchAll(pattern)) {
    const params = [...match[1].matchAll(/"([^"]+)"/g)].map((param) => param[1]);
    bodies[match[3]] = new Function(...params, match[2]);
  }
  return bodies;
}

/** Checks the helper JavaScript against vectors from vanilla code and the JVM transcriptions. */
function checkHelpers(vectors) {
  const failures = [];
  const expect = (label, ok, detail) => { if (!ok && failures.length < 20) failures.push(`${label}: ${detail}`); };
  const biome = readJsBodies(join(root, "port/src/main/java/dev/gaius/browser/BrowserBiomeManager.java"));
  const aquifer = readJsBodies(join(root, "port/src/main/java/dev/gaius/browser/BrowserAquifer.java"));
  const climate = readJsBodies(join(root, "port/src/main/java/dev/gaius/browser/BrowserClimate.java"));
  const bits = readJsBodies(join(root, "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserBitStorage.java"));
  for (const [name, bodies, key] of [["BrowserBiomeManager", biome, "nearestCorner"],
    ["BrowserAquifer", aquifer, "selectNearestCached"], ["BrowserClimate", climate, "distance"],
    ["BrowserBitStorage", bits, "get"], ["BrowserBitStorage", bits, "getAndSet"]]) {
    if (typeof bodies[key] !== "function") failures.push(`${name}.${key}: @JSBody not found`);
  }
  if (failures.length) return {failures, counts: {}};
  // BiomeManager.getBiome(III) in 26.3 (and the patched body): quart = (shifted >> 2) + corner bit.
  for (const [seed, sx, sy, sz, qx, qy, qz] of vectors.biomeManager) {
    const corner = biome.nearestCorner(BigInt(seed), sx, sy, sz);
    const actual = [(sx >> 2) + ((corner >>> 2) & 1), (sy >> 2) + ((corner >>> 1) & 1), (sz >> 2) + (corner & 1)];
    expect("BiomeManager zoom vs vanilla getBiome(III)", actual.join() === [qx, qy, qz].join(),
      `seed ${seed} shifted ${sx},${sy},${sz}: js ${actual} vanilla ${qx},${qy},${qz}`);
  }
  // SimpleBitStorage get/getAndSet on one cell, typed-array fast path and BigInt path.
  for (const [bitCount, perLong, slot, value, before, got, previous, after] of vectors.bitStorage) {
    const local = slot % perLong;
    for (const [label, make] of [["typed", (word) => new BigInt64Array([BigInt(word)])],
      ["bigint", (word) => [BigInt(word)]]]) {
      const read = bits.get(make(before), local, perLong, bitCount);
      expect(`BitStorage.get ${label} vs SimpleBitStorage`, read === got,
        `bits ${bitCount} slot ${local}: js ${read} vanilla ${got}`);
      const cells = make(before);
      const old = bits.getAndSet(cells, local, value, perLong, bitCount);
      expect(`BitStorage.getAndSet ${label} vs SimpleBitStorage`,
        old === previous && BigInt.asIntN(64, BigInt(cells[0])).toString() === after,
        `bits ${bitCount} slot ${local} value ${value}: js ${old}/${cells[0]} vanilla ${previous}/${after}`);
    }
  }
  for (const [packed, minX, minY, minZ, sizeX, sizeZ, x, y, z, hit, output] of vectors.aquifer) {
    const target = new Int32Array(8);
    const result = aquifer.selectNearestCached(new BigInt64Array(packed.map(BigInt)), minX, minY, minZ,
      sizeX, sizeZ, x, y, z, target);
    expect("Aquifer nearest centers vs JVM transcription", result === hit
      && (!hit || Array.from(target).join() === output.join()),
    `block ${x},${y},${z}: js ${result} ${Array.from(target)} jvm ${hit} ${output}`);
  }
  for (const [bounds, target, distance] of vectors.climate) {
    const result = climate.distance(new Float64Array(bounds), new BigInt64Array(target.map(BigInt)));
    expect("Climate RTree distance vs JVM transcription", result.toString() === distance,
      `js ${result} jvm ${distance}`);
  }
  return {failures, counts: {biomeManager: vectors.biomeManager.length,
    bitStorage: vectors.bitStorage.length, aquifer: vectors.aquifer.length, climate: vectors.climate.length}};
}

/** The saved-mode comparison on synthetic results: what passes, what fails and why. */
function selfTest() {
  const failures = [];
  const expect = (label, condition) => { if (!condition) failures.push(label); };
  const chunk = (x, z, status, blocks = "b", biomes = "m") => ({x, z, status, blocks: `${blocks}${x},${z}`, biomes: `${biomes}${x},${z}`,
    heightmaps: {WORLD_SURFACE_WG: `w${x},${z}`, OCEAN_FLOOR_WG: `o${x},${z}`}});
  const reference = {seed: "1", mode: "terrain", origin: {x: 0, z: 0},
    chunks: [chunk(0, 0, "minecraft:terrain"), chunk(1, 0, "minecraft:terrain"), chunk(5, 5, "minecraft:terrain")]};
  const blockDiff = (terrainDiffer, decoratedDiffer, extraKinds = {}) => ({decoratedChunks: 1,
    terrain: {chunks: 1, exact: terrainDiffer ? 0 : 1, blocks: 1000, same: 1000 - terrainDiffer, featureBlocksSame: 0,
      missing: terrainDiffer, extra: 0, other: 0},
    decorated: {chunks: 1, exact: decoratedDiffer ? 0 : 1, blocks: 1000, same: 1000 - decoratedDiffer, featureBlocksSame: 0,
      missing: 0, extra: decoratedDiffer, other: 0},
    hard: {chunks: 3, blocks: 500, differences: 0, samples: []}, extraKinds, differingChunks: []});
  const saved = (overrides = {}) => ({seed: "1", savedSpawn: {x: 8, y: 319, z: 8},
    chunks: [chunk(0, 0, "minecraft:full"), chunk(1, 0, "minecraft:terrain", "x"), chunk(5, 5, "minecraft:terrain"),
      chunk(9, 9, "minecraft:structure_starts", "?", "?")], blockDiff: blockDiff(0, 0), ...overrides});
  let result = compareSaved(reference, saved());
  expect("a clean save passes", result.problems.length === 0);
  expect("biomes of chunks at BIOMES or later are compared", result.counts.biomesCompared === 3);
  expect("a TERRAIN chunk next to a FULL one is not a pure-terrain check", result.counts.isolatedTerrain === 1
    && result.counts.isolatedTerrainMatched === 1);
  expect("structure_starts chunks are not positions", savedPositions(saved()).join(" ") === "0,0 1,0 5,5");
  result = compareSaved(reference, saved({chunks: [chunk(5, 5, "minecraft:terrain", "y")]}));
  expect("an isolated TERRAIN chunk with other blocks fails", result.problems.some((p) => /without decorated neighbours/.test(p)));
  result = compareSaved(reference, saved({chunks: [chunk(0, 0, "minecraft:full", "b", "n"), chunk(5, 5, "minecraft:terrain")]}));
  expect("a biome difference fails", result.problems.some((p) => /biome hashes differ in 1/.test(p)));
  result = compareSaved(reference, saved({savedSpawn: {x: 24, y: 319, z: 8}}));
  expect("a spawn outside the getOrigin chunk middle fails", result.problems.some((p) => /saved world spawn/.test(p)));
  const hard = blockDiff(0, 0);
  hard.hard.differences = 2;
  hard.hard.samples = [{x: 1, y: -62, z: 2, saved: "Block{minecraft:deepslate}", terrain: "Block{minecraft:bedrock}"}];
  result = compareSaved(reference, saved({blockDiff: hard}));
  expect("a bedrock difference fails", result.problems.some((p) => /has bedrock differ/.test(p)));
  const floor = blockDiff(1, 7).terrain && {terrain: blockDiff(1, 7).terrain, decorated: blockDiff(1, 7).decorated};
  result = compareSaved(reference, saved({blockDiff: blockDiff(2, 9)}), {floor});
  expect("feature-block differences within the vanilla run-to-run range pass", result.problems.length === 0
    && result.notes.length === 2);
  result = compareSaved(reference, saved({blockDiff: blockDiff(1, 40)}), {floor});
  expect("feature-block differences above the floor fail", result.problems.some((p) => /above the vanilla order-effect floor/.test(p)));
  const mismatchedHeightmaps = saved();
  mismatchedHeightmaps.chunks[0].status = "minecraft:terrain";
  mismatchedHeightmaps.chunks[0].heightmaps.WORLD_SURFACE_WG = "different";
  result = compareSaved(reference, mismatchedHeightmaps);
  expect("terrain heightmap differences fail", result.problems.some((p) => /worldgen heightmaps differ/.test(p)));
  const carversWrongHeightmaps = saved({chunks: [chunk(0, 0, "minecraft:carvers")]});
  carversWrongHeightmaps.chunks[0].heightmaps.OCEAN_FLOOR_WG = "different";
  result = compareSaved(reference, carversWrongHeightmaps);
  expect("CARVERS heightmap differences fail", result.problems.some((p) => /worldgen heightmaps differ/.test(p)));
  const terrainMissingHeightmap = saved({chunks: [chunk(0, 0, "minecraft:terrain")]});
  delete terrainMissingHeightmap.chunks[0].heightmaps.WORLD_SURFACE_WG;
  result = compareSaved(reference, terrainMissingHeightmap);
  expect("missing TERRAIN heightmaps fail explicitly", result.problems.some((p) => /worldgen heightmaps missing.*saved WORLD_SURFACE_WG/.test(p)));
  expect("terrain-over-terrain kinds are reported", terrainOverTerrain({"Block{minecraft:water} over Block{minecraft:stone}": 3,
    "Block{minecraft:moss_block} over Block{minecraft:stone}": 9}).join() === "Block{minecraft:water} over Block{minecraft:stone} x3");
  const pair = compareSavedPair(saved(), saved({chunks: [chunk(1, 0, "minecraft:terrain", "x"), chunk(0, 0, "minecraft:full", "b", "n")]}));
  expect("two saves compare biomes and common TERRAIN chunks", pair.counts.blocksCompared === 1 && pair.counts.blocksMatched === 1
    && pair.counts.biomesMatched === 1 && pair.problems.length === 1);
  // Light: compared only where both saves hold a lit 3 x 3 neighbourhood with equal blocks.
  const litWorld = (light = (x, z) => `L${x},${z}`, blocks = (x, z) => `b${x},${z}`, status = () => "minecraft:full") => {
    const chunks = [];
    for (let z = -2; z <= 2; z++) {
      for (let x = -2; x <= 2; x++) chunks.push({x, z, status: status(x, z), blocks: blocks(x, z), biomes: `m${x},${z}`, light: light(x, z)});
    }
    return {seed: "1", chunks};
  };
  let light = compareSavedLight(litWorld(), litWorld());
  expect("the lit centre 3 x 3 chunks compare their light", light.counts.lightCompared === 9 && light.counts.lightMatched === 9
    && light.differences.length === 0);
  light = compareSavedLight(litWorld(), litWorld((x, z) => (x === 0 && z === 0 ? "other" : `L${x},${z}`)));
  expect("a light difference with equal blocks is reported", light.differences.join() === "0,0");
  light = compareSavedLight(litWorld(), litWorld((x, z) => (x === 0 && z === 0 ? "other" : `L${x},${z}`),
    (x, z) => (x === 1 && z === 1 ? "ticked" : `b${x},${z}`)));
  expect("other blocks in the neighbourhood exclude the chunk", light.differences.length === 0
    && light.counts.lightDifferentBlocks === 4);
  light = compareSavedLight(litWorld(), litWorld(undefined, undefined,
    (x, z) => (x === -1 && z === 0 ? "minecraft:features" : "minecraft:full")));
  expect("an unlit or missing neighbour excludes the chunk", light.counts.lightUnlitNeighbourhood === 22 && light.counts.lightCompared === 3);
  const relitWorld = litWorld();
  for (const chunk of relitWorld.chunks) {
    chunk.light = `${chunk.light}:s`;
    chunk.relight = chunk.light;
  }
  let relit = compareRelight(relitWorld);
  expect("stored light equal to the relight", relit.counts.relightCompared === 9 && relit.counts.relightMatched === 9);
  relitWorld.chunks.find((chunk) => chunk.x === 0 && chunk.z === 0).relight = "other:s";
  relit = compareRelight(relitWorld);
  expect("a block light difference is reported", relit.differences.join() === "0,0" && relit.skyDifferences.length === 0);
  relitWorld.chunks.find((chunk) => chunk.x === 0 && chunk.z === 0).relight = "L0,0:t";
  relit = compareRelight(relitWorld);
  expect("a sky light difference is reported apart", relit.differences.length === 0 && relit.skyDifferences.join() === "0,0");
  for (const failure of failures) console.log(`WORLDGEN_SEED_PARITY_SELF_TEST_FAIL ${failure}`);
  console.log(`WORLDGEN_SEED_PARITY_SELF_TEST ${failures.length === 0 ? "PASS" : "FAIL"} (${failures.length} failure(s))`);
  return failures.length === 0;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.selfTest) {
    process.exitCode = selfTest() ? 0 : 1;
    return;
  }
  if (options.helpers) {
    const work = join(root, "port/work", options.profile);
    const outDir = options.outDir || join(root, "port/target", options.profile, "worldgen-seed-parity");
    mkdirSync(outDir, {recursive: true});
    const vanilla = await vanillaInputs(options, work, outDir);
    const temp = mkdtempSync(join(tmpdir(), "gaius-seed-parity-helpers-"));
    try {
      const classes = join(temp, "classes");
      mkdirSync(classes, {recursive: true});
      const classpath = [vanilla.jar, ...vanilla.libraries];
      const shimDir = join(harnessDir, "shims", "dev", "gaius", "browser");
      run(javaTool(options, "javac"), ["-J-Duser.language=en", "--release", "25", "-proc:none",
        "-cp", classpath.join(delimiter), "-d", classes, join(harnessDir, "HelperVectors.java"),
        ...readdirSync(shimDir).filter((name) => name.endsWith(".java")).map((name) => join(shimDir, name))],
      "compile HelperVectors");
      const vectorsFile = join(outDir, "helper-vectors.json");
      run(javaTool(options, "java"), ["-cp", [classes, ...classpath].join(delimiter),
        "dev.gaius.parity.HelperVectors", vectorsFile, String(options.helperCount)], "HelperVectors");
      const {failures, counts} = checkHelpers(JSON.parse(readFileSync(vectorsFile, "utf8")));
      for (const failure of failures) console.log(`WORLDGEN_HELPER_MISMATCH ${failure}`);
      console.log(`WORLDGEN_HELPERS ${JSON.stringify(counts)} failures=${failures.length}`);
      console.log(`WORLDGEN_SEED_PARITY_RESULT ${failures.length === 0 ? "PASS" : "FAIL"}`);
      process.exitCode = failures.length === 0 ? 0 : 1;
    } finally {
      rmSync(temp, {recursive: true, force: true});
    }
    return;
  }
  if (options.compare) {
    const [reference, candidate] = options.compare.map((file) => JSON.parse(readFileSync(file, "utf8")));
    const ok = report(`${reference.label || "reference"} vs ${candidate.label || "candidate"} `
      + `${reference.mode} seed ${reference.seed}`, compareResults(reference, candidate));
    console.log(`WORLDGEN_SEED_PARITY_RESULT ${ok ? "PASS" : "FAIL"}`);
    process.exit(ok ? 0 : 1);
  }
  const work = join(root, "port/work", options.profile);
  if (!existsSync(join(work, "version.json"))) usage(`missing ${work}/version.json`);
  const outDir = options.outDir || join(root, "port/target", options.profile, "worldgen-seed-parity");
  mkdirSync(outDir, {recursive: true});
  const vanilla = await vanillaInputs(options, work, outDir);
  const temp = mkdtempSync(join(tmpdir(), "gaius-seed-parity-"));
  try {
    const harnessClasses = join(temp, "harness-classes");
    const vanillaClasspath = [vanilla.jar, ...vanilla.libraries];
    compileHarness(options, vanillaClasspath, harnessClasses);
    const patchedClasspath = options.patched
      ? preparePatched(options, work, vanilla, harnessClasses, temp) : null;
    const universe = join(temp, "universe");
    let ok = true;
    const summary = [];
    const browserRuns = [...options.browsers, ...options.saved];
    for (const seed of options.seeds) {
      if (browserRuns.length || options.controlSide) {
        ok = browserSeed(options, {harnessClasses, vanillaClasspath, patchedClasspath, universe, outDir},
          browserRuns, seed, summary) && ok;
        continue;
      }
      for (const mode of options.modes) {
        const positions = options.positions ? ["--positions", options.positions] : [];
        const reference = runSeed(options, [harnessClasses, ...vanillaClasspath], {seed, mode,
          label: `vanilla-${vanilla.kind}`, universe,
          outFile: join(outDir, `vanilla-${mode}-${seed}.json`), extra: positions});
        summary.push({seed, mode, label: reference.label, aggregate: reference.aggregate,
          origin: reference.origin, spawn: reference.spawn});
        if (patchedClasspath) {
          const candidate = runSeed(options, patchedClasspath, {seed, mode, label: "patched-jvm",
            universe, outFile: join(outDir, `patched-jvm-${mode}-${seed}.json`),
            serverClass: options.patchedServerClass, extra: positions});
          summary.push({seed, mode, label: candidate.label, aggregate: candidate.aggregate,
            pulses: candidate.pulses?.total, browserSpawn: candidate.browserSpawn});
          const problems = compareResults(reference, candidate);
          if (!candidate.browserSpawn) problems.push(...checkBrowserSpawn(reference, candidate));
          ok = report(`patched-jvm vs vanilla ${mode} seed ${seed}`, problems) && ok;
          if (candidate.browserSpawn) {
            const vanillaSearch = reference.spawn?.vanillaSpawnSearch
              ? `; vanilla spawn search ${reference.spawn.x},${reference.spawn.y},${reference.spawn.z}` : "";
            console.log(`WORLDGEN_SEED_PARITY_BROWSER_SPAWN seed ${seed} ${mode}: `
              + `${candidate.browserSpawn.x},${candidate.browserSpawn.y},${candidate.browserSpawn.z} `
              + `chunk ${candidate.browserSpawn.chunkX},${candidate.browserSpawn.chunkZ} `
              + `(vanilla getOrigin ${reference.origin.x},${reference.origin.z}${vanillaSearch})`);
          }
        }
      }
    }
    writeFileSync(join(outDir, "summary.json"), `${JSON.stringify({profile: options.profile,
      vanilla: {kind: vanilla.kind, sha1: vanilla.sha1}, patched: options.patched || null,
      side: options.side, browsers: browserRuns.length ? browserRuns : undefined, results: summary}, null, 2)}\n`);
    console.log(`WORLDGEN_SEED_PARITY_OUTPUT ${outDir}`);
    console.log(`WORLDGEN_SEED_PARITY_RESULT ${ok ? "PASS" : "FAIL"}`);
    process.exitCode = ok ? 0 : 1;
  } finally {
    rmSync(temp, {recursive: true, force: true});
  }
}

/** The capture directory of one browser run for one seed: made now (--browser) or earlier (--saved). */
function browserCapture(options, entry, seed, outDir) {
  if (entry.directory) return join(entry.directory, seed);
  const directory = join(outDir, `browser-${entry.name}`, seed);
  const tag = `seedparity-${optionsProfileForTag(options)}-${entry.name}-${seed.replace(/^-/, "neg")}`;
  const slot = process.env.GAIUS_SLOT_SH;
  const capture = [process.execPath, join(harnessDir, "browser-world.mjs"),
    "--dist", entry.dist, "--runtime", entry.runtime, "--server-runtime", entry.serverRuntime, "--seed", seed, "--out", directory];
  // GAIUS_SLOT_SH names a machine slot wrapper (slot.sh chrome <tag> -- <command>); without it the capture runs directly.
  const spawnOptions = {stdio: "inherit", timeout: 40 * 60 * 1000};
  const result = slot ? spawnSync("bash", [slot, "chrome", tag, "--", ...capture], spawnOptions)
    : spawnSync(capture[0], capture.slice(1), spawnOptions);
  if (result.error) console.log(`WORLDGEN_SEED_PARITY_BROWSER_ERROR ${entry.name} seed ${seed}: ${result.error}`);
  return directory;
}

function optionsProfileForTag(options) {
  return String(options.profile || "profile").replace(/[^A-Za-z0-9._-]/g, "_");
}

/**
 * One seed of the browser comparison: hash every browser save on the JVM (saved mode), generate
 * the union of their comparable positions with vanilla code in terrain mode (and with the
 * patched classes when --patched is given), then compare each save with vanilla and the saves
 * with each other.
 */
function browserSeed(options, context, browserRuns, seed, summary) {
  const {harnessClasses, vanillaClasspath, patchedClasspath, universe, outDir} = context;
  let ok = true;
  const saves = [];
  let fixedPositions = options.positions
    ? readFileSync(options.positions, "utf8").split(/\r?\n/).map((line) => line.trim()).filter(Boolean) : null;
  let fixedPositionsFile = options.positions || null;
  for (let controlIndex = 0; controlIndex < options.controlRuns.length; controlIndex++) {
    const {side, order} = options.controlRuns[controlIndex];
    const controlName = controlIndex === 0 ? "jvm-control" : `jvm-control-${controlIndex + 1}`;
    const capture = join(outDir, controlName, seed);
    rmSync(capture, {recursive: true, force: true});
    const controlCapture = runSeed(options, [harnessClasses, ...vanillaClasspath], {seed, mode: "capture", label: controlName, universe,
      outFile: join(outDir, `${controlName}-capture-${seed}.json`), side,
      extra: ["--capture", join(capture, "region"), "--order", order, ...(options.positions ? ["--positions", options.positions] : [])]});
    if (!fixedPositions) {
      const {x, z} = controlCapture.origin;
      const left = -Math.floor((side - 1) / 2);
      fixedPositions = [];
      for (let dz = left; dz < left + side; dz++) {
        for (let dx = left; dx < left + side; dx++) fixedPositions.push(`${x + dx},${z + dz}`);
      }
    }
    if (!fixedPositionsFile) {
      fixedPositionsFile = join(outDir, `positions-${seed}.txt`);
      writeFileSync(fixedPositionsFile, `${fixedPositions.join("\n")}\n`);
    }
    const saved = runSeed(options, [harnessClasses, ...vanillaClasspath], {seed, mode: "saved", label: controlName,
      universe, outFile: join(outDir, `${controlName}-saved-${seed}.json`), extra: ["--saved", join(capture, "region"),
        ...(fixedPositionsFile ? ["--positions", fixedPositionsFile] : [])]});
    saves.push({entry: {name: controlName, control: true}, meta: {serverRuntime: "jvm", runtime: {runtime: "jvm"}}, saved});
  }
  for (const entry of browserRuns) {
    const directory = browserCapture(options, entry, seed, outDir);
    let meta = null;
    try { meta = JSON.parse(readFileSync(join(directory, "browser-world.json"), "utf8")); } catch {}
    const problems = [];
    if (!meta) problems.push(`no capture in ${directory}`);
    else if (meta.error) problems.push(`the browser run failed: ${String(meta.error).split("\n")[0]}`);
    else {
      const wanted = entry.serverRuntime || meta.requestedServerRuntime;
      if (wanted && meta.serverRuntime !== wanted) {
        problems.push(`the server Worker ran ${meta.serverRuntime}, not the requested ${wanted}`);
      }
      if (wanted && meta.serverTelemetry?.serverRuntime !== wanted) {
        problems.push(`the server Worker telemetry ran ${meta.serverTelemetry?.serverRuntime ?? "missing"}, not the requested ${wanted}`);
      }
      const wantedClient = entry.runtime || meta.requestedRuntime;
      if (wantedClient && meta.runtime?.runtime !== wantedClient) {
        problems.push(`the page client ran ${meta.runtime?.runtime ?? "missing"}, not the requested ${wantedClient}`);
      }
      if (String(meta.seed) !== seed) problems.push(`the capture is of seed ${meta.seed}`);
    }
    if (problems.length) {
      ok = report(`${entry.name} seed ${seed}`, problems) && ok;
      summary.push({seed, label: entry.name, capture: directory, problems,
        lastState: meta?.lastState?.screen ?? null});
      continue;
    }
    const levelDat = join(directory, "level.dat");
    try {
      const saved = runSeed(options, [harnessClasses, ...vanillaClasspath], {seed, mode: "saved", label: entry.name,
        universe, outFile: join(outDir, `${entry.name}-saved-${seed}.json`),
        extra: ["--saved", join(directory, "region"), ...(existsSync(levelDat) ? ["--level-dat", levelDat] : []),
          ...(fixedPositionsFile ? ["--positions", fixedPositionsFile] : [])]});
      saves.push({entry, meta, saved});
    } catch (error) {
      ok = report(`${entry.name} seed ${seed}`, [`saved-mode hashing failed: ${String(error.message).split("\n")[0]}`]) && ok;
    }
  }
  if (!saves.length) return false;
  const spawn = saves.find(({entry}) => !entry.control)?.saved.savedSpawn;
  if (!fixedPositions) {
    if (!spawn) {
    ok = report(`fixed positions seed ${seed}`, ["browser save has no spawn coordinates to derive the fixed chunk set"]) && ok;
    return false;
    }
    const centerX = Math.floor(spawn.x / 16), centerZ = Math.floor(spawn.z / 16);
    fixedPositions = Array.from({length: 9}, (_, index) => `${centerX + index % 3 - 1},${centerZ + Math.floor(index / 3) - 1}`);
    fixedPositionsFile = join(outDir, `positions-${seed}.txt`);
    writeFileSync(fixedPositionsFile, `${fixedPositions.join("\n")}\n`);
  }
  const positions = fixedPositions;
  const positionsFile = fixedPositionsFile || join(outDir, `positions-${seed}.txt`);
  if (!fixedPositionsFile) writeFileSync(positionsFile, `${positions.join("\n")}\n`);
  const reference = runSeed(options, [harnessClasses, ...vanillaClasspath], {seed, mode: "terrain",
    label: "vanilla", universe, outFile: join(outDir, `vanilla-terrain-positions-${seed}.json`),
    extra: ["--positions", positionsFile]});
  summary.push({seed, label: "vanilla", mode: "terrain", positions: positions.length, origin: reference.origin});
  if (patchedClasspath) {
    const patched = runSeed(options, patchedClasspath, {seed, mode: "terrain", label: "patched-jvm", universe,
      outFile: join(outDir, `patched-jvm-terrain-positions-${seed}.json`), serverClass: options.patchedServerClass,
      extra: ["--positions", positionsFile]});
    const problems = compareResults(reference, patched);
    ok = report(`patched-jvm vs vanilla terrain seed ${seed} (${positions.length} browser positions)`, problems) && ok;
    summary.push({seed, label: "patched-jvm", mode: "terrain", positions: positions.length, problems});
  }
  const floor = {};
  for (const {entry, saved} of saves.filter(({entry}) => entry.control)) {
    for (const group of ["terrain", "decorated"]) {
      const candidate = saved.blockDiff?.[group];
      const prior = floor[group];
      if (candidate?.blocks && (!prior || (candidate.missing + candidate.extra + candidate.other) / candidate.blocks
          > (prior.missing + prior.extra + prior.other) / prior.blocks)) floor[group] = candidate;
    }
  }
  for (const {entry, meta, saved} of saves) {
    const {counts, problems, notes} = compareSaved(reference, saved, {requireSpawn: !entry.control,
      floor: entry.control ? saved.blockDiff : floor, positions});
    const diff = counts.blockDiff;
    const group = (name) => `${name} ${diff[name].exact}/${diff[name].chunks} chunks exact, `
      + `${diff[name].same}/${diff[name].blocks} blocks equal (${diff[name].featureBlocksSame} feature blocks)`;
    console.log(`WORLDGEN_SEED_PARITY_BROWSER ${entry.name} seed ${seed}: TERRAIN chunks ${counts.blocksCompared} `
      + `(${counts.isolatedTerrainMatched}/${counts.isolatedTerrain} without decorated neighbours equal to vanilla), `
      + (diff ? `${group("terrain")}, ${group("decorated")}, bedrock ${diff.hard
        ? `${diff.hard.blocks - diff.hard.differences}/${diff.hard.blocks} equal in ${diff.hard.chunks} chunks` : "-"}, ` : "")
      + `biomes ${counts.biomesMatched}/${counts.biomesCompared} chunks, ${counts.saved} saved, `
      + `spawn ${counts.spawn ? `${counts.spawn.x},${counts.spawn.y},${counts.spawn.z}` : "none"} `
      + `(vanilla getOrigin ${reference.origin.x},${reference.origin.z}); client ${meta.runtime?.runtime ?? "?"}, `
      + `server Worker ${meta.serverRuntime}`);
    const relit = compareRelight(saved);
    Object.assign(counts, relit.counts);
    console.log(`WORLDGEN_SEED_PARITY_LIGHT ${entry.name} seed ${seed}: stored block light equal to the vanilla light `
      + `engine on the saved blocks in ${relit.counts.relightBlockMatched}/${relit.counts.relightCompared} chunks with a lit `
      + `3x3 neighbourhood, sky light in ${relit.counts.relightSkyMatched} (${relit.counts.relightIncomplete} lit chunks `
      + `without one, ${relit.counts.relightNoStoredLight} marked lit without stored light)`);
    if (relit.differences.length) {
      problems.push(`stored block light differs from the vanilla light engine on the saved blocks in `
        + `${relit.differences.length} chunk(s): ${relit.differences.slice(0, 30).join(" ")}`);
    }
    if (relit.skyDifferences.length) {
      notes.push(`sky light differs from a relight of the finished area in ${relit.skyDifferences.length} chunk(s) `
        + `(generation-order sky light at borders, see compareRelight): ${relit.skyDifferences.slice(0, 20).join(" ")}`);
    }
    for (const note of notes) console.log(`WORLDGEN_SEED_PARITY_NOTE ${entry.name} seed ${seed}: ${note}`);
    ok = report(`${entry.name} vs vanilla seed ${seed}`, problems) && ok;
    summary.push({seed, label: entry.name, clientRuntime: meta.runtime?.runtime ?? null, serverRuntime: meta.serverRuntime,
      worldEntrySeconds: meta.phases?.worldEntrySeconds ?? null, counts, problems, notes});
  }
  for (let first = 0; first < saves.length; first++) {
    for (let second = first + 1; second < saves.length; second++) {
      const {counts, problems} = compareSavedPair(saves[first].saved, saves[second].saved);
      const label = `${saves[first].entry.name} vs ${saves[second].entry.name}`;
      console.log(`WORLDGEN_SEED_PARITY_PAIR ${label} seed ${seed}: blocks ${counts.blocksMatched}/${counts.blocksCompared}, `
        + `biomes ${counts.biomesMatched}/${counts.biomesCompared}, light ${counts.lightMatched}/${counts.lightCompared} `
        + `(lit 3x3 neighbourhoods with equal blocks; ${counts.lightDifferentBlocks} with other blocks, `
        + `${counts.lightUnlitNeighbourhood} not lit)`);
      ok = report(`${label} seed ${seed}`, problems) && ok;
      summary.push({seed, label, counts, problems});
    }
  }
  return ok;
}

main().catch((error) => {
  console.error(error.stack || String(error));
  process.exit(1);
});
