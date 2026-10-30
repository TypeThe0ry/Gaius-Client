#!/usr/bin/env python3
"""Write and verify a TeaVM artifact profile from the POM that built it."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


KIND = "gaius-teavm-compiler-profile"
SCHEMA_VERSION = 2
COMPILER_FIELDS = (
    "mainClass",
    "targetDirectory",
    "targetFileName",
    "optimizationLevel",
    "sourceMapsGenerated",
    "debugInformationGenerated",
    "minifying",
    "shortFileNames",
    "assertionsRemoved",
)
BOOLEAN_FIELDS = {
    "sourceMapsGenerated",
    "debugInformationGenerated",
    "minifying",
    "shortFileNames",
    "assertionsRemoved",
}
EXPECTED_MAIN_CLASSES = {
    "client": "net.minecraft.client.main.Main",
    "singleplayer-worker": "dev.gaius.browser.BrowserIntegratedServerMain",
}


def canonical_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child_text(element: ET.Element, name: str) -> str | None:
    for child in element:
        if local_name(child.tag) == name:
            text = child.text.strip() if child.text else ""
            return text or None
    return None


def direct_children(element: ET.Element, name: str) -> list[ET.Element]:
    return [child for child in element if local_name(child.tag) == name]


def parse_bool(name: str, value: str | None) -> bool:
    if value == "true":
        return True
    if value == "false":
        return False
    raise RuntimeError(f"TeaVM POM field {name} must be true or false")


def read_teavm_configuration(pom: Path) -> dict[str, object]:
    try:
        document = ET.parse(pom)
    except (OSError, ET.ParseError) as exc:
        raise RuntimeError(f"could not read TeaVM POM: {pom}") from exc
    candidates: list[tuple[ET.Element, ET.Element, list[str]]] = []
    for plugin in document.getroot().iter():
        if local_name(plugin.tag) != "plugin":
            continue
        if child_text(plugin, "groupId") != "org.teavm":
            continue
        if child_text(plugin, "artifactId") != "teavm-maven-plugin":
            continue
        executions = direct_children(plugin, "executions")
        for execution_container in executions:
            for execution in direct_children(execution_container, "execution"):
                phase = child_text(execution, "phase")
                goals: list[str] = []
                for goals_container in direct_children(execution, "goals"):
                    goals.extend(
                        goal.text.strip()
                        for goal in direct_children(goals_container, "goal")
                        if goal.text and goal.text.strip()
                    )
                configurations = direct_children(execution, "configuration")
                if phase == "package" and "compile" in goals and len(configurations) == 1:
                    candidates.append((execution, configurations[0], goals))
    if len(candidates) != 1:
        raise RuntimeError(
            f"TeaVM POM must contain exactly one package-bound compile execution: {pom}"
        )
    execution, configuration, goals = candidates[0]

    values: dict[str, object] = {}
    for field in COMPILER_FIELDS:
        value = child_text(configuration, field)
        if value is None:
            raise RuntimeError(f"TeaVM POM is missing {field}: {pom}")
        values[field] = parse_bool(field, value) if field in BOOLEAN_FIELDS else value
    values["execution"] = {
        "id": child_text(execution, "id"),
        "phase": child_text(execution, "phase"),
        "goals": goals,
    }
    return values


def relative_name(root: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def file_record(
    root: Path,
    path: Path,
    content_path: Path | None = None,
) -> dict[str, object]:
    """Record a logical path while optionally hashing staged file contents."""
    path = path.resolve()
    content_path = (content_path or path).resolve()
    if not content_path.is_file() or content_path.stat().st_size == 0:
        raise RuntimeError(f"profile input is missing or empty: {content_path}")
    return {
        "path": relative_name(root, path),
        "sha256": sha256_file(content_path),
        "bytes": content_path.stat().st_size,
    }


def validate_release_configuration(configuration: dict[str, object]) -> None:
    if configuration.get("optimizationLevel") not in {"ADVANCED", "FULL"}:
        raise RuntimeError("release TeaVM output requires ADVANCED or FULL optimization")
    expected = {
        "sourceMapsGenerated": False,
        "debugInformationGenerated": False,
        "minifying": True,
        "shortFileNames": True,
        "assertionsRemoved": True,
    }
    for field, value in expected.items():
        if configuration.get(field) is not value:
            raise RuntimeError(f"release TeaVM output requires {field}={str(value).lower()}")


def validate_artifact_target(
    role: str,
    artifact: Path,
    pom: Path,
    configuration: dict[str, object],
) -> None:
    expected_main_class = EXPECTED_MAIN_CLASSES.get(role)
    if expected_main_class is not None and configuration.get("mainClass") != expected_main_class:
        raise RuntimeError(
            f"TeaVM profile role {role} requires mainClass={expected_main_class}"
        )

    target_file = configuration.get("targetFileName")
    if target_file != artifact.name:
        raise RuntimeError(
            f"TeaVM POM targetFileName {target_file!r} does not match artifact {artifact.name!r}"
        )

    target_directory_value = configuration.get("targetDirectory")
    if not isinstance(target_directory_value, str) or not target_directory_value:
        raise RuntimeError("TeaVM POM targetDirectory must be a non-empty path")
    target_directory = Path(target_directory_value).expanduser()
    if not target_directory.is_absolute():
        target_directory = pom.resolve().parent / target_directory
    if target_directory.resolve() != artifact.resolve().parent:
        raise RuntimeError(
            f"TeaVM POM targetDirectory {target_directory_value!r} does not match "
            f"artifact directory {artifact.resolve().parent}"
        )


def create_record(
    root: Path,
    role: str,
    artifact: Path,
    pom: Path,
    resources: list[Path],
    require_release: bool,
    artifact_input: Path | None = None,
) -> dict[str, object]:
    compiler = read_teavm_configuration(pom)
    validate_artifact_target(role, artifact, pom, compiler)
    if require_release:
        validate_release_configuration(compiler)
    record: dict[str, object] = {
        "kind": KIND,
        "schemaVersion": SCHEMA_VERSION,
        "role": role,
        "releaseGrade": require_release,
        "artifact": file_record(root, artifact, artifact_input),
        "pom": file_record(root, pom),
        "compiler": compiler,
        "resources": [file_record(root, path) for path in resources],
    }
    record["profileSha256"] = hashlib.sha256(
        canonical_json(record).encode("ascii")
    ).hexdigest()
    return record


def default_output(artifact: Path) -> Path:
    return artifact.with_name(f"{artifact.name}.release.json")


def write_atomically(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
            temporary.write(text)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, path)
        temporary_name = None
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("write", "verify"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--role", required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument(
        "--artifact-input",
        type=Path,
        help="hash staged bytes while recording --artifact as the published path",
    )
    parser.add_argument("--pom", type=Path, required=True)
    parser.add_argument("--resource", action="append", type=Path, default=[])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-release", action="store_true")
    args = parser.parse_args()

    expected = create_record(
        args.root,
        args.role,
        args.artifact,
        args.pom,
        args.resource,
        args.require_release,
        args.artifact_input,
    )
    output = (args.output or default_output(args.artifact)).resolve()
    if args.command == "write":
        write_atomically(output, canonical_json(expected) + "\n")
        print(f"TeaVM compiler profile: {output}")
        return 0
    if args.artifact_input is not None:
        raise RuntimeError("--artifact-input is valid only while writing a staged profile")
    try:
        actual = json.loads(output.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"TeaVM compiler profile is missing or invalid: {output}") from exc
    if actual != expected:
        raise RuntimeError(f"TeaVM compiler profile does not match current inputs: {output}")
    print(expected["profileSha256"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
