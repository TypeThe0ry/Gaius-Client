#!/usr/bin/env node
// Compile the exact BrowserFilePersistence persist(), shouldPersist() and shouldRestoreAtStartup()
// source with a small fixture and check the screenshot contract: a screenshot goes to the page's
// screenshot store as bytes (never as a base64 mirror entry), a refused one reports failure, a
// page without the store keeps the legacy mirror, and startup never restores screenshots into
// memory while options and world summaries are still restored.
// node port/scripts/browser-screenshot-persistence-javac-smoke.mjs <output-dir>

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import {join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const repository = fileURLToPath(new URL("../..", import.meta.url));
const output = resolve(process.argv[2] || "browser-screenshot-persistence-javac");
const source = await readFile(join(repository,
  "port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java"), "utf8");

function method(signature) {
  const declaration = source.indexOf(signature);
  assert.notEqual(declaration, -1, `${signature} exists`);
  const open = source.indexOf("{", declaration);
  let depth = 0;
  for (let index = open; index < source.length; index++) {
    if (source[index] === "{") depth++;
    else if (source[index] === "}" && --depth === 0) return source.slice(declaration, index + 1);
  }
  throw new Error(`${signature} is unbalanced`);
}

const methods = [
  "public static boolean persist(String path, byte[] bytes) {",
  "private static boolean shouldPersist(String path) {",
  "private static boolean shouldRestoreAtStartup(String path) {",
].map(method).join("\n\n");

const fixture = `import java.util.*;
public final class BrowserScreenshotPersistenceJavacSmoke {
  static final String SCREENSHOT_ROOT = "/gaius/screenshots/";
  static final List<String> calls = new ArrayList<>();
  static int screenshotStore;
  static String normalize(String path) { return path; }
  static String activeServerWorldId() { return null; }
  static boolean isOnDemandChunkStoragePath(String path) { return false; }
  static boolean isBinaryChunkStoragePath(String path) { return path.endsWith(".mca"); }
  static boolean isDownloadedPackFile(String path) { return path.startsWith("/gaius/downloads/"); }
  static boolean isScreenshotPath(String path) { return path.startsWith(SCREENSHOT_ROOT); }
  static boolean setBytes(String path, byte[] bytes) { calls.add("bytes:" + path); return true; }
  static void markDownloadedPackPersisted(String path, int length) {}
  static int storeScreenshot(String path, byte[] bytes) {
    calls.add("screenshot:" + path + ":" + bytes.length);
    return screenshotStore;
  }
  static String storagePrefix() { return "gaius.fs.test:"; }
  static boolean setItem(String key, String value) { calls.add("mirror:" + key); return true; }
  static void report(String event, String detail) { calls.add("report:" + event); }
  ${methods}
  static List<String> persistWith(int store, String path) {
    calls.clear();
    screenshotStore = store;
    calls.add("result:" + persist(path, new byte[] {1, 2, 3}));
    return new ArrayList<>(calls);
  }
  static void expect(List<String> actual, List<String> expected, String message) {
    if (!actual.equals(expected)) throw new AssertionError(message + ": " + actual);
  }
  public static void main(String[] args) {
    String shot = "/gaius/screenshots/2026-10-15_12.00.00.png";
    expect(persistWith(1, shot), List.of("screenshot:" + shot + ":3", "result:true"),
        "a screenshot is stored as bytes without a base64 mirror entry");
    expect(persistWith(0, shot), List.of("screenshot:" + shot + ":3",
        "report:storage-screenshot-not-stored", "result:false"), "a refused screenshot is a failed write");
    expect(persistWith(-1, shot), List.of("screenshot:" + shot + ":3",
        "mirror:gaius.fs.test:" + shot, "result:true"), "a page without the store keeps the legacy mirror");
    expect(persistWith(1, "/gaius/options.txt"), List.of("mirror:gaius.fs.test:/gaius/options.txt", "result:true"),
        "other files keep the mirror");
    if (shouldRestoreAtStartup(shot)) throw new AssertionError("startup restored a screenshot into memory");
    if (!shouldRestoreAtStartup("/gaius/options.txt")) throw new AssertionError("options.txt is no longer restored");
    if (!shouldRestoreAtStartup("/gaius/saves/World/level.dat")) throw new AssertionError("level.dat is no longer restored");
    System.out.println("BrowserFilePersistence screenshot persistence javac PASS");
  }
}
`;
const sourceDir = join(output, "src");
const classesDir = join(output, "classes");
await mkdir(sourceDir, {recursive: true});
await mkdir(classesDir, {recursive: true});
const javaFile = join(sourceDir, "BrowserScreenshotPersistenceJavacSmoke.java");
await writeFile(javaFile, fixture, "utf8");
execFileSync("javac", ["--release", "21", "-proc:none", "-d", classesDir, javaFile], {stdio: "inherit"});
execFileSync("java", ["-cp", classesDir, "BrowserScreenshotPersistenceJavacSmoke"], {stdio: "inherit"});
