#!/usr/bin/env python3
"""Generate base-image transfer/storage snapshots using the pinned original libghostty-vt.

No shell, renderer or Kinetica result is involved. PNG pixel decoding has its own
independent oracle; these cases use RGB/RGBA and zlib, requiring no host decoder.
"""
import base64
import hashlib
import json
from pathlib import Path
import runpy
import subprocess
import sys
import zlib

ROOT = Path(__file__).resolve().parent.parent
COMMON = runpy.run_path(str(ROOT / "scripts/generate-terminal-oracle.py"))
IDS = list(range(1, 17)) + [31, 32, 99, 2147483647, 2147483648, 2147483649, 2147483650, 4294967295]


def wire(header, data=b""):
    return {"wire": header + ";" + base64.b64encode(data).decode()}


def cases():
    rgb = b"\xff\x00\x80"
    raw = "f=24,s=1,v=1"
    def tx(fields, data=rgb):
        return wire("a=t," + raw + "," + fields, data)
    def query(fields, data=rgb):
        return wire("a=q," + raw + "," + fields, data)
    result = [
        ("queries_are_side_effect_free", 64, [
            query("i=31"), tx("i=31"), query("i=31,p=7", b"\0\0\0"), query("i=31", b"\0"),
            query("I=4"), query("i=31,q=1"), query("i=31,q=1", b"\0"), query("i=31,q=2", b"\0"),
            query("i=31,I=9,p=7"), query("i=31,I=9,q=2"), query("i=0")]),
        ("ids_numbers_and_implicit_ids", 64, [
            tx("i=1"), tx("I=9"), tx("I=9"), tx("i=0"), tx("i=2147483648"), tx("i=0"),
            tx("i=4294967295"), {"removeNumber": 9}, tx("I=9"), {"remove": 1}, tx("I=8")]),
        ("implicit_failure_consumes_id", 64, [tx("i=0", b"\0"), tx("i=0"), tx("I=9", b"\0"), tx("I=9")]),
        ("conflicting_ids_do_not_mutate", 64, [tx("i=31"), tx("i=31,I=2"), tx("i=31,I=2,q=1"),
            tx("i=31,I=2,q=2"), tx("i=31,I=2,q=99"), tx("i=31,q=1")]),
        ("invalid_transmissions", 64, [
            wire("a=t,i=31,f=42", rgb), wire("a=t,i=31,f=24", rgb), wire("a=t,i=31,f=24,s=10001,v=1"),
            wire("a=t,i=31,f=24,s=4294967295,v=1"), tx("i=31", b"\0\0"), tx("i=31", b"\0\0\0\0"),
            wire("a=t,i=31,f=0,s=1,v=1", b"\1\2\3\4"),
            tx("i=31,t=f", b"/tmp/does-not-exist"), tx("i=31,t=t", b"/tmp/does-not-exist"), tx("i=31,t=s", b"name")]),
        ("compression", 64, [tx("i=31,o=z", zlib.compress(rgb)), query("i=31,o=z", zlib.compress(rgb)),
            tx("i=31,o=z", b"broken"), tx("i=31,o=z", zlib.compress(rgb)[:-1]), tx("i=31,o=z", zlib.compress(rgb) + b"trailing")]),
        ("chunk_metadata_and_queries", 64, [
            wire("a=t,i=31,p=7,f=24,s=2,v=1,m=1", rgb[:2]), query("i=31"),
            wire("a=T,i=99,f=42,s=99,v=99,m=1", rgb[2:]), query("i=31", b"\0"),
            wire("a=f,i=99,f=42,s=99,v=99", rgb), tx("i=32")]),
        ("chunk_quiet_inheritance", 64, [
            wire("a=t,i=31,f=24,s=1,v=1,m=1,q=1", rgb[:1]), wire("m=1,q=0", rgb[1:2]), wire("q=0", rgb[2:]),
            wire("a=t,i=31,f=24,s=1,v=1,m=1,q=1", b""), wire("q=0", b""),
            wire("a=t,i=31,f=24,s=1,v=1,m=1", b""), wire("q=2", b""),
            wire("a=t,i=31,f=24,s=1,v=1,m=1,q=2", b""), wire("m=1,q=1", b""), wire("q=0", b"")]),
        ("chunk_conflict_keeps_pending_state", 64, [
            wire("a=t,i=31,f=24,s=1,v=1,m=1,q=1", rgb[:1]), wire("i=1,I=2,q=2", rgb[1:]), wire("q=0", b""),
            wire("a=t,I=9,f=24,s=1,v=1,m=1", rgb[:1]), wire("i=1,I=2", rgb[1:]), wire("m=0", rgb[1:])]),
        ("replacement_deletes_at_first_chunk", 64, [tx("i=31"), wire("a=t,i=31,f=24,s=1,v=1,m=1", rgb[:1]),
            wire("m=0", rgb[1:]), tx("i=31,f=42"), tx("i=31"), wire("a=t,i=31,f=24,s=1,v=1,m=1", rgb[:1]),
            {"remove": 99}, tx("i=32")]),
        ("eviction_oldest_and_transient", 9, [tx("i=1"), tx("i=2,N=1"), tx("i=3"), tx("i=4"),
            tx("i=5"), tx("i=3,N=1"), tx("i=6"), {"limit": 3}, {"limit": 64}, tx("i=7")]),
        ("oversized_image_and_replacement", 3, [tx("i=1"), wire("a=t,i=2,f=32,s=1,v=1", b"1234"),
            wire("a=t,i=1,f=32,s=1,v=1", b"1234"), tx("i=1"), {"limit": 2}, tx("i=1")]),
        ("delete_data_and_abort_chunks", 64, [tx("i=1"), tx("i=2"), tx("i=3"), {"remove": 2},
            {"removeRange": [3, 1]}, {"removeRange": [0, 0]}, {"removeRange": [1, 3]},
            wire("a=t,i=31,f=24,s=1,v=1,m=1", rgb[:1]), {"remove": 99}, wire("m=0", rgb[1:])]),
        ("disabled_and_reenabled", 0, [tx("i=1"), query("i=31"), {"limit": 64}, tx("i=0"),
            wire("a=t,i=31,f=24,s=1,v=1,m=1", rgb[:1]), {"limit": 0}, wire("m=0", rgb[1:]),
            {"limit": 64}, tx("i=0"), query("i=31")]),
        ("reset", 64, [tx("i=1"), tx("i=0"), wire("a=t,i=31,f=24,s=1,v=1,m=1", rgb[:1]),
            {"reset": True}, query("i=31"), tx("i=0"), tx("I=9")]),
    ]
    return [{"name": name, "limit": limit, "ids": IDS, "events": events} for name, limit, events in result]


def command(event):
    if "limit" in event:
        return "G " + str(event["limit"]) + " " + " ".join(map(str, IDS))
    if "reset" in event:
        return "W 1b63"
    if "wire" in event:
        body = event["wire"]
    elif "remove" in event:
        body = f'a=d,d=I,i={event["remove"]}'
    elif "removeNumber" in event:
        body = f'a=d,d=N,I={event["removeNumber"]}'
    else:
        first, last = event["removeRange"]
        body = f'a=d,d=R,x={first},y={last}'
    return "W " + ("\x1b_G" + body + "\x1b\\").encode().hex()


def main():
    if zlib.ZLIB_RUNTIME_VERSION != "1.2.12":
        raise SystemExit(f"Expected zlib 1.2.12 for compressed fixture inputs, got {zlib.ZLIB_RUNTIME_VERSION}")
    upstream = Path(sys.argv[1]).resolve()
    if subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip() != COMMON["SHA"]:
        raise SystemExit("Wrong Ghostty revision")
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "."], cwd=upstream, check=True, stdout=subprocess.DEVNULL)
    build = ROOT / "build/terminal-oracle"
    build.mkdir(exist_ok=True, parents=True)
    helper = build / "ghostty-graphics-oracle"
    library = upstream / "zig-out/lib"
    subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-I", str(upstream / "include"),
        str(ROOT / "scripts/terminal-ghostty-oracle.c"), "-L", str(library), "-lghostty-vt", "-Wl,-rpath," + str(library), "-o", str(helper)], check=True)
    corpus = cases()
    colors = "C " + " ".join(f"{v:x}" for v in COMMON["DEFAULT_COLORS"] + COMMON["DEFAULT_PALETTE"])
    for case in corpus:
        commands = ["N 2 2 0", colors, command({"limit": case["limit"]})]
        for event in case["events"]:
            commands.extend([command(event), "S"])
        result = subprocess.run([str(helper)], input="\n".join(commands) + "\n", text=True, capture_output=True, check=True)
        snapshots = [json.loads(line) for line in result.stdout.splitlines()]
        assert len(snapshots) == len(case["events"])
        case["snapshots"] = [{"replies": s["replies"], "images": s["kittyImages"]} for s in snapshots]
    output = '// Generated by scripts/generate-terminal-graphics-fixtures.py; do not edit.\n'
    output += '// Original libghostty-vt ' + COMMON["SHA"] + ' (MIT).\n'
    output += 'package io.heapy.kinetica.terminal\n\ninternal fun terminalGraphicsStorageFixtures(): String = ' + COMMON["encoded"](corpus) + '\n'
    target = ROOT / "kinetica-terminal/test/generated/TerminalGraphicsStorageFixtures.kt"
    COMMON["write_generated"](target, output)
    manifest = {"commit": COMMON["SHA"], "zlib": zlib.ZLIB_RUNTIME_VERSION,
                "cases": len(corpus), "snapshots": sum(len(c["events"]) for c in corpus),
                "probeImageIds": IDS, "generatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "helperSha256": hashlib.sha256((ROOT / "scripts/terminal-ghostty-oracle.c").read_bytes()).hexdigest(),
                "librarySha256": hashlib.sha256((library / "libghostty-vt.0.dylib").read_bytes()).hexdigest(),
                "fixturesSha256": hashlib.sha256(output.encode()).hexdigest()}
    COMMON["write_generated"](ROOT / "kinetica-terminal/test-upstream/ghostty/graphics-manifest.json", json.dumps(manifest, indent=2) + "\n")
    print(f'Generated {len(corpus)} graphics storage cases with {manifest["snapshots"]} snapshots')


if __name__ == "__main__":
    main()
