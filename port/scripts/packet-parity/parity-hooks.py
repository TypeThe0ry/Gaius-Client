#!/usr/bin/env python3
"""Tick parity hook retention check for a WasmGC overlay (no JVM, no TeaVM).

  parity-hooks.py <overlay jar | patch class directory> --expect present|absent [--json OUT]

Reads the patched vanilla classes and lists, per hook, whether its call into dev/gaius/parity is
in the constant pool of the class that must carry it. A packet-parity debug build
(GAIUS_DEBUG_PACKET_PARITY=1, registration MinecraftClientPatcher.patchTickPacketParity) must have
every hook after all later patchers ran (--expect present); a default build must have none
(--expect absent). Exit 0 when the
expectation holds, 1 otherwise, 2 on usage errors.
"""

import json
import os
import struct
import sys
import zipfile

RECORDER = "dev/gaius/parity/TickPacketRecorder"
BRIDGE = "dev/gaius/parity/BrowserTickPacketParity"
SCHEDULE = "dev/gaius/parity/BrowserTickParityExecutors"

# (class, owner, method names): every listed method must be referenced by the class.
HOOKS = (
    ("net/minecraft/server/MinecraftServer", RECORDER, ("beforeTick", "afterTick")),
    ("net/minecraft/server/MinecraftServer", BRIDGE, ("prepare",)),
    ("net/minecraft/network/Connection", RECORDER, ("outboundProtocol", "inboundProtocol", "outgoing")),
    ("net/minecraft/server/network/ServerGamePacketListenerImpl", RECORDER, ("input",)),
    ("net/minecraft/server/network/ServerCommonPacketListenerImpl", RECORDER, ("input",)),
    ("net/minecraft/server/level/ChunkHolder", RECORDER, ("chunkBroadcast",)),
    ("net/minecraft/world/entity/Entity", RECORDER, ("entity", "entityIdentity")),
    ("net/minecraft/server/dedicated/DedicatedServerProperties", BRIDGE, ("properties",)),
    ("net/minecraft/server/players/PlayerList", RECORDER, ("fixedDistance",)),
    ("net/minecraft/util/Util", SCHEDULE, ("background", "io")),
    ("net/minecraft/util/thread/AbstractConsecutiveExecutor", SCHEDULE, ("consecutiveName",)),
    ("net/minecraft/server/network/ServerGamePacketListenerImpl", SCHEDULE, ("workerMovement", "moveChunkTracking")),
    ("net/minecraft/server/level/ChunkTaskPriorityQueue", SCHEDULE, ("chunkTaskKey",)),
    ("net/minecraft/server/network/PlayerChunkSender", SCHEDULE, ("unacknowledgedBatches",)),
)


def method_refs(data):
    """(owner, name) of every Methodref/InterfaceMethodref in a class file's constant pool."""
    count = struct.unpack_from(">H", data, 8)[0]
    offset = 10
    pool = [None] * count
    index = 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, offset)[0]
            pool[index] = ("utf8", data[offset + 2:offset + 2 + length].decode("utf-8", "replace"))
            offset += 2 + length
        elif tag in (3, 4):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = ("ref", struct.unpack_from(">H", data, offset)[0])
            offset += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, struct.unpack_from(">H", data, offset)[0], struct.unpack_from(">H", data, offset + 2)[0])
            offset += 4
        elif tag == 15:
            offset += 3
        else:
            raise ValueError("unknown constant pool tag %d" % tag)
        index += 1
    refs = set()
    for entry in pool:
        if entry and entry[0] in (10, 11):
            owner = pool[pool[entry[1]][1]][1]
            name = pool[pool[entry[2]][1]][1]
            refs.add((owner, name))
    return refs


def read_class(source, name):
    if os.path.isdir(source):
        path = os.path.join(source, name + ".class")
        if not os.path.isfile(path):
            return None
        with open(path, "rb") as handle:
            return handle.read()
    with zipfile.ZipFile(source) as archive:
        try:
            return archive.read(name + ".class")
        except KeyError:
            return None


def main(argv):
    if len(argv) < 3 or argv[1] != "--expect" or argv[2] not in ("present", "absent"):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    source, expect = argv[0], argv[2]
    output = argv[argv.index("--json") + 1] if "--json" in argv else None
    rows = []
    for owner, target, names in HOOKS:
        data = read_class(source, owner)
        refs = method_refs(data) if data is not None else set()
        found = [name for name in names if (target, name) in refs]
        rows.append({"class": owner, "calls": target, "methods": list(names), "found": found,
                     "classPresent": data is not None})
    complete = [row for row in rows if len(row["found"]) == len(row["methods"])]
    touched = [row for row in rows if row["found"]]
    passed = len(complete) == len(rows) if expect == "present" else not touched
    for row in rows:
        state = "present" if len(row["found"]) == len(row["methods"]) else ("partial" if row["found"] else "absent")
        print("PARITY_HOOK %s %s -> %s %s" % (state, row["class"], row["calls"], ",".join(row["methods"])))
    print("PARITY_HOOKS expect=%s hooks=%d present=%d touched=%d RESULT %s"
          % (expect, len(rows), len(complete), len(touched), "PASS" if passed else "FAIL"))
    if output:
        with open(output, "w", encoding="utf-8", newline="\n") as handle:
            json.dump({"source": source, "expect": expect, "passed": passed, "hooks": rows}, handle, indent=2)
            handle.write("\n")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
