package dev.gaius.tools.patches.ui;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousRealInstruction;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replaceLocalServerCheck;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The ui domain of the MinecraftClientPatcher step ({@code patches/ui}): screens and
 * menus (title, pause, loading, world creation, pack selection, debug overlay), the browser
 * option defaults, GUI item caches and the font providers (FreeType, unihex, bitmap fonts).
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically.
 */
public final class UiPatches {
    private UiPatches() {
    }

    public static void patchOptionsBrowserLowSimulationDistance(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/Options.class");
        int patched = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            boolean inSimulationDistance = false;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof LdcInsnNode constant
                        && "options.simulationDistance".equals(constant.cst)) {
                    inSimulationDistance = true;
                    continue;
                }
                if (inSimulationDistance
                        && instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals("net/minecraft/SharedConstants")
                        && field.name.equals("DEBUG_ALLOW_LOW_SIM_DISTANCE")
                        && field.desc.equals("Z")) {
                    method.instructions.set(field, new InsnNode(Opcodes.ICONST_1));
                    patched++;
                    break;
                }
            }
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "Options simulation-distance range patch point was not found");
        }
        patchOptionsFastGraphicsPresetDefault(node);
        MethodNode save = find(node, "save", "()V");
        int distanceSyncs = 0;
        for (AbstractInsnNode instruction = save.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList sync = new InsnList();
            sync.add(new VarInsnNode(Opcodes.ALOAD, 0));
            sync.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/Options",
                    "minecraft",
                    "Lnet/minecraft/client/Minecraft;"));
            sync.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserSingleplayerClient",
                    "syncDistances",
                    "(Lnet/minecraft/client/Minecraft;)V",
                    false));
            save.instructions.insertBefore(instruction, sync);
            distanceSyncs++;
        }
        if (distanceSyncs == 0) {
            throw new IllegalStateException("Options save browser distance sync point was not found");
        }
        save.maxStack = Math.max(save.maxStack, 1);
        write(node, output);
    }

    /**
     * New browser players start on the Fast graphics preset. {@code Minecraft.<init>} applies the
     * persisted preset before the first resource load, so only the option's default value
     * changes; a saved choice (including Fancy) still wins. Profiles without the 26.2
     * graphics preset option (1.21.11) are left unchanged.
     */
    private static void patchOptionsFastGraphicsPresetDefault(ClassNode node) {
        String preset = "net/minecraft/client/GraphicsPreset";
        int patched = 0;
        boolean presetOption = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            boolean inPreset = false;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof LdcInsnNode constant
                        && "options.graphics.preset".equals(constant.cst)) {
                    inPreset = true;
                    presetOption = true;
                    continue;
                }
                if (inPreset
                        && instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD) {
                    break;
                }
                if (inPreset
                        && instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals(preset)
                        && field.name.equals("FANCY")) {
                    field.name = "FAST";
                    patched++;
                    break;
                }
            }
        }
        if (presetOption && patched != 1) {
            throw new IllegalStateException(
                    "Options graphics preset default patch point was not found: " + patched);
        }
    }

    public static void patchCreateWorldScreenBrowserDefaults(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/screens/worldselection/CreateWorldScreen.class");
        boolean foundNormalPreset = false;
        boolean foundDefaultOptions = false;
        boolean foundNormalDimensions = false;
        boolean patchedAllowCommands = false;
        boolean patchedInitialAllowCommands = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("openFresh")
                    && method.desc.equals("(Lnet/minecraft/client/Minecraft;Ljava/lang/Runnable;"
                            + "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldCallback;)V")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof FieldInsnNode field
                            && field.owner.equals("net/minecraft/world/level/levelgen/presets/WorldPresets")
                            && field.name.equals("NORMAL")
                            && field.desc.equals("Lnet/minecraft/resources/ResourceKey;")) {
                        foundNormalPreset = true;
                    }
                }
            } else if (method.name.startsWith("lambda$openFresh$")
                    && method.desc.equals("(Lnet/minecraft/server/WorldLoader$DataLoadContext;)"
                            + "Lnet/minecraft/world/level/levelgen/WorldGenSettings;")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("net/minecraft/world/level/levelgen/WorldOptions")
                            && call.name.equals("defaultWithRandomSeed")
                            && call.desc.equals("()Lnet/minecraft/world/level/levelgen/WorldOptions;")) {
                        foundDefaultOptions = true;
                    } else if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("net/minecraft/world/level/levelgen/presets/WorldPresets")
                            && call.name.equals("createNormalWorldDimensions")
                            && call.desc.equals("(Lnet/minecraft/core/HolderLookup$Provider;)"
                                    + "Lnet/minecraft/world/level/levelgen/WorldDimensions;")) {
                        foundNormalDimensions = true;
                    }
                }
            } else if (method.name.equals("createLevelSettings")
                    && method.desc.equals("(Z)Lnet/minecraft/world/level/LevelSettings;")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("net/minecraft/client/gui/screens/worldselection/WorldCreationUiState")
                            && call.name.equals("isAllowCommands")
                            && call.desc.equals("()Z")) {
                        InsnList replacement = new InsnList();
                        replacement.add(new InsnNode(Opcodes.POP));
                        replacement.add(new InsnNode(Opcodes.ICONST_1));
                        method.instructions.insertBefore(call, replacement);
                        method.instructions.remove(call);
                        patchedAllowCommands = true;
                        break;
                    }
                }
            } else if (method.name.equals("<init>")
                    && method.desc.equals("(Lnet/minecraft/client/Minecraft;Ljava/lang/Runnable;"
                            + "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationContext;"
                            + "Ljava/util/Optional;Ljava/util/OptionalLong;"
                            + "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldCallback;)V")) {
                for (var instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.PUTFIELD
                            && field.owner.equals("net/minecraft/client/gui/screens/worldselection/CreateWorldScreen")
                            && field.name.equals("uiState")
                            && field.desc.equals("Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;")) {
                        InsnList enableCommands = new InsnList();
                        enableCommands.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        enableCommands.add(new FieldInsnNode(
                                Opcodes.GETFIELD,
                                "net/minecraft/client/gui/screens/worldselection/CreateWorldScreen",
                                "uiState",
                                "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;"));
                        enableCommands.add(new InsnNode(Opcodes.ICONST_1));
                        enableCommands.add(new MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL,
                                "net/minecraft/client/gui/screens/worldselection/WorldCreationUiState",
                                "setAllowCommands",
                                "(Z)V",
                                false));
                        method.instructions.insert(field, enableCommands);
                        method.maxStack = Math.max(method.maxStack, 3);
                        patchedInitialAllowCommands = true;
                        break;
                    }
                }
            }
        }
        if (!foundNormalPreset
                || !foundDefaultOptions
                || !foundNormalDimensions
                || !patchedAllowCommands
                || !patchedInitialAllowCommands) {
            throw new IllegalStateException(
                    "CreateWorldScreen browser default world patch points were not found: normalPreset="
                            + foundNormalPreset
                            + ", defaultOptions=" + foundDefaultOptions
                            + ", normalDimensions=" + foundNormalDimensions
                            + ", allowCommands=" + patchedAllowCommands
                            + ", initialAllowCommands=" + patchedInitialAllowCommands);
        }
        write(node, output);
    }

    public static void patchScreenBrowserFastMenus(String jar, Path output) throws IOException {
        /*
         * Preserve the vanilla animated panorama and menu background.  The
         * previous browser optimisation replaced these methods with a solid
         * fill, which removed the original title-screen motion and made the
         * launcher feel frozen.  Menu blurriness is already disabled in the
         * browser defaults, so keeping the original render path is both
         * visually correct and bounded to the normal texture draw calls.
         * Options.menuBackgroundBlurriness stays at zero in browser defaults,
         * so the restored animation remains crisp without the blur pass.
         */
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/Screen.class");
        boolean extractedGui = node.methods.stream()
                .anyMatch(method -> method.name.equals("extractPanorama"));
        String graphics = extractedGui
                ? "net/minecraft/client/gui/GuiGraphicsExtractor"
                : "net/minecraft/client/gui/GuiGraphics";
        find(node,
                extractedGui ? "extractPanorama" : "renderPanorama",
                "(L" + graphics + ";F)V");
        find(node,
                extractedGui ? "extractMenuBackground" : "renderMenuBackground",
                "(L" + graphics + ";IIII)V");
        write(node, output);
    }

    public static void patchLevelLoadingScreenBrowserFastProgress(
            String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/LevelLoadingScreen.class");
        boolean extractedGui = node.methods.stream()
                .anyMatch(method -> method.name.equals("extractChunksForRendering"));
        String graphics = extractedGui
                ? "net/minecraft/client/gui/GuiGraphicsExtractor"
                : "net/minecraft/client/gui/GuiGraphics";
        MethodNode renderChunks = find(
                node,
                extractedGui ? "extractChunksForRendering" : "renderChunks",
                "(L" + graphics + ";IIIILnet/minecraft/server/level/progress/"
                        + "ChunkLoadStatusView;)V");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(renderChunks, code, 0, 6);
        write(node, output);
    }

    public static void patchTitleScreenBrowserFastMenus(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/TitleScreen.class");
        MethodNode realms = find(node, "realmsNotificationsEnabled", "()Z");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(realms, code, 1, 1);

        MethodNode initializer = find(node, "<clinit>", "()V");
        int attributionRewrites = 0;
        for (AbstractInsnNode instruction = initializer.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof LdcInsnNode literal)
                    || !"title.credits".equals(literal.cst)) {
                continue;
            }
            AbstractInsnNode next = nextRealInstruction(instruction);
            if (!(next instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("net/minecraft/network/chat/Component")
                    || !call.name.equals("translatable")
                    || !call.desc.equals("(Ljava/lang/String;)"
                            + "Lnet/minecraft/network/chat/MutableComponent;")) {
                throw new IllegalStateException("TitleScreen attribution shape changed");
            }
            literal.cst = "gaius.title_about";
            attributionRewrites++;
        }
        if (attributionRewrites != 1) {
            throw new IllegalStateException(
                    "TitleScreen browser attribution patch point was not found: "
                            + attributionRewrites);
        }

        int attributionCallbacks = 0;
        for (MethodNode method : node.methods) {
            boolean opensVanillaCredits = false;
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW
                        && type.desc.equals(
                                "net/minecraft/client/gui/screens/CreditsAndAttributionScreen")) {
                    opensVanillaCredits = true;
                    break;
                }
            }
            if (!opensVanillaCredits) {
                continue;
            }
            InsnList callback = new InsnList();
            callback.add(new VarInsnNode(Opcodes.ALOAD, 0));
            callback.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserAboutScreen", "open",
                    "(Lnet/minecraft/client/gui/screens/Screen;)V", false));
            callback.add(new InsnNode(Opcodes.RETURN));
            replace(method, callback, 1, 2);
            attributionCallbacks++;
        }
        if (attributionCallbacks != 1) {
            throw new IllegalStateException(
                    "TitleScreen vanilla credits callback patch point was not found: "
                            + attributionCallbacks);
        }
        fitTitleScreenCopyrightButton(node);
        disableTitleScreenRealmsButton(node);
        enableTitleScreenMultiplayerButton(node);
        removeTitleScreenIconButtons(node);
        hookTitleScreenBrowserQuit(node);
        boolean current = node.methods.stream()
                .anyMatch(method -> method.name.equals("extractRenderState"));
        if (current) {
            addTitleScreenProfileHooks(node);
        }
        write(node, output);
    }

    /** Calls the launcher quit barrier only for the title screen's Quit Game button. */
    private static void hookTitleScreenBrowserQuit(ClassNode node) {
        int hooks = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals("net/minecraft/client/Minecraft")
                        || !call.name.equals("stop")
                        || !call.desc.equals("()V")) {
                    continue;
                }
                method.instructions.set(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserSingleplayerClient",
                        "quit",
                        "()V",
                        false));
                hooks++;
            }
        }
        if (hooks != 1) {
            throw new IllegalStateException(
                    "TitleScreen browser quit hook point count changed: " + hooks);
        }
    }

    /** Refit the localized copyright PlainTextButton after vanilla registers it. */
    private static void fitTitleScreenCopyrightButton(ClassNode node) {
        MethodNode init = find(node, "init", "()V");
        String plain = "net/minecraft/client/gui/components/PlainTextButton";
        String listener = "net/minecraft/client/gui/components/events/GuiEventListener";
        int found = 0;
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (!(instruction instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
                    || !type.desc.equals(plain)) {
                continue;
            }
            MethodInsnNode add = null;
            for (AbstractInsnNode cursor = instruction.getNext(); cursor != null; cursor = cursor.getNext()) {
                if (cursor instanceof MethodInsnNode call && call.name.equals("addRenderableWidget")
                        && call.desc.endsWith("L" + listener + ";")) {
                    add = call;
                    break;
                }
            }
            if (add == null) {
                throw new IllegalStateException("TitleScreen copyright button registration changed");
            }
            AbstractInsnNode next = nextOpcode(add);
            if (!(next instanceof InsnNode pop) || pop.getOpcode() != Opcodes.POP) {
                throw new IllegalStateException("TitleScreen copyright button result shape changed");
            }
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new InsnNode(Opcodes.SWAP));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserAboutScreen", "fitCopyrightButton",
                    "(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/components/events/GuiEventListener;)V",
                    false));
            init.instructions.insertBefore(pop, hook);
            init.instructions.remove(pop);
            found++;
        }
        if (found != 1) {
            throw new IllegalStateException("TitleScreen copyright button count: " + found);
        }
    }

    /** Removes the title-screen profile, language and accessibility icon row. */
    private static void removeTitleScreenIconButtons(ClassNode node) {
        MethodNode init = find(node, "init", "()V");
        String commonButtons = "net/minecraft/client/gui/components/CommonButtons";
        java.util.List<AbstractInsnNode[]> ranges = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode factory)
                    || factory.getOpcode() != Opcodes.INVOKESTATIC
                    || !factory.owner.equals(commonButtons)
                    || !(factory.name.equals("friends") || factory.name.equals("language")
                            || factory.name.equals("accessibility"))) {
                continue;
            }
            AbstractInsnNode boundary = null;
            for (AbstractInsnNode cursor = previousOpcode(instruction);
                    cursor != null; cursor = previousOpcode(cursor)) {
                if (cursor instanceof org.objectweb.asm.tree.IincInsnNode increment
                        && increment.incr == 24) {
                    boundary = cursor;
                    break;
                }
                if (cursor instanceof MethodInsnNode position
                        && position.name.equals("setPosition")) {
                    boundary = nextOpcode(position);
                    break;
                }
                // 1.21.11 registers Options and Quit Game between the language and the
                // accessibility icon, so the accessibility block starts after the Quit Game
                // statement, not after the language icon's setPosition.
                if (cursor instanceof InsnNode pop && pop.getOpcode() == Opcodes.POP
                        && previousOpcode(pop) instanceof MethodInsnNode add
                        && add.name.equals("addRenderableWidget")) {
                    boundary = nextOpcode(pop);
                    break;
                }
            }
            AbstractInsnNode end = instruction;
            while (end != null) {
                if (end instanceof MethodInsnNode position && position.name.equals("setPosition")) {
                    break;
                }
                end = end.getNext();
            }
            if (boundary == null || end == null) {
                // 1.21.11 increments the shared row cursor after constructing the language
                // button, so the increment is no longer a predecessor of the factory call.
                // Start after the previous widget registration in that layout.
                if (boundary == null) {
                    for (AbstractInsnNode cursor = previousOpcode(instruction);
                            cursor != null; cursor = previousOpcode(cursor)) {
                        if (cursor instanceof MethodInsnNode add
                                && add.name.equals("addRenderableWidget")) {
                            boundary = nextOpcode(add);
                            break;
                        }
                    }
                }
                if (boundary == null) {
                    for (AbstractInsnNode cursor = previousOpcode(instruction);
                            cursor != null; cursor = previousOpcode(cursor)) {
                        if (cursor instanceof VarInsnNode store
                                && store.getOpcode() == Opcodes.ISTORE) {
                            boundary = nextOpcode(store);
                            break;
                        }
                    }
                }
                if (end == null) {
                    // Some legacy bytecode stores the icon before the position call is
                    // represented by the symbol table. The next widget registration is an
                    // unambiguous end marker for the icon setup block.
                    for (AbstractInsnNode cursor = instruction.getNext();
                            cursor != null; cursor = cursor.getNext()) {
                        if (cursor instanceof MethodInsnNode add
                                && add.name.equals("addRenderableWidget")) {
                            end = previousOpcode(add);
                            break;
                        }
                    }
                }
            }
            if (boundary == null || end == null) {
                throw new IllegalStateException("TitleScreen icon button shape changed: " + factory.name
                        + " boundary=" + (boundary != null) + " end=" + (end != null));
            }
            ranges.add(new AbstractInsnNode[] {boundary, end});
        }
        if (ranges.size() < 2) {
            throw new IllegalStateException("TitleScreen language/accessibility icon buttons were not found: "
                    + ranges.size());
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
            AbstractInsnNode start = ranges.get(i)[0];
            AbstractInsnNode end = ranges.get(i)[1];
            AbstractInsnNode cursor = start;
            while (cursor != null) {
                AbstractInsnNode next = cursor.getNext();
                // 1.21.11 advances the row cursor (l += 36) inside the language icon's
                // setPosition arguments; Options and Quit Game still read it afterwards.
                boolean keep = cursor != start
                        && cursor instanceof org.objectweb.asm.tree.IincInsnNode increment
                        && readsLocalAfter(end, increment.var);
                if (!keep) {
                    init.instructions.remove(cursor);
                }
                if (cursor == end) {
                    break;
                }
                cursor = next;
            }
        }
    }

    private static boolean readsLocalAfter(AbstractInsnNode from, int local) {
        for (AbstractInsnNode cursor = from.getNext(); cursor != null; cursor = cursor.getNext()) {
            if (cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD
                    && load.var == local) {
                return true;
            }
        }
        return false;
    }

    /**
     * Greys out the title screen's "Minecraft Realms" button and explains why in its tooltip.
     * Realms needs a signed-in Microsoft account; the browser client runs an offline session, so
     * RealmsMainScreen always ended on "Invalid Session / Please try restarting Minecraft".
     * Every supported profile builds the button right after the {@code menu.online} literal: its
     * {@code Builder.tooltip(...)} argument becomes the Gaius tooltip and the following
     * {@code Button.active} store becomes {@code false}.
     */
    private static void disableTitleScreenRealmsButton(ClassNode node) {
        MethodNode options = node.methods.stream()
                .filter(method -> method.name.equals("createNormalMenuOptions"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TitleScreen.createNormalMenuOptions is missing"));
        String button = "net/minecraft/client/gui/components/Button";
        AbstractInsnNode label = null;
        for (AbstractInsnNode instruction : options.instructions.toArray()) {
            if (instruction instanceof LdcInsnNode literal && "menu.online".equals(literal.cst)) {
                if (label != null) {
                    throw new IllegalStateException("TitleScreen has more than one menu.online button");
                }
                label = instruction;
            }
        }
        if (label == null) {
            throw new IllegalStateException("TitleScreen Realms button (menu.online) was not found");
        }
        MethodInsnNode tooltipCall = null;
        FieldInsnNode activeStore = null;
        for (AbstractInsnNode instruction = label.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (tooltipCall == null && instruction instanceof MethodInsnNode call
                    && call.owner.equals(button + "$Builder") && call.name.equals("tooltip")) {
                tooltipCall = call;
            } else if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("active") && field.desc.equals("Z")) {
                activeStore = field;
                break;
            }
        }
        AbstractInsnNode tooltipArgument = tooltipCall == null ? null : previousOpcode(tooltipCall);
        AbstractInsnNode activeValue = activeStore == null ? null : previousOpcode(activeStore);
        if (!(tooltipArgument instanceof VarInsnNode tooltipLoad) || tooltipLoad.getOpcode() != Opcodes.ALOAD
                || !(activeValue instanceof VarInsnNode activeLoad) || activeLoad.getOpcode() != Opcodes.ILOAD) {
            throw new IllegalStateException("TitleScreen Realms button shape changed");
        }
        InsnList tooltip = new InsnList();
        tooltip.add(new LdcInsnNode("Realms needs a signed-in Microsoft account, so it is not"
                + " available in the browser."));
        tooltip.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/network/chat/Component",
                "literal", "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", true));
        tooltip.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/client/gui/components/Tooltip",
                "create", "(Lnet/minecraft/network/chat/Component;)Lnet/minecraft/client/gui/components/Tooltip;",
                false));
        options.instructions.insertBefore(tooltipLoad, tooltip);
        options.instructions.remove(tooltipLoad);
        options.instructions.insertBefore(activeLoad, new InsnNode(Opcodes.ICONST_0));
        options.instructions.remove(activeLoad);
        options.maxStack = Math.max(options.maxStack, 6);
    }

    /** Keeps the vanilla Multiplayer action available in the offline browser client. */
    private static void enableTitleScreenMultiplayerButton(ClassNode node) {
        MethodNode options = node.methods.stream()
                .filter(method -> method.name.equals("createNormalMenuOptions"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TitleScreen.createNormalMenuOptions is missing"));
        AbstractInsnNode label = null;
        for (AbstractInsnNode instruction : options.instructions.toArray()) {
            if (instruction instanceof LdcInsnNode literal && "menu.multiplayer".equals(literal.cst)) {
                if (label != null) {
                    throw new IllegalStateException("TitleScreen has more than one menu.multiplayer button");
                }
                label = instruction;
            }
        }
        if (label == null) {
            throw new IllegalStateException("TitleScreen Multiplayer button was not found");
        }
        FieldInsnNode activeStore = null;
        for (AbstractInsnNode instruction = label.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals("net/minecraft/client/gui/components/Button")
                    && field.name.equals("active") && field.desc.equals("Z")) {
                activeStore = field;
                break;
            }
        }
        AbstractInsnNode activeValue = activeStore == null ? null : previousOpcode(activeStore);
        if (!(activeValue instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD) {
            throw new IllegalStateException("TitleScreen Multiplayer active value shape changed");
        }
        options.instructions.insertBefore(load, new InsnNode(Opcodes.ICONST_1));
        options.instructions.remove(load);
    }

    private static void addTitleScreenProfileHooks(ClassNode node) {
        String owner = "net/minecraft/client/gui/screens/TitleScreen";
        MethodNode init = find(node, "init", "()V");
        int returns = 0;
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList button = new InsnList();
            button.add(new VarInsnNode(Opcodes.ALOAD, 0));
            button.add(new VarInsnNode(Opcodes.ALOAD, 0));
            button.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserProfileScreen",
                    "titleButton",
                    "(Lnet/minecraft/client/gui/screens/Screen;)"
                            + "Lnet/minecraft/client/gui/components/Button;",
                    false));
            button.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    owner,
                    "addRenderableWidget",
                    "(Lnet/minecraft/client/gui/components/events/GuiEventListener;)"
                            + "Lnet/minecraft/client/gui/components/events/GuiEventListener;",
                    false));
            button.add(new InsnNode(Opcodes.POP));
            init.instructions.insertBefore(instruction, button);
            returns++;
        }
        if (returns != 1) {
            throw new IllegalStateException("TitleScreen.init return shape changed: " + returns);
        }
        init.maxStack = Math.max(init.maxStack, 3);

        MethodNode tick = find(node, "tick", "()V");
        InsnList firstRun = new InsnList();
        firstRun.add(new VarInsnNode(Opcodes.ALOAD, 0));
        firstRun.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserProfileScreen",
                "titleTick",
                "(Lnet/minecraft/client/gui/screens/Screen;)V",
                false));
        tick.instructions.insert(firstRun);
        tick.maxStack = Math.max(tick.maxStack, 1);
    }

    public static void patchAbstractButtonBrowserFastSprite(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/components/AbstractButton.class");
        boolean extractedGui = node.methods.stream()
                .anyMatch(candidate -> candidate.name.equals("extractDefaultSprite"));
        String graphics = extractedGui
                ? "net/minecraft/client/gui/GuiGraphicsExtractor"
                : "net/minecraft/client/gui/GuiGraphics";
        MethodNode method = find(
                node,
                extractedGui ? "extractDefaultSprite" : "renderDefaultSprite",
                "(L" + graphics + ";)V");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getX",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getY",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getX",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getWidth",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getY",
                "()I",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/components/AbstractButton",
                "getHeight",
                "()I",
                false));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new LdcInsnNode(0xD02A3440));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                graphics,
                "fill",
                "(IIIII)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 6, 2);
        write(node, output);
    }

    public static void patchGuiGraphicsBrowserItemCache(String jar, Path outputRoot)
            throws IOException {
        String legacyEntry = "net/minecraft/client/gui/GuiGraphics.class";
        String currentEntry = "net/minecraft/client/gui/GuiGraphicsExtractor.class";
        String entry;
        try (ZipFile input = new ZipFile(jar)) {
            entry = input.getEntry(currentEntry) != null ? currentEntry : legacyEntry;
        }
        String owner = entry.substring(0, entry.length() - ".class".length());
        ClassNode node = read(jar, entry);
        MethodNode method = find(node, entry.equals(currentEntry) ? "item" : "renderItem",
                "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/world/item/ItemStack;III)V");
        AbstractInsnNode start = null;
        AbstractInsnNode end = null;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (start == null
                    && instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("net/minecraft/client/renderer/item/TrackingItemStackRenderState")) {
                start = instruction;
            }
            if (start != null
                    && instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/renderer/item/ItemModelResolver")
                    && call.name.equals("updateForTopItem")
                    && call.desc.equals("(Lnet/minecraft/client/renderer/item/ItemStackRenderState;"
                            + "Lnet/minecraft/world/item/ItemStack;"
                            + "Lnet/minecraft/world/item/ItemDisplayContext;"
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/world/entity/ItemOwner;I)V")) {
                end = instruction;
                break;
            }
        }
        if (start == null || end == null) {
            throw new IllegalStateException("GuiGraphics browser item cache patch points were not found");
        }

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 3));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 2));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replacement.add(new VarInsnNode(Opcodes.ILOAD, 6));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserGuiItemCache",
                "guiState",
                "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/item/ItemStack;"
                        + "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)"
                        + "Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;",
                false));
        replacement.add(new VarInsnNode(Opcodes.ASTORE, 7));

        method.instructions.insertBefore(start, replacement);
        for (var instruction = start; instruction != null;) {
            var next = instruction.getNext();
            method.instructions.remove(instruction);
            if (instruction == end) {
                break;
            }
            instruction = next;
        }
        replaceGuiItemRenderStateDebugName(method);
        method.maxStack = Math.max(method.maxStack, 6);
        writeComputeFrames(node, outputRoot.resolve(entry));
    }

    private static void replaceGuiItemRenderStateDebugName(MethodNode method) {
        int replacements = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode toStringCall)
                    || !toStringCall.owner.equals("net/minecraft/network/chat/Component")
                    || !toStringCall.name.equals("toString")
                    || !toStringCall.desc.equals("()Ljava/lang/String;")) {
                continue;
            }
            AbstractInsnNode getNameInstruction = previousRealInstruction(toStringCall);
            AbstractInsnNode getItemInstruction = previousRealInstruction(getNameInstruction);
            AbstractInsnNode stackLoadInstruction = previousRealInstruction(getItemInstruction);
            if (!(getNameInstruction instanceof MethodInsnNode getNameCall)
                    || !getNameCall.owner.equals("net/minecraft/world/item/Item")
                    || !getNameCall.name.equals("getName")
                    || !getNameCall.desc.equals("()Lnet/minecraft/network/chat/Component;")
                    || !(getItemInstruction instanceof MethodInsnNode getItemCall)
                    || !getItemCall.owner.equals("net/minecraft/world/item/ItemStack")
                    || !getItemCall.name.equals("getItem")
                    || !getItemCall.desc.equals("()Lnet/minecraft/world/item/Item;")
                    || !(stackLoadInstruction instanceof VarInsnNode stackLoad)
                    || stackLoad.getOpcode() != Opcodes.ALOAD
                    || stackLoad.var != 3) {
                continue;
            }

            method.instructions.insertBefore(stackLoadInstruction, new LdcInsnNode("browser:item"));
            for (var remove = stackLoadInstruction; remove != null;) {
                var next = remove.getNext();
                method.instructions.remove(remove);
                if (remove == toStringCall) {
                    break;
                }
                remove = next;
            }
            replacements++;
            break;
        }
        if (replacements == 1) {
            return;
        }

        int lazyDetailReplacements = 0;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic)
                    || !dynamic.desc.equals("(Lnet/minecraft/world/item/ItemStack;)"
                            + "Lnet/minecraft/CrashReportDetail;")) {
                continue;
            }
            AbstractInsnNode stackLoadInstruction = previousRealInstruction(instruction);
            if (!(stackLoadInstruction instanceof VarInsnNode stackLoad)
                    || stackLoad.getOpcode() != Opcodes.ALOAD
                    || stackLoad.var != 3) {
                throw new IllegalStateException(
                        "GUI item lazy debug detail no longer captures item stack local 3");
            }
            method.instructions.insertBefore(stackLoadInstruction, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserGuiItemCache",
                    "itemDebugDetail",
                    "()Lnet/minecraft/CrashReportDetail;",
                    false));
            method.instructions.remove(stackLoadInstruction);
            AbstractInsnNode next = instruction.getNext();
            method.instructions.remove(instruction);
            instruction = next == null ? null : next.getPrevious();
            lazyDetailReplacements++;
        }
        if (lazyDetailReplacements != 3) {
            throw new IllegalStateException(
                    "Expected one eager or three lazy GUI item debug-name replacements, got "
                            + replacements + " eager and " + lazyDetailReplacements + " lazy");
        }
    }

    /**
     * The 26.2 F3 overlay requests an integrated-server chunk future while
     * building debug text. The browser server lives in a separate Worker,
     * so leave that optional server sample unavailable. Keep the ordinary
     * client-cache lookup for the debug entries that can display its data.
     */
    public static void patchDebugScreenOverlayBrowserNoChunk(
            String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/components/DebugScreenOverlay.class");
        MethodNode method = find(node, "getServerChunk",
                "()Lnet/minecraft/world/level/chunk/LevelChunk;");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 1);
        write(node, output);
    }

    /**
     * Keep a joined remote world visible and interactive while its verified pack reloads: once
     * the reload is done and a level is loaded, the overlay is removed right away, but only when
     * it is still the current overlay, so a rollback overlay installed by onFinish stays up.
     */
    public static void patchLoadingOverlayBrowserForeground(String jar, Path output)
            throws IOException {
        String owner = "net/minecraft/client/gui/screens/LoadingOverlay";
        MethodNode tick;
        ClassNode node = read(jar, owner + ".class");
        tick = find(node, "tick", "()V");
        int completions = 0;
        for (AbstractInsnNode instruction : tick.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.owner.equals(owner)
                    || !field.name.equals("fadeOutStart")
                    || !field.desc.equals("J")) {
                continue;
            }
            LabelNode keepOverlay = new LabelNode();
            InsnList clearForegroundOverlay = new InsnList();
            clearForegroundOverlay.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clearForegroundOverlay.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "minecraft",
                    "Lnet/minecraft/client/Minecraft;"));
            clearForegroundOverlay.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/Minecraft",
                    "level",
                    "Lnet/minecraft/client/multiplayer/ClientLevel;"));
            clearForegroundOverlay.add(new JumpInsnNode(Opcodes.IFNULL, keepOverlay));
            // onFinish may have replaced this overlay (a failed reload installs the rollback
            // reload's LoadingOverlay); only a still-current overlay is cleared.
            clearForegroundOverlay.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clearForegroundOverlay.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "minecraft",
                    "Lnet/minecraft/client/Minecraft;"));
            clearForegroundOverlay.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/Minecraft",
                    "gaius$getOverlay",
                    "()Lnet/minecraft/client/gui/screens/Overlay;",
                    false));
            clearForegroundOverlay.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clearForegroundOverlay.add(new JumpInsnNode(Opcodes.IF_ACMPNE, keepOverlay));
            clearForegroundOverlay.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clearForegroundOverlay.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    owner,
                    "minecraft",
                    "Lnet/minecraft/client/Minecraft;"));
            clearForegroundOverlay.add(new InsnNode(Opcodes.ACONST_NULL));
            clearForegroundOverlay.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/Minecraft",
                    "gaius$setOverlay",
                    "(Lnet/minecraft/client/gui/screens/Overlay;)V",
                    false));
            clearForegroundOverlay.add(keepOverlay);
            tick.instructions.insert(instruction, clearForegroundOverlay);
            completions++;
        }
        if (completions != 1) {
            throw new IllegalStateException(
                    "LoadingOverlay foreground completion point changed: " + completions);
        }
        // Publish the reload's actual progress every frame so the launcher boot screen can
        // advance during the initial resource reload instead of holding one value.
        MethodNode render = node.methods.stream()
                .filter(method -> (method.name.equals("extractRenderState")
                        || method.name.equals("render")) && method.desc.endsWith("IIF)V"))
                .findFirst()
                .orElse(null);
        boolean hasReloadField = node.fields.stream().anyMatch(field -> field.name.equals("reload")
                && field.desc.equals("Lnet/minecraft/server/packs/resources/ReloadInstance;"));
        if (render == null || !hasReloadField) {
            throw new IllegalStateException("LoadingOverlay boot progress seam is absent");
        }
        InsnList publish = new InsnList();
        publish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        publish.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "reload",
                "Lnet/minecraft/server/packs/resources/ReloadInstance;"));
        publish.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/server/packs/resources/ReloadInstance",
                "getActualProgress",
                "()F",
                true));
        publish.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserBootProgress",
                "reloadProgress",
                "(F)V",
                false));
        render.instructions.insert(publish);
        writeComputeFrames(node, output);
    }

    /**
     * Drops the pure animation that follows a finished resource reload. The overlay keeps
     * covering the reload exactly as vanilla does: {@code tick} still waits for
     * {@code ReloadInstance.isDone()}, still runs {@code checkExceptions} and hands the result
     * (success or the failure) to {@code onFinish}. Only the timestamp it then stores is moved
     * back by the two fade-out phases (logo and background, {@code FADE_OUT_TIME} each), so the
     * next frame is the vanilla last frame ({@code fadeOut >= 2}) and removes the overlay.
     * {@code isReadyToFadeOut} returns true: its one-second minimum display after the fade-in of
     * an in-game reload covers no work. The fade-in itself still runs while the reload works.
     */
    public static void patchLoadingOverlayBrowserNoFade(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/gui/screens/LoadingOverlay";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        MethodNode tick = find(node, "tick", "()V");
        boolean waitsForReload = false;
        int completions = 0;
        for (AbstractInsnNode instruction : tick.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/server/packs/resources/ReloadInstance")
                    && call.name.equals("isDone")
                    && call.desc.equals("()Z")) {
                waitsForReload = true;
                continue;
            }
            if (!(instruction instanceof MethodInsnNode clock)
                    || clock.getOpcode() != Opcodes.INVOKESTATIC
                    || !clock.owner.equals("net/minecraft/util/Util")
                    || !clock.name.equals("getMillis")
                    || !clock.desc.equals("()J")
                    || !(nextRealInstruction(clock) instanceof FieldInsnNode store)
                    || store.getOpcode() != Opcodes.PUTFIELD
                    || !store.owner.equals(owner)
                    || !store.name.equals("fadeOutStart")
                    || !store.desc.equals("J")) {
                continue;
            }
            if (!waitsForReload) {
                throw new IllegalStateException("LoadingOverlay fade-out starts before the reload is done");
            }
            InsnList skipFade = new InsnList();
            skipFade.add(new LdcInsnNode(LOADING_OVERLAY_FADE_OUT_MS));
            skipFade.add(new InsnNode(Opcodes.LSUB));
            tick.instructions.insert(clock, skipFade);
            completions++;
        }
        if (completions != 1) {
            throw new IllegalStateException("LoadingOverlay fade-out start changed: " + completions);
        }
        // The overlay must still leave on the vanilla condition fadeOut >= 2, where fadeOut is
        // the time since fadeOutStart in seconds (FADE_OUT_TIME = 1000 ms).
        MethodNode render = node.methods.stream()
                .filter(method -> (method.name.equals("extractRenderState")
                        || method.name.equals("render")) && method.desc.endsWith("IIF)V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("LoadingOverlay render method is absent"));
        boolean secondsDivisor = false;
        boolean removalThreshold = false;
        for (AbstractInsnNode instruction : render.instructions) {
            if (instruction instanceof LdcInsnNode constant && Float.valueOf(1000.0f).equals(constant.cst)) {
                secondsDivisor = true;
            } else if (instruction.getOpcode() == Opcodes.FCONST_2
                    && nextRealInstruction(instruction) != null
                    && nextRealInstruction(instruction).getOpcode() == Opcodes.FCMPL) {
                removalThreshold = true;
            }
        }
        if (!secondsDivisor || !removalThreshold) {
            throw new IllegalStateException("LoadingOverlay fade-out timing changed: seconds="
                    + secondsDivisor + ", threshold=" + removalThreshold);
        }
        MethodNode ready = find(node, "isReadyToFadeOut", "()Z");
        InsnList always = new InsnList();
        always.add(new InsnNode(Opcodes.ICONST_1));
        always.add(new InsnNode(Opcodes.IRETURN));
        replace(ready, always, 1, 1);
        writeComputeFrames(node, output);
    }

    /** Two vanilla fade-out phases of {@code LoadingOverlay.FADE_OUT_TIME} (1000 ms) each. */
    private static final long LOADING_OVERLAY_FADE_OUT_MS = 2000L;

    /**
     * Shows the title screen widgets at once. Vanilla builds the first title screen with
     * {@code fading = true}: widgets, splash and version text then fade in over two seconds
     * (invisible for the first one) after the loading overlay has gone. The constructor now
     * stores {@code false}; everything else about the screen is unchanged.
     */
    public static void patchTitleScreenBrowserNoFadeIn(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/gui/screens/TitleScreen";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        int stores = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode store)
                        || store.getOpcode() != Opcodes.PUTFIELD
                        || !store.owner.equals(owner)
                        || !store.name.equals("fading")
                        || !store.desc.equals("Z")) {
                    continue;
                }
                AbstractInsnNode value = previousRealInstruction(store);
                if (!(value instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD) {
                    throw new IllegalStateException("TitleScreen fading store changed");
                }
                method.instructions.set(load, new InsnNode(Opcodes.ICONST_0));
                stores++;
            }
        }
        if (stores != 1) {
            throw new IllegalStateException("TitleScreen fading constructor store changed: " + stores);
        }
        write(node, output);
    }

    /**
     * Removes Minecraft Realms from the title screen. Realms needs a signed-in Microsoft account,
     * which the browser client does not have, so the title screen neither shows the "Minecraft
     * Realms" button nor creates the RealmsNotificationsScreen whose constructor starts
     * {@code RealmsAvailability.get()} (the pc.realms.minecraft.net compatibility check) and whose
     * data subscription polls the Realms notifications and news. The menu column keeps vanilla's
     * 24 pixel rows: createNormalMenuOptions returns the Multiplayer row, so the icon row and
     * Options/Quit move up by one row instead of leaving a gap.
     */
    public static void patchTitleScreenBrowserNoRealms(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/gui/screens/TitleScreen";
        String notifications = "com/mojang/realmsclient/gui/screens/RealmsNotificationsScreen";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");

        MethodNode options = find(node, "createNormalMenuOptions", "(II)I");
        AbstractInsnNode label = null;
        for (AbstractInsnNode instruction : options.instructions.toArray()) {
            if (instruction instanceof LdcInsnNode literal && "menu.online".equals(literal.cst)) {
                if (label != null) {
                    throw new IllegalStateException("TitleScreen has more than one menu.online button");
                }
                label = instruction;
            }
        }
        if (label == null) {
            throw new IllegalStateException("TitleScreen Realms button (menu.online) was not found");
        }
        AbstractInsnNode first = previousOpcode(label);
        AbstractInsnNode last = null;
        for (AbstractInsnNode instruction = label.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode || instruction.getType() == AbstractInsnNode.FRAME) {
                break;
            }
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals("net/minecraft/client/gui/components/Button")
                    && field.name.equals("active") && field.desc.equals("Z")) {
                last = instruction;
                break;
            }
        }
        AbstractInsnNode after = last == null ? null : nextOpcode(last);
        if (!(first instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                || !(after instanceof VarInsnNode result) || result.getOpcode() != Opcodes.ILOAD
                || result.var != 1 || nextOpcode(after).getOpcode() != Opcodes.IRETURN) {
            throw new IllegalStateException("TitleScreen Realms button shape changed");
        }
        AbstractInsnNode cursor = first;
        while (cursor != after) {
            AbstractInsnNode next = cursor.getNext();
            if (cursor.getOpcode() >= 0) {
                options.instructions.remove(cursor);
            }
            cursor = next;
        }

        MethodNode init = find(node, "init", "()V");
        int creations = 0;
        for (AbstractInsnNode instruction : init.instructions.toArray()) {
            if (!(instruction instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
                    || !type.desc.equals(notifications)) {
                continue;
            }
            AbstractInsnNode dup = nextOpcode(type);
            AbstractInsnNode constructor = dup == null ? null : nextOpcode(dup);
            AbstractInsnNode store = constructor == null ? null : nextOpcode(constructor);
            if (dup == null || dup.getOpcode() != Opcodes.DUP
                    || !(constructor instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals(notifications) || !call.name.equals("<init>") || !call.desc.equals("()V")
                    || !(store instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.owner.equals(owner) || !field.name.equals("realmsNotificationsScreen")) {
                throw new IllegalStateException("TitleScreen RealmsNotificationsScreen creation shape changed");
            }
            init.instructions.remove(type);
            init.instructions.remove(dup);
            init.instructions.set(constructor, new InsnNode(Opcodes.ACONST_NULL));
            creations++;
        }
        if (creations != 1) {
            throw new IllegalStateException("TitleScreen RealmsNotificationsScreen creations: " + creations);
        }
        write(node, output);
    }

    /**
     * Rewrites the title screen's lower-left version text through the build-aware
     * dev.gaius.browser.BrowserAboutScreen helper. The text keeps vanilla's font, colour and
     * position: only the drawn string passes through {@code BrowserAboutScreen.titleVersion}.
     */
    public static void patchTitleScreenBrowserAboutLink(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/gui/screens/TitleScreen";
        String about = "dev/gaius/browser/BrowserAboutScreen";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        boolean extractedGui = node.methods.stream().anyMatch(method -> method.name.equals("extractRenderState"));
        MethodNode render = extractedGui
                ? find(node, "extractRenderState", "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V")
                : find(node, "render", "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V");
        String textDescriptor = "(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V";
        int versionLocal = -1;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/WorldVersion")
                    && call.name.equals("name")) {
                AbstractInsnNode concat = nextOpcode(call);
                AbstractInsnNode store = concat == null ? null : nextOpcode(concat);
                if (!(concat instanceof InvokeDynamicInsnNode) || !(store instanceof VarInsnNode local)
                        || local.getOpcode() != Opcodes.ASTORE || versionLocal >= 0) {
                    throw new IllegalStateException("TitleScreen version text shape changed");
                }
                versionLocal = local.var;
            }
        }
        int texts = 0;
        for (AbstractInsnNode instruction : render.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call) || !call.desc.equals(textDescriptor)
                    || !(call.name.equals("text") || call.name.equals("drawString"))) {
                continue;
            }
            AbstractInsnNode cursor = previousOpcode(call);
            for (int i = 0; i < 8 && cursor != null; i++) {
                if (cursor instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                        && load.var == versionLocal) {
                    render.instructions.insert(load, new MethodInsnNode(Opcodes.INVOKESTATIC, about,
                            "titleVersion", "(Ljava/lang/String;)Ljava/lang/String;", false));
                    texts++;
                    break;
                }
                cursor = previousOpcode(cursor);
            }
        }
        if (versionLocal < 0 || texts != 1) {
            throw new IllegalStateException("TitleScreen version text draw was not found: local="
                    + versionLocal + " draws=" + texts);
        }

        write(node, output);
    }

    /**
     * Closes the level loading screen when the level is ready. Vanilla keeps it open for a
     * further {@code LEVEL_LOAD_CLOSE_DELAY_MS} (500 ms) after a newly created world is ready;
     * every other join already uses a delay of 0, which the constructor now stores for all.
     */
    public static void patchLevelLoadTrackerBrowserNoCloseDelay(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/multiplayer/LevelLoadTracker";
        Path output = root.resolve(owner + ".class");
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");
        MethodNode constructor = find(node, "<init>", "(J)V");
        int stores = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode store)
                    || store.getOpcode() != Opcodes.PUTFIELD
                    || !store.owner.equals(owner)
                    || !store.name.equals("closeDelayMs")
                    || !store.desc.equals("J")) {
                continue;
            }
            AbstractInsnNode value = previousRealInstruction(store);
            if (!(value instanceof VarInsnNode load) || load.getOpcode() != Opcodes.LLOAD || load.var != 1) {
                throw new IllegalStateException("LevelLoadTracker close delay store changed");
            }
            constructor.instructions.set(load, new InsnNode(Opcodes.LCONST_0));
            stores++;
        }
        if (stores != 1) {
            throw new IllegalStateException("LevelLoadTracker close delay store changed: " + stores);
        }
        write(node, output);
    }

    public static void patchPauseScreenBrowserSingleplayer(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/gui/screens/PauseScreen.class");
        MethodNode createPauseMenu = find(node, "createPauseMenu", "()V");
        if (!replaceLocalServerCheck(createPauseMenu)) {
            throw new IllegalStateException(
                    "PauseScreen singleplayer worker label hook point was not found");
        }
        boolean lanHooked = false;
        for (AbstractInsnNode instruction = createPauseMenu.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/client/Minecraft")
                    || !call.name.equals("hasSingleplayerServer")
                    || !call.desc.equals("()Z")) {
                continue;
            }
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "net/minecraft/client/gui/screens/PauseScreen",
                    "minecraft",
                    "Lnet/minecraft/client/Minecraft;"));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
            hook.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserLanSession",
                    "maybeAddButton",
                    "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;)V",
                    false));
            // Insert after the boolean call: the receiver Minecraft reference is
            // already on the operand stack before invokevirtual and must be consumed.
            createPauseMenu.instructions.insert(instruction, hook);
            lanHooked = true;
            break;
        }
        if (!lanHooked) {
            lanHooked = hookPauseScreenLanBeforeLevelCheck(createPauseMenu);
        }
        if (!lanHooked) {
            throw new IllegalStateException("PauseScreen Open to LAN hook point was not found");
        }
        write(node, output);
    }

    /**
     * 26.3 no longer asks {@code hasSingleplayerServer()} while building the pause menu: the
     * Options/World Options row is guarded by {@code this.minecraft.level != null} instead. Adding
     * the LAN button right before that check keeps the 26.2 order (LAN, then the Options row,
     * then Disconnect) on both branches of the check. The anchor must occur exactly once.
     */
    private static boolean hookPauseScreenLanBeforeLevelCheck(MethodNode createPauseMenu) {
        String screen = "net/minecraft/client/gui/screens/PauseScreen";
        AbstractInsnNode anchor = null;
        int anchors = 0;
        for (AbstractInsnNode instruction = createPauseMenu.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof VarInsnNode self)
                    || self.getOpcode() != Opcodes.ALOAD
                    || self.var != 0
                    || !(nextOpcode(self) instanceof FieldInsnNode minecraft)
                    || minecraft.getOpcode() != Opcodes.GETFIELD
                    || !minecraft.owner.equals(screen)
                    || !minecraft.name.equals("minecraft")
                    || !minecraft.desc.equals("Lnet/minecraft/client/Minecraft;")
                    || !(nextOpcode(minecraft) instanceof FieldInsnNode level)
                    || level.getOpcode() != Opcodes.GETFIELD
                    || !level.owner.equals("net/minecraft/client/Minecraft")
                    || !level.name.equals("level")
                    || !level.desc.equals("Lnet/minecraft/client/multiplayer/ClientLevel;")
                    || nextOpcode(level) == null
                    || nextOpcode(level).getOpcode() != Opcodes.IFNULL) {
                continue;
            }
            anchor = self;
            anchors++;
        }
        if (anchors != 1) {
            if (anchors > 1) {
                throw new IllegalStateException(
                        "PauseScreen level-check LAN anchor is ambiguous: " + anchors);
            }
            return false;
        }
        // BrowserLanSession.maybeAddButton receives local 2; it must be the menu's RowHelper.
        boolean rowHelperInLocal2 = false;
        for (AbstractInsnNode instruction = createPauseMenu.instructions.getFirst();
                instruction != anchor;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/gui/layouts/GridLayout")
                    && call.name.equals("createRowHelper")
                    && call.desc.equals("(I)Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;")
                    && nextOpcode(call) instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE) {
                rowHelperInLocal2 = store.var == 2;
                break;
            }
        }
        if (!rowHelperInLocal2) {
            throw new IllegalStateException(
                    "PauseScreen RowHelper is no longer stored in local 2 before the level check");
        }
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new FieldInsnNode(
                Opcodes.GETFIELD, screen, "minecraft", "Lnet/minecraft/client/Minecraft;"));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserLanSession",
                "maybeAddButton",
                "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;)V",
                false));
        // Before the ALOAD 0 of the check (and after any label that precedes it), so both
        // the fall-through path and any jump to the check run the hook.
        createPauseMenu.instructions.insertBefore(anchor, hook);
        createPauseMenu.maxStack = Math.max(createPauseMenu.maxStack, 2);
        return true;
    }

    /** Avoids scanning every sparse bitmap provider for each fallback glyph. */
    public static void patchFontProviderSelectionIndex(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/gui/font/FontSet";
        String helper = "dev/gaius/browser/BrowserFontProviderIndex";
        ClassNode node = read(jar, owner + ".class");
        MethodNode select = find(node, "selectProviders", "(Ljava/util/List;Ljava/util/Set;)Ljava/util/List;");
        if (select == null) throw new IOException("FontSet selectProviders not found");
        int wraps = 0, iterators = 0;
        for (AbstractInsnNode instruction : select.instructions.toArray()) {
            if (instruction instanceof InvokeDynamicInsnNode dynamic
                    && dynamic.desc.equals("(L" + owner + ";Ljava/util/List;Ljava/util/Set;)Lit/unimi/dsi/fastutil/ints/IntConsumer;")) {
                // The operands already on the stack are (FontSet, providers, usedSet).
                // Temporarily save usedSet and wrap only the captured providers list.
                int used = select.maxLocals++;
                InsnList wrap = new InsnList();
                wrap.add(new VarInsnNode(Opcodes.ASTORE, used));
                wrap.add(new MethodInsnNode(Opcodes.INVOKESTATIC, helper, "wrap",
                        "(Ljava/util/List;)Ljava/util/List;", false));
                wrap.add(new VarInsnNode(Opcodes.ALOAD, used));
                select.instructions.insertBefore(dynamic, wrap);
                wraps++;
            }
        }
        for (MethodNode method : node.methods) {
            if (!method.name.startsWith("lambda$selectProviders$")
                    || !method.desc.equals("(Ljava/util/List;Ljava/util/Set;I)V")
                    || (method.access & Opcodes.ACC_STATIC) != 0) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("java/util/List")
                        && call.name.equals("iterator") && call.desc.equals("()Ljava/util/Iterator;")) {
                    method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ILOAD, 3));
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = helper;
                    call.name = "iterator";
                    call.desc = "(Ljava/util/List;I)Ljava/util/Iterator;";
                    call.itf = false;
                    iterators++;
                }
            }
        }
        if (wraps != 1 || iterators != 1) {
            throw new IOException("Expected one font provider capture/iterator, found " + wraps + "/" + iterators);
        }
        writeComputeFrames(node, root.resolve(owner + ".class"));
    }

    public static void patchBitmapFontImageSharing(String jar, Path root) throws IOException {
        String base = "net/minecraft/client/gui/font/providers/BitmapProvider";
        String image = "com/mojang/blaze3d/platform/NativeImage";
        String cache = "dev/gaius/browser/BrowserFontBitmapCache";
        ClassNode definition = read(jar, base + "$Definition.class");
        MethodNode load = find(definition, "load",
                "(Lnet/minecraft/server/packs/resources/ResourceManager;)Lcom/mojang/blaze3d/font/GlyphProvider;");
        if (load == null) throw new IOException("Bitmap definition load not found");
        int acquired = load.maxLocals++;
        int thrown = load.maxLocals++;
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        InsnList initialize = new InsnList();
        initialize.add(new InsnNode(Opcodes.ACONST_NULL));
        initialize.add(new VarInsnNode(Opcodes.ASTORE, acquired));
        initialize.add(start);
        load.instructions.insert(initialize);
        int reads = 0;
        boolean hasHolder = false;
        for (AbstractInsnNode instruction : load.instructions.toArray()) {
            // 26.2 reads NativeImage.read(Format.RGBA, stream); 26.3 removed that overload and
            // calls NativeImage.read(stream). Each shape goes to the matching cache entry point.
            boolean formatRead = instruction instanceof MethodInsnNode formatCall
                    && formatCall.getOpcode() == Opcodes.INVOKESTATIC
                    && formatCall.owner.equals(image) && formatCall.name.equals("read")
                    && formatCall.desc.equals("(Lcom/mojang/blaze3d/platform/NativeImage$Format;"
                            + "Ljava/io/InputStream;)L" + image + ";");
            boolean streamRead = instruction instanceof MethodInsnNode streamCall
                    && streamCall.getOpcode() == Opcodes.INVOKESTATIC
                    && streamCall.owner.equals(image) && streamCall.name.equals("read")
                    && streamCall.desc.equals("(Ljava/io/InputStream;)L" + image + ";");
            if (formatRead || streamRead) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                InsnList args = new InsnList();
                args.add(new VarInsnNode(Opcodes.ALOAD, 1));
                args.add(new VarInsnNode(Opcodes.ALOAD, 2));
                load.instructions.insertBefore(call, args);
                call.owner = cache;
                call.desc = formatRead
                        ? "(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/io/InputStream;"
                                + "Ljava/lang/Object;Ljava/lang/Object;)L" + image + ";"
                        : "(Ljava/io/InputStream;Ljava/lang/Object;Ljava/lang/Object;)L" + image + ";";
                InsnList save = new InsnList();
                save.add(new InsnNode(Opcodes.DUP));
                save.add(new VarInsnNode(Opcodes.ASTORE, acquired));
                load.instructions.insert(call, save);
                reads++;
            }
            if (instruction instanceof MethodInsnNode call && call.owner.equals(base + "$ImageDataHolder")
                    && call.name.equals("<init>")) {
                InsnList share = new InsnList();
                share.add(new VarInsnNode(Opcodes.ALOAD, acquired));
                share.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cache, "shareHolder",
                        "(Ljava/lang/Object;L" + image + ";)Ljava/lang/Object;", false));
                share.add(new TypeInsnNode(Opcodes.CHECKCAST, base + "$ImageDataHolder"));
                load.instructions.insert(call, share);
                hasHolder = true;
            }
        }
        if (reads != 1) throw new IOException("Expected one bitmap NativeImage.read, found " + reads);
        load.instructions.add(end);
        load.instructions.add(handler);
        load.instructions.add(new VarInsnNode(Opcodes.ASTORE, thrown));
        load.instructions.add(new VarInsnNode(Opcodes.ALOAD, acquired));
        load.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cache, "release",
                "(L" + image + ";)V", false));
        load.instructions.add(new VarInsnNode(Opcodes.ALOAD, thrown));
        load.instructions.add(new InsnNode(Opcodes.ATHROW));
        load.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        writeComputeFrames(definition, root.resolve(base + "$Definition.class"));

        ClassNode provider = read(jar, base + ".class");
        provider.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "gaiusBitmapClosed", "Z", null, null));
        MethodNode close = find(provider, "close", "()V");
        LabelNode firstClose = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new FieldInsnNode(Opcodes.GETFIELD, base, "gaiusBitmapClosed", "Z"));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, firstClose));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(firstClose);
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new InsnNode(Opcodes.ICONST_1));
        guard.add(new FieldInsnNode(Opcodes.PUTFIELD, base, "gaiusBitmapClosed", "Z"));
        AbstractInsnNode guardEnd = guard.getLast();
        close.instructions.insert(guard);
        ClassNode owner = hasHolder ? read(jar, base + "$ImageDataHolder.class") : provider;
        MethodNode ownerClose = find(owner, "close", "()V");
        LabelNode finalOwner = new LabelNode();
        InsnList release = new InsnList();
        release.add(new VarInsnNode(Opcodes.ALOAD, 0));
        release.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, "image", "L" + image + ";"));
        release.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cache, "releaseOwnership", "(L" + image + ";)Z", false));
        release.add(new JumpInsnNode(Opcodes.IFNE, finalOwner));
        release.add(new InsnNode(Opcodes.RETURN));
        release.add(finalOwner);
        if (hasHolder) ownerClose.instructions.insert(release);
        else close.instructions.insert(guardEnd, release);
        writeComputeFrames(provider, root.resolve(base + ".class"));
        if (hasHolder) writeComputeFrames(owner, root.resolve(base + "$ImageDataHolder.class"));
    }

    public static void patchUnihexProviderBrowserBulkParser(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/font/providers/UnihexProvider$Definition.class");
        MethodNode loadData = find(node, "loadData",
                "(Ljava/io/InputStream;)Lnet/minecraft/client/gui/font/providers/UnihexProvider;");
        if (loadData == null) {
            throw new IOException("UnihexProvider bulk parser patch point was not found");
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/gui/font/providers/UnihexProvider$Definition",
                "sizeOverrides",
                "Ljava/util/List;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/client/gui/font/providers/BrowserUnihexLoader",
                "load",
                "(Ljava/io/InputStream;Ljava/util/List;)"
                        + "Lnet/minecraft/client/gui/font/providers/UnihexProvider;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(loadData, code, 2, 2);

        addUnihexOverrideBridge(node, "browserOverrideFrom", "from", "()I");
        addUnihexOverrideBridge(node, "browserOverrideTo", "to", "()I");
        MethodNode dimensions = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "browserOverrideDimensions",
                "(Ljava/lang/Object;)I",
                null,
                null);
        dimensions.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dimensions.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/gui/font/providers/UnihexProvider$OverrideRange"));
        dimensions.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/font/providers/UnihexProvider$OverrideRange",
                "dimensions",
                "()Lnet/minecraft/client/gui/font/providers/UnihexProvider$Dimensions;",
                false));
        dimensions.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/font/providers/UnihexProvider$Dimensions",
                "pack",
                "()I",
                false));
        dimensions.instructions.add(new InsnNode(Opcodes.IRETURN));
        dimensions.maxStack = 1;
        dimensions.maxLocals = 1;
        node.methods.add(dimensions);
        writeComputeFrames(node, output);
    }

    public static void patchUnihexProviderBrowserAccess(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/font/providers/UnihexProvider.class");
        MethodNode constructor = find(
                node,
                "<init>",
                "(Lnet/minecraft/client/gui/font/CodepointMap;)V");
        MethodNode unpack = find(
                node,
                "unpackBitsToBytes",
                "(Ljava/nio/IntBuffer;"
                        + "Lnet/minecraft/client/gui/font/providers/UnihexProvider$LineData;II)V");
        if (constructor == null || unpack == null) {
            throw new IOException("UnihexProvider browser access points were not found");
        }
        constructor.access = (constructor.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PUBLIC;
        unpack.access = (unpack.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PUBLIC;
        write(node, output);
    }

    private static void addUnihexOverrideBridge(
            ClassNode node, String bridgeName, String accessorName, String accessorDescriptor) {
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                bridgeName,
                "(Ljava/lang/Object;)I",
                null,
                null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "net/minecraft/client/gui/font/providers/UnihexProvider$OverrideRange"));
        bridge.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/gui/font/providers/UnihexProvider$OverrideRange",
                accessorName,
                accessorDescriptor,
                false));
        bridge.instructions.add(new InsnNode(Opcodes.IRETURN));
        bridge.maxStack = 1;
        bridge.maxLocals = 1;
        node.methods.add(bridge);
    }

    public static void patchFreeTypeUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/font/providers/FreeTypeUtil.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("destroy") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 0);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("FreeTypeUtil.destroy was not found");
        }
        write(node, output);
    }

    public static void patchPackWatcher(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/screens/packs/PackSelectionScreen$Watcher.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("create")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, method.maxLocals);
            } else if (method.name.equals("pollForChanges")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, method.maxLocals);
            } else if (method.name.equals("close") || method.name.equals("watchDir")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    /** Adds the browser file picker while preserving vanilla pack discovery and reload paths. */
    public static void patchPackSelectionScreenBrowserImport(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/screens/packs/PackSelectionScreen.class");
        MethodNode init = find(node, "init", "()V");
        MethodNode tick = find(node, "tick", "()V");
        MethodNode close = find(node, "onClose", "()V");
        insertBeforeReturns(init, "install", "(Lnet/minecraft/client/gui/screens/packs/PackSelectionScreen;)V");
        insertPackButton(init);
        insertBeforeReturns(tick, "tick", "(Lnet/minecraft/client/gui/screens/packs/PackSelectionScreen;)V");
        insertBeforeReturns(close, "close", "(Lnet/minecraft/client/gui/screens/packs/PackSelectionScreen;)V");
        writeComputeFrames(node, output);
    }

    private static void insertPackButton(MethodNode method) {
        int inserted = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            LabelNode noButton = new LabelNode();
            LabelNode buttonDone = new LabelNode();
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserUserResourcePacks",
                    "createButton",
                    "(Lnet/minecraft/client/gui/screens/packs/PackSelectionScreen;)"
                            + "Lnet/minecraft/client/gui/components/Button;",
                    false));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new JumpInsnNode(Opcodes.IFNULL, noButton));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new InsnNode(Opcodes.SWAP));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/client/gui/screens/Screen",
                    "addRenderableWidget",
                    "(Lnet/minecraft/client/gui/components/events/GuiEventListener;)"
                            + "Lnet/minecraft/client/gui/components/events/GuiEventListener;",
                    false));
            code.add(new InsnNode(Opcodes.POP));
            code.add(new JumpInsnNode(Opcodes.GOTO, buttonDone));
            code.add(noButton);
            code.add(new InsnNode(Opcodes.POP));
            code.add(buttonDone);
            method.instructions.insertBefore(instruction, code);
            inserted++;
        }
        if (inserted == 0) {
            throw new IllegalStateException("Pack selection Add Pack button hook was not found");
        }
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static void insertBeforeReturns(MethodNode method, String name, String descriptor) {
        int inserted = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserUserResourcePacks",
                    name,
                    descriptor,
                    false));
            method.instructions.insertBefore(instruction, call);
            inserted++;
        }
        if (inserted == 0) {
            throw new IllegalStateException("Pack selection " + name + " hook was not found");
        }
        method.maxStack = Math.max(method.maxStack, 1);
    }

    /**
     * World export and import on the vanilla world selection screen (BrowserWorldTransfer).
     * The second footer row (Edit, Delete, Re-Create, Back) becomes one horizontal row that also
     * holds Export before Back, as wide as the first row; Create New World opens the "What would
     * you like to do?" choice (Create New World, Import World) before vanilla's create screen;
     * updateButtonStatus also enables Export for the selected world. 1.21.11, 26.2 and 26.3 share
     * this shape: four one-cell RowHelper.addChild calls in createFooterButtons (the last one adds
     * Back), one CreateWorldScreen.openFresh(Minecraft, Runnable) call and
     * updateButtonStatus(LevelSummary).
     */
    public static void patchSelectWorldScreenWorldTransfer(String jar, Path output) throws IOException {
        String owner = "net/minecraft/client/gui/screens/worldselection/SelectWorldScreen";
        String helper = "dev/gaius/browser/BrowserWorldTransfer";
        String rowHelper = "net/minecraft/client/gui/layouts/GridLayout$RowHelper";
        String layoutElement = "Lnet/minecraft/client/gui/layouts/LayoutElement;";
        ClassNode node = Files.exists(output) ? read(output) : read(jar, owner + ".class");

        MethodNode footer = find(node, "createFooterButtons",
                "(Ljava/util/function/Consumer;"
                        + "Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList;)V");
        java.util.List<MethodInsnNode> cells = new java.util.ArrayList<>();
        int backIndex = -1;
        for (AbstractInsnNode instruction : footer.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals("net/minecraft/network/chat/CommonComponents")
                    && field.name.equals("GUI_BACK")) {
                backIndex = cells.size();
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(rowHelper)
                    && call.name.equals("addChild")
                    && call.desc.equals("(" + layoutElement + ")" + layoutElement)) {
                cells.add(call);
            }
        }
        if (cells.size() != 4 || backIndex != 3) {
            throw new IllegalStateException("SelectWorldScreen footer shape changed: cells="
                    + cells.size() + " backIndex=" + backIndex);
        }
        for (int index = 0; index < 3; index++) {
            footer.instructions.set(cells.get(index), new MethodInsnNode(Opcodes.INVOKESTATIC,
                    helper, "footerButton",
                    "(L" + rowHelper + ";" + layoutElement + ")" + layoutElement, false));
        }
        InsnList backArguments = new InsnList();
        backArguments.add(new VarInsnNode(Opcodes.ALOAD, 0));
        backArguments.add(new VarInsnNode(Opcodes.ALOAD, 2));
        footer.instructions.insertBefore(cells.get(3), backArguments);
        footer.instructions.set(cells.get(3), new MethodInsnNode(Opcodes.INVOKESTATIC,
                helper, "footerBackButton",
                "(L" + rowHelper + ";" + layoutElement
                        + "Lnet/minecraft/client/gui/screens/Screen;"
                        + "Lnet/minecraft/client/gui/screens/worldselection/WorldSelectionList;)"
                        + layoutElement, false));
        footer.maxStack = footer.maxStack + 2;

        int creates = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("net/minecraft/client/gui/screens/worldselection/CreateWorldScreen")
                        && call.name.equals("openFresh")
                        && call.desc.equals("(Lnet/minecraft/client/Minecraft;Ljava/lang/Runnable;)V")) {
                    call.owner = helper;
                    call.name = "openCreateChoice";
                    creates++;
                }
            }
        }
        if (creates != 1) {
            throw new IllegalStateException("SelectWorldScreen create-world call shape changed: " + creates);
        }

        MethodNode status = find(node, "updateButtonStatus",
                "(Lnet/minecraft/world/level/storage/LevelSummary;)V");
        InsnList export = new InsnList();
        export.add(new VarInsnNode(Opcodes.ALOAD, 1));
        export.add(new MethodInsnNode(Opcodes.INVOKESTATIC, helper, "updateExportButton",
                "(Lnet/minecraft/world/level/storage/LevelSummary;)V", false));
        status.instructions.insert(export);
        status.maxStack = Math.max(status.maxStack, 1);

        writeComputeFrames(node, output);
        System.out.println("Added world export and import to SelectWorldScreen");
    }
}
