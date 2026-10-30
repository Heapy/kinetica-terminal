package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalWideEditTest {
    private val styledLink = "\u001b[31;44;53;4:3m\u001b]8;;https://wide.example\u001b\\"

    @Test fun editingWrappedWideTextClearsAndRepaintsPreviousSpacerHead() {
        for (command in listOf('P', 'X')) for (protected in listOf(false, true)) {
            val session = TerminalSession(8, 3)
            session.write(styledLink + (if (protected) "\u001bV" else "") + "\u001b[1;8H界😀")
            val previous = session.screenLine(0)
            assertTrue(previous.isSpacerHead(7))
            assertNotEquals(0, previous.style(7))
            assertNotNull(previous.hyperlink(7))
            session.write("\u001b[2;1H\u001b[42m")
            val changes = mutableListOf<TerminalChange>()
            val observation = session.observe(changes::add)
            session.write("\u001b[$command")
            observation.dispose()
            assertEquals(0, changes.single().firstRow, "Previous row needs repaint for $command, protected=$protected")
            assertFalse(previous.isSpacerHead(7))
            assertEquals(0, previous.style(7))
            assertNull(previous.hyperlink(7))
            assertEquals(terminalPalette(2), previous.background(7))
        }
    }

    @Test fun editingFirstScreenRowClearsSpacerHeadInHistoryAndInvalidatesViewport() {
        for (command in listOf('P', 'X')) {
            val session = TerminalSession(8, 2)
            session.write(styledLink + "\u001b[2;8H界😀\u001b[2;1H\n\u001b[H\u001b[42m")
            val historySize = session.historySize
            val previous = session.line(historySize - 1)
            assertTrue(previous.isSpacerHead(7))
            val changes = mutableListOf<TerminalChange>()
            val observation = session.observe(changes::add)
            session.write("\u001b[$command")
            observation.dispose()
            assertTrue(changes.single().full, "Historical edits need viewport invalidation")
            assertEquals(historySize, session.historySize)
            assertSame(previous, session.line(historySize - 1))
            assertFalse(previous.isSpacerHead(7))
            assertEquals(0, previous.style(7))
            assertNull(previous.hyperlink(7))
            assertEquals(terminalPalette(2), previous.background(7))
        }
    }
}
