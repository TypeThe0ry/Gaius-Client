package dev.gaius.tools.patches.light;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.patchDeepWorldgenPulseBackEdges;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.requireWorldgenSchedulerCalls;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The light domain of the MinecraftClientPatcher step ({@code patches/light}): the
 * vanilla light engine on the JavaScript target, whose propagation loops must not suspend.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically. The light kernel
 * route of the integrated server is {@link LightKernelPatches}.
 */
public final class LightPatches {
    private LightPatches() {
    }

    public static void patchLightEngineBrowserSynchronous(
            String jar, Path output, boolean deepWorldgenCheckpoints) throws IOException {
        ClassNode node = read(jar, "net/minecraft/world/level/lighting/LightEngine.class");
        MethodNode increases = find(node, "propagateIncreases", "()I");
        MethodNode decreases = find(node, "propagateDecreases", "()I");
        requireWorldgenSchedulerCalls("LightEngine.propagateIncreases", increases, 0);
        requireWorldgenSchedulerCalls("LightEngine.propagateDecreases", decreases, 0);
        if (deepWorldgenCheckpoints) {
            patchDeepWorldgenPulseBackEdges(increases, "LightEngine.propagateIncreases");
            patchDeepWorldgenPulseBackEdges(decreases, "LightEngine.propagateDecreases");
            writeComputeFrames(node, output);
        } else {
            write(node, output);
        }
    }
}
