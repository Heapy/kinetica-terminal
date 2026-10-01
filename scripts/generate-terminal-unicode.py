#!/usr/bin/env python3
"""Generate shared terminal properties and tests from the archived, pinned UCD snapshot.

No downloads, host Unicode database, JVM APIs, or Kinetica test output are used.
Width/derived-break rules adapted from uucode components.zig (Jacob Sandlund,
MIT) and Ghostty uucode_config.zig (Mitchell Hashimoto/contributors, MIT).
See kinetica-terminal/third-party and test-upstream/unicode for attribution.
"""
import base64
import hashlib
import json
from pathlib import Path
import struct
import tarfile

ROOT = Path(__file__).resolve().parent.parent / "kinetica-terminal"
SOURCE = ROOT / "test-upstream/unicode"
LIMIT = 0x110000
CLASSES = ["Other", "Control", "Prepend", "CR", "LF", "Regional_Indicator", "SpacingMark",
           "L", "V", "T", "LV", "LVT", "ZWJ", "ZWNJ", "Extended_Pictographic",
           "Emoji_Modifier_Base", "Emoji_Modifier", "Indic_Extend", "Indic_Linker_Extend",
           "Indic_Linker_Other", "Indic_Consonant"]


def encoded(data):
    text = base64.b64encode(data).decode()
    return 'listOf(' + ','.join('"' + text[i:i+8000] + '"' for i in range(0, len(text), 8000)) + ').joinToString("")'


def interval(value):
    bounds = value.strip().split("..")
    return int(bounds[0], 16), int(bounds[-1], 16) + 1


def records(text):
    for line in text.splitlines():
        value = line.split("#")[0].strip()
        if value:
            fields = [part.strip() for part in value.split(";")]
            start, end = interval(fields[0])
            yield start, end, fields[1:]


def fill(array, start, end, value):
    array[start:end] = bytes([value]) * (end - start)


def main():
    manifest = json.loads((SOURCE / "manifest.json").read_text())
    data = {}
    with tarfile.open(SOURCE / "sources.tar.gz") as archive:
        for path, expected in manifest["files"].items():
            raw = archive.extractfile(path).read()
            if hashlib.sha256(raw).hexdigest() != expected:
                raise ValueError(f"Unicode source changed: {path}")
            data[path] = raw.decode()

    # General category: only distinctions used by the width policy are retained.
    category = bytearray(LIMIT)
    categories = {"Cc": 1, "Cs": 1, "Zl": 1, "Zp": 1, "Mn": 2, "Me": 2}
    first = None
    for line in data["ucd/UnicodeData.txt"].splitlines():
        fields = line.split(";"); cp = int(fields[0], 16); value = categories.get(fields[2], 0)
        if fields[1].endswith(", First>"):
            first = cp
        elif fields[1].endswith(", Last>"):
            assert first is not None
            fill(category, first, cp + 1, value); first = None
        else:
            category[cp] = value

    original = bytearray(LIMIT)
    for start, end, fields in records(data["ucd/auxiliary/GraphemeBreakProperty.txt"]):
        # The derived class distinguishes the variants of Extend below.
        fill(original, start, end, 21 if fields[0] == "Extend" else CLASSES.index(fields[0]))
    incb = bytearray(LIMIT); ignorable = bytearray(LIMIT)
    for start, end, fields in records(data["ucd/DerivedCoreProperties.txt"]):
        if fields[0] == "Default_Ignorable_Code_Point": fill(ignorable, start, end, 1)
        elif fields[0] == "InCB": fill(incb, start, end, {"Extend": 1, "Linker": 2, "Consonant": 3}[fields[1]])
    emoji = {name: bytearray(LIMIT) for name in ["Emoji_Modifier", "Emoji_Modifier_Base", "Extended_Pictographic"]}
    for start, end, fields in records(data["ucd/emoji/emoji-data.txt"]):
        if fields[0] in emoji: fill(emoji[fields[0]], start, end, 1)
    vsbase = bytearray(LIMIT)
    for line in data["ucd/emoji/emoji-variation-sequences.txt"].splitlines():
        value = line.split("#")[0].strip()
        if value: vsbase[int(value.split()[0], 16)] = 1
    wide = bytearray(LIMIT)
    eaw = data["ucd/extracted/DerivedEastAsianWidth.txt"]
    for line in eaw.splitlines():
        if "@missing:" in line:
            value, width = line.split("@missing:")[1].strip().split(";")
            fill(wide, *interval(value), int(width.strip() in ["Wide", "Fullwidth"]))
    for start, end, fields in records(eaw): fill(wide, start, end, int(fields[0] in ["W", "F"]))

    binary = bytearray(); previous = -1; ascii_values = []
    for cp in range(LIMIT):
        gc = category[cp]; gb = original[cp]; ic = incb[cp]
        modifier = emoji["Emoji_Modifier"][cp]
        if modifier: derived = CLASSES.index("Emoji_Modifier")
        elif emoji["Emoji_Modifier_Base"][cp]: derived = CLASSES.index("Emoji_Modifier_Base")
        elif emoji["Extended_Pictographic"][cp]: derived = CLASSES.index("Extended_Pictographic")
        elif ic == 1: derived = CLASSES.index("ZWJ" if cp == 0x200D else "Indic_Extend")
        elif ic == 2: derived = CLASSES.index("Indic_Linker_Extend" if gb == 21 else "Indic_Linker_Other")
        elif ic == 3: derived = CLASSES.index("Indic_Consonant")
        elif gb == 21:
            assert cp == 0x200C, hex(cp)
            derived = CLASSES.index("ZWNJ")
        else: derived = gb

        if gc == 1: width = 0
        elif cp == 0xAD: width = 1
        elif ignorable[cp]: width = 0
        elif cp == 0x2E3A: width = 2
        elif cp == 0x2E3B: width = 3
        elif wide[cp] or gb == CLASSES.index("Regional_Indicator"): width = 2
        else: width = 1
        zero = width == 0 or modifier or gc == 2 or gb in [CLASSES.index("V"), CLASSES.index("T"), CLASSES.index("Prepend")]
        standalone = 2 if cp == 0x20E3 else width
        terminal_width = 0 if zero and not modifier and gb != CLASSES.index("Prepend") else min(2, standalone)
        value = terminal_width | (int(zero) << 2) | (vsbase[cp] << 3) | (derived << 4)
        if cp < 128: ascii_values.append(value)
        if value != previous:
            binary.extend(struct.pack("<II", cp, value)); previous = value

    target = ROOT / "src/generated"; target.mkdir(exist_ok=True)
    (target / "TerminalUnicodeData.kt").write_text(f'''// Generated by scripts/generate-terminal-unicode.py. Do not hand-edit.
// Unicode {manifest['unicodeVersion']} snapshot from uucode {manifest['commit']}.
// Unicode data / uucode MIT / Ghostty MIT: see third-party and test-upstream/unicode.
package io.heapy.kinetica.terminal
import kotlin.io.encoding.Base64
internal object TerminalUnicodeData {{
    const val VERSION = "{manifest['unicodeVersion']}"
    private val ascii = intArrayOf({','.join(map(str, ascii_values))})
    private val ranges: IntArray = Base64.decode({encoded(binary)}).let {{ bytes ->
        IntArray(bytes.size / 4) {{ i ->
            val offset = i * 4
            (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
                ((bytes[offset + 2].toInt() and 255) shl 16) or ((bytes[offset + 3].toInt() and 255) shl 24)
        }}
    }}
    fun properties(code: Int): Int {{
        if (code !in 0..0x10ffff) return 1
        if (code < 128) return ascii[code]
        var low = 0; var high = ranges.size / 2 - 1
        while (low < high) {{
            val mid = (low + high + 1) / 2
            if (ranges[mid * 2] <= code) low = mid else high = mid - 1
        }}
        return ranges[low * 2 + 1]
    }}
}}
''')
    cases = "\n".join(line.split("#")[0].strip() for line in data["ucd/auxiliary/GraphemeBreakTest.txt"].splitlines() if line.split("#")[0].strip())
    (ROOT / "test/generated/UnicodeBreakCases.kt").write_text(f'''// Generated from the archived Unicode GraphemeBreakTest.txt. Unicode license; see third-party.
package io.heapy.kinetica.terminal
import kotlin.io.encoding.Base64
internal fun unicodeBreakCases(): String = Base64.decode({encoded(cases.encode())}).decodeToString()
''')
    print(f"Generated {len(binary) // 8} property ranges ({len(binary)} bytes) and {len(cases.splitlines())} Unicode break cases")


if __name__ == "__main__": main()
