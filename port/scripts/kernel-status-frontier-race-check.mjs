#!/usr/bin/env node
// JVM check of KernelStatusFrontier's claim/publish contract with real threads (no Minecraft jar):
//
//   GAIUS_JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 node port/scripts/kernel-status-frontier-race-check.mjs
//
// Compiles port/src/main/java/dev/gaius/browser/kernel/KernelStatusFrontier.java with
// port/native/golden/worldgen/KernelStatusFrontierRaceCheck.java and runs the check.
import {spawnSync} from "node:child_process";
import {mkdtempSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME || "";
const tool = (name) => (home ? join(home, "bin", name) : name);
const out = mkdtempSync(join(tmpdir(), "frontier-race-"));
try {
  const run = (command, args) => {
    const result = spawnSync(command, args, {stdio: "inherit"});
    if (result.status !== 0) throw new Error(`${command} exited ${result.status}`);
  };
  run(tool("javac"), ["-encoding", "UTF-8", "-proc:none", "-d", out,
    join(root, "port/src/main/java/dev/gaius/browser/kernel/KernelStatusFrontier.java"),
    join(root, "port/native/golden/worldgen/KernelStatusFrontierRaceCheck.java")]);
  run(tool("java"), ["-cp", out, "KernelStatusFrontierRaceCheck"]);
} finally {
  rmSync(out, {recursive: true, force: true});
}
