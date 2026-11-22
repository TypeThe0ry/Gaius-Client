package dev.gaius.tools;

import dev.gaius.browser.transport.EaglerHandshakeCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** v5 byte fixtures authored from the wire spec, plus replay of a synthetic loopback capture. */
public final class EaglerRecordReplayTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final int FIXTURE_PROTOCOL = 765;
    private static final String NAME = "Replay_1";
    private static final byte[] VERSION = bytes("02000502fd0a46616b654561676c6572067265706c617900000000");
    // Standard bits 0 and 7 (two-byte VarInt), two versions, one extension UUID and version.
    private static final byte[] ALLOW = bytes("05085265706c61795f31112233445566778899aabbccddeeff00"
            + "810101020100112233445566778899aabbccddeeff02");

    public static void main(String[] args) throws Exception {
        handshakeReplay();
        failureReplays();
        if (args.length != 0) {
            if (args.length != 3) throw new IllegalArgumentException("expected capture TSV, Java protocol and guest name");
            replayCapture(Path.of(args[0]), Integer.parseInt(args[1]), args[2]);
        }
        System.out.println("EAGLER_RECORD_REPLAY PASS");
    }

    private static void handshakeReplay() {
        EaglerHandshakeCodec client = new EaglerHandshakeCodec(NAME, "", null);
        match(client.begin(new int[] {5}, new int[] {FIXTURE_PROTOCOL}, "Gaius", "Gaius"),
                "010200010005000102fd05476169757305476169757300085265706c61795f31");
        match(client.receive(VERSION), "04085265706c61795f31000000000000");
        match(client.receive(ALLOW), "08");
        check(client.receive(bytes("09")) == null, "server finish does not emit a handshake message");
        check(client.state() == EaglerHandshakeCodec.State.COMPLETE, "replay reached play gate");
        check(NAME.equals(client.acceptedName()), "accepted username");
        check(UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00").equals(client.acceptedId()),
                "accepted UUID");
    }

    private static void failureReplays() {
        // Complete version-list/Java-range/ASCII reason; complete UTF8-16 denial and error.
        expectFailure(offered(), bytes("030002000400050002002f02fd086e6f206d61746368"),
                "server rejected protocol versions:");
        expectFailure(offered(), bytes("ff04046f6f7073"), "expected server version packet");
        expectFailure(waitLogin(), bytes("06000664656e696564"), "login denied:");
        expectFailure(waitLogin(), bytes("ff08000664656e696564"), "login denied:");
        expectFailure(waitLogin(), bytes("0a00056f74686572"), "expected allow-login packet");
        expectFailure(waitLogin(), Arrays.copyOf(ALLOW, ALLOW.length - 1), "truncated Eagler packet");
        byte[] wrong = VERSION.clone();
        wrong[4] = 47;
        expectFailure(offered(), wrong, "unadvertised Minecraft version");
        wrong = VERSION.clone();
        wrong[2] = 4;
        expectFailure(offered(), wrong, "unadvertised Eagler version");
        // Proper legacy version reply lacks v5's trailing nickname flag. Not supported by Gaius.
        expectFailure(offered(), Arrays.copyOf(wrong, wrong.length - 1), "truncated Eagler packet");
        wrong = VERSION.clone();
        wrong[wrong.length - 4] = 1;
        expectFailure(offered(), wrong, "server requires authentication");
        EaglerHandshakeCodec client = waitLogin();
        client.receive(ALLOW);
        expectFailure(client, bytes("0900"), "trailing finish-login bytes");
    }

    private static void replayCapture(Path capture, int protocol, String name) throws Exception {
        List<String> messages = Files.readAllLines(capture);
        check(messages.size() == 6, "one complete six-message v5 handshake is required");
        EaglerHandshakeCodec client = new EaglerHandshakeCodec(name, "", null);
        byte[] output = client.begin(new int[] {5}, new int[] {protocol}, "Gaius", "Gaius");
        for (int i = 0; i < messages.size(); i++) {
            String[] row = messages.get(i).split("\t", -1);
            check(row.length == 2, "expected direction and hex payload");
            if ((i & 1) == 0) {
                check(row[0].equals("C"), "expected client message at " + i);
                match(output, row[1]);
            } else {
                check(row[0].equals("S"), "expected server message at " + i);
                output = client.receive(bytes(row[1]));
            }
        }
        check(output == null && client.state() == EaglerHandshakeCodec.State.COMPLETE,
                "captured replay completed without trailing bytes");
        check(name.equals(client.acceptedName()) && client.acceptedId() != null, "replayed guest identity");
        System.out.println("EAGLER_CAPTURE_REPLAY PASS messages=6 protocol=" + protocol + " uuid=" + client.acceptedId());
    }

    private static EaglerHandshakeCodec offered() {
        EaglerHandshakeCodec client = new EaglerHandshakeCodec(NAME, "", null);
        client.begin(new int[] {5}, new int[] {FIXTURE_PROTOCOL}, "Gaius", "Gaius");
        return client;
    }

    private static EaglerHandshakeCodec waitLogin() {
        EaglerHandshakeCodec client = offered();
        client.receive(VERSION);
        return client;
    }

    private static byte[] bytes(String hex) { return HEX.parseHex(hex); }

    private static void match(byte[] actual, String hex) {
        check(Arrays.equals(actual, bytes(hex)), "client payload differs from transcript: " + hex);
    }

    private static void expectFailure(EaglerHandshakeCodec client, byte[] packet, String reason) {
        try {
            client.receive(packet);
        } catch (EaglerHandshakeCodec.ProtocolException failure) {
            check(client.state() == EaglerHandshakeCodec.State.FAILED, "invalid packet did not close state");
            check(failure.getMessage().contains(reason), "unexpected rejection: " + failure.getMessage());
            return;
        }
        throw new AssertionError("expected rejection: " + reason);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
