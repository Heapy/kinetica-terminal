#!/usr/bin/env python3
"""Compare OSC 5522 metadata, writes, responses and grants with original Ghostty 1.4.0."""
import base64
import hashlib
import json
import os
import random
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SHA = "83fb95624b0439ee660c2679b43b38c8b63769cd"


def cases():
    result = []
    def b64(value): return base64.b64encode(value.encode() if isinstance(value, str) else value).decode()
    def meta(raw): result.append(dict(name=f"metadata_{len(result)}", metadata=raw))
    def transaction(name, events, limit=64*1024*1024): result.append(dict(name=name, events=events, limit=limit))
    def data(value, mime="text/plain"): return dict(op="data", mime=mime, payload=value)
    def alias(value, mime="text/plain"): return dict(op="alias", mime=mime, payload=b64(value))
    commit = dict(op="commit")
    for raw in ["", "type", "type=read:", ":type=read", "x=1", "type=nope", "type=", "type=READ",
        "type=nope:type=read", "type=read:type=nope", "type=write", "type=wdata", "type=walias",
        "type=read:x=y:=z", "type=read:loc=primary", "type=read:loc=selection", "type=read:loc=primary:loc=c",
        "type=read:password=!!!", "type=read:id=*4 2*", "type=write:id="+"é/ab_-.+09"*100,
        "type=wdata:mime=!!!:mime=dGV4dC9wbGFpbg==", "type=wdata:mime=dGV4dC9wbGFpbg==:mime=!!!",
        "type=read:pw=cHc=", "type=read:pw=cHc=:name=YXBw"]:
        meta(raw)
    for key, limit in [("mime",256),("name",256),("pw",128)]:
        for size in [0,1,limit-1,limit,limit+1,limit+2,limit+10]:
            meta(f"type=wdata:{key}="+b64(b"x"*size))
        for raw in ["Q", "QQ", "QQ=", "QQ==", "QR==", "QUJ=", "QUJD", "QQ===", "====", "=AAA", "AA=A",
                    "QQ==AA==", "QUJD\n", "QUJD ", "-w==", "_w==", "!!!"*150]:
            meta(f"type=wdata:{key}="+raw)
        for raw in [b"\xff",b"\xc0\x80",b"\xed\xa0\x80",b"\xf4\x90\x80\x80",b"\xe2\x82",b"\x00\n", "Привет 世界😀".encode()]:
            meta(f"type=walias:{key}="+b64(raw))
        meta(f"type=read:{key}="+b64("界"*(limit//3)))
        meta(f"type=read:{key}="+b64("界"*(limit//3+1)))
    transaction("empty",[commit,commit])
    payload = b64(bytes(range(256)))
    for split in range(len(payload)+1):
        transaction(f"split_{split}",[data(payload[:split]),data(payload[split:]),commit])
    transaction("single_character_packets",[data(c) for c in payload]+[commit])
    transaction("padded_chunks",[data(b64("one")),data(b64("two!")),data(""),data(b64("3")),commit])
    transaction("overwrite",[data(b64("first")),data(b64("html"),"text/html"),data(b64("last")),commit])
    transaction("empty_region",[data(""),data("","text/html"),commit])
    transaction("aliases",[alias("TEXT UTF8_STRING"),data(b64("hello")),alias("application/x-chain","TEXT"),
        alias("orphan","missing"),alias("TEXT","application/x-chain"),commit])
    transaction("alias_order",[alias("early","late"),alias("late"),data(b64("text")),commit])
    transaction("alias_collision",[data(b64("text")),data(b64("html"),"text/html"),alias("text/plain","text/html"),commit])
    transaction("alias_update",[alias("A B"),alias("A","text/html"),data(b64("text")),data(b64("html"),"text/html"),commit])
    transaction("alias_whitespace",[data(b64("text")),alias(" a\tb\nc\rd\ve\ff\u00a0g "+"x"*257),commit])
    transaction("alias_between_partial_groups",[data("a"),alias("TEXT"),data("Gk="),commit])
    for payload in ["!", " ", "A=", "AA=A", "QQ===", "QQ==A", "QR==", "QUJ=", "QQ", "QQ=", "QQ==AA==", "-w==", "_w=="]:
        transaction(f"invalid_data_{len(result)}",[data(payload),commit,data(b64("later"))])
        transaction(f"invalid_alias_{len(result)}",[dict(op="alias",mime="text/plain",payload=payload),commit])
    transaction("invalid_alias_utf8",[dict(op="alias",mime="text/plain",payload=b64(b"\xff")),commit])
    transaction("unfinished_switch",[data("QQ"),data(b64("html"),"text/html"),commit])
    for limit in [0,1,2,3,4,5,6]:
        transaction(f"budget_{limit}",[data(b64("123")),data(b64("456")),commit],limit)
    transaction("overwritten_budget",[data(b64("123")),data(b64("456"),"text/html"),data(b64("7")),commit],6)
    transaction("invalid_beats_budget",[data("QUJD!!!!"),commit],0)
    transaction("partial_budget",[data("QUJ"),data("D"),commit],2)
    transaction("mime_limit",[data(b64(str(i)),f"application/x-{i}") for i in range(65)]+[data("!!!","application/x-64"),data(b64("replaced"),"application/x-0"),commit])
    transaction("alias_limit",[data(b64("text")),alias(" ".join(f"alias-{i}" for i in range(65))),
        alias("alias-0","missing"),alias("extra alias-1","missing"),commit])
    def response(**value): result.append(dict(name=f"response_{len(result)}",response=value))
    for operation in ["read","write","wdata","walias"]:
        for status in ["DATA","DONE","EBUSY","EFBIG","EINVAL","EIO","ENOSYS","EPERM","OK"]:
            for terminator in ["st","bel"]:
                response(kind="packet",operation=operation,status=status,terminator=terminator)
    for mime in [None,"","text/plain","应用/数据"]:
        for password in [None,"","secret","пароль"]:
            response(kind="packet",operation="read",status="DATA",primary=True,id="aA09-_+.",mime=mime,password=password,payload="00ff0a80")
    response(kind="packet",id="x"*512)
    for primary in [False,True]:
        for terminator in ["st","bel"]:
            for listing in [False,True]:
                response(kind="read",primary=primary,terminator=terminator,list=listing)
                response(kind="read",primary=primary,terminator=terminator,list=listing,id="42",password="otp",
                    available=["text/plain","image/png"],contents=[dict(mime="text/plain",hex="6869"),dict(mime="image/png",hex="00ff"),dict(mime="empty",hex="")])
            response(kind="paste",primary=primary,terminator=terminator,password="paste-password",available=["text/plain","image/png"])
    for size in [0,1,2,3,4095,4096,4097,8191,8192,8193,65536]:
        response(kind="read",id="chunked",contents=[dict(mime="application/octet-stream",hex=bytes(i%256 for i in range(size)).hex())])
    for available in [[],[""],["",""],["x"*4094],["x"*4095],["x"*4096],
                      ["x"*4094,"y"],["x"*4093,"y"],["界"*1364],["界"*1365],["界"*1366],
                      ["text/plain","x"*4096,"image/png"],[f"application/x-{i}" for i in range(64)]]:
        response(kind="read",list=True,available=available)
    def grant(password,direction="read",one_time=False): return dict(action="grant",password=password,direction=direction,one_time=one_time)
    def use(password,direction="read"): return dict(action="use",password=password,direction=direction)
    def grants(name,events): result.append(dict(name=name,grants=events))
    for direction in ["read","write"]:
        for one_time in [False,True]:
            grants(f"grants_basic_{direction}_{one_time}",[use("pw"),grant("pw",direction,one_time),use("pw",direction),use("pw",direction)])
            grants(f"grants_mismatch_{direction}_{one_time}",[grant("pw",direction,one_time),use("pw","write" if direction=="read" else "read"),use("pw",direction)])
    for first in [False,True]:
        for second in [False,True]:
            grants(f"grants_merge_{first}_{second}",[grant("pw","read",first),grant("pw","write",second),use("pw"),use("pw","write"),use("pw")])
    for password in ["","x","x"*128,"x"*129,"界"*42,"界"*43,"\x00\n", "Привет"]:
        grants(f"grants_length_{len(result)}",[grant(password),use(password),dict(action="clear"),use(password)])
    grants("grants_capacity",[grant(str(i)) for i in range(33)]+[use(str(i)) for i in range(33)])
    grants("grants_no_recency",[grant(str(i)) for i in range(32)]+[use("0"),grant("0","write"),grant("32"),use("0","write"),use("1")])
    grants("grants_swap_eviction",[grant(str(i),one_time=True) for i in range(32)]+[use("0"),grant("32"),grant("33")]+[use(str(i)) for i in [31,1,2,32,33]])
    rng=random.Random(5522)
    events=[]
    for _ in range(2000):
        password=str(rng.randrange(40)); direction=rng.choice(["read","write"])
        events.append(grant(password,direction,rng.choice([True,False])) if rng.randrange(3)==0 else use(password,direction))
    grants("grants_interleaved",events)
    return result


def write_generated(path, text):
    if path.exists() and path.read_text() == text: return
    with tempfile.NamedTemporaryFile(mode="w",encoding="utf-8",dir=path.parent,delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(text); output.close(); temporary.replace(path)
        finally: temporary.unlink(missing_ok=True)


def main():
    upstream, zig = [Path(arg).resolve() for arg in sys.argv[1:3]]
    assert subprocess.check_output(["git","rev-parse","HEAD"],cwd=upstream,text=True).strip() == SHA
    subprocess.run(["git","diff","--exit-code","HEAD","--","."],cwd=upstream,check=True,stdout=subprocess.DEVNULL)
    build = ROOT/"build/terminal-clipboard-core-oracle"
    build.mkdir(parents=True,exist_ok=True)
    corpus = cases()
    (build/"cases.json").write_text(json.dumps(corpus,ensure_ascii=True))
    helper = ROOT/"scripts/terminal-ghostty-clipboard-core-oracle.zig"
    shutil.copyfile(helper,build/"main.zig")
    (build/"build.zig.zon").write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.17.0", .dependencies = .{ .ghostty = .{ .path = '+json.dumps(os.path.relpath(upstream,build))+' } }, .paths = .{""} }')
    (build/"build.zig").write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const dep = b.dependency("ghostty", .{ .target = target, .optimize = .safe, .simd = false, .@"emit-lib-vt" = true, .@"emit-xcframework" = false });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .safe, .link_libc = true });
    root.addImport("ghostty-vt", dep.module("ghostty-vt"));
    b.installArtifact(b.addExecutable(.{ .name = "clipboard-core-oracle", .root_module = root }));
}
''')
    env = os.environ | {"ZIG_GLOBAL_CACHE_DIR":os.environ.get("ZIG_GLOBAL_CACHE_DIR",str(ROOT/"build/upstream/zig-cache-0.17"))}
    subprocess.run([str(zig),"build"],cwd=build,check=True,env=env)
    output = subprocess.run([str(build/"zig-out/bin/clipboard-core-oracle")],capture_output=True,text=True)
    if output.returncode: print(output.stderr,file=sys.stderr)
    output.check_returncode()
    actual = [json.loads(line) for line in output.stdout.splitlines()]
    assert len(corpus) == len(actual)
    for case, result in zip(corpus,actual):
        assert case["name"] == result.pop("name")
        case.update(result)
    fixture = ROOT/"kinetica-terminal/test/generated/TerminalKittyClipboardCoreFixtures.kt"
    encoded = [base64.b64encode(json.dumps(c,separators=(',',':')).encode()).decode() for c in corpus]
    expressions = ['listOf('+','.join('"'+v[i:i+8000]+'"' for i in range(0,len(v),8000))+').joinToString("")' for v in encoded]
    write_generated(fixture,'// Generated from Ghostty '+SHA+' (MIT); do not edit.\n// Regenerate with scripts/generate-terminal-clipboard-core.py.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalKittyClipboardCoreFixtures(): List<String> = listOf(\n'+''.join('    '+v+',\n' for v in expressions)+')\n')
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    manifest = dict(commit=SHA,zig=subprocess.check_output([str(zig),"version"],text=True).strip(),
        cases=len(corpus),snapshots=sum(len(c.get("snapshots",[None])) for c in corpus),
        scope="Original Metadata, WriteState, Response, ReadSuccess, PasteEvent and Grants modules. No session dispatch, authorization callbacks, entropy source or platform clipboard coverage.",
        helperSha256=digest(helper),generatorSha256=digest(Path(__file__)),fixtureSha256=digest(fixture),
        sources={p:digest(upstream/p) for p in ["src/terminal/kitty/clipboard_command.zig","src/terminal/kitty/clipboard_write.zig","src/terminal/kitty/clipboard_response.zig","src/terminal/kitty/clipboard_grants.zig","src/terminal/osc/parsers/kitty_clipboard_protocol.zig","src/simd/base64.zig"]})
    write_generated(ROOT/"kinetica-terminal/test-upstream/ghostty/clipboard-core-manifest.json",json.dumps(manifest,indent=2)+'\n')
    print(f'Generated {len(corpus)} clipboard core cases / {manifest["snapshots"]} checkpoints')


if __name__ == "__main__": main()
