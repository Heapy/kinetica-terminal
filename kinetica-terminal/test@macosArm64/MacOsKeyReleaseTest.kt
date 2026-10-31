@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsKeyReleaseTest {
    @Test fun releasesUseCurrentLayoutAndNoPressTextFor720OriginalEncoderCases() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val out = StringBuilder()
        val observer = session.onInput { out.append(it.toByteArray().decodeToString()) }
        fun event(code: Int, text: String, flags: ULong, release: Boolean) = assertNotNull(NSEvent.keyEventWithType(
            if (release) NSEventTypeKeyUp else NSEventTypeKeyDown, NSMakePoint(0.0, 0.0), flags,
            0.0, window.windowNumber, null, text, text, false, code.toUShort()))
        try {
            assertTrue(window.makeFirstResponder(view))
            for (row in nativeKeyReleaseFixtures.lineSequence()) {
                val v = row.split(',')
                val kitty = v[0].toInt(); val mods = v[1].toInt(); val code = v[2].toInt(); val current = v[3].toInt()
                session.reset(); session.write("\u001b[>${kitty}u")
                val text = when (code) { 0 -> "A"; 18 -> "!"; 41 -> ":"; 36 -> "\r"; 126 -> "\uf700"; else -> "1" }
                val shifted = code in listOf(0, 18, 41)
                view.keyDown(event(code, text, if (shifted) NSEventModifierFlagShift else 0uL, false))
                out.clear()
                val flags = (if (mods and 1 != 0) NSEventModifierFlagShift else 0uL) or
                    (if (mods and 2 != 0) NSEventModifierFlagOption else 0uL) or
                    (if (mods and 4 != 0) NSEventModifierFlagControl else 0uL) or
                    (if (mods and 8 != 0) NSEventModifierFlagCommand else 0uL) or
                    (if (mods and 64 != 0) NSEventModifierFlagCapsLock else 0uL)
                // Supply the value AppKit would return after a layout switch, without
                // changing global input sources or relying on this machine's layout.
                view.releaseTerminalKey(event(code, text, flags, true), unshiftedCodepoint = current)
                val expected = v[4].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                assertContentEquals(expected, out.toString().encodeToByteArray(), row)
                out.clear()
                view.releaseTerminalKey(event(code, text, flags, true), unshiftedCodepoint = current)
                assertEquals("", out.toString(), "Already released key remains local: $row")
            }
        } finally { observer.dispose(); view.dispose(); window.close() }
    }
}
