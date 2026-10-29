#!/usr/bin/env python3
"""Run original Ghostty native file/shm readers; commit portable setup recipes and results."""
import base64
import ctypes
import hashlib
import json
import mmap
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parent.parent
SHA = "83fb95624b0439ee660c2679b43b38c8b63769cd"


def cases():
    result = []
    def add(name, medium, **values):
        result.append(dict(name=name,medium=medium,data="010203",offset=0,size=0,format=24,width=1,height=1,
            compressed=False,allowed=True,pathKind="file",location="temp",goodName=True,configuredAlias=False,
            shmName="slash",objectSize=0) | values)
    for medium in "fts":
        add(medium+"_basic",medium)
        add(medium+"_denied",medium,allowed=False)
        add(medium+"_offset",medium,data="000102030405",offset=1,size=3)
        add(medium+"_remainder",medium,data="00010203",offset=1)
        add(medium+"_short",medium,size=4)
        add(medium+"_past_end",medium,offset=9)
        add(medium+"_at_end",medium,offset=3)
        add(medium+"_huge_size",medium,size=4294967295)
        add(medium+"_huge_offset",medium,offset=4294967295)
        add(medium+"_nul",medium,pathKind="nul")
        add(medium+"_missing",medium,pathKind="missing")
        add(medium+"_empty_name",medium,pathKind="empty")
    for medium in "ft":
        add(medium+"_directory",medium,pathKind="directory")
        add(medium+"_device",medium,pathKind="device")
        add(medium+"_relative",medium,pathKind="relative")
    for location in ["custom","outside","prefix"]:
        add("temp_"+location,"t",location=location)
    add("temp_custom_alias","t",location="custom",configuredAlias=True)
    add("temp_bad_name","t",goodName=False)
    add("temp_symlink_outside","t",pathKind="symlink_outside")
    add("temp_symlink_inside","t",pathKind="symlink_inside")
    add("temp_symlink_bad_name","t",pathKind="symlink_bad_name")
    for form in ["plain","group","slash_only","too_long"]:
        add("shm_"+form,"s",shmName=form)
    add("shm_padding","s",objectSize=65536)
    add("shm_unaligned","s",data="01020304",offset=1,size=3,objectSize=65536)
    add("shm_png_remainder","s",format=100,objectSize=12)
    add("shm_compressed_remainder","s",compressed=True,objectSize=12)
    add("shm_huge_dimensions","s",width=4294967295,size=1)
    add("shm_huge_dimensions_and_offset","s",width=4294967295,offset=4294967295)
    add("shm_empty_object","s",data="")
    return result


def write_generated(path, text):
    path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists() and path.read_text() == text: return
    with tempfile.NamedTemporaryFile(mode="w",encoding="utf-8",dir=path.parent,delete=False) as f:
        temp = Path(f.name)
        try: f.write(text); f.close(); temp.replace(path)
        finally: temp.unlink(missing_ok=True)


def main():
    if sys.platform != "darwin": raise SystemExit("Native corpus requires macOS")
    upstream,zig = [Path(p).resolve() for p in sys.argv[1:3]]
    assert subprocess.check_output(["git","rev-parse","HEAD"],cwd=upstream,text=True).strip() == SHA
    subprocess.run(["git","diff","--exit-code","HEAD","--","."],cwd=upstream,check=True,stdout=subprocess.DEVNULL)
    build = ROOT/"build/terminal-native-media-oracle"
    build.mkdir(parents=True,exist_ok=True)
    helper = ROOT/"scripts/terminal-ghostty-native-media-oracle.zig"
    shutil.copyfile(helper,build/"main.zig")
    (build/"build.zig.zon").write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.17.0", .dependencies = .{ .ghostty = .{ .path = '+json.dumps(os.path.relpath(upstream,build))+' } }, .paths = .{""} }')
    (build/"build.zig").write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const dep = b.dependency("ghostty", .{ .target = target, .optimize = .safe, .simd = false, .@"emit-lib-vt" = true, .@"emit-xcframework" = false });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .safe, .link_libc = true });
    root.addImport("ghostty-vt", dep.module("ghostty-vt"));
    b.installArtifact(b.addExecutable(.{ .name = "native-media-oracle", .root_module = root }));
}
''')
    libc = ctypes.CDLL(None,use_errno=True)
    # Darwin shm_open is variadic: arm64 passes mode on the stack, not as a third fixed arg.
    libc.shm_open.argtypes=[ctypes.c_char_p,ctypes.c_int]
    libc.shm_unlink.argtypes=[ctypes.c_char_p]
    corpus=cases(); inputs=[]; objects=[]; resources=[]
    with tempfile.TemporaryDirectory(prefix="kinetica-media-",dir="/private/tmp") as temp, tempfile.TemporaryDirectory(prefix="fixtures-",dir=build) as work:
        try:
            for index,case in enumerate(corpus):
                folder=Path(temp)/str(index); folder.mkdir()
                outside=Path(work)/str(index); outside.mkdir()
                custom=outside/"custom"; custom.mkdir()
                configured=custom
                if case["configuredAlias"]:
                    configured=outside/"alias"; configured.symlink_to(custom)
                data=bytes.fromhex(case["data"])
                filename="tty-graphics-protocol-image" if case["goodName"] else "image"
                parent={"temp":folder,"custom":custom,"outside":outside,"prefix":outside/"custom-other"}[case["location"]]
                parent.mkdir(exist_ok=True)
                target=parent/filename; link=None; shm=None
                if case["medium"] == "s":
                    stem="kg"+uuid.uuid4().hex[:15]
                    name={"slash":"/"+stem,"plain":stem,"group":"kg.group/"+stem,"slash_only":"/","too_long":"x"*256}[case["shmName"]]
                    if case["pathKind"] not in ["missing","empty"] and case["shmName"] not in ["slash_only","too_long"]:
                        shm=name.encode(); fd=libc.shm_open(shm,os.O_RDWR|os.O_CREAT|os.O_EXCL,0o600)
                        if fd < 0: raise OSError(ctypes.get_errno(),"Cannot create isolated oracle shared memory")
                        objects.append(shm)
                        try:
                            size=max(len(data),case["objectSize"]); os.ftruncate(fd,size)
                            if size:
                                with mmap.mmap(fd,size) as mapping: mapping[:len(data)]=data
                        finally: os.close(fd)
                    path=name.encode()
                else:
                    if case["pathKind"] == "directory": target.mkdir()
                    elif case["pathKind"] not in ["missing","empty","device"]: target.write_bytes(data)
                    if case["pathKind"].startswith("symlink"):
                        target.unlink()
                        if case["pathKind"] == "symlink_outside": target=outside/filename; link=folder/filename
                        elif case["pathKind"] == "symlink_inside": target=folder/filename; link=outside/filename
                        else: target=folder/"image"; link=outside/filename
                        target.write_bytes(data); link.symlink_to(target)
                    path=os.fsencode(link or target)
                    if case["pathKind"] == "relative": path=os.fsencode(os.path.relpath(target,ROOT))
                    if case["pathKind"] == "device": path=b"/dev/null"
                if case["pathKind"] == "nul": path += b"\0suffix"
                if case["pathKind"] == "empty": path=b""
                resources.append((case,target,link,shm))
                inputs.append(dict(name=case["name"],path_hex=path.hex(),medium=case["medium"],temporary_directory=str(configured),
                    **{k:case[k] for k in ["allowed","offset","size","format","width","height","compressed"]}))
            (build/"cases.json").write_text(json.dumps(inputs))
            env=os.environ|{"ZIG_GLOBAL_CACHE_DIR":str(ROOT/"build/upstream/zig-cache-0.17")}
            subprocess.run([str(zig),"build"],cwd=build,env=env,check=True)
            results=[]
            for case in corpus:
                run=subprocess.run([str(build/"zig-out/bin/native-media-oracle")],cwd=ROOT,capture_output=True,text=True,
                    env=os.environ|{"KINETICA_MEDIA_CASE":case["name"]})
                if run.returncode == -6 and "panic: integer overflow" in run.stderr and case["medium"] in "ft" and case["offset"] > len(bytes.fromhex(case["data"])) and case["size"] == 0:
                    results.append(dict(name=case["name"],upstreamCrash="SIGABRT: integer overflow",error_name=None,bytes=None))
                else:
                    if run.returncode: print(run.stderr,file=sys.stderr)
                    run.check_returncode()
                    results.append(json.loads(run.stdout))
            assert len(results)==len(corpus)
            for expected,(case,target,link,shm) in zip(results,resources):
                assert expected.pop("name")==case["name"]
                if shm is not None:
                    fd=libc.shm_open(shm,os.O_RDONLY,0); expected["exists"]=fd>=0
                    if fd>=0: os.close(fd)
                    elif ctypes.get_errno()!=2: raise OSError(ctypes.get_errno(),"Unexpected shm reopen failure")
                elif case["medium"]!="s" and case["pathKind"] not in ["device","empty","missing"]: expected["exists"]=target.exists()
                else: expected["exists"]=None
                expected["linkExists"]=link.is_symlink() if link else None
                case["expected"]=expected
        finally:
            for name in objects: libc.shm_unlink(name)
    fixture=ROOT/"kinetica-terminal/test@macosArm64/generated/TerminalNativeMediaFixtures.kt"
    encoded=[base64.b64encode(json.dumps(c,separators=(',',':')).encode()).decode() for c in corpus]
    write_generated(fixture,'// Generated by scripts/generate-terminal-native-media.py; do not edit.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalNativeMediaFixtures(): List<String> = listOf(\n'+''.join('    "'+s+'",\n' for s in encoded)+')\n')
    digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    manifest=dict(commit=SHA,zig=subprocess.check_output([str(zig),"version"],text=True).strip(),platform="macos-arm64",cases=len(corpus),
        scope="Original LoadingImage.init native readers, raw encoded bytes, errors and resource deletion. PNG capability enabled with a callback that traps if invoked: decoding is outside this reader-only oracle. Recorded upstream crashes are not conformance expectations; recovery is tested separately. Decode/render behavior is tested separately.",
        upstreamCrashes=[c["name"] for c in corpus if "upstreamCrash" in c["expected"]],
        helperSha256=digest(helper),generatorSha256=digest(Path(__file__)),fixtureSha256=digest(fixture),
        sourceSha256=digest(upstream/"src/terminal/kitty/graphics_image.zig"))
    write_generated(ROOT/"kinetica-terminal/test-upstream/ghostty/native-media-manifest.json",json.dumps(manifest,indent=2)+'\n')
    print(f'Generated {len(corpus)} original native-media cases')


if __name__ == "__main__": main()
