#!/usr/bin/env node
// JVM parity for MinecraftClientPatcher.patchIdentifierHashMemo.
//
// The patcher chain of the selected TeaVM target runs on a scratch copy of the vanilla jar
// (patchkit.mjs check --keep, no TeaVM); its Identifier class goes in front of the vanilla jar.
// identifier-hash-parity/IdentifierHashParity.java prints digests of hashCode and equals over
// hand-picked ids and every id of every built-in registry (rebuilt copies, neighbours, HashMap
// round trips) with the vanilla and the patched class; every line must be equal.
//
//   node port/scripts/identifier-hash-parity.mjs [--profile <id>] [--target wasmgc|js] [--out-dir <dir>]

import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, readFileSync, rmSync} from "node:fs";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const source = join(root, "port/scripts/identifier-hash-parity/IdentifierHashParity.java");
const patchId = "MinecraftClientPatcher.patchIdentifierHashMemo";

function usage(message) {
  console.error(`identifier-hash-parity: ${message}`);
  process.exit(2);
}

function parseArgs(argv) {
  const options = {
    profile: "26.3",
    target: "wasmgc",
    outDir: join(process.env.TMPDIR || process.env.TMP || ".", "gaius-identifier-hash-parity")
  };
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    if (argument === "--profile") options.profile = argv[++index] ?? usage("--profile needs a value");
    else if (argument === "--target") options.target = argv[++index] ?? usage("--target needs a value");
    else if (argument === "--out-dir") options.outDir = resolve(argv[++index] ?? usage("--out-dir needs a value"));
    else usage(`unknown argument ${argument}`);
  }
  if (!["js", "wasmgc"].includes(options.target)) usage("--target must be js or wasmgc");
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
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 32 * 1024 * 1024, ...extra});
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${label} failed (exit ${result.status})\n${`${result.stdout || ""}${result.stderr || ""}`.slice(-8000)}`);
  }
  // The game logger may write the harness lines to either stream.
  return `${result.stdout || ""}\n${result.stderr || ""}`;
}

function libraries(work) {
  const text = readFileSync(join(work, "classpath.txt"), "utf8").trim();
  const entries = text.startsWith("/") ? text.split(":") : text.split(delimiter);
  return entries.filter(Boolean).map(nativePath)
    .filter((entry) => !/client-(named|original)\.jar$/.test(entry));
}

const COMPARED = /^(registryIds|registryHash|registryEquality|registryMisses|count|hashDigest|equalityDigest|mapMisses|hashFormula) /;

/** The harness lines; after Bootstrap, System.out goes through the logger ("[...]: [STDOUT]: "). */
function normalizedLines(stdout) {
  return stdout.split(/\r?\n/).map((line) => {
    const index = line.indexOf("[STDOUT]: ");
    return index < 0 ? line : line.slice(index + "[STDOUT]: ".length);
  }).filter((line) => COMPARED.test(line));
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const work = join(root, "port/work", options.profile);
  const vanillaJar = join(work, "client-named.jar");
  if (!existsSync(vanillaJar)) usage(`missing ${vanillaJar}`);
  rmSync(options.outDir, {recursive: true, force: true});
  mkdirSync(options.outDir, {recursive: true});
  const patchScratch = join(options.outDir, "patchkit");
  const patchOutput = join(patchScratch, "manifests");
  const patchStdout = run(process.execPath, [join(root, "port/tools/patchkit/patchkit.mjs"), "check",
    "--profile", options.profile, "--target", options.target, "--keep", "--scratch", patchScratch,
    "--out", patchOutput, "--compare", "--warn"], "patchkit check", {cwd: root});
  const scratchLine = patchStdout.split(/\r?\n/).find((line) => line.startsWith("PATCHKIT_SCRATCH "));
  if (!scratchLine) throw new Error("patchkit did not report PATCHKIT_SCRATCH");
  const manifest = JSON.parse(readFileSync(join(patchOutput, options.profile,
    `patch-manifest.${options.target}.json`), "utf8"));
  const entry = manifest.patches.find((patch) => patch.id === patchId);
  console.log(`IDENTIFIER_HASH_PATCH ${options.profile} ${options.target} ${entry?.outcome || "absent"}`);
  if (!entry || entry.outcome !== "APPLIED") throw new Error(`${patchId} is not APPLIED`);
  const patchedJar = join(scratchLine.slice("PATCHKIT_SCRATCH ".length).trim(),
    `${options.profile}-${options.target}`, "client.jar");
  const patchedClasses = join(options.outDir, "patched-classes");
  mkdirSync(patchedClasses, {recursive: true});
  run(javaTool("jar"), ["--extract", "--file", patchedJar, "net/minecraft/resources/Identifier.class"],
    "extract patched Identifier", {cwd: patchedClasses});
  const release = String(JSON.parse(readFileSync(join(root, "port/versions", `${options.profile}.json`), "utf8")).javaVersion);
  const cp = [vanillaJar, ...libraries(work)];
  const harness = join(options.outDir, "harness");
  run(javaTool("javac"), ["-J-Xmx3g", "-J-Duser.language=en", "--release", release, "-proc:none",
    "-cp", cp.join(delimiter), "-d", harness, source], "compile parity harness");
  const vanilla = run(javaTool("java"), ["-Xmx3g", "-Dmax.bg.threads=1", "-cp", [harness, ...cp].join(delimiter),
    "IdentifierHashParity"], "run vanilla parity", {cwd: options.outDir});
  const patched = run(javaTool("java"), ["-Xmx3g", "-Dmax.bg.threads=1", "-cp", [harness, patchedClasses, ...cp].join(delimiter),
    "IdentifierHashParity"], "run patched parity", {cwd: options.outDir});
  const equal = normalizedLines(vanilla).length === 9
    && normalizedLines(vanilla).includes("registryMisses 0") && normalizedLines(vanilla).includes("mapMisses 0")
    && JSON.stringify(normalizedLines(vanilla)) === JSON.stringify(normalizedLines(patched));
  console.log(normalizedLines(vanilla).join("\n"));
  console.log(`IDENTIFIER_HASH_PARITY ${options.profile} ${options.target} ${equal ? "PASS" : "FAIL"}`);
  if (!equal) {
    console.error("vanilla:\n" + vanilla + "patched:\n" + patched);
    process.exit(1);
  }
}

main();
