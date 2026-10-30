#!/usr/bin/env python3
"""Original COLRv1 and mixed-currentColor SVG fixtures using pinned FontTools."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import runpy

from fontTools.colorLib.builder import buildCOLR
from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables.S_V_G_ import SVGDocument
from fontTools.ttLib.tables.otTables import PaintFormat as P, CompositeMode

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / 'scripts/generate-terminal-color-fonts.py'
OUTPUT = ROOT / 'kinetica-terminal/test-upstream/color-fonts'


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    base = runpy.run_path(str(BASE)); data, names = base['generate']()
    def solid(index): return dict(Format=P.PaintSolid, PaletteIndex=index, Alpha=1.0)
    def glyph(name, paint): return dict(Format=P.PaintGlyph, Glyph=name, Paint=paint)
    gradient = dict(Format=P.PaintLinearGradient, x0=50, y0=0, x1=550, y1=0, x2=50, y2=700,
                    ColorLine=dict(Extend=0, ColorStop=[dict(StopOffset=0.0, PaletteIndex=0, Alpha=1.0),
                                                      dict(StopOffset=1.0, PaletteIndex=3, Alpha=1.0)]))
    paints = {
        'A': dict(Format=P.PaintColrLayers, Layers=[glyph('outer', solid(0)), glyph('inner', solid(1)), glyph('band', solid(0xffff))]),
        'B': glyph('outer', solid(2)),
        'C': glyph('outer', gradient),
        'D': dict(Format=P.PaintComposite, CompositeMode=CompositeMode.SRC_IN,
                  SourcePaint=glyph('outer', solid(3)), BackdropPaint=glyph('inner', solid(0xffff))),
        'E': dict(Format=P.PaintTranslate, dx=40, dy=30, Paint=dict(Format=P.PaintColrGlyph, Glyph='C')),
    }
    font = TTFont(io.BytesIO(data), recalcTimestamp=False)
    font['name'].setName('Kinetica COLRv1 Fixture', 1, 3, 1, 0x409)
    font['name'].setName('KineticaCOLRv1Fixture-Regular', 6, 3, 1, 0x409)
    font['COLR'] = buildCOLR(paints, version=1, glyphMap=font.getReverseGlyphMap())
    output = io.BytesIO(); font.save(output); colr = output.getvalue()

    svg_data, documents = base['generate_svg'](data)
    font = TTFont(io.BytesIO(svg_data), recalcTimestamp=False)
    font['name'].setName('Kinetica SVG Mixed Fixture', 1, 3, 1, 0x409)
    font['name'].setName('KineticaSVGMixedFixture-Regular', 6, 3, 1, 0x409)
    bodies = {
        'B': '<rect x="50" y="-700" width="500" height="700" fill="#f02010"/><rect x="150" y="-600" width="300" height="500" fill="currentColor" opacity="0.5"/>',
        'D': '<rect x="50" y="-700" width="500" height="700" fill="currentColor"/>',
        'E': '<defs><clipPath id="cut"><rect x="100" y="-500" width="300" height="300"/></clipPath></defs><g transform="translate(40,-30)" clip-path="url(#cut)"><rect x="50" y="-700" width="500" height="700" fill="#2040f0"/></g>',
    }
    for name, body in bodies.items():
        gid = font.getGlyphID(name)
        documents.append(dict(glyph=gid, compressed=name == 'D',
                              svg=f'<svg id="glyph{gid}" version="1.1" xmlns="http://www.w3.org/2000/svg">{body}</svg>'))
    documents.sort(key=lambda item: item['glyph'])
    table = newTable('SVG '); table.docList = [SVGDocument(item['svg'], item['glyph'], item['glyph'], item['compressed']) for item in documents]
    font['SVG '] = table
    output = io.BytesIO(); font.save(output); svg = output.getvalue()
    manifest = dict(fonttools=base['VERSION'], generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    baseGeneratorSha256=hashlib.sha256(BASE.read_bytes()).hexdigest(), glyphOrder=names,
                    colrv1Sha256=hashlib.sha256(colr).hexdigest(), svgMixedSha256=hashlib.sha256(svg).hexdigest(),
                    paints=paints, documents=documents)
    artifacts = {'colrv1.ttf': colr, 'svg-mixed.ttf': svg, 'vector-manifest.json': (json.dumps(manifest, indent=2) + '\n').encode()}
    for name, data in artifacts.items():
        path = OUTPUT / name
        if args.check: assert path.read_bytes() == data, f'Stale fixture: {path}'
        else: path.write_bytes(data)
    print(f'{"Verified" if args.check else "Generated"} COLRv1 / SVG mixed: {len(colr)} / {len(svg)} bytes')


if __name__ == '__main__': main()
