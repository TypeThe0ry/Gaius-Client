package dev.gaius.tools;

import dev.gaius.tools.bridges.*;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Emits the Gaius-owned same-package bridges before compiling their Java callers. */
public final class BrowserBridges {
    private BrowserBridges() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: BrowserBridges <patchSet> <renderSeam> <src|client> <classes-dir>");
        }
        boolean legacy = switch (args[0]) {
            case "legacy-12111" -> true;
            case "modern" -> false;
            default -> throw new IllegalArgumentException("Unknown bridge patch set: " + args[0]);
        };
        boolean renderpearl = args[1].startsWith("renderpearl-");
        Path output = Path.of(args[3]);
        switch (args[2]) {
            case "client" -> {
                if (legacy) {
                    emit(output, BrowserModelDependencySchedulerLegacyAsm.emit(),
                            BrowserModelDependencySchedulerNestedDiscoveryTaskLegacyAsm.emit());
                } else {
                    emit(output, BrowserModelDependencySchedulerAsm.emit(),
                            BrowserModelDependencySchedulerNestedDiscoveryTaskAsm.emit());
                    if (!renderpearl) {
                        emit(output, BrowserNoiseGraphMapperAsm.emit());
                    }
                }
            }
            case "src" -> {
                emit(output, BrowserUnihexLoaderAsm.emit(), BrowserUnihexLoaderNested1Asm.emit(),
                        BrowserUnihexLoaderNested2Asm.emit(legacy, renderpearl), BrowserUnihexLoaderNestedBrowserGlyphAsm.emit(),
                        BrowserUnihexLoaderNestedByteLinesAsm.emit(), BrowserUnihexLoaderNestedIntLinesAsm.emit(),
                        BrowserUnihexLoaderNestedShortLinesAsm.emit(), BrowserUnihexLoaderNestedTableGlyphAsm.emit(),
                        GaiusClimateIndexAsm.emit(), GaiusClimateIndexNestedCursorAsm.emit(), GaiusClimateSubTreeAsm.emit(),
                        GaiusLightColumnAccessAsm.emit());
                if (!legacy) {
                    emit(output, GaiusMeshBlockAccessAsm.emit(), GaiusStateNeighbourTablesAsm.emit(), GaiusKernelSectionsAsm.emit());
                    if (!renderpearl) {
                        emit(output, GaiusWorldgenExport262Asm.emit(),
                                GaiusWorldgenExport262Nested1Asm.emit(), GaiusWorldgenExport262Nested2Asm.emit());
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown browser bridge role: " + args[2]);
        }
    }

    private static void emit(Path output, byte[]... classes) throws IOException {
        for (byte[] bytes : classes) {
            String name = new ClassReader(bytes).getClassName();
            Path file = output.resolve(name + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        }
    }
}
