package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalModifyOtherKeysTest {
    @Test fun numericModeEncodesModifiersBeforeControlMappingAndPreservesThePen() {
        val s = TerminalSession()
        s.write("\u001b[31m\u001b[>4;2mX")
        assertEquals(2, s.modifyOtherKeysMode)
        assertEquals(s.paletteColor(1), s.screenLine(0).foreground(0))
        assertEquals("\u001b[27;5;99~", s.encodeKey(TerminalKey("c", control = true)))
        assertEquals("\u001b[27;2;13~", s.encodeKey(TerminalKey("Enter", shift = true)))
        assertEquals("\u001b[27;5;13~", s.encodeKey(TerminalKey("Enter", control = true)))
        assertEquals("\u001b[27;2;9~", s.encodeKey(TerminalKey("Tab", shift = true)))
        assertEquals("\b", s.encodeKey(TerminalKey("Backspace", control = true)))
        assertEquals("\u001b[27;6;127~", s.encodeKey(TerminalKey("Backspace", control = true, shift = true)))
        assertEquals("\u001b[27;5;128512~", s.encodeKey(TerminalKey("😀", control = true)))
        assertEquals("", s.encodeKey(TerminalKey("Enter", action = TerminalKeyAction.RELEASE)))
        assertEquals("\u001b[27;2;13~", s.encodeKey(TerminalKey("Enter", shift = true, action = TerminalKeyAction.REPEAT)))
    }

    @Test fun negotiationIsGlobalKittyOverridesItAndResetRestoresLegacy() {
        val s = TerminalSession()
        s.write("\u001b[>4;2m\u001b[?1049h")
        assertEquals(2, s.modifyOtherKeysMode)
        s.write("\u001b[>1u")
        assertEquals("\u001b[13;2u", s.encodeKey(TerminalKey("Enter", shift = true)))
        s.write("\u001b[<u")
        assertEquals("\u001b[27;2;13~", s.encodeKey(TerminalKey("Enter", shift = true)))
        s.write("\u001b[>9;2m\u001b[>4;2;3m")
        assertEquals(2, s.modifyOtherKeysMode)
        for (disable in listOf("\u001b[>4m", "\u001b[>4;0m", "\u001b[>4;1m", "\u001b[>m", "\u001b[>4n")) {
            s.write(disable)
            assertEquals(0, s.modifyOtherKeysMode, disable)
            assertEquals("\u0003", s.encodeKey(TerminalKey("c", control = true)))
            s.write("\u001b[>4;2m")
        }
        s.reset()
        assertEquals(0, s.modifyOtherKeysMode)
    }

    @Test fun layoutTextAndImeKeepTheirOwnMeaning() {
        val s = TerminalSession()
        s.write("\u001b[>4;2m")
        assertEquals("\u001b[27;2;65~", s.encodeKey(TerminalKey("A", shift = true, text = "A", consumedModifiers = 1)))
        assertEquals("!", s.encodeKey(TerminalKey("!", shift = true, text = "!", consumedModifiers = 1)))
        assertEquals("å", s.encodeKey(TerminalKey("a", alt = true, text = "å", consumedModifiers = 2)))
        assertEquals("日本", s.encodeKey(TerminalKey("Enter", text = "日本")))
        assertEquals("", s.encodeKey(TerminalKey("Backspace", text = "日本")))
        assertEquals("1", s.encodeKey(TerminalKey("1", control = true, keypad = TerminalKeypadKey.ONE)))
    }
}
