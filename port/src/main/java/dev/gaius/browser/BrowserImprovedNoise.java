package dev.gaius.browser;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/** Collapses the hottest ImprovedNoise call chain into one TeaVM-friendly method. */
public final class BrowserImprovedNoise {
    private BrowserImprovedNoise() {}

    @JSBody(params = {
            "permutation", "x", "y", "z", "offsetX", "offsetY", "offsetZ", "yScale", "yMax"
    }, script = """
            const p = permutation;
            const shiftedX = x + offsetX;
            const shiftedY = y + offsetY;
            const shiftedZ = z + offsetZ;
            const sectionX = Math.floor(shiftedX);
            const sectionY = Math.floor(shiftedY);
            const sectionZ = Math.floor(shiftedZ);
            const localX = shiftedX - sectionX;
            const fadeY = shiftedY - sectionY;
            const localZ = shiftedZ - sectionZ;
            let localY = fadeY;
            if (yScale !== 0) {
              const limitedY = yMax >= 0 && yMax < fadeY ? yMax : fadeY;
              localY -= Math.floor(limitedY / yScale + 1.0000000116860974E-7) * yScale;
            }
            const x0 = p[sectionX & 255] & 255;
            const x1 = p[(sectionX + 1) & 255] & 255;
            const xy00 = p[(x0 + sectionY) & 255] & 255;
            const xy01 = p[(x0 + sectionY + 1) & 255] & 255;
            const xy10 = p[(x1 + sectionY) & 255] & 255;
            const xy11 = p[(x1 + sectionY + 1) & 255] & 255;
            const h000 = p[(xy00 + sectionZ) & 255] & 15;
            const u000 = h000 < 8 ? localX : localY;
            const w000 = h000 < 4 ? localY : (h000 === 12 || h000 === 14 ? localX : localZ);
            const v000 = (h000 & 1 ? -u000 : u000) + (h000 & 2 ? -w000 : w000);
            const h100 = p[(xy10 + sectionZ) & 255] & 15;
            const u100 = h100 < 8 ? localX - 1 : localY;
            const w100 = h100 < 4 ? localY : (h100 === 12 || h100 === 14 ? localX - 1 : localZ);
            const v100 = (h100 & 1 ? -u100 : u100) + (h100 & 2 ? -w100 : w100);
            const h010 = p[(xy01 + sectionZ) & 255] & 15;
            const u010 = h010 < 8 ? localX : localY - 1;
            const w010 = h010 < 4 ? localY - 1 : (h010 === 12 || h010 === 14 ? localX : localZ);
            const v010 = (h010 & 1 ? -u010 : u010) + (h010 & 2 ? -w010 : w010);
            const h110 = p[(xy11 + sectionZ) & 255] & 15;
            const u110 = h110 < 8 ? localX - 1 : localY - 1;
            const w110 = h110 < 4 ? localY - 1 : (h110 === 12 || h110 === 14 ? localX - 1 : localZ);
            const v110 = (h110 & 1 ? -u110 : u110) + (h110 & 2 ? -w110 : w110);
            const h001 = p[(xy00 + sectionZ + 1) & 255] & 15;
            const u001 = h001 < 8 ? localX : localY;
            const w001 = h001 < 4 ? localY : (h001 === 12 || h001 === 14 ? localX : localZ - 1);
            const v001 = (h001 & 1 ? -u001 : u001) + (h001 & 2 ? -w001 : w001);
            const h101 = p[(xy10 + sectionZ + 1) & 255] & 15;
            const u101 = h101 < 8 ? localX - 1 : localY;
            const w101 = h101 < 4 ? localY : (h101 === 12 || h101 === 14 ? localX - 1 : localZ - 1);
            const v101 = (h101 & 1 ? -u101 : u101) + (h101 & 2 ? -w101 : w101);
            const h011 = p[(xy01 + sectionZ + 1) & 255] & 15;
            const u011 = h011 < 8 ? localX : localY - 1;
            const w011 = h011 < 4 ? localY - 1 : (h011 === 12 || h011 === 14 ? localX : localZ - 1);
            const v011 = (h011 & 1 ? -u011 : u011) + (h011 & 2 ? -w011 : w011);
            const h111 = p[(xy11 + sectionZ + 1) & 255] & 15;
            const u111 = h111 < 8 ? localX - 1 : localY - 1;
            const w111 = h111 < 4 ? localY - 1 : (h111 === 12 || h111 === 14 ? localX - 1 : localZ - 1);
            const v111 = (h111 & 1 ? -u111 : u111) + (h111 & 2 ? -w111 : w111);
            const sx = localX * localX * localX * (localX * (localX * 6 - 15) + 10);
            const sy = fadeY * fadeY * fadeY * (fadeY * (fadeY * 6 - 15) + 10);
            const sz = localZ * localZ * localZ * (localZ * (localZ * 6 - 15) + 10);
            const x00 = v000 + sx * (v100 - v000);
            const x10 = v010 + sx * (v110 - v010);
            const x01 = v001 + sx * (v101 - v001);
            const x11 = v011 + sx * (v111 - v011);
            const z0 = x00 + sy * (x10 - x00);
            const z1 = x01 + sy * (x11 - x01);
            return z0 + sz * (z1 - z0);
            """)
    public static native double noise(
            @JSByRef byte[] permutation,
            double x,
            double y,
            double z,
            double offsetX,
            double offsetY,
            double offsetZ,
            double yScale,
            double yMax);

    @JSBody(params = {
        "permutation", "sectionX", "sectionY", "sectionZ",
        "localX", "localY", "localZ", "fadeY"
    }, script = """
            const p = permutation;
            const x0 = p[sectionX & 255] & 255;
            const x1 = p[(sectionX + 1) & 255] & 255;
            const xy00 = p[(x0 + sectionY) & 255] & 255;
            const xy01 = p[(x0 + sectionY + 1) & 255] & 255;
            const xy10 = p[(x1 + sectionY) & 255] & 255;
            const xy11 = p[(x1 + sectionY + 1) & 255] & 255;
            const h000 = p[(xy00 + sectionZ) & 255] & 15;
            const u000 = h000 < 8 ? localX : localY;
            const w000 = h000 < 4 ? localY : (h000 === 12 || h000 === 14 ? localX : localZ);
            const v000 = (h000 & 1 ? -u000 : u000) + (h000 & 2 ? -w000 : w000);
            const h100 = p[(xy10 + sectionZ) & 255] & 15;
            const u100 = h100 < 8 ? localX - 1 : localY;
            const w100 = h100 < 4 ? localY : (h100 === 12 || h100 === 14 ? localX - 1 : localZ);
            const v100 = (h100 & 1 ? -u100 : u100) + (h100 & 2 ? -w100 : w100);
            const h010 = p[(xy01 + sectionZ) & 255] & 15;
            const u010 = h010 < 8 ? localX : localY - 1;
            const w010 = h010 < 4 ? localY - 1 : (h010 === 12 || h010 === 14 ? localX : localZ);
            const v010 = (h010 & 1 ? -u010 : u010) + (h010 & 2 ? -w010 : w010);
            const h110 = p[(xy11 + sectionZ) & 255] & 15;
            const u110 = h110 < 8 ? localX - 1 : localY - 1;
            const w110 = h110 < 4 ? localY - 1 : (h110 === 12 || h110 === 14 ? localX - 1 : localZ);
            const v110 = (h110 & 1 ? -u110 : u110) + (h110 & 2 ? -w110 : w110);
            const h001 = p[(xy00 + sectionZ + 1) & 255] & 15;
            const u001 = h001 < 8 ? localX : localY;
            const w001 = h001 < 4 ? localY : (h001 === 12 || h001 === 14 ? localX : localZ - 1);
            const v001 = (h001 & 1 ? -u001 : u001) + (h001 & 2 ? -w001 : w001);
            const h101 = p[(xy10 + sectionZ + 1) & 255] & 15;
            const u101 = h101 < 8 ? localX - 1 : localY;
            const w101 = h101 < 4 ? localY : (h101 === 12 || h101 === 14 ? localX - 1 : localZ - 1);
            const v101 = (h101 & 1 ? -u101 : u101) + (h101 & 2 ? -w101 : w101);
            const h011 = p[(xy01 + sectionZ + 1) & 255] & 15;
            const u011 = h011 < 8 ? localX : localY - 1;
            const w011 = h011 < 4 ? localY - 1 : (h011 === 12 || h011 === 14 ? localX : localZ - 1);
            const v011 = (h011 & 1 ? -u011 : u011) + (h011 & 2 ? -w011 : w011);
            const h111 = p[(xy11 + sectionZ + 1) & 255] & 15;
            const u111 = h111 < 8 ? localX - 1 : localY - 1;
            const w111 = h111 < 4 ? localY - 1 : (h111 === 12 || h111 === 14 ? localX - 1 : localZ - 1);
            const v111 = (h111 & 1 ? -u111 : u111) + (h111 & 2 ? -w111 : w111);
            const sx = localX * localX * localX * (localX * (localX * 6 - 15) + 10);
            const sy = fadeY * fadeY * fadeY * (fadeY * (fadeY * 6 - 15) + 10);
            const sz = localZ * localZ * localZ * (localZ * (localZ * 6 - 15) + 10);
            const x00 = v000 + sx * (v100 - v000);
            const x10 = v010 + sx * (v110 - v010);
            const x01 = v001 + sx * (v101 - v001);
            const x11 = v011 + sx * (v111 - v011);
            const z0 = x00 + sy * (x10 - x00);
            const z1 = x01 + sy * (x11 - x01);
            return z0 + sz * (z1 - z0);
            """)
    public static native double sampleAndLerp(
            @JSByRef byte[] permutation,
            int sectionX,
            int sectionY,
            int sectionZ,
            double localX,
            double localY,
            double localZ,
            double fadeY);
}
