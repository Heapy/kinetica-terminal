package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalSelectionTest {
    @Test fun directTextExtractionPreservesWholeWideGlyphsAndEmptyRanges() {
        val session = TerminalSession(8, 3)
        session.write("a界éZ")
        assertEquals("界", session.selectedText(0, 2, 0, 3))
        assertEquals("界", session.selectedText(0, 1, 0, 2))
        assertEquals("界é", session.selectedText(0, 2, 0, 4))
        assertEquals("", session.selectedText(0, 2, 0, 2))
        assertEquals("", session.selectedText(0, 3, 0, 1))
        assertEquals("a界éZ", session.selectedText(0, -10, 0, 100))
    }

    @Test fun selectedCellsSurviveUnrelatedOutputAndPaletteButNotAnOverwrite() {
        val session = TerminalSession(16, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("alpha beta")
            viewport.beginSelection(0, 0); viewport.endSelection(5, 0)
            session.write("\u001b[4;1Hprogress\u001b[1;12HX\u001b[6n\u001b[2 q\u001b]4;1;#123456\u0007")
            assertEquals("alpha", viewport.selectedText())
            session.write("\u001b[1;3HZ")
            assertEquals("", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun anchorsMoveIntoHistoryAndSurviveTrimmingBeforeTheSelectedRows() {
        val session = TerminalSession(8, 3, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("one\r\ntwo\r\nthree")
            viewport.beginSelection(0, 1); viewport.endSelection(3, 1)
            for (next in listOf("four", "five", "six", "seven")) {
                session.write("\r\n$next")
                assertEquals("two", viewport.selectedText(), next)
            }
            assertEquals("two", session.line(0).text())
            session.write("\r\neight")
            assertEquals("", viewport.selectedText(), "A recycled anchor must never select new text")
        } finally { viewport.dispose() }
    }

    @Test fun scrollingKeepsTheSameTopRowUntilThatRowIsPruned() {
        val session = TerminalSession(8, 2, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write((0..6).joinToString("\r\n"))
            viewport.scroll(2)
            val top = viewport.visibleText().substringBefore('\n')
            session.write("\r\n7")
            assertEquals(top, viewport.visibleText().substringBefore('\n'))
            session.write("\r\n8")
            assertEquals(session.line(0).text(), viewport.visibleText().substringBefore('\n'))
            assertNotEquals(top, viewport.visibleText().substringBefore('\n'))
            session.write("\u001b[3J")
            assertEquals(0, viewport.scrollOffset)
        } finally { viewport.dispose() }
    }

    @Test fun partialScrollingPreservesCoherentAnchorsAndInvalidatesReplacedInteriors() {
        val session = TerminalSession(8, 5)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("a\r\nb\r\nc\r\nd\r\ne")
            viewport.beginSelection(0, 2); viewport.endSelection(1, 3)
            session.write("\u001b[2;4r\u001b[S")
            assertEquals("c\nd", viewport.selectedText())
            viewport.beginSelection(0, 0); viewport.endSelection(1, 4)
            session.write("\u001b[S")
            assertEquals("", viewport.selectedText())
            viewport.beginSelection(0, 0); viewport.endSelection(1, 0)
            session.write("\u001b[S")
            assertEquals("a", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun screenRoundTripsResetAndResizeCannotResurrectOldSelections() {
        val session = TerminalSession(8, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("old")
            viewport.beginSelection(0, 0); viewport.endSelection(3, 0)
            session.write("\u001b[?47hnew\u001b[?47l")
            assertEquals("", viewport.selectedText())
            viewport.beginSelection(0, 0); viewport.endSelection(3, 0)
            session.resize(10, 4)
            assertEquals("", viewport.selectedText())
            session.write("\r\nline".repeat(8)); viewport.scroll(3)
            session.write("\u001b[?47h\u001b[?47l")
            assertEquals(0, viewport.scrollOffset)
            viewport.scroll(3); session.reset()
            assertEquals(0, viewport.scrollOffset)
        } finally { viewport.dispose() }
    }

    @Test fun wideAndCombinedGlyphsSelectWholeCellsInEitherDirection() {
        val session = TerminalSession(8, 2, graphemeClustering = true)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("A界e\u0301😀Z")
            viewport.beginSelection(2, 0); viewport.endSelection(3, 0)
            assertEquals("界", viewport.selectedText())
            val frame = TerminalGpuFrame(); frame.update(viewport, 8.0, 16.0)
            assertEquals(listOf(TerminalRect(8.0, 0.0, 16.0, 16.0, session.theme.selection)),
                frame.rows[0].backgrounds.filter { it.color == session.theme.selection })
            viewport.beginSelection(3, 0); viewport.endSelection(2, 0)
            assertEquals("界", viewport.selectedText())
            viewport.beginSelection(4, 0); viewport.endSelection(5, 0)
            assertEquals("😀", viewport.selectedText())
            viewport.beginSelection(3, 0); viewport.endSelection(4, 0)
            assertEquals("e\u0301", viewport.selectedText())
            session.write("\u001b[1;5H\u0308")
            assertEquals("", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun wordsAndLogicalLinesSpanSoftWrapsWithoutCrossingHardLineBreaks() {
        val session = TerminalSession(6, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("alpha-beta gamma\r\nnext")
            viewport.beginSelection(2, 0, TerminalSelectionMode.WORD)
            assertEquals("alpha-beta", viewport.selectedText())
            viewport.extendSelection(1, 2)
            assertEquals("alpha-beta gamma", viewport.selectedText())
            viewport.beginSelection(1, 2, TerminalSelectionMode.WORD); viewport.endSelection(1, 0)
            assertEquals("alpha-beta gamma", viewport.selectedText())
            viewport.beginSelection(1, 1, TerminalSelectionMode.LINE)
            assertEquals("alpha-beta gamma\n", viewport.selectedText())
            viewport.endSelection(1, 3)
            assertEquals("alpha-beta gamma\nnext\n", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun wordsHandleWhitespacePunctuationWidePaddingAndBidirectionalDragging() {
        val session = TerminalSession(30, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("one  (two);three")
            viewport.beginSelection(3, 0, TerminalSelectionMode.WORD)
            assertEquals("  ", viewport.selectedText())
            viewport.beginSelection(5, 0, TerminalSelectionMode.WORD)
            assertEquals("(", viewport.selectedText())
            viewport.beginSelection(7, 0, TerminalSelectionMode.WORD); viewport.extendSelection(0, 0)
            assertEquals("one  (two", viewport.selectedText())
            viewport.extendSelection(13, 0)
            assertEquals("two);three", viewport.selectedText())
            viewport.beginSelection(3, 1, TerminalSelectionMode.LINE)
            assertEquals("\n", viewport.selectedText())
            session.resize(4, 4); session.reset(); session.write("abc界z")
            viewport.beginSelection(1, 1, TerminalSelectionMode.WORD)
            assertEquals("abc界z", viewport.selectedText(), "The wide-glyph padding before a wrap is not whitespace")
            viewport.beginSelection(3, 0, TerminalSelectionMode.WORD)
            assertEquals("abc界z", viewport.selectedText(), "Clicking the padding selects the word that crosses it")
        } finally { viewport.dispose() }
    }

    @Test fun changingAWrapOnlyInvalidatesSelectionsThatContainItsLineBoundary() {
        val session = TerminalSession(8, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("keep xx")
            viewport.beginSelection(0, 0); viewport.endSelection(4, 0)
            session.write("YZ") // Completes row 0 and soft-wraps; "keep" is untouched.
            assertEquals("keep", viewport.selectedText())
            viewport.beginSelection(0, 0); viewport.endSelection(1, 1)
            assertEquals("keep xxYZ", viewport.selectedText())
            session.write("\u001b[1;8H\u001b[K")
            assertEquals("", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun independentSelectionsObserveOnlyTheirOwnCellsAndClearAllPaintedRows() {
        val session = TerminalSession(12, 3)
        val one = TerminalViewport(session, {}); val two = TerminalViewport(session, {})
        try {
            session.write("first second\r\nthird")
            one.beginSelection(0, 0); one.endSelection(5, 1)
            two.beginSelection(6, 0); two.endSelection(12, 0)
            session.write("\u001b[2;2HZ")
            assertEquals("", one.selectedText()); assertEquals("second", two.selectedText())
            val frame = TerminalGpuFrame(); frame.update(one, 8.0, 16.0)
            assertTrue(frame.rows.flatMap { it.backgrounds }.none { it.color == session.theme.selection })
            one.dispose(); session.write("\u001b[1;2HX")
            assertEquals("second", two.selectedText())
        } finally { one.dispose(); two.dispose() }
    }

    @Test fun synchronizedFramesKeepSelectionTextEvenOutsideTheVisibleSnapshot() {
        val session = TerminalSession(8, 3, 20)
        val viewport = TerminalViewport(session, {})
        try {
            session.write((0..9).joinToString("\r\n"))
            viewport.scroll(5); viewport.beginSelection(0, 0)
            viewport.scroll(-5); viewport.endSelection(1, 2)
            val selected = viewport.selectedText()
            assertEquals((2..9).joinToString("\n"), selected)
            session.write("\u001b[?2026h\r\nnew".repeat(30))
            assertEquals(selected, viewport.selectedText(), "Copy uses the captured frame, not recycled live rows")
            session.write("\u001b[?2026l")
            assertEquals("", viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun autoscrollExtendsSelectionAndStopsOnReleaseCancelFocusLossAndDispose() {
        for (stop in listOf<(TerminalViewport) -> Unit>({ it.endSelection(0, -2) },
            { it.cancelSelectionDrag() }, { it.setFocused(false) }, { it.dispose() })) {
            val session = TerminalSession(8, 3)
            session.write((0..20).joinToString("\r\n"))
            val clock = Clock(); val viewport = TerminalViewport(session, {}, clock)
            try {
                viewport.beginSelection(0, 2); viewport.extendSelection(0, -2)
                assertEquals(1, clock.pending)
                clock.advance(100)
                assertEquals(4, viewport.scrollOffset)
                assertEquals((14..19).joinToString("\n", postfix = "\n"), viewport.selectedText())
                stop(viewport); assertEquals(0, clock.pending)
                val offset = viewport.scrollOffset
                clock.advance(1000); assertEquals(offset, viewport.scrollOffset)
            } finally { viewport.dispose() }
        }
    }

    @Test fun autoscrollUsesTheLatestDirectionAndStopsAtHistoryLimits() {
        val session = TerminalSession(8, 3)
        session.write((0..20).joinToString("\r\n"))
        val clock = Clock(); val viewport = TerminalViewport(session, {}, clock)
        try {
            viewport.scroll(5); viewport.beginSelection(0, 1); viewport.extendSelection(0, -2)
            viewport.extendSelection(8, 4)
            clock.advance(50)
            assertEquals(3, viewport.scrollOffset)
            clock.advance(500)
            assertEquals(0, viewport.scrollOffset); assertEquals(0, clock.pending)
            viewport.extendSelection(0, -100)
            clock.advance(1000)
            assertEquals(session.historySize, viewport.scrollOffset); assertEquals(0, clock.pending)
            viewport.extendSelection(0, 1)
            assertEquals(0, clock.pending)
        } finally { viewport.dispose() }
    }

    @Test fun largeSelectionsDoNotRescanHistoryOnOutputOrSmallDragChanges() {
        val rows = Array(10_000) { TerminalLine(2).also { row -> row.historySlot = it } }
        var reads = 0
        val selection = TerminalSelection({ 0 }, { rows.lastIndex }, { reads++; rows[it] }, { reads++; it.historySlot })
        try {
            selection.begin(TerminalCellPosition(0, 0), TerminalSelectionMode.CHARACTER)
            selection.extend(TerminalCellPosition(rows.lastIndex, 1))
            reads = 0
            repeat(100) { assertNotNull(selection.range()) }
            assertTrue(reads <= 300, "Output validation should resolve only endpoint anchors: $reads")
            reads = 0
            selection.extend(TerminalCellPosition(rows.lastIndex - 1, 1))
            assertTrue(reads < 20, "Dragging one row should update only its edge watches: $reads")
        } finally { selection.clear() }
    }

    private class Clock : TerminalScheduler {
        private class Job(val time: Long, val action: () -> Unit)
        private var time = 0L
        private val jobs = mutableListOf<Job>()
        val pending: Int get() = jobs.size
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            val job = Job(time + delayMillis, action); jobs += job
            return TerminalDisposable { jobs.remove(job) }
        }
        fun advance(millis: Int) {
            val end = time + millis
            while (true) {
                val job = jobs.minByOrNull { it.time }?.takeIf { it.time <= end } ?: break
                jobs.remove(job); time = job.time; job.action()
            }
            time = end
        }
    }
}
