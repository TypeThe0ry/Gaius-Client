# TeaVM class library recipes

`apply-teavm-classlib.py` reads the local TeaVM class library and Vineflower JARs
pinned by `patcher/toolchain.lock`. TeaVM and Vineflower use Apache-2.0. The
build script stages generated Java under its overlay work directory and compiles
it with the other overlays. Each edit script verifies its decompiled base and
result. The generator uses only local inputs.

- `teavm-inflater-input-stream.gpatch` keeps a separate skip buffer and retries
  empty refills. It also retains the port's argument and stream-state handling.
- `teavm-byte-order.gpatch` fixes native byte order to little-endian in the browser.

The locked Patcher stages Vineflower under `port/work/tools`. Direct overlay
builds can set `GAIUS_VINEFLOWER_JAR` to another local copy of the locked JAR.
