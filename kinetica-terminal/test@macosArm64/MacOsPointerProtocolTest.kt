@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsPointerProtocolTest {
    @Test fun nativeViewAppliesPointerChangesAndDisposesItsSubscription() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        try {
            assertEquals(NSCursor.IBeamCursor, view.pointerCursor)
            session.write("\u001b]22;pointer\u0007")
            assertEquals(NSCursor.pointingHandCursor, view.pointerCursor)
            view.resetCursorRects()
            session.write("\u001b[?2026h\u001b]22;crosshair\u001b\\")
            assertTrue(session.synchronizedOutput)
            assertEquals(NSCursor.crosshairCursor, view.pointerCursor)
            session.write("\u001b]22;wait\u0007")
            assertEquals(TerminalPointerShape.WAIT, session.pointerShape)
            assertEquals(NSCursor.crosshairCursor, view.pointerCursor) // Not provided by Ghostty's AppKit mapping either.
            session.write("\u001b]22;xterm\u0007\u001bc")
            assertEquals(NSCursor.IBeamCursor, view.pointerCursor)
            session.write("\u001b]22;default\u0007")
            assertEquals(NSCursor.arrowCursor, view.pointerCursor)
            view.dispose()
            session.write("\u001b]22;pointer\u0007")
            assertEquals(NSCursor.arrowCursor, view.pointerCursor)
        } finally { view.dispose(); window.close() }
    }
}
