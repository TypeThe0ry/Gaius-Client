# Gaius Builder

`gaius-builder` is a native Rust desktop GUI and CLI for the local Java patcher. The GUI has a six-step flow for version selection, environment checks, terms, build options, offline build progress, and output actions. It makes no GUI network requests. Build invocations pass `--offline` and run the patcher as a child process.

## Build

Rust 1.95 or newer and a native C/C++ toolchain are required. Cargo downloads crates from crates.io; `Cargo.lock` pins dependencies. No bundled game content, fonts, mod loaders, or mod binaries are included.

Windows (MSVC):

```powershell
cd patcher\gui
cargo build --release
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
target\release\gaius-builder.exe
```

Install Visual Studio C++ Build Tools and the Windows SDK first. Java 25 or newer is required to run the patcher. The Windows GNU target is optional and was not cross-compiled.

Linux:

```sh
cd patcher/gui
cargo build --release
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
target/release/gaius-builder
```

The native window uses WGPU. A Vulkan or OpenGL driver is recommended. Headless screenshots use the egui kittest WGPU renderer and can use a software graphics renderer.

## Patcher adapter

The adapter runs `build <version> --out <directory> --json-progress --accept-terms --offline`. It passes the output directory, optional Minecraft directory (`--minecraft-dir`), the local 1.21.11 mappings file (`--mappings`), and the 26.3 shader toolchain directory (`--shader-toolchain`) only when those paths are selected. The toolchain field appears on the environment page when 26.3 is selected; its manifest, source pins, and four shader module hashes are checked before starting. Missing or invalid input is reported before the Java process starts. No game files, mappings, shader modules, or other build inputs are downloaded by the GUI. Build output is placed at `<output-folder>/<version>`; selected versions run sequentially. “Open in browser” launches the per-version portable HTML through the system file handler without starting a local server.

Each stdout progress line follows the patcher's `gaius-patcher-progress-v1` JSON Lines schema. Unknown fields and phases are tolerated, and non-JSON noise is ignored for control flow. Stderr is streamed into the bounded build log. Cancellation targets only the process group started for the build.

Graphics backend, quality, view distance, built-in packs, local packs, loader, and mods have no corresponding Java build flags, so they are not applied to the build. Their current values are written to `build-options.json` beside the per-version output and the result page states this limitation. For unchanged defaults, the badge is green only when every path and SHA-256 in the build's `expected-hashes.json` matches the produced files. Customized options with matching base hashes show yellow; any hash mismatch shows red and lists the relative paths; a missing or unreadable manifest shows an unknown badge.

Set `GAIUS_BUILDER_MOCK` to `ok`, `fail-hash`, `fail-build`, `cancel`, `unsupported-mod`, `diverged`, or `custom` to exercise the status flow without starting Java. The mock backend is available for screenshots and tests; the normal default is the real patcher process. Set `GAIUS_BUILDER_WORK_DIR` to change the local location for the terms acceptance record and default builds. That record contains only the displayed terms text SHA-256 and Unix acceptance time.

## CLI

```sh
gaius-builder doctor
gaius-builder doctor --json
gaius-builder version
gaius-builder --help
```

The plain `doctor` command relays the patcher's output and exit status. `doctor --json` emits parsed fields, version installation state, raw output, and exit code.

## Screenshots

`--state page-1` through `--state page-6` selects a page. `terms-accepted` renders page 3 after scrolling to the end and checking the acceptance box; `mods-scrolled` renders page 4 after scrolling the first mod row into view; `toolchain-field` scrolls page 2 to the 26.3 shader toolchain path. Other supported states are `detect-fail`, `hash-error`, `running`, `build-success`, `build-fail`, `cancelled`, `unsupported-mod`, `badge-green`, `badge-yellow`, and `badge-red`. `build-success` selects the completion page (page 6) and uses a successful mock result. `real-preflight-error` invokes the real Java backend for 26.3 and renders its missing-toolchain JSON error; set `GAIUS_PATCHER_SOURCE` to the patcher source file to use.

Status marks are painted as vector icons, so the UI does not require check, cross, warning, ring, bullet, or chevron glyphs. Native startup appends a system CJK font and then a symbol fallback where available. Set `GAIUS_BUILDER_NO_SYSTEM_FONTS=1` to exercise the default egui fonts only; missing glyphs render as tofu and never panic.

```sh
gaius-builder --screenshot /path/to/page.png --state page-4 \
  --theme dark --lang zh --size 960x620
gaius-builder --screenshot /path/to/failure.png --state build-fail \
  --page 5 --theme light --lang en --size 960x620
gaius-builder --screenshot /path/to/scale.png --state page-4 \
  --scale 1.25 --size 960x620
GAIUS_PATCHER_SOURCE=/path/to/GaiusPatcher.java \
  gaius-builder --screenshot /path/to/preflight.png \
  --state real-preflight-error --page 5 --size 960x620
```

## Checks

```sh
bash patcher/gui/check.sh
```

The check script runs formatting, clippy, unit tests, the Java doctor smoke test, and one headless screenshot. Set `GAIUS_BUILDER_OUT_DIR` to override the
screenshot directory and `GAIUS_BUILDER_TMP_DIR` to override the temporary
directory. Java must be available on `PATH` for the doctor check.
