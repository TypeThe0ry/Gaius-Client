import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {copyFile, mkdir, readdir, readFile, writeFile} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const repository = fileURLToPath(new URL("../..", import.meta.url));
const [patchedArgument, outputArgument] = process.argv.slice(2);
if (!patchedArgument || !outputArgument) throw new Error("usage: node port/scripts/acceptance-harness-jvm-smoke.mjs <patchkit scratch> <output>");
const patchedRoot = resolve(patchedArgument), output = resolve(outputArgument);
await mkdir(output, {recursive: true});
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = ["asm", "asm-tree"].map(name => join(asmRoot, name, "9.8", `${name}-9.8.jar`));
assert.ok(asm.every(existsSync), "cached ASM dependencies required");
const stub = join(output, "stub/dev/gaius/browser/BrowserIntegratedServerMain.java");
await mkdir(dirname(stub), {recursive: true});
await writeFile(stub, `package dev.gaius.browser;
public final class BrowserIntegratedServerMain {
  public static boolean worker;
  public static String loaded = "", waiting = "";
  public static boolean isWorkerServer() { return worker; }
  public static void markSpawnEntitiesLoaded(int x, int z) { loaded += x + "," + z + ";"; }
  public static void markSpawnEntitiesWaiting(int x, int z) { waiting += x + "," + z + ";"; }
}
`);
const classes = join(output, "classes");
const run = (command, args, options = {}) => execFileSync(command, args, {encoding: "utf8", timeout: 90000, maxBuffer: 4 * 1024 * 1024, ...options});
run("javac", ["-J-Xmx3g", "-proc:none", "-cp", asm.join(delimiter), "-d", classes, stub,
  join(repository, "port/scripts/fixtures/GaiusTextureCaptureFixture.java"),
  join(repository, "port/scripts/fixtures/GaiusSpawnEntityGateFixture.java")]);
const reports = [];
const optionsApi = join(output, "GaiusCaptureOptionsApiCheck.java");
await writeFile(optionsApi, `import net.minecraft.client.Minecraft;
public final class GaiusCaptureOptionsApiCheck {
  public static boolean[] capture(Minecraft minecraft) {
    return new boolean[] {minecraft.options.cloudStatus().get().ordinal() == 0,
      minecraft.options.particles().get().ordinal() == 2};
  }
}
`);
for (const filename of (await readdir(join(repository, "port/versions"))).filter(name => name.endsWith(".json"))) {
  const config = JSON.parse(await readFile(join(repository, "port/versions", filename), "utf8"));
  const profile = config.id;
  const work = join(repository, "port/work", profile);
  const original = join(work, "client-named.jar");
  const patched = join(patchedRoot, `${profile}-js/out`);
  assert.ok(existsSync(original) && existsSync(patched), `profile ${profile} inputs required`);
  const animation = run("java", ["-Xmx3g", "-cp", [classes, ...asm].join(delimiter), "GaiusTextureCaptureFixture", original, patched, profile]);
  const overlay = join(output, `spawn-overlay-${profile}`);
  for (const name of ["net/minecraft/server/network/config/PrepareSpawnTask$Preparing.class",
    "net/minecraft/server/level/PlayerSpawnFinder.class", "net/minecraft/server/level/ServerLevel.class"]) {
    await mkdir(dirname(join(overlay, name)), {recursive: true}); await copyFile(join(patched, name), join(overlay, name));
  }
  const librariesRoot = join(work, "libraries");
  const libraries = (await readdir(librariesRoot, {recursive: true})).filter(name => name.endsWith(".jar")).sort().map(name => join(librariesRoot, name));
  const apiClasspath = join(output, `options-api-classpath-${profile}.txt`);
  await writeFile(apiClasspath, ["-proc:none", "-cp", '"' + [original, ...libraries].join(delimiter).replaceAll("\\", "/") + '"',
    "-d", '"' + classes.replaceAll("\\", "/") + '"', '"' + optionsApi.replaceAll("\\", "/") + '"'].join("\n") + "\n");
  run("javac", ["-J-Xmx3g", "@" + apiClasspath]);
  const classpath = join(output, `classpath-${profile}.txt`);
  await writeFile(classpath, ["-Xmx3g", "-cp", '"' + [classes, overlay, original, ...libraries].join(delimiter).replaceAll("\\", "/") + '"', "GaiusSpawnEntityGateFixture"].join("\n") + "\n");
  const spawn = run("java", ["@" + classpath], {cwd: output});
  assert.match(spawn, /SPAWN_FIXUP_ONLY_FOR_SEARCHED_SPAWN/);
  const task = run("javap", ["-J-Xmx3g", "-classpath", [patched, original].join(delimiter), "-p", "-c", "net.minecraft.server.network.config.PrepareSpawnTask"]);
  assert.match(task, /SavedPosition.position:[\s\S]*?Optional.isPresent:[\s\S]*?iconst_1[\s\S]*?ixor[\s\S]*?gaius\$spawnSearched:Z/);
  // Respawn search (patchPlayerSpawnFinderBrowser): a respawn runs vanilla's spawn search, a
  // joining player keeps the deferred path. The fixture subclasses ServerLevel, so it is
  // compiled per profile with that profile's super constructor arity.
  assert.match(task, /iconst_1\s+\d+: putstatic[^\n]*PlayerSpawnFinder\.gaius\$deferSpawnSearch:Z\s+\d+: invokevirtual[^\n]*Optional\.orElseGet:[^\n]*\s+\d+: iconst_0\s+\d+: putstatic[^\n]*PlayerSpawnFinder\.gaius\$deferSpawnSearch:Z\s+\d+: checkcast[^\n]*CompletableFuture/,
    `${profile} PrepareSpawnTask.start must defer exactly its findSpawn orElseGet`);
  const levelConstructor = run("javap", ["-J-Xmx3g", "-classpath", original, "net.minecraft.server.level.ServerLevel"])
    .match(/public net\.minecraft\.server\.level\.ServerLevel\(([^)]*)\);/)?.[1];
  assert.ok(levelConstructor !== undefined, `${profile} public ServerLevel constructor is required`);
  const parameterTypes = [];
  for (let depth = 0, start = 0, index = 0; index <= levelConstructor.length; index++) {
    const character = levelConstructor[index];
    if (character === "<") depth++;
    if (character === ">") depth--;
    if ((character === "," && depth === 0) || index === levelConstructor.length) {
      if (levelConstructor.slice(start, index).trim()) parameterTypes.push(levelConstructor.slice(start, index).trim());
      start = index + 1;
    }
  }
  const superArguments = parameterTypes.map(type => ({boolean: "false", long: "0L", int: "0"})[type] ?? "null").join(", ");
  const respawnRoot = join(output, `respawn-search-${profile}`);
  const respawnSource = join(respawnRoot, "src", "GaiusRespawnSearchFixture.java");
  await mkdir(dirname(respawnSource), {recursive: true});
  await writeFile(respawnSource, (await readFile(join(repository, "port/scripts/fixtures/GaiusRespawnSearchFixture.java"), "utf8"))
    .replace("/*SERVER_LEVEL_SUPER_ARGUMENTS*/", superArguments));
  const respawnClasses = join(respawnRoot, "classes");
  const respawnCompile = join(respawnRoot, "javac.args");
  await writeFile(respawnCompile, ["-proc:none", "-nowarn", "-cp", '"' + [original, ...libraries].join(delimiter).replaceAll("\\", "/") + '"',
    "-d", '"' + respawnClasses.replaceAll("\\", "/") + '"', '"' + respawnSource.replaceAll("\\", "/") + '"'].join("\n") + "\n");
  run("javac", ["-J-Xmx3g", "@" + respawnCompile], {stdio: ["ignore", "pipe", "pipe"]});
  const respawn = {};
  for (const target of ["js", "wasmgc"]) {
    const targetOut = join(patchedRoot, `${profile}-${target}/out`);
    if (target !== "js" && !existsSync(targetOut)) continue;
    const respawnOverlay = join(respawnRoot, `overlay-${target}`);
    for (const name of ["net/minecraft/server/level/PlayerSpawnFinder.class",
      "net/minecraft/server/network/config/PrepareSpawnTask.class"]) {
      await mkdir(dirname(join(respawnOverlay, name)), {recursive: true}); await copyFile(join(targetOut, name), join(respawnOverlay, name));
    }
    const respawnClasspath = join(respawnRoot, `classpath-${target}.txt`);
    await writeFile(respawnClasspath, ["-Xmx3g", "-Xverify:all", "-cp", '"' + [respawnClasses, respawnOverlay, original, ...libraries].join(delimiter).replaceAll("\\", "/") + '"',
      "GaiusRespawnSearchFixture"].join("\n") + "\n");
    const result = run("java", ["@" + respawnClasspath], {cwd: output});
    for (const marker of ["RESPAWN_SEARCH_ON_GROUND", "RESPAWN_SEARCH_RADIUS_ZERO", "RESPAWN_SEARCH_FALLBACK",
      "JOIN_SPAWN_SEARCH_DEFERRED", "PREPARE_SPAWN_TASK_DEFER_VERIFIED"]) {
      assert.ok(result.includes(marker), `${profile} ${target} respawn search fixture missed ${marker}:\n${result}`);
    }
    respawn[target] = result.split("\n").filter(line => /RESPAWN_SEARCH|JOIN_SPAWN|PREPARE_SPAWN_TASK_DEFER/.test(line))
      .map(line => line.replace(/^.*\]: /, "").trim());
    console.log(`${profile} ${target} RESPAWN_SEARCH_OK`);
  }
  const screen = run("javap", ["-J-Xmx3g", "-classpath", original, "-p", "-c", "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen"]);
  const worldTab = run("javap", ["-J-Xmx3g", "-classpath", original, "-p", "-c", "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab"]);
  assert.match(worldTab, /CreateWorldScreen\$WorldTab\$1[\s\S]*?putfield[^\n]*seedEdit/);
  const navigationOwner = screen.match(/\/\/ Method (net\/minecraft\/client\/gui\/components\/tabs\/\w+)\.keyPressed:/)?.[1]?.replaceAll("/", ".");
  assert.ok(navigationOwner, "vanilla world screen keyboard navigation is required");
  let navigation = run("javap", ["-J-Xmx3g", "-classpath", original, "-p", "-c", navigationOwner]);
  const shortcutOwner = navigation.match(/extends (net\.minecraft\.client\.gui\.components\.tabs\.TabNavigationBar)/)?.[1] || navigationOwner;
  if (shortcutOwner !== navigationOwner) navigation = run("javap", ["-J-Xmx3g", "-classpath", original, "-p", "-c", shortcutOwner]);
  assert.match(navigation, /KeyEvent.hasControlDownWithQuirk/);
  assert.match(navigation, /(?:KeyEvent.getDigit:|InputConstants\$Key.getNumericKeyValue:)[\s\S]*?iconst_1[\s\S]*?isub/);
  reports.push({profile, animation: animation.trim(), spawn: spawn.trim(), respawn, optionsApi: true,
    seedStructure: {seedEditClass: "CreateWorldScreen$WorldTab$1", navigationOwner, shortcutOwner}});
  console.log(animation.trim()); console.log(`${profile} SPAWN_FIXUP_ONLY_FOR_SEARCHED_SPAWN`);
  console.log(`${profile} CAPTURE_OPTIONS_API_OK`);
  console.log(`${profile} SEED_WIDGET_AND_CONTROL_DIGIT_STRUCTURE_OK`);
}
await writeFile(join(output, "jvm-results.json"), JSON.stringify(reports, null, 2));
