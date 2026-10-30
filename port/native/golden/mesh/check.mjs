#!/usr/bin/env node
import assert from "node:assert/strict";
import {copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {spawnSync} from "node:child_process";

const root = fileURLToPath(new URL("../../../..", import.meta.url));
const args = process.argv.slice(2);
const option = (name) => args.includes(name) ? args[args.indexOf(name) + 1] : null;
const output = resolve(option("--out") || process.env.GAIUS_MESH_GOLDEN_OUT || join(root, "port/native/target/mesh-golden"));
mkdirSync(output, {recursive: true});
function run(command, parameters, environment = {}) {
  const result = spawnSync(command, parameters, {cwd: root, windowsHide: true, encoding: "utf8",
    env: {...process.env, ...environment}, maxBuffer: 8 * 1024 * 1024});
  writeFileSync(join(output, "last-command.log"), `${result.stdout || ""}\n${result.stderr || ""}`);
  if (result.error || result.status !== 0) throw new Error(`${command}: ${result.error || result.stderr || result.stdout}`);
  if (result.stdout) console.log(result.stdout.trim());
}
run(process.execPath, [join(root, "port/scripts/mesh-golden-compare.test.mjs")]);
run(process.execPath, ["--test", join(root, "port/scripts/mesh-jvm-sources.test.mjs")]);
run(process.execPath, [join(root, "port/scripts/m2-mesh-gate.test.mjs")]);
let dist = option("--dist") || process.env.GAIUS_MESH_DIST;
const artifactKind = dist ? "shipped" : "built-candidate";
if (!dist) {
  dist = join(output, "kernels");
  mkdirSync(dist, {recursive: true});
  for (const variant of ["simd", "baseline"]) {
    const target = join(output, `wasm-${variant}`);
    run("cargo", ["build", "--manifest-path", join(root, "port/native/Cargo.toml"), "--release", "--offline", "--locked",
      "-j", "2", "--target", "wasm32-unknown-unknown", "-p", "gaius-mesher-wasm"],
    {CARGO_TARGET_DIR: target, CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUSTFLAGS: `-C target-feature=${variant === "simd" ? "+" : "-"}simd128`});
    copyFileSync(join(target, "wasm32-unknown-unknown/release/gaius_mesher_wasm.wasm"), join(dist, `mesher.${variant}.wasm`));
  }
  run(process.execPath, [join(root, "port/native/js/kernel-manifest.mjs"), dist, "mesher=gaius-mesher-wasm"]);
}
// Default: every profile with the mesher enabled. `--profile <id>` (repeatable) selects profiles
// instead; a selected profile whose mesher is quarantined runs with its gate recorded as such.
const selected = args.flatMap((value, index) => value === "--profile" ? [args[index + 1]] : []);
const allProfiles = readdirSync(join(root, "port/versions")).filter((name) => name.endsWith(".json"))
  .map((name) => JSON.parse(readFileSync(join(root, "port/versions", name), "utf8")));
for (const id of selected) assert(allProfiles.some((profile) => profile.id === id), `unknown profile ${id}`);
const quarantined = (profile) => !profile.kernelMirror?.enabled?.mesher && !!profile.kernelMirror?.quarantined?.mesher;
const profiles = allProfiles.filter((profile) => selected.length ? selected.includes(profile.id) : profile.kernelMirror?.enabled?.mesher)
  .sort((left, right) => left.id.localeCompare(right.id));
for (const profile of profiles) {
  assert(profile.kernelMirror?.enabled?.mesher || quarantined(profile), `${profile.id}: mesher is neither enabled nor quarantined`);
}
assert(profiles.length > 0, "no enabled mesher profiles");
const reports = [];
for (const profile of profiles) {
  const out = join(output, profile.id);
  const fixturePath = join(out, "fixtures/fixtures.json");
  const common = [join(root, "port/scripts/mesh-jvm-wasm-golden.mjs"), "--profile", profile.id,
    "--out", out, "--target", join(output, "target"), "--dist", dist, "--artifact-kind", artifactKind,
    ...(quarantined(profile) ? ["--quarantined"] : [])];
  const generate = !args.includes("--no-generate");
  assert(generate || existsSync(fixturePath), `fixtures missing for ${profile.id}`);
  run(process.execPath, [...common, ...(generate ? ["--generate"] : [])]);
  if (args.includes("--verify")) {
    const fresh = join(output, "reproduce", profile.id);
    run(process.execPath, [join(root, "port/scripts/mesh-jvm-wasm-golden.mjs"), "--profile", profile.id,
      "--out", fresh, "--generate", ...(quarantined(profile) ? ["--quarantined"] : [])]);
    const expected = JSON.parse(readFileSync(fixturePath));
    const actual = JSON.parse(readFileSync(join(fresh, "fixtures/fixtures.json")));
    assert.deepEqual(actual, expected, `${profile.id}: fresh JVM fixtures differ`);
    console.log(`${profile.id}: fresh JVM fixture hashes reproduce exactly`);
  }
  reports.push({profile: profile.id, ...JSON.parse(readFileSync(join(out, "report.json")))});
}
writeFileSync(join(output, "reports.json"), JSON.stringify({schema: 1, artifactKind, reports}, null, 2) + "\n");
console.log(`MESH_JVM_GOLDEN PASS profiles=${reports.map((report) => report.gate?.enabled === false ? `${report.profile}(quarantined)` : report.profile).join(",")} sections=${reports.reduce((sum, report) => sum + report.sectionCount, 0)} artifacts=${artifactKind}`);
