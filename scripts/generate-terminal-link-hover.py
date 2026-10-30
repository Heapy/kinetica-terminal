#!/usr/bin/env python3
"""Generate OSC 8 hover cell sets from pinned Ghostty RenderState.linkCells."""
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
    end = "\x1b]8;;\x1b\\"
    def link(text, params="", uri="https://example.org"):
        return "\x1b]8;" + params + ";" + uri + "\x1b\\" + text + end
    setups = {
        "implicit_reopened": link("aa") + " " + link("bb"),
        "explicit_reopened": link("aa", "id=one") + " " + link("bb", "id=one"),
        "explicit_empty": link("aa", "id=") + " " + link("bb", "id="),
        "empty_vs_implicit": link("aa") + " " + link("bb", "id="),
        "nonempty_then_empty": link("aa", "id=one:id=") + " " + link("bb", "id=one"),
        "leading_empty_option": link("aa", ":id=one:id=two") + " " + link("bb", "id=two"),
        "middle_empty_option": link("aa", "id=one::id=two") + " " + link("bb", "id=one"),
        "trailing_empty_option": link("aa", "id=one:") + " " + link("bb", "id=one"),
        "malformed_before_id": link("aa", "incomplete:id=one") + " " + link("bb", "id=one"),
        "malformed_after_id": link("aa", "id=one:incomplete:id=two") + " " + link("bb", "id=one"),
        "invalid_close_keeps_pen": link("aa\x1b]8;id=one;\x1b\\bb"),
        "distinct_ids": link("aa", "id=one") + " " + link("bb", "id=two"),
        "distinct_uris": link("aa", "id=one") + " " + link("bb", "id=one", "https://other.org"),
        "parameter_lists": link("aa", "foo=x:id=one:bar=y") + " " + link("bb", "id=one"),
        "duplicate_ids": link("aa", "id=first:id=last") + " " + link("bb", "id=first") + " " + link("cc", "id=last"),
        "same_pen_disjoint": link("aa\x1b[3;4Hbb\x1b[2;2Hcc"),
        "explicit_disjoint": link("aa", "id=one") + "\x1b[3;4H" + link("bb", "id=one"),
        "wrapped_wide": link("abc界é😀界defghijk"),
        "wide_spacer": "12345678901" + link("界xy"),
        "unicode_ids": link("aa", "id=界") + " " + link("bb", "id=界"),
        "hidden_and_underlined": link("aa\x1b[8mbb\x1b[0;4mcc\x1b[4:3mdd\x1b[0m"),
        "erase": link("abcdefgh") + "\x1b[1;3H\x1b[3X",
        "insert_delete": link("abcdefgh") + "\x1b[1;3H\x1b[2@\x1b[1;7H\x1b[P",
        "overwrite_identity": link("abcdefgh") + "\x1b[1;3H" + link("XY"),
        "copied_rectangle": link("abcd") + "\x1b[1;1;1;4;1;3;5;1$v",
        "save_restore_pen": link("aa\x1b7\x1b]8;id=new;https://other.org\x1b\\bb\x1b8cc"),
    }
    result = [dict(name=name, columns=12, rows=4, events=[dict(text=text)]) for name, text in setups.items()]
    for name in ["implicit_reopened", "explicit_reopened", "same_pen_disjoint", "wrapped_wide"]:
        result.append(dict(name="reflow_"+name, columns=12, rows=4,
            events=[dict(text=setups[name]), dict(resize=[7,6]), dict(resize=[16,4])]))
    result.append(dict(name="scrollback", columns=12, rows=4, events=[
        dict(text=link("aa\r\nbb\r\ncc\r\ndd\r\nee\r\nff")), dict(scroll=2), dict(scroll=-1)]))
    result.append(dict(name="alternate", columns=12, rows=4, events=[dict(text=link("aa", "id=one")),
        dict(text="\x1b[?1049h"+link("bb", "id=one")), dict(text="\x1b[?1049l")]))
    return result


def main():
    upstream, zig = (Path(value).resolve() for value in sys.argv[1:3])
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip() == SHA
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "."], cwd=upstream, check=True, stdout=subprocess.DEVNULL)
    build = ROOT / "build/terminal-link-hover-oracle"
    build.mkdir(parents=True, exist_ok=True)
    corpus = cases()
    (build / "cases.json").write_text(json.dumps(corpus, ensure_ascii=True))
    helper = ROOT / "scripts/terminal-ghostty-link-hover-oracle.zig"
    (build / "main.zig").write_text(helper.read_text())
    (build / "build.zig.zon").write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.17.0", .dependencies = .{ .ghostty = .{ .path = '+json.dumps(os.path.relpath(upstream, build))+' } }, .paths = .{""} }')
    (build / "build.zig").write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const dep = b.dependency("ghostty", .{ .target = target, .optimize = .safe, .simd = false, .@"emit-lib-vt" = true, .@"emit-xcframework" = false });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .safe, .link_libc = true });
    root.addImport("ghostty-vt", dep.module("ghostty-vt"));
    b.installArtifact(b.addExecutable(.{ .name = "link-hover-oracle", .root_module = root }));
}
''')
    env = os.environ | {"ZIG_GLOBAL_CACHE_DIR": os.environ.get("ZIG_GLOBAL_CACHE_DIR", str(ROOT / "build/upstream/zig-cache-0.17"))}
    subprocess.run([str(zig), "build"], cwd=build, check=True, env=env)
    actual = [json.loads(line) for line in subprocess.check_output([str(build / "zig-out/bin/link-hover-oracle")], text=True).splitlines()]
    assert len(corpus) == len(actual)
    for case, result in zip(corpus, actual):
        assert case["name"] == result["name"]
        case["snapshots"] = result["snapshots"]
    fixture = ROOT / "kinetica-terminal/test/generated/TerminalLinkHoverFixtures.kt"
    encoded = [base64.b64encode(json.dumps(case, separators=(",", ":")).encode()).decode() for case in corpus]
    fixture.write_text("// Generated by scripts/generate-terminal-link-hover.py; do not edit.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalLinkHoverFixtures(): List<String> = listOf(\n" + "".join('    listOf(' + ', '.join('"'+value[i:i+50000]+'"' for i in range(0,len(value),50000)) + ').joinToString(""),\n' for value in encoded) + ")\n")
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = dict(commit=SHA, zig=subprocess.check_output([str(zig), "version"], text=True).strip(),
        cases=len(corpus), snapshots=sum(len(c["snapshots"]) for c in corpus),
        hoverPoints=sum(len(s["cells"]) for c in corpus for s in c["snapshots"]),
        helperSha256=digest(helper), generatorSha256=digest(Path(__file__)), fixtureSha256=digest(fixture),
        sources={p:digest(upstream/p) for p in ["src/terminal/Terminal.zig", "src/terminal/Screen.zig", "src/terminal/render.zig", "src/terminal/hyperlink.zig", "src/terminal/osc.zig", "src/terminal/osc/parsers/hyperlink.zig", "src/renderer/generic.zig", "src/Surface.zig", "src/config/Config.zig"]})
    (ROOT / "kinetica-terminal/test-upstream/ghostty/link-hover-manifest.json").write_text(json.dumps(manifest, indent=2)+"\n")
    print(f'Generated {manifest["cases"]} cases / {manifest["snapshots"]} checkpoints / {manifest["hoverPoints"]} hover points')


if __name__ == "__main__": main()
