package io.heapy.kinetica.terminal

import kotlin.random.Random
import kotlin.test.*

class TerminalHistoryCompressionTest {
    private class Clock : TerminalScheduler {
        private data class Job(val at: Long, val action: () -> Unit)
        private val jobs = mutableListOf<Job>()
        var now = 0L
        val pending: Int get() = jobs.size
        fun callbacks() = jobs.map { it.action }
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            val job = Job(now + delayMillis, action); jobs += job
            return TerminalDisposable { jobs.remove(job) }
        }
        fun advance(millis: Int) {
            val end = now + millis
            while (true) {
                val job = jobs.minByOrNull { it.at }?.takeIf { it.at <= end } ?: break
                jobs.remove(job); now = job.at; job.action()
            }
            now = end
        }
    }

    @Test fun cellCodecPreservesAllBitsAndRejectsUnprofitableRows() {
        val random = Random(882134)
        for (columns in listOf(80, 128, 257, 4096)) repeat(20) {
            val data = IntArray(columns * 4)
            for (plane in 0..3) {
                var value = if (plane % 2 == 0) Int.MIN_VALUE else Int.MAX_VALUE
                for (x in 0 until columns) {
                    if (x % 13 == 0 && x != 0) value = random.nextInt()
                    data[x * 4 + plane] = value
                }
            }
            val packed = assertNotNull(packTerminalCells(data, columns))
            assertTrue(packed.size <= data.size * 4 - 32)
            assertContentEquals(data, unpackTerminalCells(packed, columns))
        }
        assertNull(packTerminalCells(IntArray(4), 1))
        assertNull(packTerminalCells(IntArray(320) { random.nextInt() }, 80))
    }

    private fun assertLine(expected: TerminalLine, actual: TerminalLine) {
        assertEquals(expected.columns, actual.columns)
        assertEquals(expected.wrapped, actual.wrapped)
        assertEquals(expected.wrapContinuation, actual.wrapContinuation)
        assertEquals(expected.promptMarker, actual.promptMarker)
        for (x in 0 until expected.columns) {
            assertEquals(expected.text(x), actual.text(x), "text $x")
            assertEquals(expected.width(x), actual.width(x), "width $x")
            assertEquals(expected.rawForeground(x), actual.rawForeground(x), "foreground $x")
            assertEquals(expected.rawBackground(x), actual.rawBackground(x), "background $x")
            assertEquals(expected.style(x), actual.style(x), "style $x")
            assertEquals(expected.rawUnderlineColor(x), actual.rawUnderlineColor(x), "underline $x")
            assertEquals(expected.hyperlink(x), actual.hyperlink(x), "link $x")
            assertEquals(expected.semanticContent(x), actual.semanticContent(x), "semantic $x")
            assertEquals(expected.isProtected(x), actual.isProtected(x), "protected $x")
            assertEquals(expected.isSpacerHead(x), actual.isSpacerHead(x), "spacer $x")
            assertEquals(expected.hasText(x), actual.hasText(x), "written $x")
        }
    }

    @Test fun rowIdentityMetadataAndMutationObserversSurviveCompression() {
        val session = TerminalSession(80, 2)
        session.write("\u001b]133;A\u001b\\\u001b[1;4:3;38;5;17;48;2;1;2;3;58;5;5m" +
            "\u001b]8;id=a;https://example.test/\u001b\\á界🙂\u001b[1\"qP")
        val row = session.screenLine(0)
        val anchor = session.graphicsAnchor(1, 0)
        val expected = row.snapshot(session.colors)
        val hyperlink = row.hyperlink(0)
        val charged = row.storageBytes
        var changes = 0
        val observation = row.observeText { _, _ -> changes++ }
        try {
            repeat(3) {
                row.compress()
                assertTrue(row.compressed)
                assertTrue(row.residentStorageBytes < charged)
                assertEquals(charged, row.storageBytes)
                assertSame(row, anchor.line)
                assertLine(expected, row)
                assertSame(hyperlink, row.hyperlink(0))
                assertEquals(0, changes, "Compression and reads are not text mutations")
            }
            row.compress()
            row.put(0, 'Z'.code, 1, -1, -1, 0)
            assertEquals("Z", row.text(0)); assertTrue(changes > 0)
            assertEquals("á", expected.text(0), "Snapshots remain independent")
        } finally { observation.dispose(); anchor.dispose() }
    }

    @Test fun idleWorkerDebouncesOutputExcludesEveryVisibleViewAndReclaimsHiddenHistory() {
        val session = TerminalSession(80, 2, 100)
        session.write("\u001b[?25l")
        val clock = Clock()
        val first = TerminalViewport(session, {}, clock)
        // An unscheduled second view still protects the history it is displaying.
        val second = TerminalViewport(session, {})
        try {
            repeat(50) { session.write("line $it: some ordinary shell output\r\n") }
            first.scrollTo(session.historySize)
            second.scrollTo(session.historySize - 10)
            val lines = (0 until session.historySize).map(session::line)
            val charge = session.historyStorageBytes
            clock.advance(999)
            assertEquals(charge, session.historyResidentStorageBytes)
            session.write("x")
            clock.advance(1)
            assertEquals(charge, session.historyResidentStorageBytes)
            clock.advance(1000)
            for (i in lines.indices) assertEquals(i !in 0..1 && i !in 10..11, lines[i].compressed, "row $i")
            assertTrue(session.historyResidentStorageBytes < charge / 3)
            assertEquals(charge, session.historyStorageBytes)
            assertEquals("line 0: some ordinary shell output\nline 1: some ordinary shell output", first.visibleText())
            first.setVisible(false); second.setVisible(false)
            clock.advance(1000)
            assertTrue(lines.all { it.compressed })
            first.setVisible(true)
            assertContains(first.visibleText(), "line 0:")
            assertFalse(lines[0].compressed)
            session.scrollbackCompression = false
            val bytes = session.historyResidentStorageBytes
            clock.advance(5000)
            assertEquals(bytes, session.historyResidentStorageBytes)
        } finally { first.dispose(); second.dispose() }
        assertEquals(0, clock.pending)
    }

    @Test fun boundedPassesAreCancelledOnOutputDisableAndDisposal() {
        val mixed = LineRing(5, Long.MAX_VALUE)
        val mixedRows = listOf(1000, 4096, 4096, 4096, 4096).map { TerminalLine(it) }
        mixedRows.forEach(mixed::add)
        assertEquals(4, mixed.compressBatch(0, emptyList()))
        assertTrue(mixedRows.take(4).all { it.compressed })
        assertFalse(mixedRows.last().compressed, "A differently sized row must not exceed a callback's cell budget")
        val session = TerminalSession(4096, 1, 20)
        session.write("\u001b[?25l")
        val clock = Clock()
        val view = TerminalViewport(session, {}, clock)
        repeat(12) { session.write("$it\r\n") }
        val rows = (0 until session.historySize).map(session::line)
        clock.advance(1000)
        assertEquals(4, rows.count { it.compressed }, "At most 16384 cells per callback")
        assertEquals(1, clock.pending)
        val cancelled = clock.callbacks()
        session.scrollbackCompression = false
        cancelled.forEach { it() }
        assertEquals(4, rows.count { it.compressed })
        session.scrollbackCompression = true
        clock.advance(1002)
        assertTrue(rows.all { it.compressed })
        rows[0].text() // Escaping references still read and mutate the same row.
        session.requestHistoryCompression()
        val disposed = clock.callbacks()
        view.dispose(); disposed.forEach { it() }
        assertFalse(rows[0].compressed)
        assertEquals(0, clock.pending)
    }

    @Test fun heldOutputStillRestartsTheIdleDelayForBothInputPaths() {
        for (bytes in listOf(false, true)) {
            val session = TerminalSession(80, 2)
            session.write("\u001b[?25l")
            val clock = Clock()
            val viewport = TerminalViewport(session, {}, clock)
            try {
                repeat(10) { session.write("history $it\r\n") }
                val rows = (0 until session.historySize).map(session::line)
                session.write("\u001b[?2026h")
                clock.advance(900)
                if (bytes) session.writeAvailable("working".encodeToByteArray()) else session.write("working")
                clock.advance(100)
                assertTrue(rows.none { it.compressed }, "Pending damage does not mean idle output")
                assertFalse(session.synchronizedOutput, "Compression must not extend the render hold deadline")
                clock.advance(1000)
                assertTrue(rows.all { it.compressed })
            } finally { viewport.dispose() }
            assertEquals(0, clock.pending)
        }
    }

    @Test fun compressedHistoryReflowsRecyclesAndClearsLikeUncompressedHistory() {
        val reference = TerminalSession(80, 3, 30)
        val compressed = TerminalSession(80, 3, 30)
        val sessions = listOf(reference, compressed)
        repeat(90) { step ->
            for (session in sessions) {
                session.write("\u001b[38;5;${step % 256}mrow $step á界" + "x".repeat(step % 100) + "\r\n")
                when (step % 17) {
                    4 -> session.resize(37, 4)
                    9 -> session.resize(80, 3)
                    12 -> session.write("\u001b[?1049hALTERNATE")
                    13 -> session.write("\u001b[?1049l")
                    16 -> session.write("\u001b[3J")
                }
            }
            var next: Int? = 0
            while (next != null) next = compressed.compressHistoryBatch(next, emptyList())
            assertEquals(reference.historyStorageBytes, compressed.historyStorageBytes)
            assertEquals(reference.lineCount, compressed.lineCount)
            assertEquals(reference.cursorColumn, compressed.cursorColumn)
            assertEquals(reference.cursorRow, compressed.cursorRow)
            for (y in 0 until reference.lineCount) assertLine(reference.line(y), compressed.line(y))
            // The next input/resize must operate on compressed rows too, not just read them.
            next = 0
            while (next != null) next = compressed.compressHistoryBatch(next, emptyList())
        }
    }

    @Test fun selectionGraphicsAnchorsAndFrozenFramesSurviveCompressedHistory() {
        val session = TerminalSession(80, 2, 4)
        session.write("\u001b[?25lalpha")
        val anchor = session.graphicsAnchor(1, 0)
        session.write("\r\nbeta\r\ngamma\r\ndelta")
        val row = session.line(0)
        val viewport = TerminalViewport(session, {})
        try {
            viewport.scrollTo(session.historySize)
            viewport.beginSelection(0, 0)
            viewport.extendSelection(4, 1)
            viewport.endSelection(4, 1)
            val selected = viewport.selectedText()
            val visible = viewport.visibleText()
            val position = session.graphicsAnchorPosition(anchor)
            session.compressHistoryBatch(0, emptyList())
            assertTrue(row.compressed)
            assertSame(row, anchor.line)
            assertEquals(position, session.graphicsAnchorPosition(anchor))
            assertEquals(selected, viewport.selectedText())
            session.write("\u001b[?2026h")
            session.compressHistoryBatch(0, emptyList())
            session.write("\r\nnew output")
            assertEquals(visible, viewport.visibleText(), "Frozen rows own their restored cells")
            assertEquals(selected, viewport.selectedText())
            session.write("\u001b[?2026l")
            assertSame(row, anchor.line)
            repeat(10) {
                session.compressHistoryBatch(0, emptyList())
                session.write("\r\nrecycle")
            }
            assertNull(anchor.line, "Eviction still invalidates anchors attached to compressed rows")
        } finally { viewport.dispose(); anchor.dispose() }
    }
}
