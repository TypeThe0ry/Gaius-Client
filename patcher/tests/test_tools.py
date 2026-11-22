import json
import hashlib
import http.server
import io
import os
import shlex
import shutil
import socketserver
import subprocess
import tarfile
import sys
import tempfile
import threading
import unittest
from pathlib import Path


PATCHER = Path(__file__).resolve().parents[1] / "GaiusPatcher.java"
REPOSITORY = PATCHER.parents[1]
KERNEL_TOOLS = {"rust-cargo", "wasm32-target", "cargo-cache"}
PROFILES = ["26.3", "26.2", "1.21.11"]


class ToolsCommandTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="gaius-tools-")
        self.work = Path(self.temp.name) / "work"
        self.profiles = REPOSITORY / "port" / "versions"
        self.requests = 0
        self.request_ranges = []
        self.request_paths = []
        self.payloads = {}
        self.payload = b"fixture maven bytes\n"

        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                owner.requests += 1
                owner.request_paths.append(self.path)
                payload = owner.payloads.get(self.path, owner.payload)
                requested_range = self.headers.get("Range")
                owner.request_ranges.append(requested_range)
                if requested_range:
                    offset = int(requested_range.partition("=")[2].partition("-")[0])
                    body = payload[offset:]
                    self.send_response(206)
                    self.send_header("Content-Range", f"bytes {offset}-{len(payload) - 1}/{len(payload)}")
                else:
                    body = payload
                    self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_args):
                pass

        self.server = socketserver.TCPServer(("127.0.0.1", 0), Handler)
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.server_thread.join(timeout=2)
        self.temp.cleanup()

    def run_tools(self, action, *args, overrides=None):
        env = os.environ.copy()
        env["TMPDIR"] = os.environ.get("TMPDIR", self.temp.name)
        env.update(overrides or {})
        return subprocess.run(
            ["java", str(PATCHER), "tools", action, *args,
             "--profiles", str(self.profiles),
             "--work-root", str(self.work)],
            capture_output=True, text=True, stdin=subprocess.DEVNULL, env=env,
        )

    def test_status_json_reports_locked_and_unpinned_prerequisites(self):
        result = self.run_tools("status", "--json", overrides={"GAIUS_KERNELS_STRICT": "1", "GAIUS_SKIP_KERNELS": "0"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stderr, "")
        rows = [json.loads(line) for line in result.stdout.splitlines()]
        self.assertGreaterEqual(len(rows), 14)
        for row in rows:
            self.assertEqual(row["schema"], "gaius-patcher-tools-v1")
            for key in ("id", "displayName", "requiredFor", "required", "requiredReason", "state", "bytes", "sourceHost",
                        "destinationPath", "expectedSha256"):
                self.assertIn(key, row)
        repos = [row for row in rows if row["id"] == "maven-repository"]
        self.assertEqual([row["requiredFor"] for row in repos], [[profile] for profile in PROFILES])
        expected_maven_files = len(
            json.loads((PATCHER.parent / "toolchain.lock").read_text(encoding="utf-8"))["mavenRepository"]["artifacts"]
        )
        self.assertEqual(expected_maven_files, 367)
        for row in repos:
            self.assertEqual((row["state"], row["checkedFiles"], row["totalFiles"]), ("missing", 0, expected_maven_files))
        sdk = next(row for row in rows if row["id"] == "emscripten-sdk")
        self.assertEqual(sdk["state"], "informational")
        self.assertEqual(sdk["sdkPlatforms"], ["linux-x64", "windows-x64"])
        self.assertFalse(sdk["required"] or sdk["installable"])
        by_id = {row["id"]: row for row in rows}
        for tool in KERNEL_TOOLS:
            row = by_id[tool]
            self.assertIs(row["required"], False)
            self.assertEqual(row["requiredReason"], "optional")
            self.assertEqual(row["requiredFor"], [])
            self.assertFalse(row["installable"])
        self.assertNotIn("python3-command", by_id)
        python = by_id["python"]
        self.assertIs(python["required"], True)
        self.assertEqual(python["requiredReason"], "required-default")
        self.assertEqual(python["requiredFor"], PROFILES)
        self.assertIsInstance(python["resolvedCommand"], str)
        self.assertEqual(bool(python["resolvedCommand"]), python["state"] == "present")
        self.assertFalse(self.work.exists())

    def test_status_strict_kernel_requires_all_three_rust_rows_despite_inherited_skip(self):
        result = self.run_tools("status", "--json", "--strict-kernel",
                                overrides={"GAIUS_KERNELS_STRICT": "0", "GAIUS_SKIP_KERNELS": "1"})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        rows = {row["id"]: row for row in map(json.loads, result.stdout.splitlines())}
        for tool in KERNEL_TOOLS:
            row = rows[tool]
            self.assertIs(row["required"], True)
            self.assertEqual(row["requiredReason"], "required-strict-kernel")
            self.assertEqual(row["requiredFor"], PROFILES)
        self.assertFalse(self.work.exists())

    @unittest.skipUnless(os.name == "posix" and shutil.which("bash"), "requires a POSIX Bash fixture")
    def test_kernel_probes_enforce_offline_environment_and_check_target_separately(self):
        lock = json.loads((PATCHER.parent / "toolchain.lock").read_text(encoding="utf-8"))
        pins = lock["wasm"]["kernel"]
        directory = Path(self.temp.name)
        offline = ('[[ "$RUSTUP_AUTO_INSTALL" == 0 && "$CARGO_NET_OFFLINE" == true '
                   '&& "$GIT_NO_LAZY_FETCH" == 1 && "$GIT_TERMINAL_PROMPT" == 0 ]] || exit 1')
        for name, script in {
            "rustc": f"echo 'rustc {pins['rustc']} (fixture)'",
            "rustup": "echo 'x86_64-unknown-linux-gnu'",
            "cargo": (f'if [[ "$1" == --version ]]; then echo \'cargo {pins["cargo"]} (fixture)\'; '
                      'else [[ "$*" == "metadata --offline --locked --filter-platform wasm32-unknown-unknown --format-version 1" ]]; fi'),
        }.items():
            tool = directory / name
            tool.write_text(f"#!{shutil.which('bash')}\n{offline}\n{script}\n", encoding="utf-8")
            tool.chmod(0o755)
        result = self.run_tools("status", "--json", "--strict-kernel", overrides={
            "PATH": str(directory) + os.pathsep + os.environ["PATH"],
            "RUSTUP_AUTO_INSTALL": "1", "CARGO_NET_OFFLINE": "false",
            "GIT_NO_LAZY_FETCH": "0", "GIT_TERMINAL_PROMPT": "1",
        })
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        rows = {row["id"]: row for row in map(json.loads, result.stdout.splitlines())}
        self.assertEqual(rows["rust-cargo"]["state"], "present")
        self.assertEqual(rows["wasm32-target"]["state"], "missing")
        self.assertEqual(rows["cargo-cache"]["state"], "present")

    def test_install_unknown_id_fails_as_json_without_network(self):
        result = self.run_tools("install", "--only", "unknown", "--json-progress")
        self.assertNotEqual(result.returncode, 0)
        row = json.loads(result.stdout.splitlines()[0])
        self.assertEqual(row["schema"], "gaius-patcher-tools-progress-v1")
        self.assertEqual(row["step"], "error")
        self.assertIn("unknown or non-installable", row["error"])

    def configure_fixture_lock(self, digest=None):
        source = Path(self.temp.name) / "fixture-source"
        self.profiles = source / "port" / "versions"
        self.profiles.mkdir(parents=True, exist_ok=True)
        patcher = source / "patcher"
        patcher.mkdir(exist_ok=True)
        url = f"http://127.0.0.1:{self.server.server_address[1]}/artifact"
        expected = digest or hashlib.sha256(self.payload).hexdigest()
        tool = {"version": "test", "file": "fixture-maven.bin", "url": url,
                "bytes": len(self.payload), "sha256": expected}
        shader = {"emscripten": "test", "manifest": "manifest.json", "manifestSha256": "0" * 64,
                  "pinsSha256": "0" * 64, "files": {}}
        lock = {"schema": 1,
                "jdk": {"version": "25-test", "feature": 25, "distributions": {}},
                "maven": {**tool, "version": "test"},
                "vineflower": {**tool, "file": "fixture-vineflower.jar", "version": "test"},
                "remapper": {**tool, "file": "fixture-remapper.jar", "version": "test"},
                "teavm": {"version": "test", "repository": "https://repo1.maven.org/maven2/", "artifacts": []},
                "wasm": {"kernel": {"rustc": "test", "cargo": "test", "cargoLockSha256": "0" * 64,
                                      "kernelMirrorSha256": "0" * 64}, "shader": shader}}
        (patcher / "toolchain.lock").write_text(json.dumps(lock), encoding="utf-8")
        return source

    def run_fixture_tools(self, action, *args):
        environment = os.environ.copy()
        environment.pop("GAIUS_SHADER_TOOLCHAIN_PREBUILT", None)
        return subprocess.run(
            ["java", str(PATCHER), "tools", action, *args, "--profiles", str(self.profiles),
             "--work-root", str(self.work)],
            capture_output=True, text=True, stdin=subprocess.DEVNULL, env=environment,
        )

    def write_fixture_lock(self, configure):
        path = self.configure_fixture_lock() / "patcher/toolchain.lock"
        lock = json.loads(path.read_text(encoding="utf-8"))
        configure(lock)
        path.write_text(json.dumps(lock), encoding="utf-8")

    def fixture_status(self, *args):
        result = self.run_fixture_tools("status", "--json", "--offline", *args)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return [json.loads(line) for line in result.stdout.splitlines()]

    def configure_host_archives(self, *, unsafe=False):
        pins = {}
        for name, version, member, compression in (
            ("llvm", "21.1.8", "clang", "xz"), ("binaryen", "133", "wasm-opt", "gz"),
        ):
            file = f"fixture-{name}.tar.{compression}"
            contents = b"synthetic executable\n"
            buffer = io.BytesIO()
            with tarfile.open(fileobj=buffer, mode=f"w:{compression}") as archive:
                entry = tarfile.TarInfo(f"{name}/bin/{member}")
                entry.mode = 0o755
                if unsafe and name == "binaryen":
                    entry.type = tarfile.SYMTYPE
                    entry.linkname = str(Path(self.temp.name) / "outside")
                    archive.addfile(entry)
                else:
                    entry.size = len(contents)
                    archive.addfile(entry, io.BytesIO(contents))
                unused = tarfile.TarInfo(f"{name}/bin/unused")
                archive.addfile(unused)
            payload = buffer.getvalue()
            self.payloads[f"/{file}"] = payload
            pin = {"file": file, "url": f"http://127.0.0.1:{self.server.server_address[1]}/{file}",
                   "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest(),
                   "root": name, "members": [f"bin/{member}"]}
            pins[name] = {"version": version, "distributions": {"linux-x64": pin}}
        self.write_fixture_lock(lambda lock: lock["wasm"].update(hostTools={**pins, "linuxRuntime": []}))
        return {name: tool["distributions"]["linux-x64"] for name, tool in pins.items()}

    @unittest.skipUnless(sys.platform.startswith("linux") and os.uname().machine in ("x86_64", "amd64"),
                         "requires a Linux x86-64 synthetic toolchain")
    def test_wasm_host_tools_status_resume_repair_and_atomic_unpack(self):
        pins = self.configure_host_archives()
        rows = {row["id"]: row for row in self.fixture_status()}
        for name, pin in pins.items():
            row = rows[name]
            self.assertEqual(row["state"], "missing")
            self.assertEqual(row["platform"], "linux-x64")
            self.assertEqual(row["requiredReason"], "required-default")
            self.assertEqual(row["requiredFor"], PROFILES)
            self.assertEqual(row["archiveBytes"], pin["bytes"])
            self.assertEqual(row["sourceHost"], "127.0.0.1")
            self.assertTrue(row["installable"])
        self.assertFalse(self.work.exists())
        self.assertEqual(self.requests, 0)

        tools = self.work / "tools"
        tools.mkdir(parents=True)
        llvm_file = pins["llvm"]["file"]
        binaryen_file = pins["binaryen"]["file"]
        (tools / llvm_file).write_bytes(b"corrupt")
        partial = tools / f"{binaryen_file}.part"
        partial.write_bytes(self.payloads[f"/{binaryen_file}"][:13])
        rows = {row["id"]: row for row in self.fixture_status()}
        self.assertEqual(rows["llvm"]["state"], "hash-mismatch")
        install = self.run_fixture_tools("install", "--only", "llvm,binaryen", "--json-progress")
        self.assertEqual(install.returncode, 0, install.stdout + install.stderr)
        events = list(map(json.loads, install.stdout.splitlines()))
        self.assertEqual({event["id"] for event in events if event["step"] == "installed"}, {"llvm", "binaryen"})
        self.assertEqual(self.request_paths, [f"/{llvm_file}", f"/{binaryen_file}"])
        self.assertEqual(self.request_ranges, [None, "bytes=13-"])
        self.assertFalse(partial.exists())
        rows = {row["id"]: row for row in self.fixture_status()}
        for name, pin in pins.items():
            row = rows[name]
            self.assertEqual(row["state"], "present")
            bindir = Path(row["installPath"]) / name / "bin"
            self.assertEqual([f.name for f in bindir.iterdir()], [Path(pin["members"][0]).name])
            binary = bindir / Path(pin["members"][0]).name
            self.assertTrue(os.access(binary, os.X_OK))
            self.assertEqual(binary.read_bytes(), b"synthetic executable\n")
        self.assertEqual(list((tools / "host-wasm").glob(".unpack-*")), [])
        self.assertEqual(self.requests, 2)

    @unittest.skipUnless(sys.platform.startswith("linux") and os.uname().machine in ("x86_64", "amd64"),
                         "requires a Linux x86-64 synthetic toolchain")
    def test_wasm_host_tools_reject_wrong_digest_and_escaping_archive_member(self):
        pins = self.configure_host_archives(unsafe=True)
        url_path = f"/{pins['binaryen']['file']}"
        correct_payload = self.payloads[url_path]
        self.payloads[url_path] = bytes([correct_payload[0] ^ 1]) + correct_payload[1:]
        args = ("install", "--only", "binaryen", "--json-progress")
        install = self.run_fixture_tools(*args)
        self.assertNotEqual(install.returncode, 0)
        self.assertIn("SHA-256", install.stdout)
        self.assertFalse((self.work / "tools" / pins["binaryen"]["file"]).exists())

        outside = Path(self.temp.name) / "outside"
        outside.write_text("outside", encoding="utf-8")
        outside.chmod(0o755)
        self.payloads[url_path] = correct_payload
        install = self.run_fixture_tools(*args)
        self.assertNotEqual(install.returncode, 0)
        self.assertIn("unsafe extracted tool path", install.stdout)
        self.assertEqual(outside.read_text(encoding="utf-8"), "outside")
        self.assertEqual(list((self.work / "tools/host-wasm").iterdir()), [])

    def test_shader_status_checks_six_offline_files_and_reports_all_differences(self):
        names = ["gaius-shader-toolchain.json", "gaius-shader-toolchain.js", "gaius-shaderc.js",
                 "gaius-shaderc.wasm", "gaius-spvc.js", "gaius-spvc.wasm"]
        contents = {name: f"synthetic {name}".encode() for name in names}
        digests = {name: hashlib.sha256(data).hexdigest() for name, data in contents.items()}

        def configure(lock):
            lock["wasm"]["shader"].update(manifest=names[0], manifestSha256=digests[names[0]],
                                             files={name: digests[name] for name in names[1:]})

        self.write_fixture_lock(configure)
        cache = Path(self.temp.name) / "toolchain cache"
        explicit = Path(self.temp.name) / "separate shader folder"

        def shader(*args):
            rows = self.fixture_status(*args)
            result = next(row for row in rows if row["id"] == "shader-toolchain")
            self.assertEqual(result["requiredFor"], ["26.3"])
            self.assertEqual(result["expectedFiles"], names)
            self.assertEqual(result["sourceUrl"], "")
            self.assertEqual(result["sourceHost"], "")
            self.assertFalse(result["installable"])
            return result

        missing = shader()
        self.assertEqual(missing["state"], "missing-offline")
        self.assertIn("The Gaius release process prepares this package", missing["hint"])
        self.assertEqual(missing["destinationPath"], str(self.work / "tools/shader-toolchain"))
        self.assertFalse(self.work.exists(), "status must not create work files")
        args = ("--toolchain-dir", str(cache), "--shader-toolchain", str(explicit))
        external = shader(*args[:2])
        self.assertEqual(external["destinationPath"], str(cache / "shader-toolchain"))
        self.assertEqual(shader(*args)["destinationPath"], str(explicit))
        explicit.mkdir()
        for name, data in contents.items():
            (explicit / name).write_bytes(data)
        self.assertEqual(shader(*args)["state"], "verified")
        (explicit / names[3]).unlink()
        self.assertEqual(shader(*args)["state"], "missing-offline")
        changed = [names[0], names[4]]
        for name in changed:
            (explicit / name).write_bytes(b"wrong bytes")
        tampered = shader(*args)
        self.assertEqual(tampered["state"], "tampered")
        self.assertEqual(tampered["differingFiles"], changed)
        self.assertEqual([(explicit / name).read_bytes() for name in changed], [b"wrong bytes"] * 2)
        self.assertEqual(self.requests, 0)

    def test_maven_closure_aggregates_status_and_resumes_verified_install(self):
        paths = ["test/fixture/1/fixture-1.pom", "test/fixture/1/fixture-1.jar", "test/plugin/1/plugin-1.pom"]
        payloads = [b"fixture POM payload", b"fixture JAR payload", b"plugin POM payload"]
        origin = f"http://127.0.0.1:{self.server.server_address[1]}"
        pins = []
        for path, data in zip(paths, payloads):
            url_path = "/closure/" + path
            self.payloads[url_path] = data
            pins.append({"path": path, "url": origin + url_path, "bytes": len(data),
                         "sha256": hashlib.sha256(data).hexdigest()})

        def configure(lock):
            lock["mavenRepository"] = {"profiles": PROFILES, "artifacts": pins}
            lock["teavm"]["artifacts"] = [pins[1]]

        self.write_fixture_lock(configure)
        repo = self.work / "maven-repository"

        def assert_status(state, checked, failed=None):
            rows = self.fixture_status()
            grouped = [row for row in rows if row["id"] == "maven-repository"]
            self.assertEqual([row["requiredFor"] for row in grouped], [[profile] for profile in PROFILES])
            self.assertFalse(any(row["id"] in {"fixture", "remapper"} for row in rows))
            for row in grouped:
                self.assertEqual(row["destinationPath"], str(repo))
                self.assertTrue(row["installable"])
                self.assertEqual((row["state"], row["checkedFiles"], row["totalFiles"], row.get("firstFailedPath")),
                                 (state, checked, len(paths), failed))
                self.assertEqual(row["totalBytes"], sum(map(len, payloads)))

        assert_status("missing", 0, paths[0])
        first = repo / paths[0]
        first.parent.mkdir(parents=True)
        first.write_bytes(payloads[0])
        assert_status("partial", 1, paths[1])
        partial = repo / (paths[1] + ".part")
        partial.write_bytes(payloads[1][:7])
        offline = self.run_fixture_tools("install", "--only", "maven-repository", "--json-progress", "--offline")
        self.assertNotEqual(offline.returncode, 0)
        self.assertEqual(self.requests, 0)
        self.assertEqual(partial.read_bytes(), payloads[1][:7])

        def install():
            result = self.run_fixture_tools("install", "--only", "maven-repository", "--json-progress")
            return result, [json.loads(line) for line in result.stdout.splitlines()]

        result, events = install()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.request_ranges, ["bytes=7-", None])
        self.assertEqual(self.request_paths, ["/closure/" + path for path in paths[1:]])
        self.assertEqual([event["bytes"] for event in events if event["step"] == "verified"],
                         [len(payloads[1]), len(payloads[1]) + len(payloads[2])])
        pcts = [event["pct"] for event in events]
        self.assertEqual(pcts, sorted(pcts))
        self.assertEqual(pcts[-1], 100)
        self.assertFalse(partial.exists())
        self.assertEqual([(repo / path).read_bytes() for path in paths], payloads)
        assert_status("present", 3)

        bad = b"x" * len(payloads[1])
        (repo / paths[1]).write_bytes(bad)
        assert_status("hash-mismatch", 2, paths[1])
        self.payloads["/closure/" + paths[1]] = bad
        result, events = install()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SHA-256 mismatch", events[-1]["error"])
        self.assertFalse(partial.exists())
        self.assertEqual((repo / paths[1]).read_bytes(), bad)
        self.payloads["/closure/" + paths[1]] = payloads[1]
        result, _ = install()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        assert_status("present", 3)

        external = Path(self.temp.name) / "offline Maven source"
        shutil.copytree(repo, external / "maven-repository")
        old_requests = self.requests
        self.work = Path(self.temp.name) / "second-work"
        copied = self.run_fixture_tools("install", "--only", "maven-repository", "--json-progress", "--offline",
                                        "--toolchain-dir", str(external))
        self.assertEqual(copied.returncode, 0, copied.stdout + copied.stderr)
        self.assertEqual(self.requests, old_requests)
        for path, data in zip(paths, payloads):
            self.assertEqual((self.work / "maven-repository" / path).read_bytes(), data)
            self.assertEqual((external / "maven-repository" / path).read_bytes(), data)

    def test_locked_install_verifies_download_and_removes_tampered_bytes(self):
        self.configure_fixture_lock()
        partial = self.work / "tools" / "fixture-maven.bin.part"
        partial.parent.mkdir(parents=True)
        partial.write_bytes(self.payload[:7])
        installed = self.run_fixture_tools("install", "--only", "maven", "--json-progress")
        self.assertEqual(installed.returncode, 0, installed.stdout + installed.stderr)
        events = [json.loads(line) for line in installed.stdout.splitlines()]
        self.assertTrue(any(event["schema"] == "gaius-patcher-tools-progress-v1"
                            and event["step"] == "download" for event in events))
        target = self.work / "tools" / "fixture-maven.bin"
        self.assertEqual(target.read_bytes(), self.payload)
        self.assertEqual(self.request_ranges[0], "bytes=7-")

        self.work = Path(self.temp.name) / "tamper-work"
        self.configure_fixture_lock("1" * 64)
        rejected = self.run_fixture_tools("install", "--only", "maven", "--json-progress")
        self.assertNotEqual(rejected.returncode, 0)
        failure = json.loads(rejected.stdout.splitlines()[-1])
        self.assertIn("SHA-256 mismatch", failure["error"])
        self.assertFalse((self.work / "tools" / "fixture-maven.bin").exists())
        self.assertFalse((self.work / "tools" / "fixture-maven.bin.part").exists())

    def test_offline_install_fails_without_contacting_source(self):
        self.configure_fixture_lock()
        before = self.requests
        failed = self.run_fixture_tools("install", "--only", "maven", "--json-progress", "--offline")
        self.assertNotEqual(failed.returncode, 0)
        failure = json.loads(failed.stdout.splitlines()[-1])
        self.assertIn("missing tool", failure["error"])
        self.assertEqual(self.requests, before)


@unittest.skipUnless(os.name == "posix" and shutil.which("bash"), "requires a POSIX Bash fixture")
class PythonResolutionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="gaius-python-")
        self.addCleanup(self.temp.cleanup)
        self.bin = Path(self.temp.name) / "路径 with spaces"
        self.bin.mkdir()
        self.bash = shutil.which("bash")
        self.runner = REPOSITORY / "port/scripts/run-python.sh"
        self.env = {"PATH": str(self.bin)}

    def executable(self, name, code):
        path = self.bin / name
        path.write_text(f"#!{self.bash}\n{code}\n", encoding="utf-8")
        path.chmod(0o755)
        return path

    def run_python(self, *args):
        return subprocess.run([self.bash, self.runner, *args], env=self.env, capture_output=True,
                              text=True, encoding="utf-8", timeout=10)

    def assert_resolved(self, label, path):
        result = self.run_python("--resolve")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [label, str(path)])
        self.env.update(PATH="", PYTHON=result.stdout.splitlines()[1])
        run = self.run_python("-c", "import sys; print(sys.argv[1])", "argument with spaces")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(run.stdout.strip(), "argument with spaces")

    def test_python3_only(self):
        path = self.executable("python3", f'exec {shlex.quote(sys.executable)} "$@"')
        self.assert_resolved("python3", path)

    def test_python_only(self):
        path = self.executable("python", f'exec {shlex.quote(sys.executable)} "$@"')
        self.assert_resolved("python", path)

    def test_windows_python_exe_only(self):
        self.env["MSYSTEM"] = "MINGW64"
        path = self.executable("python.exe", f'exec {shlex.quote(sys.executable)} "$@"')
        self.assert_resolved("python.exe", path)

    def test_windows_launcher_only_and_no_install(self):
        self.env.update(MSYSTEM="MINGW64", PYTHON_MANAGER_AUTOMATIC_INSTALL="true",
                        PYLAUNCHER_ALLOW_INSTALL="1", PYLAUNCHER_ALWAYS_INSTALL="1")
        self.executable("python.exe", "exit 127")
        path = self.executable("resolved python.exe", f'exec {shlex.quote(sys.executable)} "$@"')
        self.executable("py.exe", '\n'.join([
            '[[ "$PYTHON_MANAGER_AUTOMATIC_INSTALL" == false && ! -v PYLAUNCHER_ALLOW_INSTALL '
            '&& ! -v PYLAUNCHER_ALWAYS_INSTALL ]] || exit 1',
            '[[ "$1" == -3 && "$2" == -c ]] || exit 2',
            f"printf '%s' {shlex.quote(str(path))}",
        ]))
        self.assert_resolved("py -3", path)

    def test_missing_or_unusable(self):
        for with_broken_alias in (False, True):
            with self.subTest(broken_alias=with_broken_alias):
                if with_broken_alias:
                    self.executable("python", "exit 1")
                result = self.run_python("--resolve")
                self.assertEqual(result.returncode, 127)
                self.assertEqual(result.stdout, "")

    def test_explicit_override_with_spaces(self):
        self.executable("python", "exit 1")
        path = self.executable("custom Python", f'exec {shlex.quote(sys.executable)} "$@"')
        self.env["PYTHON"] = str(path)
        self.assert_resolved("custom Python", path)


if __name__ == "__main__":
    unittest.main()
