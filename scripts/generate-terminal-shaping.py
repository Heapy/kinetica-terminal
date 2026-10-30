#!/usr/bin/env python3
"""Record original Ghostty shaping runs, glyph indices and terminal-grid offsets.

Uses an isolated git-archive snapshot: no production source is patched and no
installed fonts or application windows are used. --check verifies committed data.
"""
import argparse
import base64
import hashlib
import json
import os
import platform
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
SHA = "83fb95624b0439ee660c2679b43b38c8b63769cd"


def cases():
    result = []

    def add(name, text, **options):
        result.append(dict(name=name, text=text, **options))

    for family in ["jetbrains_mono", "inconsolata", "monaspace_neon", "arabic"]:
        for name, text in [
            ("operators", "-> => == === != !== <= >= <=> ::: && ||"),
            ("protected_pairs", "fi fl st ffi ffl first"),
            ("combining", "e\u0301 a\u0308\u0301 x\u0338 ->"),
            ("arabic", "لا سلام عليكم"),
            ("emoji", "A👍🏽B 👩‍💻 C ❤\ufe0e ❤\ufe0f"),
        ]:
            add(f"{family}_{name}", text, font=family)
    for cursor in range(7):
        add(f"cursor_{cursor}", "a===>b", cursor=cursor)
    for start, end in [(0, 0), (0, 2), (1, 3), (2, 2), (2, 4), (4, 5), (5, 5)]:
        add(f"selection_{start}_{end}", "a===>b", selection=[start, end])
    for name, sgr in [
        ("foreground", "31"), ("background", "41"), ("bold", "1"), ("italic", "3"),
        ("underline", "4"), ("double_underline", "4:2"), ("underline_color", "58;5;1"),
        ("inverse", "7"), ("faint", "2"), ("hidden", "8"), ("strike", "9"),
    ]:
        add(name, "=\x1b[" + sgr + "m>\x1b[0m=")
    add("invisible_prefix", "\x1b[8mhidden\x1b[0m=>")
    add("empty", "")
    add("unwritten_tail", "=>")
    add("written_spaces", "  =>  ")
    add("erased_gap", "=\x1b[2C>")
    add("wrap_head", "abc=>", columns=4)
    add("wrap_tail", "abc=>", columns=4, row=1)
    add("wide_spacer_head", "abc界=>", columns=4)
    add("wide_spacer_tail", "abc界=>", columns=4, row=1)
    add("wide_fallback", "a界=>b")
    add("missing_glyph", "a\U0010fffd=>b")
    add("graphics_placeholder", "a\U0010eeee\u0305\u0305=>b")
    add("sprite_boundary", "a█=>b")
    add("grapheme_cursor", "e\u0301=>", cursor=0)
    add("grapheme_after_cursor", "=e\u0301=>", cursor=0)
    add("wide_cursor", "👍=>", cursor=0)
    add("wide_tail_cursor", "👍=>", cursor=1)
    for cursor in [None, 0, 1, 2, 3, 4]:
        add(f"grapheme_mode_emoji_{cursor}", "\x1b[?2027h👍🏽=>👩‍💻", cursor=cursor)
    add("emoji_across_cells", "👍🏽=>👩‍💻")
    add("emoji_across_cells_selected", "👍🏽=>👩‍💻", selection=[2, 3])
    add("emoji_across_cells_cursor", "👍🏽=>👩‍💻", cursor=2)
    add("stacked_marks", "a\u0302\u0301 x\u0301\u0323 n\u0338\u0308")
    add("osc8_boundary", "=\x1b]8;id=a;https://example.com\x1b\\>\x1b]8;;\x1b\\=")
    add("ligatures_enabled", "a===>b -> !=")
    add("ligatures_disabled", "a===>b -> !=", features=["-calt", "-liga", "-dlig"])
    add("discretionary_ligatures", "a===>b -> !=", font="inconsolata", features=["dlig"])
    add("long_run", "=" * 512 + ">", columns=520)
    return result


BUILD = '''const std = @import("std");
const buildpkg = @import("src/build/main.zig");
pub fn build(b: *std.Build) !void {
    const cfg = try buildpkg.Config.init(b, "1.4.0", "0.1.0-dev");
    const deps = try buildpkg.SharedDeps.init(b, &cfg);
    const root = b.createModule(.{
        .root_source_file = b.path("src/kinetica-shaping-oracle.zig"),
        .target = cfg.target, .optimize = .safe, .link_libc = true,
    });
    const oracle = b.addTest(.{ .name = "shaping-oracle", .root_module = root,
        .filters = &.{"kinetica shaping oracle"}, .use_llvm = true });
    _ = try deps.add(oracle);
    // SharedDeps adds a Metal shader on every macOS build, even with the OpenGL
    // backend. This font-only test never renders; remove that unused build edge
    // and import so regeneration does not require the optional Metal toolchain.
    if (deps.metallib) |metal| {
        std.debug.assert(cfg.renderer == .opengl);
        _ = root.import_table.swapRemove("ghostty_metallib");
        var i: usize = 0;
        while (i < oracle.step.dependencies.items.len) {
            if (oracle.step.dependencies.items[i] == metal.step) {
                _ = oracle.step.dependencies.orderedRemove(i);
            } else i += 1;
        }
    }
    b.installArtifact(oracle);
}
'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate(corpus):
    """Reject an oracle that silently loses coverage or stops doing real shaping."""
    by_name = {case["name"]: case["expected"] for case in corpus}
    assert len(by_name) == len(corpus)
    for case in corpus:
        end = 0
        for run in case["expected"]["runs"]:
            assert end <= run["offset"] < case.get("columns", 80), case["name"]
            assert run["cells"] > 0, case["name"]
            end = run["offset"] + run["cells"]
            assert end <= case.get("columns", 80), case["name"]
            assert all(0 <= point["cluster"] < run["cells"] for point in run["input"]), case["name"]
            assert all(0 <= glyph["x"] < run["cells"] for glyph in run["glyphs"]), case["name"]
    assert not by_name["empty"]["runs"]
    assert [r["cells"] for r in by_name["unwritten_tail"]["runs"]] == [2]
    assert [r["cells"] for r in by_name["written_spaces"]["runs"]] == [6]
    assert len(by_name["background"]["runs"]) == 1
    assert len(by_name["foreground"]["runs"]) == 3
    assert by_name["ligatures_enabled"]["runs"] != by_name["ligatures_disabled"]["runs"]
    assert by_name["long_run"]["runs"][0]["cells"] == 513


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path)
    parser.add_argument("zig", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--backend", choices=["freetype", "coretext"], default="freetype")
    args = parser.parse_args()
    upstream, zig = args.upstream.resolve(), args.zig.resolve()
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip() == SHA
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "."], cwd=upstream, check=True)
    suffix = "-coretext" if args.backend == "coretext" else ""
    build = ROOT / ("build/terminal-shaping-oracle" + suffix)
    source = build / "source"
    source.mkdir(parents=True, exist_ok=True)
    # Export the commit, not a working-tree copy or a modified shaper implementation.
    with subprocess.Popen(["git", "archive", "HEAD"], cwd=upstream, stdout=subprocess.PIPE) as archive:
        subprocess.run(["tar", "-xf", "-", "-C", str(source)], stdin=archive.stdout, check=True)
        archive.stdout.close()
        assert archive.wait() == 0
    helper = ROOT / "scripts/terminal-ghostty-shaping-oracle.zig"
    shutil.copyfile(helper, source / "src/kinetica-shaping-oracle.zig")
    corpus = cases()
    (source / "src/shaping-cases.json").write_text(json.dumps(corpus, ensure_ascii=True))
    (source / "build.zig").write_text(BUILD)
    env = os.environ | {"ZIG_GLOBAL_CACHE_DIR": str(ROOT / "build/upstream/zig-cache-0.17")}
    subprocess.run([str(zig), "build", "-Dfont-backend=" + args.backend, "-Dsimd=false",
                    "-Dapp-runtime=none", "-Drenderer=opengl", "-Dsentry=false", "-Di18n=false"], cwd=source, env=env, check=True)
    output = subprocess.run([str(source / "zig-out/bin/shaping-oracle")], text=True, capture_output=True)
    (build / "stderr.log").write_text(output.stderr)
    if output.returncode:
        print(output.stderr)
    output.check_returncode()
    actual = [json.loads(line) for line in output.stdout.splitlines()]
    assert len(corpus) == len(actual)
    for case, expected in zip(corpus, actual):
        assert case["name"] == expected.pop("name")
        case["expected"] = expected
    validate(corpus)
    target = ROOT / ("kinetica-terminal/test-upstream/ghostty/shaping" + suffix + ".json")
    encoded = json.dumps(corpus, ensure_ascii=True, indent=2) + "\n"
    kotlin = ROOT / ("kinetica-terminal/test@macosArm64/generated/TerminalCoreTextShapingFixtures.kt" if suffix else
                     "kinetica-terminal/test/generated/TerminalShapingFixtures.kt")
    kotlin.parent.mkdir(parents=True, exist_ok=True)
    fixture_function = "terminalCoreTextShapingFixtures" if suffix else "terminalShapingFixtures"
    entries = [base64.b64encode(json.dumps(case, separators=(',', ':')).encode()).decode() for case in corpus]
    kotlin_text = ('// Generated by scripts/generate-terminal-shaping.py; do not edit.\n'
                   'package io.heapy.kinetica.terminal\n\ninternal fun ' + fixture_function + '(): List<String> = listOf(\n' +
                   ''.join('    listOf(' + ','.join(json.dumps(v[i:i+16000]) for i in range(0, len(v), 16000)) +
                           ').joinToString(""),\n' for v in entries) + ')\n')
    native_font = ROOT / "kinetica-terminal/test@macosArm64/generated/TerminalShapingFont.kt"
    font64 = base64.b64encode((upstream / "src/font/res/JetBrainsMonoNoNF-Regular.ttf").read_bytes()).decode()
    font_text = ('// Generated by scripts/generate-terminal-shaping.py; do not edit.\n'
                 '// JetBrains Mono, OFL-1.1; see test-upstream/ghostty/fonts/{README.md,OFL.txt}.\n'
                 'package io.heapy.kinetica.terminal\n\ninternal fun terminalShapingFont(): String = listOf(\n' +
                 ''.join('    ' + json.dumps(font64[i:i+16000]) + ',\n' for i in range(0, len(font64), 16000)) +
                 ').joinToString("")\n')
    native_font.parent.mkdir(parents=True, exist_ok=True)
    license_dir = ROOT / "kinetica-terminal/test-upstream/ghostty/fonts"
    license_dir.mkdir(parents=True, exist_ok=True)
    artifacts = [(kotlin, kotlin_text), (native_font, font_text)]
    artifacts += [(license_dir / name, (upstream / "src/font/res" / name).read_text()) for name in ["README.md", "OFL.txt"]]
    sources = ["src/font/shaper/run.zig", "src/font/shaper/harfbuzz.zig", "src/font/shaper/coretext.zig", "src/font/shaper/feature.zig",
               "src/font/CodepointResolver.zig", "src/font/SharedGrid.zig", "src/font/Collection.zig",
               "src/font/face/freetype.zig", "src/font/face/coretext.zig", "src/terminal/render.zig", "build.zig.zon",
               "pkg/freetype/build.zig.zon", "pkg/harfbuzz/build.zig.zon",
               "pkg/libpng/build.zig.zon", "pkg/zlib/build.zig.zon"]
    sources += ["src/font/res/" + name for name in ["JetBrainsMonoNoNF-Regular.ttf", "Inconsolata-Regular.ttf",
                "MonaspaceNeon-Regular.otf", "KawkabMono-Regular.ttf", "NotoColorEmoji.ttf", "NotoEmoji-Regular.ttf"]]
    manifest = dict(commit=SHA, zig=subprocess.check_output([str(zig), "version"], text=True).strip(),
                    backend=args.backend, points=12, cases=len(corpus),
                    runs=sum(len(c["expected"]["runs"]) for c in corpus),
                    glyphs=sum(len(r["glyphs"]) for c in corpus for r in c["expected"]["runs"]),
                    helperSha256=digest(helper), generatorSha256=digest(Path(__file__)),
                    fixtureSha256=hashlib.sha256(encoded.encode()).hexdigest(),
                    testArtifacts={str(path.relative_to(ROOT)): hashlib.sha256(content.encode()).hexdigest() for path, content in artifacts},
                    sources={p: digest(upstream / p) for p in sources})
    if suffix:
        manifest["macOS"] = platform.mac_ver()[0]
    for path, content in [(target, encoded), *artifacts, (target.with_name("shaping" + suffix + "-manifest.json"), json.dumps(manifest, indent=2) + "\n")]:
        if args.check:
            assert path.read_text() == content, f"Stale generated data: {path}"
        else:
            path.write_text(content)
    print(f'{"Verified" if args.check else "Generated"} {manifest["cases"]} shaping cases / {manifest["runs"]} runs / {manifest["glyphs"]} glyphs')


if __name__ == "__main__":
    main()
