#!/usr/bin/env node
// JVM and javap smoke of the browser SDL3 shim (Minecraft 26.3, work package P2).
//
//   node port/scripts/lwjgl-sdl-browser-patcher-smoke.mjs [--keep]
//
// Inputs: port/work/26.3 (client-named.jar, classpath.txt, libraries/) and, for the 26.2
// half of the MinecraftClientPatcher check, port/work/26.2; ASM 9.8 and TeaVM (config.json
// teaVMVersion) in the Maven repository (GAIUS_MAVEN_REPOSITORY or ~/.m2/repository); a JDK
// >= 21 (GAIUS_JAVA_HOME, JAVA_HOME or PATH).
//
// 1. Builds the lwjgl-sdl 3.4.3 overlay the way build-overlays.sh does (vanilla jar + the
//    port/overrides/libraries/lwjgl-sdl classes) and runs LwjglSdlBrowserPatcher on it, with the
//    entry table of port/mc/profiles/26.3/usage.lock.
// 2. javap: SDL.<clinit> no longer calls Library.loadNative; each SDL_* entry point of the usage
//    lock is exactly "load arguments, invokestatic BrowserSdl.<same name and descriptor>,
//    return", or, when BrowserSdl lacks it, a FEATURE stub "throw BrowserSdlUnsupported.entry(..)".
// 3. The patcher fails closed without the overlay and when BrowserSdl lacks an entry point a seam
//    requires; an overlay with only the required entry points gets FEATURE stubs for the rest.
// 4. BrowserSdlJvmTest (JVM unit test): SDL_Event field offsets and widths against the
//    lwjgl-sdl 3.4.3 constants, poll/flush/keyboard-state through the patched classes.
// 5. Every @JSBody script of the overlay and of dev.gaius.browser.BrowserInput{Dispatch,
//    Telemetry} parses with TeaVM's own JavaScript parser (TeaVmJsBodyCheck).
// 6. The input group of Minecraft263BrowserPatcher (InputPatches263.*) on the vanilla 26.3
//    client: SDL coverage check, eight runNow sites, MouseHandler.onButton hooks with the 26.3
//    locals, Blaze3D.openUri, SdlDebug; the patched classes load and initialize under
//    -Xverify:all.
// 7. MinecraftClientPatcher's P2 methods: dropped (targets asserted absent, nothing written)
//    on 26.3, unchanged output on 26.2.

import {CANONICAL_PROFILE, RENDERPEARL_PROFILE, escapeRegExp, profileIdPattern} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {existsSync} from "node:fs";
import {copyFile, mkdir, mkdtemp, readdir, readFile, rm, writeFile} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const keep = process.argv.includes("--keep");
const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value).trim();
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const config = JSON.parse(await readFile(join(repositoryRoot, "port/config.json"), "utf8"));
const teavmVersion = config.teaVMVersion;
const mavenRepository = process.env.GAIUS_MAVEN_REPOSITORY
  ? nativePath(process.env.GAIUS_MAVEN_REPOSITORY) : join(homedir(), ".m2/repository");
const asmJars = ["asm", "asm-tree"].map((name) => join(mavenRepository, `org/ow2/asm/${name}/9.8/${name}-9.8.jar`));
const teavmJar = (artifact) => join(mavenRepository, `org/teavm/${artifact}/${teavmVersion}/${artifact}-${teavmVersion}.jar`);
const teavmApiJars = ["teavm-interop", "teavm-jso", "teavm-jso-apis", "teavm-platform"].map(teavmJar);
const teavmParserJars = [teavmJar("teavm-core"), teavmJar("teavm-relocated-libs-rhino")];

function jdkTool(name) {
  const homes = [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean).map(nativePath);
  for (const home of [...new Set(homes)]) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}
const java = jdkTool("java");
const javac = jdkTool("javac");
const jarTool = jdkTool("jar");
const javap = jdkTool("javap");

const work263 = join(repositoryRoot, "port/work/" + RENDERPEARL_PROFILE.id + "");
const work262 = join(repositoryRoot, "port/work/" + CANONICAL_PROFILE.id + "");
const client263 = join(work263, "client-named.jar");
const client262 = join(work262, "client-named.jar");
const lwjglRoot = join(work263, "libraries/org/lwjgl");
const sdlJar = join(lwjglRoot, "lwjgl-sdl/3.4.3/lwjgl-sdl-3.4.3.jar");
const lwjglJar = join(lwjglRoot, "lwjgl/3.4.3/lwjgl-3.4.3.jar");
const vulkanJar = join(lwjglRoot, "lwjgl-vulkan/3.4.3/lwjgl-vulkan-3.4.3.jar");
for (const required of [...asmJars, ...teavmApiJars, ...teavmParserJars, client263, sdlJar, lwjglJar, vulkanJar]) {
  assert.ok(existsSync(required), `missing input ${required}`);
}
const lwjglNatives = (await readdir(join(lwjglRoot, "lwjgl/3.4.3")))
  .filter((name) => name.includes("-natives-")).map((name) => join(lwjglRoot, "lwjgl/3.4.3", name));
const classpath263 = (await readFile(join(work263, "classpath.txt"), "utf8"))
  .split(/[;:](?=[A-Za-z]:|\/)/).map((entry) => nativePath(entry)).filter(Boolean);
const libraries263 = (await readdir(join(work263, "libraries"), {recursive: true}))
  .filter((entry) => entry.endsWith(".jar") && !entry.includes("-natives-"))
  .map((entry) => join(work263, "libraries", entry)).sort();

const overlaySources = join(repositoryRoot, "port/overrides/libraries/lwjgl-sdl/src/main/java/org/lwjgl/sdl");
const overlayTestSources = join(repositoryRoot, "port/overrides/libraries/lwjgl-sdl/src/test/java/org/lwjgl/sdl");
const hostLoopSource = join(repositoryRoot, "port/overrides/classlib/src/main/java/dev/gaius/browser/HostLoop.java");
const clipboardSource = join(repositoryRoot, "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserClipboard.java");
const toolsRoot = join(repositoryRoot, "port/tools/src/main/java");
// The entry table: the SDL_* methods of the lwjgl-sdl section of the usage lock.
const usageLock = join(repositoryRoot, "port/mc/profiles/" + RENDERPEARL_PROFILE.id + "/usage.lock");
const entries = [];
let inSdlSection = false;
for (const line of (await readFile(usageLock, "utf8")).split(/\r?\n/)) {
  if (line.startsWith("[")) {
    inSdlSection = line.startsWith("[library org.lwjgl:lwjgl-sdl:");
    continue;
  }
  const match = inSdlSection && /^M org\/lwjgl\/sdl\/(SDL\w*)\.(SDL_\w+)(\(.*)$/.exec(line.trim());
  if (match) entries.push({owner: match[1], name: match[2], desc: match[3]});
}
const entryCount = entries.length;
assert.ok(entryCount > 0, `no SDL entry point in ${usageLock}`);
const seam = JSON.parse(await readFile(join(repositoryRoot, "port/mc/seams/renderpearl-1/seam.json"), "utf8"));
const requiredEntries = seam.requiredEntries.filter((entry) => entry.startsWith("org/lwjgl/sdl/"));
assert.ok(requiredEntries.length > 0, "the render seam requires no SDL entry point");

const work = await mkdtemp(join(tmpdir(), "gaius-sdl-"));
function run(command, args, options = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout: 300_000, ...options});
  if (result.error) throw result.error;
  return {status: result.status, out: result.stdout || "", err: result.stderr || ""};
}
function mustRun(command, args, options = {}) {
  const result = run(command, args, options);
  assert.equal(result.status, 0, `${command} ${args.slice(0, 4).join(" ")} failed:\n${result.out}\n${result.err}`);
  return result;
}
async function javacArgfile(name, classpath, output, sources, extra = []) {
  const argfile = join(work, `${name}.args`);
  const quote = (value) => `"${String(value).replaceAll("\\", "/")}"`;
  await writeFile(argfile, [
    ...extra, "-encoding", "UTF-8", "-nowarn", "-classpath", quote(classpath.join(delimiter)), "-d", quote(output),
    ...sources.map(quote),
  ].join("\n"));
  await mkdir(output, {recursive: true});
  mustRun(javac, [`@${argfile}`]);
}
async function javaFiles(directory) {
  return (await readdir(directory)).filter((name) => name.endsWith(".java")).map((name) => join(directory, name));
}
const javapCode = (classpath, className) => execFileSync(javap, ["-classpath", classpath.join(delimiter), "-c", "-p", className],
  {encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout: 120_000});

/** Instruction lines of one method in `javap -c -p` output. */
function methodCode(dump, signaturePattern) {
  const lines = dump.split(/\r?\n/);
  const start = lines.findIndex((line) => signaturePattern.test(line));
  assert.ok(start >= 0, `method ${signaturePattern} not found`);
  const code = [];
  for (let index = start + 1; index < lines.length; index++) {
    const line = lines[index];
    if (/^\s*$/.test(line) || /^  \S/.test(line) || /^}/.test(line)) break;
    if (/^\s+\d+: /.test(line)) code.push(line.trim().replace(/^\d+: /, ""));
  }
  return code;
}
const javaName = (name) => name.replaceAll("/", ".");
function javapMethodPattern(name, desc) {
  // javap prints Java types; match on the name and the parameter count is enough because
  // overloads (SDL_free) are told apart by their parameter type text.
  const params = desc.slice(1, desc.indexOf(")"));
  const types = [];
  for (let index = 0; index < params.length;) {
    let dims = "";
    while (params[index] === "[") { dims += "[]"; index++; }
    const code = params[index];
    if (code === "L") {
      const end = params.indexOf(";", index);
      types.push(javaName(params.slice(index + 1, end)) + dims);
      index = end + 1;
    } else {
      types.push({Z: "boolean", B: "byte", C: "char", S: "short", I: "int", J: "long", F: "float", D: "double"}[code] + dims);
      index++;
    }
  }
  const escaped = `${name}(${types.join(", ")})`.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`^  public static .* ${escaped};$`);
}

try {
  // ---------------------------------------------------------------- tools
  const toolClasses = join(work, "tools");
  const toolSources = (await readdir(toolsRoot, {recursive: true}))
    .filter((entry) => entry.endsWith(".java")).map((entry) => join(toolsRoot, entry));
  await javacArgfile("tools", [...asmJars, teavmJar("teavm-core")], toolClasses, toolSources, ["-sourcepath", `"${toolsRoot.replaceAll("\\", "/")}"`]);
  const toolClasspath = [toolClasses, ...asmJars];

  // ---------------------------------------------------------------- 1. overlay + patcher
  const overlayClasses = join(work, "overlay");
  // BrowserSdlDom reaches the host event loop through dev.gaius.browser.HostLoop of the
  // classlib overlay (its JavaScript implementation; build-overlays.sh puts the classlib overlay
  // classes on the library overlay class path), and SDL_Init installs the clipboard bridge of
  // dev.gaius.browser.BrowserClipboard.
  await javacArgfile("overlay", [sdlJar, client263, ...classpath263, ...teavmApiJars], overlayClasses,
    [...await javaFiles(overlaySources), hostLoopSource, clipboardSource], ["--release", "21", "-proc:none"]);
  const patchedJar = join(work, "lwjgl-sdl-3.4.3.jar");
  await copyFile(sdlJar, patchedJar);
  mustRun(jarTool, ["--update", "--file", patchedJar, "-C", overlayClasses, "."]);
  const patchDir = join(work, "patches");
  const patched = mustRun(java, ["-classpath", toolClasspath.join(delimiter), "dev.gaius.tools.LwjglSdlBrowserPatcher",
    patchedJar, patchDir, usageLock]);
  const stubbed = new Set([...patched.out.matchAll(/^SDL_FEATURE_STUB (\S+)$/gm)].map((match) => match[1]));
  assert.match(patched.out, new RegExp(`redirected ${entryCount - stubbed.size} SDL entry points to \\S+ `
    + `and stubbed ${stubbed.size} \\(FEATURE\\) of the ${entryCount} `));
  mustRun(jarTool, ["--update", "--file", patchedJar, "-C", patchDir, "."]);

  // ---------------------------------------------------------------- 2. javap assertions
  const sdlInit = methodCode(javapCode([patchedJar], "org.lwjgl.sdl.SDL"), /^  static \{\};$/);
  assert.deepEqual(sdlInit.map((line) => line.split(/\s+/)[0]), ["aconst_null", "putstatic", "return"]);
  assert.ok(!sdlInit.join("\n").includes("loadNative"), "SDL.<clinit> still loads a native library");
  const dumps = new Map();
  let redirected = 0;
  const assertStub = (dump, entry) => {
    const code = methodCode(dump, javapMethodPattern(entry.name, entry.desc));
    assert.deepEqual(code.map((line) => line.split(/\s+/)[0]), ["ldc", "invokestatic", "athrow"],
      `${entry.owner}.${entry.name} is not a FEATURE stub`);
    assert.ok(code[0].endsWith(`// String ${entry.owner}.${entry.name}${entry.desc}`), code[0]);
    assert.ok(code[1].endsWith("// Method org/lwjgl/sdl/BrowserSdlUnsupported.entry:"
      + "(Ljava/lang/String;)Ljava/lang/UnsupportedOperationException;"), code[1]);
  };
  for (const entry of entries) {
    const className = `org.lwjgl.sdl.${entry.owner}`;
    if (!dumps.has(className)) dumps.set(className, javapCode([patchedJar], className));
    if (stubbed.has(`${entry.owner}.${entry.name}${entry.desc}`)) {
      assertStub(dumps.get(className), entry);
      continue;
    }
    const code = methodCode(dumps.get(className), javapMethodPattern(entry.name, entry.desc));
    const calls = code.filter((line) => /^invoke/.test(line));
    assert.equal(calls.length, 1, `${entry.owner}.${entry.name} makes ${calls.length} calls`);
    assert.ok(calls[0].startsWith("invokestatic") && calls[0].endsWith(
      `// Method org/lwjgl/sdl/BrowserSdl.${entry.name}:${entry.desc}`),
      `${entry.owner}.${entry.name} does not delegate to BrowserSdl: ${calls[0]}`);
    assert.ok(/return$/.test(code.at(-1)), `${entry.owner}.${entry.name} does not return after the call`);
    assert.ok(!code.some((line) => /JNI|Functions|getstatic/.test(line)), `${entry.owner}.${entry.name} keeps native code`);
    redirected++;
  }
  assert.equal(redirected + stubbed.size, entryCount);
  console.log(`lwjgl-sdl-browser-patcher-smoke: ${redirected} SDL entry points delegate to BrowserSdl, `
    + `${stubbed.size} are FEATURE stubs; SDL.<clinit> loads nothing`);

  // ---------------------------------------------------------------- 3. fail-closed patcher
  let failure = run(java, ["-classpath", toolClasspath.join(delimiter), "dev.gaius.tools.LwjglSdlBrowserPatcher",
    sdlJar, join(work, "patches-no-overlay"), usageLock]);
  assert.notEqual(failure.status, 0, "the patcher accepted a jar without the BrowserSdl overlay");
  assert.match(failure.err, /org\/lwjgl\/sdl\/BrowserSdl\.class not found/);
  const stubSource = join(work, "stub/org/lwjgl/sdl/BrowserSdl.java");
  await mkdir(join(work, "stub/org/lwjgl/sdl"), {recursive: true});
  await writeFile(stubSource, "package org.lwjgl.sdl;\npublic final class BrowserSdl {\n"
    + "  public static boolean SDL_Init(int flags) { return true; }\n}\n");
  await javacArgfile("stub", [sdlJar], join(work, "stub-classes"), [stubSource], ["--release", "21"]);
  const stubJar = join(work, "stub.jar");
  await copyFile(sdlJar, stubJar);
  mustRun(jarTool, ["--update", "--file", stubJar, "-C", join(work, "stub-classes"), "."]);
  failure = run(java, ["-classpath", toolClasspath.join(delimiter), "dev.gaius.tools.LwjglSdlBrowserPatcher",
    stubJar, join(work, "patches-stub"), usageLock]);
  assert.notEqual(failure.status, 0, "the patcher accepted an overlay without the SDL entry points the seam requires");
  assert.match(failure.err, new RegExp(String.raw`REQUIRED SDL entry points of the ${profileIdPattern(RENDERPEARL_PROFILE)} seams are not redirected: .*SDLVideo\.SDL_CreateWindow \(org\/lwjgl\/sdl\/BrowserSdl does not implement it\)`));
  // An overlay with only the required entry points: FEATURE stubs for the rest, which need
  // BrowserSdlUnsupported.
  const requiredSource = join(work, "required/org/lwjgl/sdl/BrowserSdl.java");
  await mkdir(join(work, "required/org/lwjgl/sdl"), {recursive: true});
  const requiredMethods = entries.filter((entry) => requiredEntries.includes(`org/lwjgl/sdl/${entry.owner}.${entry.name}`));
  assert.equal(requiredMethods.length, requiredEntries.length, "a required SDL entry point is not in the usage lock");
  const javaType = (descriptor) => ({J: "long", Z: "boolean", I: "int", V: "void"}[descriptor]
    || (descriptor === "Ljava/lang/CharSequence;" ? "CharSequence" : null));
  const requiredStubs = requiredMethods.map((entry) => {
    const params = [...entry.desc.slice(1, entry.desc.indexOf(")")).matchAll(/L[^;]+;|[ZBCSIJFD]/g)].map((m) => m[0]);
    const result = entry.desc.slice(entry.desc.indexOf(")") + 1);
    assert.ok([...params, result].every(javaType), `unexpected descriptor ${entry.desc}`);
    const value = {long: "0L", boolean: "true", int: "0", void: ""}[javaType(result)];
    return `  public static ${javaType(result)} ${entry.name}(`
      + `${params.map((param, index) => `${javaType(param)} a${index}`).join(", ")}) { return ${value}; }`;
  });
  await writeFile(requiredSource, `package org.lwjgl.sdl;\npublic final class BrowserSdl {\n${requiredStubs.join("\n")}\n}\n`);
  await javacArgfile("required", [sdlJar], join(work, "required-classes"), [requiredSource], ["--release", "21"]);
  const requiredJar = join(work, "required.jar");
  await copyFile(sdlJar, requiredJar);
  mustRun(jarTool, ["--update", "--file", requiredJar, "-C", join(work, "required-classes"), "."]);
  failure = run(java, ["-classpath", toolClasspath.join(delimiter), "dev.gaius.tools.LwjglSdlBrowserPatcher",
    requiredJar, join(work, "patches-required"), usageLock]);
  assert.notEqual(failure.status, 0, "the patcher wrote FEATURE stubs without BrowserSdlUnsupported");
  assert.match(failure.err, /org\/lwjgl\/sdl\/BrowserSdlUnsupported\.class not found/);
  await javacArgfile("unsupported", [sdlJar, ...teavmApiJars], join(work, "required-classes"),
    [join(overlaySources, "BrowserSdlUnsupported.java")], ["--release", "21", "-proc:none"]);
  mustRun(jarTool, ["--update", "--file", requiredJar, "-C", join(work, "required-classes"), "."]);
  const stubPatches = join(work, "patches-required-ok");
  const stubRun = mustRun(java, ["-classpath", toolClasspath.join(delimiter), "dev.gaius.tools.LwjglSdlBrowserPatcher",
    requiredJar, stubPatches, usageLock]);
  const stubCount = (stubRun.out.match(/^SDL_FEATURE_STUB /gm) || []).length;
  assert.equal(stubCount, entryCount - requiredMethods.length, "every entry point but the required ones is a stub");
  mustRun(jarTool, ["--update", "--file", requiredJar, "-C", stubPatches, "."]);
  const stubbedEntry = entries.find((entry) => entry.name === "SDL_GetClipboardText");
  assertStub(javapCode([requiredJar], `org.lwjgl.sdl.${stubbedEntry.owner}`), stubbedEntry);
  console.log("lwjgl-sdl-browser-patcher-smoke: the patcher fails closed without the seam's required entry points; "
    + `${stubCount} FEATURE stubs otherwise`);

  // ---------------------------------------------------------------- 4. JVM unit test
  const testClasses = join(work, "test-classes");
  const testClasspath = [patchedJar, lwjglJar, vulkanJar, ...teavmApiJars, ...teavmParserJars, ...asmJars];
  await javacArgfile("tests", testClasspath, testClasses, await javaFiles(overlayTestSources), ["--release", "21", "-proc:none"]);
  const jvmTest = mustRun(java, ["--enable-native-access=ALL-UNNAMED", "-Xverify:all", "-classpath",
    [testClasses, ...testClasspath, ...lwjglNatives].join(delimiter), "org.lwjgl.sdl.BrowserSdlJvmTest"]);
  assert.match(jvmTest.out, /BrowserSdlJvmTest passed: \d+ checks/);
  console.log(`lwjgl-sdl-browser-patcher-smoke: ${jvmTest.out.trim().split(/\r?\n/).at(-1)}`);

  // ---------------------------------------------------------------- 5. @JSBody scripts parse with TeaVM
  const helperClasses = join(work, "input-helpers");
  await javacArgfile("helpers", [client263, ...teavmApiJars], helperClasses, [
    join(repositoryRoot, "port/src/main/java/dev/gaius/browser/BrowserInputDispatch.java"),
    join(repositoryRoot, "port/src/main/java/dev/gaius/browser/BrowserInputTelemetry.java"),
  ], ["--release", "21", "-proc:none"]);
  const scripts = mustRun(java, ["-classpath", [testClasses, ...teavmParserJars, ...asmJars].join(delimiter),
    "org.lwjgl.sdl.TeaVmJsBodyCheck", overlayClasses, helperClasses]);
  assert.match(scripts.out, /TeaVmJsBodyCheck: \d+ scripts, 0 with errors/);
  assert.match(scripts.out, /JSBODY_OK org\/lwjgl\/sdl\/BrowserSdlDom\.installDomBridge\(\)V/);
  assert.match(scripts.out, /JSBODY_OK dev\/gaius\/browser\/BrowserInputTelemetry\.reportMouseHandlerEntry\(JJII\)V/);
  console.log(`lwjgl-sdl-browser-patcher-smoke: ${scripts.out.trim().split(/\r?\n/).at(-1)}`);

  // ---------------------------------------------------------------- 6. the 26.3 input group
  const driverSource = join(work, "driver/P2SmokeDriver.java");
  await mkdir(join(work, "driver"), {recursive: true});
  await writeFile(driverSource, String.raw`
import dev.gaius.tools.Minecraft263BrowserPatcher;
import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import java.lang.reflect.Method;
import java.nio.file.Path;

public final class P2SmokeDriver {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String jar = args[1];
        Path out = Path.of(args[2]);
        if (mode.equals("m263")) {
            PatchRegistry.configureProfile("${RENDERPEARL_PROFILE.id}");
            Minecraft263BrowserPatcher.inputPatches(jar, out, ModernSymbols.probe(jar));
            PatchRegistry.printSummary();
        } else if (mode.equals("mcp")) {
            PatchRegistry.configureProfile(args[3]);
            Class<?> input = Class.forName("dev.gaius.tools.patches.input.InputPatches");
            Class<?> platform = Class.forName("dev.gaius.tools.patches.platform.PlatformPatches");
            call(input, "patchBrowserInputCallbacks", jar, out);
            call(input, "patchInputConstants", jar, out.resolve("com/mojang/blaze3d/platform/InputConstants.class"));
            call(platform, "patchOpenUri", jar, out.resolve("net/minecraft/util/Util$OS.class"));
            PatchRegistry.printSummary();
        } else if (mode.equals("init")) {
            for (int index = 1; index < args.length; index++) {
                Class.forName(args[index], true, P2SmokeDriver.class.getClassLoader());
                System.out.println("CLASS_INIT_VERIFIED " + args[index]);
            }
        }
    }

    private static void call(Class<?> owner, String name, String jar, Path out) throws Exception {
        Method method = owner.getDeclaredMethod(name, String.class, Path.class);
        method.setAccessible(true);
        method.invoke(null, jar, out);
    }
}
`);
  const driverClasses = join(work, "driver-classes");
  await javacArgfile("driver", toolClasspath, driverClasses, [driverSource]);
  const driverClasspath = [driverClasses, ...toolClasspath];
  const m263Out = join(work, "m263-out");
  const m263 = mustRun(java, ["-classpath", driverClasspath.join(delimiter), `-Dgaius.usage.lock=${usageLock}`,
    "P2SmokeDriver", "m263", client263, m263Out]);
  assert.match(m263.out, new RegExp(`the client references ${entryCount} SDL entry points, all among the ${entryCount}`));
  assert.match(m263.out, /^PATCH_SUMMARY applied=4 dropped=0 bringupSkipped=0$/m);
  const patchedClient = [m263Out, client263];

  const handler = javapCode(patchedClient, "com.mojang.blaze3d.platform.SDLEventHandler");
  const runNow = handler.match(/invokestatic .*dev\/gaius\/browser\/BrowserInputDispatch\.runNow:\(Lnet\/minecraft\/client\/Minecraft;Ljava\/lang\/Runnable;\)V/g) || [];
  assert.equal(runNow.length, 8, "SDLEventHandler must dispatch through runNow at exactly 8 sites");
  assert.ok(!/Minecraft\.execute:\(Ljava\/lang\/Runnable;\)V/.test(handler), "SDLEventHandler still defers input");

  const mouse = methodCode(javapCode(patchedClient, "net.minecraft.client.MouseHandler"),
    /^  public void onButton\(long, net\.minecraft\.client\.input\.MouseButtonInfo, int\);$/).join("\n");
  assert.ok(!mouse.includes("org/lwjgl/glfw"), "MouseHandler still references GLFW");
  const hook = (name, desc) => (mouse.match(new RegExp(`BrowserInputTelemetry\\.${name}:\\(${desc}\\)V`, "g")) || []).length;
  assert.equal(hook("reportMouseHandlerEntry", "JJII"), 1);
  assert.equal(hook("reportMouseHandlerDispatch", "DDZLjava/lang/Object;"), 1);
  assert.equal(hook("reportMouseClickedResult", "ZDDLjava/lang/Object;"), 1);
  assert.match(mouse, /dload +7\ndload +9\niload +6\naload +11\ninvokestatic .*reportMouseHandlerDispatch/,
    "dispatch telemetry must load the " + RENDERPEARL_PROFILE.id + " locals x=7 y=9 pressed=6 screen=11");
  assert.match(mouse, /instanceof .*net\/minecraft\/client\/gui\/screens\/LoadingOverlay/, "LoadingOverlay gate missing");

  const openUri = methodCode(javapCode(patchedClient, "com.mojang.blaze3d.Blaze3D"),
    /^  public static void openUri\(java\.net\.URI\);$/);
  assert.deepEqual(openUri.map((line) => line.split(/\s+/)[0]), ["aload_0", "invokestatic", "return"]);
  assert.match(openUri[1], /BrowserInputDispatch\.openUri:\(Ljava\/net\/URI;\)V/);

  const sdlDebug = javapCode(patchedClient, "com.mojang.blaze3d.platform.SdlDebug");
  const debugInit = methodCode(sdlDebug, /^  static \{\};$/).join("\n");
  assert.ok(!/SDL_LogOutputFunction\.create|HexFormat|invokedynamic/.test(debugInit),
    "SdlDebug.<clinit> still builds the callback");
  assert.match(debugInit, /aconst_null\nputstatic .*CALLBACK:Lorg\/lwjgl\/sdl\/SDL_LogOutputFunction;/,
    "SdlDebug.CALLBACK must be null");
  assert.deepEqual(methodCode(sdlDebug, /^  public static void init\(\);$/), ["return"]);

  const initialized = mustRun(java, ["-Xverify:all", "-classpath",
    [driverClasses, m263Out, helperClasses, client263, ...libraries263, ...teavmApiJars].join(delimiter),
    "P2SmokeDriver", "init",
    "com.mojang.blaze3d.platform.SDLEventHandler", "net.minecraft.client.MouseHandler",
    "com.mojang.blaze3d.Blaze3D", "com.mojang.blaze3d.platform.SdlDebug"]);
  assert.equal((initialized.out.match(/^CLASS_INIT_VERIFIED /gm) || []).length, 4,
    `patched input classes failed JVM verification:\n${initialized.out}\n${initialized.err}`);
  console.log("lwjgl-sdl-browser-patcher-smoke: 26.3 input group output verified (runNow x8, onButton hooks, openUri, SdlDebug)");

  // ---------------------------------------------------------------- 7. MinecraftClientPatcher P2 methods
  const mcp263Out = join(work, "mcp-263");
  const mcp263 = mustRun(java, ["-classpath", driverClasspath.join(delimiter), "P2SmokeDriver", "mcp", client263, mcp263Out, RENDERPEARL_PROFILE.id]);
  for (const id of [
    "MinecraftClientPatcher.patchBrowserMouseHandler",
    "MinecraftClientPatcher.patchBrowserKeyboardHandler",
    "MinecraftClientPatcher.patchInputConstants.glfw",
    "MinecraftClientPatcher.patchOpenUri.utilOs",
  ]) {
    assert.match(mcp263.out, new RegExp(`^PATCH_DROPPED ${escapeRegExp(id)}$`, "m"), `${RENDERPEARL_PROFILE.id} must drop ${id}`);
  }
  assert.match(mcp263.out, /^PATCH_SUMMARY applied=0 dropped=4 bringupSkipped=0$/m);
  assert.ok(!existsSync(mcp263Out), "" + RENDERPEARL_PROFILE.id + " must not write GLFW-era input classes");
  if (existsSync(client262)) {
    const mcp262Out = join(work, "mcp-262");
    const mcp262 = mustRun(java, ["-classpath", driverClasspath.join(delimiter), "P2SmokeDriver", "mcp", client262, mcp262Out, CANONICAL_PROFILE.id]);
    assert.doesNotMatch(mcp262.out, /PATCH_DROPPED/);
    for (const entry of ["net/minecraft/client/MouseHandler.class", "net/minecraft/client/KeyboardHandler.class",
      "com/mojang/blaze3d/platform/InputConstants.class", "net/minecraft/util/Util$OS.class"]) {
      assert.ok(existsSync(join(mcp262Out, entry)), `${CANONICAL_PROFILE.id} no longer writes ${entry}`);
    }
    const mouse262 = javapCode([mcp262Out, client262], "net.minecraft.client.MouseHandler");
    assert.match(mouse262, /org\/lwjgl\/glfw\/BrowserGlfw\.reportMouseHandlerEntry:\(JJII\)V/,
      "" + CANONICAL_PROFILE.id + " keeps the BrowserGlfw telemetry");
    console.log("lwjgl-sdl-browser-patcher-smoke: MinecraftClientPatcher drops its GLFW input patches on " + RENDERPEARL_PROFILE.id + " and keeps " + CANONICAL_PROFILE.id + "");
  } else {
    console.log("lwjgl-sdl-browser-patcher-smoke: port/work/" + CANONICAL_PROFILE.id + " missing, " + CANONICAL_PROFILE.id + " half of step 7 not run");
  }
  console.log("lwjgl-sdl-browser-patcher-smoke: OK");
} finally {
  if (keep) console.log(`lwjgl-sdl-browser-patcher-smoke: kept ${work}`);
  else await rm(work, {recursive: true, force: true});
}
