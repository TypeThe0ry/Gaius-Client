#!/usr/bin/env node
// Differential test of the feature-placement lookup patches (MinecraftClientPatcher.
// patchBulkSectionAccessLookup and patchHolderReferenceTagMemo) against the vanilla classes:
// the patched BulkSectionAccess and Holder.Reference of a patched client jar run the same random
// operation sequences as renamed copies of the vanilla classes and must answer identically, throw
// identically and call the level identically (feature-lookup-parity/FeatureLookupParity.java).
//
//   node port/scripts/feature-lookup-parity.mjs --patched <patched client.jar> [--profile 26.3]
//        [--iterations 200000] [--seed N] [--mutate bulk-no-y|bulk-no-key|holder-hit-false|holder-no-clear]
//
// --patched is the client.jar a `patchkit.mjs check --keep` run leaves in its scratch directory.
// With --mutate the edited patched class must make the test FAIL (exit 0 means it did, 1 that the
// test is blind to that defect); without it exit 0 means parity. Needs GAIUS_JAVA_HOME, ASM 9.8
// in ~/.m2 and port/work/<profile> (the profile fetch).
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const harness = join(root, "port/scripts/feature-lookup-parity");

function usage(message) {
  console.error(`feature-lookup-parity: ${message}`);
  process.exit(2);
}

const options = {profile: "26.3", iterations: "200000", seed: "20261113"};
const argv = process.argv.slice(2);
for (let index = 0; index < argv.length; index++) {
  const value = () => argv[++index] ?? usage(`${argv[index - 1]} needs a value`);
  switch (argv[index]) {
    case "--patched": options.patched = resolve(value()); break;
    case "--profile": options.profile = value(); break;
    case "--iterations": options.iterations = value(); break;
    case "--seed": options.seed = value(); break;
    case "--mutate": options.mutate = value(); break;
    default: usage(`unknown argument ${argv[index]}`);
  }
}
if (!options.patched || !existsSync(options.patched)) usage("--patched <patched client.jar> is required");
const java = (name) => join(process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME || "", "bin", name);
const work = join(root, "port/work", options.profile);
const profile = JSON.parse(readFileSync(join(root, "port/versions", `${options.profile}.json`), "utf8"));
const vanillaJar = join(work, profile.clientDistribution === "obfuscated-with-mappings" ? "client-named.jar" : "client-original.jar");
if (!existsSync(vanillaJar)) usage(`missing ${vanillaJar} (run the profile fetch first)`);
const libraries = readFileSync(join(work, "classpath.txt"), "utf8").trim().split(delimiter)
  .filter((entry) => entry && !/client-(named|original)\.jar$/.test(entry));
const maven = join(process.env.GAIUS_MAVEN_REPOSITORY || join(homedir(), ".m2/repository"));
const asm = ["asm", "asm-tree", "asm-commons"].map((artifact) => join(maven, "org/ow2/asm", artifact, "9.8", `${artifact}-9.8.jar`));
for (const jar of asm) if (!existsSync(jar)) usage(`${jar} is missing (ASM 9.8)`);

const temp = mkdtempSync(join(tmpdir(), "gaius-feature-lookup-"));
try {
  const classes = join(temp, "classes");
  mkdirSync(classes, {recursive: true});
  const compiled = spawnSync(java("javac"), ["-J-Duser.language=en", "--release", "25", "-proc:none", "-XDignore.symbol.file",
    "-cp", asm.join(delimiter), "-d", classes, join(harness, "FeatureLookupParity.java")], {encoding: "utf8"});
  if (compiled.status !== 0) {
    console.error(`${compiled.stdout}${compiled.stderr}`.split("\n").filter((line) => !/warning|Note:/.test(line)).join("\n"));
    process.exit(2);
  }
  const args = ["-Xmx2g", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-cp", [classes, ...asm].join(delimiter),
    "dev.gaius.parity.FeatureLookupParity", "--vanilla-jar", vanillaJar, "--patched-jar", options.patched,
    "--work", join(temp, "work"), "--libs", libraries.join(delimiter), "--iterations", options.iterations,
    "--seed", options.seed, ...(options.mutate ? ["--mutate", options.mutate] : [])];
  const result = spawnSync(java("java"), args, {encoding: "utf8", maxBuffer: 64 * 1024 * 1024});
  process.stdout.write(result.stdout || "");
  if (result.status !== 0 && !result.stdout?.includes("FEATURE_LOOKUP_PARITY")) process.stderr.write(result.stderr || "");
  if (options.mutate) {
    const failed = (result.stdout || "").includes("FEATURE_LOOKUP_PARITY FAIL");
    console.log(`FEATURE_LOOKUP_PARITY_MUTATION ${options.mutate} ${failed ? "DETECTED" : "MISSED"}`);
    process.exit(failed ? 0 : 1);
  }
  process.exit(result.status === 0 ? 0 : 1);
} finally {
  rmSync(temp, {recursive: true, force: true});
}
