import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VERSION_PROFILES = tuple(
    profile
    for path in sorted((ROOT / "port" / "versions").glob("*.json"))
    for profile in [json.loads(path.read_text(encoding="utf-8"))]
    if path.stem == profile["id"]
)


def select_profile(predicate, role):
    matches = [profile for profile in VERSION_PROFILES if predicate(profile)]
    if len(matches) != 1:
        raise ValueError(f"Expected one {role} verification profile, found {len(matches)}")
    return matches[0]


CANONICAL_SEAM = json.loads(
    (ROOT / "port" / "mc" / "shared" / "sourcesets.json").read_text(encoding="utf-8")
)["canonicalRenderSeam"]
CANONICAL_PROFILE = select_profile(
    lambda profile: profile["renderSeam"] == CANONICAL_SEAM, "canonical render seam"
)
LEGACY_PROFILE = select_profile(
    lambda profile: profile["clientDistribution"] == "obfuscated-with-mappings", "mapped legacy client"
)
RENDERPEARL_PROFILE = select_profile(
    lambda profile: profile["shaderFront"] == "renderpearl", "renderpearl shader front"
)
CANONICAL_ID = CANONICAL_PROFILE["id"]
LEGACY_ID = LEGACY_PROFILE["id"]
RENDERPEARL_ID = RENDERPEARL_PROFILE["id"]
