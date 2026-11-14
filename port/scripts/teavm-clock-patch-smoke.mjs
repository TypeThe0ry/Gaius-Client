#!/usr/bin/env node
// Runs the real TeaVMClasslibPatcher on the TeaVM class library of this checkout (the jar from
// the Maven repository; nothing is built or downloaded) and checks what it does to
// java.lang.System.nanoTime(): in the WebAssembly GC build the method returns
// HostClock.nanoTime() (one kept performance object instead of global("performance") plus a
// method call), in the JavaScript build TeaVM's own body stays, and the rest of the class is
// untouched. The patched method is verified with ASM's bytecode checker.
//
//   GAIUS_JAVA_HOME=/path/to/jdk node port/scripts/teavm-clock-patch-smoke.mjs
import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {existsSync} from "node:fs";
import {mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const config = JSON.parse(await readFile(path.join(repositoryRoot, "port/config.json"), "utf8"));
const repository = process.env.GAIUS_MAVEN_REPOSITORY || path.join(homedir(), ".m2/repository");
const asmVersion = "9.8";
const jar = path.join(repository, "org/teavm/teavm-classlib", config.teaVMVersion,
  `teavm-classlib-${config.teaVMVersion}.jar`);
const asm = ["asm", "asm-tree", "asm-analysis", "asm-util"].map(name =>
  path.join(repository, "org/ow2/asm", name, asmVersion, `${name}-${asmVersion}.jar`));
for (const file of [jar, ...asm]) {
  assert.ok(existsSync(file), `missing Maven artifact: ${file}`);
}

function selectTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
    const tool = path.join(home, "bin", name);
    if (spawnSync(tool, ["-version"]).status === 0) return tool;
  }
  return name;
}
const [java, javac, javap] = ["java", "javac", "javap"].map(selectTool);
const separator = path.delimiter;
const root = await mkdtemp(path.join(tmpdir(), "gaius-clock-patch-"));
try {
  const toolClasses = path.join(root, "tool-classes");
  await mkdir(toolClasses);
  const toolSources = path.join(repositoryRoot, "port/tools/src/main/java");
  execFileSync(javac, ["--release", "21", "-proc:none", "-classpath", asm.join(separator),
    "-sourcepath", toolSources, "-d", toolClasses,
    path.join(toolSources, "dev/gaius/tools/TeaVMClasslibPatcher.java")], {encoding: "utf8"});

  // Reads the verification harness: ASM's CheckClassAdapter over the patched class.
  const checkSource = path.join(root, "CheckPatched.java");
  await writeFile(checkSource, `
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
public class CheckPatched {
    public static void main(String[] args) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(args[0]))).accept(node, 0);
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
        System.out.println("VERIFIED " + node.methods.size());
    }
}
`);
  execFileSync(javac, ["--release", "21", "-proc:none", "-classpath", asm.join(separator),
    "-d", root, checkSource], {encoding: "utf8"});

  async function patch(target) {
    const output = path.join(root, `patched-${target}`);
    await mkdir(output);
    execFileSync(java, [`-Dgaius.teavm.target=${target}`, "-classpath",
      [toolClasses, ...asm].join(separator), "dev.gaius.tools.TeaVMClasslibPatcher", jar, output],
    {encoding: "utf8"});
    const systemClass = path.join(output, "org/teavm/classlib/java/lang/TSystem.class");
    const listing = execFileSync(javap, ["-p", "-c", "-classpath", output,
      "org.teavm.classlib.java.lang.TSystem"], {encoding: "utf8"});
    const verified = execFileSync(java, ["-classpath", [root, ...asm].join(separator), "CheckPatched",
      systemClass], {encoding: "utf8"});
    assert.match(verified, /VERIFIED \d+/);
    const start = listing.indexOf("public static long nanoTime();");
    assert.ok(start >= 0, "TSystem.nanoTime is missing");
    const end = listing.indexOf("\n\n", start);
    return {listing, body: listing.slice(start, end)};
  }

  const wasm = await patch("wasmgc");
  assert.match(wasm.body, /invokestatic\s+#\d+\s+\/\/ Method dev\/gaius\/browser\/HostClock\.nanoTime:\(\)J\s+3: lreturn/,
    `WebAssembly GC nanoTime must return HostClock.nanoTime():\n${wasm.body}`);
  assert.ok(!wasm.body.includes("Performance.now"), "the global(\"performance\") path must be gone");
  const js = await patch("js");
  assert.match(js.body, /Performance\.now/, "the JavaScript build keeps TeaVM's nanoTime");
  assert.ok(!js.body.includes("HostClock"), "the JavaScript build must not call HostClock");
  // Nothing else in the class changed: the other members of both listings are the same.
  const members = listing => listing.split("\n").filter(line => /^\s+(public|private|protected|static)/.test(line));
  assert.deepEqual(members(wasm.listing), members(js.listing), "only the body of nanoTime differs");
  console.log("TEAVM_CLOCK_PATCH_OK wasmgc: System.nanoTime -> HostClock.nanoTime; js: unchanged");
} finally {
  await rm(root, {recursive: true, force: true});
}
