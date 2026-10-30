package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalNavigationTest {
    @Test fun screenSwitchEndsActiveLinksButRetainsExistingAndFrozenTargets() {
        for (enter in listOf(47, 1047, 1049)) for (leave in listOf(47, 1047, 1049)) {
            val session = TerminalSession(8, 3)
            val viewport = TerminalViewport(session, {})
            try {
                session.write("\u001b]8;;https://primary.example\u001b\\P")
                assertEquals("https://primary.example", viewport.hyperlinkAt(0, 0))
                session.write("\u001b[?2026h\u001b[?${enter}h\u001b[HA")
                assertNull(session.hyperlinkAt(0, 0), "Entry mode $enter")
                assertEquals("https://primary.example", viewport.hyperlinkAt(0, 0), "Frozen primary stays linked")
                session.write("\u001b[?2026l")
                assertNull(viewport.hyperlinkAt(0, 0))
                session.write("\u001b]8;;https://alternate.example\u001b\\B\u001b[?${leave}l\u001b[1;2HC")
                assertEquals("https://primary.example", viewport.hyperlinkAt(0, 0), "Existing cells keep links")
                assertNull(viewport.hyperlinkAt(1, 0), "Exit mode $leave")
            } finally { viewport.dispose() }
        }
    }

    @Test fun detectedLinkPolicyIsIndependentAndAppliesToFrozenLookupAndNewWordSelections() {
        for (frozen in listOf(false, true)) {
            val session = TerminalSession(80, 4)
            val viewport = TerminalViewport(session, {})
            try {
                val url = "https://visible.example/path"
                session.write(url + "\r\n\u001b]8;;ssh://hidden.example\u001b\\label\u001b]8;;\u001b\\")
                if (frozen) session.write("\u001b[?2026h\u001b[H\u001b[2Kchanged")
                for (detected in listOf(true, false)) for (explicit in listOf(true, false)) {
                    session.detectedLinksEnabled = detected; session.osc8LinksEnabled = explicit
                    assertEquals(if (detected) url else null, viewport.hyperlinkAt(2, 0))
                    assertEquals(if (explicit) "ssh://hidden.example" else null, viewport.hyperlinkAt(2, 1))
                    if (!frozen) {
                        assertEquals(if (detected) url else null, session.hyperlinkAt(0, 2))
                        assertEquals(if (explicit) "ssh://hidden.example" else null, session.hyperlinkAt(1, 2))
                    }
                    viewport.beginSelection(2, 0, TerminalSelectionMode.WORD); viewport.endSelection(2, 0)
                    assertEquals(if (detected) url else "https", viewport.selectedText())
                    assertNotNull(session.screenLine(1).hyperlink(2), "Policy changes preserve explicit metadata")
                }
                session.detectedLinksEnabled = true; viewport.setLinkHover(2, 0)
                assertNotNull(viewport.linkHover)
                session.detectedLinksEnabled = false
                assertNull(viewport.linkHover, "Host policy revokes frozen hover immediately")
                session.write("\u001b[!p\u001b[?1049h\u001b[?1049l\u001bc")
                assertFalse(session.detectedLinksEnabled); assertFalse(session.osc8LinksEnabled)
                session.write(url); assertNull(session.hyperlinkAt(0, 2))
                session.detectedLinksEnabled = true; assertEquals(url, session.hyperlinkAt(0, 2))
            } finally { viewport.dispose() }
        }
    }

    @Test fun linkOriginsFollowVisibleFramesAndCurrentOsc8Policy() {
        val session = TerminalSession(80, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("\u001b]8;;ssh://hidden.example\u001b\\https://visible.example\u001b]8;;\u001b\\")
            assertEquals(TerminalLinkTarget("ssh://hidden.example", true), viewport.linkTargetAt(2, 0))
            session.write("\u001b[?2026h\u001b[Hchanged")
            assertEquals(TerminalLinkTarget("ssh://hidden.example", true), viewport.linkTargetAt(2, 0))
            session.osc8LinksEnabled = false
            assertEquals(TerminalLinkTarget("https://visible.example", false), viewport.linkTargetAt(2, 0))
            assertEquals("https://visible.example", viewport.hyperlinkAt(2, 0))
            session.osc8LinksEnabled = true
            assertEquals(TerminalLinkTarget("ssh://hidden.example", true), viewport.linkTargetAt(2, 0))
            assertNull(viewport.linkTargetAt(-1, 0)); assertNull(viewport.linkTargetAt(0, 3))
            session.write("\u001b[?2026l"); session.reset(); session.write("./file.txt")
            assertEquals(TerminalLinkTarget("./file.txt", false), viewport.linkTargetAt(2, 0))
        } finally { viewport.dispose() }
    }

    @Test fun recognizedSchemesAndPathsUseTheSameCellsForLinksAndDoubleClicks() {
        val links = listOf("mailto:me@example.org", "ssh://host/path", "tel:+12345", "magnet:?xt=urn:example",
            "ipfs://hash", "file:///tmp/file.txt", "/tmp/test folder/file.txt", "../relative/file.kt:42",
            "\$HOME/src/file.kt", "src/config/url.zig", ".config/ghostty/config", "~/notes.md", "https://example.org/界e\u0301")
        for (columns in listOf(80, 13)) {
            val session = TerminalSession(columns, 8)
            val viewport = TerminalViewport(session, {})
            try {
                for (link in links) {
                    session.reset(); session.write("($link)")
                    for (y in 0 until session.lineCount) for (x in 0 until columns) {
                        val line = session.line(y)
                        if ((!line.hasText(x) && line.width(x) != 0) || line.isSpacerHead(x) || line.codePoint(x) in listOf(40, 41)) continue
                        assertEquals(link, viewport.hyperlinkAt(x, y), "$link cell=$x,$y columns=$columns")
                        viewport.beginSelection(x, y, TerminalSelectionMode.WORD); viewport.endSelection(x, y)
                        assertEquals(link, viewport.selectedText(), "$link selected cell=$x,$y columns=$columns")
                    }
                }
            } finally { viewport.dispose() }
        }
    }

    @Test fun urlSelectionMapsWideGraphemesAndResumesWordDragging() {
        val session = TerminalSession(8, 10)
        val viewport = TerminalViewport(session, {})
        try {
            val url = "https://a.example/界e\u0301(foo)?q=1"
            session.write(url)
            for (y in 0 until session.lineCount) for (x in 0 until session.columns) {
                val line = session.line(y)
                if (!line.hasText(x) && line.width(x) != 0 && !line.isSpacerHead(x)) continue
                viewport.beginSelection(x, y, TerminalSelectionMode.WORD)
                viewport.endSelection(x, y)
                assertEquals(url, viewport.selectedText(), "cell=$x,$y")
            }
            session.resize(80, 4); session.reset()
            val punctuated = "https://example.org/a(b)?q=1;x=2"
            session.write("[$punctuated].")
            viewport.beginSelection(3, 0, TerminalSelectionMode.WORD)
            assertEquals(punctuated, viewport.selectedText())
            viewport.extendSelection(3, 0)
            viewport.endSelection(3, 0)
            assertEquals("https", viewport.selectedText(), "An actual drag resumes word granularity")
            session.reset()
            session.write("\u001b]8;;https://hidden.example\u001b\\two words\u001b]8;;\u001b\\")
            viewport.beginSelection(5, 0, TerminalSelectionMode.WORD); viewport.endSelection(5, 0)
            assertEquals("words", viewport.selectedText(), "OSC 8 labels retain ordinary word selection")
        } finally { viewport.dispose() }
    }

    @Test fun doubleClickSelectsTheDetectedUrlIncludingPunctuationAndSoftWraps() {
        val url = "https://example.org/a(b)?q=1;x=2"
        for (columns in listOf(80, 13)) {
            val session = TerminalSession(columns, 8)
            val viewport = TerminalViewport(session, {})
            try {
                session.write("[$url].")
                for (offset in 1..url.length) {
                    viewport.beginSelection(offset % columns, offset / columns, TerminalSelectionMode.WORD)
                    viewport.endSelection(offset % columns, offset / columns)
                    assertEquals(url, viewport.selectedText(), "URL cell=$offset columns=$columns")
                }
                session.write("\u001b[?2026h\u001b[Hchanged")
                viewport.beginSelection(3, 0, TerminalSelectionMode.WORD)
                viewport.endSelection(3, 0)
                assertEquals(url, viewport.selectedText(), "Frozen text supplies the displayed URL")
                session.write("\u001b[?2026l")
                session.reset()
                session.write("\u001b]133;A\u0007https://prompt.example\u001b]133;B\u0007/suffix")
                viewport.beginSelection(3, 0, TerminalSelectionMode.WORD)
                viewport.endSelection(3, 0)
                assertEquals("https://prompt.example", viewport.selectedText())
                session.reset()
                session.write("\u001b]8;;ssh://hidden.example\u001b\\[$url].\u001b]8;;\u001b\\")
                session.osc8LinksEnabled = false
                viewport.beginSelection(3, 0, TerminalSelectionMode.WORD)
                viewport.endSelection(3, 0)
                assertEquals(url, viewport.selectedText(), "Visible-link selection is independent of OSC 8 targets and activation policy")
            } finally { viewport.dispose() }
        }
    }

    @Test fun detectedLinksStopAtSemanticPromptBoundariesAcrossWrapsAndFrozenFrames() {
        fun osc(value: String) = "\u001b]133;$value\u0007"
        val prompt = "https://prompt.example"
        val input = "https://input.example"
        val output = "https://output.example"
        for (columns in listOf(80, 13)) {
            val session = TerminalSession(columns, 8)
            val viewport = TerminalViewport(session, {})
            try {
                session.write(osc("A") + prompt + osc("B") + input + osc("C") + output)
                for ((offset, url) in listOf(0 to prompt, prompt.length to input, (prompt.length + input.length) to output)) {
                    assertEquals(url, session.hyperlinkAt(offset / columns, offset % columns))
                    assertEquals(url, viewport.hyperlinkAt(offset % columns, offset / columns))
                }
                assertEquals(1, session.search("prompt.examplehttps").matches.size, "Literal search still spans semantic boundaries")
                session.write("\u001b[?2026h\u001b[Hchanged")
                assertEquals(prompt, viewport.hyperlinkAt(0, 0), "Frozen links use frozen semantic metadata")
                session.write("\u001b[?2026l")
                session.reset()
                session.write(osc("A") + prompt + osc("B") + "/suffix")
                assertEquals(prompt, session.hyperlinkAt(0, 0))
                assertEquals("/suffix", session.hyperlinkAt(prompt.length / columns, prompt.length % columns))
                session.reset()
                session.write("\u001b]8;;ssh://explicit.example\u001b\\" + osc("A") + prompt + osc("B") + input)
                assertEquals("ssh://explicit.example", session.hyperlinkAt(0, 0), "Explicit OSC 8 target has priority")
                session.osc8LinksEnabled = false
                assertEquals(prompt, session.hyperlinkAt(0, 0), "Disabled OSC 8 falls back to the bounded visible region")
                assertEquals(input, session.hyperlinkAt(prompt.length / columns, prompt.length % columns))
            } finally { viewport.dispose() }
        }
    }

    @Test fun semanticLinkOffsetsPreserveWideCellsCombiningMarksAndWrapSpacers() {
        val session = TerminalSession(8, 6)
        val url = "https://a.example/界e\u0301"
        session.write("\u001b]133;A\u0007$\u001b]133;B\u0007$url\u001b]133;C\u0007/suffix")
        for (y in 0 until session.lineCount) for (x in 0 until session.columns) {
            val line = session.line(y)
            if (line.semanticContent(x) == TerminalSemanticContent.INPUT && line.codePoint(x) != 32)
                assertEquals(url, session.hyperlinkAt(y, x), "cell=$x,$y")
        }
    }

    @Test fun osc8HostPolicyAppliesToExistingAndFrozenRowsWithoutRemovingMetadata() {
        val session = TerminalSession(80, 3)
        val viewport = TerminalViewport(session, {})
        val target = "https://example.org/hidden"
        val output = "\u001b]8;;$target\u001b\\界 label\r\nhttps://example.org/visible\u001b]8;;\u001b\\"
        try {
            session.write(output)
            assertEquals(target, viewport.hyperlinkAt(1, 0))
            session.write("\u001b[?2026h")
            session.osc8LinksEnabled = false
            assertNull(session.hyperlinkAt(0, 1)); assertNull(viewport.hyperlinkAt(1, 0))
            assertEquals(target, session.screenLine(0).hyperlink(0)?.uri, "The setting is an activation policy")
            assertEquals("https://example.org/visible", viewport.hyperlinkAt(3, 1), "Plain URL detection remains active")
            session.osc8LinksEnabled = true
            assertEquals(target, viewport.hyperlinkAt(1, 0), "Existing frozen metadata becomes usable again")
            session.write("\u001b[?2026l\r\nnew\r\nmore")
            assertTrue(session.historySize > 0)
            assertEquals(target, session.hyperlinkAt(0, 1), "OSC 8 metadata survives in history")
            session.osc8LinksEnabled = false
            assertNull(session.hyperlinkAt(0, 1), "The policy covers scrollback")
            session.write("\u001b[?1049h\u001b[H$output\u001b[!p")
            assertFalse(session.osc8LinksEnabled)
            assertNull(viewport.hyperlinkAt(1, 0))
            session.osc8LinksEnabled = true
            assertEquals(target, viewport.hyperlinkAt(1, 0), "Alternate-screen metadata is preserved")
            session.osc8LinksEnabled = false
            session.reset(); session.write(output)
            assertFalse(session.osc8LinksEnabled, "RIS preserves host policy")
            assertNull(viewport.hyperlinkAt(1, 0))
        } finally { viewport.dispose() }
    }

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
