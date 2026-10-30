@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import kotlinx.cinterop.useContents
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class NativeTerminalLinkHoverTest {
    private class Harness {
        private val app = NSApplication.sharedApplication()
        val session = TerminalSession(80, 5)
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 800.0, 480.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        init { window.setReleasedWhenClosed(false); window.contentView = view; view.openLink = { true } }
        fun mouse(type: NSEventType = NSEventTypeMouseMoved, x: Double = 12.0, flags: ULong = NSEventModifierFlagCommand): NSEvent =
            assertNotNull(NSEvent.mouseEventWithType(type, view.convertPoint(NSMakePoint(x, 5.0), toView = null),
                flags, 0.0, window.windowNumber, null, 0, 1, 1.0f))
        fun modifiers(flags: ULong) { view.flagsChanged(assertNotNull(NSEvent.keyEventWithType(NSEventTypeFlagsChanged,
            NSMakePoint(0.0, 0.0), flags, 0.0, window.windowNumber, null, "", "", false, 55u))) }
        fun close() { view.dispose(); window.close() }
    }

    @Test fun nativeHoverFollowsModifiersCaptureDragAndProtocolCursor() {
        val h = Harness(); val view = h.view; val session = h.session
        try {
            session.write("\u001b]8;;https://example.org\u001b\\click\u001b]8;;\u001b\\")
            view.mouseMoved(h.mouse(flags = 0u)); assertTrue(view.linkPreviewLabel.hidden)
            h.modifiers(NSEventModifierFlagCommand)
            assertEquals(NSCursor.pointingHandCursor, view.pointerCursor)
            assertEquals("https://example.org", view.linkPreviewLabel.stringValue)
            assertFalse(view.linkPreviewLabel.hidden)
            assertTrue(view.linkPreviewLabel.frame.useContents { size.width > 0 && size.height > 0 })
            assertNull(view.linkPreviewLabel.hitTest(NSMakePoint(1.0, 1.0)))
            session.write("\u001b]22;crosshair\u001b\\")
            assertEquals(NSCursor.pointingHandCursor, view.pointerCursor, "Hover overrides OSC 22")
            h.modifiers(0u); assertEquals(NSCursor.crosshairCursor, view.pointerCursor)
            assertTrue(view.linkPreviewLabel.hidden)
            h.modifiers(NSEventModifierFlagCommand)
            session.write("\u001b[?1000h")
            assertTrue(view.linkPreviewLabel.hidden, "Capture mode changes apply without pointer movement")
            h.modifiers(NSEventModifierFlagCommand or NSEventModifierFlagShift)
            assertFalse(view.linkPreviewLabel.hidden)
            session.configureMouseShiftCapture(defaultCapture = true, allowApplication = false)
            assertTrue(view.linkPreviewLabel.hidden)
            session.configureMouseShiftCapture()
            assertFalse(view.linkPreviewLabel.hidden)
            session.write("\u001b[?1000l"); h.modifiers(NSEventModifierFlagCommand)
            view.mouseDown(h.mouse(NSEventTypeLeftMouseDown))
            view.mouseDragged(h.mouse(NSEventTypeLeftMouseDragged, 40.0))
            assertTrue(view.linkPreviewLabel.hidden)
            view.mouseDragged(h.mouse(NSEventTypeLeftMouseDragged))
            assertFalse(view.linkPreviewLabel.hidden, "Hover returns at the origin even though activation remains cancelled")
            view.mouseUp(h.mouse(NSEventTypeLeftMouseUp))
            NSNotificationCenter.defaultCenter.postNotificationName(NSWindowDidResignKeyNotification, h.window)
            assertTrue(view.linkPreviewLabel.hidden); assertEquals(NSCursor.crosshairCursor, view.pointerCursor)
            view.mouseMoved(h.mouse()); assertFalse(view.linkPreviewLabel.hidden)
            view.mouseExited(h.mouse()); assertTrue(view.linkPreviewLabel.hidden)
        } finally { h.close() }
    }

    @Test fun nativePreviewModesFrozenTargetsAndDisposalKeepUiCurrent() {
        val h = Harness(); val view = h.view; val session = h.session
        try {
            session.write("https://example.org")
            view.mouseMoved(h.mouse()); assertEquals("https://example.org", view.linkPreviewLabel.stringValue)
            session.detectedLinksEnabled = false
            assertTrue(view.linkPreviewLabel.hidden); assertEquals(NSCursor.IBeamCursor, view.pointerCursor)
            session.detectedLinksEnabled = true
            assertFalse(view.linkPreviewLabel.hidden)
            view.linkPreviewMode = TerminalLinkPreviewMode.OSC8
            assertTrue(view.linkPreviewLabel.hidden); assertEquals(NSCursor.pointingHandCursor, view.pointerCursor)
            session.reset(); session.write("\u001b]8;;https://hidden.example\u001b\\click\u001b]8;;\u001b\\")
            assertFalse(view.linkPreviewLabel.hidden)
            session.detectedLinksEnabled = false
            assertFalse(view.linkPreviewLabel.hidden, "Explicit OSC 8 preview is independent of detection")
            view.linkPreviewMode = TerminalLinkPreviewMode.NONE
            assertTrue(view.linkPreviewLabel.hidden); assertEquals(NSCursor.pointingHandCursor, view.pointerCursor)
            view.linkPreviewMode = TerminalLinkPreviewMode.ALL
            session.write("\u001b[?2026h\u001b[H\u001b[2Kchanged")
            assertEquals("https://hidden.example", view.linkPreviewLabel.stringValue)
            session.osc8LinksEnabled = false; assertTrue(view.linkPreviewLabel.hidden)
            session.osc8LinksEnabled = true; assertFalse(view.linkPreviewLabel.hidden)
            session.write("\u001b[?2026l"); assertTrue(view.linkPreviewLabel.hidden)
            session.reset(); session.write("\u001b]8;;https://example.org/\u202ex\u001b\\click\u001b]8;;\u001b\\")
            assertEquals("https://example.org/\\u{202E}x", view.linkPreviewLabel.stringValue)
            view.dispose(); assertTrue(view.linkPreviewLabel.hidden); assertEquals("", view.linkPreviewLabel.stringValue)
            session.write("\u001b]22;crosshair\u001b\\"); session.osc8LinksEnabled = false
            assertTrue(view.linkPreviewLabel.hidden)
        } finally { h.close() }
    }
}
