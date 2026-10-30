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
Its GPU window remains visible with frame index 0 and one image upload; the step waiting
for the next automatic frame times out. Earlier complete runs passed this case, so this
is an intermittent failure requiring a separate timer/visibility/presentation investigation.
The timeout is not relaxed and this clean-build gate is **not complete**.

`build/reports/terminal-clean-engine/report.json` retains the source hashes, individual run
results and hashes of all copied logs. `final-native.log` contains the remaining failure;
`native.log` and `native-before-tui-fix.log` retain the earlier failures. JVM/JS verification
is complete for the exported engine sources, but a passing whole native suite is still required.
