package io.heapy.kinetica.terminal

import kotlin.random.Random
import kotlin.test.*

class TerminalResourceTest {
    private fun link(uri: String) = "\u001b]8;;$uri\u001b\\"
    private fun linkBytes(value: TerminalHyperlink) = 96L + 2L * (value.id.length.toLong() + value.uri.length)

    @Test fun historyKeepsAContiguousNewestSuffixWithinBothLimits() {
        // A two-column plain row charges 128 bytes plus eight packed ints.
        val session = TerminalSession(2, 1, scrollback = 100,
            limits = TerminalLimits(historyBytes = 3 * 160L))
        for (i in 0..9) session.write("$i\r\n")
        assertEquals(listOf("7", "8", "9", ""), (0 until session.lineCount).map { session.line(it).text() })
        assertEquals(480L, session.historyStorageBytes)
        session.write(link("https://example.test/" + "a".repeat(100)) + "X\r\n")
        assertEquals(0, session.historySize, "an oversized row must also retire older history")
        assertEquals(0L, session.historyStorageBytes)
        session.write(link("") + "Y\r\nZ")
        assertEquals("Y", session.line(0).text())
        assertEquals("Z", session.screenLine(0).text())
        session.reset()
        assertEquals(0L, session.historyStorageBytes)

        val noHistory = TerminalSession(2, 1, limits = TerminalLimits(historyBytes = 0))
        noHistory.write("A\r\nB\r\nC")
        assertEquals(0, noHistory.historySize)
        assertEquals("C", noHistory.screenLine(0).text())
    }

    @Test fun sharedLinksAreChargedOnceAndOverflowPreservesTextAndUnderline() {
        val uri = "https://example.test/first"
        val limit = linkBytes(TerminalHyperlink("", uri)).toInt()
        val session = TerminalSession(5, 1, limits = TerminalLimits(hyperlinkBytesPerLine = limit))
        session.write(link(uri) + "AB" + "\u001b[4;58;2;1;2;3m" + link("https://example.test/other") + "C")
        val line = session.screenLine(0)
        assertEquals(uri, line.hyperlink(0)?.uri)
        assertSame(line.hyperlink(0), line.hyperlink(1))
        assertNull(line.hyperlink(2))
        assertEquals("ABC", line.text())
        assertEquals(0x010203, line.underlineColor(2))
        assertEquals(TerminalStyle.UNDERLINE, line.style(2))
        assertEquals(limit.toLong(), line.hyperlinkBytes)

        session.write("\r\u001b[2X" + link(uri) + "D")
        assertEquals(uri, line.hyperlink(0)?.uri, "erasing the last reference releases link capacity")
        assertNull(line.hyperlink(1))
        session.write("\u001b[2K")
        assertEquals(0L, line.hyperlinkBytes)
        assertEquals(128L + 5 * 16, line.storageBytes, "cleared optional arrays/maps must be released")
    }

    @Test fun equalOscValuesHaveSeparateLifetimesAndSnapshotSurvivesRecycling() {
        val uri = "https://example.test/"
        val one = linkBytes(TerminalHyperlink("", uri)).toInt()
        val session = TerminalSession(4, 1, scrollback = 0, limits = TerminalLimits(hyperlinkBytesPerLine = one))
        session.write(link(uri) + "á" + link(uri) + "b")
        val line = session.screenLine(0)
        assertNull(line.hyperlink(1), "a new OSC pen holds another string object, even for an equal URI")
        val snapshot = line.snapshot(session.colors.snapshot())
        session.write(link("") + "\r\nplain")
        assertEquals("áb", snapshot.text())
        assertEquals(uri, snapshot.hyperlink(0)?.uri)
        assertNull(snapshot.hyperlink(1))
        assertEquals(one.toLong(), snapshot.hyperlinkBytes)
        assertEquals(0L, line.hyperlinkBytes)
        assertEquals(128L + 4 * 16, line.storageBytes)
    }

    @Test fun resizeEnforcesByteBudgetAndKeepsNewestCellsAndCursor() {
        val session = TerminalSession(20, 2, scrollback = 100,
            limits = TerminalLimits(historyBytes = 288))
        session.write("x".repeat(96) + "WXYZ")
        session.resize(1, 2)
        assertEquals(listOf("W", "X", "Y", "Z"), (0 until session.lineCount).map { session.line(it).text() })
        assertEquals(288L, session.historyStorageBytes)
        assertEquals(1, session.cursorRow)
        assertEquals(0, session.cursorColumn)
        session.resize(20, 2)
        assertEquals("WXYZ", session.screenLine(0).text())
        assertEquals(0L, session.historyStorageBytes)
    }

    @Test fun mergingWrappedRowsAppliesTheDestinationLinkBudgetWithoutLosingText() {
        val first = "https://example.test/first"
        val second = "https://example.test/other"
        val session = TerminalSession(2, 2, limits = TerminalLimits(
            hyperlinkBytesPerLine = linkBytes(TerminalHyperlink("", first)).toInt()))
        session.write(link(first) + "AB" + link(second) + "\u001b[58;2;1;2;3mCD")
        assertEquals(second, session.screenLine(1).hyperlink(0)?.uri)
        session.resize(4, 2)
        val line = session.screenLine(0)
        assertEquals("ABCD", line.text())
        assertEquals(first, line.hyperlink(0)?.uri)
        assertSame(line.hyperlink(0), line.hyperlink(1))
        assertNull(line.hyperlink(2))
        assertNull(line.hyperlink(3))
        assertEquals(0x010203, line.underlineColor(2))
        assertEquals(0x010203, line.underlineColor(3))
    }

    @Test fun richOutputResizeAndBothScreensKeepIndependentStorageBounds() {
        val random = Random(0x524553)
        val session = TerminalSession(32, 4, scrollback = 100,
            limits = TerminalLimits(historyBytes = 8_192, hyperlinkBytesPerLine = 512))
        repeat(1200) { step ->
            when (step % 11) {
                0 -> session.resize(random.nextInt(1, 49), random.nextInt(1, 9))
                1 -> session.write("\u001b[?1049h")
                2 -> session.write("\u001b[?1049l")
                3 -> session.write("\r\u001b[2K")
                else -> session.write(link("https://example.test/$step/" + "x".repeat(random.nextInt(300))) +
                    "a" + "́".repeat(random.nextInt(1, 100)) + "\u001b[58;2;1;2;3m界\r\n")
            }
            assertTrue(session.historyStorageBytes <= session.limits.historyBytes, "history at $step")
            if (!session.alternateScreen) assertEquals(session.historyStorageBytes,
                (0 until session.historySize).sumOf { session.line(it).storageBytes })
            for (y in 0 until session.lineCount) {
                val line = session.line(y)
                val identities = mutableListOf<TerminalHyperlink>()
                var graphemeBytes = 0L
                for (x in 0 until line.columns) {
                    line.hyperlink(x)?.let { value ->
                        if (identities.none { it === value }) identities.add(value)
                    }
                    val text = line.text(x)
                    assertTrue(text.length <= 130, "bounded UTF-16 grapheme storage")
                    if (text != codePointString(line.codePoint(x))) graphemeBytes += text.length * 2L
                }
                val payload = identities.sumOf(::linkBytes)
                assertEquals(payload, line.hyperlinkBytes, "retained hyperlink references at $step/$y")
                assertTrue(payload <= session.limits.hyperlinkBytesPerLine)
                assertTrue(line.storageBytes >= 128L + line.columns * 16L + graphemeBytes + payload)
            }
        }
        session.write("\u001b[?1049l"); session.reset()
        assertEquals(0L, session.historyStorageBytes)
    }

    @Test fun invalidBudgetsAreRejectedAndLinksCanBeDisabled() {
        assertFailsWith<IllegalArgumentException> { TerminalLimits(historyBytes = -1) }
        assertFailsWith<IllegalArgumentException> { TerminalLimits(hyperlinkBytesPerLine = -1) }
        val session = TerminalSession(limits = TerminalLimits(hyperlinkBytesPerLine = 0))
        session.write(link("https://example.test") + "hello")
        assertEquals("hello", session.screenLine(0).text())
        assertNull(session.screenLine(0).hyperlink(0))
    }

    @Test fun inputRingPreservesUtf8AcrossWrapAndRejectsOverflowAtomically() {
        val queue = TerminalInputBuffer(8)
        assertNull(queue.buffer)
        queue.add("abcde")
        queue.consume(4)
        queue.add("界xy")
        val chunks = mutableListOf<Byte>()
        while (queue.size > 0) {
            val count = queue.contiguousSize
            chunks.addAll(queue.buffer!!.copyOfRange(queue.offset, queue.offset + count).toList())
            queue.consume(count)
        }
        assertEquals("e界xy", chunks.toByteArray().decodeToString())
        val storage = queue.buffer
        repeat(8) { queue.add("x") }
        assertSame(storage, queue.buffer)
        assertFailsWith<IllegalArgumentException> { queue.add("y") }
        queue.consume(2)
        assertFailsWith<IllegalArgumentException> { queue.add("界") }
        assertFailsWith<IllegalArgumentException> { queue.add("x".repeat(100_000)) }
        assertEquals(6, queue.size)
        assertEquals("xxxxxx", queue.buffer!!.copyOfRange(queue.offset, queue.offset + queue.size).decodeToString())
        queue.clear()
        assertNull(queue.buffer)
        assertEquals(0, queue.size)
        queue.add("😀")
        assertEquals(4, queue.size)
    }
}
