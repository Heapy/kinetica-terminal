package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalHistoryIsolationTest {
    @Test fun applicationEraseOnAlternatePreservesPrimaryHistoryAndAnchors() {
        for (mode in listOf(47, 1047, 1049)) for (erase in listOf("\u001b[3J", "\u001b[?3J")) {
            val session = TerminalSession(8, 3, scrollback = 20)
            val anchor = session.graphicsAnchor()
            try {
                session.write("one\r\ntwo\r\nthree\r\nfour\r\nfive")
                assertEquals(2, session.historySize)
                assertEquals(-2, session.graphicsAnchorPosition(anchor)?.y)
                session.write("\u001b[?${mode}h\u001b[HALT$erase")
                assertTrue(session.alternateScreen)
                assertEquals("ALT", session.screenLine(0).text())
                assertEquals(0, session.historySize)
                assertNotNull(anchor.line, "An inactive primary-history row must not be retired")
                session.write("\u001b[?${mode}l")
                assertEquals(2, session.historySize)
                assertEquals("one", session.line(0).text())
                assertEquals("two", session.line(1).text())
                assertEquals(-2, session.graphicsAnchorPosition(anchor)?.y)
                session.write(erase)
                assertEquals(0, session.historySize, "The same command on primary still erases its history")
                assertNull(anchor.line)
            } finally { anchor.dispose() }
        }
    }

    @Test fun explicitHostClearStillErasesPrimaryWhileAlternateIsActive() {
        val session = TerminalSession(8, 3, scrollback = 20)
        val anchor = session.graphicsAnchor()
        try {
            session.write("one\r\ntwo\r\nthree\r\nfour\r\nfive\u001b[?1049h\u001b[HALT")
            session.clearHistory()
            assertTrue(session.alternateScreen)
            assertEquals("ALT", session.screenLine(0).text())
            assertNull(anchor.line)
            session.write("\u001b[?1049l")
            assertEquals(0, session.historySize)
            assertEquals("three", session.screenLine(0).text())
        } finally { anchor.dispose() }
    }
}
