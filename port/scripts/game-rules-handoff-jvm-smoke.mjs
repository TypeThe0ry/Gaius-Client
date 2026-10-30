#!/usr/bin/env node
// Singleplayer game-rules handoff, checked without TeaVM, a browser or a build.
//
// The Create World screen decides the game rules of a new world. In the browser the integrated
// server runs in a Worker as the dedicated-server Main, so everything vanilla hands to its
// integrated server has to reach it explicitly. For the profiles whose LevelSettings holds the
// GameRules they travel inside level.dat; for the others vanilla passes them as a separate
// Optional<GameRules> (Minecraft.doWorldLoad -> IntegratedServer -> MinecraftServer), which the
// patched client forwards in the Worker start message (BrowserGameRulesHandoff) and the patched
// dedicated-server factory feeds to the DedicatedServer constructor.
//
// Per profile (a vanilla jar port/work/<profile>/client-named.jar is required):
//   semantic  compiles the profile's BrowserGameRulesHandoff and the plain-JVM fixture
//             port/scripts/fixtures/GameRulesHandoffCheck.java against the vanilla jar and runs
//             it: non-default rules survive client encode -> Worker decode -> GameRules.setAll,
//             broken texts fail loudly, and the profile's level.dat channel is exercised with
//             vanilla's own LevelSettings.parse where LevelSettings holds the rules;
//   bytecode  runs the real MinecraftClientPatcher on a copy of the jar and reads the patched
//             Minecraft.doWorldLoad and Main dedicated-server factory with javap: whichever
//             channel the vanilla bytecode uses must be forwarded, and the two channels must
//             agree (rules in LevelSettings <=> no separate Optional<GameRules> argument).
//
// usage: node port/scripts/game-rules-handoff-jvm-smoke.mjs [--profile <id>]... [--stages semantic,bytecode]
//          [--unpatched-control] [--dropped-rules-control]
// --unpatched-control is the negative control of the bytecode stage: the patcher is not run and the
// stage passes only if it rejects the vanilla bytecode. --dropped-rules-control is the one of the
// semantic stage: the Worker side of the handoff is mutated to lose the rules (the defect this
// smoke guards), and the stage passes only if the fixture rejects it.
// JVM work: run it through the machine's build slot (slot.sh build <tag> -- node ...); -Xmx3g.
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";
import {sourceFile} from "../tools/sourcesets/sourcesets.mjs";
import {VERSION_PROFILES} from "./verification-profiles.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const args = process.argv.slice(2);
const profileArguments = [];
let stages = new Set(["semantic", "bytecode"]);
let unpatchedControl = false;
let droppedRulesControl = false;
for (let index = 0; index < args.length; index++) {
  if (args[index] === "--profile") profileArguments.push(args[++index]);
  else if (args[index] === "--stages") stages = new Set(String(args[++index]).split(","));
  else if (args[index] === "--unpatched-control") unpatchedControl = true;
  else if (args[index] === "--dropped-rules-control") droppedRulesControl = true;
  else throw new Error(`unknown argument ${args[index]}`);
}
for (const stage of stages) assert.ok(["semantic", "bytecode"].includes(stage), `unknown stage ${stage}`);

const nativePath = (value) => process.platform === "win32" && /^\/[A-Za-z]\//.test(value)
  ? `${value[1].toUpperCase()}:${value.slice(2)}` : value;
function jdkTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
    const candidate = join(nativePath(home), "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}
const run = (tool, toolArgs, options = {}) => execFileSync(jdkTool(tool), toolArgs, {
  encoding: "utf8", maxBuffer: 256 * 1024 * 1024, timeout: 600_000, cwd: repositoryRoot,
  stdio: ["ignore", "pipe", "pipe"], ...options,
});

const requested = profileArguments.length > 0
  ? profileArguments : VERSION_PROFILES.map((profile) => profile.id);
const workRoot = join(repositoryRoot, "port", "work");
const profiles = requested.filter((id) => existsSync(join(workRoot, id, "client-named.jar")));
assert.ok(profiles.length > 0, `no vanilla client-named.jar under ${workRoot} for ${requested.join(", ")}`);
for (const id of requested) {
  if (!profiles.includes(id)) console.log(`SKIP ${id}: no port/work/${id}/client-named.jar`);
}

function classpathOf(profile) {
  const text = readFileSync(join(workRoot, profile, "classpath.txt"), "utf8").trim();
  const entries = text.split(":").filter(Boolean).map((entry) => nativePath(entry));
  return [join(workRoot, profile, "client-named.jar"), ...entries].join(delimiter);
}

const scratch = mkdtempSync(join(tmpdir(), "gaius-game-rules-handoff-"));
const results = [];
try {
  for (const profile of profiles) {
    const report = {profile};
    if (stages.has("semantic") && droppedRulesControl) {
      let rejected = null;
      try {
        semantic(profile);
      } catch (error) {
        // The game bootstrap routes the JVM's stderr into its log on stdout.
        const lines = `${error.stdout ?? ""}\n${error.stderr ?? ""}\n${error.message}`.split("\n");
        rejected = lines.find((line) => /AssertionError/.test(line)) ?? lines[0];
      }
      assert.ok(rejected, `${profile}: a handoff that drops the rules passed the semantic stage`);
      console.log(`CONTROL ${profile}: dropped rules rejected: ${rejected.trim()}`);
      continue;
    }
    if (stages.has("semantic")) Object.assign(report, semantic(profile));
    if (stages.has("bytecode") && unpatchedControl) {
      let rejected = null;
      try {
        bytecode(profile);
      } catch (error) {
        rejected = String(error.message).split("\n")[0];
      }
      assert.ok(rejected, `${profile}: the unpatched jar passed the bytecode stage; the stage proves nothing`);
      console.log(`CONTROL ${profile}: unpatched jar rejected: ${rejected}`);
      continue;
    }
    if (stages.has("bytecode")) Object.assign(report, bytecode(profile));
    if (stages.has("semantic") && stages.has("bytecode")) {
      assert.equal(report.levelSettingsHoldGameRules, !report.vanillaOptionalGameRules,
        `${profile}: rules must reach the server through exactly one vanilla channel `
        + "(LevelSettings or the separate Optional<GameRules>)");
    }
    results.push(report);
    console.log(`PROFILE ${JSON.stringify(report)}`);
  }
  console.log(`GAME_RULES_HANDOFF_JVM_SMOKE PASS profiles=${profiles.join(",")}`);
} finally {
  rmSync(scratch, {recursive: true, force: true});
}

function semantic(profile) {
  const directory = join(scratch, `${profile}-semantic`);
  const sources = join(directory, "src");
  mkdirSync(join(sources, "dev/gaius/browser"), {recursive: true});
  const helper = sourceFile(profile, "src", "dev/gaius/browser/BrowserGameRulesHandoff.java",
    {root: repositoryRoot});
  assert.ok(helper, `profile ${profile} compiles no BrowserGameRulesHandoff`);
  let helperText = helper.text;
  if (droppedRulesControl) {
    const mutated = helperText.replace(
      /return Optional\.of\(GameRules\.codec\(enabledFeatures\)[^;]*;/, "return Optional.empty();");
    assert.notEqual(mutated, helperText, "the dropped-rules control no longer finds the decode statement");
    helperText = mutated;
  }
  writeFileSync(join(sources, "dev/gaius/browser/BrowserGameRulesHandoff.java"), helperText);
  const fixture = join(repositoryRoot, "port/scripts/fixtures/GameRulesHandoffCheck.java");
  copyFileSync(fixture, join(sources, "GameRulesHandoffCheck.java"));
  const classes = join(directory, "classes");
  mkdirSync(classes, {recursive: true});
  const classpath = classpathOf(profile);
  run("javac", ["-J-Xmx3g", "-J-Duser.language=en", "-encoding", "UTF-8", "-Xlint:all", "-proc:none",
    "-d", classes, "-classpath", classpath,
    join(sources, "dev/gaius/browser/BrowserGameRulesHandoff.java"),
    join(sources, "GameRulesHandoffCheck.java")]);
  const output = run("java", ["-Xmx3g", "-Xss16m", "-classpath", [classes, classpath].join(delimiter),
    "GameRulesHandoffCheck"]);
  const facts = Object.fromEntries([...output.matchAll(/^CHECK (\w+)=(\d+)$/gm)]
    .map((match) => [match[1], Number(match[2])]));
  const total = /^CHECKS (\d+)$/m.exec(output);
  assert.ok(total && Number(total[1]) > 50, `${profile}: fixture did not finish its checks\n${output}`);
  assert.ok(facts.rulesCompared > 40, `${profile}: too few rules compared`);
  const holds = facts.levelSettingsHoldGameRules === 1;
  if (holds) {
    assert.equal(facts.levelDatCarriesRules, 1, `${profile}: LevelSettings holds the rules but level.dat lost them`);
  }
  return {
    semanticChecks: Number(total[1]),
    rulesCompared: facts.rulesCompared,
    encodedBytes: facts.encodedBytes,
    levelSettingsHoldGameRules: holds,
  };
}

function bytecode(profile) {
  const directory = join(scratch, `${profile}-bytecode`);
  const classes = join(directory, "tools-classes");
  const patched = join(directory, "patched");
  mkdirSync(classes, {recursive: true});
  mkdirSync(patched, {recursive: true});
  const repository = process.env.M2_REPO || join(homedir(), ".m2", "repository");
  const asm = join(repository, "org/ow2/asm/asm/9.8/asm-9.8.jar");
  const asmTree = join(repository, "org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar");
  const asmCp = [asm, asmTree].join(delimiter);
  const toolsSource = join(repositoryRoot, "port/tools/src/main/java");
  run("javac", ["-J-Xmx3g", "--release", "21", "-proc:none", "-classpath", asmCp,
    "-sourcepath", toolsSource, "-d", classes,
    join(toolsSource, "dev/gaius/tools/MinecraftClientPatcher.java")]);
  // The real chain step: the patcher reads the vanilla jar and writes the rewritten classes.
  const jar = join(workRoot, profile, "client-named.jar");
  if (!unpatchedControl) {
    run("java", ["-Xmx3g", "-classpath", [classes, asmCp].join(delimiter),
      "dev.gaius.tools.MinecraftClientPatcher", jar, patched, profile]);
  }

  const javapClass = (name, searchPath) => run("javap", ["-p", "-c", "-classpath", searchPath, name]);
  const vanilla = classpathOf(profile);
  const patchedPath = [patched, vanilla].join(delimiter);

  // --- client: Minecraft.doWorldLoad hands the Worker everything vanilla gives its IntegratedServer
  const rawMinecraft = javapClass("net.minecraft.client.Minecraft", vanilla);
  const patchedMinecraft = javapClass("net.minecraft.client.Minecraft", patchedPath);
  const rawLoad = methodBody(rawMinecraft, /\bdoWorldLoad\(/);
  const patchedLoad = methodBody(patchedMinecraft, /\bdoWorldLoad\(/);
  const vanillaOptionalGameRules = /doWorldLoad\([^)]*java\.util\.Optional<net\.minecraft\.world\.level\.gamerules\.GameRules>/
    .test(rawLoad.signature);
  const openDescriptor = "(Lnet/minecraft/client/Minecraft;"
    + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
    + "Lnet/minecraft/server/WorldStem;" + (vanillaOptionalGameRules ? "Ljava/util/Optional;" : "") + "Z)Z";
  const opens = patchedLoad.instructions.filter((line) =>
    line.includes(`BrowserSingleplayerClient.open:${openDescriptor}`));
  assert.equal(opens.length, 1,
    `${profile}: doWorldLoad must call BrowserSingleplayerClient.open${openDescriptor} once`);
  const optionalLocal = vanillaOptionalGameRules ? ["aload 4"] : [];
  const newWorldLocal = vanillaOptionalGameRules ? "iload 5" : "iload 4";
  const expectedHead = ["aload_0", "aload_1", "aload_3", ...optionalLocal, newWorldLocal,
    "invokestatic", "ifeq", "return"];
  assert.deepEqual(patchedLoad.mnemonics.slice(0, expectedHead.length), expectedHead,
  `${profile}: the Worker handoff must be the first thing doWorldLoad does and must pass `
    + `${vanillaOptionalGameRules ? "the Optional<GameRules> argument (local 4)" : "no separate rules"}`);

  // --- server: the dedicated-server factory gives DedicatedServer the rules the page sent
  const rawMain = javapClass("net.minecraft.server.Main", vanilla);
  const patchedMain = javapClass("net.minecraft.server.Main", patchedPath);
  const rawFactory = methodBodies(rawMain, /lambda\$main\$\d+\(/)
    .filter((body) => /net\.minecraft\.server\.dedicated\.DedicatedServer lambda\$main\$/.test(body.signature));
  const patchedFactory = methodBodies(patchedMain, /lambda\$main\$\d+\(/)
    .filter((body) => /net\.minecraft\.server\.dedicated\.DedicatedServer lambda\$main\$/.test(body.signature));
  assert.equal(rawFactory.length, 1, `${profile}: expected one vanilla dedicated-server factory lambda`);
  assert.equal(patchedFactory.length, 1, `${profile}: expected one patched dedicated-server factory lambda`);
  const rawServerOptional = rawFactory[0].instructions.some((line) =>
    /DedicatedServer\."<init>":\(.*Ljava\/util\/Optional;/.test(line));
  assert.equal(rawServerOptional, vanillaOptionalGameRules,
    `${profile}: the DedicatedServer constructor takes an Optional<GameRules> exactly when `
    + "doWorldLoad passes one to the IntegratedServer");
  const factory = patchedFactory[0];
  const initialRules = factory.instructions.findIndex((line) =>
    line.includes("BrowserIntegratedServerMain.initialGameRules:(Lnet/minecraft/world/flag/FeatureFlagSet;)Ljava/util/Optional;"));
  const constructed = factory.instructions.findIndex((line) => /DedicatedServer\."<init>"/.test(line));
  if (vanillaOptionalGameRules) {
    assert.ok(initialRules > 0 && constructed > initialRules,
      `${profile}: the dedicated-server factory must pass the page's rules to the DedicatedServer constructor`);
    const between = factory.instructions.slice(0, initialRules).join("\n");
    assert.ok(/WorldStem\.worldDataAndGenSettings/.test(between)
      && /WorldData\.enabledFeatures/.test(between),
    `${profile}: the rules must be decoded against the world's enabled features`);
    assert.equal(factory.instructions.filter((line) => /Optional\.empty:/.test(line)).length, 0,
      `${profile}: the factory still passes Optional.empty() as the initial game rules`);
  } else {
    assert.equal(initialRules, -1,
      `${profile}: this profile's DedicatedServer takes no separate game rules`);
  }
  return {vanillaOptionalGameRules, patchedWorkerOpen: openDescriptor, patchedServerFactory: factory.name};
}

/** One method of javap -p -c output: signature line, instruction lines and mnemonics. */
function methodBodies(listing, headerPattern) {
  const lines = listing.split(/\r?\n/);
  const bodies = [];
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (!/^  \S.*\);?$/.test(line) && !/^  \S.*\) throws .*;$/.test(line)) continue;
    if (!headerPattern.test(line)) continue;
    const instructions = [];
    for (let next = index + 1; next < lines.length && lines[next].trim() !== ""; next++) {
      const match = /^\s+\d+: (.*)$/.exec(lines[next]);
      if (match) instructions.push(match[1].replace(/\s+/g, " ").trim());
    }
    const mnemonics = instructions.map((text) => {
      const [operation, operand] = text.split(" ");
      return operation === "aload" || operation === "iload" ? `${operation} ${operand}` : operation;
    });
    const name = /(lambda\$\w+\$\d+|doWorldLoad)\(/.exec(line)?.[1] ?? line.trim();
    bodies.push({signature: line.trim(), name, instructions, mnemonics});
  }
  return bodies;
}

function methodBody(listing, headerPattern) {
  const bodies = methodBodies(listing, headerPattern);
  assert.equal(bodies.length, 1, `expected one method matching ${headerPattern}, got ${bodies.length}`);
  return bodies[0];
}
