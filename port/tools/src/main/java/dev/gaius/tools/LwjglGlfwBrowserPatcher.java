package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Redirects the original LWJGL GLFW API to the browser event/window backend. */
public final class LwjglGlfwBrowserPatcher {
    private static final String GLFW = "org/lwjgl/glfw/GLFW";
    private static final String BROWSER = "org/lwjgl/glfw/BrowserGlfw";

    private LwjglGlfwBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LwjglGlfwBrowserPatcher INPUT_JAR OUTPUT_ROOT");
        }
        Path root = Path.of(args[1]);
        patchGlfw(args[0], root.resolve("org/lwjgl/glfw/GLFW.class"));
        patchErrorCallback(args[0], root.resolve("org/lwjgl/glfw/GLFWErrorCallback.class"));
    }

    private static void patchGlfw(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/glfw/GLFW.class");
        Map<String, String> delegates = delegates();
        Map<String, String> browserNoOps = browserNoOps();
        int replaced = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC, GLFW, "GLFW", "Lorg/lwjgl/system/SharedLibrary;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1);
                replaced++;
                continue;
            }
            String target = delegates.get(method.name + method.desc);
            if (target != null) {
                delegate(method, target);
                replaced++;
                continue;
            }
            String noOp = browserNoOps.get(method.name + method.desc);
            if (noOp != null) {
                noOp(method, noOp);
                replaced++;
            }
        }
        if (replaced < 45) {
            throw new IllegalStateException("Too few GLFW methods patched: " + replaced);
        }
        write(node, output);
    }

    /**
     * GLFW 3.4.1 added browser-unimplemented IME/preedit entry points.  The
     * generated public wrappers perform a Checks check and then call their
     * native nglfw method, which is invalid after the browser GLFW library is
     * installed (the optional function address is deliberately zero).  Keep
     * these entries conditional by matching methods only when the version's
     * GLFW class actually contains them; LWJGL 3.3.x profiles simply have no
     * matching methods and retain their existing patch set.
     */
    private static Map<String, String> browserNoOps() {
        Map<String, String> result = new HashMap<>();
        addNoOp(result, "glfwGetPreeditCursorRectangle",
                "(JLjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;)V",
                "void");
        addNoOp(result, "glfwGetPreeditCursorRectangle", "(J[I[I[I[I)V", "void");
        addNoOp(result, "glfwSetPreeditCursorRectangle", "(JIIII)V", "void");
        addNoOp(result, "glfwResetPreeditText", "(J)V", "void");
        addNoOp(result, "glfwGetPreeditCandidate", "(JI)Ljava/nio/IntBuffer;", "null");
        addNoOp(result, "glfwSetPreeditCallback",
                "(JLorg/lwjgl/glfw/GLFWPreeditCallbackI;)Lorg/lwjgl/glfw/GLFWPreeditCallback;",
                "null");
        addNoOp(result, "glfwSetIMEStatusCallback",
                "(JLorg/lwjgl/glfw/GLFWIMEStatusCallbackI;)Lorg/lwjgl/glfw/GLFWIMEStatusCallback;",
                "null");
        addNoOp(result, "glfwSetPreeditCandidateCallback",
                "(JLorg/lwjgl/glfw/GLFWPreeditCandidateCallbackI;)Lorg/lwjgl/glfw/GLFWPreeditCandidateCallback;",
                "null");
        return result;
    }

    private static void addNoOp(Map<String, String> methods, String name, String descriptor, String returnKind) {
        methods.put(name + descriptor, returnKind);
    }

    private static void noOp(MethodNode method, String returnKind) {
        InsnList code = new InsnList();
        if (returnKind.equals("void")) {
            code.add(new InsnNode(Opcodes.RETURN));
        } else if (returnKind.equals("null")) {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new InsnNode(Opcodes.ARETURN));
        } else {
            throw new IllegalArgumentException("Unknown GLFW browser no-op return kind: " + returnKind);
        }
        replace(method, code, returnKind.equals("void") ? 0 : 1);
    }

    private static void patchErrorCallback(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/glfw/GLFWErrorCallback.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("create")
                    && method.desc.equals("(Lorg/lwjgl/glfw/GLFWErrorCallbackI;)Lorg/lwjgl/glfw/GLFWErrorCallback;")) {
                InsnList code = new InsnList();
                code.add(new org.objectweb.asm.tree.TypeInsnNode(
                        Opcodes.NEW, "org/lwjgl/glfw/GLFWErrorCallback$Container"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new InsnNode(Opcodes.LCONST_1));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "org/lwjgl/glfw/GLFWErrorCallback$Container",
                        "<init>",
                        "(JLorg/lwjgl/glfw/GLFWErrorCallbackI;)V",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 4);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("GLFWErrorCallback.create callback overload not found");
        }
        write(node, output);
    }

    private static void delegate(MethodNode method, String target) {
        InsnList code = new InsnList();
        Type[] arguments = Type.getArgumentTypes(method.desc);
        int local = 0;
        for (Type argument : arguments) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BROWSER, target, method.desc, false));
        Type result = Type.getReturnType(method.desc);
        code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        replace(method, code, Math.max(4, local));
    }

    private static Map<String, String> delegates() {
        Map<String, String> result = new HashMap<>();
        add(result, "glfwInit", "()Z", "init");
        add(result, "glfwTerminate", "()V", "terminate");
        add(result, "glfwDefaultWindowHints", "()V", "defaultWindowHints");
        add(result, "glfwWindowHint", "(II)V", "windowHint");
        add(result, "glfwWindowHintString", "(ILjava/lang/CharSequence;)V", "windowHintString");
        add(result, "glfwCreateWindow", "(IILjava/lang/CharSequence;JJ)J", "createWindow");
        add(result, "glfwDestroyWindow", "(J)V", "destroyWindow");
        add(result, "glfwWindowShouldClose", "(J)Z", "windowShouldClose");
        add(result, "glfwSetWindowShouldClose", "(JZ)V", "setWindowShouldClose");
        add(result, "glfwSetWindowTitle", "(JLjava/lang/CharSequence;)V", "setWindowTitle");
        add(result, "glfwSetWindowIcon", "(JLorg/lwjgl/glfw/GLFWImage$Buffer;)V", "setWindowIcon");
        add(result, "glfwGetWindowPos", "(J[I[I)V", "getWindowPos");
        add(result, "glfwSetWindowPos", "(JII)V", "setWindowPos");
        add(result, "glfwGetWindowSize", "(J[I[I)V", "getWindowSize");
        add(result, "glfwSetWindowSizeLimits", "(JIIII)V", "setWindowSizeLimits");
        add(result, "glfwSetWindowSize", "(JII)V", "setWindowSize");
        add(result, "glfwGetFramebufferSize", "(J[I[I)V", "getFramebufferSize");
        add(result, "glfwGetWindowMonitor", "(J)J", "getWindowMonitor");
        add(result, "glfwSetWindowMonitor", "(JJIIIII)V", "setWindowMonitor");
        add(result, "glfwGetWindowAttrib", "(JI)I", "getWindowAttrib");
        add(result, "glfwGetPlatform", "()I", "getPlatform");
        add(result, "glfwPlatformSupported", "(I)Z", "platformSupported");
        add(result, "glfwGetMonitors", "()Lorg/lwjgl/PointerBuffer;", "getMonitors");
        add(result, "glfwGetPrimaryMonitor", "()J", "getPrimaryMonitor");
        add(result, "glfwGetMonitorName", "(J)Ljava/lang/String;", "getMonitorName");
        add(result, "glfwGetMonitorPos", "(J[I[I)V", "getMonitorPos");
        add(result, "glfwGetMonitorContentScale", "(J[F[F)V", "getMonitorContentScale");
        add(result, "glfwGetVideoMode", "(J)Lorg/lwjgl/glfw/GLFWVidMode;", "getVideoMode");
        add(result, "glfwGetVideoModes", "(J)Lorg/lwjgl/glfw/GLFWVidMode$Buffer;", "getVideoModes");
        add(result, "glfwPollEvents", "()V", "pollEvents");
        add(result, "glfwWaitEvents", "()V", "waitEvents");
        add(result, "glfwWaitEventsTimeout", "(D)V", "waitEventsTimeout");
        add(result, "glfwPostEmptyEvent", "()V", "postEmptyEvent");
        add(result, "glfwGetInputMode", "(JI)I", "getInputMode");
        add(result, "glfwSetInputMode", "(JII)V", "setInputMode");
        add(result, "glfwRawMouseMotionSupported", "()Z", "rawMouseMotionSupported");
        add(result, "glfwGetKeyName", "(II)Ljava/lang/String;", "getKeyName");
        add(result, "glfwGetKeyScancode", "(I)I", "getKeyScancode");
        add(result, "glfwGetKey", "(JI)I", "getKey");
        add(result, "glfwGetMouseButton", "(JI)I", "getMouseButton");
        add(result, "glfwGetCursorPos", "(J[D[D)V", "getCursorPos");
        add(result, "glfwSetCursorPos", "(JDD)V", "setCursorPos");
        add(result, "glfwCreateStandardCursor", "(I)J", "createStandardCursor");
        add(result, "glfwDestroyCursor", "(J)V", "destroyCursor");
        add(result, "glfwSetCursor", "(JJ)V", "setCursor");
        add(result, "glfwJoystickPresent", "(I)Z", "joystickPresent");
        add(result, "glfwSetClipboardString", "(JLjava/lang/CharSequence;)V", "setClipboardString");
        add(result, "glfwGetClipboardString", "(J)Ljava/lang/String;", "getClipboardString");
        add(result, "glfwGetTime", "()D", "getTime");
        add(result, "glfwSetTime", "(D)V", "setTime");
        add(result, "glfwGetTimerValue", "()J", "getTimerValue");
        add(result, "glfwGetTimerFrequency", "()J", "getTimerFrequency");
        add(result, "glfwMakeContextCurrent", "(J)V", "makeContextCurrent");
        add(result, "glfwGetCurrentContext", "()J", "getCurrentContext");
        add(result, "glfwSwapBuffers", "(J)V", "swapBuffers");
        add(result, "glfwSwapInterval", "(I)V", "swapInterval");
        add(result, "glfwGetError", "(Lorg/lwjgl/PointerBuffer;)I", "getError");
        add(result, "glfwSetErrorCallback", "(Lorg/lwjgl/glfw/GLFWErrorCallbackI;)Lorg/lwjgl/glfw/GLFWErrorCallback;", "setErrorCallback");
        add(result, "glfwSetMonitorCallback", "(Lorg/lwjgl/glfw/GLFWMonitorCallbackI;)Lorg/lwjgl/glfw/GLFWMonitorCallback;", "setMonitorCallback");
        addCallback(result, "WindowPos", "windowPos");
        addCallback(result, "WindowSize", "windowSize");
        addCallback(result, "WindowClose", "windowClose");
        addCallback(result, "WindowFocus", "windowFocus");
        addCallback(result, "WindowIconify", "windowIconify");
        addCallback(result, "FramebufferSize", "framebufferSize");
        addCallback(result, "CursorEnter", "cursorEnter");
        addCallback(result, "Key", "key");
        addCallback(result, "Char", "char");
        addCallback(result, "CharMods", "charMods");
        addCallback(result, "MouseButton", "mouseButton");
        addCallback(result, "CursorPos", "cursorPos");
        addCallback(result, "Scroll", "scroll");
        addCallback(result, "Drop", "drop");
        return result;
    }

    private static void addCallback(Map<String, String> methods, String type, String targetPrefix) {
        add(methods, "glfwSet" + type + "Callback",
                "(JLorg/lwjgl/glfw/GLFW" + type + "CallbackI;)Lorg/lwjgl/glfw/GLFW" + type + "Callback;",
                "set" + Character.toUpperCase(targetPrefix.charAt(0)) + targetPrefix.substring(1) + "Callback");
    }

    private static void add(Map<String, String> methods, String name, String descriptor, String target) {
        methods.put(name + descriptor, target);
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found");
            }
            ClassNode node = new ClassNode();
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    private static void replace(MethodNode method, InsnList code, int maxStack) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = Math.max(method.maxLocals, Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
