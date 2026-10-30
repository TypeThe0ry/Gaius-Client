package dev.gaius.browser;

import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;

/**
 * Keeps large one-time client and server initialization batches cooperative in the JavaScript
 * build; the WebAssembly GC build only records the progress events.
 */
public final class BrowserStartupScheduler {
    private static final int BLOCK_REGISTRATION_BATCH = 64;
    private static final int BLOCK_STATE_CACHE_BATCH = 512;
    private static final int REGISTRY_BOOTSTRAP_BATCH = 8;
    private static final int DATAPACK_RESOURCE_BATCH = 64;

    private static int registeredBlocks;
    private static int initializedBlockStates;
    private static int bootstrappedRegistries;
    private static int decodedDatapackResources;
    private static boolean complete;

    private BrowserStartupScheduler() {
    }

    public static void blockRegistered() {
        if (!isActive()) {
            return;
        }
        registeredBlocks++;
        if (registeredBlocks % BLOCK_REGISTRATION_BATCH == 0) {
            checkpoint("blocks-registered=" + registeredBlocks);
        }
    }

    public static void blockStateInitialized() {
        if (!isActive()) {
            return;
        }
        initializedBlockStates++;
        if (initializedBlockStates % BLOCK_STATE_CACHE_BATCH == 0) {
            checkpoint("block-states-cached=" + initializedBlockStates);
        }
    }

    public static void registryBootstrapCompleted() {
        if (!isActive()) {
            return;
        }
        bootstrappedRegistries++;
        report("registries-bootstrapped=" + bootstrappedRegistries);
        if (bootstrappedRegistries % REGISTRY_BOOTSTRAP_BATCH == 0) {
            yieldToBrowser();
        }
    }

    public static void datapackResourceDecoded() {
        if (!isActive()) {
            return;
        }
        decodedDatapackResources++;
        if (decodedDatapackResources % DATAPACK_RESOURCE_BATCH == 0) {
            checkpoint("datapack-resources-decoded=" + decodedDatapackResources);
        }
    }

    public static void phase(String phase) {
        if (isActive()) {
            checkpoint(phase);
        }
    }

    public static void complete() {
        if (!isBrowserRuntime() || complete) {
            return;
        }
        complete = true;
        report("complete blocks=" + registeredBlocks
                + " states=" + initializedBlockStates
                + " registries=" + bootstrappedRegistries
                + " datapack-resources=" + decodedDatapackResources);
    }

    private static boolean isActive() {
        return !complete && isBrowserRuntime();
    }

    private static void checkpoint(String detail) {
        report(detail);
        yieldToBrowser();
    }

    private static void yieldToBrowser() {
        // The WebAssembly GC build has no artificial startup yields (design D4): the browser
        // gets its turns at the frame boundary of Minecraft.run and wherever a thread blocks.
        // The progress events above are still recorded. HostLoop.isWasmGc() is a platform
        // marker, so the JavaScript build compiles this method as before.
        if (HostLoop.isWasmGc()) {
            return;
        }
        TModernRuntimeSupport.yieldToEventLoop(0);
    }

    @JSBody(script = """
            return typeof globalThis !== 'undefined' && (
              (typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope)
              || typeof window !== 'undefined'
            );
            """)
    private static native boolean isBrowserRuntime();

    @JSBody(script = "return typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope;")
    private static native boolean isWorkerRuntime();

    @JSBody(params = "detail", script = """
            try {
              const event = {type: 'server-startup-progress', detail: String(detail), at: Date.now()};
              if (typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope) {
                postMessage(event);
              } else {
                const progress = globalThis.__gaiusClientStartupProgress
                  || (globalThis.__gaiusClientStartupProgress = []);
                progress.push(event);
                if (progress.length > 256) progress.shift();
              }
            } catch (ignored) {}
            """)
    private static native void report(String detail);
}
