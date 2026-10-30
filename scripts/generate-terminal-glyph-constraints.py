#!/usr/bin/env python3
"""Generate glyph constraint tables and geometry/width oracles from pinned Ghostty."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
SHA = '83fb95624b0439ee660c2679b43b38c8b63769cd'
FIELDS = ['size', 'align_vertical', 'align_horizontal', 'pad_top', 'pad_left', 'pad_right', 'pad_bottom',
          'relative_width', 'relative_height', 'relative_x', 'relative_y', 'max_xy_ratio', 'max_constraint_width', 'height']
DEFAULT = dict(zip(FIELDS, ['none', 'none', 'none', 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, None, 2, 'cell']))
METRICS = dict(cell_width=10, cell_height=22, cell_baseline=5, underline_position=19, underline_thickness=1,
               strikethrough_position=12, strikethrough_thickness=1, overline_position=0, overline_thickness=1,
               box_thickness=1, cursor_thickness=1, cursor_height=22, icon_height=21.12,
               icon_height_single=44.48 / 3, face_width=9.6, face_height=21.12, face_y=0.2)


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def encoded(data):
    value = base64.b64encode(data).decode()
    return 'listOf(' + ','.join(json.dumps(value[i:i+8000]) for i in range(0, len(value), 8000)) + ').joinToString("")'


def cases():
    metrics = [METRICS, METRICS | dict(cell_width=24, cell_height=18, face_width=23.5, face_height=18.2, face_y=-0.25),
               METRICS | dict(cell_width=8, cell_height=30, face_width=8.25, face_height=22.1, face_y=3.7)]
    glyphs = [dict(width=6.784, height=15.28, x=1.408, y=4.84), dict(width=10.272, height=10.272, x=2.864, y=5.304),
              dict(width=20.0, height=20.0, x=0.46, y=1.0), dict(width=2.3, height=3.7, x=-4.1, y=34.8),
              dict(width=40.2, height=5.3, x=-11.4, y=-7.9)]
    result = []
    for size in ['none', 'fit', 'cover', 'fit_cover1', 'stretch']:
        for vertical in ['none', 'start', 'end', 'center', 'center1']:
            for horizontal in ['none', 'start', 'end', 'center', 'center1']:
                for width in [1, 2]:
                    for index, metric in enumerate(metrics):
                        # Exercise group transforms, negative padding, clamping and aspect-ratio limits.
                        for variant in range(3):
                            constraint = DEFAULT | dict(size=size, align_vertical=vertical, align_horizontal=horizontal)
                            if variant == 1: constraint |= dict(pad_top=-0.2, pad_bottom=0.05, pad_left=-0.1, pad_right=0.15,
                                relative_width=0.7, relative_height=0.8, relative_x=0.2, relative_y=-0.1, max_xy_ratio=1.2, height='icon')
                            if variant == 2: constraint |= dict(pad_top=0.1, pad_bottom=0.2, pad_left=0.025, pad_right=0.025,
                                max_constraint_width=1, max_xy_ratio=0.6)
                            result.append(dict(glyph=glyphs[(index+variant)%len(glyphs)], metrics=metric, width=width, constraint=constraint))
    rows = []
    for cp in [ord('A'), 0x25a0, 0x263a, 0x1f600, 0xea61, 0xe0b0, 0xe0c0, 0xf0001, 0x754c]:
        for before in ['', 'x', '\ue8ef', '\ue0b0', '█', '\U0001fb00']:
            for after in ['', 'x', ' ', '\u2002', '\u00a0', '\ue8ef', '界']:
                for columns in [3, 8]: rows.append(dict(text=before+chr(cp)+after, columns=columns))
    rows += [dict(text='x'*5+chr(cp), columns=6) for cp in [0xea61, 0x25a0, ord('A')]]
    return result, rows


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('upstream',type=Path);parser.add_argument('zig',type=Path);parser.add_argument('--check',action='store_true')
    args=parser.parse_args();upstream=args.upstream.resolve();zig=args.zig.resolve()
    assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=upstream,text=True).strip()==SHA
    subprocess.run(['git','diff','--exit-code','HEAD','--','.'],cwd=upstream,check=True)
    build=ROOT/'build/terminal-glyph-constraints-oracle';source=build/'source';source.mkdir(parents=True,exist_ok=True)
    with subprocess.Popen(['git','archive',SHA],cwd=upstream,stdout=subprocess.PIPE) as archive:
        subprocess.run(['tar','-xf','-','-C',str(source)],stdin=archive.stdout,check=True);archive.stdout.close();assert archive.wait()==0
    helper=ROOT/'scripts/terminal-ghostty-glyph-constraints-oracle.zig'
    shutil.copyfile(helper,source/'src/kinetica-glyph-constraints-oracle.zig')
    geometry,rows=cases()
    (source/'src/glyph-constraint-geometries.json').write_text(json.dumps(geometry))
    (source/'src/glyph-constraint-rows.json').write_text(json.dumps(rows,ensure_ascii=True))
    template=runpy.run_path(str(ROOT/'scripts/generate-terminal-shaping.py'))['BUILD']
    (source/'build.zig').write_text(template.replace('shaping-oracle','glyph-constraints-oracle').replace('kinetica shaping oracle','kinetica glyph constraints oracle'))
    env=os.environ|{'ZIG_GLOBAL_CACHE_DIR':str(ROOT/'build/upstream/zig-cache-0.17')}
    subprocess.run([str(zig),'build','-Dfont-backend=freetype','-Dsimd=false','-Dapp-runtime=none','-Drenderer=opengl','-Dsentry=false','-Di18n=false'],cwd=source,env=env,check=True)
    run=subprocess.run([str(source/'zig-out/bin/glyph-constraints-oracle')],capture_output=True,text=True)
    (build/'stderr.log').write_text(run.stderr);run.check_returncode()
    output=[json.loads(line) for line in run.stdout.splitlines()]
    ranges=[value for value in output if 'start' in value]
    expected=[value['geometry'] for value in output if 'geometry' in value]
    widths=[value['widths'] for value in output if 'widths' in value]
    assert len(expected)==len(geometry) and len(widths)==len(rows)
    for case,value in zip(geometry,expected):case['expected']=value
    for case,value in zip(rows,widths):case['expected']=value
    values=[];indices=[]
    for item in ranges:
        constraint=item['constraint']
        if constraint is not None and constraint not in values:values.append(constraint)
        indices.extend([item['start'],item['end'],values.index(constraint) if constraint is not None else -1,int(item['symbol'])])
    names=dict(size='size',align_vertical='vertical',align_horizontal='horizontal',pad_top='padTop',pad_left='padLeft',pad_right='padRight',pad_bottom='padBottom',relative_width='relativeWidth',relative_height='relativeHeight',relative_x='relativeX',relative_y='relativeY',max_xy_ratio='maxAspectRatio',max_constraint_width='maxColumns',height='height')
    enum={'size':'TerminalGlyphSizing','align_vertical':'TerminalGlyphAlignment','align_horizontal':'TerminalGlyphAlignment','height':'TerminalGlyphHeight'}
    def kotlin_value(key,value):
        if key in enum:return enum[key]+'.'+value.upper()
        if value is None:return 'null'
        if key=='max_constraint_width':return str(value)
        return repr(float(value))
    entries=['TerminalGlyphConstraint('+', '.join(names[key]+' = '+kotlin_value(key,value[key]) for key in FIELDS if value[key]!=DEFAULT[key])+')' for value in values]
    table='''// Generated by scripts/generate-terminal-glyph-constraints.py. Do not edit.
// Ghostty 1.4.0 '''+SHA+''' (MIT); see third-party/GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal object TerminalGlyphConstraintData {
    private val values = arrayOf(
'''+''.join('        '+entry+',\n' for entry in entries)+'''    )
    private val ranges = intArrayOf(
'''+''.join('        '+','.join(map(str,indices[i:i+16]))+',\n' for i in range(0,len(indices),16))+'''    )
    private fun range(code: Int): Int {
        if (code !in 0..0x10ffff) return -1
        var low = 0; var high = ranges.size / 4 - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (ranges[mid * 4] <= code) low = mid else high = mid - 1
        }
        return low * 4
    }
    fun constraint(code: Int): TerminalGlyphConstraint? {
        val range = range(code)
        if (range < 0) return null
        val value = ranges[range + 2]
        return if (value < 0) null else values[value]
    }
    fun isSymbol(code: Int): Boolean = range(code).let { it >= 0 && ranges[it + 3] != 0 }
}
'''
    fixture=dict(ranges=ranges,geometry=geometry,rows=rows)
    json_data=(json.dumps(fixture,indent=2,ensure_ascii=True)+'\n').encode()
    compact=json.dumps(fixture,separators=(',',':'),ensure_ascii=True).encode()
    kotlin='// Generated from original Ghostty glyph constraints. MIT; see third-party/GHOSTTY-LICENSE.\npackage io.heapy.kinetica.terminal\nimport kotlin.io.encoding.Base64\ninternal fun terminalGlyphConstraintFixtures(): String = Base64.decode('+encoded(compact)+').decodeToString()\n'
    paths=['src/font/Glyph.zig','src/font/Metrics.zig','src/font/nerd_font_attributes.zig','src/font/nerd_font_tables.zig',
           'src/unicode/symbols_table.zig','src/unicode/symbols_uucode.zig','src/unicode/lut.zig',
           'src/build/uucode_config.zig','src/build/SharedDeps.zig','build.zig.zon',
           'src/renderer/cell.zig','src/renderer/generic.zig','src/font/SharedGrid.zig']
    sources={path:digest(upstream/path) for path in paths}
    manifest=dict(commit=SHA,zig=subprocess.check_output([str(zig),'version'],text=True).strip(),helperSha256=digest(helper),generatorSha256=digest(Path(__file__)),buildTemplateSha256=digest(ROOT/'scripts/generate-terminal-shaping.py'),ranges=len(ranges),constraints=len(values),geometryCases=len(geometry),rowCases=len(rows),sources=sources,fixtureSha256=hashlib.sha256(json_data).hexdigest())
    artifacts={ROOT/'kinetica-terminal/src/generated/TerminalGlyphConstraintData.kt':table.encode(),ROOT/'kinetica-terminal/test/generated/TerminalGlyphConstraintFixtures.kt':kotlin.encode(),ROOT/'kinetica-terminal/test-upstream/ghostty/glyph-constraints.json':json_data,ROOT/'kinetica-terminal/test-upstream/ghostty/glyph-constraints-manifest.json':(json.dumps(manifest,indent=2)+'\n').encode()}
    for path,data in artifacts.items():
        if args.check:assert path.read_bytes()==data,f'Stale generated artifact: {path}'
        else:path.write_bytes(data)
    print(f'{"Verified" if args.check else "Generated"} {len(ranges)} lookup ranges / {len(values)} constraints / {len(geometry)} geometry cases / {len(rows)} row cases')


if __name__=='__main__':main()
