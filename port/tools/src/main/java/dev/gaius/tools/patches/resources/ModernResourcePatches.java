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
 * executor, and the model bake's interners over a plain map.
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

    static final String MODEL_BAKERY_INTERNER = "net/minecraft/client/resources/model/ModelBakery$InternerImpl";
    static final String MODEL_BAKER_INTERNER = "net/minecraft/client/resources/model/ModelBaker$Interner";
    static final String GUAVA_INTERNERS = "com/google/common/collect/Interners";
    static final String BROWSER_INTERNER = "dev/gaius/browser/BrowserStrongInterner";
    static final String INTERNER_FACTORY_DESC = "()Lcom/google/common/collect/Interner;";

    /**
     * The model bake's interner keeps a plain map instead of Guava's concurrent one. The
     * constructor of {@code ModelBakery$InternerImpl} creates its two interners (vectors, material
     * infos) with {@code Interners.newStrongInterner()}; both call sites become
     * {@code BrowserStrongInterner.create()}, a HashMap-backed {@code Interner} that returns the
     * first instance equal to the sample, as Guava's strong interner does (same hashCode/equals,
     * same equals direction, null rejected, nothing removed). Guava's segments, locks and entry
     * objects only matter for real threads; in the browser the bake's tasks are coroutines of the
     * one main thread, and intern runs hashCode/equals of records and vectors only, so no task can
     * suspend inside it. Everything else in the class stays vanilla; a class without exactly these
     * two call sites (and no other use of Interners) fails the patch, which disables it.
     */
    public static void patchModelBakeryInterner(String jar, Path root) throws IOException {
        Path output = root.resolve(MODEL_BAKERY_INTERNER + ".class");
        ClassNode node;
        if (Files.exists(output)) {
            node = new ClassNode();
            new ClassReader(Files.readAllBytes(output)).accept(node, 0);
        } else {
            node = read(jar, MODEL_BAKERY_INTERNER + ".class");
        }
        if (!node.interfaces.contains(MODEL_BAKER_INTERNER)) {
            throw new IllegalStateException(MODEL_BAKERY_INTERNER + " does not implement "
                    + MODEL_BAKER_INTERNER);
        }
        int vanilla = 0;
        int browser = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    continue;
                }
                boolean guava = call.owner.equals(GUAVA_INTERNERS);
                boolean factory = call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.desc.equals(INTERNER_FACTORY_DESC) && !call.itf;
                boolean constructor = method.name.equals("<init>") && method.desc.equals("()V");
                if (guava && factory && constructor && call.name.equals("newStrongInterner")) {
                    call.owner = BROWSER_INTERNER;
                    call.name = "create";
                    vanilla++;
                } else if (guava) {
                    throw new IllegalStateException(MODEL_BAKERY_INTERNER + "." + method.name
                            + method.desc + " calls Interners." + call.name + call.desc);
                } else if (call.owner.equals(BROWSER_INTERNER) && factory && constructor
                        && call.name.equals("create")) {
                    browser++;
                }
            }
        }
        if (vanilla + browser != 2 || (vanilla != 0 && browser != 0)) {
            throw new IllegalStateException(MODEL_BAKERY_INTERNER
                    + " interner call sites vanilla=" + vanilla + " browser=" + browser);
        }
        if (vanilla == 0) {
            return;
        }
        write(node, output);
        System.out.println("Patched ModelBakery$InternerImpl with the browser strong interner");
    }
}
