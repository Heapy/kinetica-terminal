@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsEditingShortcutTest {
    @Test fun localEditingRequiresExactCommandModifiers() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        var clipboardReads = 0
        val clipboard = object : TerminalMimeClipboard {
            override fun read(location: TerminalClipboardLocation, mimeTypes: List<String>, list: Boolean,
                complete: (TerminalMimeClipboardRead) -> Unit) {
                clipboardReads++
                complete(TerminalMimeClipboardRead(available = listOf("text/plain")))
            }
            override fun write(location: TerminalClipboardLocation, contents: List<TerminalMimeClipboardContent>,
                complete: (TerminalClipboardResult) -> Unit) = complete(TerminalClipboardResult.SUCCESS)
        }
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE,
            mimeClipboard = clipboard, mimeClipboardPolicy = TerminalMimeClipboardPolicy.WRITE_AND_PASTE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = StringBuilder()
        val subscription = session.onInput { output.append(it.toByteArray().decodeToString()) }
        fun press(text: String, flags: ULong = NSEventModifierFlagCommand) {
            val code = when (text) { "a" -> 0; "c" -> 8; "v" -> 9; "k" -> 40; else -> error(text) }
            for ((type, repeat) in listOf(NSEventTypeKeyDown to false, NSEventTypeKeyDown to true, NSEventTypeKeyUp to false)) {
                window.sendEvent(requireNotNull(NSEvent.keyEventWithType(type, NSMakePoint(0.0, 0.0), flags,
                    0.0, window.windowNumber, null, text, text, repeat, code.toUShort())))
            }
        }
        try {
            assertTrue(window.makeFirstResponder(view))
            session.write("native output\u001b[>31u\u001b[?5522h")
            assertTrue(session.clipboardPasteEventsEnabled, "All paste acquisition uses the isolated fake host")
            press("a")
            assertTrue(view.accessibilitySelectedText().orEmpty().startsWith("native output"))
            assertEquals("", output.toString(), "Select all does not send Kitty events")
            view.clearSelection()
            for (key in listOf("a", "c", "v", "k")) for (extra in listOf(
                NSEventModifierFlagControl, NSEventModifierFlagOption, NSEventModifierFlagShift,
            )) {
                if (key == "k" && extra == NSEventModifierFlagShift) continue // Existing clear-history binding.
                output.clear(); press(key, NSEventModifierFlagCommand or extra)
                assertTrue(output.isNotEmpty(), "Modified $key is application input")
                assertTrue(output.contains(":2") && output.contains(":3"), "Forwarded $key keeps repeat/release")
                assertNull(view.accessibilitySelectedText(), "Modified A does not select output")
                assertTrue((view.accessibilityValue() as String).startsWith("native output"), "Modified K does not clear output")
                assertEquals(0, clipboardReads, "Modified V must not acquire clipboard contents")
            }
            // Plain Cmd+V still invokes the clipboard gesture; neither OS clipboard nor shell is used.
            output.clear(); press("v")
            assertEquals(2, clipboardReads, "Press and repeat each request the clipboard metadata")
            assertTrue(output.contains("\u001b]5522;"))
            assertFalse(output.contains("\u001b[118;"))
        } finally { subscription.dispose(); view.dispose(); window.close() }
    }
}
