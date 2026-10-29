#!/usr/bin/env python3
"""Run complete APC streams and terminal operations against pinned libghostty."""
import base64
import hashlib
import json
from pathlib import Path
import runpy
import shlex
import subprocess
import sys
import zlib

ROOT = Path(__file__).resolve().parent.parent
COMMON = runpy.run_path(str(ROOT / "scripts/generate-terminal-oracle.py"))
STORAGE = runpy.run_path(str(ROOT / "scripts/generate-terminal-graphics-fixtures.py"))
GEOMETRY = runpy.run_path(str(ROOT / "scripts/generate-terminal-graphics-geometry.py"))
ANCHORS = runpy.run_path(str(ROOT / "scripts/generate-terminal-graphics-anchors.py"))
IMAGES = runpy.run_path(str(ROOT / "scripts/generate-terminal-image-fixtures.py"))
IDS = STORAGE["IDS"]
COLORS = "C " + " ".join(f"{v:x}" for v in COMMON["DEFAULT_COLORS"] + COMMON["DEFAULT_PALETTE"])


def write(text):
    return "W " + text.encode().hex()


def apc(header, data=None):
    return write("\x1b_G" + header + (";" + base64.b64encode(data).decode() if data is not None else "") + "\x1b\\")


def image(ident, fields="", action="T"):
    return apc(f"a={action},i={ident},f=24,s=1,v=1,C=1" + ("," + fields if fields else ""), b"\x01\x02\x03")


def cases():
    result = []
    for case in STORAGE["cases"]():
        commands = ["N 10 6 1000", COLORS, STORAGE["command"]({"limit":case["limit"]}), "J", "R 10 6 8 16"]
        for event in case["events"]:
            commands += [STORAGE["command"](event), "S"]
        result.append(dict(name="storage_"+case["name"], commands=commands))
    for case in GEOMETRY["cases"]():
        if max(case["image"]) > 100:  # Large-image decoding already has its own corpus.
            continue
        result.append(dict(name="geometry_"+case["name"],commands=GEOMETRY["commands"](case)))
    for case in ANCHORS["cases"]():
        if "anchorPolicy" in case:  # Deliberate history-retirement difference has dedicated coverage.
            continue
        result.append(dict(name="anchor_"+case["name"],commands=ANCHORS["commands"](case)))

    def add(name, events, limit=1000000):
        commands=["N 10 6 1000",COLORS,STORAGE["command"]({"limit":limit}),"J","R 10 6 8 16"]
        for event in events:
            commands += [event,"S"]
        result.append(dict(name=name,commands=commands))

    add("display_identifiers_and_errors", [apc("a=p,i=31,p=7"),image(31,action="t"),
        apc("a=p,i=31,I=2,p=7"),apc("a=p,i=31,p=7,U=1,P=99"),apc("a=p,i=31,p=7,C=1"),
        apc("a=p,i=31,p=7,c=3,r=2,C=1"),apc("a=p,i=31,C=1"),apc("a=p,i=31,C=1"),
        apc("a=p,i=31,p=8,U=1,c=3,r=2"),apc("a=p,i=31,p=9,q=1"),apc("a=p,i=99,q=2")])
    add("display_numbers_and_implicit", [image(0,"I=9,p=7,c=2,r=2"),image(0,"I=9,p=7,c=3,r=2"),
        apc("a=p,I=9,p=8,C=1"),image(0,"p=7,c=2,r=3"),apc("a=d,d=N,I=9,p=7"),apc("a=d,d=N,I=9")])
    add("deferred_display", [apc("a=T,i=31,f=24,s=1,v=1,p=7,c=3,r=2,m=1",b"\x01"),
        write("\x1b[5;8H"),apc("a=f,i=99,m=1",b"\x02"),apc("q=0",b"\x03"),
        apc("a=T,i=32,f=24,s=1,v=1,p=3,m=1,q=1,P=99",b"\x01"),apc("q=0",b"\x02\x03")])
    for mode in (47,1047,1049):
        add(f"screens_{mode}", [image(31,"p=7,c=2,r=3"),write(f"\x1b[?{mode}h"),
            image(32,"p=8,c=3,r=2"),write(f"\x1b[?{mode}l"),write(f"\x1b[?{mode}h"),
            apc("a=t,i=99,f=24,s=1,v=1,m=1",b"\x01"),write("\x1bc"),image(0),write("\x1b[?47h")])
    for clear in ("\x1b[2J", "\x1b[22J", "\x1b[?2J"):
        add("clear_"+clear, [image(1,"p=7,c=2,r=3"),write("a\r\nb\r\nc\r\nd\r\ne\r\nf\r\ng\r\nh\r\ni"),
            image(2,"p=8,c=2,r=3"),image(3,action="t"),image(4,"U=1,c=1,r=1"),write(clear),write(clear)])
    for delete in ("a","A","i,i=1","I,i=1","I,i=1,p=7","I,i=1,p=99","N,I=9","n,I=9,p=7", "c","C",
                   "p,x=3,y=2","P,x=3,y=2","q,x=3,y=2,z=-1","Q,x=3,y=2,z=-1","x,x=3","X,x=3",
                   "y,y=2","Y,y=2","z,z=-1","Z,z=-1","r,x=1,y=4","R,x=1,y=4","f,i=1","F,i=1",
                   "P,x=0,y=1","X,x=4294967295","Y,y=4294967295","I,i=1,I=9","A,i=1,I=9"):
        add("delete_"+delete, [write("\x1b[2;3H"),image(1,"p=7,c=3,r=2,z=-1"),image(1,"p=8,c=2,r=1,z=2"),
            image(2,"p=7,U=1,c=2,r=2,z=-1"),image(3,action="t"),image(0,"I=9,p=7,c=2,r=2,z=-1"),
            image(4,"p=7,P=1,Q=8,H=2,V=1,z=-1"),apc("a=t,i=99,f=24,s=1,v=1,m=1",b"\x01"),apc("a=d,d="+delete),
            apc("m=0",b"\x02\x03")])
    add("relative_selection_and_replacement", [image(1,"p=9,c=2,r=2"),write("\x1b[3;4H"),image(2,"p=7"),
        apc("a=p,i=1,p=3,C=1"),image(3,"p=7,P=1,H=2,V=-1"),image(4,"p=7,P=3,Q=7,H=-3,V=2"),
        write("\x1b[5;2H"),apc("a=p,i=1,p=3,C=1"),apc("a=d,d=i,i=1,p=3"),apc("a=d,d=I,i=1")])
    add("relative_errors", [image(1,"p=7"),image(2,"p=7,P=1,Q=7"),image(3,"p=7,P=2,Q=7"),
        apc("a=p,i=1,p=7,P=1,Q=7"),apc("a=p,i=1,p=7,P=3,Q=7"),apc("a=p,i=1,p=9,P=99"),
        apc("a=p,i=1,p=9,P=1,Q=99"),apc("a=p,i=1,p=9,U=1,P=1"),apc("a=p,i=1,p=9,P=1,Q=7")])
    add("relative_depth_and_saturation", [image(1,"p=7")] + [image(i,f"p=7,P={i-1},Q=7,H=2147483647,V=-2147483648") for i in range(2,12)] +
        [image(12,"p=7,U=1,c=2,r=2"),apc("a=p,i=1,p=7,P=12,Q=7"),apc("a=d,d=I,i=12")])
    add("replacement_and_eviction_cascade", [image(1,"p=7"),image(2,"p=7,P=1,Q=7"),image(3,action="t"),image(4,"p=7,N=1"),
        STORAGE["command"]({"limit":9}),image(5),image(1,"p=9"),apc("a=d,d=R,x=1,y=5")],limit=12)
    add("partial_regions_and_insert_delete", [image(1,"p=7,c=2,r=3"),write("\x1b[3;4H"),image(2,"p=7,c=2,r=2"),
        image(3,"p=7,P=2,Q=7,H=1,V=1"),write("\x1b[2;5r\x1b[?69h\x1b[3;8s"),
        write("\x1b[2S"),write("\x1b[2T"),write("\x1b[3;4H\x1b[2L"),write("\x1b[2M")])
    add("pending_wrap_and_cancellation", [write("0123456789"),image(1,"p=7"),apc("a=p,i=1,p=8,C=0"),
        write("\x1b_Ga=t,i=2,f=24,s=1,v=1;AQID\x18"),write("\x1b_Ga=p,i=2,p=7,C=1\x1a"),
        write("\x1b_Ga=t,i=3,f=24,s=1,v=1;%%%\x1b\\")])
    add("disabled_command_and_render_hold", [image(1),STORAGE["command"]({"limit":1000000}),write("\x1b[?2026h"),
        image(1,"p=7"),image(2,"p=7,P=1,Q=7"),write("\x1b[?2026l"),STORAGE["command"]({"limit":0}),image(3)],limit=0)
    for alternate in (False,True):
        for direction in ("S","T"):
            add(f"full_scroll_{alternate}_{direction}", [write("\x1b[?47h" if alternate else "\x1b[H"),
                image(1,"p=7,c=2,r=3"),write("\x1b[6;3H"),image(2,"p=7,c=2,r=2"),
                image(3,"p=7,P=2,Q=7,H=1,V=1"),write("\x1b[2"+direction),write("\x1b[6"+direction),
                image(4,"p=7"),apc("a=d,d=A")])
    add("full_width_insert_delete", [image(1,"p=7,c=2,r=3"),write("\x1b[4;4H"),image(2,"p=7,c=2,r=2"),
        write("\x1b[2;1H\x1b[2L"),write("\x1b[3M"),write("\x1b[6L"),apc("a=d,d=A")])
    add("partial_height_window_shift", [image(1,"p=7,c=2,r=3"),write("\x1b[5;4H"),image(2,"p=7,c=2,r=2"),
        write("\x1b[1;4r\x1b[2S"),write("\x1b[2T"),write("\x1b[6S")])
    add("internal_placement_ids_after_disable", [image(1),apc("a=p,i=1,C=1"),STORAGE["command"]({"limit":0}),
        STORAGE["command"]({"limit":1000000}),image(1),apc("a=p,i=1,C=1"),write("\x1bc"),image(1)])
    add("zero_geometry_deletion", ["R 10 6 0 0",image(1,"p=7"),image(2,"p=7,c=2,r=2"),apc("a=d,d=c"),
        apc("a=d,d=A"),image(1,"p=7"),apc("a=d,d=X,x=1"),apc("a=d,d=A")])
    def frame(fields='',data=b'\x14\x28\x3c\xff'):
        return apc('a=f,i=1,f=32,s=1,v=1'+(','+fields if fields else ''),data)
    for quiet in (0,1,2):
        add(f'animation_replies_{quiet}',[image(1),frame(f'q={quiet},p=7'),frame(f'q={quiet},r=999,c=99'),
            frame(f'q={quiet},r=2,z=-1'),apc(f'a=a,i=1,c=2,q={quiet}'),
            apc(f'a=c,i=1,r=2,c=1,q={quiet}'),apc(f'a=c,i=1,r=1,c=1,q={quiet}'),
            apc(f'a=a,I=99,p=7,q={quiet}'),apc(f'a=f,I=99,r=7,q={quiet}'),
            apc(f'a=f,i=1,I=9,r=7,q={quiet}'),apc(f'a=f,r=7,q={quiet}')])
    for continuation in ('t','T','f'):
        for quiet in (0,1,2):
            add(f'animation_chunk_{continuation}_{quiet}',[image(1),frame(f'm=1,q={quiet},p=7,r=99',b'\x14'),
                apc('a=q,i=1,f=24,s=1,v=1',b'\x01\x02\x03'),
                apc(f'a={continuation},i=99,f=42,s=100,m=1',b'\x28'),
                apc(f'a={continuation},i=99,q=0',b'\x3c\xff'),apc('a=a,i=1,c=2')])
    for intervention in [apc('a=a,i=1,c=2'),apc('a=a,i=1,r=2,z=5'),apc('a=a,i=1,s=3'),
                         apc('a=c,i=1,r=2,c=1'),apc('a=c,i=1,r=1,c=2'),apc('a=d,d=f,i=1,r=2'),
                         STORAGE['command']({'limit':3}),write('\x1bc')]:
        add('animation_chunk_intervention_'+intervention,[image(1),frame(),frame('m=1',b'\x14'),intervention,
            apc('a=T,i=99',b'\x28\x3c\xff'),apc('a=a,i=1,c=3')])
    for limit in (3,4,6,7,8,9,11,12,15,16,20):
        add(f'animation_quota_{limit}',[image(1,'p=7'),image(2,action='t'),image(3,'p=7,P=1,Q=7'),
            frame(),frame(),frame('r=1'),apc('a=a,i=1,c=2'),apc('a=d,d=f,i=1,r=1'),frame(),
            apc('a=d,d=F,i=1,r=99'),apc('a=d,d=F,i=1,r=1'),image(4)],limit=limit)
    add('animation_number_generation',[image(0,'I=9'),image(0,'I=9'),
        apc('a=f,I=9,f=32,s=1,v=1,r=99,p=7',b'\x14\x28\x3c\xff'),
        apc('a=f,i=1,f=32,s=1,v=1',b'\x50\x60\x70\xff'),
        apc('a=a,I=9,c=2'),apc('a=f,I=9,f=32,s=1,v=1,m=1',b'\x01'),
        apc('a=a,i=2,c=2'),apc('a=t',b'\x02\x03\xff'),apc('a=d,d=f,I=9,r=1'),apc('a=d,d=F,I=9,r=1')])
    for fields,data in [('r=7',b'\x01'),('r=7',b'\x01\x02\x03\xffextra'),
                        ('r=7,f=42',b'\x01'),('r=7,t=f',b'nonexistent'),('r=7,s=2',bytes(8)),
                        ('r=999,c=99',bytes(4)),('r=7,o=z',b'invalid')]:
        add('animation_invalid_'+fields,[image(1),frame(fields,data),frame(),apc('a=a,i=1,c=2')])
    for mode in (47,1047,1049):
        add(f'animation_screen_{mode}',[image(1),frame(),apc('a=a,i=1,c=2'),write(f'\x1b[?{mode}h'),
            image(1),frame(data=b'\x50\x60\x70\xff'),apc('a=a,i=1,c=2'),write(f'\x1b[?{mode}l'),
            write(f'\x1b[?{mode}h'),write('\x1bc')])
    for fmt,data in [(24,b'\x10\x20\x30'),(32,b'\x10\x20\x30\x80'),(100,IMAGES['png'](1,1,8,6,0))]:
        for compressed in (False,True):
            payload=zlib.compress(data) if compressed else data
            add(f'animation_encoding_{fmt}_{compressed}',[image(1),
                apc(f'a=f,i=1,f={fmt},s=1,v=1,m=1'+(',o=z' if compressed else ''),payload[:1]),
                apc('a=T,m=1,q=1',payload[1:3]),apc('a=t,q=0',payload[3:]),
                apc('a=a,i=1,c=2'),apc('a=c,i=1,r=2,c=1'),apc('a=d,d=f,i=1,r=2')])
    return result


def main():
    if zlib.ZLIB_RUNTIME_VERSION != "1.2.12":
        raise SystemExit("Expected pinned zlib 1.2.12")
    upstream=Path(sys.argv[1]).resolve()
    if subprocess.check_output(["git","rev-parse","HEAD"],cwd=upstream,text=True).strip()!=COMMON["SHA"]:
        raise SystemExit("Wrong Ghostty revision")
    subprocess.run(["git","diff","--exit-code","HEAD","--","."],cwd=upstream,check=True,stdout=subprocess.DEVNULL)
    build=ROOT/"build/terminal-oracle"
    build.mkdir(exist_ok=True,parents=True)
    helper=build/"ghostty-execution-oracle"
    library=upstream/"zig-out/lib"
    png_version=subprocess.check_output(['pkg-config','--modversion','libpng'],text=True).strip()
    if png_version != '1.6.59': raise SystemExit('Expected pinned libpng 1.6.59')
    png_flags=shlex.split(subprocess.check_output(['pkg-config','--cflags','--libs','libpng'],text=True))
    subprocess.run(["cc","-std=c11","-Wall","-Wextra","-Werror","-I",str(upstream/"include"),str(ROOT/"scripts/terminal-ghostty-graphics-oracle.c"),
                    "-L",str(library),"-lghostty-vt","-Wl,-rpath,"+str(library),"-o",str(helper),*png_flags],check=True)
    corpus=cases()
    for case in corpus:
        result=subprocess.run([str(helper)],input="\n".join(case["commands"])+"\n",text=True,capture_output=True)
        if result.returncode:
            raise RuntimeError(case["name"]+": "+result.stderr)
        snapshots=[json.loads(line) for line in result.stdout.splitlines()]
        assert len(snapshots)==case["commands"].count("S")
        case["snapshots"]=[]
        for s in snapshots:
            snapshot={key:s[key] for key in ("kittyImages","kittyPlacements","kittyLayers","cursor","pendingWrap","history","alternate","replies")}
            snapshot["textRows"]=[[row["wrapped"],[[cell[0],cell[1]] for cell in row["cells"]]] for row in s["lines"]]
            case["snapshots"].append(snapshot)
    output='// Generated by scripts/generate-terminal-graphics-execution.py; do not edit.\n'
    output+='// Original libghostty-vt '+COMMON["SHA"]+' (MIT).\npackage io.heapy.kinetica.terminal\n\n'
    output+='internal fun terminalGraphicsExecutionFixtures(): List<String> = listOf('+','.join(f'graphicsExecution{i}()' for i in range(len(corpus)))+')\n'
    for i,case in enumerate(corpus):
        output+=f'private fun graphicsExecution{i}(): String = '+COMMON["encoded"](case)+'\n'
    COMMON["write_generated"](ROOT/"kinetica-terminal/test/generated/TerminalGraphicsExecutionFixtures.kt",output)
    dependencies=["generate-terminal-oracle.py","generate-terminal-graphics-fixtures.py","generate-terminal-graphics-geometry.py","generate-terminal-graphics-anchors.py","generate-terminal-image-fixtures.py"]
    manifest={"commit":COMMON["SHA"],"zlib":zlib.ZLIB_RUNTIME_VERSION,"libpng":png_version,"cases":len(corpus),"snapshots":sum(len(c["snapshots"]) for c in corpus),
              "generatorSha256":hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "inputGenerators":{p:hashlib.sha256((ROOT/"scripts"/p).read_bytes()).hexdigest() for p in dependencies},
              "helperSha256":hashlib.sha256((ROOT/"scripts/terminal-ghostty-oracle.c").read_bytes()).hexdigest(),
              "graphicsHelperSha256":hashlib.sha256((ROOT/"scripts/terminal-ghostty-graphics-oracle.c").read_bytes()).hexdigest(),
              "librarySha256":hashlib.sha256((library/"libghostty-vt.0.dylib").read_bytes()).hexdigest(),
              "fixturesSha256":hashlib.sha256(output.encode()).hexdigest()}
    COMMON["write_generated"](ROOT/"kinetica-terminal/test-upstream/ghostty/graphics-execution-manifest.json",json.dumps(manifest,indent=2)+"\n")
    print(f'Generated {len(corpus)} execution cases and {manifest["snapshots"]} snapshots')


if __name__=="__main__":
    main()
