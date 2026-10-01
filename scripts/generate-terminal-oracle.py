#!/usr/bin/env python3
"""Generate common Kotlin goldens from the pinned original libghostty-vt.

Usage: python3 scripts/generate-terminal-oracle.py /path/to/ghostty
First build that checkout with Zig 0.16.0:
  zig build -Demit-lib-vt=true -Doptimize=ReleaseFast -Dsimd=false -Demit-xcframework=false
This tool compiles a test-only C helper, then feeds it raw bytes, resize and key events.
It never reads Kinetica output. Ordinary tests need neither Zig nor libghostty.
"""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SHA = "4da7523faba68ccb4042ea20585817098a51c015"

# The host supplies these defaults to both engines; they are configuration, not expectations.
DEFAULT_COLORS = [0xd4d4d4, 0x1e1e1e, 0xeeeeee]
DEFAULT_PALETTE = [0x000000, 0xcd3131, 0x0dbc79, 0xe5e510, 0x2472c8, 0xbc3fbc, 0x11a8cd, 0xe5e5e5,
                   0x666666, 0xf14c4c, 0x23d18b, 0xf5f543, 0x3b8eea, 0xd670d6, 0x29b8db, 0xffffff]
for index in range(16, 256):
    if index >= 232:
        DEFAULT_PALETTE.append((8 + (index - 232) * 10) * 0x10101)
    else:
        n = index - 16
        channels = [0 if c == 0 else 55 + c * 40 for c in (n // 36, n // 6 % 6, n % 6)]
        DEFAULT_PALETTE.append(channels[0] << 16 | channels[1] << 8 | channels[2])


def encoded(value):
    data = base64.b64encode(json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode()).decode()
    return 'listOf(' + ','.join('"' + data[i:i+8000] + '"' for i in range(0, len(data), 8000)) + ').joinToString("")'


def write_generated(path, text):
    # Reproducibility checks must not truncate a fixture a concurrent compiler is reading.
    if path.exists() and path.read_bytes() == text.encode("utf-8"):
        return
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="", dir=path.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(text)
            output.close()
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


def tui_events(recording):
    """Coalesce producer writes without crossing any resize or interaction checkpoint."""
    checkpoints = {item["event"] for item in recording["checkpoints"]}
    events, pending = [], bytearray()
    def flush():
        if pending:
            events.append({"bytes": pending.hex()})
            pending.clear()
    for index, event in enumerate(recording["events"]):
        if "resize" in event:
            flush()
            events.append(event)
        else:
            pending.extend(bytes.fromhex(event["bytes"]))
        if index + 1 in checkpoints:
            flush()
    flush()
    return events


def main():
    upstream = Path(sys.argv[1]).resolve()
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip()
    if actual != SHA:
        raise SystemExit(f"Expected {SHA}, got {actual}")
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "."], cwd=upstream,
                   check=True, stdout=subprocess.DEVNULL)
    build = ROOT / "build/terminal-oracle"
    build.mkdir(parents=True, exist_ok=True)
    executable = build / "ghostty-oracle"
    library = upstream / "zig-out/lib"
    subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-I", str(upstream / "include"),
        str(ROOT / "scripts/terminal-ghostty-oracle.c"), "-L", str(library), "-lghostty-vt",
        "-Wl,-rpath," + str(library), "-o", str(executable)], check=True)
    cases = json.loads((ROOT / "kinetica-terminal/test-upstream/ghostty/scenarios.json").read_text())
    target = ROOT / "kinetica-terminal/test/generated"
    manifest = {"repository": "https://github.com/ghostty-org/ghostty", "commit": SHA,
                "helperSha256": hashlib.sha256((ROOT / "scripts/terminal-ghostty-oracle.c").read_bytes()).hexdigest(),
                "librarySha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                  for p in library.glob("libghostty-vt.*") if p.is_file()},
                "colorConfiguration": {"colors": DEFAULT_COLORS, "palette": DEFAULT_PALETTE},
                "deviceConfiguration": {"primary": [1, 2], "secondary": [0, 0, 0], "tertiary": 0, "xtversion": "Kinetica"},
                "cursorConfiguration": {"style": "underline", "blink": False}, "snapshots": {}}
    for case in cases:
        input_license = ""
        if source := case.get("tuiRecording"):
            recorded = (ROOT / "kinetica-terminal/test-upstream/tui" / source["path"]).read_bytes()
            if hashlib.sha256(recorded).hexdigest() != source["sha256"]:
                raise ValueError(f"Archived TUI recording changed: {source['path']}")
            if case["events"] != tui_events(json.loads(recorded)):
                raise ValueError(f"Scenario must replay the exact TUI recording: {case['name']}")
            input_license = f"// Input: recorded local {case['name']} workflow; see test-upstream/tui.\n"
        if recording := case.get("alacrittyRecording"):
            with tarfile.open(ROOT / "kinetica-terminal/test-upstream/alacritty/references.tar.gz") as archive:
                recorded = archive.extractfile(recording["path"]).read()
            if hashlib.sha256(recorded).hexdigest() != recording["sha256"]:
                raise ValueError(f"Archived recording changed: {recording['path']}")
            if len(case["events"]) != 1 or case["events"][0]["write"].encode() != recorded:
                raise ValueError(f"Scenario must replay the exact recording: {case['name']}")
            input_license = f"// Input: Alacritty {recording['commit']} (Apache-2.0); see test-upstream/alacritty.\n"
        commands = [f"N {case['columns']} {case['rows']} {case['history']}",
                    "C " + " ".join(f"{c:06x}" for c in DEFAULT_COLORS + DEFAULT_PALETTE)]
        for event in case["events"]:
            if "write" in event:
                commands.append("W " + event["write"].encode().hex())
            elif "bytes" in event:
                commands.append("W " + event["bytes"])
            elif "resize" in event:
                commands.append("R " + " ".join(str(x) for x in event["resize"]))
            elif "keys" in event:
                for key in event["keys"]:
                    commands.append(f"K {key['upstream']} {key['modifiers']}")
            elif "mouse" in event:
                for mouse in event["mouse"]:
                    commands.append("M " + " ".join(str(mouse[key]) for key in ("action", "button", "modifiers", "column", "row")))
            else:
                raise ValueError(event)
            commands.append("S")
        result = subprocess.run([str(executable)], input="\n".join(commands) + "\n", text=True, capture_output=True, check=True)
        snapshots = [json.loads(line) for line in result.stdout.splitlines()]
        assert len(snapshots) == len(case["events"])
        payload = json.dumps(snapshots, ensure_ascii=True, separators=(",", ":"))
        manifest["snapshots"][case["name"]] = hashlib.sha256(payload.encode()).hexdigest()
        write_generated(target / ("Ghostty_" + case["name"] + ".kt"), f'''// Generated by the original libghostty-vt at {SHA} (MIT).
{input_license}// See test-upstream/ghostty and third-party/GHOSTTY-LICENSE. Do not hand-edit.
package io.heapy.kinetica.terminal
internal fun ghostty_{case['name']}() = GhosttyFixture(
    {encoded(case)},
    {encoded(snapshots)},
)
''')
    tests = "\n".join(f"    @Test fun {c['name']}() = checkGhostty(ghostty_{c['name']}())" for c in cases)
    widths = json.loads(subprocess.check_output([str(executable)], input="U\n", text=True))
    manifest["unicodeWidthsSha256"] = hashlib.sha256(json.dumps(widths, separators=(",", ":")).encode()).hexdigest()
    write_generated(target / "GhosttyUnicodeWidths.kt", f'''// Generated by original libghostty-vt {SHA}. Do not hand-edit.
// Ghostty MIT / Unicode data licenses: see third-party and test-upstream.
package io.heapy.kinetica.terminal
internal fun ghosttyUnicodeWidths(): String = {encoded(widths)}
''')
    write_generated(target / "GhosttyOracleTest.kt", f'''// Generated test cases. Upstream: Ghostty {SHA} (MIT).
package io.heapy.kinetica.terminal
import kotlin.test.Test
internal fun ghosttyDefaultPalette(): IntArray = intArrayOf({','.join(hex(c) for c in DEFAULT_PALETTE)})
class GhosttyOracleTest {{
{tests}
}}
''')
    write_generated(ROOT / "kinetica-terminal/test-upstream/ghostty/manifest.json", json.dumps(manifest, indent=2) + "\n")
    print(f"Generated {len(cases)} scenarios, {sum(len(c['events']) for c in cases)} intermediate state comparisons")


if __name__ == "__main__":
    main()
