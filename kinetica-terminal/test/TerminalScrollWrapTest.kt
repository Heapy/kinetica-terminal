package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalScrollWrapTest {
    @Test fun scrollDownBreaksLogicalLinesForCopySearchAndReflow() {
        val session = TerminalSession(8, 5)
        session.write("abcdefghijklmnopqrstuvwxYZ")
        assertEquals(listOf(true, true, true, false, false), (0 until 5).map { session.screenLine(it).wrapped })
        session.write("\u001b[2T")
        for (row in 0 until 5) {
            assertFalse(session.screenLine(row).wrapped)
            assertFalse(session.screenLine(row).wrapContinuation)
        }
        assertEquals("abcdefgh\nijklmnop\nqrstuvwx", session.selectedText(2, 0, 4, 8))
        assertTrue(session.search("hijkl").matches.isEmpty(), "Moved rows must no longer form a single logical line")
        assertEquals(1, session.search("ijklmnop").matches.size)
        session.resize(12, 5)
        assertEquals(listOf("", "", "abcdefgh", "ijklmnop", "qrstuvwx"), (0 until 5).map { session.screenLine(it).text() })
    }

    @Test fun marginScrollUpBreaksWrapsButLinefeedKeepsThem() {
        for (alternate in listOf(false, true)) for (linefeed in listOf(false, true)) {
            val session = TerminalSession(8, 5)
            if (alternate) session.write("\u001b[?1049h")
            session.write("abcdefghijklmnopqrstuvwxYZ\u001b[2;5r")
            session.write(if (linefeed) "\u001b[5;1H\n" else "\u001b[S")
            assertTrue(session.screenLine(0).wrapped, "Rows outside the region retain metadata")
            assertEquals(linefeed, session.screenLine(1).wrapped)
            assertEquals(linefeed, session.screenLine(1).wrapContinuation)
            assertEquals(if (linefeed) "qrstuvwxYZ" else "qrstuvwx\nYZ", session.selectedText(1, 0, 2, 2))
        }
    }

    @Test fun partialWidthScrollsPreserveRowWrapMetadata() {
        for (action in listOf("\u001b[T", "\u001b[S", "\u001b[1;2H\u001bM")) {
            val session = TerminalSession(8, 5)
            session.write("abcdefghijklmnopqrstuvwxYZ\u001b[?69h\u001b[2;7s")
            val wraps = (0 until 5).map { session.screenLine(it).wrapped to session.screenLine(it).wrapContinuation }
            session.write(action)
            assertEquals(wraps, (0 until 5).map { session.screenLine(it).wrapped to session.screenLine(it).wrapContinuation })
        }
    }
}
