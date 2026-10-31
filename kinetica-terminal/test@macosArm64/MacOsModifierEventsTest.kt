@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsModifierEventsTest {
    @Test fun matchesOriginalGhosttySwiftActionsForEveryModifierFlagCombination() = withView { session, view, window ->
        val codes = listOf(56, 60, 59, 62, 58, 61, 55, 54, 57, 0)
        val kitty = listOf(57441, 57447, 57442, 57448, 57443, 57449, 57444, 57450, 57358, 0)
        val output = StringBuilder()
        val observation = session.onInput { output.append(it.toByteArray().decodeToString()) }
        var marked = false
        try {
            session.write("\u001b[>31u")
            for (row in nativeModifierEventFixtures.lineSequence()) {
                val values = row.split(',').map(String::toInt)
                val flags = values[0].toULong()
                if (values[1] == 1 && !marked) {
                    view.setMarkedText("日", NSMakeRange(1u, 0u), NSMakeRange(NSNotFound.toULong(), 0u)); marked = true
                }
                val mods = (if (flags and NSEventModifierFlagShift != 0uL) 1 else 0) or
                    (if (flags and NSEventModifierFlagOption != 0uL) 2 else 0) or
                    (if (flags and NSEventModifierFlagControl != 0uL) 4 else 0) or
                    (if (flags and NSEventModifierFlagCommand != 0uL) 8 else 0) or
                    (if (flags and NSEventModifierFlagCapsLock != 0uL) 64 else 0)
                for ((index, code) in codes.withIndex()) {
                    output.clear()
                    view.flagsChanged(event(window, code, flags))
                    val action = values[index + 2]
                    val expected = if (action < 0) "" else "\u001b[${kitty[index]}" +
                        (if (action == 0) ";${mods + 1}:3" else if (mods != 0) ";${mods + 1}" else "") + "u"
                    assertBytes(expected, output.toString(), "key=$code flags=$flags preedit=$marked")
                }
            }
        } finally { observation.dispose() }
    }

    @Test fun repeatedFlagsAndLateReleaseAfterFocusLossDoNotInvertTheAction() = withView { session, view, window ->
        val output = StringBuilder()
        val observation = session.onInput { output.append(it.toByteArray().decodeToString()) }
        try {
            session.write("\u001b[>31u")
            // Repeated notifications are still presses; no historical toggle determines action.
            repeat(2) { view.flagsChanged(event(window, 56, NSEventModifierFlagShift or 0x2uL)) }
            assertBytes("\u001b[57441;2u\u001b[57441;2u", output.toString())
            view.resignFirstResponder(); view.becomeFirstResponder(); output.clear()
            view.flagsChanged(event(window, 56, 0uL))
            assertBytes("\u001b[57441;1:3u", output.toString(), "A delayed release must not become a new press")
            output.clear()
            // Right Shift release while left Shift remains down uses its device-side bit.
            view.flagsChanged(event(window, 60, NSEventModifierFlagShift or 0x6uL))
            view.flagsChanged(event(window, 60, NSEventModifierFlagShift or 0x2uL))
            assertBytes("\u001b[57447;2u\u001b[57447;2:3u", output.toString())
        } finally { observation.dispose() }
    }

    @Test fun preeditOwnsModifierEventsUntilCompositionEnds() = withView { session, view, window ->
        val output = StringBuilder()
        val observation = session.onInput { output.append(it.toByteArray().decodeToString()) }
        try {
            session.write("\u001b[>31u")
            view.setMarkedText("日", NSMakeRange(1u, 0u), NSMakeRange(NSNotFound.toULong(), 0u))
            view.flagsChanged(event(window, 56, NSEventModifierFlagShift))
            view.flagsChanged(event(window, 56, 0uL))
            assertBytes("", output.toString())
            view.unmarkText()
            view.flagsChanged(event(window, 56, 0uL))
            assertBytes("\u001b[57441;1:3u", output.toString())
        } finally { observation.dispose() }
    }

    private fun assertBytes(expected: String, actual: String, message: String? = null) =
        assertEquals(expected.encodeToByteArray().toList(), actual.encodeToByteArray().toList(), message)

    private fun event(window: NSWindow, code: Int, flags: ULong) = assertNotNull(NSEvent.keyEventWithType(
        NSEventTypeFlagsChanged, NSMakePoint(0.0, 0.0), flags, 0.0, window.windowNumber, null, "", "", false, code.toUShort()))

    private fun withView(test: (TerminalSession, AppKitTerminalView, NSWindow) -> Unit) {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        try { assertTrue(window.makeFirstResponder(view)); test(session, view, window) }
        finally { view.dispose(); window.close() }
    }
}
