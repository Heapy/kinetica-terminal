@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.CoreGraphics.*
import kotlin.test.*

class MacOsMouseClipboardTest {
    @Test fun localMouseActionsCopyPastePreserveSelectionAndYieldToApplicationReporting() {
        NSApplication.sharedApplication()
        val board = NSPasteboard.pasteboardWithUniqueName()
        val native = TerminalNativeMimeClipboard(board)
        val reads = mutableListOf<TerminalClipboardLocation>()
        var deferred: ((TerminalMimeClipboardRead) -> Unit)? = null
        var delayRead = false
        var failRead = false
        val backend = object : TerminalMimeClipboard by native {
            override fun read(location: TerminalClipboardLocation, mimeTypes: List<String>, list: Boolean, complete: (TerminalMimeClipboardRead) -> Unit) {
                reads += location
                if (delayRead) { deferred = complete; return }
                if (failRead) { complete(TerminalMimeClipboardRead(TerminalClipboardResult.DENIED)); return }
                if (location == TerminalClipboardLocation.SELECTION) {
                    complete(TerminalMimeClipboardRead(available = listOf("text/plain"),
                        contents = if (list) emptyList() else listOf(TerminalMimeClipboardContent("text/plain", "selection paste".encodeToByteArray()))))
                } else native.read(location, mimeTypes, list, complete)
            }
        }
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE, mimeClipboard = backend)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = mutableListOf<String>()
        val subscription = session.onInput { output += it.toByteArray().decodeToString() }
        val confirmations = mutableListOf<Pair<String, (Boolean) -> Unit>>()
        view.pasteConfirmation = TerminalPasteConfirmation { text, answer ->
            confirmations += text to answer; TerminalDisposable {}
        }
        fun event(type: NSEventType, shift: Boolean = false): NSEvent {
            val event = assertNotNull(NSEvent.mouseEventWithType(type, view.convertPoint(NSMakePoint(20.0, 12.0), toView = null),
                if (shift) NSEventModifierFlagShift else 0uL, 0.0, window.windowNumber, null, 0, 1, 1.0f))
            if (type !in listOf(NSEventTypeOtherMouseDown, NSEventTypeOtherMouseDragged, NSEventTypeOtherMouseUp)) return event
            val cg = assertNotNull(event.CGEvent); CGEventSetIntegerValueField(cg, kCGMouseEventButtonNumber, 2)
            return assertNotNull(NSEvent.eventWithCGEvent(cg))
        }
        fun right(shift: Boolean = false) {
            view.rightMouseDown(event(NSEventTypeRightMouseDown, shift)); view.rightMouseUp(event(NSEventTypeRightMouseUp, shift))
        }
        fun middle() {
            view.otherMouseDown(event(NSEventTypeOtherMouseDown)); view.otherMouseDragged(event(NSEventTypeOtherMouseDragged))
            view.otherMouseUp(event(NSEventTypeOtherMouseUp))
        }
        fun text(value: String) { board.clearContents(); assertTrue(board.setString(value, NSPasteboardTypeString)) }
        try {
            session.write("alpha beta"); view.selectAll(null)
            val selected = assertNotNull(view.accessibilitySelectedText())
            middle(); assertTrue(reads.isEmpty(), "Default primary paste requires explicit host support")
            view.copyFormat = TerminalCopyFormat.VT
            view.rightClickAction = TerminalRightClickAction.IGNORE; right()
            assertEquals(selected, view.accessibilitySelectedText()); assertTrue(output.isEmpty())
            view.rightClickAction = TerminalRightClickAction.COPY; right()
            assertEquals("alpha beta", board.stringForType(NSPasteboardTypeString)); assertNotNull(board.stringForType(NSPasteboardTypeHTML))
            assertTrue(view.accessibilitySelectedText().isNullOrEmpty(), "Right copy clears regardless of clear-on-copy setting")
            text("leave"); right(); assertEquals("leave", board.stringForType(NSPasteboardTypeString)); assertTrue(reads.isEmpty())
            view.rightClickAction = TerminalRightClickAction.COPY_OR_PASTE
            view.selectAll(null); right(); assertEquals("alpha beta", board.stringForType(NSPasteboardTypeString))
            text("safe"); right(); assertEquals(listOf("safe"), output); output.clear()
            view.selectAll(null); view.rightClickAction = TerminalRightClickAction.PASTE
            text("danger\n"); right(); assertTrue(output.isEmpty()); assertEquals("danger\n", confirmations.last().first)
            assertTrue(view.accessibilitySelectedText().isNullOrEmpty(), "Right paste clears before confirmation")
            confirmations.last().second(false); assertTrue(output.isEmpty())
            view.selectAll(null); failRead = true; right(); failRead = false
            assertTrue(view.accessibilitySelectedText().isNullOrEmpty(), "A rejected right paste still clears")
            view.middleClickAction = TerminalMiddleClickAction.CLIPBOARD_PASTE
            view.selectAll(null); text("middle\n"); middle()
            assertEquals(selected, view.accessibilitySelectedText())
            session.write("\u001b[?2004h"); confirmations.last().second(true)
            assertEquals(listOf("\u001b[200~middle\n\u001b[201~"), output); output.clear()
            assertEquals(selected, view.accessibilitySelectedText(), "Middle paste retains selection after acceptance")
            view.middleClickAction = TerminalMiddleClickAction.IGNORE; val count = reads.size; middle(); assertEquals(count, reads.size)
            view.middleClickAction = TerminalMiddleClickAction.PRIMARY_PASTE; view.selectionClipboardSupported = true
            middle(); assertEquals(TerminalClipboardLocation.SELECTION, reads.last())
            assertEquals(listOf("\u001b[200~selection paste\u001b[201~"), output); output.clear()
            session.write("\u001b[?2004l\u001b[?1003h\u001b[?1006h")
            val beforeReports = reads.size; right(); middle()
            assertEquals(beforeReports, reads.size); assertEquals(4, output.size)
            assertTrue(output.all { it.startsWith("\u001b[<") }); output.clear()
            text("shift paste"); right(shift = true); assertEquals(listOf("shift paste"), output); output.clear()
            session.write("\u001b[?1003l")
            view.rightClickAction = TerminalRightClickAction.IGNORE
            view.rightMouseDown(event(NSEventTypeRightMouseDown)); session.write("\u001b[?1003h")
            view.rightMouseDragged(event(NSEventTypeRightMouseDragged)); view.rightMouseUp(event(NSEventTypeRightMouseUp))
            assertTrue(output.isEmpty(), "A local press cannot become an application drag/release")
            session.write("\u001b[?1003l\u001b[?5522h")
            middle(); assertEquals(TerminalClipboardLocation.SELECTION, reads.last())
            assertContains(output.single(), "loc=primary"); output.clear()
            session.write("\u001b[?5522l")
            view.middleClickAction = TerminalMiddleClickAction.CLIPBOARD_PASTE; delayRead = true; middle()
            val stale = assertNotNull(deferred); view.dispose()
            stale(TerminalMimeClipboardRead(contents = listOf(TerminalMimeClipboardContent("text/plain", "late".encodeToByteArray()))))
            assertTrue(output.isEmpty())
        } finally { subscription.dispose(); view.dispose(); window.close(); board.releaseGlobally() }
    }
}
