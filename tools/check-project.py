#!/usr/bin/env python3
"""Check public sample configuration and curated data; Python standard library only."""

import hashlib
import json
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent


def check(condition, message):
    if not condition:
        raise SystemExit(message)


# Regeneration must be portable, deterministic and independent of local signing.
ios = root / "iosApp"
generated = [
    ios / "Info.plist",
    *ios.glob("*.entitlements"),
    ios / "NearbyLab.xcodeproj/project.pbxproj",
    *ios.glob("NearbyLab.xcodeproj/xcshareddata/xcschemes/*.xcscheme"),
]
before = {path: path.read_bytes() for path in generated}
subprocess.run([sys.executable, str(ios / "generate_project.py")], check=True)
check(
    all(path.read_bytes() == content for path, content in before.items()),
    "Generated Xcode files differ; regenerate and commit them.",
)

service_source = (
    root / "shared/src/commonMain/kotlin/org/bigmeco/nearbylab/Transport.kt"
).read_text()
service = re.search(r'SERVICE_ID = "([^"]+)"', service_source).group(1)
check(
    f'serviceID: "{service}"' in (ios / "AppleNearbyTransport.swift").read_text(),
    "Android and Apple service IDs differ.",
)
bonjour = "_" + hashlib.sha256(service.encode()).digest()[:6].hex().upper() + "._tcp"
check(
    plistlib.loads((ios / "Info.plist").read_bytes())["NSBonjourServices"] == [bonjour],
    "Bonjour service differs from the pinned SDK hash format.",
)

resources = root / "shared/src/commonMain/composeResources"
strings = []
for locale in ("values", "values-ru"):
    entries = ET.parse(resources / locale / "strings.xml").getroot()
    keys = [entry.attrib["name"] for entry in entries]
    check(len(keys) == len(set(keys)), f"Duplicate strings in {locale}")
    strings.append(
        {
            entry.attrib["name"]: sorted(re.findall(r"%\d+\$[sd]", entry.text or ""))
            for entry in entries
        }
    )
check(strings[0] == strings[1], "Localization keys or format arguments differ.")

# Keep public benchmark claims internally consistent without exposing private logs.
data = json.loads((root / "docs/benchmarks/2026-11-09.json").read_text())
for run in data["runs"]:
    check(
        run["sent"]
        == sum(run[key] for key in ("delivered", "timedOut", "failed", "cancelled")),
        f"Unaccounted deliveries in {run['id']}",
    )
    check(
        run["goodputBytesPerSecond"]
        == run["deliveredBytes"] * 1000 // max(1, run["elapsedMs"]),
        f"Inconsistent goodput in {run['id']}",
    )
    check(
        run["latency"]["samples"] == run["delivered"],
        f"Latency sample mismatch in {run['id']}",
    )

for document in [*root.glob("*.md"), *root.glob("docs/*.md")]:
    for target in re.findall(r"(?<!!)\[[^\]]*\]\(([^)]+)\)", document.read_text()):
        if target.startswith(("http:", "https:", "#", "mailto:")):
            continue
        path = target.split("#")[0]
        check(
            (document.parent / path).exists(),
            f"Broken link in {document.name}: {target}",
        )

print(
    f"Project checks passed: generated Xcode files, service ID, {len(strings[0])} localized strings, "
    f"{len(data['runs'])} curated runs, documentation links."
)
