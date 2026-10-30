# Terminal font shaping

Multi-cell shaping remains an open compatibility requirement. `TerminalGpuFrame`
currently stores one terminal grapheme per `GlyphKey`, so neither the Metal nor the
WebGL atlas can form a programming ligature across cells. Software text grouping
already passes some longer strings to the platform, but does not provide Ghostty's
font selection, cluster mapping and alignment to terminal cells.

## Original reference

`scripts/generate-terminal-shaping.py` builds and runs the unmodified Ghostty 1.4.0
`font/shaper/run.zig` and FreeType/HarfBuzz shaper. It exports the pinned commit to
`build/terminal-shaping-oracle/source`, adds a test entry point and replaces only
the snapshot's build entry point. The reference checkout is checked for tracked
changes before export. No application window is created and no system font
discovery is configured.

The corpus has 77 scenarios, 195 shaping runs and 1147 glyphs. It covers programming
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
Noto Color Emoji and Noto Emoji text fallback faces. The same font data is registered
for each style: style cases test run boundaries, not the appearance of bold or italic
faces. Font size is 12 points with upstream defaults. Original font files and their
licenses stay in the upstream source; the committed fixture contains numeric shaping
results, not font binaries.

Generate and verify with:

```sh
python3 scripts/generate-terminal-shaping.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig
python3 scripts/generate-terminal-shaping.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig --check
```

`kinetica-terminal/test-upstream/ghostty/shaping.json` records each input and original
result. `shaping-manifest.json` records the commit, toolchain, helper, generator,
fixture, source, dependency-pin and font hashes. `--check` rebuilds and executes
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

The absolute horizontal glyph position is `(run.offset + glyph.x) * cell_width +
glyph.x_offset`. A contextual ligature can substitute several glyphs while keeping
their count unchanged; a smaller glyph count alone is not an adequate test.
Selection input uses Ghostty's inclusive column bounds. Cursor input is an explicit
run-iterator option, independent of the terminal's current cursor visibility.

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
The original FreeType/HarfBuzz corpus supplies deterministic shaping evidence;
native CoreText behavior and browser rasterization still require platform checks.
