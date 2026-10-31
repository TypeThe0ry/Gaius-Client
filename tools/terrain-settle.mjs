// Terrain completeness of the settled spawn view, shared by the singleplayer acceptance's settle
// loop and its final terrainRendered verdict.
//
// The render distance that counts is the one the game reports for itself right now
// (state.clientDistance, published by BrowserOpenGL): the effective distance, else the raw option.
// It can differ from the options a harness wrote (the quality layer retunes it, a stale options
// file wins), so it is read from every sample and never from a constant. A view is complete when
// the client holds at least the (2r + 1)^2 chunks of that distance and no visible section is
// waiting for compilation; more chunks than that is the tracking border, not an error. Anything
// unreadable stays incomplete.

function positive(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : null;
}

// {value, source} of the render distance a clientDistance record states, or {value: null}.
export function readRenderDistance(distance) {
  const effective = positive(distance?.effectiveRenderDistance);
  if (effective !== null) return {value: effective, source: "effectiveRenderDistance"};
  const raw = positive(distance?.rawRenderDistance);
  if (raw !== null) return {value: raw, source: "rawRenderDistance"};
  return {value: null, source: null};
}

export function expectedLoadedChunks(renderDistance) {
  const distance = positive(renderDistance);
  return distance === null ? null : (2 * distance + 1) ** 2;
}

// The 1.21.11 renderer does not publish the optional section audit. When available, the audit
// still verifies that visible sections were meshed; otherwise require rendered terrain pixels
// and a minimally loaded world, which are the observable signals for that profile.
export function visibleTerrainPass(spawn, firstTerrainSeconds, px) {
  return firstTerrainSeconds !== null && firstTerrainSeconds !== undefined
    && !px?.error && Number(px?.other) > 0.3
    && Number(spawn?.loaded) >= 9
    && (spawn?.audit == null || Number(spawn.audit.visible) >= 9);
}

// sample: {loaded, dist, audit}. complete is true only with a readable distance, loaded >= expected
// and a section audit that reports no uncompiled section (an absent audit does not block).
export function terrainCompleteness(sample) {
  const {value: renderDistance, source} = readRenderDistance(sample?.dist);
  const expectedLoaded = expectedLoadedChunks(renderDistance);
  const loaded = Number(sample?.loaded);
  const uncompiled = sample?.audit == null ? null : Number(sample.audit.uncompiled);
  let reason = null;
  if (expectedLoaded === null) reason = "render-distance-unreadable";
  else if (!Number.isFinite(loaded) || loaded < expectedLoaded) reason = "chunks-missing";
  else if (uncompiled !== null && uncompiled !== 0) reason = "sections-uncompiled";
  return {complete: reason === null, reason, renderDistance, renderDistanceSource: source, expectedLoaded,
    loaded: Number.isFinite(loaded) ? loaded : null, uncompiled};
}

// The render distance an options list ("renderDistance:8") asked for, or null.
export function optionRenderDistance(options) {
  for (const entry of Array.isArray(options) ? options : []) {
    const match = /^renderDistance\s*:\s*(\d+)\s*$/.exec(String(entry));
    if (match) return Number(match[1]);
  }
  return null;
}

// The final terrainRendered verdict from the last state and what the settle loop saw.
// firstTerrainSeconds: when terrain pixels first showed (null: never). settleSeconds: when the loop
// saw a complete view stay complete for its stability window (null: not within the window). A view
// that is complete at the final read with terrain drawn passes; stable reports whether the loop
// also saw it hold.
export function terrainVerdict({spawn, firstTerrainSeconds, settleSeconds, px, requestedOptions}) {
  const completeness = terrainCompleteness(spawn);
  const requestedDistance = optionRenderDistance(requestedOptions);
  const drawn = firstTerrainSeconds !== null && firstTerrainSeconds !== undefined;
  const detail = {firstTerrainSeconds, settleSeconds, stable: settleSeconds !== null && settleSeconds !== undefined,
    loaded: completeness.loaded, expectedLoaded: completeness.expectedLoaded,
    renderDistance: completeness.renderDistance, renderDistanceSource: completeness.renderDistanceSource, audit: spawn?.audit, px};
  if (requestedDistance !== null) {
    detail.optionRenderDistance = requestedDistance;
    detail.renderDistanceMatchesOptions = completeness.renderDistance === requestedDistance;
  }
  const ok = completeness.complete && drawn;
  if (!ok) detail.incomplete = !completeness.complete ? completeness.reason : "no-terrain-pixels";
  return {ok, detail};
}
