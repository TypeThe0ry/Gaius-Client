package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes Netty's native-memory and desktop-platform bootstrap paths. */
public final class NettyBrowserPatcher {
    private static final String PLATFORM =
            "io/netty/util/internal/PlatformDependent.class";
    private static final String BUFFER = "io/netty/buffer/ByteBufUtil.class";

    private NettyBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path commonRoot = Path.of(args[3]);
        Path bufferRoot = Path.of(args[4]);
        Path transportRoot = Path.of(args[5]);
        patchPlatform(Path.of(args[0]),
                commonRoot.resolve("io/netty/util/internal/PlatformDependent.class"));
        patchPlatform0(Path.of(args[0]),
                commonRoot.resolve("io/netty/util/internal/PlatformDependent0.class"));
        patchRefCnt(Path.of(args[0]),
                commonRoot.resolve("io/netty/util/internal/RefCnt.class"));
        patchNetUtil(Path.of(args[0]),
                commonRoot.resolve("io/netty/util/NetUtil.class"),
                commonRoot.resolve("io/netty/util/NetUtilInitializations.class"));
        patchEmptyArrays(Path.of(args[0]),
                commonRoot.resolve("io/netty/util/internal/EmptyArrays.class"));
        patchMacAddressUtil(Path.of(args[0]),
                commonRoot.resolve("io/netty/util/internal/MacAddressUtil.class"));
        patchResourceLeakDetector(Path.of(args[0]),
                commonRoot.resolve("io/netty/util/ResourceLeakDetector.class"));
        patchRecycler(Path.of(args[0]),
                commonRoot.resolve("io/netty/util/Recycler.class"));
        patchBuffer(Path.of(args[1]),
                bufferRoot.resolve("io/netty/buffer/ByteBufUtil.class"));
        patchHeapByteBufUtil(Path.of(args[1]),
                bufferRoot.resolve("io/netty/buffer/HeapByteBufUtil.class"));
        patchAbstractByteBufAllocator(Path.of(args[1]),
                bufferRoot.resolve("io/netty/buffer/AbstractByteBufAllocator.class"));
        patchReferenceCountedBuffer(Path.of(args[1]),
                bufferRoot.resolve("io/netty/buffer/AbstractReferenceCountedByteBuf.class"));
        patchUnpooledAllocator(Path.of(args[1]),
                bufferRoot.resolve("io/netty/buffer/UnpooledByteBufAllocator.class"));
        patchLeakAwareAllocator(Path.of(args[1]),
                "io/netty/buffer/PooledByteBufAllocator",
                bufferRoot.resolve("io/netty/buffer/PooledByteBufAllocator.class"));
        patchLeakAwareAllocator(Path.of(args[1]),
                "io/netty/buffer/AdaptiveByteBufAllocator",
                bufferRoot.resolve("io/netty/buffer/AdaptiveByteBufAllocator.class"));
        patchChannelInitializer(Path.of(args[2]),
                transportRoot.resolve("io/netty/channel/ChannelInitializer.class"));
        patchAbstractChannelUnsafe(Path.of(args[2]),
                transportRoot.resolve("io/netty/channel/AbstractChannel$AbstractUnsafe.class"));
        patchBootstrap(Path.of(args[2]),
                transportRoot.resolve("io/netty/bootstrap/Bootstrap.class"));
        patchReflectiveChannelFactory(Path.of(args[2]),
                transportRoot.resolve("io/netty/channel/ReflectiveChannelFactory.class"));
        patchDefaultChannelId(Path.of(args[2]),
                transportRoot.resolve("io/netty/channel/DefaultChannelId.class"));
        patchChannelHandlerMask(Path.of(args[2]),
                transportRoot.resolve("io/netty/channel/ChannelHandlerMask.class"));
    }

    private static void patchPlatform(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, PLATFORM);
        replace(node, "<clinit>", "()V", platformInitializer(node));
        replaceConstant(node, "initializeVarHandle", "()Z", Opcodes.ICONST_0, Opcodes.IRETURN);
        replaceConstant(node, "byteArrayBaseOffset", "()J", Opcodes.LCONST_0, Opcodes.LRETURN);
        replaceConstant(node, "hasDirectBufferNoCleanerConstructor", "()Z",
                Opcodes.ICONST_0, Opcodes.IRETURN);
        replaceAllocateArray(node);
        for (String name : new String[] {
                "isAndroid", "isWindows", "isOsx", "maybeSuperUser", "isVirtualThread",
                "hasUnsafe", "isUnaligned", "directBufferPreferred",
                "canReliabilyFreeDirectBuffers", "hasVarHandle", "useDirectBufferNoCleaner",
                "isJfrEnabled"
        }) {
            MethodNode method = findByName(node, name);
            replaceConstant(method, Opcodes.ICONST_0, Opcodes.IRETURN);
        }
        replaceConstant(find(node, "isExplicitNoPreferDirect", "()Z"),
                Opcodes.ICONST_1, Opcodes.IRETURN);
        replaceConstant(find(node, "canEnableTcpNoDelayByDefault", "()Z"),
                Opcodes.ICONST_1, Opcodes.IRETURN);
        replaceInt(find(node, "javaVersion", "()I"), 21);
        replaceInt(find(node, "bitMode", "()I"), 32);
        replaceInt(find(node, "addressSize", "()I"), 4);
        replaceLong(find(node, "usedDirectMemory", "()J"), -1L);
        replaceFieldGetter(find(node, "getUnsafeUnavailabilityCause", "()Ljava/lang/Throwable;"),
                "UNSAFE_UNAVAILABILITY_CAUSE", "Ljava/lang/Throwable;", Opcodes.ARETURN);
        replaceFieldGetter(find(node, "maxDirectMemory", "()J"),
                "MAX_DIRECT_MEMORY", "J", Opcodes.LRETURN);
        replaceFieldGetter(find(node, "tmpdir", "()Ljava/io/File;"),
                "TMPDIR", "Ljava/io/File;", Opcodes.ARETURN);
        replaceFieldGetter(find(node, "normalizedArch", "()Ljava/lang/String;"),
                "NORMALIZED_ARCH", "Ljava/lang/String;", Opcodes.ARETURN);
        replaceFieldGetter(find(node, "normalizedOs", "()Ljava/lang/String;"),
                "NORMALIZED_OS", "Ljava/lang/String;", Opcodes.ARETURN);
        replaceFieldGetter(find(node, "normalizedLinuxClassifiers", "()Ljava/util/Set;"),
                "LINUX_OS_CLASSIFIERS", "Ljava/util/Set;", Opcodes.ARETURN);
        replaceNew(find(node, "newLongCounter", "()Lio/netty/util/internal/LongCounter;"),
                "io/netty/util/internal/BrowserLongCounter");
        replaceNoop(find(node, "freeDirectBuffer", "(Ljava/nio/ByteBuffer;)V"));
        replaceThrowException(find(node, "throwException", "(Ljava/lang/Throwable;)V"));
        replaceLong(find(node, "directBufferAddress", "(Ljava/nio/ByteBuffer;)J"), 0L);
        replaceArrayStore(find(node, "putInt", "([BII)V"),
                "putInt", "([BII)V");
        replaceArrayStore(find(node, "putLong", "([BIJ)V"),
                "putLong", "([BIJ)V");
        for (MethodNode method : node.methods) {
            if (method.name.equals("newMpscQueue")
                    || method.name.equals("newSpscQueue")
                    || method.name.equals("newFixedMpscQueue")
                    || method.name.equals("newFixedMpscUnpaddedQueue")
                    || method.name.equals("newFixedMpmcQueue")) {
                replaceNew(method, "java/util/concurrent/ConcurrentLinkedQueue");
            }
        }
        write(node, output);
    }

    private static void patchRefCnt(Path jar, Path output) throws IOException {
        String entry = "io/netty/util/internal/RefCnt.class";
        try (ZipFile input = new ZipFile(jar.toFile())) {
            if (input.getEntry(entry) == null) {
                return;
            }
        }

        ClassNode node = read(jar, entry);
        String owner = "io/netty/util/internal/RefCnt";
        String atomic = owner + "$AtomicRefCnt";

        InsnList constructor = new InsnList();
        constructor.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        constructor.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        constructor.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, atomic, "init", "(L" + owner + ";)V", false));
        constructor.add(new InsnNode(Opcodes.RETURN));
        replace(node, "<init>", "()V", constructor);

        for (String descriptor : new String[] {
                "(L" + owner + ";)I",
                "(L" + owner + ";)V",
                "(L" + owner + ";I)V",
                "(L" + owner + ";)Z",
                "(L" + owner + ";I)Z"
        }) {
            String name;
            if (descriptor.endsWith(")I")) {
                name = "refCnt";
            } else if (descriptor.endsWith(";I)V")) {
                name = "retain";
            } else if (descriptor.endsWith(";I)Z")) {
                name = "release";
            } else if (descriptor.endsWith(")Z")) {
                name = "release";
            } else {
                name = "retain";
            }
            replaceRefCntDelegate(node, atomic, name, descriptor);
        }
        replaceRefCntDelegate(
                node, atomic, "isLiveNonVolatile", "(L" + owner + ";)Z");
        replaceRefCntDelegate(node, atomic, "setRefCnt", "(L" + owner + ";I)V");
        replaceRefCntDelegate(node, atomic, "resetRefCnt", "(L" + owner + ";)V");

        InsnList initializer = new InsnList();
        putInt(initializer, owner, "REF_CNT_IMPL", 2);
        initializer.add(new InsnNode(Opcodes.RETURN));
        replace(node, "<clinit>", "()V", initializer);
        write(node, output);
        System.out.println("Patched Netty RefCnt to the browser-safe atomic implementation");
    }

    private static void replaceRefCntDelegate(
            ClassNode node, String targetOwner, String name, String descriptor) {
        MethodNode method = find(node, name, descriptor);
        InsnList code = new InsnList();
        int local = 0;
        for (org.objectweb.asm.Type argument
                : org.objectweb.asm.Type.getArgumentTypes(descriptor)) {
            code.add(new org.objectweb.asm.tree.VarInsnNode(
                    argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, targetOwner, name, descriptor, false));
        code.add(new InsnNode(
                org.objectweb.asm.Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)));
        replace(method, code);
    }

    private static void patchPlatform0(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, "io/netty/util/internal/PlatformDependent0.class");
        String owner = "io/netty/util/internal/PlatformDependent0";
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(owner)));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "io/netty/util/internal/logging/InternalLoggerFactory",
                "getInstance",
                "(Ljava/lang/Class;)Lio/netty/util/internal/logging/InternalLogger;",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, owner, "logger",
                "Lio/netty/util/internal/logging/InternalLogger;"));
        putLong(code, owner, "ADDRESS_FIELD_OFFSET", -1L);
        putLong(code, owner, "BYTE_ARRAY_BASE_OFFSET", 0L);
        putLong(code, owner, "INT_ARRAY_BASE_OFFSET", 0L);
        putLong(code, owner, "INT_ARRAY_INDEX_SCALE", 4L);
        putLong(code, owner, "LONG_ARRAY_BASE_OFFSET", 0L);
        putLong(code, owner, "LONG_ARRAY_INDEX_SCALE", 8L);
        putBoolean(code, owner, "IS_ANDROID", false);
        putInt(code, owner, "JAVA_VERSION", 21);
        putNewString(code, "java/lang/UnsupportedOperationException",
                "Unsafe is unavailable in the browser");
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, owner, "UNSAFE_UNAVAILABILITY_CAUSE",
                "Ljava/lang/Throwable;"));
        putBoolean(code, owner, "RUNNING_IN_NATIVE_IMAGE", false);
        putBoolean(code, owner, "IS_EXPLICIT_TRY_REFLECTION_SET_ACCESSIBLE", false);
        putInt(code, owner, "HASH_CODE_ASCII_SEED", -1028477387);
        putInt(code, owner, "HASH_CODE_C1", -862048943);
        putInt(code, owner, "HASH_CODE_C2", 461845907);
        putBoolean(code, owner, "UNALIGNED", false);
        putLong(code, owner, "BITS_MAX_DIRECT_MEMORY", -1L);
        putBoolean(code, owner, "$assertionsDisabled", true);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(node, "<clinit>", "()V", code);

        replaceConstant(find(node, "hasUnsafe", "()Z"),
                Opcodes.ICONST_0, Opcodes.IRETURN);
        replaceConstant(find(node, "isUnaligned", "()Z"),
                Opcodes.ICONST_0, Opcodes.IRETURN);
        replaceConstant(find(node, "hasDirectBufferNoCleanerConstructor", "()Z"),
                Opcodes.ICONST_0, Opcodes.IRETURN);
        replaceConstant(find(node, "hasAllocateArrayMethod", "()Z"),
                Opcodes.ICONST_0, Opcodes.IRETURN);
        replaceConstant(find(node, "hasAlignSliceMethod", "()Z"),
                Opcodes.ICONST_0, Opcodes.IRETURN);
        replaceConstant(find(node, "hasOffsetSliceMethod", "()Z"),
                Opcodes.ICONST_0, Opcodes.IRETURN);
        replaceConstant(find(node, "isVirtualThread", "(Ljava/lang/Thread;)Z"),
                Opcodes.ICONST_0, Opcodes.IRETURN);
        replaceAllocateArray(node);
        replaceThrowException(find(node, "throwException", "(Ljava/lang/Throwable;)V"));
        replaceLong(find(node, "directBufferAddress", "(Ljava/nio/ByteBuffer;)J"), 0L);
        replaceLong(find(node, "objectFieldOffset", "(Ljava/lang/reflect/Field;)J"), -1L);
        replaceNoop(find(node, "putObject", "(Ljava/lang/Object;JLjava/lang/Object;)V"));
        replaceNoop(find(node, "copyMemory", "(JJJ)V"));
        replaceNoop(find(node, "copyMemoryWithSafePointPolling", "(JJJ)V"));
        replaceNoop(find(node, "copyMemory",
                "(Ljava/lang/Object;JLjava/lang/Object;JJ)V"));
        replaceNoop(find(node, "copyMemoryWithSafePointPolling",
                "(Ljava/lang/Object;JLjava/lang/Object;JJ)V"));
        replaceNoop(find(node, "setMemory", "(JJB)V"));
        replaceNoop(find(node, "setMemory", "(Ljava/lang/Object;JJB)V"));
        write(node, output);
    }

    private static void patchNetUtil(Path jar, Path netUtilOutput, Path initializationsOutput)
            throws IOException {
        ClassNode netUtil = read(jar, "io/netty/util/NetUtil.class");
        MethodNode sysctl = find(netUtil, "sysctlGetInt",
                "(Ljava/lang/String;)Ljava/lang/Integer;");
        InsnList noSysctl = new InsnList();
        noSysctl.add(new InsnNode(Opcodes.ACONST_NULL));
        noSysctl.add(new InsnNode(Opcodes.ARETURN));
        replace(sysctl, noSysctl);
        write(netUtil, netUtilOutput);

        ClassNode initializations = read(jar, "io/netty/util/NetUtilInitializations.class");
        MethodNode interfaces = find(initializations, "networkInterfaces",
                "()Ljava/util/Collection;");
        InsnList empty = new InsnList();
        empty.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/util/Collections", "emptyList", "()Ljava/util/List;", false));
        empty.add(new InsnNode(Opcodes.ARETURN));
        replace(interfaces, empty);

        MethodNode loopback = find(initializations, "determineLoopback",
                "(Ljava/util/Collection;Ljava/net/Inet4Address;Ljava/net/Inet6Address;)"
                        + "Lio/netty/util/NetUtilInitializations$NetworkIfaceAndInetAddress;");
        InsnList fallback = new InsnList();
        fallback.add(new TypeInsnNode(Opcodes.NEW,
                "io/netty/util/NetUtilInitializations$NetworkIfaceAndInetAddress"));
        fallback.add(new InsnNode(Opcodes.DUP));
        fallback.add(new InsnNode(Opcodes.ACONST_NULL));
        fallback.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        fallback.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "io/netty/util/NetUtilInitializations$NetworkIfaceAndInetAddress",
                "<init>",
                "(Ljava/net/NetworkInterface;Ljava/net/InetAddress;)V",
                false));
        fallback.add(new InsnNode(Opcodes.ARETURN));
        replace(loopback, fallback);
        write(initializations, initializationsOutput);
    }

    private static void patchChannelInitializer(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, "io/netty/channel/ChannelInitializer.class");
        MethodNode constructor = find(node, "<init>", "()V");
        InsnList code = new InsnList();
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "io/netty/channel/ChannelInboundHandlerAdapter", "<init>", "()V", false));
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        putNew(code, "java/util/concurrent/ConcurrentHashMap");
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/util/Collections", "newSetFromMap",
                "(Ljava/util/Map;)Ljava/util/Set;", false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                "io/netty/channel/ChannelInitializer", "initMap", "Ljava/util/Set;"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, code);
        write(node, output);
    }

    private static void patchAbstractChannelUnsafe(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, "io/netty/channel/AbstractChannel$AbstractUnsafe.class");
        MethodNode register = find(node, "register",
                "(Lio/netty/channel/EventLoop;Lio/netty/channel/ChannelPromise;)V");
        InsnList selectEventLoop = new InsnList();
        selectEventLoop.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        selectEventLoop.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "io/netty/channel/AbstractChannel$AbstractUnsafe",
                "this$0",
                "Lio/netty/channel/AbstractChannel;"));
        selectEventLoop.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        selectEventLoop.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "io/netty/channel/browser/BrowserWebSocketChannel",
                "eventLoopFor",
                "(Lio/netty/channel/Channel;Lio/netty/channel/EventLoop;)"
                        + "Lio/netty/channel/EventLoop;",
                false));
        selectEventLoop.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ASTORE, 1));
        register.instructions.insert(selectEventLoop);
        boolean replaced = false;
        for (org.objectweb.asm.tree.AbstractInsnNode instruction
                : register.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("io/netty/channel/EventLoop")
                    && call.name.equals("inEventLoop")
                    && call.desc.equals("()Z")) {
                register.instructions.insertBefore(call, new org.objectweb.asm.tree.VarInsnNode(
                        Opcodes.ALOAD, 0));
                register.instructions.insertBefore(call, new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "io/netty/channel/AbstractChannel$AbstractUnsafe",
                        "this$0",
                        "Lio/netty/channel/AbstractChannel;"));
                register.instructions.set(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "io/netty/channel/browser/BrowserWebSocketChannel",
                        "shouldRegisterInline",
                        "(Lio/netty/channel/EventLoop;Lio/netty/channel/Channel;)Z",
                        false));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            throw new IllegalStateException("AbstractChannel.AbstractUnsafe.register event-loop check was not found");
        }
        write(node, output);
    }

    private static void patchBootstrap(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, "io/netty/bootstrap/Bootstrap.class");
        ClassNode abstractBootstrap = read(jar, "io/netty/bootstrap/AbstractBootstrap.class");
        // TeaVM cannot resolve the generic AbstractBootstrap.group bridge when
        // the LAN opener is reachable from the worker entry point.  Keep a
        // concrete Bootstrap-owned setter in the patched class so the
        // injected LAN opener links to a plain field write instead of the
        // missing generic method.
        MethodNode gaiusGroup = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaiusGroup",
                "(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/Bootstrap;",
                null,
                null);
        gaiusGroup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gaiusGroup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        gaiusGroup.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                "io/netty/bootstrap/AbstractBootstrap",
                "group",
                "Lio/netty/channel/EventLoopGroup;"));
        gaiusGroup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gaiusGroup.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(gaiusGroup);
        MethodNode connect = find(node, "doConnect",
                "(Ljava/net/SocketAddress;Ljava/net/SocketAddress;"
                        + "Lio/netty/channel/ChannelPromise;)V");
        LabelNode useEventLoop = new LabelNode();
        InsnList inline = new InsnList();
        inline.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 2));
        inline.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "io/netty/channel/ChannelPromise",
                "channel",
                "()Lio/netty/channel/Channel;",
                true));
        inline.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        inline.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        inline.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 2));
        inline.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "io/netty/channel/browser/BrowserWebSocketChannel",
                "connectInline",
                "(Lio/netty/channel/Channel;Ljava/net/SocketAddress;"
                        + "Ljava/net/SocketAddress;Lio/netty/channel/ChannelPromise;)Z",
                false));
        inline.add(new JumpInsnNode(Opcodes.IFEQ, useEventLoop));
        inline.add(new InsnNode(Opcodes.RETURN));
        inline.add(useEventLoop);
        connect.instructions.insert(inline);
        write(node, output);
        write(abstractBootstrap, output.getParent().resolve("AbstractBootstrap.class"));
    }

    private static void patchReflectiveChannelFactory(Path jar, Path output) throws IOException {
        String owner = "io/netty/channel/ReflectiveChannelFactory";
        ClassNode node = read(jar, owner + ".class");
        boolean hasClazzField = node.fields.stream().anyMatch(field -> field.name.equals("clazz"));
        if (!hasClazzField) {
            node.fields.add(new org.objectweb.asm.tree.FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                    "clazz",
                    "Ljava/lang/Class;",
                    null,
                    null));
        }

        MethodNode constructor = find(node, "<init>", "(Ljava/lang/Class;)V");
        LabelNode notBrowserChannel = new LabelNode();
        LabelNode notLocalServer = new LabelNode();
        LabelNode notLocalChannel = new LabelNode();
        InsnList init = new InsnList();
        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        init.add(new LdcInsnNode("clazz"));
        init.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "io/netty/util/internal/ObjectUtil",
                "checkNotNull",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                false));
        init.add(new InsnNode(Opcodes.POP));
        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "clazz", "Ljava/lang/Class;"));

        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        init.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(
                "io/netty/channel/browser/BrowserWebSocketChannel")));
        init.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notBrowserChannel));
        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new InsnNode(Opcodes.ACONST_NULL));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "constructor",
                "Ljava/lang/reflect/Constructor;"));
        init.add(new InsnNode(Opcodes.RETURN));
        init.add(notBrowserChannel);

        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        init.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(
                "io/netty/channel/local/LocalServerChannel")));
        init.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notLocalServer));
        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new InsnNode(Opcodes.ACONST_NULL));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "constructor",
                "Ljava/lang/reflect/Constructor;"));
        init.add(new InsnNode(Opcodes.RETURN));
        init.add(notLocalServer);

        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        init.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(
                "io/netty/channel/local/LocalChannel")));
        init.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notLocalChannel));
        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new InsnNode(Opcodes.ACONST_NULL));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "constructor",
                "Ljava/lang/reflect/Constructor;"));
        init.add(new InsnNode(Opcodes.RETURN));
        init.add(notLocalChannel);

        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        init.add(new InsnNode(Opcodes.ICONST_0));
        init.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        init.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/Class",
                "getConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "constructor",
                "Ljava/lang/reflect/Constructor;"));
        init.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, init);

        MethodNode newChannel = find(node, "newChannel", "()Lio/netty/channel/Channel;");
        LabelNode notNewBrowserChannel = new LabelNode();
        LabelNode notNewLocalServer = new LabelNode();
        LabelNode notNewLocalChannel = new LabelNode();
        InsnList create = new InsnList();
        create.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        create.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "clazz", "Ljava/lang/Class;"));
        create.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(
                "io/netty/channel/browser/BrowserWebSocketChannel")));
        create.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notNewBrowserChannel));
        putNew(create, "io/netty/channel/browser/BrowserWebSocketChannel");
        create.add(new InsnNode(Opcodes.ARETURN));
        create.add(notNewBrowserChannel);

        create.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        create.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "clazz", "Ljava/lang/Class;"));
        create.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(
                "io/netty/channel/local/LocalServerChannel")));
        create.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notNewLocalServer));
        putNew(create, "io/netty/channel/local/LocalServerChannel");
        create.add(new InsnNode(Opcodes.ARETURN));
        create.add(notNewLocalServer);

        create.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        create.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "clazz", "Ljava/lang/Class;"));
        create.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(
                "io/netty/channel/local/LocalChannel")));
        create.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notNewLocalChannel));
        putNew(create, "io/netty/channel/local/LocalChannel");
        create.add(new InsnNode(Opcodes.ARETURN));
        create.add(notNewLocalChannel);

        create.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        create.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "constructor",
                "Ljava/lang/reflect/Constructor;"));
        create.add(new InsnNode(Opcodes.ICONST_0));
        create.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        create.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Constructor",
                "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        create.add(new TypeInsnNode(Opcodes.CHECKCAST, "io/netty/channel/Channel"));
        create.add(new InsnNode(Opcodes.ARETURN));
        replace(newChannel, create);

        MethodNode toString = find(node, "toString", "()Ljava/lang/String;");
        InsnList text = new InsnList();
        text.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        text.add(new InsnNode(Opcodes.DUP));
        text.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder", "<init>", "()V", false));
        text.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(owner)));
        text.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "io/netty/util/internal/StringUtil",
                "simpleClassName",
                "(Ljava/lang/Class;)Ljava/lang/String;",
                false));
        text.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false));
        text.add(new IntInsnNode(Opcodes.BIPUSH, 40));
        text.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "append",
                "(C)Ljava/lang/StringBuilder;",
                false));
        text.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        text.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "clazz", "Ljava/lang/Class;"));
        text.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "io/netty/util/internal/StringUtil",
                "simpleClassName",
                "(Ljava/lang/Class;)Ljava/lang/String;",
                false));
        text.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false));
        text.add(new LdcInsnNode(".class)"));
        text.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false));
        text.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;",
                false));
        text.add(new InsnNode(Opcodes.ARETURN));
        replace(toString, text);

        write(node, output);
    }

    private static void patchDefaultChannelId(Path jar, Path output) throws IOException {
        String owner = "io/netty/channel/DefaultChannelId";
        ClassNode node = read(jar, owner + ".class");
        replaceInt(find(node, "defaultProcessId", "()I"), 1);
        MethodNode method = find(node, "newInstance", "()Lio/netty/channel/DefaultChannelId;");
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, owner));
        code.add(new InsnNode(Opcodes.DUP));
        putByteArray(code, new byte[] {0x47, 0x41, 0x49, 0x55, 0x53, 0x00, 0x00, 0x01});
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "PROCESS_ID", "I"));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "nextSequence",
                "Ljava/util/concurrent/atomic/AtomicInteger;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/atomic/AtomicInteger",
                "getAndIncrement",
                "()I",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "nextSequence",
                "Ljava/util/concurrent/atomic/AtomicInteger;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/atomic/AtomicInteger",
                "getAndIncrement",
                "()I",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "<init>",
                "([BIIJI)V",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code);
        write(node, output);
    }

    private static void patchMacAddressUtil(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, "io/netty/util/internal/MacAddressUtil.class");
        MethodNode method = find(node, "defaultMachineId", "()[B");
        InsnList code = new InsnList();
        putByteArray(code, new byte[] {0x47, 0x41, 0x49, 0x55, 0x53, 0x00, 0x00, 0x01});
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code);
        write(node, output);
    }

    private static void patchRecycler(Path jar, Path output) throws IOException {
        String owner = "io/netty/util/Recycler";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "get", "()Ljava/lang/Object;");
        InsnList code = new InsnList();
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                owner,
                "NOOP_HANDLE",
                "Lio/netty/util/Recycler$EnhancedHandle;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "newObject",
                "(Lio/netty/util/Recycler$Handle;)Ljava/lang/Object;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code);
        replaceConstant(find(node, "threadLocalSize", "()I"), Opcodes.ICONST_0, Opcodes.IRETURN);
        write(node, output);
    }

    private static void patchChannelHandlerMask(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, "io/netty/channel/ChannelHandlerMask.class");
        replaceConstant(find(node, "isSkippable",
                "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Z"),
                Opcodes.ICONST_0, Opcodes.IRETURN);
        write(node, output);
    }

    private static void patchEmptyArrays(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, "io/netty/util/internal/EmptyArrays.class");
        MethodNode initializer = find(node, "<clinit>", "()V");
        for (var instruction = initializer.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.ANEWARRAY
                    && type.desc.equals("javax/security/cert/X509Certificate")) {
                initializer.instructions.set(type, new InsnNode(Opcodes.ACONST_NULL));
                break;
            }
        }
        write(node, output);
    }

    private static void patchResourceLeakDetector(Path jar, Path output) throws IOException {
        String owner = "io/netty/util/ResourceLeakDetector";
        ClassNode node = read(jar, owner + ".class");
        replaceNoop(find(node, "addExclusions", "(Ljava/lang/Class;[Ljava/lang/String;)V"));
        MethodNode constructor = find(node, "<init>", "(Ljava/lang/String;IJ)V");
        InsnList code = new InsnList();
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        for (String field : new String[] {"allLeaks", "reportedLeaks"}) {
            code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
            putNew(code, "java/util/concurrent/ConcurrentHashMap");
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/Collections",
                    "newSetFromMap",
                    "(Ljava/util/Map;)Ljava/util/Set;",
                    false));
            code.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, owner, field, "Ljava/util/Set;"));
        }
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        putNew(code, "java/lang/ref/ReferenceQueue");
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "refQueue", "Ljava/lang/ref/ReferenceQueue;"));
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "resourceType", "Ljava/lang/String;"));
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "samplingInterval", "I"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, code);
        write(node, output);
    }

    private static void patchReferenceCountedBuffer(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, "io/netty/buffer/AbstractReferenceCountedByteBuf.class");
        String owner = "io/netty/buffer/AbstractReferenceCountedByteBuf";
        boolean usesRefCntHelper = node.fields.stream().anyMatch(field ->
                field.name.equals("refCnt")
                        && field.desc.equals("Lio/netty/util/internal/RefCnt;"));
        if (usesRefCntHelper) {
            // Netty 4.2.15 selects RefCnt.AtomicRefCnt when the patched
            // PlatformDependent reports both Unsafe and VarHandle unavailable.
            System.out.println("Verified Netty RefCnt atomic browser fallback");
            return;
        }
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(owner)));
        code.add(new LdcInsnNode("refCnt"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/concurrent/atomic/AtomicIntegerFieldUpdater",
                "newUpdater",
                "(Ljava/lang/Class;Ljava/lang/String;)"
                        + "Ljava/util/concurrent/atomic/AtomicIntegerFieldUpdater;",
                false));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, owner, "AIF_UPDATER",
                "Ljava/util/concurrent/atomic/AtomicIntegerFieldUpdater;"));
        code.add(new InsnNode(Opcodes.LCONST_0));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "REFCNT_FIELD_OFFSET", "J"));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, owner, "REFCNT_FIELD_VH", "Ljava/lang/Object;"));
        putNew(code, owner + "$1");
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, owner, "updater",
                "Lio/netty/util/internal/ReferenceCountUpdater;"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(node, "<clinit>", "()V", code);
        write(node, output);
    }

    private static void patchUnpooledAllocator(Path jar, Path output) throws IOException {
        String owner = "io/netty/buffer/UnpooledByteBufAllocator";
        ClassNode node = read(jar, owner + ".class");
        for (String methodName : new String[] {"newHeapBuffer", "newDirectBuffer"}) {
            MethodNode method = find(node, methodName, "(II)Lio/netty/buffer/ByteBuf;");
            InsnList code = new InsnList();
            code.add(new TypeInsnNode(
                    Opcodes.NEW,
                    owner + "$InstrumentedUnpooledHeapByteBuf"));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 1));
            code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 2));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    owner + "$InstrumentedUnpooledHeapByteBuf",
                    "<init>",
                    "(Lio/netty/buffer/UnpooledByteBufAllocator;II)V",
                    false));
            code.add(new InsnNode(Opcodes.ARETURN));
            replace(method, code);
        }
        replaceCompositeBuffer(find(node, "compositeHeapBuffer",
                "(I)Lio/netty/buffer/CompositeByteBuf;"), false);
        replaceCompositeBuffer(find(node, "compositeDirectBuffer",
                "(I)Lio/netty/buffer/CompositeByteBuf;"), true);
        write(node, output);
    }

    private static void replaceCompositeBuffer(MethodNode method, boolean direct) {
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, "io/netty/buffer/CompositeByteBuf"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(direct ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "io/netty/buffer/CompositeByteBuf",
                "<init>",
                "(Lio/netty/buffer/ByteBufAllocator;ZI)V",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code);
    }

    private static void patchAbstractByteBufAllocator(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, "io/netty/buffer/AbstractByteBufAllocator.class");
        replaceCompositeBuffer(find(node, "compositeHeapBuffer",
                "(I)Lio/netty/buffer/CompositeByteBuf;"), false);
        replaceCompositeBuffer(find(node, "compositeDirectBuffer",
                "(I)Lio/netty/buffer/CompositeByteBuf;"), true);
        for (String desc : new String[] {
                "(Lio/netty/buffer/ByteBuf;)Lio/netty/buffer/ByteBuf;",
                "(Lio/netty/buffer/CompositeByteBuf;)Lio/netty/buffer/CompositeByteBuf;"
        }) {
            MethodNode method = find(node, "toLeakAwareBuffer", desc);
            node.methods.remove(method);
        }
        write(node, output);
    }

    private static void patchLeakAwareAllocator(Path jar, String owner, Path output)
            throws IOException {
        ClassNode node = read(jar, owner + ".class");
        for (MethodNode method : node.methods) {
            for (var instruction = method.instructions.getFirst();
                    instruction != null;) {
                var next = instruction.getNext();
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.name.equals("toLeakAwareBuffer")) {
                    method.instructions.remove(instruction);
                }
                instruction = next;
            }
        }
        write(node, output);
    }

    private static void patchBuffer(Path jar, Path output) throws IOException {
        ClassNode node = read(jar, BUFFER);
        InsnList code = new InsnList();
        putBoolean(code, "io/netty/buffer/ByteBufUtil", "$assertionsDisabled", true);
        code.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType("io/netty/buffer/ByteBufUtil")));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "io/netty/util/internal/logging/InternalLoggerFactory", "getInstance",
                "(Ljava/lang/Class;)Lio/netty/util/internal/logging/InternalLogger;", false));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, "io/netty/buffer/ByteBufUtil", "logger",
                "Lio/netty/util/internal/logging/InternalLogger;"));
        putNew(code, "io/netty/buffer/ByteBufUtil$1");
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, "io/netty/buffer/ByteBufUtil", "BYTE_ARRAYS",
                "Lio/netty/util/concurrent/FastThreadLocal;"));
        putInt(code, "io/netty/buffer/ByteBufUtil", "MAX_BYTES_PER_CHAR_UTF8", 3);
        code.add(new FieldInsnNode(Opcodes.GETSTATIC,
                "io/netty/buffer/UnpooledByteBufAllocator", "DEFAULT",
                "Lio/netty/buffer/UnpooledByteBufAllocator;"));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, "io/netty/buffer/ByteBufUtil",
                "DEFAULT_ALLOCATOR", "Lio/netty/buffer/ByteBufAllocator;"));
        putInt(code, "io/netty/buffer/ByteBufUtil", "THREAD_LOCAL_BUFFER_SIZE", 0);
        putInt(code, "io/netty/buffer/ByteBufUtil", "MAX_CHAR_BUFFER_SIZE", 16384);
        putNew(code, "io/netty/buffer/ByteBufUtil$2");
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, "io/netty/buffer/ByteBufUtil",
                "FIND_NON_ASCII", "Lio/netty/util/ByteProcessor;"));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(node, "<clinit>", "()V", code);
        replaceAsciiStringConstructor(node);
        write(node, output);
    }

    /**
     * Removes Netty 4.2's signature-polymorphic VarHandle branches from the
     * heap-buffer helpers. TeaVM analyzes both sides of a runtime
     * {@code hasVarHandle()} guard, even though {@link #patchPlatform} forces
     * that method to return false. Keeping the dead branch therefore exposes
     * descriptors such as {@code VarHandle.get(byte[], int):int/long}, which
     * Java source cannot represent as overloads and TeaVM cannot resolve.
     *
     * <p>Every affected Netty method starts with exactly this shape:</p>
     *
     * <pre>
     * PlatformDependent.hasVarHandle();
     * ifeq portablePath;
     * ... VarHandleByteBufferAccess ...
     * portablePath:
     * ... byte-array implementation ...
     * </pre>
     *
     * <p>Delete the guarded prefix so reachability begins at the already
     * supplied portable implementation. This preserves Netty's byte order and
     * bounds behaviour instead of emulating the accessors in the classlib.</p>
     */
    private static void patchHeapByteBufUtil(Path jar, Path output) throws IOException {
        String owner = "io/netty/buffer/HeapByteBufUtil";
        ClassNode node = read(jar, owner + ".class");
        int patched = 0;
        for (MethodNode method : node.methods) {
            var first = firstExecutable(method.instructions.getFirst());
            if (!(first instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("io/netty/util/internal/PlatformDependent")
                    || !call.name.equals("hasVarHandle")
                    || !call.desc.equals("()Z")) {
                continue;
            }
            var next = firstExecutable(first.getNext());
            if (!(next instanceof JumpInsnNode branch)
                    || branch.getOpcode() != Opcodes.IFEQ) {
                throw new IllegalStateException(
                        owner + "." + method.name + method.desc
                                + " has an unexpected VarHandle guard");
            }

            var instruction = method.instructions.getFirst();
            while (instruction != branch.label) {
                if (instruction == null) {
                    throw new IllegalStateException(
                            owner + "." + method.name + method.desc
                                    + " has no portable VarHandle fallback");
                }
                var following = instruction.getNext();
                method.instructions.remove(instruction);
                instruction = following;
            }
            if (method.localVariables != null) {
                method.localVariables.clear();
            }
            patched++;
        }
        if (patched == 0) {
            throw new IllegalStateException(
                    owner + " contains no guarded VarHandle accessors");
        }
        write(node, output);
        System.out.println(
                "Forced " + patched + " Netty heap-buffer accessors onto portable byte arrays");
    }

    private static org.objectweb.asm.tree.AbstractInsnNode firstExecutable(
            org.objectweb.asm.tree.AbstractInsnNode instruction) {
        while (instruction != null
                && (instruction.getType() == org.objectweb.asm.tree.AbstractInsnNode.LABEL
                        || instruction.getType() == org.objectweb.asm.tree.AbstractInsnNode.LINE
                        || instruction.getType() == org.objectweb.asm.tree.AbstractInsnNode.FRAME)) {
            instruction = instruction.getNext();
        }
        return instruction;
    }

    private static void replaceAsciiStringConstructor(ClassNode node) {
        MethodNode decode = find(node, "decodeString",
                "(Lio/netty/buffer/ByteBuf;IILjava/nio/charset/Charset;)Ljava/lang/String;");
        for (var instruction = decode.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("java/lang/String")
                    && call.name.equals("<init>")
                    && call.desc.equals("([BIII)V")) {
                var count = call.getPrevious();
                var offset = count == null ? null : count.getPrevious();
                var hibyte = offset == null ? null : offset.getPrevious();
                if (hibyte == null || hibyte.getOpcode() != Opcodes.ICONST_0) {
                    throw new IllegalStateException("Unexpected ASCII String constructor shape");
                }
                decode.instructions.remove(hibyte);
                decode.instructions.insertBefore(call,
                        new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 3));
                call.desc = "([BIILjava/nio/charset/Charset;)V";
                return;
            }
        }
        throw new IllegalStateException("ASCII String constructor was not found");
    }

    private static InsnList platformInitializer(ClassNode node) {
        String owner = "io/netty/util/internal/PlatformDependent";
        InsnList code = new InsnList();
        putBooleanIfPresent(node, code, owner, "$assertionsDisabled", true);
        if (hasField(node, "logger", "Lio/netty/util/internal/logging/InternalLogger;")) {
            code.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(owner)));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "io/netty/util/internal/logging/InternalLoggerFactory", "getInstance",
                    "(Ljava/lang/Class;)Lio/netty/util/internal/logging/InternalLogger;", false));
            code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "logger",
                    "Lio/netty/util/internal/logging/InternalLogger;"));
        }
        putBooleanIfPresent(
                node, code, owner, "CAN_ENABLE_TCP_NODELAY_BY_DEFAULT", true);
        if (hasField(node, "UNSAFE_UNAVAILABILITY_CAUSE", "Ljava/lang/Throwable;")) {
            putNewString(code, "java/lang/UnsupportedOperationException",
                    "Native memory is unavailable in the browser");
            code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "UNSAFE_UNAVAILABILITY_CAUSE",
                    "Ljava/lang/Throwable;"));
        }
        putLongIfPresent(node, code, owner, "MAX_DIRECT_MEMORY", 2147483647L);
        putLongIfPresent(node, code, owner, "BYTE_ARRAY_BASE_OFFSET", 0L);
        if (hasField(node, "TMPDIR", "Ljava/io/File;")) {
            putNewString(code, "java/io/File", ".");
            code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "TMPDIR", "Ljava/io/File;"));
        }
        putIntIfPresent(node, code, owner, "BIT_MODE", 32);
        putStringIfPresent(node, code, owner, "NORMALIZED_ARCH", "wasm32");
        putStringIfPresent(node, code, owner, "NORMALIZED_OS", "browser");
        putBooleanIfPresent(node, code, owner, "IS_WINDOWS", false);
        putBooleanIfPresent(node, code, owner, "IS_OSX", false);
        putBooleanIfPresent(node, code, owner, "IS_J9_JVM", false);
        putBooleanIfPresent(node, code, owner, "IS_IVKVM_DOT_NET", false);
        putIntIfPresent(node, code, owner, "ADDRESS_SIZE", 4);
        putBooleanIfPresent(node, code, owner, "BIG_ENDIAN_NATIVE_ORDER", false);

        boolean hasCleaner = node.fields.stream().anyMatch(field ->
                field.desc.equals("Lio/netty/util/internal/Cleaner;")
                        && (field.name.equals("NOOP")
                                || field.name.equals("DIRECT_CLEANER")
                                || field.name.equals("LEGACY_CLEANER")
                                || field.name.equals("CLEANER")));
        if (hasCleaner) {
            putNew(code, "io/netty/util/internal/PlatformDependent$1");
            code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ASTORE, 0));
            for (String field : new String[] {
                    "NOOP", "DIRECT_CLEANER", "LEGACY_CLEANER", "CLEANER"
            }) {
                if (hasField(node, field, "Lio/netty/util/internal/Cleaner;")) {
                    code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                    code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field,
                            "Lio/netty/util/internal/Cleaner;"));
                }
            }
        }
        putBooleanIfPresent(node, code, owner, "USE_DIRECT_BUFFER_NO_CLEANER", false);
        if (hasField(node, "DIRECT_MEMORY_COUNTER",
                "Ljava/util/concurrent/atomic/AtomicLong;")) {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "DIRECT_MEMORY_COUNTER",
                    "Ljava/util/concurrent/atomic/AtomicLong;"));
        }
        putLongIfPresent(node, code, owner, "DIRECT_MEMORY_LIMIT", 2147483647L);
        putBooleanIfPresent(node, code, owner, "HAS_ALLOCATE_UNINIT_ARRAY", false);
        putBooleanIfPresent(node, code, owner, "MAYBE_SUPER_USER", false);
        putBooleanIfPresent(node, code, owner, "EXPLICIT_NO_PREFER_DIRECT", true);
        putBooleanIfPresent(node, code, owner, "DIRECT_BUFFER_PREFERRED", false);
        if (hasField(node, "LINUX_OS_CLASSIFIERS", "Ljava/util/Set;")) {
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/util/Collections", "emptySet", "()Ljava/util/Set;", false));
            code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "LINUX_OS_CLASSIFIERS",
                    "Ljava/util/Set;"));
        }
        putBooleanIfPresent(node, code, owner, "JFR", false);
        putBooleanIfPresent(node, code, owner, "VAR_HANDLE", false);
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    private static void replaceAllocateArray(ClassNode node) {
        MethodNode method = find(node, "allocateUninitializedArray", "(I)[B");
        InsnList code = new InsnList();
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 0));
        code.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code);
    }

    private static void replaceNew(MethodNode method, String type) {
        InsnList code = new InsnList();
        putNew(code, type);
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code);
    }

    private static void replaceNoop(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code);
    }

    private static void replaceThrowException(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ATHROW));
        replace(method, code);
    }

    private static void replaceArrayStore(MethodNode method, String name, String desc) {
        InsnList code = new InsnList();
        org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(desc);
        int local = 0;
        for (org.objectweb.asm.Type argument : arguments) {
            code.add(new org.objectweb.asm.tree.VarInsnNode(
                    argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "io/netty/util/internal/BrowserPlatformSupport", name, desc, false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code);
    }

    private static void replaceInt(MethodNode method, int value) {
        InsnList code = new InsnList();
        pushInt(code, value);
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code);
    }

    private static void replaceLong(MethodNode method, long value) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(value));
        code.add(new InsnNode(Opcodes.LRETURN));
        replace(method, code);
    }

    private static void replaceConstant(ClassNode node, String name, String desc,
            int constant, int returnOpcode) {
        replaceConstant(find(node, name, desc), constant, returnOpcode);
    }

    private static void replaceConstant(MethodNode method, int constant, int returnOpcode) {
        InsnList code = new InsnList();
        code.add(new InsnNode(constant));
        code.add(new InsnNode(returnOpcode));
        replace(method, code);
    }

    private static void replaceFieldGetter(MethodNode method, String field, String desc,
            int returnOpcode) {
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(Opcodes.GETSTATIC,
                "io/netty/util/internal/PlatformDependent", field, desc));
        code.add(new InsnNode(returnOpcode));
        replace(method, code);
    }

    private static void putInt(InsnList code, String owner, String field, int value) {
        pushInt(code, value);
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field, "I"));
    }

    private static boolean hasField(ClassNode node, String name, String descriptor) {
        return node.fields.stream().anyMatch(
                field -> field.name.equals(name) && field.desc.equals(descriptor));
    }

    private static void putIntIfPresent(
            ClassNode node, InsnList code, String owner, String field, int value) {
        if (hasField(node, field, "I")) {
            putInt(code, owner, field, value);
        }
    }

    private static void putBoolean(InsnList code, String owner, String field, boolean value) {
        code.add(new InsnNode(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field, "Z"));
    }

    private static void putBooleanIfPresent(
            ClassNode node, InsnList code, String owner, String field, boolean value) {
        if (hasField(node, field, "Z")) {
            putBoolean(code, owner, field, value);
        }
    }

    private static void putLong(InsnList code, String owner, String field, long value) {
        code.add(new LdcInsnNode(value));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field, "J"));
    }

    private static void putLongIfPresent(
            ClassNode node, InsnList code, String owner, String field, long value) {
        if (hasField(node, field, "J")) {
            putLong(code, owner, field, value);
        }
    }

    private static void putString(InsnList code, String owner, String field, String value) {
        code.add(new LdcInsnNode(value));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field, "Ljava/lang/String;"));
    }

    private static void putStringIfPresent(
            ClassNode node, InsnList code, String owner, String field, String value) {
        if (hasField(node, field, "Ljava/lang/String;")) {
            putString(code, owner, field, value);
        }
    }

    private static void putNewString(InsnList code, String type, String value) {
        code.add(new TypeInsnNode(Opcodes.NEW, type));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode(value));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type, "<init>",
                "(Ljava/lang/String;)V", false));
    }

    private static void putNew(InsnList code, String type) {
        code.add(new TypeInsnNode(Opcodes.NEW, type));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false));
    }

    private static void putByteArray(InsnList code, byte[] values) {
        pushInt(code, values.length);
        code.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        for (int i = 0; i < values.length; i++) {
            code.add(new InsnNode(Opcodes.DUP));
            pushInt(code, i);
            pushInt(code, values[i]);
            code.add(new InsnNode(Opcodes.BASTORE));
        }
    }

    private static void pushInt(InsnList code, int value) {
        if (value >= -1 && value <= 5) {
            code.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            code.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            code.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            code.add(new LdcInsnNode(value));
        }
    }

    private static ClassNode read(Path jar, String entry) throws IOException {
        byte[] input;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var jarEntry = zip.getEntry(entry);
            if (jarEntry == null) {
                throw new IllegalStateException(entry + " was not found in " + jar);
            }
            try (var stream = zip.getInputStream(jarEntry)) {
                input = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        return node;
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(name + desc + " was not found"));
    }

    private static MethodNode findByName(ClassNode node, String name) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(name + " was not found"));
    }

    private static void replace(ClassNode node, String name, String desc, InsnList code) {
        replace(find(node, name, desc), code);
    }

    private static void replace(MethodNode method, InsnList code) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.maxStack = 8;
        method.maxLocals = Math.max(method.maxLocals, 4);
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
