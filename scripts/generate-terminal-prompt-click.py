#!/usr/bin/env python3
"""Prompt-click coordinates using original Ghostty terminal state and prompt iteration."""
import base64
import hashlib
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
    for mode in (1, 2):
        prompt = osc(f"A;click_events={mode}") + "$ " + osc("B")
        setups = {
            "first_row": prompt + "abc",
            "offset": "output\r\n\r\n" + prompt + "abc",
            "continuation": "out\r\n" + prompt + "abc\r\n" + osc("P;k=c") + "> " + osc("B") + "def",
            "soft_wrap": "out\r\n" + prompt + "x" * 30,
            "history": prompt + "x" * 100,
            "old_prompt": prompt + "cmd\r\n" + osc("C") + "out\r\n" + prompt + "next",
            "after_output": prompt + "cmd\r\n" + osc("C") + "output",
            "output_same_row": prompt + "cmd" + osc("C"),
            "unmarked_input": prompt + "\x1b[2J" + osc("B") + "abc",
            "isolated_continuation": "out\r\n" + prompt + "\x1b[2J\x1b[4;1H" + osc("P;k=c") + "> ",
            "cutoff_continuations": prompt + "\x1b[2J\x1b[H" + osc("P;k=c") + "x\r\ny\r\nz",
            "alternate": prompt + "\x1b[?1049h" + prompt,
            "alternate_return": prompt + "\x1b[?1049h" + prompt + "\x1b[?1049l",
            "reset": prompt + "\x1bc",
            "no_negotiation": osc("A") + "$ " + osc("B"),
        }
        for name, text in setups.items():
            events = [dict(text=text)] + [dict(x=x, y=y) for y in range(6) for x in (0, 5, 11)]
            result.append(dict(name=f"{name}_{mode}", events=events))
        result.append(dict(name=f"scrolled_{mode}", events=[dict(text=prompt + "x" * 100), dict(scroll=2)] +
            [dict(x=5, y=y) for y in range(6)]))
    for mode in ("line", "m", "v", "w"):
        prompt = osc(f"A;cl={mode}") + "$ " + osc("B")
        setups = {
            "end": prompt + "hello",
            "start": prompt + "hello\x1b[1;3H",
            "middle": prompt + "hello\x1b[1;5H",
            "output_cursor": prompt + "hello" + osc("C") + "\x1b[1;1H",
            "input_cell_output_cursor": prompt + "hello" + osc("C") + "\x1b[1;4H",
            "prompt_cells": prompt + "ab" + osc("P;k=r") + "xx" + osc("B") + "cd\x1b[1;3H",
            "wide": prompt + "a界b😀c\x1b[1;3H",
            "combining": prompt + "aéb\x1b[1;3H",
            "pending_wrap": prompt + "1234567890",
            "spacer_head": prompt + "123456789界x",
            "soft_wrap_end": prompt + "x" * 30,
            "soft_wrap_start": prompt + "x" * 30 + "\x1b[1;3H",
            "hard_wrap_end": prompt + "abc\r\ndef",
            "hard_wrap_start": prompt + "abc\r\ndef\x1b[1;3H",
            "input_until_eol": osc(f"A;cl={mode}") + "$ " + osc("I") + "abc\r\ndef",
            "noncontinuation": prompt + "x" * 30 + "\x1b[2;1H" + osc("A") + "\x1b[1;3H",
            "history": prompt + "x" * 100,
            "offset": "out\r\n" + prompt + "hello",
            "app_cursor": prompt + "hello\x1b[?1h",
            "alternate": prompt + "\x1b[?1049h" + prompt + "abc",
        }
        for name, text in setups.items():
            result.append(dict(name=f"cl_{mode}_{name}", events=[dict(text=text)] +
                [dict(x=x, y=y) for y in range(6) for x in range(12)]))
        result.append(dict(name=f"cl_{mode}_scrolled", events=[dict(text=prompt + "x" * 100), dict(scroll=2)] +
            [dict(x=x, y=y) for y in range(6) for x in (0, 5, 11)]))
    return result


def main():
    upstream = Path(sys.argv[1]).resolve()
    zig = Path(sys.argv[2]).resolve()
    assert subprocess.check_output(["git","rev-parse","HEAD"],cwd=upstream,text=True).strip() == SHA
    subprocess.run(["git","diff","--exit-code","HEAD","--","."],cwd=upstream,check=True,stdout=subprocess.DEVNULL)
    build = ROOT/"build/terminal-prompt-click-oracle"
    build.mkdir(parents=True,exist_ok=True)
    corpus = cases()
    (build/"cases.json").write_text(json.dumps(corpus,ensure_ascii=True))
    helper = ROOT/"scripts/terminal-ghostty-prompt-click-oracle.zig"
    # Compile the original coordinate helper verbatim (only its types are generic here).
    surface = (upstream / "src/Surface.zig").read_text()
    function = surface.split("fn promptClickRelativeRow(", 1)[1].split("\n}\n", 1)[0]
    body = function.split(") u32 {", 1)[1]
    (build/"main.zig").write_text(helper.read_text() +
        "\nfn promptClickRelativeRow(pages: anytype, prompt_pin: anytype, click_pin: anytype) u32 {" + body + "\n}\n")
    (build/"build.zig.zon").write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.17.0", .dependencies = .{ .ghostty = .{ .path = '+json.dumps(os.path.relpath(upstream,build))+' } }, .paths = .{""} }')
    (build/"build.zig").write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const dep = b.dependency("ghostty", .{ .target = target, .optimize = .safe, .simd = false, .@"emit-lib-vt" = true, .@"emit-xcframework" = false });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .safe, .link_libc = true });
    root.addImport("ghostty-vt", dep.module("ghostty-vt"));
    b.installArtifact(b.addExecutable(.{ .name = "prompt-click-oracle", .root_module = root }));
}
''')
    env = os.environ | {"ZIG_GLOBAL_CACHE_DIR":os.environ.get("ZIG_GLOBAL_CACHE_DIR",str(ROOT/"build/upstream/zig-cache-0.17"))}
    subprocess.run([str(zig),"build"],cwd=build,check=True,env=env)
    output = subprocess.run([str(build/"zig-out/bin/prompt-click-oracle")],capture_output=True,text=True)
    if output.returncode: print(output.stderr,file=sys.stderr)
    output.check_returncode()
    actual = [json.loads(line) for line in output.stdout.splitlines()]
    assert len(corpus) == len(actual)
    for case, result in zip(corpus,actual):
        assert case["name"] == result["name"]
        case["snapshots"] = result["snapshots"]
    fixture = ROOT/"kinetica-terminal/test/generated/TerminalPromptClickFixtures.kt"
    encoded = [base64.b64encode(json.dumps(c,separators=(',',':')).encode()).decode() for c in corpus]
    fixture.write_text('// Generated by scripts/generate-terminal-prompt-click.py; do not edit.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalPromptClickFixtures(): List<String> = listOf(\n'+''.join('    "'+v+'",\n' for v in encoded)+')\n')
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    manifest = dict(commit=SHA,zig=subprocess.check_output([str(zig),"version"],text=True).strip(),
        cases=len(corpus),snapshots=sum(len(c["snapshots"]) for c in corpus),

        helperSha256=digest(helper),generatorSha256=digest(Path(__file__)),fixtureSha256=digest(fixture),
        sources={p:digest(upstream/p) for p in ["src/terminal/Terminal.zig","src/terminal/stream_terminal.zig","src/terminal/stream.zig","src/terminal/Parser.zig","src/terminal/PageList.zig","src/terminal/Screen.zig","src/Surface.zig"]})
    (ROOT/"kinetica-terminal/test-upstream/ghostty/prompt-click-manifest.json").write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'Generated {len(corpus)} prompt-click cases / {manifest["snapshots"]} checkpoints')


if __name__ == "__main__": main()
