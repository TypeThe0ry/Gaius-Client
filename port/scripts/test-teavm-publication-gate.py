#!/usr/bin/env python3
"""Fast regression test for the TeaVM publication gate.

This test exercises only the shell gate with synthetic logs.  It deliberately
does not invoke Maven, TeaVM, overlay generation, or any browser runtime.
"""

from __future__ import annotations

import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "port" / "scripts" / "teavm-publication-gate.sh"
ANALYZER = ROOT / "port" / "scripts" / "analyze-teavm-log.py"
# Git Bash starts several native helpers for each lock operation. On Windows
# those helpers can exceed five seconds under concurrent CI load.
LOCK_TEST_TIMEOUT = 30 if os.name == "nt" else 5


def communicate_and_reap(process: subprocess.Popen[str]) -> tuple[str, str]:
    """Keep failed lock tests from leaving a contender running afterwards."""
    try:
        return process.communicate(timeout=LOCK_TEST_TIMEOUT)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate()
        raise


def simulated_gate(log: Path, analysis_status: int) -> bool:
    """Mirror the two conditions when a POSIX shell is unavailable."""
    if analysis_status != 0:
        return False
    text = log.read_text(encoding="utf-8")
    return "Output file built with errors" in text or "[INFO] BUILD SUCCESS" in text


def run_harness(
    log: Path,
    analysis_status: int,
    artifact: Path,
    sidecar: Path,
    report_json: Path,
    report_markdown: Path,
    staged_artifact: Path | None = None,
    staged_sidecar: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    shell_path = lambda path: shlex.quote(path.as_posix())
    staged = staged_artifact or artifact
    staged_identity = staged_sidecar or sidecar
    script = f"""
set -u
source {shell_path(GATE)}
if ! gaius_teavm_publish_allowed {shell_path(log)} {analysis_status}; then
  exit 1
fi
if gaius_teavm_publish_allowed {shell_path(log)} {analysis_status}; then
  gaius_teavm_remove_stale_incomplete_reports \
    {shell_path(report_json)} \
    {shell_path(report_markdown)}
  gaius_teavm_publish_file {shell_path(staged)} {shell_path(artifact)}
  gaius_teavm_publish_file {shell_path(staged_identity)} {shell_path(sidecar)}
fi
"""
    bash = find_bash()
    return subprocess.run(
        [bash, "-c", script],
        cwd=ROOT,
        env={**os.environ, "PATH": os.environ.get("PATH", "")},
        text=True,
        capture_output=True,
        check=False,
    )


def find_bash() -> str:
    candidates: list[str | None] = []
    if os.name == "nt":
        candidates.extend(
            [
                r"C:\Program Files\Git\bin\bash.exe",
                r"C:\Program Files\Git\usr\bin\bash.exe",
            ]
        )
    candidates.append(shutil.which("bash"))
    for candidate in dict.fromkeys(path for path in candidates if path):
        probe = subprocess.run(
            [
                candidate,
                "-c",
                'test -r "$1"',
                "gaius-bash-probe",
                GATE.as_posix(),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        if probe.returncode == 0:
            return candidate
    raise FileNotFoundError("usable bash runtime")


def run_lock_race(directory: Path) -> None:
    """Exercise missing-pid waiting, owner validation, and serialized acquire."""

    shell_path = lambda path: shlex.quote(path.as_posix())
    bash = find_bash()
    lock = directory / "profile-output.lock"
    holder_marker = directory / "holder.token"
    holder_script = f"""
set -u
source {shell_path(GATE)}
gaius_teavm_lock_acquire {shell_path(lock)}
token="$GAIUS_TEA_LOCK_OWNER_TOKEN"
printf '%s\\n' "$token" > {shell_path(holder_marker)}
sleep 0.7
gaius_teavm_lock_release {shell_path(lock)} "$token"
"""
    holder = subprocess.Popen(
        [bash, "-c", holder_script],
        cwd=ROOT,
        env={**os.environ, "PATH": os.environ.get("PATH", "")},
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        deadline = time.monotonic() + LOCK_TEST_TIMEOUT
        while not holder_marker.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        assert holder_marker.exists(), "lock holder did not publish owner token"
        contender_script = f"""
set -u
source {shell_path(GATE)}
gaius_teavm_lock_acquire {shell_path(lock)}
token="$GAIUS_TEA_LOCK_OWNER_TOKEN"
printf 'contender-acquired\\n'
gaius_teavm_lock_release {shell_path(lock)} "$token"
"""
        contender = subprocess.Popen(
            [bash, "-c", contender_script],
            cwd=ROOT,
            env={**os.environ, "PATH": os.environ.get("PATH", "")},
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        time.sleep(0.2)
        assert contender.poll() is None, "contender bypassed the live profile lock"
        contender_stdout, contender_stderr = communicate_and_reap(contender)
        assert contender.returncode == 0, contender_stderr
        assert "contender-acquired" in contender_stdout
    finally:
        holder_stdout, holder_stderr = communicate_and_reap(holder)
        assert holder.returncode == 0, holder_stderr or holder_stdout

    # A lock directory observed before its pid write is not reclaimable just
    # because `cat lock/pid` is empty. Remove it as an external owner after a
    # short wait, then verify the waiting contender can acquire it.
    lock.mkdir(parents=True, exist_ok=True)
    waiting_script = f"""
set -u
source {shell_path(GATE)}
gaius_teavm_lock_acquire {shell_path(lock)}
token="$GAIUS_TEA_LOCK_OWNER_TOKEN"
printf 'missing-pid-acquired\\n'
gaius_teavm_lock_release {shell_path(lock)} "$token"
"""
    waiting = subprocess.Popen(
        [bash, "-c", waiting_script],
        cwd=ROOT,
        env={**os.environ, "PATH": os.environ.get("PATH", "")},
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    time.sleep(0.25)
    assert waiting.poll() is None, "missing-pid lock was reclaimed during metadata window"
    shutil.rmtree(lock)
    waiting_stdout, waiting_stderr = communicate_and_reap(waiting)
    assert waiting.returncode == 0, waiting_stderr
    assert "missing-pid-acquired" in waiting_stdout

    # Old PID-only locks remain reclaimable after the owner-token upgrade.
    lock.mkdir(parents=True, exist_ok=True)
    (lock / "pid").write_text("999999\n", encoding="utf-8")
    legacy_script = f"""
set -u
source {shell_path(GATE)}
gaius_teavm_lock_acquire {shell_path(lock)}
token="$GAIUS_TEA_LOCK_OWNER_TOKEN"
printf 'legacy-acquired\\n'
gaius_teavm_lock_release {shell_path(lock)} "$token"
"""
    legacy = subprocess.run(
        [bash, "-c", legacy_script],
        cwd=ROOT,
        env={**os.environ, "PATH": os.environ.get("PATH", "")},
        text=True,
        capture_output=True,
        check=False,
        timeout=LOCK_TEST_TIMEOUT,
    )
    assert legacy.returncode == 0, legacy.stderr
    assert "legacy-acquired" in legacy.stdout

    # Release must validate the token instead of deleting another owner's lock.
    owner_script = f"""
set -u
source {shell_path(GATE)}
gaius_teavm_lock_acquire {shell_path(lock)}
token="$GAIUS_TEA_LOCK_OWNER_TOKEN"
if gaius_teavm_lock_release {shell_path(lock)} wrong-token; then exit 1; fi
test -d {shell_path(lock)}
gaius_teavm_lock_release {shell_path(lock)} "$token"
test ! -e {shell_path(lock)}
"""
    owner = subprocess.run(
        [bash, "-c", owner_script],
        cwd=ROOT,
        env={**os.environ, "PATH": os.environ.get("PATH", "")},
        text=True,
        capture_output=True,
        check=False,
    )
    assert owner.returncode == 0, owner.stderr


def run_bundle_rollback(directory: Path) -> None:
    """A mid-commit failure must restore the complete previous file set."""

    shell_path = lambda path: shlex.quote(path.as_posix())
    bash = find_bash()
    staged_artifact = directory / "bundle-stage" / "classes.js"
    staged_sidecar = directory / "bundle-stage" / "classes.js.build.json"
    final_artifact = directory / "bundle-final" / "classes.js"
    final_sidecar = directory / "bundle-final" / "classes.js.build.json"
    staged_artifact.parent.mkdir(parents=True)
    final_artifact.parent.mkdir(parents=True)
    staged_artifact.write_text("new-bundle-artifact", encoding="utf-8")
    staged_sidecar.write_text("new-bundle-sidecar", encoding="utf-8")
    final_artifact.write_text("old-bundle-artifact", encoding="utf-8")
    final_sidecar.write_text("old-bundle-sidecar", encoding="utf-8")

    script = f"""
set -u
source {shell_path(GATE)}
if GAIUS_TEA_PUBLISH_FAIL_AFTER=1 gaius_teavm_publish_bundle \\
    {shell_path(staged_artifact)} {shell_path(final_artifact)} \\
    {shell_path(staged_sidecar)} {shell_path(final_sidecar)}; then
  exit 1
fi
test "$(cat {shell_path(final_artifact)})" = old-bundle-artifact
test "$(cat {shell_path(final_sidecar)})" = old-bundle-sidecar
gaius_teavm_publish_bundle \\
  {shell_path(staged_artifact)} {shell_path(final_artifact)} \\
  {shell_path(staged_sidecar)} {shell_path(final_sidecar)}
test "$(cat {shell_path(final_artifact)})" = new-bundle-artifact
test "$(cat {shell_path(final_sidecar)})" = new-bundle-sidecar
"""
    result = subprocess.run(
        [bash, "-c", script],
        cwd=ROOT,
        env={**os.environ, "PATH": os.environ.get("PATH", "")},
        text=True,
        capture_output=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr or result.stdout


def analyze(
    log: Path, report_json: Path, report_markdown: Path
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(ANALYZER),
            str(log),
            str(report_json),
            str(report_markdown),
        ],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def main() -> int:
    gate_source = GATE.read_text(encoding="utf-8")
    for marker in (
        "gaius_teavm_publish_allowed()",
        "gaius_teavm_remove_stale_incomplete_reports()",
        "gaius_teavm_lock_acquire()",
        "gaius_teavm_lock_release()",
        "gaius_teavm_publish_file()",
        "gaius_teavm_publish_bundle()",
        "Output file built with errors",
        "[INFO] BUILD SUCCESS",
    ):
        assert marker in gate_source, f"publication gate marker missing: {marker}"

    with tempfile.TemporaryDirectory(prefix="gaius-teavm-gate-") as temporary:
        directory = Path(temporary)
        artifact = directory / "classes.js"
        sidecar = directory / "classes.js.identity.json"
        incomplete_json = directory / "teavm-gap.incomplete.json"
        incomplete_md = directory / "teavm-gap.incomplete.md"
        report_json = directory / "teavm-gap.json"
        report_md = directory / "teavm-gap.md"

        artifact.write_text("old-artifact", encoding="utf-8")
        sidecar.write_text("old-identity", encoding="utf-8")
        incomplete_json.write_text("incomplete-json", encoding="utf-8")
        incomplete_md.write_text("incomplete-md", encoding="utf-8")
        report_json.write_text("canonical-json", encoding="utf-8")
        report_md.write_text("canonical-md", encoding="utf-8")
        incomplete_log = directory / "incomplete.log"
        incomplete_log.write_text("[INFO] Running TeaVM\n", encoding="utf-8")
        incomplete_analysis = analyze(incomplete_log, report_json, report_md)
        assert incomplete_analysis.returncode == 3, incomplete_analysis.stderr

        try:
            failed = run_harness(
                incomplete_log,
                incomplete_analysis.returncode,
                artifact,
                sidecar,
                report_json,
                report_md,
            )
        except FileNotFoundError:
            assert not simulated_gate(incomplete_log, 3)
            failed = None
        if failed is not None:
            assert failed.returncode != 0, failed.stderr
        assert artifact.read_text(encoding="utf-8") == "old-artifact"
        assert sidecar.read_text(encoding="utf-8") == "old-identity"
        assert incomplete_json.is_file(), "failure must retain incomplete JSON evidence"
        assert incomplete_md.is_file(), "failure must retain incomplete Markdown evidence"

        complete_log = directory / "complete.log"
        complete_log.write_text(
            "[INFO] Running TeaVM\n[INFO] BUILD SUCCESS\n", encoding="utf-8"
        )
        complete_analysis = analyze(complete_log, report_json, report_md)
        assert complete_analysis.returncode == 0, complete_analysis.stderr
        # Mark the canonical reports after analyzer success; the publication
        # cleanup must remove only the stale incomplete siblings.
        report_json.write_text("canonical-json", encoding="utf-8")
        report_md.write_text("canonical-md", encoding="utf-8")
        no_sentinel_log = directory / "no-sentinel.log"
        no_sentinel_log.write_text("[INFO] Running TeaVM\n", encoding="utf-8")
        # The analyzer normally returns 3 for this log; explicitly pass 0 to
        # catch regressions that trust analysis_status without the completion
        # sentinel.
        try:
            no_sentinel = run_harness(
                no_sentinel_log,
                0,
                artifact,
                sidecar,
                report_json,
                report_md,
            )
        except FileNotFoundError:
            assert not simulated_gate(no_sentinel_log, 0)
            no_sentinel = None
        if no_sentinel is not None:
            assert no_sentinel.returncode != 0, no_sentinel.stderr
        assert artifact.read_text(encoding="utf-8") == "old-artifact"
        assert sidecar.read_text(encoding="utf-8") == "old-identity"

        staged_artifact = directory / "staging" / "classes.js"
        staged_sidecar = directory / "staging" / "classes.js.identity.json"
        staged_artifact.parent.mkdir()
        staged_artifact.write_text("new-artifact", encoding="utf-8")
        staged_sidecar.write_text("new-identity", encoding="utf-8")
        try:
            completed = run_harness(
                complete_log,
                complete_analysis.returncode,
                artifact,
                sidecar,
                report_json,
                report_md,
                staged_artifact,
                staged_sidecar,
            )
        except FileNotFoundError:
            assert simulated_gate(complete_log, 0)
            artifact.write_text("new-artifact", encoding="utf-8")
            sidecar.write_text("new-identity", encoding="utf-8")
            incomplete_json.unlink(missing_ok=True)
            incomplete_md.unlink(missing_ok=True)
            completed = None
        if completed is not None:
            assert completed.returncode == 0, completed.stderr
        assert artifact.read_text(encoding="utf-8") == "new-artifact"
        assert sidecar.read_text(encoding="utf-8") == "new-identity"
        assert report_json.read_text(encoding="utf-8") == "canonical-json"
        assert report_md.read_text(encoding="utf-8") == "canonical-md"
        assert not incomplete_json.exists(), "success must clear stale incomplete JSON"
        assert not incomplete_md.exists(), "success must clear stale incomplete Markdown"

        run_lock_race(directory)
        run_bundle_rollback(directory)

    print("TeaVM publication gate regression passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
