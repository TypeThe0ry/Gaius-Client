package dev.gaius.browser;

import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.teavm.jso.JSBody;

/** Allocation-free browser structure-terrain blending for packed Beardifier inputs. */
public final class BrowserBeardifier {
    private BrowserBeardifier() {
    }

    public static int[] packPieces(List<Beardifier.Rigid> pieces) {
        int[] packed = new int[pieces.size() * 8];
        for (int index = 0; index < pieces.size(); index++) {
            Beardifier.Rigid piece = pieces.get(index);
            BoundingBox box = piece.box();
            int offset = index * 8;
            packed[offset] = box.minX();
            packed[offset + 1] = box.minY();
            packed[offset + 2] = box.minZ();
            packed[offset + 3] = box.maxX();
            packed[offset + 4] = box.maxY();
            packed[offset + 5] = box.maxZ();
            packed[offset + 6] = piece.terrainAdjustment().ordinal();
            packed[offset + 7] = piece.groundLevelDelta();
        }
        return packed;
    }

    public static int[] packJunctions(List<JigsawJunction> junctions) {
        int[] packed = new int[junctions.size() * 3];
        for (int index = 0; index < junctions.size(); index++) {
            JigsawJunction junction = junctions.get(index);
            int offset = index * 3;
            packed[offset] = junction.getSourceX();
            packed[offset + 1] = junction.getSourceGroundY();
            packed[offset + 2] = junction.getSourceZ();
        }
        return packed;
    }

    @JSBody(params = {"pieces", "junctions", "kernel", "blockX", "blockY", "blockZ"}, script = """
            const packedPieces = pieces && pieces.data ? pieces.data : pieces;
            const packedJunctions = junctions && junctions.data ? junctions.data : junctions;
            const beardKernel = kernel && kernel.data ? kernel.data : kernel;
            if (!packedPieces || !packedJunctions || !beardKernel) return 0.0;

            let math = globalThis.__gaiusBeardifierMath;
            if (!math) {
                const buffer = new ArrayBuffer(8);
                const doubles = new Float64Array(buffer);
                const longs = new BigInt64Array(buffer);
                const magic = BigInt("6910469410427058090");
                const one = BigInt(1);
                math = {
                  beard: function(dx, dy, dz, deltaY, values) {
                    const kernelX = (dx + 12) | 0;
                    const kernelY = (dy + 12) | 0;
                    const kernelZ = (dz + 12) | 0;
                    if (kernelX < 0 || kernelX >= 24
                        || kernelY < 0 || kernelY >= 24
                        || kernelZ < 0 || kernelZ >= 24) {
                      return 0.0;
                    }
                    const vertical = deltaY + 0.5;
                    const distanceSquared = dx * dx + vertical * vertical + dz * dz;
                    const inverseInput = distanceSquared / 2.0;
                    const half = 0.5 * inverseInput;
                    doubles[0] = inverseInput;
                    longs[0] = BigInt.asIntN(64, magic - (longs[0] >> one));
                    let inverse = doubles[0];
                    inverse = inverse * (1.5 - half * inverse * inverse);
                    const scale = -vertical * inverse / 2.0;
                    const kernelIndex = Math.imul(kernelZ, 576)
                      + Math.imul(kernelX, 24) + kernelY;
                    return scale * values[kernelIndex];
                  }
                };
                globalThis.__gaiusBeardifierMath = math;
            }

            const x = blockX | 0;
            const y = blockY | 0;
            const z = blockZ | 0;
            let total = 0.0;
            for (let offset = 0; offset < packedPieces.length; offset += 8) {
              const minX = packedPieces[offset] | 0;
              const minY = packedPieces[offset + 1] | 0;
              const minZ = packedPieces[offset + 2] | 0;
              const maxX = packedPieces[offset + 3] | 0;
              const maxY = packedPieces[offset + 4] | 0;
              const maxZ = packedPieces[offset + 5] | 0;
              const adjustment = packedPieces[offset + 6] | 0;
              const groundY = (minY + (packedPieces[offset + 7] | 0)) | 0;
              const distanceX = Math.max(0, (minX - x) | 0, (x - maxX) | 0) | 0;
              const distanceZ = Math.max(0, (minZ - z) | 0, (z - maxZ) | 0) | 0;
              const deltaY = (y - groundY) | 0;

              let distanceY;
              if (adjustment === 0) {
                continue;
              } else if (adjustment === 1 || adjustment === 2) {
                distanceY = deltaY;
              } else if (adjustment === 3) {
                distanceY = Math.max(0, (groundY - y) | 0, (y - maxY) | 0) | 0;
              } else {
                distanceY = Math.max(0, (minY - y) | 0, (y - maxY) | 0) | 0;
              }

              if (adjustment === 1 || adjustment === 4) {
                const buryX = adjustment === 4 ? distanceX / 2.0 : distanceX;
                const buryY = distanceY / 2.0;
                const buryZ = adjustment === 4 ? distanceZ / 2.0 : distanceZ;
                const length = Math.sqrt(
                  buryX * buryX + buryY * buryY + buryZ * buryZ);
                const delta = (length - 0.0) / (6.0 - 0.0);
                let contribution;
                if (delta < 0.0) {
                  contribution = 1.0;
                } else if (delta > 1.0) {
                  contribution = 0.0;
                } else {
                  contribution = 1.0 + delta * (0.0 - 1.0);
                }
                total += adjustment === 4 ? contribution * 0.8 : contribution;
              } else {
                total += math.beard(
                  distanceX, distanceY, distanceZ, deltaY, beardKernel) * 0.8;
              }
            }

            for (let offset = 0; offset < packedJunctions.length; offset += 3) {
              const junctionDeltaX = (x - (packedJunctions[offset] | 0)) | 0;
              const junctionDeltaY = (y - (packedJunctions[offset + 1] | 0)) | 0;
              const junctionDeltaZ = (z - (packedJunctions[offset + 2] | 0)) | 0;
              total += math.beard(
                junctionDeltaX, junctionDeltaY, junctionDeltaZ,
                junctionDeltaY, beardKernel) * 0.4;
            }
            return total;
            """)
    public static native double compute(
            Object pieces, Object junctions, Object kernel, int blockX, int blockY, int blockZ);
}
