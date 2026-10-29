# Ghostty 1.4 reference migration

The compatibility target now includes Ghostty 1.4.0, released on October 27, 2026.
The existing generated fixtures still use `4da7523faba68ccb4042ea20585817098a51c015`
(September 30). Do not relabel those fixtures as 1.4 results.

The official annotated tag `v1.4.0` resolves to
`83fb95624b0439ee660c2679b43b38c8b63769cd`. A separate checkout at
`build/upstream/ghostty-1.4.0` keeps the previous oracle reproducible during migration.
The release requires Zig 0.17.0. Its official macOS arm64 archive SHA-256 is
`b607e9b9234790a008116ae5bdb71c6243b84b9fb42a53a9e70fde41c06c536a`.

Sources: [release notes](https://ghostty.org/docs/install/release-notes/1-4-0),
[tagged source](https://github.com/ghostty-org/ghostty/tree/83fb95624b0439ee660c2679b43b38c8b63769cd),
[official Zig download index](https://ziglang.org/download/index.json).

## Reference and verification

- [ ] Build the original 1.4 libghostty-vt with Zig 0.17 and adapt the C oracle to API changes.
- [ ] Update Zig oracle helpers for the new language/library and upstream interfaces.
- [ ] Regenerate all affected fixtures and provenance through `scripts/`, review behavioral
      differences, and preserve the existing scenario coverage.
- [ ] Add regressions for newly supported behavior; passing the old corpus alone is insufficient.
- [ ] Update the primary pin only with the corresponding generated evidence and implementation.
- [ ] Run shared JVM/JS checks, relevant Native tests and fresh browser rendering/input checks.
- [ ] Complete the release-note/source audit for remaining emulation and input differences.

## Confirmed terminal behavior to implement or reconcile

- [ ] DECSTR (`CSI ! p`): reset the selected modes, margins, pen/protection/charsets, saved
      cursor, cursor appearance, modifyOtherKeys, status display and checksum variant while
      retaining screen contents and cursor position. Match `Terminal.softReset` exactly:
      it resets palette overrides but preserves dynamic colors and **does not reset tab stops**.
- [ ] RIS resets palette overrides in addition to its existing state reset.
- [ ] DECXCPR (`CSI ?6n`) responds with `CSI ?row;column;1R`, including origin/margin handling.
- [ ] `CSI 19 t` reports screen size in characters; the release dispatches `.csi_19_t`.
- [ ] DECRQCRA/XTCHECKSUM requests and configured checksum variants.
- [ ] Visibility mode 2033, its mode query, `CSI ?998n` and `CSI ?999;1/2n` reports, connected
      to host visibility. View animation visibility alone does not implement this protocol.
- [ ] OSC 7501 program status parsing, support query, state/observer integration and bounds.
- [ ] OSC 5522 clipboard transactions, host authorization, MIME data, aliases, cancellation,
      bounded buffering and protocol replies. Existing OSC 52 support does not cover it.
- [ ] Compare new origin-relative CHA/VPA/HPR/VPR, CUB/BS margin behavior, parameterless TBC,
      pending wrap with DECAWM disabled, charset/single-shift and reflow changes against tests.
- [ ] Compare mode reports, OSC 105 routing, empty OSC 22 reset and modified Backspace input.

## Graphics and external media

- [ ] Separate image transmission identity from content generation: animation playback and
      edits must not invalidate a pending frame merely because pixels changed. Compare the
      release's pending image/frame state and continuation behavior against the old corpus.
- [ ] Reconcile erased-history placement cleanup, cancelled/aborted APC commands and C1
      introducers using whole-stream and bytewise oracle regressions.
- [ ] Adapt placeholder/render-state oracles to the release's moved placement resolution.
- [ ] Native file, temporary-file and shared-memory readers with explicit host policy,
      offset/size handling, bounded allocations, safe file validation and deterministic cleanup.
      The common `attachGraphicsMedia` API is implemented; injected-reader tests do not prove
      platform I/O support.
- [ ] Follow 1.4's shared-memory name rules (pass valid names unchanged to `shm_open`, including
      forms without a leading slash) and canonical `/private/tmp` temporary-file handling.
- [ ] Add original-library external-media comparisons and real native lifecycle regressions,
      including cleanup after read/decode failure and rejected paths/ranges.
- [ ] Complete the existing decoded storage and frame-wide renderer resource audit.

The broader graphics acceptance list remains in
[terminal production readiness](terminal-production-readiness.md). This migration adds to that
work; it does not declare the existing graphics or release gates complete.
