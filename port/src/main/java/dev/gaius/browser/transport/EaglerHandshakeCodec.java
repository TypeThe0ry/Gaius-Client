package dev.gaius.browser.transport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** EaglercraftX binary login exchange, independent of the WebSocket implementation. */
public final class EaglerHandshakeCodec {
    public enum State { NEW, WAIT_VERSION, WAIT_LOGIN, WAIT_FINISH, COMPLETE, FAILED }

    public record Version(int handshakeProtocol, int minecraftProtocol, String brand, String serverVersion,
                          int authMethod, byte[] salt, boolean nicknameSelection) { }

    private final String username;
    private final String requestedServer;
    private final StateListener listener;
    private State state = State.NEW;
    private int handshakeVersion;
    private int[] handshakeCandidates;
    private int[] minecraftCandidates;
    private String acceptedName;
    private UUID acceptedId;

    public String acceptedName() { return acceptedName; }
    public UUID acceptedId() { return acceptedId; }

    public interface StateListener {
        default void version(Version value) { }
        default void completed() { }
        default void failed(String reason) { }
    }

    public EaglerHandshakeCodec(String username, String requestedServer, StateListener listener) {
        validateUsername(username);
        this.username = username;
        this.requestedServer = requestedServer == null ? "" : requestedServer;
        this.listener = listener == null ? new StateListener() { } : listener;
    }

    public State state() { return state; }

    /** Builds the v2-v5 hello. Authentication is deliberately disabled; username is still supplied. */
    public byte[] begin(int[] eaglerVersions, int[] minecraftProtocols, String brand, String clientVersion) {
        require(state == State.NEW, "hello already sent");
        validateCandidates(eaglerVersions, "Eagler");
        validateCandidates(minecraftProtocols, "Minecraft");
        handshakeCandidates = eaglerVersions.clone();
        minecraftCandidates = minecraftProtocols.clone();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        out.write(2);
        writeU16(out, eaglerVersions.length);
        for (int candidate : eaglerVersions) writeU16(out, candidate);
        writeU16(out, minecraftProtocols.length);
        for (int candidate : minecraftProtocols) writeU16(out, candidate);
        writeAscii8(out, brand);
        writeAscii8(out, clientVersion);
        out.write(0);
        writeAscii8(out, username);
        state = State.WAIT_VERSION;
        return out.toByteArray();
    }

    /** Consumes one complete binary WebSocket message and returns the next client payload, if any. */
    public byte[] receive(byte[] message) {
        require(message != null, "null server message");
        try {
            Cursor in = new Cursor(message);
            int id = in.u8();
            if (state == State.WAIT_VERSION) {
                if (id == 3) throw new ProtocolException("server rejected protocol versions: " + in.remainingBytes());
                require(id == 2, "expected server version packet");
                Version version = readVersion(in);
                require(in.remaining() == 0, "trailing server version bytes");
                require(version.handshakeProtocol() >= 2 && version.handshakeProtocol() <= 5,
                        "unsupported Eagler handshake version");
                require(Arrays.stream(handshakeCandidates).anyMatch(candidate -> candidate == version.handshakeProtocol()),
                        "server selected an unadvertised Eagler version");
                require(Arrays.stream(minecraftCandidates).anyMatch(candidate -> candidate == version.minecraftProtocol()),
                        "server selected an unadvertised Minecraft version");
                require(version.authMethod() == 0, "server requires authentication");
                handshakeVersion = version.handshakeProtocol();
                listener.version(version);
                state = State.WAIT_LOGIN;
                return loginRequest();
            }
            if (state == State.WAIT_LOGIN) {
                if (id == 6 || id == 255) throw new ProtocolException("login denied: " + in.remainingBytes());
                require(id == 5, "expected allow-login packet");
                acceptedName = in.ascii8();
                validateUsername(acceptedName);
                acceptedId = new UUID(in.i64(), in.i64());
                if (handshakeVersion >= 5) {
                    int capabilityMask = readVarInt(in);
                    in.skip(Integer.bitCount(capabilityMask));
                    int count = in.u8();
                    require(count <= 32, "too many extended capabilities");
                    in.skip(count * 17);
                }
                require(in.remaining() == 0, "trailing allow-login bytes");
                state = State.WAIT_FINISH;
                return new byte[] {8};
            }
            if (state == State.WAIT_FINISH) {
                if (id == 6 || id == 255) throw new ProtocolException("login denied: " + in.remainingBytes());
                require(id == 9, "expected finish-login packet");
                require(in.remaining() == 0, "trailing finish-login bytes");
                state = State.COMPLETE;
                listener.completed();
                return null;
            }
            throw new ProtocolException("unexpected server message in state " + state);
        } catch (ProtocolException ex) {
            state = State.FAILED;
            listener.failed(ex.getMessage());
            throw ex;
        }
    }

    private byte[] loginRequest() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(4);
        writeAscii8(out, username);
        writeAscii8(out, requestedServer);
        out.write(0); // no password / no online authentication
        if (handshakeVersion >= 4) {
            out.write(0); // cookie disabled
            out.write(0);
        }
        if (handshakeVersion >= 5) {
            writeVarInt(out, 0); // no standard capabilities
            out.write(0); // no extended capabilities
        }
        return out.toByteArray();
    }

    private static Version readVersion(Cursor in) {
        int protocol = in.u16();
        int minecraft = in.u16();
        String brand = in.ascii8();
        String serverVersion = in.ascii8();
        int authMethod = in.u8();
        int saltLength = in.u16();
        byte[] salt = in.bytes(saltLength);
        boolean nickname = in.u8() != 0;
        return new Version(protocol, minecraft, brand, serverVersion, authMethod, salt, nickname);
    }

    private static void validateCandidates(int[] values, String label) {
        require(values != null && values.length > 0 && values.length <= 16, label + " candidate count must be 1..16");
        for (int value : values) require(value >= 0 && value <= 65535, label + " candidate out of range");
    }

    private static void validateUsername(String value) {
        require(value != null && value.matches("[A-Za-z0-9_]{3,16}"), "invalid offline username");
    }

    private static void writeAscii8(ByteArrayOutputStream out, String value) {
        require(value != null, "null ASCII field");
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        require(value.equals(new String(bytes, StandardCharsets.US_ASCII)) && bytes.length <= 255,
                "invalid or oversized ASCII field");
        out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        require(value >= 0 && value <= 65535, "u16 out of range");
        out.write(value >>> 8);
        out.write(value);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        do {
            int part = value & 0x7f;
            value >>>= 7;
            out.write(value == 0 ? part : part | 0x80);
        } while (value != 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ProtocolException(message);
    }

    public static final class ProtocolException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public ProtocolException(String message) { super(message); }
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int index;
        Cursor(byte[] bytes) { this.bytes = bytes; }
        int remaining() { return bytes.length - index; }
        String remainingBytes() { return Arrays.toString(Arrays.copyOfRange(bytes, index, bytes.length)); }
        int u8() { need(1); return bytes[index++] & 255; }
        int u16() { return (u8() << 8) | u8(); }
        long i64() {
            long value = 0;
            for (int i = 0; i < 8; i++) value = (value << 8) | u8();
            return value;
        }
        String ascii8() {
            int length = u8();
            byte[] data = bytes(length);
            for (byte b : data) require((b & 0x80) == 0, "non-ASCII handshake string");
            return new String(data, StandardCharsets.US_ASCII);
        }
        byte[] bytes(int length) { need(length); byte[] result = Arrays.copyOfRange(bytes, index, index + length); index += length; return result; }
        void skip(int length) { need(length); index += length; }
        int readVarInt() {
            int result = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int part = u8();
                result |= (part & 127) << shift;
                if ((part & 128) == 0) return result;
            }
            throw new ProtocolException("VarInt exceeds five bytes");
        }
        private void need(int length) { if (length < 0 || length > remaining()) throw new ProtocolException("truncated Eagler packet"); }
    }

    private static int readVarInt(Cursor in) { return in.readVarInt(); }
}
