@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import platform.QuartzCore.*
import kotlin.test.*

class NativeMetalDrawableTest {
    @Test fun resumesAnimationAfterDrawableTemporarilyUnavailable() {
        val app = NSApplication.sharedApplication()
        val session = TerminalSession(20, 6)
        val view = AppKitTerminalView(session, rendering = TerminalRendering.GPU)
        var unavailable = false
        var missed = 0
        view.acquireMetalDrawable = { layer ->
            if (unavailable) { missed++; null } else layer.nextDrawable()
        }
        val window = NSWindow(NSMakeRect(120.0, 120.0, 240.0, 120.0),
            NSWindowStyleMaskBorderless, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false)
        window.contentView = view
        try {
            runWindowSteps(app, { "missed=$missed uploads=${view.imageUploads} submitted=${view.submittedGpuFrames}" }, sequence {
                window.orderFrontRegardless()
                yield("Visible window" to { window.occlusionState and NSWindowOcclusionStateVisible != 0uL })
                session.write("\u001b[?25l\u001b_Ga=T,i=1,q=2,f=32,s=1,v=1,c=4,r=4,C=1;AQID/w==\u001b\\" +
                    "\u001b_Ga=f,i=1,q=2,f=32,s=1,v=1,z=100;BAUG/w==\u001b\\" +
                    "\u001b_Ga=a,i=1,r=1,z=100,s=3\u001b\\")
                val current = assertNotNull(session.graphicsState.storage.image(1))
                yield("Initial image upload" to { view.imageUploads == 1 })
                val generation = current.generation
                unavailable = true
                yield("Animation wake without a drawable" to { missed > 0 })
                // Let both the 100 ms animation wake and any window-attachment
                // redraws run into the unavailable drawable before restoring it.
                val restoreAfter = CACurrentMediaTime() + 0.3
                yield("Drain pending redraws" to { CACurrentMediaTime() >= restoreAfter })
                assertSame(generation, current.generation, "Do not advance an animation without a drawable")
                assertEquals(1, view.imageUploads)
                // Changing this flag neither invalidates the layer nor writes terminal output.
                // Only the renderer's retry can recover the consumed animation wake.
                unavailable = false
                yield("Automatic recovery" to { view.imageUploads >= 2 && current.animation.currentIndex == 1 })
            })
        } finally {
            view.dispose()
            window.orderOut(null); window.close()
        }
    }

    @Test fun coalescesDrawableRetriesAndCancelsThemWhenHiddenOrDisposed() {
        val app = NSApplication.sharedApplication()
        val viewport = TerminalViewport(TerminalSession(20, 6), {})
        var invalidations = 0
        val renderer = MetalTerminalDrawing(acquireDrawable = { null }) { invalidations++ }
        renderer.resize(240.0, 120.0, 1.0, 14.0, 8.0, 16.0)
        fun delay(): () -> Boolean {
            val until = CACurrentMediaTime() + 0.1
            return { CACurrentMediaTime() >= until }
        }
        try {
            runWindowSteps(app, { "invalidations=$invalidations" }, sequence {
                repeat(10) { renderer.draw(viewport) }
                yield("Coalesced drawable retry" to { invalidations > 0 })
                yield("No repeated timer" to delay())
                assertEquals(1, invalidations)
                assertEquals(0, renderer.submittedFrames)
                renderer.draw(viewport)
                renderer.setVisible(false)
                yield("Hidden renderer cancels retry" to delay())
                assertEquals(1, invalidations)
                renderer.setVisible(true)
                renderer.draw(viewport)
                yield("Restored renderer can retry" to { invalidations == 2 })
                renderer.draw(viewport)
                renderer.dispose()
                yield("Disposed renderer cancels retry" to delay())
                assertEquals(2, invalidations)
            })
        } finally { viewport.dispose(); renderer.dispose() }
    }
}

