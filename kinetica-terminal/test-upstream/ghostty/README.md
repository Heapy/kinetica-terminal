# Ghostty differential oracle

Pinned source: `4da7523faba68ccb4042ea20585817098a51c015` from
<https://github.com/ghostty-org/ghostty>. The MIT license is in
`../../third-party/GHOSTTY-LICENSE`.

`scenarios.json` is the input specification. The C helper in
`scripts/terminal-ghostty-oracle.c` runs the original `libghostty-vt` through its
public API. After every write/resize/key batch it exports logical cells, wraps, cursor,
pending wrap, screen selection, scrollback count, title, modes, effective colors,
palette changes, and PTY replies. Both engines receive the same host color defaults;
the generator records that configuration in the manifest. Stored golden snapshots come
entirely from original libghostty. The Neovim replay has the explicit reply-policy exception
described below; its reference transcript is also retained unchanged.
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

Neovim probes some extensions that Kinetica does not implement. Its `live_nvim` scenario
declares `replyPolicy: kinetica-legacy-capabilities`: comparison changes only Ghostty's
DECRQM replies for modes 69 (left/right margins), 2031 (color-scheme notifications) and
2048 (in-band resize) from supported/reset (`2`) to unrecognized (`0`), and removes the
Kitty keyboard query reply `CSI ? 0 u`. This is a documented capability difference, not
Ghostty-equivalent behavior. `TerminalProtocolReportsTest` separately checks these exact
negative answers, ignored enable requests, unchanged pen and legacy key input. The policy
does not alter any cells, modes, other replies, producer bytes or archived golden data.
When implementing one of these extensions, remove its exception and replace the negative
capability test with encoder/host coverage before advertising support.

DECRQSS SGR and scrolling-margin replies are compared byte-for-byte without that exception.
Cases cover every implemented underline style, all color encodings, palette identity,
saved pens, screen switches, reset and resize. SGR formatting follows the pinned original
[`Terminal.printAttributes`](https://github.com/ghostty-org/ghostty/blob/4da7523faba68ccb4042ea20585817098a51c015/src/terminal/Terminal.zig)
and [`dcs.Command.DECRQSS`](https://github.com/ghostty-org/ghostty/blob/4da7523faba68ccb4042ea20585817098a51c015/src/terminal/dcs.zig).
Like that reference, the SGR report omits the separate underline color.

The normalized representation maps empty cells/tab markers to spaces and wide
tails to empty strings. It preserves palette-vs-RGB values in the golden data;
the comparator checks those typed values directly, every palette entry, and the
effective foreground/background/cursor colors at each checkpoint. Automatic hyperlink
IDs, semantic prompts and image metadata need separate coverage. Ghostty's
page-granularity scrollback pruning differs from our
strict line limit; scenarios used for exact history comparison stay below limits.
