#!/usr/bin/env node
import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {cpSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {homedir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
function option(name, fallback = null) {
  const index = process.argv.indexOf(`--${name}`);
  if (index < 0) return fallback;
  const value = process.argv[index + 1];
  if (!value || value.startsWith("--")) throw new Error(`--${name} requires a value`);
  return value;
}
const output = resolve(option("out") || (() => { throw new Error("--out is required"); })());
const version = JSON.parse(readFileSync(join(root, "port/config.json"))).teaVMVersion;
const repository = process.env.MAVEN_REPOSITORY || join(homedir(), ".m2/repository");
const javaHome = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
const executable = name => javaHome ? join(javaHome.replace(/^\/([a-z])\//i, "$1:/"), "bin", name) : name;
const jar = name => join(repository, "org/teavm", name, version, `${name}-${version}.jar`);
const dependencies = [
  ...["teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-jso-impl", "teavm-platform",
    "teavm-metaprogramming-api", "teavm-metaprogramming-impl", "teavm-relocated-libs-asm",
    "teavm-relocated-libs-asm-tree", "teavm-relocated-libs-asm-analysis", "teavm-relocated-libs-asm-commons",
    "teavm-relocated-libs-asm-util", "teavm-relocated-libs-hppc", "teavm-relocated-libs-rhino",
    "teavm-extension-apis", "teavm-extension-spi", "teavm-extension-spi-util", "teavm-tooling"].map(jar),
  join(repository, "com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar"),
  join(repository, "joda-time/joda-time/2.12.2/joda-time-2.12.2.jar"),
];
const selected = option("profile");
const browserExports = option("browser-exports") && resolve(option("browser-exports"));
const browserReport = browserExports && JSON.parse(readFileSync(join(browserExports, "report.json")));
if (browserReport) assert.equal(browserReport.status, "PASS", "browser exports must come from a passing gate");
const profiles = readdirSync(join(root, "port/versions")).filter(name => name.endsWith(".json"))
  .map(name => JSON.parse(readFileSync(join(root, "port/versions", name))))
  .filter(profile => !selected || profile.id === selected);
assert(profiles.length, "no selected profiles");
mkdirSync(output, {recursive: true});
assert(!existsSync(join(output, "report.json")), "use a fresh output directory for each run");
function overlay(kind, profile) {
  const directories = (option(`${kind}-overlays`) || "").split(",").filter(Boolean);
  const candidates = directories.flatMap(directory => [resolve(directory, `${kind}-${profile.id}`), resolve(directory)]);
  const result = candidates.find(directory => existsSync(join(directory, `teavm-core-${version}-gaius.jar`)));
  assert(result, `missing --${kind}-overlays for ${profile.id}`);
  return result;
}
const identity = path => ({path, sha256: createHash("sha256").update(readFileSync(path)).digest("hex")});
function command(name, args, log, environment = {}, workingDirectory = root) {
  const result = spawnSync(name, args, {cwd: workingDirectory, encoding: "utf8", timeout: 300000,
    maxBuffer: 16 << 20, env: {...process.env, ...environment}});
  const text = (result.stdout || "") + (result.stderr || "");
  writeFileSync(log, text);
  assert.equal(result.status, 0, `${name} failed: ${result.error || text.slice(-2000)}; ${log}`);
  return text;
}
function pass(text) {
  const summary = text.match(/^REGION_STORAGE_PASS regions=(\d+) chunks=(\d+) absent=(\d+) payloadBytes=(\d+) fileBytes=(\d+)$/m);
  assert(summary, "probe did not finish all assertions");
  return Object.fromEntries(["regions", "chunks", "absent", "payloadBytes", "fileBytes"]
    .map((name, index) => [name, Number(summary[index + 1])]));
}
function regions(text) {
  const files = Object.fromEntries([...text.matchAll(/^REGION (r\.\d+\.0\.mca) ([A-Za-z0-9+/=]+)$/gm)]
    .map(match => [match[1], match[2]]));
  assert.equal(Object.keys(files).length, pass(text).regions, "missing serialized region files");
  return files;
}
const report = {scope: "vanilla RegionFile and product FileChannel/persistence with a synchronous byte-store fixture",
  browserWorldGatePassed: false, profiles: [], status: "RUNNING"};
try {
  for (const profile of profiles) {
    const directory = join(output, profile.id);
    const classes = join(directory, "classes");
    const driver = join(directory, "driver");
    const clock = join(directory, "clock");
    for (const path of [classes, driver, clock]) mkdirSync(path, {recursive: true});
    const work = join(root, "port/work", profile.id);
    const vanilla = join(work, "client-named.jar");
    const marker = `/port/work/${profile.id}/`;
    const libraries = readFileSync(join(work, "classpath.txt"), "utf8").trim().split(":")
      .filter(path => path.includes(marker)).map(path => join(work, path.split(marker)[1]))
      .filter(path => path.endsWith(".jar") && path.includes(`${join(work, "libraries")}`));
    const vanillaClasspath = [classes, vanilla, ...libraries].join(delimiter);
    command(executable("javac"), ["-J-Xmx3g", "--release", "21", "-proc:none", "-cp", vanillaClasspath,
      "-d", classes, join(root, "port/scripts/region-storage-parity/RegionStorageProbe.java"),
      join(root, "port/scripts/region-storage-parity/RegionStorageJvm.java")], join(directory, "javac.log"));
    command(executable("javac"), ["-J-Xmx3g", "--release", "21", "-proc:none", "-d", clock,
      join(root, "port/scripts/region-storage-parity/clock/net/minecraft/util/Util.java"),
      join(root, "port/scripts/region-storage-parity/clock/net/minecraft/world/level/ChunkPos.java")], join(directory, "clock.log"));
    const jvmDirectory = join(directory, "jvm-write");
    const jvmText = command(executable("java"), ["-Xmx3g", "-cp", vanillaClasspath,
      "dev.gaius.storage.RegionStorageJvm", "write", jvmDirectory], join(directory, "jvm-write.log"));
    const row = {profile: profile.id, vanilla: identity(vanilla), jvm: pass(jvmText), runtimes: {}, readers: {}};
    report.profiles.push(row);
    const written = {jvm: regions(jvmText)};
    for (const kind of ["wasm", "js"]) {
      const overlays = overlay(kind, profile);
      const core = join(overlays, `teavm-core-${version}-gaius.jar`);
      const classlib = kind === "wasm" && option("wasm-classlib") ? resolve(option("wasm-classlib"))
        : join(overlays, `teavm-classlib-${version}-gaius.jar`);
      const compilerDependencies = [core, classlib, ...dependencies];
      command(executable("javac"), ["-J-Xmx3g", "--release", "21", "-proc:none", "-cp",
        compilerDependencies.join(delimiter), "-d", driver, join(root, "port/tests/classlib/driver/ClasslibBuild.java")],
      join(directory, `driver-${kind}.log`));
      const patchedVanilla = join(overlays, `client-named-${profile.id}-gaius.jar`);
      assert(existsSync(patchedVanilla), `missing patched vanilla jar ${patchedVanilla}`);
      const patchedLibraries = libraries.map(path => {
        const replacement = join(overlays, path.slice(work.length + 1));
        return existsSync(replacement) ? replacement : path;
      });
      const classpath = [clock, classes, driver, ...compilerDependencies, join(overlays, "classlib-classes"),
        join(overlays, "client-override-classes"), patchedVanilla, ...patchedLibraries].join(delimiter);
      const target = join(directory, kind);
      mkdirSync(target, {recursive: true});
      const properties = kind === "wasm" ? ["gaius.teavm.jspi=true", "gaius.teavm.syncClinits=false",
        "gaius.teavm.syncMonitors=false", "gaius.teavm.asyncBarrier=none"] : ["gaius.teavm.syncClinits=true",
        "gaius.teavm.syncMonitors=true", "gaius.teavm.asyncBarrier=object,throwable,map,collection,iterator,stringbuilder"];
      command(executable("java"), ["-Xmx3g", "-cp", classpath, "dev.gaius.classlib.driver.ClasslibBuild", kind,
        target, "dev.gaius.storage.RegionStorageProbe", kind === "wasm" ? "SIMPLE" : "ADVANCED", "strict=false",
        "gaius.teavm.role=client", "gaius.teavm.pinRuntimeNames=true", "gaius.teavm.telemetry=false", ...properties],
      join(target, "compile.log"));
      if (kind === "wasm") {
        command(executable("jar"), ["xf", core, "org/teavm/backend/wasm/wasm-gc-runtime.js"],
          join(target, "extract.log"), {}, target);
        const runtime = join(target, "org/teavm/backend/wasm/wasm-gc-runtime.js");
        command("node", [join(root, "port/web/runtime/wasmgc/patch-wasm-gc-runtime.mjs"), runtime,
          join(target, "gaius-wasm-gc-runtime.js")], join(target, "runtime.log"));
        cpSync(join(root, "port/web/runtime/wasmgc/gaius-jspi.js"), join(target, "gaius-jspi.js"));
      } else {
        command("bash", [join(root, "port/scripts/run-python.sh"), join(root, "port/scripts/postprocess-teavm-js.py"),
          join(target, "classes.js")], join(target, "postprocess.log"));
      }
      row.runtimes[kind] = {core: identity(core), classlib: identity(classlib), vanilla: identity(patchedVanilla)};
      function run(mode, name, files = {}) {
        const receipt = join(target, `${name}.receipt.json`);
        const configuration = join(target, `${name}.config.json`);
        writeFileSync(configuration, JSON.stringify({profile, files, receipt}));
        const args = ["--require", join(root, "port/scripts/region-storage-parity/bridge.cjs")];
        if (kind === "wasm") args.push("--experimental-wasm-jspi", join(root, "port/tests/classlib/runner/run-wasm.cjs"),
          join(target, "gaius-wasm-gc-runtime.js"), join(target, "gaius-jspi.js"), join(target, "classes.wasm"));
        else args.push(join(root, "port/tests/classlib/runner/run-js.cjs"), join(target, "classes.js"));
        const text = command("node", [...args, mode, "/gaius/saves/storage-parity/region"], join(target, `${name}.log`),
          {GAIUS_STORAGE_PROBE_CONFIG: configuration});
        const persisted = JSON.parse(readFileSync(receipt));
        const metrics = pass(text);
        if (mode === "write") {
          const exported = regions(text);
          assert.deepEqual(persisted.regions, exported, "persistent bytes differ from reopened VFS bytes");
          assert(persisted.patches + persisted.snapshots > 0, "writer bypassed persistence");
          written[kind] = exported;
          row.runtimes[kind].writes = {patches: persisted.patches, snapshots: persisted.snapshots, ...metrics};
        } else row.readers[name] = metrics;
      }
      row.runtimes[kind].run = run;
      run("write", `${kind}-write`);
      run("read", `jvm-to-${kind}`, written.jvm);
    }
    row.runtimes.js.run("read", "wasm-to-js", written.wasm);
    row.runtimes.wasm.run("read", "js-to-wasm", written.js);
    for (const kind of ["wasm", "js"]) {
      const destination = join(directory, `${kind}-to-jvm`);
      mkdirSync(destination, {recursive: true});
      for (const [name, bytes] of Object.entries(written[kind])) writeFileSync(join(destination, name), Buffer.from(bytes, "base64"));
      row.readers[`${kind}-to-jvm`] = pass(command(executable("java"), ["-Xmx3g", "-cp", vanillaClasspath,
        "dev.gaius.storage.RegionStorageJvm", "read", destination], join(directory, `${kind}-to-jvm.log`)));
    }
    if (browserReport) {
      row.browserReaders = {};
      const browserProfile = browserReport.profiles.find(candidate => candidate.profile === profile.id);
      assert(browserProfile && browserProfile.directions.length === 4, "missing browser backend/direction coverage");
      for (const direction of browserProfile.directions) {
        assert(["opfs", "indexeddb"].includes(direction.backend));
        assert(["wasm", "js"].includes(direction.writer) && ["wasm", "js"].includes(direction.reader)
          && direction.writer !== direction.reader);
        const name = `${direction.backend}-${direction.writer}-to-${direction.reader}`;
        const source = join(browserExports, profile.id, direction.backend, `${direction.writer}-to-${direction.reader}`);
        row.browserReaders[name] = pass(command(executable("java"), ["-Xmx3g", "-cp", vanillaClasspath,
          "dev.gaius.storage.RegionStorageJvm", "read", source], join(directory, `browser-${name}-to-jvm.log`)));
      }
      assert.equal(Object.keys(row.browserReaders).length, 4, "duplicate browser backend/direction coverage");
      console.log(`${profile.id}: browser exports accepted by vanilla JVM (${Object.keys(row.browserReaders).length})`);
    }
    console.log(`${profile.id}: PASS writers=3 crossReaders=${Object.keys(row.readers).length} chunks=${row.jvm.chunks}`);
  }
  report.status = "PASS";
} catch (error) {
  report.status = "FAIL";
  report.error = String(error.stack || error);
  process.exitCode = 1;
  console.error(report.error.split("\n").slice(0, 4).join("\n"));
} finally {
  writeFileSync(join(output, "report.json"), JSON.stringify(report, null, 2) + "\n");
}
