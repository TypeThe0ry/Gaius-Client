import dev.gaius.tools.patches.serverworker.ServerWorkerPatches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Applies the production patch to an original input; checks complete executable method bodies. */
public final class ServerMovementPatchTest {
    private static final String OWNER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
    private static final String HOOK = "dev/gaius/browser/BrowserIntegratedServerMain";
    private static final String CACHE = "net/minecraft/server/level/ServerChunkCache";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ClassNode read(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static String body(MethodNode method) {
        var text = new Textifier();
        method.accept(new TraceMethodVisitor(text));
        var output = new java.io.StringWriter();
        text.print(new java.io.PrintWriter(output));
        return output.toString();
    }

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        Path directory = Path.of(args[1]);
        Path output = directory.resolve(OWNER + ".class");
        ClassNode original;
        try (var zip = new ZipFile(jar); var in = zip.getInputStream(zip.getEntry(OWNER + ".class"))) {
            original = read(in.readAllBytes());
        }
        ServerWorkerPatches.patchServerGamePacketListenerBrowserWorker(jar, output);
        var patched = read(Files.readAllBytes(output));
        Map<String, String> expected = new HashMap<>();
        for (var method : original.methods) expected.put(method.name + method.desc, body(method));
        int moves = 0, acknowledgements = 0;
        StringBuilder proof = new StringBuilder();
        for (var method : patched.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(HOOK)) continue;
                if (call.name.equals("moveServerPlayerChunkTracking")) {
                    check(method.name.equals("handleMovePlayer") || method.name.equals("handlePlayerPositionChange"),
                            "tracking redirect outside movement body");
                    check(call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.desc.equals("(L" + CACHE + ";Lnet/minecraft/server/level/ServerPlayer;)V"),
                            "tracking redirect descriptor");
                    call.setOpcode(Opcodes.INVOKEVIRTUAL);
                    call.owner = CACHE; call.name = "move";
                    call.desc = "(Lnet/minecraft/server/level/ServerPlayer;)V";
                    moves++;
                } else if (call.name.equals("acknowledgeChunkBatch")) {
                    check(method.name.equals("handleChunkBatchReceived") && call.desc.equals("()V"),
                            "acknowledgement outside batch handler");
                    method.instructions.remove(call);
                    acknowledgements++;
                } else {
                    throw new AssertionError("movement body bypass or unexpected hook: " + call.name);
                }
            }
            String key = method.name + method.desc;
            check(body(method).equals(expected.remove(key)), "original method body changed: " + key);
            if (method.name.equals("handleMovePlayer") || method.name.equals("handlePlayerPositionChange")
                    || method.name.equals("handleAcceptTeleportPacket") || method.name.equals("updateAwaitingTeleport")
                    || method.name.equals("handlePlayerKnownMovement") || method.name.equals("handleClientTickEnd")
                    || method.name.endsWith("CollidingWithAnythingNew")) {
                proof.append(key).append('\n').append(body(method)).append('\n');
            }
        }
        check(expected.isEmpty(), "original methods removed");
        check(moves == 2 && acknowledgements == 1, "required hooks missing or duplicated");
        String movement = proof.toString();
        for (String operation : new String[]{"updateAwaitingTeleport", "jumpFromGround", "checkMovementStatistics",
                "doCheckFallDamage", "handlePlayerKnownMovement", "receivedMovementThisTick",
                "CollidingWithAnythingNew", "horizontalCollision"}) {
            check(movement.contains(operation), "original movement operation missing: " + operation);
        }
        Files.writeString(directory.resolve("retained-movement.txt"), movement);
        System.out.println("PASS: production patch retains every original method body after reversing only tracking/ACK hooks; "
                + patched.methods.size() + " methods, teleport/collision/jump/statistics/fall/tick bookkeeping retained");
    }
}
