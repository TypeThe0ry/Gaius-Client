#!/usr/bin/env python3
"""Small, non-build fixtures for quick-check's javap resolver."""

from __future__ import annotations

import contextlib
import importlib.util
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock


SCRIPT = Path(__file__).resolve().parent / "legacy" / "quick-check.py"
SPEC = importlib.util.spec_from_file_location("gaius_quick_check_javap", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot import {SCRIPT}")
QUICK_CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUICK_CHECK)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


@contextlib.contextmanager
def environment(**updates: str | None):
    saved = dict(os.environ)
    try:
        for key, value in updates.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        yield
    finally:
        os.environ.clear()
        os.environ.update(saved)


def javap_fixture(home: Path) -> Path:
    """Create a platform-neutral fake javap file for path resolution tests."""
    (home / "bin").mkdir(parents=True, exist_ok=True)
    executable = home / "bin" / ("javap.exe" if os.name == "nt" else "javap")
    executable.write_text("fake javap", encoding="utf-8")
    return executable


def check_java_home_without_path() -> None:
    with TemporaryDirectory(prefix="gaius-javap-") as temporary:
        root = Path(temporary)
        home = root / "jdk"
        executable = javap_fixture(home)
        classpath = root / "classes"
        classpath.mkdir()
        empty_path = root / "empty-path"
        empty_path.mkdir()
        with environment(
            GAIUS_JAVA_HOME=None,
            JAVA_HOME=str(home),
            PATH=str(empty_path),
        ), mock.patch.object(QUICK_CHECK.subprocess, "check_output", return_value="fake bytecode") as run:
            resolved = QUICK_CHECK.resolve_javap()
            output = QUICK_CHECK.run_javap(classpath, "example.Fixture")

        require(resolved == executable, "JAVA_HOME/bin/javap was not preferred over PATH")
        require(output == "fake bytecode", "run_javap did not use the resolved tool")
        require(run.call_args.args[0][0] == str(executable), "run_javap invoked bare javap")


def check_msys_java_home_on_windows() -> None:
    if os.name != "nt":
        return
    with TemporaryDirectory(prefix="gaius-javap-msys-") as temporary:
        home = Path(temporary)
        executable = javap_fixture(home)
        drive = home.drive
        msys_home = "/" + drive.rstrip(":").lower() + home.as_posix()[2:]
        with environment(GAIUS_JAVA_HOME=None, JAVA_HOME=msys_home, PATH=""):
            require(
                QUICK_CHECK.resolve_javap() == executable,
                "MSYS /c/... JAVA_HOME was not normalized on Windows",
            )


def check_missing_tool_error() -> None:
    with TemporaryDirectory(prefix="gaius-javap-missing-") as temporary:
        empty_path = Path(temporary)
        with environment(GAIUS_JAVA_HOME=None, JAVA_HOME=None, PATH=str(empty_path)):
            try:
                QUICK_CHECK.resolve_javap()
            except QUICK_CHECK.JavapPrerequisiteError as exc:
                message = str(exc)
            else:
                raise AssertionError("resolve_javap unexpectedly succeeded without a tool")
        require("javap prerequisite not found" in message, "missing-tool error is unclear")
        require("GAIUS_JAVA_HOME" in message and "JAVA_HOME" in message, "error lacks setup hint")
        require("PATH" in message, "error lacks PATH fallback hint")


def check_path_fallback() -> None:
    with TemporaryDirectory(prefix="gaius-javap-path-") as temporary:
        path_tool = Path(temporary) / "javap"
        path_tool.write_text("fake javap", encoding="utf-8")
        with environment(GAIUS_JAVA_HOME=None, JAVA_HOME=None, PATH=""):
            with mock.patch.object(QUICK_CHECK.shutil, "which", return_value=str(path_tool)) as which:
                require(QUICK_CHECK.resolve_javap() == path_tool, "PATH javap fallback was not used")
                require(which.call_args.args == ("javap",), "PATH fallback did not query javap")


def main() -> None:
    check_java_home_without_path()
    check_msys_java_home_on_windows()
    check_missing_tool_error()
    check_path_fallback()
    print("quick-check javap resolver fixture passed")


if __name__ == "__main__":
    main()
