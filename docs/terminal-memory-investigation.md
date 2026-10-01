# Terminal memory investigation (macOS)

Migration note: measurements below were recorded in the original Kinetica worktree.
Untracked `build/reports` evidence remains there and is not included in this source repository.

Question: why does Kinetica Terminal use more memory than Ghostty?

Measured on 2026-09-30, Apple M4 Max, macOS (Darwin 25.6.0), with `footprint <pid>` and
`vmmap <pid>`. The Kinetica build is the installed `/Applications/Kinetica Terminal.app`
(`samples/native-terminal`, Metal renderer from `kinetica-terminal/src@macosArm64/MetalTerminalDrawing.kt`).

## Initial observation

One process per app, one window each:

| Terminal | Physical footprint | Of which graphics |
|---|---|---|
| Kinetica Terminal | 254–292 MB | ~194 MB "Owned physical footprint (unmapped) (graphics)", 3 × 13.1 MB drawables |
| Ghostty | 150 MB | ~8 MB unmapped graphics, 3 × 24.4 MB + 48.9 MB IOSurface |
| Terminal.app | 43 MB | 7 MB IOSurface |

The drawables (`CAMetalLayer` triple buffering) were normal; Kinetica's window was even smaller
than Ghostty's. The whole difference was ~190 MB of unmapped GPU memory owned by the process.

The conditions were not equal: Kinetica was rendering continuously (a Claude Code session was
running inside it), while Ghostty and Terminal.app had just been launched and were idle.

## Hypothesis that was rejected

Kotlin/Native releases an Objective-C object only when the Kotlin GC collects its wrapper, so
dropped atlas textures (`textures.clear()`), replaced instance buffers (`buffers[slot] = …`) or
old drawables could stay alive. An experiment test drove `MetalTerminalDrawing` through resize
churn, atlas resets and 300 steady frames and compared `phys_footprint` before and after
`kotlin.native.runtime.GC.collect()`. GC reclaimed at most ~4 MB in every scenario, and the
steady-state footprint did not grow with resizes or atlas resets once each frame ran inside an
autorelease pool.

## Actual cause: Metal driver reservation on the first render pass

Instrumenting `draw()` showed that CPU-side preparation (atlas, instance batches) costs a few
MB; ~220 MB appears only after `command.commit()` of the first frame. Bisection:

| First GPU work in the process | Footprint after |
|---|---|
| Empty command buffer (no render encoder) | +2 MB |
| Empty render pass, 64×64 offscreen target, no pipeline | +199 MB |
| Empty render pass, 2048×2048 target | +218 MB |
| Offscreen `snapshot()` (no `CAMetalLayer`) | +238 MB |

The same happens without Kotlin at all. A minimal Swift program that clears a 64×64 texture:

```swift
let dev = MTLCreateSystemDefaultDevice()!    // footprint 3 MB
let q = dev.makeCommandQueue()!
// one render pass with loadAction = .clear, then commit + waitUntilCompleted
// footprint 204 MB; a second command queue adds ~3 MB
```

The reservation is released by the driver when the process stops submitting GPU work:

| Frame interval | Footprint while rendering | After 4 s without frames |
|---|---|---|
| 0.5 s | 207 MB | 10 MB |
| 1.0 s | 207 MB | 10 MB |
| 2.0 s | 199 MB | 10 MB |

Kinetica Terminal itself behaves the same way:

| Kinetica state | Total footprint | Unmapped graphics |
|---|---|---|
| Output being rendered | 292 MB | 196 MB |
| Idle for 25+ s | **92 MB** | **4 MB** |

## Conclusion

Kinetica does not leak GPU memory. The extra ~200 MB is a per-process Metal driver reservation
that exists while the app renders at least every ~2 s and is returned after a few idle seconds.
The original comparison measured a rendering Kinetica against an idle Ghostty. Idle to idle,
Kinetica (92 MB) is below Ghostty (150 MB) and above Terminal.app (43 MB).

Confirmed with a Claude Code session running in each terminal at the same time:

| Terminal | Total footprint | Unmapped graphics | IOSurface |
|---|---|---|---|
| Kinetica Terminal | 292 MB | 196 MB | 47 MB |
| Ghostty | 334 MB | 200 MB | 46 MB |
| Terminal.app | 329–360 MB | 195 MB | 62–93 MB |

Under equal load all three carry the same ~200 MB driver reservation, and Kinetica has the
smallest total footprint.

## Test harness pitfall

Pumping the run loop with `NSRunLoop.runUntilDate` without an `autoreleasepool { }` keeps every
`CAMetalLayer.nextDrawable()` result alive until the process exits: 40 resizes grew the test
process by 966 MB, none of it reclaimable by GC. The application is not affected because
`application.run()` (`samples/native-terminal/src/main.kt`) drains the pool per event, but
long GPU scenarios in `kinetica-terminal/test@macosArm64/` pump the run loop the same way and
should wrap each frame in `autoreleasepool { }`.
