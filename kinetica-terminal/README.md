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
one glyph batch per atlas page, then cursor/decorations (at most six draw calls for text-only frames). They redraw the
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

Canvas, WebGL, AppKit and Metal views render static and animated Kitty graphics from direct RGB/RGBA, PNG and
zlib transmissions. Images follow source crops, placement scaling, three z layers, scrollback,
relative placement anchors and screen switches. The lowest layer shows through default cell
backgrounds; explicit backgrounds remain opaque. Synchronized output and resize holds retain
the completed image generation together with the text frame, including during WebGL recovery.

`BrowserTerminalView` and `AppKitTerminalView` accept `graphicsStorageBytes = 64 * 1024 * 1024`,
enabling a 64 MiB decoded image budget per screen by default; pass zero to disable command capture and replies. This setting
belongs to the session, so views sharing a session should use the same value. Each browser
renderer separately owns a 64 MiB LRU cache of bounded image tiles with sampling gutters.
The native renderers use the same budget and tiling. Offscreen/deleted tiles and disposed views
retire their bitmap/texture cache entries. WebGL/Metal include retired textures still used by
unfinished commands in the 64 MiB budget. Budget pressure completes those commands before
reclaiming pixels or allocating more tiles; Metal resumes with a load-preserving render pass.
Ordinary Metal frames remain asynchronous. Image draws copy their small instance data into
command-owned storage. The budget counts RGBA tile payloads, not driver allocation overhead,
glyph atlases or decoded session storage.
The host
still owns session image data after a view is disposed. Diagnostics expose
`data-terminal-image-bytes`, `data-terminal-image-tiles` and cumulative
`data-terminal-image-uploads`. Unicode placeholders (`U+10EEEE`) select image IDs from raw
foreground colors and placement IDs from underline colors. Diacritics supply row/column/high
ID bits; adjacent compatible cells inherit omitted coordinates. Virtual images preserve aspect
ratio within their requested grid, follow ordinary text scrolling/reflow and always draw below
text. Relative children use the minimum visible origin of their parent's placeholder cells.
Animation commands load, edit, compose, select and delete frames and control gaps, playback and
loop counts. A one-shot timer requests the next render; that render advances playback on a
shared monotonic clock. A stalled renderer does not spin timers or catch up by skipping frames.
Hidden/detached views and render holds suspend playback; showing the view or releasing the hold
resumes it. Finite, stopped and unplaced animations become idle.
`TerminalSession.attachGraphicsMedia` lets a host explicitly authorize and resolve file,
temporary-file and shared-memory transmissions. The host receives the decoded path/name,
unsigned offset and exact size, format and byte limit; it returns owned bytes or a fixed
protocol error synchronously on the session thread. Without an attached host, external media
return an unsupported-medium error.
The common executor supports queries, image/frame loads, quiet replies and both screens.
On macOS, `MacOsTerminalGraphicsMedia` provides the native readers. Every medium defaults to
denied; an embedding host can enable the ones it authorizes:

```kotlin
val media = session.attachGraphicsMedia(MacOsTerminalGraphicsMedia(
    allowFiles = true,
    allowTemporaryFiles = true,
    allowSharedMemory = true,
))
// Detach when the host closes its session.
media.dispose()
```

The native application explicitly enables these readers for its local PTY sessions. Library
views do not enable them automatically. Relative paths use the application's working directory.
Files must be regular files; validation uses the opened descriptor's canonical path. Temporary
files must be within a recognized temporary directory and their canonical path must contain
`tty-graphics-protocol`. After that validation they are deleted even on read/decode failure.
Shared-memory names pass unchanged to `shm_open` and are unlinked after a successful open,
including later failures. Reads are bounded by the protocol and optional `maxReadBytes` cap
(400 MiB by default); only the requested shared-memory range plus page alignment is mapped.
File reads and memory copies are synchronous. These path rules follow Ghostty and do not
provide race-free deletion against another process replacing a pathname or shared-memory name.

The decoded-byte budget is not a total memory bound:
RGB-to-RGBA animation conversion follows upstream admission semantics and can exceed it;
transfer assembly, decoder scratch buffers and immutable held-frame generations have separate
lifetimes. Compressed raw RGB/RGBA decoding retains only the declared pixel prefix and
publishes that owned buffer without another full copy. Extra animation-frame bytes are
discarded while still validating the whole zlib stream and its expansion limit. Compressed
PNG retains its encoded PNG buffer for the second decoding stage. WebGL/Metal textures used
by unfinished commands are included in their separate renderer budget described above.
Run `node scripts/verify-terminal-graphics.mjs` against the built browser
demo for actual Canvas/WebGL pixel, scroll, context-loss and resource-lifecycle checks.
`NativeGraphicsTimingTest` exercises automatic playback and hide/show, detach and disposal in
isolated AppKit/Metal windows. `NativeGraphicsRenderingTest` reads actual offscreen pixels, including fractional
Metal scales, image orientation, crop/alpha/layers, synchronized frames and tile seams.

Pass an optional `renderObserver: TerminalRenderObserver` to `BrowserTerminalView` or
`AppKitTerminalView` to measure rendering. `onFrameStarted` supplies the viewport actually
being drawn (including synchronized-output holds); capture input/content markers there.
`onFrameSubmitted` reports separate CPU times for acquiring a drawable, updating cell drawing
commands, rasterizing/uploading atlas misses, batching, and submitting draw commands. Software
painters interleave layout and drawing, so report their whole CPU cost as submission.
`onGpuTime` reports asynchronous GPU execution duration, or null when unavailable. WebGL uses
at most eight timer queries, expires stalled queries after five seconds, discards disjoint or
lost-context results, and cancels polling on disposal. Timing queries do not force synchronous
GPU waits or readback. Image-budget pressure may wait independently; Metal duration includes
all completed batches of a split frame.
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
direction has a 1 MiB queue, configured by `TerminalStreamLimits`. Clipboard replies have an
additional lazy 16 MiB reserve for one complete clipboard response (see OSC 52/5522 below). The server must limit total
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
Ordinary pending input is limited to 1 MiB; callers must
split larger pastes and allow the transport to drain. The queue uses one bounded byte ring,
allocated lazily; tiny writes cannot accumulate separate queued objects. Overflow rejects the
whole input without enqueuing a prefix and calls `onInputRejected` (a system beep by default).
It does not throw from the UI event loop. Large strings are rejected before UTF-8 encoding; the
encoding temporary is bounded to 3 MiB. Exit and disposal release the queue and unread output.
The transport pauses parsing when fewer than 64 KiB are free for replies, enough for a complete
maximum-size pending OSC palette query plus the remaining commands in a read slice. Draining
input resumes that exact position. OSC 52 replies can grow the ring by a separate bounded
allowance of 11,184,821 bytes, enough for Base64 of an 8 MiB read plus its header and terminator.
They remain atomic with existing input; output parsing pauses while ordinary capacity is exhausted.
Exhausting the combined allowance rejects the entire new response through `onInputRejected`.
The extra allocation is released when the ring drains. The kernel PTY
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
- DEC reverse video (mode 5) swaps the effective default foreground and background for all
  renderers. `TerminalViewport.theme` reflects the displayed colors, including retained frames;
  `TerminalSession.theme` and OSC 10/11/21 replies keep the underlying color identities.
  Explicit SGR colors, palette entries, cursor/selection colors and image RGB are unchanged.
  SGR inverse applies after default resolution. Mode changes repaint existing rows and preserve
  the old displayed theme during synchronized output. The mode supports query/save/restore,
  survives DECSTR and screen changes, and resets on RIS.
- DEC mode 1036 (enabled by default) controls the ESC prefix on legacy Alt text.
  Disabling it sends the host's text unchanged; enabling it preserves single-byte shifted
  punctuation and uses the unshifted layout scalar for Option-translated Unicode. Browser
  input uses its known layout map; AppKit applies this when `optionAsMeta` is enabled.
  C0 combinations and modified functional keys use their own tables independently of 1036:
  Shift+Enter/Control+Enter and modified Tab/Escape remain distinct in legacy mode, and
  Control+i/m/[ use fixterms. Kitty and modifyOtherKeys retain their own encodings.
  Mode 1039, ANSI SRM 12 (default set), DEC slow-scroll 4 and autorepeat 8 (default reset)
  retain queryable state, matching Ghostty 1.4's ignored runtime behavior. They do not enable
  local echo, delay scrolling or suppress key repeats. All these modes survive DECSTR and
  screen switches; RIS restores defaults. Private modes also support save/restore.
- Mouse modes 1000/1002/1003 support X10, UTF-8 (1005), SGR (1006), urxvt (1015) and
  SGR pixels (1016), with left/middle/right, back/forward, vertical/horizontal wheel, modifiers
  and releases outside the viewport. Cell formats suppress repeated motion within one cell;
  pixel mode retains every motion event, including subcell movement.
  Legacy reports preserve raw 8-bit coordinates through both transports; coordinates above 223
  cannot be represented in that format. Shift starts local selection; its release does not leak
  an application button release. Each view owns its motion/gesture state. The browser handles
  pointer capture and chorded buttons; AppKit registers and disposes its own tracking area.
  Both views supply physical pixels, accounting for browser DPR or AppKit backing scale.
  Custom hosts use `sendMousePixels` with `TerminalMouseGeometry`; the cell-only `sendMouse`
  API emits no report in pixel mode because it cannot reconstruct a pointer position.
  Pixel coordinates are zero-based, rounded with the original encoder's f32 precision and
  retained outside the viewport for dragging/releases. Non-finite input is rejected; values
  outside signed 32-bit pixel range saturate as a recovery policy (the original can trap).
  The last enabled format wins; disabling any format selects X10. Requested mode bits remain
  independently queryable/saveable. DECSTR preserves them; RIS clears them. `sgrMouse` describes
  the requested 1006 bit; `mouseFormat` describes the active wire format.
- Mode 2026 keeps parsing and replying while surfaces retain the frame captured at the exact
  start-of-hold escape sequence. Each view copies only its visible rows and freezes their palette,
  theme and cursor. Both hosts release abandoned holds after one second; repeated enable requests
  cannot extend the deadline. Reset, EOF, and an explicit `resize` also release the hold, even for
  unchanged dimensions. A view mounted during a hold stays blank until release. GPU context
  recovery and forced software redraws use the captured frame.
- Custom renderers can subscribe to `onRenderHold` and call `flushSynchronizedOutput` on their
  deadline. Direct `TerminalViewport` embeddings should supply a `TerminalScheduler` whose
  cancellable callbacks run on the owning thread, and report host visibility through
  `setVisible`. The scheduler also drives animation redraws. Without a scheduler, the caller
  drives hold release and animation ticks, and the cursor remains in its visible blink phase;
  no background thread is created.
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
  guarantee. The parser retains at most 4096 UTF-16 units per ordinary control string, 64 parameters and
  four intermediate bytes. These independent limits also bound the current and saved pens.
  OSC 52 has a separate UTF-8 byte limit: its capture after `52;`, including a trailing NUL,
  must fit within 8 MiB, matching the pinned Ghostty. Completion/cancellation releases that
  capture's large backing storage. Conversion to strings/bytes adds bounded temporary copies.
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
and X10/UTF-8/SGR/urxvt/SGR-pixel mouse encoding. Unknown OSC/DCS payloads are consumed
without printing, with bounded parser storage. Clipboard access requires an explicitly attached host and authorization policy.
DECSCA and ISO SPA/EPA protect cells; selective ED/EL and ordinary erasure follow
Ghostty's distinction between DEC and ISO protection. Protection survives save/restore
and reflow. Named differences from Alacritty are recorded in the upstream test adapter.

Legacy charset state follows Ghostty 1.4: G0–G3 can designate ASCII, British (`#` → `£`)
or DEC Special Graphics through `ESC (` / `)` / `*` / `+`. SI/SO and LS2/LS3 select GL;
SS2/SS3 apply to exactly the next printable codepoint, including combining marks. Control
sequences do not consume a pending single shift. GR invocation is retained but does not
remap UTF-8 characters, matching Ghostty. Unknown designations leave the previous set intact.
Mapping preserves codepoints above 255 and DEC underscore. Saved cursors retain the entire
charset state, including pending single shift; screen switches copy the active charset and
1049 exit restores its saved state. DECSTR/RIS restore default designations and invocation.
Restoring an unset saved cursor selects the initial state and home position.

DECCOLM (`CSI ? 3 h/l`) selects 132/80 columns only when DEC mode 40 is enabled.
Otherwise it is ignored, including screen erasure. An accepted command resizes both
screens, clears the active display with the current background/protection rules and homes
the cursor. Width changes reset tab stops; primary history reflows only with DECAWM enabled.
Mode queries/save/restore, DECSTR and RIS follow Ghostty. Geometry changes reach the attached
transport/PTY through `onResize`. Browser/AppKit handle `onSizeRefreshRequested` for every
mode-40 set/reset by restoring their measured grid; headless sessions retain their current
dimensions. Subsequent host resizing can replace a DECCOLM width. Resizing also releases a
synchronized frame with paired host notifications, including the notification omitted by
libghostty-vt's internal DECCOLM resize path.

DECSTR (`CSI ! p`) resets the active pen, margins, saved cursor, selected modes, cursor
appearance, palette and checksum variant while retaining text, history, cursor position,
tab stops, dynamic colors, hyperlinks, synchronized output and graphics. DECSASD (`CSI Ps $ }`)
accepts main/status-line selection; like Ghostty, printable output directed to the unimplemented
status line is discarded, while controls still execute. RIS and DECSTR select the main display.

DECRQCRA (`CSI Pi;Pg;Pt;Pl;Pb;Pr * y`) and XTCHECKSUM (`CSI Ps # y`) require explicit host
opt-in: `session.configureChecksumReporting(true, extension = 0)`. They are disabled by default,
as in Ghostty, because per-cell queries can read other programs' screen output. `extension`
sets both the current variant and the value restored by RIS/DECSTR; accepted bits are 1
(positive sum), 2 (omit attributes), 4 (keep blanks), 8 (include undrawn cells), and 16 (full
codepoints). Combinations 0–31 are valid; for example, 23 matches xterm's
`no-negate,no-attributes,no-trim,full`. Terminal commands cannot enable this host policy.
SGR blink state is retained for checksums and DECRQSS; text blinking remains unrendered.

Device identification supports DA1/DA2/DA3 (`CSI c`, `CSI > c`, `CSI = c`), legacy
DECID (`ESC Z`), and XTVERSION (`CSI > q`). The conservative profile reports
`CSI ? 1;2 c` (VT100 with advanced video), `CSI > 0;0;0 c`, a zero unit ID, and
the name `Kinetica`. It advertises no image/clipboard features and discloses no
host identifiers. The primary parameters have their VT100 meaning, not the
VT220 feature-bit interpretation; see [XTerm controls](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html).
Request parsing follows the pinned Ghostty's parameter-tolerant behavior. Invalid
prefixes/intermediates and primary-DA replies produce no identification reply.

XTGETTCAP (`DCS + q`) answers all 272 static capabilities from the pinned Ghostty terminfo
table, including colors, booleans and parameterized control strings. Hex keys are case
insensitive; unknown/malformed keys are skipped, and each known key gets its own ST-terminated
reply. `session.terminfoName` supplies `TN`; null, empty or more than 128 UTF-8 bytes means
unknown. This configuration survives resets and screen changes. `MacOsPty` sets it from the
child's actual `TERM`, including an explicit environment override. Other transports must
configure it when they know the peer's terminal profile.

Requests accept up to 1 MiB of DCS payload, excluding the header. Replies are streamed in
blocks of at most 32 KiB, with only one block awaiting delivery. The bounded transports pause
PTY/server output until the response finishes; custom async hosts must use `writeAvailable`
and acknowledge `TerminalInputData.ProtocolReply` delivery. Untracked `onInput` observers
retain synchronous operation. Reset/transport closure cancels unsent blocks. Run
`node scripts/verify-terminal-terminfo.mjs` to check the browser integration.

`CSI 21 t` reports the current terminal title as `OSC l … ST` only after the host calls
`configureTitleReporting(true)`. Reporting defaults to disabled, like Ghostty: it returns
application-controlled title bytes to the foreground program's input. The policy survives
RIS/DECSTR and screen switches; RIS clears the title itself. No window geometry is required.
OSC 0/2 title capture accepts at most 2047 bytes and rejects invalid UTF-8 before changing
state. The libghostty handler then retains the first 1024 bytes, even if the cut splits a
character. `session.title` uses replacement text for display, while reports preserve those
exact bytes. `node scripts/verify-terminal-title.mjs` checks actual browser transport replies.

Color commands accept X11 names, `rgb:`/`rgbi:`, and 3/6/9/12-digit `#` forms, plus bare
3/6-digit hex. Queries preserve BEL versus ST and report 16-bit channels. Chained requests,
partial malformed lists, and resets follow the pinned Ghostty behavior. Colors are shared by
primary/alternate screens. RIS resets palette overrides and preserves dynamic foreground,
background and cursor colors; OSC 104 and 110–112 restore configured defaults.
Unsupported pointer, selection, Tektronix and special-color targets are consumed without
effects or replies. The archived X11 names and license can be regenerated offline with
`python3 scripts/generate-terminal-x11-colors.py`; provenance is in `third-party/x11-rgb.json`.

Kitty Color Protocol (`OSC 21`) sets, resets and queries palette entries, foreground,
background and cursor colors. Replies combine queries in request order and retain BEL/ST,
using 8-bit RGB channels. Unsupported special colors are ignored like the pinned libghostty.
Requests exceeding its 2048-byte payload or 528-operation limit are rejected atomically.
OSC 21 and the xterm color commands share state and configured reset defaults.

OSC 22 controls the mouse pointer separately from the text cursor. All 34 W3C names and
Ghostty's xterm/foot aliases are accepted; an empty request restores `text`, other invalid
names are ignored and RIS preserves the
last requested shape. Browser views apply CSS cursor styles. AppKit uses Ghostty's native
mapping (text, arrow, hands, axial resize, context menu, crosshair and not-allowed); unsupported
native shapes preserve the previously displayed cursor. `pointerShape` and `onPointerShape`
expose application requests to custom hosts, independently of hover overrides.

Unicode widths and grapheme properties come from a pinned Unicode 18.0.0 snapshot,
shared across targets. Mode 2027 handles combining text, ZWJ emoji, flags, skin-tone
modifiers and VS15/VS16 width changes, including wrapping and reflow. Use
`TerminalSession(graphemeClustering = true)` to enable it initially; its default is
false, matching the reference library. Applications can set/reset/query the mode;
reset restores the configured default. Ambiguous characters are narrow, and each
cell retains at most 64 suffix codepoints. See [Unicode data and policy](test-upstream/unicode/README.md).

Kitty keyboard negotiation supports query, set/add/remove flags and bounded push/pop stacks,
independent for the normal and alternate screens. All five progressive enhancements are encoded:
disambiguation, press/repeat/release, alternate keys, all-key reporting and associated Unicode text.
AppKit and browser views forward the event type and layout information while retaining IME ownership
and local application shortcuts. `TerminalKey` also exposes these fields for custom hosts.
Browsers without the Keyboard Layout Map API infer unshifted punctuation from previously observed
keys, with a US-layout fallback; exact first-key alternate reporting depends on host layout data.
The default remains legacy input until an application negotiates Kitty.

ANSI keyboard action mode (`CSI 2 h/l`, KAM) is tracked and queryable. As in Ghostty,
hosts must explicitly set `keyboardLockAllowed = true` before applications can block
typing. `sendKey` and committed `sendText`/IME input then respect the lock; raw
`sendInput`, protocol replies, local shortcuts and paste remain available. The pure
`encodeKey` function does not apply host policy. RIS and DECSTR clear the requested
lock while retaining the host's permission setting. Both AppKit and browser views
use these paths for keyboard and IME input.

Numeric `modifyOtherKeys` mode 2 (`CSI > 4 ; 2 m`) follows the pinned Ghostty encoder;
Kitty takes precedence while its flags are active. AppKit and browser input support
modified Enter/Tab and printable keys, without encoding IME commitments twice.

DECBKM (`CSI ? 67 h/l`) swaps plain Backspace and Ctrl+Backspace between DEL and BS in
legacy input. Ghostty's Shift/Alt/Super combinations and Kitty keyboard encoding do not use
that swap. The mode supports query and private save/restore, survives DECSTR and screen
changes, and resets on RIS. Backspace events carrying printable dead-key/composition text
are consumed without sending deletion bytes. Host-local shortcuts still precede terminal input.
DECRQM reports fixed-reset state 4 for ANSI 1/5/7/10/11/13–19 and DEC 117; set/reset attempts
cannot change those fixed reports. Other unimplemented modes remain unrecognized.

The color-scheme query (`CSI ? 996 n`) and mode 2031 notifications use the host's
configured theme classification, independently of OSC color overrides. `configureTheme`
infers light/dark from the background by default; hosts may pass an explicit
`TerminalColorScheme` or call `configureColorScheme`. Notifications are sent only
while subscribed, including during synchronized output, and RIS clears the subscription.

Visibility queries (`CSI ? 998 n`) and mode 2033 report `CSI ? 999 ; 1 n` for a
potentially visible terminal and `CSI ? 999 ; 2 n` for a hidden terminal. Every mode
enable reports immediately; subsequent notifications require a visibility change.
AppKit supplies window occlusion, attachment and ancestor visibility; browser views
supply document, intersection, attachment and CSS visibility. Custom hosts use
`attachVisibility(initiallyVisible)` and update/dispose the returned binding on the
session thread. A shared session is visible if any attached host is visible. Unknown
headless visibility starts true; disposing the last host makes it false. RIS clears
the subscription while retaining host visibility, and DECSTR preserves both.

OSC 7501 reports program state (`idle`, `working`, `done`, `blocked`, `error`) and
optional identity, app, progress, block kind, title and message. Register
`onProgramStatus` to enable reports and support-query replies; without a listener,
the protocol is not advertised and reports are dropped, matching libghostty's
effect callback contract. The event includes the accepted report and a snapshot
of `programStatuses`; lifecycle-only cleanup has a null report. The last listener's
disposal releases all records. Views do not subscribe or present statuses automatically.

The bounded registry retains up to 256 records, replacing each report's record
completely and evicting the least recently updated record at capacity. `clear`
removes a record and its descendants, or everything when no id is given.
`programStatusApp(id)` resolves the nearest ancestor's app. OSC 133 A, transport
EOF and abrupt transport disposal remove `working`/`blocked` records while retaining
`idle`/`done`/`error`. RIS removes all records; DECSTR and screen switches preserve them.
Custom transports call `finishInput()` at process EOF. Text and field limits follow
the pinned Ghostty parser; decoded titles/messages are plain untrusted text, never
markup or terminal input. Presentation and notification policy belong to the host.

In-band resize mode 2048 reports committed rows, columns and physical pixel dimensions
on enable and resize. `resize(columns, rows, TerminalCellSize(width, height))` supplies
per-cell pixels after display scaling; AppKit/browser views supply this automatically.
The same geometry answers `CSI 14/16/18 t`. Before the first resize no geometry is
available to report. A resize without explicit cell size reuses the last size, or zero
pixels if unknown. Repeated explicit resizes still report, and RIS resets the subscription.

`CSI 8;rows;columns t` emits `onWindowResizeRequested(TerminalWindowResizeRequest)`.
Zero or omitted dimensions mean keep the current dimension; requests retain the original
unsigned 16-bit values. The event is synchronous, including during synchronized output,
and does not resize the grid or send a reply. Without a subscribed host it has no effect.
Hosts must explicitly permit window changes, schedule layout outside parser dispatch,
constrain it to their geometry limits and call `resize` with the actual measured result.
Dispose the subscription when its host closes.

Standalone surfaces and DSL host factories accept `windowResizePolicy`, denied by default
like Ghostty's `vt-window-resize-allowed`. Accepted requests merge their nonzero axes into
one 16 ms deferred operation; policy and host identity are checked again before layout.
Detach, reattachment and disposal cancel stale work. Requested axes have Ghostty's minimum
40 columns / 10 rows and the engine's 4096-per-axis / one-million-cell bounds. Omitted axes
preserve the actual pixel extent, including any fractional cell remainder.

AppKit resizes its owning ordinary window, including the surrounding UI, within the window's
size limits and screen's visible frame; hidden, minimized and full-screen windows are ignored.
Its normal measurement path then updates the grid and PTY. Browser views call the host's
`resizeContainer(TerminalWindowSize)` with CSS pixels; null axes remain unchanged. The browser
DSL factory also supplies the session ID. The callback owns container layout and additional
viewport constraints; the existing ResizeObserver measures the result. The native sample has
an opt-in Settings choice, and the browser sample has an unchecked “Allow app resizing” control.

REP (`CSI Ps b`), scroll-and-clear (`CSI 22 J`) and DEC mode save/restore
(`CSI ? Ps s` / `CSI ? Ps r`) are supported. Scroll-and-clear moves written rows into
primary history, subject to its strict limits; alternate screens retain no history.
X10 mouse mode 9 reports only left/middle/right presses, without modifiers, motion,
releases or wheel events. The chosen legacy/SGR encoding remains independent of tracking.

Horizontal margins (DECSLRM, mode 69) constrain scrolling and character/line editing.
Origin mode applies both horizontal and vertical offsets. Margin reports, saved modes,
wide characters, grapheme wrapping and resize follow the pinned Ghostty oracle.

BS and CUB support reverse-wrap mode 45 and extended reverse-wrap mode 1045. The first
crosses soft-wrapped lines; the second also crosses hard line breaks and wraps from the
top to the bottom of the scrolling region. Extended mode takes precedence when both
are enabled. Both require autowrap (mode 7), consume a pending wrap before moving, and
follow Ghostty's margin/outside-region behavior. Mode queries and DEC mode save/restore
are supported; RIS and DECSTR disable both reverse-wrap modes.

OSC 7 stores its raw payload in `workingDirectoryUri`; an empty report or reset clears it.
`onWorkingDirectory` observes every accepted report, including repeated values, immediately
even during synchronized output. Like libghostty, reset clears state without a report event.
Payloads must fit in 2047 UTF-8 bytes. URI parsing is a host decision, not a parser side effect.
`localWorkingDirectory(localHostname)` accepts `file` and `kitty-shell-cwd` URIs with an explicit
`localhost` or matching machine authority. It decodes file paths as UTF-8, preserves literal
Kitty paths, and rejects malformed escapes, NULs and remote authorities. The native sample
uses existing local directories for new tabs/windows, falling back to the PTY's directory.

OSC 133 supports `A/N/P/B/I/C/D/L` prompt, input, output and fresh-line transitions.
`TerminalLine.promptMarker` identifies prompt/continuation rows, `semanticContent(column)`
identifies individual cells, and the session exposes `cursorAtPrompt`, `cursorSemanticContent`
and `hasSemanticPrompts`. Marks survive scrolling, editing, reflow and independent screens;
soft wraps preserve input state while `I` ends input at an explicit line feed.
`onSemanticPrompt` delivers accepted commands after applying state, even during render holds.
Its bounded options expose command IDs, errors, exit codes, prompt kinds and click preferences;
`commandLineBytes()` decodes `cmdline`/`cmdline_url` without executing anything. Invalid encodings
return null. Click preferences are metadata for custom hosts; they do not synthesize mouse/key input.

Like libghostty-vt, a new embedded session assumes no shell redraw support. `A/N;redraw=1`,
`redraw=0` and `redraw=last` select full, disabled or current-line prompt clearing on primary
screen resize. RIS restores Ghostty's terminal default (full redraw). Alternate grids are not
cleared for prompt redraw. `TerminalViewport.jumpToPrompt(-1/+1)` and both view wrappers navigate
relative to the viewport, skip continuations, and do nothing on alternate screens or frozen frames.
The native sample exposes Previous/Next Prompt in its Shell menu; embedding apps can use the
same API. These features require the shell to emit OSC 133; the library does not install shell scripts.

OSC 52 supports Base64 clipboard writes, clearing and `?` reads, including optional padding,
binary data at the engine boundary and BEL/ST-preserving replies. `attachClipboard(backend, policy)`
binds one host to the session. Both view constructors and DSL host adapters accept `clipboardPolicy`;
they deny access by default. `WRITE_ONLY` permits remote applications to copy text without reading
the user's clipboard; `ALLOW` permits both. A custom `TerminalClipboardPolicy` can show a permission
dialog and complete asynchronously on the session thread. The samples explicitly select `WRITE_ONLY`.

The AppKit and browser backends support the standard text clipboard; primary/selection destinations
return an empty read and ignore writes. Custom backends receive all three destinations and raw bytes.
Native tests use an isolated pasteboard. Invalid UTF-8 writes leave text clipboards unchanged.
Browsers additionally enforce their secure-context, permissions and user-activation requirements.
Denied/failed reads reply empty; writes have no acknowledgement. With no attached host, requests
have no effects or replies, matching libghostty's absent callbacks.

Requests are serialized so a read sees preceding writes. Up to 16 requests and 8 MiB of decoded
write data can wait for authorization or I/O; excess requests are dropped atomically. Read results
over 8 MiB receive an empty response. Reset cancels uncompleted requests; replacing/disposing a host
invalidates its pending callbacks. Effects already submitted to the OS cannot be undone. Ordinary
copy/paste gestures retain their existing behavior independently of OSC 52 policy.

OSC 5522 supports MIME reads, available-type listings, chunked writes, aliases, clearing,
status replies and remembered password grants. Supply a `TerminalMimeClipboard` implementation
through `session.attachMimeClipboard(backend, policy)`. `TerminalMimeClipboardPolicy.DENY`
is the default; `WRITE_ONLY`, `WRITE_AND_PASTE`, `ALLOW` or a custom asynchronous policy can authorize operations.
Requests expose the untrusted application name, direction, MIME types and grant state, never
the password itself. A host must commit all write representations atomically, and report
unsupported destinations or I/O errors explicitly. Without an attached host, reads return EPERM
and writes return ENOSYS. Both platform views and DSL host adapters accept a separate
`mimeClipboardPolicy`, defaulting to `DENY`; both samples explicitly select `WRITE_AND_PASTE`.
The views also accept a custom `mimeClipboard` backend and detach it on disposal.

With mode 5522 enabled, AppKit Paste and trusted browser paste events send a MIME-type listing
and a cryptographically generated one-use read password instead of inserting clipboard text.
`WRITE_AND_PASTE` allows that follow-up read and writes, without granting unsolicited reads.
Explicit `DENY`/`WRITE_ONLY` policies still reject the follow-up read. Clipboard data is fetched
only for the requested formats. Failures do not fall back to inserting text.
Custom hosts should call `session.pasteClipboard()` only for trusted user clipboard gestures;
its `false` return allows ordinary text paste when the mode or host is unavailable. A `true`
return means handled, including failures; the optional completion reports delivery or cancellation.
`session.paste(text)`, dropped paths, IME input and synthetic browser paste events remain text input.
Mode queries report unknown without a MIME host; save/restore and RIS/DECSTR follow Ghostty.
Up to sixteen gestures can wait behind clipboard I/O. Host replacement, reset and transport
failure cancel pending gestures and revoke their passwords.

The AppKit MIME adapter maps types through Uniform Type Identifiers, including custom binary
formats, and publishes all representations as one pasteboard item. Listings inspect only declared
types without loading lazy providers. As in Ghostty 1.4, copied files are available as shell-escaped
paths under `text/plain` and CRLF-separated URLs under `text/uri-list`; the latter specifically
represents file copies. Primary/selection destinations are unsupported. Reads enforce an aggregate
8 MiB limit, and writes enforce 64 MiB after expanding aliases to distinct native formats.
Invalid UTF-8 plain text, conflicting representations of one native type, and oversized writes
are rejected before changing the clipboard. AppKit requires clearing before publishing: a system
publication failure can leave an empty clipboard and reports an I/O error. The adapter never reads
the previous clipboard to implement rollback under a write-only policy.

The browser adapter uses the [asynchronous Clipboard API](https://www.w3.org/TR/clipboard-apis/#async-clipboard-api) with its permission, secure-context
and user-activation requirements intact. Native types such as plain text, HTML and PNG are
preferred; other supported MIME types use Web Custom Formats. Those custom formats use the
browser's clipboard namespace and may not be exposed to native applications. HTML sanitization
and image re-encoding follow browser behavior. A listing reads `ClipboardItem.types` without
calling `getType`; data reads request only the selected representations, within an aggregate
8 MiB limit. Writes prepare one item with all formats and enforce a 64 MiB aggregate budget
after alias expansion. Unsupported representations reject the whole write. Disposing the view
stops deferred reads before fetching further formats and discards late replies; the browser
cannot abort an already submitted write. Primary/selection locations and clearing return ENOSYS:
`Clipboard.write([])` is a no-op, and the API has no asynchronous clear operation.

Use `TerminalStreamConnection`, `BrowserTerminalConnection` or `MacOsPty` when attaching an
asynchronous MIME host. These adapters retain input while authorization, clipboard I/O or reply
delivery waits. Custom feeders can call `writeAvailable(bytes, offset, length)`, retain the
unconsumed suffix and schedule a retry from `onOutputReady`. The synchronous `write` methods
reject continuation while `outputPaused` is true. A custom input transport must call
`ProtocolReply.trackDelivery()` during `onInput`, then complete that receipt with `true` after
accepting all response bytes, or `false` on rejection/closure. Built-in adapters do this automatically.
Once OSC 5522 is active, clipboard operations
are ordered across OSC 52 and OSC 5522; ordinary output continues until another clipboard command
needs the host. At most one such complete packet is retained outside the parser.

OSC 5522 captures less than 8 MiB per packet, excluding the OSC prefix, matching Ghostty's
allocating parser. Writes have a 64 MiB decoded transaction budget. Reads serve at most four
requested types, with an 8 MiB aggregate data budget and a 16 MiB encoded-response budget.
The complete response is admitted atomically, so keyboard input and other replies cannot split
its DATA/DONE sequence. Delivery acknowledgements prevent multiple large responses from filling
the same reserve. Replacing a host cancels its waiting responses; bytes already submitted to a
transport finish in order. RIS clears grants and pending host requests but preserves an unfinished
write assembly, as in Ghostty 1.4. Detaching a host or closing its transport discards that assembly.

OSC 9 and `OSC 777;notify;title;body` emit `onNotification` events. Recognized ConEmu
commands are kept separate from notification text. OSC 9;4 emits `onProgress` reports for
remove, determinate, error, indeterminate and paused states; missing percentages remain null,
valid values clamp to 100, and RIS emits remove. These events are immediate during synchronized
output. Parsing follows the pinned Ghostty's 2048-byte capture limit and malformed-command rules.

Both surfaces display progress above the terminal grid: errors are red, pauses orange, and
missing percentages animate (except pause, which displays 100%). Every report restarts a
15-second timeout. Remove, reset and surface disposal clear the indicator and its timers.
The overlay does not consume terminal rows or intercept pointer input.

Desktop delivery requires an explicit `notificationPolicy` on a view or DSL host factory;
the library default denies delivery. `requestNotificationPermission` is intended for a user
action. Terminal output never opens an OS permission prompt. Samples expose an “Enable Terminal
Notifications…” menu action on macOS and an “Enable notifications” browser button. Delivery also
requires OS/browser authorization. macOS uses UserNotifications from a bundled `.app`; foreground
presentation remains under the embedding application's notification-center delegate. Browser
delivery uses the Notifications API and its secure-context restrictions.

`TerminalNotificationDelivery` allows a custom host backend. Default backends replace previous
surface notifications and clear them on focus/disposal. The macOS backend permits one pending
OS operation and coalesces additional requests to the latest value; stale completions clean up
only their own request identifiers. Notification tests intercept desktop delivery, validate real
`UNMutableNotificationContent` fields and exercise permission/race handling without posting user
notifications. AppKit progress pixels and browser progress state/expiry are checked separately.

This is an initial VT implementation, not complete xterm conformance. Known limits:

- No sixel, ligatures or blinking text. Hyperlink activation and search controls
  are currently provided by the native host; browser hosts can use the shared search/link APIs.
  DECRQSS supports cursor style, SGR attributes and vertical/horizontal margins;
  its SGR report omits underline color, matching the pinned Ghostty reference.
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
  TUIs continue to receive wheel reports when mouse tracking is active. On macOS, momentum
  scrolls local history (including the Shift override), but is not converted into TUI mouse
  reports or alternate-screen cursor keys: those protocols cannot cancel queued scrolling.
  Direct vertical trackpad movement in those TUI modes uses a 2× multiplier, retaining
  fractional movement and the per-event input limit. Ordinary wheels and local history
  keep their existing speed.
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
node scripts/verify-terminal-mime-clipboard.mjs
node scripts/verify-terminal-graphics.mjs
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
library; it is not a runtime dependency. The C oracle is pinned to Ghostty 1.4.0; separate
Zig animation and placeholder corpora retain their previous reference during the
[migration](../docs/ghostty-1.4-migration.md). Adapted runtime code carries Ghostty's MIT license;
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
