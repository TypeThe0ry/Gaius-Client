#!/usr/bin/env node
// JVM check of KernelStatusFrontier's timeout lifecycle (no Minecraft jar): the timeout of a ticket whose
// claim is decided (kernel action published, or fallback) is cancelled, so the ticket and the kernel result
// its selected action captured are not kept reachable for the full timeout; the claim contract (one action
// per ticket, registration order, a winning timeout still selects vanilla) is unchanged.
//
//   GAIUS_JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 node port/scripts/kernel-status-frontier-watchdog-check.mjs
//
// Compiles port/src/main/java/dev/gaius/browser/kernel/KernelStatusFrontier.java with
// port/native/golden/worldgen/KernelStatusFrontierWatchdogCheck.java and runs the check.
import {spawnSync} from "node:child_process";
import {mkdtempSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME || "";
const tool = (name) => (home ? join(home, "bin", name) : name);
const frontier = process.env.GAIUS_FRONTIER_SOURCE
  || join(root, "port/src/main/java/dev/gaius/browser/kernel/KernelStatusFrontier.java");
const out = mkdtempSync(join(tmpdir(), "frontier-watchdog-"));
try {
  const run = (command, args) => {
    const result = spawnSync(command, args, {stdio: "inherit"});
    if (result.status !== 0) throw new Error(`${command} exited ${result.status}`);
  };
  run(tool("javac"), ["-encoding", "UTF-8", "-proc:none", "-d", out, frontier,
    join(root, "port/native/golden/worldgen/KernelStatusFrontierWatchdogCheck.java")]);
  run(tool("java"), ["-cp", out, "KernelStatusFrontierWatchdogCheck"]);
} finally {
  rmSync(out, {recursive: true, force: true});
}
