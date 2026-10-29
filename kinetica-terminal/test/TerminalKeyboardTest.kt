package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalKeyboardTest {
    @Test fun altPrefixSupportsScalarFallbackAndRetainsHostTextOwnership() {
        val session = TerminalSession()
        assertNull(session.encodeKey(TerminalKey("a")), "Unmodified typing remains owned by IME")
        assertEquals("\u001ba", session.encodeKey(TerminalKey("a", alt = true)))
        assertEquals("\u001b😀", session.encodeKey(TerminalKey("😀", alt = true)))
        assertEquals("\u001b界", session.encodeKey(TerminalKey("Unidentified", alt = true, text = "", unshiftedCodepoint = 0x754c)))
        assertEquals("å", session.encodeKey(TerminalKey("a", alt = true, text = "å", consumedModifiers = 2)))
        session.write("\u001b[?1036l")
        assertFalse(session.altEscapePrefix)
        assertEquals("å", session.encodeKey(TerminalKey("a", alt = true, text = "å", unshiftedCodepoint = 97)))
        assertEquals("", session.encodeKey(TerminalKey("a", alt = true, text = "", unshiftedCodepoint = 97)))
        assertEquals("\u001b\u0001", session.encodeKey(TerminalKey("a", alt = true, control = true)))
        assertEquals("\u001b\r", session.encodeKey(TerminalKey("Enter", alt = true)))
        session.write("\u001b[?1039h\u001b[!p")
        assertFalse(session.altEscapePrefix)
        session.reset()
        assertTrue(session.altEscapePrefix)
        assertTrue(session.sendReceiveMode)
    }

    @Test fun keyboardLockRequiresHostOptInAndOnlySuppressesTyping() {
        val session = TerminalSession()
        val out = StringBuilder()
        session.onInput { out.append(it.toByteArray().decodeToString()) }
        session.write("\u001b[2h")
        assertTrue(session.keyboardDisabled)
        assertFalse(session.keyboardInputBlocked)
        assertTrue(session.sendKey(TerminalKey("Enter")))
        session.sendText("日本")
        assertEquals("\r日本",out.toString())
        session.keyboardLockAllowed = true
        out.clear()
        for (mode in listOf("","\u001b[>4;2m","\u001b[>31u")) {
            session.write(mode)
            for (action in TerminalKeyAction.entries) for (key in listOf("Enter","a","ArrowUp","Control"))
                assertTrue(session.sendKey(TerminalKey(key,action = action)))
            session.sendText("committed IME")
        }
        assertEquals("",out.toString())
        assertNotNull(session.encodeKey(TerminalKey("Enter")),"Pure encoding remains independent of host KAM policy")
        session.write("\u001b[?2004h\u001b[?1004h\u001b[2\$p")
        session.paste("paste"); session.sendFocus(true); session.sendInput("raw")
        assertEquals("\u001b[2;1\$y\u001b[200~paste\u001b[201~\u001b[Iraw",out.toString())
        session.keyboardLockAllowed = false
        out.clear(); session.sendText("unlocked")
        assertEquals("unlocked",out.toString())
        session.keyboardLockAllowed = true
        session.write("\u001b[!p")
        assertFalse(session.keyboardDisabled); assertTrue(session.keyboardLockAllowed)
        session.sendText("soft")
        session.write("\u001b[2h\u001bc")
        assertFalse(session.keyboardDisabled); assertTrue(session.keyboardLockAllowed)
        session.sendText("hard")
        assertEquals("unlockedsofthard",out.toString())
    }

    @Test fun negotiationStacksBelongToScreensAndReset() {
        val s = TerminalSession()
        val out = StringBuilder()
        s.onInput { out.append(it.toByteArray().decodeToString()) }
        s.write("\u001b[?u\u001b[>1u\u001b[=2;2u\u001b[?u\u001b[=1;3u\u001b[?u")
        assertEquals("\u001b[?0u\u001b[?3u\u001b[?2u", out.toString())
        s.write("\u001b[?1049h")
        assertEquals(0, s.kittyKeyboardFlags)
        s.write("\u001b[>31u\u001b[?1049l")
        assertEquals(2, s.kittyKeyboardFlags)
        s.write("\u001b[?1049h")
        assertEquals(31, s.kittyKeyboardFlags)
        s.write("\u001b[<u")
        assertEquals(0, s.kittyKeyboardFlags)
        s.reset()
        assertEquals(0, s.kittyKeyboardFlags)
        s.write("\u001b[?1049h")
        assertEquals(0, s.kittyKeyboardFlags)
    }

    @Test fun boundedStackAndInvalidFlags() {
        val s = TerminalSession()
        for (n in 1..12) s.write("\u001b[>${n}u")
        for (n in 12 downTo 5) { assertEquals(n, s.kittyKeyboardFlags); s.write("\u001b[<u") }
        assertEquals(0, s.kittyKeyboardFlags)
        s.write("\u001b[>7u\u001b[<0u\u001b[=31;0u\u001b[>32u\u001b[=32u")
        assertEquals(7, s.kittyKeyboardFlags)
        s.write("\u001b[<65535u")
        assertEquals(0, s.kittyKeyboardFlags)
    }

    @Test fun enterModifiersAndLegacyRestoration() {
        val s = TerminalSession()
        s.write("\u001b[>1u")
        assertEquals("\r", s.encodeKey(TerminalKey("Enter")))
        assertEquals("\u001b[13;2u", s.encodeKey(TerminalKey("Enter", shift = true)))
        assertEquals("\u001b[13;5u", s.encodeKey(TerminalKey("Enter", control = true)))
        assertEquals("\u001b[99;5u", s.encodeKey(TerminalKey("c", control = true)))
        assertEquals("\u001b[27u", s.encodeKey(TerminalKey("Escape")))
        s.write("\u001b[<u")
        assertEquals("\u001b[27;2;13~", s.encodeKey(TerminalKey("Enter", shift = true)))
        assertEquals("\u0003", s.encodeKey(TerminalKey("c", control = true)))
    }

    @Test fun allEnhancementsPreserveUnicodeAndReleaseNeverInsertsText() {
        val s = TerminalSession()
        s.write("\u001b[>31u")
        val key = TerminalKey("Ф", shift = true, text = "Ф", unshiftedCodepoint = 'ф'.code,
            baseCodepoint = 'a'.code, consumedModifiers = 1)
        assertEquals("\u001b[1092:1060:97;2;1060u", s.encodeKey(key))
        assertEquals("\u001b[1092:1060:97;2:2;1060u", s.encodeKey(key.copy(action = TerminalKeyAction.REPEAT)))
        assertEquals("\u001b[1092:1060:97;2:3u", s.encodeKey(key.copy(action = TerminalKeyAction.RELEASE)))
        assertEquals("\u001b[128512;;128512u", s.encodeKey(TerminalKey("😀")))
        assertEquals("\u001b[57442;5u", s.encodeKey(TerminalKey("Control", control = true, physicalCode = "ControlLeft")))
        assertEquals("\u001b[1;1:1A", s.encodeKey(TerminalKey("ArrowUp")))
        assertEquals("\u001b[13~", s.encodeKey(TerminalKey("F3")))
        assertEquals("\u001b[57414u", s.encodeKey(TerminalKey("Enter", keypad = TerminalKeypadKey.ENTER)))
    }

    @Test fun textAndRecoveryKeysRespectEventFlags() {
        val s = TerminalSession()
        s.write("\u001b[>3u")
        assertEquals("A", s.encodeKey(TerminalKey("A", shift = true, consumedModifiers = 1)))
        assertEquals("", s.encodeKey(TerminalKey("Enter", action = TerminalKeyAction.RELEASE)))
        assertEquals("\u001b[97;1:3u", s.encodeKey(TerminalKey("a", action = TerminalKeyAction.RELEASE)))
        s.write("\u001b[=1u")
        val output = mutableListOf<TerminalInputData>()
        s.onInput { output += it }
        assertTrue(s.sendKey(TerminalKey("a", action = TerminalKeyAction.RELEASE)))
        assertTrue(output.isEmpty())
    }
}
