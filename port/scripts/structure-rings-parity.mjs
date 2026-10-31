#!/usr/bin/env node
// Concentric-ring structure parity (strongholds): the ring positions of a patched
// ChunkGeneratorStructureState against vanilla, on the JVM, for every seed and profile.
//
// Vanilla ChunkGeneratorStructureState.generateRingPositions draws a start chunk for every ring
// position and moves it to a biome its biome search picks (BiomeSource.findBiomeHorizontal, a
// 112-block square, random choice among the matches). A browser patch of that class must keep
// every position: they decide where strongholds generate and where ender eyes and /locate point.
//
// Per profile:
//   1. the client patcher chain of the selected TeaVM target runs on a scratch copy of the vanilla
//      jar (patchkit.mjs check --keep, no TeaVM), or --patched-jar names an already patched jar;
//      its ChunkGeneratorStructureState class, with the runtime helper both targets compile
//      (dev/gaius/browser/BrowserRingPositions.java of the shared sources), goes in front of the
//      vanilla jar;
//   2. port/scripts/worldgen-seed-parity/StructureRingsParity.java (with the profile's
//      ParityCompat) lists the positions of new "minecraft:normal" worlds with vanilla classes,
//      then with the patched class: before the whole list is read, the patched list answers
//      contains() probes (the 21 x 21 chunks around the world origin, then each vanilla
//      position and its 8 neighbours), as ConcentricRingsStructurePlacement.isPlacementChunk
//      asks once per generated chunk;
//   3. exact checks: the same positions in the same order, every probe answered as vanilla's
//      list answers it, and for the lazy list every position within its search reach of its start.
//      The result line also counts the searches a lazy list had run after getRingPositionsFor
//      (resolvedAtList), after the origin probes (resolvedAfterOrigin) and before the whole list
//      was read (resolvedBeforeCopy), and sums the times of the three phases.
//
//   node port/scripts/structure-rings-parity.mjs [--profile <id>]... [--target wasmgc|js]
//        [--seeds 12345,...] [--patched-jar <jar>] [--out-dir <dir>]
//   node port/scripts/structure-rings-parity.mjs --self-test
//
// Exit status: 0 parity, 1 a difference or a failed run, 2 usage.
// JVM work: run it through the machine's build slot (patchkit, javac and two -Xmx3g JVMs per profile).

import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {loadProfiles, sourceFile} from "../tools/sourcesets/sourcesets.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const harnessDir = join(root, "port/scripts/worldgen-seed-parity");
const DEFAULT_SEEDS = ["12345", "20260930", "-7327290349473744307", "0", "42", "8675309"];
const STATE_CLASS = "net/minecraft/world/level/chunk/ChunkGeneratorStructureState.class";
const LAZY_HELPER = "dev/gaius/browser/BrowserRingPositions.java";
const ORIGIN_PROBE_RADIUS = 10;

function usage(message) {
  console.error(`structure-rings-parity: ${message}`);
  process.exit(2);
}

function parseArgs(argv) {
  const options = {profiles: [], target: "wasmgc", seeds: DEFAULT_SEEDS, patchedJar: null,
    outDir: join(tmpdir(), "gaius-structure-rings-parity"), selfTest: false};
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    const value = () => argv[++index] ?? usage(`${argument} needs a value`);
    switch (argument) {
      case "--profile": options.profiles.push(value()); break;
      case "--target": options.target = value(); break;
      case "--seeds": options.seeds = value().split(",").map((seed) => seed.trim()).filter(Boolean); break;
      case "--patched-jar": options.patchedJar = resolve(value()); break;
      case "--out-dir": options.outDir = resolve(value()); break;
      case "--self-test": options.selfTest = true; break;
      default: usage(`unknown argument ${argument}`);
    }
  }
  if (!["js", "wasmgc"].includes(options.target)) usage("--target must be js or wasmgc");
  for (const seed of options.seeds) if (!/^-?\d+$/.test(seed)) usage(`bad seed ${seed}`);
  if (options.patchedJar && options.profiles.length !== 1) usage("--patched-jar needs exactly one --profile");
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

/**
 * Runs the target's client patcher chain on a scratch copy of the vanilla jar; returns the patched
 * jar. --compare --warn: a difference from the checked-in patch locks is reported, not fatal (patchkit
 * check --compare is the lock gate); a failed REQUIRED patch still fails.
 */
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
  const rings = manifest.patches.filter((entry) => (entry.rewrites ?? []).includes(STATE_CLASS))
    .map((entry) => `${entry.id}=${entry.outcome}`);
  console.log(`STRUCTURE_RINGS_PATCHES ${profile} ${target} ${rings.join(" ") || "none"}`);
  return join(directory, `${profile}-${target}`, "client.jar");
}

/**
 * The probes the patched list answers before its whole list is read, in two groups: the
 * 21 x 21 chunks around the world origin (where a new world starts generating), then every
 * vanilla position with its 8 neighbours. Returns the probe file text and the flat probe lists.
 */
export function probesFor(reference) {
  const lines = [];
  const probes = {};
  for (const {seed, sets} of reference.seeds) {
    for (const {set, positions} of sets) {
      const seen = new Set();
      const groups = [[], []];
      const add = (group, x, z) => {
        const key = `${x},${z}`;
        if (!seen.has(key)) {
          seen.add(key);
          groups[group].push(key);
        }
      };
      for (let z = -ORIGIN_PROBE_RADIUS; z <= ORIGIN_PROBE_RADIUS; z++) {
        for (let x = -ORIGIN_PROBE_RADIUS; x <= ORIGIN_PROBE_RADIUS; x++) add(0, x, z);
      }
      for (const [x, z] of positions) {
        for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) add(1, x + dx, z + dz);
      }
      probes[`${seed} ${set}`] = groups.flat();
      lines.push(`${seed} ${set} ${groups[0].join(" ")} | ${groups[1].join(" ")}`);
    }
  }
  return {text: `${lines.join("\n")}\n`, probes};
}

/** Exact differences between a vanilla and a patched result; an empty list means parity. */
export function compareRings(reference, candidate, probes) {
  const problems = [];
  if (reference.profile !== candidate.profile) problems.push(`profile ${reference.profile} vs ${candidate.profile}`);
  const candidateSeeds = new Map(candidate.seeds.map((entry) => [entry.seed, entry]));
  for (const {seed, sets} of reference.seeds) {
    const other = candidateSeeds.get(seed);
    if (!other) {
      problems.push(`seed ${seed} missing`);
      continue;
    }
    if (other.sets.length !== sets.length) problems.push(`seed ${seed}: ${sets.length} vs ${other.sets.length} ring sets`);
    const otherSets = new Map(other.sets.map((entry) => [entry.set, entry]));
    for (const expected of sets) {
      const actual = otherSets.get(expected.set);
      const label = `seed ${seed} ${expected.set}`;
      if (!actual) {
        problems.push(`${label} missing`);
        continue;
      }
      if (actual.positions.length !== expected.positions.length) {
        problems.push(`${label}: ${expected.positions.length} vs ${actual.positions.length} positions`);
      }
      const moved = [];
      expected.positions.forEach(([x, z], index) => {
        const [ax, az] = actual.positions[index] ?? [];
        if (ax !== x || az !== z) moved.push({index, vanilla: [x, z], patched: [ax, az]});
      });
      if (moved.length) {
        const sample = moved.slice(0, 4).map((entry) => `#${entry.index} ${entry.vanilla} -> ${entry.patched}`);
        problems.push(`${label}: ${moved.length}/${expected.positions.length} positions differ (${sample.join("; ")})`);
      }
      const list = probes?.[`${seed} ${expected.set}`] ?? [];
      if (list.length) {
        const members = new Set(expected.positions.map(([x, z]) => `${x},${z}`));
        if (actual.probes.length !== list.length) {
          problems.push(`${label}: ${actual.probes.length} probe answers for ${list.length} probes`);
        } else {
          const wrong = list.filter((key, index) => (actual.probes[index] === "1") !== members.has(key));
          if (wrong.length) problems.push(`${label}: ${wrong.length}/${list.length} contains() answers differ (${wrong.slice(0, 4).join(" ")})`);
        }
      }
      if (actual.starts) {
        const outside = actual.positions.filter(([x, z], index) => {
          const [sx, sz] = actual.starts[index];
          return Math.abs(x - sx) > actual.reach || Math.abs(z - sz) > actual.reach;
        });
        if (outside.length) problems.push(`${label}: ${outside.length} positions outside the search reach ${actual.reach}`);
      }
    }
  }
  return problems;
}

/** Positions that left their start (the biome search moved them) and the largest move, from a lazy run. */
function moves(result) {
  let moved = 0;
  let total = 0;
  let largest = 0;
  for (const {sets} of result.seeds) {
    for (const {positions, starts} of sets) {
      if (!starts) return null;
      positions.forEach(([x, z], index) => {
        const distance = Math.max(Math.abs(x - starts[index][0]), Math.abs(z - starts[index][1]));
        total++;
        if (distance) moved++;
        largest = Math.max(largest, distance);
      });
    }
  }
  return {moved, total, largest};
}

function timing(result, key) {
  return result.seeds.flatMap(({sets}) => sets.map((set) => set[key])).reduce((sum, value) => sum + value, 0);
}

function runProfile(options, profile) {
  const work = join(root, "port/work", profile);
  const vanillaJar = join(work, "client-named.jar");
  if (!existsSync(vanillaJar)) usage(`missing ${vanillaJar}`);
  const out = join(options.outDir, profile, options.target);
  rmSync(out, {recursive: true, force: true});
  mkdirSync(out, {recursive: true});
  const libs = libraries(work);
  const vanillaClasspath = [vanillaJar, ...libs];
  const harness = join(out, "harness-classes");
  run(javaTool("javac"), ["-J-Xmx3g", "-J-Duser.language=en", "--release", "25", "-proc:none",
    "-cp", vanillaClasspath.join(delimiter), "-d", harness, join(harnessDir, "StructureRingsParity.java"),
    join(harnessDir, "compat", profile, "ParityCompat.java")], `compile StructureRingsParity ${profile}`);
  const jar = options.patchedJar ?? patchedJar(profile, options.target, options.outDir);
  const patched = join(out, "patched-classes");
  mkdirSync(patched, {recursive: true});
  run(javaTool("jar"), ["xf", jar, STATE_CLASS], "extract the patched ChunkGeneratorStructureState", {cwd: patched});
  if (!existsSync(join(patched, STATE_CLASS))) throw new Error(`${jar} has no ${STATE_CLASS}`);
  const helper = sourceFile(profile, "src", LAZY_HELPER, {target: options.target, root});
  if (!helper) throw new Error(`${profile} ${options.target} compiles no ${LAZY_HELPER}`);
  run(javaTool("javac"), ["-J-Xmx3g", "-J-Duser.language=en", "--release", "25", "-proc:none",
    "-cp", vanillaClasspath.join(delimiter), "-d", patched, helper.path], `compile ${LAZY_HELPER}`);
  const java = (classpath, label, extra) => {
    const file = join(out, `${label}.json`);
    const started = Date.now();
    const stdout = run(javaTool("java"), ["-Xmx3g", "-Dmax.bg.threads=1", "-cp", classpath.join(delimiter),
      "dev.gaius.parity.StructureRingsParity", "--seeds", options.seeds.join(","), "--out", file,
      "--label", label, ...extra], `${profile} ${label} run`, {timeout: 30 * 60 * 1000});
    if (!stdout.includes("WORLDGEN_RING_PARITY_OK")) throw new Error(`${profile} ${label}: no result line`);
    console.log(`STRUCTURE_RINGS_RUN ${profile} ${label} wallMillis=${Date.now() - started}`);
    return JSON.parse(readFileSync(file, "utf8"));
  };
  const reference = java([harness, ...vanillaClasspath], "vanilla", []);
  const {text, probes} = probesFor(reference);
  const probeFile = join(out, "probes.txt");
  writeFileSync(probeFile, text);
  const candidate = java([patched, harness, ...vanillaClasspath], `patched-${options.target}`, ["--probes", probeFile]);
  const problems = compareRings(reference, candidate, probes);
  const sets = candidate.seeds.flatMap(({sets: list}) => list);
  const summary = {
    profile, target: options.target, seeds: options.seeds.length, sets: sets.length,
    positions: sets.reduce((sum, set) => sum + set.positions.length, 0),
    probes: Object.values(probes).reduce((sum, list) => sum + list.length, 0),
    list: [...new Set(sets.map((set) => set.list))],
    resolvedAtList: sets.reduce((sum, set) => sum + set.resolvedAtList, 0),
    resolvedAfterOrigin: sets.reduce((sum, set) => sum + set.resolvedAfterGroups[0], 0),
    resolvedBeforeCopy: sets.reduce((sum, set) => sum + set.resolvedBeforeCopy, 0),
    moves: moves(candidate),
    vanillaListMillis: Math.round(timing(reference, "listMillis")),
    patchedListMillis: Math.round(timing(candidate, "listMillis")),
    patchedProbeMillis: Math.round(timing(candidate, "probeMillis")),
    patchedCopyMillis: Math.round(timing(candidate, "copyMillis")),
    problems,
  };
  writeFileSync(join(out, "report.json"), `${JSON.stringify(summary, null, 2)}\n`);
  for (const problem of problems) console.log(`STRUCTURE_RINGS_MISMATCH ${profile} ${options.target}: ${problem}`);
  console.log(`STRUCTURE_RINGS_RESULT ${profile} ${options.target} ${problems.length ? "FAIL" : "PASS"} `
    + `seeds=${summary.seeds} sets=${summary.sets} positions=${summary.positions} probes=${summary.probes} `
    + `resolvedAtList=${summary.resolvedAtList} resolvedAfterOrigin=${summary.resolvedAfterOrigin} `
    + `resolvedBeforeCopy=${summary.resolvedBeforeCopy} vanillaListMillis=${summary.vanillaListMillis} `
    + `patchedListMillis=${summary.patchedListMillis} patchedProbeMillis=${summary.patchedProbeMillis} `
    + `patchedCopyMillis=${summary.patchedCopyMillis}`
    + (summary.moves ? ` moved=${summary.moves.moved}/${summary.moves.total} largestMove=${summary.moves.largest}` : ""));
  return problems.length === 0;
}

function selfTest() {
  const reference = {profile: "p", seeds: [{seed: "1", sets: [{set: "s", positions: [[100, 5], [-3, 90]]}]}]};
  const {probes} = probesFor(reference);
  const list = probes["1 s"];
  if (list.length !== 9 + 9 + 21 * 21) throw new Error(`probe count ${list.length}`);
  const answers = list.map((key) => (key === "100,5" || key === "-3,90" ? "1" : "0")).join("");
  const good = {profile: "p", seeds: [{seed: "1", sets: [{set: "s", positions: [[100, 5], [-3, 90]],
    probes: answers, starts: [[98, 7], [-3, 90]], reach: 8}]}]};
  const check = (label, candidate, expected) => {
    const problems = compareRings(reference, candidate, probes);
    if (problems.length !== expected) throw new Error(`${label}: ${JSON.stringify(problems)}`);
  };
  check("identical", good, 0);
  check("moved", {...good, seeds: [{seed: "1", sets: [{...good.seeds[0].sets[0], positions: [[101, 5], [-3, 90]]}]}]}, 1);
  check("probe", {...good, seeds: [{seed: "1", sets: [{...good.seeds[0].sets[0], probes: `${answers[0] === "1" ? "0" : "1"}${answers.slice(1)}`}]}]}, 1);
  check("reach", {...good, seeds: [{seed: "1", sets: [{...good.seeds[0].sets[0], starts: [[80, 5], [-3, 90]]}]}]}, 1);
  check("missing seed", {profile: "p", seeds: []}, 1);
  console.log("STRUCTURE_RINGS_SELF_TEST PASS");
}

const options = parseArgs(process.argv.slice(2));
if (options.selfTest) {
  selfTest();
} else {
  const profiles = options.profiles.length ? options.profiles : loadProfiles(root).map((profile) => profile.id);
  let ok = true;
  for (const profile of profiles) {
    try {
      ok = runProfile(options, profile) && ok;
    } catch (error) {
      console.log(`STRUCTURE_RINGS_RESULT ${profile} ${options.target} FAIL ${error.message}`);
      ok = false;
    }
  }
  console.log(`STRUCTURE_RINGS_PARITY ${ok ? "PASS" : "FAIL"} target=${options.target} profiles=${profiles.join(",")}`);
  process.exitCode = ok ? 0 : 1;
}
