// Differential test of TeaVMClasslibPatcher.patchTemplateMapEntryArray (Map.of / Map.ofEntries /
// Map.copyOf of the TeaVM class library place their entries through
// TCollectionsModernSupport.mapEntryArray instead of TeaVM's probing body):
//
// 1. the real patcher writes NEtriesMap twice, as the build has it today (lookup index only) and
//    with the change; the change refuses a body that is not TeaVM's;
// 2. port/scripts/fixtures/TemplateMapEntryArrayDifferential.java loads each next to the compiled
//    Gaius class library overrides and the TeaVM class library jar in its own class loader and
//    compares slot layout, iteration order, lookups, exceptions and the hashCode/equals/getKey/
//    getValue call traces on random cases and on Map.copyOf of the vanilla en_us translations.
//
//   node port/scripts/classlib-template-map-entry-array.test.mjs [--profile <id>]
// --profile (default: every profile with port/work/<id>/client-named.jar) takes the ICU4J jar
// from that profile's classpath.txt and the en_us.json from its client jar.
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const index = process.argv.indexOf("--profile");
const profiles = index > 0 ? [process.argv[index + 1]]
  : readdirSync(join(root, "port/versions")).filter(name => name.endsWith(".json")).map(name => name.slice(0, -5))
    .filter(profile => existsSync(join(root, "port/work", profile, "client-named.jar"))).sort();
assert.ok(profiles.length > 0, "no profile with a client jar");
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const exe = name => home ? join(home, "bin", process.platform === "win32" ? `${name}.exe` : name) : name;
const m2 = process.env.GAIUS_MAVEN_REPOSITORY || join(process.env.USERPROFILE || process.env.HOME, ".m2", "repository");
const teavm = "0.15.0";
const classlib = join(m2, "org/teavm/teavm-classlib", teavm, `teavm-classlib-${teavm}.jar`);
const teavmJars = ["teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-core", "teavm-platform"]
  .map(name => join(m2, "org/teavm", name, teavm, `${name}-${teavm}.jar`));
const asm = ["asm", "asm-tree"].map(name => join(m2, "org/ow2/asm", name, "9.8", `${name}-9.8.jar`));
const jzlib = join(m2, "com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar");
for (const file of [classlib, ...teavmJars, ...asm, jzlib]) assert.ok(existsSync(file), `missing ${file}`);

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 << 20, ...options});
  assert.equal(result.status, 0, `${command} failed\n${(result.stdout || "").slice(-6000)}${(result.stderr || "").slice(-6000)}`);
  return result.stdout;
}

const out = mkdtempSync(join(tmpdir(), "gaius-template-map-"));
try {
  const tools = join(out, "tools");
  const fixture = join(out, "fixture");
  for (const dir of [tools, fixture]) mkdirSync(dir);
  run(exe("javac"), ["-nowarn", "-proc:none", "--release", "21", "-cp", asm.join(delimiter),
    "-sourcepath", join(root, "port/tools/src/main/java"), "-d", tools,
    join(root, "port/tools/src/main/java/dev/gaius/tools/TeaVMClasslibPatcher.java")]);
  run(exe("javac"), ["-nowarn", "-d", fixture, join(root, "port/scripts/fixtures/TemplateMapEntryArrayDifferential.java")]);

  for (const profile of profiles) {
    const work = join(root, "port/work", profile);
    const icu = readFileSync(join(work, "classpath.txt"), "utf8").trim().split(/[:;]/).find(path => /icu4j-[^/\\]*\.jar$/.test(path));
    assert.ok(icu && existsSync(icu), `${profile}: no ICU4J jar in classpath.txt`);
    const dir = join(out, profile);
    const overrides = join(dir, "overrides");
    const before = join(dir, "before");
    const after = join(dir, "after");
    for (const path of [overrides, before, after]) mkdirSync(path, {recursive: true});
    // The class library overrides the tested classes need, compiled as build-overlays.sh does
    // (classlib overlay root + the JDK API roots, against the TeaVM class library).
    const sourcepath = [join(root, "port/overrides/classlib/src/main/java"),
      join(root, "port/overrides/classlib/src/target-wasmgc/java"),
      join(root, "port/overrides/jdk/21/src/main/java")].filter(existsSync).join(delimiter);
    const util = "org/teavm/classlib/java/util";
    run(exe("javac"), ["-nowarn", "-proc:none", "-encoding", "UTF-8", "-implicit:class", "-d", overrides,
      "-cp", [classlib, ...teavmJars, jzlib, icu].join(delimiter), "-sourcepath", sourcepath,
      join(root, "port/overrides/jdk/21/src/main/java", util, "TCollectionsModernSupport.java"),
      join(root, "port/overrides/classlib/src/main/java", util, "THashMap.java")]);
    const patch = run(exe("java"), ["-cp", [fixture, tools, ...asm].join(delimiter),
      "TemplateMapEntryArrayDifferential$Patch", classlib, before, after]);
    assert.match(patch, /TEMPLATE_MAP_PATCH_OK refusesReplacedBody=true/);
    const lang = join(dir, "en_us.json");
    writeFileSync(lang, run("unzip", ["-p", join(work, "client-named.jar"), "assets/minecraft/lang/en_us.json"]));
    const libraries = [overrides, classlib, ...teavmJars, jzlib, icu];
    // Without fast throw the JIT keeps the exception messages (it drops them on hot paths).
    const result = run(exe("java"), ["-Xss8m", "-XX:-OmitStackTraceInFastThrow", "-cp", fixture, "TemplateMapEntryArrayDifferential",
      [before, ...libraries].join(delimiter), [after, ...libraries].join(delimiter), lang], {timeout: 1_800_000});
    for (const line of result.trim().split("\n")) console.log(`${profile} ${line}`);
    assert.match(result, /TEMPLATE_MAP_ENTRY_ARRAY_DIFFERENTIAL_OK cases=\d+/);
  }
  console.log(`CLASSLIB_TEMPLATE_MAP_ENTRY_ARRAY_TEST PASS profiles=${profiles.join(",")}`);
} finally {
  rmSync(out, {recursive: true, force: true});
}
