#!/usr/bin/env python3
"""Compare the existing graphics corpus against original Ghostty before/at 1.4.

Usage: python3 scripts/compare-terminal-graphics-release.py OLD_CHECKOUT RELEASE_CHECKOUT
Both checkouts must already contain their built libghostty-vt. This probe does not
rewrite fixtures or their pin. Full results go to build/reports/ghostty-1.4-graphics.json.
"""
import hashlib
import json
from pathlib import Path
import runpy
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
OLD = "4da7523faba68ccb4042ea20585817098a51c015"
RELEASE = "83fb95624b0439ee660c2679b43b38c8b63769cd"
GENERATOR = ROOT / "scripts/generate-terminal-graphics-execution.py"
HELPER = ROOT / "scripts/terminal-ghostty-graphics-oracle.c"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(checkout, expected, output, png_flags):
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout, text=True).strip()
    if actual != expected:
        raise SystemExit(f"Expected {expected}, got {actual} at {checkout}")
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "."], cwd=checkout,
                   check=True, stdout=subprocess.DEVNULL)
    library = checkout / "zig-out/lib"
    subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-I", str(checkout / "include"),
                    str(HELPER), "-L", str(library), "-lghostty-vt", "-Wl,-rpath," + str(library),
                    "-o", str(output), *png_flags], check=True)
    return {"commit": actual, "librarySha256": digest(library / "libghostty-vt.0.dylib")}


def snapshots(executable, case):
    result = subprocess.run([str(executable)], input="\n".join(case["commands"]) + "\n",
                            text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(f"{executable.name}: {case['name']}: {result.stderr}")
    states = [json.loads(line) for line in result.stdout.splitlines()]
    assert len(states) == case["commands"].count("S"), case["name"]
    # Compare the same observable fields as the committed common execution fixtures.
    output = []
    for state in states:
        selected = {key: state[key] for key in ("kittyImages", "kittyPlacements", "kittyLayers",
                    "cursor", "pendingWrap", "history", "alternate", "replies")}
        # Placement maps and layer membership have no semantic iteration order.
        selected["kittyPlacements"] = sorted(selected["kittyPlacements"],key=lambda item: json.dumps(item))
        selected["kittyLayers"] = [sorted(layer,key=lambda item: json.dumps(item)) for layer in selected["kittyLayers"]]
        selected["textRows"] = [[row["wrapped"], [[cell[0], cell[1]] for cell in row["cells"]]]
                                for row in state["lines"]]
        output.append(selected)
    return output


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    version = subprocess.check_output(["pkg-config", "--modversion", "libpng"], text=True).strip()
    if version != "1.6.59":
        raise SystemExit("Expected pinned libpng 1.6.59")
    png_flags = shlex.split(subprocess.check_output(["pkg-config", "--cflags", "--libs", "libpng"], text=True))
    directory = ROOT / "build/terminal-release-comparison"
    directory.mkdir(parents=True, exist_ok=True)
    old_exe, new_exe = directory / "old", directory / "release"
    old = build(Path(sys.argv[1]).resolve(), OLD, old_exe, png_flags)
    new = build(Path(sys.argv[2]).resolve(), RELEASE, new_exe, png_flags)
    generator = runpy.run_path(str(GENERATOR))
    if generator["zlib"].ZLIB_RUNTIME_VERSION != "1.2.12":
        raise SystemExit("Expected pinned zlib 1.2.12")
    cases = generator["cases"]()
    report = {"old": old, "release": new, "libpng": version, "zlib": "1.2.12",
              "scriptSha256": digest(Path(__file__)), "generatorSha256": digest(GENERATOR),
              "helperSha256": digest(HELPER), "baseHelperSha256": digest(ROOT / "scripts/terminal-ghostty-oracle.c"),
              "corpusSha256": hashlib.sha256(json.dumps(cases,sort_keys=True,separators=(",", ":")).encode()).hexdigest(),
              "cases": len(cases), "snapshots": 0, "differences": []}
    for case in cases:
        before, after = snapshots(old_exe, case), snapshots(new_exe, case)
        report["snapshots"] += len(before)
        differences = [{"checkpoint": i, "fields": {key: {"old": a[key], "release": b[key]}
                       for key in a if a[key] != b[key]}}
                       for i, (a, b) in enumerate(zip(before, after)) if a != b]
        if differences:
            report["differences"].append({"name": case["name"], "commands": case["commands"],
                                          "differences": differences})
    destination = ROOT / "build/reports/ghostty-1.4-graphics.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Compared {report['cases']} cases / {report['snapshots']} checkpoints; "
          f"{len(report['differences'])} cases changed. Report: {destination}")
    for difference in report["differences"]:
        print(json.dumps(difference["name"],ensure_ascii=True) + ": " +
              ", ".join(sorted({field for step in difference["differences"] for field in step["fields"]})))


if __name__ == "__main__":
    main()
