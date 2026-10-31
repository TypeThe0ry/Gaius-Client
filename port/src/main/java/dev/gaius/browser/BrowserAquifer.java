package dev.gaius.browser;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/** Allocation-free nearest-center scan for warmed vanilla aquifer caches. */
public final class BrowserAquifer {
    private BrowserAquifer() {
    }

    @JSBody(
            params = {
                "packed",
                "minGridX",
                "minGridY",
                "minGridZ",
                "gridSizeX",
                "gridSizeZ",
                "blockX",
                "blockY",
                "blockZ",
                "output"
            },
            script = """
                    const source = packed && packed.data ? packed.data : packed;
                    const target = output && output.data ? output.data : output;
                    if (!source || !target || target.length < 8) return false;

                    const decodedCaches = globalThis.__gaiusAquiferDecodedLocations
                      || (globalThis.__gaiusAquiferDecodedLocations = new WeakMap());
                    let decoded = decodedCaches.get(source);
                    if (!decoded || decoded.length !== source.length) {
                      decoded = {
                        length: source.length,
                        ready: new Uint8Array(source.length),
                        x: new Int32Array(source.length),
                        y: new Int32Array(source.length),
                        z: new Int32Array(source.length)
                      };
                      decodedCaches.set(source, decoded);
                    }

                    const x = blockX | 0;
                    const y = blockY | 0;
                    const z = blockZ | 0;
                    const baseGridX = ((x - 5) | 0) >> 4;
                    const baseGridY = Math.floor(((y + 1) | 0) / 12) | 0;
                    const baseGridZ = ((z - 5) | 0) >> 4;
                    const sentinel = BigInt("9223372036854775807");
                    let distance0 = 2147483647;
                    let distance1 = 2147483647;
                    let distance2 = 2147483647;
                    let distance3 = 2147483647;
                    let index0 = 0;
                    let index1 = 0;
                    let index2 = 0;
                    let index3 = 0;

                    for (let offsetX = 0; offsetX <= 1; offsetX++) {
                      const gridX = (baseGridX + offsetX) | 0;
                      for (let offsetY = -1; offsetY <= 1; offsetY++) {
                        const gridY = (baseGridY + offsetY) | 0;
                        for (let offsetZ = 0; offsetZ <= 1; offsetZ++) {
                          const gridZ = (baseGridZ + offsetZ) | 0;
                          const index = Math.imul(
                            (Math.imul((gridY - minGridY) | 0, gridSizeZ | 0)
                              + ((gridZ - minGridZ) | 0)) | 0,
                            gridSizeX | 0) + ((gridX - minGridX) | 0) | 0;
                          const position = source[index];
                          if (position === sentinel) return false;

                          if (decoded.ready[index] === 0) {
                            decoded.x[index] = Number(BigInt.asIntN(
                              26, position >> BigInt(38))) | 0;
                            decoded.y[index] = Number(BigInt.asIntN(12, position)) | 0;
                            decoded.z[index] = Number(BigInt.asIntN(
                              26, position >> BigInt(12))) | 0;
                            decoded.ready[index] = 1;
                          }
                          const centerX = decoded.x[index];
                          const centerY = decoded.y[index];
                          const centerZ = decoded.z[index];
                          const dx = (centerX - x) | 0;
                          const dy = (centerY - y) | 0;
                          const dz = (centerZ - z) | 0;
                          const distance = (Math.imul(dx, dx)
                            + Math.imul(dy, dy) + Math.imul(dz, dz)) | 0;

                          if (distance0 >= distance) {
                            distance3 = distance2;
                            distance2 = distance1;
                            distance1 = distance0;
                            distance0 = distance;
                            index3 = index2;
                            index2 = index1;
                            index1 = index0;
                            index0 = index;
                          } else if (distance1 >= distance) {
                            distance3 = distance2;
                            distance2 = distance1;
                            distance1 = distance;
                            index3 = index2;
                            index2 = index1;
                            index1 = index;
                          } else if (distance2 >= distance) {
                            distance3 = distance2;
                            distance2 = distance;
                            index3 = index2;
                            index2 = index;
                          } else if (distance3 >= distance) {
                            distance3 = distance;
                            index3 = index;
                          }
                        }
                      }
                    }

                    target[0] = distance0;
                    target[1] = distance1;
                    target[2] = distance2;
                    target[3] = distance3;
                    target[4] = index0;
                    target[5] = index1;
                    target[6] = index2;
                    target[7] = index3;
                    return true;
                    """)
    public static native boolean selectNearestCached(
            @JSByRef long[] packed,
            int minGridX,
            int minGridY,
            int minGridZ,
            int gridSizeX,
            int gridSizeZ,
            int blockX,
            int blockY,
            int blockZ,
            @JSByRef int[] output);
}
