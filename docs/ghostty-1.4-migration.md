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
- [ ] Add regressions for newly supported behavior; passing the old corpus alone is insufficient.
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
- [ ] OSC 5522 clipboard transactions, host authorization, MIME data, aliases, cancellation,
      bounded buffering and protocol replies. Existing OSC 52 support does not cover it.
- [x] Reconcile origin-relative CHA/VPA/HPR/VPR, CUB/BS margin behavior and the changed reflow scenarios.
- [ ] Extend coverage for parameterless TBC, pending wrap with DECAWM disabled and charset/single-shift changes.
- [x] Empty OSC 22 resets the pointer to text.
- [ ] Compare mode reports, OSC 105 routing and modified Backspace input.

## Graphics and external media

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

The native media corpus has 61 cases: 57 compare original-reader bytes/errors and cleanup.
Four regular/temporary-file seeks past EOF with omitted size crash the original Zig 0.17
safe build with integer overflow. Those crashes are recorded rather than treated as conformance
expectations; our separate recovery assertions require an empty read and temporary-file cleanup.
The image decoder then rejects insufficient pixel data. The oracle enables PNG capability with
a trapping callback and never decodes; actual PNG decoding is covered by native integration tests.

The broader graphics acceptance list remains in
[terminal production readiness](terminal-production-readiness.md). This migration adds to that
work; it does not declare the existing graphics or release gates complete.
