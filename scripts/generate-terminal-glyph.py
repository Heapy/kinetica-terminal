#!/usr/bin/env python3
"""Glyph protocol parser, execution and glossary oracle using pinned Ghostty."""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SHA = "83fb95624b0439ee660c2679b43b38c8b63769cd"


def cases():
    import struct, random
    result = []
    apc = lambda s: "\x1b_25a1;" + s + "\x1b\\"
    empty = base64.b64encode(bytes(10)).decode()
    query = apc("q;cp=e0a0")
    def add(name, events):
        result.append(dict(name=name, events=[dict(text=x) if isinstance(x,str) else x for x in events]))
    def register(data, options="cp=e0a0"):
        return apc("r;" + options + ";" + base64.b64encode(data).decode())
    add("verbs", [apc(x) for x in ["s","s;","s;ignored=yes","q","r","r;","r;;","c","d","S","","ss;","sX;"]])
    for cp in ["e0a0","E0A0","+e0a0","0xe0a0","e0_a0","_e0a0","e0a0_","e0__a0","-0","+0","","zz","1fffff","200000","10ffff","d800","e000","f8ff","f900","f0000","ffffd","ffffe","100000","10fffd","10fffe"]:
        add("codepoint_"+str(len(result)), [apc("q;cp="+cp), register(bytes(10),"cp="+cp), apc("q;cp="+cp), apc("c;cp="+cp)])
    add("last_option",[register(bytes(10),"cp=41;cp=e0a0;upm=0;upm=2048;reply=0;reply=1"),query,apc("c;cp=e0a0;cp=bad"),query])
    for option in ["fmt=glyf","fmt=colrv0","fmt=colrv1","fmt=nope","upm=0","upm=+1000","upm=1_000","upm=4294967295","upm=4294967296","upm=-0","aw=0","lh=0","upm=2048;aw=512;lh=1024;width=2",
                   "width=0","width=02","width=2","size=height","size=advance","size=contain","size=cover","size=stretch","size=nope",
                   "align=end,baseline","align=center","align=start,end,extra","pad=0.1,0.2,0.3,0.4","pad=.1,+.2,-0,1.","pad=.6,0,.6,0","pad=1e-2,0,0,0","pad=-.1,0,0,0","pad=0,0,0","reply=0","reply=1","reply=2","reply=bad","reply=02"]:
        add("option_"+str(len(result)),[register(bytes(10),"cp=e0a0;"+option),query,apc("r;cp=e0a0;"+option+";%%%"),query])
    add("lifecycle",[register(bytes(10)),query,"\x1b[?1049h",query,"\x1b[!p",query,"\x1b[?1049l",query,"\x1bc",query])
    add("clear",[register(bytes(10)),register(bytes(10),"cp=e0a1"),apc("c;cp=zz"),query,apc("c;cp=e0a1"),query,apc("c"),query])
    for payload in ["", "A", "AA", "AAA", "AAAA", "====", "A===", "AAAAAAAAAAAAAA==", "AAAAAAAAAAAAAB==", "AAAAAAAAAAAAAA=", "AAAAAAAAAAAAAA===", "AAAA AAAA", "____", "////"]:
        add("base64_"+str(len(result)),[apc("r;cp=e0a0;"+payload),query])
    header = lambda n: struct.pack(">hhhhh",n,0,0,10,10)
    shapes = [b"",bytes(9),bytes(10),bytes(11),bytes(12),bytes(13),header(-1),header(0)+b"\0\1",header(1),
              header(2)+struct.pack(">HHH",1,1,0),header(1)+struct.pack(">HH",0,1),
              header(1)+struct.pack(">HH",0,0)+b"\x31",
              header(1)+struct.pack(">HH",2,0)+b"\x39\x02",
              header(1)+struct.pack(">HH",0,0)+b"\x39\x01",
              header(1)+struct.pack(">HH",1,0)+b"\x01\x37"+struct.pack(">hB",-10,20)+struct.pack(">hB",30,40),
              header(1)+struct.pack(">HH",5460,0),header(1)+struct.pack(">HH",5461,0),bytes(65536),bytes(65537)]
    for index,data in enumerate(shapes): add("glyf_"+str(index),[register(data),query])
    rng=random.Random(1337)
    for index in range(60):
        n=rng.randrange(1,6)
        flags=bytes(rng.randrange(256) & ~8 for _ in range(n))
        data=header(1)+struct.pack(">HH",n-1,0)+flags+bytes(rng.randrange(256) for _ in range(n*4))
        if index%3==0: data=data[:rng.randrange(10,len(data))]
        add("outline_"+str(index),[register(data),query])
    for ending in ["\x18","\x1a","\x1b[0m","\x07","\u009c","\u0090"]:
        add("termination_"+str(len(result)),["\x1b_25a1;r;cp=e0a0;"+empty+ending,query])
    add("fifo",[dict(fill=1024),apc("q;cp=e000"),apc("q;cp=e001"),register(bytes(10),"cp=e000"),register(bytes(10),"cp=f000"),apc("q;cp=e000"),apc("q;cp=e001"),apc("c"),query])
    for length in [1024*1024-1,1024*1024,1024*1024+1]:
        add("capture_"+str(length),[dict(capture=length),apc("s")])
    for points in [5461,5462]:
        flags=bytes([0x39,255])*(points//256)
        if points%256: flags+=bytes([0x39,points%256-1])
        add("point_limit_"+str(points),[register(header(1)+struct.pack(">HH",points-1,0)+flags),query])
    return result


def main():
    upstream = Path(sys.argv[1]).resolve()
    zig = Path(sys.argv[2]).resolve()
    assert subprocess.check_output(["git","rev-parse","HEAD"],cwd=upstream,text=True).strip() == SHA
    subprocess.run(["git","diff","--exit-code","HEAD","--","."],cwd=upstream,check=True,stdout=subprocess.DEVNULL)
    build = ROOT/"build/terminal-glyph-oracle"
    build.mkdir(parents=True,exist_ok=True)
    corpus = cases()
    (build/"cases.json").write_text(json.dumps(corpus,ensure_ascii=True))
    helper = ROOT/"scripts/terminal-ghostty-glyph-oracle.zig"
    shutil.copyfile(helper,build/"main.zig")
    (build/"build.zig.zon").write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.17.0", .dependencies = .{ .ghostty = .{ .path = '+json.dumps(os.path.relpath(upstream,build))+' } }, .paths = .{""} }')
    (build/"build.zig").write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const dep = b.dependency("ghostty", .{ .target = target, .optimize = .safe, .simd = false, .@"emit-lib-vt" = true, .@"emit-xcframework" = false });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .safe, .link_libc = true });
    root.addImport("ghostty-vt", dep.module("ghostty-vt"));
    b.installArtifact(b.addExecutable(.{ .name = "glyph-oracle", .root_module = root }));
}
''')
    env = os.environ | {"ZIG_GLOBAL_CACHE_DIR":os.environ.get("ZIG_GLOBAL_CACHE_DIR",str(ROOT/"build/upstream/zig-cache-0.17"))}
    subprocess.run([str(zig),"build"],cwd=build,check=True,env=env)
    output = subprocess.run([str(build/"zig-out/bin/glyph-oracle")],capture_output=True,text=True)
    if output.returncode: print(output.stderr,file=sys.stderr)
    output.check_returncode()
    actual = [json.loads(line) for line in output.stdout.splitlines()]
    assert len(corpus) == len(actual)
    for case, result in zip(corpus,actual):
        assert case["name"] == result["name"]
        case["snapshots"] = result["snapshots"]
    fixture = ROOT/"kinetica-terminal/test/generated/TerminalGlyphFixtures.kt"
    encoded = [base64.b64encode(json.dumps(c,separators=(',',':')).encode()).decode() for c in corpus]
    fixture.write_text('// Generated by scripts/generate-terminal-glyph.py; do not edit.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalGlyphFixtures(): List<String> = listOf(\n'+''.join('    listOf(' + ','.join(json.dumps(v[i:i+16000]) for i in range(0,len(v),16000)) + ').joinToString(""),\n' for v in encoded)+')\n')
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    manifest = dict(commit=SHA,zig=subprocess.check_output([str(zig),"version"],text=True).strip(),
        cases=len(corpus),snapshots=sum(len(c["snapshots"]) for c in corpus),

        helperSha256=digest(helper),generatorSha256=digest(Path(__file__)),fixtureSha256=digest(fixture),
        sources={p:digest(upstream/p) for p in ["src/terminal/Terminal.zig","src/terminal/stream_terminal.zig","src/terminal/stream.zig","src/terminal/Parser.zig","src/terminal/apc.zig","src/terminal/apc/glyph/request.zig","src/terminal/apc/glyph/execute.zig","src/terminal/apc/glyph/Glossary.zig","src/terminal/apc/glyph/response.zig","src/font/opentype/glyf.zig"]})
    (ROOT/"kinetica-terminal/test-upstream/ghostty/glyph-manifest.json").write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'Generated {len(corpus)} glyph cases / {manifest["snapshots"]} checkpoints')


if __name__ == "__main__": main()
