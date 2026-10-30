@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsSelectionPolicyTest {
    @Test fun typingCompositionEscapeAndKeyboardLockUseTheirOwnSelectionRules() = withView { session, view, window ->
        val output = StringBuilder()
        val observation = session.onInput { output.append(it.toByteArray().decodeToString()) }
        fun key(text: String, code: UShort, flags: ULong = 0uL, type: NSEventType = NSEventTypeKeyDown) {
            val event = assertNotNull(NSEvent.keyEventWithType(type, NSMakePoint(0.0, 0.0), flags, 0.0,
                window.windowNumber, null, text, text, false, code))
            if (type == NSEventTypeKeyUp) view.keyUp(event) else view.keyDown(event)
        }
        fun select() { view.selectAll(null); assertEquals("selected output", view.accessibilitySelectedText()) }
        try {
            session.write("selected output")
            for (mode in listOf("", "\u001b[>4;2m", "\u001b[>31u")) {
                session.write(mode); view.selectionClearOnTyping = false
                select(); key("\r", 36u); assertEquals("selected output", view.accessibilitySelectedText())
                view.insertText("typed", NSMakeRange(NSNotFound.toULong(), 0u))
                assertEquals("selected output", view.accessibilitySelectedText())
                view.setMarkedText("日", NSMakeRange(1u, 0u), NSMakeRange(NSNotFound.toULong(), 0u))
                assertEquals("selected output", view.accessibilitySelectedText())
                view.unmarkText(); assertEquals("selected output", view.accessibilitySelectedText())
                key("\u001b", 53u); assertNull(view.accessibilitySelectedText(), "Escape is unconditional when encoded")
                view.selectionClearOnTyping = true
                select(); key("\r", 36u); assertNull(view.accessibilitySelectedText())
                select(); view.insertText("", NSMakeRange(NSNotFound.toULong(), 0u)); assertNotNull(view.accessibilitySelectedText())
                view.setMarkedText("日", NSMakeRange(1u, 0u), NSMakeRange(NSNotFound.toULong(), 0u))
                assertNull(view.accessibilitySelectedText(), "Preedit clears immediately, before commitment")
                select(); view.unmarkText(); assertNull(view.accessibilitySelectedText(), "Ending an existing preedit applies the policy")
            }
            // A forwarded Command combination and a real Kitty release are input too.
            select(); key("a", 0u, NSEventModifierFlagCommand or NSEventModifierFlagShift)
            assertNull(view.accessibilitySelectedText())
            select(); key("a", 0u, NSEventModifierFlagCommand or NSEventModifierFlagShift, NSEventTypeKeyUp)
            assertNull(view.accessibilitySelectedText())
            select()
            view.flagsChanged(assertNotNull(NSEvent.keyEventWithType(NSEventTypeFlagsChanged, NSMakePoint(0.0, 0.0),
                NSEventModifierFlagShift, 0.0, window.windowNumber, null, "", "", false, 56u)))
            assertNotNull(view.accessibilitySelectedText(), "Pure modifiers preserve selection even when Kitty encodes them")
            session.keyboardLockAllowed = true; session.write("\u001b[2h"); output.clear()
            key("\u001b", 53u); view.insertText("blocked", NSMakeRange(NSNotFound.toULong(), 0u))
            assertNotNull(view.accessibilitySelectedText()); assertEquals("", output.toString())
            session.write("\u001b[2l")
            view.insertText("typed", NSMakeRange(NSNotFound.toULong(), 0u)); assertNull(view.accessibilitySelectedText())
        } finally { observation.dispose() }
    }

    @Test fun clearOnCopyRunsAtDispatchAndNeverClearsAnAutomaticOrLaterSelection() {
        val pending = mutableListOf<(TerminalClipboardResult) -> Unit>()
        var throwWrite = false
        val clipboard = object : TerminalMimeClipboard {
            override fun read(location: TerminalClipboardLocation, mimeTypes: List<String>, list: Boolean, complete: (TerminalMimeClipboardRead) -> Unit) =
                complete(TerminalMimeClipboardRead(TerminalClipboardResult.UNSUPPORTED))
            override fun write(location: TerminalClipboardLocation, contents: List<TerminalMimeClipboardContent>, complete: (TerminalClipboardResult) -> Unit) {
                if (throwWrite) error("Clipboard failed")
                pending += complete
            }
        }
        withView(clipboard) { session, view, _ ->
            session.write("selected output")
            view.selectAll(null); view.copy(null)
            assertEquals("selected output", view.accessibilitySelectedText(), "Default copy preserves selection")
            pending.removeAt(0)(TerminalClipboardResult.SUCCESS)
            view.selectionClearOnCopy = true; view.copy(null)
            assertNull(view.accessibilitySelectedText(), "Clear at dispatch, without waiting on clipboard I/O")
            view.selectAll(null); pending.removeAt(0)(TerminalClipboardResult.DENIED)
            assertEquals("selected output", view.accessibilitySelectedText(), "An old callback cannot clear a newer selection")
            view.copyOnSelect = TerminalCopyOnSelect.CLIPBOARD; view.selectAll(null)
            assertEquals("selected output", view.accessibilitySelectedText(), "Automatic copy ignores clear-on-copy")
            pending.removeAt(0)(TerminalClipboardResult.SUCCESS)
            throwWrite = true; view.copy(null)
            assertNull(view.accessibilitySelectedText(), "Ghostty clears after the attempt even if the host fails")
            view.copy(null); assertTrue(pending.isEmpty(), "Absent selection does not copy")
        }
    }

    private fun withView(clipboard: TerminalMimeClipboard? = null, block: (TerminalSession, AppKitTerminalView, NSWindow) -> Unit) {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE, mimeClipboard = clipboard)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        try { assertTrue(window.makeFirstResponder(view)); block(session, view, window) }
        finally { view.dispose(); window.close() }
    }
}
