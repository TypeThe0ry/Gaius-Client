package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Keeps Mojang's blocked-server provider non-blocking in browser builds. */
public final class PatchyBrowserPatcher {
    private static final String ENTRY = "com/mojang/patchy/MojangBlockListSupplier.class";
    private static final String BLOCKED_SERVERS_URL =
            "https://sessionserver.mojang.com/blockedservers";

    private PatchyBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: PatchyBrowserPatcher INPUT_JAR OUTPUT_CLASS");
        }

        byte[] input;
        try (ZipFile jar = new ZipFile(args[0])) {
            var entry = jar.getEntry(ENTRY);
            if (entry == null) {
                throw new IllegalStateException(ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(entry)) {
                input = stream.readAllBytes();
            }
        }

        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        MethodNode createBlockList = node.methods.stream()
                .filter(method -> method.name.equals("createBlockList")
                        && method.desc.equals("()Ljava/util/function/Predicate;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Mojang block-list factory was not found"));
        boolean hasBlockedServersUrl = false;
        boolean readsResponseBody = false;
        for (var instruction = createBlockList.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof LdcInsnNode constant
                    && BLOCKED_SERVERS_URL.equals(constant.cst)) {
                hasBlockedServersUrl = true;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("java/net/URLConnection")
                    && call.name.equals("getInputStream")
                    && call.desc.equals("()Ljava/io/InputStream;")) {
                readsResponseBody = true;
            }
        }
        if (!hasBlockedServersUrl || !readsResponseBody) {
            throw new IllegalStateException(
                    "Mojang block-list factory no longer matches the expected URL read path");
        }
        // ServerNameResolver.DEFAULT is initialized before the browser's first event-loop turn.
        // A URLConnection read here would suspend TeaVM from inside that class initializer and
        // leave the resolver half-initialized. AddressCheck.createFromService filters null
        // provider results, so the browser intentionally keeps the provider disabled and uses
        // its normal allow-all fallback. Desktop builds still use the unmodified library jar.
        createBlockList.instructions.clear();
        createBlockList.tryCatchBlocks.clear();
        createBlockList.localVariables.clear();
        createBlockList.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        createBlockList.instructions.add(new InsnNode(Opcodes.ARETURN));
        createBlockList.maxStack = 1;
        createBlockList.maxLocals = 1;

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
