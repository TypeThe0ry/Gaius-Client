// Sends a /fill and waits for the integrated server's answer.
//
// After a far teleport the server regenerates the chunks around the player and may hold its tick
// for tens of seconds. A /fill that arrives before the chunks are full is answered "That position
// is not loaded" and does nothing, so a command that is only sent and never read leaves the walk
// without its platform. This repeats the command until the server has answered with a real result:
// at once after a "not loaded" answer (after retryDelayMs), and, while the server says nothing, at
// most once per silenceMs so queued copies cannot pile up behind a busy tick.
export const FILL_ANSWER = /Successfully filled \d+ block|No blocks were filled|That position is not loaded/i;

// send() issues the command once and resolves to a function that returns the chat lines the server
// produced since the send (read repeatedly while waiting for the answer).
export async function fillUntilLoaded({send, sleep, now = Date.now, timeoutMs = 240000, silenceMs = 60000,
  retryDelayMs = 2000, pollMs = 200}) {
  const startedAt = now(), deadline = startedAt + timeoutMs, attempts = [];
  while (now() < deadline) {
    const read = await send();
    const waitEnd = Math.min(deadline, now() + silenceMs);
    let answer = null;
    while (answer === null && now() < waitEnd) {
      answer = read().find(line => FILL_ANSWER.test(line)) ?? null;
      if (answer === null) await sleep(pollMs);
    }
    attempts.push({answer: answer === null ? null : String(answer).trim(), atSeconds: +((now() - startedAt) / 1000).toFixed(1)});
    if (answer !== null && !/not loaded/i.test(answer)) return {ok: true, attempts};
    if (answer !== null) await sleep(retryDelayMs);
  }
  return {ok: false, attempts};
}
