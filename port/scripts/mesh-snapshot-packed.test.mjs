import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";

function pack(values, bits) {
  const perWord = Math.floor(64 / bits);
  const words = new BigUint64Array(Math.ceil(values.length / perWord));
  const mask = (1n << BigInt(bits)) - 1n;
  for (let i = 0; i < values.length; i++) {
    const word = Math.floor(i / perWord);
    const shift = (i % perWord) * bits;
    words[word] |= (BigInt(values[i]) & mask) << BigInt(shift);
  }
  return words;
}

function packed(raw, bits, index) {
  if (bits === 0) return 0;
  const perWord = Math.floor(64 / bits);
  const word = Math.floor(index / perWord);
  const shift = (index - word * perWord) * bits;
  const mask = bits === 32 ? 0xffffffff : (1 << bits) - 1;
  return Number((raw[word] >> BigInt(shift)) & BigInt(mask));
}

test("packed state decoder preserves every palette index", () => {
  for (let bits = 1; bits <= 16; bits++) {
    const size = 4096;
    const limit = 2 ** Math.min(bits, 20);
    const values = Array.from({length: size}, (_, i) => (i * 37 + 11) % limit);
    const raw = pack(values, bits);
    for (const index of [0, 1, 15, 16, 63, 64, 255, 256, 4094, 4095]) {
      assert.equal(packed(raw, bits, index), values[index], `bits=${bits} index=${index}`);
    }
  }
});

test("fast snapshot path is switchable and keeps vanilla fallbacks", () => {
  const source = readFileSync(new URL("../mc/shared/canonical/src/java/dev/gaius/browser/kernel/mesh/SectionSnapshot.java",
    import.meta.url), "utf8");
  assert.match(source, /storage\.getRaw\(\)/u);
  assert.match(source, /MeshKernelBridge\.fastSnapshotEnabled\(\)/u);
  assert.match(source, /storage\.get\(row \| x\)/u);
  assert.match(source, /gaius\$blockEntities\(\)/u);
});

test("default-off snapshot switch is read once and packed scratch stays lazy", () => {
  const bridge = readFileSync(new URL("../src/main/java/dev/gaius/browser/kernel/mesh/MeshKernelBridge.java",
    import.meta.url), "utf8");
  const buffers = readFileSync(new URL("../src/main/java/dev/gaius/browser/kernel/mesh/MeshJobBuffers.java",
    import.meta.url), "utf8");
  const snapshot = readFileSync(new URL("../mc/shared/canonical/src/java/dev/gaius/browser/kernel/mesh/SectionSnapshot.java",
    import.meta.url), "utf8");
  const launcher = readFileSync(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
  assert.match(bridge, /private static final boolean FAST_SNAPSHOT_ENABLED = fastSnapshotEnabledJs\(\)/u);
  assert.match(bridge, /return FAST_SNAPSHOT_ENABLED;/u);
  assert.match(bridge, /__gaiusLauncherFlags[\s\S]{0,120}meshSnapshotFast/u);
  assert.doesNotMatch(bridge, /URLSearchParams/u);
  assert.match(launcher, /__gaiusLauncherFlags = Object\.freeze\(\{/u);
  assert.match(launcher, /meshSnapshotFast: launcherBoolean\("gaiusMeshSnapshot"\)/u);
  assert.match(launcher, /chunkPipelineBatching,/u);
  assert.match(launcher, /meshInstallPriority: launcherBoolean\("gaiusMeshInstallPriority"\)/u);
  assert.match(launcher, /meshAdaptiveBudget: launcherBoolean\("gaiusMeshAdaptiveBudget"\)/u);
  assert.match(launcher, /meshReadyBudgetMiB: urlParams\.get\("gaiusMeshReadyBudgetMiB"\)/u);
  assert.match(buffers, /public int\[\] packedStates;\n/u);
  assert.match(buffers, /public int\[\] paletteIds;\n/u);
  assert.match(buffers, /if \(packedStates == null\)\s*\{\s*packedStates = new int\[65536\];/u);
  assert.match(snapshot, /if \(!\(palette instanceof GlobalPalette\) && fast\)/u);
  assert.match(snapshot, /mapVanilla\(value, palette, air, table\.stateCount\)/u);
  assert.doesNotMatch(snapshot, /MeshKernelBridge\.fastSnapshotEnabled\(\)[\s\S]{0,80}new URLSearchParams/u);
});

test("packed and flat state jobs frame the correct state and light offsets", () => {
  const context = vm.createContext({ArrayBuffer, BigInt, DataView, Float32Array, Int32Array, Math, Number,
    RangeError, String, TypeError, Uint8Array, Uint16Array, Uint32Array});
  vm.runInContext(readFileSync(new URL("../web/kernels/mesh-job.js", import.meta.url), "utf8"), context);
  const codec = context.GaiusMeshJob;
  const common = {light: new Uint8Array(8000).fill(0x5a), biomeQuarts: new Uint8Array(216),
    biomePalette: new Int32Array(5), swampMask: new Uint8Array(32), vanilla: true};
  const packedStates = new Int32Array([0, 27, 7, -1, 0]);
  const packed = codec.meshSection({...common, packedStates}, 1);
  const packedView = new DataView(packed);
  assert.equal(packedView.getUint32(16 + 12, true) & codec.FLAGS.PACKED_STATES,
    codec.FLAGS.PACKED_STATES);
  const packedCountAt = 16 + 96;
  assert.equal(packedView.getUint32(packedCountAt, true), packedStates.length);
  assert.deepEqual([...new Int32Array(packed, packedCountAt + 4, packedStates.length)], [...packedStates]);
  const packedLightAt = packedCountAt + 4 + packedStates.byteLength;
  assert.equal(new Uint8Array(packed)[packedLightAt], 0x5a);

  const states = new Uint16Array(8000);
  states[0] = 23;
  const flat = codec.meshSection({...common, states}, 2);
  const flatView = new DataView(flat);
  assert.equal(flatView.getUint32(16 + 12, true) & codec.FLAGS.PACKED_STATES, 0);
  assert.equal(new Uint16Array(flat, 16 + 96, 8000)[0], 23);
  assert.equal(new Uint8Array(flat)[16 + 96 + 16000], 0x5a);
});
