import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {test} from "node:test";
import {fileURLToPath} from "node:url";

const helper = fileURLToPath(new URL("./teavm-role-option.sh", import.meta.url)).replaceAll("\\", "/");

test("build identity uses the effective worker telemetry option", () => {
  const cases = [
    [{GAIUS_TEAVM_STRIP_TELEMETRY: "true", GAIUS_TEAVM_SINGLEPLAYER_WORKER_STRIP_TELEMETRY: "false"}, "true"],
    [{GAIUS_TEAVM_STRIP_TELEMETRY: "true", GAIUS_TEAVM_WORKER_STRIP_TELEMETRY: "false"}, "false"],
    [{GAIUS_TEAVM_STRIP_TELEMETRY: "false", GAIUS_TEAVM_WORKER_STRIP_TELEMETRY: "true"}, "true"],
    [{}, "false"],
  ];
  for (const [flags, expected] of cases) {
    const result = spawnSync("bash", ["-c", 'source "$1"; gaius_teavm_role_option WORKER STRIP_TELEMETRY false', "bash", helper],
      {encoding: "utf8", env: {...process.env, GAIUS_TEAVM_STRIP_TELEMETRY: "",
        GAIUS_TEAVM_WORKER_STRIP_TELEMETRY: "", GAIUS_TEAVM_SINGLEPLAYER_WORKER_STRIP_TELEMETRY: "", ...flags}});
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), expected);
  }
});
