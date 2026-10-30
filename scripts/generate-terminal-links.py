#!/usr/bin/env python3
"""Original Ghostty/Oniguruma URL-path matches and Unicode classes, without Kinetica expectations."""
import base64
import hashlib
import json
import os
from pathlib import Path
import random
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SHA = "83fb95624b0439ee660c2679b43b38c8b63769cd"
ONIG_PACKAGE = "N-V-__8AAHjwMQDBXnLq3Q2QhaivE0kE2aD138vtX2Bq1g7c"


def cases(source):
    values = [json.loads(s) for s in re.findall(r'\.input = ("(?:\\.|[^"\\])*")', source)]
    negatives = source.split("const no_match_cases =", 1)[1].split("};", 1)[0]
    values += [json.loads(s) for s in re.findall(r'^\s*("(?:\\.|[^"\\])*")', negatives, re.M)]
    for scheme in ["http://", "https://", "mailto:", "ftp://", "file:", "ssh:", "git://", "ssh://", "tel:", "magnet:", "ipfs://", "ipns://", "gemini://", "gopher://", "news:", "HTTPS://", "unknown://"]:
        for suffix in ["a", "a.", "a,", "a;", "a!", "a?", "a:", ",.", "a(foo)", "a(foo]", "a[foo)", "a[]", "a(foo-bar)", "[::1]:80/a", "[abc]", "[g::1]", "a/界é", "a/😀", "a/𐐀", "a/²", "a\u00a0b", "a\u200cb"]:
            values.append("before (" + scheme + suffix + ") after")
    for prefix in ["/", "./", "../", "~/", "$HOME/", "foo/$BAR/", ".config/", "src/"]:
        for suffix in ["a", "a.txt", "a:", ":", "a::", "a b", "a.txt b.txt", "a b.txt", "a b/c.txt", "a :", "a b:", "a  b", "a ./b", "a ~/b", "a ssh://b", "a_b/c,d.txt", "界/файл.txt", "😀/x.txt", "a(b)", "a/b."]:
            values.append("[" + prefix + suffix + "]")
    values += ["$10/bar.txt", "$١٢/bar.txt", "foo$BAR/baz.txt", "foo~/bar.txt", "//foo.txt", "a//b.txt", "xhttps://a", "https://a\nhttps://b", "https://a\thttps://b", "./a.txt more.txt tail", "/foo : /bar", "~/", "$HOME/", "../", "/", ""]
    for cp in list(range(0x80, 0x100)) + [0x301, 0x661, 0x16ee, 0x200c, 0x2167, 0x3000, 0xff10, 0x10400, 0x1d7d8, 0x1f600]:
        char = chr(cp)
        values += ["https://a/"+char, "https://a("+char+")", char+"/file.txt", "$"+char+"/file.txt", "a"+char+"~/file.txt"]
    rng = random.Random(1400)
    tokens = ["foo", "bar.txt", "http://", "ssh:", "mailto:", "$HOME", "$10", "١٢", "./", "../", "~/", "/", "//", ":", ".", ",", " ", "  ", "(", ")", "[", "]", "?", ";", "😀", "界", "é", "\u00a0", "-", "_", "\n"]
    for _ in range(5000):
        values.append("".join(rng.choices(tokens, k=rng.randrange(1, 18))))
    return list(dict.fromkeys(values))


def main():
    upstream, zig = (Path(value).resolve() for value in sys.argv[1:3])
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip() == SHA
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "."], cwd=upstream, check=True, stdout=subprocess.DEVNULL)
    build = ROOT / "build/terminal-links-oracle"
    build.mkdir(parents=True, exist_ok=True)
    helper = ROOT / "scripts/terminal-ghostty-links-oracle.zig"
    source = upstream / "src/config/url.zig"
    inputs = cases(source.read_text())
    (build / "cases.json").write_text(json.dumps(inputs, ensure_ascii=True))
    (build / "main.zig").write_bytes(helper.read_bytes())
    (build / "url.zig").write_bytes(source.read_bytes())
    package = os.path.relpath(upstream / "pkg/oniguruma", build)
    (build / "build.zig.zon").write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.17.0", .dependencies = .{ .oniguruma = .{ .path = '+json.dumps(package)+' } }, .paths = .{""} }')
    (build / "build.zig").write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const oni = b.dependency("oniguruma", .{ .target = target, .optimize = .fast });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .safe, .link_libc = true });
    root.addImport("oniguruma", oni.module("oniguruma"));
    b.installArtifact(b.addExecutable(.{ .name = "links-oracle", .root_module = root }));
}
''')
    env = os.environ | {"ZIG_GLOBAL_CACHE_DIR": str(ROOT / "build/upstream/zig-cache-0.17")}
    subprocess.run([str(zig), "build"], cwd=build, env=env, check=True)
    onig = build / "zig-pkg" / ONIG_PACKAGE
    license = (onig / "COPYING").read_bytes()
    for destination in ["third-party/ONIGURUMA-LICENSE", "resources/META-INF/LICENSES/ONIGURUMA-BSD.txt"]:
        (ROOT / "kinetica-terminal" / destination).write_bytes(license)
    output = subprocess.check_output([str(build / "zig-out/bin/links-oracle")], text=True)
    lines = [json.loads(line) for line in output.split("\n") if line]
    classes, actual = lines[0], lines[1:]
    assert len(actual) == len(inputs)
    for expected_input, result in zip(inputs, actual):
        assert expected_input == result["input"]
        raw = expected_input.encode()
        result["ranges"] = [[len(raw[:offset].decode().encode("utf-16-le")) // 2 for offset in pair] for pair in result["ranges"]]
    fixture = ROOT / "kinetica-terminal/test/generated/TerminalLinkMatcherFixtures.kt"
    encoded = [base64.b64encode(json.dumps(case, separators=(",", ":")).encode()).decode() for case in actual]
    chunks = [encoded[start:start+500] for start in range(0, len(encoded), 500)]
    fixture_text = "// Generated by scripts/generate-terminal-links.py; do not edit.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalLinkMatcherFixtures(): List<String> = buildList {\n"
    fixture_text += "".join(f"    addAll(terminalLinkMatcherFixtures{i}())\n" for i in range(len(chunks))) + "}\n"
    for index, chunk in enumerate(chunks):
        fixture_text += f"\nprivate fun terminalLinkMatcherFixtures{index}(): List<String> = listOf(\n" + "".join('    "'+value+'",\n' for value in chunk) + ")\n"
    fixture.write_text(fixture_text)
    data = ROOT / "kinetica-terminal/src/generated/TerminalLinkCharacterData.kt"
    data.parent.mkdir(parents=True, exist_ok=True)
    text = "// Generated by scripts/generate-terminal-links.py from Ghostty 1.4 / Oniguruma 6.9.9; do not edit.\n// Copyright (c) 2002-2021 K.Kosako; BSD-2-Clause, see third-party/ONIGURUMA-LICENSE.\npackage io.heapy.kinetica.terminal\n"
    for name, ranges in [("Word", classes["wordRanges"]), ("ClassWord", classes["classWordRanges"]), ("Digit", classes["digitRanges"])]:
        text += "\ninternal val terminalLink"+name+"Ranges: IntArray = intArrayOf(\n"
        text += "".join("    "+str(start)+", "+str(end)+",\n" for start, end in ranges) + ")\n"
    data.write_text(text)
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    manifest = dict(commit=SHA, oniguruma="6.9.9", onigurumaOptimize="fast", zig=subprocess.check_output([str(zig), "version"], text=True).strip(),
        cases=len(actual), matches=sum(len(case["ranges"]) for case in actual), unicodeScalars=0x110000-0x800,
        pattern=classes["pattern"], wordRanges=len(classes["wordRanges"]), classWordRanges=len(classes["classWordRanges"]), digitRanges=len(classes["digitRanges"]),
        helperSha256=digest(helper), generatorSha256=digest(Path(__file__)), fixtureSha256=digest(fixture), characterDataSha256=digest(data),
        sources={path:digest(upstream / path) for path in ["src/config/url.zig", "pkg/oniguruma/build.zig.zon", "pkg/oniguruma/regex.zig", "src/Surface.zig"]},
        onigurumaPackage=ONIG_PACKAGE,
        onigurumaSources={path:digest(onig / path) for path in ["COPYING", "src/unicode.c", "src/unicode_property_data.c", "src/regparse.c", "src/regexec.c"]})
    (ROOT / "kinetica-terminal/test-upstream/ghostty/links-manifest.json").write_text(json.dumps(manifest, indent=2)+"\n")
    print(f'Generated {len(actual)} inputs / {manifest["matches"]} matches; {manifest["wordRanges"]} word and {manifest["digitRanges"]} digit ranges')


if __name__ == "__main__": main()
