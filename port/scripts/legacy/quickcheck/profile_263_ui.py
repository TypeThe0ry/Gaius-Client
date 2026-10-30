#!/usr/bin/env python3
r"""Minecraft 26.3 client glue / UI assertions (work package P8).

Checks the patched 26.3 client overlay jar (build-overlays.sh output) with javap, so it needs
no TeaVM build. It is the P8 domain module of quick-check (PLAN "quickcheck/profile_263_ui.py");
until the quick-check profile dispatch calls it, run it directly:

    python port/scripts/legacy/quickcheck/profile_263_ui.py \
        [--jar port/work/overlays/26.3/client-named-26.3-gaius.jar] \
        [--vanilla port/work/26.3/client-named.jar] \
        [--runtime-classes port/target/26.3/maven/classes]

The runtime-class checks run when the 26.3 source set was compiled (generate-pom.sh and a
javac-only mvnw compile); without them the run fails unless
--allow-missing-runtime-classes is given.

Exit status 0 when every check passes, 1 otherwise, 2 when an input is missing.

``checks(jar, vanilla, javap)`` returns ``[(name, ok, detail)]`` for callers that aggregate.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
DEFAULT_JAR = ROOT / "port" / "work" / "overlays" / "26.3" / "client-named-26.3-gaius.jar"
DEFAULT_VANILLA = ROOT / "port" / "work" / "26.3" / "client-named.jar"
DEFAULT_RUNTIME_CLASSES = ROOT / "port" / "target" / "26.3" / "maven" / "classes"

LAN = "dev/gaius/browser/BrowserLanSession.maybeAddButton"
FONT_CACHE = "dev/gaius/browser/BrowserFontBitmapCache"
FIXED_CTOR_ARGS = (
    "net.minecraft.server.packs.PackLocationInfo, "
    "net.minecraft.server.packs.resources.ResourceMetadata, java.util.Set<java.lang.String>, "
    "java.util.List<java.nio.file.Path>, "
    "java.util.Map<net.minecraft.server.packs.PackType, java.util.List<java.nio.file.Path>>"
)


def resolve_javap() -> Path:
    for variable in ("GAIUS_JAVA_HOME", "JAVA_HOME"):
        home = os.environ.get(variable)
        if not home:
            continue
        if os.name == "nt" and re.match(r"^/[A-Za-z]/", home):
            home = f"{home[1].upper()}:{home[2:]}"
        for name in ("javap.exe", "javap"):
            candidate = Path(home) / "bin" / name
            if candidate.is_file():
                return candidate
    found = shutil.which("javap")
    if not found:
        raise FileNotFoundError("javap not found (set GAIUS_JAVA_HOME or JAVA_HOME)")
    return Path(found)


class Javap:
    def __init__(self, javap: Path, jar: Path):
        self.javap = javap
        self.jar = jar
        self.cache: dict[str, str] = {}

    def __call__(self, class_name: str) -> str:
        if class_name not in self.cache:
            result = subprocess.run(
                [str(self.javap), "-c", "-p", "-constants", "-cp", str(self.jar), class_name],
                capture_output=True, text=True, encoding="utf-8", errors="replace")
            if result.returncode != 0:
                raise RuntimeError(f"javap {class_name} failed: {result.stderr.strip()}")
            self.cache[class_name] = result.stdout
        return self.cache[class_name]


def method(text: str, header_fragment: str) -> str:
    """The javap body of the first member whose header line contains header_fragment."""
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.startswith("  ") and not line.startswith("   ") and header_fragment in line:
            body = [line]
            for follow in lines[index + 1:]:
                if follow.startswith("  ") and not follow.startswith("   ") and follow.strip():
                    break
                body.append(follow)
            return "\n".join(body)
    return ""


def position(body: str, needle: str) -> int:
    return body.find(needle)


def ordered(body: str, *needles: str) -> bool:
    cursor = -1
    for needle in needles:
        found = body.find(needle, cursor + 1)
        if found < 0:
            return False
        cursor = found
    return True


def int_constant(text: str, field: str) -> int | None:
    match = re.search(rf"public static final int {re.escape(field)} = (-?\d+);", text)
    return int(match.group(1)) if match else None


def instruction_lines(body: str) -> list[str]:
    return [line.strip() for line in body.splitlines() if re.match(r"^\s+\d+: ", line)]


def pushes(line: str, value: int) -> bool:
    return re.search(rf"\b(?:bipush|sipush)\s+{value}\b", line) is not None or (
        "ldc" in line and re.search(rf"// int {value}\b", line) is not None)


def preset_distances(body: str) -> dict[str, list[int]]:
    """Per GraphicsPreset arm, the int pushed right after Options.renderDistance/simulationDistance."""
    result: dict[str, list[int]] = {"renderDistance": [], "simulationDistance": []}
    code = instruction_lines(body)
    for index, line in enumerate(code):
        for option in result:
            if f"Options.{option}:" in line and index + 1 < len(code):
                match = re.search(r"\b(?:bipush|sipush)\s+(-?\d+)", code[index + 1])
                result[option].append(int(match.group(1)) if match else -1)
    return result


def checks(jar: Path, vanilla: Path, javap_path: Path) -> list[tuple[str, bool, str]]:
    out: list[tuple[str, bool, str]] = []
    patched = Javap(javap_path, jar)
    original = Javap(javap_path, vanilla)

    def check(name: str, ok: bool, detail: str = "") -> None:
        out.append((name, bool(ok), detail))

    # --- PauseScreen: LAN before the Options/World Options row, 26.3 anchor ------------------
    pause = method(patched("net.minecraft.client.gui.screens.PauseScreen"), "void createPauseMenu()")
    check("PauseScreen calls BrowserLanSession.maybeAddButton once",
          pause.count(LAN) == 1, f"calls={pause.count(LAN)}")
    check("PauseScreen LAN hook runs before the level check, Options, World Options, Disconnect",
          ordered(pause, LAN, "Field net/minecraft/client/Minecraft.level:",
                  "Field OPTIONS:", "Field WORLD_OPTIONS:",
                  "CommonComponents.disconnectButtonLabel"))
    check("PauseScreen disconnect label asks BrowserSingleplayerClient.isLocalSession",
          "dev/gaius/browser/BrowserSingleplayerClient.isLocalSession" in pause
          and "Minecraft.isLocalServer" not in pause)

    # --- Options: vanilla sprint default, low simulation distance, Fast preset, distance sync -
    constants = original("com.mojang.blaze3d.platform.InputConstants")
    key_r = int_constant(constants, "KEY_R")
    key_lcontrol = int_constant(constants, "KEY_LCONTROL")
    check("InputConstants KEY_R/KEY_LCONTROL are 26.3 SDL scancodes",
          (key_r, key_lcontrol) == (21, 224), f"KEY_R={key_r} KEY_LCONTROL={key_lcontrol}")
    options_text = patched("net.minecraft.client.Options")
    options_init = method(options_text, "net.minecraft.client.Options(net.minecraft.client.Minecraft")
    code = instruction_lines(options_init)
    sprint_key = None
    for index, line in enumerate(code):
        if "// String key.sprint" not in line:
            continue
        window = code[index + 1:index + 12]
        if any("ToggleKeyMapping.\"<init>\"" in follow for follow in window):
            sprint_key = window
            break
    check("Options key.sprint default remains KEY_LCONTROL (224)",
          sprint_key is not None and key_r is not None
          and key_lcontrol is not None
          and any(pushes(line, key_lcontrol) for line in sprint_key[:2]),
          "window=" + (" | ".join(sprint_key[:3]) if sprint_key else "missing"))
    check("Options simulation distance allows the low browser range",
          "DEBUG_ALLOW_LOW_SIM_DISTANCE" not in options_init
          and "// String options.simulationDistance" in options_init)
    preset = options_init[options_init.find("// String options.graphics.preset"):]
    preset = preset[:preset.find("Field graphicsPreset:") + 1] if "Field graphicsPreset:" in preset else ""
    check("Options graphics preset defaults to FAST",
          "GraphicsPreset.FAST" in preset and "GraphicsPreset.FANCY" not in preset)
    check("Options.save syncs worker distances",
          "BrowserSingleplayerClient.syncDistances" in method(options_text, "public void save()"))

    # --- Fonts: bitmap image sharing on the 26.3 read(InputStream) shape, provider index -------
    definition = method(patched("net.minecraft.client.gui.font.providers.BitmapProvider$Definition"),
                        "com.mojang.blaze3d.font.GlyphProvider load(")
    check("BitmapProvider$Definition.load reads through the 3-argument BrowserFontBitmapCache.read",
          f"{FONT_CACHE}.read:(Ljava/io/InputStream;Ljava/lang/Object;Ljava/lang/Object;)" in definition
          and "NativeImage.read" not in definition
          and f"{FONT_CACHE}.shareHolder" in definition and f"{FONT_CACHE}.release" in definition)
    holder = method(patched("net.minecraft.client.gui.font.providers.BitmapProvider$ImageDataHolder"),
                    "public void close()")
    check("BitmapProvider$ImageDataHolder.close releases shared ownership",
          f"{FONT_CACHE}.releaseOwnership" in holder)
    font_set = patched("net.minecraft.client.gui.font.FontSet")
    check("FontSet provider selection uses BrowserFontProviderIndex",
          "dev/gaius/browser/BrowserFontProviderIndex.wrap" in font_set
          and "dev/gaius/browser/BrowserFontProviderIndex.iterator" in font_set)

    # --- Resource packs ---------------------------------------------------------------------
    supplier = method(patched("net.minecraft.server.packs.FilePackResources$FileResourcesSupplier"),
                      "openResources(")
    check("FilePackResources openResources merges safe atlas overlays",
          ordered(supplier, "Pack$Metadata.overlays:",
                  "dev/gaius/browser/BrowserPackOverlayCompat.mergeSafeAtlasOverlays"))
    fixed = patched("net.minecraft.server.packs.FixedPathPackResources")
    ctor_line = next((line for line in fixed.splitlines()
                      if f"net.minecraft.server.packs.FixedPathPackResources({FIXED_CTOR_ARGS})" in line),
                     "")
    check("FixedPathPackResources is the Gaius browser-archive override with a builder-visible ctor",
          bool(ctor_line) and not ctor_line.strip().startswith("private")
          and "java.util.Set<java.lang.String> resourceSet" in fixed
          and "__gaiusVanillaAssets" not in fixed  # JSBody bodies live in annotations
          and "externalResourceLength" in fixed and "listRawPaths" in fixed,
          ctor_line.strip())
    builder = patched("net.minecraft.server.packs.FixedPathPackResources$Builder")
    check("FixedPathPackResources$Builder still constructs the override",
          "Method net/minecraft/server/packs/FixedPathPackResources.\"<init>\":(Lnet/minecraft/server/packs/PackLocationInfo;"
          "Lnet/minecraft/server/packs/resources/ResourceMetadata;Ljava/util/Set;Ljava/util/List;Ljava/util/Map;)V"
          in builder)
    pack_builder = patched("net.minecraft.server.packs.VanillaPackResourcesBuilder")
    build = method(pack_builder, "net.minecraft.server.packs.VanillaPackResources build(")
    check("VanillaPackResourcesBuilder.build exposes the full resources as the only layer",
          ordered(build, "FixedPathPackResources$Builder.build:", "List.of:(Ljava/lang/Object;)",
                  "VanillaPackResources.\"<init>\"")
          and "invokedynamic" not in build and "Stream" not in build)
    check("VanillaPackResourcesBuilder skips the desktop jar-root probe",
          "ImmutableMap.of:()" in method(pack_builder, "lambda$static$1()"))
    check("VanillaPackResources is the vanilla 26.3 holder (not a 26.2 override)",
          "FixedPathPackResources fullResources" in patched("net.minecraft.server.packs.VanillaPackResources"))

    # --- Mining hit sound --------------------------------------------------------------------
    breaking = method(patched("net.minecraft.client.multiplayer.ClientLevel"), "void playBreakingSound(")
    check("ClientLevel.playBreakingSound divides the hit volume by 4",
          ordered(breaking, "SoundType.getHitSound", "// float 4.0f", "fdiv")
          and "// float 8.0f" not in breaking)
    check("MultiPlayerGameMode has no hit sound left (26.2 patch dropped on 26.3)",
          "SoundType.getHitSound" not in patched("net.minecraft.client.multiplayer.MultiPlayerGameMode"))

    # --- Title screen ------------------------------------------------------------------------
    title = patched("net.minecraft.client.gui.screens.TitleScreen")
    check("TitleScreen.init adds the Edit Profile button",
          "dev/gaius/browser/BrowserProfileScreen.titleButton" in method(title, "protected void init()"))
    check("TitleScreen.tick opens the profile editor on first launch",
          "dev/gaius/browser/BrowserProfileScreen.titleTick" in method(title, "public void tick()"))
    check("TitleScreen realms notifications are off and the About action is wired",
          "iconst_0" in method(title, "realmsNotificationsEnabled()")
          and "gaius.title_about" in title
          and "dev/gaius/browser/BrowserAboutScreen.open" in title)

    # --- Verified-only patches (M262 / MCP) ----------------------------------------------------
    distances = preset_distances(method(patched("net.minecraft.client.GraphicsPreset"), "void apply("))
    check("GraphicsPreset FAST and FANCY pin browser distances render=8 simulation=6",
          distances["renderDistance"][:2] == [8, 8] and distances["simulationDistance"][:2] == [6, 6],
          str(distances))
    identifier = method(patched("net.minecraft.resources.Identifier"), "resolveAgainst(")
    check("Identifier.resolveAgainst avoids Path.resolve(String, String[])",
          "java/nio/file/Path.resolve:(Ljava/lang/String;[Ljava/lang/String;)" not in identifier
          and ".resolve:(Ljava/nio/file/Path;Ljava/lang/String;[Ljava/lang/String;)" in identifier)
    queue = method(patched("net.minecraft.server.packs.DownloadQueue"),
                   "net.minecraft.server.packs.DownloadQueue(java.nio.file.Path)")
    check("DownloadQueue runs on the cooperative browser executor",
          "dev/gaius/browser/BrowserCooperativeExecutor.defer" in queue)
    port_probe = method(patched("net.minecraft.util.HttpUtil"), "int getAvailablePort()")
    check("HttpUtil.getAvailablePort returns the browser port without a ServerSocket",
          "sipush        25564" in port_probe and "java/net/ServerSocket" not in port_probe)
    return out


def runtime_checks(classes: Path, javap_path: Path) -> list[tuple[str, bool, str]]:
    """Checks of the compiled 26.3 Gaius sources (port/mc/shared sets over port/src/main)."""
    runtime = Javap(javap_path, classes)
    out: list[tuple[str, bool, str]] = []
    cache = runtime("dev.gaius.browser.BrowserFontBitmapCache")
    out.append(("26.3 BrowserFontBitmapCache has the three-argument read(InputStream, Object, Object) and decodes through VersionOps",
                 "read(java.io.InputStream, java.lang.Object, java.lang.Object)" in cache
                 and "VersionOps.readImage" in cache and "NativeImage.read:" not in cache, ""))
    lan = runtime("dev.gaius.browser.BrowserLanSession")
    add_button = method(lan, "void maybeAddButton(")
    column_span = method(runtime("dev.gaius.browser.ops.RenderpearlVersionOps"), "int pauseMenuLanButtonColumns(")
    out.append(("26.3 BrowserLanSession spans the LAN button over both pause-menu columns (VersionOps)",
                 "RowHelper.addChild:(Lnet/minecraft/client/gui/layouts/LayoutElement;I)" in add_button
                 and "VersionOps.pauseMenuLanButtonColumns" in add_button
                 and "iconst_2" in column_span, ""))
    out.append(("26.3 BrowserLanSession falls back to the running client version, not 26.2",
                 "net/minecraft/SharedConstants.getCurrentVersion" in lan
                 and "publishLanInvite(java.lang.String, java.lang.String)" in lan, ""))
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--vanilla", type=Path, default=DEFAULT_VANILLA)
    parser.add_argument("--runtime-classes", type=Path, default=DEFAULT_RUNTIME_CLASSES,
                        help="javac output of the 26.3 source set (generate-pom.sh + mvnw compile)")
    parser.add_argument("--allow-missing-runtime-classes", action="store_true",
                        help="skip the runtime class checks when --runtime-classes does not exist")
    args = parser.parse_args(argv)
    for path in (args.jar, args.vanilla):
        if not path.is_file():
            print(f"profile_263_ui: missing {path}", file=sys.stderr)
            return 2
    javap = resolve_javap()
    results = checks(args.jar, args.vanilla, javap)
    if args.runtime_classes.is_dir():
        results += runtime_checks(args.runtime_classes, javap)
    elif args.allow_missing_runtime_classes:
        print(f"NOTE: {args.runtime_classes} not found; 26.3 runtime class checks not run")
    else:
        # Fail closed: without the javac output the runtime checks would silently not run.
        results.append(("26.3 runtime classes present", False,
                        f"{args.runtime_classes} not found (run generate-pom.sh + mvnw compile for 26.3, "
                        "or pass --allow-missing-runtime-classes)"))
    failed = 0
    for name, ok, detail in results:
        print(f"{'PASS' if ok else 'FAIL'}: {name}" + (f" ({detail})" if detail and not ok else ""))
        failed += 0 if ok else 1
    print(f"profile_263_ui: {len(results) - failed}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
