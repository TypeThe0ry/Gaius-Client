// Test of TeaVMClasslibPatcher.patchArrayDequeIteratorRemove: TeaVM's ArrayDeque iterator (behind
// java.util.ArrayDeque and Gaius' ConcurrentLinkedQueue / ConcurrentLinkedDeque / LinkedBlockingQueue
// wrappers in the WebAssembly build) resumed one slot too far after Iterator.remove() of an element of
// the back half, so the scan skipped the next element and read null behind the tail (a null
// dereference trap of the server Worker, an NPE of the client).
//
// 1. the real patcher rewrites TArrayDeque$1 from the TeaVM class library jar and refuses its own output;
// 2. port/scripts/fixtures/ArrayDequeIteratorRemoveCheck.java scans deques (every position, head
//    offsets that wrap the array, random removal patterns in both directions, removeIf, the loop shapes of
//    ParticleGroup.tickParticles and CrossFrameResourcePool.endFrame) over TeaVM's class library as it is:
//    that must show defects (the regression is demonstrated), and over the patched class: none.
//
//   node port/scripts/classlib-arraydeque-iterator-remove.test.mjs
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const exe = name => home ? join(home, "bin", process.platform === "win32" ? `${name}.exe` : name) : name;
const m2 = process.env.GAIUS_MAVEN_REPOSITORY || join(process.env.USERPROFILE || process.env.HOME, ".m2", "repository");
const teavm = "0.15.0";
const classlib = join(m2, "org/teavm/teavm-classlib", teavm, `teavm-classlib-${teavm}.jar`);
const teavmJars = ["teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-core", "teavm-platform"]
  .map(name => join(m2, "org/teavm", name, teavm, `${name}-${teavm}.jar`));
const asm = ["asm", "asm-tree"].map(name => join(m2, "org/ow2/asm", name, "9.8", `${name}-9.8.jar`));
for (const file of [classlib, ...teavmJars, ...asm]) assert.ok(existsSync(file), `missing ${file}`);

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 << 20, ...options});
  assert.equal(result.status, 0, `${command} failed\n${(result.stdout || "").slice(-6000)}${(result.stderr || "").slice(-6000)}`);
  return result.stdout;
}

const out = mkdtempSync(join(tmpdir(), "gaius-arraydeque-"));
try {
  const tools = join(out, "tools");
  const fixture = join(out, "fixture");
  const patched = join(out, "patched");
  for (const dir of [tools, fixture, patched]) mkdirSync(dir);
  const libraries = [classlib, ...teavmJars];
  run(exe("javac"), ["-nowarn", "-proc:none", "--release", "21", "-cp", asm.join(delimiter),
    "-sourcepath", join(root, "port/tools/src/main/java"), "-d", tools,
    join(root, "port/tools/src/main/java/dev/gaius/tools/TeaVMClasslibPatcher.java")]);
  run(exe("javac"), ["-nowarn", "-proc:none", "-cp", libraries.join(delimiter), "-d", fixture,
    join(root, "port/scripts/fixtures/ArrayDequeIteratorRemoveCheck.java")]);

  const patch = run(exe("java"), ["-cp", [fixture, tools, ...asm, ...libraries].join(delimiter),
    "ArrayDequeIteratorRemoveCheck", "patch", classlib, patched]);
  assert.match(patch, /ARRAY_DEQUE_PATCH_OK refusesPatchedBody=true/);

  const before = run(exe("java"), ["-cp", [fixture, ...libraries].join(delimiter), "ArrayDequeIteratorRemoveCheck", "check"]);
  const after = run(exe("java"), ["-cp", [fixture, patched, ...libraries].join(delimiter), "ArrayDequeIteratorRemoveCheck", "check"]);
  for (const line of before.trim().split("\n")) console.log(`unpatched ${line}`);
  for (const line of after.trim().split("\n")) console.log(`patched   ${line}`);
  const defects = text => Number(/ARRAY_DEQUE_DEFECTS (\d+)/.exec(text)?.[1]);
  assert.ok(defects(before) > 0, "TeaVM's ArrayDeque iterator showed no defect: the regression is not demonstrated");
  assert.equal(defects(after), 0, "the patched ArrayDeque iterator still has defects");
  console.log("CLASSLIB_ARRAYDEQUE_ITERATOR_REMOVE_TEST PASS");
} finally {
  rmSync(out, {recursive: true, force: true});
}
