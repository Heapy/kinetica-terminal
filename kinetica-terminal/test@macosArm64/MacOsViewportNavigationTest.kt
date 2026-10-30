@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsViewportNavigationTest {
    private class Fixture {
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        val output = StringBuilder()
        private val subscription = session.onInput { output.append(it.toByteArray().decodeToString()) }
        var scrolls = 0
        init {
            window.setReleasedWhenClosed(false); window.contentView = view
            assertTrue(window.makeFirstResponder(view))
            view.onScrollActivity = { scrolls++ }
        }
        fun populate(protocol: String = "") {
            session.reset()
            repeat(session.rows * 3) { session.write("\u001b]133;A\u0007command-$it\r\n\u001b]133;C\u0007output-$it\r\n") }
            session.write(protocol)
            output.clear(); scrolls = 0
        }
        fun press(code: Int, flags: ULong = NSEventModifierFlagCommand, repeat: Boolean = false,
            chars: String = when (code) {
                115 -> "\uf729"; 119 -> "\uf72b"; 116 -> "\uf72c"; 121 -> "\uf72d"
                126 -> "\uf700"; 125 -> "\uf701"; else -> error("Specify characters for key $code")
            }) {
            for (type in listOf(NSEventTypeKeyDown, NSEventTypeKeyUp)) {
                window.sendEvent(requireNotNull(NSEvent.keyEventWithType(type, NSMakePoint(0.0, 0.0),
                    flags, 0.0, window.windowNumber, null, chars, chars, repeat && type == NSEventTypeKeyDown, code.toUShort())))
            }
        }
        fun dispose() { subscription.dispose(); view.dispose(); window.close() }
    }

    @Test fun ghosttyDefaultsNavigateHistoryAndPromptsWithoutApplicationInput() {
        NSApplication.sharedApplication()
        val fixture = Fixture()
        with(fixture) { try {
            for (protocol in listOf("", "\u001b[>4;2m", "\u001b[>31u")) {
                populate(protocol)
                val history = session.historySize
                press(116); assertEquals(session.rows, view.scrollOffset)
                press(116, repeat = true); assertEquals(session.rows * 2, view.scrollOffset)
                press(121); assertEquals(session.rows, view.scrollOffset)
                press(121, repeat = true); assertEquals(0, view.scrollOffset)
                press(115); assertEquals(history, view.scrollOffset)
                assertTrue((view.accessibilityValue() as String).startsWith("command-0\noutput-0"))
                val changes = scrolls
                press(115, repeat = true)
                assertEquals(changes, scrolls, "At the history boundary there is no scroll activity")
                press(119); assertEquals(0, view.scrollOffset)
                for (flags in listOf(NSEventModifierFlagCommand, NSEventModifierFlagCommand or NSEventModifierFlagShift)) {
                    press(126, flags)
                    val previous = (history - 1) / 2 * 2
                    assertEquals(history - previous, view.scrollOffset)
                    assertTrue((view.accessibilityValue() as String).startsWith("command-${previous / 2}\n"))
                    press(125, flags); assertEquals(0, view.scrollOffset)
                }
                assertEquals(view, window.firstResponder)
                assertEquals("", output.toString(), protocol)
            }
            // Navigation preserves the user's selection instead of acting like typed input.
            assertTrue(view.reveal(session.search("command-0").matches.single()))
            val selected = view.accessibilitySelectedText()
            press(119); press(115)
            assertEquals(selected, view.accessibilitySelectedText())
            assertEquals("", output.toString())
        } finally { dispose() } }
    }

    @Test fun navigationRespectsModifiersCompositionHoldsAndScreenOwnership() {
        NSApplication.sharedApplication()
        val fixture = Fixture()
        with(fixture) { try {
            populate("\u001b[>31u")
            for ((code, flags) in listOf(
                116 to NSEventModifierFlagShift,
                116 to (NSEventModifierFlagShift or NSEventModifierFlagControl),
                116 to (NSEventModifierFlagCommand or NSEventModifierFlagShift),
                115 to (NSEventModifierFlagCommand or NSEventModifierFlagOption),
                126 to (NSEventModifierFlagCommand or NSEventModifierFlagControl),
            )) {
                output.clear(); press(code, flags)
                assertEquals(0, view.scrollOffset)
                assertTrue(output.isNotEmpty(), "Unbound modifier combination must reach the application")
                assertTrue(output.contains(":3"), "Forwarded key retains its release")
            }
            output.clear(); press(92, chars = "\uf72c")
            assertEquals(0, view.scrollOffset, "Keypad identity takes priority over navigation-like text")
            assertTrue(output.isNotEmpty())
            output.clear()
            session.keyboardLockAllowed = true
            session.write("\u001b[2h")
            press(115); assertEquals(session.historySize, view.scrollOffset)
            press(119); assertEquals(0, view.scrollOffset)
            session.write("\u001b[2l\u001b[?2026h")
            press(115); press(126)
            assertEquals(0, view.scrollOffset, "Atomic presentation remains frozen")
            session.write("\u001b[?2026l\u001b[?1049h")
            press(115); press(126)
            assertEquals(0, view.scrollOffset, "Alternate screen does not reveal primary history")
            session.write("\u001b[?1049l")
            assertEquals("", output.toString())
            view.setMarkedText("preedit", NSMakeRange(7u, 0u), NSMakeRange(NSNotFound.toULong(), 0u))
            press(115)
            assertEquals(0, view.scrollOffset, "Navigation cannot interrupt marked text")
            view.unmarkText()
            session.reset(); session.write("plain\r\n".repeat(session.rows * 3) + "\u001b[>31u")
            output.clear(); press(126); press(125)
            assertEquals(0, view.scrollOffset)
            assertEquals("", output.toString(), "Missing prompt marks still consume the local binding")
        } finally { dispose() } }
    }
}
