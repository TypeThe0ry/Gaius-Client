import {test} from "node:test";
import assert from "node:assert/strict";
import {mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, realpathSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";

const script = fileURLToPath(new URL("./release-train.sh", import.meta.url));

test("release requires a readable slot before invoking any work", () => {
  const dir = mkdtempSync(join(tmpdir(), "train-slot-"));
  try {
    const python = join(dir, "python");
    const marker = join(dir, "python-called");
    writeFileSync(python, `#!/bin/bash\nprintf called > "$PY_MARKER"\nexit 1\n`, {mode: 0o755});
    for (const slot of [undefined, "", join(dir, "missing")]) {
      const env = {...process.env, PYTHON: python, PY_MARKER: marker};
      if (slot === undefined) delete env.GAIUS_SLOT; else env.GAIUS_SLOT = slot;
      const result = spawnSync("bash", [script, "--out", join(dir, "out"), "--resources", join(dir, "resources")], {env, encoding: "utf8"});
      assert.equal(result.status, 2);
      assert.match(result.stderr, /GAIUS_SLOT must name a readable slot/);
      assert.equal(existsSync(marker), false);
    }
  } finally { rmSync(dir, {recursive: true, force: true}); }
});

test("dry run always displays a slotted build and executes neither slot nor compiler", () => {
  const dir = mkdtempSync(join(tmpdir(), "train-slot-plan-"));
  try {
    const python = join(dir, "python");
    const slot = join(dir, "slot.sh");
    const marker = join(dir, "slot-called");
    writeFileSync(python, '#!/bin/bash\ncase "$2" in profiles) printf "one\\ntwo\\nthree\\n";; plan) printf "plan\\n";; *) exit 9;; esac\n', {mode: 0o755});
    writeFileSync(slot, '#!/bin/bash\nprintf called > "$SLOT_MARKER"\n', {mode: 0o755});
    for (const useSlot of [false, true]) {
      const env = {...process.env, PYTHON: python, SLOT_MARKER: marker};
      if (useSlot) env.GAIUS_SLOT = slot; else delete env.GAIUS_SLOT;
      const result = spawnSync("bash", [script, "--out", join(dir, "out"), "--resources", join(dir, "resources"), "--js", "rebuild", "--dry-run"], {env, encoding: "utf8"});
      assert.equal(result.status, 0, result.stderr);
      assert.match(result.stdout, useSlot ? /slot\.sh build/ : /required-slot\.sh.*build/);
      assert.equal(existsSync(marker), false);
    }
  } finally { rmSync(dir, {recursive: true, force: true}); }
});

test("release passes its configured slot to both build and acceptance", () => {
  const dir = mkdtempSync(join(tmpdir(), "train slot $()-"));
  try {
    const bin = join(dir, "bin"), python = join(dir, "python"), slot = join(dir, "slot.sh");
    const slotLog = join(dir, "slot-log"), nodeLog = join(dir, "node-log");
    mkdirSync(bin);
    writeFileSync(python, '#!/bin/bash\nif [[ "$2" == profiles ]]; then printf "one\\ntwo\\nthree\\n"; fi\nexit 0\n', {mode: 0o755});
    // This stand-in records the request and never executes the compiler argument.
    writeFileSync(slot, '#!/bin/bash\nprintf "%s\\0" "$@" > "$SLOT_LOG"\nexit "${SLOT_EXIT:-0}"\n', {mode: 0o755});
    writeFileSync(join(bin, "node"), '#!/bin/bash\nprintf "%s|%s\\n" "$*" "$GAIUS_SLOT" >> "$NODE_LOG"\nexit 0\n', {mode: 0o755});
    const env = {...process.env, PYTHON: python, GAIUS_SLOT: slot, GAIUS_TRAIN_TAG: "custom tag", SLOT_LOG: slotLog,
      NODE_LOG: nodeLog, PATH: `${bin}:${process.env.PATH}`};
    const result = spawnSync("bash", [script, "--out", join(dir, "out"), "--resources", join(dir, "resources"), "--js", "rebuild"], {env, encoding: "utf8"});
    assert.equal(result.status, 0, result.stderr);
    const args = readFileSync(slotLog, "utf8").split("\0").slice(0, -1);
    assert.deepEqual(args.slice(0, 3), ["build", "custom tag", "--"]);
    assert.equal(args[3], python);
    assert.equal(args[5], "compile");
    const acceptance = readFileSync(nodeLog, "utf8").split("\n").find(line => line.startsWith("port/scripts/release-acceptance.mjs "));
    assert.ok(acceptance);
    assert.equal(acceptance.split("|").at(-1), realpathSync(slot));
    writeFileSync(nodeLog, "");
    const failed = spawnSync("bash", [script, "--out", join(dir, "out-failed"), "--resources", join(dir, "resources"), "--js", "rebuild"],
      {env: {...env, SLOT_EXIT: "41"}, encoding: "utf8"});
    assert.equal(failed.status, 41, failed.stderr);
    assert.doesNotMatch(readFileSync(nodeLog, "utf8"), /port\/scripts\/release-acceptance\.mjs/);
  } finally { rmSync(dir, {recursive: true, force: true}); }
});

test("gates-only does not normalize or invoke the supplied slot", () => {
  const dir = mkdtempSync(join(tmpdir(), "train-slot-gates-"));
  try {
    const bin = join(dir, "bin"), python = join(dir, "python"), marker = join(dir, "slot-normalized");
    const unusedSlot = join(dir, "unused-slot");
    mkdirSync(bin);
    writeFileSync(python, '#!/bin/bash\nif [[ "$2" == profiles ]]; then printf "one\\ntwo\\nthree\\n"; fi\nexit 0\n', {mode: 0o755});
    writeFileSync(join(bin, "node"), '#!/bin/bash\nexit 0\n', {mode: 0o755});
    writeFileSync(join(bin, "cygpath"), '#!/bin/bash\nif [[ "$2" == "$UNUSED_SLOT" ]]; then printf called > "$MARKER"; exit 1; fi\nprintf "%s\\n" "$2"\n', {mode: 0o755});
    const env = {...process.env, PYTHON: python, GAIUS_SLOT: unusedSlot, UNUSED_SLOT: unusedSlot,
      MARKER: marker, PATH: `${bin}:${process.env.PATH}`};
    const result = spawnSync("bash", [script, "--out", join(dir, "out"), "--resources", join(dir, "resources"), "--js", "rebuild", "--gates-only"], {env, encoding: "utf8"});
    assert.equal(result.status, 0, result.stderr);
    assert.equal(existsSync(marker), false);
  } finally { rmSync(dir, {recursive: true, force: true}); }
});
