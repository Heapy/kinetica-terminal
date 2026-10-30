# Terminal font shaping

Multi-cell shaping remains an open compatibility requirement. `TerminalGpuFrame`
currently stores one terminal grapheme per `GlyphKey`, so neither the Metal nor the
WebGL atlas can form a programming ligature across cells. Software text grouping
already passes some longer strings to the platform, but does not provide Ghostty's
font selection, cluster mapping and alignment to terminal cells.

The shared `TerminalShapingText`/`terminalPositionGlyphs` layer now preserves terminal
origins through platform shaping, including UTF-16 surrogate pairs, reordered marks,
and marks attached to ligatures spanning several cells. `TerminalNativeShaper` uses
CoreText with forced left-to-right embedding, records actual fallback font identities,
and retains those faces until disposal. This layer is tested independently; connection
to the GPU frame and glyph atlas remains open.

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
The original FreeType/HarfBuzz and CoreText corpora supply shaping evidence. Native
GPU integration, browser shaping and actual renderer pixels remain separate gates.
