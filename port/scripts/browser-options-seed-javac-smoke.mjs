#!/usr/bin/env node
// Compile the exact BrowserFilePersistence.seedDefaultOptions() source body with a small fixture
// and verify that a singleplayer server Worker (an active world id) never seeds options.txt,
// while the page still seeds it when it is missing. The Worker restores only its world, so a
// seed there would write defaults over the player's options in the shared store.
// node port/scripts/browser-options-seed-javac-smoke.mjs <output-dir>

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import {join, resolve} from "node:path";
import {tmpdir} from "node:os";
import {fileURLToPath} from "node:url";

const repository = fileURLToPath(new URL("../..", import.meta.url));
const output = resolve(process.argv[2] || join(tmpdir(), "gaius-browser-options-seed-javac"));
const source = await readFile(join(repository,
  "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java"), "utf8");
const declaration = source.indexOf("private static void seedDefaultOptions() {");
assert.notEqual(declaration, -1, "BrowserFilePersistence.seedDefaultOptions() exists");
const open = source.indexOf("{", declaration);
let depth = 0, close = -1;
for (let i = open; i < source.length; i++) {
  if (source[i] === "{") depth++;
  else if (source[i] === "}" && --depth === 0) { close = i + 1; break; }
}
assert.ok(close > open, "seedDefaultOptions body is balanced");
const seed = source.slice(declaration, close);

const fixture = `import java.util.*;
public final class BrowserOptionsSeedJavacSmoke {
  static final String OPTIONS_PATH = "/gaius/options.txt";
  static final List<String> calls = new ArrayList<>();
  static String worldId;
  static boolean optionsPresent;
  interface VirtualFile { boolean isFile(); }
  interface VirtualFileSystem { VirtualFile getFile(String path); }
  static final class VirtualFileSystemProvider {
    static VirtualFileSystem getInstance() {
      return path -> optionsPresent && OPTIONS_PATH.equals(path) ? () -> true : null;
    }
  }
  static String activeServerWorldId() { return worldId; }
  static boolean migrateLegacyDefaultOptions(VirtualFile existing) { calls.add("migrate"); return true; }
  static void reconcileAutomaticPreset(VirtualFile existing) { calls.add("reconcile"); }
  static void writeDefaultOptions(String detail) { calls.add("write"); }
  static void report(String event, String detail) { calls.add("report:" + event); }
  static String describe(Throwable exception) { return String.valueOf(exception); }
  ${seed}
  static List<String> run(String world, boolean present) {
    calls.clear();
    worldId = world;
    optionsPresent = present;
    seedDefaultOptions();
    return new ArrayList<>(calls);
  }
  public static void main(String[] args) {
    if (!run(null, false).equals(List.of("write"))) throw new AssertionError("the page did not seed missing options");
    if (!run("", false).equals(List.of("write"))) throw new AssertionError("an empty world id is the page");
    if (!run(null, true).equals(List.of("migrate"))) throw new AssertionError("the page did not migrate stored options");
    if (!run("New World", false).isEmpty()) throw new AssertionError("a server Worker wrote default options");
    if (!run("New World", true).isEmpty()) throw new AssertionError("a server Worker rewrote stored options");
    System.out.println("BrowserFilePersistence.seedDefaultOptions() javac Worker/page PASS");
  }
}
`;
const sourceDir = join(output, "src");
const classesDir = join(output, "classes");
await mkdir(sourceDir, {recursive: true});
await mkdir(classesDir, {recursive: true});
const javaFile = join(sourceDir, "BrowserOptionsSeedJavacSmoke.java");
await writeFile(javaFile, fixture, "utf8");
execFileSync("javac", ["--release", "21", "-proc:none", "-d", classesDir, javaFile], {stdio: "inherit"});
execFileSync("java", ["-cp", classesDir, "BrowserOptionsSeedJavacSmoke"], {stdio: "inherit"});
