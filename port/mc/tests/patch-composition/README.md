# Patch composition JVM fixtures

Run from the repository root with caller-owned scratch and verified profile JSON inputs:

```sh
node port/scripts/patch-composition-jvm-test.mjs command <scratch> <profile.json>...
node port/scripts/patch-composition-jvm-test.mjs loading <scratch> <profile.json>...
```

The command test applies the real patch to each supplied jar. It then executes that
jar's actual `performCommand` bytecode before/after the patch in an isolated JVM
fixture. Parsing, command execution, source output, logger, profiler and browser
reporting are explicit boundary doubles; there is no game world or browser.
The command callback is not executed. The actual Exception conversion and original
catch/finally/profiler control flow remain in the tested method, including the real
component/style operations and error logging branches.

Cases include success, no context, checked/unchecked exceptions, Errors, a bare
Throwable, failing diagnostics, conversion failure, throwing profiler cleanup and
parsing outside vanilla's try region. Traces, converted components and escaped
Throwable identity are compared with vanilla. A fresh exception from the callee
also checks the debug stack presentation without an extra wrapper frame. Removing
only the inline diagnostics must restore the exact vanilla method hash and exception
table; every other original method must retain its hash. The rethrow must remain
covered by exactly the original handlers. Missing/duplicate/nonstatic execution
calls, pre-existing diagnostics and an unknown enclosing handler shape must fail
before writing output.

`GAIUS_PATCH_COMPOSITION_TOOLS_SOURCE` can select an archived patch-tool source root
to demonstrate that the same tests reject a pre-fix patch. It never changes the
selected game input, production profile or checked-in locks.

The loading test runs the actual insecure-toast FEATURE followed by loading
TELEMETRY on every supplied jar. The warning count must stay zero after composition;
an absent output must instead retain vanilla's warning (TELEMETRY does not enable
the optional FEATURE itself). An earlier added marker method and all unrelated
methods must survive. Removing only the handleLogin entry event must restore the
complete FEATURE-composed method hash. The entry hook and the earlier marker are
also extracted and executed under JVM verification with a recording BrowserOpenGL
double. Production frame computation covers the complete composed class. The actual
post-login warning tail executes with all server/seen/secure-policy combinations;
the factory shape removes the full Minecraft receiver expression and rejects an
unknown receiver before writing output. This is composition evidence, not a complete ClientPacketListener login,
toast display or browser-world acceptance.
