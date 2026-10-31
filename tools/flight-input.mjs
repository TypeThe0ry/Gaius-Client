// Creative flight for the performance windows of the singleplayer acceptance.
//
// Holding W and Space from the ground is not flight: a creative player only flies after a
// double tap of Space, and a held Space then only climbs, so a "flying" window opened that way
// measured walking or jumping and could not tell whether the keys were held at all. The window
// now starts from a measured flight: the player is lifted clear of the terrain, Space is
// double-tapped with edges long enough for a slow frame to see both (a 30 ms tap can open and
// close inside one frame and never register), and flight counts as enabled only when the height
// stays constant while the player is airborne. W is then held (with the browser's key repeat) for
// the whole window and the horizontal travel is measured from the reported positions.

const sleepFor = ms => new Promise(resolve => setTimeout(resolve, ms));

// tap(): one complete press and release of Space. readPlayer(): {x, y, z} of the player now.
// lift(player): puts the player well above the terrain (a vanilla command). Resolves to
// {enabled, attempts: [{attempt, y0, y1, dy, stable, airborne}]}.
export async function enableCreativeFlight({tap, readPlayer, lift, sleep = sleepFor, attempts = 5,
    tapGapMs = 120, settleMs = 1500, probeMs = 1000, stableBlocks = 0.2, minAirborneGain = 8}) {
  const log = [];
  let start = await readPlayer();
  if (!start || ![start.x, start.y, start.z].every(Number.isFinite)) {
    return {enabled: false, attempts: log, problem: "player position unreadable"};
  }
  const baselineY = start.y;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    await lift(start);
    await sleep(1200);
    await tap();
    await sleep(tapGapMs);
    await tap();
    await sleep(settleMs);
    const before = await readPlayer();
    await sleep(probeMs);
    const after = await readPlayer();
    const dy = Number.isFinite(before?.y) && Number.isFinite(after?.y) ? after.y - before.y : null;
    const stable = dy !== null && Math.abs(dy) < stableBlocks;
    const airborne = Number.isFinite(after?.y) && after.y >= baselineY + minAirborneGain;
    log.push({attempt, y0: before?.y ?? null, y1: after?.y ?? null, dy, stable, airborne});
    if (stable && airborne) return {enabled: true, attempts: log};
    start = after || start;
  }
  return {enabled: false, attempts: log, problem: "the player kept changing height after the double tap"};
}

// Distance covered between two reported positions over a window, against a floor in blocks per
// second. Creative flight is about 11 blocks/s and walking about 4.3, so 3 only rejects a window in
// which the player stood still or the keys never reached the game.
export function flightWindowMovement({start, end, durationMs, minBlocksPerSecond = 3}) {
  const known = [start?.x, start?.z, end?.x, end?.z].every(Number.isFinite);
  const horizontal = known ? Math.hypot(end.x - start.x, end.z - start.z) : null;
  const seconds = durationMs / 1000;
  const blocksPerSecond = horizontal !== null && seconds > 0 ? horizontal / seconds : null;
  const ok = blocksPerSecond !== null && blocksPerSecond >= minBlocksPerSecond;
  return {ok, horizontalBlocks: horizontal, blocksPerSecond, durationMs, minBlocksPerSecond,
    start: start ? {x: start.x, y: start.y, z: start.z} : null, end: end ? {x: end.x, y: end.y, z: end.z} : null};
}

// Holds a key for a window: one keyDown, then a repeat every repeatMs (a held key repeats in a
// browser, and a game that polls a pressed set over a stalled frame still sees it down), then keyUp.
// Resolves to a function that releases the key; calling it twice is harmless.
export async function holdKeyDuring({down, repeat, up, repeatMs = 250}) {
  let released = false;
  await down();
  const timer = setInterval(() => { if (!released) Promise.resolve(repeat()).catch(() => {}); }, repeatMs);
  return async () => {
    if (released) return;
    released = true;
    clearInterval(timer);
    await up();
  };
}
