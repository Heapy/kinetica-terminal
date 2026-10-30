#!/usr/bin/env python3
"""Generate original, deterministic color-font fixtures (project Apache-2.0 license).

Build dependency: fonttools==4.60.1, pure-Python wheel SHA-256
906306ac7afe2156fcf0042173d6ebbb05416af70f6b370967b47f8f00103bbb.
No third-party font artwork is copied. Coordinates and palettes below are also
the independent pixel-test specification; HarfBuzz is not used by this generator.
"""
import argparse
import hashlib
import io
import json
import base64
import struct
import zlib
from pathlib import Path
import fontTools
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables.sbixGlyph import Glyph
from fontTools.ttLib.tables.sbixStrike import Strike
from fontTools.ttLib.tables.S_V_G_ import SVGDocument

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / 'kinetica-terminal/test-upstream/color-fonts'
VERSION = '4.60.1'
PALETTE = [[240, 32, 16, 255], [16, 224, 64, 128], [96, 96, 96, 255], [32, 64, 240, 128]]
RECTANGLES = {'outer': [50, 0, 550, 700], 'inner': [150, 100, 450, 600],
              'band': [100, 275, 500, 425], 'letter': [40, 0, 560, 700],
              'fallback': [200, 200, 400, 400]}
LAYERS = {'A': [('outer', 0), ('inner', 1), ('band', 0xffff)],
          'B': [('outer', 2)], 'C': [('outer', 3)], 'D': [('inner', 0xffff)],
          'E': [('outer', 0), ('band', 2)]}


def generate():
    assert fontTools.__version__ == VERSION, f'Expected fontTools {VERSION}'
    names = ['.notdef', 'space', 'M', 'H', 'A', 'B', 'C', 'D', 'E', 'F'] + list(RECTANGLES)
    builder = FontBuilder(1000, isTTF=True)
    builder.setupGlyphOrder(names)
    builder.setupCharacterMap({32: 'space'} | {ord(name): name for name in names if len(name) == 1})
    glyphs = {}
    for name in names:
        pen = TTGlyphPen(None)
        rect = RECTANGLES.get(name)
        if name in ['M', 'H', 'F']: rect = RECTANGLES['letter']
        if name in ['A', 'B', 'C', 'D']: rect = RECTANGLES['fallback']
        if rect:
            x0, y0, x1, y1 = rect
            pen.moveTo((x0, y0)); pen.lineTo((x0, y1)); pen.lineTo((x1, y1)); pen.lineTo((x1, y0)); pen.closePath()
        glyphs[name] = pen.glyph()
    builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({name: (600, glyphs[name].xMin if glyphs[name].numberOfContours else 0) for name in names})
    builder.setupHorizontalHeader(ascent=800, descent=-200, lineGap=0)
    builder.setupNameTable({'familyName': 'Kinetica Color Fixture', 'styleName': 'Regular',
        'uniqueFontIdentifier': 'KineticaColorFixture-1', 'fullName': 'Kinetica Color Fixture Regular',
        'psName': 'KineticaColorFixture-Regular', 'version': 'Version 1.0',
        'copyright': 'Original Kinetica Terminal test artwork. Apache-2.0.'})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, sTypoLineGap=0, usWinAscent=800, usWinDescent=200,
                    sxHeight=500, sCapHeight=700)
    builder.setupPost(isFixedPitch=1)
    builder.setupCPAL([[tuple(channel / 255 for channel in color) for color in PALETTE]])
    builder.setupCOLR(LAYERS, version=0)
    builder.setupHead(created=3406620153, modified=3406620153)
    builder.font.recalcTimestamp = False
    data = io.BytesIO(); builder.save(data)
    return data.getvalue(), names


def png(width, height, pixels):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    raw = b''.join(b'\0' + bytes(pixels[y * width * 4:(y + 1) * width * 4]) for y in range(height))
    # One deterministic DEFLATE stored block, independent of compression heuristics.
    compressed = b'\x78\x01\x01' + struct.pack('<HH', len(raw), 65535 - len(raw)) + raw + struct.pack('>I', zlib.adler32(raw))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)) + chunk(b'IDAT', compressed) + chunk(b'IEND', b'')


def generate_sbix(base):
    font = TTFont(io.BytesIO(base), recalcTimestamp=False)
    del font['COLR']; del font['CPAL']
    # CoreText measures sbix glyphs using their outline bounds. Author matching
    # fallback geometry, unlike the deliberately different COLR test fallbacks.
    for name in ['A', 'B', 'C', 'D']:
        rect = [50, 0, 550, 700] if name != 'D' else [-100, -100, 400, 600]
        x0, y0, x1, y1 = rect
        pen = TTGlyphPen(None)
        pen.moveTo((x0, y0)); pen.lineTo((x0, y1)); pen.lineTo((x1, y1)); pen.lineTo((x1, y0)); pen.closePath()
        font['glyf'][name] = pen.glyph(); font['hmtx'][name] = (600, x0)
    font['name'].setName('Kinetica Sbix Fixture', 1, 3, 1, 0x409)
    font['name'].setName('KineticaSbixFixture-Regular', 6, 3, 1, 0x409)
    table = newTable('sbix'); description = []
    for ppem, gray in [(20, 96), (40, 176)]:
        width, height = ppem // 2, ppem * 7 // 10
        strike = Strike(ppem=ppem, resolution=72)
        images = {}
        for name in ['A', 'B', 'D']:
            pixels = []
            for y in range(height):
                for x in range(width):
                    color = [gray, gray, gray, 255] if name != 'B' else (
                        [240, 32, 16, 255] if x < width // 2 else [32, 64, 240, 128])
                    pixels += color
            origin = [-ppem // 10, -ppem // 10] if name == 'D' else [ppem // 20, 0]
            data = png(width, height, pixels)
            strike.glyphs[name] = Glyph(glyphName=name, originOffsetX=origin[0], originOffsetY=origin[1], graphicType='png ', imageData=data)
            images[name] = dict(width=width, height=height, origin=origin, rgba=pixels, pngBase64=base64.b64encode(data).decode())
        strike.glyphs['C'] = Glyph(glyphName='C', referenceGlyphName='A', graphicType='dupe')
        table.strikes[ppem] = strike
        description.append(dict(ppem=ppem, gray=gray, images=images))
    font['sbix'] = table
    output = io.BytesIO(); font.save(output)
    return output.getvalue(), description


def generate_svg(base):
    font = TTFont(io.BytesIO(base), recalcTimestamp=False)
    del font['COLR']; del font['CPAL']
    font['name'].setName('Kinetica SVG Fixture', 1, 3, 1, 0x409)
    font['name'].setName('KineticaSVGFixture-Regular', 6, 3, 1, 0x409)
    documents = []
    for name in ['A', 'C']:
        pen = TTGlyphPen(None)
        pen.moveTo((50, 0)); pen.lineTo((50, 700)); pen.lineTo((550, 700)); pen.lineTo((550, 0)); pen.closePath()
        font['glyf'][name] = pen.glyph(); font['hmtx'][name] = (600, 50)
        glyph = font.getGlyphID(name)
        body = '<rect x="50" y="-700" width="500" height="700" fill="#606060"/>' if name == 'A' else (
            '<defs><linearGradient id="paint"><stop offset="0" stop-color="#f02010"/>'
            '<stop offset="1" stop-color="#2040f0" stop-opacity="0.5"/></linearGradient></defs>'
            '<rect x="50" y="-700" width="500" height="700" fill="url(#paint)"/>')
        svg = f'<svg id="glyph{glyph}" version="1.1" xmlns="http://www.w3.org/2000/svg">{body}</svg>'
        documents.append(dict(glyph=glyph, svg=svg, compressed=name == 'C'))
    table = newTable('SVG ')
    table.docList = [SVGDocument(item['svg'], item['glyph'], item['glyph'], item['compressed']) for item in documents]
    font['SVG '] = table
    output = io.BytesIO(); font.save(output)
    return output.getvalue(), documents


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    font, names = generate()
    sbix, strikes = generate_sbix(font)
    svg, documents = generate_svg(font)
    manifest = dict(fonttools=VERSION, generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    fontSha256=hashlib.sha256(font).hexdigest(), unitsPerEm=1000, advance=600,
                    ascender=800, descender=-200, glyphOrder=names, rectangles=RECTANGLES, palette=PALETTE, layers=LAYERS)
    sbix_manifest = dict(fonttools=VERSION, generatorSha256=manifest['generatorSha256'],
                         fontSha256=hashlib.sha256(sbix).hexdigest(), glyphOrder=names, strikes=strikes)
    svg_manifest = dict(fonttools=VERSION, generatorSha256=manifest['generatorSha256'],
                       fontSha256=hashlib.sha256(svg).hexdigest(), glyphOrder=names, documents=documents)
    kotlin = '// Generated by scripts/generate-terminal-color-fonts.py. Original Apache-2.0 test artwork.\npackage io.heapy.kinetica.terminal\ninternal fun terminalSbixFont(): String = "' + base64.b64encode(sbix).decode() + '"\n'
    artifacts = {'colrv0.ttf': font, 'manifest.json': (json.dumps(manifest, indent=2) + '\n').encode(),
                 'sbix.ttf': sbix, 'sbix-manifest.json': (json.dumps(sbix_manifest, indent=2) + '\n').encode(),
                 'svg.ttf': svg, 'svg-manifest.json': (json.dumps(svg_manifest, indent=2) + '\n').encode()}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, data in artifacts.items():
        path = OUTPUT / name
        if args.check: assert path.read_bytes() == data, f'Stale generated artifact: {path}'
        else: path.write_bytes(data)
    native = ROOT / 'kinetica-terminal/test@macosArm64/generated/TerminalSbixFont.kt'
    if args.check: assert native.read_text() == kotlin, f'Stale generated artifact: {native}'
    else: native.write_text(kotlin)
    native = ROOT / 'kinetica-terminal/test@macosArm64/generated/TerminalSvgFont.kt'
    kotlin = '// Generated by scripts/generate-terminal-color-fonts.py. Original Apache-2.0 test artwork.\npackage io.heapy.kinetica.terminal\ninternal fun terminalSvgFont(): String = "' + base64.b64encode(svg).decode() + '"\n'
    if args.check: assert native.read_text() == kotlin, f'Stale generated artifact: {native}'
    else: native.write_text(kotlin)
    print(f'{"Verified" if args.check else "Generated"} COLRv0 / sbix / SVG fixtures: {len(font)} / {len(sbix)} / {len(svg)} bytes')


if __name__ == '__main__': main()
