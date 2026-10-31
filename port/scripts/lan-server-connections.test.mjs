import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';

const bootstrap = await readFile(new URL('../web/singleplayer/server-worker-bootstrap.js', import.meta.url), 'utf8');
const code = bootstrap.slice(bootstrap.indexOf('function createLanServerConnections('),
  bootstrap.indexOf('\nfunction requestStop()'));
const create = vm.runInNewContext(`(${code})`);
const id = '0123456789abcdef0123456789abcdef';
const host = `lan-server-${id}.gaius-local`;

function fixture() {
  let at = 0, nextTimer = 0, stopped = false, result = true;
  const timers = new Map(), channels = new Map(), opens = [], events = [];
  const controller = create({channels: () => channels, open: value => {
    opens.push({id: value, at});
    if (result instanceof Error) throw result;
    return result;
  }, isStopped: () => stopped, now: () => at,
  notify: (...args) => events.push(args),
  setTimer: (fn, ms) => { const key = ++nextTimer; timers.set(key, {fn, at: at + ms}); return key; },
  clearTimer: key => timers.delete(key)});
  const advance = ms => {
    const until = at + ms;
    for (;;) {
      const due = [...timers].filter(([, t]) => t.at <= until).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      at = due[1].at;
      timers.delete(due[0]);
      due[1].fn();
    }
    at = until;
  };
  return {controller, channels, opens, events, timers, advance,
    result(value) { result = value; }, shutdown() { stopped = true; }};
}

test('registration grace, duplicate requests, connecting and connected endpoints never duplicate', () => {
  const f = fixture();
  assert.equal(f.controller.request(id), true);
  assert.equal(f.controller.request(id), true);
  f.advance(14000);
  assert.equal(f.opens.length, 1);
  f.channels.set(1, {host, closed: false, connected: false});
  f.advance(60000);
  assert.equal(f.opens.length, 1);
  f.channels.get(1).connected = true;
  f.advance(60000);
  assert.equal(f.opens.length, 1);
  assert.equal(f.timers.size, 1);
});

test('closed or retired endpoint is replenished with the same invite, without touching local startup', () => {
  for (const retire of [false, true]) {
    const f = fixture();
    f.controller.request(id);
    f.channels.set(1, {host, closed: false, connected: true});
    f.channels.set(2, {host: `server-${id}.gaius-local`, closed: false, connected: true});
    f.advance(250);
    if (retire) f.channels.delete(1); else f.channels.get(1).closed = true;
    f.advance(250);
    assert.equal(f.opens.length, 2);
    assert.equal(f.opens[1].id, id);
    f.advance(10000);
    assert.equal(f.opens.length, 2, 'old closed entry must not duplicate pending registration');
    f.channels.set(3, {host, closed: false, connected: false});
    f.advance(30000);
    assert.equal(f.opens.length, 2);
  }
});

test('failed replenishment backs off and recovers after the relay endpoint returns', () => {
  const f = fixture();
  f.controller.request(id);
  f.channels.set(1, {host, closed: false, connected: true});
  f.advance(250);
  f.channels.get(1).closed = true;
  f.result(false);
  f.advance(250);
  assert.equal(f.opens.length, 2);
  f.advance(999);
  assert.equal(f.opens.length, 2);
  f.advance(1);
  assert.equal(f.opens.length, 3);
  f.advance(1999);
  assert.equal(f.opens.length, 3);
  f.result(new Error('temporarily unavailable'));
  f.advance(1);
  assert.equal(f.opens.length, 4);
  f.result(true);
  f.advance(4000);
  assert.equal(f.opens.length, 5);
  f.channels.set(3, {host, closed: false, connected: true});
  f.advance(30000);
  assert.equal(f.opens.length, 5);
  assert.ok(f.events.some(e => e[0] === 'open-failed'));
});

test('missing initial registration eventually retries rather than silently losing invitation', () => {
  const f = fixture();
  f.controller.request(id);
  f.advance(15000);
  assert.equal(f.opens.length, 1);
  assert.ok(f.events.some(e => e[0] === 'registration-timeout'));
  f.advance(1000);
  assert.equal(f.opens.length, 2);
});

test('stop cancels timer and prevents new opens, including a stop observed by the timer', () => {
  for (const direct of [true, false]) {
    const f = fixture();
    f.controller.request(id);
    if (direct) f.controller.stop(); else { f.shutdown(); f.advance(250); }
    assert.equal(f.timers.size, 0);
    f.advance(60000);
    assert.equal(f.opens.length, 1);
    assert.equal(f.controller.request(id), false);
  }
});

test('initial opener failure is reported to the caller without retaining a retry timer', () => {
  const f = fixture();
  f.result(false);
  assert.equal(f.controller.request(id), false);
  assert.equal(f.timers.size, 0);
  f.result(true);
  assert.equal(f.controller.request(id), true);
  assert.equal(f.opens.length, 2);
});
