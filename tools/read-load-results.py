#!/usr/bin/env python3
"""Extract load summaries and replica versions from NearbyLab NDJSON; no device control."""

import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("paths", nargs="+", type=Path, help="NDJSON files or directories")
parser.add_argument("--output", type=Path)
args = parser.parse_args()
files = []
for path in args.paths:
    files.extend(sorted(path.rglob("*.ndjson")) if path.is_dir() else [path])
results = {}
replicas = []
issues = []
defaults_v02 = {
    "mode": "ECHO",
    "bytes": 1024,
    "rate": 10,
    "seconds": 10,
    "window": 16,
    "timeoutMs": 10000,
}
for path in files:
    for index, line in enumerate(path.read_text().splitlines(), 1):
        try:
            row = json.loads(line)
            event = row.get("event", row)
            kind = event.get("kind")
            if kind in ("load_result", "load_remote_result"):
                result = json.loads(event["value"])
                # Early 0.2 builds omitted default-valued fields; preserve their documented values.
                result["plan"] = defaults_v02 | result["plan"]
                result["source"] = str(path)
                result["utc"] = row.get("utc")
                result["goodputBytesPerSecond"] = (
                    result["deliveredBytes"] * 1000 // max(1, result["elapsedMs"])
                )
                result["accounted"] = result["sent"] == sum(
                    result[k] for k in ("delivered", "timedOut", "failed", "cancelled")
                )
                result["allAcknowledged"] = (
                    result["sent"] > 0 and result["sent"] == result["delivered"]
                )
                previous = results.get(result["run"])
                if previous is None or kind == "load_result":
                    results[result["run"]] = result
            elif kind == "replica":
                replicas.append(
                    {
                        "source": str(path),
                        "utc": row.get("utc"),
                        **json.loads(event["value"]),
                    }
                )
            elif kind in (
                "load_error",
                "error",
                "load_remote_timeout",
                "migration_failed",
                "migration_recovered",
                "migration_prepare",
                "migration_commit",
                "migration_takeover",
            ):
                issues.append(
                    {
                        "source": str(path),
                        "utc": row.get("utc"),
                        "kind": kind,
                        "value": event["value"],
                    }
                )
        except (ValueError, KeyError, TypeError) as error:
            issues.append(
                {"source": str(path), "line": index, "parseError": str(error)}
            )
report = {
    "format": "NearbyLab-0.2",
    "results": list(results.values()),
    "replicas": replicas,
    "events": issues,
}
encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
if args.output:
    args.output.write_text(encoded)
else:
    print(encoded, end="")
