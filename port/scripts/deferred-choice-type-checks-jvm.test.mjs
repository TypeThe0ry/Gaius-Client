// JVM gate of MinecraftClientPatcher.patchDeferredChoiceTypeChecks (the dropped Util.fetchChoiceType
// schema checks of entity and block entity registration, deferred to BrowserChoiceTypeChecks until
// the real data fixer is built):
//
// 1. port/mc/fixtures/DeferredChoiceTypePatchCheck.java applies the real patch to the profile's
//    vanilla client jar: the jar has exactly two dropped fetchChoiceType call sites outside Util,
//    the patch changes exactly those two instructions, is idempotent, and refuses a used result, a
//    second site and a missing site without writing anything.
// 2. port/mc/fixtures/DeferredChoiceTypeBootstrapCheck.java bootstraps the vanilla registries in two
//    fresh JVMs, vanilla and with the patched classes, and this runner requires:
//    - the same built-in registries (every entry: raw id, key, class);
//    - the recorded checks equal, in order, the checks vanilla runs (serializable entity types,
//      minecraft block entity types), and every one resolves to a choice type after the replay;
//    - the same log (log4j, every level) during bootstrap, an empty log for the replay, and no
//      "No data fixer registered" line anywhere;
//    - DataFixers is initialised during bootstrap in vanilla and not in the patched run, and the
//      order in which every other Minecraft/Mojang class is initialised is the same (the classes of
//      the data-fixer graph are the only ones that move to the replay).
//
//   node port/scripts/deferred-choice-type-checks-jvm.test.mjs --profile <id> [--keep <dir>]
// Needs port/work/<id>/client-named.jar and classpath.txt.
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import {sourceFile} from "../tools/sourcesets/sourcesets.mjs";

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

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 << 20, ...options});
  assert.equal(result.status, 0,
    `${command} ${args.at(-2) ?? ""} failed\n${(result.stdout || "").slice(-4000)}${(result.stderr || "").slice(-4000)}`);
  return result.stdout;
}

// Data-fixer graph classes: initialised by DataFixers.<clinit> in vanilla, by the replay when patched.
const DATAFIX = /^(net\/minecraft\/util\/datafix\/|net\/minecraft\/util\/filefix\/|com\/mojang\/datafixers\/)/;
const GAME = /^(net\/minecraft\/|com\/mojang\/)/;

function segments(lines, isMark) {
  const parts = [[]];
  for (const line of lines) {
    if (isMark(line)) parts.push([]);
    else parts.at(-1).push(line);
  }
  return parts;
}

function readLog(dir) {
  // pattern "%level|%logger|%msg%n"; hidden-class names and identity hashes are not stable.
  const lines = readFileSync(join(dir, "log.txt"), "utf8").split("\n").filter(Boolean)
    .map(line => line.replace(/\$\$Lambda[/\w$.]*/g, "$$$$Lambda").replace(/@[0-9a-f]{4,}\b/g, "@ID"));
  const parts = segments(lines, line => /^INFO\|gaius-choice-type-gate\|GATE /.test(line));
  assert.equal(parts.length, 3, `log marks in ${dir}`);
  return {bootstrap: parts[0], replay: parts[1], verify: parts[2], all: lines};
}

function readInit(dir) {
  const names = readFileSync(join(dir, "init.log"), "utf8").split("\n")
    .map(line => /Initializing '([^']+)'/.exec(line)?.[1]).filter(Boolean)
    // Hidden lambda classes carry their address in the name; their host class stays in the list.
    .filter(name => !name.includes("$$Lambda") && !name.includes("+0x"));
  const parts = segments(names, name => name.startsWith("DeferredChoiceTypeBootstrapCheck$"));
  assert.equal(parts.length, 3, `class init marks in ${dir}`);
  return {bootstrap: parts[0], replay: parts[1], verify: parts[2]};
}

function firstDifference(a, b) {
  const length = Math.max(a.length, b.length);
  for (let index = 0; index < length; index++) {
    if (a[index] !== b[index]) return `#${index}: ${a[index]} | ${b[index]}`;
  }
  return "none";
}

const out = keep ? join(keep, profile) : mkdtempSync(join(tmpdir(), "gaius-choice-type-"));
rmSync(out, {recursive: true, force: true});
try {
  const tools = join(out, "tools");
  const patched = join(out, "patched");
  const classes = join(out, "classes");
  const sources = join(out, "src");
  for (const dir of [tools, patched, classes, sources]) mkdirSync(dir, {recursive: true});

  run(exe("javac"), ["-nowarn", "-proc:none", "--release", "21", "-cp", asm.join(delimiter),
    "-sourcepath", join(root, "port/tools/src/main/java"), "-d", tools,
    join(root, "port/mc/fixtures/DeferredChoiceTypePatchCheck.java")]);
  const initializerList = join(out, "clinit-classes.txt");
  const patch = run(exe("java"), ["-cp", [tools, ...asm].join(delimiter), "DeferredChoiceTypePatchCheck", jar, patched,
    initializerList]);
  const initializers = new Set(readFileSync(initializerList, "utf8").split("\n").filter(Boolean));
  assert.match(patch, /DEFERRED_CHOICE_TYPE_PATCH_OK .* changed=2 idempotent=true negative=3/);
  console.log(patch.trim().split("\n").at(-1));

  // The helper as the profile's wasmgc build compiles it (source sets, relocations).
  const rel = "dev/gaius/browser/BrowserChoiceTypeChecks.java";
  const helper = sourceFile(profile, "src", rel, {target: "wasmgc", root});
  assert.ok(helper, `profile ${profile} compiles no BrowserChoiceTypeChecks`);
  mkdirSync(dirname(join(sources, rel)), {recursive: true});
  writeFileSync(join(sources, rel), helper.text);
  const classpath = [jar, ...libraries].join(delimiter);
  run(exe("javac"), ["-nowarn", "-proc:none", "-encoding", "UTF-8", "-cp", classpath, "-d", classes,
    join(sources, rel), join(root, "port/mc/fixtures/DeferredChoiceTypeBootstrapCheck.java")]);
  const config = join(out, "log4j2-gate.xml");
  writeFileSync(config, `<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
  <Appenders>
    <File name="gate" fileName="\${sys:gaius.gate.log}" immediateFlush="true" append="false">
      <PatternLayout pattern="%level|%logger|%msg%n"/>
    </File>
  </Appenders>
  <Loggers>
    <Root level="all"><AppenderRef ref="gate"/></Root>
  </Loggers>
</Configuration>
`);

  const runs = {};
  for (const mode of ["vanilla", "patched"]) {
    const dir = join(out, `run-${mode}`);
    mkdirSync(dir, {recursive: true});
    const cp = mode === "patched" ? [patched, classes, classpath] : [classes, classpath];
    const stdout = run(exe("java"), ["-Xmx4g", "-Djava.awt.headless=true",
      `-Xlog:class+init=info:file=${join(dir, "init.log")}`,
      `-Dlog4j2.configurationFile=${config}`, `-Dgaius.gate.log=${join(dir, "log.txt")}`,
      "-cp", cp.join(delimiter), "DeferredChoiceTypeBootstrapCheck", mode, dir], {cwd: dir, timeout: 900_000});
    // Bootstrap wraps System.out into the log ([STDOUT]); the summary line ends up there.
    const summary = [...stdout.split("\n"), ...readFileSync(join(dir, "log.txt"), "utf8").split("\n")]
      .map(line => /CHOICE_TYPE_GATE .*/.exec(line)?.[0]).find(Boolean);
    assert.ok(summary, `no summary from the ${mode} run:\n${stdout.slice(-2000)}`);
    console.log(summary);
    runs[mode] = {
      dir,
      summary,
      registries: readFileSync(join(dir, "registries.txt"), "utf8"),
      expected: readFileSync(join(dir, "expected.txt"), "utf8"),
      log: readLog(dir),
      init: readInit(dir),
    };
  }
  const {vanilla, patched: deferred} = runs;

  assert.equal(deferred.registries, vanilla.registries, "built-in registries differ");
  assert.equal(deferred.expected, vanilla.expected, "expected checks differ");
  const pending = readFileSync(join(deferred.dir, "pending.txt"), "utf8");
  assert.equal(pending, vanilla.expected, "recorded checks differ from vanilla's checks: "
    + firstDifference(pending.split("\n"), vanilla.expected.split("\n")));
  const expectedCount = vanilla.expected.split("\n").filter(Boolean).length;
  assert.ok(expectedCount > 0, "no checks");

  for (const mode of ["vanilla", "patched"]) {
    assert.ok(!runs[mode].log.all.some(line => line.includes("No data fixer registered")),
      `${mode} logged a missing choice type`);
  }
  assert.deepEqual(deferred.log.bootstrap, vanilla.log.bootstrap,
    `bootstrap log differs: ${firstDifference(deferred.log.bootstrap, vanilla.log.bootstrap)}`);
  assert.deepEqual(deferred.log.replay, [], `the replay logged: ${deferred.log.replay.slice(0, 5)}`);
  assert.deepEqual(vanilla.log.replay, [], "vanilla logged between the marks");

  const fixers = "net/minecraft/util/datafix/DataFixers";
  assert.ok(vanilla.init.bootstrap.includes(fixers), "vanilla bootstrap did not build the data fixer");
  assert.ok(!deferred.init.bootstrap.includes(fixers), "the patched bootstrap built the data fixer");
  assert.ok(deferred.init.replay.includes(fixers), "the replay did not build the data fixer");
  const game = names => names.filter(name => GAME.test(name));
  // Classes the vanilla bootstrap initialises and the patched bootstrap does not ("moved"): the
  // data-fixer graph, which the replay initialises in the same order, and game classes outside
  // the data-fixer packages only without a static initializer (initialising them has no effect).
  const deferredBootstrap = new Set(deferred.init.bootstrap);
  const moved = game(vanilla.init.bootstrap).filter(name => !deferredBootstrap.has(name));
  const movedFix = moved.filter(name => DATAFIX.test(name));
  const movedOther = moved.filter(name => !DATAFIX.test(name));
  const movedOtherWithInitializer = movedOther.filter(name => initializers.has(name));
  assert.deepEqual(movedOtherWithInitializer, [], "game classes with a static initializer moved out of the bootstrap");
  const replayFix = game(deferred.init.replay).filter(name => DATAFIX.test(name));
  assert.deepEqual(replayFix, movedFix, `data-fixer classes differ: ${firstDifference(replayFix, movedFix)}`);
  // Every game class with a static initializer that both bootstraps initialise: the same order.
  // (Initialising a class without one runs no code; only its position could move.)
  const movedSet = new Set(moved);
  // The data-fixer packages are left out here: the classes of the fixer graph that game code also
  // uses (Pair, DataFixTypes, ...) are initialised there at the game code's first use instead of by
  // the fixer graph; they are listed in the report.
  const vanillaGame = game(vanilla.init.bootstrap)
    .filter(name => !movedSet.has(name) && initializers.has(name) && !DATAFIX.test(name));
  const deferredGame = game(deferred.init.bootstrap).filter(name => initializers.has(name) && !DATAFIX.test(name));
  const sharedFix = game(deferred.init.bootstrap).filter(name => DATAFIX.test(name));
  const sharedFixWithInitializer = sharedFix.filter(name => initializers.has(name));
  const reorderedWithoutInitializer = game(deferred.init.bootstrap).filter(name => !initializers.has(name)).length;
  assert.deepEqual(deferredGame, vanillaGame,
    `game class initialisation order differs: ${firstDifference(deferredGame, vanillaGame)}`);
  const vanillaAll = new Set(vanilla.init.bootstrap);
  const replayOther = game(deferred.init.replay).filter(name => !movedSet.has(name)
    && !game(vanilla.init.replay).includes(name));
  assert.deepEqual(replayOther, [], `game classes initialised only by the patched replay: ${replayOther.slice(0, 10)}`);
  const helperInit = deferred.init.bootstrap.filter(name => name.startsWith("dev/gaius/"));
  assert.deepEqual(helperInit, ["dev/gaius/browser/BrowserChoiceTypeChecks"], `helper classes ${helperInit}`);
  const library = names => names.filter(name => !GAME.test(name) && !name.startsWith("dev/gaius/"));
  const vanillaLibrary = new Set(library(vanilla.init.bootstrap));
  const deferredLibrary = new Set([...library(deferred.init.bootstrap), ...library(deferred.init.replay)]);
  const libraryOnlyVanilla = [...vanillaLibrary].filter(name => !deferredLibrary.has(name));
  const libraryMovedToReplay = library(deferred.init.replay).filter(name => vanillaLibrary.has(name));

  const report = {
    profile,
    checks: expectedCount,
    registryLines: vanilla.registries.split("\n").length - 1,
    bootstrapLogLines: vanilla.log.bootstrap.length,
    gameClassesWithInitializerInBothBootstraps: vanillaGame.length,
    gameClassesWithoutInitializerInPatchedBootstrap: reorderedWithoutInitializer,
    dataFixerClassesMovedToReplay: replayFix.length,
    otherGameClassesMoved: movedOther,
    dataFixerClassesInitialisedByGameCode: sharedFix.length,
    dataFixerClassesInitialisedByGameCodeWithInitializer: sharedFixWithInitializer,
    libraryClassesMovedToReplay: libraryMovedToReplay.length,
    libraryClassesOnlyInVanilla: libraryOnlyVanilla,
  };
  assert.deepEqual(libraryOnlyVanilla, [], "library classes initialised only in vanilla");
  writeFileSync(join(out, "report.json"), JSON.stringify(report, null, 2) + "\n");
  console.log(`DEFERRED_CHOICE_TYPE_JVM_GATE PASS ${JSON.stringify(report)}`);
} finally {
  if (!keep) rmSync(out, {recursive: true, force: true});
}
