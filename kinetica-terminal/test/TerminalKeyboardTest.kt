package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalKeyboardTest {
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
        assertEquals("\r", s.encodeKey(TerminalKey("Enter", shift = true)))
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
