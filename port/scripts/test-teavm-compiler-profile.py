#!/usr/bin/env python3
"""Regression for publishing a TeaVM compiler profile from staged bytes."""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "port" / "scripts" / "teavm-compiler-profile.py"


def run(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(TOOL), *arguments],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="gaius-teavm-profile-") as temporary:
        directory = Path(temporary)
        staged = directory / "staging" / "classes.js"
        published = directory / "dist" / "classes.js"
        staged_profile = staged.with_name(f"{staged.name}.release.json")
        published_profile = published.with_name(f"{published.name}.release.json")
        resource = directory / "resources.txt"
        pom = directory / "release-pom.xml"
        staged.parent.mkdir(parents=True)
        published.parent.mkdir(parents=True)
        staged.write_text("// staged TeaVM output\n", encoding="utf-8")
        resource.write_text("assets/.mcassetsroot\n", encoding="utf-8")
        pom.write_text(
            f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.gaius</groupId><artifactId>fixture</artifactId><version>1</version>
  <build><plugins><plugin>
    <groupId>org.teavm</groupId><artifactId>teavm-maven-plugin</artifactId>
    <executions><execution><id>web-client</id><phase>package</phase>
      <goals><goal>compile</goal></goals><configuration>
        <mainClass>net.minecraft.client.main.Main</mainClass>
        <targetDirectory>{published.parent.as_posix()}</targetDirectory>
        <targetFileName>classes.js</targetFileName>
        <optimizationLevel>ADVANCED</optimizationLevel>
        <sourceMapsGenerated>false</sourceMapsGenerated>
        <debugInformationGenerated>false</debugInformationGenerated>
        <minifying>true</minifying><shortFileNames>true</shortFileNames>
        <assertionsRemoved>true</assertionsRemoved>
      </configuration></execution></executions>
  </plugin></plugins></build>
</project>
""",
            encoding="utf-8",
        )

        common = (
            "--root",
            str(ROOT),
            "--role",
            "client",
            "--artifact",
            str(published),
            "--pom",
            str(pom),
            "--resource",
            str(resource),
            "--require-release",
        )
        written = run(
            "write",
            *common,
            "--artifact-input",
            str(staged),
            "--output",
            str(staged_profile),
        )
        if written.returncode != 0:
            raise AssertionError(written.stderr or written.stdout)
        shutil.copyfile(staged, published)
        shutil.copyfile(staged_profile, published_profile)

        verified = run("verify", *common)
        if verified.returncode != 0:
            raise AssertionError(verified.stderr or verified.stdout)
        published.write_text("// mismatched output\n", encoding="utf-8")
        rejected = run("verify", *common)
        if rejected.returncode == 0:
            raise AssertionError("compiler profile accepted mismatched published bytes")

    print("TeaVM staged compiler profile regression passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
