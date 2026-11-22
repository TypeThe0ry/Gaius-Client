#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def emit(phase, step, pct, message, error=None):
    print(json.dumps({
        "schema": "gaius-patcher-progress-v1",
        "phase": phase,
        "step": step,
        "pct": pct,
        "message": message,
        "error": error,
        "futureField": {"ignored": True},
    }), flush=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


args = sys.argv[1:]
scenario = os.environ.get("GAIUS_FAKE_PATCHER_SCENARIO", "success")
if scenario == "tool-paths":
    if args == ["doctor"]:
        sys.exit(0)
    for flag, value in [("--toolchain-dir", "fixture shared tools"),
                        ("--shader-toolchain", "fixture shader modules")]:
        assert args.count(flag) == 1 and args[args.index(flag) + 1] == value, args
    if args[:2] == ["tools", "install"]:
        assert args[args.index("--only") + 1] == "maven-repository", args
    else:
        assert "--offline" in args, args
if args[:2] == ["tools", "status"]:
    print(json.dumps({
        "schema": "gaius-patcher-tools-v1", "id": "maven", "displayName": "Maven",
        "required": True, "requiredReason": "required-default",
        "requiredFor": ["26.3", "26.2", "1.21.11"],
        "state": "missing" if scenario == "missing-tools" else "present", "bytes": 2,
        "sourceHost": "fixture.invalid", "destinationPath": "", "expectedSha256": ""
    }))
    if scenario == "missing-kernel-tools":
        strict = "--strict-kernel" in args
        for tool in ("rust-cargo", "wasm32-target", "cargo-cache"):
            print(json.dumps({
                "schema": "gaius-patcher-tools-v1", "id": tool, "displayName": tool,
                "required": strict,
                "requiredReason": "required-strict-kernel" if strict else "optional",
                "requiredFor": ["26.3", "26.2", "1.21.11"] if strict else [],
                "state": "missing", "bytes": 0, "sourceHost": "",
                "destinationPath": "", "expectedSha256": "",
            }))
    sys.exit(0)
if args[:2] == ["tools", "install"]:
    print(json.dumps({"schema": "gaius-patcher-tools-progress-v1", "id": "maven",
                      "step": "download", "pct": 50, "bytes": 1, "totalBytes": 2, "error": None}), flush=True)
    if scenario == "slow-tools":
        time.sleep(60)
    sys.exit(0)
if args == ["doctor"] and scenario == "encoding":
    sys.stdout.buffer.write("Game dir: C:\\Users\\张三\\工作\n".encode("gbk"))
    sys.stderr.buffer.write("日志 C:\\Users\\张三\\文件\n".encode("utf-8"))
    sys.exit(0)
if args[:1] == ["doctor"] and "--verify-files" in args:
    expected = ["doctor", "26.3", "--minecraft-dir", "fixture launcher"]
    if scenario == "verify-failed":
        expected = ["doctor", "1.21.11", "--minecraft-dir", "fixture launcher",
                    "--mappings", "fixture mappings.txt"]
    assert args == expected + ["--verify-files", "--json"], args

    def verify_event(event, group, checked, total, **details):
        print(json.dumps({"schema": "gaius-patcher-verify-v1", "event": event,
                          "group": group, "checked": checked, "total": total,
                          **details}), flush=True)

    verify_event("progress", "client", 0, 1)
    verify_event("group", "client", 1, 1, failed=[])
    verify_event("group", "libraries", 2, 2, failed=[])
    if scenario == "verify-failed":
        path = "assets/indexes/34.json"
        verify_event("group", "asset-index", 1, 1, failed=[{
            "path": path, "expected": "1" * 40, "actual": "2" * 40,
            "reason": "sha1-mismatch",
        }])
        verify_event("group", "asset-objects", 0, 0, failed=[{
            "path": path, "expected": None, "actual": None,
            "reason": "prerequisite-failed",
        }], failureCount=2)
        sys.exit(1)
    verify_event("group", "asset-index", 1, 1, failed=[])
    verify_event("group", "asset-objects", 5, 5, failed=[])
    if scenario == "verify-wait":
        time.sleep(60)
    sys.exit(0)
version = args[args.index("build") + 1]
out = Path(args[args.index("--out") + 1])
print("non-json stdout noise", flush=True)
print("stderr: build started", file=sys.stderr, flush=True)

emit("future_phase", "future-step", 3, "Future protocol phase")
emit("fetch", "local-files", 8, "Validating local launcher files")

if scenario == "preflight":
    message = "26.3 requires a prebuilt shader toolchain; use --shader-toolchain <dir>"
    emit("error", "failed", 100, message, message)
    print(message, file=sys.stderr, flush=True)
    sys.exit(1)

if scenario == "mid-error":
    emit("compile", "client", 55, "Running client compilation")
    message = "compiler stopped after the progress event"
    emit("error", "failed", 100, message, message)
    print("compiler diagnostic", file=sys.stderr, flush=True)
    sys.exit(7)

if scenario == "slow-tree":
    out.mkdir(parents=True, exist_ok=True)
    child = subprocess.Popen([
        sys.executable,
        "-c",
        "import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)",
    ])
    (out / "child.pid").write_text(str(child.pid), encoding="utf-8")
    print("child process started", file=sys.stderr, flush=True)
    while True:
        time.sleep(0.2)

if scenario == "slow":
    while True:
        print("waiting for cancellation", file=sys.stderr, flush=True)
        time.sleep(0.2)

emit("stage", "source-tree", 18, "Staging the isolated build tree")
emit("compile", "client", 55, "Running client compilation")
out.mkdir(parents=True, exist_ok=True)
(out / "received-args.json").write_text(json.dumps(args), encoding="utf-8")
site = out / "site"
site.mkdir()
html = out / f"Gaius-{version}.html"
index = site / "index.html"
html.write_text("portable artifact", encoding="utf-8")
index.write_text("site artifact", encoding="utf-8")
manifest = {
    "schema": "gaius-patcher-hashes-v1",
    "version": version,
    "html": {"path": html.name, "sha256": "0" * 64 if scenario == "hash-mismatch" else digest(html)},
    "site": [{"path": "index.html", "sha256": digest(index)}],
}
if scenario != "hash-missing":
    (out / "expected-hashes.json").write_text(json.dumps(manifest), encoding="utf-8")
emit("publish", "artifacts", 100, "Published the site, single-file HTML, and hash manifest")
print("stderr: build complete", file=sys.stderr, flush=True)
