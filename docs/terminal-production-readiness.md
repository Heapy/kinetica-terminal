# Terminal production readiness

Migration note: older measurements below were recorded in the original Kinetica worktree.
Their untracked `build/reports` evidence remains there and is not included in this source
repository. Current checks also generate local reports under this repository's `build/reports`.

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

AppKit `firstRectForCharacterRange` now follows Ghostty's `Surface.imePointScaled` and
Swift `firstRect`: IME geometry starts at the live cursor's horizontal midpoint, counts
positive-width preedit scalars and clips to the remaining terminal width. It no longer
uses a stale retained cursor during synchronized output. Collapsed ranges report zero width;
when preedit has positive width their location offsets the dictation insertion point.
Unknown `NSNotFound` locations keep the cursor anchor. Look Up continues to use the selected
word's baseline independently of IME geometry. The implementation preserves the upstream's
explicit unit exception: preedit width remains in backing pixels while x/y/height are in
AppKit points.
`MacOsLookupTest` checks actual window/screen coordinate conversions, CJK and surrogate-pair
emoji widths, ignored zero-width scalars, collapsed/unknown ranges, clipping, clearing preedit,
live cursor motion during held output and the existing dictionary selection baseline.
This validates the geometry returned to AppKit; it does not replace physical dictation/IME
acceptance or close the separate preedit rendering audit.
All 15 selected native Look Up, composition, keyboard-protocol and selection-policy tests pass.

AppKit text interpretation now accumulates `insertText` and command callbacks for one
`interpretKeyEvents` call before forwarding input. If the event started with preedit, committed
fragments are sent as plain typed text before replaying Right/Down/Up or modified Left; plain
Left, confirmation Enter and other keys are not replayed. This follows Ghostty 1.4.0's
`SurfaceView_AppKit.keyDown`, `shouldReplayCommittedPreeditKey` and
`shouldSuppressComposingControlInput`: a single C0 scalar from a composing event is suppressed,
while multi-scalar text, DEL and C1 remain unchanged. Commands cancelling preedit cannot leak
a Backspace/Enter/Tab/Escape into existing terminal input, even when the callback first clears
the marked text. Ordinary commits retain their original key identity and Kitty text fields.
`MacOsCompositionTest` drives the production interpretation boundary with real `NSEvent`
objects and controlled NSTextInputClient callback sequences. It covers legacy/modifyOtherKeys/
Kitty, repeat events, split and partial commits, all single C0 controls, modified Left,
command cancellation, keyboard lock and cleanup after an interpreter failure. This is a
deterministic callback test, not an executable upstream oracle or acceptance evidence for an
installed Korean/Japanese IME. Physical IME workflows, layout-switch handling and the full
native key-release routing remain under the open keyboard/IME gate.
Validation: all 15 selected native composition, keyboard-protocol, modifier, focus and
selection-policy tests pass, including the 10,240-event original Swift modifier comparison.

AppKit modifier actions now follow the current event's flags instead of alternating a
remembered pressed set. This prevents a repeated notification or a release arriving after
focus loss from being misreported as the opposite action. Right-side modifiers consult the
SDK device-side mask when the aggregate modifier remains set; active IME preedit suppresses
modifier delivery, matching the pinned Swift surface callback. The left-side aggregate rule
is retained as in Ghostty 1.4.0 rather than inferred from our event history.
`scripts/generate-native-modifier-events.mjs` executes the original Swift `flagsChanged` and
`ghosttyMods` functions with real `NSEvent` objects and archives 10,240 expected actions.
`MacOsModifierEventsTest` compares our final Kitty bytes against those actions and adds
repeated-event, focus-return and composition sequences. All three tests failed before the
implementation change. Fixture and manifest regeneration is byte-identical.
The corrected implementation passes all 10,240 event comparisons and 34 native tests across
modifier classification, keyboard protocols, focus, keypad, selection, link hover and accessibility.

Focus loss now releases the last forwarded key and then both sides of held modifiers,
in Ghostty 1.4.0 `Surface.keyCallback`/`focusCallback` order: Shift, Control, Alt, Super,
right before left, clearing each modifier before encoding its releases. The shared tracker
retains no associated text, suppresses a key already released normally, and clears its state
before sending synthetic events. It follows the upstream last-event model rather than
releasing every entry in the host's pressed-key set. Layout/base/keypad identity is cleared
when synthesizing a modifier; a Chromium check caught the initial implementation incorrectly
adding the preceding letter's base codepoint to Shift releases.
Browser blur/visibility and AppKit responder/window focus paths send these events before
the focus-out report. AppKit now observes window activation as well as responder changes,
and restores focus only if the terminal still owns the first responder. Synthetic releases
do not clear selection or scroll the viewport; keyboard lock still suppresses input, and
disposal unregisters the window observers without sending releases.
`TerminalKeyFocusTest`, `MacOsKeyFocusTest` and `verify-terminal-focus.mjs` cover exact event
bytes, ordinary and delayed keyup, modifier order, repeated focus events, selection retention,
keyboard lock and disposal. These are source-derived tests of the pinned callback behavior,
not an executable differential oracle for the entire Ghostty surface/input runtime.
Validation: 1,702 JVM tests, fresh Release JS tests, 22 focused macOS tests, the
Canvas/WebGL focus verifier and the macOS/Linux × Canvas/WebGL editing regression pass.

Browser window focus now reconciles the terminal with its actual DOM focus owner when the
window returns, including a textarea inside a shadow root. Previously `window.blur` produced
CSI O and a hollow cursor, but the matching `window.focus` had no handler; because the textarea
remained active, its input-focus listener did not run again and CSI I/the active cursor were lost.
The browser adapter now handles window focus and document hidden/visible transitions, retaining
`focusChanged` deduplication and without calling DOM focus on return. Disconnected, hidden or
externally focused controls cannot claim terminal focus. Render intersection alone does not
remove keyboard ownership. Synchronized output keeps its retained frame while focus repaints
the cursor, and disposal unregisters the additional listener.
This follows original Ghostty `Surface.focusCallback`'s focus-state/render/report transitions;
the held-key release path is covered by the additional checks above.
`scripts/verify-terminal-focus.mjs` first reproduced the missing CSI I before the fix, then
passed for Canvas/WebGL with exact reports and cursor pixels, repeated events, outside-owner
protection, visibility notifications, a retained frame, shadow DOM and disposal. The test also
activates another Chromium tab (headless Chromium may omit those native focus transitions).
Fresh Release JS tests and editing regressions pass for macOS/Linux in both renderers.
Evidence: `scripts/verify-terminal-focus.mjs`, `scripts/verify-terminal-editing.mjs`,
`/tmp/kinetica-window-focus-*`. This does not establish physical screen-reader acceptance.

A fragment-shader audit corrected the opacity and decoration exceptions in the initial faint
and minimum-contrast implementations. Original `cell_text.f.glsl` returns intrinsic color
atlas pixels without applying vertex tint/alpha; original `cell_text.v.glsl` overrides the
foreground color/alpha at the active block cursor after contrast correction. Accordingly,
color glyphs preserve their own alpha, while monochrome masks apply configured faint alpha.
Block-cursor text, geometric glyphs and underline/overline/strike use the opaque cursor
foreground, including explicit underline colors. Underline/overline/strike are grayscale
sprites upstream and receive minimum-contrast correction; graphical character glyphs retain
their separate no-min-contrast exemption. Concealment still suppresses all foreground layers.
All standard software/GPU renderers now use per-glyph raster metadata; Canvas and AppKit use
the bounded bitmap caches for ordinary frames too. Canvas retains clean rows and only redraws
the dirty row range unless images require full compositing; AppKit respects the dirty rectangle.
Font/scale changes, hiding and disposal release the caches without changing terminal state.
`scripts/generate-terminal-glyph-opacity.mjs` executes the original fragment main unchanged
for 48 color/mask, foreground-alpha and texture-coverage combinations. Its WebGL2 interface
uses normalized 1×1 samplers and signed flag uniforms (identical bit operations), while the
original branch and blending code remains literal. Source, generator and fixture provenance
is recorded in `test-upstream/ghostty/glyph-opacity-manifest.json`.
Validation passes 1698 JVM tests, fresh Release JS tests and 35 focused Native tests.
The fragment corpus regenerates byte-for-byte. Browser faint pixels pass 12 renderer/font-size/DPR
combinations (12.5/14 px, DPR 1/1.25/2); contrast/opacity pixels pass 12 renderer/font-source/DPR
combinations, including explicit focus, dirty-row retention, gray COLR and mixed monochrome text.
Native checks compare AppKit/Metal, gray sbix artwork, block-cursor text/wide/graphic/color cells,
decorations, images, shaping and hide/dispose lifecycles. These do not close the broader gates above.
Evidence: `TerminalGlyphOpacityTest`, `TerminalFaintTest`, `NativeGraphicsRenderingTest`,
`AppKitTerminalGlyphsTest`, `scripts/verify-terminal-faint.mjs`,
`scripts/verify-terminal-contrast.mjs`, `/tmp/kinetica-glyph-opacity-*`.

`session.minimumContrast` implements Ghostty's optional 1…21 contrast policy (default 1,
which disables it). The shared glyph path evaluates linear RGB luminance after faint alpha,
using the resolved cell background; insufficient contrast selects opaque black or white.
Selection and inverse colors are resolved first. Active block-cursor text, intrinsic color
glyphs and box/block/Powerline/legacy graphics keep their original colors. Sprite decorations
use contrast correction too, as confirmed by the subsequent fragment-shader audit above.
Kitty image pixels do not participate in the contrast calculation. Policy changes repaint
views and retain their captured value through synchronized output without changing copied text.
Canvas and AppKit use bounded shaped-glyph bitmap caches, sharing
GPU clipping and color resolution. Font/scale changes, hiding and disposal release those
resources. The system-font browser fallback probes white/black paint response so gray color
artwork is not mistaken for a tintable text mask. The subsequent glyph-opacity audit made
this path unconditional so intrinsic color alpha is also preserved with contrast disabled.
`scripts/generate-terminal-contrast.mjs` executes the pinned original GLSL functions in WebGL2;
8132 deterministic cases cover chromatic/gray pairs, alpha 0…255 and thresholds 1…21.
The original source hashes, generator and fixture hashes and Chromium version are recorded in
`test-upstream/ghostty/contrast-manifest.json`. CPU calculation uses double precision to avoid
an intermediate Float rounding error that incorrectly increased alpha at the exact 21:1 boundary.
Validation passes 1694 JVM tests, fresh Release JS tests and 25 focused Native tests.
Canvas/WebGL pixel checks pass all 12 combinations of DPR 1/1.25/2, bundled/system fonts
and renderer, covering both background extremes, faint 0/0.5/1, Kitty images, hide/restore,
disable/re-enable and intrinsic gray COLR artwork (including the system-font raster fallback).
AppKit/Metal pixels match for gray sbix artwork and corrected monochrome fallback text at
all three scales with and without faint; the sbix face remains exempt, as in original CoreText.
The GLSL corpus regenerates byte-for-byte. These checks do not close the broader acceptance gates above.
Evidence: `TerminalContrastTest`, `NativeGraphicsRenderingTest`, `AppKitTerminalGlyphsTest`,
`scripts/verify-terminal-contrast.mjs`, `/tmp/kinetica-contrast-*` (final native results in
`native-final.log` for 24 existing tests and `native-bitmap.log` for the corrected sbix/fallback test).

Selection foreground/background now accept literal RGB, `cell-foreground`, `cell-background`
and Ghostty's default terminal-relative colors through `session.selectionColors`.
`TerminalSelectionColors()` selects Ghostty defaults; a null host override uses the existing
Kinetica theme selection style. Resolution follows pinned `renderer/generic.zig` after bold
foregrounds and SGR inverse, before cursor overrides. Explicit underline colors, hidden/faint
attributes, wide cells and opaque selection backgrounds retain their respective semantics.
Policy changes repaint all views, survive terminal/theme resets and retain captured policy,
theme and palette through synchronized output. Selection text and rich-copy styles are unchanged.
Shared tests cover all 16 foreground/background combinations on ordinary and shaped paths,
three views, inverse/default/indexed/RGB colors, reset, theme/DEC 5, cursor, wide/hidden/faint
text and held frames. Validation passes 1689 JVM tests, fresh Release JS tests and 20 focused
Native tests. Canvas/WebGL and AppKit/Metal selected pixels match equivalent explicit-SGR
colors for all 16 combinations with and without inverse at scales 1/1.25/2, including text,
blocks, emoji and decorations. Existing macOS/Linux browser selection regressions also pass.
Evidence: `TerminalSelectionColorsTest`, `NativeGraphicsRenderingTest`,
`scripts/verify-terminal-selection-colors.mjs`, `/tmp/kinetica-selection-colors-*`.

`session.boldColor` now implements pinned Ghostty `Style.fg`: disabled by default,
`BRIGHT` maps bold ANSI colors 0–7 to 8–15, and explicit RGB additionally overrides the default
foreground and matching truecolor cells. Other truecolor and palette entries remain unchanged.
Foreground resolution precedes inverse, selection/cursor overrides and decoration fallbacks,
and is shared by ordinary text, shaped glyphs and geometric blocks. Software batching now
preserves raw foreground identities even when their current RGB values coincide. Host policy
changes repaint existing cells, survive terminal/theme resets and retain captured policy and
palette during synchronized output; terminal color state and rich-copy formats stay intact.
A new oracle calls unmodified original `Style.fg` for 6288 resolutions across two palettes,
two default foregrounds, all 256 indices, explicit/default RGB, bold/non-bold and three policies.
Fixture and manifest regeneration is byte-identical. Sources, revision and generator hashes
are pinned in `test-upstream/ghostty/bold-color-manifest.json`.
Validation passes 1685 JVM tests, fresh Release JS tests and 20 focused Native tests.
Canvas/WebGL pixels pass at DPR 1/1.25/2, and AppKit/Metal pixels pass at scales 1/1.25/2.
Configured text/block/emoji/decorations match equivalent explicit-SGR colors, including inverse
and policy updates without rewriting cells. Evidence: `TerminalBoldColorTest`,
`NativeGraphicsRenderingTest`, `scripts/verify-terminal-bold-color.mjs`,
`/tmp/kinetica-bold-color-*` (final native run: `native-verified.log`).

Configurable faint rendering now follows pinned Ghostty `Config.zig` and
`renderer/generic.zig`: `session.faintOpacity` defaults to 0.5, clamps to 0…1 and uses
`ceil(value * 255) / 255` for SGR 2 foregrounds. NaN is rejected before state changes.
Monochrome text, geometric glyphs and decorations share the setting; backgrounds remain
opaque. The subsequent fragment-shader audit above supersedes the initial color-glyph
expectation: intrinsic color alpha and block-cursor foreground overrides ignore faint. Configuration invalidates all session views, preserves glyph atlas keys and survives
RIS/DECSTR and palette/theme changes. Synchronized frames retain their captured opacity until
release. Text blinking remains unrendered, consistent with the pinned upstream renderer's
cursor-only blink timer; it is not a verified missing Ghostty rendering feature.
Validation passes 1680 JVM tests, fresh Release JS tests and 18 focused Native tests.
Browser pixels pass with Canvas/WebGL, font sizes 12.5/14 and DPR 1/1.25/2; native pixels pass
with AppKit and Metal at scales 1/1.25/2. Both sweep alpha settings 0/0.1/0.5/0.9/1 over text,
emoji, block geometry, five underline styles, strike and overline, with plain/image backgrounds.
Evidence: `TerminalFaintTest`, `NativeGraphicsRenderingTest`,
`scripts/verify-terminal-faint.mjs`, `/tmp/kinetica-faint-opacity-*`.

Selection and clipboard scans now restore each compressed source row after reading it,
including exception paths. Plain/VT/HTML/mixed copy and legacy selection text process one
compressed row at a time; frame snapshots also preserve source history compression. Rows
already resident for rendering stay resident. Output strings, mapping caches and retained
snapshot rows still scale with selection size; this does not close the overall resource gate.
Four regression tests failed before the fix: ordinary selection text expanded a measured
history from 59,672 to 143,888 resident accounting bytes, long copies accumulated decoded rows,
retained copy caches left rows decoded, and interrupted formatting failed to release its row.
`TerminalCopyStorageTest` now checks all formats and trim settings over 600 rows, styled links,
combining/wide text, codepoint maps, wrapped/blank rows, shared visible rows, repeat copies,
exceptions, and capture followed by hidden history erasure. Validation passes 1678 JVM tests,
fresh Release JS tests and 23 focused Native tests. Browser copy-on-select and frozen-gesture
verifiers pass on macOS/Linux with Canvas/WebGL and Retina. Evidence logs:
`/tmp/kinetica-copy-storage-*`.

Browser/AppKit now count actual local left presses with shared Ghostty repeat-click rules,
independent of native click-count hints. `clickRepeatIntervalMillis = 0` uses the macOS system
interval or 500 ms browser fallback; positive values cover Ghostty's u32 millisecond range.
Each repeat must be within the interval from the previous press and one cell-width radius
from the first press. Boundaries are inclusive and counts saturate at three. The current cell
supplies the initial word/line/output selection, while dragging retains the first press's
tracked cell and x coordinate, including when output scrolls that cell into history.
Screen changes, missing/recycled origins, backwards/invalid time, force click and application
mouse ownership reset or restart the sequence. The former AppKit force-click count offset and
browser mousedown/detail reinterpretation are removed. Existing synthetic event tests now emit
real press/release sequences with deliberate timing instead of fabricating count hints.
Validation passes 1667 JVM tests, fresh Release JS tests and 24 focused Native tests. Dedicated
browser checks pass on macOS/Linux with Canvas/WebGL and Retina; selection, Shift continuation,
copy-on-select, link, navigation and full browser regressions pass. Evidence:
`TerminalClickSequenceTest`, `MacOsSelectionTest`, `scripts/verify-terminal-click-sequence.mjs`,
`/tmp/kinetica-click-sequence-*`.

Shift-click can now continue a completed local pointer selection after the repeat-click
interval, following `Surface.mouseButtonCallback`'s continuation branch. The original anchor,
timestamp and character/word/line/output granularity remain; current modifiers control
rectangles, and continuation can shrink/reverse the selection or resume autoscroll. The
interval boundary remains eligible for multi-click instead. Empty, invalidated, cleared and
deep-press-consumed origins cannot continue; application Shift capture takes precedence.
Validation passes 1664 JVM tests, fresh Release JS tests and 20 focused Native tests.
Dedicated browser Shift-click, existing selection and mouse-clipboard verifiers pass on
macOS/Linux with Canvas/WebGL. Evidence: `TerminalShiftSelectionTest`, `MacOsSelectionTest`,
`scripts/verify-terminal-shift-selection.mjs`, `/tmp/kinetica-shift-selection-*`.
Retained frames now preserve gesture anchors and repeat-click state, including origins in
history outside the visible viewport and current selection. Held drags, Shift continuation,
word/deep presses and pending link clicks read captured cells despite hidden output or screen
switches; current link policy still applies. Snapshot rows are shared with retained copy data,
and gesture anchors are rebound lazily. Select All remains limited to visible retained rows.
Selection edits made during a hold are discarded on reveal; untouched holds preserve a valid
live click sequence. This closes the previously recorded frozen-gesture gap, without closing
the broader input/selection gate or resource acceptance requirements.
Validation passes 1674 JVM tests, fresh Release JS tests and 27 focused Native tests. Dedicated
frozen-gesture, selection and copy-on-select browser checks pass on macOS/Linux with Canvas/WebGL
and Retina, alongside the full browser verifier. Evidence: `TerminalFrozenSelectionTest`,
`MacOsSelectionTest`, `scripts/verify-terminal-frozen-gesture.mjs`,
`/tmp/kinetica-frozen-gesture-*`.

Browser/AppKit now implement Ghostty's right-click actions (context menu, mixed copy,
paste, copy-or-paste, ignore) and middle-click actions (selection paste, standard paste,
ignore). Defaults remain context menu and selection paste; selection paste requires an
explicit custom-host capability and targets `SELECTION`, not OSC 52's `PRIMARY`.
Behavior follows pinned `Surface.mouseButtonCallback` and Config fields: reporting owns
events before local actions, right copy clears unconditionally, right paste clears before
the request, and middle paste retains selection. Local press ownership suppresses later
drag/release reports even if the application enables reporting during the gesture.
Mouse paste shares unsafe-text confirmation and mode-5522 handling; browser synthetic
events cannot grant protocol reads. Pending text acquisition and confirmation share one
cancellation identity, rejecting replaced, detached, disposed and duplicate completions.
Validation passes 1661 JVM tests, fresh Release JS tests and 13 focused Native tests,
including real AppKit button events, private pasteboards and isolated PTY paste regression.
The mouse-clipboard browser verifier passes on macOS/Linux with Canvas/WebGL, as do the
full browser verifier, paste confirmation and copy-on-select regressions. Evidence:
`MacOsMouseClipboardTest`, `TerminalPasteConfirmationTest`,
`scripts/verify-terminal-mouse-clipboard.mjs`, `/tmp/kinetica-mouse-clipboard-*`.

Browser/AppKit now expose `selectionClearOnCopy` (default false) and `selectionClearOnTyping`
(default true), following pinned `Surface.copy_to_clipboard`, `keyCallback`, `preeditCallback`
and the corresponding Config fields. Explicit copies clear immediately after dispatch when
enabled, even on a rejected/failed clipboard write; automatic copy-on-select does not clear.
Asynchronous completions cannot clear a newer selection. Disabling clear-on-typing retains
selection for typed text and IME, while encoded Escape remains unconditional. Pure modifiers,
consumed empty encodings and KAM-blocked keys do not clear or scroll; encoded Kitty releases
apply the same input policy as presses, including forwarded Command combinations on AppKit.
Lock keys follow Ghostty's non-modifier classification. Starting/changing/ending preedit applies
the typing policy before commitment. Existing paste/DnD and selection invalidation remain
independent. An internal sendKey overload distinguishes actual bytes from handled empty events
without changing the public Boolean contract. Tests also verify effect-before-delivery ordering.
Validation passes 1659 JVM tests, fresh Release JS tests and 39 focused Native tests covering
keyboard/IME, clipboard, lookup, selection and navigation. Browser selection-policy checks pass
across macOS/Linux shortcuts and Canvas/WebGL, including delayed clipboard rejection and KAM;
the full browser terminal verifier also passes. Evidence: `TerminalKeyboardTest`,
`MacOsSelectionPolicyTest`, `scripts/verify-terminal-selection-policy.mjs` and
`/tmp/kinetica-selection-policy-*`.

Copy-on-select now implements Ghostty 1.4's none/primary/clipboard/both policies in Browser
and AppKit views. Left gestures copy only at release; Select All and newly committed context-menu
selections also copy. Automatic copies always use mixed format, independently of explicit-copy
format. Cancelled gestures, pointer motion and application-owned mouse reports cannot write the
clipboard. Existing selections containing a context click and unwritten cells preserve the
selection/clipboard. Context-link matching respects Cmd/Ctrl: detected URLs select their matched
range, while OSC 8 takes precedence and selects the clicked cell, including wide-cell normalization.
Context menus and Select All use the presented frame during synchronized output. These behaviors
were traced through pinned `Surface.setSelectionAndCopy`, `mouseButtonCallback`, `linkAtPos` and
the select-all binding, plus `Config.copy-on-select`. Default macOS policy is none; Linux browser
policy is primary. The built-in macOS/browser adapters cannot provide primary selection and never
redirect it to standard; custom MIME backends receive the `SELECTION` destination for the
`PRIMARY` policy, matching Ghostty’s `.selection` routing. This is distinct from the OSC 52
`PRIMARY` destination (`p`). Both attempts
remain independent if one backend call throws, and clipboard failures preserve the selection.
Validation passes 1657 JVM tests, fresh Release JS tests and 52 focused Native tests, including
private-pasteboard destination routing, native mouse/menu events and injected host failure.
The dedicated browser copy-on-select verifier and existing editing/selection regressions pass
on macOS/Linux shortcuts with Canvas/WebGL. Evidence: `TerminalContextSelectionTest`,
`MacOsCopyTest`, `scripts/verify-terminal-copy-on-select.mjs` and `/tmp/kinetica-copy-on-select-*`.
The corrected `PRIMARY`/`BOTH` routing to `SELECTION` is also verified with 1659 JVM
tests, fresh Release JS tests, `MacOsCopyTest`, and all four platform/renderer combinations
of the browser copy-on-select verifier; logs: `/tmp/kinetica-copy-selection-routing-*`.

Browser/AppKit user copy now supports plain, VT, HTML and mixed formats; mixed is the
default and publishes plain text plus styled HTML as one item. VT/HTML preserve explicit
cell colors, SGR attributes, underline styles/colors and OSC 8 links. Standalone VT/HTML
include the terminal theme; mixed HTML inherits the destination's default colors. Styled
written spaces and codepoint mappings follow the original formatter, including its URI-byte
escaping. Copy policies can change during a synchronized-output hold: selected rows, palette,
theme and links survive history erasure and later output. User actions write through the MIME
backend independently of OSC policy. Browser DOM copy replaces stale representations; the
default asynchronous backend falls back to plain text only for an unsupported MIME API.

The expanded original `ScreenFormatter` oracle checks 198 cases / 3744 selections / 59904
outputs. An explicitly identical palette is asserted in the original runner, avoiding accidental
comparison of different application defaults. Cases cover both trimming policies and mappings,
style transitions, colors, links through blank cells, background-only rows and Unicode.
Small-screen rich output matches byte-for-byte; long selections deliberately use a continuous
HTML wrapper/VT theme header rather than Ghostty's internal page framing. A separate 600-row
replay verifies text, colors, underline styles, links and retained-frame copying. Validation:
1654 JVM tests, fresh Release JS tests, 40 focused Native tests (private pasteboard and PTY),
browser editing/selection across macOS/Linux shortcuts and Canvas/WebGL, and real Chromium
Clipboard API checks for all four formats, including sanitized HTML text/color/link content.
Fixtures and manifest reproduce byte-for-byte. Evidence: `test-upstream/ghostty/copy-manifest.json`,
`TerminalStyledCopyTest`, `MacOsCopyTest`, browser verifiers and `/tmp/kinetica-rich-copy-*`.

Clipboard codepoint mappings now support inclusive Unicode ranges, last-match priority,
multi-codepoint/empty replacements and non-recursive replacement in Browser/AppKit plain copy.
Each view snapshots its configured rule list. The formatter retains which text spans came from
cells, so rules affect grapheme/emoji components after trimming while generated blank-cell spaces
and line separators remain untouched. Coalesced spans survive synchronized-output holds with
selected history; changing rules during a hold does not read newer screen text or alter the
selection/accessibility value. `copyText(Boolean)` retains its existing API and delegates to an
overload accepting mappings. The new original `ScreenFormatter` corpus has 88 cases / 1656
selections / 6624 outputs across mapped/unmapped and trimmed/untrimmed forms, replayed whole and
bytewise. Fixture/manifest hashes reproduce exactly. All 1651 JVM tests, fresh Release JS tests,
36 focused Native tests (private pasteboard), and browser editing/selection checks pass across
macOS/Linux shortcuts and Canvas/WebGL. Evidence: `test-upstream/ghostty/copy-manifest.json`,
`scripts/verify-terminal-editing.mjs`, and `/tmp/kinetica-copy-map-*`.
The subsequent styled-copy increment above uses original formatter output:
`formatter.zig` applies mappings to VT text, despite the contrary comments in
`Surface.copySelectionToClipboards`.

Plain clipboard copy now uses Ghostty's cell formatter separately from selection/accessibility
text. Browser shortcuts/DOM copy and AppKit shortcuts/menu actions trim trailing ASCII spaces
by default; hosts can disable trimming. Interior soft-wrap spaces, wide/combining cells and
non-ASCII whitespace follow original Ghostty output. Selected empty cells copy an empty string,
while absent selections leave the clipboard alone. Both policies preserve selected history and
the presented text through synchronized-output holds; replacing/clearing selection drops the
inherited clipboard forms. OSC/MIME protocol writes are unchanged. The expanded semantic oracle
checks 73 cases / 94 snapshots in both policies, including words/lines/output, pointer gestures,
rectangles, tabs, styled/combining spaces and resize/history. Fixtures regenerate byte-for-byte.
Validation passes 1648 JVM tests, fresh Release JS tests, 33 focused Native tests (with a private
pasteboard), and browser editing/selection checks on macOS/Linux shortcuts and Canvas/WebGL.
Evidence: `test-upstream/ghostty/semantic-line-manifest.json`, `scripts/verify-terminal-editing.mjs`,
and `/tmp/kinetica-copy-*`. Styled-format evidence is recorded separately above.

Ordinary text paste now matches original Ghostty `input/paste.zig`: the fixed xterm control-byte
set is replaced by spaces, bracketed mode preserves CR/LF, and unbracketed mode maps each LF to
CR (including CRLF → CRCR). This fixes the former ESC deletion and unconditional line-ending
normalization. Public `TerminalSession.paste` keeps its signature/JVM facade; typing/IME and raw
input retain their separate paths. A new original-library oracle covers 267 cases / 534 encodings:
all ASCII values, U+0080–U+00FF, mixed endings, Unicode, frame delimiters and larger mixed text.
Fixtures and provenance regenerate byte-for-byte. Validation passes all 1640 JVM tests, fresh
Release JS tests and 42 focused Native tests, including an isolated raw PTY that verifies exact
bytes; browser paste integration and trusted Canvas/WebGL text-drop/MIME tests also pass.
Evidence: `test-upstream/ghostty/paste-manifest.json` and `/tmp/kinetica-paste-*`.
Browser/AppKit now also implement Ghostty's host-side paste confirmation. The default policy
confirms CR/LF in plain mode and the exact closing bracket marker in either mode; bracketed
multiline text is trusted by default. Both settings and the prompt presenter are overridable.
The original text is previewed before encoding, and confirmation uses the current terminal mode.
Cancel preserves selection and scroll; replacement, detachment and disposal invalidate pending
callbacks. Empty clipboard text does nothing. Explicit text input/DnD and mode-5522 metadata
events retain Ghostty's separate bypass paths. Native tests use injected text/private pasteboards
and isolated windows, including real NSAlert sheet acceptance/cancellation; no installed app or
user shell is replaced. The browser modal restores the editable caret as well as focus, fixing a
Chromium case where the first printable character after closing the dialog was otherwise lost.
Disposed views reject focus restoration so closing their prompts cannot emit new focus input.
The extended original oracle now covers 273 cases / 546 encodings / 819 safety checks and
regenerates byte-for-byte. All 1646 JVM tests, fresh Release JS tests, 24 focused Native tests,
Canvas/WebGL paste-dialog checks, the full browser integration, trusted DnD and MIME-clipboard
checks pass. Evidence: `scripts/verify-terminal-paste.mjs`,
`build/reports/terminal-browser-paste.json`, and `/tmp/kinetica-paste-confirm-*`.

Browser select-all now uses Ghostty's platform defaults (Cmd+A / Ctrl+Shift+A), and browser/
AppKit local editing requires exact modifiers rather than intercepting extra Control/Option/
Shift/Meta combinations. Browser coverage includes retained Unicode history, local Kitty event
isolation, copy/no-selection fallthrough, native paste-event delegation, keyboard lock, composition,
held presentation frames and alternate screens in Mac/Linux × Canvas/WebGL. Native editing tests
use an isolated MIME clipboard backend and verify extra modifiers do not acquire clipboard data.

The new browser check exposed select-all including outer blank rows. Direct original
`Screen.selectAll` comparisons also caught loss of written spaces inside the selection. The shared
selection action now trims only the outer NUL/ASCII-space/tab cells, preserves interior written
whitespace, and keeps the prior selection if the screen has no selectable text. The extended
semantic-line oracle covers select-all at all 89 checkpoints in 68 scenarios, including empty/
whitespace screens, combined spaces, wide glyphs, history and resize. Its generated fixtures and
manifest reproduce byte-for-byte. Validation passes all 1638 JVM tests, fresh Release JS tests,
46 focused Native tests, and browser editing/selection/navigation/accessibility/general integration.
Evidence: `semantic-line-manifest.json`, `build/reports/terminal-browser-editing.json`, and
`/tmp/kinetica-editing-*`. This does not close the remaining broader readiness gates.

The standalone AppKit surface now handles Ghostty's macOS history/page/prompt defaults,
matching the browser implementation: Cmd+Home/End, Cmd+PageUp/PageDown and Cmd+Up/Down
(with Cmd+Shift+Up/Down aliases). This replaces the old broad Shift+PageUp/PageDown interception.
Physical navigation keys and exact modifiers leave keypad and extra-modifier input available to
applications. Local navigation preserves selection/focus and emits no Kitty press/repeat/release
events; boundary/no-prompt actions remain consumed, while IME and held presentation frames do
not scroll. Thirteen focused macOS tests pass, including NSWindow dispatch in legacy,
modifyOtherKeys and Kitty modes, accessible text, selection, keyboard lock, alternate screens,
marked text and existing modifier/keypad checks. Reproduce with `MacOsViewportNavigationTest`,
`MacOsAccessibilityTest`, `MacOsKeyboardProtocolTest` and `MacOsKeypadTest`; retained log:
`/tmp/kinetica-native-viewport-navigation.log`. No shared or browser implementation changed.

Ghostty's `scroll_to_selection` is now available through the shared viewport and both view
wrappers, with the macOS Cmd+J default in browser/AppKit. It resolves the existing normalized
selection anchors, positions the first selected row at the viewport top (clamped to the active
screen), and preserves the selected text. Absent/invalidated selections, atomic holds and disposed
views return false; an already revealed selection still consumes its binding. Shared tests cover
reverse/rectangular selections, retained output, invalidation, history removal, alternate screens
and disposal. A native test dispatches Cmd+J through NSWindow and checks accessible text and
Kitty event isolation; browser tests use real pointer selection, new output, exact modifiers and
no-selection fallthrough across Mac/Linux and Canvas/WebGL. Validation passes 1637 JVM tests,
fresh Release JS tests, 47 focused Native tests, and browser navigation, selection, accessibility
and existing integration checks. Logs: `/tmp/kinetica-selection-navigation-*`; browser reports
remain under `build/reports`. Full screen-reader acceptance remains open.

Browser viewport navigation now follows the pinned Ghostty 1.4 defaults: platform-specific
history boundaries, full-page scrolling and previous/next shell prompts (including the macOS
Cmd+Shift aliases). Matching uses physical keys and exact modifiers; Kitty press/repeat/release
events stay local for consumed bindings, while extra modifiers and keypad keys reach applications.
The input advertises these bindings through `aria-keyshortcuts`. The browser navigation verifier
checks Mac/Linux bindings in Canvas/WebGL, accessible output after scrolling, retained input
focus, keyboard lock, IME, atomic holds, alternate screens and missing prompt marks. Fresh Release
JS tests, the existing browser integration and accessibility stability verifier pass. Evidence:
`build/reports/terminal-browser-navigation.json` and `terminal-browser-accessibility.json`.
This closes the viewport/prompt keyboard wiring gap, not screen-reader reading navigation or
announcement/physical assistive-technology acceptance.

Offline clean-export verification now passes all 1635 JVM tests, fresh Release JS tests
and all 1773 Native tests, including live TUI cases. The export contains no imported
project artifacts or upstream clones; IP networking is denied, and existing compiler/
dependency caches are retained. All 1732 tracked files match `007c49c` before and after
the native run. This closes the independent clean-engine gate; application builds and
empty-cache provisioning retain their separate scope.

The investigation also fixes retrying an unavailable Metal drawable and corrects native
test assumptions about occlusion and delayed presentation callbacks. The idle lifecycle
test passes 30 repetitions, the two drawable tests pass 60 cases across 30 repetitions,
and the independent WindowServer/OCR verifier passes initial, uncovered and reordered
window contents plus lifecycle assertions. See [clean build evidence and reproduction](terminal-clean-build.md)
and `build/reports/terminal-clean-engine/retry/report.json` for exact scope and retained
failure/success logs. The broader readiness checklist above remains open.

The next mixed-state probe found styled wide-character spacer heads surviving DCH
after a bottom-row wrap outside vertical margins. DCH/ECH now follow the original
`Screen.splitCellBoundary`: clear a wrapped row's ending spacer before shifting, clear
the previous row/history spacer when editing its continued wide glyph, and split ECH
boundaries before applying ISO protection. ICH/rectangular-scroll semantics remain distinct.
Fifty-four original scenarios / 270 checkpoints cover both screens, protection, row/history
boundaries, out-of-margin cursors and ICH preservation controls; 24 failed before the fix.
Two shared tests verify style/link cleanup and repaint notifications for previous/history
rows. Validation passes 1635 JVM tests, fresh Release JS tests and 1150 Native
oracle/wide-edit/history-compression tests. All 1144 VT artifacts reproduce byte-for-byte.
Logs are `/tmp/kinetica-wide-edit-*`.

The screen-state audit exposed active OSC 8 links leaking across alternate-screen
switches. Ghostty ends the old screen's hyperlink before copying the cursor/pen. Kinetica
now does the same only when the screen actually changes, retaining other pen attributes
and existing cell links. Twelve original scenarios / 117 checkpoints cover all 47/1047/1049
entry/exit pairs, repeated requests and saved cursors. All twelve and a shared viewport
link/frozen-frame regression failed before the fix. Validation passes 1579 JVM tests,
fresh Release JS tests, 1103 Native oracle/navigation tests and real browser link clicks
on Canvas/WebGL with Mac/Linux modifiers. All 1090 VT fixture/manifest artifacts reproduce
byte-for-byte. Logs are `/tmp/kinetica-screen-link-*`.

The browser accessibility stability check reproduced a detached DOM reading range after
cursor blinking: repeated `textContent` assignment replaced the unchanged output node.
The surface now retains one text node and edits only the changed UTF-16 span, keeping
unaffected ranges and skipping identical updates. A Chromium browser check verifies actual
AX text/log exposure, input focus, range preservation before/after edits, synchronized-output
holds and disposal in both Canvas and WebGL. Fresh Release JS tests and the full existing
browser integration suite pass. Evidence is `terminal-browser-accessibility.json`; the
reproducible check is `scripts/verify-terminal-accessibility.mjs`. Announcements, browser
screen-reader reading navigation and real screen-reader acceptance remain open; viewport/prompt
keyboard shortcuts are now covered by the navigation increment above.

The next malformed-input probe exposed DCS payload decoding before DECRQSS byte-limit
validation. DCS now consumes original bytes, preserving C1 bytes as payload and counting
XTGETTCAP storage without UTF-8 expansion. Six new original scenarios / 191 checkpoints
cover valid/malformed UTF-8, two-byte status-request limits, ST/CAN/SUB/OSC exits, NUL,
DEL and capability-query recovery. Five scenarios and the String/byte write-parity unit
failed before the fix. All 1566 JVM tests, fresh Release JS tests and 1113 focused Native
oracle/parser/session/terminfo tests pass; all 1078 VT artifacts reproduce byte-for-byte.

The DEL parser checkpoint adds 17 original-libghostty scenarios / 40 checkpoints for
ground output, escape/CSI/DCS states, OSC payloads, split UTF-8, REP, charsets and wrapping.
Before the fix, 14 original scenarios and one parser unit test failed. DEL now prints as
a narrow cell in ground, is ignored in DCS and remains payload in OSC/APC. The session
matches Ghostty's Latin-1 width fast path without changing generated Unicode properties.
This checkpoint passes all 1559 JVM tests, fresh Release JS tests and 1090 focused Native
oracle/parser/Unicode tests. All 1072 VT fixture/manifest artifacts reproduce byte-for-byte.
These tests cover this parser boundary; the broader completion gates remain open.

The Ghostty feature-parity work remains in progress. Its requested scope is:

- [x] Kitty keyboard negotiation and all five enhancements, common encoder and AppKit/browser input.
- [x] xterm numeric modifyOtherKeys mode 2, matching the pinned Ghostty.
- [x] Light/dark query (`CSI ? 996 n`) and theme notifications (2031).
- [x] Kitty Color Protocol (`OSC 21`), including atomic request limits and query ordering.
- [x] Horizontal margins (DECSLRM / 69).
- [x] In-band resize (2048), physical cell geometry from AppKit/browser and CSI 14/16/18 t.
- [x] REP, Kitty scroll-and-clear (`CSI 22 J`), XTSAVE/XTRESTORE (strict history limits documented).
- [x] X10 mouse tracking (9).
- [x] OSC 52 clipboard and OSC 1337 Copy with host policy and platform integration.
- [x] Desktop notifications (OSC 9/777) and ConEmu progress (OSC 9;4).
- [x] OSC 7 / OSC 1337 CurrentDir working directory, report observers and local-directory inheritance on macOS.
- [x] OSC 133 prompt/command metadata, redraw semantics and prompt navigation APIs/menu.
- [x] Kitty graphics, including platform rendering and resource limits.
- [x] OSC 22 pointer shape with browser CSS and Ghostty-equivalent AppKit mappings.

The graphics gates below are complete; the overall production gates above remain open.
The original-library oracles compare image/frame bytes, replies, placement geometry and
storage accounting. Separate Canvas/WebGL and AppKit/Metal tests verify our actual pixels,
cache pressure and lifecycle; they do not compare font rasterization with Ghostty's GUI.
See the [resource ownership audit](terminal-graphics-memory.md) for the distinct budgets,
host-dependent presentation surfaces and retained snapshots. The following paragraphs retain
the implementation history and the validation available at each earlier checkpoint.

The first graphics increment added a byte-oriented APC lifecycle
and an internal `G` command parser with fixed header storage, bounded Base64 capture,
32-bit signed/unsigned fields and ownership-safe completion/reset. At that checkpoint all eight
command actions were parsed, including animation commands, but the session still ignored image
requests. The later session
execution increment is described below; subsequent browser and native increments enable static
graphics in Canvas/WebGL and AppKit/Metal views respectively.
Tests adapted from the pinned Ghostty parser cover flags, malformed headers, duplicate keys,
integer overflow, padding/tail bits, every input split, 150 KB data and resource release.
This checkpoint passes 490 JVM tests, the freshly built JS suite and 11 focused Native tests;
the existing 279 libghostty VT scenarios remain green on JVM/JS. The new command-parser tests
are adapted unit tests, not a new original-libghostty image-state comparison.

The image decoder increment adds common RGB/RGBA, PNG and zlib decoding. PNG supports
all standard color types and depths, transparency, all filters and Adam7, with CRC and
Adler checks. Dimensions and per-buffer byte limits are checked before pixel allocations;
PNG reads fragmented IDAT directly and reconstructs rows using bounded scratch memory.
The default PNG image is decoded without color-profile conversion. Kitty animation,
storage-wide memory accounting and platform image presentation remain separate work.
Independent libpng/zlib fixtures cover 120 pixel cases, 23 valid compressed streams and
341 rejected streams. The storage oracle exposed and fixed rejection of trailing zlib
bytes; a dedicated decoder regression now covers that rule. The decoder checkpoint passes
499 JVM tests from its isolated commit tree, the freshly built JS suite and 20 Native
parser/decoder tests. Both generated image artifacts reproduce
byte-for-byte. See `kinetica-terminal/test-upstream/images/README.md` for the pinned tools,
limits and precise coverage. Neither parser nor decoder executes image commands in the
session yet, and this is not an original-libghostty image-state oracle.

The base-image storage increment adds an internal per-screen component for query validation,
t/T transmission metadata, chunk assembly, image IDs/numbers, inherited quiet flags,
replacement-at-first-chunk, bounded storage and priority/age eviction. It returns display
metadata to the future placement executor; it does not claim to execute a=T end to end.
Image content generations remain distinct across replacement, reset and independent stores.
The new original-libghostty graphics corpus covers 15 scenarios and 120 checkpoints of exact
replies and decoded RGB/RGBA bytes, including zlib, queries during pending transfers, ID
allocation, replacement, image removal, limit changes and reset. These are component-level
transfer/storage tests; deletion commands are translated to storage operations by the test
adapter, so placement-aware delete dispatch, animation and session integration remain open.
PNG transfer uses the shared decoder and its independent libpng corpus; the C oracle does
not yet install a PNG host callback. Validation passes 505 JVM tests, the freshly built JS
suite and all 26 Native graphics tests. The 15 new C-helper paths pass AddressSanitizer with
the same uninstrumented-library/disabled-LeakSanitizer limits noted below.

Shared placement geometry now covers intersected source rectangles, native/aspect-ratio/
explicit-grid sizing, creation-time offset clamping, unsigned saturation, z-layer classification,
viewport visibility, bounded cursor advancement and permanent top/bottom crops during margin
scrolls. An original-libghostty geometry corpus supplies 98 scenarios and 1400 checkpoints,
including zero/changed cell geometry, large protocol values, scrollback origins and rectangular
margin containment. The common tests compare the library's exact placement fields and geometry,
layer-filter membership, cursor/history results and cropped rectangles. Validation passes all
510 JVM tests, the freshly built JS suite and 31 Native graphics tests. All 286 text/graphics
oracle artifacts reproduce byte-for-byte; the 98 geometry C-helper paths pass AddressSanitizer
with the same instrumentation limits. These functions are not yet wired to live line anchors,
reflow/screen lifecycle or renderers; the complete placement acceptance item stays open.

The following increment adds row-owned graphics anchors to the session and resizer. Anchors
survive ordinary text erasure and follow retained rows through scrolling, primary reflow and
alternate-grid resizing. Retired rows invalidate anchors and release references on strict
history eviction, clear/reset and recycling. Empty rows with anchors remain significant during
height reduction; wide-cell and blank-cell remapping is checked against actual libghostty
placements. The dedicated corpus contains 45 cases and 418 checkpoints, with whole/bytewise
replay. Explicit history erasure deliberately invalidates deleted anchors instead of Ghostty's
relocation to `(0, 0)`; the corpus documents that policy and preserves the original snapshots.
This remains internal infrastructure: placement dispatch, margin integration and renderers are
still outstanding. Validation passes all 515 JVM tests, the freshly built JS suite and 36 Native
graphics tests. Both new generated artifacts reproduce byte-for-byte, and the 45 C-helper paths
pass AddressSanitizer with the upstream library uninstrumented and leak detection disabled.

The session execution increment connects bounded APC capture to screen-local image storage and
placements. Direct base-image `q/t/T/p/d` commands now run through the real session, including
deferred display after chunk completion, inherited quiet flags, ID/number lookup and exact
responses. Placements support replacement, internal IDs, virtual records and relative parent
chains with cycle/depth checks and saturating offsets. Parent deletion cascades through a
reverse-edge index; uppercase deletion, replacement and eviction apply their distinct image
lifetime rules. Static deletion selectors, ED2/22J, screen switches, reset, full/margin scrolling
and IL/DL are connected to the engine's row operations. A single graphics APC capture is limited to
65 MiB (raised from this increment's original 1 MiB to match Ghostty 1.4), and each screen
has at most 16,384 placements as an embedding policy; existing image transfer/decode/storage
limits still apply. Disabled storage does not capture payloads or answer capability queries.

An original-library session corpus covers 196 scenarios and 2017 checkpoints. Both engines
receive complete command bytes; tests compare replies, decoded image bytes, placement metadata,
geometry, layer membership, cursor, history and text rows after each checkpoint. This exposed
differences now covered explicitly: replacement reclaims orphan data, IL/DL keep grid positions,
full-screen SD/RI retain positions, and no-history SU clamps origins to the first row. Separate
tests cover placement/capture limits, both-screen cleanup, disabled recognition and damage
publication during synchronized output. This increment does not yet draw images: hosts have an
internal opt-in storage limit, left disabled in application views. Animation commands, Unicode
placeholder rendering, external media, view snapshots/caches and platform pixels remain open.
Validation passes all 521 JVM tests, the freshly built JS suite and all 42 Native graphics
tests. The new fixtures and manifest reproduce byte-for-byte. All 196 C-helper scenarios pass
AddressSanitizer with the original library uninstrumented and leak detection disabled.

The browser rendering increment adds immutable image-placement snapshots to viewport holds,
viewport clipping before GPU float conversion, and a shared tiled-image representation. Canvas
and WebGL now render all three image layers with non-default backgrounds kept opaque and block
glyphs in the text layer. Cropped edge tiles prevent linear-filter color bleeding; one-pixel
gutters keep scaled internal seams continuous. Browser views enable a configurable 64 MiB
decoded budget per screen, while each renderer owns a separate 64 MiB tile LRU. Invisible
tiles, replacement generations, lost contexts and view disposal release renderer resources.
Session pixels survive view disposal and remain available for remounting.

The pixel verifier exercises both Canvas and WebGL at device scale 2: backgrounds and block
glyphs across all layers, RGBA blending, source cropping, chunked scaled images across tile
boundaries, cache reuse/deletion, scrolling/history, alternate-screen isolation, synchronized
replacement, WebGL recovery during a hold and actual canvas/texture disposal. It caught and
fixed crop-edge filtering bleed in both backends. Validation passes 526 JVM tests, the freshly
built JS suite, 68 selected Native graphics/GPU/block/presentation tests and the existing browser
integration verifier. These are browser pixels and shared Native tests, not AppKit/Metal image
presentation tests. Native rendering, animation, Unicode placeholders and external media remain
open, so the overall graphics acceptance items below remain incomplete.

The native rendering increment connects the same image snapshots/tiles to AppKit and Metal.
AppKit uses explicitly released CGImages backed by copied CFData, so pinned Kotlin buffers
never escape their lifetime. Fractional tile clips disable edge antialiasing to avoid seams;
image interpolation remains linear. Metal uploads premultiplied RGBA tiles and uses a separate
linear sampler for images while retaining nearest sampling for the glyph atlas. Each image
draw copies its instance bytes into command-owned storage; interactive rendering keeps the
existing single asynchronous frame in flight and does not wait for GPU completion.

Native views now enable the same configurable 64 MiB decoded budget per screen as browser
views, with a separate 64 MiB renderer tile cache. Deletion, screen changes and disposal drop
cache entries; submitted Metal commands own resources until completion. New offscreen pixel
tests cover all three layers and block glyphs, blending, source crops, orientation, tile seams,
cache reuse/disposal, held replacements and scroll/screen transitions. Metal runs at 1x, 1.25x
and 2x. All 77 selected Native graphics/GPU/block/presentation/Metal tests pass, and the native
application plus its UI module compile without installing or replacing the user's app. The
shared/browser implementation is unchanged from the preceding verified commit. Animation,
Unicode placeholders and external media remain open.

The Unicode-placeholder increment scans visible `U+10EEEE` runs, using raw palette indices/RGB
bytes for IDs and the pinned diacritic table for row/column/high-ID fields. It preserves Ghostty's
run inheritance, virtual-target preference, aspect-ratio fitting, centering and exact integer
rounding. Placeholder images always enter the below-text layer, while the placeholder grapheme
itself never reaches a font renderer. Relative descendants can resolve against the minimum
visible origin of a virtual parent's cells. Viewport snapshots retain those fragments and their
image generations together with text during synchronized output and resize holds.

Rounded source spans can be zero or extend one texel past an edge while destination pixels
remain visible. Shared tiles implement premultiplied boundary sampling and edge clamping, so
all four renderers handle these cases consistently. The new oracle links the unmodified pinned
Ghostty **Zig module**, because its C placement API does not expose placeholder runs/geometry.
It covers 390 cases and 403 checkpoints, including every diacritic, unsigned image IDs, invalid
marks, target lookup, palette changes, grid limits, scroll/reflow and screen transitions.
The screen-switch case explicitly selects the bottom viewport after returning to primary,
matching this host's existing screen-switch policy rather than changing viewport UX.

Validation passes 529 JVM tests, the freshly built JS suite, 81 selected Native graphics/rendering
tests and real Canvas/WebGL/AppKit/Metal placeholder pixels, including inheritance, cell erasure,
held frames and rounded source spans. Generated diacritics, fixtures and their provenance manifest
reproduce byte-for-byte. This oracle runs with Zig ReleaseSafe checks; it is not an ASan run of
the full Ghostty renderer. Animation and external media remain open.

The next increment adds the isolated animation core: immutable full RGBA frame buffers,
clipped transmission, Wuffs-compatible integer source-over composition, frame edits/deletion,
playback controls and deterministic monotonic-clock advancement. A new oracle executes the
unmodified pinned Ghostty Zig module for 400 cases and 2030 checkpoints, including all 65,536
source/destination alpha combinations. It compares every frame's bytes, gaps, playback state,
loop counters, timestamps, memory size and content-generation invalidation. Fixtures and their
manifest reproduce byte-for-byte. All 533 JVM tests, the fresh Release JS suite and 57 Native
graphics tests pass. Additional tests preserve previously published pixels across edits and
root promotion, exercise failed frame admission and prevent gapless scheduling loops.
This is a component checkpoint: session dispatch/replies, chunk identity, eviction accounting,
render snapshots and host timer integration still need to consume this core. Animation therefore
remains unchecked below; the current session still rejects standalone animation commands.

Render frames now carry a separate immutable image-content snapshot rather than a storage
entry. Texture identity follows the immutable pixel buffer, independently of transfer identity,
so returning to an unchanged animation frame can reuse its texture while editing a frame
cannot alter already captured pixels. The animation/snapshot regression covers selection,
editing and root promotion. All 534 JVM tests, the fresh Release JS suite and 58 Native graphics
tests pass, including actual AppKit/Metal pixels. The fresh Canvas/WebGL verifier also passes
image pixels, held frames, tile reuse and cleanup. Session animation and host timers remain open.

Session execution now consumes the animation core. Commands `a=f`, `a=a`, `a=c` and `d=f/F`
load/select/edit/compose/delete frames, with original reply fields and quiet inheritance.
Transmit-like continuation commands retain the first chunk's header and image generation;
intervening content changes or eviction reject the stale transfer. Storage accounts for every
full frame, excludes the target from frame-admission eviction and updates image-number lookup
on content changes. Final-frame deletion preserves descendant cleanup, while root promotion
retains placements. Explicit monotonic ticks publish damage without requiring more PTY bytes,
and synchronized-output snapshots keep their original pixels until release.

The 400 animation cases now replay through complete and bytewise session input as well as the
isolated core. The C execution corpus grows to 244 cases/2394 checkpoints, adding 48 scenarios
for replies, quiet modes, chunk identity, quota/eviction, screen lifecycle and raw/compressed
RGB/RGBA/PNG. Its graphics wrapper installs libpng 1.6.59 through libghostty's host decoder API;
the original base oracle still runs without that callback. Generated fixtures and manifests
reproduce byte-for-byte. Client-selected frame pixels, edits during holds, composition and root
promotion are exercised in Canvas/WebGL and AppKit/Metal (including fractional native scales).
Validation passes 536 JVM tests, the fresh Release JS suite, 61 Native graphics tests and the
fresh browser pixel verifier. The C graphics host helper passes all 244 scenarios with
ASan/UBSan; the linked original libghostty dylib is not instrumented in that check.
Automatic host timers/visibility remain open. Root RGB conversion retains Ghostty's admission
semantics, which can exceed the nominal pixel-byte limit; the broader resource audit remains open.

Automatic playback now uses a shared monotonic clock and one-shot viewport timers. A timer
requests a redraw; advancement occurs when the renderer builds that frame, avoiding timer
loops and catch-up while drawing is stalled. Hidden/detached/disposed hosts and synchronized
or resize holds cancel animation wakeups. Visible hosts resume without additional PTY input.
Browser hosts observe document/intersection visibility and ordinary root/ancestor visibility
changes; AppKit hosts observe window occlusion, attachment and hide/unhide transitions.
The native window regression also exposed a software backing-layer callback that suppressed
`drawRect`; the custom display delegate now belongs only to the Metal layer.

This checkpoint passes 541 JVM tests, the freshly built Release JS suite, both browser
verifiers (Canvas/WebGL pixels, timed finite playback, hidden ancestors and existing input/
rendering/lifecycle coverage), and 96 selected Native graphics, GPU, presentation and render
observer tests. The native timer test uses actual isolated software/Metal windows and checks
hide/show, ordering out, detach/reattach and disposal. Deterministic common tests cover stale
callbacks, stalled rendering, finite loops, loading state, holds and shared-session views.
External media and the broader storage/frame-wide resource audit remain open.

The next increment adds the common external-media host boundary. `attachGraphicsMedia` applies
to both screens and denies external access when detached. The executor resolves `t=f/t/s`
payloads before image decoding, preserves unsigned `O`/`S`, verifies exact explicit lengths and
returned-byte limits, and retains query/frame/replacement and quiet-reply semantics. Direct
chunk continuations never reinterpret their payload as a path. Fixed error variants prevent
host exception details from entering protocol replies. Six new common tests pass in the full
547-test JVM suite and fresh Release JS suite. These tests use injected readers; actual native
filesystem/shared-memory access, unlink behavior and platform regressions remain unimplemented.

The reference migration to Ghostty 1.4.0 (`83fb95624b0439ee660c2679b43b38c8b63769cd`)
now updates the main C oracle and all C graphics corpora. The 279 text/input scenarios retain
1760 checkpoints; graphics now include 48 anchor cases/442 states and 266 execution cases/2489
states, alongside the unchanged-size storage and geometry corpora. Their generators reproduce
byte-for-byte. New cancellation and reflow cases use the original library's results, and
explicit history erasure no longer needs a reference-policy exception.

The implementation follows the release's blank-pin/saved-cursor reflow, APC cancellation,
history placement cleanup, RIS palette reset, empty OSC 22, widening-grapheme spacer attributes,
origin-relative movement, left-margin CUB/BS and prompt-aware ED2 behavior. Validation passes
547 JVM tests, fresh Release JS tests, 279 Native oracle tests, 103 selected Native graphics/
GPU/presentation/pointer tests and both browser verifiers.

The Zig animation and placeholder corpora now also use 1.4: all 400 animation scenarios and
390 placeholder scenarios remain covered, with original RenderState visibility checks.
The storage clock matches the release's idle-generation gate and resumes after image or
placement mutations. This increment passes 548 JVM tests, fresh Release JS tests, 74 Native
graphics/animation tests and both Canvas/WebGL graphics verifiers. New 1.4 protocols and
remaining work are tracked in the
[1.4 migration checklist](ghostty-1.4-migration.md); this checkpoint does not claim full 1.4 parity.

External media now abandon unfinished direct image/frame loads before processing a new
transmission, matching the 1.4 executor even when the host rejects the read. Queries and
conflicting identifiers preserve the existing load. The execution oracle adds 63 scenarios
(total 329 / 2813 checkpoints); injected-host tests cover successful file, temporary-file and
shared-memory replacements and frame additions. The stable transmission ticket in upstream
snapshot restoration is separate from APC chunk identity, which still checks pixel generation.
The corpus reproduces byte-for-byte; all 550 JVM tests, fresh Release JS tests and 14 focused
Native media/execution tests pass.

DECSTR (`CSI ! p`) now resets the active pen/protection/charsets, saved cursor, margins,
implemented resettable modes, modifyOtherKeys, cursor appearance and palette overrides.
It preserves text/history, cursor position and pending wrap, dynamic colors, hyperlinks,
custom tab stops, unrelated modes/Kitty keyboard flags, synchronized output and graphics
(including unfinished image/frame loads). Parameterized or prefixed forms are ignored.
Fifteen original-libghostty scenarios add 49 intermediate states; a graphics case checks
preserved transfers, and host-default/presentation regressions cover configured cursor style
and deferred repaint. Main oracle totals are 294 scenarios / 1809 states; graphics execution
is 330 / 2823. Both corpora reproduce byte-for-byte. All 567 JVM tests, fresh Release JS tests, 294 Native
oracle tests and 8 Native reset/graphics tests pass. Status-display,
checksum and reverse-wrap/keyboard-disable behavior remain tracked in the migration checklist.

DECXCPR now includes page 1 (`CSI ?row;column;1R`) and `CSI 19 t` replies with the screen
size in characters (`CSI 9;rows;columns t`). Device status requests reject extra parameters
and unsupported private forms. Four additional original-libghostty scenarios cover normal/
alternate screens, pending wrap, origin/margins, unknown host geometry, resizing and malformed
queries (298 scenarios / 1829 states total). All 571 JVM tests and fresh Release JS tests pass.

DECRQCRA/XTCHECKSUM now implement all 32 combinations of the original checksum flags,
including DEC character mapping, full Unicode codepoints, combining suffixes, wide spacers,
video attributes, blank trimming, undrawn cells, signed sums and origin-relative clipping.
The host must explicitly enable screen readback with `configureChecksumReporting`; defaults
match standalone Ghostty and libghostty (disabled, DEC-compatible variant). RIS/DECSTR restore
the host's configured variant without changing the readback policy. Invalid host configuration
is rejected before changing either setting. DECSASD discards printable status-line output
while controls continue to execute; both resets select main output. Blink attributes are now
retained for checksums, save/restore and DECRQSS, without changing the existing text renderer.

Fifty original-library scenarios add 212 intermediate states, including every flag combination,
policy transitions, malformed requests, screen/history changes and reset behavior (348 scenarios /
2041 states total). All C corpora and manifests reproduce byte-for-byte. The C helper's optional
checksum API branch still permits compilation against the earlier reference headers for release
comparisons. Transport tests cover checksum/size replies after a maximum palette reply and
exact pause/resume through bounded input queues; the existing reply reserve remains sufficient.
Validation passes all 622 JVM tests, fresh Release JS tests, all 348 Native oracle scenarios and
6 additional Native checksum-policy/backpressure tests.

Visibility protocol mode 2033 now connects to browser and AppKit hosts, with a one-shot
`CSI ?998n` query and `CSI ?999;1/2n` responses. Repeated enables report immediately;
unchanged host visibility does not. RIS clears the subscription but preserves host state,
while DECSTR preserves both. Disposable host bindings aggregate multiple surfaces without
letting headless rendering viewports override them. Disposed bindings cannot revive a surface.

The pinned original Zig parser/mode/encoder produces 11 cases / 92 checkpoints, replayed
whole and bytewise; fixture and manifest generation reproduces byte-for-byte. The helper's
explicit host adapter models changed visibility and subscription gating without inserting
query bytes into incomplete sequences. All 625 JVM tests and fresh Release JS tests pass,
as do 10 selected Native tests covering original fixtures, bounded replies, animation timing
and real software/Metal window hiding, hidden ancestors, full occlusion, detach/reattach and
disposal. Both browser verifiers pass; visibility coverage uses real CSS and DOM changes plus
a synthetic document-visibility event, not an actual background-tab transition.

OSC 7501 now exposes validated program-status reports and record snapshots through
`onProgramStatus`. Installing a host observer enables support-query replies; without one,
reports and queries are ignored, matching libghostty's optional effect handler. Views do
not automatically present statuses or send desktop notifications. Parsing follows original
byte limits, duplicate-field semantics, ID validation, Base64 and safe UTF-8 checks. Reply
contents are fixed capabilities and never echo report data.

The registry implements complete replacement, nearest-ancestor app resolution, subtree
clearing and a 256-record least-recently-updated cap. Records are independent of screens and
render holds. RIS clears them; DECSTR preserves them. New shell prompts, drained EOF and
abrupt transport disposal discard working/blocked states while retaining idle/done/error.
Disposing an already finished transport does not clear a later program's records.

The original-library oracle covers 260 cases / 290 checkpoints, replayed whole and bytewise;
fixtures and provenance reproduce byte-for-byte. Registry semantics are separately tested
against the [protocol specification](https://www.superlogical.com/rex/docs/build/program-status),
not a copied oracle registry. Validation passes all 631 JVM tests, fresh Release JS tests,
22 Native parser/registry/transport/backpressure tests and 8 macOS host/PTY tests, including
real process exit and disposal. The fresh browser integration verifier also passes.

BS/CUB now implement normal and extended reverse wrap (modes 45/1045), including pending
wrap consumption, soft-line boundaries, hard-line crossing, scrolling-region wraparound,
outside-margin behavior and extended-mode precedence. DECAWM gates both modes; RIS/DECSTR
reset them. Fifty-four additional original-library scenarios cover 232 intermediate states,
including one-cell grids, wide glyphs, mode queries, save/restore and screen changes. The main
corpus now has 402 cases / 2273 states and regenerates byte-for-byte.

ANSI KAM (mode 2) is tracked independently of host permission. `keyboardLockAllowed` defaults
to false, as in Ghostty's `vt-kam-allowed`. With explicit opt-in, `sendKey` and committed
`sendText`/IME input are suppressed, but paste, local shortcuts, raw host input and protocol
replies remain available. The pure encoder is unchanged. AppKit and browser input paths use
this policy, and RIS/DECSTR clear the requested lock while preserving host permission.
Validation passes all 686 JVM tests, fresh Release JS tests, 402 Native oracle cases, six
Native common keyboard tests, two AppKit keyboard/IME tests and the updated browser verifier.

Native external Kitty media now have a concrete `MacOsTerminalGraphicsMedia` host adapter.
Library defaults deny all media; the native local-PTY application explicitly enables files,
temporary files and shared memory and detaches the binding on close. Reads validate opened
file types/canonical paths, exact sizes and offsets, enforce encoded-byte limits, and map only
the requested shared-memory range plus alignment. Shared names are passed unchanged. Validated
temporary files and opened shared-memory names are consumed even after read/decode failure.
Path-based cleanup follows the original semantics; it is not race-free against concurrent name
replacement. Nonblocking file open prevents a FIFO from hanging before regular-file validation.

A separate original-Ghostty native oracle records 61 real-resource scenarios. Fifty-seven
compare bytes, errors and cleanup; four past-EOF file cases crash the original Zig safe reader
and are retained as explicit upstream crashes. Our empty EOF recovery is tested separately,
not counted as conformance. The reader oracle never invokes PNG decoding; native integration
tests separately cover PNG success/failure, image/frame transmission, queries and cleanup.
Fixtures and provenance regenerate byte-for-byte. Validation passes 686 JVM tests, fresh Release
JS tests, 16 focused Native media/decoder tests and one native application policy/lifecycle test.
Descriptor-stability, host read limits and FIFO rejection are included. No installed app or
live user shell is replaced or terminated by these tests.

The next terminal-state increment preserves pending wrap across HT, CHT, CBT and TBC,
including when a backward tab leaves the right margin. TBC accepts its omitted/default mode
and rejects extra parameters. The CSI parser now follows Ghostty's trailing-parameter rule:
a separator commits the preceding value but an empty final slot adds no parameter. An SGR
attribute with a colon but no subparameter (such as `4:`) leaves the existing pen unchanged.

Fifty-nine original-library scenarios / 342 checkpoints cover those controls with default and
custom tab stops, DECAWM on/off, narrow/wide/combining output, malformed headers, SGR, reports
and cursor commands. The main corpus now has 461 cases / 2615 checkpoints and reproduces
byte-for-byte. Validation passes all 745 JVM tests, fresh Release JS tests and all 461 Native
oracle scenarios. Charset designation
and single-shift remain separate open work in the Ghostty 1.4 migration checklist.

Charset handling now uses immutable G0–G3 state with ASCII, British and DEC Special Graphics
designations, GL/GR invocation and a pending single-shift slot. SI/SO, LS2/LS3 and SS2/SS3
follow the original. A single shift is consumed by the next printable codepoint even when
it combines with an existing cell or has zero width; controls and ignored status-line text
preserve it. Width/grapheme processing uses the unmapped codepoint. Unknown designations
preserve the selected set, Unicode above 255 stays unmapped, and DEC underscore stays `_`.
GR invocation is retained without remapping UTF-8 output, matching Ghostty's current scope.

Saved cursors retain all charset state; normal screen switches copy it and 1049 exit restores
the saved state. Restoring an unset slot now applies initial defaults/home. DECSTR invalidates
the saved slot so resize cannot reflow a fabricated saved position. Both resets restore default
charsets. Seventy-one original-library scenarios exercise these paths; two exact Alacritty
recordings establish the documented underscore difference without modifying archived fixtures.

The original corpus now contains 534 cases / 2961 checkpoints and regenerates byte-for-byte.
Validation passes all 818 JVM tests, fresh Release JS tests, and 591 Native tests covering
the original Ghostty corpus, Alacritty replays and parser regressions. This closes the charset
migration item; clipboard transactions and the remaining protocol/resource audits stay open.

The mode/input audit adds DECBKM (67), query and private save/restore. Legacy plain and
Ctrl+Backspace swap DEL/BS; Shift/Alt/Super combinations keep their original table entries,
modifyOtherKeys keeps its extended encodings, and Kitty ignores DECBKM. Printable host text
on a Backspace event is consumed without sending deletion bytes, matching dead-key behavior.
The mode survives DECSTR and screen changes; RIS resets it. DECRPM now reports permanently
reset for ANSI 1/5/7/10/11/13–19 and DEC 117, independently of set/reset attempts.

Thirty-eight original scenarios add 166 checkpoints, including unknown/malformed queries,
press/repeat/release, modifiers/lock bits, host text, protocol changes and reset/save lifecycle.
They also confirm that OSC 105 affects unsupported special colors in Ghostty, not the ANSI
palette: our ignored behavior already agrees, as do unsupported dynamic resets 113–119.
The full 572-case / 3127-checkpoint oracle regenerates byte-for-byte. Validation passes 856
JVM tests, fresh Release JS tests, 578 Native oracle/keyboard tests, three AppKit input tests,
and the fresh browser integration verifier with real Backspace events.

Mouse formats 1005 (UTF-8), 1015 (urxvt) and 1016 (SGR pixels) now work through the
common encoder and both views. Physical pixels account for DPR/backing scale; pixel mode
retains subcell motion and unclamped outside drag/release positions. Cell formats keep their
motion suppression. Format requests have independent query/save bits, while the most recent
command selects the wire format; disabling any format returns X10. Kotlin/JS explicitly
roundtrips float bits so coordinates have the original encoder's f32 precision on every target.

Fifty-one original scenarios add 264 checkpoints, including a persistent original encoder
for motion deduplication, pixel rounding/boundaries, UTF-8 thresholds and format lifecycle.
The full 623-case / 3391-checkpoint oracle regenerates byte-for-byte. Validation passes 910
JVM tests, fresh Release JS tests, 630 Native oracle/common-mouse tests, four Native AppKit/
PTY mouse tests, and the browser integration verifier at DPR 2. Native synthetic middle-button
and wheel events now preserve explicit coordinates instead of inheriting the user's cursor.

Legacy Alt mode 1036 now controls text prefixing, with UTF-8, missing-text and layout-scalar
fallbacks. AppKit Option-as-Meta carries the actual text and layout identity; browser Alt events
use known unshifted layout data. Modified Enter/Tab/Escape follow the original PC-style table,
independently of 1036, and C0/fixterms distinguish combinations such as Control+i/m/[. Multi-scalar
modifyOtherKeys input falls through to legacy handling; Kitty consumes events without text or
identity without inserting data. Existing Shift+Enter and Control+[ host expectations were
updated to the original-library bytes, rather than preserving those older mismatches.

ANSI SRM 12, DEC slow-scroll 4, autorepeat 8 and Alt mode 1039 now preserve/report state with
Ghostty's defaults, RIS/DECSTR and screen lifecycle. They have no invented echo, scrolling or
repeat-filter behavior: the original stores them but has no runtime consumers for those effects.
Twenty-two original cases add 131 checkpoints, including all modifiers for Enter/Tab/Escape,
C0/fixterms, locks, Unicode and all three key actions. The oracle's optional Option-as-Alt setting
separates host configuration from consumed text modifiers. The full 645-case / 3522-checkpoint
corpus reproduces byte-for-byte. The four graphics corpora sharing the C helper were regenerated
for provenance; all their generated Kotlin snapshots stayed unchanged.

Validation passes 933 JVM tests, fresh Release JS tests, 664 Native oracle/keyboard/AppKit tests
and the browser integration verifier, including Alt-translated Unicode with a known layout.
Reverse video (DEC 5) now swaps effective default foreground/background at the viewport's
render-theme boundary. Stored colors, OSC replies, palette values and explicit cell colors
remain unchanged; SGR inverse still acts after default resolution. Cursor/selection colors
retain their identities, and the block cursor's text uses the effective background. The shared
boundary also covers surface clearing, composition overlays, image backgrounds and retained
frames, so a mode change during synchronized output appears only when the frame is released.

Ten original-library scenarios add 49 checkpoints with optional original RenderState colors
(`O` in the C helper), alongside existing raw cell/OSC comparisons. The complete 655-case /
3571-checkpoint corpus reproduces byte-for-byte. Shared graphics-helper provenance was
regenerated with unchanged graphics snapshots. Validation passes 946 JVM tests, fresh Release
JS tests, 698 Native oracle/color/presentation/selection/Metal tests, five native graphics pixel
tests (software plus Metal at 1×/1.25×/2×), and the Canvas/WebGL browser verifier. These checks
cover explicit RGB, default-color glyphs/backgrounds, cursor/selection, images and held frames.

DECCOLM now honors the mode-40 gate and selects 80/132 columns before clearing the active
display with the current background/protection rules. Without the gate it leaves the screen
unchanged and reports mode 3 reset. Resizing resets tab stops when width changes and respects
DECAWM for primary/history reflow. Browser/AppKit refresh their physical grid on mode-40
set/reset; ordinary browser painting no longer overwrites the protocol-selected width.
Existing resize observers propagate dimensions to stream transports and real native PTYs.

Nineteen focused original-library cases add 70 checkpoints, plus six unchanged Alacritty
recordings whose DECCOLM behavior differs from Ghostty. Their complete Ghostty results replace
the Alacritty expectations in the replay adapter; the original fixtures are retained. Raw
Ghostty snapshots also preserve its missing end-hold callback on internal DECCOLM resize.
The explicitly named `paired-resize` test policy reconciles that callback with original mode
state because Kinetica's host contract releases held frames with paired notifications.

The 680-case / 3647-checkpoint corpus reproduces byte-for-byte. Validation passes 972 JVM tests,
fresh Release JS tests, all 680 Native oracle cases, 28 Native AppKit/PTY/transport/size/frame
tests, and the Canvas/WebGL browser verifier, including host resizing and mode-40 restoration.
Clipboard paste events (5522) remain open, along with the broader acceptance work below.

The first OSC 5522 implementation increment supplies an internal metadata decoder and bounded
write accumulator. It validates structure before decoding duplicate fields, distinguishes dropped
packets from invalid values, sanitizes IDs, enforces UTF-8/field limits and decodes strict Base64
across arbitrary packet boundaries. Transactions preserve MIME insertion order, overwrite earlier
regions, resolve ordered aliases and count overwritten bytes toward the captured write budget.
An invalid/oversized transaction cannot commit partial data. MIME and alias maps each cap at 64;
tokenization avoids allocating a list for every metadata field or whitespace-separated alias.

These primitives are not yet wired into session OSC dispatch or platform clipboard access.
The initial corpus contained 519 original-Ghostty cases / 1716 checkpoints, with
source/helper/generator/output hashes and byte-for-byte regeneration.
Validation passes 975 JVM tests, fresh Release JS tests, and the three Native core tests
(including all original-module checkpoints). No platform clipboard access is enabled by this increment.

The second OSC 5522 increment adds exact status/read/paste-event response encoding and
session password-grant storage. Read data is emitted lazily in 4096-byte chunks, empty
representations are omitted, and targets listings obey the original byte bound and newline
rules. Password grants distinguish read/write, merge persistent and one-time permissions,
consume one-time grants even on a direction mismatch, and preserve the original 32-entry
capacity and eviction order. This store accepts passwords; secure entropy generation and
authorization decisions remain outside it.

The second-increment `clipboard-core-manifest.json` covered 676 cases / 4072 checkpoints, including
all response statuses, BEL/ST, binary chunk boundaries, UTF-8 listings, capacity boundaries
and 2000 interleaved grant operations. Validation passes 978 JVM tests, fresh Release JS tests
and six Native clipboard-core/response tests. Fixtures and manifest reproduce byte-for-byte.
Session routing, async authorization/completion, secure paste events, parser capture budgets,
reply backpressure and native/browser MIME adapters remain required to enable OSC 5522.

The third increment adds an internal asynchronous dispatcher for read/write authorization,
MIME transactions and status replies. Host operations remain serialized until the complete
response is acknowledged by the reply sink. Pending requests and their retained bytes are
bounded; a full queue rejects the packet without effects so the owner can pause and retry.
Read results are copied before deferred delivery, and both decoded data and exact encoded
reply sizes are checked before emitting OK. Duplicate callbacks are ignored. Binding reset
or disposal cancels pending work, including an unfinished write transaction; this is an
embedding lifecycle operation, not an assertion that Ghostty's RIS aborts write assembly.

The third-increment corpus contained 718 cases / 4262 checkpoints, including 42 original TerminalStream
dispatch scenarios that compare host access metadata, committed MIME contents and wire replies.
Separate tests cover asynchronous permissions, delivery acknowledgements, queue limits,
cancellation, host/transport failures and exact reply-size boundaries. Validation passes
985 JVM tests, fresh Release JS tests and 13 Native Kitty clipboard tests. Fixtures and
manifest reproduce byte-for-byte. At that stage the dispatcher was internal and unmounted: session
routing with parser backpressure, transport delivery acknowledgements, secure paste events
and native/browser MIME adapters remain necessary for end-to-end OSC 5522 support.

The transport increment introduces optional internal delivery receipts for protocol replies.
The byte ring acknowledges a reply only after the transport consumes its final byte; clearing
the ring cancels outstanding receipts. Session dispatch waits for every accepting transport,
and a plain input observer cannot claim delivery. Missing, rejecting or canceled consumers
produce failure. Completion chains run iteratively, and the native PTY prevents nested write
loops so callbacks can enqueue the next reply safely. Cleanup still releases queues, timers
and program status when a completion callback throws.

Validation passes 995 JVM tests, fresh Release JS tests and 35 Native delivery/stream/PTY
tests. The isolated PTY verifies 2000 chained replies, an intact 2 MiB payload and cancellation
of an undelivered tail. Initial large-PTY runs hit wall-clock deadlines; the fresh serial
Native run passes with the original deadlines and unchanged transport logic. Diagnostic
failure messages retain the delivery phase, pending byte count and terminal output.

Those receipts provided the delivery primitive for OSC 5522; the following session increment
connects them to the dispatcher.

OSC 5522 read/write commands now route through `TerminalSession.attachMimeClipboard` and the
public asynchronous MIME host/policy API. Access defaults to denial. OSC capture follows the
original 8 MiB allocating-parser limit, including UTF-8 byte accounting and cancellation.
The session retains at most one complete command while an earlier clipboard operation waits.
Operations are ordered across OSC 52 and OSC 5522, including async write-to-read transitions.
Stream/PTY adapters pause and resume without dropping the remaining bytes; the public
`writeAvailable`/`onOutputReady` pair exposes the same contract for custom feeders.
Custom input transports register public `ProtocolReply.trackDelivery()` receipts during
`onInput`; the common integration test exercises this entirely through public APIs.

Read responses are assembled within the checked 16 MiB wire budget and submitted atomically;
keyboard input cannot appear between their DATA and DONE packets. The two protocols share
delivery ordering. At most one response per live binding can wait, plus a detached response
already submitted to a transport. Replacing a host drops its waiting replies and commands
without transferring stale writes to the replacement. Closing a transport cancels outstanding
host callbacks. RIS clears grants/requests while preserving unfinished write assembly, verified
against a new original-Ghostty dispatch fixture. Detachment discards the assembly.

The corpus now has 719 cases / 4267 checkpoints. Session tests replay the dispatch fixtures
through the public host API and bounded transport, both byte-by-byte and in whole packets;
the custom four-byte write-budget fixture remains covered at the binding layer. Platform
MIME clipboard adapters, secure password generation and mode-5522 paste events remain open.

Validation passes 1006 JVM tests, fresh Release JS tests, 11 Native MIME integration tests
and 67 Native parser/clipboard/transport/PTY regressions. A separate real PTY writes 1 MiB of
binary MIME data, creates an alias and reads it back; the child checks every response byte,
including the available-types listing and DATA chunk framing. The 719-case corpus and its
manifest reproduce byte-for-byte.

The AppKit surface now binds a native MIME clipboard adapter, with a separate default-deny
policy exposed through the view and DSL host. The native application explicitly permits writes.
Type mapping, metadata-only listings and copied-file representations follow the pinned
Ghostty 1.4 macOS implementation: canonical plain text, shell-escaped file paths, and CRLF
URI lists. Custom binary MIME formats map through UTType. All formats are prepared in one
pasteboard item before publication; malformed text, conflicting native aliases and oversized
writes leave the previous contents intact. Reads have an aggregate 8 MiB budget; native-format
expansion has a 64 MiB write budget. A changed pasteboard owner invalidates a read with BUSY.
AppKit's clear-then-publish API can leave an empty clipboard on a system publication failure;
the adapter reports I/O failure and does not read old clipboard data for rollback.

Ten new tests use unique pasteboards and cover native/custom byte round trips, file URLs,
multiple items, lazy-provider access, limits, rejected writes, owner changes, unsupported
locations, policy and view disposal. The existing 1 MiB binary PTY exchange also passes, as do
nine DSL/native-application tests. The combined Native clipboard/parser/stream regression
suite passes all 72 tests. Browser MIME access, secure password generation and
mode-5522 paste events remain open.

The browser surface now binds a MIME adapter through the same separate default-deny policy;
the browser sample explicitly permits writes. It uses native ClipboardItem formats where
supported and Web Custom Formats for other MIME types, preserving browser permission and
sanitization requirements. Reads fetch only requested formats, list metadata without getType,
and enforce an aggregate 8 MiB limit before copying Blob data. Writes prepare a single item
and cap expanded representations at 64 MiB. Unsupported types reject the whole write. View
disposal cancels deferred data fetches and late protocol delivery. The browser cannot cancel
already submitted writes or clear the clipboard through the async API; clearing reports ENOSYS
instead of treating write([]), which leaves existing data unchanged, as success.

The dedicated headless Chromium verifier covers real plain text, HTML, PNG and binary custom
formats through OSC 5522, exact read-response bytes, write/read ordering, policies, unsupported
locations, lazy providers, aggregate bounds, a 4 MiB transaction expanded beyond 64 MiB through
aliases, synchronous/asynchronous API failures, missing API and disposal while a read is pending.
The fresh Release JS test suite, browser application build, dedicated MIME verifier and general
WebGL/Canvas/input/OSC-52 browser verifier pass. The OSC-52 verifier now uses the bounded
transport, which retains the remainder of ST terminators across asynchronous clipboard waits.
Secure password generation and mode-5522 paste events remain open on both platforms.

Secure paste-password generation now uses the original 22-character alphabet and unbiased
rejection sampling with 44-byte batches. Entropy comes from JVM SecureRandom, browser Web Crypto
getRandomValues and macOS SecRandomCopyBytes. Missing/failed entropy returns no password, with
no weak fallback; temporary random-byte storage is wiped on success and failure. Browser errors
are handled as JavaScript Throwable values, including DOMException, rather than only Kotlin
Exception subclasses.

The clipboard oracle now has 1043 cases / 4591 checkpoints, including 324 new calls to original
Ghostty generateOtp under deterministic entropy overrides. Fixtures check every byte value,
rejection/redraw, partial-generation failure and exact entropy-call counts. Separate platform
smoke tests exercise the real CSPRNG adapters; they do not claim to measure entropy quality.
Validation passes 1009 JVM tests, the fresh Release JS suite, 46 Native clipboard tests, and
four entropy tests in headless Chromium with real Web Crypto and injected failures. The
expanded corpus and manifest reproduce byte-for-byte.
User-initiated paste events, their delivery/grant lifecycle and mode-5522 capability reporting
remain to be connected.

The internal clipboard dispatcher now queues protected paste events alongside OSC operations.
Only metadata is fetched for a user gesture; no clipboard representation is read or sent.
It limits event listings to the original 16 MIME types and 4096-byte listing budget, generates
a fresh secure password, and installs a one-time read grant before sending the event. The
whole event must be acknowledged before another queued operation accesses the clipboard.
Entropy/metadata/size failures send no event and grant no access. Reset, disposal and output
failure revoke grants and cancel queued gestures; stale callbacks cannot complete newer work.
Targets-only reads and requests without a human name leave a grant unused; checking the wrong
direction consumes it. An explicit host denial still rejects a granted follow-up request.

The corpus now covers 1070 cases / 4618 checkpoints, including 27 original-handler protected
paste cases. Common tests compare exact event bytes and grant use, then separately cover async
metadata, delivery receipts, cancellation, queue capacity, failures and unfinished writes.
Validation passes 1019 JVM tests, the fresh Release JS suite and 56 Native clipboard tests;
the expanded corpus and manifest reproduce byte-for-byte.
Session mode state, cross-protocol gesture ordering and native/browser paste entry points
remain to be wired before mode 5522 is advertised.

Mode 5522 is now connected to session state and trusted AppKit/browser paste gestures.
Its capability query requires an attached MIME host, while save/restore retains the mode bit;
DECSTR preserves it and RIS clears it. User gestures join the ordered OSC 52/5522 queue,
with at most sixteen waiting behind clipboard I/O. Metadata callbacks are invalidated by
mode changes, and host replacement/reset/transport failure cancel pending work and grants.
A handled failure never inserts clipboard text as a fallback. Scripted text, dropped paths
and synthetic browser events keep their ordinary text-paste path.

The samples use WRITE_AND_PASTE: writes and a granted follow-up read are allowed, without
allowing unsolicited reads or remembering new permissions. Library views still default to
DENY. A real Chromium paste verifies the event, custom binary follow-up and rejected password
reuse. An isolated AppKit pasteboard verifies that user paste does not load lazy providers,
that only the requested format is read, and that dropped paths remain ordinary text.
Validation passes 1025 JVM tests, the fresh Release JS suite, 63 Native clipboard tests,
nine native UI/application tests, the browser MIME verifier and the general browser verifier.
The original clipboard oracle remains at 1070 cases / 4618 checkpoints; this increment adds
host/session integration checks without changing generated fixtures.

XTGETTCAP now answers the complete pinned terminfo table: 272 static capabilities plus a
host-configured TN. The macOS PTY reports the actual child TERM; missing, empty or overlong
names are silent. A generated 309-case original-handler corpus checks every static capability,
hex normalization, malformed keys, numeric headers, embedded control bytes, DCS termination,
the exact 1 MiB capture boundary and 128-byte UTF-8 name boundary. It exposed NUL removal in
the generic string collector, which could turn a malformed capability into a valid query;
DCS now preserves that byte. Other string payload limits are unchanged.

Capability responses are emitted lazily in at most 32 KiB delivery-tracked chunks, one in
flight at a time. Output parsing pauses until the response completes; closing the transport
or resetting the session discards unsent chunks. Common tests hold a partial writer, check
that following screen output waits, and verify cancellation/reconnection. The real PTY test
requests 20,000 long TN responses and compares all bytes against its own TERM environment.
Chromium separately checks a blocked writer, large responses and transport disposal.
Validation passes 1032 JVM tests, fresh Release JS tests, four Native capability tests,
87 native clipboard/transport regressions, and the capability/general browser verifiers.
Live Vim, Neovim and tmux tests pass in isolated shells (Neovim uses the existing local
`build/manual-release/nvim-macos-arm64/bin/nvim` through KINETICA_NVIM). Generated table,
fixtures and manifest reproduce byte-for-byte against the unmodified 1.4.0 sources.

CTC (`CSI W`, `CSI 0/2/5 W`) now sets or clears shared tab stops, and DECST8C
(`CSI ?5W`) restores the default eight-column interval. Both valid and rejected commands
preserve pending wrap. Another 96 original-library scenarios / 483 checkpoints cover
default/custom stops, malformed headers, trailing empty parameters, DECAWM on/off,
margins, alternate screens, DECSTR/RIS and resize. Each is replayed whole and bytewise.
All 1128 JVM tests and the fresh Release JS suite pass; the main oracle corpus and manifest
reproduce byte-for-byte. The main corpus contains 776 scenarios / 4130 checkpoints.

XTWINOPS `CSI 8;rows;columns t` now emits a synchronous, disposable
`onWindowResizeRequested` event without resizing the grid. Zero/omitted dimensions remain
zero, and raw values preserve the original unsigned 16-bit range. Twenty-three original
libghostty cases / 72 checkpoints cover event payloads, invalid headers, cancellation,
pending wrap, screen switches, synchronized output, resets and an absent host. Common
tests also verify independent subscription disposal and later host-driven resizing.
All 1153 JVM tests and fresh Release JS tests pass. The main corpus is now 799 scenarios /
4202 checkpoints; all affected text/graphics fixtures and manifests reproduce byte-for-byte.
The following host increment connects those requests through `windowResizePolicy`, denied by
default. A single 16 ms task merges nonzero axes, rechecks authorization and host identity,
and cancels on detachment, reattachment or disposal. The shared conversion preserves omitted
pixel extents, applies Ghostty's 40-column / 10-row minimum and limits the resulting grid to
4096 per axis and one million cells. AppKit accounts for surrounding UI and clamps ordinary
visible windows to their size limits and the screen's visible frame. Browser applications
receive CSS pixel sizes through `resizeContainer` and own their container constraints.

The native sample exposes a persisted opt-in setting; the browser sample exposes an unchecked
checkbox and constrains its container to the page viewport. Tests exercise 20,000 requests
with one pending job, separate-axis composition, policy revocation, cancellation inside the
policy callback, stale timers and host replacement. Isolated AppKit/Metal windows and real
Canvas/WebGL containers verify final grid sizes, deferred layout, zero dimensions, oversized
requests and disposal. A test-owned PTY emits CSI 8 t after enabling the setting through the
native Settings UI; the real application layout converges to the requested grid.
Validation passes 1156 JVM tests, fresh Release JS tests, six focused Native resize tests,
six native size/visibility/effect regressions, nine UI/application tests, and both the
dedicated window-resize and general browser verifiers. No installed application or user
shell is replaced; native window and PTY checks use isolated test-owned resources.

Title readback (`CSI 21 t`) now follows libghostty's explicit host option through
`configureTitleReporting`, disabled by default and preserved across reset. OSC 0/2 titles
are captured as at most 2047 raw bytes and validated as UTF-8 before changing state; the
handler retains at most 1024 bytes. A cut inside a character is repaired for display but
preserved exactly in protocol replies. This also fixes the previous acceptance of invalid
UTF-8 titles and larger title payloads. The main oracle adds 44 cases / 152 checkpoints,
for 843 cases / 4354 checkpoints, including malformed requests, policy changes, lifecycle,
capture limits, invalid sequences, C0 filtering and multibyte cuts. Snapshot display titles
use documented UTF-8 replacement; original reply hex is never reconstructed from that text.
Validation passes 1201 JVM tests, fresh Release JS tests, 58 Native title/parser checks and
the browser transport verifier. All affected text/graphics fixtures and manifests reproduce
byte-for-byte. Title stacks (CSI 22/23 t) remain unimplemented in the original handlers too.

Kitty drag-and-drop (`OSC 72`) now has an internal host-neutral command/state/response core.
The original unmodified Ghostty modules generate 139 scenarios / 360 checkpoints, comparing
decoded metadata, registration/acceptance bytes, chunk state, offered and retained drop data,
events and exact reply bytes. Cases include unsigned/signed numeric boundaries, malformed
metadata, BEL/ST, continuation metadata, one-MiB MIME-list limits, data packet boundaries,
client IDs, drop replacement, conclusion and reset. The reset adapter only clears chunking,
matching `Terminal.fullReset`; it does not claim to test the wire parser or a platform host.

Host-supplied drop data is copied only after checking a configurable budget (16 MiB by
default), with at most 16 representations. Offered MIME data is capped at one MiB and cached
across unchanged pointer moves. Responses are lazy byte packets with at most 4096 payload
bytes; outstanding packet sequences own their snapshot even after replacement or disposal.
The host budget is an explicit embedding constraint beyond the original trusted-host API.
Tests additionally check caller mutation, budget rejection without state changes, retained
response ownership and reuse of unchanged MIME payloads. The generator and manifest record
the pinned sources and helper hashes, including the shared fixture encoder:

```sh
python3 scripts/generate-terminal-dnd.py build/upstream/ghostty-1.4.0 build/upstream/zig-aarch64-macos-0.17.0/zig
```

The first core increment did not yet dispatch OSC 72 from `TerminalSession`.
The existing shell-quoted file-path drop behavior is separate from this protocol.
Validation passes all 1204 JVM tests, the fresh Release JS suite and all three focused
macOS Native tests (including the complete 360-checkpoint oracle). Fixtures and provenance
reproduce byte-for-byte. No platform drag interaction is claimed by these core checks.

The next increment connects OSC 72 to `TerminalSession` through raw byte capture capped at
eight MiB, excluding the prefix and without a terminating NUL reservation. C0 filtering,
CAN/SUB cancellation, C1 payload bytes, fragmented commands and UTF-8 string feeds preserve
the original parser's behavior. RIS clears only continuation metadata; registration and
held drop data survive, as in Ghostty. `TerminalReplyStream` now accepts byte packets, so
plain MIME bytes are not round-tripped through UTF-8. One delivery-tracked chunk of at most
32 KiB is outstanding; parsing resumes after delivery, and cancellation discards unsent packets
while allowing an already accepted chunk to finish.

The DnD oracle now adds 28 wire cases / 76 checkpoints through the original `TerminalStream`
and actual command effects, for 167 cases / 436 checkpoints total. Whole and fragmented
feeds compare parser acceptance, state, events and exact responses, including the eight-MiB
capture boundary and one-MiB MIME cap. Session tests cover a 256-KiB binary data response,
stalled/partial writes, ordered following DnD and terminfo queries, connection disposal,
reset during delivery and duplicate completion callbacks. The browser verifier exercises
binary registration, RIS, exact replies, a stopped transport and seven-byte partial writes.
Host negotiation and browser/AppKit drag callbacks remain open; these checks do not simulate
an OS drag or claim that dropped files already use OSC 72.
Validation passes all 1208 JVM tests, fresh Release JS tests, seven Native DnD tests and
22 Native parser/title/terminfo/backpressure regressions. Both browser DnD and existing
XTGETTCAP transport verifiers pass. The expanded fixtures and manifest reproduce byte-for-byte.

The shared drag-host coordinator now separates view lifetime from terminal registration.
Each view receives a binding with default copy/move feedback intersected with the source's
allowed operations, then the client's accepted operation once available. Delayed OS reads
carry a registration/gesture ticket; leave, a new gesture, unregister/re-register, disposal
and transport cancellation invalidate stale callbacks. Re-registering the existing client
without unregistering preserves the original state's identity and ongoing drop semantics.

One session queues at most four host actions and 16 MiB of not-yet-submitted drop data, in
addition to the core's held drop. Each action has at most sixteen representations and one
MiB of MIME bytes. Only unsent tail moves from the same view coalesce; accepted drops retain
their order. Payloads are copied after admission, and MIME tokens containing spaces/control
bytes are rejected. Invalid offers also cancel an older queued move. Switching active views
sends leave before movement; disposing one view cancels its queued work and owned drop data
while preserving registration and the application-owned session. Delivery failure clears
pending host data and invalidates readers, while transport-accepted bytes finish normally.
Resume callbacks schedule work instead of reentering the current parser call.

Common regressions cover 20,000 blocked moves, source/client operation negotiation, partial
acceptance, owned bytes, a queued drop followed by a new drag, stale/duplicate readers,
multiple views, queue and data limits, malformed MIME tokens and transport failure. This is
the shared host coordination layer; browser DOM and AppKit drag callbacks remain unconnected.
Validation passes 1215 JVM tests, fresh Release JS tests and fourteen Native DnD tests,
including all original-library core/wire checkpoints. A reply already in progress retains
its own data snapshot when a view is disposed; later requests cannot access that view's
discarded drop state. No browser or AppKit drag interaction is claimed by this increment.

The browser host now connects trusted `dragenter`/`dragover`/`dragleave`/`drop` and drag-end
events to that coordinator. It offers up to sixteen ordered string/file representations,
including multiple files with the same MIME type. It captures strings while the browser's
drop data store is readable and retains immutable File objects for sequential, abortable
FileReader reads. Files are size-checked before reading, strings are checked after browser
exposure and UTF-8 encoding, and the aggregate admitted payload is at most 16 MiB. Cells use
CSS geometry; pixel coordinates use device scale. Source permissions intersect the client's
copy/move choice. Unregistered text drops use normal bracketed-paste handling; browser file
drops never invent local shell paths. Arbitrary untrusted scripted drag events are ignored.

The new browser verifier passes on Canvas and WebGL at device scale two. Pointer-driven
HTML text drags verify trusted events, MIME order, Unicode/NUL content, physical coordinates,
partial/final acceptance, rejection, new gestures, leave and conclusion. Playwright's normal
drag path converts programmatically offered File objects to text, so file verification uses
trusted Chromium CDP drag input with isolated temporary files. Actual FileReader results
match the binary source bytes; an over-budget file starts no read. Separate controlled
FileReader completion tests verify abort and stale-callback guards after unregister/re-register,
DOM removal/reinsertion and view disposal. A blocked real stream adapter coalesces 100 moves,
then delivers the queued drop and exact data after recovery. These controlled read delays
test lifecycle ordering, not slow filesystem timing. The full fresh JS suite, general browser
regression verifier and existing OSC 72 transport verifier also pass.

AppKit now connects native destination callbacks to the shared coordinator. Hover inspects
only pasteboard type metadata; drop loads native/custom MIME data from that drag's pasteboard,
with a sixteen-representation cap and 16 MiB aggregate byte limit. File URLs expose escaped
paths and CRLF URI lists without opening files; ordinary URLs and empty binary representations
are supported. Cell/physical coordinates use the native view geometry and backing scale.
Client acceptance intersects source copy/move permissions. Dynamic type registration maps
MIME names to UTIs and replaces the previous list, restoring file-only registration when the
client unregisters. Unregistered file drops retain ordinary shell-quoted bracketed paste.

Six isolated native tests pass. They invoke actual NSView destination callbacks using an
NSDraggingInfo fixture with real windows and unique pasteboards; both software and Metal views
negotiate and return exact text, HTML and custom binary bytes. Lazy-provider counters prove
hover/rejection do not read data and that cancellation tests reach the provider. Reentrant
pasteboard replacement, unregister/re-register, view removal/reinsertion and disposal cannot
publish stale drops. Additional checks cover file/ordinary URLs, empty binary data, over-budget
data rejection, 100 coalesced moves behind tracked delivery, captured data after pasteboard
replacement, and disposal cleanup. These tests do not claim a hardware-driven Finder drag.

The related native regression run passes 36 of 37 tests (host coordination, clipboard,
mouse, selection, usability and window resize). The remaining visibility test also fails
alone at its first visible-window check. The UI-adapter integration test and five native
sample tests pass; three sample tests time out waiting for a key window. The system session
query confirms `CGSSessionScreenIsLocked=1`. These visibility/focus checks must be rerun in
an unlocked desktop session; this increment does not claim a fully green desktop suite.

The later October 30 native checks and external pixel runs are recorded in the
[migration validation](ghostty-1.4-migration.md). At `2f45267`, the current engine-only
binary passes actual occlusion/hide captures, all eight Metal/AppKit resize stages,
713 continuous-resize captures and 715 isolated htop captures with zero detected anomalies.
The earlier capture failure is no longer reproduced. A first OCR timeout remains recorded;
offline recognition completed on that saved image before the unchanged verifier passed.
These results do not replace the separate hardware interaction and performance gates.

XTSHIFTESCAPE (`CSI > 0/1 s`) now controls application Shift mouse capture. The terminal keeps
the original tri-state request separately from host policy: omitted/zero disables capture,
one enables it, and RIS restores the unspecified state. DECSTR, cursor saves and screen
switches retain the request. Hosts can select a default or force capture on/off; policy
survives terminal resets. Mouse encoders emit the Shift modifier in each reporting format,
except X10 press-only mode, which ignores modifiers. Local selection remains the default.
The per-view reporter retains ownership of an already reported press so disabling capture
mid-drag cannot leave the application with a stuck button. Browser context menus and native
wheel routing also honor the effective policy.

The dedicated original-Ghostty parser/state oracle covers 17 scenarios / 86 checkpoints,
including malformed parameters, partial/cancelled input and reset/screen behavior. Common
tests replay whole and bytewise, verify host overrides and exact Shift mouse bytes in all
formats, physical-pixel coordinates, wheel input and gesture completion. All 1222 JVM tests
and the fresh JS suite pass. Native callback and real Chromium pointer checks verify captured
Shift drags alongside the existing default local-selection path.
Four AppKit mouse/PTY tests and eleven common mouse tests pass on Native. Both browser and
AppKit wheel checks preserve Shift; AppKit converts a Shift-modified vertical line-wheel event
to its horizontal axis, so the native report reflects that platform event. Browser capture
also suppresses the shifted context menu. Generated fixtures and provenance reproduce
byte-for-byte.

ENQ (`0x05`) now emits an optional host-configured answerback, disabled by default. Hosts can
configure UTF-8 text or raw bytes; only 1–255 encoded bytes are retained, and empty/oversized
values suppress replies. Configuration is copied, survives terminal resets and never exposes
host identity implicitly. Raw replies preserve embedded NUL and non-UTF-8 data. Parser-state
behavior follows the original stream: an ENQ is executed only in control-executing states,
without disrupting a pending escape/CSI sequence or pending wrap.

Thirty original-Ghostty scenarios / 79 checkpoints compare byte boundaries, binary/Unicode
payloads, host replacement, reset/screen/hold behavior and ENQ in ESC/CSI/DCS/OSC/APC/SOS/PM
contexts. Fixtures and provenance reproduce byte-for-byte. Validation passes all 1225 JVM
tests, the fresh JS suite and three native enquiry tests. A separate isolated real-PTY test
receives and compares all 255 raw bytes twice, including NUL and high bytes, with RIS between
the requests; both responses remain exact and no transport input is rejected.

The CSI dispatch audit found missing `j/k` cursor-left/up aliases and excess-parameter
commands that incorrectly changed cursor, cells or pending wrap. The aliases now use the
existing movement handlers; single-parameter movement/tab/edit/scroll commands and
two-parameter positioning reject extra parameters before applying any state changes.
Trailing empty slots retain the original parser's counting behavior.

Thirty-four original-library scenarios / 408 checkpoints failed before the fix while the
prior 843 oracle tests passed. The new cases exercise numeric/empty excess parameters,
pending wrap, default/zero/large alias counts, margins, reverse wrap and alternate screens.
All 1259 JVM tests and the fresh Release JS suite now pass, with original-library fixtures
and provenance reproducing byte-for-byte. Shared parser/state behavior is exercised through
whole-buffer and bytewise input; no platform host path changes in this increment.

The next CSI audit compares all 63 final bytes with omitted, zero and excess parameters
after pending wrap. It exposed 31 failing cases: unimplemented finals incorrectly cancelled
wrap, and several commands treated an explicit zero as one. Dispatch now returns without
changing state for unimplemented finals. HPR/VPR preserve a zero movement count while still
applying positioning's wrap reset; zero CHT/CBT, DCH, IL/DL and SU/SD leave the text and
cursor in place. This also avoids splitting wide cells at margins for zero edit/scroll counts.

The increment adds 90 original-library cases / 729 checkpoints, including 27 omitted-versus-
zero comparisons in the middle of lines, with margins and on alternate screens. The prior
877 oracle cases passed before the fix. All 1349 JVM tests and the fresh Release JS suite
pass after it; regenerated fixtures and provenance reproduce byte-for-byte. The main C
corpus now contains 967 cases / 5491 intermediate states, each replayed whole and bytewise.

The input-protocol audit found that a DECRQM query without a mode number incorrectly emitted
an unknown-mode-zero reply. Dispatch now requires exactly one parsed parameter, preserving
the distinction between an absent number, explicit zero and a committed empty slot. Six
original-library query cases reproduce the failure on primary/alternate screens and with
pending wrap. Six additional negotiation cases already match Ghostty: Kitty push/pop/set/query
and modifyOtherKeys set/reset are observed through actual Ctrl+C bytes and flag queries across
omitted, zero, excessive, out-of-range and colon parameters. The increment adds 12 scenarios /
582 checkpoints; the main corpus now contains 979 cases / 6073 states. All 1367 JVM tests and
the fresh Release JS suite pass, and fixtures/provenance reproduce byte-for-byte.

The 1.4 release-note audit also found an unimplemented APC family: Glyph Protocol (`25a1`).
The new shared handler recognizes support, query, register and clear independently of Kitty
image enablement. It validates the supported simple TrueType `glyf` subset, retains owned
contours/points and normalized registration metadata in a bounded session-wide FIFO, preserves
entries on rejected replacements, and clears them on RIS (not DECSTR or screen switches).
Like the pinned Ghostty implementation, it does not yet rasterize those outlines or apply
their width to cells, and coverage queries report only glossary entries, not system fonts.

The original parser/executor/outline decoder produces 170 scenarios / 496 checkpoints,
replayed whole and bytewise. Comparisons include exact replies, glossary size, decoded points
and contour endpoints, metrics and width, numeric syntax, last-option precedence, strict
Base64 padding, registration verbosity, namespace boundaries, malformed/composite/hinted
glyphs, 64 KiB payload and individual allocation limits, 1 MiB APC capture, replacement/FIFO
eviction and terminal lifecycle. Common checks additionally verify bounded capture cleanup
and host image-policy independence; browser transport and isolated native PTY scenarios
exercise the actual request/reply path. This closes the Glyph Protocol gap, not the broader
release/source or graphics resource audit.
Validation passes 1375 JVM tests, the fresh JS suite, three Native common tests, the real
native PTY exchange and the Chromium input/rendering verifier. Generated fixtures and
source provenance reproduce byte-for-byte.

Graphics acceptance work:

Compressed raw RGB/RGBA decoding now retains only the declared pixel prefix, rather than
materializing the whole inflated payload and then copying/truncating it. This preserves
Ghostty's acceptance of excess animation-frame bytes. The inflater still checks the entire
stream, checksum, trailing data and expansion limit; invalid dimensions preserve the original
decompression-first error precedence without retaining output. Successful raw decoding hands
off the collector's owned buffer directly. Outer-zlib PNG decoding still retains its encoded
PNG chunks for the second stage, and broader transient/storage accounting remains open.

Fourteen added original-Ghostty cases cover RGB/RGBA exact frames, excess/short data, bad
checksums, truncation, trailing data and concatenated streams. The animation corpus now has
414 cases / 2058 checkpoints and reproduces byte-for-byte. Allocation-guard tests allow only
four retained output bytes for a 1×1 RGBA frame with a 60,000-byte inflated payload, reject
corruption after that prefix, preserve the expansion limit, and verify direct buffer ownership.
Validation passes 1029 JVM tests, the fresh Release JS suite, 85 Native graphics tests and
the browser Canvas/WebGL graphics verifier, including the frame-wide GPU budget stress case.

Software image uploads now write directly into host-owned buffers. Canvas keeps one reusable
1024×1024 ImageData (4 MiB) and caps its canvas tile cache at the remaining 60 MiB, rather than
allocating a Kotlin RGBA array and a new ImageData for each upload. Row stride and dirty bounds
keep narrower edge tiles correct. Diagnostics include the upload buffer; an image-free frame
or disposal releases it. AppKit fills mutable CFData directly before publishing its image
provider, removing the simultaneous Kotlin tile/CFData copy. Published bytes remain immutable.
This accounts for application-owned RGBA payloads, not browser/Core Graphics internal copies,
compositor/driver overhead or GC/OS reclamation timing. Decoded generations, held frames,
animation conversion/composition and encoded-transfer buffers still require the wider audit.

Validation passes 1216 JVM tests, the freshly built JS suite and six native image-rendering
tests covering software/Metal pixels, alpha, orientation, cropping, seams, placeholders,
animation, synchronized frames and cleanup. The browser stress case now covers both renderers:
twenty distinct crops exceed the resident budget, yet all twenty visible stripes survive.
Canvas allocates exactly one ImageData for the whole case and reuses it for every upload;
its measured canvas-plus-upload peak is 66,434,104 bytes, below 64 MiB (67,108,864 bytes).
WebGL's independently measured peak remains below that limit, including unfinished commands.
Eight shared tile/cache tests also pass on Native, and the general browser verifier passes.

RGB animation uploads now expand directly into the destination canvas during composition,
instead of retaining a full intermediate RGBA source. Opaque RGB replaces destination pixels
under both overwrite and source-over; clipping avoids work outside the canvas. Existing
frames still copy before editing, preserving previously published render snapshots. RGB-root
conversion remains before admission, including on an eventual ENOSPC response, matching the
original executor's observable state. Pixel-allocation guards verify one destination allocation
per successful append/edit, no allocation on rejected admission with an RGBA root, and only
the required root conversion when a rejected append starts from RGB. This removes one full
source-sized transient buffer, without claiming a combined bound for decoded storage, held
frames, root conversion, destination copies and in-progress transfers.

Thirty-two new original-Ghostty scenarios compare RGB source composition over RGB roots and
transparent/partial/opaque RGBA roots, both composition modes, partial/full clipping, extreme
offsets and append/edit behavior. The animation corpus now has 446 scenarios / 2186 checkpoints;
fixtures and provenance reproduce byte-for-byte. All 1218 JVM tests, the fresh JS suite and
graphics checks on Canvas/WebGL pass, including animation, held frames and resource budgets.
All ten native animation tests and six software/Metal pixel-rendering tests also pass.

Completed raw image/frame transfers now relinquish their exact owned buffer to the decoder,
removing a second full RGB/RGBA pixel copy. A final chunk that grows the accumulator allocates
only its final length; a partially used growth buffer is trimmed once before ownership moves.
Command and external-media buffers remain borrowed and are copied into transfer-owned storage.
Standalone borrowed-input decoding still copies, and oversized raw frame tails are trimmed
rather than retained in published pixels. PNG and zlib decoding keep their existing paths.

Allocation guards prove that a complete raw upload makes one transfer-owned allocation and
publishes that exact buffer. Chunked tests cover both final growth and trimming, and verify
that mutating command payloads, replacing/removing an image and retaining an old render
generation never changes published bytes. Decoder tests cover RGB/RGBA, images/frames,
excess/invalid lengths and resource limits. All 1352 JVM tests, the fresh JS suite, 16 native
storage/decoder tests, six native software/Metal pixel tests and both browser graphics
renderers pass. Existing original-library oracles remain unchanged. This removes a transient
copy without claiming a combined peak-memory bound; RGB-root conversion still follows the
original admission order and can exceed the nominal resident limit.

The resident-storage audit now reads the original Ghostty counter directly, alongside all
stored animation frames, image/placement counts, pending-transfer state and exact replies.
Twenty-seven scenarios / 197 checkpoints cover RGB conversion at admission boundaries,
composition, repeated limits, later eviction, both screens and host changes during incomplete
APCs and chunked frame uploads. For example, a three-byte RGB root under a three-byte limit
becomes a four-byte RGBA root even when the attempted frame append fails; reapplying the
three-byte host limit then evicts it. These are observed upstream states, not inferred bounds.

The new comparisons exposed two host-policy mismatches: a same-limit fast path skipped that
eviction, and changing a positive limit discarded the active APC parser. The fast path now
requires both screens to be within the requested limit, and only disabling graphics clears
APC capture. The existing disable/reset resource-release contract remains covered by the
full suite. All three new comparison groups failed before the fix and pass afterward on
JVM, JS and Native. All 1355 JVM tests and the fresh JS suite pass; the new original-library
fixtures and provenance reproduce byte-for-byte. Combined transient/held-frame accounting
remains a separate open gate.

The retained-generation audit found an additional decoded-memory owner in tile-cache keys:
their generation identity was the `TerminalGraphicsPixels` object itself. Thus a small crop
could retain the complete source after the session and viewport stopped using it. Immutable
pixels now carry a separate identity token with no back-reference; tile keys retain only
that token. Rewrapping/selecting the same pixels preserves cache identity, while replacement
buffers cannot alias an old uploaded tile.

A JVM weak-reference regression failed before the change: a live four-byte tile cache kept
its one-MiB source reachable. Afterward the source object and array are collectable while the
cached pixels remain readable without another upload. A common cache regression checks reuse
across content wrappers and separation of replacement buffers. All 1369 JVM tests, the fresh
JS suite, nine native frame/cache tests, six native software/Metal pixel tests and Canvas/WebGL
graphics verifiers pass, including animation, render holds and tile reuse. This removes cache
keys from decoded-source ownership; viewport frozen frames and explicitly retained caller
snapshots remain owners. Synchronized-output and resize holds share one viewport slot, cleared
on release/disposal. Combined transient and host-resource accounting is not declared complete.

Outer-zlib PNG decoding now lends the inflated collector buffer to the PNG parser together
with its valid length. It no longer trims that buffer into a second full encoded-PNG copy
while the original growth buffer remains live. Bounds for signatures, chunk headers, payloads
and CRCs use the valid length. Decoded RGBA output remains independent of the borrowed input.
The complete outer zlib stream is validated before PNG parsing, including its checksum,
trailing-data and expansion-limit checks. A regression failed at the former trimming copy;
additional tests cover every truncated prefix of a complete backing PNG, dirty spare capacity,
the full PNG fixture matrix and encoded-buffer mutation after decoding. This is a reduction
in temporary application-owned bytes, not a combined RSS or GPU/decoder storage bound.
Validation passes all 1385 JVM tests, the fresh Release JS suite, ten Native decoder tests
and ten Native software/Metal rendering tests. The Canvas/WebGL verifier and Native pixel
tests now exercise ordinary and outer-zlib PNG crops through the real views; browser input
also includes ancillary padding that leaves spare capacity in the inflated collector.

- [x] RGB/RGBA and PNG decoding, zlib payloads, dimensions and decoded-byte limits.
- [x] Image IDs/numbers, chunked transmission, queries/replies, quiet settings, deletion and eviction.
- [x] Placements, cropping/scaling, z order, relative parents, scroll/reflow and screen/reset lifecycle.
- [x] Unicode placeholders, run inheritance, aspect fitting and virtual-parent rendering.
- [x] Animation frames/composition/timing.
- [x] Explicit host policy for file, temporary-file and shared-memory transmission media.
- [x] Browser Canvas/WebGL and AppKit/Metal images, cache disposal, context recovery and render holds.
- [x] Bound WebGL/Metal RGBA tile payloads across distinct crops and unfinished commands, including resources retired from the LRU.
- [x] Complete the decoded/transient-buffer and software-renderer backing-resource audit; see the [ownership report](terminal-graphics-memory.md). Tile payload accounting excludes driver overhead and glyph atlases; host geometry and caller-held snapshots remain separate owners, with no aggregate RSS promise.
- [x] Original-libghostty image/placement oracle and real platform pixel/resource regressions.

Closure evidence: `TerminalGraphicsExecutionTest` replays 330 original cases / 2823 snapshots
whole and bytewise. Geometry, anchor, animation, Unicode-placeholder and accounting oracles
cover the independent rendering inputs and lifetimes; accounting now has 30 cases / 243
checkpoints. `NativeGraphicsMediaTest` exercises actual isolated files and shared-memory
objects, including access policy and cleanup. `NativeGraphicsRenderingTest` covers software
and Metal pixels, holds, hidden-resource recovery and frame-wide pressure. The actual
`verify-terminal-graphics.mjs` Canvas/WebGL run at `2f45267` passes image pixels, crop/layers,
placeholders, holds, context recovery, pressure and disposal. These scopes satisfy the graphics
gates without claiming a process-wide RSS bound or closing unrelated production gates.

WebGL and Metal now charge both resident tiles and retired tiles referenced by unfinished
commands to the same 64 MiB budget. Cache eviction cannot reclaim a pending texture. Under
pressure, WebGL completes queued commands; Metal commits and completes the current bounded
batch, then resumes with `MTLLoadActionLoad` on the same target. No placement is omitted or
rescaled to fit the cache. Ordinary Metal frames remain asynchronous, and measured GPU duration
includes all batches. Completed native evictions mark pixels purgeable so Kotlin wrapper
collection is not required before the driver may reclaim their backing data. Context loss,
disposal and no-image frames also retire their resources through the completion boundary.

The completion/discard semantics follow [OpenGL finish](https://registry.khronos.org/OpenGL/specs/es/1.1/es_full_spec_1.1.pdf)
and [Metal resource purgeability](https://developer.apple.com/documentation/metal/mtlresource/setpurgeablestate(_:)).
This bounds application-accounted RGBA tile payloads, not process RSS or driver allocation
overhead; decoded generations, software surfaces and glyph atlases require separate accounting.

Validation passes 1027 JVM tests and the fresh Release JS suite. Shared tests retain simulated
GPU references through eviction/disposal and reject allocations before a failed completion
barrier. Six native graphics tests include pixel equality between an ordinary Metal renderer
and a two-tile budget across all layers and repeated frames; 19 additional native tests cover
cache state, Metal pixels, frame lifecycle and render observers. The browser graphics verifier
renders twenty distinct crops from one image in a single frame exceeding 64 MiB, tracks deleted
but unfinished resources, and samples pixels only after the final draw. All twenty visible
stripes survive and the accounted peak stays within budget. Both the Canvas/WebGL graphics
verifier and general browser verifier pass. No upstream fixtures changed in this host-resource fix.

The OSC 133 increment adds 26 semantic/alignment oracle cases plus the original Alacritty
DECALN recording, covering 173 new checkpoints. It compares row and cell markers, cursor
classification, input termination, screen transitions, margins, erasure and resize redraw.
Separate common tests cover command metadata/decoding, observer disposal, prompt navigation
and host Clear Screen. Native sample commands and the browser host exercise navigation;
click options were initially exposed as metadata. The subsequent Ghostty 1.4 prompt-click
increment implements `click_events=1/2` in both hosts.
Its separate original-library corpus covers 32 cases / 586 checkpoints, including prompt rows
in history, soft wraps, continuation boundaries, output, erasure, reset and alternate screens.
The terminal parser and prompt iterator run unmodified; the small coordinate helper is extracted
verbatim from pinned Surface.zig. The adapter models an already completed local click, while
common gesture tests, real Chromium pointer events and isolated AppKit mouse callbacks validate
host arbitration. The increment passes 1372 JVM tests, fresh JS tests, three Native common prompt
tests, three AppKit selection tests, four Native mouse/PTY tests and the browser verifier.
The following `cl=line/m/v/w` increment uses original Screen.promptClickMove to extend that
corpus to 116 cases / 6506 checkpoints. All four modes retain Ghostty 1.4's line-based,
input-cell navigation, including wide-cell counting, non-input gaps and soft/hard boundaries.
Both hosts also exercise the resulting CSI/SS3 cursor keys and the shared host policy.
Validation passes 1372 JVM tests, fresh JS tests, three Native common prompt tests, four
AppKit selection tests, four Native mouse/PTY tests and the Chromium verifier. Generated
fixtures and provenance reproduce byte-for-byte.
Validation passes 479 JVM tests, the freshly built JS suite, all 279 Native oracle scenarios,
six Native common semantic tests and seven native application tests. The browser verifier
passes prompt navigation alongside its existing rendering/input/lifecycle checks. All 282
oracle artifacts reproduce byte-for-byte; 27 new scenario/helper paths pass AddressSanitizer
with the uninstrumented upstream library and disabled LeakSanitizer noted below.
Kitty graphics remains unimplemented.

The OSC 7 increment passes 446 JVM tests and the freshly built JS suite. Native checks cover
all 252 oracle scenarios, two common working-directory tests and seven application tests,
including a real new shell inheriting a reported directory different from its parent's cwd.
All 255 oracle artifacts reproduce byte-for-byte. The four new cases cover 28 checkpoints;
their C-helper paths pass AddressSanitizer
with the same instrumentation limits described below. URI validation rejects remote hosts,
malformed escapes and NULs before the sample checks filesystem existence. OSC 133 and
Kitty graphics remain unimplemented.

The notification/progress increment passes 440 JVM tests, the freshly built JS suite and
browser integration. All 248 original-libghostty oracle scenarios pass on Native, alongside
eight focused common/native effect tests and six native application tests. AppKit checks
progress colors/width in cached pixels, resize, pointer transparency and disposal. Browser
checks cover permission actions, intercepted notification delivery, all progress states,
the real 15-second expiry and detached observers. Native delivery tests inject the notification
center, retain real UN content objects and check denial, flood coalescing and late completions;
OS banner presentation itself is not asserted. Sample menu routing is tested without opening
an OS prompt. All 251 oracle artifacts reproduce exactly; five effect-helper scenarios pass
AddressSanitizer with the same instrumentation limits as the clipboard checkpoint below.

The horizontal-margin and OSC 52 increment passes 431 JVM tests and the freshly built JS
suite. Native validation passes 266 oracle/transport/backpressure/AppKit regressions plus
nine clipboard unit/platform tests; the final clipboard oracle subset also passes after
adding malformed multibyte selectors. The browser verifier exercises real clipboard write/read,
host denial and rejected browser promises. A dedicated native PTY verifies a 2 MiB clipboard
read byte-for-byte; AppKit clipboard tests use a private pasteboard. Both sample applications
compile with explicit write-only clipboard policies. All 246 oracle artifacts reproduce
byte-for-byte, and the clipboard C-helper cases pass AddressSanitizer (the upstream library
is not instrumented; LeakSanitizer is disabled on this macOS runtime).
This closes these two features, not notifications, command metadata, graphics or the broader gates.

The Kitty increment adds 32 original-libghostty scenarios with 16,554 key events and
screen-local stack transitions. The Neovim replay no longer filters Kitty replies.
The encoder matrix covers all nonzero flags; native/DOM tests cover event routing and
IME commitment. This does not close the remaining features or the broader acceptance gates.

The imported Alacritty corpus contains 45 recordings, stored verbatim with source
hashes. Generated common tests compare full and deterministically chunked streams.
The original minimal engine passed 22/45 before fixes; that baseline also exposed
the upstream harness's history-padding behavior, now handled by the adapter.
Current pass counts must come from fresh test output, not this document.

Ghostty-derived parser tests and fixes are being added with the MIT license and
the source revision recorded. Existing WebGL2/Metal renderers and real macOS PTY
are implemented, but their earlier tests do not prove the unchecked gates above.

The pinned original `libghostty-vt` now generates 1760 intermediate snapshots from
279 scenarios. The common suite checks cells/styles/protection, wraps, cursor/pending wrap,
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
snapshots and transcripts are retained without modification. Its replay declares three
explicit capability differences: unsupported modes 69/2031/2048 answer DECRQM with status 0
instead of Ghostty's status 2. Kitty keyboard replies are compared unchanged.
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

Line selection now follows Ghostty 1.4's semantic prompt/input/output boundaries and trims
edge empty cells, ASCII spaces and tabs. It no longer appends an artificial final newline.
Line drags retain written interior spaces, ignore blank destinations and can start on a blank
region. The original-source oracle compares ranges and copied text at 1492 positions and
2340 gesture steps, including reverse movement, wide cells, soft wraps, history and resize.
The separate URL candidate-range checks remain untrimmed. This is line-selection coverage;
it does not establish equivalence of every configurable word or rectangular selection mode.

Word selection now uses the original default delimiter set and groups adjacent delimiters.
Blank-origin/destination drags search toward the opposite endpoint; wide spacers follow their
owner glyph. `session.selectionWordChars` supplies a reset-preserved Unicode delimiter policy
to both live and frozen views. The expanded original-source selection oracle covers 2787
positions and 2970 word-gesture steps with default and custom delimiter sets. Host regressions
exercise actual double clicks and policy changes, including the URL override's transition
back to ordinary word dragging.

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
also passes. The shared viewport now supports rectangular ranges with original-reference
copy checks, per-row mutation watches, wide-glyph highlighting and frozen/autoscroll tests.
Rectangular platform gestures now use the original pointer thresholds and platform modifier
policy; the migration document records the associated oracle and host checks. Broader keyboard
acceptance and assistive-technology navigation remain outside this completed selection-anchor gate.

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
