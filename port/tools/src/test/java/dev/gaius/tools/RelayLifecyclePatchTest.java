package dev.gaius.tools;

import static dev.gaius.tools.patches.support.ClientPatchSupport.find;
import static dev.gaius.tools.patches.support.ClientPatchSupport.previousOpcode;
import static dev.gaius.tools.patches.support.ClientPatchSupport.read;

import dev.gaius.tools.patches.network.NetworkPatches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Runs the lifecycle patchers on the named vanilla jars for 1.21.11, 26.2 and 26.3. */
public final class RelayLifecyclePatchTest {
    private static final String SCREENS = "net/minecraft/client/gui/screens/";
    private static final String JOIN = SCREENS + "multiplayer/JoinMultiplayerScreen";
    private static final String CONNECT = SCREENS + "ConnectScreen";
    private static final String SELECTION = SCREENS + "multiplayer/ServerSelectionList";
    private static final String BASE_SELECTION = "net/minecraft/client/gui/components/AbstractSelectionList";
    private static final String ENTRY = "(L" + SELECTION + "$Entry;)V";
    private static final String TARGET_HOOK = "serverListTargetChanged";
    private static final String CONNECTION = "net/minecraft/network/Connection";
    private static final String RELAYS = "dev/gaius/browser/BrowserRelaysScreen";
    private static final String ID = "gaius$relayWarmupAttempt";
    private static final String SERVER = "Lnet/minecraft/client/multiplayer/ServerData;";
    private static final String FUTURE = ")Lio/netty/channel/ChannelFuture;";
    private static final String REMOTE_ADDRESS = "(Ljava/net/InetSocketAddress;"
            + "Lnet/minecraft/server/network/EventLoopGroupHolder;L" + CONNECTION + ";";

    private RelayLifecyclePatchTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: RelayLifecyclePatchTest <scratch> <named-client.jar>...");
        }
        for (int i = 1; i < args.length; i++) {
            Path output = Files.createDirectories(Path.of(args[0], "profile-" + i));
            checkPatch(args[i], output);
            System.out.println("Verified relay lifecycle patch against " + args[i]);
        }
    }

    private static void checkPatch(String jar, Path output) throws Exception {
        NetworkPatches.patchServerDataTransportProtocol(jar,
                output.resolve("net/minecraft/client/multiplayer/ServerData.class"));
        NetworkPatches.patchJoinMultiplayerScreenRelays(jar, output.resolve(JOIN + ".class"));
        NetworkPatches.patchConnectScreenBrowserRecovery(jar, output);
        NetworkPatches.patchConnectionBrowserWebSocket(jar, output.resolve(CONNECTION + ".class"));

        ClassNode list = read(output.resolve(JOIN + ".class"));
        MethodNode init = find(list, "init", "()V");
        checkSelectionAfterWarmup(init, "serverListInitialized");
        check(calls(init, RELAYS, "joinButton").size() == 1, "existing Relays button is kept");
        MethodNode reposition = find(list, "repositionElements", "()V");
        checkSelectionAfterWarmup(reposition, "serverListRepositioned");
        MethodNode added = find(list, "added", "()V");
        check(calls(added, list.superName, "added").size() == 1 && atReturns(added, "serverListAdded"),
                "actual added lifecycle keeps its vanilla superclass call");
        for (String method : List.of("onClose", "removed")) {
            MethodNode exit = find(list, method, "()V");
            var cleanup = one(exit, RELAYS, "serverListRemoved");
            check(loadsThis(cleanup) && previousOpcode(previousOpcode(cleanup)) == null,
                    method + " releases its own list before vanilla screen switching");
        }
        MethodNode join = find(list, "join", "(" + SERVER + ")V");
        var parsedStart = one(join, RELAYS, "startConnecting");
        check(calls(join, CONNECT, "startConnecting").isEmpty(), "vanilla entry point is wrapped exactly once");
        var parser = one(join, "net/minecraft/client/multiplayer/resolver/ServerAddress", "parseString");
        check(join.instructions.indexOf(parser) < join.instructions.indexOf(parsedStart),
                "vanilla parses before an attempt can reserve");

        ClassNode selection = read(output.resolve(SELECTION + ".class"));
        MethodNode select = find(selection, "setSelected", ENTRY);
        var vanillaChange = one(select, JOIN, "onSelectedChange");
        var selectionHook = one(select, RELAYS, TARGET_HOOK);
        check(atReturns(select, TARGET_HOOK)
                && select.instructions.indexOf(vanillaChange) < select.instructions.indexOf(selectionHook),
                "typed selection notifies the target hook after vanilla has updated the screen");
        check(selectionHook.desc.equals("(Lnet/minecraft/client/Minecraft;L" + JOIN + ";L" + SELECTION + ";)V"),
                "target hook receives its Minecraft, owner screen and selection widget");
        MethodNode bridge = find(selection, "setSelected", "(L" + BASE_SELECTION + "$Entry;)V");
        check(one(bridge, SELECTION, "setSelected").desc.equals(ENTRY) && calls(bridge, RELAYS, TARGET_HOOK).isEmpty(),
                "generic mouse/keyboard selection dispatches through the typed hook only once");
        MethodNode refresh = find(selection, "refreshEntries", "()V");
        check(atReturns(refresh, TARGET_HOOK), "refresh observes a selection cleared without invoking the setter");

        ClassNode screen = read(output.resolve(CONNECT + ".class"));
        check(screen.fields.stream().anyMatch(f -> f.name.equals(ID) && f.desc.equals("I")),
                "attempt identity belongs to the ConnectScreen instance");
        MethodNode pending = screen.methods.stream().filter(m -> m.name.equals("connect")).findFirst().orElseThrow();
        var bind = one(pending, RELAYS, "connectingAttempt");
        check(bind.getNext() instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(CONNECT) && field.name.equals(ID),
                "connector snapshots the scoped attempt on the exact screen");
        var launch = one(pending, "java/lang/Thread", "start");
        check(pending.instructions.indexOf(bind) < pending.instructions.indexOf(launch),
                "identity is bound before thread start");
        check(loadsAttempt(one(find(screen, "removed", "()V"), RELAYS, "releaseRelayWarmup")),
                "Cancel and screen replacement release the leaving attempt");

        ClassNode connector = read(output.resolve(CONNECT + "$1.class"));
        MethodNode run = find(connector, "run", "()V");
        var channel = one(run, CONNECTION, "gaius$connectWithRelayWarmup");
        check(channel.desc.equals(REMOTE_ADDRESS + "I" + FUTURE) && loadsAttempt(channel),
                "only the owning connector carries the attempt to the channel");
        check(atReturns(run, "releaseRelayWarmup"), "every connector return releases an unclaimed reservation");
        var cleanup = run.tryCatchBlocks.getLast();
        check(cleanup.type == null && run.instructions.indexOf(cleanup.start) < run.instructions.indexOf(channel)
                && run.instructions.indexOf(channel) < run.instructions.indexOf(cleanup.end),
                "finally also covers unchecked connector failures");
        check(calls(run, RELAYS, "releaseRelayWarmup").stream().anyMatch(call ->
                run.instructions.indexOf(call) > run.instructions.indexOf(cleanup.handler) && loadsAttempt(call)),
                "finally uses the exiting connector identity");

        ClassNode connection = read(output.resolve(CONNECTION + ".class"));
        var regularConnect = one(find(connection, "connect", REMOTE_ADDRESS + FUTURE), CONNECTION,
                "gaius$connectWithRelayWarmup");
        check(previousOpcode(regularConnect).getOpcode() == Opcodes.ICONST_0,
                "a concurrent ordinary connection cannot claim a list reservation");
        one(find(connection, channel.name, channel.desc), RELAYS, "connectChannel");
        check(calls(find(connection, "connectToLocalServer", "(Ljava/net/SocketAddress;)L" + CONNECTION + ";"),
                RELAYS, "connectChannel").isEmpty(), "local connections bypass reservation handoff");
    }

    private static void checkSelectionAfterWarmup(MethodNode method, String warmupHook) {
        var warm = one(method, RELAYS, warmupHook);
        var target = one(method, RELAYS, TARGET_HOOK);
        check(atReturns(method, TARGET_HOOK) && method.instructions.indexOf(warm) < method.instructions.indexOf(target),
                method.name + " samples selection only after its legacy warmup hook");
    }

    private static boolean atReturns(MethodNode method, String hook) {
        boolean found = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                found = true;
                if (!(previousOpcode(instruction) instanceof MethodInsnNode call)
                        || !call.owner.equals(RELAYS) || !call.name.equals(hook)) {
                    return false;
                }
            }
        }
        return found;
    }

    private static boolean loadsThis(MethodInsnNode call) {
        return previousOpcode(call) instanceof VarInsnNode variable
                && variable.getOpcode() == Opcodes.ALOAD && variable.var == 0;
    }

    private static boolean loadsAttempt(MethodInsnNode call) {
        return previousOpcode(call) instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(CONNECT) && field.name.equals(ID);
    }

    private static MethodInsnNode one(MethodNode method, String owner, String name) {
        var matches = calls(method, owner, name);
        check(matches.size() == 1, method.name + " calls " + owner + "." + name + " exactly once");
        return matches.getFirst();
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                matches.add(call);
            }
        }
        return matches;
    }

    private static void check(boolean expected, String description) {
        if (!expected) {
            throw new AssertionError(description);
        }
    }
}
