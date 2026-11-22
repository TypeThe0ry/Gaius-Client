package dev.gaius.tools;

import dev.gaius.browser.transport.EaglerHandshakeCodec;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Deterministic replay of Eagler hello, version, login, allow and finish messages. */
public final class EaglerHandshakeCodecTest {
    public static void main(String[] args) {
        replay(5);
        rejectsInvalidInput();
    }

    private static void replay(int version) {
        final boolean[] completed = {false};
        EaglerHandshakeCodec codec = new EaglerHandshakeCodec("Player_1", "", new EaglerHandshakeCodec.StateListener() {
            @Override public void completed() { completed[0] = true; }
        });
        byte[] hello = codec.begin(new int[] {2, 3, 4, 5}, new int[] {47}, "Gaius", "0.4");
        check((hello[0] & 255) == 1 && (hello[1] & 255) == 2, "multi-version hello discriminator");
        byte[] login = codec.receive(versionMessage(version));
        check((login[0] & 255) == 4, "login request id");
        check(login.length == (version < 4 ? 12 : version == 4 ? 14 : 16), "version-specific login shape");
        check(Arrays.equals(new byte[] {8}, codec.receive(allowMessage(version))), "finish login request");
        check(codec.receive(new byte[] {9}) == null, "server finish has no client reply");
        check("Player_1".equals(codec.acceptedName()), "accepted player name");
        check(codec.acceptedId().equals(new java.util.UUID(0, 0)), "accepted player UUID");
        check(completed[0] && codec.state() == EaglerHandshakeCodec.State.COMPLETE, "completed replay");
    }

    private static byte[] versionMessage(int version) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2); u16(out, version); u16(out, 47); ascii8(out, "EaglerXServer"); ascii8(out, "1.8");
        out.write(0); u16(out, 0); out.write(0);
        return out.toByteArray();
    }

    private static byte[] allowMessage(int version) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(5); ascii8(out, "Player_1");
        for (int i = 0; i < 16; i++) out.write(0);
        if (version >= 5) { out.write(1); out.write(0); out.write(0); }
        return out.toByteArray();
    }

    private static void rejectsInvalidInput() {
        expectFailure(() -> new EaglerHandshakeCodec("ab", "", null));
        EaglerHandshakeCodec truncated = new EaglerHandshakeCodec("Player", "", null);
        truncated.begin(new int[] {5}, new int[] {47}, "Gaius", "0.4");
        expectFailure(() -> truncated.receive(new byte[] {2, 0}));
        check(truncated.state() == EaglerHandshakeCodec.State.FAILED, "truncated server packet fails closed");
        EaglerHandshakeCodec wrongState = new EaglerHandshakeCodec("Player", "", null);
        wrongState.begin(new int[] {5}, new int[] {47}, "Gaius", "0.4");
        expectFailure(() -> wrongState.receive(new byte[] {9}));
    }

    private static void ascii8(ByteArrayOutputStream out, String text) {
        byte[] value = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(value.length); out.writeBytes(value);
    }
    private static void u16(ByteArrayOutputStream out, int value) { out.write(value >>> 8); out.write(value); }
    private static void expectFailure(Runnable action) {
        boolean failed = false;
        try { action.run(); } catch (IllegalArgumentException expected) { failed = true; }
        check(failed, "expected protocol validation failure");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
