#!/usr/bin/env python3
"""Kitty drag-and-drop core oracle using the unmodified pinned Ghostty Zig module."""
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
    result = []
    def c(metadata, payload="", **kw):
        return dict(metadata=metadata, payload=payload.encode().hex(), **kw)
    def add(name, events): result.append(dict(name=name, events=events))
    metadata = ["", "t=q", "t=q:i=4294967295", "t=q:i=1:i=2", "t=q:", "t=q::", "t", "t=", "t=z", "t=qq", "z=1", "t=q:z=1", "x=", "x=-", "x=+1", "x=1z", "i=-1", "i=4294967296", "i=00000000000", "t =q", "t=q: i=1", "t=q:o=3:m=2:x=-1:y=2:X=-3:Y=4"]
    for key in "mioxyXY":
        for value in ["0", "2147483647", "2147483648", "4294967295", "4294967296", "-2147483648", "-4294967295"]:
            metadata.append(f"t=r:{key}={value}")
    for i, raw in enumerate(metadata): add(f"metadata_{i}", [c(raw)])
    for kind in "aAmMrRopPeEkq":
        for bel in [False, True]:
            add(f"type_{kind}_{bel}", [c(f"t={kind}:i=7", "text/plain", bel=bel), c("t=r:x=1",bel=bel)])
    add("registration", [c("t=a:i=1","text/plain  text/html"), c("t=a:i=9","application/json"), c("t=a:x=1:i=30","machine"), c("t=q:i=12"), c("t=A"), c("t=A"), c("t=r:x=1")])
    add("registration_chunks", [c("t=a:i=7:m=1","text/"), c("m=1:i=8","plain "), c("t=invalid","ignored"), c("t=q","text/html"), c("t=q:i=4"), c("t=A")])
    add("status_chunks", [c("t=a:i=5"), c("t=m:o=2:m=1","text/"), c("t=q:o=1:m=1","plain "), c("m=0","text/html"), c("t=m:o=99"," a  b "), c("t=m:o=1"), c("t=A")])
    add("reset_chunk_only", [c("t=a:i=5:m=1","a"), dict(action="reset"), c("t=q"), c("t=m:o=2:m=1","text/"), dict(action="reset"), c("t=m:o=1","plain"), dict(action="dispose"), c("t=q")])
    for action in ["register", "accept"]:
        prefix="t=a" if action=="register" else "t=m:o=2"
        add("limit_"+action,[c("t=a:i=9"),c(prefix+":m=1",count=1048576),c("m=0","b"),c(prefix),c("t=A")])
        add("limit_exact_"+action,[c("t=a"),c(prefix+":m=1",count=1048575),c("m=0"," "),c(prefix),c("t=A")])
    for op in [0,1,2,3,4294967295]:
        add(f"drag_lifecycle_{op}", [c("t=a:i=7","text/plain"),dict(action="leave"),dict(action="move",mimes=["text/plain","text/html"]),c(f"t=m:o={op}","text/plain"),dict(action="move",x=9,y=8,X=-2,Y=-1,operations=3,mimes=["text/plain","text/html"]),dict(action="drop",items=[dict(mime="text/plain",hex="68656c6c6f"),dict(mime="text/html",hex="")]),dict(action="leave"),c("t=r:x=1:i=99"),c("t=r:x=2"),dict(action="reset"),c("t=r:x=1"),c(f"t=r:o={op}"),c("t=r:x=1"),c("t=A")])
    for size in [0,1,3071,3072,3073,6144,6145]:
        for bel in [False,True]:
            add(f"data_{size}_{bel}",[c("t=a:i=4294967295"),dict(action="drop",items=[dict(mime="application/octet-stream",fill=255,count=size)]),c("t=r:x=1",bel=bel),c("t=r:o=1",bel=bel)])
    for request in ["x=-1","x=3","x=1:y=2","x=-1:Y=2","x=1:y=3:Y=4","Y=-1","x=0:y=1"]:
        add("request_"+request.replace(":","_").replace("=",""),[c("t=r:"+request),c("t=a:i=7"),dict(action="drop",items=[dict(mime="text/plain",hex="00ff")]),c("t=r:"+request,bel=True)])
    add("new_drag_discards_data",[c("t=a"),dict(action="drop",items=[dict(mime="text/plain",hex="6162")]),dict(action="move",mimes=["image/png"]),c("t=r:x=1"),dict(action="leave"),dict(action="leave"),c("t=m:o=2"),dict(action="move",mimes=[]),c("t=A")])
    add("drop_replacement",[c("t=a:i=1"),dict(action="drop",items=[dict(mime="a",hex="01")]),c("t=a:i=2","b"),dict(action="drop",items=[dict(mime="b",hex="02")]),c("t=r:x=1"),c("t=A")])
    add("drop_item_cap",[c("t=a"),dict(action="drop",items=[dict(mime=f"type/{i}",hex=f"{i:02x}") for i in range(20)]),c("t=r:x=16"),c("t=r:x=17"),c("t=A")])
    for size in [4095,4096,4097,8192]:
        add(f"move_chunks_{size}",[c("t=a:i=7"),dict(action="move",mimes=["a"*(size-1)]),dict(action="leave")])
    add("move_coordinates",[c("t=a:i=7"),dict(action="move",x=4294967295,y=4294967295,X=-2147483648,Y=2147483647,operations=0,mimes=[]),dict(action="leave")])
    return result


def main():
    upstream = Path(sys.argv[1]).resolve()
    zig = Path(sys.argv[2]).resolve()
    assert subprocess.check_output(["git","rev-parse","HEAD"],cwd=upstream,text=True).strip() == SHA
    subprocess.run(["git","diff","--exit-code","HEAD","--","."],cwd=upstream,check=True,stdout=subprocess.DEVNULL)
    build = ROOT/"build/terminal-dnd-oracle"
    build.mkdir(parents=True,exist_ok=True)
    corpus = cases()
    (build/"cases.json").write_text(json.dumps(corpus,ensure_ascii=True))
    helper = ROOT/"scripts/terminal-ghostty-dnd-oracle.zig"
    shutil.copyfile(helper,build/"main.zig")
    (build/"build.zig.zon").write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.17.0", .dependencies = .{ .ghostty = .{ .path = '+json.dumps(os.path.relpath(upstream,build))+' } }, .paths = .{""} }')
    (build/"build.zig").write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const dep = b.dependency("ghostty", .{ .target = target, .optimize = .safe, .simd = false, .@"emit-lib-vt" = true, .@"emit-xcframework" = false });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .safe, .link_libc = true });
    root.addImport("ghostty-vt", dep.module("ghostty-vt"));
    b.installArtifact(b.addExecutable(.{ .name = "dnd-oracle", .root_module = root }));
}
''')
    env = os.environ | {"ZIG_GLOBAL_CACHE_DIR":os.environ.get("ZIG_GLOBAL_CACHE_DIR",str(ROOT/"build/upstream/zig-cache-0.17"))}
    subprocess.run([str(zig),"build"],cwd=build,check=True,env=env)
    output = subprocess.run([str(build/"zig-out/bin/dnd-oracle")],capture_output=True,text=True)
    if output.returncode: print(output.stderr,file=sys.stderr)
    output.check_returncode()
    actual = [json.loads(line) for line in output.stdout.splitlines()]
    assert len(corpus) == len(actual)
    for case, result in zip(corpus,actual):
        assert case["name"] == result["name"]
        case["snapshots"] = result["snapshots"]
    import runpy
    common = runpy.run_path(str(ROOT/"scripts/generate-terminal-oracle.py"))
    fixture = ROOT/"kinetica-terminal/test/generated/TerminalDndFixtures.kt"
    text = '// Generated by scripts/generate-terminal-dnd.py; do not edit.\n// Original Ghostty '+SHA+' (MIT).\npackage io.heapy.kinetica.terminal\n'
    text += 'internal fun terminalDndFixtures(): List<String> = listOf('+','.join(f'dndCase{i}()' for i in range(len(corpus)))+')\n'
    for i,case in enumerate(corpus): text += f'private fun dndCase{i}(): String = '+common['encoded'](case)+'\n'
    common['write_generated'](fixture,text)
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    manifest = dict(commit=SHA,zig=subprocess.check_output([str(zig),"version"],text=True).strip(),
        cases=len(corpus),snapshots=sum(len(c["snapshots"]) for c in corpus),
        hostAdapter="Calls unmodified dnd.handleCommand and State move/drop/leave methods. Reset clears chunking only, as Terminal.fullReset does. No OSC parser, platform drag or OS data access. MIME lists use lossless byte run-length snapshots.",
        helperSha256=digest(helper),generatorSha256=digest(Path(__file__)),fixtureSha256=digest(fixture),
        commonGeneratorSha256=digest(ROOT/"scripts/generate-terminal-oracle.py"),
        sources={p:digest(upstream/p) for p in ["src/terminal/kitty/dnd.zig","src/terminal/kitty/dnd_command.zig","src/terminal/kitty/dnd_drop.zig","src/terminal/kitty/dnd_response.zig","src/terminal/Terminal.zig"]})
    (ROOT/"kinetica-terminal/test-upstream/ghostty/dnd-manifest.json").write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'Generated {len(corpus)} DnD cases / {manifest["snapshots"]} checkpoints')


if __name__ == "__main__": main()
