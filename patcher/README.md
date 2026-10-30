# Gaius Patcher

Gaius Patcher builds a personal copy of the Gaius browser client on your own
computer, from your own copy of the game. It is compatible with Java Edition
1.21.11, 26.2 and 26.3.

> **Not an official Minecraft product. Not approved by or associated with
> Mojang or Microsoft.** This repository contains no Mojang code and no Mojang
> files. To use the patcher you must own Minecraft: Java Edition and accept
> the [Minecraft EULA](https://www.minecraft.net/eula). See the
> [disclaimer](../DISCLAIMER.md).

## What the patcher does

1. **Collects the official files from your installation.** It reads the
   client jar, libraries and assets from your launcher's game directory and
   checks every file against the SHA-1 pinned in
   `port/versions/<version>.json`. It never downloads game files: a version you
   have not installed with the official launcher cannot be built. The only
   download is the 1.21.11 obfuscation mappings, which the launcher does not
   keep; they come straight from the Mojang address in your own version
   metadata, or from a file you pass with `--mappings`. Nothing goes through a
   Gaius server.
2. **Prepares the few sources that change official classes.** Gaius changes
   about ten official classes (`patcher/source-patches.json`). Their Gaius
   versions are published only as source patches, which hold the lines Gaius
   wrote and the line ranges they replace, never a line of official code.
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

Steps 3 to 5 are being wired up; today the patcher implements `doctor`,
`fetch` and `prepare`.

## Versions

The patcher is versioned with the Gaius release it reproduces: patcher
`0.4.0-pre.5` builds the same client as Gaius release `v0.4.0-pre.5`, and a
newer release needs a newer patcher. The built client reports the release's
own version number (`0.4.0` in its About screen), exactly like the official
build. `java patcher/GaiusPatcher.java version` prints both numbers, and
`fetch` records them in `fetch-manifest.json`. Patcher releases are tagged
`vX.Y.Z` in the public repository and contain the patcher source only, never
a build.

`build` is not available yet; until it is, the patcher can check your
installation and collect the official files.

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
```

For 1.21.11, which ships obfuscated, remap the client
(`port/scripts/remap-client.sh`) before `prepare`.

| Option | Meaning |
| --- | --- |
| `--minecraft-dir <dir>` | Your launcher's game directory, if it is not in the usual place |
| `--mappings <file>` | 1.21.11 only: a `client_mappings` file to use instead of downloading it |
| `--offline` | Never use the network |
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
the bases are the ones users get. It refuses a patch that would carry a line
of official code: an added line that also appears in the decompiled class of
any version it processed. Keep such a line where it is in the decompiled
class, or, if it is not official code (a declaration Gaius must repeat),
add the SHA-256 of its stripped text to the entry's `reviewedLines`. The
public tree check refuses any `.gpatch` that holds removed or context lines.

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
