#!/usr/bin/env node

// Unit-style smoke for the 26.3 bring-up infrastructure (migration plan contracts C2/C3):
// - dev.gaius.tools.PatchRegistry: bring-up list parsing, bring-up activation rules (only with
//   GAIUS_BRINGUP=1 and only for a profile whose bringupAllowed is true), BRINGUP_SKIP/PATCH_DROPPED/PATCH_SUMMARY
//   lines, dropped-target assertions, pending-list output on failure, profile conflicts, and the
//   patch id rule it shares with version-profile.sh, check-build-log-skips.mjs and
//   check-version-profile.mjs;
// - dev.gaius.tools.Minecraft263BrowserPatcher: refuses 26.2/1.21.11 and non-renderpearl jars,
//   runs its six patch groups and the JdkCompat group in order on the 26.3 jar;
// - MinecraftClientPatcher/Minecraft262BrowserPatcher main(): every patch call is wrapped in
//   PatchRegistry.run("<Class>.<method>", "<spec>", () -> <method>(...)), the spec being the
//   patch's kind, targets and profiles (patchkit step 1);
// - specs: a patch whose targets leave out the build's TeaVM target reports
//   PATCH_TARGET_SKIPPED without running its body, one whose profiles leave out the profile
//   reports PATCH_NOT_APPLICABLE; malformed specs are refused; a failing body of a patch that is
//   not REQUIRED is restored (the files it wrote under the configured output root) and reported
//   as PATCH_DISABLED, a failing REQUIRED body still fails (restored and recorded only in check
//   mode), strict mode disables nothing, the run log records every outcome;
//   check-build-log-skips.mjs (G4) accepts exactly the ids of port/tools/target-skips/wasmgc.txt
//   in a wasmgc log and of port/tools/target-skips/js.txt in a js log, counts the new outcome
//   lines, and those lists name exactly the ids whose spec leaves the target out (patchkit lint);
// When port/tools/bringup/26.3.txt exists (or --bringup-list PATH is given), it also runs
// MCP -> M262 -> M263 on a copy of the 26.3 jar in bring-up mode and checks that every skip is
// listed (pass --require-bringup-list to fail instead of skipping that part without a list).
import {CANONICAL_PROFILE, LEGACY_PROFILE, RENDERPEARL_PROFILE, escapeRegExp, profileIdPattern} from "./verification-profiles.mjs";
const scratchProfileId = `${RENDERPEARL_PROFILE.id.split(".")[0]}.${Number(RENDERPEARL_PROFILE.id.split(".")[1]) + 6}`;
const snapshotProfileId = `${RENDERPEARL_PROFILE.id.split(".")[0]}.${Number(RENDERPEARL_PROFILE.id.split(".")[1]) + 1}-snapshot-2`;
import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {access, copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";
import {lint, parseSpec} from "../tools/patchkit/patchkit.mjs";

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
const jar262 = join(repositoryRoot, "port/work/" + CANONICAL_PROFILE.id + "/client-named.jar");
const jar263 = join(repositoryRoot, "port/work/" + RENDERPEARL_PROFILE.id + "/client-named.jar");
const listFlag = process.argv.indexOf("--bringup-list");
const bringupList263 = listFlag >= 0 ? nativePath(process.argv[listFlag + 1])
  : join(repositoryRoot, "port/tools/bringup/" + RENDERPEARL_PROFILE.id + ".txt");
const requireBringupList = listFlag >= 0 || process.argv.includes("--require-bringup-list");

function jdkTool(name) {
  const homes = [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean).map(nativePath);
  for (const home of [...new Set(homes)]) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

const DRIVER = String.raw`
import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public final class PatchRegistrySmokeDriver {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "parse" -> {
                try {
                    var entries = PatchRegistry.parseBringupList(Path.of(args[1]));
                    for (var entry : entries.values()) {
                        System.out.println("ENTRY " + entry.patchId() + " | " + entry.owner() + " | "
                                + entry.reason());
                    }
                } catch (IllegalStateException error) {
                    System.out.println("PARSE_ERROR " + error.getMessage());
                }
            }
            case "skip" -> {
                if (!args[1].equals("-")) PatchRegistry.configureProfile(args[1]);
                System.out.println("ACTIVE " + PatchRegistry.bringupActive() + " PROFILE "
                        + PatchRegistry.profile());
                for (int i = 2; i < args.length; i++) {
                    System.out.println("QUERY " + args[i] + " " + PatchRegistry.bringupSkip(args[i]));
                }
                PatchRegistry.printSummary();
            }
            case "run" -> {
                PatchRegistry.configureProfile(args[1]);
                for (int i = 2; i < args.length; i++) {
                    String id = args[i];
                    boolean fail = id.endsWith("Fail");
                    try {
                        PatchRegistry.run(id, "REQUIRED", () -> {
                            System.out.println("BODY " + id);
                            if (fail) throw new IOException("boom " + id);
                        });
                    } catch (IOException error) {
                        System.out.println("RETHROWN " + error.getMessage());
                    }
                }
                PatchRegistry.applied("Manual.patch");
                PatchRegistry.printSummary();
            }
            case "spec" -> {
                PatchRegistry.configureProfile(args[1]);
                for (int i = 2; i + 1 < args.length; i += 2) {
                    String id = args[i];
                    PatchRegistry.run(id, args[i + 1], () -> System.out.println("BODY " + id));
                }
                PatchRegistry.printSummary();
            }
            case "undeclared" -> {
                PatchRegistry.configureProfile(args[1]);
                PatchRegistry.run(args[2], () -> System.out.println("BODY " + args[2]));
                PatchRegistry.printSummary();
            }
            case "rollback" -> {
                PatchRegistry.configureProfile(args[1]);
                Path root = Path.of(args[2]);
                if (!args[3].equals("no-output")) {
                    PatchRegistry.configureOutput(root);
                }
                PatchRegistry.run("Demo.writer", "PERF", () -> {
                    put(root, "a/Pre.class", "writer");
                    put(root, "a/New.class", "new");
                });
                PatchRegistry.run("Demo.broken", "TELEMETRY", () -> {
                    put(root, "a/Pre.class", "broken!");
                    put(root, "b/Broken.class", "x");
                    Files.delete(root.resolve("a/New.class"));
                    throw new IllegalStateException("patch point missing; skipping it is wrong");
                });
                try {
                    PatchRegistry.run("Demo.required", "REQUIRED", () -> {
                        put(root, "a/Req.class", "r");
                        throw new IOException("required boom");
                    });
                } catch (IOException error) {
                    System.out.println("RETHROWN " + error.getMessage());
                }
                PatchRegistry.run("Demo.after", "FEATURE", () -> put(root, "a/After.class", "after"));
                PatchRegistry.printSummary();
                for (String name : new String[] {"a/Pre.class", "a/New.class", "b/Broken.class",
                        "a/Req.class", "a/After.class"}) {
                    Path file = root.resolve(name);
                    System.out.println("FILE " + name + " " + (Files.exists(file)
                            ? Files.readString(file) : "-"));
                }
            }
            case "dropped" -> {
                String[] targets = java.util.Arrays.copyOfRange(args, 3, args.length);
                try {
                    PatchRegistry.dropped(args[2], args[1], targets);
                    System.out.println("DROPPED_OK");
                } catch (IllegalStateException | IllegalArgumentException error) {
                    System.out.println("DROPPED_ERROR " + error.getMessage());
                }
            }
            case "make-jar" -> {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "a/B", null, "java/lang/Object", null);
                writer.visitField(Opcodes.ACC_PUBLIC, "field", "I", null, null).visitEnd();
                var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "m", "(I)V", null, null);
                method.visitCode();
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(0, 2);
                method.visitEnd();
                writer.visitEnd();
                try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
                    out.putNextEntry(new JarEntry("a/B.class"));
                    out.write(writer.toByteArray());
                    out.closeEntry();
                    out.putNextEntry(new JarEntry("assets/x.txt"));
                    out.write(1);
                    out.closeEntry();
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static void put(Path root, String name, String text) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
`;

const javac = jdkTool("javac");
const java = jdkTool("java");
const jarTool = jdkTool("jar");
await Promise.all([asm, asmTree, jar262, jar263].map((path) => access(path)));

function mainBody(source, name) {
  const start = source.indexOf("public static void main(String[] args)");
  assert.ok(start >= 0, `${name}: main() not found`);
  let index = source.indexOf("{", start) + 1;
  let depth = 1;
  const bodyStart = index;
  while (depth > 0) {
    const ch = source[index++];
    if (ch === "{") depth++;
    else if (ch === "}") depth--;
  }
  return source.slice(bodyStart, index - 1);
}

// Every statement of main() is a declaration, control flow, a comment, a PatchRegistry
// bookkeeping call or PatchRegistry.run("<Class>.<method>", "<spec>", () -> <method>(...)) with
// a valid spec (kind, targets, profiles).
function checkWrappedMain(source, className, minimumWrapped) {
  const body = mainBody(source, className);
  const ids = [];
  let statementStart = true;
  let depth = 0;
  for (const raw of body.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === "" || line.startsWith("//")) continue;
    if (statementStart && depth === 0) {
      // The called method is the patcher's own or that of a domain class
      // (port/tools/patchkit/README.md "Domains"), which keeps the historical id.
      const wrapped = line.match(
        /^PatchRegistry\.run\("([A-Za-z0-9]+)\.([A-Za-z0-9_]+)", "([^"]+)", \(\) -> (?:[A-Z][A-Za-z0-9]*\.)?([A-Za-z0-9_]+)\(/);
      // A patch that needs a guard around several calls wraps a block lambda; its id then names
      // the patch (there is no single called method to compare it with).
      const wrappedBlock = line.match(
        /^PatchRegistry\.run\("([A-Za-z0-9]+)\.([A-Za-z0-9_]+)", "([^"]+)", \(\) -> \{$/);
      if (wrapped) {
        assert.equal(wrapped[1], className, `${className}: wrong class in id: ${line}`);
        assert.equal(wrapped[2], wrapped[4], `${className}: id does not name the called method: ${line}`);
        assert.doesNotThrow(() => parseSpec(wrapped[3]), `${className}: invalid spec: ${line}`);
        ids.push(wrapped[2]);
      } else if (wrappedBlock) {
        assert.equal(wrappedBlock[1], className, `${className}: wrong class in id: ${line}`);
        assert.doesNotThrow(() => parseSpec(wrappedBlock[3]), `${className}: invalid spec: ${line}`);
        ids.push(wrappedBlock[2]);
      } else {
        assert.match(line, new RegExp(
          "^(if \\(|} else \\{|}|throw new |String |Path |boolean |int "
          + "|PatchRegistry\\.configureProfile\\(|PatchRegistry\\.configureOutput\\("
          + "|PatchRegistry\\.printSummary\\(\\);)"),
          `${className} main(): unwrapped statement: ${line}`);
      }
    }
    for (const ch of line.replace(/"(?:[^"\\]|\\.)*"/g, "\"\"")) {
      if (ch === "(") depth++;
      else if (ch === ")") depth--;
    }
    statementStart = depth === 0 && /[;{}]$/.test(line);
  }
  assert.ok(ids.length >= minimumWrapped,
    `${className}: only ${ids.length} wrapped patch calls (expected >= ${minimumWrapped})`);
  assert.match(body, /PatchRegistry\.configureProfile\(/, `${className}: profile not configured`);
  assert.match(body, /PatchRegistry\.configureOutput\(root\);/, `${className}: output root not configured`);
  assert.match(body, /PatchRegistry\.printSummary\(\);\s*$/, `${className}: summary missing`);
  return ids;
}

// The bytecode of Minecraft.run() in a patched class directory (javap -c).
function minecraftRun(root) {
  const text = execFileSync(jdkTool("javap"), ["-c", "-p", "-cp", root, "net.minecraft.client.Minecraft"],
    {encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout: 120_000});
  const start = text.indexOf("public void run();");
  assert.ok(start >= 0, "javap shows no Minecraft.run()");
  return text.slice(start, text.indexOf("Exception table:", start));
}

const work = await mkdtemp(join(tmpdir(), "gaius-patch-registry-"));
const copyG4 = async (root) => {
  await mkdir(join(root, "port/scripts"), {recursive: true});
  await mkdir(join(root, "port/tools/patchkit"), {recursive: true});
  await mkdir(join(root, "port/tools/sourcesets"), {recursive: true});
  await mkdir(join(root, "port/tools/src/main/java"), {recursive: true});
  await mkdir(join(root, "port/mc/shared"), {recursive: true});
  for (const set of ["canonical", "renderpearl-1", "legacy-12111"]) {
    await mkdir(join(root, "port/mc/shared", set), {recursive: true});
  }
  await mkdir(join(root, "port/versions"), {recursive: true});
  for (const file of ["port/scripts/check-build-log-skips.mjs", "port/scripts/verification-profiles.mjs",
    "port/tools/patchkit/patchkit.mjs", "port/tools/patchkit/target-lock.mjs",
    "port/tools/sourcesets/sourcesets.mjs", "port/mc/shared/sourcesets.json"]) {
    await copyFile(join(repositoryRoot, file), join(root, file));
  }
  for (const file of (await readdir(join(repositoryRoot, "port/versions"))).filter((name) => name.endsWith(".json"))) {
    await copyFile(join(repositoryRoot, "port/versions", file), join(root, "port/versions", file));
  }
};
try {
  // Source contract for the main() dispatch.
  const mcpIds = checkWrappedMain(
    await readFile(join(toolsPackage, "MinecraftClientPatcher.java"), "utf8"),
    "MinecraftClientPatcher", 170);
  const m262Ids = checkWrappedMain(
    await readFile(join(toolsPackage, "Minecraft262BrowserPatcher.java"), "utf8"),
    "Minecraft262BrowserPatcher", 30);
  assert.equal(new Set(m262Ids).size, m262Ids.length, "M262 patch ids must be unique");

  const classes = join(work, "classes");
  await mkdir(classes, {recursive: true});
  const sources = [
    ...(await readdir(toolsPackage)).filter((name) => name.endsWith(".java"))
      .map((name) => join(toolsPackage, name)),
  ];
  const driverSource = join(work, "PatchRegistrySmokeDriver.java");
  await writeFile(driverSource, DRIVER);
  execFileSync(javac, ["-J-Duser.language=en", "-J-Xmx3g", "--release", "21", "-proc:none",
    "-classpath", [asm, asmTree, teavmCore].join(delimiter), "-sourcepath", toolsRoot, "-d", classes,
    ...sources, driverSource], {encoding: "utf8", timeout: 180_000});
  const classpath = [classes, asm, asmTree].join(delimiter);
  const baseEnv = {...process.env};
  for (const name of ["GAIUS_BRINGUP", "GAIUS_BRINGUP_LIST", "GAIUS_PROFILE",
    "GAIUS_MINECRAFT_VERSION", "GAIUS_PATCH_VERBOSE", "GAIUS_AUTHLIB_JAR", "GAIUS_TEAVM_TARGET"]) {
    delete baseEnv[name];
  }
  // The patchers run in a scratch directory outside the repository; VersionProfile finds the
  // version profiles through this file and the other port/versions/*.json next to it.
  baseEnv.GAIUS_VERSION_PROFILE = join(repositoryRoot, "port/versions/" + RENDERPEARL_PROFILE.id + ".json");
  // A bring-up profile that exists only in the scratch roots below: a copy of 26.3 under another id.
  const scratchProfile = async (directory) => {
    const profile = JSON.parse(await readFile(join(repositoryRoot, "port/versions/" + RENDERPEARL_PROFILE.id + ".json"), "utf8"));
    await mkdir(join(directory, "port/versions"), {recursive: true});
    await writeFile(join(directory, `port/versions/${scratchProfileId}.json`),
      JSON.stringify({...profile, id: scratchProfileId, bringupAllowed: true,
        renderSeam: "scratch", clientDistribution: "scratch", shaderFront: "scratch"}, null, 2));
  };
  const run = (args, env = {}, cwd = work) => {
    const result = spawnSync(java, ["-Xmx3g", "-Duser.language=en", "-classpath", classpath, ...args], {
      encoding: "utf8", cwd, env: {...baseEnv, ...env}, timeout: 300_000,
      maxBuffer: 64 * 1024 * 1024,
    });
    return {status: result.status, out: result.stdout, err: result.stderr};
  };
  const driver = (args, env, cwd) => run(["PatchRegistrySmokeDriver", ...args], env, cwd);

  // Bring-up list parsing.
  const list = join(work, "bringup.txt");
  await writeFile(list, [
    "# comment line",
    "   # indented comment",
    "",
    "Demo.patchA | P3 | reason text   # trailing comment | not a field",
    "Demo.patchFail | P7a | fails on purpose",
    "step:LwjglSdlBrowserPatcher | P2 | shell step",
    "step:LwjglMemoryPatcher@lwjgl | P1 | module shell step",
    "",
  ].join("\n"));
  let result = driver(["parse", list]);
  assert.equal(result.status, 0, result.err);
  // Same rules as version-profile.sh, check-version-profile.mjs and
  // check-build-log-skips.mjs: '#' starts a comment anywhere on the line.
  assert.equal(result.out.trim().split(/\r?\n/).join("\n"), [
    "ENTRY Demo.patchA | P3 | reason text",
    "ENTRY Demo.patchFail | P7a | fails on purpose",
    "ENTRY step:LwjglSdlBrowserPatcher | P2 | shell step",
    "ENTRY step:LwjglMemoryPatcher@lwjgl | P1 | module shell step",
  ].join("\n"));
  for (const [text, message] of [
    ["Demo.x | Q3 | bad owner", "invalid owner package"],
    ["Demo.x | P0 | lead is not an owner", "invalid owner package"],
    ["Demo.x | P3 |   ", "missing reason"],
    ["Demo.x | P3 | # only a comment", "missing reason"],
    ["Demo.x | P3", "expected '<patchId> | <owner> | <reason>'"],
    ["Demo.x | P3 | reason | extra field", "expected '<patchId> | <owner> | <reason>'"],
    ["Demo.x | P3 | one\nDemo.x | P5 | two", "duplicate bring-up entry Demo.x"],
    ["bad id | P3 | reason", "invalid patch id"],
    ["a/b | P3 | slash", "invalid patch id"],
    [":x | P3 | leading colon", "invalid patch id"],
  ]) {
    const bad = join(work, "bad.txt");
    await writeFile(bad, text + "\n");
    result = driver(["parse", bad]);
    assert.match(result.out, new RegExp("^PARSE_ERROR .*" + escapeRegExp(message)),
      `bad list not rejected: ${JSON.stringify(text)}`);
  }

  // The four readers of a bring-up list accept exactly the same patch ids:
  // ^[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*$ (PatchRegistry, version-profile.sh,
  // check-build-log-skips.mjs, check-version-profile.mjs).
  const canonicalId = "[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*";
  for (const [file, text] of [
    ["port/tools/src/main/java/dev/gaius/tools/PatchRegistry.java", `Pattern.compile("${canonicalId}")`],
    ["port/scripts/version-profile.sh", `part[1] !~ /^${canonicalId}$/`],
    ["port/scripts/check-build-log-skips.mjs", `!/^${canonicalId}$/.test(fields[0])`],
    ["port/scripts/check-version-profile.mjs", `!/^${canonicalId}$/u.test(patchId)`],
  ]) {
    assert.ok((await readFile(join(repositoryRoot, file), "utf8")).includes(text),
      `${file} must use the patch id rule ${canonicalId}`);
  }
  const idRoot = join(work, "id-root");
  await mkdir(join(idRoot, "port/tools/bringup"), {recursive: true});
  await mkdir(join(idRoot, "port/tools/target-skips"), {recursive: true});
  await mkdir(join(idRoot, "port/scripts"), {recursive: true});
  // An empty js log skips nothing, so the js list of this root lists nothing.
  await writeFile(join(idRoot, "port/tools/target-skips/js.txt"), "# no WebAssembly GC-only patches\n");
  await scratchProfile(idRoot);
  await copyG4(idRoot);
  const emptyLog = join(work, "empty.log");
  await writeFile(emptyLog, "");
  const idList = join(idRoot, "port/tools/bringup/26.9.txt");
  const bash = process.platform === "win32" ? "bash.exe" : "bash";
  for (const [id, valid] of [
    ["Demo.patchA", true], ["step:LwjglMemoryPatcher@lwjgl", true], ["$x", true], ["_x", true],
    ["9x-y", true], [":x", false], [".x", false], ["@x", false], ["-x", false], ["a/b", false],
    ["Demo/patch", false],
  ]) {
    await writeFile(idList, `${id} | P3 | boundary case\n`);
    result = driver(["parse", idList]);
    assert.equal(/^ENTRY /m.test(result.out), valid, `PatchRegistry on ${id}: ${result.out}`);
    const shell = spawnSync(bash, ["-c",
      'source "$1/port/scripts/version-profile.sh"; GAIUS_MINECRAFT_VERSION=26.9; gaius_bringup_ids "$2"',
      "_", repositoryRoot.replaceAll("\\", "/"), idRoot.replaceAll("\\", "/")], {encoding: "utf8"});
    assert.equal(shell.status === 0 && shell.stdout.trim() === id, valid,
      `version-profile.sh on ${id}: ${shell.status} ${shell.stdout} ${shell.stderr}`);
    const lint = spawnSync(process.execPath, [join(idRoot, "port/scripts/check-build-log-skips.mjs"),
        "--profile", scratchProfileId, emptyLog], {encoding: "utf8"});
    assert.equal(lint.status, valid ? 0 : 2,
      `check-build-log-skips.mjs on ${id}: ${lint.stdout} ${lint.stderr}`);
  }
  // Ids outside the rule are refused at run time as well.
  result = driver(["skip", RENDERPEARL_PROFILE.id, "a/b"], {GAIUS_BRINGUP_LIST: list});
  assert.notEqual(result.status, 0);
  assert.match(result.err, /Invalid patch id: a\/b/);

  // Bring-up is active only with the flag and an eligible profile.
  result = driver(["skip", RENDERPEARL_PROFILE.id, "Demo.patchA", "Demo.patchB"],
    {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: list});
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, new RegExp(String.raw`^ACTIVE true PROFILE ${profileIdPattern(RENDERPEARL_PROFILE)}$`, "m"));
  assert.match(result.out, /^BRINGUP_SKIP Demo\.patchA$/m);
  assert.match(result.out, /^QUERY Demo\.patchA true$/m);
  assert.match(result.out, /^QUERY Demo\.patchB false$/m);
  assert.match(result.out, /^PATCH_SUMMARY applied=0 dropped=0 bringupSkipped=1$/m);
  assert.match(result.err, /^BRINGUP_UNUSED Demo\.patchFail$/m, "unused listed ids are reported");
  assert.doesNotMatch(result.err, /BRINGUP_UNUSED step:/, "other scopes are not reported");
  for (const profile of [CANONICAL_PROFILE.id, LEGACY_PROFILE.id]) {
    result = driver(["skip", profile, "Demo.patchA"], {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: list});
    assert.equal(result.status, 0, result.err);
  assert.match(result.out, new RegExp(`^ACTIVE false PROFILE ${escapeRegExp(profile)}$`, "m"));
    assert.match(result.out, /^QUERY Demo\.patchA false$/m);
    assert.doesNotMatch(result.out, /BRINGUP_SKIP/);
  assert.match(result.err, new RegExp(`^BRINGUP_IGNORED profile=${escapeRegExp(profile)} `, "m"));
  }
  result = driver(["skip", RENDERPEARL_PROFILE.id, "Demo.patchA"], {GAIUS_BRINGUP_LIST: list});
  assert.match(result.out, new RegExp(String.raw`^ACTIVE false PROFILE ${profileIdPattern(RENDERPEARL_PROFILE)}$`, "m"), "no flag, no bring-up");
  assert.match(result.out, /^QUERY Demo\.patchA false$/m);
  for (const flag of ["true", "yes"]) {
    result = driver(["skip", RENDERPEARL_PROFILE.id, "Demo.patchA"], {GAIUS_BRINGUP: flag, GAIUS_BRINGUP_LIST: list});
    assert.match(result.out, /^QUERY Demo\.patchA true$/m, `GAIUS_BRINGUP=${flag}`);
  }
  // Profile sources: configureProfile, then gaius.profile / GAIUS_PROFILE, then GAIUS_MINECRAFT_VERSION.
  result = driver(["skip", "-", "Demo.patchA"],
    {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: list, GAIUS_MINECRAFT_VERSION: RENDERPEARL_PROFILE.id});
  assert.match(result.out, new RegExp(String.raw`^ACTIVE true PROFILE ${profileIdPattern(RENDERPEARL_PROFILE)}$`, "m"), "GAIUS_MINECRAFT_VERSION fallback");
  result = run(["-Dgaius.profile=" + RENDERPEARL_PROFILE.id + "", "-Dgaius.bringup=1", `-Dgaius.bringup.list=${list}`,
    "PatchRegistrySmokeDriver", "skip", "-", "Demo.patchA"]);
  assert.match(result.out, /^QUERY Demo\.patchA true$/m, "system properties");
  result = driver(["skip", RENDERPEARL_PROFILE.id, "Demo.patchA"], {GAIUS_PROFILE: CANONICAL_PROFILE.id});
  assert.notEqual(result.status, 0);
  assert.match(result.err, new RegExp(String.raw`Patcher profile ${profileIdPattern(RENDERPEARL_PROFILE)} disagrees with environment variable GAIUS_PROFILE=${profileIdPattern(CANONICAL_PROFILE)}`));
  result = driver(["skip", RENDERPEARL_PROFILE.id, "Demo.patchA"], {GAIUS_MINECRAFT_VERSION: CANONICAL_PROFILE.id});
  assert.equal(result.status, 0, "GAIUS_MINECRAFT_VERSION is only a fallback");
  result = driver(["skip", RENDERPEARL_PROFILE.id, "Demo.patchA"], {GAIUS_BRINGUP: "1"}, work);
  assert.notEqual(result.status, 0, "bring-up without a list must fail");
  assert.match(result.err, new RegExp(String.raw`Bring-up mode for profile ${profileIdPattern(RENDERPEARL_PROFILE)} needs port\/tools\/bringup\/${profileIdPattern(RENDERPEARL_PROFILE)}\.txt`));
  // The default list location is port/tools/bringup/<profile>.txt above the working directory.
  const fakeRoot = join(work, "fake-root");
  await mkdir(join(fakeRoot, "port/tools/bringup"), {recursive: true});
  await mkdir(join(fakeRoot, "nested/dir"), {recursive: true});
  await copyFile(list, join(fakeRoot, "port/tools/bringup/26.9.txt"));
  await scratchProfile(fakeRoot);
  result = driver(["skip", scratchProfileId, "Demo.patchA"], {GAIUS_BRINGUP: "1"}, join(fakeRoot, "nested/dir"));
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^QUERY Demo\.patchA true$/m, "list located from the working directory");

  // run(): applied/skip accounting, failures propagate and print the pending list.
  result = driver(["run", RENDERPEARL_PROFILE.id, "Demo.patchA", "Demo.patchOk"],
    {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: list, GAIUS_PATCH_VERBOSE: "1"});
  assert.equal(result.status, 0, result.err);
  assert.doesNotMatch(result.out, /^BODY Demo\.patchA$/m, "skipped body must not run");
  assert.match(result.out, /^BODY Demo\.patchOk$/m);
  assert.match(result.out, /^PATCH_APPLIED Demo\.patchOk$/m);
  assert.match(result.out, /^PATCH_SUMMARY applied=2 dropped=0 bringupSkipped=1$/m);
  result = driver(["run", RENDERPEARL_PROFILE.id, "Demo.patchOk", "Demo.patchFail"], {GAIUS_BRINGUP_LIST: list});
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^RETHROWN boom Demo\.patchFail$/m);
  assert.match(result.out, /^PATCH_SUMMARY applied=2 dropped=0 bringupSkipped=0 failed=1$/m,
    "a failed body is not counted as applied");
  assert.match(result.err, new RegExp(String.raw`Patch Demo\.patchFail failed for profile ${profileIdPattern(RENDERPEARL_PROFILE)} \(bring-up owner P7a: fails on purpose\)`));
  assert.match(result.err, /^BRINGUP_PENDING Demo\.patchA \| P3 \| reason text$/m);
  assert.match(result.err, /^BRINGUP_PENDING step:LwjglSdlBrowserPatcher \| P2 \| shell step$/m);
  result = driver(["run", RENDERPEARL_PROFILE.id, "Demo.patchA", "Demo.otherFail"],
    {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: list});
  assert.match(result.out, /^RETHROWN boom Demo\.otherFail$/m);
  assert.match(result.err, new RegExp(String.raw`Patch Demo\.otherFail failed in bring-up mode for profile ${profileIdPattern(RENDERPEARL_PROFILE)} and is not listed in `));
  assert.doesNotMatch(result.err, /BRINGUP_PENDING/);
  result = driver(["run", CANONICAL_PROFILE.id, "Demo.patchFail"], {GAIUS_BRINGUP_LIST: list});
  assert.match(result.out, /^RETHROWN boom Demo\.patchFail$/m);
  assert.doesNotMatch(result.err, /BRINGUP_PENDING/, "" + CANONICAL_PROFILE.id + " never loads a bring-up list");

  // Targets of the spec: the TeaVM target comes from gaius.teavm.target, then GAIUS_TEAVM_TARGET
  // (BuildTarget); js is the default.
  result = driver(["spec", RENDERPEARL_PROFILE.id, "Demo.patchJs", "PERF js"], {GAIUS_PATCH_VERBOSE: "1"});
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^BODY Demo\.patchJs$/m, "a JavaScript-only patch must run for js");
  assert.match(result.out, /^PATCH_APPLIED Demo\.patchJs$/m);
  assert.match(result.out, /^PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0$/m,
    "the js summary line must not change");
  result = driver(["spec", RENDERPEARL_PROFILE.id, "Demo.patchJs", "PERF js"], {GAIUS_TEAVM_TARGET: "wasmgc"});
  assert.equal(result.status, 0, result.err);
  assert.doesNotMatch(result.out, /^BODY /m, "a JavaScript-only patch must not run for wasmgc");
  assert.match(result.out, /^PATCH_TARGET_SKIPPED Demo\.patchJs target=wasmgc$/m);
  assert.match(result.out, /^PATCH_SUMMARY applied=0 dropped=0 bringupSkipped=0 targetSkipped=1$/m);
  result = run(["-Dgaius.teavm.target=js", "PatchRegistrySmokeDriver", "spec", RENDERPEARL_PROFILE.id, "Demo.patchJs",
    "PERF js"], {GAIUS_TEAVM_TARGET: "wasmgc"});
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^BODY Demo\.patchJs$/m, "gaius.teavm.target overrides GAIUS_TEAVM_TARGET");
  result = driver(["spec", RENDERPEARL_PROFILE.id, "Demo.patchJs", "PERF js"], {GAIUS_TEAVM_TARGET: "wasm"});
  assert.notEqual(result.status, 0, "an unknown TeaVM target must fail");
  assert.match(result.err, /Unknown TeaVM target 'wasm'/);
  // The mirror image, for the patches of the WebAssembly GC output only.
  result = driver(["spec", RENDERPEARL_PROFILE.id, "Demo.patchWasm", "REQUIRED wasmgc"], {GAIUS_TEAVM_TARGET: "wasmgc",
    GAIUS_PATCH_VERBOSE: "1"});
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^BODY Demo\.patchWasm$/m, "a WebAssembly GC-only patch must run for wasmgc");
  assert.match(result.out, /^PATCH_APPLIED Demo\.patchWasm$/m);
  assert.match(result.out, /^PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0$/m);
  result = driver(["spec", RENDERPEARL_PROFILE.id, "Demo.patchWasm", "REQUIRED wasmgc"]);
  assert.equal(result.status, 0, result.err);
  assert.doesNotMatch(result.out, /^BODY /m, "a WebAssembly GC-only patch must not run for js");
  assert.match(result.out, /^PATCH_TARGET_SKIPPED Demo\.patchWasm target=js$/m);
  assert.match(result.out, /^PATCH_SUMMARY applied=0 dropped=0 bringupSkipped=0 targetSkipped=1$/m);
  // Profiles of the spec: bounds compare the leading dotted numbers of the profile id.
  result = driver(["spec", RENDERPEARL_PROFILE.id, "Demo.newer", "PERF >=" + CANONICAL_PROFILE.id + "", "Demo.legacy", "PERF " + LEGACY_PROFILE.id + "",
    "Demo.older", "FEATURE <=" + CANONICAL_PROFILE.id + "", "Demo.union", "TELEMETRY " + LEGACY_PROFILE.id + ",>=" + RENDERPEARL_PROFILE.id + "", "Demo.all", "REQUIRED js wasmgc *"]);
  assert.equal(result.status, 0, result.err);
  assert.deepEqual(result.out.split(/\r?\n/).filter((line) => line.startsWith("BODY ")),
    ["BODY Demo.newer", "BODY Demo.union", "BODY Demo.all"]);
  assert.match(result.out, new RegExp(String.raw`^PATCH_NOT_APPLICABLE Demo\.legacy profile=${profileIdPattern(RENDERPEARL_PROFILE)}$`, "m"));
  assert.match(result.out, new RegExp(String.raw`^PATCH_NOT_APPLICABLE Demo\.older profile=${profileIdPattern(RENDERPEARL_PROFILE)}$`, "m"));
  assert.match(result.out, /^PATCH_SUMMARY applied=3 dropped=0 bringupSkipped=0 notApplicable=2$/m);
  result = driver(["spec", snapshotProfileId, "Demo.newer", "PERF >=" + RENDERPEARL_PROFILE.id]);
  assert.match(result.out, /^BODY Demo\.newer$/m, ">=" + RENDERPEARL_PROFILE.id + " covers a 26.4 snapshot");
  for (const spec of ["SOMETIMES", "PERF js js", "PERF >" + CANONICAL_PROFILE.id + "", "PERF *," + CANONICAL_PROFILE.id + "", "PERF wasm", ""]) {
    result = driver(["spec", RENDERPEARL_PROFILE.id, "Demo.bad", spec]);
    assert.notEqual(result.status, 0, `spec ${JSON.stringify(spec)} must be refused`);
    assert.match(result.err, /patch spec|empty patch spec/);
    assert.throws(() => parseSpec(spec), undefined, `patchkit must refuse ${JSON.stringify(spec)} too`);
  }
  // The deprecated run(id, body) still runs, as REQUIRED, and says so.
  result = driver(["undeclared", RENDERPEARL_PROFILE.id, "Demo.legacyCall"]);
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^BODY Demo\.legacyCall$/m);
  assert.match(result.err, /^PATCH_UNDECLARED Demo\.legacyCall$/m);

  // Failures: a non-REQUIRED body is restored and disabled, a REQUIRED one fails.
  const rollbackRoot = join(work, "rollback");
  const prepare = async () => {
    await rm(rollbackRoot, {recursive: true, force: true});
    await mkdir(join(rollbackRoot, "a"), {recursive: true});
    await writeFile(join(rollbackRoot, "a/Pre.class"), "pre");
  };
  const runLog = join(work, "patch-run.jsonl");
  await prepare();
  await rm(runLog, {force: true});
  result = driver(["rollback", RENDERPEARL_PROFILE.id, rollbackRoot, "output"], {GAIUS_PATCH_RUN_LOG: runLog});
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^PATCH_DISABLED Demo\.broken kind=TELEMETRY error=IllegalStateException: patch point missing; skipping it is wrong$/m);
  assert.match(result.out, /^PATCH_FAILED Demo\.required kind=REQUIRED error=IOException: required boom$/m);
  assert.match(result.out, /^RETHROWN required boom$/m, "a REQUIRED failure propagates");
  assert.match(result.out, /^PATCH_SUMMARY applied=2 dropped=0 bringupSkipped=0 disabled=1 failed=1$/m);
  for (const [name, content] of [["a/Pre.class", "writer"], ["a/New.class", "new"], ["b/Broken.class", "-"],
    ["a/Req.class", "r"], ["a/After.class", "after"]]) {
    assert.match(result.out, new RegExp(`^FILE ${escapeRegExp(name)} ${content}$`, "m"),
      `${name} after the disabled patch`);
  }
  const records = (await readFile(runLog, "utf8")).trim().split("\n").map((line) => JSON.parse(line));
  assert.deepEqual(records.map((record) => `${record.id} ${record.kind} ${record.outcome}`), [
    "Demo.writer PERF APPLIED", "Demo.broken TELEMETRY DISABLED", "Demo.required REQUIRED FAILED",
    "Demo.after FEATURE APPLIED"]);
  assert.deepEqual(records[0].classes, ["a/New.class", "a/Pre.class"]);
  assert.deepEqual(records[1].classes, ["a/New.class", "a/Pre.class", "b/Broken.class"]);
  assert.deepEqual([records[0].profile, records[0].target, records[0].profiles, records[0].targets.join()],
    [RENDERPEARL_PROFILE.id, "js", "*", "js,wasmgc"]);
  // Check mode: the REQUIRED failure is restored and recorded, and the patcher goes on.
  await prepare();
  result = driver(["rollback", RENDERPEARL_PROFILE.id, rollbackRoot, "output"], {GAIUS_PATCH_CHECK: "1"});
  assert.equal(result.status, 0, result.err);
  assert.doesNotMatch(result.out, /^RETHROWN/m, "check mode records a REQUIRED failure");
  assert.match(result.out, /^PATCH_FAILED Demo\.required kind=REQUIRED /m);
  assert.match(result.out, /^FILE a\/Req\.class -$/m, "check mode restores a failed REQUIRED patch");
  assert.match(result.out, /^PATCH_SUMMARY applied=2 dropped=0 bringupSkipped=0 disabled=1 failed=1$/m);
  // Strict mode: nothing is disabled.
  await prepare();
  result = driver(["rollback", RENDERPEARL_PROFILE.id, rollbackRoot, "output"], {GAIUS_PATCH_STRICT: "1"});
  assert.notEqual(result.status, 0, "strict mode fails on any failure");
  assert.match(result.out, /^PATCH_FAILED Demo\.broken kind=TELEMETRY /m);
  // Without an output root nothing can be restored, so nothing is disabled.
  await prepare();
  result = driver(["rollback", RENDERPEARL_PROFILE.id, rollbackRoot, "no-output"]);
  assert.notEqual(result.status, 0, "a failure without an output root cannot be disabled");
  assert.match(result.err, /Demo\.broken \(TELEMETRY\) cannot be disabled/);
  // Injected failures exercise the DISABLED path of a body that would succeed.
  result = driver(["spec", RENDERPEARL_PROFILE.id, "Demo.fine", "PERF", "Demo.other", "PERF"], {GAIUS_PATCH_FAIL: "Demo.fine"});
  assert.notEqual(result.status, 0, "an injected failure without an output root is not disabled");
  await prepare();
  result = driver(["rollback", RENDERPEARL_PROFILE.id, rollbackRoot, "output"], {GAIUS_PATCH_FAIL: "Demo.writer"});
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^PATCH_DISABLED Demo\.writer kind=PERF error=IllegalStateException: injected failure \(gaius\.patch\.fail\)$/m);
  assert.match(result.out, /^FILE a\/Pre\.class pre$/m);

  // G4 and target skips: a wasmgc log must skip exactly the listed ids for its profile.
  const targetRoot = join(work, "target-root");
  await mkdir(join(targetRoot, "port/tools/target-skips"), {recursive: true});
  await mkdir(join(targetRoot, "port/scripts"), {recursive: true});
  await copyG4(targetRoot);
  // The gate reads the profile's bringupAllowed from port/versions next to it.
  await mkdir(join(targetRoot, "port/versions"), {recursive: true});
  for (const name of (await readdir(join(repositoryRoot, "port/versions"))).filter((n) => n.endsWith(".json"))) {
    await copyFile(join(repositoryRoot, "port/versions", name), join(targetRoot, "port/versions", name));
  }
  await writeFile(join(targetRoot, "port/tools/src/main/java/Parts.java"), [
    'PatchRegistry.runPart("Demo.part", "PERF NOT_APPLICABLE", () -> {});',
    'PatchRegistry.runPart("Demo.unavailable", "REQUIRED NOT_APPLICABLE", () -> {});',
    'PatchRegistry.runPart("Demo.wasmRoute", "PERF NOT_APPLICABLE", () -> {});',
  ].join("\n"));
  const gateProfile = JSON.parse(await readFile(baseEnv.GAIUS_VERSION_PROFILE, "utf8"));
  const profilePath = join(targetRoot, "port/versions", `${gateProfile.id}.json`);
  gateProfile.patchkitNotApplicable = {"Demo.unavailable": "No fixture seam",
    "Demo.wasmRoute": {targets: ["js"], reason: "The fixture keeps vanilla on js"}};
  await writeFile(profilePath, JSON.stringify(gateProfile));
  await writeFile(join(targetRoot, "port/tools/target-skips/wasmgc.txt"), [
    "# test list",
    "Demo.patchJs | * | JavaScript-only",
    "Demo.patch263 | " + RENDERPEARL_PROFILE.id + " | only " + RENDERPEARL_PROFILE.id + " has it",
    "",
  ].join("\n"));
  await writeFile(join(targetRoot, "port/tools/target-skips/js.txt"), [
    "# test list",
    "Demo.patchWasm | * | WebAssembly GC-only",
    "",
  ].join("\n"));
  const g4 = async (name, lines, extra = []) => {
    const log = join(work, `${name}.log`);
    await writeFile(log, lines.join("\n") + "\n");
    return spawnSync(process.execPath, [join(targetRoot, "port/scripts/check-build-log-skips.mjs"),
      "--profile", RENDERPEARL_PROFILE.id, ...extra, log], {encoding: "utf8"});
  };
  const wasmLines = (ids, count = ids.length) => [
    "Adding wasmgc classlib overrides from /x/port/overrides/classlib/src/target-wasmgc/java",
    ...ids.map((id) => `PATCH_TARGET_SKIPPED ${id} target=wasmgc`),
    `PATCH_SUMMARY applied=3 dropped=0 bringupSkipped=0 targetSkipped=${count}`,
  ];
  result = await g4("wasm-ok", wasmLines(["Demo.patchJs", "Demo.patch263"]));
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /target=wasmgc targetSkipped=2 /);
  result = await g4("wasm-unlisted", wasmLines(["Demo.patchJs", "Demo.patch263", "Demo.semantic"]));
  assert.equal(result.status, 1, "an unlisted target skip must fail G4");
  assert.match(result.stdout, new RegExp(String.raw`Demo\.semantic was skipped for wasmgc, but .* does not list it for ${profileIdPattern(RENDERPEARL_PROFILE)}`));
  result = await g4("wasm-missing", wasmLines(["Demo.patchJs"]));
  assert.equal(result.status, 1, "a listed id that the build ran must fail G4");
  assert.match(result.stdout, new RegExp(String.raw`lists Demo\.patch263 for ${profileIdPattern(RENDERPEARL_PROFILE)}, but the wasmgc build did not skip it`));
  result = await g4("wasm-count", wasmLines(["Demo.patchJs", "Demo.patch263"], 1));
  assert.equal(result.status, 1, "a summary that disagrees with the lines must fail G4");
  result = await g4("wasm-" + CANONICAL_PROFILE.id + "", wasmLines(["Demo.patchJs"]), ["--profile", CANONICAL_PROFILE.id]);
  assert.equal(result.status, 0, "an id listed for " + RENDERPEARL_PROFILE.id + " only is not expected in a " + CANONICAL_PROFILE.id + " log: "
    + result.stdout + result.stderr);
  // Profile terms as in the PatchRegistry spec: ">=26.2" covers 26.2 and 26.3, not 1.21.11.
  await writeFile(join(targetRoot, "port/tools/target-skips/wasmgc.txt"), [
    "Demo.patchJs | * | JavaScript-only",
    "Demo.patch263 | " + RENDERPEARL_PROFILE.id + " | only " + RENDERPEARL_PROFILE.id + " has it",
    "Demo.patchModern | >=" + CANONICAL_PROFILE.id + " | " + CANONICAL_PROFILE.id + " and later",
    "Demo.patchOld | <=" + LEGACY_PROFILE.id + "," + CANONICAL_PROFILE.id + " | " + LEGACY_PROFILE.id + " and " + CANONICAL_PROFILE.id + "",
    "",
  ].join("\n"));
  result = await g4("wasm-terms-" + RENDERPEARL_PROFILE.id + "", wasmLines(["Demo.patchJs", "Demo.patch263", "Demo.patchModern"]));
  assert.equal(result.status, 0, ">=" + CANONICAL_PROFILE.id + " covers " + RENDERPEARL_PROFILE.id + ": " + result.stdout + result.stderr);
  result = await g4("wasm-terms-" + CANONICAL_PROFILE.id + "", wasmLines(["Demo.patchJs", "Demo.patchModern", "Demo.patchOld"]),
    ["--profile", CANONICAL_PROFILE.id]);
  assert.equal(result.status, 0, ">=" + CANONICAL_PROFILE.id + " and <=" + LEGACY_PROFILE.id + "," + CANONICAL_PROFILE.id + " cover " + CANONICAL_PROFILE.id + ": " + result.stdout + result.stderr);
  result = await g4("wasm-terms-" + LEGACY_PROFILE.id + "", wasmLines(["Demo.patchJs", "Demo.patchModern", "Demo.patchOld"]),
    ["--profile", LEGACY_PROFILE.id]);
  assert.equal(result.status, 1, ">=" + CANONICAL_PROFILE.id + " does not cover " + LEGACY_PROFILE.id + "");
  assert.match(result.stdout, new RegExp(String.raw`Demo\.patchModern was skipped for wasmgc, but .* does not list it for ${profileIdPattern(LEGACY_PROFILE)}`));
  result = await g4("wasm-terms-missing", wasmLines(["Demo.patchJs", "Demo.patch263"]));
  assert.equal(result.status, 1, "a >=" + CANONICAL_PROFILE.id + " id that a " + RENDERPEARL_PROFILE.id + " build ran must fail G4");
  assert.match(result.stdout, new RegExp(String.raw`lists Demo\.patchModern for ${profileIdPattern(RENDERPEARL_PROFILE)}, but the wasmgc build did not skip it`));
  await writeFile(join(targetRoot, "port/tools/target-skips/wasmgc.txt"), [
    "# test list",
    "Demo.patchJs | * | JavaScript-only",
    "Demo.patch263 | " + RENDERPEARL_PROFILE.id + " | only " + RENDERPEARL_PROFILE.id + " has it",
    "",
  ].join("\n"));
  result = await g4("js-skip", ["PATCH_TARGET_SKIPPED Demo.patchJs target=wasmgc",
    "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1"], ["--target", "js"]);
  assert.equal(result.status, 1, "a js log must not skip patches for a target");
  result = await g4("js-ok", ["PATCH_TARGET_SKIPPED Demo.patchWasm target=js",
    "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1"]);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.doesNotMatch(result.stdout, /target=/, "the js result line stays unchanged");
  result = await g4("js-missing", ["PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0"]);
  assert.equal(result.status, 1, "a js log that ran a listed WebAssembly GC-only patch must fail G4");
  assert.match(result.stdout, new RegExp(String.raw`lists Demo\.patchWasm for ${profileIdPattern(RENDERPEARL_PROFILE)}, but the js build did not skip it`));
  result = await g4("js-unlisted", ["PATCH_TARGET_SKIPPED Demo.patchWasm target=js",
    "PATCH_TARGET_SKIPPED Demo.patchJs target=js",
    "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=2"]);
  assert.equal(result.status, 1, "a js log must not skip an unlisted patch");
  assert.match(result.stdout, new RegExp(String.raw`Demo\.patchJs was skipped for js, but .* does not list it for ${profileIdPattern(RENDERPEARL_PROFILE)}`));
  result = await g4("summary-garbled", ["PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 extra=1"]);
  assert.equal(result.status, 1, "an unrecognized PATCH_SUMMARY line must fail G4");
  // The outcomes of patchkit step 1: not applicable for the profile, disabled, failed.
  const jsBase = ["PATCH_TARGET_SKIPPED Demo.patchWasm target=js"];
  result = await g4("not-applicable", [...jsBase, "PATCH_NOT_APPLICABLE Demo.legacy profile=" + RENDERPEARL_PROFILE.id + "",
    "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1 notApplicable=1"]);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, / notApplicable=1 /);
  result = await g4("not-applicable-count", [...jsBase, "PATCH_NOT_APPLICABLE Demo.legacy profile=" + RENDERPEARL_PROFILE.id + "",
    "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1"]);
  assert.equal(result.status, 1, "a not-applicable count that disagrees with the lines must fail G4");
  const disabledLog = [...jsBase,
    "PATCH_DISABLED Demo.fast kind=PERF error=IllegalStateException: patch point missing, skipping it",
    "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1 disabled=1"];
  result = await g4("disabled", disabledLog);
  assert.equal(result.status, 0, "a disabled PERF patch is a warning: " + result.stdout + result.stderr);
  assert.match(result.stdout, /^WARNING .*PERF patch Demo\.fast was disabled/m);
  assert.doesNotMatch(result.stdout, /unregistered skip/, "the error text of a disabled patch is no skip");
  result = await g4("disabled-strict", disabledLog, ["--no-disabled"]);
  assert.equal(result.status, 1, "--no-disabled fails on a disabled patch");
  result = await g4("disabled-required", [...jsBase, "PATCH_DISABLED Demo.core kind=REQUIRED error=x",
    "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1 disabled=1"]);
  assert.equal(result.status, 1, "a disabled REQUIRED patch must fail G4");
  result = await g4("disabled-count", [...jsBase, "PATCH_DISABLED Demo.fast kind=PERF error=x",
    "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1"]);
  assert.equal(result.status, 1, "a disabled count that disagrees with the lines must fail G4");
  const partSummary = "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1";
  result = await g4("disabled-part", [...jsBase,
    "PATCH_DISABLED Demo.part kind=PERF summary=part error=x", partSummary]);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  result = await g4("disabled-unknown-part", [...jsBase,
    "PATCH_DISABLED Demo.unknown kind=PERF summary=part error=x", partSummary]);
  assert.equal(result.status, 1, "an unknown part cannot bypass summary accounting");
  result = await g4("declared-part", [...jsBase,
    `PATCH_NOT_APPLICABLE Demo.unavailable profile=${gateProfile.id} summary=part`, partSummary]);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  result = await g4("undeclared-part", [...jsBase,
    `PATCH_NOT_APPLICABLE Demo.unknown profile=${gateProfile.id} summary=part`, partSummary]);
  assert.equal(result.status, 1, "an undeclared exclusion must fail G4");
  result = await g4("wrong-part-summary", [...jsBase,
    `PATCH_NOT_APPLICABLE Demo.unavailable profile=${gateProfile.id} summary=applied`, partSummary]);
  assert.equal(result.status, 1, "the part's declared summary slot must match");
  // A per-target declaration covers only its targets: the js log may exclude the part, a
  // wasmgc log may not.
  result = await g4("declared-target-part", [...jsBase,
    `PATCH_NOT_APPLICABLE Demo.wasmRoute profile=${gateProfile.id} summary=part`, partSummary]);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  result = await g4("declared-other-target-part", [
    "Adding wasmgc classlib overrides from /x/port/overrides/classlib/src/target-wasmgc/java",
    "PATCH_TARGET_SKIPPED Demo.patchJs target=wasmgc", "PATCH_TARGET_SKIPPED Demo.patch263 target=wasmgc",
    `PATCH_NOT_APPLICABLE Demo.wasmRoute profile=${gateProfile.id} summary=part`,
    "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=2"]);
  assert.equal(result.status, 1, "a declaration for js does not cover a wasmgc build");
  assert.match(result.stdout, /Demo\.wasmRoute is declared not applicable for js target\(s\), not wasmgc/);
  result = await g4("failed", [...jsBase, "PATCH_FAILED Demo.core kind=REQUIRED error=x",
    "PATCH_SUMMARY applied=1 dropped=0 bringupSkipped=0 targetSkipped=1 failed=1"]);
  assert.equal(result.status, 1, "a failed patch must fail G4");
  // The repository lists name exactly the ids whose spec leaves the target out (patchkit lint also
  // checks every registration against port/tools/patchkit/classification.tsv), and G4 accepts them.
  const repositoryLint = lint(repositoryRoot);
  assert.deepEqual(repositoryLint.problems, [], "patchkit lint of port/tools");
  // The ids a 26.3 log skips: the lists use the profile terms of the PatchRegistry specs
  // ("*", ids, ">=<id>", "<=<id>"); 26.3 is the newest profile.
  const versionOf = (text) => text.replace(/^[<>]=/, "").split(".").map(Number);
  const compare263 = (text) => {
    const [major, minor = 0] = versionOf(text);
    return major !== 26 ? Math.sign(major - 26) : Math.sign(minor - 3);
  };
  const covers263 = (terms) => terms === "*" || terms.split(",").some((term) =>
    term.startsWith(">=") ? compare263(term) <= 0
      : term.startsWith("<=") ? compare263(term) >= 0 : compare263(term) === 0);
  const listIds = async (target) => (await readFile(join(repositoryRoot, `port/tools/target-skips/${target}.txt`), "utf8"))
    .split(/\r?\n/).map((line) => line.replace(/#.*$/, "").trim()).filter(Boolean)
    .map((line) => line.split("|").map((field) => field.trim()))
    .filter((fields) => covers263(fields[1])).map((fields) => fields[0]);
  const jsOnlyIds = await listIds("wasmgc");
  const wasmOnlyIds = await listIds("js");
  await copyFile(join(repositoryRoot, "port/tools/target-skips/wasmgc.txt"),
    join(targetRoot, "port/tools/target-skips/wasmgc.txt"));
  result = await g4("repository-list", wasmLines(jsOnlyIds));
  assert.equal(result.status, 0, "the repository list parses: " + result.stdout + result.stderr);
  await copyFile(join(repositoryRoot, "port/tools/target-skips/js.txt"),
    join(targetRoot, "port/tools/target-skips/js.txt"));
  result = await g4("repository-js-list", [
    ...wasmOnlyIds.map((id) => `PATCH_TARGET_SKIPPED ${id} target=js`),
    `PATCH_SUMMARY applied=3 dropped=0 bringupSkipped=0 targetSkipped=${wasmOnlyIds.length}`,
  ]);
  assert.equal(result.status, 0, "the repository js list parses: " + result.stdout + result.stderr);

  // dropped(): targets must be absent; members are checked by name or exact descriptor.
  const tinyJar = join(work, "tiny.jar");
  result = driver(["make-jar", tinyJar]);
  assert.equal(result.status, 0, result.err);
  for (const [targets, expected] of [
    [["a/Gone.class", "a/Gone", "a/B#gone", "a/B#m(J)V", "c/D#m"], "DROPPED_OK"],
    [["a/B.class"], "DROPPED_ERROR .*still present.*a/B\\.class"],
    [["a/B"], "DROPPED_ERROR .*still present.*a/B"],
    [["assets/x.txt"], "DROPPED_ERROR .*assets/x\\.txt"],
    [["a/B#m"], "DROPPED_ERROR .*a/B#m"],
    [["a/B#m(I)V"], "DROPPED_ERROR .*a/B#m\\(I\\)V"],
    [["a/B#field"], "DROPPED_ERROR .*a/B#field"],
    [[], "DROPPED_ERROR .*at least one target"],
  ]) {
    result = driver(["dropped", tinyJar, "Demo.dropped", ...targets]);
    assert.match(result.out, new RegExp("^" + expected, "m"), `dropped ${targets.join(",")}`);
    if (expected === "DROPPED_OK") assert.match(result.out, /^PATCH_DROPPED Demo\.dropped$/m);
    else assert.doesNotMatch(result.out, /PATCH_DROPPED/);
  }

  // Minecraft263BrowserPatcher refuses 26.2/1.21.11 and blaze3d jars, runs the shells on 26.3.
  const m263Root = join(work, "m263-out");
  for (const profile of [CANONICAL_PROFILE.id, LEGACY_PROFILE.id]) {
    result = run(["dev.gaius.tools.Minecraft263BrowserPatcher", jar263, m263Root, profile]);
    assert.notEqual(result.status, 0, `M263 ran for ${profile}`);
  assert.match(result.err, new RegExp(`runs only for a renderpearl render seam, not for profile ${escapeRegExp(profile)}`));
  }
  result = run(["dev.gaius.tools.Minecraft263BrowserPatcher", jar262, m263Root, RENDERPEARL_PROFILE.id]);
  assert.notEqual(result.status, 0, "M263 accepted a blaze3d jar");
  assert.match(result.err, new RegExp(String.raw`not a ${profileIdPattern(RENDERPEARL_PROFILE)}\+ (?:renderpearl )?client \(renderApi=BLAZE3D`));
  result = run(["dev.gaius.tools.Minecraft263BrowserPatcher", jar263, m263Root]);
  assert.notEqual(result.status, 0, "M263 requires the version argument");
  // The 26.3 domains patch the output of the modern chain; on the vanilla jar a chain check
  // must refuse the missing chain hooks. Since v0.4 the render group's inventory throttle
  // rewrite (QualityTierPatches) needs the MinecraftClientPatcher hook and refuses first;
  // otherwise TerrainPatches263.verifyTerrainChain does.
  result = run(["dev.gaius.tools.Minecraft263BrowserPatcher", jar263, m263Root, RENDERPEARL_PROFILE.id]);
  assert.notEqual(result.status, 0, "M263 accepted a jar the modern chain never patched");
  assert.match(result.err, new RegExp(String.raw`${profileIdPattern(RENDERPEARL_PROFILE)} terrain chain is incomplete|inventory throttle \(MinecraftClientPatcher\.patchGameRendererBrowserInventoryWorldRenderThrottle\): expected exactly one call, found 0`));

  // Minecraft262BrowserPatcher: optional version argument, modern profiles only.
  result = run(["dev.gaius.tools.Minecraft262BrowserPatcher", jar262, join(work, "m262-out"), LEGACY_PROFILE.id]);
  assert.notEqual(result.status, 0);
  assert.match(result.err, new RegExp(String.raw`serves the modern patch set only, not profile ${profileIdPattern(LEGACY_PROFILE)}`));
  result = run(["dev.gaius.tools.Minecraft262BrowserPatcher", jar262]);
  assert.notEqual(result.status, 0);
  assert.match(result.err, /usage: Minecraft262BrowserPatcher INPUT_JAR OUTPUT_ROOT \[MINECRAFT_VERSION\]/);

  // The WebAssembly GC-only frame boundary (design D4): for wasmgc, MinecraftClientPatcher puts
  // HostLoop.awaitAnimationFrame() at the head of the Minecraft.run loop, before
  // "aload_0; getfield running; ifeq", where both back edges of the loop land.
  if (existsSync(jar263)) {
    const wasmOut = join(work, "mcp-wasmgc-" + RENDERPEARL_PROFILE.id + "");
    result = run(["-Dgaius.teavm.target=wasmgc", "dev.gaius.tools.MinecraftClientPatcher", jar263, wasmOut,
      RENDERPEARL_PROFILE.id], {GAIUS_PATCH_VERBOSE: "1"});
    assert.equal(result.status, 0, `MinecraftClientPatcher failed for wasmgc:\n${result.err}`);
    assert.match(result.out, /^PATCH_APPLIED MinecraftClientPatcher\.patchMinecraftRunFrameBoundary$/m);
    const body = minecraftRun(wasmOut);
    const head = /(\d+): invokestatic +#\d+ +\/\/ Method dev\/gaius\/browser\/HostLoop\.awaitAnimationFrame:\(\)V\s+\d+: aload_0\s+\d+: getfield +#\d+ +\/\/ Field running:Z\s+\d+: ifeq/.exec(body);
    assert.ok(head, "the frame boundary is not at the head of the Minecraft.run loop:\n" + body.slice(0, 3000));
    assert.equal((body.match(/HostLoop\.awaitAnimationFrame/g) || []).length, 1);
    const backEdges = [...body.matchAll(/\d+: goto +(\d+)/g)].filter((match) => match[1] === head[1]).length;
    assert.ok(backEdges >= 2, `the loop's back edges do not reach the frame boundary (${backEdges})`);
  }

  // End to end on 26.3 in bring-up mode (needs the committed bring-up list).
  if (existsSync(bringupList263)) {
    const listed = new Set((await readFile(bringupList263, "utf8")).split(/\r?\n/)
      .map((line) => line.replace(/#.*$/, "").trim()).filter(Boolean)
      .map((line) => line.split("|")[0].trim()));
    const stage = join(work, "stage-" + RENDERPEARL_PROFILE.id + ".jar");
    await copyFile(jar263, stage);
    const env = {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: bringupList263};
    const skipped = [];
    // The client chain of build-overlays.sh (MinecraftServerWorkerPatcher left out: no M263
    // domain reads its classes). The chunk-draw telemetry step does not use PatchRegistry.
    for (const [tool, args, output] of [
      ["dev.gaius.tools.MinecraftClientPatcher", [stage, join(work, "e2e-mcp"), RENDERPEARL_PROFILE.id], join(work, "e2e-mcp")],
      ["dev.gaius.tools.MinecraftChunkDrawTelemetryPatcher", [RENDERPEARL_PROFILE.id, stage, join(work, "e2e-cdt")],
        join(work, "e2e-cdt")],
      ["dev.gaius.tools.Minecraft262BrowserPatcher", [stage, join(work, "e2e-m262"), RENDERPEARL_PROFILE.id], join(work, "e2e-m262")],
      ["dev.gaius.tools.Minecraft263BrowserPatcher", [stage, join(work, "e2e-m263"), RENDERPEARL_PROFILE.id], join(work, "e2e-m263")],
    ]) {
      result = run([tool, ...args], env);
      assert.equal(result.status, 0, `${tool} failed in bring-up mode:\n${result.err}`);
      if (tool === "dev.gaius.tools.MinecraftChunkDrawTelemetryPatcher") {
        assert.match(result.out, new RegExp(String.raw`^CHUNK_DRAW_TELEMETRY_PATCH_OK profile=${profileIdPattern(RENDERPEARL_PROFILE)} `, "m"));
      } else {
        // The js chain skips the WebAssembly GC-only patches (port/tools/target-skips/js.txt).
        assert.match(result.out,
          /^PATCH_SUMMARY applied=\d+ dropped=\d+ bringupSkipped=\d+(?: targetSkipped=\d+)?$/m);
      }
      if (tool === "dev.gaius.tools.MinecraftClientPatcher") {
        assert.match(result.out,
          /^PATCH_TARGET_SKIPPED MinecraftClientPatcher\.patchMinecraftRunFrameBoundary target=js$/m);
        assert.doesNotMatch(minecraftRun(output), /HostLoop\.awaitAnimationFrame/,
          "the js Minecraft.run must keep its per-present yield and no frame boundary");
      }
      if (tool === "dev.gaius.tools.Minecraft263BrowserPatcher") {
        const m263Applied = Number(/^PATCH_SUMMARY applied=(\d+) /m.exec(result.out)[1]);
        assert.ok(m263Applied > 0, "the " + RENDERPEARL_PROFILE.id + " domains applied no patch on the chained jar");
        const domainLines = result.out.split(/\r?\n/)
          .filter((line) => /^[A-Za-z]+Patches263: /.test(line)).map((line) => line.split(":")[0]);
        assert.deepEqual(domainLines, ["RenderPatches263", "InputPatches263", "TerrainPatches263",
          "WorldgenPatches263", "ServerPatches263", "UiPatches263", "JdkCompatPatches263"]);
        assert.match(result.out,
          new RegExp(String.raw`^Minecraft263BrowserPatcher: profile ${profileIdPattern(RENDERPEARL_PROFILE)}, renderApi=RENDERPEARL .*input=SDL`, "m"));
      }
      assert.doesNotMatch(result.out + result.err, /^(?!BRINGUP_).*\bSkipp(ed|ing)\b/m,
        `${tool}: unregistered skip in the log`);
      skipped.push(...[...result.out.matchAll(/^BRINGUP_SKIP (\S+)$/gm)].map((match) => match[1]));
      if (existsSync(output)) {
        execFileSync(jarTool, ["--update", "--file", stage, "-C", output, "."], {timeout: 120_000});
      }
    }
    for (const id of skipped) assert.ok(listed.has(id), `skip of unlisted id ${id}`);
    console.log(`patch-registry-smoke: ${RENDERPEARL_PROFILE.id} bring-up chain completed, ${skipped.length} listed skips`);
  } else if (requireBringupList) {
    throw new Error(`missing bring-up list ${bringupList263}`);
  } else {
    console.log("patch-registry-smoke: " + RENDERPEARL_PROFILE.id + " bring-up chain not run (no port/tools/bringup/" + RENDERPEARL_PROFILE.id + ".txt)");
  }
  console.log(`patch-registry-smoke: OK (MCP ${mcpIds.length} and M262 ${m262Ids.length} wrapped calls)`);
} finally {
  await rm(work, {recursive: true, force: true});
}
