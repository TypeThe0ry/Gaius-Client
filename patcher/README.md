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
2. **Applies the Gaius patches** to that jar on your machine.
3. **Compiles** the patched game for the browser with TeaVM.
4. **Writes your build**: a site folder and a single HTML file.

Steps 2 to 4 are being wired up; today the patcher implements `doctor` and
`fetch`.

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
```

| Option | Meaning |
| --- | --- |
| `--minecraft-dir <dir>` | Your launcher's game directory, if it is not in the usual place |
| `--mappings <file>` | 1.21.11 only: a `client_mappings` file to use instead of downloading it |
| `--offline` | Never use the network |
| `--accept-terms` | Accept the [terms of use](TERMS.md) without the prompt |
| `--work <dir>` | Where to write the collected files |

## What may be public

Only files Gaius wrote: the patcher, the patches, the browser runtime and the
version profiles. Official jars, libraries, assets, mappings, anything derived
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

## Tests

```sh
java patcher/GaiusPatcher.java selftest
python3 -m unittest discover -s patcher/tests
```

The tests use synthetic files only and never touch the network.

## License

MIT, see `LICENSE`.
