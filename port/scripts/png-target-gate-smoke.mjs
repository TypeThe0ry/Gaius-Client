import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {loadProfiles} from "../tools/kernelmirror/kernelmirror.mjs";

const root = fileURLToPath(new URL("../..", import.meta.url));
const argumentsList = process.argv.slice(2);
const option = (name) => argumentsList[argumentsList.indexOf(name) + 1];
assert(argumentsList.includes("--snapshots") && argumentsList.includes("--out"));
const snapshots = resolve(option("--snapshots"));
const out = resolve(option("--out"));
mkdirSync(out, {recursive: true});
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const javap = javaHome ? join(javaHome, "bin", process.platform === "win32" ? "javap.exe" : "javap") : "javap";

function methodBody(text, declaration) {
  const lines = text.split("\n");
  const start = lines.findIndex((line) => line.includes(declaration));
  assert(start >= 0, declaration);
  const following = lines.slice(start + 1);
  const end = following.findIndex((line) => line.startsWith("  ") && !line.startsWith("    ") && line.trim());
  return following.slice(0, end < 0 ? following.length : end).join("\n");
}

const results = [];
for (const profile of loadProfiles(root)) {
  const snapshot = JSON.parse(readFileSync(join(snapshots, profile.id + ".json"), "utf8"));
  assert.equal(readFileSync(join(snapshot.overlayDir, "TEAVM_TARGET"), "utf8").trim(), "js");
  const archives = Object.entries(snapshot.files).filter(([, file]) => file.entries?.["org/lwjgl/stb/STBImage.class"]);
  assert.equal(archives.length, 1);
  const archive = join(snapshot.overlayDir, archives[0][0]);
  const inspect = (owner) => execFileSync(javap, ["-J-Xmx3g", "-p", "-c", "-constants", "-classpath", archive, owner],
      {encoding: "utf8", maxBuffer: 4 * 1024 * 1024});
  const stb = inspect("org.lwjgl.stb.STBImage");
  const stub = inspect("org.lwjgl.stb.BrowserPng");
  writeFileSync(join(out, profile.id + "-STBImage.javap"), stb);
  writeFileSync(join(out, profile.id + "-BrowserPng.javap"), stub);
  assert(!stb.includes("org/lwjgl/stb/BrowserPng") && !stb.includes("org/lwjgl/stb/PngPixels"));
  const body = methodBody(stb, "stbi_load_from_memory(java.nio.ByteBuffer, java.nio.IntBuffer");
  const firstCall = body.split("\n").find((line) => line.includes("invoke"));
  assert(firstCall?.includes("Method decodePng:"), "JS must enter the original decoder without a PNG gate call");
  assert(stub.includes("static final boolean AVAILABLE = false;"));
  const decode = methodBody(stub, "tryDecode(");
  assert(decode.includes("aconst_null") && decode.includes("areturn") && !decode.includes("invoke"));
  results.push({profile: profile.id, target: "js", acceleratedBranchPresent: false,
      availabilityCalls: 0, inputCopiesBeforeOriginalDecode: 0, firstCall: firstCall.trim()});
}
const wasm = readFileSync(join(root, "port/overrides/libraries/lwjgl-stb/src/target-wasmgc/java/org/lwjgl/stb/BrowserPng.java"), "utf8");
const gate = wasm.indexOf("if (!available()");
const copying = wasm.indexOf("byte[] encoded");
assert(gate >= 0 && copying > gate);
assert(wasm.includes("GaiusPngKernel.enabled && globalThis.GaiusPngKernel.ready"));
writeFileSync(join(out, "report.json"), JSON.stringify({results, wasmAvailabilityBeforeCopying: true}, null, 2) + "\n");
console.log(`PNG_TARGET_GATE PASS profiles=${results.length} JS gateCalls=0 acceleratedBranches=0 predecodeCopies=0`);
