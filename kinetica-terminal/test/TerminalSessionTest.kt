package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalSessionTest {
    @Test fun chunksHaveTheSameMeaningAsOneWrite() {
        val input = "hello\r\n\u001b[31;1m世界e\u0301😀\u001b[0m\u001b[2;4H!\u001b]2;my shell\u001b\\"
        val expected = TerminalSession(20, 4).apply { write(input) }
        val bytes = input.encodeToByteArray()
        for (split in 0..bytes.size) {
            val actual = TerminalSession(20, 4)
            actual.write(bytes, 0, split); actual.write(bytes, split)
            assertSameScreen(expected, actual)
            assertEquals("my shell", actual.title)
        }
        val bytewise = TerminalSession(20, 4)
        for (b in bytes) bytewise.write(byteArrayOf(b))
        assertSameScreen(expected, bytewise)
    }

    @Test fun malformedUtf8RecoversAndEofFlushes() {
        val terminal = TerminalSession(20, 2)
        terminal.write(byteArrayOf(0xe2.toByte(), 65, 0xff.toByte(), 0xed.toByte(), 0xa0.toByte(), 0x80.toByte(), 0xf0.toByte()))
        terminal.finishInput()
        // Each byte of the invalid surrogate encoding ED A0 80 is a maximal
        // ill-formed subpart, matching Ghostty's incremental UTF-8 decoder.
        assertEquals("�A�����", terminal.screenLine(0).text())
    }

    @Test fun delayedWrapAndCarriageReturn() {
        val terminal = TerminalSession(4, 2)
        terminal.write("abcd")
        assertEquals(0, terminal.cursorRow)
        terminal.write("\rX")
        assertEquals("Xbcd", terminal.screenLine(0).text())
        terminal.write("\u001b[1;4HdZ")
        assertEquals("Z", terminal.screenLine(1).text())
        assertTrue(terminal.screenLine(0).wrapped)
    }

    @Test fun historyIsBoundedAndPhysicalLinesAreReused() {
        val terminal = TerminalSession(10, 2, scrollback = 3)
        repeat(100) { terminal.write("$it\r\n") }
        assertEquals(3, terminal.historySize)
        assertEquals(listOf("96", "97", "98", "99", ""), (0 until terminal.lineCount).map { terminal.line(it).text() })
        assertEquals(99, terminal.scrolledLines)
    }

    @Test fun alternateScreenRestoresPrimaryAndCursorWithoutHistoryPollution() {
        val terminal = TerminalSession(10, 3)
        terminal.write("prompt\u001b[?1049h")
        terminal.write("application\r\n\r\n\r\n\r\n")
        assertEquals(0, terminal.historySize)
        terminal.write("\u001b[?1049lX")
        assertEquals("promptX", terminal.screenLine(0).text())
        assertEquals(0, terminal.historySize)
    }

    @Test fun marginsScrollOnlyTheirRegion() {
        val terminal = TerminalSession(6, 4)
        terminal.write("first\r\nsecond\r\nthird\r\nlast")
        terminal.write("\u001b[2;3r\u001b[3;1H\n")
        assertEquals(listOf("first", "third", "", "last"), (0..3).map { terminal.screenLine(it).text() })
        assertEquals(0, terminal.historySize)
    }

    @Test fun sgrAndEraseUseCurrentBackground() {
        val terminal = TerminalSession(8, 2)
        terminal.write("\u001b[1;38;2;12;34;56;48;5;196mA\u001b[K")
        val line = terminal.screenLine(0)
        assertEquals(0x0c2238, line.foreground(0))
        assertEquals(0xff0000, line.background(0))
        assertEquals(TerminalStyle.BOLD, line.style(0))
        assertEquals(0xff0000, line.background(7))
        terminal.write("\u001b[0mB")
        assertEquals(-1, line.foreground(1))
    }

    @Test fun wideCharacterOverwritesAndDeletesNeverLeaveOrphans() {
        val terminal = TerminalSession(8, 2)
        terminal.write("a界b\u001b[1;3HX")
        assertEquals("a Xb", terminal.screenLine(0).text())
        terminal.write("\r界界\u001b[1;2H\u001b[P")
        val line = terminal.screenLine(0)
        for (x in 0 until 8) {
            if (line.width(x) == 0) assertTrue(x > 0 && line.width(x - 1) == 2)
            if (line.width(x) == 2) assertTrue(x < 7 && line.width(x + 1) == 0)
        }
    }

    @Test fun insertingAndDeletingLinesDoesNotAppendHistory() {
        val terminal = TerminalSession(5, 3)
        terminal.write("one\r\ntwo\r\nthree\u001b[H\u001b[M")
        assertEquals(listOf("two", "three", ""), (0..2).map { terminal.screenLine(it).text() })
        assertEquals(0, terminal.historySize)
        terminal.write("\u001b[L")
        assertEquals(listOf("", "two", "three"), (0..2).map { terminal.screenLine(it).text() })
    }

    @Test fun resizingPreservesHistoryAndRestoresResizedPrimaryScreen() {
        val terminal = TerminalSession(8, 3)
        terminal.write("one\r\ntwo\r\nthree\u001b[?1049h")
        terminal.resize(4, 2)
        terminal.write("\u001b[?1049l")
        // Confirmed by Ghostty's resize_primary_restore_regression oracle scenario.
        assertEquals(2, terminal.historySize)
        assertEquals(listOf("one", "two", "thre", "e"), (0..3).map { terminal.line(it).text() })
        assertTrue(terminal.cursorColumn < 4 && terminal.cursorRow < 2)
    }

    @Test fun shrinkingBelowUnusedRowsKeepsPromptInPlace() {
        val terminal = TerminalSession(80, 24)
        terminal.write("prompt$ ")
        terminal.resize(40, 12)
        assertEquals("prompt$", terminal.screenLine(0).text())
        assertEquals(0, terminal.cursorRow)
        assertEquals(0, terminal.historySize)
    }

    @Test fun inputEncodingTracksModesAndProtocolReplies() {
        val terminal = TerminalSession()
        val output = mutableListOf<String>()
        val subscription = terminal.onInput { output.add(it.toByteArray().decodeToString()) }
        terminal.sendKey(TerminalKey("ArrowUp"))
        terminal.write("\u001b[?1h\u001b[?2004h\u001b[2;3H\u001b[6n")
        terminal.sendKey(TerminalKey("ArrowUp"))
        terminal.sendKey(TerminalKey("c", control = true))
        terminal.paste("a\nb")
        assertEquals(listOf("\u001b[A", "\u001b[2;3R", "\u001bOA", "\u0003", "\u001b[200~a\rb\u001b[201~"), output)
        subscription.dispose(); terminal.sendInput("ignored")
        assertEquals(5, output.size)
    }

    @Test fun escapeStringPayloadsAreBoundedAndDoNotPrint() {
        val terminal = TerminalSession(10, 2)
        terminal.write("\u001b]2;" + "x".repeat(10_000) + "\u0007OK")
        assertEquals("", terminal.title)
        terminal.write("\u001bPignored\u001b\\!")
        assertEquals("OK!", terminal.screenLine(0).text())
        terminal.write("\u001b[" + "1;".repeat(100) + "mX")
        assertEquals("OK!X", terminal.screenLine(0).text())
    }

    @Test fun oneDamageNotificationPerWriteAndObserversCanUnsubscribe() {
        val terminal = TerminalSession(20, 5)
        val changes = mutableListOf<TerminalChange>()
        val subscription = terminal.observe(changes::add)
        terminal.write("a\r\nb")
        assertEquals(listOf(TerminalChange(0, 1, false)), changes)
        subscription.dispose(); terminal.write("x")
        assertEquals(1, changes.size)
    }

    @Test fun selectedWrappedLinesCopyWithoutInsertedNewlines() {
        val terminal = TerminalSession(4, 3)
        terminal.write("abcdef\r\nxy")
        assertEquals("abcdef\nxy", terminal.selectedText(0, 0, 2, 2))
    }

    @Test fun selectionPreservesTypedSpacesAndSkipsWideWrapPadding() {
        val terminal = TerminalSession(5, 4)
        terminal.write("a   界x")
        assertEquals("a   界x", terminal.selectedText(0, 0, 1, 3))
        terminal.resize(8, 4)
        assertEquals("a   界x", terminal.selectedText(0, 0, 0, 7))
        terminal.resize(4, 4)
        assertEquals("a   界x", terminal.selectedText(0, 0, 1, 3))
    }

    @Test fun invalidDimensionsAndLengthsFailBeforeMutation() {
        assertFailsWith<IllegalArgumentException> { TerminalSession(0, 1) }
        assertFailsWith<IllegalArgumentException> { TerminalSession(scrollback = -1) }
        val terminal = TerminalSession()
        assertFailsWith<IllegalArgumentException> { terminal.resize(Int.MAX_VALUE, 2) }
        assertEquals(80, terminal.columns)
        assertFailsWith<IllegalArgumentException> { terminal.write(byteArrayOf(1), 1, 1) }
    }

    @Test fun decLineDrawingCanUseEitherCharacterSet() {
        val terminal = TerminalSession(20, 2)
        terminal.write("\u001b(0lqk\u001b(B \u001b)0\u000exq\u000fx")
        assertEquals("┌─┐ │─x", terminal.screenLine(0).text())
    }

    @Test fun mouseFocusAndPasteRespectApplicationModes() {
        val terminal = TerminalSession(80, 24)
        val sent = mutableListOf<String>()
        terminal.onInput { sent.add(it.toByteArray().decodeToString()) }
        assertFalse(terminal.sendMouse(0, 0, 0))
        terminal.write("\u001b[?1002h\u001b[?1006h\u001b[?1004h\u001b[?2004h")
        assertTrue(terminal.sendMouse(2, 3, 0))
        assertTrue(terminal.sendMouse(2, 3, 0, release = true))
        assertFalse(terminal.sendMouse(2, 3, 3, motion = true))
        assertFalse(terminal.sendMouse(2, 3, 0, shift = true))
        terminal.sendFocus(true)
        terminal.paste("a\u001b[201~b")
        assertEquals(listOf("\u001b[<0;3;4M", "\u001b[<0;3;4m", "\u001b[I", "\u001b[200~a[201~b\u001b[201~"), sent)
    }

    private fun assertSameScreen(expected: TerminalSession, actual: TerminalSession) {
        assertEquals(expected.cursorColumn, actual.cursorColumn); assertEquals(expected.cursorRow, actual.cursorRow)
        for (y in 0 until expected.rows) for (x in 0 until expected.columns) {
            val e = expected.screenLine(y); val a = actual.screenLine(y)
            assertEquals(e.text(x), a.text(x)); assertEquals(e.width(x), a.width(x))
            assertEquals(e.foreground(x), a.foreground(x)); assertEquals(e.style(x), a.style(x))
        }
    }
}
