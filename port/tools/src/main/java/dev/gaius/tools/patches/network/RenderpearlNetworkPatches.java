package dev.gaius.tools.patches.network;

import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.find;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.read;
import static dev.gaius.tools.patches.support.RenderpearlPatchSupport.write;

import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The network domain of the Minecraft263BrowserPatcher step, the tail of the modern chain for the
 * renderpearl render seam ({@code patches/network}), work package P7a.
 *
 * <ul>
 *   <li>{@link #patchDiscoveryServiceBrowser}: authlib 10 starts the discovery fetch in
 *       {@code MinecraftServicesDiscoveryService.create(Proxy, boolean)} whatever the flag says
 *       (PLAN D7). The one call in {@code Minecraft.<init>} and the one that
 *       {@code MinecraftClientPatcher.patchServerMainDiscoveryServiceBrowser} leaves in the
 *       server {@code Main} are retargeted to {@code BrowserDiscoveryServices.create}, which
 *       returns {@code createOffline} unless the browser session is online.</li>
 * </ul>
 *
 * <p>{@code Minecraft263BrowserPatcher} registers it under its historical id
 * ({@code ServerPatches263.patchDiscoveryServiceBrowser}) with the other patches of that group;
 * its profiles come from the spec. Classes are read and written through
 * {@code RenderpearlPatchSupport}.
 */
public final class RenderpearlNetworkPatches {
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String SERVER_MAIN = "net/minecraft/server/Main";
    static final String DISCOVERY_SERVICE =
            "com/mojang/authlib/services/MinecraftServicesDiscoveryService";
    static final String DISCOVERY_CREATE_DESCRIPTOR =
            "(Ljava/net/Proxy;Z)L" + DISCOVERY_SERVICE + ";";
    static final String DISCOVERY_SERVICES = "dev/gaius/browser/BrowserDiscoveryServices";

    private RenderpearlNetworkPatches() {
    }

    public static void patchDiscoveryServiceBrowser(String jar, Path root) throws IOException {
        retargetDiscoveryCreate(jar, root, MINECRAFT, "<init>",
                "(Lnet/minecraft/client/main/GameConfig;)V");
        retargetDiscoveryCreate(jar, root, SERVER_MAIN, "main", "([Ljava/lang/String;)V");
        System.out.println("Routed 26.3 discovery-service creation through BrowserDiscoveryServices"
                + " (offline sessions and the Worker no longer fetch discovery)");
    }

    private static void retargetDiscoveryCreate(String jar, Path root, String owner, String name,
            String descriptor) throws IOException {
        ClassNode node = read(jar, root, owner);
        MethodNode method = find(node, name, descriptor);
        MethodInsnNode target = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("create")
                    || !call.desc.equals(DISCOVERY_CREATE_DESCRIPTOR)) {
                continue;
            }
            if (call.owner.equals(DISCOVERY_SERVICES)) {
                throw new IllegalStateException(owner + "." + name + " is already patched");
            }
            if (!call.owner.equals(DISCOVERY_SERVICE) || call.getOpcode() != Opcodes.INVOKESTATIC) {
                continue;
            }
            if (target != null) {
                throw new IllegalStateException(owner + "." + name
                        + " calls MinecraftServicesDiscoveryService.create(Proxy, boolean) twice");
            }
            target = call;
        }
        if (target == null) {
            throw new IllegalStateException(owner + "." + name
                    + " no longer calls MinecraftServicesDiscoveryService.create(Proxy, boolean)");
        }
        // Same static descriptor, only the owner changes: stack and frames are unchanged.
        target.owner = DISCOVERY_SERVICES;
        write(node, root);
    }
}
