package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Exact browser implementation of biome zoom's nearest-corner calculation. */
public final class BrowserBiomeManager {
    private BrowserBiomeManager() {
    }

    // BiomeManager.getBiome runs this for every biome lookup (~190k calls/s while the Worker
    // generates chunks), and each call derives the same per-quart "fiddle" offsets eight
    // times with BigInt LCG chains. The offsets depend only on the seed and the quart corner,
    // and neighbouring lookups share corners, so they are kept in a direct-mapped cache.
    @JSBody(params = {"seed", "shiftedX", "shiftedY", "shiftedZ"}, script = """
            const nearestCorner = globalThis.__gaiusBiomeNearestCorner
              || (globalThis.__gaiusBiomeNearestCorner = (function () {
                const multiplier = BigInt("6364136223846793005");
                const increment = BigInt("1442695040888963407");
                const shift = BigInt(24);
                const mask = BigInt(1023);
                const SIZE = 4096;
                const keyX = new Int32Array(SIZE);
                const keyY = new Int32Array(SIZE);
                const keyZ = new Int32Array(SIZE);
                const filled = new Uint8Array(SIZE);
                const fiddleX = new Float64Array(SIZE);
                const fiddleY = new Float64Array(SIZE);
                const fiddleZ = new Float64Array(SIZE);
                let cachedSeed = null;
                const next = (value, salt) =>
                  BigInt.asIntN(64, value * (value * multiplier + increment) + salt);
                const fiddle = (value) =>
                  (Number((value >> shift) & mask) / 1024.0 - 0.5) * 0.9;
                // Returns the cache slot holding the fiddles of quart corner (x, y, z).
                const slotFor = (seed, x, y, z) => {
                  const slot = (Math.imul(x, 73856093) ^ Math.imul(y, 19349663)
                    ^ Math.imul(z, 83492791)) & (SIZE - 1);
                  if (filled[slot] === 1 && keyX[slot] === x && keyY[slot] === y
                      && keyZ[slot] === z) {
                    return slot;
                  }
                  const bx = BigInt(x), by = BigInt(y), bz = BigInt(z);
                  let value = next(seed, bx);
                  value = next(value, by);
                  value = next(value, bz);
                  value = next(value, bx);
                  value = next(value, by);
                  value = next(value, bz);
                  fiddleX[slot] = fiddle(value);
                  value = next(value, seed);
                  fiddleY[slot] = fiddle(value);
                  value = next(value, seed);
                  fiddleZ[slot] = fiddle(value);
                  keyX[slot] = x;
                  keyY[slot] = y;
                  keyZ[slot] = z;
                  filled[slot] = 1;
                  return slot;
                };
                return (seed, shiftedX, shiftedY, shiftedZ) => {
                  if (seed !== cachedSeed) {
                    filled.fill(0);
                    cachedSeed = seed;
                  }
                  const baseX = (shiftedX | 0) >> 2;
                  const baseY = (shiftedY | 0) >> 2;
                  const baseZ = (shiftedZ | 0) >> 2;
                  const fractionX = (shiftedX & 3) / 4.0;
                  const fractionY = (shiftedY & 3) / 4.0;
                  const fractionZ = (shiftedZ & 3) / 4.0;
                  let nearest = 0;
                  let nearestDistance = Infinity;
                  for (let corner = 0; corner < 8; corner++) {
                    const highX = (corner & 4) !== 0;
                    const highY = (corner & 2) !== 0;
                    const highZ = (corner & 1) !== 0;
                    const slot = slotFor(seed, highX ? baseX + 1 : baseX,
                      highY ? baseY + 1 : baseY, highZ ? baseZ + 1 : baseZ);
                    const distanceX = (highX ? fractionX - 1.0 : fractionX) + fiddleX[slot];
                    const distanceY = (highY ? fractionY - 1.0 : fractionY) + fiddleY[slot];
                    const distanceZ = (highZ ? fractionZ - 1.0 : fractionZ) + fiddleZ[slot];
                    const distance = distanceZ * distanceZ
                      + distanceY * distanceY + distanceX * distanceX;
                    if (nearestDistance > distance) {
                      nearest = corner;
                      nearestDistance = distance;
                    }
                  }
                  return nearest;
                };
              })());
            return nearestCorner(seed, shiftedX, shiftedY, shiftedZ) | 0;
            """)
    public static native int nearestCorner(long seed, int shiftedX, int shiftedY, int shiftedZ);
}
