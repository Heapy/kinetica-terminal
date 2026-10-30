package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalCopyStorageTest {
    private fun rows(count: Int = 600): Array<TerminalLine> {
        val session = TerminalSession(80, 3, count)
        session.write((0 until count).joinToString("\r\n") { i ->
            "\u001b[31;4:3m\u001b]8;id=$i;https://example.org/$i\u0007row-$i á界 &  \u001b]8;;\u0007\u001b[0m"
        })
        return Array(count) { session.line(it).snapshot(session.colors) }.also { rows ->
            // Exercise wrapped rows and an unwritten row between styled runs.
            rows[2].wrapped = true
            rows[4].clear(0, 80, -1, -1, 0)
        }
    }

    @Test fun everyCopyFormatRestoresCompressedRowsWithoutInflatingTheWholeSelection() {
        val rows = rows()
        val range = TerminalSelectionRange(TerminalCellPosition(0, 0), TerminalCellPosition(rows.lastIndex, 80))
        val mappings = listOf(TerminalClipboardCodepointMap('&'.code, "<mapped>"))
        for (format in TerminalCopyFormat.entries) for (trim in listOf(false, true)) {
            val expected = terminalCopyData(range, { rows[it] }, TerminalTheme(), format, trim, mappings)
            rows.forEach { it.compress() }
            assertTrue(rows.all { it.compressed })
            // A visible row shared with the renderer must keep its original resident state.
            rows.last().text()
            val bytes = rows.sumOf { it.residentStorageBytes }
            val actual = terminalCopyData(range, { index ->
                assertTrue(rows.count { !it.compressed } <= 2, "Only the current row and visible row may be decoded")
                rows[index]
            }, TerminalTheme(), format, trim, mappings)
            assertEquals(expected, actual, "$format trim=$trim")
            assertTrue(rows.dropLast(1).all { it.compressed }, "$format releases decoded history")
            assertFalse(rows.last().compressed, "Copy must not compress the visible row")
            assertEquals(bytes, rows.sumOf { it.residentStorageBytes })
        }
    }

    @Test fun retainedCopiesAndMappingCachesKeepRowsCompressed() {
        val rows = rows(30)
        val range = TerminalSelectionRange(TerminalCellPosition(10, 0), TerminalCellPosition(39, 80))
        val expected = terminalCopyData(range, { rows[it - 10] }, TerminalTheme(), TerminalCopyFormat.MIXED, false, emptyList())
        rows.forEach { it.compress() }
        val bytes = rows.sumOf { it.residentStorageBytes }
        val snapshot = TerminalCopySnapshot(range, rows, TerminalTheme())
        repeat(2) {
            assertEquals(expected.text, snapshot.text(false, emptyList()))
            assertEquals(expected, snapshot.data(TerminalCopyFormat.MIXED, false, emptyList()))
            assertTrue(rows.all { it.compressed })
            assertEquals(bytes, rows.sumOf { it.residentStorageBytes })
        }
    }

    @Test fun failedPlainCopyReleasesTheDecodedRow() {
        val rows = rows(8)
        rows.forEach { it.compress() }
        val bytes = rows.sumOf { it.residentStorageBytes }
        val range = TerminalSelectionRange(TerminalCellPosition(0, 0), TerminalCellPosition(7, 80))
        assertFailsWith<IllegalStateException> {
            terminalSelectionText(range, { rows[it] }) { _, _ -> error("interrupted formatter") }
        }
        assertTrue(rows.all { it.compressed })
        assertEquals(bytes, rows.sumOf { it.residentStorageBytes })
    }

    @Test fun snapshotsAndLegacySelectionTextDoNotExpandCompressedHistory() {
        val session = TerminalSession(80, 3, 100)
        session.write((0..70).joinToString("\r\n") { "row-$it á界" })
        val view = TerminalViewport(session, {})
        try {
            view.scrollTo(session.historySize)
            view.beginSelection(0, 0)
            view.scrollTo(0)
            view.extendSelection(12, 2)
            view.endSelection(12, 2)
            val expected = view.selectedText()
            val rows = (0 until session.historySize).map(session::line)
            rows.forEach { it.compress() }
            val bytes = session.historyResidentStorageBytes
            assertEquals(expected, view.selectedText())
            assertEquals(bytes, session.historyResidentStorageBytes)
            session.write("\u001b[?2026h")
            assertTrue(rows.all { it.compressed }, "Capturing a frame must preserve source history compression")
            assertEquals(bytes, session.historyResidentStorageBytes)
            session.write("\u001b[3J\u001b[HCHANGED")
            assertEquals(expected, view.selectedText())
            assertContains(assertNotNull(view.copyData()?.text), "row-0")
        } finally { view.dispose() }
    }
}
