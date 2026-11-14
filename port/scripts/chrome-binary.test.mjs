import assert from "node:assert/strict";
import {test} from "node:test";
import {chromeGpuArgs, chromeLaunchArgs, chromePlatformArgs, DEFAULT_LINUX_GPU_FLAGS, LINUX_GPU_FLAGS_ENV, resolveChromeBinary} from "./chrome-binary.mjs";
import {countLinuxProcesses, linuxCpuLoadPercent, parseLinuxCpuStat, parseLinuxProcessTable} from "./linux-process-snapshot.mjs";

test("Chrome resolution honors explicit aliases and checks that they exist", () => {
  const available = new Set(["/custom/chrome", "/usr/bin/google-chrome"]);
  const exists = path => available.has(path);
  const access = () => {};
  assert.equal(resolveChromeBinary({platform: "linux", env: {PATH: ""}, overrides: ["/custom/chrome"], exists, access}), "/custom/chrome");
  assert.throws(() => resolveChromeBinary({platform: "linux", env: {PATH: ""}, overrides: ["/missing/chrome"], exists, access}), /override not found/);
  assert.equal(resolveChromeBinary({platform: "linux", env: {PATH: ""}, exists, access}), "/usr/bin/google-chrome");
});

test("Windows keeps its standard Chrome installation path", () => {
  const expected = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
  const path = resolveChromeBinary({platform: "win32", env: {PATH: ""},
    exists: candidate => candidate === expected});
  assert.equal(path, "C:/Program Files/Google/Chrome/Application/chrome.exe");
});

test("Linux Chrome GPU flags default to Vulkan and accept explicit replacement or disable", () => {
  assert.deepEqual(chromeGpuArgs({platform: "linux", env: {}}), DEFAULT_LINUX_GPU_FLAGS);
  assert.deepEqual(chromeGpuArgs({platform: "linux", env: {[LINUX_GPU_FLAGS_ENV]: "[]"}}), []);
  assert.deepEqual(chromeGpuArgs({platform: "linux", env: {[LINUX_GPU_FLAGS_ENV]: '["--use-angle=swiftshader"]'}}), ["--use-angle=swiftshader"]);
  assert.deepEqual(chromeGpuArgs({platform: "win32", env: {[LINUX_GPU_FLAGS_ENV]: "invalid"}}), []);
  assert.throws(() => chromeGpuArgs({platform: "linux", env: {[LINUX_GPU_FLAGS_ENV]: '["bad"]'}}), /JSON array of Chrome flags/);
});

test("Linux Chrome gets the sandbox flag as root and always the basic password store", () => {
  assert.deepEqual(chromePlatformArgs({platform: "linux", userId: 0}), ["--no-sandbox", "--password-store=basic"]);
  assert.deepEqual(chromePlatformArgs({platform: "linux", userId: 1000}), ["--password-store=basic"]);
  assert.deepEqual(chromePlatformArgs({platform: "win32", userId: 0}), []);
  assert.deepEqual(chromeLaunchArgs({platform: "linux", userId: 0, env: {[LINUX_GPU_FLAGS_ENV]: "[]"}}),
    ["--no-sandbox", "--password-store=basic"]);
});

test("Linux idle process snapshot parsing preserves acceptance fields", () => {
  const before = parseLinuxCpuStat("cpu 100 10 30 200 5 2 3 0 0 0\n");
  const after = parseLinuxCpuStat("cpu 130 10 40 250 10 2 3 0 0 0\n");
  assert.equal(linuxCpuLoadPercent(before, after), 42.1);
  const processes = parseLinuxProcessTable([
    "  12 java java -jar server.jar",
    "  13 chrome /usr/bin/google-chrome --headless=new --remote-debugging-port=0",
    "  14 chrome /usr/bin/google-chrome --type=renderer",
    "  15 cargo cargo build",
    "  16 javac javac Main.java",
  ].join("\n"));
  assert.deepEqual(countLinuxProcesses(processes), {java: 1, headlessChrome: 1, desktopChrome: 1, build: 2});
});
