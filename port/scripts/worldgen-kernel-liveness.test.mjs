import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../web/kernels/worldgen-kernel.js', import.meta.url), 'utf8');

function facadeFor(submit, clock = {now: 0}) {
  const timers = new Map();
  let nextTimer = 1;
  const context = {
    __gaiusWorldgenKernelConfig: {host: 'shared', jobDeadlineMs: 5},
    GaiusKernelMirror: {enabled: () => true},
    __gaiusKernelClient: {available: () => true, submit},
    GaiusWorldgenJob: {
      generator: (_key, ir) => ir,
      terrain: (runner, _handle, _x, _z, _options, jobOptions) =>
        runner.submit('TERRAIN', {}, jobOptions),
    },
    performance: {now: () => clock.now},
    setInterval(callback) { const id = nextTimer++; timers.set(id, callback); return id; },
    clearInterval(id) { timers.delete(id); },
    queueMicrotask,
  };
  vm.runInNewContext(source, context, {filename: 'worldgen-kernel.js'});
  const kernel = context.GaiusWorldgenKernel;
  assert.equal(kernel.registerGenerator(1, new Uint8Array([1])), true);
  return {
    kernel,
    tickDeadlines() { for (const callback of [...timers.values()]) callback(); },
    clock,
  };
}

const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

async function fallbackCase(name, submit, advance = async () => {}) {
  const facade = facadeFor(submit);
  const events = [];
  facade.kernel.submitTerrain(1, 4, 7, 0, null,
    (result) => events.push(['result', result]), (reason) => events.push(['vanilla', reason]));
  await flush();
  await advance(facade);
  await flush();
  assert.deepEqual(events.map(([kind]) => kind), ['vanilla'], `${name}: one vanilla fallback`);
  assert.equal(facade.kernel.pending(), 0, `${name}: pending job released`);
  assert.equal(facade.kernel.telemetry().transient, 1, `${name}: transient decline`);
  return facade;
}

await fallbackCase('never-arrives', () => new Promise(() => {}), async (facade) => {
  facade.clock.now = 6;
  facade.tickDeadlines();
});

for (const code of ['cancelled', 'terminated', 'request-cap']) {
  const error = new Error(code);
  error.code = code;
  await fallbackCase(code, () => Promise.reject(error));
}

// A timeout settles the chunk exactly once; a late worker completion is ignored and the next
// request still reaches its own completion path.
let finishFirst;
let calls = 0;
const facade = facadeFor(() => {
  calls++;
  return calls === 1 ? new Promise((resolve) => { finishFirst = resolve; })
    : Promise.reject(Object.assign(new Error('request-cap'), {code: 'request-cap'}));
});
const settled = [];
facade.kernel.submitTerrain(1, 1, 1, 0, null, () => settled.push('first-result'), () => settled.push('first-vanilla'));
await flush();
facade.clock.now = 6;
facade.tickDeadlines();
await flush();
finishFirst({ignoredLateValue: true});
await flush();
facade.kernel.submitTerrain(1, 2, 1, 0, null, () => settled.push('second-result'), () => settled.push('second-vanilla'));
await flush();
assert.deepEqual(settled, ['first-vanilla', 'second-vanilla']);
assert.equal(facade.kernel.pending(), 0);
assert.equal(facade.kernel.telemetry().late, 0, 'late result is dropped before settlement');
console.log('worldgen kernel liveness: missing result, cancellation, worker termination, request-cap and late-result checks passed');
