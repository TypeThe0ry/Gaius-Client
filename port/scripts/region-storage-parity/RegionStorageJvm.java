package dev.gaius.storage;

import java.io.PrintStream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class RegionStorageJvm {
    private RegionStorageJvm() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream output = System.out;
        PrintStream error = System.err;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.setOut(output);
        System.setErr(error);
        RegionStorageProbe.main(args);
    }
}
