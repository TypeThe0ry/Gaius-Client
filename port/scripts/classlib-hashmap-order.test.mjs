// JVM differential test of the HashMap/HashSet class library override (port/overrides/classlib):
// compiles THashMap/THashSet against TeaVM's class library jar and runs
// fixtures/HashMapOrderDifferential.java, which drives them and java.util.HashMap/HashSet with the
// same random operations and requires the same iteration order (and TeaVM's TLinkedHashMap on top
// of the override to keep java.util.LinkedHashMap's order). fixtures/LinkedHashLegacyTrace.java
// then replays random TLinkedHashMap/TLinkedHashSet operations once on TeaVM's original
// THashMap/THashSet and once on the override; the two traces must be identical. No TeaVM compile.
//   node port/scripts/classlib-hashmap-order.test.mjs [seedsPerKind]
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
assert.ok(home, "set GAIUS_JAVA_HOME or JAVA_HOME");
const exe = name => join(home, "bin", process.platform === "win32" ? `${name}.exe` : name);
const m2 = process.env.GAIUS_MAVEN_REPOSITORY || join(process.env.USERPROFILE || process.env.HOME, ".m2", "repository");
const teavm = ["teavm-classlib", "teavm-interop", "teavm-core", "teavm-platform", "teavm-jso", "teavm-jso-apis"]
  .map(name => join(m2, "org", "teavm", name, "0.15.0", `${name}-0.15.0.jar`));
for (const jar of teavm) assert.ok(existsSync(jar), `missing ${jar}`);
const out = mkdtempSync(join(tmpdir(), "gaius-hashmap-order-"));
try {
  const overrides = join(root, "port/overrides/classlib/src/main/java/org/teavm/classlib/java/util");
  const fixtures = join(root, "port/scripts/fixtures");
  const compile = spawnSync(exe("javac"), ["-nowarn", "-proc:none", "-encoding", "UTF-8", "-cp", teavm.join(delimiter), "-d", out,
    join(overrides, "THashMap.java"), join(overrides, "THashSet.java"),
    join(fixtures, "org/teavm/classlib/java/util/HashCloneProbe.java"),
    join(fixtures, "HashMapOrderDifferential.java")], {encoding: "utf8"});
  assert.equal(compile.status, 0, compile.stdout + compile.stderr);
  const run = spawnSync(exe("java"), ["-Xmx1g", "-cp", [out, ...teavm].join(delimiter), "HashMapOrderDifferential",
    process.argv[2] || "300"], {encoding: "utf8", maxBuffer: 64 << 20});
  process.stdout.write(run.stdout.slice(-4000));
  assert.equal(run.status, 0, run.stderr.slice(-4000));
  assert.match(run.stdout, /HASHMAP_ORDER_OK checks=\d+/);

  // TLinkedHashMap/TLinkedHashSet must behave exactly as on TeaVM's original classes.
  const trace = join(out, "linked-trace");
  mkdirSync(trace);
  const traceCompile = spawnSync(exe("javac"), ["-nowarn", "-proc:none", "-encoding", "UTF-8", "-cp", teavm.join(delimiter),
    "-d", trace, join(fixtures, "LinkedHashLegacyTrace.java")], {encoding: "utf8"});
  assert.equal(traceCompile.status, 0, traceCompile.stdout + traceCompile.stderr);
  const traces = [[trace, ...teavm], [out, trace, ...teavm]].map(cp => {
    const result = spawnSync(exe("java"), ["-Xmx1g", "-cp", cp.join(delimiter), "LinkedHashLegacyTrace"],
      {encoding: "utf8", maxBuffer: 64 << 20});
    assert.equal(result.status, 0, result.stderr.slice(-4000));
    return result.stdout.trim();
  });
  assert.match(traces[0], /^LINKED_TRACE length=\d+ hash=[0-9a-f]+$/);
  assert.equal(traces[1], traces[0], "TLinkedHashMap/TLinkedHashSet trace differs from TeaVM's original THashMap");
  console.log(`${traces[1]} (same as TeaVM's original THashMap)`);
} finally {
  rmSync(out, {recursive: true, force: true});
}
