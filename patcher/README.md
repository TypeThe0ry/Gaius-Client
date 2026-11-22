# Gaius Patcher

Gaius Patcher builds a personal copy of the Gaius browser client on your own
computer, from your own copy of the game. It is compatible with Java Edition
1.21.11, 26.2 and 26.3.

> **Not an official Minecraft product. Not approved by or associated with
> Mojang or Microsoft.** This repository contains no Mojang code and no Mojang
> files. To use the patcher you must own Minecraft: Java Edition and accept
> the [Minecraft EULA](https://www.minecraft.net/eula). See the
> [disclaimer](../DISCLAIMER.md).

## Public source snapshot

The public tree cannot build the game on its own. All three overlay profiles
need the omitted light and Unihex bridge generators in addition to the official
game files. The [local overlay recipe](recipes/README.md#local-bridge-generation)
describes how maintainers who already have the matching source inputs generate
the bridges locally. This snapshot supports source inspection and standalone
checks.

## What the patcher does

1. **Collects the official files from your installation.** It reads the
   client jar, libraries and assets from your launcher's game directory and
   checks every file against the SHA-1 pinned in
   `port/versions/<version>.json`. It never downloads game files: a version you
   have not installed with the official launcher cannot be built. A standalone
   `fetch` can obtain the 1.21.11 obfuscation mappings, which the launcher does
   not keep, or you can pass them with `--mappings`. The `build` command always
   runs offline and requires those mappings in its work directory or at the
   local path passed with `--mappings`. Nothing goes through a Gaius server.
2. **Prepares the few sources that change official classes.** Gaius changes
   about ten official classes (`patcher/source-patches.json`). Their Gaius
   versions are published only as source patches, which hold the lines Gaius
   wrote, the line ranges they replace, and line numbers of your own
   decompiled text to copy where Gaius moved or re-indented a line, never a
   line of official code.
   `prepare` decompiles each class from your own fetched files with
   [Vineflower](https://github.com/Vineflower/vineflower) (Apache-2.0,
   downloaded once from Maven Central and checked against the SHA-256 pinned
   in `source-patches.json`, or passed with `--decompiler`), checks the
   decompiled text against the SHA-256 the patch records, applies the patch
   and checks the result the same way. The decompiled classes stay in your
   work directory.
3. **Applies the Gaius bytecode patches** to the jar on your machine.
4. **Compiles** the patched game for the browser with TeaVM.
5. **Writes your build**: a site folder and a single HTML file.

The `build` command runs steps 3 to 5 in a disposable copy of the public
source tree. It invokes the repository's existing release scripts, so the
patcher and the normal release build share one pipeline rather than two
implementations. The disposable tree and all generated files are written
under `--out`; the repository is never used as a build output directory.

## Versions

The patcher is versioned with the Gaius release it reproduces: patcher
`0.4.0-pre.6` builds the same client as Gaius release `v0.4.0-pre.6`, and a
newer release needs a newer patcher. The built client reports the release's
own version number (`0.4.0` in its About screen), exactly like the official
build. `java patcher/GaiusPatcher.java version` prints both numbers, and
`fetch` records them in `fetch-manifest.json`. Patcher releases are tagged
`vX.Y.Z` in the public repository and contain the patcher source only, never
a build.

`build` is offline after the local launcher files and toolchain have been
installed. On an online run, missing pinned host-tool archives, JDK, Maven and
Vineflower files are downloaded once and verified before use. `fetch` and `tools install` can populate
`<work-root>/maven-repository` from the locked Maven closure. Use
`--toolchain-dir <dir>` to provide an offline source at `<dir>/maven-repository`.
`tools status --json --offline` counts the verified files in the working
repository; `tools install --only maven-repository --json-progress` provisions
missing files, resumes interrupted downloads, and publishes only verified bytes.
Pass `--mappings` for 1.21.11 when
the mappings are not already in your work directory. A build writes `site/`, `Gaius-<version>.html` and
`expected-hashes.json` under `--out` (or under the default work-root build
directory). Add `--json-progress` to receive one compact JSON object per line
on stdout; build logs stay on stderr and a failure emits an error object before
exiting non-zero. For 26.3, use `tools status --json --offline` for the exact
placement path and file list for the prebuilt shader toolchain. The Gaius
release process prepares this package. This tool cannot download it. Use
`--shader-toolchain <dir>` (or
`GAIUS_SHADER_TOOLCHAIN_PREBUILT`) to select the unpacked directory. The locked
Emscripten SDK is only needed when rebuilding the shader modules separately.
Builds invoke
the repository's Bash release scripts; on Windows, Git for Windows Bash must
be available as `bash` on `PATH`.

Python 3 is required for release metadata, asset preparation, and site staging.
The patcher checks `PYTHON` if set, otherwise it looks for a working `python`
or `python3`, then `py -3` on Windows. The selected interpreter is passed to
all staged build scripts through `PYTHON`.

The default patcher build skips the Wasm kernels. To include them, pass
`--strict-kernel`. This mode requires the pinned Rust/Cargo versions, the
`wasm32-unknown-unknown` target, and a populated offline Cargo dependency cache.
Check the same prerequisites as the build with `tools status --json --offline`
using `--strict-kernel` for the strict build. Each row includes `required`,
`requiredReason`, and `requiredFor`. Each result identifies the tool, its status, and why the selected build needs it.

`patcher/toolchain.lock` records the JDK, Maven, Vineflower, TeaVM, Rust kernel
inputs, Wasm host tools and 26.3 shader toolchain versions and hashes. The
default build requires LLVM 21.1.8 and Binaryen 133 on all three profiles.
Their archives are pinned for Linux and Windows x86-64. Install them with
`java patcher/GaiusPatcher.java tools install --only llvm,binaryen --json-progress`.
The installer verifies the archives and unpacks the locked files into an
atomic, content-keyed directory under `<work-root>/tools/host-wasm/`.
An XZ-capable `tar` is required, and Linux also needs `dpkg-deb`.
`--toolchain-dir <dir>`
uses a local cache containing the pinned files and optional `maven-repository/`;
missing offline files produce a message with the tool name, expected hash and
work-root destination. Verified tools and the selected Maven repository are
staged in the disposable build tree. `build.log` and `wasm-host-tools.json`
record the host-tool versions used for the build.

When updating the shader toolchain, build it with
`port/scripts/build-wasm-shader-toolchain.sh --out <dir>` and then run
`python3 patcher/tools/lock_shader_toolchain.py <dir> --archive <output.zip>`. The generator verifies
the built manifest, source pins, files, and checked-in loader before updating
only the shader section of the lock, including the SHA-256 and size of the
generated ZIP. Use the matching output directory or ZIP with the updated lock;
do not mix files from different builds. Public distribution remains subject to
a separate review. The source export omits the ZIP and its locked loader; to build with the shader toolchain, supply the exact files or archive recorded in the lock.

## Terms of use

Before it reads any game file, the patcher asks you to accept its
[terms of use](TERMS.md): you own the game, you follow the Minecraft EULA and
Usage Guidelines, and your build stays with you. Type `I AGREE` when asked, or
pass `--accept-terms`. The acceptance is stored in the work directory with a
hash of the terms, and the patcher asks again if the terms change.
`java patcher/GaiusPatcher.java terms` shows them.

## Your build is for you only

The HTML the patcher produces contains code and assets from your own copy of
the game. Use it yourself, on your own computer or your own local network.
**Do not share it, upload it, or host it on a public website.** If friends
want to play, each of them runs the patcher with their own copy of the game.

## Usage

You need Java 25 (Java 21 is enough for 1.21.11). Install and start each
version you want (1.21.11, 26.2 or 26.3) once in the official launcher, so
its files are on disk.

```sh
java patcher/GaiusPatcher.java doctor
java patcher/GaiusPatcher.java terms
java patcher/GaiusPatcher.java fetch 26.3
java patcher/GaiusPatcher.java prepare 26.3
java patcher/GaiusPatcher.java build 26.3 --offline --accept-terms \
  --out "$HOME/gaius-build/26.3"
```

For 1.21.11, which ships obfuscated, remap the client
(`port/scripts/remap-client.sh`) before `prepare`.

Run `manifest <build-output-dir> --out <dir>` to record the site and portable
HTML hashes of a reference build. Run `verify <build-output-dir> --manifest
<file>` with a trusted manifest to check a build. `verify` checks the recorded hashes and reports any differences.

| Option | Meaning |
| --- | --- |
| `--minecraft-dir <dir>` | Your launcher's game directory, if it is not in the usual place |
| `--mappings <file>` | 1.21.11 only: a `client_mappings` file to use instead of downloading it |
| `--shader-toolchain <dir>` | 26.3: verified prebuilt shader toolchain directory |
| `--toolchain-dir <dir>` | Directory containing pinned tools and optionally `maven-repository/` |
| `--quality <low|mid|high|ultra>` | Set the first-run browser quality tier; `medium` is accepted as an alias for `mid`, saved tiers take precedence |
| `--render-distance <n>` | Set the first-run render distance (2–32 chunks); existing browser options are preserved |
| `--backend <auto|webgpu|webgl2>` | Set the graphics backend preference; an existing `gpu` query parameter wins |
| `--resource-pack <file>` | Copy one local resource-pack file into `site/resourcepacks/`; repeat for more files |
| `--offline` | Never use the network |
| `--strict-kernel` | Enable Wasm kernels and require their locked Rust inputs (`build` and `tools status`) |
| `--json-progress` | Print one JSON progress object per line to stdout (`build`, `manifest`, `verify`, `tools install`) |
| `--manifest <file>` | Reference manifest for `verify` |
| `--out <dir>` | Build output directory containing the site and portable HTML |
| `--accept-terms` | Accept the [terms of use](TERMS.md) without the prompt |
| `--work <dir>` | Where to write the collected files |
| `--decompiler <jar>` | `prepare`: your copy of the pinned decompiler, instead of downloading it |
| `--into <dir>` | `prepare`: write the patched sources under this directory instead of the source tree |

## What may be public

Only files Gaius wrote (plus the third-party libraries listed in `NOTICE`):
the patcher, the bytecode patches, the browser runtime and UI, the Rust
kernels, the build scripts and the version profiles. Modified copies of
official classes are published only as patches against the user's own game. Official jars, libraries, assets, mappings, anything derived
from them (patched jars, compiled `classes.wasm` or `classes.js`, packed
assets, built HTML) and screenshots of the game never enter this repository
or its releases.

`patcher/public-allowlist.txt` lists what is exported; files under
`patcher/public-root/` (README, disclaimer, issue rules, issue forms and the
issue guard) go to the root of the public tree.
`patcher/tools/public_tree_check.py` then refuses an export that contains a
file in a vanilla package, a binary or media file, a copy of an official file (by
SHA-1), or a blocked word:

```sh
python3 patcher/tools/export_public_tree.py --out /tmp/gaius-public
python3 patcher/tools/public_tree_check.py /tmp/gaius-public \
  --vanilla-jar port/work/26.3/client-original.jar \
  --asset-index port/work/26.3/assets/indexes/34.json
```

## Source patches (maintainers)

`patcher/patches/<version>/<id>.gpatch` are generated, never edited by hand.
On a machine with the official game files, after `fetch` (and the remap for
1.21.11):

```sh
python3 patcher/tools/make_source_patches.py --pin-decompiler vineflower-<version>.jar  # once per decompiler update
python3 patcher/tools/make_source_patches.py 26.2 26.3 1.21.11
python3 patcher/tools/make_source_patches.py 26.2 26.3 1.21.11 --check
```

The tool decompiles through the patcher itself (`GaiusPatcher decompile`), so
the bases are the ones users get. Lines are aligned ignoring indentation, and
a line the decompiled class already has becomes a copy by line number
(`=<line> <count>`, or `~<line> <indent>` to re-indent it). The tool refuses a
patch that would still carry a line of official code as text: usually a line
only another version's class has, which means this version cannot produce
the file, so drop it from the entry's `versions`. A line that is not official
code (a declaration Gaius must repeat) can be accepted by adding the SHA-256
of its stripped text to the entry's `reviewedLines`. The public tree check
refuses any `.gpatch` that holds removed or context lines.

## Publishing

The public repository is
[TypeThe0ry/Gaius-Client](https://github.com/TypeThe0ry/Gaius-Client). Changes
are made in the private repository and synced by hand: export the allowlisted
files, run the tests and the public tree check on the exported tree, and only
then copy it over the public tree and open a pull request there. Each sync
replaces the public tree, so do not edit the public repository directly.

## Tests

```sh
java patcher/GaiusPatcher.java selftest
python3 -m unittest discover -s patcher/tests
```

The tests use synthetic files only and never touch the network.

## License

MIT, see `LICENSE`.
