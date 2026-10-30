@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlinx.cinterop.useContents
import kotlin.test.*

class MacOsCopyTest {
    @Test fun copyOnSelectCommitsMixedDataAndRoutesIndependentDestinations() {
        NSApplication.sharedApplication()
        val board = NSPasteboard.pasteboardWithUniqueName()
        val native = TerminalNativeMimeClipboard(board)
        val destinations = mutableListOf<TerminalClipboardLocation>()
        var rejectStandard = false
        val backend = object : TerminalMimeClipboard by native {
            override fun write(location: TerminalClipboardLocation, contents: List<TerminalMimeClipboardContent>, complete: (TerminalClipboardResult) -> Unit) {
                destinations += location
                if (rejectStandard && location == TerminalClipboardLocation.STANDARD) error("Host write failed")
                native.write(location, contents, complete)
            }
        }
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE, mimeClipboard = backend)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        fun mouse(type: NSEventType, x: Double, y: Int = 0, count: Long = 1, modifiers: ULong = 0uL): NSEvent {
            val point = view.bounds.useContents { NSMakePoint(size.width / session.columns * x, size.height / session.rows * (y + 0.5)) }
            return assertNotNull(NSEvent.mouseEventWithType(type, view.convertPoint(point, toView = null),
                modifiers, 0.0, window.windowNumber, null, 0, count, 1.0f))
        }
        try {
            session.write("alpha beta")
            view.selectAll(null); assertTrue(destinations.isEmpty(), "macOS defaults to none")
            view.copyFormat = TerminalCopyFormat.VT
            view.copyOnSelect = TerminalCopyOnSelect.PRIMARY; view.selectAll(null)
            assertEquals(listOf(TerminalClipboardLocation.SELECTION), destinations); destinations.clear()
            assertNull(board.stringForType(NSPasteboardTypeString), "Unsupported selection is never redirected")
            view.copyOnSelect = TerminalCopyOnSelect.BOTH; view.selectAll(null)
            assertEquals(listOf(TerminalClipboardLocation.STANDARD, TerminalClipboardLocation.SELECTION), destinations); destinations.clear()
            assertEquals("alpha beta", board.stringForType(NSPasteboardTypeString))
            assertNotNull(board.stringForType(NSPasteboardTypeHTML), "Automatic copies use mixed even when explicit copy uses VT")
            view.copyOnSelect = TerminalCopyOnSelect.CLIPBOARD
            view.mouseDown(mouse(NSEventTypeLeftMouseDown, 0.1))
            view.mouseDragged(mouse(NSEventTypeLeftMouseDragged, 4.8))
            assertTrue(destinations.isEmpty(), "No writes while dragging")
            view.mouseUp(mouse(NSEventTypeLeftMouseUp, 4.8))
            assertEquals(listOf(TerminalClipboardLocation.STANDARD), destinations); destinations.clear()
            assertEquals("alpha", board.stringForType(NSPasteboardTypeString))
            view.menuForEvent(mouse(NSEventTypeRightMouseDown, 2.5))
            assertTrue(destinations.isEmpty(), "A context menu inside the selection keeps the clipboard")
            view.menuForEvent(mouse(NSEventTypeRightMouseDown, 7.5))
            assertEquals("beta", board.stringForType(NSPasteboardTypeString)); assertEquals(1, destinations.size); destinations.clear()
            view.mouseDown(mouse(NSEventTypeLeftMouseDown, 0.1)); view.mouseDragged(mouse(NSEventTypeLeftMouseDragged, 4.8))
            view.resignFirstResponder(); view.mouseUp(mouse(NSEventTypeLeftMouseUp, 4.8))
            assertTrue(destinations.isEmpty(), "Cancellation does not commit")
            session.write("\u001b[?1000h\u001b[?1006h")
            view.mouseDown(mouse(NSEventTypeLeftMouseDown, 1.5)); view.mouseUp(mouse(NSEventTypeLeftMouseUp, 1.5))
            view.menuForEvent(mouse(NSEventTypeRightMouseDown, 7.5))
            assertTrue(destinations.isEmpty(), "Application mouse reporting does not auto-copy")
            session.write("\u001bchttps://example.org/path")
            view.menuForEvent(mouse(NSEventTypeRightMouseDown, 10.5))
            assertNotEquals("https://example.org/path", board.stringForType(NSPasteboardTypeString))
            view.clearSelection()
            view.menuForEvent(mouse(NSEventTypeRightMouseDown, 10.5, modifiers = NSEventModifierFlagCommand))
            assertEquals("https://example.org/path", board.stringForType(NSPasteboardTypeString))
            session.write("\u001bc\u001b]8;;https://example.org\u0007hello world\u001b]8;;\u0007")
            view.menuForEvent(mouse(NSEventTypeRightMouseDown, 1.5, modifiers = NSEventModifierFlagCommand))
            assertEquals("e", board.stringForType(NSPasteboardTypeString))
            destinations.clear()
            view.copyOnSelect = TerminalCopyOnSelect.BOTH; rejectStandard = true
            view.selectAll(null)
            assertEquals(listOf(TerminalClipboardLocation.STANDARD, TerminalClipboardLocation.SELECTION), destinations)
            assertNotNull(view.accessibilitySelectedText(), "Host failure keeps selection and still attempts the other destination")
        } finally { view.dispose(); window.close(); board.releaseGlobally() }
    }

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
