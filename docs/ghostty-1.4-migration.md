# Ghostty 1.4 reference migration

The compatibility target now includes Ghostty 1.4.0, released on October 27, 2026.
The main reference, all C-generated fixtures, and both Zig animation and Unicode-placeholder
corpora now use the release. The previous September 30 checkout remains available for
historical comparisons; no oracle corpus retains its old pin.

The official annotated tag `v1.4.0` resolves to
`83fb95624b0439ee660c2679b43b38c8b63769cd`. A separate checkout at
`build/upstream/ghostty-1.4.0` keeps the previous oracle reproducible during migration.
The release requires Zig 0.17.0. Its official macOS arm64 archive SHA-256 is
`b607e9b9234790a008116ae5bdb71c6243b84b9fb42a53a9e70fde41c06c536a`.

Sources: [release notes](https://ghostty.org/docs/install/release-notes/1-4-0),
[tagged source](https://github.com/ghostty-org/ghostty/tree/83fb95624b0439ee660c2679b43b38c8b63769cd),
[official Zig download index](https://ziglang.org/download/index.json).

## Reference and verification

- [x] Build the original 1.4 libghostty-vt with Zig 0.17 and verify C oracle API compatibility.
- [x] Update Zig oracle helpers for the new language/library and upstream interfaces.
- [x] Regenerate all affected fixtures and provenance through `scripts/`, review behavioral
      differences, and preserve the existing scenario coverage.
- [x] Add original-reference and platform regressions for the implemented behavior listed below;
      passing the old corpus alone is insufficient. The broader source audit remains open.
- [x] Update the primary C-reference pin with regenerated evidence and corresponding implementation.
- [x] Run shared JVM/JS checks, relevant Native tests and fresh browser rendering/input checks for the C-reference migration.
- [ ] Complete the release-note/source audit for remaining emulation and input differences.

The first release comparison builds the existing C graphics host against both libraries and
runs all 244 current execution scenarios (2394 checkpoints). Twenty scenarios differ, covering
reflow/anchor positions, saved cursor behavior and cancelled APC execution. Array ordering for
placement maps and layer membership is normalized before comparison. This is a comparison of
two original libraries, not a passing compatibility claim for Kinetica against 1.4.

Reproduce with:

```sh
cd build/upstream/ghostty-1.4.0
ZIG_GLOBAL_CACHE_DIR="$PWD/../zig-cache-0.17" ../zig-aarch64-macos-0.17.0/zig build -Demit-lib-vt=true -Doptimize=fast -Dsimd=false -Demit-xcframework=false
cd ../../..
python3 scripts/compare-terminal-graphics-release.py build/upstream/ghostty build/upstream/ghostty-1.4.0
```

The report at `build/reports/ghostty-1.4-graphics.json` contains exact changed checkpoints,
input commands, both library hashes and the corpus/helper/generator hashes. The script requires
the two exact revisions, clean tracked upstream sources and pinned libpng/zlib versions; it
does not rewrite committed fixtures. The existing C host compiles and runs unchanged against
the new library. Zig's own build fetch succeeded after a transient dependency TLS failure;
avoid seeding its 0.17 cache with standalone `zig fetch` archives, which produced a nested
package and a subsequent hash mismatch in this run. Dependency hashes were not changed.

The first implementation checkpoint aligns the C reference corpus with the release: 279 text/
input scenarios (1760 states), 15 storage cases (120 states), 98 geometry cases (1400 states),
48 anchor cases (442 states) and 266 integrated graphics cases (2489 states). Generation is
byte-for-byte reproducible. The expanded cases cover blank anchors after hard/soft breaks,
pending wrap and repeated widening, cancelled APC actions/continuations and C1 exits.
History-clear now compares directly with the original library; the old retirement exception
has been removed.

Implementation changes preserve pins and saved cursors through reflow, discard cancelled APCs,
remove placements in explicitly erased history, reset the palette on RIS while retaining dynamic
colors, reset empty OSC 22 to text, preserve the current pen on a widening grapheme's spacer,
avoid adding origin margins twice, stop CUB/BS at the left margin, and find shell prompts above
blank rows for ED2. Validation passes 547 JVM tests, fresh Release JS tests, all 279 Native C
oracle tests, 103 additional Native graphics/GPU/presentation/pointer tests, and both browser
verifiers. The new protocols below remain open.

The Zig oracles now build with Zig 0.17 against the same unmodified release. Animation retains
400 cases / 2030 checkpoints, including all 65,536 alpha pairs. Five loading-state cases expose
the release's storage-wide idle-generation gate: once playback parks, repeated ticks (including
a restarted clock) leave it untouched until image or placement state changes. Storage now
implements that gate; a focused regression also checks wakeups from another image, replacement
placements and arriving frames.

The placeholder oracle reads the original `RenderState` scanner's raw runs and resolved
geometry, including hidden runs and their error diagnostics. It retains all 390 cases / 403
checkpoints and 297 diacritics, adds the original render state's visibility flag, and records
render/storage source hashes. Existing run and geometry values are unchanged. Both generators
reproduce fixtures and provenance byte-for-byte. Validation passes all 548 JVM tests, fresh
Release JS tests, 74 Native graphics/animation tests and both Canvas/WebGL graphics verifiers.

## Confirmed terminal behavior to implement or reconcile

- [x] Bound detected URLs by OSC 133 semantic-content transitions, including transitions
      within a row and across soft wraps. Explicit OSC 8 targets retain priority; literal
      search still spans semantic boundaries. The original `Screen.selectLine` oracle covers
      26 scenarios / 47 checkpoints / 1392 cell ranges, checking semantic metadata before
      ranges with whole and bytewise input. It matches libghostty-vt's initial disabled prompt
      redraw policy and separately exercises OSC 133 redraw `0`, `1` and `last`.
      Generation is byte-for-byte reproducible. Validation passes 1388 JVM tests, fresh Release
      JS tests and 18 focused Native tests, including the actual AppKit Cmd-click callback.
- [x] Align ordinary line-selection regions with original `Screen.selectLine` semantic
      boundaries and whitespace trimming. The extended oracle checks 31 cases / 52 snapshots,
      1492 untrimmed and trimmed selections and 2340 line-gesture steps against original
      `SelectionGesture` and `selectionString(trim = false)`. Blank targets retain selection;
      blank origins can anchor a drag. Copy preserves interior written spaces and omits the
      previously synthesized final newline. Wide glyphs and combining sequences remain whole.
      Generation reproduces byte-for-byte. Validation passes 1389 JVM tests, fresh Release JS
      tests, 25 focused Native tests and actual browser gestures/copy/highlights on Canvas and
      WebGL. AppKit coverage includes native triple-click and reverse drag events.
- [x] Match Ghostty 1.4's macOS safe-opening policy for links supplied by terminal output.
      The default `AppKitTerminalView.openLink` validates web/mail targets, rejects unsafe
      characters, canonicalizes local paths and resolves symlinks, and rejects special,
      executable and active-content files using extensions, UTIs and executable status.
      Custom schemes require deferred confirmation with selectable, normalized and escaped
      target text. Denials offer sanitized copying without an unrestricted fallback.
      Replaced requests, disposed views and revoked OSC 8 policy cannot approve stale actions.
      The unmodified `macos/Sources/Helpers/UntrustedURL.swift` produces 83 comparative cases
      over 32 isolated resources, including directory/file slash hints and symlinks; source,
      generator and fixture hashes are recorded in `native-links-manifest.json`. Generation
      is byte-for-byte reproducible. Fifteen Native tests pass for the classifier corpus,
      offscreen dialog configuration, response routing, cancellation/disposal, Cmd-click and
      shared navigation. These checks do not launch external applications or claim interactive
      acceptance of a visible modal sheet. Hosts overriding `openLink` own their target policy.
      See [GHSA-cw69-wm67-5pv8](https://github.com/ghostty-org/ghostty/security/advisories/GHSA-cw69-wm67-5pv8).
- [x] Add a host setting equivalent to `link-osc8`: `session.osc8LinksEnabled` gates
      session/viewport `hyperlinkAt` without destroying stored OSC 8 metadata. It immediately
      affects current rows, history, alternate-screen rows and synchronized snapshots,
      preserves plain HTTP(S) detection, and survives RIS/DECSTR. Hosts use the policy-aware
      accessor for previews, highlighting and link-specific copying. The common navigation
      regression passes on JVM, JS and Native; the complete JVM suite passes 1383 tests and
      the freshly built Release JS suite passes.

- [x] Kitty drag-and-drop (`OSC 72`) end-to-end session and host integration. The internal
      command/state/response core and session now match 167 original-library scenarios /
      436 checkpoints, including 28 scenarios through the original `TerminalStream`.
      It handles registration, chunked acceptance, move/drop/leave, indexed data requests and
      conclusion, including the original refusal of drag-out and remote data transfer.
      Raw wire parsing and bounded transport delivery are connected. Browser drag events
      and string/file representations are connected; AppKit routes its drag pasteboard
      through the same coordinator, including native and custom MIME representations.
      A shared host coordinator now owns per-view gestures, client/source operation feedback,
      bounded pending actions and stale-reader cancellation. Chromium checks cover trusted
      text/file drags, source/client operations, binary data, budgets, backpressure and disposal
      on both renderers. Isolated AppKit callback tests cover both native renderers,
      lazy providers, file/ordinary URLs, binary data, budgets, backpressure, stale reads
      and registration cleanup. These use real windows/pasteboards and a dragging-info
      fixture; a hardware-driven Finder drag is not claimed by these tests.
- [x] Title report (`CSI 21 t`) with explicit `configureTitleReporting` host opt-in,
      disabled by default and preserved across resets. Original-library cases also align
      OSC 0/2 validation and limits: 2047 captured bytes, valid UTF-8, then a 1024-byte
      retained title. Replies preserve a partial UTF-8 suffix; display text uses replacement.
      CSI 22/23 t title stacks remain ignored, as in both upstream terminal handlers.
- [x] OSC 133 `click_events=1/2` host reporting on a completed local left click. Both views
      send an unmodified SGR press with viewport or prompt-relative rows, including history.
      Drags that return to their start, word/line selection, cancelled gestures, frozen frames,
      alternate screens and application-owned mouse gestures do not synthesize a prompt click.
      Host `cursorClickToMove` policy survives resets. The original terminal/prompt iterator
      and extracted Surface coordinate helper generate 32 cases / 586 checkpoints, replayed
      whole and bytewise; artifacts reproduce byte-for-byte. Validation: 1372 JVM tests,
      fresh JS tests, three Native common prompt tests, three AppKit selection tests, four
      Native mouse/PTY tests and the Chromium input/rendering verifier.
- [x] Older OSC 133 `cl=line/m/v/w` click-to-cursor key synthesis. All four follow the original
      Screen.promptClickMove implementation: input-cell counting, skipped non-input cells,
      hard/soft line boundaries and CSI/SS3 cursor keys. The extended original-library corpus
      has 116 cases / 6506 checkpoints, including wide cells, combining marks, spacer heads,
      history, erased prompts and cursor content versus cell content. The shared gesture and
      host-policy gates apply to both reporting families. Validation passes 1372 JVM tests,
      fresh JS tests, three Native common prompt tests, four AppKit selection tests, four
      Native mouse/PTY tests and Chromium input/rendering checks; fixtures reproduce exactly.
- [x] XTWINOPS resize request (`CSI 8;rows;columns t`) core observer contract, with raw
      zero/omitted dimensions, unsigned 16-bit limits and no automatic terminal resize.
      Twenty-three original-library cases cover payloads, rejected headers, cancellation,
      pending wrap, resets, alternate screens, synchronized output and an absent host.
- [x] Connect permitted XTWINOPS requests to browser layout and native window resizing,
      with one deferred task, merged axes, policy rechecks and lifecycle cancellation.
      AppKit accounts for UI chrome and screen/window limits; browser hosts receive CSS
      extents and own container constraints. Both default to denial. Native Settings and
      the browser demo provide explicit opt-ins, verified through real layouts and a test PTY.
- [x] XTGETTCAP (`DCS +q`): all 272 static terminfo capabilities, dynamic TN from the host's
      actual TERM, malformed-key skipping, boolean/valued replies and DCS capture boundaries.
      The generated table and 309 original-handler cases include uppercase/lowercase hex,
      numeric headers, control bytes, cancellation and UTF-8 name limits. Long responses use
      bounded delivery chunks and stop consuming output until the transport acknowledges them.

- [x] DECSTR (`CSI ! p`): reset implemented modes, margins, pen/protection/charsets, saved
      cursor, cursor appearance and modifyOtherKeys while retaining screen contents and cursor
      position. Palette overrides reset; dynamic colors, hyperlinks, tab stops, synchronized
      output, graphics and unfinished image/frame transfers remain intact. Fifteen original-
      library scenarios cover 49 states, with another graphics execution scenario for transfers.
- [x] Connect DECSTR/RIS to status-display/checksum state and configured checksum defaults.
- [x] Reverse-wrap modes 45/1045 and ANSI keyboard-disable mode 2, including mode queries,
      reverse-wrap save/restore and RIS/DECSTR. Host KAM enforcement is opt-in (default off,
      matching Ghostty); AppKit/browser key and IME paths honor it while paste/replies remain usable.
- [x] RIS resets palette overrides in addition to its existing state reset.
- [x] DECXCPR (`CSI ?6n`) responds with `CSI ?row;column;1R`, including origin/margin handling.
- [x] `CSI 19 t` reports screen size in characters; the release dispatches `.csi_19_t`.
- [x] DECRQCRA/XTCHECKSUM requests and all 32 checksum variants, with explicit host opt-in
      disabled by default, matching both libghostty and standalone Ghostty. DECSASD discards
      printable status-line output while continuing to execute controls.
- [x] Visibility mode 2033, its mode query, `CSI ?998n` and `CSI ?999;1/2n` reports, connected
      to browser and AppKit host visibility. Eleven original-library scenarios / 92 states
      cover parser/mode/reset behavior; host tests cover hiding, attachment and native occlusion.
- [x] OSC 7501 program status parsing, support query, state/observer integration and bounds.
      Support is enabled by a host observer, matching libghostty's optional effect handler;
      views do not automatically show statuses. The bounded registry also implements record
      replacement, app inheritance, subtree clearing and prompt/transport lifecycle cleanup.
- [x] OSC 5522 clipboard transactions, host authorization, MIME data, aliases, cancellation,
      bounded buffering and acknowledged protocol replies, with AppKit/browser MIME adapters.
- [x] Reconcile origin-relative CHA/VPA/HPR/VPR, CUB/BS margin behavior and the changed reflow scenarios.
- [x] Support `CSI j/k` as the original's CUB/CUU aliases, including margins, reverse wrap
      and alternate screens. Reject excess parameters for cursor movement, tab movement,
      erase/insert/delete and scrolling before changing cells, cursor or pending wrap.
      Thirty-four original-library scenarios / 408 checkpoints cover rejection and accepted
      trailing empty parameters; both whole-stream and bytewise replay match.
- [x] Preserve pending wrap for unimplemented CSI finals. Distinguish omitted counts from
      explicit zero for HPR/VPR, CHT/CBT, DCH, IL/DL and SU/SD: zero relative movement still
      applies positioning semantics, while zero tab/edit/scroll counts leave text in place.
      Ninety original-library scenarios / 729 checkpoints cover all 63 CSI final bytes and
      explicit-zero behavior in the middle of lines, at margins and on alternate screens.
- [x] Extend coverage for parameterless TBC and pending wrap with DECAWM disabled. Tab
      movement/clearing preserves pending wrap; TBC rejects extra parameters. CSI parameter
      counting follows the original parser, which does not append an empty trailing slot.
- [x] CTC (`CSI W`, `CSI 0/2/5 W`) and DECST8C (`CSI ?5W`) set/clear/reset shared tab stops,
      preserving pending wrap. Original-library cases cover malformed parameters, margins,
      screen switches, reset and resize, replayed whole and bytewise.
- [x] G0–G3 charset designation (ASCII/British/DEC), SI/SO/LS2/LS3 and SS2/SS3, including
      combining-character consumption, unknown designation handling, width before mapping,
      saved cursors, screen switches and DECSTR/RIS. GR invocation is retained but does not
      remap UTF-8 characters, as in the original. Seventy-one dedicated scenarios plus two
      exact Alacritty replays cover the lifecycle and DEC underscore difference.
- [x] Empty OSC 22 resets the pointer to text.
- [x] Compare fixed mode reports, OSC 105 routing and modified Backspace input. ANSI modes
      1/5/7/10/11/13–19 and DEC 117 report permanently reset. DECBKM (67) swaps plain and
      Ctrl+Backspace in legacy encoding; other modifier combinations and Kitty retain their
      own encodings. DECSTR preserves 67; RIS resets it. OSC 105 resets unsupported special
      colors in Ghostty and has no palette/dynamic-color effect; original fixtures verify this.
- [x] DECRQM requires exactly one parsed parameter; a missing mode number produces no
      reply, while explicit zero and a committed empty slot keep their original semantics.
      Twelve original-library scenarios / 582 checkpoints cover ANSI/DEC query boundaries
      and Kitty/modifyOtherKeys negotiation, including actual encoded Ctrl+C bytes.
- [x] Mouse formats UTF-8 (1005), urxvt (1015) and SGR pixels (1016), including
      independent requested mode bits, last-command format selection and reset/save lifecycle.
      Browser/AppKit supply physical pixels with DPR/backing scale; pixel motion retains
      subcell events and outside drag/release coordinates. Persistent original-encoder
      comparisons cover cell deduplication, rounding and UTF-8 coordinate boundaries.
- [x] XTSHIFTESCAPE (`CSI > 0/1 s`) controls whether applications capture Shift mouse input.
      Omitted parameters disable capture; RIS clears the request, while DECSTR and screen
      switches preserve it. Host defaults/forced policy survive resets. AppKit/browser paths
      honor capture, including the encoded modifier, and preserve local selection otherwise.
      Seventeen original-parser scenarios cover 86 states, including invalid/cancelled input.
- [x] ENQ (`0x05`) with explicit host answerback configuration, disabled by default. Raw
      replies of 1–255 bytes preserve NUL/non-UTF-8 data; oversized responses are suppressed.
      Thirty original-parser scenarios / 79 states cover byte limits, parser contexts,
      partial sequences, host changes, pending wrap, resets and screen transitions.
- [x] Legacy Alt-prefix mode 1036, with UTF-8/layout fallbacks, C0/fixterms and independent
      modified functional-key tables, connected to AppKit Option-as-Meta and browser layout input.
      ANSI SRM (12), DEC slow-scroll (4), autorepeat (8) and Alt mode 1039 retain queryable
      state without invented runtime effects, matching the original's ignored behavior.
      These modes preserve state across DECSTR/screens; RIS restores defaults.
- [x] Reverse video (DEC 5), including mode query/save/restore, DECSTR/RIS and both screens.
      Only effective rendering defaults swap; explicit colors, palette entries, cursor/selection
      colors and OSC replies retain their identity. Viewport theme snapshots cover software,
      Canvas/WebGL, AppKit/Metal, composition/clearing and synchronized output.
- [x] DECCOLM and its gate (3/40), including 80/132-column geometry, current-background
      erasure/protection, tabs, history/reflow, screens, resets and mode query/save/restore.
      Browser/AppKit restore host geometry on mode-40 requests; transports and native PTYs
      receive accepted size changes. Ordinary browser repainting preserves protocol geometry.
- [x] Close the clipboard paste-event mode 5522 gap. Mode reporting depends on a MIME host;
      save/restore and RIS/DECSTR match the original. Trusted AppKit/browser paste sends metadata
      and a secure one-time read grant, preserving order across OSC 52/5522 and gestures.
      Session tests cover delivery failure, host replacement, reset and stale callbacks;
      platform checks cover lazy AppKit providers and real trusted Chromium paste. Scripted
      text and dropped paths keep their normal paste behavior. See the production-readiness
      log for validation and the module guide for host policy and bounded queue semantics.

## Memory and lifecycle

- [x] Hidden browser views release image caches and canvas backing storage; WebGL also releases
      its atlas, buffers and program. AppKit clears software caches; Metal waits for submitted
      commands before purging image/atlas/buffer resources and detaching its layer device.
      Browser checks cover hidden ancestors, detachment, document visibility, context recovery
      while hidden and held image generations. Nine native graphics tests include actual Metal
      submission, disposal and showing the layer again before completion. Live window occlusion
      is still a separate acceptance gate requiring an unlocked desktop.
- [x] Idle scrollback compression with stable row identity and lazy cell restoration. The portable
      implementation compresses cell planes, retaining grapheme/link metadata separately; it does
      not reproduce Ghostty's OS-specific page-compression mechanism. One worker per session
      excludes all attached visible ranges and bounds each callback to 16,384 cells. Input,
      including held output, restarts the idle delay; disabling/disposal cancels work. Logical
      history eviction charges remain unchanged. Seven common regressions cover integer bits,
      metadata, budgets, multiple views, selection/graphics anchors, snapshots, reflow and reuse.
      The implementation checkpoint passed 1382 JVM tests, fresh JS tests, all seven new Native
      common tests, nine Native graphics tests and both browser integration verifiers.

The host acceptance checks also run actual browser/AppKit timers. The browser history verifier
compares exact WebGL/Canvas screenshots across compression, scroll-away/back and hide/show,
including styled Unicode text. Its 400-row fixture at 1000×640 CSS pixels / DPR 2 reduces
accounted row storage from 1,099,632 to 492,394 bytes while preserving the logical charge.
These are accounting values, not heap/RSS measurements. AppKit verifies automatic compression,
readback and cancellation on disposal for both native rendering backends, without opening a
window. These checks supplement, rather than replace, the live window gates.

## Graphics and external media

- [x] Experimental Glyph Protocol (`APC 25a1`) support, query, register and clear commands.
      Original Ghostty parsing/execution/outline decoding produce 170 cases / 496 checkpoints
      comparing wire replies, glossary occupancy, decoded contours/points and authored metrics.
      The corpus covers numeric/options/Base64 edge cases, reply suppression, PUA boundaries,
      simple/compound/hinted/malformed glyphs, payload/outline/APC limits, FIFO replacement,
      alternate screens and RIS/DECSTR. Glyphs are retained but not rendered, matching the
      pinned implementation; no system-font coverage or layout override is claimed.
      Validation: 1375 JVM tests, fresh JS tests, three Native common tests, one real PTY
      exchange, Chromium transport/UI checks and reproducible fixtures/provenance.

- [x] Verify image/frame continuation identity against the actual release executor. Wire
      chunked frames still pin `Image.generation` in `graphics_exec.zig` and reject a changed
      target. The new stable `Image.transmission` belongs to `ImageStorage.PendingImage`
      tickets used to restore decoded pixels from snapshots, not ordinary APC chunking.
      The earlier requirement to change wire identity conflated these two paths.
- [x] External file/temporary-file/shared-memory transmissions abandon unfinished direct
      image or frame chunks before processing the new command, including failed host reads.
      Queries and conflicting identifiers leave the previous transfer intact.
- [x] Reconcile erased-history placement cleanup and cancelled/aborted APC commands, including
      C1 abort/recovery, using whole-stream and bytewise oracle regressions.
- [x] Adapt placeholder/render-state oracles to the release's moved placement resolution.
- [x] Native file, temporary-file and shared-memory readers with explicit host policy,
      offset/size handling, bounded allocations, safe file validation and deterministic cleanup.
      `MacOsTerminalGraphicsMedia` defaults to deny; the local-PTY application explicitly enables
      all three media. Real resource tests supplement the common injected-reader tests.
- [x] Follow 1.4's shared-memory name rules (pass valid names unchanged to `shm_open`, including
      forms without a leading slash) and canonical `/private/tmp` temporary-file handling.
- [x] Add original-library external-media comparisons and real native lifecycle regressions,
      including cleanup after read/decode failure and rejected paths/ranges.
- [ ] Complete the existing decoded storage and frame-wide renderer resource audit.

The transfer-buffer audit removes a redundant full RGB/RGBA decode copy: exact completed
buffers move from the transfer owner to immutable pixels. Final chunks grow to exact capacity,
while borrowed inputs remain copied and excessive raw frame tails remain trimmed. Allocation
and lifetime regressions cover single/chunked loads, caller mutation and retained old images.
This does not combine encoded-input, resident-image, composition and held-frame budgets;
the original's RGB-root conversion can still exceed the nominal resident limit.

The resident-accounting oracle now reads actual original storage counters and every frame,
covering 27 scenarios / 197 checkpoints. It confirms the conversion overrun and fixes two
host-limit differences: reapplying the same limit must evict an over-limit image, and changing
a positive limit must preserve an unfinished APC command. Disabling graphics still releases
capture/storage. Broader combined transient and retained-frame accounting remains open.

The outer-zlib PNG path now passes the collector's valid prefix directly to the PNG parser,
removing a second full encoded-PNG allocation when geometric growth leaves spare capacity.
Every signature/chunk/CRC boundary uses the logical length, and pixels own their output.
Inflation still validates the entire stream before PNG parsing, preserving checksum and
expansion-limit behavior. A regression failed at the old trimming allocation; boundary tests
cover truncated prefixes backed by a complete PNG, dirty unused capacity, all existing PNG
color/depth/interlace fixtures, and mutation of borrowed encoded input after decoding.
This removes one avoidable temporary; it does not merge the separate storage/transfer,
decoder, held-frame and renderer budgets.
Validation passes 1385 JVM tests, the freshly built Release JS suite, ten Native decoder
tests and ten Native software/Metal rendering tests. Browser Canvas/WebGL and native pixel
checks now explicitly include PNG cropping with and without outer zlib compression.

The retained-generation audit found that tile-cache keys held entire decoded source images,
even for tiny crops. Keys now retain a detached identity token owned by the immutable pixel
buffer, with no reference back to it. A live four-byte cached crop no longer retains its
one-MiB source, verified with JVM weak references; cache reuse and held-image correctness
remain covered on JS and Native. Viewport synchronized/resize holds share one frozen-frame
slot, while caller-retained snapshots remain explicit owners of their pixels.

The native media corpus has 61 cases: 57 compare original-reader bytes/errors and cleanup.
Four regular/temporary-file seeks past EOF with omitted size crash the original Zig 0.17
safe build with integer overflow. Those crashes are recorded rather than treated as conformance
expectations; our separate recovery assertions require an empty read and temporary-file cleanup.
The image decoder then rejects insufficient pixel data. The oracle enables PNG capability with
a trapping callback and never decodes; actual PNG decoding is covered by native integration tests.

The broader graphics acceptance list remains in
[terminal production readiness](terminal-production-readiness.md). This migration adds to that
work; it does not declare the existing graphics or release gates complete.
