package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalAccessibleDocumentTest {
    @Test fun coordinateCollectionSkipsUnrequestedHistoryRows() {
        val session = TerminalSession(80, 2, 200)
        repeat(100) { session.write("row-$it\tvalue\r\n") }
        var cells = 0
        val text = terminalSelectionText(
            TerminalSelectionRange(TerminalCellPosition(0, 0), TerminalCellPosition(session.lineCount - 1, 80)),
            session::line,
            cellRange = object : TerminalTextPositions {
                override fun tracks(row: Int): Boolean = row == 50
                override fun cell(row: Int, column: Int, width: Int, start: Int, end: Int) {
                    assertEquals(50, row)
                    assertTrue(end > start)
                    cells++
                }
            },
        )
        assertContains(text, "row-0"); assertContains(text, "row-99")
        assertTrue(cells in 1..81, "Coordinate callbacks must scale with the requested row, not all history")
    }

    @Test fun fullScreenTextMatchesOriginalGhosttyReadTextFormatting() {
        var checked = 0
        for (encoded in terminalCopyFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            if (case.getValue("mappings").jsonArray.isNotEmpty()) continue
            for (bytewise in listOf(false, true)) {
                val session = TerminalSession(case.getValue("columns").jsonPrimitive.int, case.getValue("rows").jsonPrimitive.int)
                val input = case.getValue("text").jsonPrimitive.content.encodeToByteArray()
                if (bytewise) input.forEach { session.write(byteArrayOf(it)) } else session.write(input)
                val full = case.getValue("selections").jsonArray.first { item ->
                    val value = item.jsonObject
                    val range = value.getValue("range").jsonArray.map { it.jsonPrimitive.int }
                    !value.getValue("rectangle").jsonPrimitive.boolean && range == listOf(0, 0, session.columns, session.lineCount - 1)
                }.jsonObject
                val expected = full.getValue("plain").jsonPrimitive.content
                assertEquals(expected, terminalAccessibleDocument(session.lineCount, session::line, null).text, "${case["name"]} bytewise=$bytewise")
                val all = TerminalSelectionRange(TerminalCellPosition(0, 0), TerminalCellPosition(session.lineCount - 1, session.columns))
                val selected = terminalAccessibleDocument(session.lineCount, session::line, all)
                assertEquals(expected, selected.text)
                assertEquals(expected, selected.selections.joinToString("") { selected.text.substring(it.start, it.end) })
                for (item in case.getValue("selections").jsonArray) {
                    val value = item.jsonObject
                    if (value.getValue("rectangle").jsonPrimitive.boolean) continue
                    val points = value.getValue("range").jsonArray.map { it.jsonPrimitive.int }
                    val range = TerminalSelectionRange(TerminalCellPosition(points[1], points[0]),
                        TerminalCellPosition(points[3], points[2]))
                    val document = terminalAccessibleDocument(session.lineCount, session::line, range)
                    assertEquals(value.getValue("plain").jsonPrimitive.content, document.selected().joinToString(""),
                        "${case["name"]} range=$points bytewise=$bytewise")
                }
                checked++
            }
        }
        assertEquals(44, checked)
    }

    @Test fun historyAndRepeatedUnicodeUseOneUtf16CoordinateSpace() {
        val session = TerminalSession(8, 2)
        session.write("A😀B\r\nA😀B\r\nlast")
        assertEquals(1, session.historySize)
        val range = TerminalSelectionRange(TerminalCellPosition(1, 1), TerminalCellPosition(1, 3))
        val document = terminalAccessibleDocument(session.lineCount, session::line, range)
        assertEquals("A😀B\nA😀B\nlast", document.text)
        assertEquals(listOf(TerminalAccessibleRange(6, 8)), document.selections)
        assertEquals("😀", document.selected().single())
    }

    @Test fun softWrapPaddingDoesNotOccupyACharacterButWideEdgesExpand() {
        val session = TerminalSession(4, 2)
        session.write("abc界Z")
        val range = TerminalSelectionRange(TerminalCellPosition(1, 1), TerminalCellPosition(1, 2))
        val document = terminalAccessibleDocument(session.lineCount, session::line, range)
        assertEquals("abc界Z", document.text)
        assertEquals(listOf(TerminalAccessibleRange(3, 4)), document.selections)
        assertEquals("界", document.selected().single())
    }

    @Test fun syntheticSpacesBlankRowsAndWrittenSpacesHaveStableOffsets() {
        val session = TerminalSession(8, 4)
        session.write("a\u001b[5Gb\r\n\r\nc  ")
        fun document(start: TerminalCellPosition, end: TerminalCellPosition) =
            terminalAccessibleDocument(session.lineCount, session::line, TerminalSelectionRange(start, end))
        val spaces = document(TerminalCellPosition(0, 1), TerminalCellPosition(0, 4))
        assertEquals("a   b\n\nc  ", spaces.text)
        assertEquals(listOf("   "), spaces.selected())
        assertEquals(listOf("\n"), document(TerminalCellPosition(1, 0), TerminalCellPosition(2, 0)).selected())
        assertEquals(listOf("  "), document(TerminalCellPosition(2, 1), TerminalCellPosition(2, 3)).selected())
        assertTrue(document(TerminalCellPosition(3, 0), TerminalCellPosition(3, 8)).selections.isEmpty())
    }

    @Test fun rectangularHistorySelectionPreservesCompressionAndExpandsGlyphs() {
        val session = TerminalSession(8, 2)
        session.write("a😀z\r\nb界x\r\nlast")
        val history = session.line(0)
        history.compress()
        assertTrue(history.compressed, "Exercise a genuinely compressed history row")
        val range = TerminalSelectionRange(TerminalCellPosition(0, 2), TerminalCellPosition(1, 3), rectangle = true)
        val document = terminalAccessibleDocument(session.lineCount, session::line, range)
        assertEquals(listOf("😀", "界"), document.selected())
        assertTrue(history.compressed, "Document/range queries must restore compressed storage")
    }

    private fun TerminalAccessibleText.selected() = selections.map { text.substring(it.start, it.end) }
}
