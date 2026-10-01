package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalKeypadTest {
    @Test fun numericKeypadKeepsLayoutAndDoesNotApplyMainKeyboardControlMappings() {
        val session = TerminalSession()
        assertNull(session.encodeKey(TerminalKey("2"))) // The platform commits ordinary text.
        assertEquals("\u0000", session.encodeKey(TerminalKey("2", control = true)))
        assertEquals("2", session.encodeKey(TerminalKey("2", control = true, keypad = TerminalKeypadKey.TWO)))
        assertEquals(",", session.encodeKey(TerminalKey(",", keypad = TerminalKeypadKey.DECIMAL)))
        session.write("\u001b=\u001b[?1035l")
        assertNull(session.encodeKey(TerminalKey("2")))
        assertEquals("\u001bOr", session.encodeKey(TerminalKey("2", keypad = TerminalKeypadKey.TWO)))
        assertEquals("\u001bOn", session.encodeKey(TerminalKey(",", keypad = TerminalKeypadKey.DECIMAL)))
    }

    @Test fun keypadEqualAndSeparatorFollowVt220Sequences() {
        // XTerm's VT220 keypad table covers these keys; the pinned Ghostty encoder has
        // no application entries for them. This is an explicit compatibility extension.
        val session = TerminalSession()
        val equal = TerminalKey("=", keypad = TerminalKeypadKey.EQUAL)
        val comma = TerminalKey(",", keypad = TerminalKeypadKey.SEPARATOR)
        assertEquals("=", session.encodeKey(equal)); assertEquals(",", session.encodeKey(comma))
        session.write("\u001b=\u001b[?1035l")
        assertEquals("\u001bOX", session.encodeKey(equal)); assertEquals("\u001bOl", session.encodeKey(comma))
        assertEquals("\u001bO8X", session.encodeKey(equal.copy(shift = true, alt = true, control = true)))
        assertNull(session.encodeKey(equal.copy(meta = true)))
    }

    @Test fun numericEnterHonorsLinefeedModeAndApplicationEnterStaysDistinct() {
        val session = TerminalSession()
        val enter = TerminalKey("Enter", keypad = TerminalKeypadKey.ENTER)
        session.write("\u001b[20h")
        assertEquals("\r\n", session.encodeKey(enter))
        session.write("\u001b=\u001b[?1035l")
        assertEquals("\u001bOM", session.encodeKey(enter))
        assertEquals("\r\n", session.encodeKey(TerminalKey("Enter")))
        session.reset()
        assertFalse(session.applicationKeypad); assertTrue(session.keypadNumericOverride)
        assertEquals("\r", session.encodeKey(enter))
    }
}
