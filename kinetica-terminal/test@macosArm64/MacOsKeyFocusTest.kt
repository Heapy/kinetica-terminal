@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsKeyFocusTest {
    @Test fun windowLossReleasesKeysBeforeFocusReportAndPreservesSelection() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val out = StringBuilder()
        val observation = session.onInput { out.append(it.toByteArray().decodeToString()) }
        fun key(release: Boolean = false) = assertNotNull(NSEvent.keyEventWithType(
            if (release) NSEventTypeKeyUp else NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
            NSEventModifierFlagControl, 0.0, window.windowNumber, null, "\r", "\r", false, 36u))
        fun lose() = NSNotificationCenter.defaultCenter.postNotificationName(NSWindowDidResignKeyNotification, window)
        fun regain() = NSNotificationCenter.defaultCenter.postNotificationName(NSWindowDidBecomeKeyNotification, window)
        try {
            session.write("selected\u001b[>31u\u001b[?1004h")
            assertTrue(window.makeFirstResponder(view)); out.clear()
            view.keyDown(key()); assertEquals("\u001b[13;5u", out.toString()); out.clear()
            view.selectAll(null)
            lose()
            assertEquals("\u001b[13;5:3u\u001b[57448;1:3u\u001b[57442;1:3u\u001b[O", out.toString())
            assertEquals("selected", view.accessibilitySelectedText(), "Synthetic releases do not act as typed input")
            out.clear(); lose(); view.keyUp(key(true)); assertEquals("", out.toString())
            assertEquals(view, window.firstResponder, "Window focus can change without a responder change")
            regain(); regain(); assertEquals("\u001b[I", out.toString()); out.clear()

            view.keyDown(key()); view.keyUp(key(true)); out.clear()
            view.resignFirstResponder()
            assertEquals("\u001b[57448;1:3u\u001b[57442;1:3u\u001b[O", out.toString())
            assertTrue(window.makeFirstResponder(null)); out.clear(); regain()
            assertEquals("", out.toString(), "A different responder must retain keyboard ownership")

            assertTrue(window.makeFirstResponder(view)); out.clear()
            session.keyboardLockAllowed = true; session.write("\u001b[2h")
            view.keyDown(key()); session.write("\u001b[2l"); lose()
            assertEquals("\u001b[O", out.toString(), "Locked input does not create pending keys")
            regain(); out.clear(); view.keyDown(key()); out.clear()
            view.dispose(); lose(); regain()
            assertEquals("", out.toString(), "Disposal removes window observers without sending input")
        } finally { observation.dispose(); view.dispose(); window.close() }
    }
}
