# Upstream terminal test corpus

The clipboard corpus executes the original `ScreenFormatter` for 198 cases / 3744
selections / 59904 formatted outputs, with/without trimming and mappings: plain text, VT,
HTML with the theme and HTML without the theme (mixed copy). Both engines receive the same
explicit palette; the original runner asserts the palette setup before each case. It covers overlapping
Unicode ranges, expansions, empty replacements, non-recursive replacement, graphemes/emoji,
written versus synthetic whitespace, tabs, style flags, indexed/RGB/underline colors, theme
changes, OSC 8 links, linked blank cells, background-only rows, wrap boundaries and rectangles.
Plain data is compared after whole-stream and bytewise input; rich data is compared after
whole-stream input on each shared-engine target. Exact rich byte comparisons use small screens;
they do not assert Ghostty's internal page framing for long history. A separate 600-row test
checks VT replay's text/styles/links and retained copies across a synchronized-output hold.
Regenerate with `python3 scripts/generate-terminal-copy.py /path/to/ghostty /path/to/zig`;
`ghostty/copy-manifest.json` records the source pin and hashes.

The semantic-selection corpus also compares plain clipboard text with trimming enabled and
disabled, through original `Screen.selectionString`/`ScreenFormatter`. Its 73 cases / 94
snapshots cover select-all, word/line/output selection, pointer gestures and rectangles,
including written spaces, tabs, Unicode spaces, combining spaces, wrap boundaries and history.
Regenerate with `python3 scripts/generate-terminal-semantic-line.py /path/to/ghostty /path/to/zig`;
`ghostty/semantic-line-manifest.json` records the pin, policies and source/artifact hashes.

The paste corpus executes original libghostty-vt `encodePaste`, `isSafePaste` and
`isSafePasteWith`: 273 cases cover all byte-valued characters, CR/LF combinations,
Unicode, bracket delimiters/near misses, empty input and larger text. It records 546
encodings and 819 safety results. Shared tests compare exact output bytes and both
host-policy settings; dialog lifecycle and real platform gestures have separate tests.
Regenerate with `python3 scripts/generate-terminal-paste.py /path/to/ghostty /path/to/zig`;
`ghostty/paste-manifest.json` records the pinned revision and source/artifact hashes.

The XTGETTCAP corpus enumerates the original Ghostty terminfo map (272 static capabilities)
and replays 309 original-handler cases. Generated runtime replies preserve boolean, numeric,
literal control and parameterized terminfo representations exactly. Cases cover mixed-case
hex, multiple/malformed keys, DCS headers/termination, the 1 MiB capture boundary and configured
TN names, including UTF-8 byte limits. Regenerate with
`python3 scripts/generate-terminal-terminfo.py /path/to/ghostty /path/to/zig`;
`ghostty/terminfo-manifest.json` records source and artifact hashes. The helper enumerates
byte-identical terminfo source copies because Zig prohibits sharing one physical source file
between two modules; request execution uses the unmodified Ghostty module itself.

The [image decoder corpus](images/README.md) separately compares common PNG decoding
with pinned libpng and validates zlib streams against pinned zlib. It does not yet
cover original-libghostty image storage, placements or platform image rendering.

The animation corpus links the unmodified pinned Ghostty Zig module and executes
414 command/clock scenarios with 2058 snapshots. It compares all frame pixels, gaps, playback
state, loop counters, timestamps, byte counts and image-generation changes. The alpha cases
exercise all 65,536 source/destination alpha pairs. The adapter replays both the isolated core
and complete/bytewise session input, including explicit clock ticks and storage accounting.
Compressed RGB/RGBA frame cases compare exact data, tolerated excess bytes, short data,
checksum failure, truncation, trailing bytes and concatenated zlib streams with Ghostty.
Regenerate with `python3 scripts/generate-terminal-graphics-animation.py /path/to/ghostty
/path/to/zig`; hashes and source pin are in `ghostty/graphics-animation-manifest.json`.
The Wuffs integer source-over arithmetic retains its MIT license and provenance under
`third-party/wuffs.json`; its runtime notice is included with the other packaged licenses.

The full graphics execution corpus additionally covers 244 scenarios and 2394 checkpoints,
including 48 animation scenarios for chunking, replies/quiet modes, generation changes, memory
pressure, deletion, screen transitions and RGB/RGBA/PNG with optional zlib compression.
`scripts/terminal-ghostty-graphics-oracle.c` wraps the existing C oracle and installs the
libghostty PNG host callback using independent libpng 1.6.59; the base oracle's no-decoder
configuration is unchanged. Regenerate with `python3 scripts/generate-terminal-graphics-execution.py
/path/to/ghostty`; `pkg-config` must locate the pinned libpng. The manifest records both helper
hashes and the decoder version. These deterministic protocol oracles do not establish automatic
host timer scheduling; actual rendered-pixel checks live in the browser and Native verifiers.

Alacritty reference recordings and expected grids are pinned by commit and SHA-256
in `alacritty/manifest.json`. `references.tar.gz` preserves the selected upstream
files verbatim with deterministic archive metadata. The Apache-2.0 license is
included. Generated Kotlin data runs the same corpus on JVM, JS and macOS Native.

Regenerate with `python3 scripts/import-terminal-fixtures.py /path/to/alacritty`
at the pinned commit. Expectations must never be regenerated from Kinetica output.

The adapter removes allocation-only metadata, orders rows oldest/top first, maps
wide spacers to zero-width cells, maps tab markers to visually blank cells, and
normalizes generated hyperlink IDs (explicit IDs and URIs are preserved). It
compares retained history padded with blank rows to the configured limit (as
Alacritty's `initialize_all` does), screen cells, colors, styles, combining text, underline
colors, hyperlinks, and wrap markers. Tab copy semantics need separate tests.
Alacritty's source fixtures omit cursors, modes and replies; this suite alone does
not establish VT conformance. Whole-stream and deterministic chunked replays use
the same upstream golden expectations.

The link-hover corpus calls the original `RenderState.linkCells` after real OSC 8 input.
Its 32 cases / 44 checkpoints / 2152 hover positions cover implicit/reopened and explicit
identities, empty/repeated/malformed ID options, invalid close commands, disjoint rows,
wide/combining cells, edits, alternate screens, reflow and scrollback. Common tests replay
whole and bytewise input, then compare every visible highlighted cell. Separate painter
checks cover Ghostty's single/double hover underline rule, hidden text, custom underline
colors, detected-link ranges, frozen views and immediate host policy revocation.
Regenerate with `python3 scripts/generate-terminal-link-hover.py /path/to/ghostty /path/to/zig`;
`ghostty/link-hover-manifest.json` records source, generator, helper and fixture hashes.

## Explicit behavior differences

- `hyperlinks`: Ghostty rejects an OSC 8 close command carrying a nonempty `id` and
  retains the current link; Alacritty closes it. The exact archived recording is also run
  through original Ghostty as `alacritty_hyperlinks`. Its complete grid, modes and replies
  are checked whole and chunked; every ID string in Alacritty's original linked cells is
  additionally preserved as an assertion. The source recording and grid remain unchanged.

- `underline`, row 7, columns 0–9: `SGR 4:3;21` selects double underline in
  Kinetica, following Ghostty `src/terminal/sgr.zig` at commit
  `4da7523faba68ccb4042ea20585817098a51c015` (case 21) and xterm. The Alacritty
  fixture retains undercurl. The adapter asserts the exact original value before
  applying this narrowly located policy expectation. The archived grid is unchanged.
- `selective_erasure`, rows 0–1, columns 0 and 2: Ghostty's DECSCA and selective
  erase clear the unprotected `A`/`C`, retaining protected `B`. The Alacritty fixture
  retains all three characters. The adapter asserts the original characters before
  applying the four-cell difference; `protected_alacritty_recording` also replays
  the same bytes against the original Ghostty, including each cell's protection bit.
- `erase_in_line`: Ghostty's EL clears pending wrap and erases the rightmost cell;
  Alacritty preserves pending wrap, so the following output occupies different rows.
  This recording uses the complete grid generated by original Ghostty as its explicit
  policy expectation. The adapter verifies identical input bytes, dimensions and history
  limit before using it; whole and chunked replays still compare every cell and wrap.
  The original Alacritty input and expected grid remain archived and unmodified.
- `decaln_reset`: Ghostty preserves foreground/background colors during DECALN;
  Alacritty resets them. The adapter uses the original Ghostty grid for this recording,
  verifies identical archived input/dimensions/history, and compares every cell and wrap.
  Neither the Alacritty source fixture nor Ghostty's generated expectations are rewritten.
- `saved_cursor` and `saved_cursor_alt`: Ghostty 1.4's DEC Special Graphics table preserves
  underscore (`U+005F`); Alacritty maps it to space. Both exact archived recordings also run
  through original Ghostty. Their replay adapter verifies identical bytes/dimensions/history
  and compares the complete original-Ghostty grid, including every cell and wrap. The
  Alacritty recordings and expected grids remain unchanged.

Passing this corpus includes the above documented policy comparison; it must not
be described as byte-for-byte agreement with every Alacritty behavior.
