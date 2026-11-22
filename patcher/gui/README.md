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

Install Visual Studio C++ Build Tools and the Windows SDK first. Java 25 or newer is required to run the patcher. The Windows GNU target is optional and was not cross-compiled on Linux.

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

The adapter runs `build <version> --out <directory> --json-progress --accept-terms --offline`. It also passes configured Minecraft (`--minecraft-dir`), 1.21.11 mappings (`--mappings`), shared tool-cache (`--toolchain-dir`), and 26.3 shader (`--shader-toolchain`) paths. Build output is placed at `<output-folder>/<version>` and selected versions run sequentially. “Open in browser” launches the per-version portable HTML through the system file handler without starting a local server.

Each stdout progress line follows the patcher's `gaius-patcher-progress-v1` JSON Lines schema. Unknown fields and phases are tolerated, and non-JSON noise is ignored for control flow. Stderr is streamed into the bounded build log. Cancellation targets only the process group started for the build.

Before enabling Next on the check page or enabling Build, the GUI runs `doctor <version> --verify-files --json` for each selected version. It passes the same optional local launcher directory and mappings file as the build. The check is offline and requires complete results for the client, libraries, asset index, and asset objects. Changing a selected version or input path invalidates the previous results. Recheck reruns the scan after files are repaired.

The check page shows the Python 3 command that the patcher selected. Its tool
rows use the patcher's `required`, `requiredReason`, and `requiredFor` fields
to gate Next and Build. Optional rows stay visible and cannot block either
button. Under **Environment → Build-tool folders**, browse to, edit, or clear
either directory override. The app remembers these choices in `tool-paths.json`
in the builder work directory and refreshes Java tool status after edits. The
same overrides are passed to explicit tool installation where applicable.
Diagnostics include the two overrides with user profiles redacted.

The offline Maven repository shows a verification summary for each selected
profile. The tool installer offers one shared Maven repository download when
Java marks it installable. LLVM and Binaryen are required for every profile.
Their rows show the locked sizes and sources and use the same install flow.
The Gaius release process prepares the 26.3 shader module package. The GUI
checks the supplied files and offers a folder picker. This tool cannot download
the package. The Emscripten SDK row lists platforms for reference.

The default build skips Wasm kernels. Enable **Base → Advanced →
Strict kernel mode** to build them. Changing this setting refreshes the tool
check, and both the check and the build pass `--strict-kernel` when enabled.
The pinned Rust/Cargo versions, the wasm32 target, and the offline Cargo cache
then become required. See the [Patcher tool status commands](../README.md#usage).

The GUI passes graphics backend (`auto`, `webgpu`, or `webgl2`), quality (`low`, `mid`, `high`, or `ultra`), view distance (`2..32`), and selected local resource packs to the Java patcher. View distance seeds the first run only and does not overwrite a saved `options.txt`. Built-in packs, loader, and mods are recorded in `build-options.json` but do not affect the build. Default applied settings show green after hash verification; customized applied settings show yellow and are excluded from byte-for-byte reproducibility comparison. Hash mismatches show red and list relative paths; a missing or unreadable manifest shows an unknown badge.

Set `GAIUS_BUILDER_MOCK` to `ok`, `fail-hash`, `fail-build`, `cancel`, `unsupported-mod`, `diverged`, or `custom` to exercise the status flow without starting Java. The mock backend is available for screenshots and tests; the normal default is the real patcher process. Set `GAIUS_BUILDER_WORK_DIR` to change the local location for the terms acceptance record and default builds. The builder restores a record only if its SHA-256 matches the exact displayed text in the active language and its Unix acceptance time is plausible. Revoke removes the local record.

## CLI

```sh
gaius-builder doctor
gaius-builder doctor --json
gaius-builder version
gaius-builder --help
```

The plain `doctor` command relays the patcher's output and exit status. `doctor --json` emits parsed fields, version installation state, raw output, and exit code.

## Screenshots

`--state page-1` through `--state page-6` selects a page. `terms-accepted` renders page 3 after scrolling to the end and checking the acceptance box; `mods-scrolled` renders page 4 after scrolling the first mod row into view; `toolchain-field` opens the directory settings on page 2. Other supported states are `detect-fail`, `hash-error`, `running`, `build-success`, `build-success-three`, `build-fail`, `cancelled`, `unsupported-mod`, `badge-green`, `badge-yellow`, and `badge-red`. `build-success` selects the completion page (page 6) and uses a successful mock result; `build-success-three` renders successful results for all three profiles. `real-preflight-error` invokes the real Java backend for 26.3 and renders its missing-toolchain JSON error; set `GAIUS_PATCHER_SOURCE` to the patcher source file to use.

Status marks are painted as vector icons, so the UI does not require check, cross, warning, ring, bullet, or chevron glyphs. Native startup appends a system CJK font and then a symbol fallback where available. Set `GAIUS_BUILDER_NO_SYSTEM_FONTS=1` to exercise the default egui fonts only; missing glyphs render as tofu and never panic.

`tools-optional`, `tools-strict`, and `tools-python-exe` render the tool check
with missing optional kernel tools, missing required kernel tools, and a resolved
Windows Python command. `verify-ok`, `verify-running`, and `verify-failed-index`
render the official file check. `terms-pre-accepted` renders restored acceptance. These states use sample results without starting Java or modifying the local acceptance record.

Page 2 also supports `host-tools-missing`, `host-tools-mismatch`,
`host-tools-ready`, `shader-verified`, `shader-missing`, `shader-tampered`,
`maven-partial`, and `maven-ready`. Use `shader-missing-scrolled` to capture the
expected files and folder picker in a small window. For example:

```sh
gaius-builder --screenshot /path/to/page.png --state page-4 \
  --theme dark --lang zh --size 960x620
gaius-builder --screenshot /path/to/failure.png --state build-fail \
  --page 5 --theme light --lang en --size 960x620
gaius-builder --screenshot /path/to/scale.png --state page-4 \
  --scale 1.25 --size 960x620
gaius-builder --screenshot /path/to/shader-missing.png --page 2 \
  --state shader-missing --theme light --lang en --size 800x540 --scale 1.5
GAIUS_PATCHER_SOURCE=/path/to/GaiusPatcher.java \
  gaius-builder --screenshot /path/to/preflight.png \
  --state real-preflight-error --page 5 --size 960x620
```

## Display scaling

**Auto** follows the operating system without additional UI zoom. The other
choices (100%, 125%, 150%, 175%, 200%, 250%) are relative to the display scale.
Use **Ctrl+=**, **Ctrl+-**, **Ctrl+0** (Auto), or **Ctrl+mouse wheel**. The builder
reduces the effective zoom as needed to keep the compact layout usable, without
changing your saved preference. The About dialog shows the native and effective
scales and can copy startup/current display geometry and font diagnostics.

The builder saves its zoom and last regular window placement in
`ui-settings.json` in its local work directory. It discards placement if the
saved position is not on a connected monitor. Linux/macOS placement is best
effort where the compositor does not expose positions or the work area.
The window, screenshot, and DPI report use the same work directory: `--work`
overrides `GAIUS_BUILDER_WORK_DIR`, which overrides the platform default.
Explicit `--scale` overrides the saved zoom for the current invocation. Missing
or unreadable settings fall back to Auto. Preview modes don't write UI settings
or restore saved window placement.
Offscreen `--screenshot` accepts `--native-ppp` separately from `--scale`;
`--size` is the window size in native logical points. Screenshot stdout and
`--dpi-report` JSON include `requested_zoom`, `requested_zoom_auto`,
`effective_zoom`, and `zoom_source` (`cli`, `settings`, or `default`).

## Windows DPI verification

Use a build of the real `gaius-builder.exe` with `--dpi-report` support, on an
unlocked interactive Windows 10 (1703+) or Windows 11 desktop. Run the kit in a
fresh Windows PowerShell 5.1 or PowerShell 7 session. From `patcher\gui`:

```powershell
.\tools\dpi-check.ps1 -BuilderExe .\target\release\gaius-builder.exe `
  -OutDir 'D:\gaius-evidence' -TempDir 'D:\gaius-scratch' -Capture -Theme dark -Lang zh
```

Use your designated output and scratch roots. `-OutDir` and `-TempDir` can
instead come from `GAIUS_BUILDER_OUT_DIR` and `GAIUS_BUILDER_TMP_DIR`; if no
temp root is given the script uses Windows' temp directory. Each run creates
its own `dpi-*` output subdirectory and a separate temporary subdirectory. Raw
builder reports and measurements are retained even if a later case fails.

The kit runs eight mock previews: pages 1–6, page 4 with the Mods tab, and the
successful completion page. It waits for each JSON report and a visible window
owned by the launched PID. It checks the OS-reported executable path and
requires **PerMonitorV2** on both the builder window and the PowerShell capture
thread, even if the PowerShell process DPI default was already set. It closes
each preview with `WM_CLOSE`, falling back to stopping only the process it
started. It does not run a build or write builder settings.

Each case produces `*.report.json` from the builder and `*.window.json` with
`GetDpiForWindow` and the physical `GetWindowRect` outer bounds. With `-Capture`
it also saves an unscaled `*.png` of those bounds (including any invisible
resize border reported by Windows). Without `-Capture` the same native checks
run, but no PNGs are saved. Captures read the real desktop: use a display large
enough to fit the entire outer rectangle on one monitor, and keep other
windows, overlays, and notifications away from the preview. Keep the display
settings unchanged during a run.

The native preview CLI is:

```text
gaius-builder.exe --dpi-report <file.json> [--page 1..6] [--state config-mods|build-success|normal] [--theme dark|light] [--lang zh|en] [--dpi-hold]
```

Without `--dpi-hold`, the builder exits after the first rendered frame. With
it, the read-only mock preview stays visible until closed. The report is a startup
snapshot, not a log of DPI changes. For manual verification:

1. Set Windows **Settings → System → Display → Scale** on the intended monitor.
   Confirm the preview opens on that monitor, then run `-Capture` separately at
   100%, 125%, 150%, 200%, and 250%. Compare `dpi` against the
   expected 96, 120, 144, 192, and 240 respectively. Check PNG sizes against the recorded
   physical width and height. Inspect text, layout, scrollbars, and footer
   controls for blur or clipping, especially on Mods and completion.
2. On two monitors with different scales, launch a single held preview (for
   example, `gaius-builder.exe --dpi-report <new-file.json> --page 4 --state
   config-mods --theme light --lang en --dpi-hold`). Drag it fully onto the
   other monitor and back without restarting. Check that text redraws sharply,
   layout scales without clipping. Repeat on page 6 with `--state build-success`.
   Close the previews yourself. Then repeat in the normal interactive GUI to
   check click and scroll alignment, keyboard focus, resize limits, About's
   live DPI numbers, and window/zoom restore. Unplug the monitor that held the
   window and restart to check that it reopens centered on a connected display.
   Separate startup captures do not verify a live transition.

## Checks

```sh
export GAIUS_BUILDER_OUT_DIR="$PWD/patcher/gui/target/check"
export GAIUS_BUILDER_TMP_DIR="${TMPDIR:-/tmp}/gaius-builder-check"
bash patcher/gui/check.sh
```

The check script runs formatting, clippy, unit tests, the Java doctor smoke test, and one headless screenshot. Java must be available on `PATH` for the doctor check.
