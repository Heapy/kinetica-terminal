#!/usr/bin/env python3
"""Generate original, deterministic COLR fixtures (project Apache-2.0 license).

Build dependency: fonttools==4.60.1, pure-Python wheel SHA-256
906306ac7afe2156fcf0042173d6ebbb05416af70f6b370967b47f8f00103bbb.
No third-party font artwork is copied. Coordinates and palettes below are also
the independent pixel-test specification; HarfBuzz is not used by this generator.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import fontTools
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

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


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    font, names = generate()
    manifest = dict(fonttools=VERSION, generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    fontSha256=hashlib.sha256(font).hexdigest(), unitsPerEm=1000, advance=600,
                    ascender=800, descender=-200, glyphOrder=names, rectangles=RECTANGLES, palette=PALETTE, layers=LAYERS)
    artifacts = {'colrv0.ttf': font, 'manifest.json': (json.dumps(manifest, indent=2) + '\n').encode()}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, data in artifacts.items():
        path = OUTPUT / name
        if args.check: assert path.read_bytes() == data, f'Stale generated artifact: {path}'
        else: path.write_bytes(data)
    print(f'{"Verified" if args.check else "Generated"} COLRv0 fixture: {len(font)} bytes')


if __name__ == '__main__': main()
