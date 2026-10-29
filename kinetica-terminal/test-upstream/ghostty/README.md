# Ghostty differential oracle

Pinned source: `4da7523faba68ccb4042ea20585817098a51c015` from
<https://github.com/ghostty-org/ghostty>. The MIT license is in
`../../third-party/GHOSTTY-LICENSE`.

The separate [graphics component corpus](graphics-manifest.json) is generated with
`python3 scripts/generate-terminal-graphics-fixtures.py /path/to/ghostty`. Its 15 cases
and 120 checkpoints compare base-image transfers, query isolation, chunk/quiet inheritance,
IDs/numbers, replacement, storage removal, eviction and limit/reset behavior. The C helper's
`G limit ids...` command enables storage and selects image IDs to probe through the original
public API. Every ID allocated or explicitly used by these scenarios is in the probe list;
this is not general image enumeration (the public API exposes lookup and placement iteration).
It snapshots decoded RGB/RGBA bytes without retaining borrowed handles across mutations.
The common test adapter feeds headers/payloads whole and bytewise and translates removal
events into storage operations. It does not prove placement-aware delete dispatch, animation,
screen lifecycle, session execution or rendering. PNG uses a separate libpng decoder corpus;
this original-library helper has no PNG callback yet. The graphics manifest pins the library,
helper/generator, input compressor and generated fixtures independently of the text VT corpus.

`scenarios.json` is the input specification. The C helper in
`scripts/terminal-ghostty-oracle.c` runs the original `libghostty-vt` through its
public API. After every write/resize/key batch it exports logical cells, wraps, cursor,
pending wrap, screen selection, scrollback count, title, modes, effective colors,
palette changes, and PTY replies. Both engines receive the same host color defaults;
the generator records that configuration in the manifest. Stored golden snapshots come
entirely from original libghostty, including the unmodified Neovim capability replies.
Scenarios with `clipboard: true` attach in-memory host clipboards to both engines. The original
library decodes writes, calls its public clipboard callbacks and encodes read replies; snapshots
retain every callback's location and binary data. Cases cover optional selectors, all destinations,
strict alphabet/padding validation, noncanonical tail bits, binary/Unicode contents, BEL/ST, clearing,
malformed/canceled requests, 150 KB payloads, alternate screens, synchronized output and reset.
Host permission, asynchronous I/O, disposal and resource exhaustion have separate common/platform
tests. The C helper's clipboard callback storage is test-only and never accesses the OS clipboard.
Scenarios with `effects: true` install the original library's desktop-notification and progress
callbacks. Ordered snapshots preserve titles, bodies, literal progress states and omitted
percentages, including malformed ConEmu fallback, reserved commands, UTF-8 byte limits, split
input, alternate screens, render holds and RIS removal. Progress expiry and desktop permission/
delivery are host responsibilities, tested outside the oracle.
Scenarios with `workingDirectory: true` query the original raw PWD value and record every
PWD callback. They cover repeated reports, explicit clearing, reset without callback, screen
switches, render holds, cancellation, raw URI preservation and the 2047-byte UTF-8 payload
boundary. Local URI validation and new-tab directory inheritance are separate host tests.
Scenarios with `semanticPrompts: true` read original per-cell semantic content, per-row prompt
markers and cursor-at-prompt state. Cases cover every OSC 133 action, malformed commands, byte
limits, continuation kinds, soft/hard breaks, wide/grapheme cells, protected erasure, rectangular
margins, screen-local cursor state, saved cursors, reset and all redraw modes during resize.
The C library initializes prompt redraw as disabled; OSC 133 can enable it, and RIS restores
the underlying Terminal default of full redraw. Command option/decoding and viewport navigation
tests are separate, since this C API does not export semantic-command callbacks.
The device-attributes and XTVERSION callbacks also receive a shared, explicit host
identity: primary `1;2` (VT100 with advanced video), secondary `0;0;0`, a zero unit
ID, and the name `Kinetica`. This is configuration supplied to the original
encoder, not substituted expected bytes. Request validation and encoding remain
upstream behavior, including parameter-tolerant DA requests and rejection of
invalid prefixes/intermediates. Private primary-DA replies never become queries.
Cursor appearance uses configured steady-underline defaults on both sides. The helper reads
visual shape through `GhosttyRenderState`; `GHOSTTY_TERMINAL_DATA_CURSOR_STYLE` is the SGR pen
style and is deliberately not used for cursor geometry. Mode 12 and mode 2026 are queried
independently, and the helper records each render-hold callback.
It does not execute a shell or any terminal-requested external action.

Build the pinned checkout with Zig 0.16.0:

```sh
zig build -Demit-lib-vt=true -Doptimize=ReleaseFast -Dsimd=false -Demit-xcframework=false
```

Then from Kinetica:

```sh
python3 scripts/generate-terminal-oracle.py /path/to/ghostty
./kotlin test -m kinetica-terminal -p jvm -p macosArm64
```

Generated common Kotlin fixtures are committed test data; ordinary tests need
neither the upstream checkout, Zig, a C compiler nor a network connection. The
generator never reads Kinetica's results. Both whole-write and bytewise replay
must agree with each intermediate upstream snapshot. `manifest.json` records
source revision, helper/library hashes and snapshot hashes.

The corpus includes 32 resize scenarios: soft/hard breaks, printed spaces versus empty
cells, colored blanks, wide-cell padding and one-column grids, combining text, styles
and links, scrollback, pending wrap, current/saved cursor pins, simultaneous height/width
changes, and both active and inactive alternate screens. Expectations come exclusively
from the original library, including the regression that previously expected cropped text.
Protection scenarios check DEC/ISO modes, selective and ordinary erasure, invalid parameters,
cursor save/restore, reset, alternate screens, reflow, and wide-cell boundaries. Two original
Alacritty recordings also run through Ghostty to pin the documented cross-emulator differences.

Color scenarios exercise stored palette identity versus direct RGB, colored blank erasure,
saved pens, history/reflow, alternate screens, RIS persistence, dynamic defaults, reset lists,
BEL/ST replies, malformed input, supported X11 color formats and all 782 archived X11 names.
The new alternate-screen cases also pin destination-pen erasure, ISO protection and pending wrap.
Named-color input retains the X11 notice in `../../third-party/X11-RGB-LICENSE.txt`.

Synchronization cases cover repeated set/reset, multiple boundaries within one write,
immediate reports, alternate screens, palette changes, RIS and resize (including unchanged
dimensions). Cursor cases cover all DECSCUSR values, defaults, malformed/private forms, mode
12, save/restore, screen switching, and DECRQSS replies. Host timeouts and actual presentation
are tested separately: original libghostty has no internal clock or graphics surface.

Keypad cases also call the original key encoder configured directly from its terminal state.
Eight matrices cover 27 numeric/navigation keys, every Shift/Alt/Ctrl combination and both
NumLock flags, with modes 1/66/1035 independently enabled/disabled: 3,456 encodings in total.
The helper appends those bytes to the outgoing transcript alongside protocol replies; Kinetica
must produce the identical transcript. Separate transition cases pin ESC =/>, mode queries,
cursor save/restore, screen switching and RIS. `keys` events specify the upstream key name,
logical host key, physical keypad identity and modifier bits (Shift=1, Alt=2, Ctrl=4, NumLock=8).
The physical lock flag has no separate Kinetica field: logical navigation comes from the host,
and the original encoder matrices prove both flag states agree under mode 1035's policy.
Locale-specific decimal input, main Return versus keypad Enter, IME ownership, keypad equal/
separator extensions and linefeed-mode behavior have separate common and host integration tests.

Binary-header cases cover C1 recovery inside CSI/ESC/DCS, unassigned high header bytes,
ignored-string boundaries and UTF-8 sequences crossing these transitions. A 15-byte compressed
fragment from `ant-1.10.15.jar` reproduces the Gradle binary-output report; its exact source hash
and offset are recorded in the input scenario. Whole/bytewise playback must match original
Ghostty. A separate common test compares decoded-string input with its UTF-8 representation,
including split surrogate pairs and one damage publication per write.

Mouse cases call the original mouse encoder with options read from its terminal state.
Eight matrices contain 5,120 events across disabled/1000/1002/1003 tracking and legacy/SGR
formats, left/middle/right/back/forward, four wheel directions, Alt/Ctrl combinations,
press/release/motion, viewport boundaries and byte-coordinate limits. The `M` helper command
uses 8×16 cells and positions one pixel inside the requested cell; each event uses a fresh
encoder so these matrices test encoding independently of per-view gesture state. Outgoing
transcripts are compared as hex bytes, including invalid UTF-8 in legacy reports. Shift bypass,
motion deduplication, canceled gestures and native/DOM event mapping are host-policy tests,
not expectations manufactured from Kinetica output. The C helper's eight matrices also pass
AddressSanitizer against the pinned original library.

The [live TUI recordings](../tui/README.md) preserve
producer output and resizes from zsh, Vim, Neovim, less and tmux; their expected states
and replies also come entirely from this oracle. Recordings contain no expected
Kinetica screen. The replay checks their hashes and all retained intermediate
states at resize/interaction boundaries.

Kitty keyboard cases use the original key encoder with the terminal's negotiated flags.
`keyboard-matrix.json` expands into 16,554 input events over all 31 nonzero flag combinations,
press/repeat/release, Shift/Alt/Ctrl/Super/lock modifiers, functional/keypad/modifier keys,
non-Latin alternate keys, supplementary Unicode and text without physical identity.
The matrix configures Option as Alt explicitly on the reference encoder. Negotiation snapshots
cover separate screen stacks, set/add/remove, bounded overflow/underflow, invalid flags and RIS.
The generator hashes the matrix source in the manifest. Platform tests separately exercise
AppKit and DOM routing, IME ownership and local shortcuts; those are not libghostty API inputs.

Neovim capability replies, including mode 69, Kitty keyboard and color-scheme queries,
are compared unchanged. Horizontal-margin scenarios cover negotiation and saved modes,
origin-relative positioning, reports, tabs, carriage return, wrap, index/reverse index,
rectangular scrolling and editing, wide cells, graphemes, protected/styled/link cells,
malformed commands, pending wrap, alternate screens and resize to a one-column grid.

DECRQSS SGR and scrolling-margin replies are compared byte-for-byte.
Cases cover every implemented underline style, all color encodings, palette identity,
saved pens, screen switches, reset and resize. SGR formatting follows the pinned original
[`Terminal.printAttributes`](https://github.com/ghostty-org/ghostty/blob/4da7523faba68ccb4042ea20585817098a51c015/src/terminal/Terminal.zig)
and [`dcs.Command.DECRQSS`](https://github.com/ghostty-org/ghostty/blob/4da7523faba68ccb4042ea20585817098a51c015/src/terminal/dcs.zig).
Like that reference, the SGR report omits the separate underline color.

The normalized representation maps empty cells/tab markers to spaces and wide
tails to empty strings. It preserves palette-vs-RGB values in the golden data;
the comparator checks those typed values directly, every palette entry, and the
effective foreground/background/cursor colors at each checkpoint. Automatic hyperlink
IDs and image metadata need separate coverage. Semantic markers are compared in the explicitly
enabled scenarios described above. Ghostty's
page-granularity scrollback pruning differs from our strict line limit. The
`scroll_clear_bounded`, `scroll_clear_no_history` and `scroll_clear_alternate` scenarios
declare `historyPolicy: strict-limit`: the comparator caps retained history at the configured
line limit (zero on alternate screens), removes only the excess oldest oracle rows, and
compares all remaining cells, cursor state and replies exactly. Original snapshots retain
the upstream rows unchanged. In particular, libghostty's scroll-and-clear can retain rows
even with zero configured scrollback. Other scenarios compare the exact history count.

The modifyOtherKeys matrices cover 3,249 events in numeric, application-cursor and
application-keypad modes. Transition cases test enabling/disabling, malformed requests,
Kitty precedence and RIS. Color-scheme cases configure the original library's callback;
host changes use the original report encoder gated by mode 2031, mirroring Ghostty's
host behavior. Cases cover both schemes, OSC overrides, repeated updates, alternate screens,
synchronized output and RIS. The helper starts with a configured dark scheme.

Size-report cases install the original library's geometry callback, initially unavailable.
Resize events commit per-cell pixels (8×16 by default, or an explicit `cellSize` pair).
Mode 2048 and CSI 14/16/18 t use these committed values. The cases cover enable/reset,
repeated and pixel-only resizes, alternate screens, saved modes, synchronized output,
unknown/zero geometry, malformed requests and multiplication beyond 32 bits. Native
and browser tests verify physical pixels at display scale and actual font changes.

REP cases include wide characters, combining text, graphemes, charset switches, insert mode,
wrap boundaries and resets. Scroll-and-clear covers blank/colored/protected rows, cursor
positions and margins. Saved-mode cases check repeated saves/restores, defaults, side effects
and subscriptions. Two X10 matrices add 1,280 mouse events; transition cases distinguish
independent mode bits from the active encoding policy, including saved/restored modes.

Kitty color cases cover all 256 palette entries, configured foreground/background/cursor
colors, mixed xterm/Kitty commands, set/query/reset ordering, unknown special colors,
malformed entries, both terminators, 2048-byte payload and 528-operation boundaries,
alternate screens, history and synchronized output. Host-specific defaults and actual GPU
recoloring have separate tests. Pointer shape is captured through the original library's
`GHOSTTY_TERMINAL_DATA_MOUSE_SHAPE` API at every checkpoint; OSC 22 cases cover all W3C
names and aliases, malformed/canceled/incomplete commands, screen switches and RIS.
