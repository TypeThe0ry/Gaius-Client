#!/usr/bin/env node

// Probes the official 26.2 and 26.3 client jars with dev.gaius.tools.ModernSymbols and asserts
// the facts the modern patch set relies on (migration plan contract C1): render API package and
// rename table, class-to-interface changes, UncheckedAutoCloseable, authlib flavour, input
// backend, InputConstants key codes and BufferBuilder semantic ids. It also checks that a 26.2
// probe returns every 26.2 name unchanged and that mixed jars are rejected.
//
// Inputs: port/work/26.2 and port/work/26.3 (client-named.jar plus classpath.txt/libraries for
// the authlib jar), ASM 9.8 in ~/.m2, a JDK >= 21 (GAIUS_JAVA_HOME or JAVA_HOME).
import {CANONICAL_PROFILE, LEGACY_PROFILE, RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {access, mkdir, mkdtemp, readdir, readFile, rm, writeFile} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const toolsRoot = join(repositoryRoot, "port/tools/src/main/java");
const toolsPackage = join(toolsRoot, "dev/gaius/tools");
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = join(asmRoot, "asm/9.8/asm-9.8.jar");
const asmTree = join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar");
// The patchers that carry TeaVM WebAssembly GC code compile against teavm-core.
const teavmVersion = JSON.parse(await readFile(join(repositoryRoot, "port/config.json"), "utf8")).teaVMVersion;
const teavmCore = join(homedir(), `.m2/repository/org/teavm/teavm-core/${teavmVersion}/teavm-core-${teavmVersion}.jar`);
const jars = {
  [CANONICAL_PROFILE.id]: join(repositoryRoot, "port/work/" + CANONICAL_PROFILE.id + "/client-named.jar"),
  [RENDERPEARL_PROFILE.id]: join(repositoryRoot, "port/work/" + RENDERPEARL_PROFILE.id + "/client-named.jar"),
};

function jdkTool(name) {
  const homes = [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean).map(nativePath);
  for (const home of [...new Set(homes)]) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

const DRIVER = String.raw`
import dev.gaius.tools.ModernSymbols;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public final class ModernSymbolsSmokeDriver {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void expectThrows(Class<? extends Throwable> type, String contains, Runnable body) {
        try {
            body.run();
        } catch (Throwable thrown) {
            check(type.isInstance(thrown), "expected " + type.getSimpleName() + " but got " + thrown);
            check(thrown.getMessage() != null && thrown.getMessage().contains(contains),
                    "message '" + thrown.getMessage() + "' lacks '" + contains + "'");
            return;
        }
        throw new AssertionError("expected " + type.getSimpleName() + " containing " + contains);
    }

    public static void main(String[] args) throws Exception {
        ModernSymbols s262 = ModernSymbols.probe(args[0]);
        ModernSymbols s263 = ModernSymbols.probe(args[1]);
        Path work = Path.of(args[2]);

        String buffer = "com/mojang/blaze3d/buffers/GpuBuffer";
        check(s262.renderType(buffer).equals(buffer), "${CANONICAL_PROFILE.id} GpuBuffer renamed");
        check(s263.renderType(buffer).equals("com/mojang/renderpearl/api/buffers/GpuBuffer"),
                "${RENDERPEARL_PROFILE.id} GpuBuffer");
        check(s262.invokeOpcode(buffer) == Opcodes.INVOKEVIRTUAL, "${CANONICAL_PROFILE.id} GpuBuffer opcode");
        check(s263.invokeOpcode(buffer) == Opcodes.INVOKEINTERFACE, "${RENDERPEARL_PROFILE.id} GpuBuffer opcode");
        check(!s262.isInterface("com/mojang/blaze3d/systems/GpuDevice"), "${CANONICAL_PROFILE.id} GpuDevice kind");
        check(s263.isInterface("com/mojang/blaze3d/systems/GpuDevice"), "${RENDERPEARL_PROFILE.id} GpuDevice kind");
        // Nested classes follow their outer class.
        check(s263.renderType("com/mojang/blaze3d/opengl/GlBuffer$Direct$1")
                .equals("com/mojang/renderpearl/backend/opengl/GlBuffer$Direct$1"), "nested rename");
        check(s262.renderType("com/mojang/blaze3d/opengl/GlBuffer$Direct$1")
                .equals("com/mojang/blaze3d/opengl/GlBuffer$Direct$1"), "nested ${CANONICAL_PROFILE.id}");
        check(!s263.renderTypePresent("com/mojang/blaze3d/opengl/GlDevice$ShaderCompilationKey"),
                "${RENDERPEARL_PROFILE.id} has no GlDevice$ShaderCompilationKey");
        // Descriptors: moved classes are renamed, blaze3d classes kept in place are not.
        String desc = "(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/buffers/GpuBuffer;"
                + "Lcom/mojang/blaze3d/systems/RenderSystem;[Lcom/mojang/blaze3d/vertex/VertexFormatElement;"
                + "Ljava/util/List<Lcom/mojang/blaze3d/buffers/GpuBuffer;>;J)V";
        check(s262.renderDesc(desc).equals(desc), "${CANONICAL_PROFILE.id} descriptor must be unchanged");
        String mapped = s263.renderDesc(desc);
        check(mapped.equals("(Lcom/mojang/renderpearl/api/commands/CommandEncoder;"
                + "Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/blaze3d/systems/RenderSystem;"
                + "[Lcom/mojang/renderpearl/api/vertex/VertexFormatElement;"
                + "Ljava/util/List<Lcom/mojang/renderpearl/api/buffers/GpuBuffer;>;J)V"), mapped);
        // Removed classes.
        check(s263.renderTypeRemoved("com/mojang/blaze3d/platform/GLX"), "GLX removed on ${RENDERPEARL_PROFILE.id}");
        check(!s262.renderTypeRemoved("com/mojang/blaze3d/platform/GLX"), "GLX kept on ${CANONICAL_PROFILE.id}");
        expectThrows(IllegalStateException.class, "removed",
                () -> s263.renderType("com/mojang/blaze3d/platform/GLX"));
        expectThrows(IllegalStateException.class, "removed",
                () -> s263.renderDesc("Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;"));
        expectThrows(IllegalArgumentException.class, "Not a moved ${CANONICAL_PROFILE.id} render class",
                () -> s263.renderType("com/mojang/blaze3d/systems/RenderSystem"));
        // authlib.
        String session = "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService";
        check(s262.authlibType(session).equals(session), "${CANONICAL_PROFILE.id} authlib");
        check(s263.authlibType(session + "$1").equals(
                "com/mojang/authlib/services/MinecraftServicesSessionService$1"), "nested authlib");
        check(s263.authlibType("com/mojang/authlib/GameProfile").equals("com/mojang/authlib/GameProfile"),
                "authlib classes outside yggdrasil stay");
        check(s263.authlibDesc("(Lnet/minecraft/client/User;Lcom/mojang/authlib/yggdrasil/ProfileResult;)V")
                .equals("(Lnet/minecraft/client/User;Lcom/mojang/authlib/services/ProfileResult;)V"),
                "authlib descriptor");
        expectThrows(IllegalStateException.class, "removed in authlib 10",
                () -> s263.authlibType("com/mojang/authlib/yggdrasil/YggdrasilAuthenticationService"));
        expectThrows(IllegalArgumentException.class, "no verified authlib 10 mapping",
                () -> s263.authlibType("com/mojang/authlib/yggdrasil/YggdrasilEnvironment"));
        // Constants.
        check(s262.inputKey("KEY_LCONTROL") == 341 && s263.inputKey("KEY_LCONTROL") == 224, "KEY_LCONTROL");
        check(s262.vertexSemantic("NORMAL") == 5 && s263.vertexSemantic("NORMAL") == 6, "NORMAL id");
        expectThrows(IllegalStateException.class, "UV3_SEMANTIC_ID", () -> s262.vertexSemantic("UV3"));
        expectThrows(IllegalStateException.class, "not a constant", () -> s263.inputKey("KEY_NOPE"));
        check(ModernSymbols.cached(args[1]) == ModernSymbols.cached(args[1]), "cached() memoizes");
        System.out.println("API_OK");

        // A ${RENDERPEARL_PROFILE.id} jar with a ${CANONICAL_PROFILE.id} render class and a GLFW input class is a mixed state.
        Path mixed = work.resolve("mixed.jar");
        try (ZipFile source = new ZipFile(args[1]); ZipFile old = new ZipFile(args[0]);
                ZipOutputStream out = new ZipOutputStream(java.nio.file.Files.newOutputStream(mixed))) {
            for (Enumeration<? extends ZipEntry> it = source.entries(); it.hasMoreElements(); ) {
                ZipEntry entry = it.nextElement();
                if (entry.getName().equals("net/minecraft/client/KeyboardHandler.class")) continue;
                out.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream in = source.getInputStream(entry)) { in.transferTo(out); }
                out.closeEntry();
            }
            for (String name : new String[] {"com/mojang/blaze3d/opengl/GlDevice.class",
                    "net/minecraft/client/KeyboardHandler.class"}) {
                out.putNextEntry(new ZipEntry(name));
                try (InputStream in = old.getInputStream(old.getEntry(name))) { in.transferTo(out); }
                out.closeEntry();
            }
        }
        try {
            ModernSymbols.probe(mixed.toString());
            throw new AssertionError("mixed jar was accepted");
        } catch (IllegalStateException expected) {
            String message = expected.getMessage();
            check(message.contains("com/mojang/blaze3d/opengl/GlDevice is still present"), message);
            check(message.contains("both org/lwjgl/glfw and org/lwjgl/sdl"), message);
        }
        System.out.println("MIXED_REJECTED");

        // A renderpearl jar in which one of the eight class-to-interface types is a class.
        check(ModernSymbols.CLASS_TO_INTERFACE_KEYS.size() == 8, "eight class-to-interface keys");
        for (String key : ModernSymbols.CLASS_TO_INTERFACE_KEYS) {
            check(s263.isInterface(key) && !s262.isInterface(key), "interface kind of " + key);
        }
        String pearlDevice = "com/mojang/renderpearl/api/device/GpuDevice.class";
        Path classDevice = work.resolve("class-device.jar");
        try (ZipFile source = new ZipFile(args[1]);
                ZipOutputStream out = new ZipOutputStream(java.nio.file.Files.newOutputStream(classDevice))) {
            for (Enumeration<? extends ZipEntry> it = source.entries(); it.hasMoreElements(); ) {
                ZipEntry entry = it.nextElement();
                out.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream in = source.getInputStream(entry)) {
                    if (!entry.getName().equals(pearlDevice)) {
                        in.transferTo(out);
                    } else {
                        ClassWriter writer = new ClassWriter(0);
                        new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public void visit(int version, int access, String name, String signature,
                                    String superName, String[] interfaces) {
                                super.visit(version, access & ~Opcodes.ACC_INTERFACE, name, signature,
                                        superName, interfaces);
                            }
                        }, 0);
                        out.write(writer.toByteArray());
                    }
                }
                out.closeEntry();
            }
        }
        try {
            ModernSymbols.probe(classDevice.toString());
            throw new AssertionError("renderpearl jar with a class GpuDevice was accepted");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("renderpearl class com/mojang/renderpearl/api/device/GpuDevice"
                    + " (for com/mojang/blaze3d/systems/GpuDevice) is not an interface"), expected.getMessage());
        }
        System.out.println("CLASS_INTERFACE_MIX_REJECTED");

        // A ${RENDERPEARL_PROFILE.id} client with an authlib 9 jar is a mixed state as well.
        String authlib9 = s262.authlibJar;
        check(authlib9 != null, "${CANONICAL_PROFILE.id} authlib jar not located");
        try {
            ModernSymbols.probe(args[1], authlib9);
            throw new AssertionError("${RENDERPEARL_PROFILE.id} client with authlib 9 was accepted");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("references authlib SERVICES but"), expected.getMessage());
        }
        System.out.println("AUTHLIB_MISMATCH_REJECTED");
    }
}
`;

const javac = jdkTool("javac");
const java = jdkTool("java");
await Promise.all([asm, asmTree, teavmCore, ...Object.values(jars)].map((path) => access(path)));

const work = await mkdtemp(join(tmpdir(), "gaius-modern-symbols-"));
try {
  const classes = join(work, "classes");
  await mkdir(classes, {recursive: true});
  const sources = [
    ...(await readdir(toolsPackage)).filter((name) => name.endsWith(".java"))
      .map((name) => join(toolsPackage, name)),
  ];
  const driverSource = join(work, "ModernSymbolsSmokeDriver.java");
  await writeFile(driverSource, DRIVER);
  const compileClasspath = [asm, asmTree, teavmCore].join(delimiter);
  execFileSync(javac, ["-J-Duser.language=en", "--release", "21", "-proc:none",
    "-classpath", compileClasspath, "-sourcepath", toolsRoot, "-d", classes,
    ...sources, driverSource], {encoding: "utf8", timeout: 120_000});
  const runtimeClasspath = [classes, asm, asmTree].join(delimiter);
  const env = {...process.env};
  for (const name of ["GAIUS_AUTHLIB_JAR", "GAIUS_PROFILE", "GAIUS_BRINGUP", "GAIUS_BRINGUP_LIST"]) {
    delete env[name];
  }
  const runJava = (args) => spawnSync(java, ["-Duser.language=en", "-classpath", runtimeClasspath,
    ...args], {encoding: "utf8", env, timeout: 180_000, maxBuffer: 64 * 1024 * 1024});

  const probes = {};
  for (const [profile, jar] of Object.entries(jars)) {
    const result = runJava(["dev.gaius.tools.ModernSymbols", jar]);
    assert.equal(result.status, 0, `${profile} probe failed:\n${result.stderr}`);
    probes[profile] = JSON.parse(result.stdout);
  }
  const p262 = probes[CANONICAL_PROFILE.id];
  const p263 = probes[RENDERPEARL_PROFILE.id];

  // Render API and the rename table.
  assert.equal(p262.renderApi, "BLAZE3D");
  assert.equal(p263.renderApi, "RENDERPEARL");
  assert.equal(p262.uncheckedAutoCloseable, false);
  assert.equal(p263.uncheckedAutoCloseable, true);
  const keys = Object.keys(p262.renderTypes);
  assert.equal(keys.length, 119, "rename table must cover the 119 top-level classes " + RENDERPEARL_PROFILE.id + " moved or removed");
  assert.deepEqual(Object.keys(p263.renderTypes), keys);
  for (const key of keys) {
    const entry = p262.renderTypes[key];
    assert.equal(entry.name, key, `${CANONICAL_PROFILE.id} probe must return ${key} unchanged`);
    assert.ok(!entry.removed && !entry.absent, `${CANONICAL_PROFILE.id} jar must contain ${key}`);
  }
  const removed263 = keys.filter((key) => p263.renderTypes[key].removed);
  assert.deepEqual(removed263.sort(), [
    "com/mojang/blaze3d/GLFWErrorCapture",
    "com/mojang/blaze3d/GLFWErrorScope",
    "com/mojang/blaze3d/opengl/VertexArrayCache",
    "com/mojang/blaze3d/platform/GLX",
    "com/mojang/blaze3d/preprocessor/GlslPreprocessor",
    "com/mojang/blaze3d/vulkan/VulkanBindGroupLayout",
    "com/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule",
    "com/mojang/blaze3d/vulkan/glsl/SpvSampler",
    "com/mojang/blaze3d/vulkan/glsl/SpvUniformBuffer",
    "com/mojang/blaze3d/vulkan/glsl/SpvVariable",
    "com/mojang/blaze3d/vulkan/glsl/SpvcUtil",
  ]);
  for (const key of keys) {
    const entry = p263.renderTypes[key];
    if (entry.removed) continue;
    assert.ok(!entry.absent, `${RENDERPEARL_PROFILE.id} jar lacks ${entry.name} for ${key}`);
    assert.ok(entry.name.startsWith("com/mojang/renderpearl/"), `${key} -> ${entry.name}`);
  }
  for (const [key, expected] of Object.entries({
    "com/mojang/blaze3d/buffers/GpuBuffer": "com/mojang/renderpearl/api/buffers/GpuBuffer",
    "com/mojang/blaze3d/buffers/GpuFence": "com/mojang/renderpearl/api/commands/GpuFence",
    "com/mojang/blaze3d/systems/GpuDevice": "com/mojang/renderpearl/api/device/GpuDevice",
    "com/mojang/blaze3d/systems/CommandEncoder": "com/mojang/renderpearl/api/commands/CommandEncoder",
    "com/mojang/blaze3d/opengl/GlDevice": "com/mojang/renderpearl/backend/opengl/GlDevice",
    "com/mojang/blaze3d/opengl/GlBuffer": "com/mojang/renderpearl/backend/opengl/GlBuffer",
    "com/mojang/blaze3d/GpuFormat": "com/mojang/renderpearl/api/GpuFormat",
    "com/mojang/blaze3d/vertex/VertexFormat": "com/mojang/renderpearl/api/vertex/VertexFormat",
    "com/mojang/blaze3d/vertex/VertexFormatElement": "com/mojang/renderpearl/api/vertex/VertexFormatElement",
    "com/mojang/blaze3d/vulkan/VulkanBackend": "com/mojang/renderpearl/backend/vulkan/VulkanBackend",
    "com/mojang/blaze3d/vulkan/glsl/GlslCompiler": "com/mojang/renderpearl/frontend/shaders/GlslCompiler",
    "com/mojang/blaze3d/vulkan/glsl/ShaderCompileException": "com/mojang/renderpearl/util/ShaderCompileException",
    "com/mojang/blaze3d/systems/TracyGpuProfiler": "com/mojang/renderpearl/frontend/TracyGpuProfiler",
  })) {
    assert.equal(p263.renderTypes[key].name, expected, key);
  }
  // Exactly eight render classes became interfaces (render-backend notes V.2).
  const becameInterfaces = keys.filter((key) => p262.renderTypes[key].interface === false
    && p263.renderTypes[key].interface === true).sort();
  assert.deepEqual(becameInterfaces, [
    "com/mojang/blaze3d/buffers/GpuBuffer",
    "com/mojang/blaze3d/systems/CommandEncoder",
    "com/mojang/blaze3d/systems/GpuDevice",
    "com/mojang/blaze3d/systems/GpuSurface",
    "com/mojang/blaze3d/systems/RenderPass",
    "com/mojang/blaze3d/textures/GpuSampler",
    "com/mojang/blaze3d/textures/GpuTexture",
    "com/mojang/blaze3d/textures/GpuTextureView",
  ]);
  const becameClasses = keys.filter((key) => p262.renderTypes[key].interface === true
    && p263.renderTypes[key].interface === false);
  assert.deepEqual(becameClasses, [], "no render interface turned into a class");

  // authlib.
  assert.equal(p262.authlib, "YGGDRASIL");
  assert.equal(p263.authlib, "SERVICES");
  assert.match(String(p262.authlibJar).replaceAll("\\", "/"), /\/authlib-9\.[^/]*\.jar$/);
  assert.match(String(p263.authlibJar).replaceAll("\\", "/"), /\/authlib-10\.[^/]*\.jar$/);
  for (const [key, value] of Object.entries(p262.authlibTypes)) assert.equal(value, key);
  assert.deepEqual(p263.authlibTypes, {
    "com/mojang/authlib/yggdrasil/FriendsService": "com/mojang/authlib/services/FriendsService",
    "com/mojang/authlib/yggdrasil/ProfileResult": "com/mojang/authlib/services/ProfileResult",
    "com/mojang/authlib/yggdrasil/ServicesKeyInfo": "com/mojang/authlib/services/ServicesKeyInfo",
    "com/mojang/authlib/yggdrasil/ServicesKeySet": "com/mojang/authlib/services/ServicesKeySet",
    "com/mojang/authlib/yggdrasil/ServicesKeyType": "com/mojang/authlib/services/ServicesKeyType",
    "com/mojang/authlib/yggdrasil/TextureUrlChecker": null,
    "com/mojang/authlib/yggdrasil/YggdrasilAuthenticationService": null,
    "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService":
      "com/mojang/authlib/services/MinecraftServicesSessionService",
    "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo":
      "com/mojang/authlib/services/MinecraftServicesKeyInfo",
    "com/mojang/authlib/yggdrasil/response/MinecraftTexturesPayload":
      "com/mojang/authlib/services/response/MinecraftTexturesPayload",
  });

  // Input backend and key codes (GLFW key codes vs SDL scancodes).
  assert.equal(p262.inputBackend, "GLFW");
  assert.equal(p263.inputBackend, "SDL");
  for (const [name, glfw, sdl] of [
    ["KEY_A", 65, 4], ["KEY_R", 82, 21], ["KEY_LCONTROL", 341, 224], ["KEY_ESCAPE", 256, 41],
    ["KEY_SPACE", 32, 44],
  ]) {
    assert.equal(p262.inputKeys[name], glfw, `${CANONICAL_PROFILE.id} InputConstants.${name}`);
    assert.equal(p263.inputKeys[name], sdl, `${RENDERPEARL_PROFILE.id} InputConstants.${name}`);
  }
  assert.ok(Object.keys(p262.inputKeys).length > 100, "" + CANONICAL_PROFILE.id + " KEY_* constants were not read");
  assert.ok(Object.keys(p263.inputKeys).length > 100, "" + RENDERPEARL_PROFILE.id + " KEY_* constants were not read");
  assert.equal(p262.inputConstants.MOUSE_BUTTON_LEFT, 0);
  assert.equal(p263.inputConstants.MOUSE_BUTTON_LEFT, 1);

  // BufferBuilder semantic ids: 26.3 inserted UV3 and moved NORMAL to 6.
  assert.deepEqual(p262.vertexSemantics,
    {POSITION: 0, COLOR: 1, UV0: 2, UV1: 3, UV2: 4, NORMAL: 5, LINE_WIDTH: 6});
  assert.deepEqual(p263.vertexSemantics,
    {POSITION: 0, COLOR: 1, UV0: 2, UV1: 3, UV2: 4, UV3: 5, NORMAL: 6, LINE_WIDTH: 7});

  // Java API behaviour and mixed-state rejection.
  const driver = runJava(["ModernSymbolsSmokeDriver", jars[CANONICAL_PROFILE.id], jars[RENDERPEARL_PROFILE.id], work]);
  assert.equal(driver.status, 0, `API driver failed:\n${driver.stdout}\n${driver.stderr}`);
  assert.match(driver.stdout, /^API_OK$/m);
  assert.match(driver.stdout, /^MIXED_REJECTED$/m);
  assert.match(driver.stdout, /^CLASS_INTERFACE_MIX_REJECTED$/m);
  assert.match(driver.stdout, /^AUTHLIB_MISMATCH_REJECTED$/m);

  // Older blaze3d generations probe as they are: 1.21.11 already declared CommandEncoder,
  // GpuDevice and RenderPass as interfaces, so the class-to-interface assertion applies only to
  // renderpearl jars.  Optional: needs port/work/1.21.11.
  const jar1211 = join(repositoryRoot, "port/work/" + LEGACY_PROFILE.id + "/client-named.jar");
  if (existsSync(jar1211)) {
    const result = runJava(["dev.gaius.tools.ModernSymbols", jar1211]);
    assert.equal(result.status, 0, `${LEGACY_PROFILE.id} probe failed:\n${result.stderr}`);
    const p1211 = JSON.parse(result.stdout);
    assert.equal(p1211.renderApi, "BLAZE3D");
    assert.deepEqual(Object.keys(p1211.renderTypes).filter((key) => p1211.renderTypes[key].interface)
      .filter((key) => [
        "com/mojang/blaze3d/buffers/GpuBuffer", "com/mojang/blaze3d/systems/CommandEncoder",
        "com/mojang/blaze3d/systems/GpuDevice", "com/mojang/blaze3d/systems/GpuSurface",
        "com/mojang/blaze3d/systems/RenderPass", "com/mojang/blaze3d/textures/GpuSampler",
        "com/mojang/blaze3d/textures/GpuTexture", "com/mojang/blaze3d/textures/GpuTextureView",
      ].includes(key)).sort(), [
      "com/mojang/blaze3d/systems/CommandEncoder",
      "com/mojang/blaze3d/systems/GpuDevice",
      "com/mojang/blaze3d/systems/RenderPass",
    ]);
  } else {
    console.log("modern-symbols-smoke: port/work/" + LEGACY_PROFILE.id + " is absent; " + LEGACY_PROFILE.id + " probe skipped");
  }
  console.log("modern-symbols-smoke: OK (" + CANONICAL_PROFILE.id + " blaze3d/yggdrasil/GLFW, " + RENDERPEARL_PROFILE.id + " "
    + "renderpearl/services/SDL, 119 render keys, 8 class->interface, mixed jars rejected)");
} finally {
  await rm(work, {recursive: true, force: true});
}
