package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalCopyMappingTest {
    @Test fun clipboardMappingsMatchOriginalGhosttyFormatter() {
        for (encoded in terminalCopyFixtures()) for (chunked in listOf(false, true)) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession(case.getValue("columns").jsonPrimitive.int, case.getValue("rows").jsonPrimitive.int)
            val bytes = case.getValue("text").jsonPrimitive.content.encodeToByteArray()
            if (chunked) bytes.forEach { session.write(byteArrayOf(it)) } else session.write(bytes)
            val mappings = case.getValue("mappings").jsonArray.map { item ->
                val value = item.jsonObject
                TerminalClipboardCodepointMap(value.getValue("first").jsonPrimitive.int..value.getValue("last").jsonPrimitive.int,
                    value.getValue("replacement").jsonPrimitive.content)
            }
            for ((index, item) in case.getValue("selections").jsonArray.withIndex()) {
                val value = item.jsonObject
                val points = value.getValue("range").jsonArray.map { it.jsonPrimitive.int }
                val range = TerminalSelectionRange(TerminalCellPosition(points[1], points[0]),
                    TerminalCellPosition(points[3], points[2]), value.getValue("rectangle").jsonPrimitive.boolean)
                for (trim in listOf(false, true)) {
                    val context = "${case["name"]} selection=$index trim=$trim chunked=$chunked"
                    val content = terminalSelectionCopyContent(range, session::line, trim)
                    assertEquals(value.getValue(if (trim) "trimmed" else "plain").jsonPrimitive.content,
                        content.format(emptyList()), context)
                    assertEquals(value.getValue(if (trim) "mappedTrimmed" else "mapped").jsonPrimitive.content,
                        content.format(mappings), context)
                }
            }
        }
    }

    @Test fun mappingPoliciesCanChangeWhileHistoryAndVisibleTextAreFrozen() {
        val session = TerminalSession(8, 2)
        val viewport = TerminalViewport(session, {})
        val mappings = listOf(TerminalClipboardCodepointMap(' '.code, "_"), TerminalClipboardCodepointMap('a'.code, "α"))
        try {
            session.write("a b\r\nc d\r\ne f"); viewport.selectAll()
            val original = viewport.selectedText()
            assertEquals("α_b\nc_d\ne_f", viewport.copyText(false, mappings))
            assertEquals("α b\nc d\ne f", viewport.copyText(true, mappings), "Buffered spaces bypass mappings after trimming")
            session.write("\u001b[?2026h\u001b[3J\u001b[HCHANGED")
            assertEquals("α_b\nc_d\ne_f", viewport.copyText(false, mappings))
            assertEquals("α b\nc d\ne f", viewport.copyText(true, mappings))
            assertEquals("a B\nc d\ne f", viewport.copyText(true, listOf(TerminalClipboardCodepointMap('b'.code, "B"))))
            assertEquals(original, viewport.copyText(false)); assertEquals(original, viewport.selectedText())
            viewport.clearSelection(); assertEquals("", viewport.copyText(false, mappings))
            viewport.selectAll(); assertEquals("c_d\ne_f", viewport.copyText(false, mappings))
            session.write("\u001b[?2026l"); assertEquals("", viewport.copyText(false, mappings))
        } finally { viewport.dispose() }
    }

    @Test fun mappingsAreUnicodeRangesAndValidateReplacementScalars() {
        assertFailsWith<IllegalArgumentException> { TerminalClipboardCodepointMap(-1..5, "a") }
        assertFailsWith<IllegalArgumentException> { TerminalClipboardCodepointMap(5..4, "a") }
        assertFailsWith<IllegalArgumentException> { TerminalClipboardCodepointMap(0..0x110000, "a") }
        for (value in listOf("\ud800", "\udc00", "a\ud800b", "\udc00\ud800")) {
            assertFailsWith<IllegalArgumentException> { TerminalClipboardCodepointMap(0..0x10ffff, value) }
        }
        assertEquals("😀\u0000", TerminalClipboardCodepointMap(0x10ffff, "😀\u0000").replacement)
        assertEquals("", TerminalClipboardCodepointMap(0..0x10ffff, "").replacement)
    }
}
