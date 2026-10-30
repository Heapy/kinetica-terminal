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
      The behavior-to-regression map below records the reviewed families. Runtime acceptance
      and performance requirements in the production checklist remain separate gates.
      A subsequent per-attribute check found missing SGR 53/55 despite advertised
      Smol/Rmol capabilities. The family-level review missed this concrete gap; the
      remaining attribute-level audit stays open rather than treating the table as proof
      of each advertised attribute.

The final capability review checks `TerminalSession`'s DA/DA2/DA3/XTVERSION dispatch and
`TerminalTerminfoTest.queriesMatchEveryOriginalCapabilityAndParserBoundary`, including
whole and bytewise original replies. Identity remains conservative and Kinetica-branded;
graphics negotiation depends on a graphics host. The upstream `stream.zig` explicitly
ignores OSC 66/99/3008 callbacks and `stream_terminal.zig` ignores DCS tmux control mode;
CSI 22/23 t title stacks are also no-ops. The module's compatibility scope now lists these
intentional ignores, separately from implemented OSC 9/777 and ordinary tmux TUI operation.
That review covered identity and intentional ignores; the subsequent per-attribute review below
showed why it was insufficient to close the entire capability gate.

The overline follow-up implements SGR 53/55 as an independent style bit, preserves it through
saved cursors, both screens, reflow and compressed history, and includes it in DECRQSS in
the original attribute order (after underline, before blink). Every painter draws the top
decoration in the effective foreground, independently of the underline color; concealment,
inverse/faint, palette updates and synchronized-frame holds apply. Shared glyph/font masks
remain unchanged because overline does not change glyph rasterization. Original parser
comparison also exposed unsupported SGR subparameters: only groups 4/38/48/58 accept colons;
other colon groups are consumed without changing the pen.

Five new original scenarios failed before the implementation. A further matrix checks 26
SGR codes with one, multiple and trailing subparameters. Together these add six scenarios /
110 checkpoints to the whole/bytewise oracle (1015 scenarios / 6433 checkpoints total).
Common tests compare covered decoration pixels independently of batching, both painter
paths, frozen-frame publication and compressed style retention. The new native and browser
pixel checks exercise the real software/GPU renderers, reset, concealment, inverse/faint and
palette repaint; the browser checks DPR 1, 1.25 and 2. This corrects the earlier capability
audit's insufficient evidence for Smol/Rmol; it does not mark unrelated production gates done.
Validation passes all 1486 JVM tests, freshly built Release JS tests, 1029 focused Native
oracle/decoration/software/Metal tests and all six Canvas/WebGL DPR checks. All 1018 original
oracle fixture/manifest artifacts regenerate byte-for-byte. Logs are `/tmp/kinetica-overline-*`.

The next SGR source comparison found different handling of supported subparameter groups.
Unknown underline styles now fall back to single underline, while extra or trailing colons
reject that underline group. RGB colon forms require exactly three components with an optional
colorspace slot; indexed forms and semicolon RGB consume the same parameters as the original,
including mixed separators. Components and palette indices use their low byte, matching the
original `sgr.zig` truncation instead of rejecting values above 255. Incomplete groups preserve
the original parser's consumption so subsequent attributes are interpreted consistently.
Seven new original scenarios cover 664 checkpoints: underline styles 0–6, 9, 255, 256 and
65535 from both underlined and plain pens; RGB/indexed bounds on all three channels; every
semicolon/colon combination of five/six-value RGB and indexed groups; trailing, missing and
extra parameters, with following attributes and DECRQSS replies. All seven failed before the
fix while the preceding 1015 scenarios passed. The corpus now has 1022 scenarios / 7097
checkpoints, checked with whole and bytewise input.
Validation passes all 1493 JVM tests, freshly built Release JS tests and all 1022 Native
oracle scenarios. All 1025 oracle fixture/manifest artifacts regenerate byte-for-byte.
Logs are `/tmp/kinetica-sgr-boundaries-*`. These checks establish parser/state/reply parity
for this matrix; the remaining rendering and production acceptance gates stay open.

The release behavior review uses the pinned `stream.zig` / `stream_terminal.zig` dispatch,
keyboard/mouse encoders and graphics implementation as the reference. It checks the terminal
capabilities and relevant input/state changes against executable regression families rather
than treating matching handler names as sufficient evidence. The generated names below are
registered in [GhosttyOracleTest](../kinetica-terminal/test/generated/GhosttyOracleTest.kt);
separate corpora carry their own generator, source hashes and pinned commit under
`kinetica-terminal/test-upstream/ghostty/`.

| Reviewed behavior | Executable evidence in `kinetica-terminal/test/` |
| --- | --- |
| Image commands, IDs, chunks, errors, deletion, replacement and resets | `TerminalGraphicsExecutionTest.completeCommandStreamsMatchOriginalLibghostty`; storage and image fixtures |
| Animation, relative placements, transient eviction, crops, scrolling and reflow | `TerminalGraphicsAnimationTest.animationCoreMatchesOriginalGhosttyExecutionAndClock`; geometry, anchor, Unicode-placeholder and accounting tests |
| MIME clipboard and paste events | `TerminalKittyClipboardCoreTest`, `TerminalKittyClipboardResponseTest`, `TerminalKittyClipboardPasteTest`, `TerminalClipboardPasteSessionTest` and host binding tests |
| iTerm2 clipboard/directory aliases | `TerminalClipboardTest`, `TerminalWorkingDirectoryTest`; generated `iterm2_copy*` / `iterm2_cwd*` cases |
| Visibility, host resize and prompt clicks | `TerminalVisibilityTest`, `TerminalWindowResizeTest`, `TerminalPromptClickTest`, `TerminalSemanticPromptTest`; original visibility, resize and prompt corpora |
| Soft reset, backarrow, checksums, extended cursor/screen reports, ANSI mode queries | `TerminalSoftResetTest`, `TerminalChecksumTest`, `TerminalProtocolReportsTest`; generated `soft_reset_*`, `backarrow_*`, `extended_cursor_*`, `size_queries` and mode-query cases |
| Terminfo, decoration reports and configured cursor reset | `TerminalTerminfoTest`; generated SGR/report cases; `TerminalSession` resets DECSCUSR 0 to the constructor's cursor style/blink defaults |
| Capture limits, cancellation and bounded combining clusters | `TerminalGraphicsApcTest`, `TerminalGraphicsExecutionTest`, `TerminalBackpressureTest`, `TerminalAdversarialTest`, `TerminalUnicodeTest`; original malformed/cancelled and Unicode cases |
| Glyph registration and program status | `TerminalGlyphProtocolTest`, `TerminalGlyphConstraintTest`, `TerminalProgramStatusTest`; dedicated original corpora |
| Prompt-aware clearing/history | Generated `erase_display_after_history`, `scroll_clear_*`, `semantic_blank_prompt_reflow`; `TerminalSemanticPromptTest` |
| Legacy/modifyOtherKeys/Kitty encoding, consumed modifiers and Option policy | `TerminalModifyOtherKeysTest`, `TerminalKeyboardTest`; generated keyboard, `modify_other_keys_*`, `option_policy_*` and `backarrow_*` cases; native event matrices |
| Mouse formats and Shift capture | `TerminalMouseTest`, `TerminalMouseShiftTest`; generated `mouse_formats_1005_*`, `1015_*`, `1016_*` and original Shift-capture corpus |
| Palette/dynamic-color reset, theme notifications and pointer reset | `TerminalColorsTest`, `TerminalColorSchemeTest`, `TerminalKittyColorTest`; generated `soft_reset_palette_dynamic`, `color_scheme_notifications`, `pointer_shape*` cases |
| UTF-8 effects, DCS and C0/C1 controls | `TerminalEffectsTest`, `TerminalProtocolDeliveryTest`, `VtParserTest`, `TerminalBackpressureTest`; generated malformed/cancelled, title/C1 and final-dispatch cases |
| Erasure/wrap state, reflow and saved cursors | `TerminalSessionTest`, `TerminalViewportTest`, `TerminalSoftResetTest`; generated `resize_*`, `erase_*`, `reverse_wrap_*` cases |
| Variation selectors, combining marks, charset invocation/single shifts | `TerminalUnicodeTest`; generated `unicode_*`, `charset_*`, `repeat_wide_combining` cases |
| Tab clearing, autowrap, reverse wrap and margins | `TerminalSessionTest`, `TerminalViewportTest`; generated `tab_*`, `reverse_wrap_*`, `semantic_margins`, `margins_and_reports` cases |

No reviewed family remains wholly absent from the implementation or regression coverage.
This is a finite source/behavior audit, not a claim that arbitrary command sequences, host
event combinations or fonts are exhaustively tested. The platform checks and outstanding
production gates keep their own acceptance criteria. The detailed increments below retain
the original-oracle counts, platform checks and intentional embedding differences.

The final full engine Native run passes all 1608 tests, including the shared original
corpora, actual files/shared memory, software/Metal pixels, native input and isolated live
PTY/window tests. The first full run passed 1607 and timed out on the 20,000-reply XTGETTCAP
exchange. That unchanged exchange passed alone in 19.1 seconds; its 20-second watchdog was
too close to an observed successful run. A 60-second watchdog and queue-state diagnostics
retain the same approximately 4.7 MiB exact comparison, zero rejection and child-exit checks.
The entire suite then passes (`/tmp/kinetica-final-native-all-recheck.log`); the original
failure remains in `/tmp/kinetica-final-native-all.log`. This is a test-watchdog correction,
not a transport performance improvement. The current Release browser integration and real
zsh/Vim/Neovim/less/tmux PTY workflows also pass, including Neovim undercurl pixels and
post-resize navigation. Logs: `/tmp/kinetica-final-browser-main.log` and
`/tmp/kinetica-final-browser-tui.log`. The unchanged shared implementation already passes
1478 JVM tests and the fresh Release JS suite at the graphics-memory checkpoint.

- [x] Separate resolved macOS Option-as-Alt policy from layout-consumed modifiers in
      shared key encoding. `TerminalKey.optionAsAlt` explicitly controls the legacy Alt
      prefix, modifyOtherKeys modifier mask and Kitty associated-text eligibility; consumed
      modifiers still control plain-text event ownership. An omitted policy preserves the
      existing embedding convention. The original C encoder now accepts all four policies
      plus the right-Alt modifier bit; replay passes the resolved policy independently.
      Twenty-four new scenarios / 164 checkpoints cross both sides, consumed Alt, shifted
      and missing text, actions, mode 1036 and legacy/modifyOtherKeys/Kitty. Fifteen scenarios
      failed before the fix. These fixtures prove shared encoding; separate native adapter
      evidence follows below.
      Validation: 1476 JVM tests, fresh Release JS tests, 1021 Native oracle/keyboard tests
      and the actual Chromium keyboard/IME verifier. All 1012 oracle fixture/manifest files
      regenerate byte-for-byte (1009 scenarios / 6323 total checkpoints).

- [x] Expose native Option-as-Alt selection through `AppKitTerminalOptionAsAlt`
      (`NONE`, `BOTH`, `LEFT`, `RIGHT`) on the standalone view and Kinetica host factory.
      The existing `optionAsMeta` boolean remains the default-policy shorthand. Resolve
      AppKit's device-side bits as in original `Ghostty.Input.swift`: right wins when both
      are down, aggregate-only synthetic flags default to left. Translate the selected side
      without Option while retaining Shift/CapsLock; leave the other side with AppKit's
      text/IME path. Pass the resolved policy independently to shared encoding, including
      release and modifier events. Functional-key modifiers are preserved on either side.
      A native matrix checks 192 press/release pairs across policies, side combinations,
      legacy/modifyOtherKeys/Kitty, Shift and repeat, plus functional keys and IME commits.
      Expectations use the current AppKit layout without changing it; the passing run uses
      Cyrillic layout translation and covers Ghostty's non-ASCII legacy unshifted fallback.
      All five keyboard tests pass, all ten live-window/IME/PTY tests pass on the same
      production implementation, and the actual Kinetica host test verifies left/right
      forwarding. This macOS-only step does not change the shared or browser implementation.

- [x] Route the original iTerm2 OSC 1337 `Copy=:base64` and `CurrentDir=value` keys to
      the existing clipboard policy/queue and working-directory observers. Match ASCII
      case-insensitive keys and the original fixed capture: at most 2047 UTF-8 bytes after
      `1337;`, including the key/value separator. Missing/empty values, clipboard read/clear
      attempts, unsupported keys and oversized commands stay ignored. Five original C-library
      scenarios add 85 checkpoints, replayed whole and bytewise, covering malformed keys,
      binary clipboard values, BEL/ST, cancellation, boundaries, screens, holds and resets.
      Separate host tests cover denial, deferred permission ordering, cancellation and a
      rejected oversized write; actual Chromium clipboard writes/readback and denied aliases
      pass through the transport. Validation: 1448 JVM tests, fresh Release JS tests and
      995 focused Native oracle/clipboard/directory tests. The generated oracle sources
      and manifest regenerate byte-for-byte. The broader source audit remains open.

- [x] Add rectangular ranges to the shared selection engine and viewport API. Original
      Ghostty supplies 6804 rectangle/text expectations, checked in four corner orientations
      with whole-stream and bytewise input. Regression tests cover per-row wide-glyph
      highlighting, mutation watches, dynamic shape changes, frozen frames, history anchors
      and autoscroll without changing columns.
      Validation: 1404 JVM tests, fresh Release JS tests and existing Canvas/WebGL gesture
      regressions. The rectangle corpus regenerates byte-for-byte. Browser gesture checks
      here cover existing modes; rectangular platform events remain the next gate.
- [x] Connect rectangular pointer gestures with original pixel thresholds and platform
      modifier policy (Option on macOS; Ctrl+Alt elsewhere). Current modifiers also reach
      autoscroll ticks. Same-cell movement can select text and consume a prompt click;
      release alone does not synthesize a drag. Modifier-only Kitty key reports preserve
      the active browser selection. Programmatic cell-boundary APIs retain their behavior.
      The original oracle adds 4000 cell/rectangle drag expectations across threshold edges,
      directions, padding and five cell widths. It canonicalizes original selection corners
      through `topLeft`/`bottomRight`, preserving the inclusive-to-exclusive column conversion.
      All fixtures regenerate byte-for-byte. Validation passes 1406 JVM tests, fresh Release
      JS tests, 77 focused Native tests and actual Chromium gestures/pixels for both macOS
      and Linux modifier policies with Canvas/WebGL. The known live GPU visibility gate
      remains separate from these selection checks.

- [x] Select complete command output with Cmd+triple-click on macOS and Ctrl+triple-click
      on other browser platforms, following original `Surface`, `Screen.selectOutput` and
      `SelectionGesture` behavior. Dragging extends by command blocks and returns to the
      original block on prompt/input targets. OSC 133 markers, written spaces, wide cells,
      continuation prompts, missing prompts and synchronized views are covered.
      The expanded original oracle has 63 scenarios / 84 checkpoints, 5451 cell positions
      and 3780 gesture steps per mode, replayed whole and bytewise. Fixture regeneration
      is byte-identical. Validation: 1401 JVM tests, Release JS tests, 71 focused Native
      tests and real Chromium selection gestures with Canvas/WebGL. This validation excludes
      the separately documented live GPU visibility gate below.

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

- [x] Open browser links with Cmd+click on macOS and Ctrl+click elsewhere. Both browser and
      AppKit now activate on release in the original cell, following original `Surface`
      release handling and `SelectionGesture`; a drag remains cancelled after returning
      to the start, including same-cell threshold drags. Exact binding modifiers and TUI
      mouse capture/Shift override apply. Native target conversion and deferred approvals
      retain their existing policy.
      The browser default opens absolute web/mail targets with `noopener,noreferrer`,
      confirms external schemes and rejects relative/file/executable/browser-local targets
      and unsafe characters. Hosts can supply their own `openLink` policy. Confirmation
      rechecks disposal and the originating OSC 8 policy; detected text links stay independent.
      Validation: 1414 JVM tests, fresh Release JS tests, 79 focused Native tests, and real
      Chromium link plus selection gestures on Canvas/WebGL with macOS/Linux modifiers.
      `scripts/verify-terminal-links.mjs` also checks frozen and wrapped targets, cancellation,
      denied schemes, declined/revoked approvals, disposal and host callback behavior.
      Hover feedback and multi-cell shaping remain separate open requirements below.

- [x] Shape multi-cell font runs in GPU renderers, including programming ligatures.
      Browser configurations without loaded font bytes retain a per-cell path;
      original Ghostty `renderer/generic.zig` uses a font-shaper run iterator and cluster mapping.
      The [font shaping reference](terminal-font-shaping.md) records original run boundaries,
      font identities, glyph substitutions and terminal-grid offsets using embedded fonts.
      Shared cluster-to-grid positioning and an independent Native CoreText shaper now
      match original glyph/offset expectations. The Native checks use a separate original
      CoreText corpus because it differs from HarfBuzz for some marking-glyph offsets.
      The common GPU frame now accepts optional run shaping, retains face/glyph identities
      and clips device-pixel glyph bearings/offsets to run and row bounds. Primary-font
      boundaries and cluster inputs match the original corpus. Metal now resolves whole
      graphemes, excludes LastResort, substitutes missing clusters and draws individual
      CoreText glyphs through the atlas. Native pixel checks cover ligatures, selection,
      cursor/style/background boundaries, color emoji, long runs and resource resets;
      an isolated window presents its initial and restored frames. WebGL now accepts
      an explicitly loaded font set, maps HarfBuzz output to the grid and rasterizes
      outlines/color PNGs. Browser pixels and font lifecycle pass at DPR 1, 1.25 and 2.
      The demo now enables a pinned local font set with real style, emoji and CJK faces.
      Default-font checks cover pointer selection, 513-character runs, disposal and
      failed/corrupt asset fallback. Both shaped GPU paths now apply original symbol,
      emoji and Nerd Font fitting rules; 2,250 geometry cases and 759 terminal rows
      match the independent original constraint oracle. WebGL also renders COLRv0/CPAL
      layers with alpha and current-foreground colors; independent pixel checks cover
      palette gray, empty base outlines and cache invalidation. Original desktop probes
      record the pinned backends' limitations on the same fixture. The remaining
      COLRv1 and browser SVG now use the native glyph-index face as well, with
      foreground-aware atlas entries for vector paints. Chromium and Firefox
      COLRv1 pixels and Firefox SVG pixels match their native Canvas references
      at DPR 1/1.25/2. Browser codec/currentColor limitations are recorded in the
      shaping reference; the integration does not claim to override them.
      Native JPEG/TIFF sbix now has
      deterministic fixtures, original backend probes and Metal regressions.
      Shaped WebGL also paints JPEG/TIFF through a private glyph-index font,
      preserving HarfBuzz substitutions without a second shaping pass. Real
      Chromium pixels match native Canvas at DPR 1/1.25/2; codec fallback, intrinsic
      white, rejected/pending loads and font disposal have regression coverage.
      Native SVG classification
      now checks individual glyph ranges: ordinary outlines retain their size,
      gray artwork keeps its intrinsic color and compressed gradients reach Metal.
      Original CoreText/FreeType probes record the distinct backend behavior.
      PNG sbix now has independent
      multi-strike, transparency, offset and dupe checks; Metal preserves intrinsic
      gray bitmap colors instead of tinting them as text. See the shaping reference.
- [x] Implement shared hover identity, range tracking and decorations. Reopened implicit
      OSC 8 links remain independent; explicit nonempty IDs match by ID plus URI across
      disjoint visible rows. Copies, reflow and frozen snapshots retain identity. The original
      parser treats empty IDs as implicit, keeps the last nonempty repeated ID, stops at
      malformed options, and rejects close commands with nonempty IDs. The independent
      `RenderState.linkCells` corpus covers 32 cases / 44 checkpoints / 2152 hover points,
      replayed whole and bytewise, and reproduces byte-for-byte. Hover adds a single underline
      or changes an existing single underline to double, retaining colors and invisible-text
      behavior without mutating stored styles. Policy revocation updates frozen views at once.
      The archived Alacritty hyperlinks recording now has a complete original-Ghostty
      expectation for its differing invalid-close behavior, preserving its explicit-ID checks.
      Validation passes 1420 JVM tests, fresh Release JS tests, 86 focused Native tests,
      and browser link gestures on Canvas/WebGL with macOS/Linux modifiers. The browser
      verifier here covers existing activation; hover event wiring, pointer feedback and
      previews remain open below.
- [x] Connect link hover feedback in browser and AppKit: pointer cursor, target preview and
      shared range highlighting. Exact modifiers update even without mouse movement; TUI
      capture, Shift escape and application/host Shift-capture changes immediately refresh
      the result. Dragging to another cell suppresses hover; returning restores hover while
      leaving click activation cancelled. Exit, lost focus, visibility and disposal clear it.
      OSC 22 remains the underlying cursor and is restored when hover ends. Visible/frozen
      contents and immediate OSC 8 revocation supply the preview target.
      `linkPreviewMode` supports ALL (default), OSC8 and NONE independently of highlighting
      and activation. A single-line status overlay is pointer-transparent, uses current theme
      colors, and escapes invisible/control characters without changing the original target.
      Validation: 1420 JVM tests, fresh Release JS tests, 93 focused Native tests and real
      Chromium hover pixels/previews, activation and selection with Canvas/WebGL on both
      macOS/Linux modifier policies. AppKit tests exercise event callbacks and overlay state;
      the live GPU visibility gate was unresolved at this checkpoint (see the later recheck below).
- [x] Add the independent `link-url` host policy as `session.detectedLinksEnabled` (default true).
      Disabled detection removes plain URL/path lookup, hover and activation and restores
      ordinary word selection on double-click. Explicit OSC 8 links retain their own setting;
      existing/frozen content uses current policy without deleting metadata. Settings survive
      resets. Browser/native default opener confirmations recheck the setting for their origin,
      so detection revocation cannot accidentally revoke an explicit OSC 8 approval.
      Validation: 1421 JVM tests, fresh Release JS tests, 94 focused Native tests and browser
      hover/activation/word-copy checks on Canvas/WebGL with macOS/Linux modifier policies.

- [x] Native Force Click selects the original press's word, consumes further dragging and
      stops autoscroll. It bypasses the double-click URL override, honors current word
      delimiters, and preserves the prior selection when the pressed cell has no word.
      Consumed gestures cannot emit prompt-click reports; application mouse capture retains
      its press/release stream. The next local click sequence starts afresh even if AppKit
      continues its native click count. Original `SelectionGesture.deepPress` is exercised at
      2787 positions with state-consumption assertions; fixtures reproduce byte-for-byte.
      Validation passes 1398 JVM tests, fresh Release JS tests, 60 focused Native tests and
      Canvas/WebGL selection checks. Native synthetic pressure events reach the real callback
      and exercise real autoscroll timers; physical trackpad acceptance is not claimed.
- [x] Native Quick Look reads the current pointer word without changing selection and forwards
      attributed text with the current font to AppKit's definition presenter. Force Click
      invokes it once per gesture when the system preference is enabled, independently of
      application mouse reporting. Word lookup uses visible/frozen cells and current delimiters;
      partially visible words retain their whole text with a clipped viewport anchor.
      Definition rectangles preserve Ghostty's baseline offsets in the view's flipped coordinates;
      IME requests matching the marked range retain their cursor anchor.
      Validation passes 1400 JVM tests, fresh Release JS tests, 69 focused Native lookup/selection/input tests
      and Canvas/WebGL selection checks. The original word/formatting corpus and expanded source
      provenance regenerate byte-for-byte. Tests intercept system presentation; physical trackpad
      and visible system-definition popup acceptance remain unclaimed.

The expanded native run also exercised the pre-existing live GPU/IME window gate. Its first-frame
timeout reproduced on an isolated build of the exact pre-lookup HEAD `78ee5a0`. The test uses
the existing `runWindowSteps` AppKit loop and explicitly waits for WindowServer visibility before
asserting frame submission. That environment reported `visible=true, occlusion=8192` without
`NSWindowOcclusionStateVisible`; an independent Swift window, including floating/all-Spaces mode,
reported the same condition. No offscreen or visibility-bypassing assertion replaced this gate.

The October 30 recheck at `ffef0ea` passes all ten `MacOsTerminalTest` tests, including actual
WindowServer visibility, first presentation, hide/show recovery, IME and real PTYs. All five
`MacOsRenderObserverTest` tests also pass with the current engine-only test binary: atomic TUI
resize, continuous resize, retained glyph geometry, cover/hide recovery without further output,
and observer disposal. Logs: `/tmp/kinetica-live-window-recheck.log` and
`/tmp/kinetica-live-render-observer.log`. The earlier visibility blocker is no longer reproduced.
External pixel acceptance remains open: the current-binary occlusion verifier reaches its initial
visible frame, but `screencapture` fails with `could not create image from window` for all attempts.
`build/reports/terminal-window/run-eslh_w39/report.json` retains the failure and source/binary
hashes. This does not prove pixels, physical presentation latency or the open load-performance
gates. Earlier attempts selected the old root-project binary and are not current-build evidence.

The subsequent October 30 recheck at `2f45267` closes that external capture blocker. It uses
the freshly linked engine-only binary (SHA-256
`9a47732dc816e9675726228dad93bce21e9a511206eedd0556b6dbad9204f09f`). The first capture
now succeeds, but offline Vision OCR exceeds its ten-second deadline (`run-q51qgz7r`).
Running that same OCR helper against the saved PNG completes with the expected `BEFORE`
text; the unchanged verifier then passes all three occlusion/hide stages (`run-sfxkha7l`).
Eight resize stages verify actual text, ink geometry and background for Metal and AppKit
(`run-jxzjvbdp`). Continuous resize passes 713 captured frames (`run-zdg7c473`); an isolated
real `htop` PTY passes 715 (`run-tks3_q1r`), both with zero detected anomalous frames.
Their render traces include 39 and 29 distinct grids respectively, ruling out one unchanged
held frame throughout the resize.
All report paths are under `build/reports/terminal-window/`; reports retain source/binary
hashes, and the stream reports retain individual frame measurements. The saved htop image
was visually inspected and contains its colored UI. These are WindowServer pixel checks
and a TUI-content heuristic, not physical scanout, hardware gestures or load-performance
acceptance. No installed application or existing shell was replaced or terminated.

- [x] Double-click a recognized visible HTTP(S) link as one complete selection, following
      `Surface`'s override of ordinary word selection. Soft wraps, wide/combined cells and
      OSC 133 boundaries retain their cell mapping. OSC 8 target URIs and activation policy
      do not replace the visible selected text. A release with no new position no longer
      synthesizes a drag; actual dragging resumes the existing word-selection behavior.
      Validation passes 1391 JVM tests, fresh Release JS tests, 38 focused Native tests and
      the Canvas/WebGL selection verifier, including actual double-click, copy and drag events.
- [x] Match ordinary word selection with original `Screen.selectWord`,
      `selectWordBetween` and configurable `selection-word-chars`. Adjacent delimiters form
      one group; unwritten cells have no word; blank-origin/destination drags search toward
      the opposite endpoint. Wide-cell spacers inherit their owner's code point. The public
      `session.selectionWordChars` policy parses Unicode code points, always includes NUL,
      survives resets and applies to live and frozen views. Invalid surrogate sequences leave
      the previous policy intact. The expanded original-source oracle covers 45 cases /
      66 snapshots / 2787 cell positions and 2970 gesture steps each for lines and words,
      with whole and bytewise input, custom delimiters and supplementary code points.
      Generation reproduces byte-for-byte. Validation passes 1392 JVM tests, fresh Release JS
      tests, 40 focused Native tests and Canvas/WebGL gestures/copy checks with host policy changes.
- [x] Match the default URL/path grammar in `src/config/url.zig`, including non-HTTP schemes,
      rooted/relative paths, environment-variable prefixes, spaced path segments and original
      case/punctuation/Unicode rules. A portable scanner replaces platform regex behavior;
      original Oniguruma 6.9.9 supplies 6018 inputs / 5069 matches and separate word, bracketed
      word and digit classes over all 1,112,064 Unicode scalar values. Shared link lookup and
      double-click selection use the same matched ranges, including wide cells and soft wraps.
      Validation passes 1395 JVM tests, fresh Release JS tests, 45 focused Native tests and
      Canvas/WebGL double-click/copy checks. Fixtures, tables, manifest and notices regenerate
      byte-for-byte; the original oracle exits successfully before its output is accepted.
- [x] Complete native activation of detected file paths: try lexical resolution against the
      local terminal working directory as `Surface.resolvePathForOpening` does, then follow
      `Ghostty.App.swift` conversion and safe-opening policy. Existing relative paths win
      before scheme interpretation; remaining plain paths get native tilde expansion and
      application-directory fallback. Shell variables and line-number suffixes stay literal.
      The original Swift conversion block supplies effective-target and policy expectations;
      separate native filesystem regressions cover the preceding core resolution step.
      Public host callbacks continue receiving raw matched text.
- [x] Keep deferred confirmation of plain-text non-HTTP links independent of `link-osc8`
      while preserving revocation of pending explicit OSC 8 requests. Internal link results
      retain origin through live and frozen views; explicit OSC 8 targets never become paths.
      Actual offscreen Cmd-click tests use the default pipeline with injected presentation and
      launch boundaries. Validation: 1396 JVM tests, fresh Release JS tests, 56 focused Native
      tests and Canvas/WebGL selection checks. The 207-case native oracle regenerates byte-for-byte.
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
      The unmodified `macos/Sources/Helpers/UntrustedURL.swift` and extracted native path
      conversion produce 207 comparative cases over 32 isolated resources, including
      directory/file slash hints and symlinks; source,
      generator and fixture hashes are recorded in `native-links-manifest.json`. Generation
      is byte-for-byte reproducible. The Native checks above cover the classifier corpus,
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

- [x] Bound WebGL backing requests by both `MAX_RENDERBUFFER_SIZE` and the per-axis
      `MAX_VIEWPORT_DIMS`, refreshing limits on context restoration. Viewport coordinates
      and CSS presentation use actual `drawingBufferWidth/Height`, preserving physical cell
      geometry when the browser allocates less than requested, as permitted by the
      [WebGL drawing-buffer specification](https://registry.khronos.org/webgl/specs/1.0.3/#2.2).
      Oversized surfaces clip at the device boundary instead of stretching a smaller buffer;
      host geometry and protocol cell dimensions stay independent. Host resize invalidation
      compares consecutive host measurements, so a capped backing size does not invalidate
      every unchanged frame. Real Chromium thin-surface tests cover both device-limit axes,
      DPR 1/1.25/2, exact image pixels, hide/show, recovery and disposal. Additional injected
      limits exercise independent axes, changed restored capabilities and a real smaller
      backing buffer beneath unchanged requested attributes. The regression failed before
      the fix. Validation passes the fresh Release JS suite, `verify-terminal-surface-limits.mjs`,
      the Canvas/WebGL graphics verifier and the general browser input/rendering verifier.
- [x] Clamp native Metal drawable dimensions to the device's 2D texture limit, including
      visibility restoration and test readback. The family lookup follows original
      `renderer/metal/Device.zig` and the [Apple feature-set table](https://developer.apple.com/metal/Metal-Feature-Set-Tables.pdf):
      Apple10 supports 32,768 pixels, Apple3 and later 16,384, with the original 8,192 fallback.
      Previously an oversized width left CAMetalLayer's drawable size at zero on the tested
      device. Thin real textures now verify both axes at its 16,384 limit, scales 1/1.25/2,
      image/background pixels and hide/show; normal fractional sizing keeps its ceil behavior.
      Readback checks its byte count in Long before allocating a texture or ByteArray; its
      4 GiB rejection test runs only on a device supporting the 32,768 limit. All eleven
      Native graphics tests pass. This does not close the separate live-window visibility
      gate or impose a combined resident/renderer memory budget.
- [x] Resize browser backing canvases by shrinking an axis before growing the other.
      Canvas/WebGL previously requested the new width at the old height during tall-to-wide
      changes: a DPR-2 regression requested 28,730,880 RGBA bytes between surfaces requiring
      at most 12,026,880 bytes each. Real browser tests now intercept backing-size assignments
      in both directions and require every intermediate area to fit the larger endpoint,
      while checking unchanged image pixels. The Release JS suite and Canvas/WebGL graphics
      verifier pass. This bounds intermediate requested area, not browser allocation overhead
      or the maximum final host surface size; the broader resource audit remains open.
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
- [x] Complete the existing decoded storage and frame-wide renderer resource audit.
      The [ownership report](terminal-graphics-memory.md) accounts for both screens,
      pending transfers, held generations, decode/composition scratch, frame-wide tile
      resources and host-dependent presentation surfaces. It proves resident pixel bytes
      stay within 4/3 of the configured limit after RGB-root conversion; this is not a
      process-memory or driver-allocation limit. Broader production/performance gates remain open.
- [x] Match the original 65 MiB graphics APC limit instead of silently dropping unchunked
      images above 1 MiB. The accounting oracle reads the original default directly;
      regressions exercise large complete/fragmented commands, cancellation and overflow
      at exactly one byte above the limit. Three new aggregate-conversion scenarios bring
      accounting coverage to 30 cases / 243 checkpoints, with byte-for-byte regeneration.
      Validation: all 1478 JVM tests, fresh Release JS tests, focused Native capture,
      accounting, image, execution and software/Metal tests, and actual Canvas/WebGL
      graphics pixels, pressure, holds and disposal checks pass.

The transfer-buffer audit removes a redundant full RGB/RGBA decode copy: exact completed
buffers move from the transfer owner to immutable pixels. Final chunks grow to exact capacity,
while borrowed inputs remain copied and excessive raw frame tails remain trimmed. Allocation
and lifetime regressions cover single/chunked loads, caller mutation and retained old images.
This does not combine encoded-input, resident-image, composition and held-frame budgets;
the original's RGB-root conversion can still exceed the nominal resident limit.

Graphics queries now validate without retaining decoded output. Uncompressed raw queries borrow
the command bytes; compressed raw queries consume the entire zlib stream with its fixed history
and checksum but no pixel-prefix collector. PNG queries retain two scanlines, validate every
filter and palette index (including Adam7), and skip the full RGBA destination. Outer-zlib PNG
still needs an encoded PNG collector for the second stage. Encoded/decoded/work limits and the
original decompression-before-dimensions error order remain unchanged; queries do not use the
resident storage budget or mutate pending transfers. The raw allocation regression failed before
the change. Existing malformed-image tests now require query/transmission error parity, and all
libpng fixtures validate with both direct and outer-zlib payloads while rejecting any output
pixel allocation. Validation passes 1451 JVM tests, fresh Release JS tests, 39 focused Native
image/zlib/storage/execution/media tests and actual Canvas/WebGL graphics pixels and lifecycle.
This removes a transient decoded image from queries; it does not close the combined resource audit.

The first RGB-root edit or disjoint self-composition now reuses its fresh RGBA conversion
as the destination, removing one further full `width * height * 4` temporary allocation.
Later edits still copy published RGBA pixels, and new-frame admission retains the original
conversion-before-reservation order. Allocation regressions failed before the fix and now
check both blend/overwrite modes, RGB/RGBA sources and unchanged caller-held generations.
Validation passes all 1442 JVM tests, the freshly built Release JS suite, 25 focused Native
animation/accounting/software/Metal tests and the actual browser Canvas/WebGL graphics
verifier. This reduces transient memory without closing the combined resource audit.

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

The decoded-generation lifetime audit now has JVM weak-reference evidence in addition to
byte-stability checks. A one-MiB image is uploaded through actual chunked APC input, frozen,
replaced repeatedly and deleted from storage. Intermediate generations become collectable
while the original remains readable. Explicit sync release, sync/resize timeouts, a resize
followed by synchronized output, and viewport disposal all release the original object and
byte array while the session, viewport and scheduler remain strongly reachable. An explicit
caller-held frame remains a valid owner until the caller releases it.

Separate weak-reference tests keep successful PNG pixels or a decoding exception alive while
checking collection of compressed input and inflated encoded PNG buffers.
The rejected-animation test keeps storage and its ENOSPC result alive: old RGB root bytes and
the rejected decoded frame are released, while the required converted RGBA root remains.
This preserves the original conversion-before-admission behavior, including its resident-limit
overrun. These tests close the decoded-generation lifetime gap; they do not impose a new
combined cap on storage, scratch space, explicit snapshots or host resources.
Validation passes all 1413 JVM tests and 29 existing Native decoder/cache/software/Metal
tests. The actual Canvas/WebGL graphics verifier also passes, including disposal, held pixels
and frame-wide cache pressure. Measured application-accounted peaks are 66,434,104 bytes for
Canvas and 66,356,416 bytes for WebGL, below their 67,108,864-byte budgets. These measurements
exclude browser/driver internal copies and are not process RSS bounds.

The native media corpus has 61 cases: 57 compare original-reader bytes/errors and cleanup.
Four regular/temporary-file seeks past EOF with omitted size crash the original Zig 0.17
safe build with integer overflow. Those crashes are recorded rather than treated as conformance
expectations; our separate recovery assertions require an empty read and temporary-file cleanup.
The image decoder then rejects insufficient pixel data. The oracle enables PNG capability with
a trapping callback and never decodes; actual PNG decoding is covered by native integration tests.

The broader graphics acceptance list remains in
[terminal production readiness](terminal-production-readiness.md). This migration adds to that
work; it does not declare the existing graphics or release gates complete.
