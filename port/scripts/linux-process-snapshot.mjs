import {execFileSync} from "node:child_process";
import {readFile} from "node:fs/promises";
import {setTimeout as sleep} from "node:timers/promises";

export function parseLinuxCpuStat(text) {
  const line = String(text).split("\n", 1)[0];
  const values = line.trim().split(/\s+/).slice(1).map(Number);
  if (values.length < 4 || values.some(value => !Number.isFinite(value) || value < 0)) return null;
  const total = values.reduce((sum, value) => sum + value, 0);
  return {total, idle: values[3] + (values[4] || 0)};
}

export function linuxCpuLoadPercent(before, after) {
  if (!before || !after) return null;
  const total = after.total - before.total;
  const idle = after.idle - before.idle;
  if (total <= 0 || idle < 0 || idle > total) return null;
  return Math.round(((total - idle) / total) * 1000) / 10;
}

export function parseLinuxProcessTable(text) {
  return String(text).split("\n").flatMap(line => {
    const match = /^\s*(\d+)\s+(\S+)\s*(.*)$/.exec(line);
    return match ? [{pid: Number(match[1]), comm: match[2].toLowerCase(), args: match[3]}] : [];
  });
}

export function countLinuxProcesses(processes) {
  const chrome = processes.filter(process => /^(?:chrome|google-chrome|chromium)(?:$|[-_])/.test(process.comm));
  const headlessChrome = chrome.filter(process => /(?:--headless(?:=|\s|$)|--remote-debugging-port(?:=|\s|$))/.test(process.args));
  return {
    java: processes.filter(process => /^java(?:$|[-_])/.test(process.comm)).length,
    headlessChrome: headlessChrome.length,
    desktopChrome: chrome.length - headlessChrome.length,
    build: processes.filter(process => /^(?:cargo|javac)(?:$|[-_])/.test(process.comm)).length,
  };
}

export function listLinuxProcesses() {
  const output = execFileSync("ps", ["-ww", "-eo", "pid=,comm=,args="], {encoding: "utf8", maxBuffer: 8 * 1024 * 1024});
  return parseLinuxProcessTable(output);
}

export async function linuxProcessSnapshot({sampleMs = 500} = {}) {
  const before = parseLinuxCpuStat(await readFile("/proc/stat", "utf8"));
  await sleep(sampleMs);
  const [afterText, processes] = await Promise.all([
    readFile("/proc/stat", "utf8"),
    Promise.resolve(listLinuxProcesses()),
  ]);
  return {
    cpu: linuxCpuLoadPercent(before, parseLinuxCpuStat(afterText)),
    ...countLinuxProcesses(processes),
    at: new Date().toISOString(),
  };
}
