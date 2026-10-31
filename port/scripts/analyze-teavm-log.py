#!/usr/bin/env python3

import json
import re
import sys
from collections import Counter
from pathlib import Path


ERROR_PATTERN = re.compile(
    r"^\[ERROR\] (?P<kind>Class|Method|Field) (?P<symbol>.+?) "
    r"(?:was not found|implements .+? which is missing in the classpath|"
    r"extends .+? which is missing in the classpath)$"
)


def parse(log_path: Path) -> dict:
    counters = {
        "class": Counter(),
        "method": Counter(),
        "field": Counter(),
    }
    first_call_sites: dict[tuple[str, str], str] = {}
    pending: tuple[str, str] | None = None

    log_text = log_path.read_text(errors="replace")
    completed_analysis = (
        "Output file built with errors" in log_text
        or "[INFO] BUILD SUCCESS" in log_text
    )
    failure_reason = None
    if "OutOfMemoryError" in log_text or "Java heap space" in log_text:
        failure_reason = "out-of-memory"

    for raw_line in log_text.splitlines():
        match = ERROR_PATTERN.match(raw_line)
        if match:
            kind = match.group("kind").lower()
            symbol = match.group("symbol")

            if " implements " in symbol:
                symbol = symbol.split(" implements ", 1)[1].split(",", 1)[0]
            elif " extends " in symbol:
                symbol = symbol.split(" extends ", 1)[1].split(",", 1)[0]

            counters[kind][symbol] += 1
            pending = (kind, symbol)
            continue

        if pending and raw_line.startswith("    at "):
            first_call_sites.setdefault(pending, raw_line.strip()[3:])
            pending = None
        elif raw_line.startswith("["):
            pending = None

    result = {
        "source": str(log_path),
        "completedAnalysis": completed_analysis,
        "failureReason": failure_reason,
        "totalOccurrences": sum(sum(counter.values()) for counter in counters.values()),
        "uniqueSymbols": sum(len(counter) for counter in counters.values()),
        "categories": {},
    }

    for kind, counter in counters.items():
        result["categories"][kind] = [
            {
                "symbol": symbol,
                "occurrences": occurrences,
                "firstCallSite": first_call_sites.get((kind, symbol)),
            }
            for symbol, occurrences in counter.most_common()
        ]

    return result


def markdown(report: dict) -> str:
    lines = [
        "# TeaVM compatibility gap",
        "",
        f"- Completed analysis: {str(report['completedAnalysis']).lower()}",
        f"- Failure reason: {report['failureReason'] or 'none'}",
        f"- Error occurrences: {report['totalOccurrences']}",
        f"- Unique missing symbols: {report['uniqueSymbols']}",
        "",
        "This report is generated from the real `net.minecraft.client.main.Main` "
        "TeaVM analysis. Repeated occurrences are retained because they indicate "
        "which compatibility surfaces block the largest reachable call graph.",
        "",
    ]

    for kind in ("class", "method", "field"):
        entries = report["categories"][kind]
        lines.extend(
            [
                f"## Missing {kind}s ({len(entries)})",
                "",
                "| Count | Symbol | First reachable call site |",
                "| ---: | --- | --- |",
            ]
        )
        for entry in entries:
            symbol = entry["symbol"].replace("|", r"\|")
            call_site = (entry["firstCallSite"] or "").replace("|", r"\|")
            lines.append(
                f"| {entry['occurrences']} | `{symbol}` | `{call_site}` |"
            )
        lines.append("")

    return "\n".join(lines)


def main() -> int:
    if len(sys.argv) not in (2, 4):
        print(
            "usage: analyze-teavm-log.py LOG [JSON_OUTPUT MARKDOWN_OUTPUT]",
            file=sys.stderr,
        )
        return 2

    log_path = Path(sys.argv[1]).resolve()
    if not log_path.is_file():
        print(f"TeaVM log not found: {log_path}", file=sys.stderr)
        return 1

    report = parse(log_path)
    json_text = json.dumps(report, indent=2, sort_keys=False) + "\n"
    markdown_text = markdown(report)

    if len(sys.argv) == 2:
        print(json_text, end="")
        return 0

    json_output = Path(sys.argv[2])
    markdown_output = Path(sys.argv[3])
    if not report["completedAnalysis"]:
        json_output = json_output.with_name(
            f"{json_output.stem}.incomplete{json_output.suffix}"
        )
        markdown_output = markdown_output.with_name(
            f"{markdown_output.stem}.incomplete{markdown_output.suffix}"
        )
    json_output.parent.mkdir(parents=True, exist_ok=True)
    markdown_output.parent.mkdir(parents=True, exist_ok=True)
    json_output.write_text(json_text)
    markdown_output.write_text(markdown_text)
    print(f"Gap report: {markdown_output}")
    print(f"Gap data:   {json_output}")
    return 0 if report["completedAnalysis"] else 3


if __name__ == "__main__":
    raise SystemExit(main())
