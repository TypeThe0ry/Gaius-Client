#!/usr/bin/env python3
"""Fixture tests for portable artifact identity and publication safety."""

from __future__ import annotations

import contextlib
import gzip
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from verification_profiles import CANONICAL_ID, LEGACY_ID, RENDERPEARL_ID


SCRIPT = Path(__file__).with_name("build-portable-html.py")
SPEC = importlib.util.spec_from_file_location("build_portable_html", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
PORTABLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PORTABLE)

QUICK_CHECK_SCRIPT = Path(__file__).parent / "legacy" / "quick-check.py"
QUICK_CHECK_SPEC = importlib.util.spec_from_file_location(
    "quick_check", QUICK_CHECK_SCRIPT
)
assert QUICK_CHECK_SPEC is not None and QUICK_CHECK_SPEC.loader is not None
QUICK_CHECK = importlib.util.module_from_spec(QUICK_CHECK_SPEC)
QUICK_CHECK_SPEC.loader.exec_module(QUICK_CHECK)


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


def compressed(value: bytes) -> bytes:
    return gzip.compress(value, mtime=0)


def teavm_pom(main_class: str, target_directory: Path, target_file: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <build><plugins><plugin>
    <groupId>org.teavm</groupId>
    <artifactId>teavm-maven-plugin</artifactId>
    <executions><execution>
    <id>compile-fixture</id>
    <phase>package</phase>
    <goals><goal>compile</goal></goals>
    <configuration>
      <mainClass>{main_class}</mainClass>
      <targetDirectory>{target_directory}</targetDirectory>
      <targetFileName>{target_file}</targetFileName>
      <optimizationLevel>ADVANCED</optimizationLevel>
      <sourceMapsGenerated>false</sourceMapsGenerated>
      <debugInformationGenerated>false</debugInformationGenerated>
      <minifying>true</minifying>
      <shortFileNames>true</shortFileNames>
      <assertionsRemoved>true</assertionsRemoved>
    </configuration></execution></executions>
  </plugin></plugins></build>
</project>
"""


class PortableArtifactIdentityTest(unittest.TestCase):
    def setUp(self) -> None:
        self._fixture_environment = hermetic_gaius_environment()
        self._fixture_environment.__enter__()
        self.addCleanup(self._fixture_environment.__exit__, None, None, None)

    @staticmethod
    def write_compiler_profile(
        root: Path,
        role: str,
        artifact: Path,
        pom: Path,
        resources: list[Path],
    ) -> None:
        record = PORTABLE.compiler_profile.create_record(
            root,
            role,
            artifact,
            pom,
            resources,
            True,
        )
        PORTABLE.compiler_profile.write_atomically(
            PORTABLE.compiler_profile.default_output(artifact),
            PORTABLE.compiler_profile.canonical_json(record) + "\n",
        )

    def make_fixture(
        self,
        directory: str,
        *,
        version: str = CANONICAL_ID,
        distribution: str = "named",
        profile_file: str | None = None,
        profile_id: str | None = None,
        launcher_version: str | None = None,
        launcher_asset_index: str | None = None,
        classes: bytes | None = None,
        classes_gzip: bytes | None = None,
        boot: bool = False,
    ) -> tuple[Path, Path, Path]:
        root = Path(directory) / "project"
        dist = root / "port" / "web" / "dist"
        (root / "port" / "versions").mkdir(parents=True)
        dist.mkdir(parents=True)

        profile_file = profile_file or f"{version}.json"
        profile_id = profile_id or version
        launcher_version = launcher_version or version
        launcher_asset_index = launcher_asset_index or ("32" if version == CANONICAL_ID else "29")
        (root / "port" / "config.json").write_text(
            json.dumps(
                {
                    "versionProfile": f"versions/{profile_file}",
                    "teaVMVersion": "0.15.0",
                }
            ),
            encoding="utf-8",
        )
        (root / "port" / "versions" / profile_file).write_text(
            json.dumps(
                {
                    "id": profile_id,
                    "releaseType": "release",
                    "clientDistribution": distribution,
                    "protocolVersion": 776 if version == CANONICAL_ID else 774,
                    "worldVersion": 4903 if version == CANONICAL_ID else 4671,
                    "storage": {
                        "schema": 2,
                        "databaseName": f"gaius-fs-v2-{profile_id}",
                        "prefix": f"gaius.fs.v2:{profile_id}:",
                        "opfsDirectory": f"regions-v2-{profile_id}",
                    },
                    "official": {"assetIndexId": launcher_asset_index},
                }
            ),
            encoding="utf-8",
        )

        index = (
            "<!doctype html>\n"
            f'<script>const args = ["--version", "{launcher_version}", '
            f'"--assetIndex", "{launcher_asset_index}"];</script>\n'
            '</head>\n'
            f'<script data-gaius-storage-profile="v2">'
            f'window.__gaiusProfileId = "{profile_id}"; '
            f'window.__gaiusWorldVersion = {4903 if version == CANONICAL_ID else 4671}; '
            'window.__gaiusStorageSchema = 2; '
            f'window.__gaiusStorageDatabaseName = "gaius-fs-v2-{profile_id}"; '
            f'window.__gaiusStoragePrefix = "gaius.fs.v2:{profile_id}:"; '
            f'window.__gaiusStorageOpfsDirectory = "regions-v2-{profile_id}";</script>\n'
            '  <script>\n    if (typeof Error === "function") {}\n  </script>\n'
        )
        if boot:
            # The launcher's boot script tag and its source (the page runtime picks the client).
            index = index.replace("<!doctype html>\n", "<!doctype html>\n" + PORTABLE.BOOT_TAG, 1)
            boot_source = root / PORTABLE.BOOT_SOURCE
            boot_source.parent.mkdir(parents=True, exist_ok=True)
            # The Wasm portable page moves the boot source that selects the runtime.
            boot_source.write_text(
                "window.__gaiusBoot = {fixture: true};\nconst clientRuntime = selectRuntime();\n",
                encoding="utf-8",
            )
        (dist / "index.html").write_text(index, encoding="utf-8")

        classes = classes or (
            b'"use strict";'
            b"/*gaius-java-finite-long-cast*/"
            b"target-attestation;262-startup"
        )
        (dist / "classes.js").write_bytes(classes)
        (dist / "classes.js.gz").write_bytes(
            classes_gzip if classes_gzip is not None else compressed(classes)
        )
        server = b"/*gaius-integrated-server-input-coroutine*/server-startup"
        (dist / "singleplayer-server.js").write_bytes(server)
        (dist / "singleplayer-server.js.gz").write_bytes(compressed(server))
        (dist / "gaius-hotpath.wasm").write_bytes(b"wasm")
        (dist / "gaius-hotpath.wasm.gz").write_bytes(compressed(b"wasm"))
        (dist / "vanilla-assets.pack.gz").write_bytes(compressed(b"vanilla"))
        (dist / "singleplayer-server-worker.js").write_text(
            "self.onmessage = () => {};\n",
            encoding="utf-8",
        )
        (dist / "relay-nodes.json").write_text(
            json.dumps(
                {"kind": "gaius-relay-registry", "protocolVersion": 1, "nodes": []}
            ),
            encoding="utf-8",
        )
        # A portable page embeds the notice and license texts of the project root.
        (root / "LICENSES").mkdir()
        (root / "NOTICE").write_text("Fixture notice\n", encoding="utf-8")
        (root / "LICENSE").write_text("Fixture license\n", encoding="utf-8")
        (root / "LICENSES" / "Fixture.txt").write_text("Fixture terms\n", encoding="utf-8")
        source_bootstrap = root / "port" / "web" / "singleplayer" / "server-worker-bootstrap.js"
        source_bootstrap.parent.mkdir(parents=True)
        source_bootstrap.write_text("self.__gaiusBootstrap = true;\n", encoding="utf-8")
        source_publication_gate = root / "port" / "scripts" / "teavm-publication-gate.sh"
        source_publication_gate.parent.mkdir(parents=True, exist_ok=True)
        source_publication_gate.write_text(
            "gaius_teavm_publish_allowed() { return 0; }\n",
            encoding="utf-8",
        )
        source_launcher_template = root / "port" / "web" / "launcher" / "index.template.html"
        source_launcher_template.parent.mkdir(parents=True, exist_ok=True)
        source_launcher_template.write_text(
            "<!doctype html><title>Gaius fixture</title>\n",
            encoding="utf-8",
        )
        source_file = (
            root
            / "port"
            / "src"
            / "main"
            / "java"
            / "dev"
            / "gaius"
            / "browser"
            / "Fixture.java"
        )
        source_file.parent.mkdir(parents=True)
        source_file.write_text("final class Fixture {}\n", encoding="utf-8")
        work = root / "port" / "work"
        version_work = work / version
        overlays = work / "overlays"
        version_work.mkdir(parents=True)
        (version_work / "version.json").write_text(
            json.dumps(
                {
                    "id": version,
                    "assetIndex": {"id": launcher_asset_index},
                    "libraries": [
                        {
                            "name": "example:browser-library:1.0",
                            "downloads": {
                                "artifact": {"path": "example/browser-library-1.0.jar"}
                            },
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        asset_index = version_work / "assets" / "indexes" / f"{launcher_asset_index}.json"
        asset_index.parent.mkdir(parents=True)
        asset_index.write_text('{"objects":{}}\n', encoding="utf-8")
        generated_resources = root / "port" / "target" / "generated-resources"
        client_resources = [
            generated_resources / "dev/gaius/browser/minecraft-resources.txt",
            generated_resources / "dev/gaius/browser/minecraft-embedded-resources.txt",
            asset_index,
            generated_resources / "assets/minecraft/sounds.json",
            generated_resources / "assets/minecraft/font/include/unifont.json",
            generated_resources / "assets/minecraft/font/include/unifont_pua.json",
            dist / "vanilla-assets.pack.gz",
        ]
        for index, resource in enumerate(client_resources[:-1]):
            if resource == asset_index:
                continue
            resource.parent.mkdir(parents=True, exist_ok=True)
            resource.write_text(f"fixture-resource-{index}\n", encoding="utf-8")
        client_staging_pom = root / "port" / "target" / "generated-pom.xml"
        client_staging_pom.write_text(
            teavm_pom(
                "net.minecraft.client.main.Main",
                root / "port" / "target" / ".teavm-staging" / "client" / "dist",
                "classes.js",
            ),
            encoding="utf-8",
        )
        client_pom = root / "port" / "target" / "release-generated-pom.xml"
        client_pom.write_text(
            teavm_pom("net.minecraft.client.main.Main", dist, "classes.js"),
            encoding="utf-8",
        )
        worker_resources = (
            root
            / "port"
            / "target"
            / "server-worker"
            / "generated-resources/dev/gaius/browser/minecraft-resources.txt"
        )
        worker_resources.parent.mkdir(parents=True, exist_ok=True)
        worker_resources.write_text("fixture-worker-resources\n", encoding="utf-8")
        worker_staging_pom = (
            root / "port" / "target" / "server-worker" / "generated-pom.xml"
        )
        worker_staging_pom.write_text(
            teavm_pom(
                "dev.gaius.browser.BrowserIntegratedServerMain",
                root
                / "port"
                / "target"
                / "server-worker"
                / ".teavm-staging"
                / "dist",
                "singleplayer-server.js",
            ),
            encoding="utf-8",
        )
        worker_pom = (
            root / "port" / "target" / "server-worker" / "release-generated-pom.xml"
        )
        worker_pom.write_text(
            teavm_pom(
                "dev.gaius.browser.BrowserIntegratedServerMain",
                dist,
                "singleplayer-server.js",
            ),
            encoding="utf-8",
        )
        overlay_files = {
            overlays / f"client-named-{version}-gaius.jar": b"client-overlay",
            overlays / "teavm-classlib-0.15.0-gaius.jar": b"classlib-overlay",
            overlays / "teavm-core-0.15.0-gaius.jar": b"core-overlay",
            overlays / "libraries" / "example" / "browser-library-1.0.jar": b"library-overlay",
        }
        for overlay, value in overlay_files.items():
            overlay.parent.mkdir(parents=True, exist_ok=True)
            overlay.write_bytes(value)
        for artifact_name, role in (
            ("classes.js", "client"),
            ("singleplayer-server.js", "singleplayer-worker"),
            ("gaius-hotpath.wasm", "wasm-hotpath"),
            ("singleplayer-server-worker.js", "worker-bootstrap"),
            ("vanilla-assets.pack.gz", "vanilla-assets"),
            ("relay-nodes.json", "relay-registry"),
        ):
            PORTABLE.build_identity.write_sidecar(
                root,
                role,
                dist / artifact_name,
            )
        for role, artifact, pom, resources in (
            ("client", dist / "classes.js", client_pom, client_resources),
            (
                "singleplayer-worker",
                dist / "singleplayer-server.js",
                worker_pom,
                [worker_resources],
            ),
        ):
            self.write_compiler_profile(root, role, artifact, pom, resources)
        return root, dist, dist / "Gaius.html"

    # A 26.3-style fixture: the client calls the WebAssembly shader toolchain
    # and the page carries the loader tag of postprocess-index-html.py.
    SHADER_TOOLCHAIN_CLASSES = (
        b'"use strict";'
        b"/*gaius-java-finite-long-cast*/"
        b"target-attestation;263-startup;"
        b"window.__gaiusShaderToolchain.shadercBegin()"
    )

    def add_shader_toolchain(self, root: Path, dist: Path) -> str:
        """Publish fixture toolchain files, their manifest, sidecar and loader tag."""
        files = {
            "gaius-shader-toolchain.js": (
                "(function (root) { root.__gaiusShaderToolchainReady = "
                "Promise.resolve(root.__gaiusPortableAssetsReady).then(function () {"
                " return root.__gaiusShaderToolchainUrls; }); })(globalThis);\n"
            ).encode("ascii"),
            "gaius-shaderc.js": b"var GaiusShadercModule = () => {};\n",
            "gaius-shaderc.wasm": b"\0asm\1\0\0\0shaderc",
            "gaius-spvc.js": b"var GaiusSpvcModule = () => {};\n",
            "gaius-spvc.wasm": b"\0asm\1\0\0\0spvc",
        }
        for name, value in files.items():
            (dist / name).write_bytes(value)
        (dist / "gaius-shader-toolchain.json").write_text(
            json.dumps(
                {
                    "schema": 1,
                    "emscripten": "6.0.10 (fixture)",
                    "sources": {"shaderc": "fixture", "spirv-cross": "fixture"},
                    "files": {
                        name: {
                            "bytes": len(value),
                            "sha256": hashlib.sha256(value).hexdigest(),
                        }
                        for name, value in files.items()
                    },
                }
            ),
            encoding="utf-8",
        )
        PORTABLE.build_identity.write_sidecar(
            root, "shader-toolchain", dist / "gaius-shader-toolchain.json"
        )
        token = PORTABLE.shader_toolchain_token(dist)
        index = dist / "index.html"
        tag = (
            f'  <script data-gaius-shader-toolchain data-profile="{RENDERPEARL_ID}" '
            f'src="gaius-shader-toolchain.js?v={token}"></script>\n'
        )
        index.write_text(
            index.read_text(encoding="utf-8").replace("<!doctype html>\n", "<!doctype html>\n" + tag, 1),
            encoding="utf-8",
        )
        return token

    def make_shader_toolchain_fixture(self, directory: str) -> tuple[Path, Path, Path, str]:
        root, dist, output = self.make_fixture(
            directory,
            version=RENDERPEARL_ID,
            launcher_asset_index="34",
            classes=self.SHADER_TOOLCHAIN_CLASSES,
        )
        token = self.add_shader_toolchain(root, dist)
        return root, dist, output, token

    @staticmethod
    def run_build(root: Path, dist: Path, output: Path) -> None:
        with contextlib.redirect_stdout(io.StringIO()):
            PORTABLE.build(dist, output, root=root)

    @staticmethod
    def embedded_manifest(html: Path) -> dict:
        marker = "const portableManifest = "
        text = html.read_text(encoding="utf-8")
        start = text.index(marker) + len(marker)
        end = text.index(";", start)
        return json.loads(text[start:end])

    @staticmethod
    def quick_check_identity(root: Path, dist: Path, output: Path) -> bool:
        with mock.patch.object(QUICK_CHECK, "PORT", root / "port"), \
                mock.patch.object(QUICK_CHECK, "VERSION_CONFIG", root / "port" / "config.json"), \
                mock.patch.object(QUICK_CHECK, "DIST", dist), \
                mock.patch.object(QUICK_CHECK, "PORTABLE_HTML", output), \
                mock.patch.object(QUICK_CHECK, "PORTABLE_MANIFEST", dist / "Gaius.manifest.json"), \
                mock.patch.object(QUICK_CHECK, "INDEX_HTML", dist / "index.html"), \
                mock.patch.object(
                    QUICK_CHECK,
                    "BUILD_IDENTITY_SCHEMA_VERSION",
                    PORTABLE.build_identity.IDENTITY_SCHEMA_VERSION,
                ), \
                mock.patch.object(
                    QUICK_CHECK,
                    "current_build_identity_for_quick_check",
                    side_effect=lambda *_args, **_kwargs: PORTABLE.build_identity.current_build_identity(root),
                ):
            return QUICK_CHECK.portable_artifact_identity_matches()

    @staticmethod
    def legacy_overlay_identity(root: Path, paths: list[Path]) -> dict[str, object]:
        """Hash overlay paths exactly as the pre-external-root implementation did."""
        digest = hashlib.sha256()
        policy = PORTABLE.build_identity.OVERLAY_POLICY
        digest.update(policy.encode("ascii") + b"\0")
        total_bytes = 0
        ordered = sorted(paths, key=lambda path: path.relative_to(root).as_posix())
        for path in ordered:
            relative = path.relative_to(root).as_posix()
            size = path.stat().st_size
            digest.update(relative.encode("utf-8"))
            digest.update(b"\0")
            digest.update(str(size).encode("ascii"))
            digest.update(b"\0")
            digest.update(PORTABLE.build_identity.sha256_file(path).encode("ascii"))
            digest.update(b"\n")
            total_bytes += size
        return {
            "policy": policy,
            "sha256": digest.hexdigest(),
            "fileCount": len(ordered),
            "bytes": total_bytes,
        }

    @staticmethod
    def msys_path(path: Path) -> str:
        value = str(path.resolve()).replace("\\", "/")
        if len(value) < 3 or value[1] != ":":
            raise AssertionError(f"expected a Windows drive path, got {value}")
        return f"/{value[0].lower()}{value[2:]}"

    def test_correct_26_2_publishes_and_embeds_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)

            self.run_build(root, dist, output)

            manifest_path = dist / "Gaius.manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(manifest["profile"], CANONICAL_ID)
            self.assertEqual(manifest["worldVersion"], 4903)
            self.assertEqual(
                manifest["storage"],
                {
                    "schema": 2,
                    "databaseName": ("gaius-fs-v2-" + CANONICAL_ID + ""),
                    "prefix": ("gaius.fs.v2:" + CANONICAL_ID + ":"),
                    "opfsDirectory": ("regions-v2-" + CANONICAL_ID + ""),
                },
            )
            self.assertEqual(manifest["buildIdentity"]["worldVersion"], 4903)
            self.assertEqual(manifest["buildIdentity"]["storage"], manifest["storage"])
            self.assertEqual(
                manifest["buildIdentity"]["compatibilitySha256"],
                manifest["classesJs"]["build"]["compatibilitySha256"],
            )
            self.assertEqual(
                manifest["classesJs"]["rawSha256"],
                PORTABLE.sha256_file(dist / "classes.js"),
            )
            self.assertEqual(
                manifest["classesJs"]["gzipSha256"],
                PORTABLE.sha256_file(dist / "classes.js.gz"),
            )
            self.assertEqual(
                manifest["singleplayerServerJs"]["rawSha256"],
                PORTABLE.sha256_file(dist / "singleplayer-server.js"),
            )
            self.assertEqual(
                manifest["wasmHotpath"]["gzipSha256"],
                PORTABLE.sha256_file(dist / "gaius-hotpath.wasm.gz"),
            )
            self.assertEqual(self.embedded_manifest(output), manifest)
            self.assertTrue(self.quick_check_identity(root, dist, output))
            self.assertEqual(
                gzip.decompress((dist / "classes.js.gz").read_bytes()),
                (dist / "classes.js").read_bytes(),
            )
            self.assertEqual(
                {signature["name"] for signature in manifest["signatures"]},
                {
                    "client-finite-long-patch",
                    "client-target-attestation",
                    "server-input-pump",
                },
            )

    def test_overlay_identity_is_location_stable_across_scope_and_path_styles(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, _dist, _output = self.make_fixture(directory)
            self.assertEqual(
                PORTABLE.build_identity.IDENTITY_SCHEMA_VERSION,
                QUICK_CHECK.BUILD_IDENTITY_SCHEMA_VERSION,
            )
            self.assertEqual(
                PORTABLE.build_identity.IDENTITY_SCHEMA_VERSION,
                2,
            )
            self.assertEqual(
                PORTABLE.build_identity.OVERLAY_POLICY,
                QUICK_CHECK.BUILD_IDENTITY_OVERLAY_POLICY,
            )
            self.assertEqual(
                PORTABLE.build_identity.OVERLAY_POLICY,
                "gaius-active-overlay-inputs-v1",
            )
            shared_overlays = root / "port" / "work" / "overlays"
            isolated_overlays = root / "port" / "work" / "overlays" / CANONICAL_ID
            shutil.copytree(shared_overlays, isolated_overlays)
            external_shared_overlays = Path(directory) / "external-shared-overlays"
            external_isolated_overlays = Path(directory) / "external-isolated-overlays"
            shutil.copytree(shared_overlays, external_shared_overlays)
            shutil.copytree(isolated_overlays, external_isolated_overlays)
            client_version = root / "port" / "work" / CANONICAL_ID / "client-version.json"
            client_version.write_bytes((b'{"id":"' + CANONICAL_ID.encode("ascii") + b'"}\n'))
            profile_path = root / "port" / "versions" / ("" + CANONICAL_ID + ".json")
            profile = json.loads(profile_path.read_text(encoding="utf-8"))
            base_environment = {
                "GAIUS_VERSION_PROFILE_PATH": ("versions/" + CANONICAL_ID + ".json"),
                "GAIUS_BUILD_ROOT": str(root / "port" / "target" / CANONICAL_ID),
            }

            cases: list[tuple[str, dict[str, str], Path, str]] = [
                ("shared-internal", {}, shared_overlays, "shared"),
                (
                    "shared-external",
                    {"GAIUS_OVERLAY_DIRECTORY": str(external_shared_overlays)},
                    external_shared_overlays,
                    "shared",
                ),
                (
                    "isolated-internal",
                    {
                        **base_environment,
                        "GAIUS_OVERLAY_DIRECTORY": str(isolated_overlays),
                    },
                    isolated_overlays,
                    "isolated",
                ),
                (
                    "isolated-external",
                    {
                        **base_environment,
                        "GAIUS_OVERLAY_DIRECTORY": str(external_isolated_overlays),
                    },
                    external_isolated_overlays,
                    "isolated",
                ),
            ]
            if os.name == "nt":
                cases.append(
                    (
                        "isolated-external-msys",
                        {
                            **base_environment,
                            "GAIUS_OVERLAY_DIRECTORY": self.msys_path(
                                external_isolated_overlays
                            ),
                        },
                        external_isolated_overlays,
                        "isolated",
                    )
                )

            identities: dict[str, dict[str, object]] = {}
            for label, environment, overlay_root, scope in cases:
                with self.subTest(case=label), mock.patch.dict(
                    os.environ, environment, clear=False
                ), mock.patch.object(QUICK_CHECK, "PORT", root / "port"):
                    identity = PORTABLE.build_identity.current_build_identity(root)
                    quick_check = QUICK_CHECK.current_build_identity_for_quick_check(
                        profile,
                        ("versions/" + CANONICAL_ID + ".json"),
                        profile_path,
                    )
                    self.assertIsNotNone(quick_check)
                    assert quick_check is not None
                    identities[label] = identity

                    overlay_inputs = PORTABLE.build_identity._overlay_inputs(root, profile)
                    self.assertEqual(identity["overlay"]["fileCount"], len(overlay_inputs))
                    if overlay_root.is_relative_to(root):
                        self.assertEqual(
                            identity["overlay"],
                            self.legacy_overlay_identity(
                                root, [path for _name, path in overlay_inputs]
                            ),
                        )
                    if scope == "isolated":
                        self.assertTrue(
                            all(
                                name == ("port/work/" + CANONICAL_ID + "/version.json")
                                or name == ("port/work/" + CANONICAL_ID + "/client-version.json")
                                or name.startswith(("port/work/overlays/" + CANONICAL_ID + "/"))
                                for name, _path in overlay_inputs
                            )
                        )
                    else:
                        self.assertTrue(
                            all(
                                name == ("port/work/" + CANONICAL_ID + "/version.json")
                                or name == ("port/work/" + CANONICAL_ID + "/client-version.json")
                                or name.startswith("port/work/overlays/")
                                for name, _path in overlay_inputs
                            )
                        )
                    self.assertEqual(quick_check["overlay"], identity["overlay"])
                    self.assertEqual(
                        quick_check["compatibilitySha256"],
                        identity["compatibilitySha256"],
                    )

            self.assertEqual(
                identities["shared-external"]["overlay"],
                identities["shared-internal"]["overlay"],
            )
            self.assertEqual(
                identities["isolated-external"]["overlay"],
                identities["isolated-internal"]["overlay"],
            )
            self.assertEqual(
                identities["isolated-external"]["compatibilitySha256"],
                identities["isolated-internal"]["compatibilitySha256"],
            )
            if os.name == "nt":
                self.assertEqual(
                    identities["isolated-external-msys"]["overlay"],
                    identities["isolated-internal"]["overlay"],
                )

            with mock.patch.dict(
                os.environ,
                {
                    **base_environment,
                    "GAIUS_OVERLAY_DIRECTORY": str(root / "port" / "work"),
                },
                clear=False,
            ), mock.patch.object(QUICK_CHECK, "PORT", root / "port"):
                ancestor_inputs = dict(
                    PORTABLE.build_identity._overlay_inputs(root, profile)
                )
                ancestor_quick_inputs = dict(
                    QUICK_CHECK.build_identity_overlay_inputs(root, profile)
                )
            self.assertIn(("port/work/" + CANONICAL_ID + "/version.json"), ancestor_inputs)
            self.assertIn(("port/work/" + CANONICAL_ID + "/client-version.json"), ancestor_inputs)
            self.assertNotIn(("port/work/overlays/" + CANONICAL_ID + "/version.json"), ancestor_inputs)
            self.assertNotIn(
                ("port/work/overlays/" + CANONICAL_ID + "/client-version.json"), ancestor_inputs
            )
            self.assertEqual(ancestor_quick_inputs.keys(), ancestor_inputs.keys())
            self.assertIn(("port/work/" + CANONICAL_ID + "/version.json"), ancestor_quick_inputs)
            self.assertIn(("port/work/" + CANONICAL_ID + "/client-version.json"), ancestor_quick_inputs)

    def test_client_compiler_profile_rejects_wrong_target_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            pom = root / "port" / "target" / "release-generated-pom.xml"
            pom.write_text(
                pom.read_text(encoding="utf-8").replace(
                    "<targetFileName>classes.js</targetFileName>",
                    "<targetFileName>other.js</targetFileName>",
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "targetFileName"):
                self.run_build(root, dist, output)

    def test_client_compiler_profile_rejects_wrong_main_class(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            pom = root / "port" / "target" / "release-generated-pom.xml"
            pom.write_text(
                pom.read_text(encoding="utf-8").replace(
                    "<mainClass>net.minecraft.client.main.Main</mainClass>",
                    "<mainClass>example.WrongMain</mainClass>",
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "requires mainClass"):
                self.run_build(root, dist, output)

    def test_old_gzip_with_new_js_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            old_classes = (b"old-" + LEGACY_ID.encode("ascii") + b"-client")
            root, dist, output = self.make_fixture(
                directory,
                classes=(b"new-" + CANONICAL_ID.encode("ascii") + b"-client"),
                classes_gzip=compressed(old_classes),
            )
            output.write_text("previous portable release", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "does not expand"):
                self.run_build(root, dist, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "previous portable release")

    def test_quick_check_rejects_mixed_versions_independently(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            self.run_build(root, dist, output)
            (dist / "classes.js.gz").write_bytes(compressed((b"old-" + LEGACY_ID.encode("ascii") + b"-client")))

            self.assertFalse(self.quick_check_identity(root, dist, output))

    def test_old_server_gzip_is_rejected_before_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            (dist / "singleplayer-server.js.gz").write_bytes(
                compressed(b"old-singleplayer-server")
            )
            output.write_text("previous portable release", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "does not expand"):
                self.run_build(root, dist, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "previous portable release")

    def test_old_wasm_gzip_is_rejected_before_publication(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            (dist / "gaius-hotpath.wasm.gz").write_bytes(compressed(b"old-wasm"))
            output.write_text("previous portable release", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "does not expand"):
                self.run_build(root, dist, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "previous portable release")

    def test_quick_check_rejects_changed_embedded_components(self) -> None:
        for artifact_name in (
            "classes.js",
            "singleplayer-server.js",
            "gaius-hotpath.wasm",
            "singleplayer-server-worker.js",
            "vanilla-assets.pack.gz",
            "relay-nodes.json",
        ):
            with self.subTest(artifact=artifact_name), tempfile.TemporaryDirectory() as directory:
                root, dist, output = self.make_fixture(directory)
                self.run_build(root, dist, output)

                artifact = dist / artifact_name
                artifact.write_bytes(artifact.read_bytes() + b"changed")
                self.assertFalse(self.quick_check_identity(root, dist, output))

    def test_stale_worker_identity_is_rejected_even_when_gzip_matches(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            server = b"/*gaius-integrated-server-input-coroutine*/new-server"
            (dist / "singleplayer-server.js").write_bytes(server)
            (dist / "singleplayer-server.js.gz").write_bytes(compressed(server))

            with self.assertRaisesRegex(RuntimeError, "artifact hash does not match"):
                self.run_build(root, dist, output)

    def test_missing_worker_identity_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            PORTABLE.build_identity.sidecar_path(
                dist / "singleplayer-server.js"
            ).unlink()

            with self.assertRaisesRegex(RuntimeError, "sidecar is missing"):
                self.run_build(root, dist, output)

    def test_source_change_rejects_all_previously_built_components(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            source = (
                root
                / "port"
                / "src"
                / "main"
                / "java"
                / "dev"
                / "gaius"
                / "browser"
                / "Fixture.java"
            )
            source.write_text("final class Fixture { int changed; }\n", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "source does not match"):
                self.run_build(root, dist, output)

    def test_teavm_publication_gate_change_rejects_previous_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            gate = root / "port" / "scripts" / "teavm-publication-gate.sh"
            gate.write_text(
                "gaius_teavm_publish_allowed() { return 1; }\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "source does not match"):
                self.run_build(root, dist, output)

    def test_launcher_template_change_rejects_previous_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            template = root / "port" / "web" / "launcher" / "index.template.html"
            template.write_text(
                "<!doctype html><title>Changed Gaius fixture</title>\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "source does not match"):
                self.run_build(root, dist, output)

    def test_overlay_change_rejects_all_previously_built_components(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            overlay = (
                root
                / "port"
                / "work"
                / "overlays"
                / "libraries"
                / "example"
                / "browser-library-1.0.jar"
            )
            overlay.write_bytes(b"changed-overlay")

            with self.assertRaisesRegex(RuntimeError, "overlay does not match"):
                self.run_build(root, dist, output)

    def test_wrong_profile_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(
                directory,
                profile_file=("" + CANONICAL_ID + ".json"),
                profile_id=LEGACY_ID,
                launcher_version=CANONICAL_ID,
                launcher_asset_index="32",
            )

            with self.assertRaisesRegex(RuntimeError, "launcher version"):
                self.run_build(root, dist, output)
            self.assertFalse(output.exists())

    def test_truncated_gzip_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            classes = (
                b'"use strict";'
                b"/*gaius-java-finite-long-cast*/target-attestation;262-startup"
            )
            root, dist, output = self.make_fixture(
                directory,
                classes=classes,
                classes_gzip=compressed(classes)[:-8],
            )
            output.write_text("previous portable release", encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "complete gzip"):
                self.run_build(root, dist, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "previous portable release")

    def test_build_failure_does_not_overwrite_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            output.write_text("previous portable release", encoding="utf-8")
            original_replace = PORTABLE.os.replace

            def fail_for_html(source: str, target: str) -> None:
                if Path(target).name == output.name:
                    raise OSError("simulated publication failure")
                original_replace(source, target)

            with mock.patch.object(PORTABLE.os, "replace", side_effect=fail_for_html):
                with self.assertRaisesRegex(OSError, "publication failure"):
                    self.run_build(root, dist, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "previous portable release")
            self.assertFalse((dist / "Gaius.manifest.json").exists())
            self.assertEqual(list(dist.glob(f".{output.name}.*.tmp")), [])

    def test_manifest_replace_failure_rolls_back_html_and_commit_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            self.run_build(root, dist, output)
            manifest_path = dist / "Gaius.manifest.json"
            previous_html = output.read_bytes()
            previous_manifest = manifest_path.read_bytes()

            classes = (dist / "classes.js").read_bytes() + b";next-release"
            (dist / "classes.js").write_bytes(classes)
            (dist / "classes.js.gz").write_bytes(compressed(classes))
            PORTABLE.build_identity.write_sidecar(
                root,
                "client",
                dist / "classes.js",
            )
            version = PORTABLE.load_version_profile(root)[0]["id"]
            metadata = json.loads(
                (root / "port" / "work" / version / "version.json").read_text(
                    encoding="utf-8"
                )
            )
            asset_index_id = metadata["assetIndex"]["id"]
            generated_resources = root / "port" / "target" / "generated-resources"
            self.write_compiler_profile(
                root,
                "client",
                dist / "classes.js",
                root / "port" / "target" / "release-generated-pom.xml",
                [
                    generated_resources / "dev/gaius/browser/minecraft-resources.txt",
                    generated_resources
                    / "dev/gaius/browser/minecraft-embedded-resources.txt",
                    root
                    / "port"
                    / "work"
                    / version
                    / "assets"
                    / "indexes"
                    / f"{asset_index_id}.json",
                    generated_resources / "assets/minecraft/sounds.json",
                    generated_resources
                    / "assets/minecraft/font/include/unifont.json",
                    generated_resources
                    / "assets/minecraft/font/include/unifont_pua.json",
                    dist / "vanilla-assets.pack.gz",
                ],
            )
            original_replace = PORTABLE.os.replace
            failed = False

            def fail_commit_marker_once(source: str, target: str) -> None:
                nonlocal failed
                if Path(target).resolve() == manifest_path.resolve() and not failed:
                    failed = True
                    raise OSError("simulated commit marker failure")
                original_replace(source, target)

            with mock.patch.object(PORTABLE.os, "replace", side_effect=fail_commit_marker_once):
                with self.assertRaisesRegex(OSError, "commit marker failure"):
                    self.run_build(root, dist, output)

            self.assertEqual(output.read_bytes(), previous_html)
            self.assertEqual(manifest_path.read_bytes(), previous_manifest)
            self.assertEqual(self.embedded_manifest(output), json.loads(previous_manifest))

    def test_manifest_is_replaced_after_html_as_commit_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            manifest_path = dist / "Gaius.manifest.json"
            original_replace = PORTABLE.os.replace
            replacements: list[Path] = []

            def record_replace(source: str, target: str) -> None:
                replacements.append(Path(target))
                original_replace(source, target)

            with mock.patch.object(PORTABLE.os, "replace", side_effect=record_replace):
                self.run_build(root, dist, output)

            normalized = [path.resolve() for path in replacements]
            self.assertLess(
                normalized.index(output.resolve()),
                normalized.index(manifest_path.resolve()),
            )
            self.assertEqual(self.embedded_manifest(output), json.loads(manifest_path.read_bytes()))

    def test_1_21_11_legacy_profile_remains_compatible(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(
                directory,
                version=LEGACY_ID,
                distribution="obfuscated-with-mappings",
                classes=(b"legacy-" + LEGACY_ID.encode("ascii") + b"-client"),
            )

            self.run_build(root, dist, output)

            manifest = json.loads(
                (dist / "Gaius.manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual(manifest["profile"], LEGACY_ID)
            self.assertEqual(manifest["signatures"], [])

    @staticmethod
    def embedded_assets(html: Path) -> dict:
        """Every embedded payload asset, decoded, in document order."""
        return PORTABLE.parse_portable_payload(html.read_text(encoding="utf-8"))

    def test_26_2_page_gets_no_shader_toolchain(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            self.run_build(root, dist, output)

            manifest = json.loads((dist / "Gaius.manifest.json").read_text(encoding="utf-8"))
            self.assertNotIn("shaderToolchain", manifest)
            embedded = self.embedded_assets(output)
            # Decode-priority order: the client and its wasm gate the boot, the server comes last.
            self.assertEqual(list(embedded), ["classes", "wasm", "vanilla", "server"])
            # Each asset is compressed once and embedded once (no base64 inside gzip inside text).
            self.assertEqual(embedded["classes"], (dist / "classes.js.gz").read_bytes())
            self.assertEqual(embedded["server"], (dist / "singleplayer-server.js.gz").read_bytes())
            self.assertEqual(embedded["vanilla"], (dist / "vanilla-assets.pack.gz").read_bytes())
            html_text = output.read_text(encoding="utf-8")
            self.assertNotIn("const embedded = ", html_text)
            self.assertIn('const portablePayload = {"assets":', html_text)
            manifest = json.loads((dist / "Gaius.manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["payloadEncoding"], "gaius-b64-v1")
            html = output.read_text(encoding="utf-8")
            self.assertNotIn("__gaiusShaderToolchain", html)
            self.assertNotIn("data-gaius-shader-toolchain", html)

    def test_release_artifact_contract_reads_the_portable_payload(self) -> None:
        """build-teavm-release.sh gates every release on this smoke; it must parse the page we emit."""
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is not installed")
        smoke = Path(__file__).resolve().parents[2] / "apps" / "bridge" / "browser-full-path-artifact-contract-smoke.mjs"
        markers = (b"__gaiusClientPacketDrainEnabled;clientPacketDrainSession;"
                   b"clientPacketDrainDemandToken;clientPacketDrainDisabledRequests;")
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(
                directory,
                classes=b'"use strict";/*gaius-java-finite-long-cast*/target-attestation;262-startup;' + markers,
            )
            self.run_build(root, dist, output)
            self.assertNotIn(b"\r", output.read_bytes(), "the portable page is written with LF on every host")
            env = {key: value for key, value in os.environ.items() if not key.startswith("GAIUS_")}
            env.update({"GAIUS_VERSION_PROFILE_PATH": ("versions/" + CANONICAL_ID + ".json"), "GAIUS_DIST_DIRECTORY": str(dist)})
            for label in ("lf", "crlf"):
                if label == "crlf":
                    # A page that went through a CRLF checkout or editor still passes the gate.
                    output.write_bytes(output.read_bytes().replace(b"\n", b"\r\n"))
                result = subprocess.run([node, str(smoke)], env=env, text=True, capture_output=True,
                                        timeout=120, check=False)
                self.assertEqual(result.returncode, 0, f"{label}: {result.stderr}")
                report = json.loads(result.stdout)
                self.assertEqual(report["status"], "pass", label)
                self.assertEqual(report["artifact"]["embeddedClassesBytes"],
                                 len((dist / "classes.js").read_bytes()), label)

    def test_portable_bootstrap_unpacks_every_asset(self) -> None:
        """Runs the generated bootstrap in node with a fake DOM (main-thread decoder path)."""
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is not installed")
        script = r"""
const fs = require("fs");
const crypto = require("crypto");
const vm = require("vm");
const html = fs.readFileSync(process.argv[2], "utf8");
const parts = new Map();
for (const match of html.matchAll(/<script type="text\/x-gaius-b64" data-gaius-asset="([A-Za-z0-9_-]+)" data-part="(\d+)">([^<]*)<\/script>/g)) {
  if (!parts.has(match[1])) parts.set(match[1], []);
  parts.get(match[1]).push({textContent: match[3], parentNode: {removeChild() {}}});
}
const start = html.indexOf('<script data-gaius-portable="1">') + '<script data-gaius-portable="1">'.length;
const bootstrap = html.slice(start, html.indexOf("</script>", start));
const blobs = new Map();
const window = {
  document: {querySelectorAll: (selector) => parts.get(/data-gaius-asset="([^"]+)"/.exec(selector)[1]) || []},
  Blob, Response, DecompressionStream, Uint8Array, ArrayBuffer, Promise, Map, Object, Array, JSON, String, Math, Error, Date,
  setTimeout, setInterval: () => 0,
  URL: {createObjectURL: (blob) => { const id = "blob:" + blobs.size; blobs.set(id, blob); return id; }},
  atob: (text) => Buffer.from(text, "base64").toString("binary"), crypto: crypto.webcrypto,
};
window.window = window;
const context = vm.createContext(window);
vm.runInContext(bootstrap, context);
const sha = (bytes) => crypto.createHash("sha256").update(Buffer.from(bytes)).digest("hex");
(async () => {
  await context.__gaiusPortableAssetsReady;
  const classes = await blobs.get(context.__gaiusClassesUrl).arrayBuffer();
  const vanilla = await context.__gaiusVanillaAssetsCompressedPromise;
  const server = await context.__gaiusSingleplayerServerGzipDataPromise;
  const removed = [...parts.values()].every((list) => list.every((element) => element.textContent === ""));
  process.stdout.write(JSON.stringify({classes: sha(classes), vanilla: sha(vanilla), server: sha(server),
    classesType: blobs.get(context.__gaiusClassesUrl).type, removed, portable: context.__gaiusPortableBuild}));
})().catch((error) => { console.error(error); process.exit(1); });
"""
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory)
            self.run_build(root, dist, output)
            script_path = Path(directory) / "bootstrap-check.js"
            script_path.write_text(script, encoding="utf-8")
            result = subprocess.run([node, str(script_path), str(output)], text=True,
                                    capture_output=True, timeout=60, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(result.stdout)
            sha = lambda data: hashlib.sha256(data).hexdigest()  # noqa: E731
            self.assertEqual(report["classes"], sha((dist / "classes.js").read_bytes()))
            self.assertEqual(report["vanilla"], sha(gzip.decompress((dist / "vanilla-assets.pack.gz").read_bytes())))
            self.assertEqual(report["server"], sha((dist / "singleplayer-server.js.gz").read_bytes()))
            self.assertEqual(report["classesType"], "text/javascript")
            self.assertTrue(report["removed"], "every payload part leaves the DOM once decoded")
            self.assertTrue(report["portable"])

    WASM_CLIENT = b"\0asm\1\0\0\0fixture-client-module"
    WASM_SCRIPTS = {
        "gaius-wasm-gc-runtime.js": "globalThis.TeaVM = {wasmGC: {}};\n",
        "gaius-jspi.js": "globalThis.GaiusJspi = {};\n",
        "gaius-wasm-boot.js": "window.main = function () {};\n",
    }

    def make_wasm_dist(self, directory: str) -> Path:
        wasm_dist = Path(directory) / "wasm-dist"
        wasm_dist.mkdir()
        (wasm_dist / "classes.wasm").write_bytes(self.WASM_CLIENT)
        for name, source in self.WASM_SCRIPTS.items():
            (wasm_dist / name).write_text(source, encoding="utf-8")
        return wasm_dist

    def test_wasm_client_is_embedded_beside_the_javascript_client(self) -> None:
        """--wasm-dist: one more payload, its runtime scripts as inert text, the manifest entry."""
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory, boot=True)
            wasm_dist = self.make_wasm_dist(directory)
            with contextlib.redirect_stdout(io.StringIO()):
                PORTABLE.build(dist, output, root=root, wasm_dist=wasm_dist)
            html = output.read_text(encoding="utf-8")
            decoded = PORTABLE.parse_portable_payload(html)
            self.assertEqual(gzip.decompress(decoded[PORTABLE.WASM_CLIENT_ASSET]), self.WASM_CLIENT)
            self.assertEqual(gzip.decompress(decoded["classes"]), (dist / "classes.js").read_bytes())
            manifest = self.embedded_manifest(output)
            self.assertEqual(manifest["wasmClient"]["rawSha256"], hashlib.sha256(self.WASM_CLIENT).hexdigest())
            self.assertEqual(manifest["wasmClient"]["rawBytes"], len(self.WASM_CLIENT))
            self.assertEqual(sorted(manifest["wasmClient"]["scripts"]), sorted(self.WASM_SCRIPTS))
            self.assertEqual(json.loads((dist / "Gaius.manifest.json").read_text(encoding="utf-8")), manifest)
            # The boot script reads the announcement before it picks the runtime.
            announce = html.index('window.__gaiusPortableRuntimes = {"wasm":{"client":"classes.wasm"}};')
            self.assertLess(announce, html.index("window.__gaiusBoot = {fixture: true};"))
            for name, source in self.WASM_SCRIPTS.items():
                self.assertIn(f'<script type="text/plain" data-gaius-wasm-script="{name}">\n{source}  </script>\n', html)
            self.assertIn("portableWasmClient ? Promise.resolve(null) : decodeAsset(", html)
            self.assertIn(
                "Object.defineProperty(window, name, {configurable: true, enumerable: true, writable: true, value: promise});",
                SCRIPT.read_text(encoding="utf-8"),
                "portable lazy runtime values must remain assignable to the Wasm bootstrap",
            )

            # Without --wasm-dist the page is the JavaScript one.
            plain_output = dist / "Gaius-js.html"
            with contextlib.redirect_stdout(io.StringIO()):
                PORTABLE.build(dist, plain_output, root=root)
            plain_html = plain_output.read_text(encoding="utf-8")
            for marker in ("__gaiusPortableRuntimes", "data-gaius-wasm-script", PORTABLE.WASM_CLIENT_ASSET,
                           "portableWasmClient"):
                self.assertNotIn(marker, plain_html)
            self.assertNotIn("wasmClient", self.embedded_manifest(plain_output))

    def test_portable_bootstrap_starts_the_selected_client(self) -> None:
        """Runs the portable bootstrap and verifies that WasmGC is its only client runtime."""
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is not installed")
        script = r"""
const fs = require("fs");
const crypto = require("crypto");
const vm = require("vm");
const html = fs.readFileSync(process.argv[2], "utf8");
const selected = process.argv[3];
const parts = new Map();
for (const match of html.matchAll(/<script type="text\/x-gaius-b64" data-gaius-asset="([A-Za-z0-9_-]+)" data-part="(\d+)">([^<]*)<\/script>/g)) {
  if (!parts.has(match[1])) parts.set(match[1], []);
  parts.get(match[1]).push({textContent: match[3], parentNode: {removeChild() {}}});
}
const inert = new Map();
for (const match of html.matchAll(/<script type="text\/plain" data-gaius-wasm-script="([^"]+)">\n([^<]*)  <\/script>/g)) {
  inert.set(match[1], {textContent: match[2]});
}
const start = html.indexOf('<script data-gaius-portable="1">') + '<script data-gaius-portable="1">'.length;
const bootstrap = html.slice(start, html.indexOf("</script>", start));
const blobs = new Map();
const window = {
  document: {
    querySelectorAll: (selector) => parts.get(/data-gaius-asset="([^"]+)"/.exec(selector)[1]) || [],
    querySelector: (selector) => inert.get(/data-gaius-wasm-script="([^"]+)"/.exec(selector)[1]) || null,
  },
  Blob, Response, DecompressionStream, Uint8Array, ArrayBuffer, Promise, Map, Object, Array, JSON, String, Math, Error, Date,
  setTimeout, setInterval: () => 0,
  URL: {createObjectURL: (blob) => { const id = "blob:" + blobs.size; blobs.set(id, blob); return id; }},
  atob: (text) => Buffer.from(text, "base64").toString("binary"), crypto: crypto.webcrypto,
  // The WasmGC release runtime is fixed before the portable bootstrap runs.
  __gaiusPortableRuntimes: {wasm: {client: "classes.wasm"}},
  __gaiusRuntimeSelection: {selected},
};
window.window = window;
const context = vm.createContext(window);
vm.runInContext(bootstrap, context);
const sha = (bytes) => crypto.createHash("sha256").update(Buffer.from(bytes)).digest("hex");
(async () => {
  await context.__gaiusPortableAssetsReady;
  const report = {selected};
  report.client = await blobs.get(context.__gaiusClassesUrl).text();
  report.jsClientLoader = typeof context.__gaiusPortableLoadJsClient;
  report.wasmClientRemoved = (parts.get("wasmclient") || []).every((element) => element.textContent === "");
  report.classesUntouched = (parts.get("classes") || []).every((element) => element.textContent !== "");
  if (selected === "wasm") {
    report.module = sha(await context.__gaiusWasmSource);
    report.runtime = await blobs.get(context.__gaiusWasmRuntimeUrl).text();
    report.jspi = await blobs.get(context.__gaiusJspiUrl).text();
  }
  process.stdout.write(JSON.stringify(report));
})().catch((error) => { console.error(error); process.exit(1); });
"""
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(directory, boot=True)
            wasm_dist = self.make_wasm_dist(directory)
            with contextlib.redirect_stdout(io.StringIO()):
                PORTABLE.build(dist, output, root=root, wasm_dist=wasm_dist)
            script_path = Path(directory) / "bootstrap-runtime-check.js"
            script_path.write_text(script, encoding="utf-8")
            reports = {}
            result = subprocess.run([node, str(script_path), str(output), "wasm"], text=True,
                                        capture_output=True, timeout=60, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            reports["wasm"] = json.loads(result.stdout)
            sha = lambda data: hashlib.sha256(data).hexdigest()  # noqa: E731
            wasm = reports["wasm"]
            self.assertEqual(wasm["module"], sha(self.WASM_CLIENT), "TeaVM gets the inflated module bytes")
            self.assertEqual(wasm["client"], self.WASM_SCRIPTS["gaius-wasm-boot.js"])
            self.assertEqual(wasm["runtime"], self.WASM_SCRIPTS["gaius-wasm-gc-runtime.js"])
            self.assertEqual(wasm["jspi"], self.WASM_SCRIPTS["gaius-jspi.js"])
            self.assertEqual(wasm["jsClientLoader"], "undefined")
            self.assertTrue(wasm["classesUntouched"], "the internal JavaScript payload stays unread by the WasmGC bootstrap")

    def test_shader_toolchain_client_embeds_loader_and_modules(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output, token = self.make_shader_toolchain_fixture(directory)
            self.run_build(root, dist, output)

            manifest = json.loads((dist / "Gaius.manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["profile"], RENDERPEARL_ID)
            toolchain = manifest["shaderToolchain"]
            self.assertEqual(toolchain["version"], token)
            self.assertEqual(toolchain["build"]["role"], "shader-toolchain")
            self.assertEqual(
                toolchain["build"]["compatibilitySha256"],
                manifest["buildIdentity"]["compatibilitySha256"],
            )
            self.assertEqual(
                toolchain["manifest"]["sha256"],
                PORTABLE.sha256_file(dist / "gaius-shader-toolchain.json"),
            )
            self.assertEqual(
                set(toolchain["modules"]),
                {"gaius-shaderc.js", "gaius-shaderc.wasm", "gaius-spvc.js", "gaius-spvc.wasm"},
            )
            self.assertEqual(
                toolchain["loader"]["sha256"],
                PORTABLE.sha256_file(dist / "gaius-shader-toolchain.js"),
            )
            self.assertEqual(self.embedded_manifest(output), manifest)
            self.assertTrue(self.quick_check_identity(root, dist, output))

            html = output.read_text(encoding="utf-8")
            # The page's own loader tag is gone; the loader runs inline after the
            # portable bootstrap and before the launcher scripts.
            self.assertNotIn("gaius-shader-toolchain.js?v=", html)
            inline = (('  <script data-gaius-shader-toolchain data-profile="' + RENDERPEARL_ID + '" ') +
                      'data-gaius-portable-inline="1">\n')
            self.assertEqual(html.count(inline), 1)
            self.assertEqual(html.count("data-gaius-shader-toolchain"), 1)
            bootstrap_at = html.index('<script data-gaius-portable="1">')
            loader_at = html.index(inline)
            launcher_at = html.index('  <script>\n    if (typeof Error === "function")')
            self.assertLess(bootstrap_at, loader_at)
            self.assertLess(loader_at, launcher_at)
            self.assertIn(
                f'window.__gaiusShaderToolchainUrls = {{version: "{token}"}};', html
            )
            self.assertIn(
                (dist / "gaius-shader-toolchain.js").read_text(encoding="utf-8"), html
            )
            for key in ("shadercJs", "shadercWasm", "spvcJs", "spvcWasm"):
                self.assertIn(f"{key}: URL.createObjectURL(", html)
            embedded = self.embedded_assets(output)
            self.assertEqual(
                list(embedded),
                ["classes", "wasm", "shader-shadercJs", "shader-shadercWasm", "shader-spvcJs",
                 "shader-spvcWasm", "vanilla", "server"],
            )
            for key, name in (
                ("shadercJs", "gaius-shaderc.js"),
                ("shadercWasm", "gaius-shaderc.wasm"),
                ("spvcJs", "gaius-spvc.js"),
                ("spvcWasm", "gaius-spvc.wasm"),
            ):
                compressed_bytes = embedded["shader-" + key]
                self.assertEqual(gzip.decompress(compressed_bytes), (dist / name).read_bytes())
                self.assertEqual(
                    toolchain["modules"][name]["gzipSha256"],
                    hashlib.sha256(compressed_bytes).hexdigest(),
                )

    def test_shader_toolchain_client_without_loader_tag_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output = self.make_fixture(
                directory,
                version=RENDERPEARL_ID,
                launcher_asset_index="34",
                classes=self.SHADER_TOOLCHAIN_CLASSES,
            )

            with self.assertRaisesRegex(RuntimeError, "has no gaius-shader-toolchain.js loader"):
                self.run_build(root, dist, output)
            self.assertFalse(output.exists())

    def test_shader_toolchain_missing_module_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output, _token = self.make_shader_toolchain_fixture(directory)
            (dist / "gaius-spvc.wasm").unlink()

            with self.assertRaisesRegex(FileNotFoundError, "gaius-spvc.wasm"):
                self.run_build(root, dist, output)
            self.assertFalse(output.exists())

    def test_shader_toolchain_rebuilt_module_without_new_page_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output, _token = self.make_shader_toolchain_fixture(directory)
            (dist / "gaius-shaderc.wasm").write_bytes(b"\0asm\1\0\0\0rebuilt")

            with self.assertRaisesRegex(RuntimeError, "loader token"):
                self.run_build(root, dist, output)

    def test_shader_toolchain_manifest_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output, _token = self.make_shader_toolchain_fixture(directory)
            manifest_path = dist / "gaius-shader-toolchain.json"
            toolchain_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            toolchain_manifest["files"]["gaius-spvc.js"]["sha256"] = "0" * 64
            manifest_path.write_text(json.dumps(toolchain_manifest), encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "gaius-spvc.js does not match"):
                self.run_build(root, dist, output)

    def test_shader_toolchain_stale_identity_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output, _token = self.make_shader_toolchain_fixture(directory)
            manifest_path = dist / "gaius-shader-toolchain.json"
            # Same records, different bytes: the sidecar no longer describes the file.
            manifest_path.write_text(
                json.dumps(json.loads(manifest_path.read_text(encoding="utf-8")), indent=1),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "artifact hash does not match"):
                self.run_build(root, dist, output)

    def test_shader_toolchain_loader_that_cannot_be_inlined_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, dist, output, _token = self.make_shader_toolchain_fixture(directory)
            loader = dist / "gaius-shader-toolchain.js"
            loader.write_bytes(loader.read_bytes() + b"// </script>\n")
            self.add_shader_toolchain_records(root, dist)

            with self.assertRaisesRegex(RuntimeError, "cannot be inlined"):
                self.run_build(root, dist, output)

    def add_shader_toolchain_records(self, root: Path, dist: Path) -> None:
        """Refresh manifest, sidecar and loader tag after a fixture file changed."""
        manifest_path = dist / "gaius-shader-toolchain.json"
        toolchain_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for name in toolchain_manifest["files"]:
            value = (dist / name).read_bytes()
            toolchain_manifest["files"][name] = {
                "bytes": len(value),
                "sha256": hashlib.sha256(value).hexdigest(),
            }
        manifest_path.write_text(json.dumps(toolchain_manifest), encoding="utf-8")
        PORTABLE.build_identity.write_sidecar(root, "shader-toolchain", manifest_path)
        index = dist / "index.html"
        text = PORTABLE.SHADER_TOOLCHAIN_TAG.sub("", index.read_text(encoding="utf-8"))
        tag = (
            f'  <script data-gaius-shader-toolchain data-profile="{RENDERPEARL_ID}" '
            f'src="gaius-shader-toolchain.js?v={PORTABLE.shader_toolchain_token(dist)}"></script>\n'
        )
        index.write_text(text.replace("<!doctype html>\n", "<!doctype html>\n" + tag, 1), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
