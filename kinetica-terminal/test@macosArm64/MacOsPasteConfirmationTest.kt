@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsPasteConfirmationTest {
    @Test fun disposedViewCannotReacquireFocusWhileSheetCloses() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = mutableListOf<String>()
        val subscription = session.onInput { output += it.toByteArray().decodeToString() }
        view.readPasteText = { "pending\n" }
        try {
            assertTrue(window.makeFirstResponder(view))
            session.write("\u001b[?1004h"); view.paste(null)
            assertNotNull(window.attachedSheet)
            output.clear(); view.dispose()
            assertFalse(view.acceptsFirstResponder()); assertFalse(view.becomeFirstResponder())
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
            assertTrue(output.isEmpty(), "Closing a disposed view's prompt must not emit focus or paste input")
        } finally { subscription.dispose(); view.dispose(); window.close() }
    }

    @Test fun clipboardGesturePreservesSelectionUntilApprovalAndUsesCurrentMode() {
        NSApplication.sharedApplication()
        val session = TerminalSession(80, 5)
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = mutableListOf<String>()
        val subscription = session.onInput { output += it.toByteArray().decodeToString() }
        var text = "one\r\ntwo"
        var reads = 0
        view.readPasteText = { reads++; text }
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val previews = mutableListOf<String>()
        var dismissed = 0
        view.pasteConfirmation = TerminalPasteConfirmation { value, complete ->
            previews += value; callbacks += complete
            TerminalDisposable { dismissed++; complete(true) }
        }
        fun commandV() {
            window.sendEvent(assertNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
                NSEventModifierFlagCommand, 0.0, window.windowNumber, null, "v", "v", false, 9u)))
        }
        try {
            assertTrue(window.makeFirstResponder(view))
            session.write((0..30).joinToString("\r\n") { "line $it" })
            view.scrollTo(10); view.selectAll(null)
            val selected = assertNotNull(view.accessibilitySelectedText())
            commandV()
            assertEquals(listOf(text), previews); assertEquals(1, reads)
            assertTrue(output.isEmpty()); assertEquals(selected, view.accessibilitySelectedText()); assertEquals(10, view.scrollOffset)
            callbacks.last()(false); callbacks.last()(true)
            assertTrue(output.isEmpty()); assertEquals(selected, view.accessibilitySelectedText()); assertEquals(10, view.scrollOffset)
            view.paste(null)
            session.write("\u001b[?2004h")
            callbacks.last()(true); callbacks.last()(true)
            assertEquals(listOf("\u001b[200~one\r\ntwo\u001b[201~"), output)
            assertNull(view.accessibilitySelectedText()); assertEquals(0, view.scrollOffset); assertEquals(2, dismissed)
            // A bracketed multiline paste does not prompt; an embedded closing frame still does.
            view.paste(null); assertEquals(2, previews.size)
            text = "before\u001b[201~after"; view.paste(null)
            assertEquals(text, previews.last()); assertEquals(2, output.size)
            window.contentView = null; window.contentView = view
            callbacks.last()(true); assertEquals(2, output.size)
            view.paste(null); view.dispose(); callbacks.last()(true); view.paste(null)
            assertEquals(2, output.size); assertEquals(4, dismissed)
        } finally { subscription.dispose(); view.dispose(); window.close() }
    }

    @Test fun nativePreviewAndSheetButtonsAcceptOrCancelWithoutClipboardAccess() {
        NSApplication.sharedApplication()
        val text = "echo '<b>界😀</b>'\r\n" + "a".repeat(3000)
        val alert = nativePasteAlert(text)
        val buttons = alert.buttons.filterIsInstance<NSButton>()
        assertEquals(listOf("Cancel", "Paste"), buttons.map { it.title })
        assertEquals("\u001b", buttons.first().keyEquivalent)
        assertNotEquals("\r", buttons.last().keyEquivalent)
        val preview = assertIs<NSTextView>(assertIs<NSScrollView>(alert.accessoryView).documentView)
        assertEquals(text, preview.string); assertFalse(preview.editable); assertFalse(preview.richText); assertTrue(preview.selectable)
        var answer: Boolean? = null
        presentNativePaste(null, text) { answer = it }.dispose()
        assertEquals(false, answer)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false)
        try {
            for ((response, expected) in listOf(NSAlertFirstButtonReturn to false, NSAlertSecondButtonReturn to true)) {
                answer = null
                val prompt = presentNativePaste(window, text) { answer = it }
                val sheet = assertNotNull(window.attachedSheet)
                window.endSheet(sheet, response)
                val deadline = NSDate.dateWithTimeIntervalSinceNow(2.0)
                while (answer == null && deadline.timeIntervalSinceNow > 0) {
                    NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
                }
                assertEquals(expected, answer)
                prompt.dispose(); sheet.orderOut(null)
            }
            answer = null
            val prompt = presentNativePaste(window, text) { answer = it }
            assertNotNull(window.attachedSheet); prompt.dispose(); prompt.dispose()
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
            assertNull(answer, "Disposal closes a sheet without approving its contents")
        } finally { window.close() }
    }
}
