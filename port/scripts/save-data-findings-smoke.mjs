import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const launcher = await readFile(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
const boot = await readFile(new URL("../web/boot/gaius-boot.js", import.meta.url), "utf8");
const sw = await readFile(new URL("../web/sw/gaius-sw.js", import.meta.url), "utf8");
const worker = await readFile(new URL("../web/runtime/wasmgc/gaius-wasm-worker.js", import.meta.url), "utf8");

// S5: asynchronous IndexedDB failures must reject the public flush promise and
// update the visible status line; the old catch-only implementation swallowed both.
assert.match(launcher, /storageWriteFailure\s*=\s*error/);
assert.match(launcher, /window\.__gaiusFsFlush\s*=\s*function[\s\S]{0,500}const failure = storageWriteFailure[\s\S]{0,180}storageWriteFailure = null[\s\S]{0,120}if \(failure\) throw failure/);
assert.match(launcher, /保存失败：/);

// S7: persist is requested on a gesture or first save and its result is retained
// in a stable page telemetry object.
assert.match(launcher, /navigator\.storage/);
assert.match(launcher, /storagePersistTelemetry\.result\s*=\s*result === true \? "granted" : "denied"/);
assert.match(launcher, /requestPersistentStorage\("world-save"\)/);

// S9: all gzip entry points inspect 1f 8b before constructing a gzip decoder.
for (const source of [boot, sw, worker]) {
  assert.match(source, /0x1f/);
  assert.match(source, /0x8b/);
}

console.log("SAVE_DATA_FINDINGS_OK");
