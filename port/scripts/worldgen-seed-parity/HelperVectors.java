package dev.gaius.parity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Test vectors for the JavaScript bodies of the browser worldgen helpers
 * (worldgen-seed-parity.mjs --helpers). Expected values come from vanilla code where the helper
 * replaces a vanilla computation (BiomeManager zoom, SimpleBitStorage) and from the JVM
 * transcriptions in shims/ otherwise (aquifer nearest centers, climate distance). The
 * transcriptions are what the patched-class parity run executes, so a pass here ties the
 * browser JavaScript to vanilla worldgen.
 *
 * <p>usage: {@code HelperVectors <out.json> [count]}
 */
public final class HelperVectors {
    private HelperVectors() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 4000;
        SplittableRandom random = new SplittableRandom(0x6a15L);
        List<String> biome = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long seed = index % 7 == 0 ? index : random.nextLong();
            int x = coordinate(random, index, 30_000_000);
            int y = coordinate(random, index, 2048);
            int z = coordinate(random, index, 30_000_000);
            int[] recorded = new int[3];
            BiomeManager manager = new BiomeManager((qx, qy, qz) -> {
                recorded[0] = qx;
                recorded[1] = qy;
                recorded[2] = qz;
                return Holder.direct(null);
            }, seed);
            manager.getBiome(x, y, z);
            biome.add("[\"" + seed + "\"," + (x - 2) + "," + (y - 2) + "," + (z - 2) + ","
                    + recorded[0] + "," + recorded[1] + "," + recorded[2] + "]");
        }
        List<String> bits = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int bitCount = 1 + random.nextInt(31);
            int size = 1 + random.nextInt(4096);
            SimpleBitStorage storage = new SimpleBitStorage(bitCount, size);
            long mask = (1L << bitCount) - 1L;
            for (int slot = 0; slot < size; slot++) {
                storage.set(slot, (int) random.nextLong(mask + 1));
            }
            int slot = random.nextInt(size);
            int value = (int) random.nextLong(mask + 1);
            long[] before = storage.getRaw().clone();
            int got = storage.get(slot);
            int previous = storage.getAndSet(slot, value);
            long[] after = storage.getRaw();
            int cell = slot / (64 / bitCount);
            bits.add("[" + bitCount + "," + (64 / bitCount) + "," + slot + "," + value + ",\""
                    + before[cell] + "\"," + got + "," + previous + ",\"" + after[cell] + "\"]");
        }
        List<String> aquifer = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int gridSizeX = 3 + random.nextInt(4);
            int gridSizeY = 4 + random.nextInt(6);
            int gridSizeZ = 3 + random.nextInt(4);
            int blockX = coordinate(random, index, 1_000_000);
            int blockY = -64 + random.nextInt(384);
            int blockZ = coordinate(random, index, 1_000_000);
            int minGridX = ((blockX - 5) >> 4) - random.nextInt(2);
            int minGridY = Math.floorDiv(blockY + 1, 12) - 1 - random.nextInt(2);
            int minGridZ = ((blockZ - 5) >> 4) - random.nextInt(2);
            long[] packed = new long[gridSizeX * gridSizeY * gridSizeZ];
            for (int slot = 0; slot < packed.length; slot++) {
                int gx = minGridX + slot % gridSizeX;
                int gz = minGridZ + (slot / gridSizeX) % gridSizeZ;
                int gy = minGridY + slot / (gridSizeX * gridSizeZ);
                packed[slot] = random.nextInt(40) == 0 ? Long.MAX_VALUE : BlockPos.asLong(
                        gx * 16 + random.nextInt(10), gy * 12 + random.nextInt(9),
                        gz * 16 + random.nextInt(10));
            }
            int[] output = new int[8];
            boolean hit = dev.gaius.browser.BrowserAquifer.selectNearestCached(packed, minGridX,
                    minGridY, minGridZ, gridSizeX, gridSizeZ, blockX, blockY, blockZ, output);
            StringBuilder line = new StringBuilder("[[");
            for (int slot = 0; slot < packed.length; slot++) {
                line.append(slot == 0 ? "" : ",").append('"').append(packed[slot]).append('"');
            }
            line.append("],").append(minGridX).append(',').append(minGridY).append(',')
                    .append(minGridZ).append(',').append(gridSizeX).append(',').append(gridSizeZ)
                    .append(',').append(blockX).append(',').append(blockY).append(',')
                    .append(blockZ).append(',').append(hit).append(",[");
            for (int slot = 0; slot < 8; slot++) {
                line.append(slot == 0 ? "" : ",").append(hit ? output[slot] : 0);
            }
            aquifer.add(line.append("]]").toString());
        }
        List<String> climate = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double[] bounds = new double[14];
            long[] target = new long[7];
            StringBuilder boundsText = new StringBuilder();
            StringBuilder targetText = new StringBuilder();
            for (int axis = 0; axis < 7; axis++) {
                long low = random.nextLong(-20_000, 20_000);
                long high = low + random.nextLong(0, 20_000);
                bounds[axis * 2] = low;
                bounds[axis * 2 + 1] = high;
                target[axis] = random.nextLong(-40_000, 40_000);
                boundsText.append(axis == 0 ? "" : ",").append(low).append(',').append(high);
                targetText.append(axis == 0 ? "" : ",").append('"').append(target[axis]).append('"');
            }
            climate.add("[[" + boundsText + "],[" + targetText + "],\""
                    + dev.gaius.browser.BrowserClimate.distance(bounds, target) + "\"]");
        }
        String json = "{\n\"biomeManager\": [\n" + String.join(",\n", biome) + "\n],\n"
                + "\"bitStorage\": [\n" + String.join(",\n", bits) + "\n],\n"
                + "\"aquifer\": [\n" + String.join(",\n", aquifer) + "\n],\n"
                + "\"climate\": [\n" + String.join(",\n", climate) + "\n]\n}\n";
        Files.writeString(out, json, StandardCharsets.UTF_8);
        System.out.println("HELPER_VECTORS " + out + " count=" + count);
    }

    private static int coordinate(SplittableRandom random, int index, int range) {
        return switch (index % 5) {
            case 0 -> random.nextInt(-64, 64);
            case 1 -> random.nextInt(-range, range);
            case 2 -> range - random.nextInt(8);
            case 3 -> -range + random.nextInt(8);
            default -> random.nextInt(-4096, 4096);
        };
    }
}
