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
