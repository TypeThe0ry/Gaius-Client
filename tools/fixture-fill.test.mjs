import test from "node:test";
import assert from "node:assert/strict";
import {fillUntilLoaded} from "./fixture-fill.mjs";

// A server whose tick is held until `busyUntil` (virtual ms) and that rejects /fill with
// "That position is not loaded" until `loadedAt`. Answers appear when the held tick resumes.
function fakeServer({busyUntil = 0, loadedAt = 0}) {
  const clock = {t: 0};
  const sent = [];
  const sleep = async ms => {clock.t += ms;};
  const send = async () => {
    const sentAt = clock.t;
    sent.push(sentAt);
    return () => {
      const answerAt = Math.max(sentAt, busyUntil);
      if (clock.t < answerAt) return [];
      return [answerAt >= loadedAt ? "[CHAT] Successfully filled 18 block(s)" : "[CHAT] That position is not loaded"];
    };
  };
  return {clock, sent, sleep, send, now: () => clock.t};
}

test("an immediate answer is taken after one send", async () => {
  const s = fakeServer({});
  const result = await fillUntilLoaded(s);
  assert.equal(result.ok, true);
  assert.equal(s.sent.length, 1);
});

test("not loaded answers are repeated until the chunks are full", async () => {
  const s = fakeServer({loadedAt: 9000});
  const result = await fillUntilLoaded(s);
  assert.equal(result.ok, true);
  assert.ok(s.sent.length > 1);
  assert.match(result.attempts[0].answer, /not loaded/);
  assert.match(result.attempts.at(-1).answer, /Successfully filled/);
});

test("a silent server is asked once per silence window, not on every poll", async () => {
  const s = fakeServer({busyUntil: 150000});
  const result = await fillUntilLoaded({...s, silenceMs: 60000});
  assert.equal(result.ok, true);
  assert.deepEqual(s.sent, [0, 60000, 120000]);
});

test("a server that never loads the area ends not ok at the deadline", async () => {
  const s = fakeServer({loadedAt: Infinity});
  const result = await fillUntilLoaded({...s, timeoutMs: 20000});
  assert.equal(result.ok, false);
  assert.ok(s.clock.t >= 20000 && s.clock.t < 30000);
});
