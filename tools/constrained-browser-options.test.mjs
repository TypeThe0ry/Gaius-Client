import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";
import test from "node:test";

const harness = fileURLToPath(new URL("../port/scripts/minecraft-263-singleplayer-cdp.mjs", import.meta.url));

test("invalid constrained browser options fail before launching Chrome or serving files", () => {
  for (const [name, value, message] of [
    ["LOWMEM_STABILITY_MS", "Infinity", "LOWMEM_STABILITY_MS must be a finite number >= 0"],
    ["LOWMEM_STABILITY_MS", "bad", "LOWMEM_STABILITY_MS must be a finite number >= 0"],
    ["LOWMEM_STABILITY_MS", "-1", "LOWMEM_STABILITY_MS must be a finite number >= 0"],
    ["CPU_THROTTLE_RATE", "Infinity", "CPU_THROTTLE_RATE must be a finite number >= 1"],
    ["CPU_THROTTLE_RATE", "0", "CPU_THROTTLE_RATE must be a finite number >= 1"],
    ["CHROME_EXTRA_ARGS_JSON", "{}", "invalid CHROME_EXTRA_ARGS_JSON"],
    ["CHROME_EXTRA_ARGS_JSON", "[1]", "invalid CHROME_EXTRA_ARGS_JSON"],
    ["CHROME_EXTRA_ARGS_JSON", "broken", "invalid CHROME_EXTRA_ARGS_JSON"],
  ]) {
    const env = {...process.env};
    for (const key of ["LOWMEM_STABILITY_MS", "CPU_THROTTLE_RATE", "CHROME_EXTRA_ARGS_JSON"]) delete env[key];
    env[name] = value;
    const result = spawnSync(process.execPath, [harness], {env, encoding: "utf8", timeout: 10000});
    assert.equal(result.error, undefined, `${name}=${value}`);
    assert.equal(result.status, 1, `${name}=${value}`);
    assert.ok(result.stderr.includes(message), result.stderr);
  }
});
