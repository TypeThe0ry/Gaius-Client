#!/usr/bin/env python3
"""Fixture coverage for quick-check's active overlay profile resolver."""

from __future__ import annotations

import ast
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory

from verification_profiles import CANONICAL_ID, LEGACY_ID, RENDERPEARL_ID
UNKNOWN_PROFILE_ID = f"{max(int(profile.split('.')[0]) for profile in (CANONICAL_ID, LEGACY_ID, RENDERPEARL_ID)) + 1}.1"


SCRIPT = Path(__file__).resolve().parent / "legacy" / "quick-check.py"
SPEC = importlib.util.spec_from_file_location("gaius_quick_check", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot import {SCRIPT}")
QUICK_CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUICK_CHECK)


@contextlib.contextmanager
def hermetic_gaius_environment():
    """Run fixture code without inheriting profile-selection overrides."""
    fixture_env = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith("GAIUS_")
    }
    saved_env = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(fixture_env)
        yield
    finally:
        os.environ.clear()
        os.environ.update(saved_env)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def artifact_path(coordinate: str, version: str, classifier: str | None = None) -> str:
    group, artifact = coordinate.split(":")
    suffix = f"-{version}"
    if classifier is not None:
        suffix += f"-{classifier}"
    return (
        f"{group.replace('.', '/')}/{artifact}/{version}/"
        f"{artifact}{suffix}.jar"
    )


def library_entries(
    versions: dict[str, str],
    distribution: str,
    window_module: str = "lwjgl-glfw",
    lwjgl_classifier: str | None = "unset",
) -> list[dict]:
    if lwjgl_classifier == "unset":
        lwjgl_classifier = "unsafe" if distribution == "named" else None
    libraries = [
        ("org.lwjgl:lwjgl", versions["lwjgl"], lwjgl_classifier),
        (f"org.lwjgl:{window_module}", versions["lwjgl"], None),
        ("org.lwjgl:lwjgl-opengl", versions["lwjgl"], None),
        ("org.lwjgl:lwjgl-openal", versions["lwjgl"], None),
        ("io.netty:netty-transport", versions["netty"], None),
        ("com.mojang:authlib", versions["authlib"], None),
        ("org.joml:joml", versions["joml"], None),
        ("com.mojang:patchy", versions["patchy"], None),
    ]
    return [
        {
            "name": ":".join(
                part
                for part in (coordinate, version, classifier)
                if part is not None
            ),
            "downloads": {
                "artifact": {
                    "path": artifact_path(coordinate, version, classifier),
                },
            },
        }
        for coordinate, version, classifier in libraries
    ]


def write_profile(root: Path, version: str, distribution: str) -> None:
    profile_path = root / "versions" / f"{version}.json"
    profile_path.parent.mkdir(parents=True, exist_ok=True)
    profile_path.write_text(
        json.dumps({"id": version, "clientDistribution": distribution}),
        encoding="utf-8",
    )


def write_version(
    root: Path,
    version: str,
    distribution: str,
    versions: dict[str, str],
    window_module: str = "lwjgl-glfw",
    lwjgl_classifier: str | None = "unset",
) -> None:
    work = root / "work" / version
    work.mkdir(parents=True, exist_ok=True)
    libraries = library_entries(versions, distribution, window_module, lwjgl_classifier)
    (work / "version.json").write_text(
        json.dumps({"id": version, "libraries": libraries}),
        encoding="utf-8",
    )
    (work / "client-version.json").write_text(
        json.dumps({"id": version}),
        encoding="utf-8",
    )

    overlays = root / "work" / "overlays"
    overlays.mkdir(parents=True, exist_ok=True)
    (overlays / f"client-named-{version}-gaius.jar").write_bytes(b"client")
    classpath_entries = []
    for library in libraries:
        path = library["downloads"]["artifact"]["path"]
        classpath_library = work / "libraries" / path
        classpath_library.parent.mkdir(parents=True, exist_ok=True)
        classpath_library.write_bytes(b"library")
        overlay_library = overlays / "libraries" / path
        overlay_library.parent.mkdir(parents=True, exist_ok=True)
        overlay_library.write_bytes(b"overlay")
        classpath_entries.append(str(classpath_library))
    (work / "classpath.txt").write_text(
        os.pathsep.join(classpath_entries),
        encoding="utf-8",
    )


def set_active_profile(root: Path, version: str) -> None:
    (root / "config.json").write_text(
        json.dumps({"versionProfile": f"versions/{version}.json"}),
        encoding="utf-8",
    )


def check_profile_scoped_defaults() -> None:
    keys = ("GAIUS_BUILD_ROOT", "GAIUS_VERSION_PROFILE_PATH")
    saved = {key: os.environ.get(key) for key in keys}
    try:
        for key in keys:
            os.environ.pop(key, None)
        base = QUICK_CHECK.PORT / "target"
        require(
            QUICK_CHECK._profile_scoped_default(base, LEGACY_ID) == base,
            "legacy quick-check target default unexpectedly became profile-scoped",
        )

        os.environ["GAIUS_VERSION_PROFILE_PATH"] = ("versions/" + LEGACY_ID + ".json")
        require(
            QUICK_CHECK._profile_scoped_default(base, LEGACY_ID) == base / LEGACY_ID,
            "GAIUS_VERSION_PROFILE_PATH did not select a profile-scoped target",
        )

        os.environ.pop("GAIUS_VERSION_PROFILE_PATH")
        os.environ["GAIUS_BUILD_ROOT"] = ("port/target/" + LEGACY_ID + "")
        require(
            QUICK_CHECK._profile_scoped_default(base, LEGACY_ID) == base / LEGACY_ID,
            "GAIUS_BUILD_ROOT did not select a profile-scoped target",
        )

        if os.name == "nt":
            msys_path = QUICK_CHECK._configured_path(
                "/c/gaius-profile-target",
                base,
            )
            require(
                msys_path == Path("C:/gaius-profile-target").resolve(),
                "Git-Bash /c/... path was not converted to a native Windows path",
            )
    finally:
        for key, value in saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def check_release_pom_contract() -> None:
    require(
        QUICK_CHECK.CLIENT_TEA_POM.name == "release-generated-pom.xml",
        "quick-check client release validation uses the staging POM",
    )
    require(
        QUICK_CHECK.WORKER_TEA_POM.name == "release-generated-pom.xml"
        and QUICK_CHECK.WORKER_TEA_POM.parent.name == "server-worker",
        "quick-check Worker release validation uses the staging POM",
    )


def check_manifest_top_level_identity() -> None:
    storage = {
        "schema": 2,
        "databaseName": ("gaius-fs-v2-" + CANONICAL_ID + ""),
        "prefix": ("gaius.fs.v2:" + CANONICAL_ID + ":"),
        "opfsDirectory": ("regions-v2-" + CANONICAL_ID + ""),
    }
    expected = {
        "schemaVersion": 2,
        "profile": {
            "id": CANONICAL_ID,
            "path": ("versions/" + CANONICAL_ID + ".json"),
            "sha256": "a" * 64,
            "clientDistribution": "named",
            "protocolVersion": 776,
            "worldVersion": 4903,
            "worldgenTelemetryMode": "task-pulsed",
            "storage": storage,
        },
        "worldVersion": 4903,
        "worldgenTelemetryMode": "task-pulsed",
        "storage": storage,
        "source": {"sha256": "b" * 64},
        "protocol": {"sha256": "c" * 64},
        "overlay": {"sha256": "d" * 64},
        "compatibilitySha256": "e" * 64,
    }
    manifest = {
        "profile": CANONICAL_ID,
        "profilePath": ("versions/" + CANONICAL_ID + ".json"),
        "worldVersion": 4903,
        "worldgenTelemetryMode": "task-pulsed",
        "storage": storage,
        "buildIdentity": copy.deepcopy(expected),
    }
    require(
        QUICK_CHECK.manifest_top_level_identity_matches(manifest, expected),
        "matching portable top-level identity was rejected",
    )

    for field, forged in (
        ("profile", 262),
        ("profilePath", None),
        ("worldVersion", "4903"),
        ("worldgenTelemetryMode", None),
        ("storage", None),
    ):
        candidate = copy.deepcopy(manifest)
        if forged is None and field == "profilePath":
            candidate.pop(field)
        elif forged is None and field in {"worldgenTelemetryMode", "storage"}:
            candidate.pop(field)
        else:
            candidate[field] = forged
        require(
            not QUICK_CHECK.manifest_top_level_identity_matches(candidate, expected),
            f"forged or missing top-level {field} identity was accepted",
        )

    candidate = copy.deepcopy(manifest)
    candidate["buildIdentity"]["profile"].pop("worldVersion")
    require(
        not QUICK_CHECK.manifest_top_level_identity_matches(candidate, expected),
        "missing nested profile worldVersion was accepted",
    )

    candidate = copy.deepcopy(manifest)
    candidate["buildIdentity"]["storage"]["schema"] = True
    require(
        not QUICK_CHECK.manifest_top_level_identity_matches(candidate, expected),
        "boolean storage schema spoof was accepted as integer 2",
    )


def overlay_bytecode_check_names() -> set[str]:
    """Names of the check table in check_overlay_bytecode(), read from the source."""
    tree = ast.parse(SCRIPT.read_text(encoding="utf-8"))
    for function in tree.body:
        if isinstance(function, ast.FunctionDef) and function.name == "check_overlay_bytecode":
            for node in ast.walk(function):
                if (
                    isinstance(node, ast.Assign)
                    and any(isinstance(target, ast.Name) and target.id == "checks" for target in node.targets)
                    and isinstance(node.value, ast.List)
                ):
                    return {
                        element.elts[0].value
                        for element in node.value.elts
                        if isinstance(element, ast.Tuple)
                        and isinstance(element.elts[0], ast.Constant)
                    }
    raise AssertionError("check_overlay_bytecode() has no checks table")


def check_profile_rules() -> None:
    rules = QUICK_CHECK.PROFILE_RULES
    require(set(rules) >= {LEGACY_ID, CANONICAL_ID, RENDERPEARL_ID}, "a supported profile has no quick-check rules")
    require(QUICK_CHECK.is_named_family(CANONICAL_ID), ("" + CANONICAL_ID + " is not in the named family"))
    require(QUICK_CHECK.is_named_family(RENDERPEARL_ID), ("" + RENDERPEARL_ID + " is not in the named family"))
    require(not QUICK_CHECK.is_named_family(LEGACY_ID), ("" + LEGACY_ID + " was judged by the named rules"))
    require(not QUICK_CHECK.is_named_family(UNKNOWN_PROFILE_ID), "an unknown profile was judged by the named rules")
    require(QUICK_CHECK.profile_rules(UNKNOWN_PROFILE_ID) is None, "an unknown profile has rules")
    require(
        rules[RENDERPEARL_ID].window_library == ("lwjgl_sdl", "org.lwjgl:lwjgl-sdl")
        and rules[CANONICAL_ID].window_library == ("lwjgl_glfw", "org.lwjgl:lwjgl-glfw"),
        "window library per profile changed",
    )
    require(
        rules[RENDERPEARL_ID].render_api == "renderpearl" and rules[CANONICAL_ID].render_api == "blaze3d",
        "render API per profile changed",
    )
    require(
        rules[RENDERPEARL_ID].authlib_session[0].startswith("com.mojang.authlib.services.")
        and rules[CANONICAL_ID].authlib_session[0].startswith("com.mojang.authlib.yggdrasil."),
        "authlib session service per profile changed",
    )
    require(not rules[CANONICAL_ID].not_applicable, ("" + CANONICAL_ID + " must be judged by every named-family rule"))
    require(not rules[CANONICAL_ID].domain_modules, ("" + CANONICAL_ID + " unexpectedly dispatches domain modules"))
    require(
        rules[RENDERPEARL_ID].domain_modules == ("render", "input", "terrain", "worldgen", "server", "ui"),
        ("" + RENDERPEARL_ID + " does not dispatch the six wave-1 domain modules"),
    )
    names = overlay_bytecode_check_names()
    for profile_id, profile in rules.items():
        require(profile.family in {"named", "legacy"}, f"{profile_id} has an unknown rule family")
        stale = sorted(set(profile.not_applicable) - names)
        require(not stale, f"{profile_id} not-applicable entries name no overlay check: {stale}")
        require(
            all(isinstance(reason, str) and reason.strip() for reason in profile.not_applicable.values()),
            f"{profile_id} has a not-applicable entry without a reason",
        )
        for domain in profile.domain_modules:
            path = QUICK_CHECK.profile_module_path(profile_id, domain)
            require(path.is_file(), f"{profile_id} domain module {path} is missing")
            require(
                (profile_id, domain) in QUICK_CHECK.PROFILE_MODULE_ADAPTERS,
                f"{profile_id} domain module {domain} has no adapter",
            )
            module = QUICK_CHECK.load_profile_module(profile_id, domain)
            entry = {"render": "run", "worldgen": "run_checks"}.get(domain, "checks")
            require(callable(getattr(module, entry, None)), f"{path} lost its {entry}() entry point")


def check_not_applicable_reporting() -> None:
    names = sorted(QUICK_CHECK.NOT_APPLICABLE_263)
    shared = "A shared named-family rule"
    checks = [(name, index % 2 == 0) for index, name in enumerate(names)] + [(shared, False)]
    saved = list(QUICK_CHECK.FAILURES)
    try:
        QUICK_CHECK.FAILURES.clear()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            QUICK_CHECK.report_profile_checks(RENDERPEARL_ID, checks)
        require(
            QUICK_CHECK.FAILURES == [shared],
            f"26.3 not-applicable reporting failed the wrong checks: {QUICK_CHECK.FAILURES}",
        )
        require(
            all(f"N/A {name} [26.3: " in output.getvalue() for name in names),
            ("a not-applicable " + RENDERPEARL_ID + " check was not reported with its reason"),
        )

        QUICK_CHECK.FAILURES.clear()
        with contextlib.redirect_stdout(io.StringIO()):
            QUICK_CHECK.report_profile_checks(CANONICAL_ID, checks)
        require(
            QUICK_CHECK.FAILURES == [name for name, ok in checks if not ok],
            ("" + CANONICAL_ID + " skipped a named-family rule that is only not applicable to " + RENDERPEARL_ID + ""),
        )

        QUICK_CHECK.FAILURES.clear()
        with contextlib.redirect_stdout(io.StringIO()):
            QUICK_CHECK.report_profile_checks(RENDERPEARL_ID, checks[1:])
        require(
            QUICK_CHECK.FAILURES
            == [shared, f"26.3 not-applicable entry names an existing check: {names[0]}"],
            f"a stale 26.3 not-applicable entry was not reported: {QUICK_CHECK.FAILURES}",
        )
    finally:
        QUICK_CHECK.FAILURES[:] = saved


def check_unknown_profile_fails_closed(root: Path) -> None:
    write_profile(root, UNKNOWN_PROFILE_ID, "named")
    write_version(root, UNKNOWN_PROFILE_ID, "named", {
        "lwjgl": "3.4.9",
        "netty": "4.2.20.Final",
        "authlib": "11.0.1",
        "joml": "1.10.9",
        "patchy": "2.2.10",
    }, window_module="lwjgl-sdl", lwjgl_classifier=None)
    set_active_profile(root, UNKNOWN_PROFILE_ID)
    try:
        QUICK_CHECK.resolve_overlay_paths(root)
    except QUICK_CHECK.OverlayResolutionError as exc:
        require("no quick-check rules" in str(exc), f"unexpected resolver error: {exc}")
    else:
        raise AssertionError("a profile without quick-check rules resolved overlay paths")


def check_263_resolution(root: Path) -> None:
    versions_263 = {
        "lwjgl": "3.4.3",
        "netty": "4.2.16.Final",
        "authlib": "10.0.77",
        "joml": "1.10.9",
        "patchy": "2.2.10",
    }
    write_profile(root, RENDERPEARL_ID, "named")
    # 26.3 ships the plain lwjgl core jar and lwjgl-sdl; it has no lwjgl-glfw.
    write_version(root, RENDERPEARL_ID, "named", versions_263, window_module="lwjgl-sdl", lwjgl_classifier=None)
    set_active_profile(root, RENDERPEARL_ID)
    resolved = QUICK_CHECK.resolve_overlay_paths(root)
    libraries = resolved["libraries"]
    require(resolved["version"] == RENDERPEARL_ID, ("active " + RENDERPEARL_ID + " profile was not selected"))
    require("lwjgl_glfw" not in libraries, ("" + RENDERPEARL_ID + " resolution still requires lwjgl-glfw"))
    require(
        libraries["lwjgl_sdl"]
        == root / "work" / "overlays" / "libraries" / "org/lwjgl/lwjgl-sdl/3.4.3/lwjgl-sdl-3.4.3.jar",
        ("" + RENDERPEARL_ID + " lwjgl-sdl overlay path is not profile-derived"),
    )
    require(
        libraries["lwjgl"].name == "lwjgl-3.4.3.jar"
        and "10.0.77" in str(libraries["authlib"]),
        ("" + RENDERPEARL_ID + " library overlay versions were not resolved"),
    )
    require(
        list(resolved["expected_paths"])[:3] == ["client", "lwjgl", "lwjgl_sdl"],
        ("" + RENDERPEARL_ID + " expected overlay order changed"),
    )
    require(not QUICK_CHECK.missing_overlay_paths(resolved), ("complete " + RENDERPEARL_ID + " fixture is missing artifacts"))

    # A 26.3 metadata file that still lists lwjgl-glfw (a 26.2 copy) must not
    # satisfy the SDL window module.
    write_version(root, RENDERPEARL_ID, "named", versions_263, window_module="lwjgl-glfw", lwjgl_classifier=None)
    try:
        QUICK_CHECK.resolve_overlay_paths(root)
    except QUICK_CHECK.OverlayResolutionError as exc:
        require("org.lwjgl:lwjgl-sdl" in str(exc), f"unexpected resolver error: {exc}")
    else:
        raise AssertionError(("" + RENDERPEARL_ID + " resolved without an lwjgl-sdl library"))


@hermetic_gaius_environment()
def main() -> None:
    check_profile_scoped_defaults()
    check_release_pom_contract()
    check_manifest_top_level_identity()
    check_profile_rules()
    check_not_applicable_reporting()
    current_versions = {
        "lwjgl": "3.4.1",
        "netty": "4.2.15.Final",
        "authlib": "9.0.75",
        "joml": "1.10.8",
        "patchy": "2.2.10",
    }
    legacy_versions = {
        "lwjgl": "3.3.3",
        "netty": "4.2.7.Final",
        "authlib": "7.0.61",
        "joml": "1.10.8",
        "patchy": "2.2.10",
    }

    with TemporaryDirectory(prefix="gaius-quick-check-") as temporary:
        root = Path(temporary)
        write_profile(root, CANONICAL_ID, "named")
        write_profile(root, LEGACY_ID, "obfuscated-with-mappings")
        write_version(root, CANONICAL_ID, "named", current_versions)
        write_version(root, LEGACY_ID, "obfuscated-with-mappings", legacy_versions)

        set_active_profile(root, CANONICAL_ID)
        resolved = QUICK_CHECK.resolve_overlay_paths(root)
        require(resolved["version"] == CANONICAL_ID, ("active " + CANONICAL_ID + " profile was not selected"))
        require(resolved["client_distribution"] == "named", ("" + CANONICAL_ID + " distribution was not selected"))
        require(
            resolved["client"]
            == root / "work" / "overlays" / ("client-named-" + CANONICAL_ID + "-gaius.jar"),
            ("" + CANONICAL_ID + " client overlay path is not profile-derived"),
        )
        require(
            "3.4.1" in str(resolved["libraries"]["lwjgl"])
            and "4.2.15.Final" in str(resolved["libraries"]["netty_transport"])
            and "9.0.75" in str(resolved["libraries"]["authlib"]),
            ("" + CANONICAL_ID + " library overlay versions were not resolved"),
        )
        require(not QUICK_CHECK.missing_overlay_paths(resolved), ("complete " + CANONICAL_ID + " fixture is missing artifacts"))

        set_active_profile(root, LEGACY_ID)
        resolved = QUICK_CHECK.resolve_overlay_paths(root)
        require(resolved["version"] == LEGACY_ID, ("profile switch to " + LEGACY_ID + " was not selected"))
        require(
            resolved["client"]
            == root / "work" / "overlays" / ("client-named-" + LEGACY_ID + "-gaius.jar"),
            "legacy client overlay path is not profile-derived",
        )
        require(
            "3.3.3" in str(resolved["libraries"]["lwjgl"])
            and "4.2.7.Final" in str(resolved["libraries"]["netty_transport"])
            and "7.0.61" in str(resolved["libraries"]["authlib"]),
            ("" + LEGACY_ID + " library overlay versions were not resolved"),
        )

        set_active_profile(root, CANONICAL_ID)
        current_client = root / "work" / "overlays" / ("client-named-" + CANONICAL_ID + "-gaius.jar")
        current_client.unlink()
        (root / "work" / "overlays" / ("client-named-" + LEGACY_ID + "-gaius.jar")).write_bytes(
            b"old overlay must not satisfy current profile"
        )
        resolved = QUICK_CHECK.resolve_overlay_paths(root)
        missing = QUICK_CHECK.missing_overlay_paths(resolved)
        require(
            ("client", current_client) in missing,
            "missing current client overlay was not reported at its exact path",
        )
        require(resolved["client"] == current_client, "resolver fell back to the legacy client overlay")

        changed_versions = {
            "lwjgl": "3.4.2",
            "netty": "4.2.16.Final",
            "authlib": "9.0.76",
            "joml": "1.10.9",
            "patchy": "2.2.11",
        }
        write_version(root, CANONICAL_ID, "named", changed_versions)
        resolved = QUICK_CHECK.resolve_overlay_paths(root)
        require(
            all(
                version in str(resolved["libraries"][key])
                for key, version in (
                    ("lwjgl", "3.4.2"),
                    ("netty_transport", "4.2.16.Final"),
                    ("authlib", "9.0.76"),
                )
            ),
            "changed library metadata did not move overlay paths",
        )
        require(not QUICK_CHECK.missing_overlay_paths(resolved), "changed library fixture is incomplete")

        check_263_resolution(root)
        check_unknown_profile_fails_closed(root)

    print("quick-check profile resolver fixture passed")


if __name__ == "__main__":
    main()
