#!/usr/bin/env python3
"""Regression test for the VanillaPackResources prefix binary search.

The old browser resource list was locale-sorted.  ``lowerBound`` then stopped
at the first unrelated path, which silently dropped resources from five
datapack namespaces.  This test models that exact failure, proves the
Java-natural-order repair removes every omission, and can optionally check a
generated resource-list file after a build.
"""

from __future__ import annotations

import argparse
import bisect
import json
from pathlib import Path
import sys
from typing import Iterable


FORMER_OMISSIONS = {
    "data/minecraft/enchantment/": (
        "aqua_affinity.json",
        "blast_protection.json",
        "feather_falling.json",
        "punch.json",
    ),
    "data/minecraft/worldgen/noise/": (
        "cave_cheese.json",
        "cave_entrance.json",
        "cave_layer.json",
        "clay_bands_offset.json",
    ),
    "data/minecraft/worldgen/noise_settings/": (
        "amplified.json",
        "large_biomes.json",
        "nether.json",
        "overworld.json",
    ),
    "data/minecraft/worldgen/structure/": (
        "ancient_city.json",
        "buried_treasure.json",
        "desert_pyramid.json",
        "stronghold.json",
    ),
    "data/minecraft/worldgen/structure_set/": (
        "ancient_cities.json",
        "buried_treasures.json",
        "desert_pyramids.json",
        "villages.json",
    ),
}


BARRIERS = {
    "data/minecraft/enchantment/": "data/minecraft/enchantment_provider/enderman_loot_drop.json",
    "data/minecraft/worldgen/noise/": "data/minecraft/worldgen/noise_settings/amplified.json",
    "data/minecraft/worldgen/noise_settings/": "data/minecraft/worldgen/structure/ancient_city.json",
    "data/minecraft/worldgen/structure/": "data/minecraft/worldgen/structure_set/ancient_cities.json",
    "data/minecraft/worldgen/structure_set/": "data/minecraft/worldgen/structure_set_extra/example.json",
}


def lower_bound(values: list[str], target: str) -> int:
    """Mirror VanillaPackResources.lowerBound and Java String ordering."""

    return bisect.bisect_left(values, target)


def listed_resources(values: list[str], prefix: str) -> list[str]:
    start = lower_bound(values, prefix)
    result: list[str] = []
    for resource in values[start:]:
        if not resource.startswith(prefix):
            break
        result.append(resource)
    return result


def compact_unsorted_fixture(prefix: str, names: Iterable[str]) -> list[str]:
    """Build the old failure shape: prefix entries, barrier, then omissions."""

    names = tuple(names)
    selected = [prefix + "a.json", prefix + "b.json"]
    omitted = [prefix + name for name in names]
    return selected + [BARRIERS[prefix]] + omitted


def check_fixture() -> dict[str, object]:
    categories: dict[str, dict[str, object]] = {}
    for prefix, names in FORMER_OMISSIONS.items():
        before = compact_unsorted_fixture(prefix, names)
        before_listed = listed_resources(before, prefix)
        expected = [prefix + name for name in names]
        omitted_before = sorted(set(expected) - set(before_listed))

        repaired = sorted(set(before))
        after_listed = listed_resources(repaired, prefix)
        omitted_after = sorted(set(expected) - set(after_listed))
        if not omitted_before:
            raise AssertionError(f"fixture did not reproduce omission: {prefix}")
        if omitted_after:
            raise AssertionError(
                f"sorted repair still omits {omitted_after!r} for {prefix}"
            )
        categories[prefix] = {
            "formerOmissions": len(omitted_before),
            "repairedOmissions": len(omitted_after),
            "beforeListed": before_listed,
        }
    return categories


def check_resource_list(path: Path) -> dict[str, object]:
    raw = path.read_text(encoding="utf-8")
    values = [line.strip() for line in raw.splitlines() if line.strip()]
    if values != sorted(set(values)):
        raise AssertionError(
            f"{path} is not unique and sorted in Java String.compareTo order"
        )
    omissions: dict[str, list[str]] = {}
    for prefix, names in FORMER_OMISSIONS.items():
        expected = {prefix + name for name in names}
        omissions[prefix] = sorted(expected - set(listed_resources(values, prefix)))
    non_empty = {prefix: missing for prefix, missing in omissions.items() if missing}
    if non_empty:
        raise AssertionError(f"resource list omissions: {non_empty!r}")
    return {"path": str(path), "entries": len(values), "omissions": omissions}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--resource-list",
        type=Path,
        help="also validate a generated minecraft-resources.txt file",
    )
    args = parser.parse_args()

    result: dict[str, object] = {
        "model": "VanillaPackResources.lowerBound(String.compareTo)",
        "categories": check_fixture(),
    }
    if args.resource_list is not None:
        result["resourceList"] = check_resource_list(args.resource_list)
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, UnicodeError) as exc:
        print(f"resource-order regression failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
