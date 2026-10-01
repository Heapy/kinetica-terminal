# Terminal renderer measurements

Migration note: measurements below were recorded in the original Kinetica worktree.
Untracked `build/reports` evidence remains there and is not included in this source repository.

The benchmarks use the same bounded Python PTY producer on both platforms: ten seconds of
colored Unicode scrolling at a target 4 MiB/s, a 120×32 grid, two seconds of warmup and six
seconds of frame/input samples. Input markers go through the terminal input adapter and real
PTY and are echoed into a reserved top row. The observer associates that row with the frame
**before** rendering; it never reads newer session content from a completion callback.
The producer has at most 64 KiB of user-space output backlog. It runs in raw terminal mode
and restores the PTY discipline on exit. The browser additionally uses the common bounded
stream and a credit-limited loopback WebSocket; this fixture is not a production shell server.

Build Release first, finish tests/builds, then run the benchmarks sequentially. Do not compare
runs made on different displays or while compilation is consuming CPU. Raw frame samples and
environment metadata are retained alongside p50/p95/p99/max distributions. Short runs are
diagnostic evidence, not a universal throughput or latency guarantee.

```sh
./kotlin build -m kinetica-terminal -m browser-terminal -m native-terminal -p js -p macosArm64 -v release
mkdir -p build/reports/terminal-renderer
```

## macOS

```sh
build/tasks/_native-terminal_linkMacosArm64Release/native-terminal.kexe \
  --benchmark-renderer "$PWD/scripts/terminal-render-load.py" \
  "$PWD/build/reports/terminal-renderer/native.json"
```

This opens its own window and child PTY, then closes/reaps them. It does not replace the
packaged app or interact with existing terminal sessions. The window is placed on the
connected display with the highest advertised refresh rate; the report records the display
identity, refresh capability, scale and GPU. `AppKitTerminalView.insertText` supplies committed
text, so the latency excludes hardware keyboard, event delivery and IME composition time.
The window is driven programmatically without forcing app activation; its key-window state
is recorded. The manual event loop uses an autorelease pool for every iteration.
Keep the benchmark window visible. Its occlusion transitions are recorded; an occluded run
is invalid for presentation/latency measurements. Reports are saved before validation errors
are raised, including missing input echoes, so failed runs retain their raw evidence.

CPU phases use monotonic wall time. Drawable acquisition is separate because
[`nextDrawable()` can block](https://developer.apple.com/documentation/quartzcore/cametallayer/nextdrawable()).
GPU time is the command buffer's `GPUEndTime - GPUStartTime`, read after completion.
Input-to-display and presented-frame intervals use
[`MTLDrawable.presentedTime`](https://developer.apple.com/documentation/metal/mtldrawable/presentedtime),
not completion-handler arrival time. Callback delivery is marshaled to the main thread.
The report counts successful presentations separately from submitted frames and keeps zero
presentation timestamps out of latency/FPS calculations. No pixel readback is performed.

The existing AppKit scheduling and GPU-completion guard remain in use. Measurements must
use the same display: refresh and acquisition delays differ between the connected 60 Hz
and 120 Hz screens. The native observer test checks GPU ownership, idle behavior, hide/show,
resize, held-frame content and callback disposal.

Window correctness also has a separate pixel verifier. After compiling the native tests,
run these on a logged-in macOS desktop with Screen Recording permission for the launching
process:

```sh
python3 scripts/verify-terminal-window.py
python3 scripts/verify-terminal-window.py --scenario resize
python3 scripts/verify-terminal-window.py --scenario continuous-resize
python3 scripts/verify-terminal-window.py --scenario continuous-resize --tui htop --duration 30
python3 scripts/verify-terminal-window.py --scenario continuous-resize --tui claude --duration 30
```

Each command opens only its own fixture window. The native test supplies that window's ID;
the driver captures it and uses offline Apple Vision OCR. Reports, source/binary hashes,
native logs and PNGs are retained in `build/reports/terminal-window/run-*`. Missing capture
permission fails the check. The occlusion scenario checks the final output after covering
and ordering out the window, without new writes to trigger recovery. The resize scenario
compares glyph bounds before, between and after frame updates while growing and shrinking
the window, then checks actual AppKit grid resizing. OCR verifies the text; the green fixture's
actual ink bounds detect scaling or displacement, with a one-pixel rounding tolerance.
Holding the previous drawable exposes compositor scaling that offscreen
Metal readback cannot detect.

The resize fixture deliberately uses a contrasting host-window background: matching it to
the terminal's background would hide exposed strips around an older drawable. Nine interior
background samples must match the terminal theme. The continuous variant replays the view's
live-resize callbacks around 360 native window-size changes, without moving the user's mouse.
It uses ScreenCaptureKit to capture **only that window**, checking every complete captured
frame for shifted/clipped/missing green glyphs and exposed host background. Vertical position
is measured relative to the fixed AppKit title text: the single-window stream sometimes shifts
both the title bar and terminal by the same resize step. Raw coordinates and these whole-window
offsets are retained, rather than counted as terminal-only movement. Glyph dimensions, pixel
count and exposed background remain independent checks. It retains frame metadata and diagnostic
PNGs in `stream-report.json`; the static verifier also checks the text through OCR. Neither
verifier establishes physical scanout or a universal FPS guarantee. Set `KINETICA_WINDOW_SOFTWARE=1`
for the continuous scenario's ordinary AppKit drawing control.

The optional TUI variants launch the installed executable in an isolated PTY and a disposable
empty directory. `htop` is read-only and filters the process list to the fixture. Claude runs
with tools and customizations disabled; the fixture declines external instruction imports,
trusts only its disposable directory, and waits for the main prompt without submitting a model
request. Startup text is retained to distinguish the main UI from onboarding dialogs. These
variants use a larger window, record model and rendered-frame text counts, and flag near-empty
captures or a drop of more than 60% in bright content pixels between adjacent captures.
This heuristic catches large flashes, but is not a pixel-perfect oracle for changing TUI
content. Inspect the diagnostic PNGs and `tui-frames.json` alongside `stream-report.json`.
The driver removes inherited `NO_COLOR`, `CLICOLOR`, `CLICOLOR_FORCE` and `FORCE_COLOR`
settings from its fixture process. Automation often sets `NO_COLOR=1` for logs; passing it
to a real terminal makes `htop` and other applications suppress their own colors. When
opening the desktop app from such a runner, clear those variables for the `open -n` process
as well. The terminal library still respects the environment supplied by its caller.

AppKit measurement also preserves an application's synchronized-output transaction. Pixel-only
resizes leave the session untouched; grid changes during a hold are coalesced until the hold
ends, then notify the PTY once. The existing hold deadline applies an abandoned pending resize.
Native regressions cover both normal completion and the deadline; explicit calls to
`TerminalSession.resize` retain their documented behavior of releasing a hold immediately.

During live resize the native viewport also retains its pre-resize frame while immediately
updating the PTY grid. Otherwise the primary buffer can move its header into history before
the TUI redraws it, producing a one-frame jump. Unsynchronized output releases this snapshot
after an 8 ms quiet interval, with a 50 ms deadline that repeated geometry changes cannot
extend. If the TUI starts synchronized output, it keeps that pre-resize snapshot until the
atomic redraw ends, using the existing synchronized-output deadline. This also works through
forced repaint and the software renderer, and all pending timers are cancelled on disposal.
The quiet interval is a bounded presentation heuristic, not an atomic-frame guarantee for
arbitrary applications that do not use synchronized output. The capture fixture checks that
many different grids are rendered during the drag, so retaining one frame for its entire
duration cannot pass as a flicker fix.

Glyph quads preserve the atlas bitmap's device-pixel size and snap their origins to the
physical grid. They crop any final overhanging texel at the rounded cell boundary instead
of squeezing the bitmap into a fractional cell or blending it into its neighbour. Viewport
normalization uses the actual framebuffer dimensions; the WebGL canvas's CSS dimensions
also match that framebuffer at the current device pixel ratio. This prevents repeated
characters from losing different strokes depending on their column. The native pixel test
compares repeated zeros in normal, bold, dim and inverse styles at 1× and 2× and fractional
window sizes. `scripts/verify-terminal-glyphs.mjs` performs the corresponding WebGL readback
at DPR 1, 1.25 and 2. Build the browser sample with **`-v release`** before running it: the demo
HTML imports `browser-terminaljsrelease`, not the default build variant.

The AppKit GPU view uses a `CAMetalLayer` as its own backing layer, with an event-driven
`displayLayer` delegate and `updateLayer`. AppKit owns its geometry; there is no separate empty
AppKit backing bitmap around a Metal child. A persistent opaque background covers newly exposed
pixels while the previous drawable remains at its original pixel size. Size changes and the
whole live-resize interval use Core Animation transaction presentation: commit the Metal command,
wait until scheduled (not completed), then present the drawable. Other output remains asynchronous.
The existing one-command-in-flight ownership guard still applies. The event-driven view follows
[Apple's custom Metal view example](https://developer.apple.com/documentation/metal/creating-a-custom-metal-view),
and resize presentation follows the
[`presentsWithTransaction` contract](https://developer.apple.com/documentation/quartzcore/cametallayer/presentswithtransaction).

These captures verify WindowServer contents, not physical scanout or input latency. A
restored window can display a cached surface without a fresh positive drawable presentation
timestamp; absence of that callback alone does not prove missing or stale window content.
Keep presentation timing checks restricted to continuously visible benchmark windows.

Two scheduling experiments were rejected. Waiting for a presentation callback before the next
frame reduced FPS and increased input latency in three paired runs, despite passing a queue-size
test (`native-present-wait-rejected-*`). A `CAMetalDisplayLink` prototype avoided main-thread
drawable acquisition but also had worse observed presentation latency on this setup
(`native-displaylink-*`). Neither scheduling change nor its API bridge is retained in the
library. These experiments do not prove either strategy is unsuitable for all workloads;
they demonstrate why smaller queues or faster submission alone cannot establish responsiveness.

## Browser

Serve the repository root (for example `python3 -m http.server 4175 --bind 127.0.0.1`).
Install Playwright separately or point to an existing local installation; set an executable
path if its Chromium binary is not the default. Then:

```sh
KINETICA_BROWSER_BASE_URL=http://127.0.0.1:4175 \
PLAYWRIGHT_IMPORT=/absolute/path/to/playwright/index.mjs \
PLAYWRIGHT_CHROMIUM_EXECUTABLE=/absolute/path/to/chrome-headless-shell \
node scripts/benchmark-terminal-renderer.mjs
```

On macOS the script selects ANGLE Metal explicitly; use `KINETICA_ANGLE=default` to measure
the browser default or another ANGLE backend name when supported. Always inspect the reported
renderer: the default headless executable on this machine uses SwiftShader. Hardware and
software-WebGL timings are not interchangeable. The script requires no browser internals or
mangled Kotlin/JS names; the demo's `profile=1` query enables the optional observer bridge.

Input starts at the textarea's actual `beforeinput` event generated by Playwright's text
insertion, followed by Enter. Timing ends when commands for the echo frame have been submitted.
This **does not measure browser display presentation**. GPU duration uses the optional
[`EXT_disjoint_timer_query_webgl2`](https://registry.khronos.org/webgl/extensions/EXT_disjoint_timer_query_webgl2/)
extension, with nonblocking polling and invalid-result handling. If unavailable, GPU duration
is null. The report deliberately leaves presented frames and input-to-presented latency null.
Browser compositor/physical presentation requires separate tracing or external measurement.
CPU clock quantization can produce zero for very short phases. The reported browser queue
peak is sampled at submissions; peer outstanding-byte accounting is checked on every message.

The scripts `benchmark-terminal.mjs` and native `--benchmark-parser` / `--benchmark-pty`
remain separate parser/transport throughput measurements. CPU phase times, GPU duration and
presentation delay overlap; summing them does not produce a meaningful frame duration.
The current benchmarks do not close the full performance gate: cold glyph/atlas-pressure,
high-DPR/browser-compositor, longer overload and wider hardware coverage remain necessary.
