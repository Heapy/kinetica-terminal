package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalNavigationTest {
    @Test fun searchFindsUnicodeAcrossWrapsAndRevealsScrollback() {
        val session = TerminalSession(8, 3)
        session.write("one\r\nabc界e\u0301xyz\r\ntwo\r\nthree\r\nfour\r\nfive")
        val result = session.search("界e\u0301xyz")
        val match = result.matches.single()
        assertTrue(match.endLine > match.startLine, "Fixture must cross a soft wrap")
        val viewport = TerminalViewport(session, {})
        try {
            assertTrue(viewport.selectMatch(match))
            assertEquals("界e\u0301xyz", viewport.selectedText())
            assertTrue(viewport.scrollOffset > 0)
            assertTrue(session.search("threefour").matches.isEmpty(), "Hard newlines must not disappear")
            assertEquals(1, session.search("THREE").matches.size)
            assertTrue(session.search("THREE", caseSensitive = true).matches.isEmpty())
            session.write("\r\n界界界")
            assertTrue(session.search("界", limit = 2).truncated)
            viewport.selectAll()
            assertContains(viewport.selectedText(), "abc界e\u0301xyz")
            viewport.scrollTo(Int.MAX_VALUE)
            assertEquals(session.historySize, viewport.scrollOffset)
            viewport.scrollTo(-1)
            assertEquals(0, viewport.scrollOffset)
        } finally { viewport.dispose() }
    }

    @Test fun searchCoversWholeEmojiAndCombinedCells() {
        val session = TerminalSession(20, 2)
        session.write("a😀e\u0301b")
        assertEquals(TerminalSearchMatch(0, 1, 0, 3), session.search("😀").matches.single())
        assertEquals(TerminalSearchMatch(0, 3, 0, 4), session.search("\u0301").matches.single())
        session.write("\u001b[?1049hOTHER")
        assertTrue(session.search("😀").matches.isEmpty())
    }

    @Test fun hyperlinksHandleOsc8AndWrappedPlainUrlsWithoutPunctuation() {
        val session = TerminalSession(10, 8)
        session.write("(https://example.org/a).\r\n\u001b]8;;https://example.org/explicit\u001b\\界 link\u001b]8;;\u001b\\")
        assertEquals("https://example.org/a", session.hyperlinkAt(0, 3))
        assertEquals("https://example.org/a", session.hyperlinkAt(1, 4))
        assertNull(session.hyperlinkAt(2, 2), "Trailing closing punctuation is not part of the URL")
        assertEquals("https://example.org/explicit", session.hyperlinkAt(3, 1), "A wide trailing cell uses its lead link")
        assertNull(session.hyperlinkAt(7, 0))
    }

    @Test fun hostClearAndThemeChangesPreservePromptAndProtocolState() {
        val session = TerminalSession(10, 3)
        session.write("history\r\nold\r\nold\r\nprompt> \u001b[?2004h\u001b[?1h\u001b[31m")
        val column = session.cursorColumn
        session.clearScreen()
        assertEquals("prompt>", session.screenLine(0).text())
        assertEquals(column, session.cursorColumn)
        assertEquals(0, session.cursorRow)
        assertTrue(session.bracketedPaste); assertTrue(session.applicationCursor)
        assertTrue(session.historySize > 0)
        session.clearHistory()
        assertEquals(0, session.historySize)
        assertEquals("prompt>", session.screenLine(0).text())
        session.write("x")
        assertEquals(0xcd3131, session.screenLine(0).foreground(column))
        val theme = TerminalTheme(background = 0xfafafa, foreground = 0x242424)
        session.configureTheme(theme)
        assertEquals(theme, session.theme)
        session.write("\u001b]11;#123456\u0007\u001b]111\u0007")
        assertEquals(theme.background, session.theme.background, "OSC reset uses the newly configured defaults")
    }

    @Test fun clearDoesNotFeedHostCommandsIntoAnIncompleteEscapeSequence() {
        val session = TerminalSession(20, 2)
        session.write("prompt\u001b[38;2;10;")
        session.clearHistory(); session.clearScreen()
        session.write("20;30mX")
        assertEquals(0x0a141e, session.screenLine(0).foreground(6))
    }

    @Test fun alternateScreenExitStillErasesTheEntireAlternateBuffer() {
        val session = TerminalSession(20, 3)
        session.write("primary\u001b[?47hfirst\r\nlast\u001b[?1047l")
        assertEquals("primary", session.screenLine(0).text())
        session.write("\u001b[?47h")
        assertTrue((0 until session.rows).all { session.screenLine(it).text().isEmpty() },
            "DEC 1047 erasure must not use the host action that retains the prompt")
    }

    @Test fun shellQuotingKeepsSpacesQuotesSubstitutionsAndNewlinesLiteral() {
        assertEquals("'/tmp/a b'", terminalShellQuote("/tmp/a b"))
        assertEquals("'/tmp/a'\\''b'", terminalShellQuote("/tmp/a'b"))
        assertEquals("'/tmp/\$(touch NO)\n`whoami`'", terminalShellQuote("/tmp/\$(touch NO)\n`whoami`"))
        assertFailsWith<IllegalArgumentException> { terminalShellQuote("a\u0000b") }
    }
}
