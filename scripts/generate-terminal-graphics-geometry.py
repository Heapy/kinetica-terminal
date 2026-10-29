#!/usr/bin/env python3
"""Generate placement geometry from the pinned original libghostty public API."""
import base64
import hashlib
import json
from pathlib import Path
import runpy
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
COMMON = runpy.run_path(str(ROOT / "scripts/generate-terminal-oracle.py"))


def cases():
    result = []
    cells = [(0,0), (8,16), (7,13), (1,1), (0,17), (11,0), (214748364,300000000)]
    for width, height in [(1,1), (13,7), (7,13), (10000,1), (1,10000)]:
        fields = ["", "x=3,y=2", "w=1,h=1", "x=4294967295,y=4294967295", "w=4294967295,h=4294967295",
                  "c=1", "r=1", "c=3", "r=4", "c=3,r=4", "X=4294967295,Y=4294967295",
                  "X=4294967295,Y=4294967295,c=3", "X=4294967295,Y=4294967295,r=4",
                  "X=4294967295,Y=4294967295,c=3,r=4", "c=4294967295", "r=4294967295", "c=4294967295,r=4294967295",
                  f"x={width},y={height}", f"x={width-1},y={height-1},c=3", f"y={height},h=1,r=2",
                  f"x={width},w=1,c=2", "X=1,Y=1,c=1,r=1", "U=1,c=3,r=2"]
        fields += [f"z={z}" for z in (-2147483648,-1073741825,-1073741824,-1,0,2147483647)]
        for cell in cells:
            result.append(dict(name=f"size_{width}x{height}_cell_{cell[0]}x{cell[1]}", image=[width,height], cell=cell,
                               events=[{"place": "a=p,i=31,p=7,C=1" + ("," + f if f else "")} for f in fields]))
    for cell in [(0,0), (8,16), (7,0), (0,13)]:
        result.append(dict(name=f"offset_lifetime_{cell[0]}x{cell[1]}", image=[13,7], cell=cell, events=[
            {"place":"a=p,i=31,p=7,C=1,X=4294967295,Y=4294967295,c=3,r=4"},
            {"resize":[16,32]}, {"resize":[1,1]}, {"resize":[0,0]}, {"resize":[8,16]},
            {"place":"a=p,i=31,p=7,C=1,X=4294967295,Y=4294967295,c=3,r=4"}, {"resize":[16,32]}]))
    for cell in [(0,0),(8,16),(1,1),(7,13)]:
        events=[]
        for x,y in [(0,0),(9,0),(0,5),(9,5)]:
            for fields in ("", "c=3", "r=4", "c=10,r=6", "c=4294967295,r=4294967295", "x=13,y=7"):
                events.append({"place":"a=p,i=31,p=7,"+fields, "cursor":[x,y]})
        result.append(dict(name=f"cursor_{cell[0]}x{cell[1]}", image=[13,7], cell=cell, history=1000, events=events))
    for height in (35,1):
        for fields in ("", "c=2", "r=3", "c=3,r=3", "c=3,r=3,x=13", "c=3,r=3,y="+str(height)):
            for direction in (-1,1):
                for offset in (0,12):
                    row=1 if direction < 0 else 2
                    name=f"margin_{height}_{fields}_{direction}_{offset}"
                    result.append(dict(name=name, image=[13,height], cell=[8,16], margins=[1,4,0,9], events=[
                        {"place":"a=p,i=31,p=7,C=1,Y="+str(offset)+(","+fields if fields else ""), "cursor":[2,row]},
                        {"scroll":direction}, {"scroll":direction}, {"scroll":direction}, {"scroll":direction}]))
    for x,y,fields in [(0,1,"c=2,r=3"),(8,1,"c=2,r=3"),(2,0,"c=2,r=3"),(2,3,"c=2,r=3"),
                       (2,1,"c=4294967295,r=3"),(2,1,"c=2,r=3"),(7,1,"c=4294967295,r=3")]:
        result.append(dict(name=f"horizontal_margin_{x}_{y}_{fields}", image=[13,35], cell=[8,16], margins=[1,4,2,7], events=[
            {"place":"a=p,i=31,p=7,C=1,"+fields, "cursor":[x,y]}, {"scroll":-1}, {"scroll":1}]))
    return result


def write_bytes(value):
    return "W "+value.encode().hex()


def commands(case):
    colors = "C " + " ".join(f"{v:x}" for v in COMMON["DEFAULT_COLORS"] + COMMON["DEFAULT_PALETTE"])
    result = [f'N 10 6 {case.get("history",0)}', colors, "G 1000000 31", "J", f'R 10 6 {case["cell"][0]} {case["cell"][1]}']
    width,height=case["image"]
    payload=base64.b64encode(bytes(width*height*3)).decode()
    result.append(write_bytes(f"\x1b_Ga=t,i=31,f=24,s={width},v={height};{payload}\x1b\\"))
    if "margins" in case:
        top,bottom,left,right=case["margins"]
        result.append(write_bytes(f"\x1b[{top+1};{bottom+1}r"))
        if left or right!=9:
            result.append(write_bytes(f"\x1b[?69h\x1b[{left+1};{right+1}s"))
    for event in case["events"]:
        if "cursor" in event:
            x,y=event["cursor"]
            result.append(write_bytes(f"\x1b[{y+1};{x+1}H"))
        if "place" in event:
            # Avoid a trailing comma, which is not a valid graphics header.
            event["place"]=event["place"].rstrip(',')
            result.append(write_bytes("\x1b_G"+event["place"]+"\x1b\\"))
        elif "resize" in event:
            cw,ch=event["resize"]
            result.append(f"R 10 6 {cw} {ch}")
        else:
            delta=event["scroll"]
            result.append(write_bytes(f'\x1b[{abs(delta)}'+('S' if delta<0 else 'T')))
        result.append("S")
    return result


def main():
    upstream=Path(sys.argv[1]).resolve()
    if subprocess.check_output(["git","rev-parse","HEAD"],cwd=upstream,text=True).strip()!=COMMON["SHA"]:
        raise SystemExit("Wrong Ghostty revision")
    subprocess.run(["git","diff","--exit-code","HEAD","--","."],cwd=upstream,check=True,stdout=subprocess.DEVNULL)
    build=ROOT/"build/terminal-oracle"
    build.mkdir(exist_ok=True,parents=True)
    helper=build/"ghostty-geometry-oracle"
    library=upstream/"zig-out/lib"
    subprocess.run(["cc","-std=c11","-Wall","-Wextra","-Werror","-I",str(upstream/"include"),str(ROOT/"scripts/terminal-ghostty-oracle.c"),
                    "-L",str(library),"-lghostty-vt","-Wl,-rpath,"+str(library),"-o",str(helper)],check=True)
    corpus=cases()
    for case in corpus:
        result=subprocess.run([str(helper)],input="\n".join(commands(case))+"\n",text=True,capture_output=True)
        if result.returncode:
            raise RuntimeError(case["name"]+": "+result.stderr)
        snapshots=[json.loads(line) for line in result.stdout.splitlines()]
        assert len(snapshots)==len(case["events"])
        case["snapshots"]=[{key:s[key] for key in ("kittyPlacements","kittyLayers","cursor","pendingWrap","history")} for s in snapshots]
        for snapshot in case["snapshots"]:
            assert len(snapshot["kittyPlacements"])<=1
    output='// Generated by scripts/generate-terminal-graphics-geometry.py; do not edit.\n'
    output+='// Original libghostty-vt '+COMMON["SHA"]+' (MIT).\npackage io.heapy.kinetica.terminal\n\n'
    output+='internal fun terminalGraphicsGeometryFixtures(): String = '+COMMON["encoded"](corpus)+'\n'
    COMMON["write_generated"](ROOT/"kinetica-terminal/test/generated/TerminalGraphicsGeometryFixtures.kt",output)
    manifest={"commit":COMMON["SHA"],"cases":len(corpus),"snapshots":sum(len(c["events"]) for c in corpus),
              "generatorSha256":hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "helperSha256":hashlib.sha256((ROOT/"scripts/terminal-ghostty-oracle.c").read_bytes()).hexdigest(),
              "librarySha256":hashlib.sha256((library/"libghostty-vt.0.dylib").read_bytes()).hexdigest(),
              "fixturesSha256":hashlib.sha256(output.encode()).hexdigest()}
    COMMON["write_generated"](ROOT/"kinetica-terminal/test-upstream/ghostty/graphics-geometry-manifest.json",json.dumps(manifest,indent=2)+"\n")
    print(f'Generated {len(corpus)} geometry cases and {manifest["snapshots"]} snapshots')


if __name__=="__main__":
    main()
