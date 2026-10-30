#!/usr/bin/env python3
"""Minecraft 26.3 server, identity, authlib and storage checks (work package P7a).

Domain module for quick-check.py (the framework itself belongs to P9). It only reads the
built 26.3 overlays and the sources; it never builds anything.

API for the framework:
    checks(root: Path, overlay_dir: Path, javap: Callable[[Path, str], str]) -> list[(str, bool)]
where ``javap(classpath, class_name)`` returns ``javap -c -p`` output (quick-check.py's
``run_javap`` has this shape).

Standalone:
    python port/scripts/legacy/quickcheck/profile_263_server.py [--overlay-dir DIR] [--root DIR]
exits 0 when every check passes, 1 otherwise, 2 when the overlay is missing.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable

PROFILE_ID = "26.3"
SERVICES = "com/mojang/authlib/services"
DISCOVERY_RECORDS = (
    "com.mojang.authlib.services.response.discovery.DiscoveryResponse",
    "com.mojang.authlib.services.response.discovery.Discovery",
    "com.mojang.authlib.services.response.discovery.Endpoints",
    "com.mojang.authlib.services.response.discovery.Endpoint",
)
STORAGE_ROW = {
    "worldVersion": "5023",
    "database": "gaius-fs-v2-26.3",
    "prefix": "gaius.fs.v2:26.3:",
    "opfs": "regions-v2-26.3",
}


def method_body(text: str, header_fragment: str) -> str:
    """The javap -c body of the first method whose declaration line contains the fragment."""
    lines = text.splitlines(True)
    for index, line in enumerate(lines):
        stripped = line.strip()
        if line.startswith("  ") and not line.startswith("   ") and stripped.endswith(";") \
                and header_fragment in stripped:
            end = len(lines)
            for next_index in range(index + 1, len(lines)):
                candidate = lines[next_index]
                if candidate.startswith("  ") and not candidate.startswith("   ") \
                        and candidate.strip().endswith(";"):
                    end = next_index
                    break
            return "".join(lines[index:end])
    return ""


def last_opcodes(body: str, count: int) -> list[str]:
    opcodes = re.findall(r"^\s+\d+: (\w+)", body, re.MULTILINE)
    return opcodes[-count:]


def storage_rows(text: str) -> int:
    """Occurrences of a complete 26.3 storage row (all four values within one short span)."""
    rows = 0
    for match in re.finditer(r"26\.3", text):
        window = text[max(0, match.start() - 40):match.start() + 400]
        if all(value in window for value in STORAGE_ROW.values()):
            rows += 1
    return rows


def checks(root: Path, overlay_dir: Path, javap: Callable[[Path, str], str]) -> list[tuple[str, bool]]:
    client = overlay_dir / f"client-named-{PROFILE_ID}-gaius.jar"
    authlib = next(iter(sorted((overlay_dir / "libraries/com/mojang/authlib").glob("*/authlib-*.jar"))),
                   overlay_dir / "missing-authlib.jar")
    results: list[tuple[str, bool]] = []

    main = javap(client, "net.minecraft.server.Main")
    main_body = method_body(main, "public static void main(java.lang.String[])")
    discovery_create = "dev/gaius/browser/BrowserDiscoveryServices.create:(Ljava/net/Proxy;Z)"
    results.append((
        "26.3 server Main creates discovery through BrowserDiscoveryServices.create(Proxy, false)",
        "iconst_0" in main_body
        and discovery_create in main_body
        and f"{SERVICES}/MinecraftServicesDiscoveryService.create:" not in main_body
        and "BrowserIntegratedServerMain.rethrowStartupFailure" in main_body,
    ))

    world_loader = javap(client, "net.minecraft.server.WorldLoader")
    results.append((
        "26.3 WorldLoader startup telemetry marks WORLD_REGISTRIES loading",
        "world-loader-worldgen-registries-started" in world_loader
        and "world-loader-dimension-registries-started" in world_loader
        and "RegistryDataLoader.WORLD_REGISTRIES" in world_loader,
    ))

    pending = javap(client, "net.minecraft.resources.RegistryLoadTask$PendingRegistration")
    load_body = method_body(pending, "loadFromResource(")
    results.append((
        "26.3 datapack registry decode yields to the browser before both loadFromResource returns",
        load_body.count("BrowserStartupScheduler.datapackResourceDecoded") == 2
        and load_body.count("areturn") == 2,
    ))

    listener = javap(client, "net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener")
    prepare_body = method_body(listener, "java.util.Map<net.minecraft.resources.Identifier, T> prepare(")
    results.append((
        "26.3 client JSON reload listeners keep the per-resource startup yield in prepare",
        prepare_body.count("BrowserStartupScheduler.datapackResourceDecoded") == 1,
    ))

    player_list = javap(client, "net.minecraft.server.players.PlayerList")
    is_op = method_body(player_list, "public boolean isOp(net.minecraft.server.players.NameAndId)")
    results.append((
        "26.3 PlayerList.isOp ends with the browser allow-commands flag instead of false",
        "BrowserPlayerListCompat.commandsAllowedForAllPlayers:()Z" in is_op
        and last_opcodes(is_op, 2) == ["invokestatic", "ireturn"]
        and "setAllowCommandsForAllPlayers" not in player_list,
    ))

    listener_impl = javap(client, "net.minecraft.server.network.ServerGamePacketListenerImpl")
    position = method_body(listener_impl, "handlePlayerPositionChange(double, double, double")
    move_player = method_body(listener_impl, "public void handleMovePlayer(")
    results.append((
        "26.3 player movement keeps the Worker fast path and browser chunk tracking",
        position.count("BrowserIntegratedServerMain.moveServerPlayerChunkTracking") == 2
        and "ServerChunkCache.move:" not in position
        and "BrowserIntegratedServerMain.applyWorkerMovement" in move_player
        and "BrowserIntegratedServerMain.isWorkerServer" in move_player,
    ))

    validator = javap(client, "net.minecraft.util.SignatureValidator")
    services_from = method_body(
        validator, "from(com.mojang.authlib.services.ServicesKeySet, com.mojang.authlib.services.ServicesKeyType)")
    results.append((
        "26.3 chat signature validation uses the services key-set overload and is disabled",
        "NO_VALIDATION" in services_from and last_opcodes(services_from, 2) == ["getstatic", "areturn"]
        and "yggdrasil" not in validator,
    ))

    minecraft = javap(client, "net.minecraft.client.Minecraft")
    minecraft_init = method_body(minecraft, "Minecraft(net.minecraft.client.main.GameConfig)")
    results.append((
        "26.3 Minecraft.<init> creates discovery through BrowserDiscoveryServices (offline: no fetch)",
        minecraft_init.count(discovery_create) == 1
        and f"{SERVICES}/MinecraftServicesDiscoveryService.create:" not in minecraft_init,
    ))
    results.append((
        "26.3 identity bridge takes authlib 10's services ProfileResult",
        "public void gaius$replaceIdentity(net.minecraft.client.User, com.mojang.authlib.services.ProfileResult);"
        in minecraft
        and "com.mojang.authlib.yggdrasil" not in minecraft,
    ))

    resource_key = javap(client, "net.minecraft.resources.ResourceKey")
    resource_key_clinit = method_body(resource_key, "static {};")
    results.append((
        "26.3 ResourceKey.<clinit> keeps REGISTRY_STREAM_CODEC next to the browser VALUES map "
        "(update_tags and every registry-key packet encode through it)",
        "putstatic" in resource_key_clinit
        and "REGISTRY_STREAM_CODEC" in resource_key_clinit
        and "java/util/concurrent/ConcurrentHashMap" in resource_key_clinit
        and "MapMaker" not in resource_key_clinit,
    ))

    friends = javap(client, "net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler")
    friends_init = method_body(friends, "RemoteFriendListUpdateHandler(com.mojang.authlib.services.FriendsService")
    results.append((
        "26.3 remote friend list uses a browser HashSet",
        "java/util/HashSet" in friends_init and "CopyOnWriteArraySet" not in friends_init,
    ))

    skins = javap(client, "net.minecraft.client.resources.SkinManager")
    results.append((
        "26.3 uploaded skins cannot claim a Mojang signature",
        "BrowserUploadedSkin.skinSignatureState" in skins
        and "MinecraftProfileTextures.signatureState" not in skins,
    ))

    session = javap(authlib, "com.mojang.authlib.services.MinecraftServicesSessionService")
    helper = method_body(session, "gaiusAllowedTextureUrl(com.mojang.authlib.services.MinecraftServicesDiscoveryService")
    unpack = method_body(session, "unpackTextures(com.mojang.authlib.properties.Property)")
    results.append((
        "authlib 10 texture check: uploaded skins, discovery, textures.minecraft.net when offline",
        "data:image/png;base64," in helper
        and "MinecraftServicesDiscoveryService.isAllowedTextureDomain" in helper
        and "com/mojang/authlib/exceptions/MinecraftClientException" in helper
        and "https://textures.minecraft.net/texture/" in helper
        and "http://textures.minecraft.net/texture/" in helper
        and "gaiusAllowedTextureUrl:(Lcom/mojang/authlib/services/MinecraftServicesDiscoveryService;" in unpack
        and "MinecraftServicesDiscoveryService.isAllowedTextureDomain" not in unpack
        and "BrowserAuthlibGson.decodeTextures:(Ljava/lang/String;)"
            "Lcom/mojang/authlib/services/response/MinecraftTexturesPayload;" in unpack,
    ))
    records = [javap(authlib, record) for record in DISCOVERY_RECORDS]
    results.append((
        "authlib 10 discovery and key-set records have Gson no-argument constructors",
        all(f"public {name}();" in text for name, text in zip(DISCOVERY_RECORDS, records))
        and "MinecraftServicesKeyInfo$KeySetResponse();"
        in javap(authlib, "com.mojang.authlib.services.MinecraftServicesKeyInfo$KeySetResponse")
        and "MinecraftServicesKeyInfo$KeyData();"
        in javap(authlib, "com.mojang.authlib.services.MinecraftServicesKeyInfo$KeyData"),
    ))

    def source(path: str) -> str:
        try:
            return (root / path).read_text(encoding="utf-8")
        except OSError:
            return ""

    server_main = source("port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java")
    results.append((
        "Worker server.properties turns the 26.3 white-list default off",
        '"white-list=false"' in server_main and '"enforce-whitelist=false"' in server_main,
    ))
    compat = source("port/mc/shared/renderpearl-1/src/java/dev/gaius/browser/BrowserPlayerListCompat.java")
    results.append((
        "26.3 BrowserPlayerListCompat sets the allow-commands flag read by PlayerList.isOp",
        "public static boolean commandsAllowedForAllPlayers()" in compat
        and "commandsAllowedForAllPlayers = true;" in compat
        and "UnsupportedOperationException" not in compat,
    ))
    profile = subprocess.run([
        "node", "--input-type=module", "-e",
        'import {sourceFile} from "./port/tools/sourcesets/sourcesets.mjs"; '
        'process.stdout.write(sourceFile(process.argv[1], "src", '
        '"dev/gaius/browser/BrowserProfile.java").text);', PROFILE_ID,
    ], cwd=root, text=True, encoding="utf-8", capture_output=True, check=True, timeout=60).stdout
    results.append((
        "26.3 BrowserProfile replaces the offline identity with a services ProfileResult",
        "import com.mojang.authlib.services.ProfileResult;" in profile
        and "gaius$replaceIdentity(user, new ProfileResult(" in profile
        and "UnsupportedOperationException" not in profile,
    ))
    client_source = source("port/src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java")
    persistence = source("port/overrides/classlib/src/main/java/dev/gaius/browser/BrowserFilePersistence.java")
    bootstrap = source("port/web/singleplayer/server-worker-bootstrap.js")
    results.append((
        "26.3 storage row (5023, gaius-fs-v2-26.3) is present in all six storage allow-lists",
        storage_rows(client_source) == 3 and storage_rows(server_main) == 1
        and storage_rows(persistence) == 1 and storage_rows(bootstrap) == 1,
    ))
    bringup = source("port/tools/bringup/26.3.txt")
    results.append((
        "26.3 bring-up list has no P7a entries",
        bool(bringup) and not re.search(r"^[^#|]+\|\s*P7a\s*\|", bringup, re.MULTILINE),
    ))
    return results


def _javap_tool() -> str:
    for variable in ("GAIUS_JAVA_HOME", "JAVA_HOME"):
        home = os.environ.get(variable)
        if not home:
            continue
        if os.name == "nt" and re.match(r"^/[A-Za-z](?:/|$)", home):
            home = f"{home[1].upper()}:{home[2:]}"
        for name in ("javap.exe", "javap"):
            candidate = Path(home) / "bin" / name
            if candidate.exists():
                return str(candidate)
    return shutil.which("javap") or "javap"


def _javap(classpath: Path, class_name: str) -> str:
    if not classpath.exists():
        return f"missing classpath: {classpath}"
    try:
        return subprocess.check_output(
            [_javap_tool(), "-J-Duser.language=en", "-classpath", str(classpath), "-c", "-p", class_name],
            text=True, stderr=subprocess.STDOUT, timeout=60)
    except Exception as exc:  # noqa: BLE001
        return f"javap failed: {exc}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    default_root = Path(__file__).resolve().parents[4]
    parser.add_argument("--root", type=Path, default=default_root)
    parser.add_argument("--overlay-dir", type=Path)
    options = parser.parse_args()
    overlay_dir = options.overlay_dir or options.root / "port/work/overlays" / PROFILE_ID
    if not (overlay_dir / f"client-named-{PROFILE_ID}-gaius.jar").exists():
        print(f"missing 26.3 overlay: {overlay_dir}", file=sys.stderr)
        return 2
    results = checks(options.root, overlay_dir, _javap)
    for name, ok in results:
        print(f"{'OK  ' if ok else 'FAIL'} {name}")
    failed = sum(1 for _, ok in results if not ok)
    print(f"profile_263_server: {len(results) - failed}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
