# Headed Eagler loopback

Run from the repository root with Node 24 and an already installed `ws` (the
bridge's local dependency or `EAGLER_WS_MODULE`). The wire self-test needs no
browser or Java. With `MC_SERVER_DIR` set, it also validates the generated
packets with the native Java decoders and checks TCP-to-WebSocket delivery of
81 chunks, movement, chat echo and keepalive with a Node WebSocket client.

```bash
export TMPDIR=/tmp/eglx
mkdir -pm 700 "$TMPDIR"
export EAGLER_WS_MODULE=/path/to/ws  # optional; omit if apps/bridge can resolve ws
export MC_SERVER_DIR=/path/to/local/minecraft-server-dir
unshare -n -- bash -c 'ip link set lo up && exec node port/scripts/eagler-headed-loopback.mjs --self-test'
```

For headed acceptance, use the newly staged WASM-only portable with the Eagler
client and the Eagler selector. An older portable without the selector cannot
pass. Set `PORTABLE` and run:

```bash
export PORTABLE=/absolute/path/to/staged/Gaius.html
export OUT=$(mktemp -d "$TMPDIR/eagler-headed-XXXXXX")
export GPU=webgpu
unshare -n -- bash -c 'ip link set lo up && exec xvfb-run -a node port/scripts/eagler-headed-loopback.mjs'
```

`MC_SERVER_DIR` is a **read-only classpath source** supplying the cached version
jar and libraries. `EncodeWorld.java` uses that version's packet codecs to make
an original flat test pad. It does not start or call the dedicated server main,
use its worlds or properties, or read/create `eula.txt`. `JAVA` selects the
locally installed JVM (for example JDK 25). If you have an independently
authorized, already running offline Minecraft backend in the same network
namespace, `BACKEND_PORT` uses it instead. The gateway always dials `127.0.0.1`.
The harness does not start or stop that backend or click through server prompts.

Inspect `OUT/report.json` and `browser.log`. Before clicking Multiplayer,
`titleRelay` records the bridge/init state, available relay counters/connect
phases, CDP request and WebSocket attempts, and visible resource hints. Missing
bridge counters/phases are `null`, not measured zeros. `zeroObservedWork` is
scoped to those observations; it is not a startup performance result. The
network namespace prevents external egress independently of CDP.

For a full pass the report must show Eagler `02, 05, 09`, TCP login success and
the gateway's own acknowledgement, world entry with loaded chunks, a unique
chat token seen on the wire and echoed in the browser, and at least 60 s of
PLAY with repeated pose changes and movement packets. With `GPU=webgpu`, the
report also checks successful WebGPU presentation without fallback or kernel
translation errors. The fixture is not a
full vanilla gameplay simulation or a public Eagler server compatibility test.
