package dev.gaius.tools.patches.storage;

import static dev.gaius.tools.patches.support.ModernPatchSupport.JDK_COMPAT;
import static dev.gaius.tools.patches.support.ModernPatchSupport.find;
import static dev.gaius.tools.patches.support.ModernPatchSupport.nextOpcode;
import static dev.gaius.tools.patches.support.ModernPatchSupport.read;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replace;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replaceBoolean;
import static dev.gaius.tools.patches.support.ModernPatchSupport.replaceCall;
import static dev.gaius.tools.patches.support.ModernPatchSupport.requireOne;
import static dev.gaius.tools.patches.support.ModernPatchSupport.write;

import java.io.IOException;
import java.nio.file.Path;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The storage domain of the Minecraft262BrowserPatcher step, the {@code modern} patch set arm
 * ({@code patches/storage}): the region file storage cache, structure template
 * sources and the file fixer's file access, hard links and copy-on-write file system.
 *
 * <p>{@code Minecraft262BrowserPatcher.main} registers these patches in the chain order under
 * their historical ids ({@code Minecraft262BrowserPatcher.<method>}); the ASM helpers that other
 * domains of that step share live in ModernPatchSupport and are imported statically.
 */
public final class ModernStoragePatches {
    private ModernStoragePatches() {
    }

    private static final int BROWSER_REGION_FILE_CACHE_SIZE = 16;

    public static void patchRegionFileStorageCache(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/world/level/chunk/storage/RegionFileStorage";
        ClassNode node = read(jar, owner + ".class");
        MethodNode cache = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("cache")
                    && method.desc.equals("(JLjava/util/Optional;)V")) {
                cache = method;
            }
        }
        if (cache != null) {
            // 26.3: getRegionFile(ChunkPos, boolean create) caches both open files and
            // Optional.empty() for absent regions through cache(long, Optional), which evicts
            // (and closes) the oldest entry above MAX_CACHE_SIZE.
            int limits = 0;
            for (AbstractInsnNode instruction : cache.instructions.toArray()) {
                if (instruction instanceof IntInsnNode integer
                        && integer.getOpcode() == Opcodes.SIPUSH
                        && integer.operand == 256) {
                    cache.instructions.set(
                            integer,
                            new IntInsnNode(Opcodes.BIPUSH, BROWSER_REGION_FILE_CACHE_SIZE));
                    limits++;
                }
            }
            requireOne("RegionFileStorage.cache 256-entry limit", limits);
            int constants = 0;
            for (FieldNode field : node.fields) {
                if (field.name.equals("MAX_CACHE_SIZE")
                        && field.desc.equals("I")
                        && Integer.valueOf(256).equals(field.value)) {
                    field.value = BROWSER_REGION_FILE_CACHE_SIZE;
                    constants++;
                }
            }
            requireOne("RegionFileStorage MAX_CACHE_SIZE constant", constants);
            write(node, root.resolve(owner + ".class"));
            System.out.println("Bounded RegionFileStorage.cache(long, Optional) to "
                    + BROWSER_REGION_FILE_CACHE_SIZE + " regions");
            return;
        }
        MethodNode getRegionFile = find(
                node,
                "getRegionFile",
                "(Lnet/minecraft/world/level/ChunkPos;)"
                        + "Lnet/minecraft/world/level/chunk/storage/RegionFile;");

        int limits = 0;
        for (AbstractInsnNode instruction : getRegionFile.instructions.toArray()) {
            if (instruction instanceof IntInsnNode integer
                    && integer.getOpcode() == Opcodes.SIPUSH
                    && integer.operand == 256) {
                getRegionFile.instructions.set(
                        integer,
                        new IntInsnNode(
                                Opcodes.BIPUSH, BROWSER_REGION_FILE_CACHE_SIZE));
                limits++;
            }
        }
        requireOne("RegionFileStorage 256-entry cache limit", limits);

        int constants = 0;
        for (FieldNode field : node.fields) {
            if (field.name.equals("MAX_CACHE_SIZE")
                    && field.desc.equals("I")
                    && Integer.valueOf(256).equals(field.value)) {
                field.value = BROWSER_REGION_FILE_CACHE_SIZE;
                constants++;
            }
        }
        requireOne("RegionFileStorage MAX_CACHE_SIZE constant", constants);

        write(node, root.resolve(owner + ".class"));
        System.out.println(
                "Bounded Minecraft 26.2 RegionFileStorage cache to "
                        + BROWSER_REGION_FILE_CACHE_SIZE + " open files");
    }

    public static void patchTemplateSource(String jar, Path root) throws IOException {
        String owner =
                "net/minecraft/world/level/levelgen/structure/templatesystem/loader/TemplateSource";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "readTextStructure",
                "(Ljava/io/InputStream;)Lnet/minecraft/nbt/CompoundTag;");
        int replaced = replaceCall(
                method,
                Opcodes.INVOKEVIRTUAL,
                "java/io/Reader",
                "readAllAsString",
                "()Ljava/lang/String;",
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "readAll",
                "(Ljava/io/Reader;)Ljava/lang/String;");
        requireOne("TemplateSource Reader.readAllAsString", replaced);
        write(node, root.resolve(owner + ".class"));
    }

    public static void patchFileFixAccess(String jar, Path root) throws IOException {
        String providerOwner = "net/minecraft/util/filefix/access/FileAccessProvider";
        ClassNode provider = read(jar, providerOwner + ".class");
        boolean removedField = provider.fields.removeIf(field -> field.name.equals("baseDirectory")
                && field.desc.equals("Ljava/lang/ScopedValue;"));
        if (!removedField) {
            throw new IllegalStateException("FileAccessProvider ScopedValue field was not found");
        }
        provider.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "gaius$baseDirectory",
                "Ljava/nio/file/Path;",
                null,
                null));
        MethodNode constructor = find(provider, "<init>", "(I)V");
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        constructorCode.add(new InsnNode(Opcodes.DUP));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, providerOwner, "accessedFiles", "Ljava/util/List;"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, providerOwner, "dataVersion", "I"));
        constructorCode.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, constructorCode, 3, 2);

        provider.methods.remove(find(provider, "baseDirectory", "()Ljava/lang/ScopedValue;"));
        MethodNode setter = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$setBaseDirectory",
                "(Ljava/nio/file/Path;)V",
                null,
                null);
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        setter.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, providerOwner, "gaius$baseDirectory", "Ljava/nio/file/Path;"));
        setter.instructions.add(new InsnNode(Opcodes.RETURN));
        setter.maxStack = 2;
        setter.maxLocals = 2;
        provider.methods.add(setter);
        MethodNode getter = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$getBaseDirectory",
                "()Ljava/nio/file/Path;",
                null,
                null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, providerOwner, "gaius$baseDirectory", "Ljava/nio/file/Path;"));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        getter.maxStack = 1;
        getter.maxLocals = 1;
        provider.methods.add(getter);
        write(provider, root.resolve(providerOwner + ".class"));

        String accessOwner = "net/minecraft/util/filefix/access/FileAccess";
        ClassNode access = read(jar, accessOwner + ".class");
        MethodNode get = find(access, "get", "()Ljava/util/List;");
        boolean patchedGet = false;
        for (AbstractInsnNode instruction = get.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(providerOwner)
                    || !call.name.equals("baseDirectory")
                    || !call.desc.equals("()Ljava/lang/ScopedValue;")) {
                continue;
            }
            AbstractInsnNode scopedGet = nextOpcode(call);
            AbstractInsnNode pathCast = nextOpcode(scopedGet);
            if (!(scopedGet instanceof MethodInsnNode getCall)
                    || !getCall.owner.equals("java/lang/ScopedValue")
                    || !getCall.name.equals("get")
                    || !getCall.desc.equals("()Ljava/lang/Object;")
                    || !(pathCast instanceof TypeInsnNode cast)
                    || cast.getOpcode() != Opcodes.CHECKCAST
                    || !cast.desc.equals("java/nio/file/Path")) {
                throw new IllegalStateException("FileAccess ScopedValue read shape changed");
            }
            call.name = "gaius$getBaseDirectory";
            call.desc = "()Ljava/nio/file/Path;";
            get.instructions.remove(scopedGet);
            get.instructions.remove(pathCast);
            patchedGet = true;
            break;
        }
        if (!patchedGet) {
            throw new IllegalStateException("FileAccess base-directory read was not found");
        }
        write(access, root.resolve(accessOwner + ".class"));

        String modifyOwner = "net/minecraft/util/filefix/operations/ModifyContent";
        ClassNode modify = read(jar, modifyOwner + ".class");
        MethodNode fix = find(modify, "fix",
                "(Ljava/nio/file/Path;Lnet/minecraft/util/worldupdate/UpgradeProgress;)V");
        InsnList fixCode = new InsnList();
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, modifyOwner, "fileAccessProvider",
                "L" + providerOwner + ";"));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fixCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                providerOwner,
                "gaius$setBaseDirectory",
                "(Ljava/nio/file/Path;)V",
                false));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, modifyOwner, "fixFunction",
                "Lnet/minecraft/util/filefix/operations/ModifyContent$FixFunction;"));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fixCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/util/filefix/operations/ModifyContent$FixFunction",
                "run",
                "(Lnet/minecraft/util/worldupdate/UpgradeProgress;)V",
                true));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, modifyOwner, "fileAccessProvider",
                "L" + providerOwner + ";"));
        fixCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, providerOwner, "close", "()V", false));
        fixCode.add(new InsnNode(Opcodes.RETURN));
        replace(fix, fixCode, 2, 3);
        write(modify, root.resolve(modifyOwner + ".class"));
        System.out.println("Replaced Minecraft 26.2 ScopedValue file-fix context");
    }

    public static void patchCopyOnWriteFileSystem(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/filefix/virtualfilesystem/CopyOnWriteFileSystem";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "hardLinkFiles", "(Ljava/util/List;)V");
        int replacements = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/nio/file/Files")
                    && call.name.equals("createLink")
                    && call.desc.equals(
                            "(Ljava/nio/file/Path;Ljava/nio/file/Path;)Ljava/nio/file/Path;")) {
                InsnList prepareCopy = new InsnList();
                prepareCopy.add(new InsnNode(Opcodes.SWAP));
                prepareCopy.add(new InsnNode(Opcodes.ICONST_0));
                prepareCopy.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/nio/file/CopyOption"));
                method.instructions.insertBefore(call, prepareCopy);
                call.name = "copy";
                call.desc = "(Ljava/nio/file/Path;Ljava/nio/file/Path;"
                        + "[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;";
                replacements++;
            }
        }
        if (replacements == 0) {
            throw new IllegalStateException("CopyOnWriteFileSystem hard-link calls were not found");
        }
        method.maxStack = Math.max(method.maxStack, 3);
        write(node, root.resolve(owner + ".class"));
    }

    public static void patchFileFixerUpperHardLinks(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/filefix/FileFixerUpper";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "supportsHardLinks", "(Ljava/nio/file/Path;)Z");
        replaceBoolean(method, false);
        write(node, root.resolve(owner + ".class"));
    }

    public static void patchCopyOnWriteProvider(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/filefix/virtualfilesystem/CopyOnWriteFSProvider";
        ClassNode node = read(jar, owner + ".class");
        MethodNode move = find(node, "move",
                "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)V");
        TypeInsnNode unsupported = null;
        for (AbstractInsnNode instruction = move.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/nio/file/AtomicMoveNotSupportedException")) {
                unsupported = type;
                break;
            }
        }
        if (unsupported == null) {
            throw new IllegalStateException("CopyOnWriteFSProvider atomic-move branch was not found");
        }
        JumpInsnNode branch = null;
        for (AbstractInsnNode instruction = unsupported.getPrevious(); instruction != null;
                instruction = instruction.getPrevious()) {
            if (instruction instanceof JumpInsnNode jump) {
                branch = jump;
                break;
            }
        }
        if (branch == null) {
            throw new IllegalStateException("CopyOnWriteFSProvider atomic-move jump was not found");
        }
        AbstractInsnNode cursor = branch.getNext();
        while (cursor != null && cursor != branch.label) {
            AbstractInsnNode next = cursor.getNext();
            move.instructions.remove(cursor);
            cursor = next;
        }
        move.instructions.set(branch, new InsnNode(Opcodes.POP));

        MethodNode initializer = find(node, "<clinit>", "()V");
        InsnList initializerCode = new InsnList();
        initializerCode.add(new InsnNode(Opcodes.ACONST_NULL));
        initializerCode.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                owner,
                "DUMMY_DIRECTORY_VIEW",
                "Ljava/nio/file/attribute/BasicFileAttributeView;"));
        initializerCode.add(new InsnNode(Opcodes.RETURN));
        replace(initializer, initializerCode, 1, 0);
        write(node, root.resolve(owner + ".class"));
    }
}
