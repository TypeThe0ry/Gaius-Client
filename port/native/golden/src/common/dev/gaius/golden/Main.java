package dev.gaius.golden;

import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Dumps golden fixtures from the real Minecraft classes on the JVM.
 *
 * <p>Usage: {@code Main <profile> <output-directory>}. The output directory
 * receives one {@code <kind>.jsonl} per fixture kind; see Case for the value
 * encoding and the fixture classes for each kind's params.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: Main <profile> <output-directory>");
            System.exit(2);
        }
        String profile = args[0];
        Path out = Path.of(args[1]);

        // The registry bootstrap is what the built-in worldgen lookup needs;
        // the raw noise classes themselves do not depend on it.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        try (FixtureSink sink = new FixtureSink(out)) {
            RandomFixtures.write(sink);
            MthFixtures.write(sink);
            NoiseFixtures.write(sink);
            sink.printCounts(profile);
        }
    }
}
