package dev.gaius.tools.patches.resources;

import static dev.gaius.tools.patches.support.ModernPatchSupport.read;
import static dev.gaius.tools.patches.support.ModernPatchSupport.write;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The resources domain of the Minecraft262BrowserPatcher step, the {@code modern} patch set arm
 * ({@code patches/resources}): the download queue on the browser's cooperative
 * executor.
 *
 * <p>{@code Minecraft262BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft262BrowserPatcher.<method>}); the ASM helpers that other
 * domains of that step share live in ModernPatchSupport and are imported statically.
 */
public final class ModernResourcePatches {
    private ModernResourcePatches() {
    }

    /**
     * Keep resource-pack downloads on a browser-yielding executor.  In 26.2
     * DownloadQueue owns a ConsecutiveExecutor backed by Util.nonCriticalIoPool().
     * TeaVM's browser implementation executes that pool inline; a 60&nbsp;MiB
     * resource-pack therefore monopolises the Worker and the completion stage
     * never reaches DownloadedPackSource.  Wrapping the pool with the same
     * bounded cooperative adapter used by the integrated server preserves the
     * CompletableFuture chain while yielding between download tasks.
     */
    public static void patchDownloadQueueBrowserCooperativeExecutor(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/server/packs/DownloadQueue";
        Path output = root.resolve(owner + ".class");
        ClassNode node;
        if (Files.exists(output)) {
            node = new ClassNode();
            new ClassReader(Files.readAllBytes(output)).accept(node, 0);
        } else {
            node = read(jar, owner + ".class");
        }

        int patched = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")
                    || !method.desc.equals("(Ljava/nio/file/Path;)V")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !call.owner.equals("net/minecraft/util/Util")
                        || !call.name.equals("nonCriticalIoPool")
                        || !call.desc.equals("()Lnet/minecraft/TracingExecutor;")) {
                    continue;
                }
                AbstractInsnNode next = call.getNext();
                if (next instanceof MethodInsnNode existing
                        && existing.getOpcode() == Opcodes.INVOKESTATIC
                        && existing.owner.equals("dev/gaius/browser/BrowserCooperativeExecutor")
                        && existing.name.equals("defer")
                        && existing.desc.equals("(Ljava/util/concurrent/Executor;)"
                                + "Ljava/util/concurrent/Executor;")) {
                    patched++;
                    continue;
                }
                method.instructions.insert(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/gaius/browser/BrowserCooperativeExecutor",
                        "defer",
                        "(Ljava/util/concurrent/Executor;)Ljava/util/concurrent/Executor;",
                        false));
                method.maxStack = Math.max(method.maxStack, 1);
                patched++;
            }
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "26.2 DownloadQueue cooperative executor patch points=" + patched);
        }
        write(node, output);
        System.out.println("Patched 26.2 DownloadQueue with browser cooperative executor");
    }
}
