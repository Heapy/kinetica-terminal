#!/usr/bin/env python3
"""Generate common image-decoder goldens using independent zlib and libpng.

Requires a C compiler and pkg-config libpng (or --png-prefix /opt/homebrew).
Ordinary Kotlin tests use checked-in fixtures and need neither dependency.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import random
import struct
import subprocess
import tempfile
import zlib

ROOT = Path(__file__).resolve().parent.parent
SIGNATURE = b"\x89PNG\r\n\x1a\n"
LIBPNG_VERSION = "1.6.59"
ZLIB_VERSION = "1.2.12"


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def png(width, height, depth, color, interlace, filter_offset=0, transparent=True):
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    maximum = (1 << depth) - 1
    palette_size = min(16, 1 << depth)
    pixels = [[tuple(((x * 37 + y * 71 + c * 113) * (193 if depth == 16 else 1)) & maximum
                     for c in range(channels)) for x in range(width)] for y in range(height)]
    if color == 3:
        pixels = [[(p[0] % palette_size,) for p in row] for row in pixels]
    result = SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, depth, color, 0, 0, interlace))
    if color == 3:
        result += chunk(b"PLTE", bytes(v for i in range(palette_size) for v in (i * 17, 255 - i * 17, (i * 43) % 256)))
    if transparent and color in (0, 2, 3):
        transparency = (bytes([0, 127]) if color == 3 else b"".join(struct.pack(">H", v) for v in pixels[0][0]))
        result += chunk(b"tRNS", transparency)
    # Unknown ancillary data must not change pixel values.
    result += chunk(b"vpAg", b"fixture")
    passes = [(0,0,8,8),(4,0,8,8),(0,4,4,8),(2,0,4,4),(0,2,2,4),(1,0,2,2),(0,1,1,2)] if interlace else [(0,0,1,1)]
    raw = bytearray()
    for sx, sy, dx, dy in passes:
        if sx >= width or sy >= height:
            continue
        previous = None
        for row_number, y in enumerate(range(sy, height, dy)):
            samples = [v for x in range(sx, width, dx) for v in pixels[y][x]]
            if depth == 16:
                scanline = b"".join(struct.pack(">H", v) for v in samples)
            elif depth == 8:
                scanline = bytes(samples)
            else:
                scanline = bytearray((len(samples) * depth + 7) // 8)
                for i, v in enumerate(samples):
                    scanline[i * depth // 8] |= v << (8 - depth - i * depth % 8)
            previous = previous if previous is not None else bytes(len(scanline))
            stride = max(1, (channels * depth + 7) // 8)
            filter_kind = (row_number + filter_offset) % 5
            raw.append(filter_kind)
            for i, value in enumerate(scanline):
                a = scanline[i - stride] if i >= stride else 0
                b = previous[i]
                c = previous[i - stride] if i >= stride else 0
                paeth = min((a, b, c), key=lambda p: abs(a + b - c - p))
                prediction = (0, a, b, (a + b) // 2, paeth)[filter_kind]
                raw.append((value - prediction) % 256)
            previous = scanline
    packed = zlib.compress(raw, 9)
    # Split even inside the zlib header/checksum, including empty IDAT chunks.
    for i in range(0, len(packed), 7):
        result += chunk(b"IDAT", b"") + chunk(b"IDAT", packed[i:i+7])
    return result + chunk(b"IEND", b"")


def encoded(data):
    text = base64.b64encode(data).decode()
    parts = [json.dumps(text[i:i+8000]) for i in range(0, len(text), 8000)]
    if len(parts) > 1:
        return 'listOf(' + ',\n        '.join(parts) + ').joinToString("")'
    return parts[0] if parts else '""'


def write(path, text):
    if not path.exists() or path.read_text() != text:
        # Do not truncate fixtures that a concurrent compiler could be reading.
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="", dir=path.parent, delete=False) as output:
            temporary = Path(output.name)
            try:
                output.write(text)
                output.close()
                temporary.replace(path)
            finally:
                temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--png-prefix")
    args = parser.parse_args()
    build = ROOT / "build/image-oracle"
    build.mkdir(parents=True, exist_ok=True)
    helper = build / "png-oracle"
    flags = (["-I" + args.png_prefix + "/include", "-L" + args.png_prefix + "/lib", "-lpng"] if args.png_prefix else
             subprocess.check_output(["pkg-config", "--cflags", "--libs", "libpng"], text=True).split())
    subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", str(ROOT / "scripts/terminal-png-oracle.c"), *flags, "-o", str(helper)], check=True)
    libpng_version = subprocess.check_output([str(helper), "--version"], text=True).strip()
    if libpng_version != LIBPNG_VERSION or zlib.ZLIB_RUNTIME_VERSION != ZLIB_VERSION:
        raise SystemExit(f"Expected libpng {LIBPNG_VERSION} and zlib {ZLIB_VERSION}; got {libpng_version} and {zlib.ZLIB_RUNTIME_VERSION}")
    cases = []
    png_hashes = {}
    with tempfile.TemporaryDirectory(prefix="terminal-image-fixtures-") as directory:
        path = Path(directory) / "image.png"
        for color, depths in ((0, (1,2,4,8,16)), (2, (8,16)), (3, (1,2,4,8)), (4, (8,16)), (6, (8,16))):
            for depth in depths:
                for interlace in (0, 1):
                    for width, height in ((9,7), (1,1), (1,9), (9,1)):
                        name = f"png_c{color}_d{depth}_i{interlace}_{width}x{height}"
                        image = png(width, height, depth, color, interlace, depth % 5, height != 1)
                        path.write_bytes(image)
                        result = subprocess.check_output([str(helper), str(path)])
                        assert struct.unpack(">II", result[:8]) == (width, height)
                        cases.append(f'    TerminalGraphicsPngFixture("{name}", {width}, {height}, {encoded(image)}, {encoded(result[8:])})')
                        png_hashes[name] = {"png": hashlib.sha256(image).hexdigest(), "rgba": hashlib.sha256(result[8:]).hexdigest()}
    zcases = []
    patterns = [(b"", 1), (b"x", 1), (b"abc", 1000), (bytes(range(256)), 3), (b"abcde", 15000)]
    for pi, (pattern, count) in enumerate(patterns):
        source = pattern * count
        for level, strategy in ((0, zlib.Z_DEFAULT_STRATEGY), (6, zlib.Z_DEFAULT_STRATEGY), (9, zlib.Z_FIXED), (6, zlib.Z_HUFFMAN_ONLY)):
            encoder = zlib.compressobj(level, zlib.DEFLATED, 15, 8, strategy)
            compressed = encoder.compress(source) + encoder.flush()
            assert zlib.decompress(compressed) == source
            zcases.append(f'    TerminalGraphicsZlibFixture("zlib_{pi}_{level}_{strategy}", {encoded(pattern)}, {count}, {encoded(compressed)})')
    # Multiple block types and history surviving full/sync flush boundaries.
    pattern = bytes(range(256))
    encoder = zlib.compressobj()
    compressed = bytearray()
    for i in range(300):
        compressed += encoder.compress(pattern)
        if i % 7 == 0:
            compressed += encoder.flush(zlib.Z_SYNC_FLUSH)
    compressed += encoder.flush()
    assert zlib.decompress(compressed) == pattern * 300
    zcases.append(f'    TerminalGraphicsZlibFixture("zlib_blocks", {encoded(pattern)}, 300, {encoded(compressed)})')
    # Legal degenerate dynamic trees: only EOB, with either empty or one-bit distance alphabet.
    for distance_length in (0, 1):
        bits = []
        def put(value, count):
            bits.extend((value >> i) & 1 for i in range(count))
        put(1, 1); put(2, 2); put(0, 5); put(0, 5); put(14, 4)
        for code in (16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1):
            put(1 if code in (0,1) else 0, 3)
        for _ in range(256):
            put(0, 1)
        put(1, 1); put(distance_length, 1); put(0, 1)
        packed = bytearray((len(bits) + 7) // 8)
        for i, bit in enumerate(bits):
            packed[i // 8] |= bit << (i % 8)
        compressed = b"\x78\x01" + packed + b"\x00\x00\x00\x01"
        assert zlib.decompress(compressed) == b""
        zcases.append(f'    TerminalGraphicsZlibFixture("zlib_empty_tree_{distance_length}", "", 1, {encoded(compressed)})')
    # Deterministic corrupted streams whose rejection is independently established by zlib.
    base = zlib.compress(b"corrupt me repeatedly " * 100, 9)
    invalid = {base[:i] for i in range(len(base))}
    rng = random.Random(1951)
    for _ in range(300):
        changed = bytearray(base)
        for _ in range(rng.randint(1, 3)):
            changed[rng.randrange(len(changed))] ^= 1 << rng.randrange(8)
        try:
            zlib.decompress(changed)
        except zlib.error:
            invalid.add(bytes(changed))
    target = ROOT / "kinetica-terminal/test/generated/TerminalGraphicsImageFixtures.kt"
    output = '// Generated by scripts/generate-terminal-image-fixtures.py; do not edit.\npackage io.heapy.kinetica.terminal\n\n'
    output += 'internal class TerminalGraphicsPngFixture(val name: String, val width: Int, val height: Int, val png: String, val rgba: String)\n'
    output += 'internal class TerminalGraphicsZlibFixture(val name: String, val pattern: String, val count: Int, val zlib: String)\n'
    output += 'internal fun terminalGraphicsPngFixtures() = listOf(\n' + ',\n'.join(cases) + '\n)\n'
    output += 'internal fun terminalGraphicsZlibFixtures() = listOf(\n' + ',\n'.join(zcases) + '\n)\n'
    output += 'internal fun terminalGraphicsInvalidZlibFixtures() = listOf(\n' + ',\n'.join('    ' + encoded(b) for b in sorted(invalid)) + '\n)\n'
    write(target, output)
    manifest = {"libpng": libpng_version,
                "zlib": zlib.ZLIB_RUNTIME_VERSION, "pngCases": len(cases), "zlibCases": len(zcases),
                "invalidZlibCases": len(invalid), "pngHashes": png_hashes,
                "generatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "helperSha256": hashlib.sha256((ROOT / "scripts/terminal-png-oracle.c").read_bytes()).hexdigest(),
                "fixturesSha256": hashlib.sha256(output.encode()).hexdigest()}
    directory = ROOT / "kinetica-terminal/test-upstream/images"
    directory.mkdir(exist_ok=True)
    write(directory / "manifest.json", json.dumps(manifest, indent=2) + "\n")
    print(f"Generated {len(cases)} libpng pixel cases, {len(zcases)} zlib streams and {len(invalid)} zlib rejection cases")


if __name__ == "__main__":
    main()
