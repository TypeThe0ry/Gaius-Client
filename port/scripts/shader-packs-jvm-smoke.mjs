#!/usr/bin/env node
// Compile the shader settings client seam against every supported vanilla jar; no TeaVM build.
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {homedir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";
import {loadProfiles, sourceFile, stageSources} from "../tools/sourcesets/sourcesets.mjs";

const root = fileURLToPath(new URL("../../", import.meta.url));
const args = process.argv.slice(2);
assert.ok(args.length === 0 || (args.length === 2 && args[0] === "--out"),
  "usage: node port/scripts/shader-packs-jvm-smoke.mjs [--out DIR]");
const scratch = args[1] ?? mkdtempSync(join(root, "port/work/shader-packs-compile-"));
mkdirSync(scratch, {recursive: true});
const tool = (name) => join(process.env.GAIUS_JAVA_HOME ?? process.env.JAVA_HOME ?? "/usr", "bin", name);
try {
  for (const profile of loadProfiles(root)) {
    const work = join(root, "port/work", profile.id);
    const directory = join(scratch, profile.id);
    const sources = join(directory, "sources");
    const classes = join(directory, "classes");
    mkdirSync(classes, {recursive: true});
    stageSources(profile, "client", "wasmgc", sources, root);
    // These files must resolve identically for both TeaVM targets.
    const names = ["BrowserShaderPacks", "BrowserShaderPacksScreen", "BrowserScreens"];
    for (const name of names) {
      const relative = `dev/gaius/browser/${name}.java`;
      assert.equal(sourceFile(profile, "client", relative, {root, target: "js"}).text,
        sourceFile(profile, "client", relative, {root, target: "wasmgc"}).text);
    }
    const dependencies = readFileSync(join(work, "classpath.txt"), "utf8").trim()
      .split(delimiter).filter(Boolean).map((entry) => {
        // Cached classpaths can refer to another checkout; use this profile's local libraries.
        if (!existsSync(entry) && entry.includes("/libraries/")) {
          return join(work, "libraries", entry.split("/libraries/")[1]);
        }
        return entry;
      });
    const version = JSON.parse(readFileSync(join(root, "port/config.json"), "utf8")).teaVMVersion;
    const repository = process.env.MAVEN_REPOSITORY ?? join(homedir(), ".m2/repository");
    const jso = ["teavm-jso", "teavm-jso-apis", "teavm-interop"].map((artifact) =>
      join(repository, "org/teavm", artifact, process.env.GAIUS_TEAVM_VERSION ?? version,
        `${artifact}-${process.env.GAIUS_TEAVM_VERSION ?? version}.jar`));
    const command = ["-J-Xmx3g", "--release", "21", "-proc:none", "-sourcepath", sources,
      "-classpath", [join(work, "client-named.jar"), ...dependencies, ...jso].join(delimiter),
      "-d", classes, ...names.map((name) => join(sources, "dev/gaius/browser", `${name}.java`))];
    writeFileSync(join(directory, "compile-command.json"), JSON.stringify(command, null, 2) + "\n");
    try {
      const output = execFileSync(tool("javac"), command, {encoding: "utf8", timeout: 120_000});
      writeFileSync(join(directory, "compile.log"), output + "exit=0\n");
    } catch (error) {
      writeFileSync(join(directory, "compile.log"), `${error.stdout ?? ""}${error.stderr ?? ""}`);
      throw error;
    }
    console.log(`SHADER_PACKS_COMPILE PASS profile=${profile.id}`);
  }
} finally {
  if (!args[1]) rmSync(scratch, {recursive: true, force: true});
}
