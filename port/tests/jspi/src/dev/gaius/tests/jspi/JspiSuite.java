package dev.gaius.tests.jspi;

/**
 * JSPI semantic suite of the WebAssembly GC build (design D3, M1 WP2): compiled with the wasmgc
 * overlays (gaius.teavm.jspi=true) by port/tests/jspi/build.sh and run by run-node.mjs or
 * run-chrome.mjs. Groups:
 * <ul>
 *   <li>semantic: the 17 thread semantics checks of M0 spike S5 plus clinitJvm (18);</li>
 *   <li>probes: the JSPI host probes of the M1 T0 review fixes and S5's host task checks;</li>
 *   <li>wp2: join, interrupt, class initialization and frame boundary checks of WP2/WP7.</li>
 * </ul>
 * Every check reports {@code RESULT <group>.<name> PASS|FAIL ...|SKIP ...}; the runner reads
 * globalThis.__jspiSuite and fails unless every check that ran passed.
 */
public final class JspiSuite {
    private JspiSuite() {
    }

    public static void main(String[] args) {
        Host.report("started", "1");
        SemanticTests.run();
        ProbeTests.run();
        Wp2Tests.run();
        String summary = Checks.summary();
        Host.report("failures", String.valueOf(Checks.FAILURES.size()));
        Host.done(summary);
    }
}
