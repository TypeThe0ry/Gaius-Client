#!/usr/bin/env node
// The WebAssembly GC Math intrinsics of TeaVMCoreBrowserPatcher (GaiusWasmMath):
//   - with GAIUS_TEAVM_TARGET=wasmgc the patched WasmGCIntrinsics.apply starts with
//     GaiusWasmMath.register(inline registry), and the helper is copied into the patched jar;
//   - with the JavaScript target neither is written (the JavaScript TeaVM jar stays as it was);
//   - GaiusWasmMath.register, run against the real TeaVM 0.15 classes (a DefaultIntrinsicRegistry),
//     registers exactly floor, ceil, sqrt, rint, abs, min and max of double (abs, min, max also
//     of float) for Math and StrictMath, and nothing else.
// That the instructions compute the JDK's results bit for bit is checked by the class library
// suite (port/tests/classlib, sections math and strictmath, variants wasm and wasm-strict); its
// WebAssembly module then imports no teavmMath floor, ceil or sqrt.
//
//   node port/scripts/teavm-wasm-math-smoke.mjs
// Needs a JDK (JAVA_HOME or PATH) and the TeaVM 0.15.0 and ASM 9.8 jars in the local Maven
// repository (MAVEN_REPOSITORY or ~/.m2/repository). Nothing is downloaded.
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const posixDrive = path => process.platform === "win32" && /^\/[a-zA-Z]\//.test(path)
  ? `${path[1]}:${path.slice(2)}` : path;
const javaHome = process.env.GAIUS_JAVA_HOME || (process.env.JAVA_HOME ? posixDrive(process.env.JAVA_HOME) : null);
const tool = name => javaHome ? join(javaHome, "bin", name) : name;
const repository = process.env.MAVEN_REPOSITORY || join(homedir(), ".m2", "repository");
const asm = ["asm", "asm-tree"].map(name => join(repository, "org/ow2/asm", name, "9.8", `${name}-9.8.jar`));
const teavmCore = join(repository, "org/teavm/teavm-core/0.15.0/teavm-core-0.15.0.jar");
for (const jar of [...asm, teavmCore]) {
  assert.ok(existsSync(jar), `missing ${jar}`);
}
const toolSources = join(root, "port/tools/src/main/java");
const INTRINSICS = "org/teavm/backend/wasm/intrinsics/WasmGCIntrinsics.class";
const HELPER = "dev/gaius/tools/runtime/GaiusWasmMath.class";

function sh(command, args, env = {}) {
  return execFileSync(command, args, {encoding: "utf8", env: {...process.env, ...env}, maxBuffer: 64 << 20});
}

const work = mkdtempSync(join(tmpdir(), "gaius-wasm-math-"));
try {
  const classes = join(work, "tool-classes");
  mkdirSync(classes, {recursive: true});
  const patchers = readdirSync(join(toolSources, "dev/gaius/tools"))
    .filter(name => name.endsWith(".java")).map(name => join(toolSources, "dev/gaius/tools", name));
  sh(tool("javac"), ["--release", "21", "-proc:none", "-nowarn", "-classpath", [...asm, teavmCore].join(delimiter),
    "-sourcepath", toolSources, "-d", classes, ...patchers]);
  assert.ok(existsSync(join(classes, HELPER)), "GaiusWasmMath is compiled with the patchers");

  const patch = target => {
    const output = join(work, `patches-${target}`);
    sh(tool("java"), ["-classpath", [classes, ...asm, teavmCore].join(delimiter),
      "dev.gaius.tools.TeaVMCoreBrowserPatcher", teavmCore,
      join(output, "org/teavm/backend/javascript/intrinsics/reflection/ClassInfoGenerator.class")],
    {GAIUS_TEAVM_TARGET: target});
    return output;
  };
  const wasm = patch("wasmgc");
  const js = patch("js");
  assert.ok(existsSync(join(wasm, INTRINSICS)), "wasmgc: WasmGCIntrinsics is patched");
  assert.ok(existsSync(join(wasm, HELPER)), "wasmgc: GaiusWasmMath is copied into the jar");
  assert.ok(!existsSync(join(js, INTRINSICS)), "js: WasmGCIntrinsics stays the stock class");
  assert.ok(!existsSync(join(js, HELPER)), "js: GaiusWasmMath is not copied");

  // The registration is the first thing apply does, with the inline registry (local 2).
  const disassembly = sh(tool("javap"), ["-c", "-p", join(wasm, INTRINSICS)]);
  const apply = disassembly.slice(disassembly.indexOf("public static void apply("));
  const body = apply.split("\n").filter(line => /^\s+\d+: /.test(line)).slice(0, 2).map(line => line.trim());
  assert.match(body[0] || "", /^0: aload_2$/, `apply starts with the inline registry: ${body}`);
  assert.match(body[1] || "", /invokestatic .*dev\/gaius\/tools\/runtime\/GaiusWasmMath\.register:\(Ljava\/lang\/Object;\)V/,
    `apply then calls GaiusWasmMath.register: ${body}`);
  assert.equal((disassembly.match(/GaiusWasmMath\.register/g) || []).length, 1, "registered once");

  // GaiusWasmMath against the real TeaVM classes: what it registers, and that every intrinsic is
  // a WasmGCInlineIntrinsic.
  const probeSource = join(work, "probe", "MathProbe.java");
  mkdirSync(dirname(probeSource), {recursive: true});
  writeFileSync(probeSource, `
import dev.gaius.tools.runtime.GaiusWasmMath;
import org.teavm.backend.wasm.intrinsics.WasmGCInlineIntrinsic;
import org.teavm.model.MethodReference;
import org.teavm.vm.intrinsic.DefaultIntrinsicRegistry;

public final class MathProbe {
  public static void main(String[] args) {
    DefaultIntrinsicRegistry<WasmGCInlineIntrinsic> registry = new DefaultIntrinsicRegistry<>(null);
    GaiusWasmMath.register(registry);
    String[] expected = GaiusWasmMath.methods();
    if (expected.length != 20) throw new AssertionError("methods: " + expected.length);
    for (String key : expected) {
      WasmGCInlineIntrinsic intrinsic = registry.getIntrinsic(MethodReference.parse(key));
      if (intrinsic == null) throw new AssertionError("not registered: " + key);
      String method = key.substring(key.lastIndexOf('.', key.indexOf('(')) + 1);
      if (!intrinsic.toString().equals("GaiusWasmMath." + method)) {
        throw new AssertionError(key + " -> " + intrinsic);
      }
    }
    for (String key : new String[] {"java.lang.Math.abs(I)I", "java.lang.Math.min(II)I",
        "java.lang.Math.max(JJ)J", "java.lang.Math.sin(D)D", "java.lang.Math.pow(DD)D",
        "java.lang.Math.round(D)J", "java.lang.Math.signum(D)D", "java.lang.StrictMath.exp(D)D"}) {
      if (registry.getIntrinsic(MethodReference.parse(key)) != null) {
        throw new AssertionError("must stay a call: " + key);
      }
    }
    System.out.println("WASM_MATH_REGISTRY_OK " + expected.length);
  }
}
`, "utf8");
  const probeClasses = join(work, "probe-classes");
  mkdirSync(probeClasses, {recursive: true});
  sh(tool("javac"), ["--release", "21", "-proc:none", "-nowarn", "-classpath", [classes, teavmCore].join(delimiter),
    "-d", probeClasses, probeSource]);
  const output = sh(tool("java"), ["-classpath", [probeClasses, classes, teavmCore].join(delimiter), "MathProbe"]);
  assert.match(output, /WASM_MATH_REGISTRY_OK 20/);
  console.log(JSON.stringify({
    ok: true,
    wasmPatched: [INTRINSICS, HELPER],
    jsUnchanged: true,
    registered: 20,
  }));
} finally {
  rmSync(work, {recursive: true, force: true});
}
