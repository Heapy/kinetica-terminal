#!/usr/bin/env python3
"""Build pinned, full HarfBuzz for the browser without HB_TINY's shaping omissions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
VERSION = "11.0.0"
URL = f"https://deps.files.ghostty.org/harfbuzz-{VERSION}.tar.xz"
SHA256 = "f16351bafe214725fe2c1d5b59f0d93e49905a4b247899fb90d70cff953a2b9b"
OUTPUT = ROOT / "kinetica-terminal/web/harfbuzz"
BRIDGE = ROOT / "scripts/terminal-harfbuzz-bridge.cc"
EXPORTS = """malloc free hb_version_string hb_blob_create hb_blob_destroy hb_blob_get_length hb_blob_get_data
hb_face_create hb_face_destroy hb_face_get_glyph_count hb_face_get_upem
hb_font_create hb_font_destroy hb_font_get_face hb_font_set_scale hb_font_set_ppem hb_font_get_nominal_glyph
hb_font_get_variation_glyph hb_font_get_glyph_extents hb_font_get_h_extents
hb_buffer_create hb_buffer_destroy hb_buffer_reset hb_buffer_add_utf16 hb_buffer_set_direction
hb_buffer_set_cluster_level hb_buffer_guess_segment_properties hb_buffer_get_length
hb_buffer_get_glyph_infos hb_buffer_get_glyph_positions hb_buffer_allocation_successful hb_shape
hb_ot_color_glyph_reference_png hb_ot_color_has_layers hb_ot_color_glyph_get_layers hb_ot_color_palette_get_colors
kt_hb_outline kt_hb_outline_data kt_hb_outline_length kt_hb_outline_destroy""".split()
FLAGS = ["-target", "wasm32-wasi", "-mexec-model=reactor", "-O2", "-fno-exceptions", "-fno-rtti",
         "-fno-threadsafe-statics", "-DHB_NO_MT", "-DHB_NO_MMAP", "-DHB_NO_GETENV", "-DHB_NO_SETLOCALE",
         "-Wl,--strip-all", "-Wl,--max-memory=134217728"]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zig", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    zig = args.zig.resolve()
    version = subprocess.check_output([zig, "version"], text=True).strip()
    if version != "0.17.0":
        raise SystemExit(f"Expected pinned Zig 0.17.0, got {version}")
    work = ROOT / "build/browser-harfbuzz"
    work.mkdir(parents=True, exist_ok=True)
    archive = ROOT / f"build/upstream/harfbuzz-{VERSION}.tar.xz"
    if not archive.exists():
        archive.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL) as response, archive.open("wb") as target:
            shutil.copyfileobj(response, target)
    if digest(archive) != SHA256:
        raise SystemExit("HarfBuzz source archive checksum mismatch")
    source = work / f"harfbuzz-{VERSION}"
    if source.exists():
        shutil.rmtree(source)
    with tarfile.open(archive) as package:
        package.extractall(work, filter="data")
    shutil.copyfile(BRIDGE, source / "terminal-harfbuzz-bridge.cc")
    command = [str(zig), "c++", *FLAGS, *[f"-Wl,--export={name}" for name in EXPORTS], "-Isrc",
               "src/harfbuzz.cc", "terminal-harfbuzz-bridge.cc", "-o", "../harfbuzz.wasm"]
    subprocess.run(command, cwd=source, check=True, env=os.environ | {
        "ZIG_GLOBAL_CACHE_DIR": str(ROOT / "build/upstream/zig-cache-0.17")})
    artifacts = {"harfbuzz.wasm": work / "harfbuzz.wasm", "licenses/HarfBuzz.txt": source / "COPYING"}
    licenses = {
        "Zig.txt": "LICENSE",
        "LLVM-libcxx.txt": "lib/libcxx/LICENSE.TXT",
        "LLVM-libcxxabi.txt": "lib/libcxxabi/LICENSE.TXT",
        "LLVM-libunwind.txt": "lib/libunwind/LICENSE.TXT",
        "WASI.txt": "lib/libc/wasi/LICENSE",
        "WASI-Apache.txt": "lib/libc/wasi/LICENSE-APACHE",
        "WASI-Apache-LLVM.txt": "lib/libc/wasi/LICENSE-APACHE-LLVM",
        "WASI-MIT.txt": "lib/libc/wasi/LICENSE-MIT",
        "Cloudlibc.txt": "lib/libc/wasi/libc-bottom-half/cloudlibc/LICENSE",
        "Musl.txt": "lib/libc/wasi/libc-top-half/musl/COPYRIGHT",
    }
    artifacts.update({f"licenses/{name}": zig.parent / path for name, path in licenses.items()})
    manifest = {
        "harfbuzz": VERSION, "source_url": URL, "source_sha256": SHA256, "zig": version,
        "ghostty_reference_commit": "83fb95624b0439ee660c2679b43b38c8b63769cd",
        "generator_sha256": digest(Path(__file__)), "bridge_sha256": digest(BRIDGE),
        "flags": FLAGS, "exports": EXPORTS, "maximum_wasm_memory_bytes": 134217728,
        "artifacts": {name: digest(path) for name, path in artifacts.items()},
    }
    manifest_path = work / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    artifacts["manifest.json"] = manifest_path
    for name, path in artifacts.items():
        destination = OUTPUT / name
        if args.check:
            if not destination.exists() or destination.read_bytes() != path.read_bytes():
                raise SystemExit(f"Generated artifact differs: {destination.relative_to(ROOT)}")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, destination)
    print(f"{'Verified' if args.check else 'Generated'} HarfBuzz {VERSION}: {artifacts['harfbuzz.wasm'].stat().st_size} WASM bytes")


if __name__ == "__main__":
    main()
