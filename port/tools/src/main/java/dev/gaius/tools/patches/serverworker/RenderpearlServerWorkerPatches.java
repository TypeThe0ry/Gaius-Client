package dev.gaius.tools.patches.serverworker;

import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.find;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.read;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.write;

import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The server-worker domain of the Minecraft263BrowserPatcher step, the tail of the modern chain
 * for the renderpearl render seam ({@code patches/server-worker}), work package P7a.
 *
 * <ul>
 *   <li>{@link #patchPlayerListIsOpBrowserCommands}: 26.3 removed
 *       {@code PlayerList.setAllowCommandsForAllPlayers}; {@code isOp} now ends in
 *       {@code iconst_0; ireturn}. The final {@code iconst_0} becomes
 *       {@code BrowserPlayerListCompat.commandsAllowedForAllPlayers()}, a Gaius flag that the
 *       26.3 {@code BrowserPlayerListCompat.allowCommandsForAllPlayers(PlayerList)} sets for the
 *       browser Worker. That is the 26.2 semantics (isOp &rarr; the dedicated server's
 *       operator permissions) without writing ops.json.</li>
 *   <li>{@link #patchMobSpawnOverlayCache}: serves repeated mob spawn overlays from a cache.</li>
 * </ul>
 *
 * <p>Not patched (recorded in the P7a notes): 26.3's teleport confirmation now runs the full
 * movement check through handlePlayerPositionChange, and the Worker movement fast path bypasses
 * the new one-position-packet-per-tick rule; both stay vanilla until measured.
 *
 * <p>{@code Minecraft263BrowserPatcher} registers these patches in the chain order under their
 * historical ids ({@code ServerPatches263.<method>}); their profiles come from the specs. The
 * startup yield and the discovery service patch of the same group are in the platform
 * ({@code RenderpearlPlatformPatches}) and network ({@code RenderpearlNetworkPatches}) domains.
 * {@code jar} is the chain's current client jar, {@code root} the directory whose classes are
 * merged back into it; classes are read and written through {@code RenderpearlPatchSupport}.
 */
public final class RenderpearlServerWorkerPatches {
    static final String PLAYER_LIST = "net/minecraft/server/players/PlayerList";
    static final String IS_OP_DESCRIPTOR = "(Lnet/minecraft/server/players/NameAndId;)Z";
    static final String PLAYER_LIST_COMPAT = "dev/gaius/browser/BrowserPlayerListCompat";
    static final String MOB_SPAWN_OVERLAY =
            "net/minecraft/world/attribute/modifier/MobSpawnSettingsModifier$Overlay";
    static final String MOB_SPAWN_SETTINGS = "net/minecraft/world/level/biome/MobSpawnSettings";
    static final String OVERLAY_APPLY_DESCRIPTOR =
            "(L" + MOB_SPAWN_SETTINGS + ";L" + MOB_SPAWN_SETTINGS + ";)L" + MOB_SPAWN_SETTINGS + ";";
    static final String OVERLAY_UNCACHED = "gaius$applyUncached";
    static final String OVERLAY_CACHE = "dev/gaius/browser/BrowserMobSpawnOverlayCache";

    private RenderpearlServerWorkerPatches() {
    }

    /**
     * Renames {@code MobSpawnSettingsModifier$Overlay.apply(MobSpawnSettings, MobSpawnSettings)}
     * to {@code gaius$applyUncached} and adds an {@code apply} that serves repeated pairs from
     * {@code BrowserMobSpawnOverlayCache}. The overlay is a pure function of two immutable
     * values; NaturalSpawner evaluated it for every entity on every tick (several percent of the
     * server Worker while chunks load).
     */
    public static void patchMobSpawnOverlayCache(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, MOB_SPAWN_OVERLAY);
        if ((node.access & Opcodes.ACC_FINAL) == 0) {
            throw new IllegalStateException(MOB_SPAWN_OVERLAY + " is no longer final");
        }
        if (node.methods.stream().anyMatch(method -> method.name.equals(OVERLAY_UNCACHED))) {
            throw new IllegalStateException(MOB_SPAWN_OVERLAY + " is already patched");
        }
        MethodNode original = find(node, "apply", OVERLAY_APPLY_DESCRIPTOR);
        original.name = OVERLAY_UNCACHED;
        MethodNode apply = new MethodNode(Opcodes.ACC_PUBLIC, "apply", OVERLAY_APPLY_DESCRIPTOR,
                null, null);
        LabelNode miss = new LabelNode();
        InsnList code = apply.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OVERLAY_CACHE, "lookup",
                "(L" + MOB_SPAWN_SETTINGS + ";L" + MOB_SPAWN_SETTINGS + ";)L"
                        + MOB_SPAWN_SETTINGS + ";", false));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(miss);
        code.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {MOB_SPAWN_SETTINGS}));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOB_SPAWN_OVERLAY, OVERLAY_UNCACHED,
                OVERLAY_APPLY_DESCRIPTOR, false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OVERLAY_CACHE, "store",
                "(L" + MOB_SPAWN_SETTINGS + ";L" + MOB_SPAWN_SETTINGS + ";L"
                        + MOB_SPAWN_SETTINGS + ";)L" + MOB_SPAWN_SETTINGS + ";", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        apply.maxStack = 5;
        apply.maxLocals = 3;
        node.methods.add(apply);
        write(node, root);
        System.out.println("Cached 26.3 MobSpawnSettingsModifier.Overlay.apply by identity");
    }

    public static void patchPlayerListIsOpBrowserCommands(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, PLAYER_LIST);
        for (MethodNode method : node.methods) {
            if (method.name.equals("setAllowCommandsForAllPlayers")) {
                throw new IllegalStateException(PLAYER_LIST
                        + " still has setAllowCommandsForAllPlayers; use the vanilla setter");
            }
        }
        MethodNode isOp = find(node, "isOp", IS_OP_DESCRIPTOR);
        for (AbstractInsnNode instruction : isOp.instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals(PLAYER_LIST)
                    && field.name.equals("allowCommandsForAllPlayers")) {
                throw new IllegalStateException("PlayerList.isOp still reads allowCommandsForAllPlayers");
            }
            if (instruction instanceof MethodInsnNode call && call.owner.equals(PLAYER_LIST_COMPAT)) {
                throw new IllegalStateException("PlayerList.isOp is already patched");
            }
        }
        AbstractInsnNode last = isOp.instructions.getLast();
        while (last != null && last.getOpcode() < 0) {
            last = last.getPrevious();
        }
        AbstractInsnNode denied = last == null ? null : previousOpcode(last);
        if (last == null || last.getOpcode() != Opcodes.IRETURN
                || denied == null || denied.getOpcode() != Opcodes.ICONST_0) {
            throw new IllegalStateException(
                    "PlayerList.isOp no longer ends in iconst_0; ireturn");
        }
        // Both push one int: max stack and frames are unchanged.
        isOp.instructions.set(denied, new MethodInsnNode(Opcodes.INVOKESTATIC, PLAYER_LIST_COMPAT,
                "commandsAllowedForAllPlayers", "()Z", false));
        write(node, root);
        System.out.println("Patched 26.3 PlayerList.isOp to honour the browser allow-commands flag");
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }
}
