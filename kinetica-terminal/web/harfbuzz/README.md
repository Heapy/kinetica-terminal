# Browser HarfBuzz runtime

This self-hosted WASM reactor provides full HarfBuzz 11.0.0, the version pinned by
Ghostty 1.4.0. `terminal-harfbuzz.mjs` owns fonts and a reusable shaping buffer, and
returns copied glyph positions, outlines, metrics, COLRv0 layers and color PNG data. It has no
dependency on the Kinetica UI framework or a package CDN.

`BrowserTerminalView.configureShapingFonts` connects this runtime to WebGL font
selection, cluster-to-grid mapping and individual-glyph atlas rasterization. The
host supplies font bytes and this module's URL; CSS system-font family names alone
cannot supply the font bytes required by HarfBuzz. See the
[embedding API](../../README.md#browser) and [integration checks](../../../docs/terminal-font-shaping.md#browser-gpu-integration).

## API

Serve this directory, including its licenses, as static assets. Supply SFNT font
bytes (`.ttf` or `.otf`), rather than CSS font names or compressed web-font files:

```js
import { createTerminalHarfBuzz } from "./terminal-harfbuzz.mjs";

const runtime = await createTerminalHarfBuzz();
try {
  const response = await fetch("./fonts/JetBrainsMono-Regular.ttf");
  if (!response.ok) throw new Error("Font load failed");
  const font = runtime.createFont(await response.arrayBuffer(), 14);
  try {
    const glyphs = font.shape("a===>b");
    // glyph, UTF-16 index, xAdvance, yAdvance, xOffset, yOffset.
    const outline = font.outline(glyphs[0].glyph);
    const bounds = font.extents(glyphs[0].glyph);
  } finally { font.dispose(); }
} finally { runtime.dispose(); }
```

Font sizes are device pixels. All returned positions, advances, extents and outline
coordinates are in 26.6 units (divide by 64 for pixels), with positive Y upwards.
Outline commands are a packed `Float32Array`: `0,x,y` moves, `1,x,y` draws a line,
`2,cx,cy,x,y` draws a quadratic curve, `3,c1x,c1y,c2x,c2y,x,y` draws a cubic curve,
and `4` closes a contour. `colorPng(glyph)` returns a copied `Uint8Array` or `null`.
`hasColorLayers` reports COLRv0 data. `colorLayers(glyph)` returns `null` or a copied
array of `{glyph, color}` in paint order; `color` is unpremultiplied `[r,g,b,a]`
from CPAL palette zero, or `null` for the current text foreground. Color layers
must be composited before atlas upload, and fixed palette colors must not be tinted.
`nominalGlyph(codepoint, variationSelector = 0)` returns a glyph ID or `null`.
`setSize(pixels)` updates the font's scale and PPEM; existing renderer caches must
be invalidated when the size changes.

Shaping forces LTR and the `CHARACTERS` cluster level, matching Ghostty. The default
`liga` feature can be overridden with a feature list such as `['-liga', '-calt']`.
The runtime deliberately does not perform font fallback or terminal-cell layout.

Ownership is explicit: disposing a font releases its HarfBuzz references; disposing
the runtime also disposes remaining fonts and drops its WASM instance. Operations
on disposed handles fail. Results remain valid after subsequent calls or disposal.
WASM memory has a binary-enforced 128 MiB maximum. Font input is limited to 32 MiB
per font and 64 MiB of owned font data per runtime; glyph outlines are limited to
1,048,576 floats; one COLRv0 glyph is limited to 4,096 layers. Repeated create/shape/outline/dispose cycles reuse the heap without
depending on JavaScript finalization. These limits do not include caller-owned
font bytes or renderer caches.

## Rebuild and verify

Generated WASM, notices and `manifest.json` come from the pinned source archive and
checked-in bridge. Do not edit them by hand:

```sh
python3 scripts/generate-browser-harfbuzz.py build/upstream/zig-aarch64-macos-0.17.0/zig
python3 scripts/generate-browser-harfbuzz.py build/upstream/zig-aarch64-macos-0.17.0/zig --check
```

The manifest records source, bridge, generator and artifact hashes, the compiler
version, exports, build flags and memory limit. License files are copied unchanged
from the HarfBuzz and Zig distributions. The reactor's only WASI imports are three
closed diagnostic stream operations; it has no filesystem or network imports.

With the pinned Ghostty checkout in `build/upstream/ghostty-1.4.0`, Playwright and a
static server serving the repository root:

```sh
KINETICA_BROWSER_BASE_URL=http://127.0.0.1:4188 node scripts/verify-browser-harfbuzz.mjs
KINETICA_BROWSER_BASE_URL=http://127.0.0.1:4188 node scripts/verify-terminal-color-fonts.mjs
KINETICA_BROWSER_BASE_URL=http://127.0.0.1:4188 node scripts/verify-terminal-sbix.mjs
```

Set `PLAYWRIGHT_CHROMIUM_EXECUTABLE` when using an existing Chromium installation.
The verifier checks original font/corpus hashes, every raw field in 194 original
runs / 1146 glyphs in both Node and Chromium, outline and color-PNG extraction,
input validation, disposal, independent result storage and stable WASM allocation.
The omitted 195th original run is geometric block artwork, not a font run. The
original Noto Color Emoji bitmap face uses its 109-pixel strike when comparing raw
FreeType advances; its raster is later fitted to terminal cells by a renderer.

## Why build the full library?

The stock `harfbuzzjs` 1.6.4 probe omitted the fallback mark offset in the original
Monaspace combining-mark case. Its
[build uses `HB_TINY`](https://github.com/harfbuzz/harfbuzzjs/blob/3f162fa2c6ba68b40839fe2c11502a26632e20df/Makefile),
which disables legacy/fallback shaping. This build keeps those shaping paths and
matches all original raw results without changing expectations or adding tolerance.
It does not ship the probed npm package or rely on its garbage-collected handles.
