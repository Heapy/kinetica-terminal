package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalSoftResetTest {
    @Test fun softResetUsesHostCursorDefaultsAndPreservesSynchronizedPresentation() {
        val session = TerminalSession(12,4,cursorStyle = TerminalCursorStyle.BAR,cursorBlink = true)
        val changes = mutableListOf<TerminalChange>()
        val observation = session.observe(changes::add)
        try {
            session.write("\u001b[2 q\u001b[?25l\u001b[?2004h\u001b[>4;2m\u001b[>31uold")
            session.write("\u001b[?2026h")
            changes.clear()
            session.write("\u001b[!pnew")
            assertTrue(session.synchronizedOutput)
            assertTrue(changes.isEmpty(),"DECSTR must not release a synchronized frame")
            assertEquals(TerminalCursorStyle.BAR,session.cursorStyle)
            assertTrue(session.cursorBlink); assertTrue(session.cursorVisible)
            assertTrue(session.bracketedPaste); assertEquals(31,session.kittyKeyboardFlags)
            assertEquals(0,session.modifyOtherKeysMode)
            session.write("\u001b[?2026l")
            assertEquals("oldnew",session.screenLine(0).text())
            assertTrue(changes.any { it.full },"Releasing the hold must publish the palette and cursor reset")
        } finally { observation.dispose() }
    }

    @Test fun softResetRecolorsExistingCellsWithoutEndingTheirHyperlink() {
        val session = TerminalSession(12,4)
        session.write("\u001b]4;1;#123456\u0007\u001b]10;#abcdef\u0007" +
            "\u001b]8;id=kept;https://example.com\u001b\\\u001b[31;4:3;58;5;2mA")
        val line = session.screenLine(0)
        assertEquals(0x123456,line.foreground(0))
        session.write("\u001b[!pB")
        assertSame(line,session.screenLine(0))
        assertEquals(terminalPalette(1),line.foreground(0))
        assertEquals(0xabcdef,session.theme.foreground)
        assertEquals(-1,line.foreground(1)); assertEquals(0,line.style(1))
        assertEquals(line.hyperlink(0),line.hyperlink(1))
        assertEquals("https://example.com",line.hyperlink(1)?.uri)
    }
}
