import dev.gaius.tools.PatchTargets;
import dev.gaius.tools.patches.network.NetworkPatches;
import dev.gaius.tools.patches.support.ClientPatchSupport;
import dev.gaius.tools.patches.telemetry.TelemetryPatches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Real jar FEATURE -> TELEMETRY composition, plus execution of the retained entry hook. */
public final class ClientLoadingCompositionTest implements Opcodes {
    private static final String OWNER = "net/minecraft/client/multiplayer/ClientPacketListener";
    private static final String GL = "org/lwjgl/opengl/BrowserOpenGL";
    private static final List<String> events = new ArrayList<>();
    private static int checks;
    public static void event(String label) { events.add(label); }
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
    private static long warnings(ClassNode node) {
        return node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof FieldInsnNode f && f.name.equals("UNSECURE_SERVER_WARNING")).count();
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static boolean loadingTarget(MethodNode method) {
        if (Set.of("handleLogin", "startWaitingForNewLevel", "handleLevelChunkWithLight",
                "handleChunkBatchStart", "handleChunkBatchFinished", "notifyPlayerLoaded").contains(method.name)) return true;
        return Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call
                && ((call.owner.equals("net/minecraft/client/multiplayer/LevelLoadTracker")
                    && Set.of("tickClientLoad", "loadingPacketsReceived").contains(call.name))
                || (call.owner.equals(OWNER) && call.name.equals("notifyPlayerLoaded"))));
    }
    private static byte[] bytes(ClassNode node) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer); return writer.toByteArray();
    }
    private static ClassNode empty(String name) {
        var node = new ClassNode(); node.version = V21; node.access = ACC_PUBLIC | ACC_SUPER;
        node.name = name; node.superName = "java/lang/Object"; return node;
    }
    private static final class HookLoader extends ClassLoader {
        final byte[] hook;
        HookLoader(byte[] hook) { super(ClientLoadingCompositionTest.class.getClassLoader()); this.hook = hook; }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(GL.replace('/', '.'))) return super.loadClass(name, resolve);
            var loaded = findLoadedClass(name);
            if (loaded == null) loaded = define(name, hook);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
    private static void executeRetainedHook(ClassNode node) throws Exception {
        var method = method(node, "handleLogin");
        var first = method.instructions.getFirst();
        while (first.getOpcode() < 0) first = first.getNext();
        var call = first.getNext(); while (call.getOpcode() < 0) call = call.getNext();
        check(first instanceof LdcInsnNode ldc && ldc.cst.equals("client.handleLogin"), "retained telemetry entry label");
        check(call instanceof MethodInsnNode m && m.owner.equals(GL) && m.name.equals("reportMinecraftEvent"), "retained telemetry entry call");
        var hook = empty(GL);
        var forward = new MethodNode(ACC_PUBLIC | ACC_STATIC, "reportMinecraftEvent", "(Ljava/lang/String;)V", null, null);
        forward.instructions.add(new VarInsnNode(ALOAD, 0));
        forward.instructions.add(new MethodInsnNode(INVOKESTATIC, "ClientLoadingCompositionTest", "event", forward.desc, false));
        forward.instructions.add(new InsnNode(RETURN)); hook.methods.add(forward);
        var fixture = empty("gaius/tests/LoadingPrefix");
        var run = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        run.instructions.add(first.clone(new HashMap<>())); run.instructions.add(call.clone(new HashMap<>()));
        run.instructions.add(new InsnNode(RETURN)); fixture.methods.add(run);
        var marker = method(node, "gaius$compositionMarker");
        var copy = new MethodNode(marker.access, marker.name, marker.desc, null, null); marker.accept(copy); fixture.methods.add(copy);
        var loader = new HookLoader(bytes(hook));
        var type = loader.define("gaius.tests.LoadingPrefix", bytes(fixture));
        type.getMethod("run").invoke(null);
        check(events.equals(List.of("client.handleLogin")), "actual composed entry hook executes once on JVM");
        check(type.getMethod(marker.name).invoke(null).equals(37), "earlier composed method still executes on JVM");
    }
    private static void executeWarningTail(ClassNode node) throws Exception {
        var fixture = empty("gaius/tests/LoginWarningTail");
        fixture.fields.add(new FieldNode(ACC_PUBLIC, "serverData", "Ljava/lang/Object;", null, null));
        fixture.fields.add(new FieldNode(ACC_PUBLIC, "seenInsecureChatWarning", "Z", null, null));
        fixture.fields.add(new FieldNode(ACC_PUBLIC, "secure", "Z", null, null));
        var init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(RETURN)); fixture.methods.add(init);
        var secure = new MethodNode(ACC_PUBLIC, "enforcesSecureChat", "()Z", null, null);
        secure.instructions.add(new VarInsnNode(ALOAD, 0));
        secure.instructions.add(new FieldInsnNode(GETFIELD, fixture.name, "secure", "Z"));
        secure.instructions.add(new InsnNode(IRETURN)); fixture.methods.add(secure);
        var login = method(node, "handleLogin");
        var boundary = Arrays.stream(login.instructions.toArray()).filter(i -> i instanceof FieldInsnNode f
                && f.getOpcode() == PUTFIELD && f.name.equals("serverEnforcesSecureChat")).findFirst().orElseThrow();
        var labels = new HashMap<LabelNode, LabelNode>();
        for (var i = boundary.getNext(); i != null; i = i.getNext()) if (i instanceof LabelNode label) labels.put(label, new LabelNode());
        var run = new MethodNode(ACC_PUBLIC, "run", "()V", null, null);
        for (var i = boundary.getNext(); i != null; i = i.getNext()) {
            if (i.getType() == AbstractInsnNode.FRAME || i.getType() == AbstractInsnNode.LINE) continue;
            var copy = i.clone(labels);
            if (copy instanceof FieldInsnNode f) {
                check(f.owner.equals(OWNER) && Set.of("serverData", "seenInsecureChatWarning").contains(f.name), "warning tail retains only vanilla policy fields");
                f.owner = fixture.name; if (f.name.equals("serverData")) f.desc = "Ljava/lang/Object;";
            }
            if (copy instanceof MethodInsnNode call) {
                check(call.owner.equals(OWNER) && call.name.equals("enforcesSecureChat") && call.desc.equals("()Z"), "warning tail retains only secure-chat policy call");
                call.owner = fixture.name;
            }
            run.instructions.add(copy);
        }
        fixture.methods.add(run);
        var type = new HookLoader(new byte[0]).define(fixture.name.replace('/', '.'), bytes(fixture));
        for (boolean hasServer : List.of(false, true)) for (boolean seen : List.of(false, true)) for (boolean isSecure : List.of(false, true)) {
            var instance = type.getConstructor().newInstance();
            type.getField("serverData").set(instance, hasServer ? new Object() : null);
            type.getField("seenInsecureChatWarning").setBoolean(instance, seen);
            type.getField("secure").setBoolean(instance, isSecure);
            type.getMethod("run").invoke(instance);
            check(type.getField("seenInsecureChatWarning").getBoolean(instance) == (seen || (hasServer && !isSecure)), "actual warning tail preserves branch merge and seen flag");
        }
    }
    private static void rejectUnknownFactory(ClassNode raw, Path root) throws Exception {
        var login = method(raw, "handleLogin");
        var factory = Arrays.stream(login.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                && call.owner.equals("net/minecraft/client/gui/components/toasts/SystemToast") && call.name.equals("multiline")).findFirst();
        if (factory.isEmpty()) return;
        var id = Arrays.stream(login.instructions.toArray()).filter(i -> i instanceof FieldInsnNode f && f.name.equals("UNSECURE_SERVER_WARNING")).findFirst().orElseThrow();
        var minecraft = ClientPatchSupport.previousOpcode(id);
        ((FieldInsnNode) minecraft).name = "unknownMinecraft";
        var writer = new ClassWriter(0); raw.accept(writer);
        var jar = root.resolve("unknown-factory.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(OWNER + ".class")); zip.write(writer.toByteArray()); zip.closeEntry();
        }
        var output = root.resolve("rejected");
        boolean rejected = false;
        try { NetworkPatches.patchSuppressInsecureServerToast(jar.toString(), output); }
        catch (java.io.IOException expected) { rejected = expected.getMessage().contains("receiver shape changed"); }
        check(rejected && !Files.exists(output.resolve(OWNER + ".class")), "unknown factory receiver rejected before output");
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]); Files.createDirectories(root);
        var raw = ClientPatchSupport.read(args[0], OWNER + ".class");
        check(warnings(raw) == 1, "official jar contains one insecure warning");
        var file = root.resolve("composed/" + OWNER + ".class");
        NetworkPatches.patchSuppressInsecureServerToast(args[0], root.resolve("composed"));
        var feature = ClientPatchSupport.read(file);
        check(warnings(feature) == 0, "FEATURE removes only insecure warning");
        var marker = new MethodNode(ACC_PUBLIC | ACC_STATIC, "gaius$compositionMarker", "()I", null, null);
        marker.instructions.add(new IntInsnNode(BIPUSH, 37)); marker.instructions.add(new InsnNode(IRETURN)); marker.maxStack = 1;
        feature.methods.add(marker); ClientPatchSupport.write(feature, file);
        TelemetryPatches.patchClientPacketListenerLoadingDiagnostics(args[0], file);
        var composed = ClientPatchSupport.read(file);
        check(warnings(composed) == 0, "TELEMETRY must not restore insecure warning");
        dev.gaius.tools.HierarchyClassWriter.source(composed, args[0]);
        ClientPatchSupport.writeComputeFrames(composed, root.resolve("framed/" + OWNER + ".class"));
        check(true, "complete composed class supports production frame computation");
        executeRetainedHook(composed);
        executeWarningTail(composed);
        for (var before : feature.methods) {
            var after = composed.methods.stream().filter(m -> m.name.equals(before.name) && m.desc.equals(before.desc)).findFirst().orElseThrow();
            if (!loadingTarget(before)) check(PatchTargets.methodHash(before).equals(PatchTargets.methodHash(after)), "unrelated composed method retained: " + before.name);
        }
        var login = method(composed, "handleLogin");
        var label = Arrays.stream(login.instructions.toArray()).filter(i -> i instanceof LdcInsnNode ldc && ldc.cst.equals("client.handleLogin")).findFirst().orElseThrow();
        var event = label.getNext(); while (event.getOpcode() < 0) event = event.getNext();
        login.instructions.remove(event); login.instructions.remove(label);
        check(PatchTargets.methodHash(method(feature, "handleLogin")).equals(PatchTargets.methodHash(login)), "removing only telemetry restores the complete FEATURE-composed login method");
        var standalone = root.resolve("standalone/" + OWNER + ".class");
        TelemetryPatches.patchClientPacketListenerLoadingDiagnostics(args[0], standalone);
        check(warnings(ClientPatchSupport.read(standalone)) == 1, "absent output falls back to vanilla without implicitly enabling FEATURE");
        rejectUnknownFactory(raw, root);
        System.out.println("CLIENT_LOADING_COMPOSITION_PASS checks=" + checks + " raw=1 afterFeature=0 afterTelemetry=0 standalone=1 jar=" + args[0]);
    }
}
