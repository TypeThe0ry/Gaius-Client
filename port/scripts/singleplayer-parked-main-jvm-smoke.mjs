#!/usr/bin/env node
// Parked server Main on a JVM: the browser's idle server Worker starts vanilla
// net.minecraft.server.Main itself and parks right after Bootstrap.validate
// (BrowserIntegratedServerMain.resumeAfterBootstrap, ServerWorkerPatches.patchServerMainBrowser).
// This smoke patches Main from the clean remapped jar with the real patcher chain (patchkit check
// --keep), then runs it on a JVM with the vanilla classes and libraries and two shims
// (parked-main-jvm-smoke/shims): Bootstrap must have run in Main's order before the park, the
// built-in holders (max_health, movement_speed, a block and an item) must be bound there, and the
// real world arguments must re-parse with vanilla's parser after the park.
//
//   node port/scripts/singleplayer-parked-main-jvm-smoke.mjs [--profile <id>]... [--work <port/work>]
//       [--scratch <dir>]
//
// Needs port/work/<profile>/client-named.jar, classpath.txt and libraries (fetch-version.sh,
// remap-client.sh). It proves the vanilla order and the patched bytecode on a JVM; the TeaVM
// runtime itself is covered by the release train's world entry.
import {RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repository = resolve(here, "../..");
const args = process.argv.slice(2);
const profiles = [];
let work = join(repository, "port/work");
let scratchRoot = null;
for (let index = 0; index < args.length; index++) {
  if (args[index] === "--profile") profiles.push(args[++index]);
  else if (args[index] === "--work") work = resolve(args[++index]);
  else if (args[index] === "--scratch") scratchRoot = resolve(args[++index]);
  else throw new Error(`unknown argument ${args[index]}`);
}
if (!profiles.length) profiles.push(RENDERPEARL_PROFILE.id);

const jdk = (name) => {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(candidate + ".exe")) return candidate;
  }
  return name;
};
// classpath.txt is written by MSYS tools (/d/... entries joined by ':').
const nativePath = (value) => process.platform === "win32"
  ? value.replace(/^\/([a-zA-Z])\//, (_, drive) => drive.toUpperCase() + ":/") : value;
const listFiles = (root) => readdirSync(root, {recursive: true}).map(String)
  .filter((name) => name.endsWith(".java")).map((name) => join(root, name));

const scratch = scratchRoot ?? mkdtempSync(join(tmpdir(), "gaius-parked-main-"));
mkdirSync(scratch, {recursive: true});
try {
  for (const profile of profiles) {
    const vanillaJar = join(work, profile, "client-named.jar");
    const classpathFile = join(work, profile, "classpath.txt");
    assert.ok(existsSync(vanillaJar), `missing ${vanillaJar}`);
    assert.ok(existsSync(classpathFile), `missing ${classpathFile}`);
    const libraries = readFileSync(classpathFile, "utf8").trim().split(":/").map((entry, index) =>
      nativePath(index === 0 ? entry : "/" + entry)).filter((entry) => entry && !/client-(named|original)\.jar$/.test(entry));
    for (const library of libraries) assert.ok(existsSync(library), `missing library ${library}`);

    // The real patcher chain on a scratch copy of the clean jar.
    const patchOut = join(scratch, profile + "-patchkit");
    const check = spawnSync(process.execPath, [join(repository, "port/tools/patchkit/patchkit.mjs"),
      "check", "--profile", profile, "--target", "wasmgc", "--out", patchOut, "--keep", "--quiet"],
    {encoding: "utf8", maxBuffer: 256 * 1024 * 1024, env: {...process.env, TMP: scratch, TEMP: scratch, TMPDIR: scratch}});
    const kept = /PATCHKIT_SCRATCH (.+)/.exec(check.stdout + check.stderr)?.[1]?.trim();
    try {
      assert.equal(check.status, 0, `patchkit check failed:\n${check.stdout}\n${check.stderr}`);
      assert.ok(kept, "patchkit did not report its kept scratch directory");
      const patchedMain = join(kept, `${profile}-wasmgc`, "out/net/minecraft/server/Main.class");
      assert.ok(existsSync(patchedMain), `patched server Main is missing: ${patchedMain}`);
      const patched = join(scratch, profile + "-patched/net/minecraft/server");
      mkdirSync(patched, {recursive: true});
      copyFileSync(patchedMain, join(patched, "Main.class"));
    } finally {
      if (kept) rmSync(kept, {recursive: true, force: true});
    }

    const classes = join(scratch, profile + "-classes");
    mkdirSync(classes, {recursive: true});
    const compileClasspath = [vanillaJar, ...libraries].join(delimiter);
    const javac = spawnSync(jdk("javac"), ["--release", "21", "-proc:none", "-nowarn",
      "-classpath", compileClasspath, "-d", classes,
      ...listFiles(join(here, "parked-main-jvm-smoke"))], {encoding: "utf8"});
    assert.equal(javac.status, 0, `javac failed:\n${javac.stdout}${javac.stderr}`);

    // Shims and the patched Main come before the vanilla jar; cwd is scratch (Main writes
    // nothing before the park, and the shim ends the run there).
    const runtime = [classes, join(scratch, profile + "-patched"), vanillaJar, ...libraries].join(delimiter);
    const cwd = join(scratch, profile + "-cwd");
    mkdirSync(cwd, {recursive: true});
    // The patched Main is TeaVM input: its recomputed frames type one existing exception local
    // as Object, which TeaVM accepts and HotSpot's verifier rejects (already true before the park
    // hook; the train's own client-patches Main.class fails the same check), so this JVM run
    // skips bytecode verification of application classes.
    const run = spawnSync(jdk("java"), ["-Xmx2g", "-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote",
      "-classpath", runtime, "ParkedServerMainSmoke"],
      {encoding: "utf8", cwd, maxBuffer: 64 * 1024 * 1024, timeout: 300000});
    const output = (run.stdout || "") + (run.stderr || "");
    const pass = /PARKED_MAIN_JVM_SMOKE PASS[^\n]*/.exec(output)?.[0];
    assert.ok(run.status === 0 && pass, `${profile}: parked Main smoke failed (status ${run.status}):\n${output.slice(-6000)}`);
    // logs/ is the JVM's log4j file appender (the browser runtime logs to the console only).
    const written = readdirSync(cwd).filter((name) => name !== "logs");
    assert.deepEqual(written, [], `${profile}: Main wrote files before the park: ${written}`);
    console.log(`${profile}: ${pass}`);
  }
} finally {
  if (!scratchRoot) rmSync(scratch, {recursive: true, force: true});
}
