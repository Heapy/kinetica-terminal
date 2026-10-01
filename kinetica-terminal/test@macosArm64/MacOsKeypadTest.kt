@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsKeypadTest {
    @Test fun russianLayoutPreservesControlAndCommandKeyIdentity() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val output = mutableListOf<String>()
        val subscription = session.onInput { output += it.toByteArray().decodeToString() }
        try {
            for ((code, text) in listOf(8 to "с", 0 to "ф", 6 to "я", 2 to "в", 33 to "х")) {
                view.keyDown(requireNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
                    NSEventModifierFlagControl, 0.0, 0, null, text, text, false, code.toUShort())))
            }
            assertEquals(listOf("\u0003", "\u0001", "\u001a", "\u0004", "\u001b"), output)
            output.clear()
            view.insertText("привет", NSMakeRange(NSNotFound.toULong(), 0u))
            assertEquals(listOf("привет"), output)
            assertEquals("v", terminalShortcutKey("м", 9))
            assertEquals("z", terminalShortcutKey("z", 16), "Latin layouts retain their logical key")
        } finally { subscription.dispose(); view.dispose() }
    }

    @Test fun nativeEventsPreserveKeypadIdentityAndOrdinaryEnter() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, optionAsMeta = true, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = mutableListOf<String>()
        val subscription = session.onInput { output.add(it.toByteArray().decodeToString()) }
        fun press(code: Int, chars: String, modifiers: ULong = 0u) {
            val event = requireNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
                modifiers, 0.0, window.windowNumber, null, chars, chars, false, code.toUShort()))
            view.keyDown(event)
        }
        try {
            assertTrue(window.makeFirstResponder(view))
            val codes = listOf(82, 83, 84, 85, 86, 87, 88, 89, 91, 92, 65, 75, 67, 78, 69, 76, 81, 95)
            val chars = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ",", "/", "*", "-", "+", "\u0003", "=", ",")
            codes.indices.forEach { press(codes[it], chars[it], NSEventModifierFlagNumericPad) }
            assertEquals(chars.map { if (it == "\u0003") "\r" else it }, output)
            output.clear()
            session.write("\u001b=\u001b[?1035l")
            codes.indices.forEach { press(codes[it], chars[it], NSEventModifierFlagNumericPad) }
            assertEquals("pqrstuvwxyno jmkMXl".replace(" ", "").map { "\u001bO$it" }, output)
            output.clear()
            press(36, "\r") // Return is not keypad Enter.
            press(76, "\u0003")
            press(83, "1", NSEventModifierFlagShift or NSEventModifierFlagControl or NSEventModifierFlagOption)
            press(126, "\uf700", NSEventModifierFlagNumericPad) // This flag also marks arrow keys.
            assertEquals(listOf("\r", "\u001bOM", "\u001bO8q", "\u001b[A"), output)
            session.write("\u001b[?1035h")
            press(83, "1")
            assertEquals("1", output.last())
        } finally { subscription.dispose(); view.dispose(); window.close() }
    }
}
