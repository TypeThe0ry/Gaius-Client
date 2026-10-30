package dev.gaius.tools.patches.platform;

import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.find;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.read;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.readClass;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.replace;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.write;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.writeClass;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The platform domain of the Minecraft263BrowserPatcher step, the tail of the modern chain for the
 * renderpearl render seam ({@code patches/platform}): desktop-only platform services
 * and the cooperative startup of the server Worker. The JDK API gaps of that step are
 * {@link JdkCompatPatches}.
 * <ul>
 *   <li>{@link #patchSystemSpecsCpuInfo}: {@code DebugEntrySystemSpecs.getCpuInfo()} returns
 *       "Browser runtime" with the graphics quality tier
 *       ({@code BrowserQualityOptions.debugCpuInfo}; 26.3 removed {@code GLX._getCpuInfo}, the
 *       26.2 target of {@code MinecraftClientPatcher.patchGlx}); prunes oshi from the reachable
 *       set.</li>
 *   <li>{@link #patchMacosUtil}: stubs {@code disableCloseWindowMenuItem()} (ObjC bridge) and the
 *       two SDL hint setters; MinecraftClientPatcher.patchMacosUtil already forces
 *       {@code IS_MACOS=false}, this also prunes their reachability.</li>
 *   <li>{@link #patchBlaze3dOpenUri}: {@code Blaze3D.openUri} opens http(s) links through
 *       {@code BrowserInputDispatch} (the vanilla body schedules {@code SDL_OpenURL} on an I/O
 *       pool, after the click's user activation may have expired).</li>
 *   <li>{@link #patchRegistryLoadTaskBrowserStartupYield}: 26.3 decodes every datapack JSON
 *       registry of the server Worker (world, dimension, and the recipes and loot tables that
 *       26.2 still scanned through SimpleJsonResourceReloadListener.scanDirectory) in
 *       {@code RegistryLoadTask$PendingRegistration.loadFromResource}. A
 *       {@code BrowserStartupScheduler.datapackResourceDecoded()} call before each of its two
 *       returns restores the cooperative startup yield (batched, 64 resources per yield).</li>
 * </ul>
 *
 * <p>{@code Minecraft263BrowserPatcher} registers these patches in the chain order under their
 * historical ids ({@code RenderPatches263.patchSystemSpecsCpuInfo},
 * {@code RenderPatches263.patchMacosUtil}, {@code InputPatches263.patchBlaze3dOpenUri},
 * {@code ServerPatches263.patchRegistryLoadTaskBrowserStartupYield}); their profiles come from
 * the specs. Classes are read and written through {@code RenderpearlPatchSupport}.
 */
public final class RenderpearlPlatformPatches {
    static final String SYSTEM_SPECS =
            "net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs";
    static final String MACOS_UTIL = "com/mojang/blaze3d/platform/MacosUtil";
    static final String BROWSER_CPU_INFO = "Browser runtime";
    static final String QUALITY_OPTIONS = "dev/gaius/browser/quality/BrowserQualityOptions";
    static final String BLAZE3D = "com/mojang/blaze3d/Blaze3D";
    static final String DISPATCH = "dev/gaius/browser/BrowserInputDispatch";
    static final String PENDING_REGISTRATION =
            "net/minecraft/resources/RegistryLoadTask$PendingRegistration";
    static final String LOAD_FROM_RESOURCE_DESCRIPTOR =
            "(Lcom/mojang/serialization/Decoder;Lnet/minecraft/resources/RegistryOps;"
                    + "Lnet/minecraft/resources/ResourceKey;"
                    + "Lnet/minecraft/server/packs/resources/Resource;)"
                    + "Lcom/mojang/datafixers/util/Either;";
    static final String STARTUP_SCHEDULER = "dev/gaius/browser/BrowserStartupScheduler";

    private RenderpearlPlatformPatches() {
    }

    public static void patchSystemSpecsCpuInfo(String jar, Path root) throws IOException {
        ClassNode node = readClass(jar, root, SYSTEM_SPECS);
        MethodNode method = find(node, "getCpuInfo", "()Ljava/lang/String;");
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException(SYSTEM_SPECS + ".getCpuInfo is no longer static");
        }
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(BROWSER_CPU_INFO));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, QUALITY_OPTIONS, "debugCpuInfo",
                "(Ljava/lang/String;)Ljava/lang/String;", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code);
        writeClass(node, root, false);
        System.out.println("Patched 26.3 DebugEntrySystemSpecs.getCpuInfo to \""
                + BROWSER_CPU_INFO + "\"");
    }

    public static void patchMacosUtil(String jar, Path root) throws IOException {
        ClassNode node = readClass(jar, root, MACOS_UTIL);
        int stubbed = 0;
        for (String[] target : new String[][] {
                {"disableCloseWindowMenuItem", "()V"},
                {"setFullscreenMenuVisibility", "(Z)V"},
                {"setCtrlClickEmulatesRightClick", "(Z)V"}}) {
            MethodNode method = find(node, target[0], target[1]);
            if ((method.access & Opcodes.ACC_STATIC) == 0) {
                throw new IllegalStateException(MACOS_UTIL + "." + target[0]
                        + " is no longer static");
            }
            InsnList code = new InsnList();
            code.add(new InsnNode(Opcodes.RETURN));
            replace(method, code);
            stubbed++;
        }
        writeClass(node, root, false);
        System.out.println("Stubbed " + stubbed + " 26.3 MacosUtil ObjC/SDL-hint entry points");
    }

    public static void patchBlaze3dOpenUri(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, BLAZE3D);
        int patched = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("openUri") && method.desc.equals("(Ljava/net/URI;)V")
                    && (method.access & Opcodes.ACC_STATIC) != 0) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCH, "openUri", "(Ljava/net/URI;)V", false));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1);
                patched++;
            }
        }
        if (patched != 1) {
            throw new IllegalStateException("Blaze3D.openUri(URI) found " + patched + " times");
        }
        write(node, root, 0);
    }

    public static void patchRegistryLoadTaskBrowserStartupYield(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, PENDING_REGISTRATION);
        MethodNode load = find(node, "loadFromResource", LOAD_FROM_RESOURCE_DESCRIPTOR);
        if ((load.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException(PENDING_REGISTRATION + ".loadFromResource is not static");
        }
        for (AbstractInsnNode instruction : load.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(STARTUP_SCHEDULER)) {
                throw new IllegalStateException(PENDING_REGISTRATION
                        + ".loadFromResource already calls " + STARTUP_SCHEDULER);
            }
        }
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode instruction : load.instructions) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                returns.add(instruction);
            }
        }
        // 26.3: Either.left(decoded) after closing the reader, and Either.right(error) from the
        // parse-failure handler. Both count as one decoded resource.
        if (returns.size() != 2) {
            throw new IllegalStateException(PENDING_REGISTRATION
                    + ".loadFromResource return count changed: " + returns.size());
        }
        for (AbstractInsnNode areturn : returns) {
            // The Either stays on the operand stack across the ()V call: no stack or frame change.
            load.instructions.insertBefore(areturn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, STARTUP_SCHEDULER, "datapackResourceDecoded", "()V", false));
        }
        write(node, root);
        System.out.println("Patched 26.3 RegistryLoadTask datapack decode with browser startup yields");
    }
}
