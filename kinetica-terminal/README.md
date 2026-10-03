# Shared Kotlin terminal

`kinetica-terminal` contains a common Kotlin VT engine and glyph batching layer, browser
WebGL2 surface, and macOS Metal surface hosted in AppKit. The macOS transport runs a local shell
through a real PTY. The browser binds
to an application-provided transport, usually a WebSocket connected to a server-side PTY.
The JVM target supports headless use and engine tests.

Terminal bytes never become UI text nodes or reactive cells. A platform view observes
the session directly; embedders own its mount and disposal. Packed `IntArray` cell storage, recycled lines,
byte- and line-bounded circular scrollback, damage tracking, and retained drawing commands for clean rows keep
work tied to the visible screen. Browser redraws coalesce with `requestAnimationFrame`; AppKit
invalidates damaged row rectangles. Both GPU backends draw instanced glyph rectangles from a
bounded atlas. Performance parity with xterm.js and sustained 120 fps are not established.

## Rendering

`BrowserTerminalView(..., rendering = ...)` and `AppKitTerminalView(..., rendering = ...)`
accept `TerminalRendering.AUTO` (default), `GPU`, or `SOFTWARE`. AUTO prefers WebGL2/Metal and
falls back to Canvas 2D/AppKit drawing if initialization or drawing fails. GPU requires the
GPU backend and reports failures; SOFTWARE explicitly selects the original platform painter.
The enum selects our renderer: platform Canvas/AppKit implementations can themselves use GPU acceleration.

The shared `TerminalGpuFrame` retains cell drawing commands for clean rows. `TerminalGlyphAtlas`
packs up to four 1024×1024 RGBA pages (16 MiB of atlas textures per surface). Missing cells are
rasterized by an offscreen Canvas 2D context or AppKit/Core Graphics, at the current font size
and display scale. Repeated glyphs reuse atlas entries across text colors; colored emoji retain
their rasterized colors. Bold and italic have separate entries. Font/scale changes reset the
atlas. Exhausting it rebuilds the entire visible glyph set, so no submitted instance references
an evicted slot. A visible set that still cannot fit triggers AUTO fallback or a GPU-mode error.

Solid Unicode Block Elements (U+2580–U+2590 and U+2594–U+259F) are drawn as cell-aligned
rectangles in the shared viewport. They bypass font bearings and line spacing, so adjacent
blocks and quadrant artwork join without seams. GPU and software painters snap rectangle
edges to physical pixels. Shaded blocks and blocks with combining marks still use the font.

Both backends reuse packed instance arrays, with 12 floats per rectangle, and draw backgrounds,
one glyph batch per atlas page, then cursor/decorations (at most six draw calls). They redraw the
visible framebuffer and upload the visible instance batches each frame; partial GPU buffer
uploads and scroll-only transforms are future optimizations. Only changed rows are rebuilt on
the CPU. This is cell-based terminal rendering, not a general shaping/layout engine for editors.

WebGL context loss pauses drawing and restores shaders, buffers, and the atlas when the browser
restores the context; the session/input remains alive. Canvas fallback handles GPU unavailability
and renderer errors. Metal keeps one command buffer in flight, coalesces pending updates, and
reuses buffers only after completion. Interactive rendering never calls `waitUntilCompleted`
or reads pixels back. AppKit layers preserve the native IME composition overlay.

For diagnostics, the browser host exposes `data-terminal-renderer`, `data-terminal-glyphs`,
`data-terminal-rasterizations`, and `data-terminal-draw-calls`. The native view exposes
`renderingBackend` and `renderingFailure`. Kadingirra/Babylon are not dependencies of these
graphics backends; the JVM target currently remains a headless engine, not a desktop surface.

Pass an optional `renderObserver: TerminalRenderObserver` to `BrowserTerminalView` or
`AppKitTerminalView` to measure rendering. `onFrameStarted` supplies the viewport actually
being drawn (including synchronized-output holds); capture input/content markers there.
`onFrameSubmitted` reports separate CPU times for acquiring a drawable, updating cell drawing
commands, rasterizing/uploading atlas misses, batching, and submitting draw commands. Software
painters interleave layout and drawing, so report their whole CPU cost as submission.
`onGpuTime` reports asynchronous GPU execution duration, or null when unavailable. WebGL uses
at most eight timer queries, expires stalled queries after five seconds, discards disjoint or
lost-context results, and cancels polling on disposal. No synchronous GPU wait/readback is used.
These diagnostics are disabled by default. Observers run on the UI thread and must be short,
read-only and nonthrowing; IDs are local to a mounted surface.

Only Metal also supplies `onFramePresented`, using the drawable's actual presentation host
time. Zero denotes a dropped/unpresented drawable. Browser rAF, submission and GPU execution
are separate from display presentation, so the WebGL observer cannot establish physical FPS
or input-to-display latency. See [renderer benchmarks](../docs/terminal-renderer-benchmarks.md)
for reproducible real-PTY workloads and the limits of each measurement.

## Browser

```kotlin
val session = TerminalSession(columns = 80, rows = 24, scrollback = 10_000)
val surface = BrowserTerminalView(session)
document.querySelector("#app")!!.appendChild(surface.view)
// On unmount: surface.dispose(); surface.view.parentNode?.removeChild(surface.view)

session.write("\u001b[32mHello from Kotlin\u001b[0m\r\n")
```

Give the mount element a definite width and height. The surface fits the terminal grid to
that element, measures its monospace font, and scales the canvas for device pixel ratio.
Each mounted view currently determines the dimensions of its session; use separate sessions
for independently sized terminals.

Wire a binary transport with `BrowserTerminalConnection(session, transport, onInputRejected,
onFailure)`. The factory supplies browser timers to the common `TerminalStreamConnection`.
Implement `TerminalByteTransport` for your WebSocket framing:

- `write(bytes, offset, length)` consumes/copies an accepted prefix and returns its byte count,
  or zero while blocked. Never retain or modify the borrowed array. The library retries a
  blocked writer on an 8 ms timer; `writable()` is also available as a readiness hint.
- `resize(columns, rows)` receives the initial grid and later changes. Coalesce unsent resizes
  in the adapter, and keep protocol control messages bounded too.
- `consumed(bytes)` returns output credits to the remote producer **after parsing and damage
  publication**. Aggregate unsent credits into a counter instead of queuing individual messages.

Call `receive(ByteArray)` with UTF-8 output. It copies accepted bytes into a fixed-capacity
ring and returns immediately; it does **not** acknowledge them. A false return admits no prefix.
Retry that range later, or close a producer that exceeded its credit window. By default each
direction has a 1 MiB queue, configured by `TerminalStreamLimits`. The server must limit total
unacknowledged output to the output capacity, including packets still in transit, and pause its
PTY until credits arrive. Checking the browser WebSocket's `bufferedAmount` or peer input credits
before accepting a write bounds the other direction. Browser WebSocket buffering itself is
outside this library; a cooperative wire protocol is essential.

Parsing yields at a 4 ms soft budget or 256 KiB, with checkpoints after controls and at most
64 ordinary bytes apart. It reserves room for pending protocol replies, so a blocked writer
also pauses output parsing safely. Input rejection invokes the required `onInputRejected`
callback without admitting a prefix; report it to the user. Callback/transport failures stop
the connection, release queues/subscriptions/timers, and invoke `onFailure` once. That callback
should report the disconnection and close your socket. Transport callbacks must be nonblocking.

`finishOutput()` delivers EOF after all accepted output and flushes incomplete UTF-8 once;
`outputFinished` then becomes true. It does not close the transport's input direction. Dispose
the connection when disconnecting; discarded output receives no credits. Queue sizes, paused
state, rejection count and failure are observable on the returned connection.

The old synchronous `BrowserTerminalConnection(session, send, resize)` prototype has been
replaced: adapters now work with byte ranges and explicit consumption credits. Already decoded
local text can still use `session.write(String)`. The library does not start a server, open a
network connection, or choose an authentication or WebSocket framing protocol.

Keyboard input uses a textarea with IME composition support. Drag to select, use the platform
copy shortcut to copy, and paste normally. Shift bypasses application mouse reporting for
local selection; Shift+PageUp/PageDown and the wheel navigate scrollback. Hidden text exposes
the visible output to assistive technology, without announcing every output chunk.

Both hosts preserve numeric-keypad identity in `TerminalKey.keypad`. `ESC =` / `ESC >` and
DEC mode 66 select application/numeric keypad mode; DECRQM reports the selected state.
Following Ghostty, mode 1035 defaults to enabled and forces numeric encoding. Applications
that need the VT application keypad disable it with `CSI ? 1035 l` and enable mode 66.
Digits, decimal, operators and keypad Enter then send SS3 sequences with Shift/Alt/Ctrl
modifier parameters. Main-keyboard Return stays distinct. Logical navigation keys produced
with NumLock off follow cursor/editing-key modes, rather than the physical digit position.
Numeric mode preserves the platform's decimal separator and leaves keypad digits/operators
literal even with modifiers; Meta/Command and active IME composition remain host-owned.

Keypad equal and separator use the XTerm VT220 sequences `SS3 X` and `SS3 l`, respectively;
these extend the pinned Ghostty encoder, which lacks application entries for those two keys.
Numeric keypad Enter honors Kinetica's linefeed mode like ordinary Return; application keypad
Enter remains `SS3 M`. Custom hosts supply the logical key plus `TerminalKeypadKey` identity,
so a locale's comma decimal separator can still encode the decimal application key (`SS3 n`).

## macOS

```kotlin
val session = TerminalSession()
val surface = AppKitTerminalView(session)
surface.setFrame(window.contentView!!.bounds)
window.contentView = surface
val pty = MacOsPty(session) // /bin/zsh -l, starting in the user's home directory

// On window close, on the main thread:
pty.dispose()
surface.dispose()
```

`MacOsPty` accepts an executable, argument list, environment overrides, working directory,
and `onExit` callback. Arguments are passed directly to `execve`, without shell interpolation.
`onExit` receives an exit code, `128 + signal` for signal termination, or `-1` if unavailable.
The PTY starts with `TERM=xterm-256color` and `COLORTERM=truecolor`; applications must stay
within the supported subset below. Override the environment when a different profile is needed.

Read/write operations are nonblocking. The run loop polls every 8 ms, reads at most 256 KiB
per turn and yields at a 4 ms work budget. Budget checks run after each control operation and
at most 64 ordinary input bytes apart. A single VT operation/observer callback is atomic and
can exceed that soft budget. Parsing retains an unread tail of at most 16 KiB and preserves
partial UTF-8/escape state across turns; one processed portion publishes one damage notification.
Short PTY reads do not impose an additional syscall-count limit: a flooded macOS PTY can return
roughly 1 KiB per read even with a 16 KiB destination. `receivedBytes` and `readOperations`
expose transport throughput without recording terminal content.
Pending input is limited to 1 MiB; callers must
split larger pastes and allow the transport to drain. The queue uses one fixed-capacity byte ring,
allocated lazily; tiny writes cannot accumulate separate queued objects. Overflow rejects the
whole input without enqueuing a prefix and calls `onInputRejected` (a system beep by default).
It does not throw from the UI event loop. Large strings are rejected before UTF-8 encoding; the
encoding temporary is bounded to 3 MiB. Exit and disposal release the queue and unread output.
The transport pauses parsing when fewer than 64 KiB are free for replies, enough for a complete
maximum-size pending OSC palette query plus the remaining commands in a read slice. Draining
input resumes that exact position; replies are neither dropped nor duplicated. The kernel PTY
then supplies output backpressure when the child is not reading input. This protects memory;
a child that never resumes reading can of course remain stalled.
`pendingInputBytes`, `bufferedOutputBytes`, `outputPausedForInput` and `rejectedInputCount`
expose transport state without retaining input content. The reserve is pinned by common tests;
new response-generating protocols must update those tests and the reserve if necessary.
Closing hangs up the PTY and reaps its
child off the UI thread, escalating to termination after a bounded grace period.

The native surface uses `NSTextInputClient` for marked text and committed input. Command+C/V
copy and paste. Option supports macOS text input by default; pass `optionAsMeta = true` to
`AppKitTerminalView` for escape-prefixed Option keys. Drag selection, wheel scrolling,
Shift+PageUp/PageDown, and accessibility text are available.

## Engine and lifecycle

- Confine a session and its observers to one thread. Mounted surfaces and the macOS transport
  use the UI thread. Marshal off-thread transport output before calling `write`.
- `write(ByteArray)` retains partial UTF-8 and escape sequences across calls. `write(String)`
  retains split UTF-16 surrogate pairs. Use one representation consistently per stream.
  Call `finishInput()` at EOF to flush an incomplete character.
- Escape/CSI/DCS headers use raw byte transitions, including C1 controls, following the
  pinned Ghostty parser. Ground-state text decodes UTF-8 and ignores decoded C1 characters;
  OSC/DCS text payloads retain their separate bounded string handling. String input applies
  the same UTF-8 wire representation within headers, without publishing nested damage.
- `observe` reports changed row ranges once per ordinary write. `onInput` receives outgoing
  `TerminalInputData`, including terminal protocol replies; `onResize` receives dimension changes.
  All return disposable subscriptions. Notifications are synchronous. This replaces the earlier
  prototype's `String` callback: `Text` carries Unicode to encode as UTF-8, while `Bytes` carries
  immutable protocol bytes. `toByteArray()` returns a fresh wire representation. Never decode
  legacy mouse reports as text and re-encode them. The provided stream/PTY adapters admit each
  event atomically to their bounded queues, checking oversized text before allocating UTF-8.
- Mouse modes 1000/1002/1003 and SGR mode 1006 support left/middle/right, back/forward,
  vertical/horizontal wheel, modifiers, releases outside the viewport and cell motion suppression.
  Legacy reports preserve raw 8-bit coordinates through both transports; coordinates above 223
  cannot be represented in that format. Shift starts local selection; its release does not leak
  an application button release. Each view owns its motion/gesture state. The browser handles
  pointer capture and chorded buttons; AppKit registers and disposes its own tracking area.
- Mode 2026 keeps parsing and replying while surfaces retain the frame captured at the exact
  start-of-hold escape sequence. Each view copies only its visible rows and freezes their palette,
  theme and cursor. Both hosts release abandoned holds after one second; repeated enable requests
  cannot extend the deadline. Reset, EOF, and an explicit `resize` also release the hold, even for
  unchanged dimensions. A view mounted during a hold stays blank until release. GPU context
  recovery and forced software redraws use the captured frame.
- Custom renderers can subscribe to `onRenderHold` and call `flushSynchronizedOutput` on their
  deadline. Direct `TerminalViewport` embeddings should supply a `TerminalScheduler` whose
  cancellable callbacks run on the owning thread. Without a scheduler, the caller drives hold
  release and the cursor remains in its visible blink phase; no background thread is created.
- `cursorStyle` and `cursorBlink` constructor arguments configure the appearance restored by
  DECSCUSR default/RIS. Defaults are a steady underline. Block, underline and bar cursors support
  mode 12 and DECRQSS cursor-style replies. Host blink timers run every 500 ms only while focused,
  visible and following output; unfocused views show an outline. Holds pause blinking. Unmount
  cancels both blink and hold timers and releases captured rows.
- `TerminalLine` exposes live read-only views. Scrollback can recycle a line after later writes;
  copy its text if you need an immutable snapshot.
- `limits = TerminalLimits(historyBytes = ..., hyperlinkBytesPerLine = ...)` configures retained
  storage budgets. Defaults are 32 MiB of accounted primary history and 64 KiB of link payload
  per physical row. History obeys both this budget and `scrollback`, evicting oldest rows before
  retaining new ones. An oversized newest row retires the old history too, so retained history
  stays a contiguous suffix. Active rows are preserved. `historyStorageBytes` reports primary
  history storage, including while the alternate screen is active.
- These are accounting bytes, **not an exact heap/RSS limit**. A row charges 128 bytes plus
  16 bytes per packed cell; optional reference arrays charge 32 + 8 bytes per column each.
  Combined strings charge 32 + 2 bytes per UTF-16 unit, attributes 32 bytes per populated cell,
  links 96 + 2 bytes per URI/ID UTF-16 unit, and link-map capacity 64 bytes per peak distinct
  entry. A single OSC 8 pen shared across a row is charged once there; other rows and snapshots
  charge it independently. Optional arrays/maps are released when empty. VM object headers,
  allocation rounding, collector behavior and renderer resources vary by platform. The ring's
  fixed reference table (at most `scrollback` entries) is separate from the row-storage budget.
- Link exhaustion omits only new destination metadata; text, colors and underline survive.
  Existing references to an admitted OSC 8 pen remain usable. A new OSC 8 command creates a new
  pen even for an equal URI. Erasing its last row reference releases that payload budget. URI
  strings are never truncated into different destinations. Reflow applies the destination row's
  budget, so merging rich rows can drop some links. Set the link budget to zero to omit links.
  This is Kinetica resource policy, not a claim of Ghostty-equivalent exhaustion behavior.
- Both screen buffers remain bounded by at most 1,000,000 cells each (axes at most 4096), with
  64 suffix codepoints per cell and the per-row link budget. View snapshots copy only visible
  rows. Resize can temporarily retain old and new storage together; limits are not a peak-memory
  guarantee. The parser retains at most 4096 UTF-16 units per control string, 64 parameters and
  four intermediate bytes. These independent limits also bound the current and saved pens.
  A synchronized-output hold also retains the copied text of an existing selection, which
  can extend beyond the visible rows. Selection watches use space proportional to selected rows.
- Pass `theme = TerminalTheme(...)` to `TerminalSession` to configure foreground, background,
  cursor and selection colors. `session.theme` returns the effective colors used by every
  surface and by OSC reports. `paletteColor(index)` returns a current 256-color palette entry.
  Cells retain palette indices internally; their public foreground/background/underline getters
  return current RGB values, or `-1` for the corresponding default. Palette changes repaint
  existing screen and history text; direct RGB cells keep their color. Themes belong to sessions.
- `resize` reflows primary-screen text and scrollback, including wide cells, styles, hyperlinks,
  and current/saved cursor positions. Hard line breaks stay distinct from soft wraps. The alternate
  screen crops its physical grid so full-screen applications can redraw it. Reflow enforces both
  history budgets while emitting rows; resizing to one column discards wide glyphs,
  matching Ghostty. Unused bottom rows are trimmed before content is moved into history.
- `TerminalViewport` owns per-view damage, scroll position and selection. Selections and
  scrolled viewports follow row identities through history instead of numerical row offsets.
  Unrelated output and palette changes preserve selected text; overwriting selected cells,
  pruning selected rows, resize, reset or switching screens clears the selection. When a
  scrolled top row is pruned, the view stays at the oldest retained row. Reflow preserves an
  approximate scroll offset, not a text anchor across changed wrapping.
- Double click selects a word and triple click selects a logical line, including soft wraps
  and its final newline. Word boundaries use whitespace and shell punctuation; paths and URLs
  remain together. Dragging extends in the starting granularity. Copy expands partial wide
  cells to whole glyphs, preserves combining sequences and omits wide-glyph wrap padding.
  Dragging beyond the top/bottom autoscrolls at 50 ms intervals, up to ten rows per tick.
  Release, pointer cancellation, focus loss, synchronized-output hold and disposal stop the timer.
  Committed keyboard/IME/paste input clears selection; copying leaves it intact.
- Sessions are application-owned. Unmounting a view removes its observers, listeners and
  scheduled redraws, but does not close a shared transport. Dispose transports explicitly.
- Session IDs label platform surfaces for diagnostics. Dispose a view before removing it;
  the session and transport remain application-owned. Observers and timers return
  `TerminalDisposable`, with no dependency on a UI framework.
- Kinetica host DSL adapters live in `../kinetica-terminal-ui`. The app shell comes from
  the external Kinetica framework; the engine and standalone views remain independent.

## Compatibility scope

Implemented: streaming UTF-8, CR/LF/BS/tab, delayed autowrap, cursor positioning/save/restore,
erase and insert/delete characters/lines, scroll margins, origin and insert modes, independent
normal/alternate screen save slots and distinct 47/1047/1049 behavior, SGR attributes with
semicolon/colon 16/256/RGB colors and colored single/double/curly/dotted/dashed underlines,
OSC 4/104 palette set/query/reset and OSC 10/11/12/110/111/112 dynamic colors,
DEC line drawing/alignment, title and hyperlink OSC, status/cursor reports, application cursor/keypad keys, function/navigation keys, bracketed
paste, focus reporting, synchronized output, cursor shapes/blinking, DECRQSS cursor-style,
SGR and vertical-margin queries,
and legacy/SGR mouse encoding. Unknown OSC/DCS payloads are consumed
without printing, with bounded parser storage. OSC clipboard commands are not executed.
DECSCA and ISO SPA/EPA protect cells; selective ED/EL and ordinary erasure follow
Ghostty's distinction between DEC and ISO protection. Protection survives save/restore
and reflow. Named differences from Alacritty are recorded in the upstream test adapter.

Device identification supports DA1/DA2/DA3 (`CSI c`, `CSI > c`, `CSI = c`), legacy
DECID (`ESC Z`), and XTVERSION (`CSI > q`). The conservative profile reports
`CSI ? 1;2 c` (VT100 with advanced video), `CSI > 0;0;0 c`, a zero unit ID, and
the name `Kinetica`. It advertises no image/clipboard features and discloses no
host identifiers. The primary parameters have their VT100 meaning, not the
VT220 feature-bit interpretation; see [XTerm controls](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html).
Request parsing follows the pinned Ghostty's parameter-tolerant behavior. Invalid
prefixes/intermediates and primary-DA replies produce no identification reply.

Color commands accept X11 names, `rgb:`/`rgbi:`, and 3/6/9/12-digit `#` forms, plus bare
3/6-digit hex. Queries preserve BEL versus ST and report 16-bit channels. Chained requests,
partial malformed lists, and resets follow the pinned Ghostty behavior. Colors are shared by
primary/alternate screens and survive RIS; OSC 104 and 110–112 restore configured defaults.
Unsupported pointer, selection, Tektronix and special-color targets are consumed without
effects or replies. The archived X11 names and license can be regenerated offline with
`python3 scripts/generate-terminal-x11-colors.py`; provenance is in `third-party/x11-rgb.json`.

Unicode widths and grapheme properties come from a pinned Unicode 18.0.0 snapshot,
shared across targets. Mode 2027 handles combining text, ZWJ emoji, flags, skin-tone
modifiers and VS15/VS16 width changes, including wrapping and reflow. Use
`TerminalSession(graphemeClustering = true)` to enable it initially; its default is
false, matching the reference library. Applications can set/reset/query the mode;
reset restores the configured default. Ambiguous characters are narrow, and each
cell retains at most 64 suffix codepoints. See [Unicode data and policy](test-upstream/unicode/README.md).

This is an initial VT implementation, not complete xterm conformance. Known limits:

- No image protocols, ligatures or blinking text. Hyperlink activation and search controls
  are currently provided by the native host; browser hosts can use the shared search/link APIs.
  DECRQSS supports cursor style, SGR attributes and vertical margins;
  its SGR report omits underline color, matching the pinned Ghostty reference.
- Kitty keyboard, modifyOtherKeys, horizontal margins (mode 69), color-scheme notifications
  (2031) and in-band resize (2048) are not implemented or advertised. Applications use the
  legacy key encoder; Neovim's live tests cover that fallback.
- Mouse input surfaces support clicks, dragging, wheel and all-motion tracking.
  Local selection supports character/word/line dragging and autoscroll; rectangular selection
  and configurable word separators are not implemented.
- Accessibility exposes current screen text; it does not yet provide xterm.js-level navigation
  and announcement behavior.

## Native application shortcuts

The macOS sample uses native window tabs, each with its own session, shell process and renderer.
Cmd+T creates a tab, Cmd+N creates a window, Cmd+W closes the current tab, Cmd+Shift+[ / ] switches
between tabs, Cmd+1…9 selects a tab directly, and Cmd+Q exits. New tabs inherit the active
shell's current directory. Menus use AppKit key equivalents. OSC titles update the tab title;
a finished shell closes its own tab/window. Quitting waits
asynchronously for all owned PTYs to reap their children before AppKit terminates the process.
`MacOsPty.close(onClosed)` exposes that completion boundary to other application hosts.

- Cmd+F searches retained output, including soft-wrapped Unicode text. Cmd+G / Cmd+Shift+G
  and Return / Shift+Return select the next/previous result; Escape closes the search bar.
  Search is literal, optionally case-sensitive, and reports at most 10,000 matches (`+` means more).
- The scrollbar overlays retained output while scrolling or navigating history, then hides
  after one idle second. It stays hidden when there is no history; ordinary terminal output
  does not reveal it. Its visibility never changes the grid width. Precise trackpad deltas accumulate into whole rows;
  TUIs continue to receive wheel reports when mouse tracking is active.
- Cmd+plus (or Cmd+=), Cmd+minus and Cmd+0 zoom/reset the current terminal. Cmd+comma opens
  Settings for the installed monospace font, size and theme; settings apply to every window
  and persist across launches. Window position and size are also saved.
- Cmd+click opens OSC 8 links and detected HTTP(S) URLs. Cmd+A selects retained output;
  right-click provides Copy, Paste, Select All and clearing actions. Shift bypasses TUI mouse reporting.
- Cmd+K clears the screen while retaining the current prompt line; Cmd+Shift+K clears scrollback.
  Neither resets the shell or VT modes. Dropping files pastes shell-quoted paths without executing them.
- Control shortcuts fall back to physical Latin key positions with a non-Latin input layout,
  so Ctrl+C also works with Russian selected. Ordinary Cyrillic and IME text stay unchanged.

Embedders enable these native controls by wrapping their view in `AppKitTerminalPane`;
`AppKitTerminalView` remains a bare terminal view. Both platform views expose `configureFont(family, size)`.
The common engine exposes `search`, `hyperlinkAt`, `configureTheme`, `clearScreen`, `clearHistory`,
and viewport selection/reveal methods for hosts with their own controls.

## Run and verify

```sh
./kotlin run -m native-terminal
# Optional .app bundle:
bash scripts/package-native-terminal.sh

./kotlin build -m browser-terminal -p js -v release
python3 -m http.server 4173 --bind 127.0.0.1
# Open http://127.0.0.1:4173/samples/browser-terminal/web/index.html

# Live macOS tests require tmux and nvim, or KINETICA_TMUX / KINETICA_NVIM paths.
./kotlin test -m kinetica-terminal -p jvm -p macosArm64
./kotlin build -m kinetica-terminal -p js -v release
node build/artifacts/CompiledWebArtifact/kinetica-terminaljsTestrelease/kotlin-output/kinetica-terminal_test.mjs
node scripts/verify-terminal.mjs
node scripts/verify-terminal-blocks.mjs
node scripts/verify-terminal-glyphs.mjs
node scripts/verify-terminal-selection.mjs
node scripts/verify-terminal-transport.mjs
node scripts/verify-terminal-tui.mjs
node scripts/benchmark-terminal.mjs
node scripts/stress-terminal.mjs
```

App packaging defaults to Release. Use `--debug` explicitly for a debug build, or
`--output 'build/apps/Another Terminal.app'` to package a separate copy. `--skip-build` reuses
the selected variant's existing executable.

The selection verifier exercises real Chromium click counts and pointer capture with both
WebGL and Canvas, checks copied text and highlight pixels, and saves screenshots/report under
`build/reports/terminal-selection/`. Common tests cover history/recycling, screen changes,
wide/combined cells, soft wraps, synchronized output and deterministic drag timers. Native
tests cover AppKit mouse events, run-loop autoscroll cancellation and Metal selection pixels.

Live macOS tests use the system zsh, Vim and less, plus installed tmux and Neovim. Set
`KINETICA_TMUX` to select its executable; native tests also search common Homebrew
locations and `PATH`. Set `KINETICA_NVIM` to select the Neovim executable with the same
native search behavior. The browser TUI verifier defaults to `/opt/homebrew/bin/tmux` and `nvim`
and uses Python 3 for a real local PTY, with the same Playwright setup as the other
browser verifiers. Both runners create isolated files/sockets and bypass user rc
files. They check Unicode edits on disk, mouse positioning, search/page navigation,
interrupts, independent panes, resize and return to the shell. Neovim additionally verifies
negotiated colored undercurl and arrow navigation after resize. Browser captures,
screenshots and the report are saved under `build/reports/terminal-tui/`.
The [archived recordings](test-upstream/tui/README.md) replay against independent
libghostty expectations on all common-test platforms without those live dependencies.

The native sample also provides byte-stream diagnostics for a directory of regular files:

```sh
./kotlin build -m native-terminal -p macosArm64 -v release
build/tasks/_native-terminal_linkMacosArm64Release/native-terminal.kexe --benchmark-parser /path/to/files
build/tasks/_native-terminal_linkMacosArm64Release/native-terminal.kexe --benchmark-pty /path/to/files
```

The first measures parsing time separately from file reads. The second runs `/bin/cat` through
the real PTY and reports completion time, read counters and run-loop timer progress. Neither
starts a graphics surface or measures presented frames. Binary payloads are never echoed to
the calling terminal. The PTY line discipline may expand LF to CRLF, so its byte count can
exceed the source size; command completion is checked from the child's exit status.

The common tests include pinned Alacritty recordings and Ghostty-derived parser/UTF-8
regressions, with deterministic chunked replay. See [test-upstream](test-upstream/README.md)
for licenses, provenance, generation and documented behavior differences. The test-only
[Ghostty oracle](test-upstream/ghostty/README.md) generates snapshots from the original
library; it is not a runtime dependency. Adapted runtime code carries Ghostty's MIT license;
Unicode/uucode and X11 data retain their own notices,
also included under `resources/META-INF/LICENSES` and in packaged native demo bundles.

Production readiness is tracked in [the completion checklist](../docs/terminal-production-readiness.md).
Passing these corpora does not yet establish production readiness or full VT conformance.

The browser demo is local echo with a few demonstration commands, explicitly not a shell.
The native sample runs a real shell. The browser verifier requires Playwright/Chromium;
`PLAYWRIGHT_IMPORT`, `PLAYWRIGHT_CHROMIUM_EXECUTABLE`, and `KINETICA_BROWSER_BASE_URL` can select
existing installations. It checks WebGL/Canvas dynamic-palette pixels, glyph reuse, font changes, context recovery,
Canvas fallback, input, IME, paste, resize, history bounds, retention and disposal, and writes a
screenshot to `/private/tmp/kinetica-terminal-browser.png` by default. The native tests also render
to a real Metal texture and check pixel colors, text, colored emoji, cache reuse, and scale changes.
Additional pixel checks exercise atomic updates, context recovery during a hold, cursor shapes
and browser blinking. Deterministic common tests cover deadlines, paired callbacks, continuing
input/replies, immediate repaint/scroll anchors, multiple views and timer cancellation; an AppKit
run-loop test verifies recovery from an abandoned hold.
The [native window pixel verifier](../docs/terminal-renderer-benchmarks.md#macos) additionally
captures its isolated window after occlusion and during resize, including the interval before
a new drawable arrives. It requires macOS Screen Recording permission and uses offline OCR
to check text and glyph geometry; offscreen texture tests cannot detect compositor stretching.
The transport verifier runs a loopback WebSocket server with byte-credit windows in both
directions. It checks UI timer/frame progress while outgoing replies are blocked, exact binary
replies interleaved with Unicode keyboard input, queued EOF, oversized-message rejection and
JavaScript transport failure cleanup. It uses the WebSocket server bundled with the selected
Playwright installation; the fixture protocol is not an imposed application protocol.
The native reply-flood test uses the macOS system Perl to write maximum-size palette requests
while withholding stdin reads for two seconds, then compares every reply byte and checks run-loop
timer progress, zero rejected replies and released queues. Separate regressions check oversized
user input recovery and disposal from an output observer. Common tests exercise cooperative
parsing across UTF-8 boundaries, reply reservations, command checkpoints and damage coalescing.
The benchmark measures decoded-string parsing, buffer mutation and invalidation, with painting
excluded. It reports five-sample median throughput for ASCII, color sequences, Unicode and
mode-2027 grapheme sequences; it
does not establish end-to-end latency or equivalence to another emulator.
The separate stress script saturates the history budget with unique links, combining strings
and underline colors, then checks retained Chromium JS heap after garbage collection across
three output phases and reset. Its heap measurements exclude GPU memory and process RSS.
