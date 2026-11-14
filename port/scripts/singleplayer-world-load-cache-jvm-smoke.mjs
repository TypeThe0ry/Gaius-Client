#!/usr/bin/env node
// World load cache on a JVM: the server's WorldLoader with the world load cache patch
// (WorldLoaderCachePatches) against the real vanilla classes. Per data pack selection (default,
// and a custom world data pack) one JVM loads the world without a prewarm and one with
// BrowserWorldLoadOps.prewarm first; every datapack registry entry, every tag, the advancement,
// recipe and function ids and the pack list must be identical, the default selection must be
// served from the record ("world-load-cache hit"), and the custom selection must run vanilla's
// code ("world-load-cache miss") and still contain the custom pack's content.
//
//   node port/scripts/singleplayer-world-load-cache-jvm-smoke.mjs [--profile <id>] [--work <port/work>]
//       [--scratch <dir>] [--patched <dir of a kept patchkit scratch>]
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
let profile = RENDERPEARL_PROFILE.id;
let work = join(repository, "port/work");
let scratchRoot = null;
let patchedKept = null;
for (let index = 0; index < args.length; index++) {
  if (args[index] === "--profile") profile = args[++index];
  else if (args[index] === "--work") work = resolve(args[++index]);
  else if (args[index] === "--scratch") scratchRoot = resolve(args[++index]);
  else if (args[index] === "--patched") patchedKept = resolve(args[++index]);
  else throw new Error(`unknown argument ${args[index]}`);
}
const jdk = (name) => {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(candidate + ".exe")) return candidate;
  }
  return name;
};
const listFiles = (root) => readdirSync(root, {recursive: true}).map(String)
  .filter((name) => name.endsWith(".java")).map((name) => join(root, name));
const scratch = scratchRoot ?? mkdtempSync(join(tmpdir(), "gaius-world-load-cache-"));
mkdirSync(scratch, {recursive: true});
try {
  const vanillaJar = join(work, profile, "client-named.jar");
  const classpathFile = join(work, profile, "classpath.txt");
  assert.ok(existsSync(vanillaJar), `missing ${vanillaJar}`);
  const libraries = readFileSync(classpathFile, "utf8").trim().split(":/").map((entry, index) =>
    index === 0 ? entry : "/" + entry).filter((entry) => entry && !/client-(named|original)\.jar$/.test(entry));

  let kept = patchedKept;
  if (!kept) {
    const check = spawnSync(process.execPath, [join(repository, "port/tools/patchkit/patchkit.mjs"),
      "check", "--profile", profile, "--target", "wasmgc", "--out", join(scratch, "patchkit"), "--keep", "--quiet"],
    {encoding: "utf8", maxBuffer: 256 * 1024 * 1024, env: {...process.env, TMP: scratch, TEMP: scratch, TMPDIR: scratch}});
    assert.equal(check.status, 0, `patchkit check failed:\n${check.stdout}\n${check.stderr}`);
    kept = /PATCHKIT_SCRATCH (.+)/.exec(check.stdout + check.stderr)?.[1]?.trim();
  }
  assert.ok(kept, "patchkit did not report its kept scratch directory");
  const patchedRoot = join(kept, `${profile}-wasmgc`, "out");
  const patchedLoader = join(patchedRoot, "net/minecraft/server/WorldLoader.class");
  assert.ok(existsSync(patchedLoader), `patched WorldLoader is missing: ${patchedLoader}`);
  const patched = join(scratch, "patched/net/minecraft/server");
  mkdirSync(patched, {recursive: true});
  copyFileSync(patchedLoader, join(patched, "WorldLoader.class"));

  const classes = join(scratch, "classes");
  mkdirSync(classes, {recursive: true});
  const sources = [
    ...listFiles(join(here, "world-load-cache-jvm-smoke")),
    join(repository, "port/mc/shared/renderpearl-1/src/java/dev/gaius/browser/BrowserWorldLoadOps.java"),
    join(repository, "port/mc/shared/renderpearl-1/src/java/dev/gaius/browser/BrowserWorldLoadCache.java"),
  ];
  const javac = spawnSync(jdk("javac"), ["--release", "21", "-proc:none", "-nowarn",
    "-classpath", [vanillaJar, ...libraries].join(delimiter), "-d", classes, ...sources], {encoding: "utf8"});
  assert.equal(javac.status, 0, `javac failed:\n${javac.stdout}${javac.stderr}`);

  const runtime = [classes, join(scratch, "patched"), vanillaJar, ...libraries].join(delimiter);
  const outputs = {};
  for (const selection of ["default", "custom"]) {
    for (const prewarm of selection === "default" ? ["0", "1", "miss"] : ["0", "1"]) {
      const cwd = join(scratch, `run-${selection}-${prewarm}`);
      rmSync(cwd, {recursive: true, force: true});
      mkdirSync(cwd, {recursive: true});
      const run = spawnSync(jdk("java"), ["-Xmx3g", "-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote",
        "-classpath", runtime, "dev.gaius.browser.WorldLoadCacheSmoke", prewarm, selection, cwd],
      {encoding: "utf8", cwd, maxBuffer: 256 * 1024 * 1024, timeout: 600000});
      const output = (run.stdout || "") + (run.stderr || "");
      assert.equal(run.status, 0, `${selection} prewarm=${prewarm} failed (status ${run.status}):\n${output.slice(-6000)}`);
      // Vanilla routes System.out through its logger: strip that prefix.
      const lines = output.split("\n").map((line) => line.replace(/^.*?\[STDOUT\]: /, ""));
      outputs[`${selection}-${prewarm}`] = {
        digest: lines.find((line) => line.startsWith("DIGEST "))?.slice(7),
        sections: lines.filter((line) => line.startsWith("SECTION ")),
        events: lines.filter((line) => line.startsWith("EVENT world-load-cache")),
        timing: lines.find((line) => line.startsWith("TIMING ")),
      };
      console.log(`${selection} prewarm=${prewarm}: ${outputs[`${selection}-${prewarm}`].timing} digest=${outputs[`${selection}-${prewarm}`].digest}`);
      for (const event of outputs[`${selection}-${prewarm}`].events) console.log("  " + event);
    }
  }
  for (const selection of ["default", "custom"]) {
    const off = outputs[`${selection}-0`];
    const on = outputs[`${selection}-1`];
    assert.ok(off.digest && on.digest, `${selection}: missing digest`);
    const differing = on.sections.filter((line, index) => line !== off.sections[index]);
    assert.deepEqual(differing, [], `${selection}: sections differ between the plain load and the prewarmed one`);
    assert.equal(on.digest, off.digest);
  }
  assert.ok(outputs["default-1"].events.some((line) => / hit /.test(line)), "default selection was not served from the record");
  assert.ok(outputs["default-1"].events.some((line) => /resources hit/.test(line)), "default selection did not reuse the recorded resources");
  // A record that does not match the world's packs (diagnostic) is declined and vanilla runs.
  assert.deepEqual(outputs["default-miss"].sections, outputs["default-0"].sections, "forced miss changed the load");
  assert.ok(outputs["default-miss"].events.some((line) => / miss /.test(line)), "forced miss was not declined");
  assert.ok(!outputs["default-0"].events.length, "a load without a prewarm used the cache");
  assert.ok(outputs["custom-1"].events.some((line) => / miss /.test(line)), "custom pack selection did not fall back");
  assert.ok(!outputs["custom-1"].events.some((line) => / hit /.test(line)), "custom pack selection used the record");
  assert.ok(outputs["custom-1"].sections.some((line) => /functions 1:/.test(line)), "custom data pack function is missing");
  console.log("WORLD_LOAD_CACHE_JVM_SMOKE PASS default digest=" + outputs["default-1"].digest +
    " custom digest=" + outputs["custom-1"].digest);
} finally {
  if (!scratchRoot) rmSync(scratch, {recursive: true, force: true});
}
