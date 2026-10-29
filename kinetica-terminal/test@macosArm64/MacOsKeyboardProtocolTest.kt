@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsKeyboardProtocolTest {
    @Test fun nativeEventsSendKittyModifiersRepeatReleaseAndKeepImeText() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val out = StringBuilder()
        val subscription = session.onInput { out.append(it.toByteArray().decodeToString()) }
        fun event(code: Int, chars: String, flags: ULong = 0u, repeat: Boolean = false, release: Boolean = false,
            unmodified: String = chars): NSEvent =
            requireNotNull(NSEvent.keyEventWithType(if (release) NSEventTypeKeyUp else NSEventTypeKeyDown,
                NSMakePoint(0.0, 0.0), flags, 0.0, window.windowNumber, null, chars, unmodified, repeat, code.toUShort()))
        try {
            assertTrue(window.makeFirstResponder(view))
            session.write("\u001b[>3u")
            view.keyDown(event(36, "\r"))
            view.keyDown(event(36, "\r", NSEventModifierFlagShift))
            view.keyDown(event(36, "\r", NSEventModifierFlagControl))
            view.keyDown(event(126, "\uf700", repeat = true))
            view.keyUp(event(126, "\uf700", release = true))
            assertEquals("\r\u001b[13;2u\u001b[13;5u\u001b[1;1:2A\u001b[1;1:3A", out.toString())
            out.clear()
            session.write("\u001b[=31u")
            view.keyDown(event(0, "a"))
            view.keyUp(event(0, "a", release = true))
            assertEquals("\u001b[97;;97u\u001b[97;1:3u".encodeToByteArray().toList(), out.toString().encodeToByteArray().toList())
            out.clear()
            view.keyDown(event(0, "ф"))
            view.keyUp(event(0, "ф", release = true))
            assertEquals("\u001b[1092::97;;1092u\u001b[1092::97;1:3u".encodeToByteArray().toList(),
                out.toString().encodeToByteArray().toList())
            out.clear()
            view.setMarkedText("日本", NSMakeRange(0u, 2u), NSMakeRange(NSNotFound.toULong(), 0u))
            view.insertText("日本", NSMakeRange(NSNotFound.toULong(), 0u))
            assertEquals("日本", out.toString())
            out.clear()
            session.write("\u001b[<u")
            view.keyDown(event(36, "\r", NSEventModifierFlagShift))
            assertEquals("\r", out.toString())
            out.clear()
            session.write("\u001b[>4;2m")
            view.keyDown(event(36, "\r", NSEventModifierFlagShift))
            view.keyDown(event(8, "\u0003", NSEventModifierFlagControl, unmodified = "c"))
            view.keyDown(event(8, "\u0003", NSEventModifierFlagControl, repeat = true, unmodified = "c"))
            view.keyUp(event(8, "\u0003", NSEventModifierFlagControl, release = true, unmodified = "c"))
            assertEquals("\u001b[27;2;13~\u001b[27;5;99~\u001b[27;5;99~", out.toString())
            out.clear()
            session.write("\u001b[>4n")
            view.keyDown(event(8, "\u0003", NSEventModifierFlagControl, unmodified = "c"))
            assertEquals("\u0003", out.toString())
            out.clear()
            session.write("\u001b[?996n\u001b[?2031h")
            session.configureTheme(TerminalTheme(background = 0xffffff))
            session.write("\u001b]11;#000000\u0007\u001b[?996n\u001b[?2031l")
            session.configureTheme(TerminalTheme(background = 0x1e1e1e))
            assertEquals("\u001b[?997;1n\u001b[?997;2n\u001b[?997;2n", out.toString())
        } finally { subscription.dispose(); view.dispose(); window.close() }
    }
}
