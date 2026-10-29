@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.useContents
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class NativeVisibilityTest {
    @Test fun reportsFollowHiddenAncestorsOcclusionAttachmentAndDisposal() {
        val app = NSApplication.sharedApplication()
        for (backend in listOf(TerminalRendering.SOFTWARE,TerminalRendering.GPU)) {
            val session = TerminalSession(20,6)
            val reports = mutableListOf<String>()
            session.onInput { reports += it.toByteArray().decodeToString() }
            val view = AppKitTerminalView(session,rendering = backend)
            val window = NSWindow(NSMakeRect(120.0,120.0,240.0,120.0),NSWindowStyleMaskBorderless,NSBackingStoreBuffered,false)
            window.setReleasedWhenClosed(false)
            window.level = NSFloatingWindowLevel
            val screen = NSScreen.screens.filterIsInstance<NSScreen>().first()
            window.setFrameOrigin(screen.visibleFrame.useContents { NSMakePoint(origin.x+40,origin.y+40) })
            val host = NSView(NSMakeRect(0.0,0.0,240.0,120.0))
            view.frame = host.bounds
            window.contentView = host
            host.addSubview(view)
            val cover = NSWindow(window.frame.useContents {
                NSMakeRect(origin.x-32,origin.y-32,size.width+64,size.height+64)
            },NSWindowStyleMaskBorderless,NSBackingStoreBuffered,false)
            cover.setReleasedWhenClosed(false)
            cover.opaque = true; cover.backgroundColor = NSColor.blackColor; cover.level = window.level+1
            fun visible() = window.occlusionState and NSWindowOcclusionStateVisible != 0uL
            fun reported(value: Boolean) = session.potentiallyVisible == value &&
                reports.lastOrNull() == "\u001b[?999;${if (value) 1 else 2}n"
            try {
                session.write("\u001b[?2033h")
                assertTrue(reported(false),"An unattached window starts hidden")
                runWindowSteps(app, { "$backend host=${session.potentiallyVisible} window=${window.visible} occluded=${!visible()} reports=$reports" }, sequence {
                    window.orderFrontRegardless()
                    yield("Visible window" to { visible() && reported(true) })
                    view.hidden = true
                    yield("Hidden view" to { reported(false) })
                    view.hidden = false
                    yield("Unhidden view" to { reported(true) })
                    host.hidden = true
                    yield("Hidden ancestor" to { reported(false) })
                    host.hidden = false
                    yield("Unhidden ancestor" to { reported(true) })
                    cover.orderFrontRegardless()
                    yield("Fully occluded window" to { window.visible && !visible() && reported(false) })
                    cover.orderOut(null)
                    yield("Uncovered window" to { visible() && reported(true) })
                    view.removeFromSuperview()
                    yield("Detached view" to { reported(false) })
                    host.addSubview(view)
                    yield("Reattached view" to { reported(true) })
                    window.orderOut(null)
                    yield("Ordered out" to { reported(false) })
                    window.orderFrontRegardless()
                    yield("Restored window" to { visible() && reported(true) })
                    view.dispose()
                    assertTrue(reported(false))
                    val count = reports.size
                    view.dispose(); view.hidden = true; view.hidden = false
                    assertEquals(count,reports.size,"Disposed surfaces cannot emit visibility changes")
                    assertTrue(reports.zipWithNext().all { (a,b) -> a != b },"Unchanged visibility must not emit duplicate reports")
                })
            } finally { view.dispose(); cover.orderOut(null); cover.close(); window.orderOut(null); window.close() }
        }
    }
}
