# Terminal production readiness

Migration note: measurements below were recorded in the original Kinetica worktree.
Untracked `build/reports` evidence remains there and is not included in this source repository.

Status: **in progress; not production-ready**. The target is a shared Kotlin VT
engine used by the browser and native macOS surfaces, with reliable application
compatibility, input, rendering, bounded resources, and verified lifecycle.
The JVM target runs the same engine; a JVM desktop window is outside the existing
browser/macOS delivery scope.

This is the completion checklist, not a list of optional follow-ups. A passing
fixture subset or a working shell demo does not close the goal.

## Compatibility and state

- [ ] Ghostty-derived parser behavior covered by incremental and malformed-input tests.
- [x] Alacritty replay corpus on JVM, JS and macOS, with explicit documented differences.
- [x] Test-only original libghostty oracle: normalized cells, cursor, modes, replies,
      history and resize events; pinned upstream revision and reproducible runner.
- [ ] Correct independent primary/alternate screens, cursor save/restore, margins,
      tab stops, erasure, character protection and wrap semantics.
- [x] Resize/reflow of soft-wrapped text, cursor/saved positions, wide cells and
      scrollback, including resize while the alternate screen is active.
- [x] Generated, pinned Unicode width and grapheme data; Unicode conformance tests,
      combining marks, emoji ZWJ, flags, variation selectors and split UTF-8.
- [ ] Modern text-terminal protocols: indexed/dynamic palette, SGR subparameters,
      underline styles/colors, OSC 8, cursor styles, reports, synchronized output,
      application keypad and keyboard/mouse modes actually advertised by the terminal.
- [x] Recorded and live shell, Vim/Neovim, tmux, less and full-screen TUI workflows.
- [ ] Compatibility/capability documentation accurately describes unsupported image
      protocols and other extensions; device reports do not advertise absent features.

## Browser and macOS behavior

- [ ] GPU/software surfaces represent all implemented cell/cursor attributes; color
      emoji, DPR/font changes, context loss, atlas pressure and resize are verified.
- [ ] Keyboard shortcuts, modifier combinations, IME composition, paste/copy,
      selection across wide/wrapped cells, word/line selection and autoscroll.
- [ ] Mouse tracking including all-motion mode; local selection bypass remains usable.
- [ ] Accessible text, focus and useful assistive-technology interaction on both platforms.
- [x] Selection/scroll anchors survive unrelated output and recycling correctly.
- [ ] Mount/unmount, reconnection, window close, PTY exit and renderer failure release
      observers, timers, GPU resources, file descriptors and child processes.

## Resource and performance evidence

- [ ] Bounded parser strings/parameters, grapheme storage, history memory and queues;
      adversarial streams preserve invariants without crashes or runaway work.
- [ ] Backpressure and bounded scheduling under sustained PTY/network output; UI input
      and frames stay responsive under load, without unbounded transport queues.
- [ ] Benchmarks distinguish parsing, layout/batching, GPU submission and presented
      frames; capture throughput, frame-time distribution and input latency on both
      platforms. A 120 fps claim requires measured frame delivery on suitable hardware.
- [ ] Clipboard/link/window effects stay behind explicit host policy and user actions;
      terminal output alone cannot silently execute external actions.
- [ ] Reproducible build/test commands, licenses/provenance and API documentation;
      tests run from a clean checkout without the study clones or network downloads.

## Current evidence

The imported Alacritty corpus contains 45 recordings, stored verbatim with source
hashes. Generated common tests compare full and deterministically chunked streams.
The original minimal engine passed 22/45 before fixes; that baseline also exposed
the upstream harness's history-padding behavior, now handled by the adapter.
Current pass counts must come from fresh test output, not this document.

Ghostty-derived parser tests and fixes are being added with the MIT license and
the source revision recorded. Existing WebGL2/Metal renderers and real macOS PTY
are implemented, but their earlier tests do not prove the unchecked gates above.

The pinned original `libghostty-vt` now generates 722 intermediate snapshots from
143 scenarios. The common suite checks cells/styles/protection, wraps, cursor/pending wrap,
selected modes, replies, title, screen selection and history at every checkpoint,
both with whole writes and bytewise input. Its 32 resize scenarios cover primary
reflow and alternate-grid cropping, saved/current cursors, wide spacers, one-column
grids, printed spaces, colored blanks, styles/links, history, and combined width/
height changes. A separate stress regression narrows a 4096-column buffer to one
column with bounded history. Additional erasure/protection scenarios cover DEC/ISO
pen state, screen-local protection mode, save/restore, pending wrap, selective erasure,
and wide-cell boundaries. Differences from Alacritty's underline, selective erasure,
and erase-at-pending-wrap behavior are explicit policy cases backed by original
upstream data.

Indexed cell colors now retain their identity. OSC 4/104 and 10–12/110–112 update/query/reset
palette and default/cursor colors across history, reflow, both screens and RIS. Oracle comparisons
check typed cell colors, all 256 palette entries, effective defaults and exact BEL/ST replies.
Color specifications include all 782 archived X11 names and malformed/chained requests. The
tests exposed and fixed alternate-screen clearing with the wrong background; destination pen,
ISO protection and repeated-entry wrap behavior have dedicated upstream regressions. This closes
the palette part of the modern-protocol gate, not the remaining protocols in that gate.

Mode 2026 now freezes a view's visible rows, palette, theme and cursor at the exact escape
boundary while parsing and protocol replies continue. Both hosts enforce a one-second deadline
that repeated enables cannot extend. Reset, EOF and resize release it; new views wait for a
completed frame. Cursor controls cover block/underline/bar, mode-12 blinking, configured defaults,
screen state and DECRQSS. Common deterministic tests check deadlines, input during holds,
scroll-anchor publication order, forced redraw, multiple views and timer disposal. Real WebGL,
Canvas and Metal pixels verify atomic frames and cursor shapes; the browser additionally tests
context recovery during a hold and actual blinking, and AppKit verifies its run-loop timeout.
Additional reports and the remaining keyboard/mouse protocols in the modern-protocol gate remain open.

Application keypad now supports ESC =/>, DEC modes 66/1035, queries, reset and global state
across screen switches. Both hosts preserve physical keypad identity separately from logical
NumLock navigation and localized decimal text. The original Ghostty encoder supplies 3,456
key/modifier/mode comparisons, alongside whole/bytewise VT transition snapshots. Common tests
also cover ordinary versus keypad Return, linefeed mode, decimal localization and the documented
VT220 equal/separator extensions. Real AppKit NSEvents cover every mapped keypad key and
modifiers; Chromium checks actual NumpadEnter delivery, DOM mapping, navigation, Unicode/IME
ownership and exact bytes through the asynchronous stream. All 146 oracle artifacts regenerate
byte-for-byte. Extended keyboard protocols and broader TUI acceptance still need verification.

Binary-stream regressions now compare raw C1 controls and high bytes in ESC/CSI/DCS headers
and ignored strings against original Ghostty, including a 15-byte fragment from
`ant-1.10.15.jar`. Header bytes reach the VT state machine before UTF-8 decoding; ground text
still uses the streaming decoder. String writes preserve their UTF-8 wire interpretation in
headers, including split surrogate pairs. These cases close the observed mismatch, not all
malformed-input or string-payload compatibility gaps.

Mouse input now preserves protocol bytes separately from UTF-8 text. This fixes legacy reports
at zero-based column 95 and above, and retains modifiers on legacy releases. The original Ghostty
mouse encoder supplies 5,120 event comparisons across the supported tracking/format combinations,
buttons, wheel directions, modifiers and coordinate boundaries. All 146 oracle artifacts reproduce
byte-for-byte, and the new C helper paths pass AddressSanitizer. Common tests verify per-surface
motion state, mode/resize invalidation, Shift selection, cancellation, immutable input events and
atomic rejection under a stalled writer. Chromium exercises actual pointer capture/chorded
buttons through the byte transport; it exposed an internal host-root focus transition that used
to interrupt the gesture. AppKit tests use NSEvent/CGEvent for motion, buttons, wheel, Shift and
tracking-area disposal. A real PTY compares mixed UTF-8/legacy input bytes exactly. Full interactive
TUI acceptance and sustained mouse/input latency remain part of the open gates above.

The native sample now uses an AppKit menu and native window tabs. Cmd+N/T/W/Q and Cmd+Shift+[ / ]
create windows/tabs, close, quit and switch tabs with independent shell sessions and focus. Its
separate application test dispatches native menu key equivalents, verifies two distinct live
shell PIDs and shared native tab grouping, closes one shell without terminating the other, and
checks that shutdown completion follows child reaping. Cmd+Q uses NSTerminateLater while PTYs
close asynchronously, including sessions already closing when quit was requested. A dedicated
PTY regression verifies completion callbacks and run-loop progress for a child ignoring HUP.
The signed Release bundle was launched locally; these checks do not prove all lifecycle or
accessibility requirements. The local evidence report is `build/reports/terminal-mouse-desktop.json`.

Live tests now run isolated zsh, Vim 9.1, less 668 and tmux 3.7c through `MacOsPty`,
and through a real PTY/WebSocket connected to Chromium's WebGL surface. They verify
Unicode command editing and Ctrl+C, Vim paste/file writes/mouse targeting, less search
and page navigation, tmux independent panes, resize and restoration of the shell.
The browser test uses actual keyboard/pointer/paste events where applicable, checks
saved file contents, and captures screenshots. tmux's outer redraw preceded its inner
PTY resize by approximately 250 ms: kernel-size probes and its upstream resize timer
identified a test readiness race. The test now waits for the actual inner PTY size.
Native socket fixtures use short, private temporary paths within the AF_UNIX limit.

Four input-only recordings preserve these producer bytes and resize events. Their
19 retained write/resize checkpoints replay on all three targets against original
libghostty expectations, including whole and bytewise input. This exposed missing
DA2/DA3 and XTVERSION responses. The engine now supplies a conservative VT100 identity,
a zero unit/firmware number, the name Kinetica, and legacy DECID. Nine additional oracle
checkpoints cover those queries, invalid forms, pending wrap and screen/reset transitions.
The oracle uses the same explicit host identity configuration; it still parses and
encodes all replies itself. Reply-reserve tests include the new query families.
All 146 oracle artifacts reproduce exactly. The new five C-helper scenarios pass
AddressSanitizer; LeakSanitizer is unavailable on this macOS runtime and was disabled.
Live reports/screenshots and oracle validation are under `build/reports/terminal-tui/`.
The original checkpoint above covered four applications; the Neovim extension follows.

Neovim 0.12.5 now runs the same live editing workflow through `MacOsPty` and Chromium's
real PTY/WebSocket connection: bracketed Unicode/combining paste, file writes, SGR mouse
targeting, search, resize, arrow input and restoration of the shell. Tests use isolated XDG
directories and no user configuration. Native readiness checks wait for a completed
synchronized frame and the initial cursor before sending editor commands, and verify the
paste is rendered before leaving Insert mode. The first native run failed its saved-file
assertion without checking those readiness boundaries; the revised checks observe them
rather than adding a fixed sleep.

This exposed missing DECRQSS SGR reports: the core now reports active pen attributes and
indexed/RGB colors, plus vertical scrolling margins. It follows the pinned Ghostty formatter,
including its omission of underline color from SGR reports. Neovim's subsequent colored
undercurl is verified as native cell attributes and an actual WebGL pixel, with a retained
screenshot. Three additional oracle scenarios cover all implemented underline styles, color
formats, saved/screen-local pens, reset/resize, malformed/overlong DCS and bytewise input.
Malformed-input replay exposed DCS cancellation behavior: Ghostty unhooks a complete request
on CAN/SUB, whereas Kinetica discarded it. The parser now unhooks DCS on those exits while
still discarding canceled OSC. The oracle pins the resulting replies and subsequent text.
Backpressure tests exercise the maximum implemented SGR reply alongside pending palette
replies. All four new C-helper scenarios pass AddressSanitizer (LeakSanitizer remains disabled).

The input-only Neovim recording adds eight reference checkpoints. Original libghostty
snapshots and transcripts are retained without modification. Its replay declares four
explicit capability differences: unsupported modes 69/2031/2048 answer DECRQM with status 0
instead of Ghostty's status 2, and the unsupported Kitty keyboard query is unanswered.
Only those exact reply substitutions occur in the comparator; other replies and all cell,
cursor, mode, palette, history and synchronization checks remain exact. A separate negative
capability test ensures requests do not enable or advertise these extensions or change the
pen. This is fallback compatibility, not support for those extensions. The full set of 146
oracle artifacts regenerates byte-for-byte. Browser reports, screenshots and sanitizer/reproducibility evidence are under
`build/reports/terminal-tui/`; producer-only archives are in
`kinetica-terminal/test-upstream/tui/`.
Regenerating fixtures concurrently with a compiler once exposed a truncated file read;
generation now skips byte-identical files and replaces changed files atomically.
Extended keyboard protocols, broader TUI configurations and sustained input/frame latency
remain open; these live application workflows alone do not establish complete compatibility.

Selection now uses a shared Kotlin model with row-identity anchors and watches on selected
cell ranges. Unrelated output, palette updates and rows moving into history preserve it;
overwriting selected cells, pruning/recycling selected rows, reset, resize and screen changes
invalidate it. Scrolled views retain their top row until it is pruned, then clamp to the
oldest retained row. Resize intentionally clears selection and retains only an approximate
scroll offset across reflow. Ordinary history updates resolve anchors without rescanning the
selected history; a 10,000-row regression checks the lookup cost and edge-only drag updates.

Both hosts support double-click word and triple-click logical-line selection, dragging at
that granularity, whole wide/combined glyphs and soft wraps. Clicking a wide-glyph wrap spacer
selects the word across the wrap. A shared 50 ms autoscroll timer runs only during an active
out-of-bounds drag and stops on release, cancellation, blur, synchronized hold or disposal.
Frozen frames retain copied selection text even beyond their visible snapshot. This adds
temporary copied-text storage proportional to the selection; row watches are likewise
proportional to selected rows. The public session text-range helper also expands wide-cell
endpoints consistently. Committed keyboard, IME and paste input clear selection.

Fifteen common selection tests cover these invariants, independent views, partial scrolling,
hard/soft line boundaries, directional changes and timer limits. Two AppKit tests use native
NSEvent click counts, mouse dragging, text input and the real run loop. Metal tests verify
highlight pixels across both halves of a wide glyph and repaint after invalidation. The new
Chromium selection verifier tests copy events, actual double/triple clicks, captured dragging,
autoscroll/cancellation, disposal and WebGL/Canvas pixels. Its initial fixed-delay readiness
assertion failed once; repeated runs passed, and it now awaits observed scroll progress before
testing cancellation rather than assuming a timer fires within 120 ms. This is not latency
evidence. Reports and inspected screenshots are under `build/reports/terminal-selection/`.
The existing browser keypad/IME, binary mouse/chord, focus, context-loss and rendering suite
also passes. Rectangular selection, configurable word separators, broader keyboard acceptance
and assistive-technology navigation remain outside this completed selection-anchor gate.

Unicode properties are generated from seven archived, hashed UCD 18.0.0 snapshot
files pinned by Ghostty's uucode dependency. All 853 original grapheme boundary
vectors pass both the standard algorithm and the separately declared terminal
tailoring. Every one of 1,114,112 codepoint widths matches an independent original-
Ghostty reference. Streaming snapshots cover ZWJ emoji, flags, modifiers, VS15/VS16,
Indic/Hangul/spacing marks, mode negotiation, cursor/wrap changes, reflow, and the
64-codepoint suffix limit. The 3,000-operation invariant test now mixes those
sequences, malformed bytes, mode changes and resize; its wide-tail wrapping failure
was minimized, fixed, and retained as an original-Ghostty regression.

History now has both row and accounted-byte limits, enforced during scrolling and while
emitting reflowed rows. Defaults retain at most 32 MiB of accounted history and 64 KiB of
hyperlink payload per physical row. Optional metadata arrays are released when empty;
shared OSC 8 pens are charged once per row. Link exhaustion preserves text and styles while
omitting new destinations, a documented Kinetica policy distinct from Ghostty's allocation
policy. Eight resource tests cover eviction/recovery, merged rows, snapshots/recycling,
1,200 randomized rich-output/resize/screen operations, and atomic UTF-8 queue rejection.
The macOS input queue is a fixed 1 MiB byte ring, with bounded temporary encoding and release
on exit/disposal; its real-PTY test also checks non-ASCII/supplementary input.

`scripts/stress-terminal.mjs` saturates history with unique links and combining strings.
At 127×34, three successive 1,200-row phases retained 374 history rows and 33,470,538 accounting
bytes. Chromium JS heap after GC measured 26,425,416 / 26,504,224 / 26,469,012 bytes; reset reduced
it to 4,142,780 bytes and zero history. The local report is
`build/reports/terminal-resource-stress.json`. These are retained JS-heap measurements, not
GPU/RSS or peak-memory bounds; reflow can retain old and replacement storage simultaneously.
The macOS transport now pauses parsing below a 64 KiB reply reserve and retains at most a
16 KiB unread tail. A common resumable byte decoder preserves UTF-8/VT state and publishes one
damage notification per processed portion. Its cooperative checkpoints run after control
operations and at most 64 ordinary bytes apart. The PTY yields at a 4 ms soft work budget and
256 KiB processed bytes, including any previous turn's tail. Atomic commands and observer
callbacks can exceed the time budget. Nonblocking writes also honor the budget and avoid
repeated EAGAIN syscalls within a turn. Oversized user input calls `onInputRejected` (default
system beep), increments a diagnostic counter and retains no rejected prefix.

The native Release benchmark replays 189 regular files (111,720,611 bytes) from the local
Gradle 9.3.1 `lib` directory at 80×24. The PTY commonly returned approximately 1 KiB per read;
an additional limit of 16 reads per timer turn caused unnecessary delays. Removing that limit
retains the 4 ms / 256 KiB budgets. One run before the fixes took 63.174 seconds through the
PTY, versus 27.200 seconds with both the read-limit and parser fixes (2.32× faster). The isolated
read-limit change took 23.047 seconds with the old parser. Direct parsing took 6.347 seconds
before and 6.317 seconds after. The corrected parser emitted 485 reply bytes instead of 148,
so the final run also does different protocol work. All PTY runs completed with exit code zero
and no rejected input. The report with corpus hashes is `build/reports/terminal-binary-stream.json`.
These are individual measurements with no renderer; they do not compare GUI performance
against other terminals or establish frame-time/input-latency bounds. The packaging script now
defaults to Release, with Debug available explicitly through `--debug`.

Five common backpressure tests verify worst-case pending palette replies plus other query
families, exact resumed replies, UTF-8 splits, command checkpoints, atomic admission and damage
coalescing. A real macOS PTY regression floods 64 maximum-size palette requests while its reader
waits two seconds, then checks every reply byte, timer progress, no rejected replies and released
queues. Another regression disposes from an output observer and verifies the pump stays stopped.
The browser factory now uses a common bounded duplex stream: copied UTF-8 admission, separate
1 MiB input/output rings, deferred consumption credits, partial nonblocking writes, the same
64 KiB reply reserve, and cooperative 4 ms / 256 KiB turns. Nine common tests cover admission,
pause/resume, byte-exact replies, UTF-8/EOF, timer budgets, ring wrap, callback reentry, disposal
and transport/scheduler failures. Applications still supply the wire protocol and peer credit
handling; `receive` rejects an entire chunk when it cannot fit, without acknowledging it.

`scripts/verify-terminal-transport.mjs` tests that contract over a real local WebSocket with
bounded peer credits and a deliberately stalled input consumer. It consumed all 2,097,682
output bytes, compared replies and interleaved Unicode user input byte-for-byte, and finished
with both queues empty. Peak queued output/input/socket bytes were 1,048,576 / 1,009,515 / 65,536.
The sole input rejection was an intentional oversized message. Animation-frame and timer
callbacks continued during the stall; EOF, writer failure and a peer credit violation were
also checked. The local report is `build/reports/terminal-stream-transport.json`. This fixture
is a protocol test, not a browser shell server, and callback progress does not measure presented
frames or input latency. Measured sustained frame/input latency remains open on both platforms.

Verified checkpoint (2026-09-30): 293 common tests pass on JVM, JavaScript and
macOS Native; twenty-three additional macOS tests cover live TUI, the real PTY and Metal/AppKit.
Browser integration checks pass for WebGL pixels, glyph caching, context recovery,
font resize, software fallback, input, history and disposal. WebGL, Canvas and Metal pixel
checks also verify recoloring existing rows after palette changes. Fixture and Unicode generators
reproduce their Kotlin output exactly, and all 182 archived Alacritty source files
match the recorded SHA-256 hashes. This checkpoint does not close the remaining gates.

The release Chromium parser benchmark at this resource checkpoint ran at 127×34 cells,
with a 10,000-line / 32 MiB history policy: five-sample median UTF-8-equivalent
throughput was 30.9 MiB/s ASCII, 20.1 colors, 20.5 legacy Unicode and 17.8 with
grapheme clustering enabled. The grapheme workload retained 9,714 history rows at the
byte limit; the other workloads retained 10,000. Input was decoded strings; painting and transport
decoding were excluded. The local report is `build/reports/terminal-parser-benchmark.json`.
These measurements do not satisfy the frame-time/input-latency gate. The macOS sample
was rebuilt and packaged with Ghostty, uucode, Unicode and X11 runtime license notices; its ad-hoc signature
was verified. No release publication or production-readiness claim follows from this.

Renderer diagnostics now distinguish CPU drawable acquisition, cell-command preparation,
atlas rasterization/uploads, batching and command submission. `TerminalRenderObserver` is
optional and disabled by default. WebGL GPU queries are asynchronous, bounded to eight,
expire after five seconds, discard disjoint/lost-context results and clean up even when a
renderer fails between query begin/end. Metal reports command-buffer GPU duration and actual
drawable presentation timestamps separately, delivering callbacks on the UI thread. Frame
markers are captured from the viewport before rendering, including synchronized-output holds.
Three JS tests cover query backlogs, unsupported/disjoint/lost timers, timeout and disposal;
one native test covers real callbacks, GPU ownership, hide/show, resize, held-frame content,
idle behavior and disposal. This checkpoint passes 293 JVM, 296 JS and 316 Native tests,
plus the existing browser rendering/input/context-recovery verifier.

The new [renderer benchmark commands](terminal-renderer-benchmarks.md) drive a real PTY with
colored Unicode at a target 4 MiB/s and interleaved input markers, at 120×32 cells. The retained
ten-second runs exclude two seconds of warmup and measure six seconds of frames/input. The
final native run on Apple M4 Max's 120 Hz built-in display recorded 446 actual presentations
(74.3/s), all 46 input echoes, and 49.4 ms p95 committed-text-to-presentation latency. Its
programmatically driven window was not the application's key window; visibility was checked.
The final Chromium/ANGLE Metal run recorded 720 submissions and GPU samples, all 47 input
echoes, and 18.1 ms p95 DOM-beforeinput-to-submission latency. Browser CPU/GPU p95 was
0.5/0.125 ms. Its 120 submissions/s do not establish 120 displayed frames/s: browser
presentation and input-to-display values are explicitly unavailable.

Earlier native runs accidentally used different displays; the harness now pins and records
the display. Waiting for presentation before submitting the next frame reduced frame rate
and increased latency in three paired runs. A CAMetalDisplayLink prototype also had worse
observed presentation latency here. Both experiments were removed; the original scheduler
remains. No performance improvement is claimed from adding diagnostics.

One subsequent native run missed presentation evidence for input markers 13–46. Its old
harness failed before saving raw frame/visibility data, so the cause remains unproven; do not
attribute it to occlusion or consider the three-run sequence successful. The harness now
persists failed reports and occlusion transitions and rejects occluded measurements. A fresh
visible run passed all checks. `build/reports/terminal-renderer/validation.json` records this
unresolved observation alongside the passing checks and source hashes. Long overload runs,
browser compositor/presentation measurement, cold/atlas-pressure workloads, event-delivery
latency and broader hardware still remain in the open performance gates.

Native resize verification reproduced compositor stretching while the previous drawable was
still displayed: growing the fixture from 640 to 960 points widened the glyph run by about
188 physical pixels. The Metal layer now retains the old drawable at its original scale,
anchored to screen top-left (bottom-left gravity in its flipped coordinates), with clipping.
The window pixel verifier checks both a deliberately retained drawable and actual AppKit
resizing. All eight captures had identical ink bounds across growth/shrinkage and redraw;
the report is `build/reports/terminal-window/run-0j6yr83g/report.json`. No timing or frame-rate
improvement is inferred from this correctness fix.

The occlusion verifier independently checks WindowServer screenshots after covering and
ordering out a window. The corrected lifecycle test accepts a GPU-completed frame when
restored; a cached surface may be visible without a new positive presentation timestamp.
The final screenshots contain the expected latest output in all three phases
(`build/reports/terminal-window/run-psdv953y/report.json`). This does not explain the earlier
load run with missing presentation evidence, whose cause remains unresolved.

Shell completion now closes its own native tab/window automatically after child reaping.
A real-PTY regression verifies background-tab exit, nonzero exit and Ctrl+D, other sessions
remaining alive, and the last window closing. Existing menu/shutdown tests still pass.
The fresh checkpoint passes 318 terminal Native tests and two native application tests;
JVM/JS were not rerun for these macOS-only changes.

The follow-up resize-flicker fix makes `CAMetalLayer` the view's backing layer, removing the
separate AppKit bitmap around a Metal child. Its persistent theme background covers areas
exposed while an older drawable is retained; resize presentations participate in the Core
Animation transaction. The contrasting-background fixture reproduced exposed strips before
the fix (`run-y5hds6y9`) and now passes all eight retained/redrawn/AppKit captures
(`run-g8polo5h`). The continuous fixture also passes 622 captured frames (`run-2u51v0_5`),
with no terminal-relative displacement, glyph scaling/clipping or exposed host background.
Five frames move the AppKit title and terminal together in the window capture; raw coordinates
are retained, and this is not a claim of zero physical display movement. See the
[capture methodology](terminal-renderer-benchmarks.md).

This checkpoint passes 319 terminal Native tests and two application tests. The IME overlay
test follows the new backing-layer placement. Idle assertions distinguish new frame requests
from late presentation callbacks, and the occlusion fixture lets the initial window settle.
The same production sources pass the cover/hide pixel verifier (`run-ndpe638o`). A subsequent
occlusion run (`run-4l6gzwe8`) still failed to establish the covered state: WindowServer kept
reporting the fixture visible. That fixture instability remains recorded separately from
rendered-pixel results in `build/reports/terminal-flicker-validation.json`; no universal
occlusion or performance guarantee follows from this resize fix.

The packaged Release load check also retained a failure: five input markers lacked presentation
evidence (`terminal-renderer/native-flicker-release.json`). An immediate control using the
previously installed executable failed the same check for three markers
(`terminal-renderer/native-flicker-installed-control.json`). Both recorded about 38 presented
frames/s and substantial drawable-acquisition delays on this desktop. This establishes that
the failure also occurs before the resize change; it does not establish its cause or prove
performance equivalence. Both failed reports remain available, and the performance gate stays
open. The resize validation report explicitly separates these results from its passing checks.

The next incremental macOS update preserves synchronized-output holds across AppKit
measurement. Pixel-only resizes no longer release a hold, and real grid changes are coalesced
until the application's frame ends or its existing hold deadline expires. This passes 320
terminal Native tests and two application tests, including normal completion and abandoned-hold
resize regressions. An actual `htop` resize capture passes 391 frames (`run-uw3to_ai`). Claude's
trust dialog passes 391 frames (`run-knwe96oi`), but two subsequent fixture startups did not
reach its main prompt; main-UI verification is still pending. The user requested installation
of this verified incremental improvement while that investigation continues. Build 3 is
packaged with `Kinetica-Kotlin.icon`; `terminal-tui-resize-validation.json` retains the scope
and failed startup reports. This checkpoint does not claim all TUI flicker is eliminated.

The main Claude UI subsequently reproduced a separate resize jump (`run-cblq577k`): shrinking
the primary grid scrolled away its header before the application's redraw restored it. The
native live-resize path now retains the pre-resize viewport while applying the PTY dimensions
immediately. It releases after output settles, with a non-extending 50 ms deadline; synchronized
output retains that same completed snapshot until its atomic redraw ends. Deterministic tests
cover fragmented redraw, protocol takeover, deadline recovery, repeated resize and disposal.

The final candidate passes 323 Native tests, 12 focused JVM presentation tests, two application
tests. The browser WebGL/Canvas verifier also passed, but a later audit found that this run
served the previous Release artifact: the default Debug build did not update the demo URL.
That browser result does not validate the shared-viewport change. The direct JS test command
has no test task in this project, so browser checks must explicitly build the Release sample.
Actual main-UI
Claude and `htop` captures each exercise 1800 geometry changes over 30 seconds: 1838 and 1835
WindowServer frames respectively have no detected blank/large-drop frames (`run-_wp7x1du`,
`run-_hc8745j`). Their render traces contain 33 and 32 distinct grids, ruling out holding one
unchanged frame for the whole drag. Saved PNGs also show both real interfaces. The static
continuous capture passes 395 frames (`run-xthyssvx`), including glyph geometry and exposed
background checks. These are programmatic native window resizes with live-resize callbacks;
the captures and TUI-content heuristic do not establish physical scanout or universal timing
guarantees. The capture methodology and unsynchronized-output limit remain documented above.

The next glyph-quality regression reproduced different pixel shapes for identical `0` glyphs
in adjacent columns at the actual native font metrics. A ceil-sized atlas bitmap was squeezed
into a fractional cell, causing nearest sampling to discard different texel columns. Batches
now preserve bitmap pixels 1:1, snap to the physical grid, and crop overhang at the cell boundary;
Metal/WebGL normalization uses the actual framebuffer size. The 1× regression also caught and
fixed a bold glyph's final texel blending into its neighbour. Native readback now proves exact
equality across 64 zeros, normal/bold/dim/inverse styles, 1×/2× scales and fractional view sizes.

This checkpoint passes 324 Native tests and two application tests. The freshly built browser
**Release** artifact passes the matching WebGL pixel check at DPR 1, 1.25 and 2 and the existing
WebGL/Canvas/input/lifecycle verifier. That fresh run also covers the earlier shared viewport
change which the stale browser run had missed. Colored `htop` passes 656 resize captures
(`run-jp2ujgr2`) and fixed glyphs pass 671 (`run-41yi2l78`). The diagnostic screenshots retain
actual color output; the earlier monochrome launch was caused by the automation runner's
inherited `NO_COLOR=1`. Build 5 and `terminal-glyph-validation.json` record this checkpoint.

Build 6 fixes seams in solid Unicode Block Elements, including Claude's quadrant artwork.
The previous font-based path left background pixels inside a full-block cell in real Metal
readback. The common viewport now emits exact cell-relative rectangles for U+2580–U+2590 and
U+2594–U+259F; both GPU and software painters align their edges to physical pixels. Shaded
blocks and combined sequences retain the font path. Common tests cover all 29 solid shapes,
colors/inverse/faint/concealment, selection, decorations and text-run boundaries. Metal tests
compare every artwork pixel at 1×, 1.25× and 2×. All 328 Native tests pass, along with the fresh
Release browser's WebGL/Canvas block tests at two font sizes and three DPRs, repeated-digit
tests and existing integration suite. A real isolated Claude window also passes 1070 resize
captures with no detected anomalies (`run-3tv_bwol`); its screenshot was inspected for seams.
The reproduction, hashes and verification logs are recorded in `terminal-block-validation.json`.
