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

## Local bridge generation

The public source tree cannot build the game on its own. The overlay build
needs the light generator and seven Unihex generators omitted from the export.
The official installation supplies the game inputs but does not supply these
Gaius bridge generator sources.

For maintainers who already have an authorized, matching full source checkout,
set `GAIUS_LOCAL_BRIDGE_GENERATORS` to its absolute
`port/tools/src/main/java/dev/gaius/tools/bridges` directory in Bash before
invoking the normal overlay build. The checked-out generator sources take
precedence. The [local source recipe](local-bridge-sources.sh) selects the exact
missing inputs and fails if any are unavailable. The build compiles the Java
generators with the locked ASM 9.10.1 and runs `BrowserBridges` for the selected
profile. It writes classfiles into the local overlay work directory.

The WasmGC packaging step uses `UnihexTableBuilder` with the prepared client
and verified local font ZIPs to generate the glyph table when that step is
enabled. Keep the game inputs and generated build files on your own machine.
