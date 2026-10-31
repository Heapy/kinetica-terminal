#!/usr/bin/env node
// Execute the pinned original fragment main, including the color-atlas early return.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const upstream = resolve(process.argv[2] ?? `${root}/build/upstream/ghostty-1.4.0`);
const pin = JSON.parse(readFileSync(`${root}/kinetica-terminal/third-party/ghostty.json`));
assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], { cwd: upstream, encoding: 'utf8' }).trim(), pin.commit);
execFileSync('git', ['diff', '--exit-code', 'HEAD', '--', '.'], { cwd: upstream });
const common = readFileSync(`${upstream}/src/renderer/shaders/glsl/common.glsl`, 'utf8');
const functions = common.slice(common.indexOf('float luminance(vec3 color)'), common.indexOf('\n}', common.indexOf('vec4 load_color(')) + 2);
const fragment = readFileSync(`${upstream}/src/renderer/shaders/glsl/cell_text.f.glsl`, 'utf8');
const originalMain = fragment.slice(fragment.indexOf('void main()'));
const cases = [];
for (const colored of [0, 1]) for (const alpha of [0, 1, 26, 128, 230, 255]) for (const coverage of [0, 32, 128, 255]) cases.push([colored, alpha, coverage]);
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? 'playwright');
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
let version;
try {
  version = browser.version();
  const page = await browser.newPage();
  const output = await page.evaluate(({ functions, originalMain, cases }) => {
    const canvas = document.createElement('canvas'); canvas.width = 1; canvas.height = 1;
    const gl = canvas.getContext('webgl2', { antialias: false, premultipliedAlpha: true });
    if (!gl) throw new Error('WebGL2 unavailable');
    const compile = (type, source) => {
      const s = gl.createShader(type); gl.shaderSource(s, source); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      return s;
    };
    const vs = compile(gl.VERTEX_SHADER, `#version 300 es
      void main() { vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)); gl_Position = vec4(p * 2.0 - 1.0, 0, 1); }`);
    const fs = compile(gl.FRAGMENT_SHADER, `#version 300 es
      precision highp float; precision highp int;
      ${functions}
      const int USE_LINEAR_BLENDING = 4, USE_LINEAR_CORRECTION = 8;
      const uint ATLAS_GRAYSCALE = 0u, ATLAS_COLOR = 1u;
      uniform int bools; uniform uint atlas; uniform uvec4 foreground;
      uniform sampler2D atlas_grayscale, atlas_color;
      struct Input { uint atlas; vec4 color; vec4 bg_color; vec2 tex_coord; }; Input in_data;
      out vec4 out_FragColor;
      #define main original_fragment_main
      ${originalMain}
      #undef main
      void main() {
        in_data.atlas = atlas; in_data.color = load_color(foreground, true);
        in_data.bg_color = vec4(0,0,0,1); in_data.tex_coord = vec2(0.5);
        original_fragment_main();
      }`);
    const program = gl.createProgram(); gl.attachShader(program, vs); gl.attachShader(program, fs); gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program));
    gl.useProgram(program); gl.disable(gl.DITHER);
    // ES disallows uint != int; signed flag inputs preserve the original bit operations.
    // Both sampler declarations point at the same 1x1 texture. This adapts texture coordinates
    // to WebGL2 without changing a single line of the original fragment main.
    gl.uniform1i(gl.getUniformLocation(program, 'atlas_grayscale'), 0);
    gl.uniform1i(gl.getUniformLocation(program, 'atlas_color'), 0);
    gl.uniform1i(gl.getUniformLocation(program, 'bools'), 4);
    const texture = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    const pixel = new Uint8Array(4);
    return cases.map(([colored, alpha, coverage]) => {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([coverage, coverage, coverage, coverage]));
      gl.uniform1ui(gl.getUniformLocation(program, 'atlas'), colored);
      gl.uniform4ui(gl.getUniformLocation(program, 'foreground'), 255, 255, 255, alpha);
      gl.drawArrays(gl.TRIANGLES, 0, 3); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
      if (gl.getError() !== gl.NO_ERROR) throw new Error('Oracle rendering failed');
      return [...pixel];
    });
  }, { functions, originalMain, cases });
  cases.forEach((c, i) => c.push(...output[i]));
} finally { await browser.close(); }
const fixture = `${root}/kinetica-terminal/test/generated/TerminalGlyphOpacityFixtures.kt`;
writeFileSync(fixture, '// Generated by scripts/generate-terminal-glyph-opacity.mjs; do not edit.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalGlyphOpacityFixtures(): List<IntArray> = listOf(\n' + cases.map(c => `    intArrayOf(${c.join(', ')}),\n`).join('') + ')\n');
const digest = path => createHash('sha256').update(readFileSync(path)).digest('hex');
writeFileSync(`${root}/kinetica-terminal/test-upstream/ghostty/glyph-opacity-manifest.json`, JSON.stringify({ commit: pin.commit,
  chromium: version, cases: cases.length, generatorSha256: digest(fileURLToPath(import.meta.url)), fixtureSha256: digest(fixture),
  sources: Object.fromEntries(['src/renderer/shaders/glsl/common.glsl', 'src/renderer/shaders/glsl/cell_text.f.glsl', 'src/renderer/shaders/glsl/cell_text.v.glsl'].map(p => [p, digest(`${upstream}/${p}`)])) }, null, 2) + '\n');
console.log(`Generated ${cases.length} original Ghostty fragment outputs`);
