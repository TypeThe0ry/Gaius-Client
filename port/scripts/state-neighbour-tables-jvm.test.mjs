// JVM gate of MinecraftClientPatcher.patchStateDefinitionNeighbourTables (multi-property block and
// fluid states and their neighbour tables built by index arithmetic in GaiusStateNeighbourTables
// instead of StateDefinition's HashMap<List, S> and StateCollection pivot cache):
//
// 1. port/mc/fixtures/StateNeighbourTablesPatchCheck.java applies the real patch to the profile's
//    vanilla client jar: nothing else in the jar builds neighbour tables, the patch writes only
//    StateDefinition and adds only the prologue in front of the unchanged vanilla body, is
//    idempotent, and refuses a body without the neighbour visit, a second caller and an exception
//    handler without writing. A generation without StateDefinition$StateCollection is a drop.
// 2. port/mc/fixtures/StateNeighbourTablesBootstrapCheck.java runs Bootstrap.bootStrap() in fresh
//    JVMs with -XX:hashCode=3 (identity hashes from one global sequence, as TeaVM gives them out),
//    the class directory first on the class path in every run (vanilla: the vanilla StateDefinition
//    and the unused helper; patched: the patched StateDefinition and the helper), and dumps every
//    block and fluid state (order, ids, values, key/value array classes, the whole neighbour table:
//    classes, row identity, row contents), the identity hash of every block, fluid, state, property,
//    value and of the wildcard, and every built-in registry. This runner requires:
//    - "primed" (both JVMs first run every library path of the vanilla body and of the helper once
//      on throwaway objects, so the JVM's own first-use identity hashes are behind us): the
//      vanilla run twice gives identical files (the gate is deterministic), and the patched run
//      gives byte-identical states, identity hashes and registries, the same identity sequence
//      position after bootstrap, and the same class initialisation order;
//    - "cold" (no priming): identical registries and states (identity hashes in toString masked),
//      and the first identity hash requests of the dumped objects in the same order up to and
//      including the wildcard, i.e. through the first multi-property definition (afterwards JVM-only
//      first-use hashes of the vanilla body shift the sequence, which changes hash-dependent Guava
//      cache eviction on the JVM; TeaVM has no such requests);
//    - mutants of the BrowserBridges class are caught: no value hashes, no wildcard hash, unshared rows,
//      reversed rows.
//
//   node port/scripts/state-neighbour-tables-jvm.test.mjs --profile <id> [--keep <dir>]
// Needs port/work/<id>/client-named.jar and classpath.txt.
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";
import {generateBrowserBridges} from "./browser-bridge-classes.mjs";

const root = fileURLToPath(new URL("../..", import.meta.url));
const argument = name => {
  const index = process.argv.indexOf(name);
  return index > 0 ? process.argv[index + 1] : undefined;
};
const profile = argument("--profile") || process.env.GAIUS_PROFILE;
const keep = argument("--keep");
assert.ok(profile, "--profile <id> (or GAIUS_PROFILE) is required");
const work = join(root, "port/work", profile);
const jar = join(work, "client-named.jar");
assert.ok(existsSync(jar), `missing ${jar}`);
const libraries = readFileSync(join(work, "classpath.txt"), "utf8").trim().split(/[:;]/).filter(Boolean);
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const exe = name => home ? join(home, "bin", process.platform === "win32" ? `${name}.exe` : name) : name;
const m2 = process.env.GAIUS_MAVEN_REPOSITORY || join(process.env.USERPROFILE || process.env.HOME, ".m2", "repository");
const asm = ["asm", "asm-tree", "asm-util", "asm-analysis"]
  .map(name => join(m2, "org/ow2/asm", name, "9.8", `${name}-9.8.jar`));
asm.push(join(m2, "org/teavm/teavm-core/0.15.0/teavm-core-0.15.0.jar"));
for (const file of asm) assert.ok(existsSync(file), `missing ${file}`);

const STATE = "net/minecraft/world/level/block/state";
const HELPER = `${STATE}/GaiusStateNeighbourTables`;
const GAME = /^(net\/minecraft\/|com\/mojang\/)/;

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 << 20, ...options});
  assert.equal(result.status, 0,
    `${command} ${args.at(-3) ?? ""} failed\n${(result.stdout || "").slice(-4000)}${(result.stderr || "").slice(-4000)}`);
  return result.stdout;
}

const sha = text => createHash("sha256").update(text).digest("hex");

function firstDifference(a, b) {
  const length = Math.max(a.length, b.length);
  for (let index = 0; index < length; index++) {
    if (a[index] !== b[index]) return `#${index}: ${a[index]} | ${b[index]}`;
  }
  return "none";
}

// identity.txt: "<label> <hash>" lines; labels made unique by their block/fluid context.
function identities(text) {
  const entries = [];
  let context = "";
  for (const line of text.split("\n").filter(Boolean)) {
    const cut = line.lastIndexOf(" ");
    const label = line.slice(0, cut);
    if (!line.startsWith(" ")) context = label;
    entries.push({label: line.startsWith(" ") ? `${context} /${label.trim()}` : label, hash: Number(line.slice(cut + 1))});
  }
  return entries;
}

// The dumped objects in the order of their first identity hash request (one label per hash).
function requestOrder(entries) {
  const byHash = new Map();
  for (const {label, hash} of entries.slice(1)) if (!byHash.has(hash)) byHash.set(hash, label);
  return [...byHash.entries()].sort((a, b) => a[0] - b[0]).map(entry => entry[1]);
}

function readInit(dir) {
  const names = readFileSync(join(dir, "init.log"), "utf8").split("\n")
    .map(line => /Initializing '([^']+)'/.exec(line)?.[1]).filter(Boolean)
    .filter(name => !name.includes("$$Lambda") && !name.includes("+0x"));
  const prime = names.indexOf("StateNeighbourTablesBootstrapCheck$PrimeDone");
  const boot = names.indexOf("StateNeighbourTablesBootstrapCheck$BootstrapDone");
  assert.ok(prime >= 0 && boot > prime, `class init marks in ${dir}`);
  return {prime: names.slice(0, prime), bootstrap: names.slice(prime + 1, boot)};
}

const out = keep ? join(keep, profile) : mkdtempSync(join(tmpdir(), "gaius-state-neighbours-"));
rmSync(out, {recursive: true, force: true});
try {
  gate();
} finally {
  if (!keep) rmSync(out, {recursive: true, force: true});
}

function gate() {
  const tools = join(out, "tools");
  const patchRoot = join(out, "patch");
  const bridges = join(out, "bridges");
  for (const dir of [tools, patchRoot]) mkdirSync(dir, {recursive: true});

  run(exe("javac"), ["-nowarn", "-proc:none", "--release", "21", "-cp", asm.join(delimiter),
    "-sourcepath", join(root, "port/tools/src/main/java"), "-d", tools,
    join(root, "port/mc/fixtures/StateNeighbourTablesPatchCheck.java"),
    join(root, "port/scripts/fixtures/StateNeighbourTablesMutator.java")]);
  const initializerList = join(out, "clinit-classes.txt");
  const patch = run(exe("java"), ["-cp", [tools, ...asm].join(delimiter), "StateNeighbourTablesPatchCheck", jar,
    patchRoot, initializerList]);
  const patchLine = patch.trim().split("\n").at(-1);
  console.log(patchLine);
  const dropped = /STATE_NEIGHBOUR_PATCH_DROPPED/.test(patchLine);
  generateBrowserBridges({root, profile, role: "src", scratch: out, out: bridges,
    owners: dropped ? undefined : [HELPER], java: exe("java"), javac: exe("javac"), maven: m2});
  if (dropped) {
    assert.ok(!existsSync(join(bridges, `${HELPER}.class`)), `profile ${profile} has no StateCollection but emits ${HELPER}`);
    const report = {profile, outcome: "dropped (no StateDefinition$StateCollection)"};
    mkdirSync(out, {recursive: true});
    writeFileSync(join(out, "report.json"), JSON.stringify(report, null, 2) + "\n");
    console.log(`STATE_NEIGHBOUR_JVM_GATE PASS ${JSON.stringify(report)}`);
    return;
  }
  assert.match(patchLine, /STATE_NEIGHBOUR_PATCH_OK .* prologue=11 idempotent=true negative=3/);
  const initializers = new Set(readFileSync(initializerList, "utf8").split("\n").filter(Boolean));

  const classpath = [jar, ...libraries].join(delimiter);
  const vanillaDefinition = join(out, "vanilla-definition");
  mkdirSync(vanillaDefinition, {recursive: true});
  run("unzip", ["-q", "-o", jar, `${STATE}/StateDefinition.class`, "-d", vanillaDefinition]);

  const harness = join(out, "harness");
  run(exe("javac"), ["-nowarn", "-proc:none", "-encoding", "UTF-8", "-cp", [bridges, classpath].join(delimiter),
    "-d", harness, join(root, "port/mc/fixtures/StateNeighbourTablesBootstrapCheck.java")]);

  function runGate(name, dir, definitionRoot, mode) {
    const runDir = join(out, `run-${name}`);
    mkdirSync(runDir, {recursive: true});
    const stdout = run(exe("java"), ["-Xmx4g", "-Djava.awt.headless=true", "-XX:+UnlockExperimentalVMOptions",
      "-XX:hashCode=3", `-Xlog:class+init=info:file=${join(runDir, "init.log")}`,
      "-cp", [definitionRoot, dir, harness, classpath].join(delimiter), "StateNeighbourTablesBootstrapCheck", name, runDir, mode],
    {cwd: runDir, timeout: 900_000});
    const summary = stdout.split("\n").map(line => /STATE_NEIGHBOUR_GATE .*/.exec(line)?.[0]).find(Boolean);
    assert.ok(summary, `no summary from ${name}:\n${stdout.slice(-2000)}`);
    const files = {};
    for (const file of ["states", "identity", "registries"]) {
      files[file] = readFileSync(join(runDir, `${file}.txt`), "utf8");
    }
    return {name, summary, ...files, init: readInit(runDir)};
  }

  const runs = {};
  for (const [name, definition, mode] of [
    ["vanilla-primed", vanillaDefinition, "primed"],
    ["vanilla-primed-2", vanillaDefinition, "primed"],
    ["patched-primed", patchRoot, "primed"],
    ["vanilla-cold", vanillaDefinition, "cold"],
    ["patched-cold", patchRoot, "cold"],
  ]) {
    runs[name] = runGate(name, bridges, definition, mode);
    console.log(runs[name].summary);
  }

  const vanilla = runs["vanilla-primed"];
  const control = runs["vanilla-primed-2"];
  const patched = runs["patched-primed"];
  for (const file of ["states", "identity", "registries"]) {
    assert.equal(control[file], vanilla[file], `the vanilla gate is not deterministic (${file}.txt)`);
    assert.equal(patched[file], vanilla[file], `primed ${file}.txt differs: `
      + firstDifference(patched[file].split("\n"), vanilla[file].split("\n")));
  }
  const strip = summary => summary.replace(/run=\S+ /, "");
  assert.equal(strip(patched.summary), strip(vanilla.summary), "summaries differ");
  const gameInit = names => names.filter(name => GAME.test(name) && initializers.has(name));
  assert.deepEqual(gameInit(patched.init.bootstrap), gameInit(vanilla.init.bootstrap),
    `class initialisation order differs: ${firstDifference(gameInit(patched.init.bootstrap), gameInit(vanilla.init.bootstrap))}`);
  assert.deepEqual(patched.init.bootstrap.filter(name => GAME.test(name)),
    vanilla.init.bootstrap.filter(name => GAME.test(name)), "game class initialisation (all) differs");

  const vanillaCold = runs["vanilla-cold"];
  const patchedCold = runs["patched-cold"];
  assert.equal(patchedCold.registries, vanillaCold.registries, "cold registries differ");
  const mask = text => text.replace(/@[0-9a-f]+\b/g, "@ID");
  assert.equal(mask(patchedCold.states), mask(vanillaCold.states), "cold states differ: "
    + firstDifference(mask(patchedCold.states).split("\n"), mask(vanillaCold.states).split("\n")));
  const coldVanillaOrder = requestOrder(identities(vanillaCold.identity));
  const coldPatchedOrder = requestOrder(identities(patchedCold.identity));
  const wildcardRank = coldVanillaOrder.indexOf("wildcard");
  assert.ok(wildcardRank > 0, "no wildcard in the cold identity order");
  assert.deepEqual(coldPatchedOrder.slice(0, wildcardRank + 1), coldVanillaOrder.slice(0, wildcardRank + 1),
    `cold identity order differs before the wildcard: ${firstDifference(coldPatchedOrder, coldVanillaOrder)}`);
  let coldAgree = 0;
  while (coldAgree < coldVanillaOrder.length && coldPatchedOrder[coldAgree] === coldVanillaOrder[coldAgree]) coldAgree++;
  const coldOffsets = new Map();
  const ve = identities(vanillaCold.identity);
  const pe = identities(patchedCold.identity);
  for (let index = 0; index < ve.length; index++) {
    const delta = ve[index].hash - pe[index].hash;
    coldOffsets.set(delta, (coldOffsets.get(delta) || 0) + 1);
  }

  // Mutants of the helper: each must change what the primed gate compares.
  const mutants = [
    ["no-value-hashes", "identity"],
    ["no-wildcard-hash", "identity"],
    ["unshared-rows", "states"],
    ["reversed-rows", "states"],
  ];
  const caught = [];
  for (const [what, file] of mutants) {
    const dir = join(out, `classes-mutant-${caught.length}`);
    run(exe("java"), ["-cp", [tools, ...asm].join(delimiter), "StateNeighbourTablesMutator",
      join(bridges, `${HELPER}.class`), what, join(dir, `${HELPER}.class`)]);
    let mutant = null;
    try {
      mutant = runGate(`mutant-${caught.length}`, dir, patchRoot, "primed");
    } catch (error) {
      if (!(error instanceof assert.AssertionError) || !/ failed\n/.test(error.message)
          || /VerifyError|ClassFormatError|LinkageError/.test(error.message)) throw error;
    }
    if (mutant) assert.notEqual(mutant[file], vanilla[file], `mutant not caught: ${what}`);
    caught.push(mutant ? `${what} (${file})` : `${what} (bootstrap fails)`);
    rmSync(join(out, `run-mutant-${caught.length - 1}`), {recursive: true, force: true});
  }

  const summaryOf = run_ => Object.fromEntries(run_.summary.split(" ").slice(1).map(pair => pair.split("=")));
  const report = {
    profile,
    patch: patchLine,
    primed: {
      ...summaryOf(vanilla),
      run: undefined,
      statesSha256: sha(vanilla.states),
      identitySha256: sha(vanilla.identity),
      registriesSha256: sha(vanilla.registries),
      identityLines: vanilla.identity.split("\n").length - 1,
      gameClassesWithInitializerInBootstrap: gameInit(vanilla.init.bootstrap).length,
      identical: ["states", "identity", "registries", "class init order", "control run"],
    },
    cold: {
      vanillaAfterBootstrap: Number(summaryOf(vanillaCold).afterBootstrap),
      patchedAfterBootstrap: Number(summaryOf(patchedCold).afterBootstrap),
      requestOrderAgreesThrough: coldAgree,
      wildcardRank,
      dumpedObjects: coldVanillaOrder.length,
      hashOffsets: Object.fromEntries([...coldOffsets.entries()].sort((a, b) => b[1] - a[1])),
    },
    mutantsCaught: caught,
  };
  writeFileSync(join(out, "report.json"), JSON.stringify(report, null, 2) + "\n");
  console.log(`STATE_NEIGHBOUR_JVM_GATE PASS ${JSON.stringify(report)}`);
}
