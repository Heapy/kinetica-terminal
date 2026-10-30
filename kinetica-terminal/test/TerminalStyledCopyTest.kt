package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalStyledCopyTest {
    @Test fun styledSelectionsMatchOriginalGhostty() {
        for (encoded in terminalCopyFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession(case.getValue("columns").jsonPrimitive.int, case.getValue("rows").jsonPrimitive.int)
            session.write("\u001b]10;#123456\u0007\u001b]11;#abcdef\u0007")
            session.write(case.getValue("paletteSetup").jsonPrimitive.content)
            session.write(case.getValue("text").jsonPrimitive.content)
            val mappings = case.getValue("mappings").jsonArray.map { item ->
                val value = item.jsonObject
                TerminalClipboardCodepointMap(value.getValue("first").jsonPrimitive.int..value.getValue("last").jsonPrimitive.int,
                    value.getValue("replacement").jsonPrimitive.content)
            }
            for ((index, item) in case.getValue("selections").jsonArray.withIndex()) {
                val value = item.jsonObject
                val p = value.getValue("range").jsonArray.map { it.jsonPrimitive.int }
                val range = TerminalSelectionRange(TerminalCellPosition(p[1], p[0]), TerminalCellPosition(p[3], p[2]),
                    value.getValue("rectangle").jsonPrimitive.boolean)
                for (mapped in listOf(false, true)) for (trim in listOf(false, true)) {
                    val expected = value.getValue("styled").jsonArray[(if (mapped) 2 else 0) + (if (trim) 1 else 0)].jsonArray
                    for ((formatIndex, format) in listOf(TerminalCopyFormat.VT, TerminalCopyFormat.HTML, TerminalCopyFormat.MIXED).withIndex()) {
                        val data = terminalCopyData(range, session::line, session.theme, format, trim, if (mapped) mappings else emptyList())
                        val context = "${case["name"]} selection=$index format=$format trim=$trim mapped=$mapped"
                        assertEquals(expected[formatIndex].jsonPrimitive.content, if (format == TerminalCopyFormat.VT) data.text else data.html, context)
                        if (format == TerminalCopyFormat.MIXED) {
                            val field = if (mapped) { if (trim) "mappedTrimmed" else "mapped" } else { if (trim) "trimmed" else "plain" }
                            assertEquals(value.getValue(field).jsonPrimitive.content, data.text, context)
                        }
                    }
                }
            }
        }
    }

    @Test fun heldSelectionRetainsStylePaletteLinksAndHistoryAcrossFormatChanges() {
        val session = TerminalSession(20, 2)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("\u001b[31ma first\r\n\u001b]8;id=link;https://example.org\u0007second\u001b]8;;\u0007\r\n\u001b[4:3mthird")
            viewport.selectAll()
            val formats = TerminalCopyFormat.entries.associateWith { assertNotNull(viewport.copyData(it)) }
            session.write("\u001b[?2026h\u001b]4;1;#00ff00\u0007\u001b]10;#ff0000\u0007\u001b[3J\u001b[HCHANGED")
            for (format in TerminalCopyFormat.entries) assertEquals(formats[format], viewport.copyData(format), "$format keeps the presented frame")
            val mapped = assertNotNull(viewport.copyData(TerminalCopyFormat.MIXED, codepointMap = listOf(TerminalClipboardCodepointMap('a'.code, "<&"))))
            assertTrue(assertNotNull(mapped.text).startsWith("<& first")); assertContains(assertNotNull(mapped.html), "&lt;&amp;")
            viewport.clearSelection(); assertNull(viewport.copyData())
            viewport.selectAll()
            assertFalse(assertNotNull(viewport.copyData()?.html).contains("CHANGED"))
            session.write("\u001b[?2026l"); assertNull(viewport.copyData())
        } finally { viewport.dispose() }
        assertNull(viewport.copyData())
    }

    @Test fun longHistoryCopiesTextStylesAndLinksAndSurvivesAHeldFrame() {
        val session = TerminalSession(16, 3)
        val viewport = TerminalViewport(session, {})
        val count = 600
        try {
            session.write((0 until count).joinToString("\r\n") { i ->
                "\u001b[${31 + i % 6};4:3m\u001b]8;id=$i;https://example.org/$i\u0007row-$i Σ\u001b]8;;\u0007\u001b[0m"
            })
            viewport.selectAll()
            val expectedText = (0 until count).joinToString("\n") { "row-$it Σ" }
            val colors = session.colors.snapshot()
            val original = (0 until count).map { session.line(it).snapshot(colors) }
            val vt = assertNotNull(viewport.copyData(TerminalCopyFormat.VT)?.text)
            val mixed = assertNotNull(viewport.copyData())
            assertEquals(expectedText, mixed.text)
            for (i in 0 until count) assertContains(assertNotNull(mixed.html), "<a href=\"https://example.org/$i\">row-$i &#931;")
            session.write("\u001b[?2026h\u001b[3J\u001b[2J\u001b[Hchanged")
            assertEquals(vt, viewport.copyData(TerminalCopyFormat.VT)?.text)
            assertEquals(mixed, viewport.copyData())
            val replay = TerminalSession(16, 3)
            replay.write(vt)
            assertEquals(count - replay.rows, replay.historySize)
            for (y in 0 until count) {
                val expected = original[y]
                val actual = replay.line(y)
                assertEquals(expected.text(), actual.text(), "row $y")
                for (x in expected.text().indices) {
                    assertEquals(expected.foreground(x), actual.foreground(x), "color $y,$x")
                    assertEquals(expected.style(x), actual.style(x), "style $y,$x")
                    assertEquals(expected.hyperlink(x), actual.hyperlink(x), "link $y,$x")
                }
            }
        } finally { viewport.dispose() }
    }
}
