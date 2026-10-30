#!/usr/bin/env python3
"""Generate clipboard-codepoint-map expectations using the pinned original Ghostty formatter."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SHA = "83fb95624b0439ee660c2679b43b38c8b63769cd"


def main():
    upstream, zig = (Path(value).resolve() for value in sys.argv[1:3])
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip() == SHA
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "."], cwd=upstream, check=True, stdout=subprocess.DEVNULL)
    mapping = lambda first, last, replacement: dict(first=first, last=last, replacement=replacement)
    maps = {
        "none": [],
        "all": [mapping(0, 0x10ffff, "X")],
        "spaces_and_newlines": [mapping(32, 32, "_"), mapping(10, 10, "NEWLINE"), mapping(9, 9, "TAB")],
        "overlap": [mapping(0x61, 0x7a, "a"), mapping(0x62, 0x64, "😀"), mapping(0x63, 0x63, "last")],
        "expansion": [mapping(0x2500, 0x2500, "--"), mapping(0x2502, 0x2502, "|"), mapping(0x3a3, 0x3a3, "SUM  ")],
        "graphemes": [mapping(0x301, 0x301, "<accent>"), mapping(0xfe0f, 0xfe0f, ""), mapping(0x200d, 0x200d, "+"), mapping(0x1f000, 0x1ffff, "emoji")],
        "empty": [mapping(0, 0x10ffff, "")],
        "no_recursion": [mapping(ord("a"), ord("a"), "b\n"), mapping(ord("b"), ord("b"), "c"), mapping(10, 10, "X")],
        "html_escape": [mapping(ord("a"), ord("a"), "<&\"'😀")],
    }
    texts = {
        "ascii": "a  bcd  \r\n ef  \r\nlast",
        "empty": "",
        "written_spaces": "   \r\n    \r\n ",
        "synthetic_spaces": "a\x1b[6Gb\r\n\r\nx",
        "tabs": "a\tb\r\n \t\r\nc\t",
        "box_and_sigma": "─┐│Σ─\r\nΣ  \r\n ─",
        "graphemes": "é😀👩‍💻✈️ \r\n 界 \r\n ́x ́ ",
        "unicode_spaces": "a\u00a0b\u3000 \r\né\u2028\u2029\x85c",
        "wrap": "abc   defghi  jk lmno   pqrstuvwxyz",
        "spacer_head": "1234567界ab😀c\r\nz",
        "styled": "\x1b[31;4ma b \x1b[0m\r\n\x1b[44m\x1b[2K\x1b[0m\r\nc",
        "flags": "\x1b[1;2;3;5;7;8;9;53;4:3ma b\x1b[0mc\r\n\x1b[4:2md\x1b[4:4me\x1b[4:5mf",
        "colors": "\x1b[38;2;1;2;3;48;2;4;5;6;58;2;7;8;9ma\x1b[0m\x1b[31mb\x1b[32mc\x1b[0md",
        "palette": "\x1b]4;1;#112233\x07\x1b[31;44;58;5;1ma b\x1b[0m",
        "theme": "\x1b]10;#ffeedd\x07\x1b]11;#001122\x07abc",
        "links": '\x1b]8;id=a;https://example.org/?x=1&y="\x07\x1b[31ma b\x1b[32mc\x1b]8;;\x07d\r\n\x1b]8;;https://example.org/界\x07é\x1b]8;;\x07z',
        "background_only": "a\r\n\x1b[44m\x1b[2K\x1b[0m\r\nz",
        "blank_steps": "\x1b[31ma\x1b[5Gb\r\n\x1b[0m\x1b[3Gc",
        "linked_blanks": "\x1b]8;id=a;https://example.org\x07a\x1b[5Gb\r\n\x1b[3Gc\x1b]8;;\x07d",
        "wrapped_link_blanks": "\x1b]8;id=a;https://example.org\x07a\x1b[8Gbcd\x1b]8;;\x07e",
        "styled_spaces": "\x1b[31ma  \x1b[0m  \r\n\x1b[4:3m \u0301  \x1b[0mb",
        "indexed_and_rgb": "\x1b[31ma\x1b[38;2;17;35;53mb\x1b[31mc",
    }
    palette_setup = "".join(f"\x1b]4;{i};#{(0x102030 + i * 0x010305) & 0xffffff:06x}\x07" for i in range(256))
    cases = [dict(name=f"{name}_{map_name}", columns=8, rows=4, text=text, paletteSetup=palette_setup, mappings=values)
             for name, text in texts.items() for map_name, values in maps.items()]
    build = ROOT / "build/terminal-copy-oracle"
    build.mkdir(parents=True, exist_ok=True)
    (build / "cases.json").write_text(json.dumps(cases, ensure_ascii=True))
    helper = ROOT / "scripts/terminal-ghostty-copy-oracle.zig"
    (build / "main.zig").write_bytes(helper.read_bytes())
    (build / "build.zig.zon").write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.17.0", .dependencies = .{ .ghostty = .{ .path = ' + json.dumps(os.path.relpath(upstream, build)) + ' } }, .paths = .{""} }')
    (build / "build.zig").write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const dep = b.dependency("ghostty", .{ .target = target, .optimize = .safe, .simd = false, .@"emit-lib-vt" = true, .@"emit-xcframework" = false });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .safe, .link_libc = true });
    root.addImport("ghostty-vt", dep.module("ghostty-vt"));
    b.installArtifact(b.addExecutable(.{ .name = "copy-oracle", .root_module = root }));
}
''')
    env = os.environ | {"ZIG_GLOBAL_CACHE_DIR": os.environ.get("ZIG_GLOBAL_CACHE_DIR", str(ROOT / "build/upstream/zig-cache-0.17"))}
    subprocess.run([str(zig), "build"], cwd=build, check=True, env=env)
    result = subprocess.check_output([str(build / "zig-out/bin/copy-oracle")], text=True)
    actual = [json.loads(line) for line in result.split("\n") if line]
    assert len(actual) == len(cases)
    for case, value in zip(cases, actual):
        assert case["name"] == value["name"]
        case["selections"] = value["selections"]
    fixture = ROOT / "kinetica-terminal/test/generated/TerminalCopyFixtures.kt"
    encoded = [base64.b64encode(json.dumps(case, separators=(",", ":")).encode()).decode() for case in cases]
    fixture.write_text('// Generated by scripts/generate-terminal-copy.py; do not edit.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalCopyFixtures(): List<String> = listOf(\n' + ''.join('    listOf(' + ', '.join('"' + value[i:i+30000] + '"' for i in range(0, len(value), 30000)) + ').joinToString(""),\n' for value in encoded) + ')\n')
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = dict(commit=SHA, zig=subprocess.check_output([str(zig), "version"], text=True).strip(),
        cases=len(cases), selections=sum(len(case["selections"]) for case in cases),
        helperSha256=digest(helper), generatorSha256=digest(Path(__file__)), fixtureSha256=digest(fixture),
        sources={path: digest(upstream / path) for path in ["src/terminal/formatter.zig", "src/terminal/style.zig", "src/terminal/Screen.zig", "src/config/Config.zig", "src/Surface.zig"]})
    (ROOT / "kinetica-terminal/test-upstream/ghostty/copy-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f'Generated {len(cases)} cases / {manifest["selections"]} selections / {manifest["selections"] * 16} formatted outputs')


if __name__ == "__main__":
    main()
