import assert from "node:assert/strict";
import {appendFileSync} from "node:fs";
import {join} from "node:path";
import {spawnSync} from "node:child_process";

export function waitForHeadroom(output, label) {
  const minimumGiB = Number(process.env.GAIUS_JVM_MIN_HEADROOM_GIB || 18);
  assert(Number.isSafeInteger(minimumGiB) && minimumGiB >= 8, "JVM commit headroom must be at least 8 GiB");
  if (process.platform !== "win32") return;
  const deadline = Date.now() + 60 * 60 * 1000;
  const wait = new Int32Array(new SharedArrayBuffer(4));
  for (;;) {
    const result = spawnSync("powershell.exe", ["-NoProfile", "-Command",
      "$memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory; [Console]::Write($memory.CommitLimit-$memory.CommittedBytes)"],
    {windowsHide: true, encoding: "utf8"});
    const bytes = Number(result.stdout);
    assert.equal(result.status, 0, "could not inspect Windows commit headroom");
    assert(/^-?\d+$/.test(result.stdout.trim()) && Number.isFinite(bytes), "invalid Windows commit headroom");
    appendFileSync(join(output, "headroom.jsonl"), JSON.stringify({label, at: new Date().toISOString(), minimumGiB, headroomGiB: bytes / 2 ** 30}) + "\n");
    if (bytes >= minimumGiB * 2 ** 30) return;
    if (Date.now() >= deadline) throw new Error(`NOT_RUN ${label}: commit headroom below ${minimumGiB} GiB for 60 minutes`);
    Atomics.wait(wait, 0, 0, Math.min(60000, deadline - Date.now()));
  }
}
