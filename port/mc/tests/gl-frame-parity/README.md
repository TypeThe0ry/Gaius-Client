# Buffer upload window regression

From the repository root, use an existing profile JSON and private TMPDIR:

```sh
LIBGL_ALWAYS_SOFTWARE=1 LP_NUM_THREADS=1 __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json node port/scripts/frame-parity/buffer-upload-window-test.mjs   port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java   <output> <profile.json>
```

Requires a JDK with the final foreign-function API, existing profile classpath
inputs, and surfaceless EGL/OpenGL. The runner never downloads or builds a game.

The Java fixture executes verbatim production bufferData, bufferSubData,
namedBufferData and namedBufferSubData wrappers with explicit TeaVM interop
models. It covers heap/direct buffers, full/trimmed/empty/end-empty windows,
slices, nested slices, duplicates and read-only buffers. Typed and linear
receipts must equal the input remaining bytes without changing position/limit.
The native driver then submits each expected/actual receipt, checks errors and
reads every destination byte back, including exact-end and oversized updates.
A source mismatch exits 2 after saving both receipts and native driver results.

This tests wrapper and native GL buffer semantics. It does not claim TeaVM
interop execution, browser pixels, complete frame parity or game performance.
The fixture is independent of a frame recorder. Production JSBody parity is
covered separately by port/scripts/lwjgl-linear-byte-upload-smoke.mjs.
