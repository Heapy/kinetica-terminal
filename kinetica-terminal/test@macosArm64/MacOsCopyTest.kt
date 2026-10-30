@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsCopyTest {
    @Test fun keyboardAndMenuCopyUseClipboardPolicyAndKeepSelection() {
        NSApplication.sharedApplication()
        val board = NSPasteboard.pasteboardWithUniqueName()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = mutableListOf<String>()
        val subscription = session.onInput { output += it.toByteArray().decodeToString() }
        view.writeCopyText = { board.clearContents(); assertTrue(board.setString(it, NSPasteboardTypeString)) }
        fun copied() = board.stringForType(NSPasteboardTypeString)
        fun commandC() {
            window.sendEvent(assertNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
                NSEventModifierFlagCommand, 0.0, window.windowNumber, null, "c", "c", false, 8u)))
        }
        try {
            assertTrue(window.makeFirstResponder(view))
            session.write("first  \r\n second\u00a0  \r\nthird")
            view.selectAll(null)
            val selected = assertNotNull(view.accessibilitySelectedText())
            assertEquals("first  \n second\u00a0  \nthird", selected)
            commandC(); assertEquals("first\n second\u00a0\nthird", copied())
            assertEquals(selected, view.accessibilitySelectedText()); assertTrue(output.isEmpty())
            view.copyTrimTrailingSpaces = false; view.copy(null)
            assertEquals(selected, copied())
            session.write("\u001b[?2026h\u001b[HCHANGED")
            view.copyTrimTrailingSpaces = true; view.copy(null)
            assertEquals("first\n second\u00a0\nthird", copied())
            view.copyTrimTrailingSpaces = false; commandC(); assertEquals(selected, copied())
            view.clearSelection(); view.copy(null); assertEquals(selected, copied(), "No selection leaves the clipboard alone")
            session.write("\u001b[?2026l")
            view.dispose(); view.copy(null); assertEquals(selected, copied())
        } finally { subscription.dispose(); view.dispose(); window.close(); board.releaseGlobally() }
    }
}
