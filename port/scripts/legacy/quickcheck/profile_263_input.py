#!/usr/bin/env python3
"""Minecraft 26.3 input/window assertions for quick-check (work package P2).

The quick-check framework (profile dispatch) belongs to work package P9; this module
only provides the 26.3 SDL3 window/input checks. Integration contract:

    from quickcheck import profile_263_input
    for name, passed in profile_263_input.checks(port_root, overlay_dir, javap):
        ...

``overlay_dir`` is ``port/work/overlays/26.3`` (or None to run the source checks only);
``javap`` is ``callable(classpath: list[Path], class_name: str) -> str`` returning
``javap -c -p`` output. Every check is a ``(name, bool)`` tuple, like quick-check.py.

Standalone:

    python port/scripts/legacy/quickcheck/profile_263_input.py [--overlay-dir DIR] [--java-home DIR]

prints ``PASS``/``FAIL`` lines and exits with status 1 when a check fails.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Callable, Iterable

OVERLAY = Path("overrides/libraries/lwjgl-sdl/src/main/java/org/lwjgl/sdl")
PATCHER = Path("tools/src/main/java/dev/gaius/tools/LwjglSdlBrowserPatcher.java")
USAGE_LOCK = Path("mc/profiles/26.3/usage.lock")
VERSION_PROFILE = Path("versions/26.3.json")
INPUT_PATCHES = Path("tools/src/main/java/dev/gaius/tools/Minecraft263BrowserPatcher.java")
CLIENT_PATCHER = Path("tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java")
TELEMETRY = Path("src/main/java/dev/gaius/browser/BrowserInputTelemetry.java")
DISPATCH = Path("src/main/java/dev/gaius/browser/BrowserInputDispatch.java")
BUILD_OVERLAYS = Path("scripts/build-overlays.sh")
BRINGUP = Path("tools/bringup/26.3.txt")

Check = tuple[str, bool]
Javap = Callable[[list[Path], str], str]


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


def _step(port: Path, patcher: Path) -> str:
    """A patcher's source followed by its domain classes (port/mc/patches/<domain>):
    the classes under tools/patches/ whose class comment names the step, "The <domain> domain of
    the <Patcher> step" (port/tools/patchkit/README.md, "Domains")."""
    texts = [_read(port / patcher)]
    marker = re.compile(r"domain of the " + re.escape(patcher.stem) + r" step\b")
    for path in sorted((port / patcher.parent / "patches").glob("*/*.java")):
        text = _read(path)
        if marker.search(re.sub(r"\s+\*\s+", " ", text)):
            texts.append(text)
    return "\n".join(texts)


def _entries(lock_text: str) -> list[tuple[str, str, str]]:
    """The SDL entry points of the usage lock's lwjgl-sdl section: (owner, name, descriptor)."""
    entries = []
    in_sdl = False
    for line in lock_text.splitlines():
        if line.startswith("["):
            in_sdl = line.startswith("[library org.lwjgl:lwjgl-sdl:")
            continue
        match = re.fullmatch(r"M org/lwjgl/sdl/(SDL\w*)\.(SDL_\w+)(\(.*)", line.strip()) if in_sdl else None
        if match:
            entries.append((match.group(1), match.group(2), match.group(3)))
    return entries


def _required_entries(port: Path) -> set[str]:
    """requiredEntries (owner/name) of the 26.3 render seam that are SDL entry points."""
    profile = _read(port / VERSION_PROFILE)
    seam = re.search(r'"renderSeam"\s*:\s*"([^"]+)"', profile)
    if not seam or seam.group(1) == "none":
        return set()
    manifest = _read(port / "mc" / "seams" / seam.group(1) / "seam.json")
    block = re.search(r'"requiredEntries"\s*:\s*\[([^\]]*)\]', manifest)
    names = re.findall(r'"([^"]+)"', block.group(1)) if block else []
    return {name for name in names if name.startswith("org/lwjgl/sdl/")}


def source_checks(port: Path) -> list[Check]:
    overlay = port / OVERLAY
    sdl = _read(overlay / "BrowserSdl.java")
    dom = _read(overlay / "BrowserSdlDom.java")
    events = _read(overlay / "BrowserSdlEvents.java")
    video = _read(overlay / "BrowserSdlVideo.java")
    keyboard = _read(overlay / "BrowserSdlKeyboard.java")
    patcher = _read(port / PATCHER)
    input_patches = _step(port, INPUT_PATCHES)
    client_patcher = _step(port, CLIENT_PATCHER)
    telemetry = _read(port / TELEMETRY)
    dispatch = _read(port / DISPATCH)
    build = _read(port / BUILD_OVERLAYS)
    bringup = _read(port / BRINGUP)
    entries = _entries(_read(port / USAGE_LOCK))
    entry_names = {name for _, name, _ in entries}
    defined = set(re.findall(r"public static [\w.<>\[\]]+ (SDL_\w+)\(", sdl))
    required = _required_entries(port)
    required_names = {entry.rsplit(".", 1)[1] for entry in required}
    bringup_ids = [
        line.split("|")[0].strip()
        for line in (raw.split("#", 1)[0] for raw in bringup.splitlines())
        if line.strip()
    ]
    bringup_owners = [
        line.split("|")[1].strip()
        for line in (raw.split("#", 1)[0] for raw in bringup.splitlines())
        if line.strip() and line.count("|") == 2
    ]
    return [
        (
            "26.3 SDL patcher takes its entry table from usage.lock and fails closed",
            bool(entries)
            and "ENTRY_COUNT" not in patcher
            and "entries(lock)" in patcher
            and "lock.seamRequiredEntries()" in patcher
            and "SDL entry points not found" in patcher
            and "replaced != entries.size()" in patcher
            and 'code.add(new InsnNode(Opcodes.ACONST_NULL));' in patcher
            and "loadNative=" in patcher,
        ),
        (
            "26.3 BrowserSdl defines the SDL entry points the render seam requires; the rest may be FEATURE stubs",
            bool(required_names)
            and required_names <= entry_names
            and required_names <= defined
            and (entry_names <= defined or (port / OVERLAY / "BrowserSdlUnsupported.java").is_file()),
        ),
        (
            "26.3 SDL_GL_CreateContext creates window.__gaiusWebGL (contract C5)",
            "window.__gaiusWebGL = canvasElement.getContext('webgl2'" in dom
            and "static long createContext(long window)" in video
            and "ensureContext() ? GL_CONTEXT : 0L" in video
            and "(flags & SDL_WINDOW_OPENGL) != 0L && !ensureContext()" in video,
        ),
        (
            "26.3 SDL_GL_SwapWindow is the cooperative frame yield",
            "BrowserSdlDom.swapWindow(BrowserSdlVideo.swapInterval())" in sdl
            and "boolean hidden = swapBuffersJs();" in dom
            and "@Async\n    private static native void yieldAfterPresent(boolean hidden, int interval);" in dom
            and "private static native void scheduleFrameYield(boolean hidden, int interval, FrameYieldCallback resume);" in dom,
        ),
        (
            "26.3 SDL events: re-entrant queue head, flush keeps other types, text only while active",
            "w.__gaiusSdlEvents = events;" in dom
            and "sdl.poll = () =>" in dom
            and "sdl.flush = (min, max) =>" in dom
            and "const text = sdl.textInput && printable(e) ? e.key : null;" in dom
            and "if (text !== null) push(record(771" in dom
            and "if (cssChanged) pushWindow(518, cssWidth, cssHeight);" in dom
            and "pushWindow(519, framebufferWidth, framebufferHeight)" in dom
            and "last[7] += +xrel || 0;" in dom
            and "BrowserSdlKeyboard.resync(source)" in events
            and "memSet(address, 0, SDL_Event.SIZEOF);" in events,
        ),
        (
            "26.3 SDL keyboard state is a persistent 512-entry buffer",
            "static final int SCANCODE_COUNT = 512;" in keyboard
            and "MemoryUtil.memCalloc(SCANCODE_COUNT)" in keyboard
            and "return BrowserSdlKeyboard.state();" in sdl,
        ),
        (
            "26.3 input patches: SDLEventHandler runNow x8, onButton telemetry, openUri, SdlDebug",
            "SDL_EVENT_HANDLER_EXECUTE_SITES = 8" in input_patches
            and '"runNow"' in input_patches
            and '"reportMouseHandlerEntry"' in input_patches
            and '"reportMouseHandlerDispatch"' in input_patches
            and '"reportMouseClickedResult"' in input_patches
            and "net/minecraft/client/gui/screens/LoadingOverlay" in input_patches
            and "patchBlaze3dOpenUri" in input_patches
            and "patchSdlDebug" in input_patches
            and "checkSdlShimCoverage(jar);" in input_patches
            and "org/lwjgl/glfw" not in input_patches,
        ),
        (
            "26.3 input telemetry keeps the BrowserGlfw signatures (contract C6)",
            "public static native void reportMouseHandlerEntry(\n            long callbackWindow, long minecraftWindow, int button, int action);" in telemetry
            and "public static native void reportMouseHandlerDispatch(\n            double scaledX, double scaledY, boolean pressed, Object screen);" in telemetry
            and "public static native void reportMouseClickedResult(\n            boolean clicked, double scaledX, double scaledY, Object screen);" in telemetry
            and "public static void runNow(Minecraft minecraft, Runnable task)" in dispatch,
        ),
        (
            "MinecraftClientPatcher drops its GLFW input patches only when the GLFW targets are absent",
            'PatchRegistry.dropped("MinecraftClientPatcher.patchBrowserMouseHandler", jar,' in client_patcher
            and 'PatchRegistry.dropped("MinecraftClientPatcher.patchBrowserKeyboardHandler", jar,' in client_patcher
            and 'PatchRegistry.dropped("MinecraftClientPatcher.patchInputConstants.glfw", jar,' in client_patcher
            and 'PatchRegistry.dropped("MinecraftClientPatcher.patchOpenUri.utilOs", jar,' in client_patcher
            and "hasGlfwInputSetup(jar)" in client_patcher,
        ),
        (
            "build-overlays runs LwjglSdlBrowserPatcher and the callback step on lwjgl-sdl",
            '"lwjgl-sdl|optional|yes|always|patcher:LwjglSdlBrowserPatcher' in build
            and "callbacks\"" in build.split('"lwjgl-sdl|', 1)[-1].split("\n", 1)[0],
        ),
        (
            "the 26.3 bring-up list has no P2 entries",
            (port / BRINGUP).is_file()
            and "step:LwjglSdlBrowserPatcher" not in bringup_ids
            and "MinecraftClientPatcher.patchBrowserInputCallbacks" not in bringup_ids
            and "P2" not in bringup_owners,
        ),
    ]


def _find_one(root: Path, pattern: str) -> Path | None:
    matches = sorted(root.glob(pattern))
    return matches[0] if matches else None


def artifact_checks(overlay_dir: Path, javap: Javap) -> list[Check]:
    sdl_jar = _find_one(overlay_dir, "libraries/org/lwjgl/lwjgl-sdl/*/lwjgl-sdl-*.jar")
    client_jar = overlay_dir / "client-named-26.3-gaius.jar"
    checks: list[Check] = []
    if sdl_jar is None or not client_jar.is_file():
        return [("26.3 overlay artifacts exist (lwjgl-sdl jar and patched client)", False)]
    with zipfile.ZipFile(sdl_jar) as archive:
        names = set(archive.namelist())
    checks.append((
        "26.3 lwjgl-sdl overlay jar contains the browser shim",
        {"org/lwjgl/sdl/BrowserSdl.class", "org/lwjgl/sdl/BrowserSdlDom.class",
         "org/lwjgl/sdl/BrowserSdlEvents.class", "org/lwjgl/sdl/BrowserSdlKeyboard.class",
         "org/lwjgl/sdl/BrowserSdlVideo.class"} <= names,
    ))
    sdl = javap([sdl_jar], "org.lwjgl.sdl.SDL")
    timer = javap([sdl_jar], "org.lwjgl.sdl.SDLTimer")
    events = javap([sdl_jar], "org.lwjgl.sdl.SDLEvents")
    checks.append((
        "26.3 SDL.<clinit> loads no native library and entry points delegate to BrowserSdl",
        "Library.loadNative" not in sdl
        and "org/lwjgl/sdl/BrowserSdl.SDL_GetTicksNS:()J" in timer
        and "org/lwjgl/sdl/BrowserSdl.SDL_PollEvent:(Lorg/lwjgl/sdl/SDL_Event;)Z" in events,
    ))
    handler = javap([client_jar], "com.mojang.blaze3d.platform.SDLEventHandler")
    mouse = javap([client_jar], "net.minecraft.client.MouseHandler")
    constants = javap([client_jar], "com.mojang.blaze3d.platform.InputConstants")
    checks.append((
        "26.3 patched client dispatches SDL input synchronously and reports through BrowserInputTelemetry",
        handler.count("dev/gaius/browser/BrowserInputDispatch.runNow") == 8
        and "Minecraft.execute:(Ljava/lang/Runnable;)V" not in handler
        and "dev/gaius/browser/BrowserInputTelemetry.reportMouseHandlerEntry:(JJII)V" in mouse
        and "org/lwjgl/glfw" not in mouse
        and "KEYSYM" not in constants,
    ))
    return checks


def checks(port: Path, overlay_dir: Path | None, javap: Javap | None) -> list[Check]:
    result = source_checks(port)
    if overlay_dir is not None and javap is not None:
        result.extend(artifact_checks(overlay_dir, javap))
    return result


def _javap_runner(java_home: str | None) -> Javap:
    tool = None
    for home in [java_home, os.environ.get("GAIUS_JAVA_HOME"), os.environ.get("JAVA_HOME")]:
        if home:
            candidate = Path(home) / "bin" / ("javap.exe" if os.name == "nt" else "javap")
            if candidate.is_file():
                tool = str(candidate)
                break
    tool = tool or shutil.which("javap") or "javap"

    def run(classpath: list[Path], class_name: str) -> str:
        completed = subprocess.run(
            [tool, "-classpath", os.pathsep.join(str(path) for path in classpath), "-c", "-p", class_name],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300,
        )
        return completed.stdout if completed.returncode == 0 else ""

    return run


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--port", default=str(Path(__file__).resolve().parents[3]))
    parser.add_argument("--overlay-dir", default=None)
    parser.add_argument("--java-home", default=None)
    args = parser.parse_args(list(argv) if argv is not None else None)
    port = Path(args.port)
    overlay_dir = Path(args.overlay_dir) if args.overlay_dir else None
    results = checks(port, overlay_dir, _javap_runner(args.java_home) if overlay_dir else None)
    for name, passed in results:
        print(("PASS " if passed else "FAIL ") + name)
    failed = sum(1 for _, passed in results if not passed)
    print(f"profile_263_input: {len(results) - failed}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
