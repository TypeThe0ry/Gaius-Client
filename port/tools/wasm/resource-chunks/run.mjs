// Offline small TeaVM regression, run it through your slot wrapper if you use one.
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync, copyFileSync} from "node:fs";
import {homedir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../../..");
const outIndex = process.argv.indexOf("--out");
if (outIndex < 0) throw new Error("--out DIR required");
const out = resolve(process.argv[outIndex + 1]);
const repo = process.env.MAVEN_REPOSITORY || join(homedir(), ".m2/repository");
const version = "0.15.0";
const jar = a => join(repo, "org/teavm", a, version, `${a}-${version}.jar`);
const core = jar("teavm-core");
const deps = readdirSync(join(repo, "org/teavm"))
  .map(a => jar(a)).filter(p => existsSync(p) && p !== core);
deps.push(join(repo, "com/jcraft/jzlib/1.1.3/jzlib-1.1.3.jar"),
  join(repo, "joda-time/joda-time/2.12.2/joda-time-2.12.2.jar"));
const asm = ["asm", "asm-tree", "asm-util", "asm-analysis"].map(a => join(repo, "org/ow2/asm", a, "9.8", `${a}-9.8.jar`));
for (const path of [core, ...deps, ...asm]) {
  if (!existsSync(path)) throw new Error(`Missing offline dependency: ${path}`);
}
const tools = join(out, "tools"), overlay = join(out, "overlay"), classes = join(out, "classes");
const temp = join(out,"tmp");
for (const dir of [out, tools, overlay, classes, temp]) mkdirSync(dir, {recursive:true});
process.env.TMP = temp;
process.env.TEMP = temp;
process.env.TMPDIR = temp;
function run(command, args, log) {
  if (command === "javac" || command === "jar") args = ["-J-Xmx3g", ...args];
  if (command === "java") args = [`-Djava.io.tmpdir=${temp}`, ...args];
  const result = spawnSync(command, args, {cwd:out, encoding:"utf8", maxBuffer:32*1024*1024});
  const text = (result.stdout || "") + (result.stderr || "");
  if (log) writeFileSync(join(out, log), text);
  process.stdout.write(text);
  if (result.status !== 0) throw new Error(`${command} failed: ${result.status} ${result.error || ""}`);
  return text;
}
const cp = entries => entries.join(delimiter);
run("javac", ["--release", "21", "-proc:none", "-cp", cp([core,...deps,...asm]),
  "-sourcepath", join(root,"port/tools/src/main/java"), "-d", tools,
  join(root,"port/tools/src/main/java/dev/gaius/tools/TeaVMCoreBrowserPatcher.java"),
  join(here,"ResourceBaseline.java")], "javac-patcher.log");
run("java", ["-Xmx3g", "-Dgaius.teavm.target=wasmgc", "-cp", cp([tools,core,...deps,...asm]),
  "dev.gaius.tools.TeaVMCoreBrowserPatcher", core,
  join(overlay,"org/teavm/backend/javascript/intrinsics/reflection/ClassInfoGenerator.class")],"patch.log");
const patched = join(out,"teavm-core-patched.jar");
copyFileSync(core, patched);
run("jar", ["uf",patched,"-C",overlay,"."],"jar.log");
const baselineOverlay = join(out,"baseline-overlay");
run("java", ["-Xmx3g", "-Dgaius.teavm.target=wasmgc", "-cp", cp([tools,core,...deps,...asm]),
  "dev.gaius.tools.ResourceBaseline", core, baselineOverlay],"baseline-patch.log");
const baseline = join(out,"teavm-core-baseline.jar");
copyFileSync(patched,baseline);
run("jar", ["uf",baseline,"-C",baselineOverlay,"."],"baseline-jar.log");
run("javac", ["--release","21","-proc:none","-cp",cp([core,...deps,...asm]),"-d",classes,
  join(here,"ResourceMain.java"),join(here,"VerifyPatched.java"),
  join(here,"BrowserFilePersistence.java"),
  join(root,"port/src/main/java/dev/gaius/browser/MinecraftResourceSupplier.java"),
  join(root,"port/tests/classlib/driver/ClasslibBuild.java")],"javac-test.log");
run("java", ["-Xmx3g","-Xverify:all","-cp",cp([classes,patched,...deps,...asm]),
  "dev.gaius.reslimit.VerifyPatched",overlay],"verify.log");
const list=[];
mkdirSync(join(classes,"dummy"),{recursive:true});
for (let i=0;i<12001;i++) {
  const name=`dummy/${i}`;
  list.push(name);
  writeFileSync(join(classes,name),Buffer.from([i%251]));
}
for (const name of ["minecraft-resources.txt","minecraft-embedded-resources.txt"]) {
  mkdirSync(join(classes,"dev/gaius/browser"),{recursive:true});
  writeFileSync(join(classes,"dev/gaius/browser",name),list.join("\n")+"\n");
}
mkdirSync(join(classes,"META-INF/services"),{recursive:true});
mkdirSync(join(classes,"assets/minecraft/lang"),{recursive:true});
for (const name of ["deprecated.json", "en_us.json"]) {
  writeFileSync(join(classes,"assets/minecraft/lang",name),"{}\n");
}
writeFileSync(join(classes,"META-INF/services/org.teavm.classlib.ResourceSupplier"),
  "dev.gaius.browser.MinecraftResourceSupplier\n");
for (const mode of ["stock","baseline","patched"]) {
  const dir=join(out,mode); mkdirSync(dir,{recursive:true});
  run("java",["-Xmx3g","-Xverify:all","-cp",cp([classes,mode==="stock"?core:mode==="baseline"?baseline:patched,...deps]),
    "dev.gaius.classlib.driver.ClasslibBuild","wasm",dir,"dev.gaius.reslimit.ResourceMain",
    "SIMPLE","strict=false"],`${mode}-build.log`);
}
const runtime=join(out,"wasm-gc-runtime.js");
// Read the exact runtime packaged with this TeaVM version.
run("jar",["xf",core,"org/teavm/backend/wasm/wasm-gc-runtime.js"],null);
copyFileSync(join(out,"org/teavm/backend/wasm/wasm-gc-runtime.js"),runtime);
const runner=join(out,"run.cjs");
writeFileSync(runner, `const fs=require("node:fs");
const bytes=fs.readFileSync(process.argv[3]);
if (!WebAssembly.validate(bytes,{builtins:["js-string"]})) throw new Error("validate failed");
console.log("VALIDATE_OK bytes="+bytes.length);
require(process.argv[2]);
const start=performance.now();
TeaVM.wasmGC.load(process.argv[3],{nodejs:true}).then(t=>{
  const loaded=performance.now();
  t.exports.main([]);
  console.log("INSTANTIATE_READ_OK loadMillis="+(loaded-start).toFixed(3)+
    " readMillis="+(performance.now()-loaded).toFixed(3));
}).catch(e=>{console.error(e);process.exitCode=1;});
`);
const stockBytes=readFileSync(join(out,"stock/classes.wasm"));
for (const mode of ["stock","baseline"]) {
  const bytes=readFileSync(join(out,mode,"classes.wasm"));
  if (WebAssembly.validate(bytes,{builtins:["js-string"]})) throw new Error(`${mode} positive control unexpectedly valid`);
  try { await WebAssembly.compile(bytes,{builtins:["js-string"]}); }
  catch (e) {
    if (!/Requested length.*array.new_fixed.*maximum is 10000/.test(e.message)) throw e;
    writeFileSync(join(out,`${mode}-v8.log`),e.message+"\n");
    console.log(mode.toUpperCase()+"_LIMIT_REPRO "+e.message);
  }
}
const execution = run("node",[runner,runtime,join(out,"patched/classes.wasm")],"node.log");
if (!execution.includes("RESOURCE_CHUNKS_OK resources=12001")) {
  throw new Error("Java program did not finish checking every resource");
}
run("node",[join(root,"port/tools/wasm/wasm-check.mjs"),"--json",join(out,"gate.json"),
  join(out,"patched/classes.wasm")],"gate.log");
const baselineBytes=readFileSync(join(out,"baseline/classes.wasm")).length;
const patchedBytes=readFileSync(join(out,"patched/classes.wasm")).length;
writeFileSync(join(out,"measurements.json"), JSON.stringify({resources:12001,
  stockBytes:stockBytes.length, baselineBytes, patchedBytes,
  deltaBytes:patchedBytes-baselineBytes, stockDeltaBytes:patchedBytes-stockBytes.length,
  execution:execution.trim().split("\n")},null,2)+"\n");
console.log("RESOURCE_REGRESSION_OK stockBytes="+stockBytes.length+" patchedBytes="+patchedBytes+
  " baselineBytes="+baselineBytes+" resourceDeltaBytes="+(patchedBytes-baselineBytes));
