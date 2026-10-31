package dev.gaius.golden.worldgen;

import java.io.IOException;

public final class StageTiming {
    private static int round = -1;

    private StageTiming() {
    }

    public static long start() {
        return round >= 0 ? System.nanoTime() : 0;
    }

    public static void finish(String stage, int chunkX, int chunkZ, long start) {
        if (round >= 0) {
            long elapsed = System.nanoTime() - start;
            System.out.println("TIMING\t" + stage + "\t" + round + "\t" + chunkX + "\t" + chunkZ + "\t" + elapsed);
        }
    }

    public static void run(String[] chunks, ChunkStep step) throws IOException {
        int rounds = Integer.getInteger("gaius.worldgen.rounds", 0);
        int repeats = Integer.getInteger("gaius.worldgen.repeats", 2);
        if (rounds < 0 || rounds > 100 || repeats < 1 || repeats > 100) {
            throw new IllegalArgumentException("timing bounds");
        }
        if (rounds == 0) {
            return;
        }
        for (int iteration = 0; iteration < rounds + 3; iteration++) {
            round = iteration < 3 ? -1 : iteration - 3;
            for (int repeat = 0; repeat < repeats; repeat++) {
                for (String chunk : chunks) {
                    String[] coordinates = chunk.split(",");
                    step.run(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]));
                }
            }
        }
        round = -1;
    }

    @FunctionalInterface
    public interface ChunkStep {
        void run(int chunkX, int chunkZ) throws IOException;
    }
}
