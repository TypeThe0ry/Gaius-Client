#!/usr/bin/env node
// JVM unit test of the WasmGC settle probe without a TeaVM build: compiles the production probe and
// executors (port/src/target-wasmgc/java/dev/gaius/parity), the shared TickParitySettle, the JVM
// harness's TickParityExecutors and small Minecraft stubs, runs probe-test/TickParityProbeTest, and
// checks the probe reports it printed against the capture CLI's validateProbeReports.
// It starts javac and java: on the shared machine run it inside `slot.sh build`.
//
//   node port/scripts/packet-parity/settle-probe-jvm.mjs --out <directory> [--profile <id>]
import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {validateProbeReports} from "./tick-browser-capture.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");
const args = process.argv.slice(2);
const option = name => {
  const index = args.indexOf(name);
  return index < 0 ? null : args[index + 1] || null;
};
const out = option("--out");
if (!out) {
  console.error("usage: node port/scripts/packet-parity/settle-probe-jvm.mjs --out <directory> [--profile <id>]");
  process.exit(2);
}
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
if (!home) throw new Error("set GAIUS_JAVA_HOME to the cached JDK");
const suffix = process.platform === "win32" ? ".exe" : "";
const [java, javac] = ["java", "javac"].map(name => join(home, "bin", name + suffix));

// Guava only for the JVM harness's TickParityExecutors.io (vanilla's direct executor); read-only.
const profiles = option("--profile") ? [option("--profile")]
  : readdirSync(join(root, "port/versions")).filter(name => name.endsWith(".json")).map(name => name.slice(0, -5)).sort();
let guava = null;
for (const profile of profiles) {
  const listing = join(root, "port/work", profile, "classpath.txt");
  if (!existsSync(listing)) continue;
  const text = readFileSync(listing, "utf8").trim();
  const entries = (text.startsWith("/") ? text.split(":") : text.split(delimiter)).filter(Boolean)
    .map(path => path.replace(/^\/([a-zA-Z])\//, "$1:/"));
  guava = entries.find(path => /guava-[0-9.]+-jre\.jar$/.test(path) && existsSync(path)) || null;
  if (guava) break;
}
if (!guava) throw new Error("cached Guava jar not found in port/work/<profile>/classpath.txt; no downloads allowed");

const sources = [
  "port/src/main/java/dev/gaius/parity/TickParitySettle.java",
  "port/src/main/java/dev/gaius/parity/TickParitySettleProbe.java",
  "port/src/target-wasmgc/java/dev/gaius/parity/TickParitySerialExecutor.java",
  "port/src/target-wasmgc/java/dev/gaius/parity/TickParityDirectExecutor.java",
  "port/src/target-wasmgc/java/dev/gaius/parity/BrowserSettleProbe.java",
  "port/scripts/packet-parity/TickParityExecutors.java",
  "port/scripts/packet-parity/probe-test/TickParityProbeTest.java",
  "port/scripts/packet-parity/probe-test/stubs/net/minecraft/server/MinecraftServer.java",
  "port/scripts/packet-parity/probe-test/stubs/net/minecraft/server/level/ServerLevel.java",
  "port/scripts/packet-parity/probe-test/stubs/net/minecraft/server/level/ServerChunkCache.java",
].map(path => join(root, path));

const directory = resolve(out);
const classes = join(directory, "classes");
mkdirSync(classes, {recursive: true});
const quote = value => '"' + value.replaceAll("\\", "/").replaceAll('"', '\\"') + '"';
const argumentsFile = join(directory, "javac.args");
writeFileSync(argumentsFile, ["--release", "21", "-proc:none", "-Xlint:-options", "-cp", guava, "-d", classes, ...sources]
  .map(quote).join("\n") + "\n");

function run(command, commandArgs, label) {
  const result = spawnSync(command, commandArgs, {encoding: "utf8", timeout: 300_000, maxBuffer: 64 * 1024 * 1024});
  if (result.error) throw result.error;
  writeFileSync(join(directory, label + ".log"), (result.stdout || "") + (result.stderr || ""));
  return result;
}

const compiled = run(javac, ["-J-Xmx3g", "-J-XX:ActiveProcessorCount=2", "@" + argumentsFile], "javac");
if (compiled.status !== 0) {
  console.error(compiled.stdout + compiled.stderr);
  process.exit(1);
}
const started = Date.now();
const tested = run(java, ["-Xmx512m", "-XX:ActiveProcessorCount=2", "-cp", [classes, guava].join(delimiter),
  "dev.gaius.parity.TickParityProbeTest"], "java");
const seconds = (Date.now() - started) / 1000;
const lines = (tested.stdout || "").split(/\r?\n/);
const cases = lines.filter(line => /^(PASS|FAIL) /.test(line));
const summaryLine = lines.find(line => line.startsWith("PROBE_TEST "));
const historyLine = lines.find(line => line.startsWith("PROBE_HISTORY "));
const reports = lines.filter(line => line.startsWith("PROBE_REPORT ")).map(line => ({type: "tick-parity-probe",
  json: line.slice("PROBE_REPORT ".length)}));
let contract;
try {
  // The Java probe's own started/complete reports with the bridge's configured report.
  validateProbeReports([{type: "tick-parity-probe", json: JSON.stringify({phase: "configured", executor: "serial-fifo", requested: true})},
    ...reports]);
  contract = {passed: true, reports: reports.length};
} catch (error) {
  contract = {passed: false, error: error.message, reports: reports.length};
}
const hash = path => createHash("sha256").update(readFileSync(path)).digest("hex");
const result = {
  passed: tested.status === 0 && contract.passed && cases.length > 0 && cases.every(line => line.startsWith("PASS ")),
  exitCode: tested.status,
  seconds,
  cases,
  summary: summaryLine ? JSON.parse(summaryLine.slice("PROBE_TEST ".length)) : null,
  history: historyLine ? JSON.parse(historyLine.slice("PROBE_HISTORY ".length)) : null,
  reportContract: contract,
  java: home,
  guava,
  sourceSha256: Object.fromEntries(sources.map(path => [path.slice(root.length + 1).replaceAll("\\", "/"), hash(path)])),
};
writeFileSync(join(directory, "result.json"), JSON.stringify(result, null, 2) + "\n");
for (const line of cases) console.log(line);
console.log(`SETTLE_PROBE_JVM passed=${result.passed} cases=${cases.length} checks=${result.summary?.checks ?? 0}`
  + ` reportContract=${contract.passed} seconds=${seconds}`);
if (!result.passed) {
  console.error(tested.stdout + tested.stderr);
  process.exit(1);
}
