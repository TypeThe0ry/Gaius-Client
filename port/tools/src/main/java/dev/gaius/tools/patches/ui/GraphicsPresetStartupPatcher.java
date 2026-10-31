package dev.gaius.tools.patches.ui;

import dev.gaius.tools.patches.support.QualityClasses;
import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Stops {@code Minecraft.<init>} from re-applying the saved graphics preset on every start.
 *
 * <p>Vanilla calls {@code options.applyGraphicsPreset(options.graphicsPreset().get())} once the
 * options are loaded. For a named preset (Fast/Fancy/Fabulous) that overwrites every option the
 * preset covers, so the per-GPU-tier first-launch options BrowserFilePersistence seeds (and any
 * value saved under a named preset) never took effect: new players always got the Fast values.
 * The call is redirected to a new {@code Options.gaius$applyStartupGraphicsPreset}, which only
 * replays the preset when {@code BrowserQualityOptions.replayGraphicsPresetAtStartup()} says so
 * ({@code ?gaiusPresetReplay=1} restores the vanilla behaviour). Selecting a preset in Video
 * Settings is unchanged, and changing a single option still switches the preset to Custom.
 *
 * <p>A helper of the ui domain classes of both patch set arms (LegacyUiPatches, ModernUiPatches);
 * class access through {@code QualityClasses}.
 *
 * <p>Profile independent: 1.21.11, 26.2 and 26.3 all have the call and the method. It runs
 * once per profile: from {@code Minecraft262BrowserPatcher.patchGraphicsPresetStartupReplay}
 * for 26.2 and 26.3 (after that step's {@code patchLiveFrameTargeting}, which writes
 * {@code Minecraft.class} from the input jar) and from
 * {@code Minecraft12111BrowserPatcher.patchGraphicsPresetStartupReplay} for 1.21.11; both
 * fail when {@link #apply} returns false. A profile without {@code GraphicsPreset} is reported
 * as not applicable ({@link #apply} returns false); a profile that has it in a different shape
 * fails the build.
 */
public final class GraphicsPresetStartupPatcher {
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String OPTIONS = "net/minecraft/client/Options";
    static final String GRAPHICS_PRESET = "net/minecraft/client/GraphicsPreset";
    static final String APPLY = "applyGraphicsPreset";
    static final String STARTUP_APPLY = "gaius$applyStartupGraphicsPreset";
    static final String APPLY_DESC = "(L" + GRAPHICS_PRESET + ";)V";
    static final String QUALITY_OPTIONS = "dev/gaius/browser/quality/BrowserQualityOptions";

    private GraphicsPresetStartupPatcher() {
    }

    /** Returns false (and logs) when the profile has no graphics preset to replay. */
    public static boolean apply(String jar, Path root) throws IOException {
        if (!QualityClasses.present(jar, root, GRAPHICS_PRESET)) {
            System.out.println("GraphicsPresetStartupPatcher: not applicable, " + GRAPHICS_PRESET
                    + " is not part of this profile");
            return false;
        }
        ClassNode options = QualityClasses.read(jar, root, OPTIONS);
        MethodNode apply = QualityClasses.find(options, APPLY, APPLY_DESC);
        if (QualityClasses.isStatic(apply)) {
            throw new IllegalStateException(OPTIONS + "." + APPLY + " is static");
        }
        QualityClasses.requireAbsent(options, STARTUP_APPLY, APPLY_DESC);

        ClassNode minecraft = QualityClasses.read(jar, root, MINECRAFT);
        int redirected = 0;
        for (MethodNode method : minecraft.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            for (MethodInsnNode call : QualityClasses.calls(method, Opcodes.INVOKEVIRTUAL,
                    OPTIONS, APPLY, APPLY_DESC)) {
                call.name = STARTUP_APPLY;
                redirected++;
            }
        }
        if (redirected != 1) {
            throw new IllegalStateException(MINECRAFT + ".<init> calls " + OPTIONS + "." + APPLY
                    + " " + redirected + " times, expected 1 (the startup preset replay)");
        }

        // public void gaius$applyStartupGraphicsPreset(GraphicsPreset preset) {
        //     if (BrowserQualityOptions.replayGraphicsPresetAtStartup()) applyGraphicsPreset(preset);
        // }
        MethodNode startup = new MethodNode(Opcodes.ACC_PUBLIC, STARTUP_APPLY, APPLY_DESC, null,
                null);
        LabelNode done = new LabelNode();
        InsnList code = startup.instructions;
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUALITY_OPTIONS,
                "replayGraphicsPresetAtStartup", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OPTIONS, APPLY, APPLY_DESC, false));
        code.add(done);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new InsnNode(Opcodes.RETURN));
        startup.maxLocals = 2;
        startup.maxStack = 2;
        options.methods.add(startup);

        QualityClasses.write(options, root);
        QualityClasses.write(minecraft, root);
        System.out.println("Minecraft.<init> no longer re-applies the saved graphics preset"
                + " (Options." + STARTUP_APPLY + ", ?gaiusPresetReplay=1 restores it)");
        return true;
    }
}
