# Clean engine build verification

This check builds the standalone engine from tracked files, with no project build output,
`node_modules`, upstream study checkout or generated data outside Git. It uses the pinned
Kotlin Toolchain wrapper and existing machine compiler/dependency caches. It is not a
first-install test with empty caches and does not build the external Kinetica application
framework or the sample applications.

The October 30, 2026 run uses macOS 27.0.1 (26A434), Apple Silicon, Neovim 0.12.5 and tmux 3.8.
Neovim is copied outside the exported source as an explicit live-TUI prerequisite. Native
TUI tests use their own PTYs, temporary files and tmux sockets; no installed terminal app
or existing shell session is replaced or terminated.

## Reproduction on macOS

Export tracked files to a new directory. The directory may be under `/private/tmp`:

```sh
check_root="$(mktemp -d /private/tmp/kinetica-clean-engine-XXXXXX)"
mkdir "$check_root/source"
git archive HEAD | tar -x -C "$check_root/source"
cat > "$check_root/offline.sb" <<'PROFILE'
(version 1)
(allow default)
(deny network*)
PROFILE
cat > "$check_root/offline-native.sb" <<'PROFILE'
(version 1)
(allow default)
(deny network* (local ip "*:*") (remote ip "*:*"))
PROFILE
cd "$check_root/source"

sandbox-exec -f "$check_root/offline.sb" \
  bash scripts/engine.sh test -m kinetica-terminal -p jvm
sandbox-exec -f "$check_root/offline.sb" \
  bash scripts/engine.sh build -m kinetica-terminal -p js -v release
sandbox-exec -f "$check_root/offline.sb" node \
  build/engine-project/build/artifacts/CompiledWebArtifact/kinetica-terminaljsTestrelease/kotlin-output/kinetica-terminal_test.mjs

# Set these to independently installed test tools outside the exported source.
export KINETICA_NVIM=/path/to/nvim
export KINETICA_TMUX=/path/to/tmux
sandbox-exec -f "$check_root/offline-native.sb" \
  bash scripts/engine.sh test -m kinetica-terminal -p macosArm64
```

The native profile permits local Unix sockets needed by tmux while denying IP networking.
The run independently verifies that IP connection attempts fail with `PermissionError` and
that local Unix sockets work. Toolchain/dependency downloads are therefore not possible
during the test; prerequisites must already be installed and cached.

## Failures found by the first complete run

The initial export at `6265a6625fc3494dc8139ec2a8caaa0bda09e783` passes all 1635 JVM tests and
the fresh Release JS suite, but exposes six failures in the full native run (1765 pass):

- Five selection tests retain an obsolete empty-string expectation for no selection.
  The current AppKit contract, its dedicated accessibility tests, and original Ghostty's
  `SurfaceView_AppKit.accessibilitySelectedText()` all return null/nil in this case.
  `93b994a` changes the six stale assertions to `assertNull`, retaining all nonempty-selection,
  gesture, timer and input assertions. No production behavior is changed.
- The native media oracle's `temp_outside` fixture uses the checkout directory as a supposedly
  non-temporary location. A checkout under `/tmp` invalidates that premise. `5d9c2f6` creates
  these UUID-scoped fixture directories under the user's cache directory instead; every
  fixture still checks original error/data/deletion results and removes its owned directory
  in `finally`. No host media policy is relaxed.

The focused native selection/accessibility/media regression run passes all 18 tests after
these corrections. A new export of the corrected commit is used for final verification;
project artifacts from the failing export are not copied into it.

The second full native run passes 1770 tests and exposes a timing-dependent Neovim test:
it observes the resized status line before Neovim restores its editing cursor, then records
that transient cursor as the reference for ArrowUp. The failure dump already shows the cursor
on `ROW 099`, so navigation itself succeeded. `85512bf` waits for a completed synchronized
frame and the cursor on `ROW 100` before sending ArrowUp. It preserves the subsequent exact
row/text assertion. All six live-TUI tests and ten consecutive isolated Neovim repetitions
pass after the fix.

The resulting exported source matches all 1730 tracked files at
`85512bf349c25118f10ecef3793ab31cd911238b`, verified with Git object hashes and executable
modes. Hashing honors the committed text/EOL attributes, including CRLF for `kotlin.bat`.
Only the native TUI test changed after the second export's JVM/JS runs; shared and JS sources
are unchanged. No existing project artifacts were imported into either export.

## Current result and remaining failure

The final full native run at the verified exported source passes 1770 tests and fails
`NativeGraphicsTimingTest.automaticPlaybackStopsForHiddenDetachedAndDisposedNativeViews`.
Its GPU window remains ordered on screen (`visible=true`) with frame index 0 and one
image upload; the step waiting for the next automatic frame times out. Earlier complete
runs passed this case, prompting a timer/visibility/presentation investigation.
The investigation reproduced a concrete lost-wake path: `CAMetalLayer.nextDrawable()`
can return null after the animation timer has invalidated the view. Returning without a
retry consumes that invalidation before frame preparation can schedule the next timer.
`NativeMetalDrawableTest` injects a temporary failure into the real AppKit view, waits for
attachment redraws and the animation wake to drain, then restores drawable acquisition
without writing output or manually invalidating the view. It times out before the fix.
A second failing test covers coalescing and hidden/disposed renderer cancellation.

The renderer now shares one delayed retry for unavailable and dropped drawables. Successful
acquisition, hiding and disposal cancel it; late presentation callbacks cannot cancel a
retry from a later acquisition failure. Both regressions and 21 existing native rendering,
animation, visibility and resize tests pass. This proves recovery from an unavailable
drawable; it does not establish that the earlier intermittent run took that exact path.
Both new regressions also pass ten consecutive repetitions. The original lifecycle
test still fails in three of ten repetitions: initial playback and reattachment on
the software renderer, and unhiding on Metal. A full diagnostic run passes 1772 tests
and fails software reattachment. These failures are separate from missing drawables.

Tracing the actual owner-window state resolves the remaining failure: every captured
stall has occlusion state 8192, without `NSWindowOcclusionStateVisible` (1 << 1). The view
is correctly paused because the window is fully covered; `NSWindow.visible` alone only
shows that it is ordered on screen. Visible phases in the trace use 8194. The timing
fixture now uses the same floating-window level and visible-screen placement as the
native visibility fixture, and explicitly uses the regular application activation policy.
Timeouts and all hidden/detached/disposed generation assertions are unchanged. Temporary
production tracing has been removed. Logs and the diagnostic patch are retained under
`build/reports/terminal-drawable-retry/`.

The corrected fixture passes the nine native animation/visibility/resize checks. Without
tracing, twenty repetitions of the previously problematic ordering pass all twenty animation
lifecycle cases and all forty drawable regressions. The neighbouring
`retainsLastCompletedFrameAcrossOcclusionWithoutFurtherWrites` test passes 19/20: one run
submits a sixth frame after its idle baseline of five. The combined repeat log records
79 passed / 1 failed, rather than a blanket passing result. Logs are
`/tmp/kinetica-animation-window-{after,repeat}.log` and their copies in the report directory.

A diagnostic repeat reproduces the idle assertion twice in 30 iterations. In both cases,
WindowServer reports the latest submitted frame as dropped about 0.57–0.70 seconds after
GPU completion, after the test's 0.5-second drain interval. The subsequent single recovery
frame is necessary work. [Metal's presented-time contract](https://developer.apple.com/documentation/metal/mtldrawable/presentedtime)
distinguishes presentation from completion and reports zero for unpresented/dropped frames.
A barrier that requires a positive callback also proves unsuitable on this host: the
WindowServer/OCR verifier sees the correct `BEFORE`, `AFTER COVER` and `AFTER HIDE` pixels,
but the latest callback can remain absent until the 10-second test deadline. The failed
strict-barrier run and all three matching captures remain in
`build/reports/terminal-idle-frame/pixels/run-nt_7asy6/`.

The idle test now admits exactly one recovery submission after a dropped **current** frame;
stale callbacks grant no allowance, and any unrelated submission still fails immediately.
After recovery completes it requires the original 0.2-second interval with no further
submissions. Pending recovery cannot bypass the existing 10-second deadline. Hidden idle
windows still permit zero submissions. This corrects the test's completion assumption
without changing production rendering or enlarging its wait durations.
The corrected idle test passes 30/30 repetitions, and the complete WindowServer/OCR
occlusion verifier passes all three phases plus lifecycle assertions
(`terminal-idle-frame/final-pixels/run-zqzs92v3/report.json`). The combined repeat has
117 passes / 3 failures: all three failures are in the neighbouring drawable-recovery
fixture, whose window still uses the ordinary window level. Its visibility assumptions
are the next check; this result is not reported as a wholly passing suite.
The timeout is not relaxed and the clean-export native gate remains open.

`build/reports/terminal-clean-engine/report.json` retains the source hashes, individual run
results and hashes of all copied logs. `final-native.log` contains the remaining failure;
`native.log` and `native-before-tui-fix.log` retain the earlier failures. JVM/JS verification
is complete for the exported engine sources, but a passing whole native suite is still required.
