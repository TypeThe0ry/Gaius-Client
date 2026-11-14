#!/usr/bin/env python3
"""Generate TeaVM entry points for the production telemetry JSBody methods."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[4]
out = Path(sys.argv[1]).resolve()
production = root / "port/src/main/java/dev/gaius/browser/BrowserChunkPipelineTelemetry.java"
source = production.read_text(encoding="utf-8")


def extract(method: str, marker: str) -> tuple[str, int]:
    at = source.index(method)
    start = source.rfind(marker, 0, at) + len(marker)
    end = source.index('""")', start)
    body = source[start:end]
    effective = body[len(body) - len(body.lstrip()):len(body.rstrip())]
    while effective.startswith("{") and effective.endswith("}"):
        effective = effective[1:-1].strip()
    return effective, source.count(chr(10), 0, at) + 1


epoch_body, epoch_line = extract(
    "private static native void setEpochsJs(int levelEpoch, int resourceEpoch);",
    '@JSBody(params = {"levelEpoch", "resourceEpoch"}, script = """',
)
init_body, init_line = extract(
    "private static native void initializeEnabledStateJs();",
    '@JSBody(script = """',
)
generated = out / "src/dev/gaius/fixture/JsBodyCollision.java"
generated.parent.mkdir(parents=True, exist_ok=True)
generated.write_text(f'''package dev.gaius.fixture;
import org.teavm.jso.JSBody;
public final class JsBodyCollision {{
  @JSBody(params = {{"levelEpoch", "resourceEpoch"}}, script = """
{epoch_body}
""")
  private static native void setEpochsJs(int levelEpoch, int resourceEpoch);
  @JSBody(script = """
{init_body}
""")
  private static native void initializeEnabledStateJs();
  public static void main(String[] args) {{ initializeEnabledStateJs(); setEpochsJs(1, 1); }}
}}
''', encoding="utf-8")
print(f"Generated TeaVM fixtures from {production}:{init_line},{epoch_line} (TeaVM body wrapper blocks elided)")
