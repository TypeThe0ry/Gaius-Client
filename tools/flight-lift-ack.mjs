// A flight-preparation teleport is followed immediately by vanilla gravity.
// Confirm its transient position; later flight and fixture checks remain separate.
export function flightLiftPositionApplied(from, target, player) {
  const values = [from?.y, target?.x, target?.y, target?.z, target?.yaw, target?.pitch,
    player?.x, player?.y, player?.z, player?.yaw, player?.pitch];
  if (!values.every(Number.isFinite) || target.y - from.y < 8) return false;
  const yawDelta = ((player.yaw - target.yaw) % 360 + 540) % 360 - 180;
  return Math.hypot(player.x - target.x, player.z - target.z) < 0.1
    && player.y <= target.y + 0.1 && player.y >= target.y - 4
    && player.y - from.y >= 8
    && Math.abs(yawDelta) < 0.5 && Math.abs(player.pitch - target.pitch) < 0.5;
}

export function flightLiftAcknowledged(response, target) {
  const match = /\bTeleported .+? to (-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)(?:\D|$)/.exec(String(response));
  return !!match && [target?.x, target?.y, target?.z].every((value, index) =>
    Number.isFinite(value) && Math.abs(Number(match[index + 1]) - value) < 0.000001);
}

export async function waitForFlightLift({from, target, readPlayer, sleep,
    now = Date.now, timeoutMs = 120000, pollMs = 50}) {
  const started = now();
  let player = null, polls = 0;
  while (now() - started < timeoutMs) {
    player = await readPlayer();
    polls++;
    if (flightLiftPositionApplied(from, target, player)) {
      return {ok: true, from, target, player, polls, elapsedMs: now() - started};
    }
    await sleep(pollMs);
  }
  return {ok: false, from, target, player, polls, elapsedMs: now() - started};
}
