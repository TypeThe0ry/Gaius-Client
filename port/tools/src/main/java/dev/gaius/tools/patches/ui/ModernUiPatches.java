package dev.gaius.tools.patches.ui;

import static dev.gaius.tools.patches.support.ModernPatchSupport.find;
import static dev.gaius.tools.patches.support.ModernPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ModernPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ModernPatchSupport.read;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireOne;
import static dev.gaius.tools.patches.support.ModernPatchSupport.write;

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
 * The ui domain of the Minecraft262BrowserPatcher step, the {@code modern} patch set arm (DESIGN
 * D16 {@code patches/ui}): the browser view distances of the graphics presets and their replay
 * at startup.
 *
 * <p>{@code Minecraft262BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft262BrowserPatcher.<method>}); the ASM helpers that other
 * domains of that step share live in ModernPatchSupport and are imported statically.
 */
public final class ModernUiPatches {
    private ModernUiPatches() {
    }

    /**
     * Stops {@code Minecraft.<init>} from re-applying the saved graphics preset over the
     * per-GPU-tier first-launch options (see {@link GraphicsPresetStartupPatcher}). Shared by
     * 26.2 and 26.3, so it runs here exactly once per profile; the 26.3 chain no longer applies
     * it from {@code RenderPatches263}.
     */
    public static void patchGraphicsPresetStartupReplay(String jar, Path root)
            throws IOException {
        if (!GraphicsPresetStartupPatcher.apply(jar, root)) {
            throw new IllegalStateException(
                    "26.2/26.3 must have the graphics preset startup replay in Minecraft.<init>");
        }
    }

    /**
     * Keeps the 26.2/26.3 FAST, FANCY and FABULOUS presets on the browser 8/6 distance contract.
     *
     * <p>The browser patcher used to overwrite the FAST constants with a smaller distance
     * budget.  That silently reduced world visibility and simulation coverage, so the
     * ordinal-zero FAST arm stays validation-only at its vanilla 8/6.</p>
     *
     * <p>Applying a preset writes its distances over the player's options: the ordinal-one
     * FANCY arm writes render 16 / simulation 12 and the ordinal-two FABULOUS arm render 32 /
     * simulation 12, which BrowserSingleplayerClient then forwards to the Worker.  Since
     * {@code patchGraphicsPresetStartupReplay} the preset is no longer re-applied at every start
     * (only with {@code ?gaiusPresetReplay=1}), but selecting either preset in Video Settings
     * still applies it.  Only those four constants are rewritten to 8/6.  Every other FANCY and
     * FABULOUS value (mipmaps, AO, clouds, particles, shadows, transparency, filtering) keeps
     * its vanilla constant, and the preset is not switched to CUSTOM.  All three arms are
     * shape-checked before any constant changes, and the pass fails closed if Mojang changes
     * the bytecode.</p>
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
            // Each arm's leading distance local is a dead store.  FANCY keeps its vanilla 16
            // there, so fail closed if a future build starts reading it.
            if (instruction instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ILOAD
                    && load.var == 4) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply preset distance local is now read");
            }
        }
        requireOne("GraphicsPreset.apply ordinal switch", switches);
        if (presetSwitch == null || presetSwitch.labels.size() != 3) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply FAST/FANCY switch arm is missing");
        }

        int[] fast = graphicsPresetArm(apply, presetSwitch, 0, "FAST", 8);
        int[] fancy = graphicsPresetArm(apply, presetSwitch, 1, "FANCY", 16);
        int[] fabulous = graphicsPresetArm(apply, presetSwitch, 2, "FABULOUS", 32);
        findGraphicsPresetDistanceConstant(
                apply,
                fast[0],
                fast[1],
                options,
                optionInstance,
                optionScreen,
                minecraft,
                owner,
                "FAST",
                "renderDistance",
                8);
        findGraphicsPresetDistanceConstant(
                apply,
                fast[0],
                fast[1],
                options,
                optionInstance,
                optionScreen,
                minecraft,
                owner,
                "FAST",
                "simulationDistance",
                6);
        IntInsnNode fabulousRenderDistance = findGraphicsPresetDistanceConstant(
                apply,
                fabulous[0],
                fabulous[1],
                options,
                optionInstance,
                optionScreen,
                minecraft,
                owner,
                "FABULOUS",
                "renderDistance",
                32);
        IntInsnNode fabulousSimulationDistance = findGraphicsPresetDistanceConstant(
                apply,
                fabulous[0],
                fabulous[1],
                options,
                optionInstance,
                optionScreen,
                minecraft,
                owner,
                "FABULOUS",
                "simulationDistance",
                12);
        IntInsnNode fancyRenderDistance = findGraphicsPresetDistanceConstant(
                apply,
                fancy[0],
                fancy[1],
                options,
                optionInstance,
                optionScreen,
                minecraft,
                owner,
                "FANCY",
                "renderDistance",
                16);
        IntInsnNode fancySimulationDistance = findGraphicsPresetDistanceConstant(
                apply,
                fancy[0],
                fancy[1],
                options,
                optionInstance,
                optionScreen,
                minecraft,
                owner,
                "FANCY",
                "simulationDistance",
                12);
        fancyRenderDistance.operand = 8;
        fancySimulationDistance.operand = 6;
        fabulousRenderDistance.operand = 8;
        fabulousSimulationDistance.operand = 6;
        write(node, root.resolve(owner + ".class"));
        System.out.println(
                "Pinned Minecraft 26.2/26.3 FAST, FANCY and FABULOUS graphics preset distances at"
                        + " render=8 simulation=6 (FANCY was 16/12, FABULOUS 32/12)");
    }

    /** Returns the [start, end) instruction indexes of one GraphicsPreset.apply switch arm. */
    private static int[] graphicsPresetArm(
            MethodNode apply,
            TableSwitchInsnNode presetSwitch,
            int ordinal,
            String arm,
            int preambleDistance) {
        LabelNode label = presetSwitch.labels.get(ordinal);
        int start = apply.instructions.indexOf(label);
        if (start < 0) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply " + arm + " switch label is missing");
        }
        AbstractInsnNode distance = nextOpcode(label);
        if (!(distance instanceof IntInsnNode integer)
                || integer.getOpcode() != Opcodes.BIPUSH
                || integer.operand != preambleDistance) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply " + arm + " arm distance preamble changed");
        }
        AbstractInsnNode distanceLocal = nextOpcode(distance);
        if (!(distanceLocal instanceof VarInsnNode local)
                || local.getOpcode() != Opcodes.ISTORE
                || local.var != 4) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply " + arm + " arm distance local changed");
        }

        int end = apply.instructions.size();
        for (LabelNode branch : presetSwitch.labels) {
            int index = apply.instructions.indexOf(branch);
            if (index > start && index < end) {
                end = index;
            }
        }
        int defaultIndex = apply.instructions.indexOf(presetSwitch.dflt);
        if (defaultIndex > start && defaultIndex < end) {
            end = defaultIndex;
        }
        if (end == apply.instructions.size()) {
            throw new IllegalStateException("GraphicsPreset.apply " + arm + " arm end is missing");
        }
        return new int[] {start, end};
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
            String arm,
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
                    || screenReceiver.var != 2) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply " + arm + " " + getter + " receiver shape changed");
            }
            AbstractInsnNode value = nextOpcode(call);
            if (!(value instanceof IntInsnNode integer)
                    || integer.getOpcode() != Opcodes.BIPUSH
                    || integer.operand != expected) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply " + arm + " " + getter + " constant shape changed");
            }
            AbstractInsnNode boxed = nextOpcode(value);
            if (!(boxed instanceof MethodInsnNode box)
                    || box.getOpcode() != Opcodes.INVOKESTATIC
                    || !box.owner.equals("java/lang/Integer")
                    || !box.name.equals("valueOf")
                    || !box.desc.equals("(I)Ljava/lang/Integer;")) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply " + arm + " " + getter + " boxing shape changed");
            }
            AbstractInsnNode setter = nextOpcode(boxed);
            if (!(setter instanceof MethodInsnNode set)
                    || set.getOpcode() != Opcodes.INVOKESTATIC
                    || !set.owner.equals(graphicsPreset)
                    || !set.name.equals("set")
                    || !set.desc.equals("(" + optionScreen + optionInstance
                            + "Ljava/lang/Object;)V")) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply " + arm + " " + getter + " setter shape changed");
            }
            constant = integer;
        }
        requireOne("GraphicsPreset.apply " + arm + " " + getter + " target", getterCount);
        if (constant == null) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply " + arm + " " + getter + " target is missing");
        }
        return constant;
    }
}
