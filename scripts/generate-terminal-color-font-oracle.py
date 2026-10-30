#!/usr/bin/env python3
"""Run unmodified pinned Ghostty color-font rasterizers on original test artwork."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import runpy
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
SHA = '83fb95624b0439ee660c2679b43b38c8b63769cd'
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path); parser.add_argument('zig', type=Path)
    parser.add_argument('--backend', choices=['freetype', 'coretext'], default='freetype')
    parser.add_argument('--font', choices=['colrv0', 'sbix', 'svg', 'sbix-media'], default='colrv0')
    parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    upstream, zig = args.upstream.resolve(), args.zig.resolve()
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=upstream, text=True).strip() == SHA
    subprocess.run(['git', 'diff', '--exit-code', 'HEAD', '--', '.'], cwd=upstream, check=True)
    suffix = '' if args.font == 'colrv0' else args.font + '-'
    work = ROOT / ('build/terminal-color-font-oracle-' + suffix + args.backend); source = work / 'source'
    source.mkdir(parents=True, exist_ok=True)
    with subprocess.Popen(['git', 'archive', SHA], cwd=upstream, stdout=subprocess.PIPE) as archive:
        subprocess.run(['tar', '-xf', '-', '-C', str(source)], stdin=archive.stdout, check=True)
        archive.stdout.close(); assert archive.wait() == 0
    helper = ROOT / 'scripts/terminal-ghostty-color-font-oracle.zig'
    artwork = ROOT / ('kinetica-terminal/test-upstream/color-fonts/' + args.font + '.ttf')
    shutil.copyfile(helper, source / 'src/kinetica-color-font-oracle.zig')
    shutil.copyfile(artwork, source / 'src/kinetica-color-font.ttf')
    sizes = [20] if args.font == 'colrv0' else [20, 30, 40]
    (source / 'src/kinetica-color-font-sizes.json').write_text(json.dumps(sizes))
    template = ROOT / 'scripts/generate-terminal-shaping.py'
    build = runpy.run_path(str(template))['BUILD'].replace('shaping-oracle', 'color-font-oracle').replace('kinetica shaping oracle', 'kinetica color font oracle')
    (source / 'build.zig').write_text(build)
    env = os.environ | {'ZIG_GLOBAL_CACHE_DIR': str(ROOT / 'build/upstream/zig-cache-0.17')}
    subprocess.run([str(zig), 'build', '-Dfont-backend=' + args.backend, '-Dsimd=false', '-Dapp-runtime=none',
                    '-Drenderer=opengl', '-Dsentry=false', '-Di18n=false'], cwd=source, env=env, check=True)
    run = subprocess.run([str(source / 'zig-out/bin/color-font-oracle')], capture_output=True, text=True)
    (work / 'stderr.log').write_text(run.stderr); run.check_returncode()
    results = [json.loads(line) for line in run.stdout.splitlines()]
    assert len(results) == 6 * len(sizes)
    paths = ['src/font/face/' + args.backend + '.zig', 'src/font/Glyph.zig', 'src/font/Metrics.zig',
             'src/font/Atlas.zig', 'pkg/freetype/build.zig.zon']
    output = dict(commit=SHA, backend=args.backend, zig=subprocess.check_output([str(zig), 'version'], text=True).strip(),
                  generatorSha256=digest(Path(__file__)), helperSha256=digest(helper), buildTemplateSha256=digest(template),
                  fontSha256=digest(artwork), sources={path: digest(upstream / path) for path in paths}, results=results)
    if args.backend == 'coretext': output['macos'] = platform.mac_ver()[0]
    data = (json.dumps(output, indent=2) + '\n').encode()
    target = artwork.parent / ('ghostty-' + suffix + args.backend + '.json')
    if args.check: assert target.read_bytes() == data, f'Stale oracle: {target}'
    else: target.write_bytes(data)
    print(f'{"Verified" if args.check else "Generated"} original Ghostty {args.backend}: {len(results)} color-font glyphs')

if __name__ == '__main__': main()
