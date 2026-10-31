// Runs the built noise kernel against the golden fixtures: every point-sampled normal and
// blended noise line of every profile is encoded with the browser codec (noise-job.js), run
// through run_noise_points exactly as kernel-worker.js calls it, and compared bit for bit.
//
//   node port/native/wasm-fixture-check.mjs [module.wasm] [fixtures-dir]
import {readFileSync} from "node:fs";
import {compareBits, defaultFixtures, defaultWasm, fixtureJobs, loadCodec} from "./js/fixture-jobs.mjs";

const [modulePath = defaultWasm, fixturesDir = defaultFixtures] = process.argv.slice(2);
const fail = (message) => {
  console.error(`wasm fixture check failed: ${message}`);
  process.exit(1);
};

const codec = loadCodec();
const {exports} = new WebAssembly.Instance(new WebAssembly.Module(readFileSync(modulePath)), {});
if (exports.gaius_abi_version() !== codec.ABI_VERSION) fail(`abi version ${exports.gaius_abi_version()}`);

// alloc / copy in / run / copy out / release, as kernel-worker.js does.
function run(job) {
  const input = new Uint8Array(job);
  const ptr = exports.alloc(input.length) >>> 0;
  new Uint8Array(exports.memory.buffer, ptr, input.length).set(input);
  const desc = exports.run_noise_points(ptr, input.length) >>> 0;
  if (desc === 0) fail("null run descriptor");
  const view = new DataView(exports.memory.buffer);
  const status = view.getUint32(desc, true);
  const data = new Uint8Array(exports.memory.buffer, view.getUint32(desc + 4, true), view.getUint32(desc + 8, true));
  const bytes = data.slice();
  exports.release(desc);
  exports.dealloc(ptr, input.length);
  return {status, bytes};
}

const jobs = fixtureJobs(fixturesDir);
if (jobs.length < 100) fail(`only ${jobs.length} fixture lines map to noise_points jobs`);
const perProfile = {};
let points = 0;
jobs.forEach(({label, job, expected}, index) => {
  const {status, bytes} = run(codec.noisePoints(job, index));
  if (status !== 0) fail(`${label}: status ${status}: ${new TextDecoder().decode(bytes)}`);
  const problem = compareBits(codec.readNoisePoints(bytes.buffer, index), expected);
  if (problem) fail(`${label}: ${problem}`);
  perProfile[job.profile] = (perProfile[job.profile] || 0) + 1;
  points += expected.length;
});

// The kernel must refuse a damaged frame instead of answering it.
const damaged = new Uint8Array(codec.noisePoints(jobs[0].job, 1));
damaged[0] ^= 0xff;
if (run(damaged.buffer).status !== 1) fail("a job with a bad magic was not rejected with status 1");

const summary = Object.entries(perProfile).map(([profile, count]) => `${profile}: ${count}`).join(", ");
console.log(`wasm fixture check passed: ${jobs.length} jobs (${points} points) bit-exact [${summary}]`);
