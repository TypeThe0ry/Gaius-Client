package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Routes authlib HTTP traffic through the browser bridge to avoid CORS failures.
 *
 * <p>Serves authlib 9 (Minecraft 26.2, package {@code com/mojang/authlib/yggdrasil}) and
 * authlib 10 (Minecraft 26.3, package {@code com/mojang/authlib/services}) from one name table
 * ({@link Names}). The flavour is chosen by probing the input jar for
 * {@value #AUTHLIB10_SESSION_ENTRY}; each patched class is written to the path of its input
 * entry, so build-overlays.sh folds the whole output directory back into the jar. On an
 * authlib 9 jar every name and every emitted byte is the pre-26.3 output.
 */
public final class AuthlibBrowserPatcher {
    private static final String ENTRY =
            "com/mojang/authlib/minecraft/client/MinecraftClient.class";
    private static final String SESSION_ENTRY =
            "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService.class";
    private static final String TEXTURE_ENTRY =
            "com/mojang/authlib/minecraft/MinecraftProfileTexture.class";
    private static final String TEXTURES_PAYLOAD_ENTRY =
            "com/mojang/authlib/yggdrasil/response/MinecraftTexturesPayload.class";
    private static final String KEY_INFO_ENTRY =
            "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo.class";
    private static final String KEY_SET_RESPONSE_ENTRY =
            "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo$KeySetResponse.class";
    private static final String KEY_DATA_ENTRY =
            "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo$KeyData.class";

    /** authlib 10 (Minecraft 26.3) names of the same classes. */
    static final String AUTHLIB10_SESSION_ENTRY =
            "com/mojang/authlib/services/MinecraftServicesSessionService.class";
    private static final String AUTHLIB10_TEXTURES_PAYLOAD_ENTRY =
            "com/mojang/authlib/services/response/MinecraftTexturesPayload.class";
    private static final String AUTHLIB10_KEY_INFO_ENTRY =
            "com/mojang/authlib/services/MinecraftServicesKeyInfo.class";
    private static final String AUTHLIB10_KEY_SET_RESPONSE_ENTRY =
            "com/mojang/authlib/services/MinecraftServicesKeyInfo$KeySetResponse.class";
    private static final String AUTHLIB10_KEY_DATA_ENTRY =
            "com/mojang/authlib/services/MinecraftServicesKeyInfo$KeyData.class";
    /**
     * authlib 10 texture-domain check: an instance method of the discovery service that throws
     * MinecraftClientException while the discovery document is offline or unavailable.
     */
    static final String AUTHLIB10_DISCOVERY_SERVICE =
            "com/mojang/authlib/services/MinecraftServicesDiscoveryService";
    private static final String AUTHLIB10_CLIENT_EXCEPTION =
            "com/mojang/authlib/exceptions/MinecraftClientException";
    /**
     * authlib 10 discovery-document records. They are decoded with Gson, which in the browser
     * can only instantiate classes that have a no-argument constructor (see the gson
     * UnsafeAllocator overlay), like the key-set records below.
     */
    private static final String[] AUTHLIB10_DISCOVERY_RECORD_ENTRIES = {
            "com/mojang/authlib/services/response/discovery/DiscoveryResponse.class",
            "com/mojang/authlib/services/response/discovery/Discovery.class",
            "com/mojang/authlib/services/response/discovery/Endpoints.class",
            "com/mojang/authlib/services/response/discovery/Endpoint.class",
    };
    /**
     * Texture URL prefixes of the discovery document's {@code profiles/getTexture} validUris
     * (https and http, {@code {textureId}} removed). Used only when discovery is unavailable.
     */
    static final String[] AUTHLIB10_FALLBACK_TEXTURE_PREFIXES = {
            "https://textures.minecraft.net/texture/",
            "http://textures.minecraft.net/texture/",
    };

    private AuthlibBrowserPatcher() {
    }

    private static final String SESSION_OWNER =
            "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService";
    private static final String UPLOADED_SKIN_PREFIX = "data:image/png;base64,";
    /** Mirrors BrowserUploadedSkin.isUploadedSkin; the PNG itself is validated on decode. */
    private static final int UPLOADED_SKIN_MAX_URL_LENGTH = 16_384;

    /** The authlib class names of one authlib major version, as jar entry names. */
    record Names(
            boolean services,
            String sessionEntry,
            String texturesPayloadEntry,
            String keyInfoEntry,
            String keySetResponseEntry,
            String keyDataEntry,
            String sessionConstructorDescriptor,
            String keyParseDescriptor) {
        static final Names AUTHLIB9 = new Names(
                false,
                SESSION_ENTRY,
                TEXTURES_PAYLOAD_ENTRY,
                KEY_INFO_ENTRY,
                KEY_SET_RESPONSE_ENTRY,
                KEY_DATA_ENTRY,
                "(Lcom/mojang/authlib/yggdrasil/ServicesKeySet;"
                        + "Ljava/net/Proxy;Lcom/mojang/authlib/Environment;)V",
                "([B)Lcom/mojang/authlib/yggdrasil/ServicesKeyInfo;");
        static final Names AUTHLIB10 = new Names(
                true,
                AUTHLIB10_SESSION_ENTRY,
                AUTHLIB10_TEXTURES_PAYLOAD_ENTRY,
                AUTHLIB10_KEY_INFO_ENTRY,
                AUTHLIB10_KEY_SET_RESPONSE_ENTRY,
                AUTHLIB10_KEY_DATA_ENTRY,
                "(Lcom/mojang/authlib/services/ServicesKeySet;"
                        + "Ljava/net/Proxy;Lcom/mojang/authlib/services/MinecraftServicesDiscoveryService;)V",
                "([B)Lcom/mojang/authlib/services/ServicesKeyInfo;");

        /** authlib 10 when its session service is present; authlib 9 otherwise. */
        static Names forJar(ZipFile jar) {
            boolean authlib10 = jar.getEntry(AUTHLIB10_SESSION_ENTRY) != null;
            boolean authlib9 = jar.getEntry(SESSION_ENTRY) != null;
            if (authlib10 && authlib9) {
                throw new IllegalStateException("authlib jar " + jar.getName()
                        + " contains both the authlib 9 and the authlib 10 session service");
            }
            return authlib10 ? AUTHLIB10 : AUTHLIB9;
        }

        String sessionOwner() {
            return internalName(sessionEntry);
        }

        String texturesPayloadOwner() {
            return internalName(texturesPayloadEntry);
        }
    }

    private static String internalName(String entry) {
        return entry.substring(0, entry.length() - ".class".length());
    }

    /**
     * Custom skins are carried as bounded data:image/png URLs. authlib's
     * TextureUrlChecker only accepts Mojang texture domains, so unpackTextures
     * rejected them and every player fell back to a default skin. Route the one
     * domain check through a helper that also accepts uploaded-skin data URLs;
     * all other URLs keep the vanilla domain rules. The helper is inlined here
     * rather than calling BrowserUploadedSkin because authlib is also compiled
     * into the server Worker, which must not pull in client NativeImage.
     *
     * <p>authlib 10 replaced the static TextureUrlChecker with the instance method
     * {@code MinecraftServicesDiscoveryService.isAllowedTextureDomain}, which throws
     * MinecraftClientException whenever the discovery document is unavailable (offline
     * session, blocked host, failed fetch) and is outside unpackTextures' exception table. The
     * helper therefore takes the discovery service, asks it first, and only when it throws falls
     * back to the static textures.minecraft.net prefixes of the discovery document.
     */
    static void allowUploadedSkinTextures(ClassNode sessionNode) {
        boolean services = sessionNode.name.equals(Names.AUTHLIB10.sessionOwner());
        MethodNode unpack = sessionNode.methods.stream()
                .filter(method -> method.name.equals("unpackTextures")
                        && method.desc.equals("(Lcom/mojang/authlib/properties/Property;)"
                                + "Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("authlib unpackTextures was not found"));
        if (services) {
            allowUploadedSkinTexturesAuthlib10(sessionNode, unpack);
            return;
        }
        int redirected = 0;
        for (var instruction = unpack.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("com/mojang/authlib/yggdrasil/TextureUrlChecker")
                    && call.name.equals("isAllowedTextureDomain")
                    && call.desc.equals("(Ljava/lang/String;)Z")) {
                call.owner = SESSION_OWNER;
                call.name = "gaiusAllowedTextureUrl";
                redirected++;
            }
        }
        if (redirected != 1) {
            throw new IllegalStateException(
                    "authlib texture URL check patch point count was " + redirected);
        }
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "gaiusAllowedTextureUrl",
                "(Ljava/lang/String;)Z",
                null,
                null);
        LabelNode vanilla = new LabelNode();
        InsnList code = helper.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new LdcInsnNode(UPLOADED_SKIN_PREFIX));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "startsWith", "(Ljava/lang/String;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "length", "()I", false));
        code.add(new IntInsnNode(Opcodes.SIPUSH, UPLOADED_SKIN_MAX_URL_LENGTH));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGT, vanilla));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(vanilla);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/mojang/authlib/yggdrasil/TextureUrlChecker",
                "isAllowedTextureDomain", "(Ljava/lang/String;)Z", false));
        code.add(new InsnNode(Opcodes.IRETURN));
        helper.maxStack = 2;
        helper.maxLocals = 1;
        sessionNode.methods.add(helper);
    }

    /**
     * authlib 10: {@code aload_0; getfield discoveryService; aload url; invokevirtual
     * MinecraftServicesDiscoveryService.isAllowedTextureDomain(String)Z} becomes
     * {@code invokestatic <session>.gaiusAllowedTextureUrl(MinecraftServicesDiscoveryService,
     * String)Z}; the receiver is already on the stack, so the stack shape and the method's
     * frames do not change.
     */
    private static void allowUploadedSkinTexturesAuthlib10(ClassNode sessionNode, MethodNode unpack) {
        String helperDescriptor = "(L" + AUTHLIB10_DISCOVERY_SERVICE + ";Ljava/lang/String;)Z";
        int redirected = 0;
        for (var instruction = unpack.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(AUTHLIB10_DISCOVERY_SERVICE)
                    && call.name.equals("isAllowedTextureDomain")
                    && call.desc.equals("(Ljava/lang/String;)Z")) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = sessionNode.name;
                call.name = "gaiusAllowedTextureUrl";
                call.desc = helperDescriptor;
                call.itf = false;
                redirected++;
            }
        }
        if (redirected != 1) {
            throw new IllegalStateException(
                    "authlib 10 texture URL check patch point count was " + redirected);
        }
        if (sessionNode.methods.stream().anyMatch(method -> method.name.equals("gaiusAllowedTextureUrl"))) {
            throw new IllegalStateException("authlib session already has gaiusAllowedTextureUrl");
        }
        // static boolean gaiusAllowedTextureUrl(MinecraftServicesDiscoveryService discovery, String url) {
        //     if (url.startsWith("data:image/png;base64,") && url.length() <= 16384) return true;
        //     try {
        //         return discovery.isAllowedTextureDomain(url);
        //     } catch (MinecraftClientException unavailable) {
        //         return url.startsWith("https://textures.minecraft.net/texture/")
        //                 || url.startsWith("http://textures.minecraft.net/texture/");
        //     }
        // }
        // The class is written without COMPUTE_FRAMES, so the frames are spelled out: locals are
        // always [discovery, url]; the handler frame holds the caught exception.
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "gaiusAllowedTextureUrl",
                helperDescriptor,
                null,
                null);
        LabelNode discovery = new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode unavailable = new LabelNode();
        LabelNode allowed = new LabelNode();
        InsnList code = helper.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new LdcInsnNode(UPLOADED_SKIN_PREFIX));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "startsWith", "(Ljava/lang/String;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, discovery));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "length", "()I", false));
        code.add(new IntInsnNode(Opcodes.SIPUSH, UPLOADED_SKIN_MAX_URL_LENGTH));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGT, discovery));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(discovery);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(tryStart);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, AUTHLIB10_DISCOVERY_SERVICE,
                "isAllowedTextureDomain", "(Ljava/lang/String;)Z", false));
        code.add(tryEnd);
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(unavailable);
        code.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1,
                new Object[] {AUTHLIB10_CLIENT_EXCEPTION}));
        code.add(new InsnNode(Opcodes.POP));
        for (String prefix : AUTHLIB10_FALLBACK_TEXTURE_PREFIXES) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new LdcInsnNode(prefix));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                    "startsWith", "(Ljava/lang/String;)Z", false));
            code.add(new JumpInsnNode(Opcodes.IFNE, allowed));
        }
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(allowed);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IRETURN));
        helper.tryCatchBlocks.add(new TryCatchBlockNode(
                tryStart, tryEnd, unavailable, AUTHLIB10_CLIENT_EXCEPTION));
        helper.maxStack = 2;
        helper.maxLocals = 2;
        sessionNode.methods.add(helper);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: AuthlibBrowserPatcher INPUT_JAR OUTPUT_CLASS");
        }
        Names names;
        byte[] input;
        byte[] sessionInput;
        byte[] textureInput;
        byte[] texturesPayloadInput;
        byte[] keyInfoInput;
        byte[] keySetResponseInput;
        byte[] keyDataInput;
        byte[][] discoveryRecordInputs = new byte[0][];
        try (ZipFile jar = new ZipFile(args[0])) {
            names = Names.forJar(jar);
            input = readEntry(jar, ENTRY, args[0]);
            sessionInput = readEntry(jar, names.sessionEntry(), args[0]);
            textureInput = readEntry(jar, TEXTURE_ENTRY, args[0]);
            texturesPayloadInput = readEntry(jar, names.texturesPayloadEntry(), args[0]);
            keyInfoInput = readEntry(jar, names.keyInfoEntry(), args[0]);
            keySetResponseInput = readEntry(jar, names.keySetResponseEntry(), args[0]);
            keyDataInput = readEntry(jar, names.keyDataEntry(), args[0]);
            if (names.services()) {
                discoveryRecordInputs = new byte[AUTHLIB10_DISCOVERY_RECORD_ENTRIES.length][];
                for (int index = 0; index < AUTHLIB10_DISCOVERY_RECORD_ENTRIES.length; index++) {
                    discoveryRecordInputs[index] = readEntry(
                            jar, AUTHLIB10_DISCOVERY_RECORD_ENTRIES[index], args[0]);
                }
            }
        }

        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        boolean found = false;
        boolean removedJavaProxy = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("createUrlConnection")
                    || !method.desc.equals(
                            "(Ljava/net/URL;)Ljava/net/HttpURLConnection;")) {
                continue;
            }
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserHttpProxy",
                    "proxyAuthentication",
                    "(Ljava/net/URL;)Ljava/net/URL;",
                    false));
            code.add(new VarInsnNode(Opcodes.ASTORE, 1));
            method.instructions.insert(code);
            method.maxStack = Math.max(method.maxStack, 1);
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("java/net/URL")
                        || !call.name.equals("openConnection")
                        || !call.desc.equals("(Ljava/net/Proxy;)Ljava/net/URLConnection;")) {
                    continue;
                }
                method.instructions.insertBefore(call, new org.objectweb.asm.tree.InsnNode(
                        Opcodes.POP));
                call.desc = "()Ljava/net/URLConnection;";
                removedJavaProxy = true;
            }
            found = true;
        }
        if (!found || !removedJavaProxy) {
            throw new IllegalStateException(
                    "MinecraftClient browser connection patch points were not found: method="
                            + found + " proxy=" + removedJavaProxy);
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
        // OUTPUT_CLASS is <dir>/com/mojang/authlib/minecraft/client/MinecraftClient.class, so
        // this is <dir>/com/mojang/authlib; every other class goes to its own entry path below it.
        Path authlibRoot = output.getParent().getParent().getParent();

        ClassNode sessionNode = new ClassNode();
        new ClassReader(sessionInput).accept(sessionNode, 0);
        MethodNode constructor = sessionNode.methods.stream()
                .filter(method -> method.name.equals("<init>")
                        && method.desc.equals(names.sessionConstructorDescriptor()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("authlib session constructor was not found"));
        boolean registered = false;
        for (var instruction = constructor.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("com/google/gson/GsonBuilder")
                    || !call.name.equals("registerTypeAdapter")) {
                continue;
            }
            InsnList registration = new InsnList();
            registration.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
            registration.add(new org.objectweb.asm.tree.LdcInsnNode(
                    org.objectweb.asm.Type.getType(
                            "Lcom/mojang/authlib/minecraft/MinecraftProfileTexture;")));
            registration.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserAuthlibGson",
                    "textureDeserializer",
                    "()Lcom/google/gson/JsonDeserializer;",
                    false));
            registration.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "com/google/gson/GsonBuilder",
                    "registerTypeHierarchyAdapter",
                    "(Ljava/lang/Class;Ljava/lang/Object;)Lcom/google/gson/GsonBuilder;",
                    false));
            registration.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
            var insertionPoint = instruction.getPrevious();
            while (insertionPoint != null) {
                if (insertionPoint instanceof org.objectweb.asm.tree.LdcInsnNode constant
                        && constant.cst instanceof org.objectweb.asm.Type type
                        && type.getClassName().equals("java.util.UUID")) {
                    break;
                }
                insertionPoint = insertionPoint.getPrevious();
            }
            if (insertionPoint == null) {
                throw new IllegalStateException("authlib Gson UUID registration anchor was not found");
            }
            constructor.instructions.insertBefore(insertionPoint, registration);
            registered = true;
            break;
        }
        if (!registered) {
            throw new IllegalStateException("authlib Gson registration patch point was not found");
        }
        // The textures payload is decoded by BrowserAuthlibGson.decodeTextures; its return type
        // is the payload record of the same authlib (the 26.3 source set's BrowserAuthlibGson
        // returns the services record), so both sides use names.texturesPayloadOwner().
        String payloadOwner = names.texturesPayloadOwner();
        boolean decodedTextures = false;
        for (MethodNode method : sessionNode.methods) {
            if (!method.name.equals("unpackTextures")
                    || !method.desc.equals("(Lcom/mojang/authlib/properties/Property;)"
                            + "Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;")) {
                continue;
            }
            for (var instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("com/google/gson/Gson")
                        || !call.name.equals("fromJson")
                        || !call.desc.equals("(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;")) {
                    continue;
                }
                var payloadClass = instruction.getPrevious();
                var json = payloadClass != null ? payloadClass.getPrevious() : null;
                var gson = json != null ? json.getPrevious() : null;
                var owner = gson != null ? gson.getPrevious() : null;
                if (!(payloadClass instanceof org.objectweb.asm.tree.LdcInsnNode constant)
                        || !(constant.cst instanceof org.objectweb.asm.Type type)
                        || !type.getInternalName().equals(payloadOwner)
                        || !(json instanceof VarInsnNode)
                        || !(gson instanceof org.objectweb.asm.tree.FieldInsnNode)
                        || !(owner instanceof VarInsnNode)) {
                    continue;
                }
                method.instructions.remove(owner);
                method.instructions.remove(gson);
                method.instructions.remove(payloadClass);
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = "dev/gaius/browser/BrowserAuthlibGson";
                call.name = "decodeTextures";
                call.desc = "(Ljava/lang/String;)L" + payloadOwner + ";";
                call.itf = false;
                decodedTextures = true;
                break;
            }
        }
        if (!decodedTextures) {
            throw new IllegalStateException("authlib texture Gson decode patch point was not found");
        }
        constructor.maxStack = Math.max(constructor.maxStack, 3);
        allowUploadedSkinTextures(sessionNode);
        ClassWriter sessionWriter = new ClassWriter(0);
        sessionNode.accept(sessionWriter);
        writeEntry(authlibRoot, names.sessionEntry(), sessionWriter.toByteArray());

        ClassNode textureNode = new ClassNode();
        new ClassReader(textureInput).accept(textureNode, 0);
        boolean hasNoArgsConstructor = textureNode.methods.stream()
                .anyMatch(method -> method.name.equals("<init>") && method.desc.equals("()V"));
        if (!hasNoArgsConstructor) {
            MethodNode noArgsConstructor = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "<init>",
                    "()V",
                    null,
                    null);
            noArgsConstructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            noArgsConstructor.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(""));
            noArgsConstructor.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/Collections",
                    "emptyMap",
                    "()Ljava/util/Map;",
                    false));
            noArgsConstructor.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    TEXTURE_ENTRY.substring(0, TEXTURE_ENTRY.length() - ".class".length()),
                    "<init>",
                    "(Ljava/lang/String;Ljava/util/Map;)V",
                    false));
            noArgsConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
            noArgsConstructor.maxStack = 3;
            noArgsConstructor.maxLocals = 1;
            textureNode.methods.add(noArgsConstructor);
        }
        ClassWriter textureWriter = new ClassWriter(0);
        textureNode.accept(textureWriter);
        Path textureOutput = output.getParent().getParent().resolve("MinecraftProfileTexture.class");
        Files.createDirectories(textureOutput.getParent());
        Files.write(textureOutput, textureWriter.toByteArray());

        ClassNode payloadNode = new ClassNode();
        new ClassReader(texturesPayloadInput).accept(payloadNode, 0);
        boolean payloadHasNoArgs = payloadNode.methods.stream()
                .anyMatch(method -> method.name.equals("<init>") && method.desc.equals("()V"));
        if (!payloadHasNoArgs) {
            MethodNode payloadConstructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            payloadConstructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            payloadConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LCONST_0));
            payloadConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
            payloadConstructor.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(""));
            payloadConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
            payloadConstructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/util/Collections", "emptyMap", "()Ljava/util/Map;", false));
            payloadConstructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    payloadOwner, "<init>",
                    "(JLjava/util/UUID;Ljava/lang/String;ZLjava/util/Map;)V", false));
            payloadConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
            payloadConstructor.maxStack = 7;
            payloadConstructor.maxLocals = 1;
            payloadNode.methods.add(payloadConstructor);
        }
        ClassWriter payloadWriter = new ClassWriter(0);
        payloadNode.accept(payloadWriter);
        writeEntry(authlibRoot, names.texturesPayloadEntry(), payloadWriter.toByteArray());

        String keyInfoOwner = internalName(names.keyInfoEntry());
        ClassNode keyInfoNode = new ClassNode();
        new ClassReader(keyInfoInput).accept(keyInfoNode, 0);
        MethodNode parseKeyInfo = keyInfoNode.methods.stream()
                .filter(method -> method.name.equals("parse")
                        && method.desc.equals(names.keyParseDescriptor()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "authlib services key parse method was not found"));
        parseKeyInfo.instructions.clear();
        parseKeyInfo.tryCatchBlocks.clear();
        parseKeyInfo.localVariables = null;
        parseKeyInfo.visibleTypeAnnotations = null;
        parseKeyInfo.invisibleTypeAnnotations = null;
        InsnList browserKeyParse = new InsnList();
        browserKeyParse.add(new org.objectweb.asm.tree.TypeInsnNode(
                Opcodes.NEW,
                keyInfoOwner));
        browserKeyParse.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
        browserKeyParse.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserKeyParse.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "parseRsaPublicKey",
                "([B)Ljava/security/PublicKey;",
                false));
        browserKeyParse.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                keyInfoOwner,
                "<init>",
                "(Ljava/security/PublicKey;)V",
                false));
        browserKeyParse.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        parseKeyInfo.instructions.add(browserKeyParse);
        parseKeyInfo.maxStack = 3;
        parseKeyInfo.maxLocals = 1;
        ClassWriter keyInfoWriter = new ClassWriter(0);
        keyInfoNode.accept(keyInfoWriter);
        writeEntry(authlibRoot, names.keyInfoEntry(), keyInfoWriter.toByteArray());

        ClassNode keySetResponseNode = new ClassNode();
        new ClassReader(keySetResponseInput).accept(keySetResponseNode, 0);
        ensureNoArgsConstructor(
                keySetResponseNode,
                keySetResponseNode.methods.stream()
                        .anyMatch(method -> method.name.equals("<init>") && method.desc.equals("()V"))
                        ? null
                        : keySetResponseNoArgsBody(internalName(names.keySetResponseEntry())),
                3);
        ClassWriter keySetResponseWriter = new ClassWriter(0);
        keySetResponseNode.accept(keySetResponseWriter);
        writeEntry(authlibRoot, names.keySetResponseEntry(), keySetResponseWriter.toByteArray());

        ClassNode keyDataNode = new ClassNode();
        new ClassReader(keyDataInput).accept(keyDataNode, 0);
        ensureNoArgsConstructor(
                keyDataNode,
                keyDataNode.methods.stream()
                        .anyMatch(method -> method.name.equals("<init>") && method.desc.equals("()V"))
                        ? null
                        : keyDataNoArgsBody(internalName(names.keyDataEntry())),
                2);
        ClassWriter keyDataWriter = new ClassWriter(0);
        keyDataNode.accept(keyDataWriter);
        writeEntry(authlibRoot, names.keyDataEntry(), keyDataWriter.toByteArray());

        for (int index = 0; index < discoveryRecordInputs.length; index++) {
            ClassNode recordNode = new ClassNode();
            new ClassReader(discoveryRecordInputs[index]).accept(recordNode, 0);
            addNullRecordConstructor(recordNode);
            ClassWriter recordWriter = new ClassWriter(0);
            recordNode.accept(recordWriter);
            writeEntry(authlibRoot, AUTHLIB10_DISCOVERY_RECORD_ENTRIES[index],
                    recordWriter.toByteArray());
        }
    }

    private static byte[] readEntry(ZipFile jar, String entryName, String jarPath)
            throws IOException {
        var entry = jar.getEntry(entryName);
        if (entry == null) {
            throw new IllegalStateException(entryName + " not found in " + jarPath);
        }
        try (var stream = jar.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    /** Writes {@code bytes} to the path of jar entry {@code entry} below {@code authlibRoot}. */
    private static void writeEntry(Path authlibRoot, String entry, byte[] bytes) throws IOException {
        String prefix = "com/mojang/authlib/";
        if (!entry.startsWith(prefix)) {
            throw new IllegalArgumentException("not an authlib entry: " + entry);
        }
        Path target = authlibRoot.resolve(entry.substring(prefix.length()));
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    /**
     * Adds {@code public <init>()V} that passes null/0 for every component to the record's
     * canonical constructor (the one whose parameters are the record components in order).
     * Gson overwrites the fields it finds in the JSON document.
     */
    private static void addNullRecordConstructor(ClassNode recordNode) {
        if (recordNode.recordComponents == null || recordNode.recordComponents.isEmpty()) {
            throw new IllegalStateException(recordNode.name + " is not a record");
        }
        if (recordNode.methods.stream().anyMatch(
                method -> method.name.equals("<init>") && method.desc.equals("()V"))) {
            throw new IllegalStateException(recordNode.name + " already has a no-argument constructor");
        }
        StringBuilder canonical = new StringBuilder("(");
        recordNode.recordComponents.forEach(component -> canonical.append(component.descriptor));
        String canonicalDescriptor = canonical.append(")V").toString();
        if (recordNode.methods.stream().noneMatch(method -> method.name.equals("<init>")
                && method.desc.equals(canonicalDescriptor))) {
            throw new IllegalStateException(recordNode.name
                    + " has no canonical constructor " + canonicalDescriptor);
        }
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int stack = 1;
        for (var type : org.objectweb.asm.Type.getArgumentTypes(canonicalDescriptor)) {
            switch (type.getSort()) {
                case org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.ARRAY ->
                        body.add(new InsnNode(Opcodes.ACONST_NULL));
                case org.objectweb.asm.Type.LONG -> body.add(new InsnNode(Opcodes.LCONST_0));
                case org.objectweb.asm.Type.FLOAT -> body.add(new InsnNode(Opcodes.FCONST_0));
                case org.objectweb.asm.Type.DOUBLE -> body.add(new InsnNode(Opcodes.DCONST_0));
                default -> body.add(new InsnNode(Opcodes.ICONST_0));
            }
            stack += type.getSize();
        }
        body.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, recordNode.name, "<init>", canonicalDescriptor, false));
        ensureNoArgsConstructor(recordNode, body, stack);
    }

    private static void ensureNoArgsConstructor(
            ClassNode node, InsnList body, int maxStack) {
        if (body == null) {
            return;
        }
        MethodNode noArgsConstructor = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "<init>",
                "()V",
                null,
                null);
        noArgsConstructor.instructions.add(body);
        noArgsConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        noArgsConstructor.maxStack = maxStack;
        noArgsConstructor.maxLocals = 1;
        node.methods.add(noArgsConstructor);
    }

    private static InsnList keySetResponseNoArgsBody(String owner) {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Collections",
                "emptyList",
                "()Ljava/util/List;",
                false));
        body.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Collections",
                "emptyList",
                "()Ljava/util/List;",
                false));
        body.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "<init>",
                "(Ljava/util/List;Ljava/util/List;)V",
                false));
        return body;
    }

    private static InsnList keyDataNoArgsBody(String owner) {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
        body.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/nio/ByteBuffer",
                "allocate",
                "(I)Ljava/nio/ByteBuffer;",
                false));
        body.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                owner,
                "<init>",
                "(Ljava/nio/ByteBuffer;)V",
                false));
        return body;
    }
}
