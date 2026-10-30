# Terminal font shaping

The Metal renderer shapes multi-cell runs and rasterizes individual glyphs. WebGL
does the same when its host installs SFNT faces with `configureShapingFonts`;
CSS-only system-font configurations retain the per-grapheme path. Software text grouping passes some longer
strings to the platform, but does not provide Ghostty's font selection, cluster
mapping and alignment to terminal cells.

The shared `TerminalShapingText`/`terminalPositionGlyphs` layer now preserves terminal
origins through platform shaping, including UTF-16 surrogate pairs, reordered marks,
and marks attached to ligatures spanning several cells. `TerminalNativeShaper` uses
CoreText with forced left-to-right embedding, records actual fallback font identities,
and retains those faces until disposal. Metal connects this shaper to its frame and
bounded atlas, with separate face collections for regular, bold and italic styles.

The common GPU frame accepts optional font-resolution and shaping callbacks. Its
run planner preserves style, cursor, selection and fallback-font boundaries while
allowing background changes, and excludes geometric block drawing from font glyphs.
Shaped atlas entries identify a face and glyph, carry pixel bearings and offsets,
and clip both geometry and UVs to the run and row without rescaling device pixels.
Both Metal and the configured WebGL shaper supply those callbacks.
Common tests compare primary-font run boundaries and cluster inputs in 42 original
Ghostty cases, and verify fallback substitution, clean-row retention and pixel clipping.
Validation: 1429 JVM tests, fresh Release JS tests and the Chromium WebGL glyph
regression at device-pixel ratios 1, 1.25 and 2 pass.

## Native GPU integration

CoreText resolves a face covering the entire grapheme before choosing run boundaries.
Presentation selectors participate in resolution and are removed from shaper input.
Following original Ghostty discovery, the resolver rejects `LastResort` and substitutes
U+FFFD (or space) when no candidate covers the complete grapheme. A 512-entry cell-text
cache bounds retained resolution strings. Retained faces and atlas entries reset together
on font/scale changes, hiding and disposal, after outstanding GPU work completes.

The rasterizer draws each glyph into a tight bitmap with its bearings and the primary
font's row baseline. It preserves color glyphs and antialiasing without scaling an
atlas tile to a fractional cell. Long runs retain their shaping context while sharing
individual glyph entries. Geometric block artwork stays outside the font atlas.

Validation: 50 focused Native tests pass. These include original glyph/offset checks,
whole-grapheme fallback and missing-glyph replacement, emoji presentation, exact raster
comparison with direct CoreText lines for `F`, `g` and `é`, and real Metal pixels.
The Metal checks prove that background changes preserve ligatures while selection,
styles and cursor positions reshape them; they also cover a 513-character run, cache
reuse, fractional metrics, color emoji, images, and font/visibility resets. A real
isolated window presents output on its first show and again after being hidden.
The additional window checks cover continuous resize, retained frames, occlusion,
visibility reporting, window-size requests and existing navigation interactions.

## Browser GPU integration

`BrowserTerminalFontSet` owns a self-hosted HarfBuzz runtime, immutable font sources
and uniquely named CSS FontFaces for measurement, IME and software fallback.
Loading/replacement is atomic; stale and failed requests dispose their resources.
`BrowserTerminalShaper` resolves a face for the entire grapheme using the pinned
Unicode Emoji_Presentation table and explicit selectors, then regular-style and
missing-glyph fallback. Its resolution cache is limited to 512 entries. Raw 26.6
advances and offsets round separately, as in original Ghostty, before the common
cluster-to-grid mapper runs. Font-size/scale/family changes, hiding, context loss
and disposal invalidate face IDs together with the atlas.

The rasterizer draws tight individual outlines, COLRv0 palette layers or embedded color PNGs
through the shared bounded decoder. It records bearings relative to the primary
font baseline; physical atlas pixels remain unscaled during WebGL sampling.
`scripts/verify-terminal-shaping.mjs` loads original hash-verified fonts and checks
the active WebGL surface at DPR 1, 1.25 and 2: repeated glyphs are pixel-identical,
`F`, `g` and `é` match an independent full-canvas CPU outline reference within two
color levels, and color emoji reach the framebuffer. The test also checks VS15/16,
background/style/cursor run boundaries, failed and superseded loads, font size,
hide/restore, context loss/recovery, clearing and view disposal. No test may pass
by silently switching to the software painter.

The demo now installs seven local, unmodified faces by default: four JetBrains
Mono Nerd Font styles, color/text Noto Emoji, and Noto Sans Mono CJK SC. The
generator pins source commits and licenses; fetches enforce hashes through SRI
and use content-hash URLs. Missing/corrupt assets preserve the CSS/system-font
path, which can also be selected explicitly with `?fonts=system`.
`verify-browser-demo-fonts.mjs` proves actual style/CJK/emoji face selection, pointer
selection breaking/restoring a ligature run, a complete 513-character run with
bounded atlas entries, cleanup, opt-out and missing/corrupt asset handling.
Gesture and grid verifiers use the measured font metrics instead of assuming a
host system font.
Validation with the bundled set includes the general Canvas/WebGL integration,
both platform modifier policies for selection/links/hover, block artwork and
graphics layers/resource cleanup. The isolated live PTY run passes Vim, Neovim,
less and tmux, including Unicode input, mouse targeting and resizing. The repeated
glyph pixel test separately covers both bundled and system fonts at DPR 1/1.25/2.

## Glyph fitting

The shaped Metal and WebGL paths now apply Ghostty 1.4's glyph constraints before
rasterization. Symbol glyphs shrink to their available span; emoji faces use centered
cover sizing with 2.5% horizontal padding. Generated Nerd Font rules retain group
geometry, padding, aspect limits, icon heights and stretch alignment to the grid.
A symbol can use a following blank unless the previous non-graphics symbol prevents
it. The available span and constraint participate in atlas identity, so inserting a
neighbor invalidates the fitted glyph without reusing the wider bitmap. Ordinary
text retains its existing bearings and pixel rasterization.

`scripts/generate-terminal-glyph-constraints.py` executes the pinned original
`font/Glyph.zig`, Nerd Font lookup and `renderer/cell.zig` width selection. Its
manifest records upstream source hashes, generator/helper and build template hashes.
The generated lookup covers every Unicode codepoint using 459 ranges and 276 distinct
constraints. Common tests compare 2,250 geometry cases and 759 terminal rows with
the original output, including fractional metrics, negative padding, scale groups,
wide-font stretch limits, neighboring symbols, graphics, blanks and wide spacers.
Run with `--check` to rebuild the oracle and verify byte-identical generated files.
Native tests also compare a fitted atlas glyph with an independent direct CoreText
transform, alongside the unchanged `F`, `g` and `é` pixel references.
The WebGL verifier compares a fitted color emoji with an independent full-cell PNG
reference at DPR 1/1.25/2. Both the intermediate PNG canvas and the atlas rasterizer
use the CPU Canvas path to keep downsampling consistent before texture upload.

Still open: the remaining color-font format audit, especially COLRv1/SVG and non-PNG sbix coverage.
The configured browser rasterizer consumes outlines, COLRv0 layers and embedded PNGs;
the CSS-only and software paths do not apply these shaped-atlas constraints.

## Color font formats

The shaped browser path reads COLRv0 layers and default CPAL palette colors through
HarfBuzz. It paints outlines in layer order with unpremultiplied palette alpha before
converting the result to the GPU's premultiplied pixels. Palette index `0xffff` uses
the cell's effective foreground; only those glyphs include foreground in their atlas
key. Fixed palette colors, including gray, stay untinted. Embedded color PNGs also
stay untinted when their artwork happens to be gray. Layer metadata exported from
WASM is copied, limited to 4,096 layers, and disposed handles reject access. The
browser bounds the foreground-dependence cache to 512 entries and the combined
outline data processed for one rasterization to 1,048,576 floats.

`scripts/generate-terminal-color-fonts.py` creates original rectangle artwork as a
1,768-byte COLRv0 font, with a pinned FontTools build dependency and deterministic
timestamps. The manifest contains the independent geometry, palettes and hashes.
`verify-terminal-color-fonts.mjs` checks the runtime's layer data and actual WebGL
pixels against those rectangles at DPR 1/1.25/2. Cases cover translucent overlays,
fixed gray, foreground-only layers, an empty monochrome base glyph, ordinary text,
SGR replacement and cache reuse, font-size changes, hiding and restoration. Native
Canvas `fillText` is checked against the same color rectangles; its monochrome
hinting is deliberately outside that comparison. The observed maximum color-channel
difference is one level. This exercises the native-browser drawing API used by
Ghostty's `web_canvas` backend.

The independent original-Ghostty probe records a narrower result for its desktop
backends on this particular fixture. FreeType identifies A–E as color glyphs, but
the original `Face.renderGlyph` returns `WrongAtlas` for A–D and an empty bitmap for
E. Its outline path converts the rendered bitmap to gray before checking the atlas
format. CoreText classifies these COLRv0 glyphs as text and paints their monochrome
fallback outlines; its color classifier checks sbix/SVG, not COLR. These are evidence
about this fixture and pinned revision, not a claim that all color fonts behave the
same way. No Ghostty source is patched to obtain these results. See
`test-upstream/color-fonts/ghostty-{freetype,coretext}.json` for the original pixels,
errors, backend/toolchain metadata and source hashes.

Reproduce the artwork and desktop probes:

```bash
python3 -m venv build/color-font-tools
build/color-font-tools/bin/pip install --require-hashes --only-binary=:all: -r scripts/terminal-color-fonts-requirements.txt
build/color-font-tools/bin/python scripts/generate-terminal-color-fonts.py --check
python3 scripts/generate-terminal-color-font-oracle.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig --check
python3 scripts/generate-terminal-color-font-oracle.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig --backend coretext --check
```

PNG sbix coverage uses a second original fixture with 20/40-pixel strikes, distinct
gray levels to identify the selected strike, translucent colored pixels, a `dupe`
reference and negative image offsets. Its fallback outlines match the image bounds
because CoreText uses those outlines to measure the glyph. The generator writes the
font, PNG bytes/pixels, source manifest and embedded Native fixture deterministically.
The browser runtime returns the exact authored PNG at sizes below, between and above
the available strikes. `verify-terminal-sbix.mjs` checks actual WebGL pixels at font
sizes 20/30 and DPR 1/1.25/2 against an independent CPU reference with local image
sampling, integer placement and explicit premultiplied source-over. All pixels match
exactly; changing SGR leaves both gray and colored PNGs unchanged.

The Native rasterizer now treats sbix as intrinsic color even when every pixel is
gray, following Ghostty's CoreText table classification. Previously those pixels
were classified as a text mask and tinted by the terminal foreground. A real Metal
test reproduces that failure before the fix and proves unchanged pixels after SGR
replacement and `dupe` substitution. Additional Native checks cover complete image
bounds, fractional bearings and gray strike values at font sizes 20, 30 and 40.

Original Ghostty FreeType and CoreText probes each record 18 sbix cases. The raw
FreeType face chooses its largest bitmap strike, while CoreText selects 20 or 40
according to size. These probes use the face's own metrics without the terminal's
emoji constraints; they are not cross-backend pixel-equivalence assertions. Rebuild
them with the commands above plus `--font sbix`; their output is recorded in
`test-upstream/color-fonts/ghostty-sbix-{freetype,coretext}.json`.

JPEG/TIFF use a separate 6,932-byte original sbix fixture generated by
`scripts/generate-terminal-sbix-media.py`. It contains baseline grayscale JPEG,
uncompressed RGB TIFF, a JPEG `dupe`, negative TIFF offsets and distinct 20/40-pixel
strikes. The image encoders are deterministic Python code with no external codec
dependency. The browser independently decodes every JPEG and checks every pixel;
CoreText decodes both formats through the real font path. Native tests check
authored center colors, strike selection at 20/30/40 points, `dupe` equality,
offsets, Metal output and foreground changes. All seven focused sbix/SVG tests pass.

The original Ghostty CoreText probe renders JPEG/TIFF; its FreeType probe reports
`UnknownFileFormat` for A–D at all three sizes. Each backend records 18 cases in
`ghostty-sbix-media-{coretext,freetype}.json`. Original CoreText stores color glyphs
in Display P3, whereas our current bitmap context uses DeviceRGB, so these tests
compare each path's recorded results rather than asserting identical RGB bytes
across color spaces. For example, the authored TIFF blue `[32,64,240]` becomes
`[40,63,231]` in the original P3 atlas. Reproduce the fixture with the pinned
FontTools environment and `--check`, and run the desktop commands with
`--font sbix-media --check`. All eight desktop files regenerate byte-for-byte.

The browser regression is now fixed: shaped WebGL uses the browser's native font
renderer for JPEG/TIFF glyph images while retaining HarfBuzz's selected glyph IDs
and terminal-grid positioning. A private SFNT face maps supplementary private-use
scalars directly to glyph IDs and removes substitution/positioning tables, so
Canvas does not shape the result again. The original font bytes and the original
shaping face remain unchanged. A second fixture uses `ccmp` to select a blue
glyph without a Unicode mapping; applying `ccmp` again would incorrectly turn it
gray. Both Node and actual WebGL checks prove the selected glyph stays blue.

`scripts/probe-terminal-sbix-media.mjs --require-parity --require-native` passes on
Chromium 149.0.7827.55 on macOS. At 20px / DPR 1, native Canvas and WebGL both have
367 gray / 140 blue pixels; before the fix WebGL had only 548 foreground-green
pixels. Full pixel comparisons at font sizes 20/30 and DPR 1/1.25/2 have a maximum
channel difference of zero. The checks also cover `dupe`, negative offsets, SGR
changes, simulated unsupported-codec fallback, intrinsic white artwork, font-set
replacement, rejected loads and disposal during a pending private-face load.
Codec availability follows the browser: if it paints a monochrome fallback,
comparison of two fills preserves that glyph as a tintable text mask.

The private table reader validates ranges, resolves `dupe` chains once, rejects
cycles as image sources and bounds strike/table counts. Private font data is
limited to 32 MiB per face and 64 MiB per font set, in addition to the existing
HarfBuzz budget; browser/OS decoder allocations are not an RSS guarantee. Private
CSS faces are removed with their owning font set. Raster probes use the existing
atlas dimension limit and retain no decoded-image cache. Run
`scripts/verify-terminal-native-color-font.mjs` for checksum, glyph identity,
table-copy, Unicode-boundary, malformed-input and layout-removal checks.
The Release JS suite and existing COLRv0, PNG sbix and default-font browser
regressions pass. The fixture generator reproduces both fonts byte-for-byte;
the original desktop fixture and its recorded pixels are unchanged. The formats and tags follow the
[OpenType sbix specification](https://learn.microsoft.com/en-us/typography/opentype/spec/sbix).

SVG coverage uses a third original fixture with separate gray and gzip-compressed
gradient documents, with an ordinary monochrome glyph between their glyph IDs.
The Native shaper caches SVG membership per glyph, following Ghostty's CoreText
classification, rather than applying emoji fitting to every glyph in a color font.
The rasterizer preserves intrinsic SVG colors even when all channels are gray.
The table reader retains only validated, ordered glyph ranges; document offsets,
lengths, truncation and overlapping ranges are checked without copying XML data.
CoreText remains responsible for document rendering and decompression.

Offscreen Metal regressions reproduce both previous failures: the ordinary 4×4
outline was enlarged to 144 ink pixels, and gray SVG artwork was tinted by SGR.
They now preserve the 16-pixel outline and unchanged gray pixels across foreground
changes, and verify that the compressed gradient reaches the GPU in color.
Validation passes 1,440 JVM tests, the fresh Release JS suite and 21 focused Native
tests. The original CoreText probe classifies only A/C as SVG color glyphs and
renders both documents at 20/30/40 points. The original FreeType probe renders
monochrome fallback outlines, consistent with its disabled SVG loading. Reproduce
the 18 cases per backend with the commands above plus `--font svg`; results are in
`test-upstream/color-fonts/ghostty-svg-{freetype,coretext}.json`. All three font
fixtures and six desktop probe files regenerate byte-for-byte.

Browser SVG rendering and COLRv1 remain open. The SVG Native checks do not
establish browser support for embedded SVG documents.

## Original reference

`scripts/generate-terminal-shaping.py` builds and runs the unmodified Ghostty 1.4.0
`font/shaper/run.zig` and FreeType/HarfBuzz or CoreText shaper. It exports the pinned commit to
`build/terminal-shaping-oracle[-coretext]/source`, adds a test entry point and replaces only
the snapshot's build entry point. The reference checkout is checked for tracked
changes before export. No application window is created and no system font
discovery is configured.

The HarfBuzz corpus has 77 scenarios, 195 shaping runs and 1147 glyphs. The separate
CoreText corpus has 77 scenarios, 191 runs and 1146 glyphs, with the macOS version
recorded in its manifest. These cover programming
operators, protected letter pairs, foreground/background and decoration changes,
cursor and selection boundaries, wrapped wide cells, sprites, graphics placeholders,
missing glyphs, stacked marks, Arabic, emoji presentation and mode 2027.
For example, with mode 2027 disabled, `👍🏽` occupies four terminal cells but shapes
to one glyph; selecting its last two cells or moving the cursor there splits the
sequence into separate glyphs. With mode 2027 enabled, the same sequence is one
two-cell grapheme. Marking glyphs also exercise nonzero horizontal and vertical
offsets, including negative offsets.

The source's embedded JetBrains Mono, Inconsolata, Monaspace Neon and Kawkab Mono
fonts supply four primary-font configurations. Each also has the original embedded
Noto Color Emoji and Noto Emoji text fallback faces. CoreText uses only the embedded
Noto Emoji text fallback, since it cannot load the CBDT color face. The same font data is registered
for each style: style cases test run boundaries, not the appearance of bold or italic
faces. Font size is 12 points with upstream defaults. The generator also embeds the
exact JetBrains Mono bytes in a Native-only test fixture. It copies the upstream font
attribution and OFL license to `kinetica-terminal/test-upstream/ghostty/fonts`. Native
tests create the font directly from those bytes without installing or registering it.
Other fonts stay in the upstream source used to generate the numeric reference corpora.

Generate and verify with:

```sh
python3 scripts/generate-terminal-shaping.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig
python3 scripts/generate-terminal-shaping.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig --check
python3 scripts/generate-terminal-shaping.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig --backend coretext
python3 scripts/generate-terminal-shaping.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig --backend coretext --check
```

`kinetica-terminal/test-upstream/ghostty/shaping.json` records each input and original
result. `shaping-manifest.json` records the commit, toolchain, helper, generator,
fixture, source, dependency-pin, test-artifact and font hashes. The CoreText variant
uses the `shaping-coretext` prefix. `--check` rebuilds and executes
the oracle and requires byte-identical fixtures and provenance. The Zig test uses
the testing allocator, including its leak checks. Generator assertions also check
real ligature substitution, background/foreground boundaries, empty-tail handling,
long-run coverage and valid run-relative cluster positions.
The build selects the OpenGL backend and removes the unused Metal shader import
and build dependency added unconditionally by upstream's macOS build helper. It
does not call a renderer or require the optional Metal compiler. The original
terminal, font resolver, run iterator and shaper source files are unchanged.

Each result contains:

| Field | Meaning |
| --- | --- |
| `cell_width`, `cell_height` | Original font grid metrics in pixels |
| `runs[].offset`, `cells` | Starting terminal column and number of covered cells |
| `font_index` | Original collection's font/style identity for the run |
| `input[].codepoint`, `cluster` | Codepoints sent to the shaper and their run-relative terminal columns |
| `glyphs[].glyph_index` | Actual glyph index in the selected font |
| `glyphs[].x` | Run-relative terminal column used to position the glyph |
| `glyphs[].x_offset`, `y_offset` | Original additional glyph offsets in pixels |
| `raw[]` | Unmodified HarfBuzz glyph IDs, codepoint indices, offsets and advances in 26.6 units; empty for sprites and the CoreText corpus |

The absolute horizontal glyph position is `(run.offset + glyph.x) * cell_width +
glyph.x_offset`. A contextual ligature can substitute several glyphs while keeping
their count unchanged; a smaller glyph count alone is not an adequate test.
Selection input uses Ghostty's inclusive column bounds. Cursor input is an explicit
run-iterator option, independent of the terminal's current cursor visibility.

Shared JVM/JS/Native tests map the raw HarfBuzz output to the original final columns
and offsets. Native tests compare actual CoreText glyphs and offsets with original
CoreText results for the embedded JetBrains Mono primary face, and exercise system
emoji fallback across multiple terminal cells. CoreText and HarfBuzz have real
differences: for example, the combining overlay after `x` in this font has a one-pixel
different vertical offset. Native assertions use the corresponding CoreText reference
without a positional tolerance.

Validation for this layer: 1424 JVM tests, fresh Release JS tests and five focused
macOS tests pass. Both original corpora, generated Kotlin fixtures, embedded test
font and license copies regenerate byte-for-byte. All previously recorded HarfBuzz
run/glyph expectations remain unchanged; the corpus adds raw shaping data.

## Implementation requirements

- Preserve terminal-cell origins through shaping, including wide cells, combining
  marks, emoji sequences, missing-glyph replacement and fallback-font changes.
- Match original selection, cursor, comparable-style and protected `fi`/`fl`/`st`
  boundaries. A background-only change can remain in one shaping run. Hover
  decorations must remain separate from stored font styles.
- Keep shaping context when producing bounded atlas entries for long runs. Splitting
  the text arbitrarily to fit a texture changes contextual substitutions.
- Retain shared row damage, glyph reuse, pixel snapping, color glyphs and geometric
  block rendering. Validate real glyph pixels on both platforms in addition to
  matching shared run metadata.
- Keep all shaping and font dependencies in the standalone terminal/platform layers.

An isolated probe of installed Chromium 149 found only the standard Canvas
`TextMetrics` fields, with no glyph-index or cluster-mapping API exposed. Passing a
whole string to `fillText` alone cannot provide the required terminal-grid mapping.
The original FreeType/HarfBuzz and CoreText corpora supply shaping evidence. Native and configured browser
GPU integration now have real pixel coverage. The broader browser gates listed
above remain open.

The [browser HarfBuzz runtime](../kinetica-terminal/web/harfbuzz/README.md) supplies
a reproducible full HarfBuzz 11.0.0 WASM build with explicit font ownership, bounded
memory, raw glyph positions, outline commands and color PNG extraction. Node and
Chromium both match all 194 original font runs / 1146 glyphs, including fallback
mark positioning that the probed stock harfbuzzjs build omitted. The runtime is connected
to the active WebGL renderer through the explicitly loaded font set described above.
