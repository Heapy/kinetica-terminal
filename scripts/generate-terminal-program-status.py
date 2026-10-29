#!/usr/bin/env python3
"""Generate OSC 7501 parser/effect/reply evidence from the pinned original Ghostty."""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SHA = "83fb95624b0439ee660c2679b43b38c8b63769cd"


def cases():
    result = []
    def add(name, events):
        result.append(dict(name=name,events=[dict(text=e) if isinstance(e,str) else dict(enabled=e) for e in events]))
    def osc(body, term="\x1b\\"): return "\x1b]7501;"+body+term
    def b64(data): return base64.b64encode(data).decode()
    def report(name, body): add(name,[osc(body)])
    add("subscription",[False,osc("?")+osc("state=working"),True,osc("?"),osc("?","\x07"),osc("state=idle"),False,osc("?")+"\x1bc",True,osc("?")])
    add("lifecycle",[osc("state=working:id=child:app=build:progress=20"),"\x1b[?1049h\x1b[!p\x1b[?1049l",osc("state=done:id=child"),"\x1bc","\x1bc"])
    add("fragmented",["\x1b]7501;state=blocked:msg=U3lu","Y2luZyBwaG90b3M=", "\x1b", "\\",osc("state=idle","\x07")])
    add("cancel",["\x1b]7501;state=working\x18",osc("?"),"\x1b]7501;state=done\x1a",osc("state=error")])
    add("render_hold",["\x1b[?2026h",osc("state=idle")+osc("?"),"\x1b[?2026l"])
    for state in ["idle","working","done","blocked","error","clear","","sleeping","IDLE"]:
        report("state_"+state,"state="+state+":id=job/child:kind=auth:progress=42:app=cargo:title=UGxhbg:msg=5a6J5YWo")
    for kind in ["permission","question","auth","dance",""]:
        report("kind_"+kind,"state=blocked:kind="+kind)
    for n in ["0","100","101","-1","+1","1_0","0.5","","00040","0"*500+"99","999999999999"]:
        report("progress_"+str(len(result)),"state=working:progress="+n)
    for body in ["", "app=cargo", "state=idle:state=unknown", "state=idle:state=done:app=a:app=b",
        "state=idle:state=@", "garbage:=x:Bad=1:app=b@d:future=yes:state=working:app=ok:::",
        " state = working :\tapp=cargo\t", "state=blocked:kind=auth:kind=@:progress=3:progress=101",
        "state=idle:app=a/b", "state=done:msg=:title=", "state=done:msg=a:msg=QQ==",
        "state=done:msg=QQ==:msg=@", "state=clear:id=bad//id:id=ok", "state=clear:id=@"]:
        report("pairs_"+str(len(result)),body)
    for ident in ["x","a/b",".","..","a_+-.9","", "/a","a/","a//b","a@b","é",
        "a"*32,"a"*33,"/".join(["a"]*8),"/".join(["a"]*9),"/".join(["a"*31]*4),"/".join(["a"*32]*4)]:
        report("id_"+str(len(result)),"state=clear:id="+ident)
    for key, limit in [("app",32),("title",256),("msg",2732)]:
        for size in [limit,limit+1]:
            value = "a"*size if key == "app" else "Q"*size
            report(f"limit_{key}_{size}","state=idle:"+key+"="+value+":"+key+"=ok")
        report("invalid_alphabet_limit_"+key,"state=idle:"+key+"=@"+"a"*limit)
    for key, limit in [("title",192),("msg",2048)]:
        for size in [limit-1,limit,limit+1]:
            report(f"decoded_{key}_{size}","state=done:"+key+"="+b64(b"A"*size))
        for padding in [True,False]:
            report(f"utf8_{key}_{padding}","state=done:"+key+"="+(b64("Привет 世界😀".encode()) if padding else b64("Привет 世界😀".encode()).rstrip("=")))
        for code in list(range(32))+list(range(127,160))+[0x202e,0x10ffff]:
            report(f"text_{key}_{code}","state=done:"+key+"="+b64(chr(code).encode()))
        for value in [b"\xff",b"\xc0\x80",b"\xed\xa0\x80",b"\xf4\x90\x80\x80",b"\xe2\x82"]:
            report("invalid_utf8_"+key+value.hex(),"state=done:"+key+"="+b64(value))
        for value in ["a","QQ=","QQ===","QR==","QR","QUJ=","QUJ","QQ","QQ==","QUJD","=","====","QQ==AA"]:
            report("base64_"+key+str(len(result)),"state=done:"+key+"="+value)
    for n in [16,17]: report("key_limit_"+str(n),"state=idle:"+"x"*n+"=1")
    for n in [4086,4087,4088]:
        body = "state=idle:"+"x"*(n-len("state=idle:"))
        add("body_limit_"+str(n),[osc(body),osc(body,"\x07")])
    for prefix in ["75","750","75010","07501","7501"]:
        add("prefix_"+prefix,["\x1b]"+prefix+";state=idle\x1b\\","\x1b]"+prefix+"\x1b\\"])
    return result


def write_generated(path, value):
    if path.exists() and path.read_text() == value: return
    with tempfile.NamedTemporaryFile(mode="w",encoding="utf-8",dir=path.parent,delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(value); output.close(); temporary.replace(path)
        finally: temporary.unlink(missing_ok=True)


def main():
    upstream, zig = [Path(arg).resolve() for arg in sys.argv[1:3]]
    assert subprocess.check_output(["git","rev-parse","HEAD"],cwd=upstream,text=True).strip() == SHA
    subprocess.run(["git","diff","--exit-code","HEAD","--","."],cwd=upstream,check=True,stdout=subprocess.DEVNULL)
    build = ROOT/"build/terminal-program-status-oracle"
    build.mkdir(parents=True,exist_ok=True)
    corpus = cases()
    (build/"cases.json").write_text(json.dumps(corpus,ensure_ascii=True))
    helper = ROOT/"scripts/terminal-ghostty-program-status-oracle.zig"
    shutil.copyfile(helper,build/"main.zig")
    (build/"build.zig.zon").write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.17.0", .dependencies = .{ .ghostty = .{ .path = '+json.dumps(os.path.relpath(upstream,build))+' } }, .paths = .{""} }')
    (build/"build.zig").write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const dep = b.dependency("ghostty", .{ .target = target, .optimize = .safe, .simd = false, .@"emit-lib-vt" = true, .@"emit-xcframework" = false });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .safe, .link_libc = true });
    root.addImport("ghostty-vt", dep.module("ghostty-vt"));
    b.installArtifact(b.addExecutable(.{ .name = "program-status-oracle", .root_module = root }));
}
''')
    env = os.environ | {"ZIG_GLOBAL_CACHE_DIR":os.environ.get("ZIG_GLOBAL_CACHE_DIR",str(ROOT/"build/upstream/zig-cache-0.17"))}
    subprocess.run([str(zig),"build"],cwd=build,check=True,env=env)
    output = subprocess.run([str(build/"zig-out/bin/program-status-oracle")],capture_output=True,text=True)
    if output.returncode: print(output.stderr,file=sys.stderr)
    output.check_returncode()
    actual = [json.loads(line) for line in output.stdout.splitlines()]
    assert len(corpus) == len(actual)
    for case, result in zip(corpus,actual):
        assert case["name"] == result["name"]
        case["snapshots"] = result["snapshots"]
    fixture = ROOT/"kinetica-terminal/test/generated/TerminalProgramStatusFixtures.kt"
    encoded = [base64.b64encode(json.dumps(c,separators=(',',':')).encode()).decode() for c in corpus]
    expressions = ['listOf('+','.join('"'+v[i:i+8000]+'"' for i in range(0,len(v),8000))+').joinToString("")' for v in encoded]
    write_generated(fixture,'// Generated by scripts/generate-terminal-program-status.py; do not edit.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalProgramStatusFixtures(): List<String> = listOf(\n'+''.join('    '+v+',\n' for v in expressions)+')\n')
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    manifest = dict(commit=SHA,zig=subprocess.check_output([str(zig),"version"],text=True).strip(),
        cases=len(corpus),snapshots=sum(len(c["snapshots"]) for c in corpus),
        scope="Original parser, typed effect reports and support replies. Registry/lifecycle policies are verified separately against the protocol specification.",
        helperSha256=digest(helper),generatorSha256=digest(Path(__file__)),fixtureSha256=digest(fixture),
        sources={p:digest(upstream/p) for p in ["src/terminal/osc/parsers/program_status.zig","src/terminal/osc/kitty_metadata.zig","src/terminal/osc/encoding.zig","src/terminal/stream_terminal.zig","src/terminal/osc.zig"]})
    write_generated(ROOT/"kinetica-terminal/test-upstream/ghostty/program-status-manifest.json",json.dumps(manifest,indent=2)+'\n')
    print(f'Generated {len(corpus)} program-status cases / {manifest["snapshots"]} checkpoints')


if __name__ == "__main__": main()
