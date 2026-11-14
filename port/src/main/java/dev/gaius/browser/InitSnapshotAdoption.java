package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Strict browser-side gate for the build-time GAIUSIS1 initialisation snapshot. */
public final class InitSnapshotAdoption {
    private static boolean attempted;
    private static boolean adopted;
    private static boolean fallback;
    private static int registryEntries;
    private static int datapackEntries;

    private InitSnapshotAdoption() {
    }

    /** Called before Java starts the normal bootstrap chain. False means vanilla must run. */
    public static boolean begin() {
        if (attempted) {
            return adopted;
        }
        attempted = true;
        adopted = browserSnapshotVerified();
        fallback = !adopted;
        publish();
        return adopted;
    }

    public static void registryEntryAdopted() {
        if (adopted) {
            registryEntries++;
            publish();
        }
    }

    public static void datapackEntryAdopted() {
        if (adopted) {
            datapackEntries++;
            publish();
        }
    }

    public static boolean isAdopted() {
        return adopted && !fallback;
    }

    public static void forceFallback(String reason) {
        adopted = false;
        fallback = true;
        publishFailure(reason);
    }

    private static void publish() {
        publish(adopted, fallback, registryEntries, datapackEntries);
    }

    @JSBody(script = """
            try {
              const state = globalThis.__gaiusInitSnapshotState;
              const profile = globalThis.__gaiusProfileId;
              return !!state && state.status === 'verified'
                  && typeof profile === 'string' && profile.length > 0 && state.profile === profile;
            } catch (ignored) { return false; }
            """)
    private static native boolean browserSnapshotVerified();

    @JSBody(params = {"adopted", "fallback", "registries", "datapacks"}, script = """
            try {
              globalThis.__gaiusInitSnapshotJavaCheck = {
                status: adopted ? 'adopted' : (fallback ? 'fallback' : 'pending'),
                registryEntries: registries, datapackEntries: datapacks,
                parity: adopted ? 'resource-hashes-verified' : 'not-adopted'
              };
            } catch (ignored) {}
            """)
    private static native void publish(boolean adopted, boolean fallback, int registries,
            int datapacks);

    @JSBody(params = "reason", script = """
            try {
              globalThis.__gaiusInitSnapshotJavaCheck = {
                status: 'fallback', parity: 'mismatch', reason: String(reason)
              };
            } catch (ignored) {}
            """)
    private static native void publishFailure(String reason);
}
