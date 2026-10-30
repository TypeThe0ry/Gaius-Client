import {execFile} from "node:child_process";
import {promisify} from "node:util";
import {readFile} from "node:fs/promises";

const execFileAsync = promisify(execFile);
export const DEFAULT_RELOAD_MEMORY_BUDGET_BYTES = 0.75 * 1024 ** 3;

export function parseMemoryBudget(value, fallback = DEFAULT_RELOAD_MEMORY_BUDGET_BYTES) {
  if (value === undefined || value === null || String(value).trim() === "") return fallback;
  const match = /^([0-9]+(?:\.[0-9]+)?)\s*(bytes?|b|kib|kb|mib|mb|gib|gb)?$/i.exec(String(value).trim());
  if (!match) throw new Error(`invalid reload memory budget: ${value}`);
  const amount = Number(match[1]);
  const unit = (match[2] || "bytes").toLowerCase();
  const multiplier = {b: 1, byte: 1, bytes: 1, kib: 1024, kb: 1000,
    mib: 1024 ** 2, mb: 1000 ** 2, gib: 1024 ** 3, gb: 1000 ** 3}[unit];
  const bytes = amount * multiplier;
  if (!Number.isFinite(bytes) || bytes < 0) throw new Error(`invalid reload memory budget: ${value}`);
  return Math.floor(bytes);
}

export function reloadMemoryGate(samples, budgetBytes = DEFAULT_RELOAD_MEMORY_BUDGET_BYTES) {
  const budget = parseMemoryBudget(budgetBytes);
  const series = Array.isArray(samples) ? samples : [];
  const first = series.length ? (series[0]?.rendererPrivateBytes ?? null) : null;
  const last = series.length ? (series.at(-1)?.rendererPrivateBytes ?? null) : null;
  const growth = Number.isFinite(first) && Number.isFinite(last) ? last - first : null;
  const pass = growth !== null && growth <= budget;
  return {
    budgetBytes: budget,
    firstPrivateBytes: first,
    lastPrivateBytes: last,
    firstRendererPrivateBytes: first,
    lastRendererPrivateBytes: last,
    growthBytes: growth,
    measuredCycles: series.filter(sample => Number.isFinite(sample?.rendererPrivateBytes)).length,
    pass,
    reason: growth === null ? "private browser bytes unavailable" : (pass ? null : `private browser bytes grew ${growth} bytes`),
  };
}

export async function processPrivateBytes(pid) {
  if (!Number.isInteger(Number(pid)) || Number(pid) <= 0) return null;
  const numericPid = Number(pid);
  try {
    if (process.platform === "win32") {
      const script = `[Console]::Write((Get-Process -Id ${numericPid} -ErrorAction Stop).PrivateMemorySize64)`;
      const {stdout} = await execFileAsync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", script], {windowsHide: true, timeout: 5000});
      const bytes = Number(String(stdout).trim());
      return Number.isFinite(bytes) ? bytes : null;
    }
    const smaps = await readFile(`/proc/${numericPid}/smaps_rollup`, "utf8");
    let bytes = 0;
    for (const line of smaps.split("\n")) {
      const match = /^(Private_Clean|Private_Dirty):\s+(\d+)\s+kB$/.exec(line);
      if (match) bytes += Number(match[2]) * 1024;
    }
    return bytes || null;
  } catch {
    return null;
  }
}

export async function killProcessTree(pid) {
  if (!Number.isInteger(Number(pid)) || Number(pid) <= 0) return;
  try {
    if (process.platform === "win32") {
      await execFileAsync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {windowsHide: true, timeout: 10000});
    } else {
      try { process.kill(-Number(pid), "SIGKILL"); }
      catch { process.kill(Number(pid), "SIGKILL"); }
    }
  } catch {
    // The process may have exited between the status check and cleanup.
  }
}
