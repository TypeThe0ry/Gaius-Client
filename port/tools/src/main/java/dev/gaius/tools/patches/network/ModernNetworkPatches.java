package dev.gaius.tools.patches.network;

import static dev.gaius.tools.patches.support.ModernPatchSupport.find;
import static dev.gaius.tools.patches.support.ModernPatchSupport.read;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireOne;
import static dev.gaius.tools.patches.support.ModernPatchSupport.write;

import dev.gaius.tools.ModernSymbols;
import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The network domain of the Minecraft262BrowserPatcher step, the {@code modern} patch set arm
 * ({@code patches/network}): the remote friend list without its network service.
 *
 * <p>{@code Minecraft262BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft262BrowserPatcher.<method>}); the ASM helpers that other
 * domains of that step share live in ModernPatchSupport and are imported statically.
 */
public final class ModernNetworkPatches {
    private ModernNetworkPatches() {
    }

    public static void patchRemoteFriendList(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/gui/screens/social/RemoteFriendListUpdateHandler";
        ClassNode node = read(jar, owner + ".class");
        // authlib 10 (Minecraft 26.3) moved FriendsService from yggdrasil to services; the
        // constructor body (one CopyOnWriteArraySet) is unchanged.
        MethodNode constructor = find(node, "<init>",
                ModernSymbols.cached(jar).authlibDesc("(Lcom/mojang/authlib/yggdrasil/FriendsService;"
                        + "Lnet/minecraft/client/Minecraft;)V"));
        int types = 0;
        int calls = 0;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/util/concurrent/CopyOnWriteArraySet")) {
                type.desc = "java/util/HashSet";
                types++;
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("java/util/concurrent/CopyOnWriteArraySet")
                    && call.name.equals("<init>")
                    && call.desc.equals("()V")) {
                call.owner = "java/util/HashSet";
                calls++;
            }
        }
        requireOne("RemoteFriendListUpdateHandler set allocation", types);
        requireOne("RemoteFriendListUpdateHandler set constructor", calls);
        write(node, root.resolve(owner + ".class"));
    }
}
