package dev.gaius.tools.patches.resources;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.findNullable;
import static dev.gaius.tools.patches.support.ClientPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;
import static dev.gaius.tools.patches.support.ClientPatchSupport.replace;
import static dev.gaius.tools.patches.support.ClientPatchSupport.write;
import static dev.gaius.tools.patches.support.ClientPatchSupport.writeComputeFrames;

import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The resources domain of the MinecraftClientPatcher step ({@code patches/resources}):
 * the vanilla pack, file packs and asset index served from the browser asset archive, the atlas
 * source fallbacks, and the downloads of server packs and skins through the browser bridge.
 *
 * <p>{@code MinecraftClientPatcher.main} registers these patches in the chain order under their
 * historical ids ({@code MinecraftClientPatcher.<method>}); the ASM helpers that other domains
 * of that step share live in ClientPatchSupport and are imported statically.
 */
public final class ResourcePatches {
    private ResourcePatches() {
    }

    public static void patchDownloadedPackSourceBrowserRecovery(String jar, Path output)
            throws IOException {
        ClassNode node = read(
                jar, "net/minecraft/client/resources/server/DownloadedPackSource.class");
        MethodNode cleanup = find(node, "cleanupAfterDisconnect", "()V");
        if (cleanup == null) {
            throw new IOException("DownloadedPackSource cleanupAfterDisconnect was not found");
        }
        MethodInsnNode popAll = null;
        for (AbstractInsnNode instruction = cleanup.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(
                            "net/minecraft/client/resources/server/ServerPackManager")
                    && call.name.equals("popAll")
                    && call.desc.equals("()V")) {
                popAll = call;
                break;
            }
        }
        if (popAll == null) {
            throw new IOException("DownloadedPackSource popAll disconnect cleanup was not found");
        }
        AbstractInsnNode popAllStart = popAll.getPrevious();
        while (popAllStart != null && popAllStart.getOpcode() < 0) {
            popAllStart = popAllStart.getPrevious();
        }
        if (!(popAllStart instanceof FieldInsnNode managerLoad)
                || managerLoad.getOpcode() != Opcodes.GETFIELD) {
            throw new IOException("DownloadedPackSource manager load before popAll was not found");
        }
        popAllStart = managerLoad.getPrevious();
        while (popAllStart != null && popAllStart.getOpcode() < 0) {
            popAllStart = popAllStart.getPrevious();
        }
        if (!(popAllStart instanceof VarInsnNode receiverLoad)
                || receiverLoad.getOpcode() != Opcodes.ALOAD) {
            throw new IOException("DownloadedPackSource receiver before popAll was not found");
        }

        LabelNode vanillaCleanup = new LabelNode();
        LabelNode afterPopAll = new LabelNode();
        InsnList preservePack = new InsnList();
        preservePack.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserServerPackReuse",
                "keepServerPackForRecovery",
                "()Z",
                false));
        preservePack.add(new JumpInsnNode(Opcodes.IFEQ, vanillaCleanup));
        preservePack.add(new JumpInsnNode(Opcodes.GOTO, afterPopAll));
        preservePack.add(vanillaCleanup);
        cleanup.instructions.insertBefore(popAllStart, preservePack);
        cleanup.instructions.insert(popAll, afterPopAll);
        writeComputeFrames(node, output);
    }

    public static void patchVanillaPackResourcesBuilder(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/packs/VanillaPackResourcesBuilder.class");
        MethodNode method = find(node, "lambda$static$1", "()Lcom/google/common/collect/ImmutableMap;");
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/google/common/collect/ImmutableMap",
                "of",
                "()Lcom/google/common/collect/ImmutableMap;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 0);
        write(node, output);
    }

    public static void patchFilePackResourcesBrowserIndex(String jar, Path root) throws IOException {
        String owner = "net/minecraft/server/packs/FilePackResources";
        String sharedOwner = owner + "$SharedZipFileAccess";
        String helper = "dev/gaius/browser/BrowserZipResourceIndex";
        String indexDescriptor = "L" + helper + ";";
        ClassNode node = read(jar, owner + ".class");
        ClassNode shared = read(jar, sharedOwner + ".class");
        MethodNode list = find(node, "listResources",
                "(Lnet/minecraft/server/packs/PackType;Ljava/lang/String;Ljava/lang/String;"
                        + "Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V");
        MethodInsnNode enumerationCall = null;
        VarInsnNode enumerationStore = null;
        int zipLocal = -1;
        int prefixLocal = -1;
        for (AbstractInsnNode instruction : list.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("java/util/zip/ZipFile") && call.name.equals("entries")
                    && call.desc.equals("()Ljava/util/Enumeration;")) {
                AbstractInsnNode load = previousOpcode(call);
                AbstractInsnNode store = nextOpcode(call);
                if (enumerationCall != null || !(load instanceof VarInsnNode zip)
                        || zip.getOpcode() != Opcodes.ALOAD || !(store instanceof VarInsnNode entries)
                        || entries.getOpcode() != Opcodes.ASTORE) {
                    throw new IllegalStateException("Unexpected file pack ZIP enumeration shape");
                }
                enumerationCall = call;
                enumerationStore = entries;
                zipLocal = zip.var;
            }
            if (call.owner.equals("java/lang/String") && call.name.equals("startsWith")
                    && call.desc.equals("(Ljava/lang/String;)Z")) {
                AbstractInsnNode load = previousOpcode(call);
                if (prefixLocal != -1 || !(load instanceof VarInsnNode prefix)
                        || prefix.getOpcode() != Opcodes.ALOAD) {
                    throw new IllegalStateException("Unexpected file pack prefix predicate shape");
                }
                prefixLocal = prefix.var;
            }
        }
        if (enumerationCall == null || prefixLocal == -1) {
            throw new IllegalStateException("File pack ZIP enumeration or prefix predicate missing");
        }
        VarInsnNode prefixStore = null;
        for (AbstractInsnNode instruction = enumerationStore.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE
                    && store.var == prefixLocal) {
                prefixStore = store;
                break;
            }
        }
        if (prefixStore == null) throw new IllegalStateException("File pack full prefix store missing");

        // Delay enumeration until the full overlay/type/namespace/path prefix is available.
        // Keep vanilla's validation, relative path conversion and lazy input suppliers intact.
        list.instructions.insertBefore(enumerationCall, new InsnNode(Opcodes.POP));
        list.instructions.set(enumerationCall, new InsnNode(Opcodes.ACONST_NULL));
        shared.fields.add(new FieldNode(0, "browserResourceIndex", indexDescriptor, null, null));
        InsnList query = new InsnList();
        query.add(new VarInsnNode(Opcodes.ALOAD, 0));
        query.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "zipFileAccess", "L" + sharedOwner + ";"));
        query.add(new InsnNode(Opcodes.DUP));
        query.add(new FieldInsnNode(Opcodes.GETFIELD, sharedOwner, "browserResourceIndex", indexDescriptor));
        query.add(new VarInsnNode(Opcodes.ALOAD, zipLocal));
        query.add(new MethodInsnNode(Opcodes.INVOKESTATIC, helper, "forZip",
                "(" + indexDescriptor + "Ljava/util/zip/ZipFile;)" + indexDescriptor, false));
        query.add(new InsnNode(Opcodes.DUP_X1));
        query.add(new FieldInsnNode(Opcodes.PUTFIELD, sharedOwner, "browserResourceIndex", indexDescriptor));
        query.add(new VarInsnNode(Opcodes.ALOAD, prefixLocal));
        query.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, helper, "entries",
                "(Ljava/lang/String;)Ljava/util/List;", false));
        query.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "enumeration",
                "(Ljava/util/Collection;)Ljava/util/Enumeration;", false));
        query.add(new VarInsnNode(Opcodes.ASTORE, enumerationStore.var));
        list.instructions.insert(prefixStore, query);
        list.maxStack = Math.max(list.maxStack, 3);

        MethodNode close = find(shared, "close", "()V");
        InsnList clear = new InsnList();
        clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
        clear.add(new InsnNode(Opcodes.ACONST_NULL));
        clear.add(new FieldInsnNode(Opcodes.PUTFIELD, sharedOwner, "browserResourceIndex", indexDescriptor));
        close.instructions.insert(clear);
        close.maxStack = Math.max(close.maxStack, 2);
        write(node, root.resolve(owner + ".class"));
        write(shared, root.resolve(sharedOwner + ".class"));
    }

    public static void patchFilePackResourcesBrowserAtlasOverlays(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/server/packs/FilePackResources$FileResourcesSupplier.class");
        String openArguments = "(Lnet/minecraft/server/packs/PackLocationInfo;"
                + "Lnet/minecraft/server/packs/repository/Pack$Metadata;)";
        MethodNode openFull = findNullable(node, "openFull",
                openArguments + "Lnet/minecraft/server/packs/PackResources;");
        if (openFull == null) {
            // 26.3: Pack$ResourcesSupplier.openFull became openResources, which returns the
            // pack and its overlays as a Stream; the overlay list lookup inside is unchanged.
            openFull = find(node, "openResources", openArguments + "Ljava/util/stream/Stream;");
        }
        boolean patched = false;
        for (AbstractInsnNode instruction = openFull.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals("net/minecraft/server/packs/repository/Pack$Metadata")
                    || !call.name.equals("overlays")
                    || !call.desc.equals("()Ljava/util/List;")) {
                continue;
            }
            AbstractInsnNode store = nextOpcode(instruction);
            if (!(store instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ASTORE) {
                throw new IllegalStateException("File pack overlay list was not stored after lookup");
            }
            InsnList compatibility = new InsnList();
            compatibility.add(new VarInsnNode(Opcodes.ALOAD, 0));
            compatibility.add(new FieldInsnNode(
                    Opcodes.GETFIELD, node.name, "content", "Ljava/io/File;"));
            compatibility.add(new VarInsnNode(Opcodes.ALOAD, variable.var));
            compatibility.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserPackOverlayCompat",
                    "mergeSafeAtlasOverlays",
                    "(Ljava/io/File;Ljava/util/List;)Ljava/util/List;",
                    false));
            compatibility.add(new VarInsnNode(Opcodes.ASTORE, variable.var));
            openFull.instructions.insert(store, compatibility);
            patched = true;
            break;
        }
        if (!patched) {
            throw new IllegalStateException("File pack overlay metadata lookup was not found");
        }
        openFull.maxStack = Math.max(openFull.maxStack, 2);
        write(node, output);
    }

    public static void patchSingleFileBrowserAtlasFallback(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/renderer/texture/atlas/sources/SingleFile.class");
        MethodNode run = find(node, "run",
                "(Lnet/minecraft/server/packs/resources/ResourceManager;"
                        + "Lnet/minecraft/client/renderer/texture/atlas/SpriteSource$Output;)V");
        boolean patched = false;
        for (AbstractInsnNode instruction = run.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("net/minecraft/server/packs/resources/ResourceManager")
                    || !call.name.equals("getResource")
                    || !call.desc.equals("(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;")) {
                continue;
            }
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = "dev/gaius/browser/BrowserAtlasResourceFallback";
            call.name = "getResource";
            call.desc = "(Lnet/minecraft/server/packs/resources/ResourceManager;"
                    + "Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;";
            call.itf = false;
            patched = true;
            break;
        }
        if (!patched) {
            throw new IllegalStateException("SingleFile atlas resource lookup was not found");
        }
        write(node, output);
    }

    public static void patchIndexedAssetSourceBrowserNoop(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/resources/IndexedAssetSource.class");
        MethodNode method = find(node, "createIndexFs",
                "(Ljava/nio/file/Path;Ljava/lang/String;)Ljava/nio/file/Path;");
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/server/packs/linkfs/LinkFileSystem",
                "builder",
                "()Lnet/minecraft/server/packs/linkfs/LinkFileSystem$Builder;",
                false));
        code.add(new LdcInsnNode("browser-assets"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/server/packs/linkfs/LinkFileSystem$Builder",
                "build",
                "(Ljava/lang/String;)Ljava/nio/file/FileSystem;",
                false));
        code.add(new LdcInsnNode("/"));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/nio/file/FileSystem",
                "getPath",
                "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 3, 2);
        write(node, output);
    }

    public static void patchSkinTextureDownloader(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/renderer/texture/SkinTextureDownloader.class");
        MethodNode downloadSkin = find(
                node,
                "downloadSkin",
                "(Ljava/nio/file/Path;Ljava/lang/String;)"
                        + "Lcom/mojang/blaze3d/platform/NativeImage;");
        InsnList code = new InsnList();
        LabelNode normalSkin = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserUploadedSkin", "isUploadedSkin",
                "(Ljava/lang/String;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, normalSkin));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserUploadedSkin", "readUploadedSkin",
                "(Ljava/lang/String;)Lcom/mojang/blaze3d/platform/NativeImage;", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(normalSkin);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserHttpProxy",
                "proxyTexture",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        downloadSkin.instructions.insert(code);
        downloadSkin.maxStack = Math.max(downloadSkin.maxStack, 1);
        boolean removedJavaProxy = false;
        for (var instruction = downloadSkin.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("java/net/URL")
                    && call.name.equals("openConnection")
                    && call.desc.equals("(Ljava/net/Proxy;)Ljava/net/URLConnection;")) {
                downloadSkin.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                call.desc = "()Ljava/net/URLConnection;";
                removedJavaProxy = true;
            }
        }
        if (!removedJavaProxy) {
            throw new IllegalStateException(
                    "SkinTextureDownloader browser Java Proxy patch point was not found");
        }
        write(node, output);
    }

    /**
     * Remote players only render skins whose PlayerSkin is secure, and
     * SkinManager derives that from the Mojang signature. Uploaded data:image/png
     * skins are never signed, so other players always fell back to a default
     * skin. Route the one signature read through BrowserUploadedSkin, which
     * trusts only a lone uploaded skin and keeps every other texture set's real
     * signature state.
     */
    public static void patchSkinManagerUploadedSkinSecurity(String jar, Path output)
            throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/resources/SkinManager.class");
        MethodNode build = node.methods.stream()
                .filter(method -> method.name.startsWith("lambda$registerTextures$")
                        && method.desc.endsWith(
                                "Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;"
                                        + "Ljava/lang/Void;)"
                                        + "Lnet/minecraft/world/entity/player/PlayerSkin;"))
                .findFirst()
                .orElse(null);
        if (build == null) {
            // Security patch: an uploaded data: skin must never be treated as a signed Mojang
            // texture. Every supported profile (1.21.11, 26.2, 26.3) has this lambda, so a
            // missing shape fails the build instead of leaving SkinManager unpatched.
            throw new IllegalStateException(
                    "SkinManager uploaded skin security patch point was not found");
        }
        int redirected = 0;
        for (var instruction = build.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("com/mojang/authlib/minecraft/MinecraftProfileTextures")
                    && call.name.equals("signatureState")
                    && call.desc.equals("()Lcom/mojang/authlib/SignatureState;")) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = "dev/gaius/browser/BrowserUploadedSkin";
                call.name = "skinSignatureState";
                call.desc = "(Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;)"
                        + "Lcom/mojang/authlib/SignatureState;";
                call.itf = false;
                redirected++;
            }
        }
        if (redirected != 1) {
            throw new IllegalStateException(
                    "SkinManager uploaded skin security patch point count was " + redirected);
        }
        write(node, output);
    }

    /**
     * TeaVM's browser class library does not provide a background thread for the vanilla
     * resource-pack queue.  DownloadQueue otherwise submits its CompletableFuture supplier to
     * a ConsecutiveExecutor backed by Util.nonCriticalIoPool(); that executor can remain pending
     * forever in the browser, so HttpUtil.downloadFile (and the browser resource-pack proxy) is
     * never reached.  Keep the vanilla queue/future semantics, but run its executor through the
     * cooperative browser pump.  The helper bypasses itself in the integrated-server Worker.
     */
    public static void patchDownloadQueueBrowser(String jar, Path output) throws IOException {
        String owner = "net/minecraft/server/packs/DownloadQueue";
        ClassNode node = read(jar, owner + ".class");
        MethodNode constructor = find(node, "<init>", "(Ljava/nio/file/Path;)V");
        int patched = 0;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("net/minecraft/util/Util")
                    || !call.name.equals("nonCriticalIoPool")
                    || !call.desc.equals("()Lnet/minecraft/TracingExecutor;")) {
                continue;
            }
            constructor.instructions.insert(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserCooperativeExecutor",
                    "defer",
                    "(Ljava/util/concurrent/Executor;)Ljava/util/concurrent/Executor;",
                    false));
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "DownloadQueue browser executor patch point changed: " + patched);
        }
        write(node, output);
    }
}
