#!/usr/bin/env node
// Class library conformance suite: builds dev.gaius.classlib.Main for the JVM, the TeaVM
// JavaScript backend and the TeaVM WebAssembly GC backend, runs every section on each, and
// compares the outputs line by line against the JVM. Every difference must be registered in
// port/tests/classlib/differences.txt with a reason. See port/tests/classlib/README.md.
//
//   node port/tests/classlib/runner/run.mjs --out DIR --js-overlays DIR --wasm-overlays DIR
//        [--variants jvm,js,wasm[,wasm-strict]] [--sections a,b,...] [--skip-build]
//        [--jobs N] [--timeout-ms N] [--no-register] [--main CLASS]
//
// --js-overlays / --wasm-overlays: overlay directories of build-overlays.sh for the js and the
// wasmgc target (they hold teavm-core-0.15.0-gaius.jar and teavm-classlib-0.15.0-gaius.jar, the
// TeaVM the product compiles with). "stock" uses the unpatched TeaVM 0.15.0 jars from the local
// Maven repository instead (a coroutine build for wasm, no gaius.teavm.* options): with it the
// TeaVM bug repros show the bugs the overlay patches fix. --no-register lists the differences
// without judging them (for stock runs).
// Every TeaVM compile runs with -Xmx3g, one at a time. Nothing is downloaded.
import {spawn, spawnSync} from "node:child_process";
import {cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync} from "node:fs";
import {homedir} from "node:os";
import {delimiter, dirname, join, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const suite = resolve(here, "..");
const root = resolve(suite, "../../..");

function option(name, fallback = null) {
  const index = process.argv.indexOf(`--${name}`);
  if (index < 0) return fallback;
  const value = process.argv[index + 1];
  if (value == null || value.startsWith("--")) throw new Error(`--${name} needs a value`);
  return value;
}
const flag = name => process.argv.includes(`--${name}`);

const out = resolve(option("out") || join(root, "port/tests/classlib/build"));
const variants = (option("variants") || "jvm,js,wasm").split(",").filter(Boolean);
const onlySections = option("sections") ? option("sections").split(",") : null;
const jobs = Math.max(1, Number(option("jobs", "4")));
const timeoutMs = Number(option("timeout-ms", "300000"));
const skipBuild = flag("skip-build");
const useRegister = !flag("no-register");
// --main dev.gaius.classlib.ReproMain: the TeaVM bug repros only (they compile on the stock jars).
const mainClass = option("main", "dev.gaius.classlib.Main");
const KNOWN = ["jvm", "js", "wasm", "wasm-strict"];
for (const variant of variants) {
  if (!KNOWN.includes(variant)) throw new Error(`unknown variant ${variant} (known: ${KNOWN.join(", ")})`);
}
if (!variants.includes("jvm")) throw new Error("the jvm variant is the reference and must run");

// Git Bash exports JAVA_HOME as /c/...; node on Windows needs C:/...
const posixDrive = path => process.platform === "win32" && /^\/[a-zA-Z]\//.test(path) ? `${path[1]}:${path.slice(2)}` : path;
const javaHome = process.env.GAIUS_JAVA_HOME || (process.env.JAVA_HOME ? posixDrive(process.env.JAVA_HOME) : null);
const javaBin = name => javaHome ? join(javaHome, "bin", name) : name;
const mavenRepo = process.env.MAVEN_REPOSITORY || join(homedir(), ".m2", "repository");
const TEAVM_VERSION = "0.15.0";
const teavmJar = artifact => join(mavenRepo, "org/teavm", artifact, TEAVM_VERSION, `${artifact}-${TEAVM_VERSION}.jar`);
const DEPENDENCIES = [
  ...["teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-jso-impl", "teavm-platform",
    "teavm-metaprogramming-api", "teavm-metaprogramming-impl", "teavm-relocated-libs-asm",
    "teavm-relocated-libs-asm-tree", "teavm-relocated-libs-asm-analysis", "teavm-relocated-libs-asm-commons",
    "teavm-relocated-libs-asm-util", "teavm-relocated-libs-hppc", "teavm-relocated-libs-rhino",
    "teavm-extension-apis", "teavm-extension-spi", "teavm-extension-spi-util", "teavm-tooling"].map(teavmJar),
  join(mavenRepo, "com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar"),
  join(mavenRepo, "joda-time/joda-time/2.12.2/joda-time-2.12.2.jar"),
];

// The TeaVM options of the product builds (port/scripts/generate-pom.sh, client role): the JS
// release build (ADVANCED, minified) and the WebAssembly GC build (SIMPLE, JSPI).
const JS_PROPERTIES = ["gaius.teavm.role=client", "gaius.teavm.syncClinits=true", "gaius.teavm.syncMonitors=true",
  "gaius.teavm.asyncBarrier=object,throwable,map,collection,iterator,stringbuilder",
  "gaius.teavm.pinRuntimeNames=true", "gaius.teavm.telemetry=false"];
const WASM_PROPERTIES = ["gaius.teavm.role=client", "gaius.teavm.syncClinits=false", "gaius.teavm.syncMonitors=false",
  "gaius.teavm.asyncBarrier=none", "gaius.teavm.pinRuntimeNames=true", "gaius.teavm.telemetry=false",
  "gaius.teavm.jspi=true"];

function overlayJars(directory, label) {
  if (!directory) throw new Error(`--${label} is required (an overlay directory of build-overlays.sh, or "stock")`);
  if (directory === "stock") {
    return {stock: true, core: teavmJar("teavm-core"), classlib: teavmJar("teavm-classlib")};
  }
  const core = join(resolve(directory), `teavm-core-${TEAVM_VERSION}-gaius.jar`);
  const classlib = join(resolve(directory), `teavm-classlib-${TEAVM_VERSION}-gaius.jar`);
  for (const jar of [core, classlib]) {
    if (!existsSync(jar)) throw new Error(`missing ${jar}`);
  }
  return {stock: false, core, classlib};
}

const needsJs = variants.includes("js");
const needsWasm = variants.some(v => v.startsWith("wasm"));
const jsJars = needsJs ? overlayJars(option("js-overlays"), "js-overlays") : null;
const wasmJars = needsWasm ? overlayJars(option("wasm-overlays"), "wasm-overlays") : null;

function teavmConfig(variant) {
  if (variant === "js") {
    return {target: "js", jars: jsJars, optimization: process.env.CLASSLIB_JS_OPTIMIZATION || "ADVANCED",
      strict: false, properties: jsJars.stock ? [] : [...JS_PROPERTIES, `gaius.teavm.cutReport=${join(out, "js", "sync-methods.txt")}`]};
  }
  return {target: "wasm", jars: wasmJars, optimization: process.env.CLASSLIB_WASM_OPTIMIZATION || "SIMPLE",
    strict: variant === "wasm-strict", jspi: !wasmJars.stock, properties: wasmJars.stock ? [] : WASM_PROPERTIES};
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 << 20, ...options});
  if (result.error) throw result.error;
  return result;
}
function must(command, args, options = {}, label = command) {
  const result = run(command, args, options);
  if (result.status !== 0) {
    throw new Error(`${label} failed (exit ${result.status}):\n${(result.stdout || "").slice(-6000)}\n${(result.stderr || "").slice(-6000)}`);
  }
  return result;
}
function javaSources(directory) {
  const files = [];
  for (const name of readdirSync(directory)) {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) files.push(...javaSources(path));
    else if (name.endsWith(".java")) files.push(path);
  }
  return files;
}
function log(text) {
  console.log(`[classlib] ${text}`);
}

function build() {
  rmSync(join(out, "classes"), {recursive: true, force: true});
  const jvmClasses = join(out, "classes", "jvm");
  const teavmClasses = join(out, "classes", "teavm");
  const driverClasses = join(out, "classes", "driver");
  for (const directory of [jvmClasses, teavmClasses, driverClasses]) mkdirSync(directory, {recursive: true});
  // The suite plus the BrowserStrictMath helper that StrictMath263 calls in the 26.3 worldgen.
  const sources = [...javaSources(join(suite, "src")), join(root, "port/src/main/java/dev/gaius/browser/BrowserStrictMath.java")];
  const argfile = join(out, "classes", "sources.txt");
  writeFileSync(argfile, sources.map(s => `"${s.replaceAll("\\", "/")}"`).join("\n") + "\n");
  // teavm-core: org.teavm.classlib.ResourceSupplier; teavm-jso: the @JSBody of BrowserStrictMath.
  const compileClasspath = [teavmJar("teavm-core"), teavmJar("teavm-classlib"), teavmJar("teavm-jso"), teavmJar("teavm-interop"),
    teavmJar("teavm-platform")].join(delimiter);
  must(javaBin("javac"), ["-J-Duser.language=en", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-nowarn", "-cp", compileClasspath,
    "-d", jvmClasses, `@${argfile}`], {}, "javac (suite)");
  cpSync(join(suite, "res"), jvmClasses, {recursive: true});
  cpSync(jvmClasses, teavmClasses, {recursive: true});
  // WasmBugs catches a class that is missing from TeaVM's class path, as UnsatisfiedLinkError is
  // missing in the client build; the JVM verifier needs the class, so only the TeaVM copy drops it.
  rmSync(join(teavmClasses, "dev/gaius/classlib/repro/WasmBugs$Missing.class"));
  const coreForDriver = (jsJars || wasmJars || {core: teavmJar("teavm-core")}).core;
  must(javaBin("javac"), ["-J-Duser.language=en", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", [coreForDriver, ...DEPENDENCIES].join(delimiter),
    "-d", driverClasses, join(suite, "driver", "ClasslibBuild.java")], {}, "javac (driver)");
  for (const variant of variants.filter(v => v !== "jvm")) {
    const config = teavmConfig(variant);
    const target = join(out, variant);
    rmSync(target, {recursive: true, force: true});
    mkdirSync(target, {recursive: true});
    const classpath = [driverClasses, config.jars.core, config.jars.classlib, ...DEPENDENCIES, teavmClasses].join(delimiter);
    const started = Date.now();
    log(`TeaVM ${variant}: ${config.target} ${config.optimization} strict=${config.strict} (${relative(root, config.jars.classlib)})`);
    const result = run(javaBin("java"), ["-Xmx3g", "-cp", classpath, "dev.gaius.classlib.driver.ClasslibBuild", config.target,
      target, mainClass, config.optimization, `strict=${config.strict}`, ...config.properties]);
    writeFileSync(join(target, "teavm.log"), (result.stdout || "") + (result.stderr || ""));
    const summary = (result.stdout || "").split("\n").filter(l => /^(ERROR|CLASSLIB_BUILD|jspi:)/.test(l));
    log(`TeaVM ${variant}: exit ${result.status} after ${Math.round((Date.now() - started) / 1000)} s\n  ${summary.join("\n  ")}`);
    if (result.status !== 0) throw new Error(`TeaVM ${variant} failed; see ${join(target, "teavm.log")}`);
    if (config.target === "js" && !config.jars.stock) {
      // The product's JS post-process (build-teavm.sh): Java semantics for (long) of NaN and the
      // infinities, and the module-level current-thread slot.
      const post = must("bash", [join(root, "port/scripts/run-python.sh"), join(root, "port/scripts/postprocess-teavm-js.py"),
        join(target, "classes.js")], {}, "postprocess-teavm-js.py");
      log(`postprocess-teavm-js.py: ${post.stdout.trim().split("\n").join("; ")}`);
    }
    if (config.target === "wasm") {
      // The product's runtime: TeaVM's wasm-gc-runtime.js patched by patch-wasm-gc-runtime.mjs.
      const extract = join(target, "extract");
      mkdirSync(extract, {recursive: true});
      must(javaBin("jar"), ["xf", config.jars.core, "org/teavm/backend/wasm/wasm-gc-runtime.js"], {cwd: extract}, "jar xf");
      const stockRuntime = join(extract, "org/teavm/backend/wasm/wasm-gc-runtime.js");
      if (config.jars.stock) {
        cpSync(stockRuntime, join(target, "gaius-wasm-gc-runtime.js"));
      } else {
        must("node", [join(root, "port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs"), stockRuntime,
          join(target, "gaius-wasm-gc-runtime.js")], {}, "patch-wasm-gc-runtime.mjs");
        cpSync(join(root, "port/web/runtime/wasmgc/gaius-jspi.js"), join(target, "gaius-jspi.js"));
      }
    }
  }
}

function listSections() {
  const result = must(javaBin("java"), ["-cp", join(out, "classes", "jvm"), mainClass, "--list"], {}, "Main --list");
  return result.stdout.split(/\r?\n/).filter(Boolean);
}

const RUNTIME_ENV = {...process.env, TZ: "UTC", LANG: "en_US.UTF-8", LC_ALL: "en_US.UTF-8"};
function commandFor(variant, section) {
  if (variant === "jvm") {
    return [javaBin("java"), ["-Duser.timezone=UTC", "-Duser.language=en", "-Duser.country=US", "-Dfile.encoding=UTF-8",
      "-Dstdout.encoding=UTF-8", "-Xss4m", "-cp", join(out, "classes", "jvm"), mainClass, section]];
  }
  if (variant === "js") return ["node", [join(here, "run-js.cjs"), join(out, "js", "classes.js"), section]];
  const config = teavmConfig(variant);
  const directory = join(out, variant);
  return ["node", ["--experimental-wasm-jspi", join(here, "run-wasm.cjs"), join(directory, "gaius-wasm-gc-runtime.js"),
    config.jspi ? join(directory, "gaius-jspi.js") : "-", join(directory, "classes.wasm"), section]];
}

function runSection(variant, section) {
  const [command, args] = commandFor(variant, section);
  return new Promise(resolveRun => {
    const started = Date.now();
    const child = spawn(command, args, {env: RUNTIME_ENV, windowsHide: true});
    let stdout = "", stderr = "", timedOut = false;
    child.stdout.on("data", d => { stdout += d; });
    child.stderr.on("data", d => { stderr += d; });
    const timer = setTimeout(() => { timedOut = true; child.kill("SIGKILL"); }, timeoutMs);
    child.on("close", (code, signal) => {
      clearTimeout(timer);
      resolveRun({variant, section, code, signal, timedOut, millis: Date.now() - started, stdout, stderr});
    });
    child.on("error", error => {
      clearTimeout(timer);
      resolveRun({variant, section, code: -1, signal: null, timedOut: false, millis: Date.now() - started, stdout,
        stderr: stderr + String(error)});
    });
  });
}

// One section's output as {key: value}; "<section>.$status" says how the section ended.
function parse(result) {
  const values = new Map();
  const notes = [];
  const prefix = result.section + ".";
  let raw = 0, ended = false, trap = null;
  for (const line of result.stdout.split(/\r?\n/)) {
    if (line === "") continue;
    if (line.startsWith("# ")) { notes.push(line.slice(2)); continue; }
    if (line === `END ${result.section}`) { ended = true; continue; }
    if (/^(TRAP|MODULE_INVALID|UNCAUGHT) /.test(line)) { trap = line; continue; }
    const eq = line.indexOf(" = ");
    if (line.startsWith(prefix) && eq > 0) {
      values.set(line.slice(0, eq), line.slice(eq + 3));
    } else {
      values.set(`${result.section}#${String(raw++).padStart(3, "0")}`, line);
    }
  }
  let status;
  if (result.timedOut) status = "timeout";
  else if (ended) status = "ok";
  else if (trap && trap.startsWith("MODULE_INVALID")) status = "module-invalid";
  else if (trap) status = "trap";
  else status = `crash (exit ${result.code})`;
  values.set(`${result.section}.$status`, status);
  return {values, notes, detail: trap || (status === "ok" ? null : result.stderr.split("\n").slice(-12).join(" | ").slice(0, 2000))};
}

// differences.txt: "<variants> <key glob> <reason...>", '#' comments. variants is a comma list of
// js, wasm, wasm-strict or "*"; in the glob (no spaces, as no key has one), * matches any run of
// characters and ? one character.
function loadRegister() {
  const file = join(suite, "differences.txt");
  const entries = [];
  readFileSync(file, "utf8").split(/\r?\n/).forEach((line, index) => {
    if (!line.trim() || line.startsWith("#")) return;
    const match = line.match(/^(\S+)\s+(\S+)\s+(\S.*)$/);
    if (!match) throw new Error(`differences.txt:${index + 1}: need <variants> <key glob> <reason>`);
    const fields = match.slice(1);
    for (const variant of fields[0] === "*" ? [] : fields[0].split(",")) {
      if (!KNOWN.includes(variant) || variant === "jvm") throw new Error(`differences.txt:${index + 1}: unknown variant ${variant}`);
    }
    // Glob: * and ? as in a shell, [...] a character class, everything else literal.
    const pattern = new RegExp("^" + fields[1].replace(/[.+^${}()|\\]/g, "\\$&").replaceAll("*", ".*").replaceAll("?", ".") + "$");
    entries.push({line: index + 1, variants: fields[0] === "*" ? null : fields[0].split(","), glob: fields[1], pattern,
      reason: fields[2].trim(), hits: 0});
  });
  return entries;
}

async function main() {
  mkdirSync(out, {recursive: true});
  if (!skipBuild) build();
  const sections = listSections().filter(s => !onlySections || onlySections.includes(s));
  const tasks = [];
  for (const section of sections) for (const variant of variants) tasks.push([variant, section]);
  const results = [];
  let next = 0;
  async function worker() {
    while (next < tasks.length) {
      const [variant, section] = tasks[next++];
      const result = await runSection(variant, section);
      results.push(result);
      const parsed = parse(result);
      const status = parsed.values.get(`${section}.$status`);
      if (status !== "ok") log(`${variant} ${section}: ${status} ${parsed.detail ? parsed.detail.slice(0, 300) : ""}`);
    }
  }
  const started = Date.now();
  await Promise.all(Array.from({length: Math.min(jobs, tasks.length)}, worker));
  log(`ran ${tasks.length} section runs in ${Math.round((Date.now() - started) / 1000)} s`);

  const byVariant = new Map(variants.map(v => [v, new Map()]));
  const notes = {};
  const details = {};
  const timings = {};
  for (const result of results) {
    const parsed = parse(result);
    for (const [key, value] of parsed.values) byVariant.get(result.variant).set(key, value);
    (notes[result.variant] ??= {})[result.section] = parsed.notes;
    if (parsed.detail) (details[result.variant] ??= {})[result.section] = parsed.detail;
    (timings[result.variant] ??= {})[result.section] = result.millis;
  }
  const register = useRegister ? loadRegister() : [];
  const reference = byVariant.get("jvm");
  const summary = {};
  const differences = [];
  for (const variant of variants.filter(v => v !== "jvm")) {
    const values = byVariant.get(variant);
    const keys = [...new Set([...reference.keys(), ...values.keys()])].sort();
    let equal = 0, registered = 0, unregistered = 0;
    for (const key of keys) {
      const expected = reference.has(key) ? reference.get(key) : "<missing>";
      const actual = values.has(key) ? values.get(key) : "<missing>";
      if (expected === actual) { equal++; continue; }
      const entry = register.find(e => (!e.variants || e.variants.includes(variant)) && e.pattern.test(key));
      if (entry) { entry.hits++; registered++; } else unregistered++;
      differences.push({variant, key, jvm: expected, actual, registered: entry ? `differences.txt:${entry.line}` : null,
        reason: entry ? entry.reason : null});
    }
    summary[variant] = {keys: keys.length, equal, registered, unregistered};
  }
  const ranVariants = new Set(variants);
  const stale = register.filter(e => e.hits === 0 && (!e.variants || e.variants.some(v => ranVariants.has(v)))
    && !onlySections).map(e => ({line: e.line, variants: e.variants, glob: e.glob}));
  const failed = useRegister && (differences.some(d => !d.registered) || stale.length > 0);
  const report = {suite: "port/tests/classlib", startedAt: new Date(started).toISOString(), variants, sections,
    jars: {js: jsJars, wasm: wasmJars}, options: {js: needsJs ? teavmConfig("js") : null, wasm: needsWasm ? teavmConfig("wasm") : null},
    summary, stale, differences, details, notes, timings, verdict: useRegister ? (failed ? "FAIL" : "PASS") : "LISTED"};
  writeFileSync(join(out, "report.json"), JSON.stringify(report, null, 2) + "\n");
  for (const variant of variants) {
    const lines = [...byVariant.get(variant)].map(([k, v]) => `${k} = ${v}`);
    writeFileSync(join(out, `values-${variant}.txt`), lines.join("\n") + "\n");
  }
  const text = [];
  for (const d of differences) {
    text.push(`${d.registered ? "registered  " : "UNREGISTERED"} ${d.variant} ${d.key}\n    jvm:    ${d.jvm.slice(0, 600)}\n    ${d.variant.padEnd(7)} ${d.actual.slice(0, 600)}${d.reason ? `\n    reason: ${d.reason}` : ""}`);
  }
  for (const s of stale) text.push(`STALE register entry differences.txt:${s.line} ${s.variants || "*"} ${s.glob}`);
  writeFileSync(join(out, "differences.txt"), text.join("\n") + "\n");
  for (const [variant, s] of Object.entries(summary)) {
    log(`${variant}: ${s.keys} keys, ${s.equal} equal to the JVM, ${s.registered} registered differences, ${s.unregistered} unregistered`);
  }
  if (stale.length) log(`${stale.length} stale register entries: ${stale.map(s => s.line).join(", ")}`);
  log(`verdict ${report.verdict}; ${join(out, "differences.txt")}`);
  process.exitCode = failed ? 1 : 0;
}

main().catch(error => {
  console.error(error.stack || String(error));
  process.exit(2);
});
