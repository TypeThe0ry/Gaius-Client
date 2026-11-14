package dev.gaius.browser;

import org.teavm.jso.JSBody;

/**
 * The page's atlas prebake payload: {@code globalThis.__gaiusAtlasPrebake}, which gaius-boot.js
 * fills from {@code gaius-atlas-prebake.bin.gz} (inflated, header checked, body SHA-256 checked).
 * A payload that is still loading, failed a check or belongs to another profile reads as absent.
 */
final class BrowserAtlasPrebakeJs implements BrowserAtlasPrebake.Source {
    static final BrowserAtlasPrebakeJs SOURCE = new BrowserAtlasPrebakeJs();

    private BrowserAtlasPrebakeJs() {
    }

    @Override
    public int mode() {
        return modeJs();
    }

    @Override
    public int length() {
        return lengthJs();
    }

    @Override
    public String status() {
        return statusJs();
    }

    @Override
    public String profile() {
        return profileJs();
    }

    @Override
    public void copyTo(int offset, int length, long address) {
        JsArrays.installLinearViews();
        if (!copyToJs(offset, length, (int) address)) {
            throw new IllegalStateException("atlas prebake payload range");
        }
    }

    @Override
    public boolean equalsAt(int offset, int length, long address) {
        JsArrays.installLinearViews();
        return equalsAtJs(offset, length, (int) address);
    }

    @Override
    public void publish(int[] counters, String status) {
        publishJs(counters[0], counters[1], counters[2], counters[3], counters[4], counters[5], counters[6],
                counters[7], counters[8], counters[9], status);
    }

    @JSBody(script = """
            try {
              const state = globalThis.__gaiusAtlasPrebake;
              return state && typeof state.mode === 'number' ? state.mode : 0;
            } catch (ignored) { return 0; }
            """)
    private static native int modeJs();

    @JSBody(script = """
            try {
              const state = globalThis.__gaiusAtlasPrebake;
              const profile = globalThis.__gaiusProfileId;
              return state && state.status === 'verified' && state.bytes instanceof Uint8Array
                  && typeof profile === 'string' && state.profile === profile ? state.bytes.length : -1;
            } catch (ignored) { return -1; }
            """)
    private static native int lengthJs();

    @JSBody(script = """
            try {
              const state = globalThis.__gaiusAtlasPrebake;
              if (!state) return 'absent';
              if (state.status === 'verified' && state.profile !== globalThis.__gaiusProfileId) return 'profile-mismatch';
              return String(state.status) + (state.error ? ': ' + state.error : '');
            } catch (ignored) { return 'absent'; }
            """)
    private static native String statusJs();

    @JSBody(script = """
            try {
              const profile = globalThis.__gaiusProfileId;
              return typeof profile === 'string' ? profile : null;
            } catch (ignored) { return null; }
            """)
    private static native String profileJs();

    @JSBody(params = {"offset", "length", "address"}, script = """
            const bytes = globalThis.__gaiusAtlasPrebake.bytes;
            if (offset < 0 || length < 0 || offset + length > bytes.length) return false;
            globalThis.__gaiusLinear.refresh().u8.set(bytes.subarray(offset, offset + length), address >>> 0);
            return true;
            """)
    private static native boolean copyToJs(int offset, int length, int address);

    @JSBody(params = {"offset", "length", "address"}, script = """
            const bytes = globalThis.__gaiusAtlasPrebake.bytes;
            if (offset < 0 || length < 0 || offset + length > bytes.length) return false;
            const memory = globalThis.__gaiusLinear.refresh().u8;
            const at = address >>> 0;
            for (let index = 0; index < length; index++) {
              if (memory[at + index] !== bytes[offset + index]) return false;
            }
            return true;
            """)
    private static native boolean equalsAtJs(int offset, int length, int address);

    @JSBody(params = {"mipHits", "mipMissKey", "mipMissHash", "mipMissLevel", "mipUnchecked", "stitchHits",
            "stitchMisses", "verifyChecks", "verifyMismatches", "hitMicros", "status"}, script = """
            try {
              const state = globalThis.__gaiusAtlasPrebake || (globalThis.__gaiusAtlasPrebake = {status: 'absent'});
              state.java = {status, mipHits, mipMissKey, mipMissHash, mipMissLevel, mipUnchecked,
                stitchHits, stitchMisses, verifyChecks, verifyMismatches, hitMs: hitMicros / 1000};
            } catch (ignored) {}
            """)
    private static native void publishJs(int mipHits, int mipMissKey, int mipMissHash, int mipMissLevel,
            int mipUnchecked, int stitchHits, int stitchMisses, int verifyChecks, int verifyMismatches,
            int hitMicros, String status);
}
