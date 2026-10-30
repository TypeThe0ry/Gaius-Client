package dev.gaius.tools.patches.audio;

import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.find;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.read;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.write;

import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The audio domain of the Minecraft263BrowserPatcher step, the tail of the modern chain for the
 * renderpearl render seam ({@code patches/audio}), work package P8.
 *
 * <ul>
 *   <li>{@link #patchClientLevelBreakingSoundVolume}: the browser mining hit sound volume
 *       ({@code (volume + 1) / 8} to {@code / 4}). 26.3 moved it from
 *       {@code MultiPlayerGameMode.continueDestroyBlock} (where MinecraftClientPatcher changes it
 *       on 26.2, and registers the patch as dropped on 26.3) to
 *       {@code ClientLevel.playBreakingSound}, which plays both the first hit and the server's
 *       periodic level event 2020. This reads the ClientLevel that MinecraftClientPatcher's
 *       patchClientLevelBrowserBlockBreakEffects already wrote, so the two patches compose.</li>
 * </ul>
 *
 * <p>{@code Minecraft263BrowserPatcher} registers it under its historical id
 * ({@code UiPatches263.patchClientLevelBreakingSoundVolume}) with the other patch of that group
 * ({@code RenderpearlResourcePatches}); its profiles come from the spec. Classes are read and
 * written through {@code RenderpearlPatchSupport}.
 */
public final class RenderpearlAudioPatches {
    static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";

    private RenderpearlAudioPatches() {
    }

    /** {@code ClientLevel.playBreakingSound}: {@code ldc 8.0f; fdiv} after getHitSound becomes 4.0f. */
    public static void patchClientLevelBreakingSoundVolume(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, CLIENT_LEVEL);
        MethodNode method = find(node, "playBreakingSound",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V");
        boolean hitSound = false;
        int divisors = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/world/level/block/SoundType")
                    && call.name.equals("getHitSound")
                    && call.desc.equals("()Lnet/minecraft/sounds/SoundEvent;")) {
                hitSound = true;
                continue;
            }
            if (hitSound
                    && instruction instanceof LdcInsnNode constant
                    && Float.valueOf(8.0f).equals(constant.cst)
                    && nextOpcode(instruction) != null
                    && nextOpcode(instruction).getOpcode() == Opcodes.FDIV) {
                constant.cst = Float.valueOf(4.0f);
                divisors++;
            }
        }
        if (!hitSound || divisors != 1) {
            throw new IllegalStateException("ClientLevel.playBreakingSound hit sound volume"
                    + " patch point was not found (getHitSound=" + hitSound + ", 8.0f divisors="
                    + divisors + ")");
        }
        write(node, root);
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }
}
