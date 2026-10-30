# Ghostty differential oracle

## Default URL/path matcher oracle

`links-manifest.json` pins the unmodified `src/config/url.zig` expression and Ghostty's
Oniguruma 6.9.9 package. The helper compiles an unchanged copy of the expression and links the
original package; its results contain every non-overlapping match, retaining original-string
lookbehind context. The Python generator converts byte offsets to Kotlin UTF-16 offsets.

```sh
python3 scripts/generate-terminal-links.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig
bash scripts/engine.sh test -m kinetica-terminal -p jvm --include-classes='*TerminalLinkMatcherTest'
```

The corpus includes all original positive/negative inputs, scheme/path/punctuation combinations,
Latin-1 and supplementary Unicode cases, and deterministic token fuzzing: 6018 inputs / 5069
matches. Three anchored original expressions classify every Unicode scalar for `\w`, `[\w]`
and `\d`. The first two differ in Oniguruma; sharing one platform Unicode predicate is incorrect.
The generated tables, fixture, helpers, expression, dependency pin, relevant C sources and license
have recorded hashes. The BSD notice is retained in source and packaged in `META-INF/LICENSES`.
Generation reproduces fixtures, tables, manifest and notices byte-for-byte.

Oniguruma is built in `fast` mode and the Zig helper in `safe` mode. The initial 3021-input
experiment produced byte-identical records in both modes, but the C `safe` build trapped during
`onig_end` after producing all results. The checked-in generator requires a successful exit and
uses the release-mode C build; it does not accept the partial output from a failed process.
The Kotlin runtime contains the portable scanner and generated character tables, not Oniguruma.

These tests establish matching and offset behavior, not path resolution or external application
launching. Common/native/browser tests separately exercise link lookup and double-click copying.

## Line/word selection and semantic link-boundary oracle

`semantic-line-manifest.json` pins original `Screen.selectLine` with the same options used by
`Surface.linkAtPin`: no whitespace trimming and semantic boundaries enabled. Regenerate with:

```sh
python3 scripts/generate-terminal-semantic-line.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig
bash scripts/engine.sh test -m kinetica-terminal -p jvm --include-classes='*TerminalSemanticLineTest'
```

The 45 scenarios / 66 checkpoints compare 2787 untrimmed cell ranges, the corresponding
trimmed line/word selections and 2970 gesture steps for each selection mode, replayed both
whole and bytewise.
They cover every ordering of prompt/input/output, mid-row and soft-wrap boundaries, history,
resize/reflow, wide cells, combining characters, wrap spacers, blank seams, erasure, input-until-EOL,
alternate screens, resets and explicit prompt redraw modes (`0`, `1`, `last`). Initial prompt
redraw is disabled, matching the original libghostty-vt C initializer and Kinetica's default;
the standalone Zig terminal defaults to enabled. Per-cell semantic content and per-row prompt
markers are compared before ranges, including after resize. Expected ranges come directly from original selections; the helper
only converts inclusive end columns to exclusive ones. The manifest hashes the generator, helper,
fixtures and relevant original sources. Generation reproduces byte-for-byte.

The helper also calls original `Screen.selectLine` with default whitespace trimming and
`SelectionGesture.press`/`drag` with line and word behavior. It records nullable selections and actual
`selectionString(trim = false)` output: blank drag targets retain the preceding selection,
blank origins use untrimmed fallback bounds, interior written spaces survive and no final newline
is synthesized. The test normalizes inclusive wide-glyph endpoint pins to complete display cells.
Gesture sampling covers first/middle/last rows and columns, forward/reverse movement and returns
to the origin. It does not claim all gesture timing, modifiers or host URL regex equivalence.
Word expectations come from original `Screen.selectWord` and `selectWordBetween`, including
blank cells, grouped delimiters, non-ASCII spaces and custom empty/punctuation/wide/combining
code-point sets. The default delimiter file is copied byte-for-byte from the pinned source
into the helper's build directory because Zig disallows importing the same source file into
two modules; its source hash remains in the manifest. No upstream source is modified.
Common URL tests cover actual matching,
frozen frames, explicit OSC 8 precedence and the disabled-OSC-8 fallback; the AppKit Cmd-click
test verifies the bounded target passed to the host callback without opening external applications.

## Native link-policy oracle

`native-links-manifest.json` pins the original macOS `UntrustedURL.swift` from Ghostty
1.4.0 and the detected-path conversion block from `Ghostty.App.swift`. The conversion block
is copied unchanged into a minimal action shim; its dispatch returns the effective target.
The Swift helper only converts/classifies targets and reads their display strings: it never
opens a URL or invokes a handler. Regenerate on macOS with:

```sh
python3 scripts/generate-terminal-native-links.py build/upstream/ghostty-1.4.0
bash scripts/engine.sh test -m kinetica-terminal -p macosArm64 --include-classes='*NativeTerminalLinksTest'
```

The 207 cases compare effective targets, decisions, denial reasons, canonical allowed URLs and normalized,
escaped display strings. Thirty-two isolated resources exercise safe files/directories,
all explicitly blocked extensions, script UTIs, executable bits, symlinks, directory URL
slash hints and a FIFO. Detected paths additionally cover tilde expansion, literal percent
signs, line-number suffixes, relative/absolute spelling and unsafe characters before encoding.
Temporary paths are represented by placeholders, including Foundation's `/tmp` spelling
of `/private/tmp` for relative paths. Tests recreate the same resources and restore their
working directory afterward. The manifest records the unmodified upstream source, helper,
conversion block, generator and fixture hashes plus the Swift toolchain version. Local UTI classification
uses the host's system type registry, as the original policy does.

Native integration tests separately exercise the preceding core resolution step: an existing
lexically resolved path in the terminal's working directory wins before scheme interpretation
or tilde expansion; missing paths fall through to native conversion. Shell variables are not
expanded. These filesystem tests are authored regressions, not output from the original core.

Separate native tests cover deferred confirmation, cancellation, replaced requests,
disposal, policy revocation, dialog defaults and selectable plain target text, sanitized
copying of blocked links, and Cmd-click routing. They inject the URL launcher and do not
open external applications or show modal dialogs.

## Native external-media oracle

`native-media-manifest.json` pins a separate macOS-only Zig oracle that invokes the original
`LoadingImage.init` against real isolated files, temporary files and POSIX shared memory.
Regenerate it on macOS arm64 with:

```sh
python3 scripts/generate-terminal-native-media.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig
bash scripts/engine.sh test -m kinetica-terminal -p macosArm64 --include-classes='*NativeGraphicsMediaTest'
```

The 61 portable setup recipes cover policy, offsets, exact/implicit sizes, invalid names,
canonical paths, custom temporary directories, symlinks, unchanged shared-memory names,
page padding, compressed/PNG byte ranges and cleanup. Fifty-seven compare original bytes,
error names and resource existence. Four file seeks past EOF with omitted size crash the
original Zig safe build; the manifest records those crashes, and the native test verifies
our bounded EOF recovery separately. No generated expectation substitutes our behavior for
an upstream crash. Only the oracle's own temporary resources are created/deleted.

The PNG capability gate is enabled by a callback that traps if called. This oracle ends
before decoding: it proves raw reads, not PNG validity or rendering. Separate native tests
exercise queries, image/frame loads, real PNG decoding and read/decode failure cleanup.
Additional regressions verify descriptor stability, bounded reads and nonblocking FIFO rejection.
The existing C corpora below still disable external media and retain their original scope.

## C and other component oracles

Pinned C reference: Ghostty 1.4.0, `83fb95624b0439ee660c2679b43b38c8b63769cd`, from
<https://github.com/ghostty-org/ghostty>. The MIT license is in
`../../third-party/GHOSTTY-LICENSE`.

The separate Zig animation, Unicode-placeholder, visibility and program-status corpora use the same
1.4.0 revision with Zig 0.17.0. Their manifests preserve source and generator hashes.

The [animation corpus](graphics-animation-manifest.json) contains 446 scenarios / 2186
checkpoints, replayed through the isolated animation core and whole/bytewise session input.
Its RGB-source cases cover opaque source-over/overwrite, transparent/partial/opaque destination
alpha, RGB-root conversion, appended and edited frames, clipping and uint32 offsets outside
the image. The expected pixels and error-side state come from the original executor; allocation
guards are separate Kotlin tests because our temporary-buffer strategy intentionally differs.
Regenerate with `python3 scripts/generate-terminal-graphics-animation.py /path/to/ghostty /path/to/zig`.

The [graphics accounting corpus](graphics-accounting-manifest.json) contains 27 scenarios /
197 checkpoints from the original `TerminalStream` and `ImageStorage`. It reads the actual
resident-byte counter, nominal limit, pending-transfer state, image/placement counts and
every stored frame's pixels. RGB-root conversion is observed before failed append admission
and during composition, including the resulting over-limit resident state. Subsequent edits,
same-limit reapplication, changed limits, eviction, incomplete APC/transfer payloads and
screen changes are compared whole and bytewise. Host-limit events use the same `setLimit`
operation on every existing screen as the original C API. No expected counter is computed
from Kinetica's output or inferred from only the displayed frame.
Regenerate with `python3 scripts/generate-terminal-graphics-accounting.py /path/to/ghostty /path/to/zig`.

The [visibility corpus](visibility-manifest.json) contains 11 scenarios / 92 checkpoints
for mode 2033, one-shot queries, repeat enables, mode save/restore, RIS/DECSTR, screen
switches, synchronized output and malformed or incomplete sequences. The helper uses
the original parser, mode state and response encoder. Its host adapter changes the
original visibility flag and invokes the original reporting handler only when subscribed,
mirroring `Termio.visibilityReport`; it does not inject query bytes into the parser.
Kotlin replays each case whole and bytewise. Platform tests separately verify host events.
Regenerate with `python3 scripts/generate-terminal-visibility.py /path/to/ghostty /path/to/zig`.

The browser graphics verifier also checks resource reclamation when the terminal or an
ancestor is hidden, the terminal is detached, or the document becomes hidden. Canvas
backing stores and image caches are released; WebGL additionally releases its glyph atlas,
buffers and shader program. Showing the terminal reconstructs the same pixels, including
a synchronized-output frame holding an older image generation. Context restoration while
hidden must not allocate those resources again.

`NativeGraphicsRenderingTest` checks the corresponding AppKit image-cache and Metal
image/atlas/buffer lifecycle, including exact held-frame pixels after repeated hide/show
cycles at 1x, 1.25x and 2x scale. A submitted Metal command retains its resources until
completion, even if hidden or disposed; showing it before completion must keep the layer
usable. Hidden Metal layers detach their device and shrink drawable dimensions to 1x1
(CAMetalLayer ignores zero dimensions). Queued hidden-view draws do not rebuild caches.
These tests work offscreen; live window visibility and occlusion remain covered separately
by `NativeGraphicsTimingTest` and `MacOsRenderObserverTest` and require an unlocked desktop.

The [mouse Shift capture corpus](mouse-shift-manifest.json) contains 17 XTSHIFTESCAPE scenarios /
86 checkpoints from the original terminal stream and tri-state input flag. It covers defaults,
invalid parameters/headers, cancellation, partial sequences, screen switches, saves and resets.
Kotlin replays each case whole and bytewise; host policy, encoded mouse modifiers and platform
gestures are tested separately. Regenerate with
`python3 scripts/generate-terminal-mouse-shift.py /path/to/ghostty /path/to/zig`.

The [ENQ corpus](enquiry-manifest.json) contains 30 scenarios / 79 checkpoints from the
original terminal stream and enquiry effect. Host configuration supplies raw response bytes;
the original handler decides whether to respond and applies its 255-byte bound. Hex snapshots
preserve NUL/non-UTF-8 data. Parser-state cases cover ESC/CSI/DCS headers, control strings,
partial input, pending wrap, resets and screen switches; Kotlin replays whole and bytewise.
Regenerate with `python3 scripts/generate-terminal-enquiry.py /path/to/ghostty /path/to/zig`.

The [program-status corpus](program-status-manifest.json) contains 260 scenarios /
290 checkpoints from the original OSC 7501 parser, typed effect callback and reply
encoder. It covers optional handler installation, both query terminators, reset,
fragmentation/cancellation, all states/kinds, duplicate/unknown fields, limits,
IDs and safe UTF-8/base64 decoding. Kotlin replays it whole and bytewise. No registry
is copied into the oracle: record replacement, inheritance, eviction, subtree clear
and process/prompt lifetime are separate tests against the
[protocol specification](https://www.superlogical.com/rex/docs/build/program-status).
Regenerate with `python3 scripts/generate-terminal-program-status.py /path/to/ghostty /path/to/zig`.

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

The [placement geometry corpus](graphics-geometry-manifest.json) is generated with
`python3 scripts/generate-terminal-graphics-geometry.py /path/to/ghostty`. The helper's
`J` option snapshots placement metadata, combined render geometry and the original
iterator's three z-layer filters. Its 98 cases and 1400 checkpoints cover source clipping,
native/aspect-ratio/explicit grid sizing, unsigned saturation, offsets clamped at creation,
unknown/changed cell geometry, virtual placements, cursor advancement and margin cropping.
The Kotlin adapter compares source/pixel/grid sizes, viewport coordinates, z-layer membership,
cursor/index counts through the resulting scrollback, and rewritten source rectangles after
margin scrolls. No expected geometry is calculated by the Python generator.
These are component tests: actual line anchors, reflow/screen lifecycle, parent chains,
placeholder rendering and platform pixels are not covered by this geometry corpus yet.

The [row-anchor corpus](graphics-anchors-manifest.json) is generated with
`python3 scripts/generate-terminal-graphics-anchors.py /path/to/ghostty`. Its 48 cases
and 442 checkpoints create real placements in the original library and replay text writes,
scrolling, width/height changes and screen switches. Common tests attach internal row anchors
at the same cursor positions and compare viewport coordinates, cursor, pending wrap and history
after whole and bytewise writes. Cases include blank pins, several pins in different insertion
orders, overwritten/erased text, wide glyph heads/tails, one-column grids, saved cursors and
resizing inactive screens. Tall placements make history origins observable through the public
viewport API without inventing expected coordinates in the generator.

Ghostty 1.4 removes placements anchored in erased history, matching Kinetica's row retirement.
The old `anchorPolicy: retire-cleared-history` exception has been removed. New cases exercise
blank pins after hard/soft breaks and pending wrap, plus saved cursors through repeated widening.
Separate common tests check strict line/byte-limit eviction, recycling, disposal and reset of both screens.
These tests prove anchor mechanics, not APC execution, margin clipping integration or rendering.

The [session execution corpus](graphics-execution-manifest.json) is generated with
`python3 scripts/generate-terminal-graphics-execution.py /path/to/ghostty`. Its 266 cases
and 2489 checkpoints replay complete APC bytes and ordinary terminal operations through
`TerminalSession`, both whole and bytewise. The reference helper receives exactly the same
writes, geometry changes and storage limits. The comparator checks every selected image's
decoded bytes, exact PTY replies, placement fields/resolved geometry/layers, cursor, pending
wrap, screen/history and text/wide-cell rows. Placement iteration is compared as an unordered
multiset; probe IDs are checked against the complete Kinetica image count.

This corpus reuses the storage, small-image geometry and anchor inputs, recording fresh
original-library snapshots for the integrated path. It adds deferred transmit-and-display,
ID/number/implicit lookup, placement replacement, internal IDs, virtual and relative placements,
parent selection/cycles/depth/saturated offsets, cascade deletion, eviction, all static deletion
selectors, screen switches, reset, erasure, full/margin scrolling, IL/DL and render holds.
The manifest hashes all reused input generators. History-clear is included without a policy
exception. Animation frame loading/editing/composition/control/deletion, chunk identity, quotas,
quiet replies, cancelled APC actions and C1 exits are covered. The graphics wrapper installs an
independent libpng 1.6.59 callback; the base text helper still has no PNG callback.
No file/shared-memory input or platform renderer runs in these tests.

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

Build the pinned C-reference checkout with Zig 0.17.0:

```sh
zig build -Demit-lib-vt=true -Doptimize=fast -Dsimd=false -Demit-xcframework=false
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

The cursor-tabulation-control increment adds 96 scenarios / 483 checkpoints for CTC
(`CSI W`, `CSI 0/2/5 W`) and DECST8C (`CSI ?5W`). Default/custom tab stops, malformed
parameters and headers, trailing empty parameters, pending wrap with DECAWM on/off,
margins, screen switches, DECSTR/RIS and resize are compared with the original library.
The main corpus now contains 776 scenarios / 4130 checkpoints.

The window-resize-request increment adds 23 scenarios / 72 checkpoints, bringing the
main corpus to 799 scenarios / 4202 checkpoints. `windowResize: true` attaches the original
`GHOSTTY_TERMINAL_OPT_RESIZE_WINDOW` callback and records ordered raw `[rows, columns]`
requests without changing host geometry. Full, omitted, zero and saturated parameters,
invalid headers, cancellation, pending wrap, screen switches, synchronized output and
reset are covered; a separate case leaves the host callback absent. These prove protocol
events and unchanged state, not platform window behavior. The callback API is enabled
only in the 1.4 main oracle build, preserving the graphics helper's historical build mode.

The title-report increment adds 44 scenarios / 152 checkpoints, bringing the main corpus
to 843 scenarios / 4354 checkpoints. `titleReporting` events set the original C library's
host option. The cases cover default denial/revocation, empty and changing titles, resets,
screens, synchronized output, malformed queries, OSC 0/2 capture boundaries, invalid UTF-8,
C0 filtering and truncation inside multibyte characters. Original libghostty validates the
captured title before truncating it to 1024 bytes. The generator decodes snapshot display
text with UTF-8 replacement so that an upstream cut inside a character remains representable
in Kotlin; PTY reply hex preserves every original byte independently. No expected reply is
re-encoded from the normalized display title. String-feed and byte-feed equivalence has a
separate common regression; ordinary oracle replay remains whole-byte-buffer and bytewise.

The CSI alias/arity increment adds 34 scenarios / 408 checkpoints, bringing the main corpus
to 877 scenarios / 4762 checkpoints. `CSI j/k` are compared with the original cursor-left/up
handlers across default/zero/large counts, margins, reverse wrap and alternate screens.
Cursor movement, tab movement, erase/insert/delete and scrolling reject excess numeric or
empty parameters without changing cursor, cells or pending wrap. A trailing empty slot
remains accepted when it does not increase the original parser's parameter count.
All 34 cases failed before the implementation change; the prior 843 cases still passed.

The CSI final-dispatch/zero-count increment adds 90 scenarios / 729 checkpoints, bringing
the main corpus to 967 scenarios / 5491 checkpoints. Every final byte from `0x40` to `0x7e`
is sent with no parameters, zero and excess parameters after a pending wrap, followed by
ASCII, wide and combining output. Before the fix, 31 of these 63 cases failed while all
prior 877 cases passed. Another 27 cases compare omitted and zero counts for HPR/VPR,
CHT/CBT, DCH, IL/DL and SU/SD in the middle of lines, with margins and on alternate screens.
The oracle distinguishes commands where zero is a no-op from positioning commands that
still clear pending wrap. Whole-buffer and bytewise replay compare every intermediate state.

The mode-query/keyboard-negotiation increment adds 12 scenarios / 582 checkpoints, bringing
the main corpus to 979 scenarios / 6073 checkpoints. ANSI/DEC DECRQM queries distinguish a
missing number from explicit zero and committed empty slots; extra/colon parameters are
rejected without disturbing pending wrap. Primary/alternate screen states are covered.
Six query cases failed before the fix, while the six keyboard cases and all prior cases
passed. Kitty push/pop/set/query and modifyOtherKeys set/reset are checked with omitted,
zero, out-of-range, extra and colon-separated parameters. Actual Ctrl+C encoding and flag
queries observe the negotiated state after every command; each setup resets previous state.

The tab/pending-wrap increment adds 59 scenarios / 342 checkpoints, bringing this corpus
to 461 scenarios / 2615 checkpoints. It covers omitted/explicit/malformed tab-clear parameters,
default/custom stops, HT/CHT/CBT pending-wrap preservation with DECAWM on/off, and narrow,
wide and combining-character output after backward tabs. Trailing empty CSI parameters are
checked across tab clearing, SGR, reports and cursor controls; expectations come from the
original parser/terminal, including the distinction between `0;g` and `;0g` and preservation
of the current underline style for an incomplete `4:` group.

The charset increment adds 71 dedicated scenarios and two exact archived Alacritty recordings
(`saved_cursor`, `saved_cursor_alt`): 346 more checkpoints, for 534 cases / 2961 checkpoints
in total. It exercises G0–G3 designations, SI/SO/locking shifts, SS2/SS3 (including raw C1),
ASCII/British/DEC mappings and unmapped Unicode, ignored designations, combining characters
with clustering on/off, shifts across control/status output, repetition, saved cursor slots,
47/1047/1049, resets and resize. Single shift is consumed before width/grapheme processing;
charset mapping happens afterward. The original DEC table preserves underscore; the two
Alacritty replays document that difference without changing their archived source grids.

The subsequent mode/input audit adds 38 scenarios / 166 checkpoints, bringing the main
corpus to 572 cases / 3127 checkpoints. Fixed-reset ANSI modes and DEC 117 are queried across
set/reset attempts and terminal resets. DECBKM is compared across press/repeat/release, all
Shift/Alt/Ctrl/Super combinations, lock bits, control/printable host text, modifyOtherKeys and
six Kitty flag combinations, plus mode save/restore, DECSTR, RIS and screen changes.
OSC 105 and dynamic resets 113–119 are verified as no-ops for the palette and supported dynamic
colors, matching the original's unsupported special/dynamic targets; OSC 105 is not OSC 104.

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

The 1.4 format extension adds 51 scenarios / 264 checkpoints for modes 1005/1015/1016,
all five wire formats, four tracking modes, button/modifier mappings, UTF-8 encoding
thresholds, format precedence, queries, private save/restore, DECSTR/RIS and screen changes.
`V` configures a persistent original encoder with explicit pixel geometry and optional
last-cell tracking; `L` supplies f32 pixel events and held-button state without resetting it.
The replay uses `sendMousePixels` or a per-batch surface reporter accordingly. Boundary and
fractional-coordinate batches compare unclamped pixel releases, outside dragging/hover,
inclusive viewport edges, f32 rounding and repeated subcell motion. Tracked batches use valid
press/release lifecycles; Shift selection and orphan-release suppression remain host policies.
Separate JVM/JS/Native tests cover non-finite/extreme-coordinate recovery, geometry changes,
cancellation, and physical pixel mapping through real browser/AppKit events.

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
## Unicode placeholder oracle

`graphics-unicode-manifest.json` records a separate original-Ghostty Zig-module oracle.
`scripts/terminal-ghostty-unicode-oracle.zig` calls the pinned module's actual
`kitty.graphics.unicode.placementIterator` and `Placement.renderPlacement`; it does not
reimplement their geometry. The public C placement API reports virtual placements as lacking
a viewport position and cannot provide this reference data. The helper uses a local module
dependency without editing the upstream checkout and builds with Zig 0.16.0 ReleaseSafe.

Regenerate with:

```sh
python3 scripts/generate-terminal-graphics-unicode.py build/upstream/ghostty build/upstream/zig-aarch64-macos-0.16.0/zig
```

The generator verifies the pinned commit and clean tracked sources, uses the existing
`build/upstream/zig-cache` dependency cache (overridable with `ZIG_GLOBAL_CACHE_DIR`), and
records source/helper/generator/output hashes. It also extracts the original sorted diacritic
table into `src/TerminalGraphicsDiacritics.kt`; do not edit that generated table directly.
The 390 cases/403 checkpoints compare whole and bytewise session replay on common-test targets:
every diacritic, optional-field inheritance, invalid marks, RGB/indexed colors, high ID bits,
placement selection, centered aspect fitting and rounding, grid limits and scroll/reflow/screens.
After returning from alternate screen, the lifecycle case explicitly scrolls to bottom to align
with the host viewport policy. Relative children of virtual placements have separate common
frame tests; renderer pixels and cache ownership are checked by browser/native regressions.
This corpus is not a whole-renderer pixel oracle or an AddressSanitizer run.

The legacy Alt extension adds 22 scenarios / 131 checkpoints. Mode 1036 is exercised with
1039 set/reset, UTF-8 and missing text, unshifted scalar fallback, consumed modifiers,
press/repeat/release, modifyOtherKeys and Kitty. All modifier combinations for Enter/Tab/Escape
and C0/fixterms cases are checked against original bytes; 4/8/12/1039 also cover defaults,
queries, reset and screen lifecycle. The optional final `E` argument configures the original
encoder's macOS Option-as-Alt policy independently of consumed modifiers (`optionAsAlt` in
fixtures, default 1). This matters for modifyOtherKeys and Kitty associated text; consumed Alt
with Option-as-Alt disabled must be compared under the same host policy.

Reverse-video scenarios enable `renderColors` (`O` in the C helper) to compare default
foreground/background/cursor colors returned by the original RenderState, independently of
stored terminal colors and OSC replies. Ten cases / 49 checkpoints cover palette and dynamic
colors, SGR cells, query/save/restore, screens, resets, history/erasure and synchronized state.
The original renderer state is updated immediately; retaining a displayed frame is host policy
and is tested separately with actual CPU/GPU pixels and viewport snapshots.

DECCOLM adds 19 focused scenarios / 70 checkpoints and six archived Alacritty recordings
replayed unchanged through original Ghostty. Alacritty's fixed-grid DECCOLM erasure differs
from Ghostty's mode-40 gate and actual 80/132-column resize, so those six recordings use the
complete Ghostty result, including dimensions, instead of Alacritty cells. Archive paths and
hashes remain in `scenarios.json`; original Alacritty fixtures are unchanged.

The focused cases cover disabled commands, repeated widths, margins/origin, backgrounds,
protection, tab stops, save/restore, DECSTR/RIS, all alternate-screen modes, explicit resize,
history and DECAWM-controlled reflow. The full corpus is 680 scenarios / 3647 checkpoints.
For `deccolm_holds`, the explicit `paired-resize` policy compares our paired host notifications
with original callbacks plus an end notification when the original mode-2026 bit is cleared.
The original `Terminal.resize` invoked internally by DECCOLM clears that bit without emitting
`render_hold(false)`; raw original snapshots retain this discrepancy. All other state is
compared directly. Headless mode 40 has no geometry side effect, as in libghostty-vt; separate
browser/native tests exercise Ghostty's host-side refresh behavior and actual PTY dimensions.

## OSC 5522 core oracle

`clipboard-core-manifest.json` pins the original `Metadata`, `WriteState`, `Response`,
`ReadSuccess`, `PasteEvent`, `Grants` and `generateOtp` modules/functions from Ghostty 1.4.0. The Zig helper imports
those modules directly through `ghostty-vt`; it does not copy their protocol algorithms.
The 1070 cases / 4618 checkpoints cover duplicate/malformed metadata, ID sanitation, UTF-8 and field limits,
every split point of a binary Base64 stream, independently padded chunks, invalid partial
groups, MIME replacement, ordered/chained/colliding aliases, 64-entry caps and decoded budgets.
Response cases cover every status, BEL/ST, omitted/empty metadata, binary 4096-byte chunk
boundaries and bounded UTF-8 targets listings. Grant cases cover independent directions,
one-time consumption (including mismatches), persistent merges, length/capacity boundaries,
swap-removal followed by eviction and 2000 deterministic interleaved operations.
Dispatch cases exercise the original TerminalStream effects and responses, including RIS,
authorization outcomes and repeated completion. Another 324 cases call the original OTP
generator with deterministic entropy overrides: all 256 byte values, rejected samples,
multiple batches, partial-password failures and seeded byte streams. The override fails when
its fixture bytes run out, and the underlying Io has no entropy source, exposing any fallback.
Another 27 cases invoke the original handler's paste path with mode 5522 enabled, checking
all clipboard locations, empty/large/over-16-type listings, entropy failure, exact event bytes
and one-time read-grant consumption.

```sh
python3 scripts/generate-terminal-clipboard-core.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig
```

The generator checks the pinned SHA and clean tracked upstream sources, runs with Zig's safe
optimization and archives reproducible fixtures plus source/helper/generator hashes. Common
tests compare metadata results, pending byte counts, errors, all committed binary contents,
encoded response bytes, grant decisions/counts, OTP strings and exact entropy-call counts.
They also replay the synchronous dispatch cases through the session and bounded transport.
Async delivery, platform clipboard access and platform CSPRNG calls are tested separately;
these deterministic fixtures do not establish operating-system entropy quality. User-initiated
mode-5522 paste integration remains unfinished.
