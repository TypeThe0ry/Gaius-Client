#!/usr/bin/env node
// JVM check of the host login compression patch of the WasmGC integrated server
// (MinecraftClientPatcher.patchSingleplayerHostCompression -> dev.gaius.browser.BrowserHostConnection).
//
//   node port/scripts/host-login-compression-check.mjs [--profile 26.3] [--patched <patched client jar>]
//        [--out <dir>]
//
// Without --patched the real patch chain runs first (patchkit check, wasmgc target, kept scratch
// directory) and its patched client.jar is used. Checks:
//  1. structure: javap of the patched ServerLoginPacketListenerImpl shows that
//     verifyLoginAndFinishConnectionSetup gates ClientboundLoginCompressionPacket and
//     setupCompression on BrowserHostConnection.skipsLoginCompression instead of
//     Connection.isMemoryConnection and is otherwise vanilla's body (same instructions with the
//     one call swapped; the callback still calls setupCompression with the server threshold);
//  2. verification and behaviour: host-login-compression/HostLoginCompressionCheck.java loads the
//     patched class under -Xverify:all and runs the real Connection of the profile over a channel
//     with the remote address under test (host tunnel skips, LAN guests and sockets compress).
// Exit status: 0 pass, 1 failure, 2 environment.

import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {sourceFile} from "../tools/sourcesets/sourcesets.mjs";
import {RENDERPEARL_PROFILE} from "./verification-profiles.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const nativePath = (value) => process.platform === "win32" && /^\/[A-Za-z]\//.test(value)
  ? `${value[1].toUpperCase()}:${value.slice(2)}` : value;

const options = {profile: RENDERPEARL_PROFILE.id, patched: null, out: null};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  const value = () => {
    if (i + 1 >= argv.length) usage(`${argv[i]} needs a value`);
    return argv[++i];
  };
  switch (argv[i]) {
    case "--profile": options.profile = value(); break;
    case "--patched": options.patched = resolve(nativePath(value())); break;
    case "--out": options.out = resolve(nativePath(value())); break;
    default: usage(`unknown argument ${argv[i]}`);
  }
}

function usage(message) {
  console.error(`host-login-compression-check: ${message}`);
  process.exit(2);
}

const LOGIN = "net/minecraft/server/network/ServerLoginPacketListenerImpl";
const HELPER = "dev/gaius/browser/BrowserHostConnection.java";
const workRoot = join(repositoryRoot, "port", "work", options.profile);
const vanillaJar = join(workRoot, "client-named.jar");
if (!existsSync(vanillaJar)) usage(`missing ${vanillaJar}`);
const libraries = readFileSync(join(workRoot, "classpath.txt"), "utf8")
  .trim().split(":").filter(Boolean).map(nativePath);
const vanillaPath = [vanillaJar, ...libraries];

const scratch = mkdtempSync(join(options.out ?? tmpdir(), "gaius-host-login-"));
const run = (tool, args, label, extra = {}) => {
  const result = spawnSync(tool, args, {encoding: "utf8", maxBuffer: 256 * 1024 * 1024, ...extra});
  if (result.status !== 0) {
    console.error(`${label} failed (${result.status})\n${result.stdout}\n${result.stderr}`);
    process.exit(2);
  }
  return result.stdout;
};
let failures = 0;
const expect = (label, ok) => {
  console.log(`${ok ? "PASS" : "FAIL"} ${label}`);
  if (!ok) failures++;
};

/** The javap -c lines of one method, from its header up to the next method header. */
function methodBody(listing, name) {
  const lines = listing.split(/\r?\n/);
  const header = (line) => /^  [a-z].*\);$/.test(line);
  const start = lines.findIndex((line) => header(line) && line.includes(`${name}(`));
  if (start < 0) return null;
  let end = start + 1;
  while (end < lines.length && !header(lines[end])) end++;
  return lines.slice(start, end).join("\n");
}

try {
  let patchedJar = options.patched;
  if (!patchedJar) {
    const kit = join(scratch, "patchkit");
    mkdirSync(kit, {recursive: true});
    const output = run(process.execPath, [join(repositoryRoot, "port/tools/patchkit/patchkit.mjs"), "check",
      "--profile", options.profile, "--target", "wasmgc", "--keep", "--scratch", kit,
      "--out", join(kit, "manifests")], "patchkit check");
    console.log(output.split(/\r?\n/).find((entry) => entry.includes("patchSingleplayerHostCompression"))
      ?? "patchSingleplayerHostCompression: no PATCHKIT line");
    const kept = output.match(/PATCHKIT_SCRATCH (.+)/);
    if (!kept) usage("patchkit did not keep its scratch directory");
    patchedJar = join(kept[1].trim(), `${options.profile}-wasmgc`, "client.jar");
  }
  const overlay = join(scratch, "patched-classes");
  mkdirSync(overlay, {recursive: true});
  run("jar", ["xf", patchedJar, `${LOGIN}.class`], "extract patched login listener", {cwd: overlay});
  if (!existsSync(join(overlay, `${LOGIN}.class`))) usage(`${patchedJar} has no ${LOGIN}.class`);

  // 1. structure
  const listing = (path) => run("javap", ["-p", "-c", "-cp", path, LOGIN.replaceAll("/", ".")], "javap");
  const patched = listing(overlay);
  const vanilla = listing(vanillaJar);
  const patchedVerify = methodBody(patched, "verifyLoginAndFinishConnectionSetup");
  const vanillaVerify = methodBody(vanilla, "verifyLoginAndFinishConnectionSetup");
  const count = (text, pattern) => (text?.match(pattern) ?? []).length;
  expect("vanilla verifyLoginAndFinishConnectionSetup gates on Connection.isMemoryConnection",
    count(vanillaVerify, /Connection\.isMemoryConnection:\(\)Z/g) === 1);
  expect("patched verifyLoginAndFinishConnectionSetup gates on skipsLoginCompression only",
    count(patchedVerify, /BrowserHostConnection\.skipsLoginCompression:\(Lnet\/minecraft\/network\/Connection;\)Z/g) === 1
    && count(patchedVerify, /isMemoryConnection/g) === 0);
  const shape = (body) => body
    .replace(/invokevirtual\s+#\d+\s+\/\/ Method net\/minecraft\/network\/Connection\.isMemoryConnection:\(\)Z/g, "GATE")
    .replace(/invokestatic\s+#\d+\s+\/\/ Method dev\/gaius\/browser\/BrowserHostConnection\.skipsLoginCompression:\(Lnet\/minecraft\/network\/Connection;\)Z/g, "GATE")
    .replace(/#\d+/g, "#")
    .replace(/\s+/g, " ");
  expect("the rest of verifyLoginAndFinishConnectionSetup is vanilla's (threshold test, packet, callback)",
    vanillaVerify !== null && patchedVerify !== null && shape(vanillaVerify) === shape(patchedVerify)
    && count(patchedVerify, /MinecraftServer\.getCompressionThreshold:\(\)I/g) === 2
    && count(patchedVerify, /ClientboundLoginCompressionPacket/g) >= 1);
  const callback = methodBody(patched, "lambda$verifyLoginAndFinishConnectionSetup$0");
  expect("the compression callback still calls setupCompression with the server threshold",
    count(callback, /Connection\.setupCompression:\(IZ\)V/g) === 1
    && count(callback, /MinecraftServer\.getCompressionThreshold:\(\)I/g) === 1);

  // 2. helper, verification and behaviour
  const helpers = join(scratch, "helper-classes");
  mkdirSync(helpers, {recursive: true});
  const helperSource = sourceFile(options.profile, "src", HELPER, {target: "wasmgc"});
  if (helperSource == null) usage(`profile ${options.profile} has no ${HELPER}`);
  const helperFile = join(scratch, "helper-sources", HELPER);
  mkdirSync(dirname(helperFile), {recursive: true});
  writeFileSync(helperFile, helperSource.text);
  run("javac", ["-J-Duser.language=en", "--release", "25", "-proc:none", "-nowarn",
    "-cp", vanillaPath.join(delimiter), "-d", helpers, helperFile], "compile helper");
  const harness = join(scratch, "harness-classes");
  mkdirSync(harness, {recursive: true});
  run("javac", ["-J-Duser.language=en", "--release", "25", "-proc:none", "-nowarn",
    "-cp", [helpers, ...vanillaPath].join(delimiter), "-d", harness,
    join(repositoryRoot, "port/scripts/host-login-compression/HostLoginCompressionCheck.java")], "compile harness");

  // The library class path exceeds the Windows command line: use an @argfile.
  const quote = (value) => `"${value.replace(/\\/g, "/")}"`;
  const argfile = join(scratch, "java-args.txt");
  writeFileSync(argfile, ["-Xverify:all", "-Duser.language=en",
    "-cp", quote([harness, overlay, helpers, ...vanillaPath].join(delimiter)),
    "dev.gaius.parity.HostLoginCompressionCheck"].join("\n"));
  const result = spawnSync("java", [`@${argfile}`], {encoding: "utf8", maxBuffer: 64 * 1024 * 1024});
  if (result.error) usage(`java: ${result.error.message}`);
  process.stdout.write(result.stdout ?? "");
  process.stderr.write(result.stderr ?? "");
  expect("patched login listener verifies and the gate answers for every address",
    result.status === 0 && /HOST_LOGIN_COMPRESSION_OK checks=13/.test(result.stdout ?? "")
    && (result.stdout ?? "").includes(overlay.replace(/\\/g, "/")) );
  console.log(failures === 0 ? `HOST_LOGIN_COMPRESSION_CHECK ${options.profile} PASS`
    : `HOST_LOGIN_COMPRESSION_CHECK ${options.profile} FAIL failures=${failures}`);
  process.exitCode = failures === 0 ? 0 : 1;
} finally {
  if (!options.out) rmSync(scratch, {recursive: true, force: true});
}
