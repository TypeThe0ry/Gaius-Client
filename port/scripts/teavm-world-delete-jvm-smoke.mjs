#!/usr/bin/env node
// Exercises vanilla's TeaVM Files.walkFileTree delete visitor against the real TeaVM in-memory
// VFS, before and after the production classlib patcher fixes TDefaultPath.equals(this).
import assert from "node:assert/strict";
import {spawnSync, execFileSync} from "node:child_process";
import {existsSync} from "node:fs";
import {mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const config = JSON.parse(await readFile(path.join(root, "port/config.json"), "utf8"));
const repository = process.env.GAIUS_MAVEN_REPOSITORY || path.join(homedir(), ".m2/repository");
const version = config.teaVMVersion;
const asmVersion = "9.8";
const teavm = ["teavm-classlib", "teavm-core", "teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-platform"]
  .map(name => path.join(repository, "org/teavm", name, version, `${name}-${version}.jar`));
const asm = ["asm", "asm-tree", "asm-analysis", "asm-util"].map(name =>
  path.join(repository, "org/ow2/asm", name, asmVersion, `${name}-${asmVersion}.jar`));
for (const file of [...teavm, ...asm]) assert.ok(existsSync(file), `missing Maven artifact: ${file}`);

function executable(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
    const candidate = path.join(home, "bin", process.platform === "win32" ? `${name}.exe` : name);
    if (spawnSync(candidate, ["-version"]).status === 0) return candidate;
  }
  return name;
}
const [java, javac] = ["java", "javac"].map(executable);
const separator = path.delimiter;
const out = await mkdtemp(path.join(tmpdir(), "gaius-world-delete-jvm-"));
try {
  const toolClasses = path.join(out, "tool-classes");
  const fixtureClasses = path.join(out, "fixture-classes");
  const patched = path.join(out, "patched");
  const fixtureSource = path.join(root, "port/scripts/fixtures/DefaultPathEqualsDeleteCheck.java");
  const stubSource = path.join(out, "src/dev/gaius/browser/BrowserFilePersistence.java");
  for (const directory of [toolClasses, fixtureClasses, patched, path.dirname(stubSource)]) {
    await mkdir(directory, {recursive: true});
  }
  await writeFile(stubSource, `
package dev.gaius.browser;
import java.util.ArrayList;
import java.util.List;
public final class BrowserFilePersistence {
  public static final List<String> deletedPaths = new ArrayList<>();
  public static void syncDelete(String path) { deletedPaths.add(path); }
}
`);

  const toolSources = path.join(root, "port/tools/src/main/java");
  execFileSync(javac, ["--release", "21", "-proc:none", "-classpath", asm.join(separator),
    "-sourcepath", toolSources, "-d", toolClasses,
    path.join(toolSources, "dev/gaius/tools/TeaVMClasslibPatcher.java")], {stdio: "inherit"});
  execFileSync(javac, ["--release", "21", "-proc:none", "-classpath", teavm.join(separator),
    "-d", fixtureClasses, fixtureSource, stubSource], {stdio: "inherit"});

  const fixtureClasspath = [fixtureClasses, ...teavm].join(separator);
  const before = spawnSync(java, ["-classpath", fixtureClasspath, "DefaultPathEqualsDeleteCheck"], {
    encoding: "utf8", maxBuffer: 32 << 20,
  });
  assert.notEqual(before.status, 0, "unpatched TeaVM unexpectedly deleted the locked world root");
  assert.match(`${before.stdout}\n${before.stderr}`, /DirectoryNotEmptyException: \/gaius\/saves\/W/,
    "unpatched fixture did not reproduce the vanilla world-root failure");

  execFileSync(java, ["-classpath", [toolClasses, ...asm, ...teavm].join(separator),
    "dev.gaius.tools.TeaVMClasslibPatcher", teavm[0], patched], {stdio: "inherit"});
  const after = execFileSync(java, ["-classpath", [fixtureClasses, patched, ...teavm].join(separator),
    "DefaultPathEqualsDeleteCheck"], {encoding: "utf8"});
  assert.match(after, /WORLD_DELETE_OK samePathEquals=true lockClosed=true/);
  assert.match(after, /session\.lock/);
  console.log("TEAVM_WORLD_DELETE_JVM_SMOKE PASS (unpatched failure reproduced; patched VFS delete passed)");
} finally {
  await rm(out, {recursive: true, force: true});
}
