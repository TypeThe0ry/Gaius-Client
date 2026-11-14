#!/usr/bin/env node
// Offline check of the pipeline warm-up (26.3): the patch and the runtime class, without a browser.
//
//   node port/scripts/shader-pipeline-warmup-smoke.mjs [--keep]
//
// Inputs: port/work/26.3 (client-named.jar, classpath.txt, libraries/), ASM 9.8 and the TeaVM API jars
// (config.json teaVMVersion) in the Maven repository (GAIUS_MAVEN_REPOSITORY or ~/.m2/repository), a JDK >= 21.
//
// 1. RenderpearlShaderPatches.patchShaderPipelineWarmup runs on the vanilla client jar; javap shows exactly
//    the intended insertions: ShaderManager (loadConfigs head, each load(Identifier, Resource, ...) head, one
//    eager() after requiredPipelines() and one after optionalPipelines(), one attach() before
//    setCurrentPipelineCache) and PipelineCache (PipelineWarmTarget, gaiusWarm bridge, onMiss on the miss path).
// 2. The patched classes load and verify under -Xverify:all, and ShaderPipelineWarmupJvmTest drives
//    BrowserPipelineWarmup over the real 26.3 registry and the patched PipelineCache (title split, nothing lost,
//    priority order, budget, cancel on reload, vanilla order for foreign shader packs / parameters).

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {existsSync} from "node:fs";
import {mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const keep = process.argv.includes("--keep");
const root = fileURLToPath(new URL("../..", import.meta.url));
const config = JSON.parse(await readFile(join(root, "port/config.json"), "utf8"));
const maven = process.env.GAIUS_MAVEN_REPOSITORY || join(homedir(), ".m2/repository");
const asm = ["asm", "asm-tree"].map((name) => join(maven, `org/ow2/asm/${name}/9.8/${name}-9.8.jar`));
const teavm = ["teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-platform"]
  .map((name) => join(maven, `org/teavm/${name}/${config.teaVMVersion}/${name}-${config.teaVMVersion}.jar`));
const work = join(root, "port/work/26.3");
const clientJar = join(work, "client-named.jar");
for (const required of [...asm, ...teavm, clientJar, join(work, "classpath.txt")]) {
  assert.ok(existsSync(required), `missing input ${required}`);
}
const tool = (name) => {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
    if (existsSync(join(home, "bin", name))) return join(home, "bin", name);
  }
  return name;
};
const run = (command, args, options = {}) => execFileSync(command, args, {encoding: "utf8", maxBuffer: 1 << 28, ...options});

const scratch = await mkdtemp(join(tmpdir(), "gaius-warmup-"));
try {
  // 1. Patch the vanilla jar.
  const driverDir = join(scratch, "driver");
  const patched = join(scratch, "patched");
  const toolsOut = join(scratch, "tools");
  await mkdir(driverDir, {recursive: true});
  await mkdir(patched, {recursive: true});
  await mkdir(toolsOut, {recursive: true});
  const driver = join(driverDir, "ApplyShaderWarmup.java");
  await writeFile(driver, `
import java.nio.file.Path;
public class ApplyShaderWarmup {
  public static void main(String[] a) throws Exception {
    dev.gaius.tools.patches.render.RenderpearlShaderPatches.patchShaderPipelineWarmup(a[0], Path.of(a[1]));
  }
}
`);
  run(tool("javac"), ["-d", toolsOut, "-cp", asm.join(delimiter), "-sourcepath", join(root, "port/tools/src/main/java"), driver]);
  run(tool("java"), ["-cp", [toolsOut, ...asm].join(delimiter), "ApplyShaderWarmup", clientJar, patched]);

  const javap = (owner) => run(tool("javap"), ["-p", "-c", "-cp", patched, owner]);
  const shader = javap("net.minecraft.client.renderer.ShaderManager");
  const count = (text, needle) => text.split(needle).length - 1;
  assert.equal(count(shader, "BrowserPipelineWarmup.beginConfigLoad:()V"), 1, "loadConfigs head");
  assert.equal(count(shader, "BrowserPipelineWarmup.noteShaderPack:(Ljava/lang/String;)V"), 3, "shader, include and post chain loaders");
  assert.equal(count(shader, "BrowserPipelineWarmup.eager:(Ljava/util/List;Z)Ljava/util/List;"), 2, "required and optional lists");
  assert.equal(count(shader, "BrowserPipelineWarmup.attach:(Ljava/lang/Object;)V"), 1, "attach before the cache becomes current");
  assert.equal(count(shader, "Resource.sourcePackId:()Ljava/lang/String;"), 3);
  const attachAt = shader.indexOf("BrowserPipelineWarmup.attach");
  const currentAt = shader.indexOf("RenderSystem.setCurrentPipelineCache", attachAt);
  assert.ok(attachAt > 0 && currentAt > attachAt && currentAt - attachAt < 400, "attach sits right before setCurrentPipelineCache");
  const lists = [...shader.matchAll(/invokestatic .*RenderPipelines\.(requiredPipelines|optionalPipelines):\(\)Ljava\/util\/List;\n\s+\d+: (iconst_[01])\n\s+\d+: invokestatic .*BrowserPipelineWarmup\.eager/g)];
  assert.deepEqual(lists.map((m) => [m[1], m[2]]), [["requiredPipelines", "iconst_1"], ["optionalPipelines", "iconst_0"]]);

  const cache = javap("com.mojang.blaze3d.pipeline.PipelineCache");
  assert.match(cache, /implements java\.lang\.AutoCloseable,dev\.gaius\.browser\.PipelineWarmTarget/);
  assert.equal(count(cache, "BrowserPipelineWarmup.onMiss:(Ljava/lang/Object;)V"), 1);
  assert.match(cache, /public java\.lang\.Object gaiusWarm\(java\.lang\.Object\);/);
  const getBody = cache.slice(cache.indexOf("CompiledRenderPipeline get("), cache.indexOf("public void clear"));
  assert.ok(getBody.indexOf("ifnull") < getBody.indexOf("BrowserPipelineWarmup.onMiss")
    && getBody.indexOf("BrowserPipelineWarmup.onMiss") < getBody.indexOf("compilePipeline"), "onMiss sits on the miss path before the compile");
  console.log("patch shape OK");

  // 2. Runtime class and JVM test over the patched classes.
  const classpathFile = (await readFile(join(work, "classpath.txt"), "utf8")).trim();
  const libraries = classpathFile.split(delimiter).filter(Boolean).map((entry) => {
    const index = entry.indexOf("/port/work/26.3/");
    return index < 0 ? entry : join(work, entry.slice(index + "/port/work/26.3/".length));
  });
  const runtimeOut = join(scratch, "runtime");
  await mkdir(runtimeOut, {recursive: true});
  const sources = [
    join(root, "port/src/main/java/dev/gaius/browser/PipelineWarmTarget.java"),
    join(root, "port/src/main/java/dev/gaius/browser/BrowserPipelineWarmup.java"),
    join(root, "port/tests/shader-warmup/ShaderPipelineWarmupJvmTest.java"),
  ];
  const classpath = [patched, clientJar, ...libraries, ...teavm];
  run(tool("javac"), ["-d", runtimeOut, "-cp", classpath.join(delimiter), ...sources]);
  const out = run(tool("java"), ["-Xverify:all", "-cp", [runtimeOut, ...classpath].join(delimiter),
    "dev.gaius.browser.ShaderPipelineWarmupJvmTest"]);
  assert.match(out, /ShaderPipelineWarmupJvmTest OK/);
  console.log(out.trim().split("\n").pop());
  console.log("shader-pipeline-warmup-smoke OK");
} finally {
  if (keep) console.log(`kept ${scratch}`);
  else await rm(scratch, {recursive: true, force: true});
}
