#!/usr/bin/env python3
"""Offline fixtures for matrix planning and verified Pages assembly."""

from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import os
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import gaius_pages_release as RELEASE

SPEC = importlib.util.spec_from_file_location(
    "pages_fixtures", Path(__file__).with_name("test-build-pages-site.py"))
assert SPEC is not None and SPEC.loader is not None
FIXTURES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURES)
SITE = FIXTURES.SITE


def versions(root: Path, names: tuple[str, ...] = ("alpha",)) -> Path:
    directory = root / "versions"
    directory.mkdir()
    for index, name in enumerate(names):
        SITE.write_json(directory / (name + ".json"), {
            "id": name, "official": {"releaseTime": f"2026-01-{index + 1:02d}T00:00:00Z"},
            "pages": {"enabled": True, "runtimes": ["wasm"]},
        })
    return directory


def dist(root: Path, profile: str, wasm: bool = True) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    directory = FIXTURES.make_dist(root)
    identity = SITE.profile_of(FIXTURES.INDEX)
    (directory / "index.html").write_text(FIXTURES.INDEX.replace(identity, profile), encoding="utf-8")
    if wasm:
        for name, data in (("classes.wasm", b"\0asm client"),
                           ("singleplayer-server.wasm", b"\0asm server"),
                           ("gaius-wasm-gc-runtime.js", b"runtime")):
            (directory / name).write_bytes(data)
    return directory


def checksums(directory: Path) -> None:
    entries = []
    for path in sorted(directory.iterdir()):
        if path.is_file() and path.name != "SHA256SUMS":
            entries.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name)
    (directory / "SHA256SUMS").write_bytes(("\n".join(entries) + "\n").encode("ascii"))


def write_sidecar(path: Path, profile: str) -> None:
    path.with_name(path.name + ".build.json").write_text(json.dumps({
        "profile": {"id": profile},
        "artifact": {"name": path.name, "bytes": path.stat().st_size,
                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest()},
    }), encoding="utf-8")


def build_release_site(source: Path, site: Path, archive: Path | None = None) -> None:
    profile = SITE.profile_of((source / "index.html").read_text())
    write_sidecar(source / "vanilla-assets.pack.gz", profile)
    SITE.build(source, site, archive, True, source, wasm_gzip_only=True,
               js_gzip_copies=False, no_js_fallback=True)


def use_fixture_versions(test: unittest.TestCase) -> None:
    """The fixture dists name invented profiles; give build() their version files."""
    directory = tempfile.TemporaryDirectory()
    test.addCleanup(directory.cleanup)
    profiles = versions(Path(directory.name), ("alpha", "beta", "gamma", "future"))
    patcher = patch.object(SITE, "VERSIONS_DIR", profiles)
    patcher.start()
    test.addCleanup(patcher.stop)


class PagesMatrixTest(unittest.TestCase):
    def setUp(self) -> None:
        use_fixture_versions(self)

    def test_profiles_and_wasm_only_runtime_policy_come_from_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = versions(root, ("alpha", "beta", "gamma", "future"))
            matrix = SITE.release_matrix(directory)
            self.assertEqual([profile["id"] for profile in matrix["profiles"]],
                             ["future", "gamma", "beta", "alpha"])
            self.assertEqual([profile["runtimes"] for profile in matrix["profiles"]],
                             [["wasm"]] * 4)
            profile = json.loads((directory / "future.json").read_text())
            profile["pages"]["enabled"] = False
            SITE.write_json(directory / "future.json", profile)
            self.assertEqual(SITE.release_matrix(directory)["profiles"][0]["id"], "gamma")

    def test_invalid_profile_identity_or_runtime_is_rejected(self) -> None:
        for change in ({"id": "../alpha"}, {"pages": {"enabled": True, "runtimes": ["js"]}}):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                directory = versions(Path(temporary))
                profile = json.loads((directory / "alpha.json").read_text())
                profile.update(change)
                SITE.write_json(directory / "alpha.json", profile)
                with self.assertRaises(RuntimeError):
                    SITE.release_matrix(directory)

    def test_dry_run_has_exact_layout_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = dist(root / "input", "alpha")
            plan = SITE.build(source, root / "dry", root / "dry.tar.gz", True, source,
                              wasm_gzip_only=True, js_gzip_copies=False, dry_run=True)
            self.assertFalse((root / "dry").exists())
            self.assertFalse((root / "dry.tar.gz").exists())
            built = SITE.build(source, root / "built", None, True, source,
                               wasm_gzip_only=True, js_gzip_copies=False)
            for field in ("bytes", "layout", "runtimeBytes", "keptByNextDeployBytes"):
                self.assertEqual(plan[field], built[field], field)
            self.assertEqual(sum(plan["runtimeBytes"].values()), plan["bytes"])
            self.assertEqual(SITE.site_size(root / "built")["runtimeBytes"], plan["runtimeBytes"])

    def test_single_site_gate_fails_before_copying(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = dist(root / "input", "alpha")
            with patch.object(SITE, "DEFAULT_LIMIT_BYTES", 1), self.assertRaises(RuntimeError):
                SITE.build(source, root / "output", None, True, source)
            self.assertFalse((root / "output").exists())

    def test_dual_runtime_site_supplies_missing_page_post_script_without_restaging_js(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = dist(root / "input", "alpha")
            backend = source / "runtime/webgpu"
            backend.mkdir(parents=True)
            for name in ("gpu-glue.js", "shaderxlat-job.js", "gaius-shaderxlat.wasm"):
                (backend / name).write_bytes(b"backend fixture")
            original = {name: (source / name).read_bytes() for name in ("classes.js", "singleplayer-server.js")}
            SITE.build(source, root / "site", None, True, source, wasm_gzip_only=True, js_gzip_copies=False)
            assets = json.loads((root / "site/gaius-site.json").read_text())["assets"]
            for name in SITE.WEBGPU_MODULES:
                self.assertIn("runtime/webgpu/" + name, assets)
            self.assertEqual((root / "site" / assets["runtime/webgpu/quality-post.js"]).read_bytes(),
                             (SITE.WEB / "runtime/webgpu/quality-post.js").read_bytes())
            self.assertFalse((backend / "quality-post.js").exists())
            for name, data in original.items():
                self.assertEqual((source / name).read_bytes(), data)

    def test_complete_matrix_materializes_the_planned_sites(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata = versions(root, ("alpha", "beta"))
            artifacts = root / "artifacts"
            for profile in ("alpha", "beta"):
                artifact = dist(artifacts / profile, profile)
                write_sidecar(artifact / "vanilla-assets.pack.gz", profile)
            args = [str(root / "output"), "--versions-dir", str(metadata),
                    "--artifact-root", str(artifacts), "--json", str(root / "report.json")]
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(SITE.main_matrix(args), 0)
            report = json.loads((root / "report.json").read_text())
            for profile in report["profiles"]:
                self.assertEqual(profile["bytes"], SITE.site_size(root / "output" / profile["profile"])["bytes"])
                self.assertTrue((root / "output" / f"Gaius-site-{profile['profile']}.tar.gz").is_file())
                self.assertEqual(profile["projected"], [])
            self.assertGreater(report["rootBytes"], 0)
            self.assertEqual(report["totalBytes"], report["sitesBytes"] + report["retainedBytes"]
                             + report["matrixBytes"] + report["rootBytes"])
            self.assertTrue((root / "output" / RELEASE.MATRIX_ASSET).is_file())

    def test_hard_gate_is_inclusive_and_cannot_be_raised(self) -> None:
        for size, expected in ((900_000_000, True), (900_000_001, False)):
            with self.subTest(size=size), patch.object(SITE, "site_size", return_value={
                    "profile": "alpha", "bytes": size, "keptByNextDeployBytes": 0, "rawWasmOverLimit": []}):
                report = SITE.check_total([Path("fixture")], 1_000_000_000)
                self.assertEqual(report["ok"], expected)
                self.assertEqual(report["limitBytes"], 900_000_000)

    def test_metadata_counts_in_the_preflight_gate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            metadata = Path(temporary) / "metadata.json"
            metadata.write_bytes(b"{}\n")
            with patch.object(SITE, "site_size", return_value={
                    "profile": "alpha", "bytes": 899_999_998, "keptByNextDeployBytes": 0, "rawWasmOverLimit": []}):
                report = SITE.check_total([Path("fixture")], 900_000_000, [metadata])
            self.assertEqual(report["extraBytes"], 3)
            self.assertFalse(report["ok"])

    def test_discovery_skips_shared_work_and_uses_newest_complete_dist(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            oldest = dist(root / "old", "alpha")
            newest = dist(root / "new", "alpha")
            ignored = dist(root / "work", "alpha")
            os.utime(oldest / "classes.wasm", ns=(1_700_000_000_000_000_000, 1_700_000_000_000_000_000))
            os.utime(newest / "classes.wasm", ns=(1_700_000_001_000_000_000, 1_700_000_001_000_000_000))
            found = SITE.discover_dists([root])
            self.assertNotIn(("alpha", ignored), found)
            self.assertEqual(SITE.newest_dist(found, "alpha", ("classes.wasm",)), newest)
            (newest / "singleplayer-server.wasm").write_bytes(b"")
            self.assertEqual(SITE.newest_dist(found, "alpha", ("classes.wasm", "singleplayer-server.wasm")), oldest)
            with self.assertRaises(RuntimeError):
                SITE.discover_dists([Path(root.anchor)])

    def test_matrix_missing_server_is_explicitly_projected_only_in_dry_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata = versions(root, ("alpha", "beta"))
            artifacts = root / "artifacts"
            missing = dist(artifacts / "alpha", "alpha")
            write_sidecar(missing / "vanilla-assets.pack.gz", "alpha")
            (missing / "singleplayer-server.wasm").unlink()
            donor = dist(artifacts / "beta", "beta")
            write_sidecar(donor / "vanilla-assets.pack.gz", "beta")
            args = [str(root / "output"), "--versions-dir", str(metadata),
                    "--artifact-root", str(artifacts), "--json", str(root / "report.json")]
            with contextlib.redirect_stdout(io.StringIO()):
                result = SITE.main_matrix(args + ["--dry-run", "--project-missing"])
            self.assertEqual(result, 0)
            self.assertFalse((root / "output").exists())
            report = json.loads((root / "report.json").read_text())
            self.assertFalse(report["releaseReady"])
            alpha = next(profile for profile in report["profiles"] if profile["profile"] == "alpha")
            self.assertEqual(len(alpha["projected"]), 1)
            self.assertIn("server", alpha["projected"][0]["reason"])
            self.assertEqual(alpha["projected"][0]["basis"], str(donor))
            with self.assertRaises(RuntimeError):
                SITE.main_matrix(args + ["--dry-run"])
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                SITE.main_matrix(args + ["--project-missing"])


class PagesReleaseTest(unittest.TestCase):
    def setUp(self) -> None:
        use_fixture_versions(self)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.release = self.root / "release"
        self.release.mkdir()
        self.matrix = SITE.release_matrix(versions(self.root))
        self.enabled_matrix = json.loads(json.dumps(self.matrix))
        self.source = dist(self.root / "input", "alpha")
        self.archive = self.release / "Gaius-site-alpha.tar.gz"
        build_release_site(self.source, self.root / "site", self.archive)
        SITE.write_json(self.release / RELEASE.MATRIX_ASSET, self.matrix)
        checksums(self.release)

    def assemble(self, previous: Path | None = None) -> dict:
        return RELEASE.assemble(self.release, self.root / "publish", self.enabled_matrix,
                                SITE.kept_by_next_deploy, SITE.runtime_group, "v0.4.0", previous)

    def test_verified_wasm_only_assembly_reports_exact_size(self) -> None:
        report = self.assemble()
        output = self.root / "publish"
        self.assertEqual(report["totalBytes"], sum(path.stat().st_size for path in output.rglob("*")
                                                 if path.is_file()))
        self.assertEqual(report["profiles"]["alpha"], SITE.site_size(output / "alpha")["runtimeBytes"])
        descriptor = json.loads((output / "alpha/gaius-site.json").read_text())
        self.assertEqual(descriptor["runtimePolicy"], "wasm-only")
        self.assertNotIn("classes.js", descriptor["assets"])
        self.assertNotIn("singleplayer-server.js", descriptor["assets"])
        for name in ("classes.wasm", "singleplayer-server.wasm"):
            self.assertNotIn(name, descriptor["assets"])
            self.assertFalse((output / "alpha" / descriptor["inflate"][name]).exists())
        self.assertIn("location.search + location.hash", (output / "Gaius-alpha.html").read_text())

    def test_retention_keeps_wasm_server_and_deduplicates(self) -> None:
        previous = self.root / "previous"
        previous.mkdir()
        source = dist(self.root / "previous-input", "alpha")
        (source / "singleplayer-server.wasm").write_bytes(b"\0asm old server")
        build_release_site(source, self.root / "previous-site", previous / self.archive.name)
        checksums(previous)
        report = self.assemble(previous)
        old = json.loads((self.root / "previous-site/gaius-site.json").read_text())["assets"]
        output = self.root / "publish/alpha"
        self.assertNotIn("classes.js", old)
        self.assertNotIn("singleplayer-server.js", old)
        self.assertTrue((output / old["singleplayer-server.wasm.gz"]).is_file())
        expected = sum(path.stat().st_size for path in (self.root / "previous-site").rglob("*")
                       if path.is_file() and SITE.kept_by_next_deploy(path.relative_to(
                           self.root / "previous-site").as_posix()) and not (self.root / "site" / path.relative_to(
                               self.root / "previous-site")).exists())
        self.assertEqual(report["retainedBytes"], {"alpha": expected})

    def test_corrupt_previous_generation_is_not_published(self) -> None:
        previous = self.root / "previous"
        previous.mkdir()
        (previous / self.archive.name).write_bytes(self.archive.read_bytes())
        (previous / "SHA256SUMS").write_bytes(b"bad checksum\n")
        report = self.assemble(previous)
        self.assertEqual(report["retainedBytes"], {})
        self.assertIn("not verifiable", report["warnings"][0])

    def test_retained_profile_cannot_escape_output(self) -> None:
        previous = self.root / "previous"
        previous.mkdir()
        descriptor_path = self.root / "site/gaius-site.json"
        descriptor = json.loads(descriptor_path.read_text())
        descriptor["profile"] = ".."
        SITE.write_json(descriptor_path, descriptor)
        SITE.archive_site(self.root / "site", previous / "Gaius-site-...tar.gz")
        checksums(previous)
        with self.assertRaisesRegex(RuntimeError, "invalid retained profile"):
            self.assemble(previous)
        self.assertFalse((self.root / "publish").exists())

    def test_partial_matrix_cannot_fall_back_to_portable(self) -> None:
        extra = dict(self.matrix["profiles"][0])
        extra.update(id="beta", path="beta/", siteArchive="Gaius-site-beta.tar.gz",
                     portableAsset="Gaius-beta.html")
        self.matrix["profiles"].append(extra)
        SITE.write_json(self.release / RELEASE.MATRIX_ASSET, self.matrix)
        checksums(self.release)
        with self.assertRaisesRegex(RuntimeError, "partial|match the enabled version profiles"):
            self.assemble()
        self.assertFalse((self.root / "publish").exists())

    def test_matrix_profile_set_must_match_enabled_version_profiles(self) -> None:
        mismatched = dict(self.matrix["profiles"][0])
        mismatched.update(id="beta", path="beta/", siteArchive="Gaius-site-beta.tar.gz",
                          portableAsset="Gaius-beta.html")
        self.matrix["profiles"] = [mismatched]
        SITE.write_json(self.release / RELEASE.MATRIX_ASSET, self.matrix)
        checksums(self.release)
        with self.assertRaisesRegex(RuntimeError, "match the enabled version profiles"):
            self.assemble()
        self.assertFalse((self.root / "publish").exists())

    def test_current_checksum_failure_stops_before_copying(self) -> None:
        with self.archive.open("ab") as stream:
            stream.write(b"changed")
        with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
            self.assemble()
        self.assertFalse((self.root / "publish").exists())

    def test_checksum_records_reject_crlf_duplicates_and_binary_mode(self) -> None:
        original = (self.release / "SHA256SUMS").read_bytes()
        for records in (original.replace(b"\n", b"\r\n"), original + original,
                        original.replace(b"  ", b" *")):
            with self.subTest(records=records[:16]):
                (self.release / "SHA256SUMS").write_bytes(records)
                with self.assertRaises(RuntimeError):
                    RELEASE.verified_asset(self.release, self.archive.name)

    def test_legacy_single_file_release_remains_usable(self) -> None:
        (self.release / RELEASE.MATRIX_ASSET).unlink()
        self.archive.unlink()
        (self.release / "Gaius-alpha.html").write_bytes(b"<!doctype html>legacy")
        checksums(self.release)
        report = self.assemble()
        self.assertEqual(report["layout"], "single-file")
        worker = (self.root / "publish/alpha/gaius-sw.js").read_text()
        self.assertIn("registration.unregister", worker)
        self.assertNotIn("caches.delete", worker)

    def test_archive_rejects_traversal_symlinks_and_duplicate_entries(self) -> None:
        for names in (("../escape",), ("/escape",), ("link",), ("duplicate", "duplicate")):
            with self.subTest(names=names):
                path = self.root / "unsafe.tar.gz"
                with tarfile.open(path, "w:gz") as archive:
                    for name in names:
                        entry = tarfile.TarInfo(name)
                        if name == "link":
                            entry.type = tarfile.SYMTYPE
                            entry.linkname = "../escape"
                            archive.addfile(entry)
                        else:
                            entry.size = 1
                            archive.addfile(entry, io.BytesIO(b"x"))
                with self.assertRaisesRegex(RuntimeError, "unsafe or duplicate"):
                    RELEASE.archive_entries(path)

    def test_missing_server_or_raw_wasm_is_rejected(self) -> None:
        descriptor_path = self.root / "site/gaius-site.json"
        original = json.loads(descriptor_path.read_text())
        for missing in (True, False):
            with self.subTest(missing=missing):
                descriptor = json.loads(json.dumps(original))
                if missing:
                    del descriptor["assets"]["singleplayer-server.wasm.gz"]
                else:
                    (self.root / "site/classes.wasm").write_bytes(b"raw")
                SITE.write_json(descriptor_path, descriptor)
                SITE.archive_site(self.root / "site", self.archive)
                checksums(self.release)
                with self.assertRaises(RuntimeError):
                    self.assemble()
                self.assertFalse((self.root / "publish").exists())

    def test_total_gate_includes_retention_before_any_output(self) -> None:
        entries = RELEASE.archive_entries(self.archive)
        entries["index.html"].size = 899_900_000
        previous = self.root / "previous"
        previous.mkdir()
        (previous / self.archive.name).write_bytes(self.archive.read_bytes())
        checksums(previous)
        retained = tarfile.TarInfo("singleplayer-server.0123456789abcdef.js")
        retained.size = 900_000_000
        with patch.object(RELEASE, "archive_entries", side_effect=[entries, {retained.name: retained}]), \
                patch.object(RELEASE, "check_site"), self.assertRaisesRegex(RuntimeError, "including retained"):
            self.assemble(previous)
        self.assertFalse((self.root / "publish").exists())

    def test_nonempty_output_is_preserved(self) -> None:
        output = self.root / "publish"
        output.mkdir()
        (output / "keep").write_bytes(b"original")
        with self.assertRaisesRegex(RuntimeError, "overwrite"):
            self.assemble()
        self.assertEqual((output / "keep").read_bytes(), b"original")


if __name__ == "__main__":
    unittest.main()
