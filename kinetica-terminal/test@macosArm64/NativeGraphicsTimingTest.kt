@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.useContents
import platform.AppKit.*
import platform.Foundation.*
import platform.QuartzCore.CACurrentMediaTime
import kotlin.test.*

class NativeGraphicsTimingTest {
    private fun delay(seconds: Double): () -> Boolean {
        val end = CACurrentMediaTime() + seconds
        return { CACurrentMediaTime() >= end }
    }

    @Test fun automaticPlaybackStopsForHiddenDetachedAndDisposedNativeViews() {
        val app = NSApplication.sharedApplication()
        app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        for (backend in listOf(TerminalRendering.SOFTWARE,TerminalRendering.GPU)) {
            val session = TerminalSession(20,6)
            val view = AppKitTerminalView(session,rendering=backend)
            val window = NSWindow(NSMakeRect(120.0,120.0,240.0,120.0),NSWindowStyleMaskBorderless,NSBackingStoreBuffered,false)
            window.setReleasedWhenClosed(false)
            // Playback is intentionally paused while occluded. Keep this isolated
            // fixture above ordinary app windows, including during unhide/reattach.
            window.level = NSFloatingWindowLevel
            val screen = NSScreen.screens.filterIsInstance<NSScreen>().first()
            window.setFrameOrigin(screen.visibleFrame.useContents { NSMakePoint(origin.x + 40, origin.y + 40) })
            var image: TerminalGraphicsStoredImage? = null
            try {
                window.contentView = view
                runWindowSteps(app, {
                    "$backend frame=${image?.animation?.currentIndex}, shown=${image?.animation?.shownAt}, uploads=${view.imageUploads}, " +
                        "dirty=${view.needsDisplay}, visible=${window.visible}, occluded=${window.occlusionState and NSWindowOcclusionStateVisible == 0uL}, " +
                        "occlusion=${window.occlusionState}, hidden=${view.hiddenOrHasHiddenAncestor}"
                }, sequence {
                    window.orderFrontRegardless()
                    yield("Visible window" to { window.visible && window.occlusionState and NSWindowOcclusionStateVisible != 0uL })
                    session.write("\u001b[?25l\u001b_Ga=T,i=1,q=2,f=32,s=1,v=1,c=4,r=4,C=1;AQID/w==\u001b\\" +
                        "\u001b_Ga=f,i=1,q=2,f=32,s=1,v=1,z=80;BAUG/w==\u001b\\" +
                        "\u001b_Ga=a,i=1,r=1,z=80,s=3\u001b\\")
                    val current = assertNotNull(session.graphicsState.storage.image(1)); image = current
                    yield("Automatic frame upload" to { current.animation.currentIndex == 1 && view.imageUploads >= 2 })
                    view.hidden = true
                    val hidden = current.generation
                    yield("Hidden view interval" to delay(0.25))
                    assertSame(hidden,current.generation,"$backend hidden view must not advance frames")
                    view.hidden = false
                    yield("Unhidden playback" to { current.generation !== hidden })
                    window.orderOut(null)
                    yield("Window ordered out" to { !window.visible })
                    yield("Drain visibility callbacks" to delay(0.05))
                    val orderedOut = current.generation
                    yield("Hidden window interval" to delay(0.25))
                    assertSame(orderedOut,current.generation,"$backend hidden window must be idle")
                    window.orderFrontRegardless()
                    yield("Restored playback" to { current.generation !== orderedOut })
                    view.removeFromSuperview()
                    val detached = current.generation
                    yield("Detached interval" to delay(0.25))
                    assertSame(detached,current.generation,"$backend detached view must be idle")
                    window.contentView = view
                    yield("Reattached playback" to { current.generation !== detached })
                    view.dispose()
                    val disposed = current.generation
                    yield("Disposed interval" to delay(0.25))
                    assertSame(disposed,current.generation,"$backend disposed view must cancel pending timers")
                })
            } finally { view.dispose(); window.orderOut(null); window.close() }
        }
    }
}
