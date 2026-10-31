package dev.gaius.tools.patches.platform;

import static dev.gaius.tools.patches.support.ModernPatchSupport.JDK_COMPAT;
import static dev.gaius.tools.patches.support.ModernPatchSupport.find;
import static dev.gaius.tools.patches.support.ModernPatchSupport.read;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replace;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replaceBoolean;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replaceCall;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replaceVoid;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireOne;
import static dev.gaius.tools.patches.support.ModernPatchSupport.write;

import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The platform domain of the Minecraft262BrowserPatcher step, the {@code modern} patch set arm
 * ({@code patches/platform}): the native libraries bootstrap, module lister, macOS
 * utilities, debug memory and system specs entries, and identifier path resolution.
 *
 * <p>{@code Minecraft262BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft262BrowserPatcher.<method>}); the ASM helpers that other
 * domains of that step share live in ModernPatchSupport and are imported statically.
 */
public final class ModernPlatformPatches {
    private ModernPlatformPatches() {
    }

    public static void patchNativeLibrariesBootstrap(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/platform/NativeLibrariesBootstrap";
        ClassNode node = read(jar, owner + ".class");
        replaceVoid(find(node, "loadLibraries", "()V"));
        replaceBoolean(find(node, "isVulkanLoaderAvailable", "()Z"), false);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Disabled desktop native-library bootstrap for Minecraft 26.2");
    }

    public static void patchNativeModuleLister(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/NativeModuleLister";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "tryGetModuleVersion",
                "(Ljava/lang/String;)Ljava/util/Optional;");
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Optional",
                "empty",
                "()Ljava/util/Optional;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 1);
        write(node, root.resolve(owner + ".class"));
    }

    public static void patchMacosUtil(String jar, Path root) throws IOException {
        if (ModernSymbols.cached(jar).renderpearl()) {
            // 26.3 MacosUtil has no getNsWindow/setWindowColorSpaceForOpenGLBecauseGLFWDoesnt;
            // RenderPatches263.patchMacosUtil stubs its ObjC and SDL-hint entry points.
            PatchRegistry.dropped("Minecraft262BrowserPatcher.patchMacosUtil", jar,
                    "com/mojang/blaze3d/platform/MacosUtil#getNsWindow",
                    "com/mojang/blaze3d/platform/MacosUtil"
                            + "#setWindowColorSpaceForOpenGLBecauseGLFWDoesnt");
            return;
        }
        String owner = "com/mojang/blaze3d/platform/MacosUtil";
        ClassNode node = read(jar, owner + ".class");
        for (String descriptor : new String[] {
                "(Lcom/mojang/blaze3d/platform/Window;)Ljava/util/Optional;",
                "(J)Ljava/util/Optional;"
        }) {
            MethodNode method = find(node, "getNsWindow", descriptor);
            InsnList code = new InsnList();
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/Optional",
                    "empty",
                    "()Ljava/util/Optional;",
                    false));
            code.add(new InsnNode(Opcodes.ARETURN));
            replace(method, code, 1, descriptor.equals("(J)Ljava/util/Optional;") ? 2 : 1);
        }
        replaceVoid(find(node, "setWindowColorSpaceForOpenGLBecauseGLFWDoesnt", "(J)V"));
        write(node, root.resolve(owner + ".class"));
    }

    public static void patchDetailedMemoryDebug(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/gui/components/debug/DebugEntryDetailedMemory";
        ClassNode node = read(jar, owner + ".class");
        node.fields.removeIf(field -> field.name.equals("memoryBean")
                && field.desc.equals("Ljava/lang/management/MemoryMXBean;"));

        MethodNode constructor = find(node, "<init>", "()V");
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructorCode.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, constructorCode, 1, 1);

        replaceVoid(find(node, "display",
                "(Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;"
                        + "Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;)V"));
        replaceBoolean(find(node, "isAllowed", "(Z)Z"), false);
        write(node, root.resolve(owner + ".class"));
    }

    public static void patchSystemSpecsDebug(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "firstLine", "(Ljava/lang/String;)Ljava/lang/String;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "firstLine",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 2);
        write(node, root.resolve(owner + ".class"));
    }

    public static void patchIdentifierResolveAgainst(String jar, Path root) throws IOException {
        String owner = "net/minecraft/resources/Identifier";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "resolveAgainst",
                "(Ljava/nio/file/Path;)Ljava/nio/file/Path;");
        int replaced = replaceCall(
                method,
                Opcodes.INVOKEINTERFACE,
                "java/nio/file/Path",
                "resolve",
                "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "resolve",
                "(Ljava/nio/file/Path;Ljava/lang/String;[Ljava/lang/String;)"
                        + "Ljava/nio/file/Path;");
        requireOne("Identifier Path.resolve(String, String[])", replaced);
        write(node, root.resolve(owner + ".class"));
    }
}
