#!/usr/bin/env python3
"""CPU checks for retained compiler profiles on frozen staging inputs."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("build-portable-html.py")
SPEC = importlib.util.spec_from_file_location("portable_compiler_test", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
PORTABLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PORTABLE)
COMPILER = PORTABLE.compiler_profile


def write(path: Path, content: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def save_profile(path: Path, record: dict) -> None:
    write(path, COMPILER.canonical_json(record) + "\n")


def rehash(record: dict) -> dict:
    record.pop("profileSha256", None)
    record["profileSha256"] = hashlib.sha256(COMPILER.canonical_json(record).encode("ascii")).hexdigest()
    return record


class Fixture:
    def __init__(self, directory: Path, role="client", external=False, relative_target=False):
        self.root = directory / "source"
        self.role = role
        name = "classes.js" if role == "client" else "singleplayer-server.js"
        self.original = write(
            (directory if external else self.root) / "dist" / name, "// retained compiler output\n"
        )
        self.staged = self.root / "scratch" / name
        self.staged.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self.original, self.staged)
        self.resource = write(self.root / "build/generated-resources/resources.txt", "assets/fixture.json\n")
        self.pack = write(self.original.parent / "vanilla-assets.pack.gz", "original JS pack\n")
        self.staged_pack = write(self.staged.parent / self.pack.name, "Wasm-owned pack\n")
        self.resources = [self.resource, self.pack] if role == "client" else [self.resource]
        self.staged_resources = [self.resource, self.staged_pack] if role == "client" else [self.resource]
        self.pom = self.root / "build/release-generated-pom.xml"
        target = "../dist" if relative_target else self.original.parent.as_posix()
        main_class = COMPILER.EXPECTED_MAIN_CLASSES[role]
        write(self.pom, f'''<project><build><plugins><plugin>
<groupId>org.teavm</groupId><artifactId>teavm-maven-plugin</artifactId>
<executions><execution><id>web-release</id><phase>package</phase>
<goals><goal>compile</goal></goals><configuration>
<mainClass>{main_class}</mainClass><targetDirectory>{target}</targetDirectory>
<targetFileName>{name}</targetFileName><optimizationLevel>ADVANCED</optimizationLevel>
<sourceMapsGenerated>false</sourceMapsGenerated><debugInformationGenerated>false</debugInformationGenerated>
<minifying>true</minifying><shortFileNames>true</shortFileNames><assertionsRemoved>true</assertionsRemoved>
</configuration></execution></executions></plugin></plugins></build></project>''')
        self.record = COMPILER.create_record(self.root, role, self.original, self.pom, self.resources, True)
        self.original_sidecar = COMPILER.default_output(self.original)
        self.sidecar = COMPILER.default_output(self.staged)
        save_profile(self.original_sidecar, self.record)
        shutil.copyfile(self.original_sidecar, self.sidecar)

    def verify(self, frozen=True, inplace=False):
        return PORTABLE.verified_compiler_profile(
            self.root, self.original if inplace else self.staged, self.role, self.pom,
            self.resources if inplace else self.staged_resources, frozen=frozen,
        )

    def set_pom_path(self, case):
        workspace = self.root / "compiler-workspace"
        self.pom = workspace / "release-generated-pom.xml"
        if case.endswith("grandparent"):
            self.pom = workspace / "nested" / self.pom.name
        if case.startswith("dangling"):
            workspace.symlink_to(self.root / "absent-workspace", target_is_directory=True)
        elif case == "linked-empty-directory":
            target = self.root / "empty-workspace"
            target.mkdir()
            workspace.symlink_to(target, target_is_directory=True)
        elif case == "linked-missing-subdirectory":
            target = self.root / "empty-workspace"
            target.mkdir()
            workspace.symlink_to(target, target_is_directory=True)
            self.pom = workspace / "nested" / self.pom.name
        elif case.startswith("file"):
            workspace.write_text("not a directory", encoding="utf-8")
        elif case == "link-loop":
            workspace.symlink_to(workspace, target_is_directory=True)
        elif case == "null-leaf":
            workspace.mkdir()
            self.pom = workspace / "invalid\0.xml"
        elif case == "missing-leaf":
            workspace.mkdir()
        elif not case.startswith("missing"):
            raise AssertionError(case)

    def change_pom(self, before, after):
        text = self.pom.read_text(encoding="utf-8")
        assert before in text
        self.pom.write_text(text.replace(before, after), encoding="utf-8")


class RetainedCompilerProfileTest(unittest.TestCase):
    def fixture(self, **options):
        temporary = tempfile.TemporaryDirectory(prefix="portable-compiler-")
        self.addCleanup(temporary.cleanup)
        return Fixture(Path(temporary.name), **options)

    def test_genuine_profiles_pass_inplace_and_relocated_without_writes(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for external, relative in ((False, False), (False, True), (True, False)):
                with self.subTest(role=role, external=external, relative=relative):
                    f = self.fixture(role=role, external=external, relative_target=relative)
                    paths = [f.original, f.original_sidecar, f.pom, *f.resources, f.staged, f.sidecar, f.staged_pack]
                    before = {p: p.read_bytes() for p in paths}
                    strict = f.verify(frozen=False, inplace=True)
                    self.assertEqual(f.verify(inplace=True), strict)
                    with mock.patch.object(COMPILER, "create_record", wraps=COMPILER.create_record) as create:
                        self.assertEqual(f.verify(), strict)
                    self.assertEqual(create.call_args.args[2].resolve(), f.original)
                    self.assertEqual([p.resolve() for p in create.call_args.args[4]], f.resources)
                    self.assertEqual(create.call_args.kwargs["artifact_input"], f.staged)
                    self.assertEqual(before, {p: p.read_bytes() for p in paths})
                    self.assertEqual(f.record["artifact"]["path"], COMPILER.relative_name(f.root, f.original))
                    self.assertEqual(Path(f.record["artifact"]["path"]).is_absolute(), external)

    def refresh(self, fixture):
        fixture.record = COMPILER.create_record(
            fixture.root, fixture.role, fixture.original, fixture.pom, fixture.resources, True,
        )
        save_profile(fixture.original_sidecar, fixture.record)
        save_profile(fixture.sidecar, fixture.record)

    def test_published_summary_is_independent_of_workspace_path_and_pom_crlf(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for external in (False, True):
                with self.subTest(role=role, external=external):
                    temporary = tempfile.TemporaryDirectory(prefix="portable-compiler-roots-")
                    self.addCleanup(temporary.cleanup)
                    fixtures = [Fixture(Path(temporary.name) / name, role=role, external=external)
                                for name in ("short", "different path with spaces/deeper")]
                    for index, fixture in enumerate(fixtures):
                        dependency = write(fixture.root.parent / "build/overlays/input.jar", "synthetic jar\n")
                        fixture.change_pom("<project>", "<project><dependencies><dependency>"
                                           "<groupId>fixture</groupId><artifactId>library</artifactId>"
                                           "<version>1</version><systemPath>"
                                           + dependency.as_posix()
                                           + "</systemPath></dependency></dependencies>")
                        if index:
                            fixture.pom.write_bytes(fixture.pom.read_bytes().replace(b"\n", b"\r\n"))
                        self.refresh(fixture)
                    first, second = fixtures
                    self.assertNotEqual(first.record["profileSha256"], second.record["profileSha256"])
                    self.assertEqual(first.verify(), second.verify())
                    self.assertEqual(first.verify(inplace=True, frozen=False), second.verify())
                    summary = first.verify()
                    self.assertEqual(summary["fingerprintPolicy"], COMPILER.RELEASE_FINGERPRINT_POLICY)
                    for fixture in fixtures:
                        self.assertNotIn(str(fixture.root.parent), json.dumps(summary))
                    self.assertNotIn("profileSha256", summary)
                    self.assertNotIn("sidecarSha256", summary)

    def test_semantic_pom_and_bound_content_remain_part_of_release_fingerprint(self):
        mutations = (
            lambda f: f.change_pom("ADVANCED", "FULL"),
            lambda f: f.change_pom("</configuration>", "<stopOnErrors>true</stopOnErrors></configuration>"),
            lambda f: f.change_pom("</project>", "<properties><fixture>changed</fixture></properties></project>"),
            lambda f: f.original.write_text("// changed artifact\n", encoding="utf-8"),
            lambda f: f.resource.write_text("changed resource\n", encoding="utf-8"),
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                f = self.fixture()
                original = f.verify(inplace=True, frozen=False)
                mutation(f)
                with self.assertRaises(RuntimeError):
                    f.verify(inplace=True, frozen=False)
                self.refresh(f)
                updated = f.verify(inplace=True, frozen=False)
                self.assertNotEqual(original["fingerprintSha256"], updated["fingerprintSha256"])

    def test_system_dependency_bytes_and_logical_resource_names_affect_fingerprint(self):
        f = self.fixture()
        dependency = write(f.root.parent / "input.jar", "first input\n")
        f.change_pom("<project>", "<project><dependencies><dependency><systemPath>"
                     + dependency.as_posix() + "</systemPath></dependency></dependencies>")
        self.refresh(f)
        before = f.verify(inplace=True, frozen=False)
        dependency.write_text("other input\n", encoding="utf-8")
        after = f.verify(inplace=True, frozen=False)
        self.assertNotEqual(before["fingerprintSha256"], after["fingerprintSha256"])
        renamed = f.resource.with_name("renamed.txt")
        f.resource.rename(renamed)
        f.resources[0] = renamed
        self.refresh(f)
        self.assertNotEqual(after["fingerprintSha256"],
                            f.verify(inplace=True, frozen=False)["fingerprintSha256"])

    def test_ordinary_copy_stays_strict(self):
        f = self.fixture()
        with self.assertRaisesRegex(RuntimeError, "targetDirectory"):
            f.verify(frozen=False)
        f.staged.write_text("// changed staged JS\n", encoding="utf-8")
        with self.assertRaises(RuntimeError):
            f.verify()

    def test_staged_bytes_are_hashed_even_with_a_forged_staged_profile(self):
        f = self.fixture()
        f.staged.write_text("// forged staged JS\n", encoding="utf-8")
        forged = COMPILER.create_record(
            f.root, f.role, f.original, f.pom, f.resources, True, artifact_input=f.staged,
        )
        save_profile(f.sidecar, forged)
        with self.assertRaisesRegex(RuntimeError, "retained original"):
            f.verify()

    def test_original_compiler_resources_are_required(self):
        for resource in ("resource", "pack"):
            for change in ("changed", "missing"):
                with self.subTest(resource=resource, change=change):
                    f = self.fixture()
                    path = getattr(f, resource)
                    if change == "missing":
                        path.unlink()
                    else:
                        path.write_text("changed resource\n", encoding="utf-8")
                    with self.assertRaises(RuntimeError):
                        f.verify()

    def test_wasm_pack_cannot_replace_the_original_js_compiler_input(self):
        f = self.fixture()
        self.assertNotEqual(f.pack.read_bytes(), f.staged_pack.read_bytes())
        self.assertEqual(f.verify(), COMPILER.release_summary(f.root, f.original, f.pom, f.record))
        f.pack.write_bytes(f.staged_pack.read_bytes())
        with self.assertRaises(RuntimeError):
            f.verify()

    def test_pom_and_release_contract_changes_fail(self):
        changes = (
            ("ADVANCED", "SIMPLE"), ("ADVANCED", "FULL"),
            ("<sourceMapsGenerated>false", "<sourceMapsGenerated>true"),
            ("<debugInformationGenerated>false", "<debugInformationGenerated>true"),
            ("<minifying>true", "<minifying>false"),
            ("<shortFileNames>true", "<shortFileNames>false"),
            ("<assertionsRemoved>true", "<assertionsRemoved>false"),
            ("net.minecraft.client.main.Main", "dev.gaius.browser.BrowserIntegratedServerMain"),
            ("<targetFileName>classes.js", "<targetFileName>other.js"),
            ("<phase>package", "<phase>verify"),
            ("<goal>compile", "<goal>other"),
            ("<id>web-release", "<id>other-release"),
            ("</project>", "<!-- changed POM bytes --></project>"),
        )
        for before, after in changes:
            with self.subTest(field=before):
                f = self.fixture()
                f.change_pom(before, after)
                with self.assertRaises(RuntimeError):
                    f.verify()

    def test_full_optimization_is_supported(self):
        f = self.fixture()
        f.change_pom("ADVANCED", "FULL")
        f.record = COMPILER.create_record(f.root, f.role, f.original, f.pom, f.resources, True)
        save_profile(f.original_sidecar, f.record)
        save_profile(f.sidecar, f.record)
        self.assertEqual(f.verify()["optimizationLevel"], "FULL")

    def test_target_directory_changes_fail(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for target in ("scratch", "other"):
                with self.subTest(role=role, target=target):
                    f = self.fixture(role=role)
                    f.change_pom(str(f.original.parent), str(f.root / target))
                    with self.assertRaises(RuntimeError):
                        f.verify()

    def test_role_mix_fails(self):
        for role in ("singleplayer-worker", "other"):
            with self.subTest(role=role):
                f = self.fixture()
                f.role = role
                with self.assertRaises(RuntimeError):
                    f.verify()

    def test_whole_retained_record_is_checked(self):
        changes = (
            lambda r: r.update(extra="unretained"),
            lambda r: r.update(kind="other"),
            lambda r: r.update(schemaVersion=999),
            lambda r: r.update(releaseGrade=False),
            lambda r: r.update(role="singleplayer-worker"),
            lambda r: r["artifact"].update(sha256="0" * 64),
            lambda r: r["artifact"].update(bytes=999),
            lambda r: r["artifact"].update(path="../untrusted/classes.js"),
            lambda r: r["artifact"].update(path="/untrusted/classes.js"),
            lambda r: r["pom"].update(sha256="0" * 64),
            lambda r: r["resources"][0].update(sha256="0" * 64),
            lambda r: r["resources"][0].update(path="/untrusted/resource.txt"),
            lambda r: r["resources"].reverse(),
        )
        for index, change in enumerate(changes):
            for both in (False, True):
                with self.subTest(field=index, both=both):
                    f = self.fixture()
                    record = copy.deepcopy(f.record)
                    change(record)
                    save_profile(f.sidecar, rehash(record))
                    if both:
                        save_profile(f.original_sidecar, record)
                    with self.assertRaises(RuntimeError):
                        f.verify()

    def test_json_scalar_types_must_match_the_whole_record(self):
        for both in (False, True):
            with self.subTest(both=both):
                f = self.fixture()
                record = copy.deepcopy(f.record)
                record["releaseGrade"] = 1
                save_profile(f.sidecar, record)
                if both:
                    save_profile(f.original_sidecar, record)
                with self.assertRaises(RuntimeError):
                    f.verify()

    def test_profile_digest_is_checked(self):
        f = self.fixture()
        record = copy.deepcopy(f.record)
        record["profileSha256"] = "0" * 64
        save_profile(f.sidecar, record)
        save_profile(f.original_sidecar, record)
        with self.assertRaises(RuntimeError):
            f.verify()

    def test_untrusted_record_paths_are_not_read(self):
        for field in ("artifact", "resources"):
            with self.subTest(field=field):
                f = self.fixture()
                record = copy.deepcopy(f.record)
                entry = record["artifact"] if field == "artifact" else record["resources"][0]
                entry["path"] = "/untrusted/secret"
                save_profile(f.sidecar, rehash(record))
                real_read = Path.read_text
                seen = []

                def guarded_read(path, *args, **kwargs):
                    self.assertIn(path, (f.sidecar, f.original_sidecar))
                    seen.append(path)
                    return real_read(path, *args, **kwargs)

                with mock.patch.object(Path, "read_text", guarded_read):
                    with self.assertRaises(RuntimeError):
                        f.verify()
                self.assertIn(f.sidecar, seen)

    def test_missing_or_invalid_retained_profile_fails_with_pom_present(self):
        for which in ("sidecar", "original_sidecar"):
            for content in (None, "{", "[]", "null", '{"artifact":[]}'):
                with self.subTest(which=which, content=content):
                    f = self.fixture()
                    path = getattr(f, which)
                    if content is None:
                        path.unlink()
                    else:
                        path.write_text(content, encoding="utf-8")
                    with self.assertRaises(RuntimeError):
                        f.verify()

    def test_symlink_inputs_fail(self):
        for which in ("staged", "sidecar", "original", "original_sidecar", "pom", "resource", "pack"):
            with self.subTest(which=which):
                f = self.fixture()
                path = getattr(f, which)
                saved = path.with_name(path.name + ".saved")
                path.rename(saved)
                path.symlink_to(saved)
                with self.assertRaisesRegex(RuntimeError, "symlinks"):
                    f.verify()

    def test_target_directory_symlink_cannot_make_a_copy_look_inplace(self):
        f = self.fixture()
        saved = f.original.parent.with_name("retained-dist")
        f.original.parent.rename(saved)
        f.original.parent.symlink_to(f.staged.parent, target_is_directory=True)
        f.staged_pack.write_bytes((saved / f.pack.name).read_bytes())
        forged = COMPILER.create_record(f.root, f.role, f.staged, f.pom, f.staged_resources, True)
        save_profile(f.sidecar, forged)
        with self.assertRaisesRegex(RuntimeError, "symlinks"):
            f.verify()

    def test_symlink_directories_fail(self):
        for which in ("original", "staged", "resource"):
            with self.subTest(which=which):
                f = self.fixture()
                path = getattr(f, which).parent
                saved = path.with_name(path.name + ".saved")
                path.rename(saved)
                path.symlink_to(saved, target_is_directory=True)
                with self.assertRaises(RuntimeError):
                    f.verify()

    def test_legacy_frozen_fallback_requires_absent_pom(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for inplace in (False, True):
                for missing_sidecar in (False, True):
                    with self.subTest(role=role, inplace=inplace, missing_sidecar=missing_sidecar):
                        f = self.fixture(role=role)
                        sidecar = f.original_sidecar if inplace else f.sidecar
                        f.pom.unlink()
                        if missing_sidecar:
                            sidecar.unlink()
                        self.assertEqual(f.verify(inplace=inplace), {"frozen": True, "sidecar": sidecar.name})
                        with self.assertRaises(RuntimeError):
                            f.verify(frozen=False, inplace=inplace)

    def test_invalid_pom_ancestors_cannot_use_legacy_fallback(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for case in ("dangling-parent", "dangling-grandparent", "linked-empty-directory",
                         "linked-missing-subdirectory", "file-parent", "file-grandparent",
                         "link-loop", "null-leaf"):
                for inplace in (False, True):
                    for missing_sidecar in (False, True):
                        with self.subTest(role=role, case=case, inplace=inplace,
                                          missing_sidecar=missing_sidecar):
                            f = self.fixture(role=role)
                            f.set_pom_path(case)
                            if missing_sidecar:
                                (f.original_sidecar if inplace else f.sidecar).unlink()
                            with self.assertRaises(RuntimeError):
                                f.verify(inplace=inplace)

    def test_missing_pom_workspace_keeps_legacy_summary(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for case in ("missing-leaf", "missing-parent", "missing-grandparent"):
                for inplace in (False, True):
                    for missing_sidecar in (False, True):
                        with self.subTest(role=role, case=case, inplace=inplace,
                                          missing_sidecar=missing_sidecar):
                            f = self.fixture(role=role)
                            f.set_pom_path(case)
                            sidecar = f.original_sidecar if inplace else f.sidecar
                            if missing_sidecar:
                                sidecar.unlink()
                            self.assertEqual(f.verify(inplace=inplace),
                                             {"frozen": True, "sidecar": sidecar.name})
                            with self.assertRaises(RuntimeError):
                                f.verify(frozen=False, inplace=inplace)

    def test_pom_ancestor_inspection_is_bounded_and_stops_at_symlink(self):
        f = self.fixture()
        f.set_pom_path("dangling-grandparent")
        allowed = set(f.pom.absolute().parents) - {Path(f.pom.anchor)}
        seen = []
        real_lstat = Path.lstat

        def guarded_lstat(path, *args, **kwargs):
            self.assertIn(path, allowed)
            seen.append(path)
            return real_lstat(path, *args, **kwargs)

        with mock.patch.object(Path, "lstat", guarded_lstat):
            with self.assertRaisesRegex(RuntimeError, "symlinks"):
                f.verify()
        self.assertEqual(seen, list(reversed(f.pom.absolute().parents))[1:-1])

    def test_present_pom_requires_inplace_profile(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for frozen in (False, True):
                with self.subTest(role=role, frozen=frozen):
                    f = self.fixture(role=role)
                    f.original_sidecar.unlink()
                    with self.assertRaises(RuntimeError):
                        f.verify(frozen=frozen, inplace=True)

    def test_retargeted_staged_pom_with_missing_copied_profile_fails(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for frozen in (False, True):
                with self.subTest(role=role, frozen=frozen):
                    f = self.fixture(role=role)
                    original_profile = f.original_sidecar.read_bytes()
                    f.change_pom(str(f.original.parent), str(f.staged.parent))
                    f.sidecar.unlink()
                    with self.assertRaises(RuntimeError):
                        f.verify(frozen=frozen)
                    self.assertEqual(f.original_sidecar.read_bytes(), original_profile)

    def test_invalid_present_pom_never_uses_legacy_fallback(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for inplace in (False, True):
                for invalid in ("malformed", "directory", "dangling-link"):
                    for missing_sidecar in (False, True):
                        with self.subTest(role=role, inplace=inplace, invalid=invalid,
                                          missing_sidecar=missing_sidecar):
                            f = self.fixture(role=role)
                            f.pom.unlink()
                            if invalid == "malformed":
                                f.pom.write_text("<project>", encoding="utf-8")
                            elif invalid == "directory":
                                f.pom.mkdir()
                            else:
                                f.pom.symlink_to(f.pom.with_name("absent-pom.xml"))
                            if missing_sidecar:
                                (f.original_sidecar if inplace else f.sidecar).unlink()
                            for frozen in (False, True):
                                with self.subTest(frozen=frozen):
                                    with self.assertRaises(RuntimeError):
                                        f.verify(frozen=frozen, inplace=inplace)

    def test_present_pom_rejects_corrupt_inplace_profile(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for content in (b"{", b"[]", b"null", b"\xff", None):
                with self.subTest(role=role, content=content):
                    f = self.fixture(role=role)
                    if content is None:
                        f.original_sidecar.unlink()
                        f.original_sidecar.mkdir()
                    else:
                        f.original_sidecar.write_bytes(content)
                    for frozen in (False, True):
                        with self.subTest(frozen=frozen):
                            with self.assertRaises(RuntimeError):
                                f.verify(frozen=frozen, inplace=True)

    def test_frozen_inplace_profile_checks_exact_json_types(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for field in ("releaseGrade", "minifying", "sourceMapsGenerated"):
                with self.subTest(role=role, field=field):
                    f = self.fixture(role=role)
                    record = copy.deepcopy(f.record)
                    target = record if field == "releaseGrade" else record["compiler"]
                    target[field] = int(target[field])
                    save_profile(f.original_sidecar, record)
                    with self.assertRaises(RuntimeError):
                        f.verify(inplace=True)

    def test_present_pom_checks_all_inplace_inputs(self):
        for role in COMPILER.EXPECTED_MAIN_CLASSES:
            for which in ("original", "resource", "pom", "original_sidecar"):
                with self.subTest(role=role, which=which):
                    f = self.fixture(role=role)
                    if which == "pom":
                        f.change_pom("</project>", "<!-- changed bytes --></project>")
                    elif which == "original_sidecar":
                        record = copy.deepcopy(f.record)
                        record["extra"] = "unretained"
                        save_profile(f.original_sidecar, rehash(record))
                    else:
                        getattr(f, which).write_text("changed bytes\n", encoding="utf-8")
                    for frozen in (False, True):
                        with self.subTest(frozen=frozen):
                            with self.assertRaises(RuntimeError):
                                f.verify(frozen=frozen, inplace=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
