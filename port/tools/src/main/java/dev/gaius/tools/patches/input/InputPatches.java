package dev.gaius.tools.patches.input;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The input domain of the MinecraftClientPatcher step ({@code patches/input}): the
 * GLFW-generation keyboard and mouse handlers and {@code InputConstants}, routed to the browser
 * input dispatch. On a generation without the GLFW input setup the patches register their
 * targets as dropped.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically.
 */
public final class InputPatches {
    private InputPatches() {
    }

    public static void patchInputConstants(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/InputConstants.class");
        ClassNode type = read(jar, "com/mojang/blaze3d/platform/InputConstants$Type.class");
        if (type.fields.stream().noneMatch(field -> field.name.equals("KEYSYM"))) {
            // Minecraft 26.3 (SDL3): InputConstants$Type is {KEYBOARD, MOUSE}; the vanilla
            // <clinit> (LOGGER, UNKNOWN = KEYBOARD.getOrCreate(0)) is browser-safe and there is
            // no raw-mouse API to stub. Rewriting <clinit> would read the removed KEYSYM and
            // leave LOGGER null, so the class is left as it is.
            PatchRegistry.dropped("MinecraftClientPatcher.patchInputConstants.glfw", jar,
                    "com/mojang/blaze3d/platform/InputConstants$Type#KEYSYM",
                    "com/mojang/blaze3d/platform/InputConstants#isRawMouseInputSupported",
                    "com/mojang/blaze3d/platform/InputConstants#GLFW_RAW_MOUSE_MOTION_SUPPORTED",
                    "com/mojang/blaze3d/platform/InputConstants#GLFW_RAW_MOUSE_MOTION");
            return;
        }
        boolean hasRawMouseSupportHandle = node.fields.stream().anyMatch(field ->
                field.name.equals("GLFW_RAW_MOUSE_MOTION_SUPPORTED")
                        && field.desc.equals("Ljava/lang/invoke/MethodHandle;"));
        boolean hasRawMouseMotionConstant = node.fields.stream().anyMatch(field ->
                field.name.equals("GLFW_RAW_MOUSE_MOTION") && field.desc.equals("I"));
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                if (hasRawMouseSupportHandle) {
                    code.add(new InsnNode(Opcodes.ACONST_NULL));
                    code.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "com/mojang/blaze3d/platform/InputConstants",
                            "GLFW_RAW_MOUSE_MOTION_SUPPORTED",
                            "Ljava/lang/invoke/MethodHandle;"));
                }
                if (hasRawMouseMotionConstant) {
                    code.add(new InsnNode(Opcodes.ICONST_0));
                    code.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "com/mojang/blaze3d/platform/InputConstants",
                            "GLFW_RAW_MOUSE_MOTION",
                            "I"));
                }
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "com/mojang/blaze3d/platform/InputConstants$Type",
                        "KEYSYM",
                        "Lcom/mojang/blaze3d/platform/InputConstants$Type;"));
                code.add(new InsnNode(Opcodes.ICONST_M1));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "com/mojang/blaze3d/platform/InputConstants$Type",
                        "getOrCreate",
                        "(I)Lcom/mojang/blaze3d/platform/InputConstants$Key;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/InputConstants",
                        "UNKNOWN",
                        "Lcom/mojang/blaze3d/platform/InputConstants$Key;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, 0);
            } else if (method.name.equals("isRawMouseInputSupported")
                    && method.desc.equals("()Z")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, 0);
            }
        }
        write(node, output);
    }

    public static void patchBrowserInputCallbacks(String jar, Path root) throws IOException {
        if (!hasGlfwInputSetup(jar)) {
            // Minecraft 26.3 (SDL3): MouseHandler/KeyboardHandler lost setup(Window) and its
            // Minecraft.execute dispatch lambdas; SDLEventHandler dispatches instead and
            // InputPatches263 rewrites it together with the MouseHandler.onButton hooks.
            PatchRegistry.dropped("MinecraftClientPatcher.patchBrowserMouseHandler", jar,
                    "net/minecraft/client/MouseHandler#setup");
            PatchRegistry.dropped("MinecraftClientPatcher.patchBrowserKeyboardHandler", jar,
                    "net/minecraft/client/KeyboardHandler#setup");
            return;
        }
        patchBrowserMouseHandler(jar, root.resolve("net/minecraft/client/MouseHandler.class"));
        patchBrowserKeyboardHandler(jar, root.resolve("net/minecraft/client/KeyboardHandler.class"));
    }

    /** GLFW-era input (26.2, 1.21.11): MouseHandler has setup(Window) with dispatch lambdas. */
    private static boolean hasGlfwInputSetup(String jar) throws IOException {
        ClassNode mouseHandler = read(jar, "net/minecraft/client/MouseHandler.class");
        for (MethodNode method : mouseHandler.methods) {
            if (method.name.equals("setup")
                    && method.desc.equals("(Lcom/mojang/blaze3d/platform/Window;)V")) {
                return true;
            }
        }
        return false;
    }

    private static void patchBrowserMouseHandler(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/MouseHandler.class");

        MethodNode move = findInputSetupDispatch(node, "onMove", "(JDD)V");
        InsnList moveCode = new InsnList();
        moveCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        moveCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        moveCode.add(new VarInsnNode(Opcodes.DLOAD, 3));
        moveCode.add(new VarInsnNode(Opcodes.DLOAD, 5));
        moveCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/MouseHandler",
                "onMove",
                "(JDD)V",
                false));
        moveCode.add(new InsnNode(Opcodes.RETURN));
        replace(move, moveCode, 7, 7);

        MethodNode button = findInputSetupDispatch(node, "onButton", "(JIII)V");
        InsnList buttonCode = new InsnList();
        buttonCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        buttonCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        buttonCode.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/client/input/MouseButtonInfo"));
        buttonCode.add(new InsnNode(Opcodes.DUP));
        buttonCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        buttonCode.add(new VarInsnNode(Opcodes.ILOAD, 5));
        buttonCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/client/input/MouseButtonInfo",
                "<init>",
                "(II)V",
                false));
        buttonCode.add(new VarInsnNode(Opcodes.ILOAD, 4));
        buttonCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/MouseHandler",
                "onButton",
                "(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
                false));
        buttonCode.add(new InsnNode(Opcodes.RETURN));
        replace(button, buttonCode, 6, 6);

        MethodNode scroll = findInputSetupDispatch(node, "onScroll", "(JDD)V");
        InsnList scrollCode = new InsnList();
        scrollCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        scrollCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        scrollCode.add(new VarInsnNode(Opcodes.DLOAD, 3));
        scrollCode.add(new VarInsnNode(Opcodes.DLOAD, 5));
        scrollCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/MouseHandler",
                "onScroll",
                "(JDD)V",
                false));
        scrollCode.add(new InsnNode(Opcodes.RETURN));
        replace(scroll, scrollCode, 7, 7);

        MethodNode onButton = find(
                node,
                "onButton",
                "(JLnet/minecraft/client/input/MouseButtonInfo;I)V");
        boolean entryHooked = false;
        boolean dispatchHooked = false;
        boolean resultHooked = false;
        boolean overlayGatePatched = false;
        for (var instruction = onButton.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!entryHooked
                    && instruction instanceof VarInsnNode variable
                    && variable.getOpcode() == Opcodes.ASTORE
                    && variable.var == 5) {
                InsnList entry = new InsnList();
                entry.add(new VarInsnNode(Opcodes.LLOAD, 1));
                entry.add(new VarInsnNode(Opcodes.ALOAD, 5));
                entry.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "com/mojang/blaze3d/platform/Window",
                        "handle",
                        "()J",
                        false));
                entry.add(new VarInsnNode(Opcodes.ALOAD, 3));
                entry.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/input/MouseButtonInfo",
                        "button",
                        "()I",
                        false));
                entry.add(new VarInsnNode(Opcodes.ILOAD, 4));
                entry.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/glfw/BrowserGlfw",
                        "reportMouseHandlerEntry",
                        "(JJII)V",
                        false));
                onButton.instructions.insert(instruction, entry);
                entryHooked = true;
            }
            if (!dispatchHooked
                    && instruction instanceof VarInsnNode variable
                    && variable.getOpcode() == Opcodes.ASTORE
                    && variable.var == 13
                    && previousRealInstruction(instruction) instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/input/MouseButtonEvent")
                    && call.name.equals("<init>")
                    && call.desc.equals("(DDLnet/minecraft/client/input/MouseButtonInfo;)V")) {
                InsnList dispatch = new InsnList();
                dispatch.add(new VarInsnNode(Opcodes.DLOAD, 8));
                dispatch.add(new VarInsnNode(Opcodes.DLOAD, 10));
                dispatch.add(new VarInsnNode(Opcodes.ILOAD, 6));
                dispatch.add(new VarInsnNode(Opcodes.ALOAD, 12));
                dispatch.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/glfw/BrowserGlfw",
                        "reportMouseHandlerDispatch",
                        "(DDZLjava/lang/Object;)V",
                        false));
                onButton.instructions.insert(instruction, dispatch);
                dispatchHooked = true;
            }
            if (!resultHooked
                    && instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/gui/screens/Screen")
                    && call.name.equals("mouseClicked")
                    && call.desc.equals("(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z")) {
                InsnList result = new InsnList();
                result.add(new InsnNode(Opcodes.DUP));
                result.add(new VarInsnNode(Opcodes.DLOAD, 8));
                result.add(new VarInsnNode(Opcodes.DLOAD, 10));
                result.add(new VarInsnNode(Opcodes.ALOAD, 12));
                result.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/glfw/BrowserGlfw",
                        "reportMouseClickedResult",
                        "(ZDDLjava/lang/Object;)V",
                        false));
                onButton.instructions.insert(instruction, result);
                resultHooked = true;
            }
            if (!overlayGatePatched
                    && instruction instanceof MethodInsnNode call
                    && ((call.owner.equals("net/minecraft/client/Minecraft")
                                    && call.name.equals("getOverlay"))
                            || (call.owner.equals("net/minecraft/client/gui/Gui")
                                    && call.name.equals("overlay")))
                    && call.desc.equals("()Lnet/minecraft/client/gui/screens/Overlay;")) {
                boolean guiOwnsScreen = call.owner.equals("net/minecraft/client/gui/Gui");
                AbstractInsnNode maybeIfNull = nextRealInstruction(instruction);
                AbstractInsnNode maybeGoto = nextRealInstruction(maybeIfNull);
                if (!(maybeIfNull instanceof JumpInsnNode ifNull)
                        || ifNull.getOpcode() != Opcodes.IFNULL
                        || !(maybeGoto instanceof JumpInsnNode blocked)
                        || blocked.getOpcode() != Opcodes.GOTO) {
                    throw new IllegalStateException("MouseHandler overlay gate shape changed");
                }
                LabelNode blockedLabel = blocked.label;
                LabelNode allow = new LabelNode();
                LabelNode popAndBlock = new LabelNode();
                InsnList gate = new InsnList();
                gate.add(new InsnNode(Opcodes.DUP));
                gate.add(new JumpInsnNode(Opcodes.IFNULL, allow));
                gate.add(new InsnNode(Opcodes.DUP));
                gate.add(new TypeInsnNode(
                        Opcodes.INSTANCEOF,
                        "net/minecraft/client/gui/screens/LoadingOverlay"));
                gate.add(new JumpInsnNode(Opcodes.IFEQ, popAndBlock));
                gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
                gate.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/client/MouseHandler",
                        "minecraft",
                        "Lnet/minecraft/client/Minecraft;"));
                if (guiOwnsScreen) {
                    gate.add(new FieldInsnNode(
                            Opcodes.GETFIELD,
                            "net/minecraft/client/Minecraft",
                            "gui",
                            "Lnet/minecraft/client/gui/Gui;"));
                    gate.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL,
                            "net/minecraft/client/gui/Gui",
                            "screen",
                            "()Lnet/minecraft/client/gui/screens/Screen;",
                            false));
                } else {
                    gate.add(new FieldInsnNode(
                            Opcodes.GETFIELD,
                            "net/minecraft/client/Minecraft",
                            "screen",
                            "Lnet/minecraft/client/gui/screens/Screen;"));
                }
                gate.add(new JumpInsnNode(Opcodes.IFNONNULL, allow));
                gate.add(popAndBlock);
                gate.add(new InsnNode(Opcodes.POP));
                gate.add(new JumpInsnNode(Opcodes.GOTO, blockedLabel));
                gate.add(allow);
                gate.add(new InsnNode(Opcodes.POP));
                onButton.instructions.insertBefore(maybeIfNull, gate);
                onButton.instructions.remove(maybeIfNull);
                onButton.instructions.remove(maybeGoto);
                overlayGatePatched = true;
            }
        }
        if (!entryHooked || !dispatchHooked || !resultHooked || !overlayGatePatched) {
            throw new IllegalStateException(
                    "MouseHandler.onButton browser telemetry/overlay hook points were not found");
        }

        writeComputeFrames(node, output);
    }

    private static MethodNode findInputSetupDispatch(
            ClassNode node, String targetName, String... descriptors) throws IOException {
        MethodNode match = null;
        for (MethodNode method : node.methods) {
            boolean descriptorMatches = false;
            for (String descriptor : descriptors) {
                if (method.desc.equals(descriptor)) {
                    descriptorMatches = true;
                    break;
                }
            }
            if (!method.name.startsWith("lambda$setup$") || !descriptorMatches) {
                continue;
            }
            boolean dispatchesViaClient = false;
            String lambdaTarget = null;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals("net/minecraft/client/Minecraft")
                        && call.name.equals("execute")
                        && call.desc.equals("(Ljava/lang/Runnable;)V")) {
                    dispatchesViaClient = true;
                } else if (instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof org.objectweb.asm.Handle handle
                                && handle.getOwner().equals(node.name)) {
                            lambdaTarget = handle.getName();
                        }
                    }
                }
            }
            if (!dispatchesViaClient || lambdaTarget == null) {
                continue;
            }
            MethodNode target = null;
            for (MethodNode candidate : node.methods) {
                if (candidate.name.equals(lambdaTarget)) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                continue;
            }
            boolean callsTarget = false;
            for (AbstractInsnNode instruction = target.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(node.name)
                        && call.name.equals(targetName)) {
                    callsTarget = true;
                    break;
                }
            }
            if (!callsTarget) {
                continue;
            }
            if (match != null) {
                throw new IOException("Multiple input setup dispatchers found for " + targetName);
            }
            match = method;
        }
        if (match == null) {
            throw new IOException("Input setup dispatcher was not found for " + targetName);
        }
        return match;
    }

    private static void patchBrowserKeyboardHandler(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/KeyboardHandler.class");

        MethodNode key = findInputSetupDispatch(node, "keyPress", "(JIIII)V");
        InsnList keyCode = new InsnList();
        keyCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        keyCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        keyCode.add(new VarInsnNode(Opcodes.ILOAD, 5));
        keyCode.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/client/input/KeyEvent"));
        keyCode.add(new InsnNode(Opcodes.DUP));
        keyCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        keyCode.add(new VarInsnNode(Opcodes.ILOAD, 4));
        keyCode.add(new VarInsnNode(Opcodes.ILOAD, 6));
        keyCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/client/input/KeyEvent",
                "<init>",
                "(III)V",
                false));
        keyCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/KeyboardHandler",
                "keyPress",
                "(JILnet/minecraft/client/input/KeyEvent;)V",
                false));
        keyCode.add(new InsnNode(Opcodes.RETURN));
        replace(key, keyCode, 8, 7);

        MethodNode character = findInputSetupDispatch(node, "charTyped", "(JII)V", "(JI)V");
        boolean characterHasModifiers = character.desc.equals("(JII)V");
        InsnList charCode = new InsnList();
        charCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        charCode.add(new VarInsnNode(Opcodes.LLOAD, 1));
        charCode.add(new TypeInsnNode(Opcodes.NEW, "net/minecraft/client/input/CharacterEvent"));
        charCode.add(new InsnNode(Opcodes.DUP));
        charCode.add(new VarInsnNode(Opcodes.ILOAD, 3));
        if (characterHasModifiers) {
            charCode.add(new VarInsnNode(Opcodes.ILOAD, 4));
        }
        charCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/client/input/CharacterEvent",
                "<init>",
                characterHasModifiers ? "(II)V" : "(I)V",
                false));
        charCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/KeyboardHandler",
                "charTyped",
                "(JLnet/minecraft/client/input/CharacterEvent;)V",
                false));
        charCode.add(new InsnNode(Opcodes.RETURN));
        replace(character, charCode, 6, characterHasModifiers ? 5 : 4);

        writeComputeFrames(node, output);
    }
}
