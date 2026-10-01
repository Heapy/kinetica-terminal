# Terminal usability — verification

Migration note: measurements below were recorded in the original Kinetica worktree.
Untracked `build/reports` evidence remains there and is not included in this source repository.

Collected observations and proposed basic features for the macOS terminal application.
All thirteen items are implemented and verified in the macOS application.
Items 1–4 are user-reported problems; items 5–13 are additional
features the user asked to add to the list. Changes must preserve live sessions,
shared browser engine behavior, VT state, and the existing rendering and resize fixes.

The nine added items are text zoom, persistent font and theme settings, clickable
links, Select All and a Copy/Paste context menu, clearing the screen and scrollback,
inheriting the active shell directory in new tabs, direct tab shortcuts, file drag
and drop, and remembering window position and size.

| # | Requirement | Acceptance evidence | Status |
|---|---|---|---|
| 1 | Control shortcuts work with Russian input layout | Native events and a real PTY deliver control bytes without changing ordinary Cyrillic input | Verified |
| 2 | Trackpad/wheel scrolling respects movement magnitude | Small precise deltas accumulate into rows; mouse reporting remains usable in TUIs | Verified |
| 3 | Automatic scrollback scrollbar | Overlay appears during navigation, hides after one idle second and immediately without history; does not reflow text or react to output alone | Verified |
| 4 | Find in terminal output/history | Cmd+F, next/previous, result count, highlight/reveal, Escape, Unicode and wrapped lines | Verified |
| 5 | Text zoom | Cmd+/Cmd−/Cmd0 change/reset metrics, redraw and resize PTY | Verified |
| 6 | Persistent font and color preferences | Font family, size and theme selection persist and apply to existing/new windows | Verified |
| 7 | Open hyperlinks | Cmd+click opens OSC 8 and detected http(s) links through native host policy | Verified |
| 8 | Select all and context menu | Cmd+A selects retained output; native Copy/Paste menu actions work | Verified |
| 9 | Clear screen and history actions | Explicit menu/shortcut actions clear their intended scope without resetting shell/VT modes | Verified |
| 10 | New tab inherits active shell directory | A tab opened after `cd` starts in that directory, including spaces/non-ASCII | Verified |
| 11 | Direct tab selection | Cmd+1…9 selects tabs in the active native tab group | Verified |
| 12 | File drag and drop | File URLs become safely shell-quoted paths through normal paste handling | Verified |
| 13 | Remember window geometry | Size/position restore across launches and remain reachable when displays change | Verified |

## Verification (2026-10-01)

- [Native input and usability tests](../kinetica-terminal/test@macosArm64/MacOsUsabilityTest.kt)
  exercise precise CoreGraphics wheel events, the native scrollbar, search selection,
  a real foreground PTY interrupted by Russian Ctrl+C, Cmd+click for plain/OSC 8 links,
  real file-URL pasteboards, bracketed file paste, and context-menu Copy/Paste/Select All.
- [Native application tests](../samples/native-terminal/test/TerminalUsabilityTest.kt)
  exercise menu key equivalents, next/previous search over three results, Escape,
  zoom while preserving TUI color overrides, the actual Settings panel, existing/new tabs,
  directory inheritance after `cd` to a path with spaces, quotes and Cyrillic, both clear actions,
  persistent preference reload, and window restoration/clamping after a display change.
  Filesystem paths are compared by identity because macOS returns decomposed Unicode.
- [Shared navigation tests](../kinetica-terminal/test/TerminalNavigationTest.kt) cover
  Unicode/wide/combined cells, soft wraps versus hard line boundaries, case sensitivity,
  search limits, history reveal, link punctuation, prompt/mode preservation, incomplete escapes,
  safe shell quoting, and DEC 1047 erasure distinct from the host Clear Screen action.
- [Keyboard tests](../kinetica-terminal/test@macosArm64/MacOsKeypadTest.kt) retain ordinary
  Cyrillic input and Latin shortcuts; [scroll tests](../kinetica-terminal/test/TerminalScrollAccumulatorTest.kt)
  cover accumulated distance and direction changes. Existing mouse/TUI tests preserve reporting.
- [Metal tests](../kinetica-terminal/test@macosArm64/MetalTerminalTest.kt) verify that changing
  font identity invalidates cached glyph pixels, and retain the repeated-digit and block-art regressions.
  The [browser verifier](../scripts/verify-terminal.mjs) checks font family changes in WebGL and Canvas,
  alongside colors, input, rendering recovery and resource disposal.

Passed: 308 JVM tests, 342 Native library tests, all four native application tests,
JS tests, and Chromium rendering/font/glyph/block/selection checks. Continuous native resize
with Claude captured 625 frames with zero detected anomalies. Native search and Settings
windows were captured and visually reviewed.

Local evidence: `build/reports/terminal-usability/` contains test logs, UI screenshots,
`browser-results.json`, `tested-sources.json`, and the resize capture report. The installed
bundle and backup are recorded separately in `build/reports/terminal-desktop-update.json`.

Search is literal, highlights the current result, and caps its result list at 10,000.
The native controls are provided by `AppKitTerminalPane`; the macOS app
uses them by default. Browser hosts retain their existing UI and can use the shared APIs.

## Scrollbar follow-up — build 8

The scrollbar now occupies an overlay instead of a permanent gutter. It appears only
when there is history and the user scrolls or navigates it, then hides after one idle
second. Native thumb tracking keeps it visible while dragging. New output alone does
not reveal it; clearing history and entering the alternate screen hide it immediately.
Hidden controls do not intercept terminal input. Wheel events over the control route
back to the terminal. The native thumb uses a transparent track, with no change to
the terminal grid when it appears or disappears.

`MacOsUsabilityTest.scrollbarAppearsOnlyDuringNavigationWithoutResizingTheGrid`
checks history/no-history, fractional trackpad activity, output during the idle delay
(including with search open),
grid width, hit testing, TUI reporting, alternate screen and disposal. Seven usability
and mouse/PTY tests pass. The real Metal window was captured idle, scrolling and after
auto-hide; evidence lives under `build/reports/terminal-scrollbar/`.
