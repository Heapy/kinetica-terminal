#!/usr/bin/env python3
"""Import pinned Alacritty reference recordings as portable Kotlin test data.

Usage: python3 scripts/import-terminal-fixtures.py /path/to/alacritty
The source JSON/recordings are archived verbatim; generated expectations are
derived only from upstream, never from Kinetica's output. No network access.
"""
import base64
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parent.parent
SHA = "d692748d3f61253ebe9f5094320120d22f6a046f"
FLAGS = {"BOLD": 1, "DIM": 2, "ITALIC": 4, "UNDERLINE": 8,
         "INVERSE": 16, "HIDDEN": 32, "STRIKEOUT": 64,
         "DOUBLE_UNDERLINE": 128, "UNDERCURL": 256,
         "DOTTED_UNDERLINE": 512, "DASHED_UNDERLINE": 1024}
NAMED = ["Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White",
         "BrightBlack", "BrightRed", "BrightGreen", "BrightYellow", "BrightBlue",
         "BrightMagenta", "BrightCyan", "BrightWhite"]


def color(value):
    if value is None:
        return -1
    if "Indexed" in value:
        return 0x1000000 | value["Indexed"]
    if "Spec" in value:
        rgb = value["Spec"]
        return rgb["r"] << 16 | rgb["g"] << 8 | rgb["b"]
    name = value["Named"]
    return -1 if name in ("Foreground", "Background") else 0x1000000 | NAMED.index(name)


def normalize(grid):
    raw = grid["raw"]
    # Alacritty stores newest/bottom first in a circular allocation. Its
    # historical visible_lines metadata can differ from grid.lines; retain
    # exactly raw.len logical rows and order oldest/top first.
    rows = [raw["inner"][(raw["zero"] + i) % len(raw["inner"])]
            for i in reversed(range(raw["len"]))]
    cells, ids, runs, wraps = [], {}, [], []
    for row in rows:
        wraps.append(any("WRAPLINE" in c["flags"] for c in row["inner"]))
        for c in row["inner"]:
            flags = set(c["flags"].split(" | ")) - {""}
            unknown = flags - FLAGS.keys() - {"WRAPLINE", "WIDE_CHAR", "WIDE_CHAR_SPACER", "LEADING_WIDE_CHAR_SPACER"}
            assert not unknown, unknown
            extra = c["extra"] or {}
            link = (extra.get("hyperlink") or {}).get("inner")
            # Generated automatic hyperlink IDs are implementation-specific.
            # Preserve explicit IDs and normalize only Alacritty's auto IDs.
            link_id = link["id"] if link else None
            if link_id is not None and re.fullmatch(r"\d+_alacritty", link_id):
                link_id = ""
            spacer = "WIDE_CHAR_SPACER" in flags
            # A tab marker is a blank grid cell with copy semantics, tested
            # independently; wide spacers contain no printable text.
            text = "" if spacer else c["c"].replace("\t", " ") + "".join(extra.get("zerowidth", []))
            cell = [text, 0 if spacer else 2 if "WIDE_CHAR" in flags else 1,
                    color(c["fg"]), color(c["bg"]), sum(FLAGS[f] for f in flags if f in FLAGS),
                    color(extra.get("underline_color")), link_id, link["uri"] if link else None]
            key = json.dumps(cell, ensure_ascii=True, separators=(",", ":"))
            if key not in ids:
                ids[key] = len(cells)
                cells.append(cell)
            idx = ids[key]
            if runs and runs[-1][1] == idx:
                runs[-1][0] += 1
            else:
                runs.append([1, idx])
    return {"cells": cells, "runs": runs, "wrapped": wraps, "lines": len(rows)}


def kotlin_bytes(data):
    value = base64.b64encode(data).decode("ascii")
    parts = [value[i:i+8000] for i in range(0, len(value), 8000)] or [""]
    # joinToString prevents JVM constant folding beyond the 64 KiB limit.
    return "listOf(\n" + ",\n".join('        "' + p + '"' for p in parts) + '\n    ).joinToString("")'


def main():
    upstream = Path(sys.argv[1]).resolve()
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip()
    if actual != SHA:
        raise SystemExit(f"Expected upstream {SHA}, got {actual}")
    fixtures = upstream / "alacritty_terminal/tests/ref"
    harness = (upstream / "alacritty_terminal/tests/ref.rs").read_text()
    names = re.search(r"ref_tests!\s*\{([^}]+)\}", harness).group(1).split()
    target = ROOT / "kinetica-terminal/test/generated"
    vendor = ROOT / "kinetica-terminal/test-upstream/alacritty"
    target.mkdir(parents=True, exist_ok=True)
    vendor.mkdir(parents=True, exist_ok=True)
    archive = io.BytesIO()
    hashes = {}
    paths = [Path("LICENSE-APACHE"), Path("alacritty_terminal/tests/ref.rs")]
    paths += [Path("alacritty_terminal/tests/ref") / n / f for n in names
              for f in ("alacritty.recording", "size.json", "grid.json", "config.json")]
    with tarfile.open(fileobj=archive, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for path in sorted(paths):
            data = (upstream / path).read_bytes()
            hashes[str(path)] = hashlib.sha256(data).hexdigest()
            entry = tarfile.TarInfo(str(path))
            entry.size = len(data)
            entry.mode = 0o644
            tar.addfile(entry, io.BytesIO(data))
    (vendor / "references.tar.gz").write_bytes(gzip.compress(archive.getvalue(), mtime=0))
    (vendor / "LICENSE-APACHE").write_bytes((upstream / "LICENSE-APACHE").read_bytes())
    (vendor / "manifest.json").write_text(json.dumps({"repository": "https://github.com/alacritty/alacritty",
        "commit": SHA, "files": hashes}, indent=2) + "\n")
    for name in names:
        directory = fixtures / name
        size = json.loads((directory / "size.json").read_text())
        config = json.loads((directory / "config.json").read_text())
        expected = normalize(json.loads((directory / "grid.json").read_text()))
        source = f'''// Generated from Alacritty {SHA}; Apache-2.0.
// Modified: neutral cell schema and run-length encoding. See test-upstream/alacritty.
// Regenerate with scripts/import-terminal-fixtures.py; do not edit expectations.
package io.heapy.kinetica.terminal

internal fun fixture_{name}(): ReplayFixture = ReplayFixture(
    "{name}", {size['columns']}, {size['screen_lines']}, {config['history_size']},
    {kotlin_bytes((directory / 'alacritty.recording').read_bytes())},
    {kotlin_bytes(json.dumps(expected, ensure_ascii=True, separators=(',', ':')).encode())},
)
'''
        (target / f"Alacritty_{name}.kt").write_text(source)
    tests = "\n".join(f"    @Test fun {name}() = checkReplay(fixture_{name}())" for name in names)
    (target / "AlacrittyReplayTest.kt").write_text(f'''// Generated from Alacritty {SHA}; Apache-2.0.
// Modified: replay through Kinetica and compare normalized state on every platform.
package io.heapy.kinetica.terminal
import kotlin.test.Test
class AlacrittyReplayTest {{
{tests}
}}
''')
    print(f"Imported {len(names)} fixtures; verbatim archive {len((vendor / 'references.tar.gz').read_bytes())} bytes")


if __name__ == "__main__":
    main()
