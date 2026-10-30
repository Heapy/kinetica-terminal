# Graphics memory ownership and limits

This audit covers application-owned Kitty image payloads, transfer/decode scratch buffers,
retained image generations, tile caches and requested presentation surfaces. The reference is
Ghostty 1.4.0 (`83fb95624b0439ee660c2679b43b38c8b63769cd`). It does not turn the image-storage
setting into a process-memory limit. Text/history, glyph atlases, placement/object overhead,
VM collection latency, system backing stores and driver copies have separate owners.

## Limits and owners

| Symbol | Default | Owner and enforcement |
| --- | ---: | --- |
| C | 65 MiB | One active graphics APC capture per session; `TerminalGraphicsApc` / `TerminalGraphicsCommandParser`. Headers use fixed storage. Glyph APC keeps its independent 1 MiB limit. |
| A | at most 3C/4 | Base64-decoded command payload. Capture storage is released before dispatch. The command remains borrowed by storage until the operation returns. |
| E | 400 MiB | Each screen's unfinished transfer; also the encoded-input and outer-zlib expansion limit. Transfer capacity and inflated collector capacity never exceed E. |
| P | at most 400,000,000 bytes | One decoded/animated RGBA image at the 10,000 × 10,000 dimension limit. Decoder also enforces its configured decoded-byte ceiling (400 MiB by default). RGB roots can grow from three to four bytes per pixel during animation. |
| L | 64 MiB per screen in views | Configurable `graphicsStorageBytes`. Bare sessions start disabled; standalone storage's default is 320,000,000 bytes. Both screens receive the session setting. |
| Q | 64 MiB per renderer | Image tile cache, including textures retired from the cache but still used by unfinished GPU commands. |
| T | at most 4 MiB | One 1024 × 1024 RGBA upload tile. Canvas includes its reusable ImageData in Q; WebGL/Metal have this additional transient CPU buffer. |
| B | host geometry dependent | Requested presentation surface, separate from Q. Canvas's logical RGBA payload is 4 × backing width × backing height. Native backing-store allocation belongs to AppKit/Core Animation. |

The C default is read from the original `apc.Protocol.kitty.defaultMaxBytes()` by the
[accounting oracle](../scripts/terminal-ghostty-graphics-accounting-oracle.zig), not inferred
from a passing small-payload test. A regression loads the same unchunked RGB image with more
than 1 MiB of Base64 both in one write and through 8191-byte transport fragments. The previous
1 MiB capture default silently discarded it. Capture overflow and cancellation release the
buffer before the next command. Unknown APC identifiers allocate no image payload.

`graphics_image.zig` in the original defines both the 400 MiB byte ceiling and the 10,000
pixel dimension ceiling. The smaller view storage setting and the 16,384-placement-per-screen
policy are embedding limits. Placement metadata is not charged as pixel storage. Neither the
pixel budget nor this audit promises a fixed total heap size for those objects.

## Resident images: the conversion overrun is bounded

At an operation boundary, one screen's resident frame bytes R satisfy:

```
3R <= 4L
```

New image/frame admission and reapplying a positive limit leave R <= L. The exception is the
original's conversion of an RGB root to RGBA before animation admission, even if that admission
subsequently fails. Repeating conversion on an already-RGBA root does not grow it again.

A useful proof weights each live RGB byte as 1 and each live RGBA byte as 3/4. Admission with
R <= L also bounds this weighted sum by L. RGB-to-RGBA conversion preserves the sum. Frame
edits preserve dimensions and format; deletion decreases it. Root promotion promotes an
already-RGBA frame. Applying a new positive limit evicts back to R <= the new L, and disabling
storage clears it. Since every live byte has weight at least 3/4, R <= 4L/3 throughout each
limit epoch. This is a pixel-payload bound, not a claim that `totalBytes` includes every owner.

The original oracle includes four simultaneous RGB roots, mixed animation frames, failed or
successful subsequent admission, root deletion/promotion, both screens and decreasing/reset
limits. Four three-byte roots reach exactly 16 resident bytes under a 12-byte limit. All
30 cases / 243 checkpoints compare original replies, images, frames and resident counters;
common replay additionally checks the inequality at every checkpoint. See
[TerminalGraphicsAccountingTest](../kinetica-terminal/test/TerminalGraphicsAccountingTest.kt).

## Generations outside resident storage

A viewport has one frozen-frame slot shared by synchronized-output and resize holds. It keeps
references to immutable displayed pixels, not copies of every animation frame. Charge its
held payload H at capture time: reducing today's L cannot shrink a previously captured frame.
Several viewports can retain different generations, so sum their H values separately. A single
held frame's unique image bytes are at most the active screen's resident bytes when captured.

Explicit caller-retained frames are additional owners until released. There is no fixed bound
on the number of snapshots an embedding application may keep. `renderContent` is reconciled
after mutations; tile keys retain detached identity tokens rather than source pixel buffers.
Thus one cached four-byte crop cannot keep a discarded large source alive.

[JVM snapshot tests](../kinetica-terminal/test@jvm/TerminalGraphicsSnapshotRetentionTest.kt)
keep sessions, viewports and schedulers alive while checking collection of replaced generations,
hold release, both timeouts, resize-to-sync handoff and disposal. They also demonstrate that a
caller-owned snapshot deliberately keeps its pixels alive.
[Cache tests](../kinetica-terminal/test@jvm/TerminalImageCacheRetentionTest.kt) check the tiny-crop
case. These establish ownership release; they do not guarantee immediate heap or RSS reduction.

## Transient buffers and simultaneous owners

Start with both screens' resident images, both pending transfers, every viewport hold and every
caller-owned snapshot. Add the transient owners below for the operation being performed. These
limits are independent: summing only resident storage and tile caches understates the peak.

| Phase | Additional live owners |
| --- | --- |
| Capture growth | Old and replacement Base64 buffers can coexist; each is <= C. |
| Base64 completion | Encoded capture and decoded command coexist during decoding; bounds C and A. Dispatch clears the parser's encoded owner. |
| Transfer append/trim | Old and replacement transfer capacities can coexist; each is <= E. The command payload or external read buffer remains separate. Final chunks grow to exact capacity; `take()` clears the transfer owner. |
| Native external media | Read result <= E; shared-memory read also maps the requested range plus less than one page of alignment. Mapping and descriptor are released before the result returns. A custom provider's own allocations remain its responsibility. |
| Outer zlib | Input plus a geometrically grown collector. Old and replacement collector buffers may coexist, each <= E. The full stream, checksum and expansion bound are checked even when a raw frame tail is discarded. |
| PNG | Full RGBA destination <= P, two scanlines (at most 160,000 bytes total), palette/transparency (at most 1024 bytes) and fixed DEFLATE history/tables. IDAT chunks are read without concatenation. Outer-zlib PNG borrows the collector's valid prefix without a trimming copy. |
| Query | No full decoded image. Raw zlib queries keep inflater history only; PNG queries retain scanlines and check every filter/palette index. Outer-zlib PNG still retains its encoded PNG collector. An existing transfer on either screen is preserved. |
| Animation mutation | Incoming decoded pixels, an RGB-root conversion and a copy of a previously published destination may coexist. Each new full buffer is <= P. The first root edit reuses the private conversion; later edits preserve caller-held generations. |
| Image upload | Q plus a bounded tile conversion T for WebGL/Metal. Canvas reserves T inside Q. AppKit software writes directly into the CFData retained by its cached CGImage. |

For a serialized operation, this table supplies a conservative peak calculation from the actual
pre-operation owners: add every buffer that can overlap in that phase, retaining old generations
until their final owner disappears. It deliberately does not equate L with the decode ceiling E,
or assume that both screens share one pending-transfer budget. Synchronous callbacks that retain
snapshots, injected media providers and application-owned input buffers must be charged by the
embedding application as well.

Allocation-identity tests cover final transfer ownership, bounded raw-prefix inflation, query
validation, first RGB conversion reuse and PNG collector borrowing. JVM
[decode-retention tests](../kinetica-terminal/test@jvm/TerminalGraphicsDecodeRetentionTest.kt)
keep the decoded result or error alive while checking release of encoded inputs and rejected
frames. These checks supplement, rather than replace, the original byte/accounting oracles.

## Renderer and surface accounting

`TerminalImageCache` reserves room before creating a tile. GPU eviction retires resources;
completion releases them. When Q cannot fit the next tile, WebGL drains queued commands and
Metal completes the current batch, then resumes the same frame. Q therefore bounds all
application-accounted tile textures across the frame, not merely entries left in the LRU.
The upload byte array is an additional T on those GPU paths. Canvas's reusable ImageData is
reserved inside Q. AppKit's CFData is the cached payload, not a second retained Kotlin copy.

Presentation surfaces are independent. Canvas uses host-provided backing geometry; it has no
separate fixed pixel-area policy. Resize shrinks one axis before growing the other, avoiding a
requested intermediate area larger than either endpoint. WebGL clamps to the device renderbuffer
and per-axis viewport limits, then uses actual drawing-buffer dimensions. Metal clamps each
axis to the device's 2D texture limit. These are validity limits, not a 64 MiB presentation cap.
The host controls window size, DPR and view count; the terminal resize protocol requires host
approval. Old and new compositor surfaces or driver copies can coexist outside these counters.

Hidden/disposed Canvas views release image caches, ImageData and backing dimensions. WebGL also
releases its raster canvas, glyph textures and draw resources. Native release waits for outstanding
Metal commands before purging owned resources. AppKit owns the software view's presentation store.

The browser verifier tracks actual tile/upload/canvas requests for its tested viewport and
checks pixels, pressure, context recovery, hide/show and disposal. Native tests compare Metal
and software pixels and exercise a two-tile budget across a larger visible frame. Neither is
proof of an arbitrary host surface's allocation size. Glyph atlases, vertex buffers, system
color conversion and driver-private copies are not covered by Q or by the graphics pixel model.

## Outcome

The decoded/transient/software-surface ownership audit is complete with these explicit scopes.
It found and corrected source retention, redundant copies, query allocations, transient resize
area, invalid GPU surface dimensions and the restrictive single-APC limit. There is no single
aggregate memory cap spanning all owners. Hosts needing a tighter cap must account for their
view/snapshot/input ownership and geometry in addition to configuring decoded storage. The
broader production resource/performance gates remain separate; this is not an RSS guarantee.
