#!/usr/bin/env python3
"""Generate frame composition/playback expectations from the pinned Ghostty Zig module."""
import base64
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SHA = "4da7523faba68ccb4042ea20585817098a51c015"

def cases():
    result=[]
    def command(header,data=b''):
        return {'text':'\x1b_G'+header+(';' + base64.b64encode(data).decode() if data else '')+'\x1b\\'}
    def root(width=3,height=2,fmt=24,data=None,placed=True):
        if data is None: data=bytes((i*19+3)%256 for i in range(width*height*(fmt//8)))
        return command(f'a={"T" if placed else "t"},i=1,q=2,C=1,f={fmt},s={width},v={height}',data)
    def frame(data=bytes([200,100,50,128]),fields='',width=1,height=1,fmt=32):
        return command(f'a=f,i=1,q=2,f={fmt},s={width},v={height}'+(','+fields if fields else ''),data)
    for da in range(256):
        dst=bytes(v for sa in range(256) for v in ((sa*7)%256,(sa*13+1)%256,(sa*23+255)%256,da))
        src=bytes(v for sa in range(256) for v in ((sa*31+99)%256,(sa*43+3)%256,(sa*11+71)%256,sa))
        result.append(dict(name=f'blend_alpha_{da}',events=[root(256,1,32,dst),frame(src,'r=1',256,1)]))
    for mode in [0,1,2,4294967295]:
        for x,y in [(0,0),(2,1),(3,0),(0,2),(4294967295,4294967295)]:
            result.append(dict(name=f'load_clip_{mode}_{x}_{y}',events=[root(),
                frame(fields=f'X={mode},x={x},y={y},Y=4278190335,z=-1'),
                frame(fields=f'X={mode},x={x},y={y},c=2,r=999,z=50'),
                frame(fields=f'X={mode},x={x},y={y},r=1,z=12'),
                frame(fields='c=999'),frame(fields='r=2,z=-1')]))
    for source,dest in [(1,1),(1,2),(2,1),(2,2),(0,1),(1,0),(9,1),(1,9)]:
        for rect in ['',',w=1,h=1,x=2,y=1',',w=1,h=1,X=2,Y=1',',w=1,h=1,X=1,x=2',',w=4294967295,h=4294967295',',w=1,h=1,x=4294967295']:
            result.append(dict(name=f'compose_{source}_{dest}_{rect}',events=[root(),frame(fields='Y=16909060'),
                command(f'a=c,i=1,q=2,r={source},c={dest}'+rect),command(f'a=c,i=1,q=2,C=1,r={source},c={dest}'+rect)]))
    for state in [1,2,3,4,4294967295]:
        for loops in [0,1,2,3,4294967295]:
            events=[root(),frame(fields='z=-1'),frame(fields='z=10'),frame(fields='z=20'),
                    command(f'a=a,i=1,r=1,z=5,s={state},v={loops}')]
            events += [{'tick':n} for n in [0,4,5,14,15,34,35,1000,1001,1010,1011,2,7,17,37,42,52,72]]
            events += [command('a=a,i=1,c=2'),{'tick':100},command('a=a,i=1,s=1'),{'tick':999},command('a=a,i=1,s=3'),{'tick':1000}]
            result.append(dict(name=f'playback_{state}_{loops}',events=events))
    for current in range(1,5):
        for number in [0,1,2,3,4,4294967295]:
            result.append(dict(name=f'delete_{current}_{number}',events=[root(),frame(fields='z=11'),frame(fields='z=12'),frame(fields='z=13'),
                command(f'a=a,i=1,c={current},r=1,z=10,s=3'),{'tick':100},command(f'a=d,i=1,d=f,r={number}'),
                {'tick':105},command('a=d,i=1,d=f,r=1'),{'tick':120},command('a=d,i=1,d=f,r=999'),{'tick':150}]))
    result.append(dict(name='loading_waits_for_arrival',events=[root(),frame(fields='z=10'),command('a=a,i=1,s=2'),{'tick':0},{'tick':10},
        frame(fields='z=20'),{'tick':100},{'tick':120},command('a=a,i=1,s=3'),{'tick':121}]))
    result.append(dict(name='unplaced_and_all_gapless',events=[root(placed=False),frame(fields='z=-1'),command('a=a,i=1,s=3'),{'tick':0},
        command('a=p,i=1,q=2,C=1'),{'tick':1},command('a=a,i=1,r=2,z=10'),{'tick':2},{'tick':12}]))
    result.append(dict(name='control_on_rgb',events=[root(),command('a=a,i=1,r=1,z=5'),command('a=a,i=1,s=3'),{'tick':100},
        command('a=a,i=1,r=999,c=999,s=0,v=1'),command('a=c,i=1,r=1,c=1,w=1,h=1,x=2,C=1')]))
    rng=random.Random(7411)
    for i in range(24):
        events=[root(fmt=32,data=bytes(rng.randrange(256) for _ in range(24)))]
        for j in range(6): events.append(frame(bytes(rng.randrange(256) for _ in range(24)),f'r={rng.randrange(6)},c={rng.randrange(4)},z={rng.choice([-1,0,1,40])},X={rng.randrange(2)}',3,2))
        result.append(dict(name=f'random_pixels_{i}',events=events))
    return result

def main():
    upstream=Path(sys.argv[1]).resolve(); zig=Path(sys.argv[2]).resolve()
    assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=upstream,text=True).strip()==SHA
    subprocess.run(['git','diff','--exit-code','HEAD','--','.'],cwd=upstream,check=True,stdout=subprocess.DEVNULL)
    build=ROOT/'build/terminal-animation-oracle'; build.mkdir(parents=True,exist_ok=True)
    corpus=cases(); (build/'cases.json').write_text(json.dumps(corpus,ensure_ascii=True))
    helper=ROOT/'scripts/terminal-ghostty-animation-oracle.zig'; shutil.copyfile(helper,build/'main.zig')
    (build/'build.zig.zon').write_text('.{ .name = .zig_vt_stream, .version = "0.0.0", .fingerprint = 0x34c1f71303690b3f, .minimum_zig_version = "0.16.0", .dependencies = .{ .ghostty = .{ .path = '+json.dumps(os.path.relpath(upstream,build))+' } }, .paths = .{""} }')
    (build/'build.zig').write_text('''const std = @import("std");
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const dep = b.dependency("ghostty", .{ .target = target, .optimize = .ReleaseSafe, .simd = false, .@"emit-lib-vt" = true, .@"emit-xcframework" = false });
    const root = b.createModule(.{ .root_source_file = b.path("main.zig"), .target = target, .optimize = .ReleaseSafe, .link_libc = true });
    root.addImport("ghostty-vt", dep.module("ghostty-vt"));
    b.installArtifact(b.addExecutable(.{ .name = "animation-oracle", .root_module = root }));
}
''')
    env=os.environ | {'ZIG_GLOBAL_CACHE_DIR':os.environ.get('ZIG_GLOBAL_CACHE_DIR',str(ROOT/'build/upstream/zig-cache'))}
    subprocess.run([str(zig),'build'],cwd=build,check=True,env=env)
    output=subprocess.run([str(build/'zig-out/bin/animation-oracle')],capture_output=True,text=True)
    if output.returncode: print(output.stderr,file=sys.stderr)
    output.check_returncode()
    actual=[json.loads(line) for line in output.stdout.splitlines()]
    assert len(actual)==len(corpus)
    for case,snapshot in zip(corpus,actual):
        assert case['name']==snapshot['name']; case['snapshots']=snapshot['snapshots']
    fixture=ROOT/'kinetica-terminal/test/generated/TerminalGraphicsAnimationFixtures.kt'
    encoded=[base64.b64encode(json.dumps(case,separators=(',',':')).encode()).decode() for case in corpus]
    text='// Generated by scripts/generate-terminal-graphics-animation.py; do not edit.\npackage io.heapy.kinetica.terminal\n\n'
    text+='internal fun terminalGraphicsAnimationFixtures(): List<String> = listOf(\n'+''.join(f'    animationCase{i}(),\n' for i in range(len(corpus)))+')\n'
    for i,value in enumerate(encoded):
        text+=f'private fun animationCase{i}(): String = '+('"'+value+'"' if len(value)<60000 else 'listOf('+','.join('"'+value[j:j+60000]+'"' for j in range(0,len(value),60000))+').joinToString("")')+'\n'
    fixture.write_text(text)
    digest=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
    manifest=dict(commit=SHA,zig=subprocess.check_output([str(zig),'version'],text=True).strip(),cases=len(corpus),snapshots=sum(len(c['snapshots']) for c in corpus),
        helperSha256=digest(helper),generatorSha256=digest(Path(__file__)),fixtureSha256=digest(fixture),
        sources={p:digest(upstream/p) for p in ['src/terminal/kitty/graphics_animation.zig','src/terminal/kitty/graphics_pixel.zig','src/terminal/kitty/graphics_exec.zig','src/terminal/kitty/graphics_storage.zig','pkg/wuffs/src/swizzle.zig']})
    (ROOT/'kinetica-terminal/test-upstream/ghostty/graphics-animation-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'Generated {len(corpus)} animation cases and {manifest["snapshots"]} checkpoints')

if __name__=='__main__': main()
