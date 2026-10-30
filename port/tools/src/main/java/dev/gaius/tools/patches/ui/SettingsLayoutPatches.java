package dev.gaius.tools.patches.ui;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;

import dev.gaius.tools.VersionProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The browser settings layout, the ui domain of the MinecraftClientPatcher step
 * ({@code patches/ui}): the settings screens leave out the options and buttons that have no
 * effect in a browser (each patch below names what it leaves out and why), and the main Options
 * screen shows what remains of the Online page in place of its button.
 *
 * <p>Only the screens change. Every option stays in {@code Options} with its options.txt key, so
 * a saved options.txt reads and writes exactly as before, and the vanilla controls (CycleButton,
 * sliders, OptionsList rows) lay out what is left. Each patch is a FEATURE: when one fails its
 * screen keeps the vanilla layout.
 *
 * <p>Option arrays: an option-array builder of a screen ({@code displayOptions},
 * {@code options}) loads {@code null} in place of a left-out option, and its result passes through
 * {@code BrowserOptionsLayout.compact} before it returns, so {@code OptionsList.addSmall} pairs
 * the remaining options without a gap. A widget that a screen builds and adds by itself is not
 * added (its {@code OptionsList} or layout call pops its arguments instead). Several patches can
 * rewrite one class: each reads the class this step already wrote, if any.
 */
public final class SettingsLayoutPatches {
    static final String OPTIONS = "net/minecraft/client/Options";
    static final String OPTION_INSTANCE = "net/minecraft/client/OptionInstance";
    static final String OPTION_INSTANCE_DESC = "L" + OPTION_INSTANCE + ";";
    static final String OPTION_ARRAY = "[" + OPTION_INSTANCE_DESC;
    static final String OPTIONS_LIST = "net/minecraft/client/gui/components/OptionsList";
    static final String ABSTRACT_WIDGET = "Lnet/minecraft/client/gui/components/AbstractWidget;";
    static final String COMPONENT = "Lnet/minecraft/network/chat/Component;";
    static final String LAYOUT_ELEMENT = "Lnet/minecraft/client/gui/layouts/LayoutElement;";
    static final String ROW_HELPER = "net/minecraft/client/gui/layouts/GridLayout$RowHelper";
    static final String LINEAR_LAYOUT = "net/minecraft/client/gui/layouts/LinearLayout";
    static final String SCREENS = "net/minecraft/client/gui/screens/options/";
    static final String OPTIONS_SCREEN = SCREENS + "OptionsScreen";
    static final String ONLINE_SCREEN = SCREENS + "OnlineOptionsScreen";
    static final String VIDEO_SCREEN = SCREENS + "VideoSettingsScreen";
    static final String SOUND_SCREEN = SCREENS + "SoundOptionsScreen";
    static final String MOUSE_SCREEN = SCREENS + "MouseSettingsScreen";
    static final String ACCESSIBILITY_SCREEN = SCREENS + "AccessibilityOptionsScreen";
    static final String CHAT_SCREEN = SCREENS + "ChatOptionsScreen";
    static final String CONTROLS_SCREEN = SCREENS + "controls/ControlsScreen";
    static final String PACK_SCREEN = "net/minecraft/client/gui/screens/packs/PackSelectionScreen";
    static final String LAYOUT_HELPER = "dev/gaius/browser/BrowserOptionsLayout";

    private SettingsLayoutPatches() {
    }

    // ---------------------------------------------------------------------------------------
    // Options screen
    // ---------------------------------------------------------------------------------------

    /**
     * Options: the Telemetry Data button is not added (its page explains and links Mojang's Java
     * Edition data collection settings; the offline browser session already greys it out, and
     * the browser client offers no telemetry choice), and Credits &amp; Attribution, now alone
     * on the last grid row, spans both columns so it stays centred.
     */
    public static void patchOptionsScreenBrowserLayout(String jar, Path root) throws IOException {
        ClassNode node = readCurrent(jar, root, OPTIONS_SCREEN);
        MethodNode init = find(node, "init", "()V");
        MethodInsnNode telemetry = rowAddAfter(init, "TELEMETRY");
        InsnList skip = new InsnList();
        // RowHelper.addChild(T) returns its argument: keep the button, drop the row helper.
        skip.add(new InsnNode(Opcodes.SWAP));
        skip.add(new InsnNode(Opcodes.POP));
        init.instructions.insert(telemetry, skip);
        init.instructions.remove(telemetry);

        MethodInsnNode credits = rowAddAfter(init, "CREDITS_AND_ATTRIBUTION");
        init.instructions.insertBefore(credits, new InsnNode(Opcodes.ICONST_2));
        credits.desc = "(" + LAYOUT_ELEMENT + "I)" + LAYOUT_ELEMENT;
        writeCurrent(node, root);
    }

    /**
     * Options (26.2 and later): the Online button opens a page that holds one browser setting
     * after the Realms and friends list options are gone (see
     * {@link #patchOnlineOptionsScreenBrowserLayout}), so the header row shows that setting,
     * Allow Server Listings, next to the FOV slider instead of the button.
     */
    public static void patchOptionsScreenBrowserServerListing(String jar, Path root)
            throws IOException {
        ClassNode node = readCurrent(jar, root, OPTIONS_SCREEN);
        requireField(node, "options", "L" + OPTIONS + ";");
        MethodNode init = find(node, "init", "()V");
        List<MethodInsnNode> calls = calls(init, Opcodes.INVOKEVIRTUAL, OPTIONS_SCREEN,
                "createOnlineButton", "()" + LAYOUT_ELEMENT);
        MethodInsnNode online = single(calls, "OptionsScreen.init createOnlineButton call");
        InsnList toggle = new InsnList();
        // this -> this.options.allowServerListing().createButton(this.options)
        toggle.add(new InsnNode(Opcodes.DUP));
        toggle.add(new FieldInsnNode(Opcodes.GETFIELD, OPTIONS_SCREEN, "options", "L" + OPTIONS + ";"));
        toggle.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OPTIONS, "allowServerListing",
                "()" + OPTION_INSTANCE_DESC, false));
        toggle.add(new InsnNode(Opcodes.SWAP));
        toggle.add(new FieldInsnNode(Opcodes.GETFIELD, OPTIONS_SCREEN, "options", "L" + OPTIONS + ";"));
        toggle.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OPTION_INSTANCE, "createButton",
                "(L" + OPTIONS + ";)" + ABSTRACT_WIDGET, false));
        init.instructions.insert(online, toggle);
        init.instructions.remove(online);
        writeCurrent(node, root);
    }

    /**
     * Online Options: no Realms section (Realms is not offered in the browser; the title screen
     * already reports its notifications off) and, where the page has one (26.2 and later), no
     * friends list section (friends list, friend requests, in-game notifications, presence and
     * the Xbox settings link need the Microsoft friends service, which the browser session does
     * not reach). Allow Server Listings stays, and on 1.21.11 the in-world difficulty line.
     */
    public static void patchOnlineOptionsScreenBrowserLayout(String jar, Path root)
            throws IOException {
        ClassNode node = readCurrent(jar, root, ONLINE_SCREEN);
        MethodNode builder = findNullable(node, "options",
                "(L" + OPTIONS + ";Lnet/minecraft/client/Minecraft;)" + OPTION_ARRAY);
        int removed = 0;
        if (builder != null) {
            // 1.21.11: options(options, minecraft) collects the page into a list.
            removed += nullGetter(builder, "realmsNotifications", true);
            compactResult(builder);
        } else {
            MethodNode add = find(node, "addOptions", "()V");
            for (AbstractInsnNode instruction : add.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals(OPTIONS_LIST)) {
                    continue;
                }
                boolean drop = false;
                if (call.name.equals("addHeader") && call.desc.equals("(" + COMPONENT + ")V")) {
                    String header = staticFieldBefore(call);
                    drop = "FRIENDS_HEADER".equals(header) || "REALMS_HEADER".equals(header);
                } else if (call.name.equals("addSmall")
                        && call.desc.equals("(" + ABSTRACT_WIDGET + ABSTRACT_WIDGET + ")V")) {
                    drop = true;  // friends list + friend requests, notifications + presence
                } else if (call.name.equals("addBig") && call.desc.equals("(" + ABSTRACT_WIDGET + ")V")) {
                    drop = true;  // Xbox Settings...
                } else if (call.name.equals("addBig")
                        && call.desc.equals("(" + OPTION_INSTANCE_DESC + ")V")) {
                    drop = isOptionGetter(previousOpcode(call), "realmsNotifications");
                }
                if (drop) {
                    popArguments(add, call);
                    removed++;
                }
            }
            if (removed < 2) {
                throw new IllegalStateException(
                        "OnlineOptionsScreen.addOptions Realms rows were not found: " + removed);
            }
        }
        writeCurrent(node, root);
        System.out.println("OnlineOptionsScreen browser layout: removed=" + removed);
    }

    // ---------------------------------------------------------------------------------------
    // Video Settings
    // ---------------------------------------------------------------------------------------

    /**
     * Video Settings: no fullscreen resolution slider (the browser reports one video mode, the
     * screen's, and 26.2+ labels it Exclusive Fullscreen Mode), no Exclusive Fullscreen (a
     * no-op: a page only has the Fullscreen API), no Preferred Graphics API (26.2+: the page picks
     * WebGPU or WebGL 2 from {@code ?gpu=} and the device, the option is never read) and no
     * Inactivity FPS Limit (the browser throttles a hidden tab itself and Gaius keeps the
     * vanilla limiter tracker at NONE). The macOS Menu/Dock option is already hidden
     * ({@code MacosUtil.IS_MACOS} is false in the browser).
     */
    public static void patchVideoSettingsScreenBrowserLayout(String jar, Path root)
            throws IOException {
        ClassNode node = readCurrent(jar, root, VIDEO_SCREEN);
        MethodNode add = find(node, "addOptions", "()V");
        MethodInsnNode resolution = null;
        int resolutions = 0;
        for (MethodInsnNode call : calls(add, Opcodes.INVOKEVIRTUAL, OPTIONS_LIST, "addBig",
                "(" + OPTION_INSTANCE_DESC + ")V")) {
            // The resolution slider is the one OptionInstance the method builds itself.
            if (previousOpcode(call) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
                resolution = call;
                resolutions++;
            }
        }
        if (resolutions != 1) {
            throw new IllegalStateException(
                    "VideoSettingsScreen.addOptions fullscreen resolution row: " + resolutions);
        }
        popArguments(add, resolution);

        MethodNode display = find(node, "displayOptions", "(L" + OPTIONS + ";)" + OPTION_ARRAY);
        int removed = nullGetter(display, "inactivityFpsLimit", true);
        removed += nullGetter(display, "exclusiveFullscreen", false);
        removed += nullGetter(display, "preferredGraphicsBackend", false);
        compactResult(display);
        writeCurrent(node, root);
        System.out.println("VideoSettingsScreen browser layout: displayOptions removed=" + removed);
    }

    /**
     * Video Settings (the GLFW profiles, 26.2 and 1.21.11): no Fullscreen toggle. The GLFW
     * bridge has no monitor ({@code glfwGetWindowMonitor} is 0) and only resizes the canvas, so
     * the switch never leaves or enters browser fullscreen there; the 26.3 SDL bridge drives the
     * Fullscreen API and keeps it.
     */
    public static void patchVideoSettingsScreenBrowserNoFullscreenToggle(String jar, Path root)
            throws IOException {
        ClassNode node = readCurrent(jar, root, VIDEO_SCREEN);
        MethodNode display = find(node, "displayOptions", "(L" + OPTIONS + ";)" + OPTION_ARRAY);
        nullGetter(display, "fullscreen", true);
        compactResult(display);
        writeCurrent(node, root);
    }

    // ---------------------------------------------------------------------------------------
    // Music & Sounds, Controls, Mouse Settings
    // ---------------------------------------------------------------------------------------

    /**
     * Music &amp; Sounds: no Device row. The browser OpenAL bridge has one output device, the
     * page's audio context (the system default), and no way to pick another.
     */
    public static void patchSoundOptionsScreenBrowserLayout(String jar, Path root)
            throws IOException {
        ClassNode node = readCurrent(jar, root, SOUND_SCREEN);
        MethodNode add = find(node, "addOptions", "()V");
        MethodInsnNode device = null;
        int devices = 0;
        for (MethodInsnNode call : calls(add, Opcodes.INVOKEVIRTUAL, OPTIONS_LIST, "addBig",
                "(" + OPTION_INSTANCE_DESC + ")V")) {
            if (isOptionGetter(previousOpcode(call), "soundDevice")) {
                device = call;
                devices++;
            }
        }
        if (devices != 1) {
            throw new IllegalStateException("SoundOptionsScreen.addOptions device row: " + devices);
        }
        popArguments(add, device);
        writeCurrent(node, root);
    }

    /**
     * Controls (26.3): no Quit Shortcuts. Alt+F4 and Cmd+Q close the browser window, not the
     * game, and the page cannot catch them.
     */
    public static void patchControlsScreenBrowserLayout(String jar, Path root) throws IOException {
        ClassNode node = readCurrent(jar, root, CONTROLS_SCREEN);
        MethodNode options = find(node, "options", "(L" + OPTIONS + ";)" + OPTION_ARRAY);
        nullGetter(options, "quitShortcuts", true);
        compactResult(options);
        writeCurrent(node, root);
    }

    /**
     * Mouse Settings (the GLFW profiles, 26.2 and 1.21.11): no Allow Cursor Changes. The GLFW
     * bridge's {@code glfwSetCursor} is empty, so the canvas cursor never changes there; the
     * 26.3 SDL bridge sets the canvas CSS cursor and keeps the option.
     */
    public static void patchMouseSettingsScreenBrowserNoCursorChanges(String jar, Path root)
            throws IOException {
        ClassNode node = readCurrent(jar, root, MOUSE_SCREEN);
        MethodNode options = find(node, "options", "(L" + OPTIONS + ";)" + OPTION_ARRAY);
        nullGetter(options, "allowCursorChanges", true);
        compactResult(options);
        writeCurrent(node, root);
    }

    // ---------------------------------------------------------------------------------------
    // Narrator
    // ---------------------------------------------------------------------------------------

    /**
     * Accessibility and Chat Settings: no Narrator and no Narrator Hotkey. The browser has no
     * text-to-speech backend ({@code Narrator.getNarrator()} is {@code Narrator.EMPTY}), so the
     * vanilla button only ever says the narrator is not available. The Accessibility page keeps
     * its Controls... link, alone on the narrator's row.
     */
    public static void patchNarratorOptionsBrowserLayout(String jar, Path root) throws IOException {
        ClassNode accessibility = readCurrent(jar, root, ACCESSIBILITY_SCREEN);
        MethodNode options = find(accessibility, "options", "(L" + OPTIONS + ";)" + OPTION_ARRAY);
        nullGetter(options, "narratorHotkey", true);
        compactResult(options);
        MethodNode add = find(accessibility, "addOptions", "()V");
        String rowDesc = "(" + ABSTRACT_WIDGET + OPTION_INSTANCE_DESC + ABSTRACT_WIDGET + ")V";
        MethodInsnNode row = single(calls(add, Opcodes.INVOKEVIRTUAL, OPTIONS_LIST, "addSmall", rowDesc),
                "AccessibilityOptionsScreen.addOptions narrator row");
        if (!isOptionGetter(previousOpcode(previousOpcode(row)), "narrator")) {
            throw new IllegalStateException(
                    "AccessibilityOptionsScreen.addOptions row is no longer the narrator row");
        }
        // list, narratorButton, narrator, controls -> list, controls, null
        InsnList shuffle = new InsnList();
        shuffle.add(new InsnNode(Opcodes.SWAP));
        shuffle.add(new InsnNode(Opcodes.POP));
        shuffle.add(new InsnNode(Opcodes.SWAP));
        shuffle.add(new InsnNode(Opcodes.POP));
        shuffle.add(new InsnNode(Opcodes.ACONST_NULL));
        add.instructions.insertBefore(row, shuffle);
        row.desc = "(" + ABSTRACT_WIDGET + ABSTRACT_WIDGET + ")V";
        writeCurrent(accessibility, root);

        ClassNode chat = readCurrent(jar, root, CHAT_SCREEN);
        MethodNode chatOptions = find(chat, "options", "(L" + OPTIONS + ";)" + OPTION_ARRAY);
        nullGetter(chatOptions, "narrator", true);
        compactResult(chatOptions);
        writeCurrent(chat, root);
    }

    // ---------------------------------------------------------------------------------------
    // Resource packs
    // ---------------------------------------------------------------------------------------

    /**
     * Pack selection (resource packs, and data packs at world creation): no Open Pack Folder
     * button. The packs live in the page's virtual file system, which no file manager can open
     * ({@code Util.OS.openUri} only opens web links in the browser); packs are added by dropping
     * them on the screen, as the screen's own hint says.
     */
    public static void patchPackSelectionScreenBrowserNoFolderButton(String jar, Path root)
            throws IOException {
        ClassNode node = readCurrent(jar, root, PACK_SCREEN);
        MethodNode init = find(node, "init", "()V");
        MethodInsnNode add = null;
        int adds = 0;
        for (AbstractInsnNode instruction = init.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC
                    || !field.owner.equals(PACK_SCREEN)
                    || !field.name.equals("OPEN_PACK_FOLDER_TITLE")) {
                continue;
            }
            for (AbstractInsnNode cursor = field.getNext(); cursor != null; cursor = cursor.getNext()) {
                if (cursor instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(LINEAR_LAYOUT)
                        && call.name.equals("addChild")
                        && call.desc.equals("(" + LAYOUT_ELEMENT + ")" + LAYOUT_ELEMENT)) {
                    add = call;
                    adds++;
                    break;
                }
            }
        }
        if (adds != 1) {
            throw new IllegalStateException("PackSelectionScreen.init Open Pack Folder button: " + adds);
        }
        InsnList skip = new InsnList();
        skip.add(new InsnNode(Opcodes.SWAP));
        skip.add(new InsnNode(Opcodes.POP));
        init.instructions.insert(add, skip);
        init.instructions.remove(add);
        writeCurrent(node, root);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * True when the profile's input seam is the GLFW bridge (26.2, 1.21.11): its window has no
     * monitor and its {@code glfwSetCursor} is empty. MinecraftClientPatcher registers the
     * GLFW-only layout patches only then.
     */
    public static boolean glfwInput(String minecraftVersion) {
        return "glfw".equals(VersionProfile.forId(minecraftVersion).inputSeam());
    }

    /** True when the jar's Controls screen offers Quit Shortcuts (26.3 and later). */
    public static boolean controlsHaveQuitShortcuts(String jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            var entry = zip.getEntry(CONTROLS_SCREEN + ".class");
            if (entry == null) {
                return false;
            }
            ClassNode node = new ClassNode();
            try (var input = zip.getInputStream(entry)) {
                new ClassReader(input.readAllBytes()).accept(node, 0);
            }
            MethodNode options = findNullable(node, "options", "(L" + OPTIONS + ";)" + OPTION_ARRAY);
            if (options == null) {
                return false;
            }
            for (AbstractInsnNode instruction : options.instructions.toArray()) {
                if (isOptionGetter(instruction, "quitShortcuts")) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The class as this step last wrote it, or the vanilla class. */
    static ClassNode readCurrent(String jar, Path root, String name) throws IOException {
        Path written = root.resolve(name + ".class");
        byte[] bytes;
        if (Files.isRegularFile(written)) {
            bytes = Files.readAllBytes(written);
        } else {
            try (ZipFile zip = new ZipFile(jar)) {
                var entry = zip.getEntry(name + ".class");
                if (entry == null) {
                    throw new IllegalStateException(name + ".class was not found in " + jar);
                }
                try (var input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Writes with recomputed max stack/locals; the edits add no branch, so frames stay valid. */
    static void writeCurrent(ClassNode node, Path root) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    static List<MethodInsnNode> calls(MethodNode method, int opcode, String owner, String name,
            String desc) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(desc)) {
                found.add(call);
            }
        }
        return found;
    }

    static MethodInsnNode single(List<MethodInsnNode> calls, String what) {
        if (calls.size() != 1) {
            throw new IllegalStateException(what + ": expected one, found " + calls.size());
        }
        return calls.get(0);
    }

    static void requireField(ClassNode node, String name, String desc) {
        boolean found = node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc));
        if (!found) {
            throw new IllegalStateException(node.name + " has no field " + name + " " + desc);
        }
    }

    static boolean isOptionGetter(AbstractInsnNode instruction, String getter) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && call.owner.equals(OPTIONS)
                && call.name.equals(getter)
                && call.desc.equals("()" + OPTION_INSTANCE_DESC);
    }

    /**
     * Replaces each {@code options.<getter>()} of an option-array builder with {@code null}
     * (the receiver is popped). Returns how many were replaced; {@code required} fails closed
     * when there is none.
     */
    static int nullGetter(MethodNode method, String getter, boolean required) {
        int replaced = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!isOptionGetter(instruction, getter)) {
                continue;
            }
            InsnList none = new InsnList();
            none.add(new InsnNode(Opcodes.POP));
            none.add(new InsnNode(Opcodes.ACONST_NULL));
            method.instructions.insert(instruction, none);
            method.instructions.remove(instruction);
            replaced++;
        }
        if (required && replaced == 0) {
            throw new IllegalStateException(method.name + " no longer reads Options." + getter);
        }
        return replaced;
    }

    /** Passes every returned option array through {@code BrowserOptionsLayout.compact}. */
    static void compactResult(MethodNode method) {
        if (!method.desc.endsWith(")" + OPTION_ARRAY)) {
            throw new IllegalStateException(method.name + " does not return OptionInstance[]");
        }
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) {
                continue;
            }
            InsnList compact = new InsnList();
            compact.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LAYOUT_HELPER, "compact",
                    "([Ljava/lang/Object;)[Ljava/lang/Object;", false));
            compact.add(new TypeInsnNode(Opcodes.CHECKCAST, OPTION_ARRAY));
            method.instructions.insertBefore(instruction, compact);
            returns++;
        }
        if (returns == 0) {
            throw new IllegalStateException(method.name + " has no ARETURN");
        }
    }

    /** Replaces a void {@code OptionsList} call with pops of its receiver and arguments. */
    static void popArguments(MethodNode method, MethodInsnNode call) {
        int slots = org.objectweb.asm.Type.getArgumentTypes(call.desc).length + 1;
        InsnList pops = new InsnList();
        for (; slots >= 2; slots -= 2) {
            pops.add(new InsnNode(Opcodes.POP2));
        }
        if (slots == 1) {
            pops.add(new InsnNode(Opcodes.POP));
        }
        method.instructions.insert(call, pops);
        method.instructions.remove(call);
    }

    static String staticFieldBefore(MethodInsnNode call) {
        return previousOpcode(call) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                ? field.name
                : null;
    }

    /**
     * The {@code GridLayout.RowHelper.addChild(LayoutElement)} that adds the button whose label is
     * the static Component field {@code labelField} of the Options screen.
     */
    static MethodInsnNode rowAddAfter(MethodNode init, String labelField) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode instruction = init.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC
                    || !field.owner.equals(OPTIONS_SCREEN)
                    || !field.name.equals(labelField)
                    || !field.desc.equals(COMPONENT)) {
                continue;
            }
            AbstractInsnNode cursor = nextOpcode(field);
            for (int steps = 0; cursor != null && steps < 4; steps++, cursor = nextOpcode(cursor)) {
                if (cursor instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(ROW_HELPER)
                        && call.name.equals("addChild")
                        && call.desc.equals("(" + LAYOUT_ELEMENT + ")" + LAYOUT_ELEMENT)) {
                    found.add(call);
                    break;
                }
            }
        }
        return single(found, "OptionsScreen.init " + labelField + " grid button");
    }
}
