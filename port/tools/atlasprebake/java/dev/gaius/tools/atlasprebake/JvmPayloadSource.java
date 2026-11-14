package dev.gaius.tools.atlasprebake;

import dev.gaius.browser.BrowserAtlasPrebake;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;

/**
 * The JVM counterpart of the page's payload source: the same checks gaius-boot.js makes before it
 * marks a payload verified (magic, version, lengths, body SHA-256, profile), then the bytes in
 * native memory for {@link BrowserAtlasPrebake}.
 */
final class JvmPayloadSource implements BrowserAtlasPrebake.Source {
    private final int mode;
    private final String profile;
    private final String status;
    private final ByteBuffer bytes;
    private final long address;
    int[] lastCounters = new int[0];
    String lastStatus = "";

    JvmPayloadSource(byte[] payload, int mode, String profile) {
        this.mode = mode;
        this.profile = profile;
        String check = check(payload, profile);
        this.status = check;
        if (check.equals("verified")) {
            bytes = MemoryUtil.memAlloc(payload.length);
            bytes.put(payload).flip();
            address = MemoryUtil.memAddress(bytes);
        } else {
            bytes = null;
            address = 0L;
        }
    }

    static String check(byte[] payload, String profile) {
        if (payload == null) {
            return "absent";
        }
        ByteBuffer view = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        if (payload.length < 56 || view.getLong(0) != 0x3150415355494147L) {
            return "invalid: magic";
        }
        if (view.getInt(8) != 1) {
            return "invalid: version";
        }
        int headerLength = view.getInt(12);
        int bodyLength = view.getInt(16);
        if (headerLength < 56 || bodyLength < 0 || (long) headerLength + bodyLength != payload.length) {
            return "invalid: lengths";
        }
        int profileLength = Short.toUnsignedInt(view.getShort(52));
        String stored = new String(payload, 54, Math.min(profileLength, payload.length - 54),
                java.nio.charset.StandardCharsets.US_ASCII);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    Arrays.copyOfRange(payload, headerLength, payload.length));
            if (!Arrays.equals(digest, Arrays.copyOfRange(payload, 20, 52))) {
                return "mismatch: body sha256";
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return "unverified: no SHA-256";
        }
        if (!stored.equals(profile)) {
            return "profile-mismatch";
        }
        return "verified";
    }

    @Override
    public int mode() {
        return mode;
    }

    @Override
    public int length() {
        return bytes == null ? -1 : bytes.capacity();
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public String profile() {
        return profile;
    }

    @Override
    public void copyTo(int offset, int length, long target) {
        if (offset < 0 || length < 0 || (long) offset + length > bytes.capacity()) {
            throw new IllegalStateException("atlas prebake payload range");
        }
        MemoryUtil.memCopy(address + offset, target, length);
    }

    @Override
    public boolean equalsAt(int offset, int length, long target) {
        if (offset < 0 || length < 0 || (long) offset + length > bytes.capacity()) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            if (MemoryUtil.memGetByte(address + offset + index) != MemoryUtil.memGetByte(target + index)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void publish(int[] counters, String status) {
        lastCounters = counters.clone();
        lastStatus = status;
    }
}
