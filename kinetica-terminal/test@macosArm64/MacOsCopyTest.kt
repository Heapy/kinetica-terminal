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
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE, mimeClipboard = TerminalNativeMimeClipboard(board))
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = mutableListOf<String>()
        val subscription = session.onInput { output += it.toByteArray().decodeToString() }
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
            assertNotNull(board.stringForType(NSPasteboardTypeHTML), "Default mixed copy publishes plain and HTML together")
            assertEquals(1, board.pasteboardItems?.size)
            view.copyFormat = TerminalCopyFormat.HTML; view.copy(null)
            assertNull(copied()); assertContains(assertNotNull(board.stringForType(NSPasteboardTypeHTML)), "background-color:")
            view.copyFormat = TerminalCopyFormat.VT; view.copy(null)
            assertContains(assertNotNull(copied()), "\u001b]10;rgb:"); assertNull(board.stringForType(NSPasteboardTypeHTML))
            view.copyFormat = TerminalCopyFormat.PLAIN; view.copy(null)
            assertEquals("first\n second\u00a0\nthird", copied()); assertNull(board.stringForType(NSPasteboardTypeHTML))
            view.copyFormat = TerminalCopyFormat.MIXED
            assertEquals(selected, view.accessibilitySelectedText()); assertTrue(output.isEmpty())
            view.copyTrimTrailingSpaces = false; view.copy(null)
            assertEquals(selected, copied())
            val mappings = mutableListOf(TerminalClipboardCodepointMap('f'.code, "Φ"), TerminalClipboardCodepointMap(' '.code, "_"))
            view.copyCodepointMap = mappings; mappings.clear()
            commandC(); assertEquals("Φirst__\n_second\u00a0__\nthird", copied(), "The host snapshots the configured mapping list")
            session.write("\u001b[?2026h\u001b[HCHANGED")
            view.copyTrimTrailingSpaces = true; view.copy(null)
            assertEquals("Φirst\n second\u00a0\nthird", copied())
            view.copyCodepointMap = listOf(TerminalClipboardCodepointMap('i'.code, "I"))
            view.copy(null); assertEquals("fIrst\n second\u00a0\nthIrd", copied(), "Mappings can change during the hold")
            view.copyCodepointMap = emptyList()
            view.copyTrimTrailingSpaces = false; commandC(); assertEquals(selected, copied())
            view.clearSelection(); view.copy(null); assertEquals(selected, copied(), "No selection leaves the clipboard alone")
            session.write("\u001b[?2026l")
            view.dispose(); view.copy(null); assertEquals(selected, copied())
        } finally { subscription.dispose(); view.dispose(); window.close(); board.releaseGlobally() }
    }
}
