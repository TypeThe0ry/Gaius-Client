package dev.gaius.tools.patches.input;

import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.read;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.replace;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.store;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.write;

import dev.gaius.tools.LwjglSdlBrowserPatcher;
import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The input domain of the Minecraft263BrowserPatcher step, the tail of the modern chain for the
 * renderpearl render seam ({@code patches/input}): the SDL input backend, work package
 * P2 (decision D3).
 *
 * <ul>
 *   <li>{@link #checkSdlShimCoverage}: every {@code org/lwjgl/sdl} {@code SDL_*} entry point
 *       the client references (calls and method handles) is one that
 *       {@link LwjglSdlBrowserPatcher} redirects to the browser shim; otherwise the build
 *       fails (a new call would silently reach LWJGL's native path).</li>
 *   <li>{@code SDLEventHandler}: the eight {@code Minecraft.execute(Runnable)} calls become
 *       {@code BrowserInputDispatch.runNow(Minecraft, Runnable)}, exactly eight or fail. This
 *       keeps 26.2's synchronous dispatch and removes the use of a reused or freed
 *       {@code SDL_Event} by deferred lambdas when the pump is re-entered.</li>
 *   <li>{@code MouseHandler.onButton}: the entry/dispatch/result telemetry of 26.2, now
 *       through {@code BrowserInputTelemetry}, with the locals derived from the
 *       {@code MouseButtonEvent.<init>(DDLMouseButtonInfo;)V} site instead of fixed slots,
 *       and the LoadingOverlay gate that lets a visible screen take clicks.</li>
 *   <li>{@code SdlDebug}: no SDL log callback ({@code CALLBACK = null}, {@code init()} returns),
 *       so neither the LWJGL callback machinery nor {@code HexFormat} is reachable (risk R22).</li>
 * </ul>
 *
 * <p>{@code Minecraft263BrowserPatcher} registers these patches in the chain order under their
 * historical ids ({@code InputPatches263.<method>}); their profiles come from the specs. The
 * {@code Blaze3D.openUri} patch of the same group is in the platform domain
 * ({@code RenderpearlPlatformPatches}). The 26.2 GLFW patches of MinecraftClientPatcher
 * (patchBrowserInputCallbacks, patchInputConstants, patchOpenUri) register their 26.3 parts as
 * dropped after asserting that the GLFW-era targets are absent.
 */
public final class RenderpearlInputPatches {
    static final String SDL_EVENT_HANDLER = "com/mojang/blaze3d/platform/SDLEventHandler";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String MOUSE_HANDLER = "net/minecraft/client/MouseHandler";
    static final String SDL_DEBUG = "com/mojang/blaze3d/platform/SdlDebug";
    static final String DISPATCH = "dev/gaius/browser/BrowserInputDispatch";
    static final String TELEMETRY = "dev/gaius/browser/BrowserInputTelemetry";
    static final int SDL_EVENT_HANDLER_EXECUTE_SITES = 8;

    private RenderpearlInputPatches() {
    }

    // ------------------------------------------------------------------ coverage

    /** Returns the {@code owner.name+desc} keys of every SDL_* method the client references. */
    static Set<String> sdlReferences(String jar) throws IOException {
        Set<String> references = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")
                        || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                byte[] bytes;
                try (var stream = zip.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                if (!contains(bytes, SDL_PACKAGE_BYTES)) {
                    continue;
                }
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
                for (MethodNode method : node.methods) {
                    for (AbstractInsnNode instruction = method.instructions.getFirst();
                            instruction != null;
                            instruction = instruction.getNext()) {
                        if (instruction instanceof MethodInsnNode call) {
                            addReference(references, call.owner, call.name, call.desc);
                        } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                            for (Object argument : dynamic.bsmArgs) {
                                if (argument instanceof Handle handle) {
                                    addReference(references, handle.getOwner(), handle.getName(), handle.getDesc());
                                }
                            }
                        } else if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle) {
                            addReference(references, handle.getOwner(), handle.getName(), handle.getDesc());
                        }
                    }
                }
            }
        }
        return references;
    }

    private static final byte[] SDL_PACKAGE_BYTES =
            LwjglSdlBrowserPatcher.PACKAGE.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[start + index] != needle[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void addReference(Set<String> references, String owner, String name, String desc) {
        if (owner.startsWith(LwjglSdlBrowserPatcher.PACKAGE) && name.startsWith("SDL_")) {
            references.add(owner + "." + name + desc);
        }
    }

    public static void checkSdlShimCoverage(String jar) throws IOException {
        Set<String> references = sdlReferences(jar);
        Set<String> redirected = LwjglSdlBrowserPatcher.entryKeys();
        List<String> missing = new ArrayList<>();
        for (String reference : references) {
            if (!redirected.contains(reference)) {
                missing.add(reference);
            }
        }
        if (!missing.isEmpty()) {
            // LwjglSdlBrowserPatcher redirects (or stubs) exactly the entry points of the
            // profile's usage lock, so a reference outside it means the lock is stale.
            String profile = PatchRegistry.profile();
            if (profile == null) {
                profile = "<profile>";
            }
            throw new IllegalStateException("The client references SDL entry points that the usage lock "
                    + "port/mc/profiles/" + profile + "/usage.lock does not list (LwjglSdlBrowserPatcher "
                    + "redirects only those): " + String.join(", ", missing)
                    + "; run bash port/tools/extrefs/extrefs.sh usage " + profile + " --write");
        }
        if (references.size() > redirected.size()) {
            throw new IllegalStateException("Client SDL references " + references.size()
                    + " exceed the " + redirected.size() + " redirected entry points");
        }
        System.out.println("InputPatches263: the client references " + references.size()
                + " SDL entry points, all among the " + redirected.size() + " that LwjglSdlBrowserPatcher redirects");
    }

    // ------------------------------------------------------------------ SDLEventHandler

    public static void patchSdlEventHandlerDispatch(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, SDL_EVENT_HANDLER);
        int replaced = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(MINECRAFT)
                        && call.name.equals("execute")
                        && call.desc.equals("(Ljava/lang/Runnable;)V")) {
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = DISPATCH;
                    call.name = "runNow";
                    call.desc = "(L" + MINECRAFT + ";Ljava/lang/Runnable;)V";
                    call.itf = false;
                    replaced++;
                }
            }
        }
        if (replaced != SDL_EVENT_HANDLER_EXECUTE_SITES) {
            throw new IllegalStateException("SDLEventHandler has " + replaced
                    + " Minecraft.execute(Runnable) dispatch sites, expected " + SDL_EVENT_HANDLER_EXECUTE_SITES);
        }
        // Same stack shape (Minecraft, Runnable) -> (), so the original frames stay valid.
        write(node, root, 0);
    }

    // ------------------------------------------------------------------ MouseHandler.onButton

    public static void patchMouseHandlerButtonHooks(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, MOUSE_HANDLER);
        MethodNode onButton = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("onButton")
                    && method.desc.equals("(JLnet/minecraft/client/input/MouseButtonInfo;I)V")) {
                onButton = method;
            }
        }
        if (onButton == null) {
            throw new IllegalStateException("MouseHandler.onButton(JLMouseButtonInfo;I)V not found");
        }
        ButtonLocals locals = ButtonLocals.derive(onButton);
        int entryHooks = 0;
        int dispatchHooks = 0;
        int resultHooks = 0;
        int overlayGates = 0;
        for (AbstractInsnNode instruction = onButton.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction == locals.windowStore) {
                InsnList entry = new InsnList();
                entry.add(new VarInsnNode(Opcodes.LLOAD, 1));
                entry.add(new VarInsnNode(Opcodes.ALOAD, locals.window));
                entry.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/mojang/blaze3d/platform/Window",
                        "handle", "()J", false));
                entry.add(new VarInsnNode(Opcodes.ALOAD, 3));
                entry.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/input/MouseButtonInfo",
                        "button", "()I", false));
                entry.add(new VarInsnNode(Opcodes.ILOAD, 4));
                entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TELEMETRY, "reportMouseHandlerEntry",
                        "(JJII)V", false));
                onButton.instructions.insert(instruction, entry);
                entryHooks++;
            } else if (instruction == locals.eventStore) {
                InsnList dispatch = new InsnList();
                dispatch.add(new VarInsnNode(Opcodes.DLOAD, locals.x));
                dispatch.add(new VarInsnNode(Opcodes.DLOAD, locals.y));
                dispatch.add(new VarInsnNode(Opcodes.ILOAD, locals.pressed));
                dispatch.add(new VarInsnNode(Opcodes.ALOAD, locals.screen));
                dispatch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TELEMETRY, "reportMouseHandlerDispatch",
                        "(DDZLjava/lang/Object;)V", false));
                onButton.instructions.insert(instruction, dispatch);
                dispatchHooks++;
            } else if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/gui/screens/Screen")
                    && call.name.equals("mouseClicked")
                    && call.desc.equals("(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z")) {
                InsnList result = new InsnList();
                result.add(new InsnNode(Opcodes.DUP));
                result.add(new VarInsnNode(Opcodes.DLOAD, locals.x));
                result.add(new VarInsnNode(Opcodes.DLOAD, locals.y));
                result.add(new VarInsnNode(Opcodes.ALOAD, locals.screen));
                result.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TELEMETRY, "reportMouseClickedResult",
                        "(ZDDLjava/lang/Object;)V", false));
                onButton.instructions.insert(instruction, result);
                resultHooks++;
            } else if (overlayGates == 0
                    && instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/gui/Gui")
                    && call.name.equals("overlay")
                    && call.desc.equals("()Lnet/minecraft/client/gui/screens/Overlay;")) {
                instruction = gateLoadingOverlay(onButton, call);
                overlayGates++;
            }
        }
        if (entryHooks != 1 || dispatchHooks != 1 || resultHooks != 1 || overlayGates != 1) {
            throw new IllegalStateException("MouseHandler.onButton hook points: entry=" + entryHooks
                    + " dispatch=" + dispatchHooks + " result=" + resultHooks + " overlayGate=" + overlayGates
                    + " (expected one each)");
        }
        writeComputingFrames(node, root, jar);
    }

    /**
     * {@code if (gui.overlay() != null) goto blocked} becomes "blocked only when the overlay
     * is not a LoadingOverlay or no screen is open", so a visible menu under a fading
     * LoadingOverlay takes clicks (the 26.2 gate of MinecraftClientPatcher, Gui variant).
     * Returns the last instruction of the rewritten gate.
     */
    private static AbstractInsnNode gateLoadingOverlay(MethodNode method, MethodInsnNode overlayCall) {
        AbstractInsnNode maybeIfNull = nextReal(overlayCall);
        AbstractInsnNode maybeGoto = nextReal(maybeIfNull);
        if (!(maybeIfNull instanceof JumpInsnNode)
                || maybeIfNull.getOpcode() != Opcodes.IFNULL
                || !(maybeGoto instanceof JumpInsnNode blocked)
                || blocked.getOpcode() != Opcodes.GOTO) {
            throw new IllegalStateException("MouseHandler overlay gate shape changed");
        }
        // The allow path falls through into the code after the removed GOTO, so the IFNULL
        // must have jumped exactly there.
        if (nextReal(((JumpInsnNode) maybeIfNull).label) != nextReal(maybeGoto)) {
            throw new IllegalStateException("MouseHandler overlay gate shape changed: the overlay"
                    + " null check does not jump to the instruction after its blocking GOTO");
        }
        LabelNode allow = new LabelNode();
        LabelNode popAndBlock = new LabelNode();
        InsnList gate = new InsnList();
        gate.add(new InsnNode(Opcodes.DUP));
        gate.add(new JumpInsnNode(Opcodes.IFNULL, allow));
        gate.add(new InsnNode(Opcodes.DUP));
        gate.add(new TypeInsnNode(Opcodes.INSTANCEOF, "net/minecraft/client/gui/screens/LoadingOverlay"));
        gate.add(new JumpInsnNode(Opcodes.IFEQ, popAndBlock));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(Opcodes.GETFIELD, MOUSE_HANDLER, "minecraft", "L" + MINECRAFT + ";"));
        gate.add(new FieldInsnNode(Opcodes.GETFIELD, MINECRAFT, "gui", "Lnet/minecraft/client/gui/Gui;"));
        gate.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/gui/Gui", "screen",
                "()Lnet/minecraft/client/gui/screens/Screen;", false));
        gate.add(new JumpInsnNode(Opcodes.IFNONNULL, allow));
        gate.add(popAndBlock);
        gate.add(new InsnNode(Opcodes.POP));
        gate.add(new JumpInsnNode(Opcodes.GOTO, blocked.label));
        gate.add(allow);
        gate.add(new InsnNode(Opcodes.POP));
        AbstractInsnNode last = gate.getLast();
        method.instructions.insertBefore(maybeIfNull, gate);
        method.instructions.remove(maybeIfNull);
        method.instructions.remove(maybeGoto);
        return last;
    }

    /** Local slots of MouseHandler.onButton, derived from the bytecode (26.3: 5, 7, 9, 11, 12, 6). */
    static final class ButtonLocals {
        int window = -1;
        int x = -1;
        int y = -1;
        int screen = -1;
        int event = -1;
        int pressed = -1;
        VarInsnNode windowStore;
        VarInsnNode eventStore;

        static ButtonLocals derive(MethodNode method) {
            ButtonLocals locals = new ButtonLocals();
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (locals.windowStore == null
                        && instruction instanceof MethodInsnNode call
                        && call.owner.equals(MINECRAFT)
                        && call.name.equals("getWindow")
                        && nextReal(call) instanceof VarInsnNode store
                        && store.getOpcode() == Opcodes.ASTORE) {
                    locals.windowStore = store;
                    locals.window = store.var;
                }
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.owner.equals("net/minecraft/client/input/MouseButtonEvent")
                        && call.name.equals("<init>")
                        && call.desc.equals("(DDLnet/minecraft/client/input/MouseButtonInfo;)V")) {
                    if (locals.eventStore != null) {
                        throw new IllegalStateException("MouseHandler.onButton builds more than one MouseButtonEvent");
                    }
                    AbstractInsnNode info = previousReal(call);
                    AbstractInsnNode yLoad = previousReal(info);
                    AbstractInsnNode xLoad = previousReal(yLoad);
                    AbstractInsnNode dup = previousReal(xLoad);
                    AbstractInsnNode newEvent = previousReal(dup);
                    AbstractInsnNode screenStore = previousReal(newEvent);
                    AbstractInsnNode eventStore = nextReal(call);
                    AbstractInsnNode pressedLoad = nextReal(eventStore);
                    if (!(info instanceof VarInsnNode infoLoad) || infoLoad.getOpcode() != Opcodes.ALOAD
                            || infoLoad.var != 3
                            || !(yLoad instanceof VarInsnNode y) || y.getOpcode() != Opcodes.DLOAD
                            || !(xLoad instanceof VarInsnNode x) || x.getOpcode() != Opcodes.DLOAD
                            || dup == null || dup.getOpcode() != Opcodes.DUP
                            || !(newEvent instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
                            || !(screenStore instanceof VarInsnNode screen) || screen.getOpcode() != Opcodes.ASTORE
                            || !(previousReal(screenStore) instanceof MethodInsnNode screenCall)
                            || !screenCall.name.equals("screen")
                            || !(eventStore instanceof VarInsnNode event) || event.getOpcode() != Opcodes.ASTORE
                            || !(pressedLoad instanceof VarInsnNode pressed) || pressed.getOpcode() != Opcodes.ILOAD
                            || !(nextReal(pressedLoad) instanceof JumpInsnNode pressedBranch)
                            || pressedBranch.getOpcode() != Opcodes.IFEQ) {
                        throw new IllegalStateException("MouseHandler.onButton MouseButtonEvent site shape changed");
                    }
                    locals.x = x.var;
                    locals.y = y.var;
                    locals.screen = screen.var;
                    locals.event = event.var;
                    locals.pressed = pressed.var;
                    locals.eventStore = event;
                }
            }
            if (locals.windowStore == null || locals.eventStore == null) {
                throw new IllegalStateException("MouseHandler.onButton window or MouseButtonEvent local not found");
            }
            return locals;
        }

        @Override
        public String toString() {
            return "window=" + window + " x=" + x + " y=" + y + " screen=" + screen + " event=" + event
                    + " pressed=" + pressed;
        }
    }

    // ------------------------------------------------------------------ SdlDebug

    public static void patchSdlDebug(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, SDL_DEBUG);
        boolean initializer = false;
        boolean init = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && method.desc.equals("()V")) {
                boolean createsCallback = false;
                boolean setsLogger = false;
                for (AbstractInsnNode instruction = method.instructions.getFirst();
                        instruction != null;
                        instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("org/lwjgl/sdl/SDL_LogOutputFunction")
                            && call.name.equals("create")) {
                        createsCallback = true;
                    } else if (instruction instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.PUTSTATIC
                            && field.name.equals("LOGGER")) {
                        setsLogger = true;
                    }
                }
                if (!createsCallback || !setsLogger) {
                    throw new IllegalStateException("SdlDebug.<clinit> shape changed");
                }
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/mojang/logging/LogUtils", "getLogger",
                        "()Lorg/slf4j/Logger;", false));
                code.add(new FieldInsnNode(Opcodes.PUTSTATIC, SDL_DEBUG, "LOGGER", "Lorg/slf4j/Logger;"));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(Opcodes.PUTSTATIC, SDL_DEBUG, "CALLBACK",
                        "Lorg/lwjgl/sdl/SDL_LogOutputFunction;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1);
                initializer = true;
            } else if (method.name.equals("init") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0);
                init = true;
            }
        }
        if (!initializer || !init) {
            throw new IllegalStateException("SdlDebug.<clinit>/init() not found");
        }
        write(node, root, 0);
    }

    // ------------------------------------------------------------------ helpers

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    /**
     * Recomputes frames with the real class hierarchy of the client jar (and the JDK for
     * {@code java/*} types), so the output stays verifiable.
     */
    private static void writeComputingFrames(ClassNode node, Path root, String jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return commonSuperClass(zip, type1, type2);
                }
            };
            node.accept(writer);
            store(node, root, writer.toByteArray());
        }
    }

    static String commonSuperClass(ZipFile zip, String type1, String type2) {
        if (type1.equals(type2)) {
            return type1;
        }
        List<String> ancestors1 = ancestors(zip, type1);
        List<String> ancestors2 = ancestors(zip, type2);
        if (ancestors1 == null || ancestors2 == null) {
            return "java/lang/Object";
        }
        for (String candidate : ancestors1) {
            if (ancestors2.contains(candidate)) {
                return candidate;
            }
        }
        return "java/lang/Object";
    }

    /** Class and superclasses of {@code type}, or null for an interface or an unknown type. */
    private static List<String> ancestors(ZipFile zip, String type) {
        List<String> chain = new ArrayList<>();
        String current = type;
        while (current != null) {
            chain.add(current);
            String[] header = header(zip, current);
            if (header == null) {
                return null;
            }
            if ("interface".equals(header[1])) {
                return null;
            }
            current = header[0];
        }
        return chain;
    }

    /** {superName, "interface"|"class"} of {@code type} from the jar or the JDK. */
    private static String[] header(ZipFile zip, String type) {
        try {
            ZipEntry entry = zip.getEntry(type + ".class");
            if (entry != null) {
                try (var stream = zip.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(stream.readAllBytes());
                    return new String[] {reader.getSuperName(),
                            (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0 ? "interface" : "class"};
                }
            }
            Class<?> type0 = Class.forName(type.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            Class<?> superclass = type0.getSuperclass();
            return new String[] {superclass == null ? null : superclass.getName().replace('.', '/'),
                    type0.isInterface() ? "interface" : "class"};
        } catch (IOException | ClassNotFoundException | LinkageError unknown) {
            return null;
        }
    }
}
