#!/usr/bin/env python3
"""Original Ghostty line/word selections, gestures and link bounds; no Kinetica expectations."""
import base64
import hashlib
import itertools
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SHA = "83fb95624b0439ee660c2679b43b38c8b63769cd"


def cases():
    result = []
    osc = lambda value: "\x1b]133;" + value + "\x07"
    states = {"prompt": osc("P;k=r"), "input": osc("B"), "output": osc("C")}
    for order in itertools.permutations(states):
        text = "".join(states[name] + value for name, value in zip(order, ["abc", "defghi", "jkl"]))
        result.append(dict(name="same_row_" + "_".join(order), columns=20, rows=4, events=[dict(text=text)]))
        result.append(dict(name="wrapped_" + "_".join(order), columns=5, rows=4,
            events=[dict(text=text), dict(resize=[8,4]), dict(resize=[4,4])]))
    setups = {
        "select_all_empty": "",
        "select_all_only_whitespace": " \t\r\n  \r\n\t ",
        "select_all_outer_padding": "\r\n  界 \r\n é  \r\n\r\n",
        "select_all_combined_space": " \u0301 x \u0301 ",
        "wide_and_combining": states["prompt"] + "界é" + states["input"] + "😀界abc" + states["output"] + "xyz",
        "spacer_head": states["prompt"] + "abcd" + states["input"] + "界abc" + states["output"] + "z",
        "hard_lines": states["prompt"] + "abc\r\n" + states["input"] + "def\r\n" + states["output"] + "ghi",
        "blank_seams": states["prompt"] + " "+ states["input"] + "  " + states["output"] + "  ",
        "explicit_padding": states["prompt"] + "ab" + states["input"] + "c" + " "*12 + states["output"] + "de",
        "history": (states["prompt"] + "p" + states["input"] + "1234" + states["output"] + "5678")*4,
        "erase": states["prompt"] + "abc" + states["input"] + "defghi" + "\x1b[1;2H\x1b[2X",
        "input_until_eol": states["prompt"] + ">" + osc("I") + "abcd\r\nefgh",
        "trimmed_regions": states["prompt"] + " p " + states["input"] + "  cmd  " + states["output"] + " out ",
        "wide_endpoints": " 界 \r\n😀\r\n é ",
        "non_ascii_spaces": "\u00a0x\u3000\r\n \t\r\n z ",
        "blank_edges": "\r\n abc \r\n\r\n def ",
        "written_spaces": "a  \r\n b \r\nc",
    }
    for name,text in setups.items():
        result.append(dict(name=name,columns=5,rows=4,events=[dict(text=text)]))
    prompt = osc("A") + "p" + osc("B") + "input" + osc("C") + "out"
    for name, control in {"alternate": "\x1b[?1049h"+prompt+"\x1b[?1049l", "soft_reset":"\x1b[!p", "reset":"\x1bc"}.items():
        result.append(dict(name=name,columns=5,rows=4,events=[dict(text=prompt),dict(text=control)]))
    for redraw in ["0", "1", "last"]:
        result.append(dict(name="redraw_" + redraw, columns=5, rows=4,
            events=[dict(text=osc("A;redraw=" + redraw) + "abc" + osc("B") + "defghijkl"),
                    dict(resize=[8,4]), dict(resize=[4,4])]))
    for name, text, boundaries in [
        ("word_delimiters", "one  (two);three:a|b$VAR│x", None),
        ("word_empty_cells", "one\r\n\r\ntwo\x1b[1;7HX", None),
        ("word_unicode_spaces", "a\u00a0b\u3000c é😀z", None),
        ("word_custom_empty", "one two:three;four", [0]),
        ("word_custom_punctuation", "one-two:three;four", [0, ord("-"), ord(":")]),
        ("word_custom_wide", "a界界b😀😀c", [0, ord("界"), ord("😀")]),
        ("word_custom_combining", "éé x", [0, ord("e"), 0x301]),
    ]:
        for columns in [7, 30]:
            case = dict(name=name+"_"+str(columns), columns=columns, rows=5, events=[dict(text=text)])
            if boundaries is not None: case["word_boundaries"] = boundaries
            result.append(case)
    output_cases = {
        "output_two_commands": "before\r\n" + osc("A") + "$ " + osc("B") + "one" + osc("C") + "\r\n  first  \r\nsecond \r\n" + osc("A") + "$ " + osc("B") + "two" + osc("C") + "\r\nthird",
        "output_same_row": osc("A") + "$" + osc("B") + "cmd" + osc("C") + "ab" + osc("B") + "input" + osc("C") + "cd",
        "output_without_prompt": "first\r\nsecond",
        "output_before_prompt": "\r\n first  \r\n\r\n" + osc("A") + "$ " + osc("B") + "cmd",
        "output_blank_before_prompt": "\r\n\r\n" + osc("A") + "$",
        "output_blank_command": osc("A") + "$ " + osc("B") + "cmd" + osc("C") + "\r\n\r\n" + osc("A") + "$",
        "output_continuations": osc("A") + "$ " + osc("B") + "cmd\r\n" + osc("P;k=c") + "> " + osc("B") + "more" + osc("C") + "\r\nresult\r\n" + osc("A") + "$",
        "output_orphan_continuations": osc("P;k=c") + "> " + osc("B") + "a\r\n" + osc("P;k=c") + "> " + osc("B") + "b" + osc("C") + "\r\nresult",
        "output_wide_spaces": osc("A") + "$ " + osc("B") + "cmd" + osc("C") + "\r\n  界é😀  \r\nlast  ",
    }
    for name, text in output_cases.items():
        for columns in [7, 30]:
            result.append(dict(name=name+"_"+str(columns), columns=columns, rows=8, events=[dict(text=text)]))
    result.append(dict(name="pointer_geometry", columns=5, rows=4, pointer=True, events=[dict(text="A界bcdé f\r\nxx\r\n     \r\nYZ")]))
    return result


def main():
    upstream = Path(sys.argv[1]).resolve()
    zig = Path(sys.argv[2]).resolve()
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip() == SHA
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "."], cwd=upstream, check=True, stdout=subprocess.DEVNULL)
    build = ROOT / "build/terminal-semantic-line-oracle"
    build.mkdir(parents=True, exist_ok=True)
    corpus = cases()
    (build / "cases.json").write_text(json.dumps(corpus, ensure_ascii=True))
    helper = ROOT / "scripts/terminal-ghostty-semantic-line-oracle.zig"
    (build / "main.zig").write_text(helper.read_text())
    (build / "selection-codepoints.zig").write_bytes((upstream / "src/terminal/selection_codepoints.zig").read_bytes())
    (build/"build.zig.zon").write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.17.0", .dependencies = .{ .ghostty = .{ .path = '+json.dumps(os.path.relpath(upstream,build))+' } }, .paths = .{""} }')
    (build/"build.zig").write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const dep = b.dependency("ghostty", .{ .target = target, .optimize = .safe, .simd = false, .@"emit-lib-vt" = true, .@"emit-xcframework" = false });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .safe, .link_libc = true });
    root.addImport("ghostty-vt", dep.module("ghostty-vt"));
    b.installArtifact(b.addExecutable(.{ .name = "semantic-line-oracle", .root_module = root }));
}
''')
    env = os.environ | {"ZIG_GLOBAL_CACHE_DIR": os.environ.get("ZIG_GLOBAL_CACHE_DIR", str(ROOT/"build/upstream/zig-cache-0.17"))}
    subprocess.run([str(zig), "build"], cwd=build, check=True, env=env)
    output = subprocess.check_output([str(build/"zig-out/bin/semantic-line-oracle")], text=True)
    actual = [json.loads(line) for line in output.splitlines()]
    assert len(corpus) == len(actual)
    for case, result in zip(corpus, actual):
        assert case["name"] == result["name"]
        case["snapshots"] = result["snapshots"]
    fixture = ROOT/"kinetica-terminal/test/generated/TerminalSemanticLineFixtures.kt"
    encoded = [base64.b64encode(json.dumps(c, separators=(",", ":")).encode()).decode() for c in corpus]
    fixture.write_text("// Generated by scripts/generate-terminal-semantic-line.py; do not edit.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalSemanticLineFixtures(): List<String> = listOf(\n" + "".join('    listOf(' + ', '.join('"'+v[i:i+50000]+'"' for i in range(0, len(v), 50000)) + ').joinToString(""),\n' for v in encoded) + ")\n")
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    manifest = dict(commit=SHA, zig=subprocess.check_output([str(zig), "version"], text=True).strip(),
        initialPromptRedraw=False,
        cases=len(corpus), snapshots=sum(len(c["snapshots"]) for c in corpus),
        ranges=sum(len(s["ranges"]) for c in corpus for s in c["snapshots"]),
        selectAll=sum(len(c["snapshots"]) for c in corpus),
        pointerDrags=sum(len(s["pointerDrags"]) for c in corpus for s in c["snapshots"]),
        rectangles=sum(len(s["rectangles"]) for c in corpus for s in c["snapshots"]),
        outputSelections=sum(len(s["outputs"]) for c in corpus for s in c["snapshots"]),
        outputGestureSteps=sum(sum(len(g) for g in s["outputGestures"]) for c in corpus for s in c["snapshots"]),
        deepPresses=sum(len(s["deepPress"]) for c in corpus for s in c["snapshots"]),
        wordGestureSteps=sum(sum(len(g) for g in s["wordGestures"]) for c in corpus for s in c["snapshots"]),
        gestureSteps=sum(sum(len(g) for g in s["gestures"]) for c in corpus for s in c["snapshots"]),
        helperSha256=digest(helper), generatorSha256=digest(Path(__file__)), fixtureSha256=digest(fixture),
        sources={p:digest(upstream/p) for p in ["src/terminal/Terminal.zig", "src/terminal/c/terminal.zig", "src/terminal/stream_terminal.zig", "src/terminal/Screen.zig", "src/terminal/SelectionGesture.zig", "src/terminal/Selection.zig", "src/terminal/selection_codepoints.zig", "src/terminal/formatter.zig", "src/terminal/PageList.zig", "src/Surface.zig", "src/apprt/embedded.zig", "src/input/key_mods.zig", "src/surface_mouse.zig", "macos/Sources/Ghostty/Surface View/SurfaceView_AppKit.swift", "macos/Sources/Helpers/Extensions/UserDefaults+Extension.swift"]})
    (ROOT/"kinetica-terminal/test-upstream/ghostty/semantic-line-manifest.json").write_text(json.dumps(manifest, indent=2)+"\n")
    print(f'Generated {len(corpus)} cases / {manifest["snapshots"]} checkpoints / {manifest["ranges"]} cell ranges')


if __name__ == "__main__": main()
