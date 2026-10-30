package dev.gaius.tools.patches.ui;

import static dev.gaius.tools.patches.support.LegacyPatchSupport.find;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.read;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.requireOne;
import static dev.gaius.tools.patches.support.LegacyPatchSupport.write;

import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The ui domain of the Minecraft12111BrowserPatcher step, the {@code legacy-12111} patch set arm
 * ({@code patches/ui}): the browser view distances of the graphics presets and their
 * replay at startup.
 *
 * <p>{@code Minecraft12111BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft12111BrowserPatcher.<method>}); the ASM helpers that
 * other domains of that step share live in LegacyPatchSupport and are imported
 * statically.
 */
public final class LegacyUiPatches {
    private LegacyUiPatches() {
    }

    /**
     * Stops {@code Minecraft.<init>} from re-applying the saved graphics preset over the
     * per-GPU-tier first-launch options (see {@link GraphicsPresetStartupPatcher}); the 1.21.11
     * counterpart of {@code Minecraft262BrowserPatcher.patchGraphicsPresetStartupReplay}.
     */
    public static void patchGraphicsPresetStartupReplay(String jar, Path root)
            throws IOException {
        if (!GraphicsPresetStartupPatcher.apply(jar, root)) {
            throw new IllegalStateException(
                    "1.21.11 must have the graphics preset startup replay in Minecraft.<init>");
        }
    }

    /**
     * Verifies the 1.21.11 FAST preset's vanilla distance values without
     * reducing the user's render or simulation distance.  Browser throughput
     * work belongs in the cooperative schedulers; this patcher must not use a
     * smaller view as a substitute for fixing chunk delivery.
     *
     * <p>This is a narrow bytecode shape check on the FAST ordinal arm.  Match
     * the ordinal switch, the FAST arm's vanilla 8/6 constants, both Options
     * getter receiver chains, the boxed integer stores, and the preset setter.
     * Any missing, duplicated, or reshaped target fails closed instead of
     * allowing an unrelated distance rewrite.</p>
     *
     * <p>Unlike the 26.2/26.3 patcher, the FANCY (16/12) and FABULOUS (32/12) arms keep their
     * vanilla distances here (minecraft-12111-patcher-smoke pins all three arms to vanilla).
     * With {@code patchGraphicsPresetStartupReplay} they only apply when the player picks
     * that preset in Video Settings, or at startup with {@code ?gaiusPresetReplay=1}.</p>
     */
    public static void patchGraphicsPresetBrowserDistances(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/client/GraphicsPreset";
        String minecraft = "net/minecraft/client/Minecraft";
        String options = "net/minecraft/client/Options";
        String optionInstance = "Lnet/minecraft/client/OptionInstance;";
        String optionScreen = "Lnet/minecraft/client/gui/screens/options/OptionsSubScreen;";
        ClassNode node = read(jar, owner + ".class");
        MethodNode apply = find(node, "apply", "(L" + minecraft + ";)V");

        TableSwitchInsnNode presetSwitch = null;
        int switches = 0;
        for (AbstractInsnNode instruction : apply.instructions.toArray()) {
            if (instruction instanceof TableSwitchInsnNode table
                    && table.min == 0
                    && table.max == 2) {
                presetSwitch = table;
                switches++;
            }
        }
        requireOne("GraphicsPreset.apply ordinal switch", switches);
        if (presetSwitch == null || presetSwitch.labels.size() != 3) {
            throw new IllegalStateException("GraphicsPreset.apply FAST switch arm is missing");
        }

        int fastStart = apply.instructions.indexOf(presetSwitch.labels.get(0));
        if (fastStart < 0) {
            throw new IllegalStateException("GraphicsPreset.apply FAST switch label is missing");
        }
        AbstractInsnNode fastDistance = nextOpcode(presetSwitch.labels.get(0));
        if (!(fastDistance instanceof IntInsnNode integer)
                || integer.getOpcode() != Opcodes.BIPUSH
                || integer.operand != 8) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply FAST arm distance preamble changed");
        }
        AbstractInsnNode fastDistanceLocal = nextOpcode(fastDistance);
        if (!(fastDistanceLocal instanceof VarInsnNode local)
                || local.getOpcode() != Opcodes.ISTORE
                || local.var != 4) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply FAST arm distance local changed");
        }

        int fastEnd = apply.instructions.size();
        for (LabelNode branch : presetSwitch.labels) {
            int index = apply.instructions.indexOf(branch);
            if (index > fastStart && index < fastEnd) {
                fastEnd = index;
            }
        }
        int defaultIndex = apply.instructions.indexOf(presetSwitch.dflt);
        if (defaultIndex > fastStart && defaultIndex < fastEnd) {
            fastEnd = defaultIndex;
        }
        if (fastEnd == apply.instructions.size()) {
            throw new IllegalStateException("GraphicsPreset.apply FAST arm end is missing");
        }

        IntInsnNode renderDistance = findGraphicsPresetDistanceConstant(
                apply,
                fastStart,
                fastEnd,
                options,
                optionInstance,
                optionScreen,
                minecraft,
                owner,
                "renderDistance",
                8);
        IntInsnNode simulationDistance = findGraphicsPresetDistanceConstant(
                apply,
                fastStart,
                fastEnd,
                options,
                optionInstance,
                optionScreen,
                minecraft,
                owner,
                "simulationDistance",
                6);
        write(node, root.resolve(owner + ".class"));
        System.out.println(
                "Preserved Minecraft 1.21.11 FAST graphics preset distances at render=8 simulation=6");
    }

    private static IntInsnNode findGraphicsPresetDistanceConstant(
            MethodNode method,
            int start,
            int end,
            String options,
            String optionInstance,
            String optionScreen,
            String minecraft,
            String graphicsPreset,
            String getter,
            int expected) {
        int getterCount = 0;
        IntInsnNode constant = null;
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = instructions[index];
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(options)
                    || !call.name.equals(getter)
                    || !call.desc.equals("()" + optionInstance)) {
                continue;
            }
            getterCount++;
            AbstractInsnNode optionsField = previousOpcode(call);
            AbstractInsnNode minecraftLoad = previousOpcode(optionsField);
            AbstractInsnNode screenLoad = previousOpcode(minecraftLoad);
            AbstractInsnNode presetLoad = previousOpcode(screenLoad);
            if (!(optionsField instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(minecraft)
                    || !field.name.equals("options")
                    || !field.desc.equals("L" + options + ";")
                    || !(minecraftLoad instanceof VarInsnNode minecraftReceiver)
                    || minecraftReceiver.getOpcode() != Opcodes.ALOAD
                    || minecraftReceiver.var != 1
                    || !(screenLoad instanceof VarInsnNode screenReceiver)
                    || screenReceiver.getOpcode() != Opcodes.ALOAD
                    || screenReceiver.var != 2
                    || !(presetLoad instanceof VarInsnNode presetReceiver)
                    || presetReceiver.getOpcode() != Opcodes.ALOAD
                    || presetReceiver.var != 0) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply FAST " + getter + " receiver shape changed");
            }
            AbstractInsnNode value = nextOpcode(call);
            if (!(value instanceof IntInsnNode integer)
                    || integer.getOpcode() != Opcodes.BIPUSH
                    || integer.operand != expected) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply FAST " + getter + " constant shape changed");
            }
            AbstractInsnNode boxed = nextOpcode(value);
            if (!(boxed instanceof MethodInsnNode box)
                    || box.getOpcode() != Opcodes.INVOKESTATIC
                    || !box.owner.equals("java/lang/Integer")
                    || !box.name.equals("valueOf")
                    || !box.desc.equals("(I)Ljava/lang/Integer;")) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply FAST " + getter + " boxing shape changed");
            }
            AbstractInsnNode setter = nextOpcode(boxed);
            if (!(setter instanceof MethodInsnNode set)
                    || set.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !set.owner.equals(graphicsPreset)
                    || !set.name.equals("set")
                    || !set.desc.equals("(" + optionScreen + optionInstance
                            + "Ljava/lang/Object;)V")) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply FAST " + getter + " setter shape changed");
            }
            constant = integer;
        }
        requireOne("GraphicsPreset.apply FAST " + getter + " target", getterCount);
        if (constant == null) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply FAST " + getter + " target is missing");
        }
        return constant;
    }
}
