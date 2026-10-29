@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.useContents
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class NativeWindowResizeTest {
    @Test fun permittedRequestsResizeTheOwningWindowAndCancelledRequestsDoNot() {
        val app = NSApplication.sharedApplication()
        for (backend in listOf(TerminalRendering.SOFTWARE, TerminalRendering.GPU)) {
            var allowed = false
            val session = TerminalSession()
            val view = AppKitTerminalView(session, rendering = backend, windowResizePolicy = { allowed })
            val window = NSWindow(NSMakeRect(100.0, 100.0, 780.0, 550.0),
                NSWindowStyleMaskTitled or NSWindowStyleMaskResizable, NSBackingStoreBuffered, false)
            window.setReleasedWhenClosed(false)
            val host = NSView(NSMakeRect(0.0, 0.0, 780.0, 550.0))
            window.contentView = host
            view.setFrame(NSMakeRect(6.0, 10.0, 768.0, 530.0))
            view.autoresizingMask = NSViewWidthSizable or NSViewHeightSizable
            host.addSubview(view)
            fun pump() {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.12))
                host.layoutSubtreeIfNeeded(); app.updateWindows()
            }
            fun frame() = window.frame.useContents { listOf(origin.x, origin.y, size.width, size.height) }
            try {
                window.orderFrontRegardless(); pump()
                val initial = frame()
                session.write("\u001b[8;18;60t"); pump(); assertEquals(initial, frame())
                allowed = true
                session.write("\u001b[8;18;60t")
                assertEquals(initial, frame(), "Parser dispatch cannot reenter layout")
                pump(); assertEquals(60, session.columns); assertEquals(18, session.rows)
                session.write("\u001b[8;;70t\u001b[8;20t"); pump()
                assertEquals(70, session.columns); assertEquals(20, session.rows)
                val height = frame()[3]
                session.write("\u001b[8;;65t"); pump()
                assertEquals(65, session.columns); assertEquals(height, frame()[3])
                val before = frame()
                session.write("\u001b[8;22;75t"); allowed = false; pump(); assertEquals(before, frame())
                allowed = true
                session.write("\u001b[8;22;75t"); view.removeFromSuperview(); host.addSubview(view)
                pump(); assertEquals(before, frame(), "Reattachment must not reuse a stale request")
                session.write("\u001b[8;65535;65535t"); pump()
                val visible = window.screen!!.visibleFrame
                assertTrue(frame()[2] <= visible.useContents { size.width })
                assertTrue(frame()[3] <= visible.useContents { size.height })
                assertTrue(session.columns.toLong() * session.rows <= 1_000_000)
                val bounded = frame()
                session.write("\u001b[8;18;60t"); view.dispose(); pump(); assertEquals(bounded, frame())
            } finally { view.dispose(); window.orderOut(null); window.close() }
        }
    }
}
