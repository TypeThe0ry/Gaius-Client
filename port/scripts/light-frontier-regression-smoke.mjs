#!/usr/bin/env node
/* Real JVM contract test for the Java light release and completion paths. */
import assert from "node:assert/strict";
import {existsSync, mkdirSync, rmSync} from "node:fs";
import {join} from "node:path";
import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const scratch = process.env.TMPDIR || process.env.TEMP || process.env.TMP;
assert(scratch, "TMPDIR/TEMP/TMP must point at the stream scratch directory");
const out = join(scratch, "gaius-light-jvm-smoke");
rmSync(out, {recursive: true, force: true});
mkdirSync(out, {recursive: true});
const sourceRoot = join(root, "src/main/java/dev/gaius/browser/kernel/light");
const sources = ["CompletionFrontier.java", "InFlightReleaseGate.java"].map(name => join(sourceRoot, name));
const test = join(root, "native/golden/light/route/LightKernelJvmTest.java");
const classes = join(out, "classes");
mkdirSync(classes, {recursive: true});
const javac = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", "javac") : "javac";
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", "java") : "java";
const compile = spawnSync(javac, ["-encoding", "UTF-8", "-proc:none", "-d", classes, ...sources, test], {encoding: "utf8", windowsHide: true});
assert.equal(compile.status, 0, compile.stderr || compile.stdout);
const run = spawnSync(java, ["-cp", classes, "dev.gaius.browser.kernel.light.LightKernelJvmTest"], {encoding: "utf8", windowsHide: true});
assert.equal(run.status, 0, run.stderr || run.stdout);
assert.match(run.stdout, /LIGHT_KERNEL_JVM_TEST PASS/);
console.log(run.stdout.trim());
rmSync(out, {recursive: true, force: true});
