@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsKeyboardProtocolTest {
    @Test fun optionSidePolicyControlsTranslationAndExtendedKeyboardText() {
        NSApplication.sharedApplication()
        for (policy in AppKitTerminalOptionAsAlt.entries) {
            val session = TerminalSession()
            // Explicit policy overrides the legacy convenience switch.
            val view = AppKitTerminalView(session, optionAsMeta = true, rendering = TerminalRendering.SOFTWARE,
                optionAsAlt = policy)
            val window = NSWindow(NSMakeRect(0.0,0.0,640.0,320.0),NSWindowStyleMaskTitled,NSBackingStoreBuffered,false)
            window.setReleasedWhenClosed(false); window.contentView = view
            val output = StringBuilder()
            val observation = session.onInput { output.append(it.toByteArray().decodeToString()) }
            fun event(flags: ULong, text: String, logical: String, code: Int = 0,
                repeat: Boolean = false, release: Boolean = false) = requireNotNull(NSEvent.keyEventWithType(
                    if (release) NSEventTypeKeyUp else NSEventTypeKeyDown, NSMakePoint(0.0,0.0), flags,
                    0.0,window.windowNumber,null,text,logical,repeat,code.toUShort()))
            try {
                assertTrue(window.makeFirstResponder(view))
                // Both pressed resolves to right; synthetic aggregate-only Option resolves to left.
                for (side in listOf(0x20uL,0x40uL,0x60uL,0uL)) {
                    val selected = policy == AppKitTerminalOptionAsAlt.BOTH ||
                        policy == AppKitTerminalOptionAsAlt.RIGHT && side and 0x40uL != 0uL ||
                        policy == AppKitTerminalOptionAsAlt.LEFT && side and 0x40uL == 0uL
                    for (mode in listOf("legacy","modify2","kitty31")) {
                        session.reset()
                        session.write(when (mode) { "modify2" -> "\u001b[>4;2m"; "kitty31" -> "\u001b[>31u"; else -> "" })
                        for (shift in listOf(false,true)) for (repeat in listOf(false,true)) {
                            output.clear()
                            val flags = NSEventModifierFlagOption or side or (if (shift) NSEventModifierFlagShift else 0uL)
                            val text = if (shift) "Å" else "å"
                            val logical = if (shift) "A" else "a"
                            val press = event(flags,text,logical,repeat = repeat)
                            // AppKit translation depends on the user's current layout. Keep that
                            // layout unchanged and compare against its actual Option-free text.
                            val translated = if (selected) requireNotNull(press.charactersByApplyingModifiers(
                                flags and NSEventModifierFlagOption.inv())) else text
                            val unshifted = requireNotNull(press.charactersByApplyingModifiers(0uL))
                                .lowercase().terminalCodepoints().single()
                            view.keyDown(press)
                            view.keyUp(event(flags,text,logical,release = true))
                            val expected = when (mode) {
                                "legacy" -> if (selected) "\u001b" +
                                    (if (translated.length == 1 && translated[0].code < 128) translated else codePointString(unshifted))
                                    else translated
                                "modify2" -> if (selected) "\u001b[27;${if (shift) 4 else 3};${translated[0].code}~" else translated
                                else -> {
                                    val shifted = if (shift && translated[0].code != unshifted) ":${translated[0].code}" else ""
                                    val base = if (unshifted != 97 && translated != "a") (if (shifted.isEmpty()) "::97" else ":97") else ""
                                    val identity = "$unshifted$shifted$base"
                                    val mods = if (shift) 4 else 3
                                    "\u001b[$identity;$mods${if (repeat) ":2" else ""}${if (selected) "" else ";${translated[0].code}"}u" +
                                        "\u001b[$identity;$mods:3u"
                                }
                            }
                            assertEquals(expected.encodeToByteArray().toList(),output.toString().encodeToByteArray().toList(),
                                "$policy side=$side mode=$mode shift=$shift repeat=$repeat")
                        }
                    }
                    session.reset(); output.clear()
                    view.keyDown(event(NSEventModifierFlagOption or side,"\r","\r",36))
                    assertEquals("\u001b\r",output.toString(),"Option policy does not strip functional-key modifiers")
                }
                session.write("\u001b[>31u"); output.clear()
                view.setMarkedText("日",NSMakeRange(1u,0u),NSMakeRange(NSNotFound.toULong(),0u))
                view.insertText("日本",NSMakeRange(NSNotFound.toULong(),0u))
                assertEquals("日本",output.toString(),"IME commits keep text ownership for $policy")
            } finally { observation.dispose(); view.dispose(); window.close() }
        }
    }

    @Test fun altPrefixModeReachesOptionAsMetaWithoutChangingFunctionalKeys() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, optionAsMeta = true, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0,0.0,640.0,320.0),NSWindowStyleMaskTitled,NSBackingStoreBuffered,false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val out = StringBuilder()
        val observer = session.onInput { out.append(it.toByteArray().decodeToString()) }
        fun press(text: String = "a", raw: String = "a", code: Int = 0,
            flags: ULong = NSEventModifierFlagOption, repeat: Boolean = false) {
            view.keyDown(requireNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown,NSMakePoint(0.0,0.0),
                flags,0.0,window.windowNumber,null,text,raw,repeat,code.toUShort())))
        }
        val layoutA = requireNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown,NSMakePoint(0.0,0.0),
            NSEventModifierFlagOption,0.0,window.windowNumber,null,"a","a",false,0u))
            .charactersByApplyingModifiers(0uL).orEmpty()
        try {
            assertTrue(window.makeFirstResponder(view))
            press()
            session.write("\u001b[?1036l")
            press(); press("å"); press(repeat = true)
            press("\r","\r",36)
            press("\u0001",flags = NSEventModifierFlagOption or NSEventModifierFlagControl)
            assertEquals("\u001b${layoutA.repeat(4)}\u001b\r\u001b\u0001",out.toString(), "Option-as-Alt translates without Option even when mode 1036 suppresses the prefix")
            out.clear()
            session.write("\u001b[?1039h\u001b[!p")
            press()
            session.write("\u001bc")
            press()
            assertEquals("$layoutA\u001b$layoutA",out.toString())
        } finally { observer.dispose(); view.dispose(); window.close() }
    }

    @Test fun backarrowModeReachesNativeBackspaceWithoutChangingKittyEncoding() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0,0.0,640.0,320.0),NSWindowStyleMaskTitled,NSBackingStoreBuffered,false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val out = StringBuilder()
        val observer = session.onInput { out.append(it.toByteArray().decodeToString()) }
        fun backspace(flags: ULong = 0u) {
            view.keyDown(requireNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown,NSMakePoint(0.0,0.0),
                flags,0.0,window.windowNumber,null,"\u007f","\u007f",false,51u)))
        }
        try {
            assertTrue(window.makeFirstResponder(view))
            backspace(); backspace(NSEventModifierFlagControl)
            session.write("\u001b[?67h")
            backspace(); backspace(NSEventModifierFlagControl); backspace(NSEventModifierFlagShift)
            backspace(NSEventModifierFlagControl or NSEventModifierFlagShift)
            assertEquals("\u007f\b\b\u007f\u007f\b",out.toString())
            out.clear()
            session.write("\u001b[>4;2m")
            backspace(NSEventModifierFlagControl or NSEventModifierFlagShift)
            assertEquals("\u001b[27;6;127~",out.toString())
            out.clear()
            session.write("\u001b[>4n\u001b[>1u")
            backspace(); backspace(NSEventModifierFlagControl)
            assertEquals("\u007f\u001b[127;5u",out.toString())
            out.clear()
            session.write("\u001b[<u\u001b[!p")
            backspace()
            assertEquals("\b",out.toString(),"DECSTR preserves DECBKM")
            out.clear()
            session.write("\u001bc")
            backspace()
            assertEquals("\u007f",out.toString())
        } finally { observer.dispose(); view.dispose(); window.close() }
    }

    @Test fun optedInKeyboardLockBlocksNativeKeysAndImeCommitsUntilReset() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session,rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0,0.0,640.0,320.0),NSWindowStyleMaskTitled,NSBackingStoreBuffered,false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val out = StringBuilder()
        val observer = session.onInput { out.append(it.toByteArray().decodeToString()) }
        fun key(type: NSEventType = NSEventTypeKeyDown) = requireNotNull(NSEvent.keyEventWithType(
            type,NSMakePoint(0.0,0.0),0u,0.0,window.windowNumber,null,"a","a",false,0u))
        try {
            assertTrue(window.makeFirstResponder(view))
            session.write("\u001b[2h")
            view.insertText("default",NSMakeRange(NSNotFound.toULong(),0u))
            assertEquals("default",out.toString())
            session.keyboardLockAllowed = true; out.clear()
            for (mode in listOf("","\u001b[>4;2m","\u001b[>31u")) {
                session.write(mode)
                view.keyDown(key()); view.keyUp(key(NSEventTypeKeyUp))
                view.setMarkedText("日",NSMakeRange(1u,0u),NSMakeRange(NSNotFound.toULong(),0u))
                view.insertText("日本",NSMakeRange(NSNotFound.toULong(),0u))
                assertFalse(view.hasMarkedText())
            }
            assertEquals("",out.toString())
            session.write("\u001b[!p")
            view.insertText("restored",NSMakeRange(NSNotFound.toULong(),0u))
            assertEquals("restored",out.toString())
        } finally { observer.dispose(); view.dispose(); window.close() }
    }

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
            assertEquals("\u001b[27;2;13~", out.toString(), "Legacy PC-style table also distinguishes Shift+Enter")
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
