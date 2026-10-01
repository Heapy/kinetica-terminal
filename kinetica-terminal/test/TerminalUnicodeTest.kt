package io.heapy.kinetica.terminal

import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*

class TerminalUnicodeTest {
    @Test fun configuredGraphemeModeIsRestoredByReset() {
        val session = TerminalSession(graphemeClustering = true)
        session.write("👨‍👩‍👧‍👦")
        assertEquals(2, session.cursorColumn)
        session.write("\u001b[?2027l")
        assertFalse(session.graphemeClustering)
        session.reset()
        assertTrue(session.graphemeClustering)
    }

    @Test fun allCodepointWidthsMatchOriginalGhostty() {
        val ranges = Json.parseToJsonElement(Base64.decode(ghosttyUnicodeWidths()).decodeToString()).jsonArray
            .map { it.jsonPrimitive.int }
        assertEquals(0, ranges[0])
        for (i in ranges.indices step 2) {
            val end = ranges.getOrNull(i + 2) ?: 0x110000
            for (code in ranges[i] until end) {
                val actual = terminalCharacterWidth(code)
                if (ranges[i + 1] != actual) assertEquals(ranges[i + 1], actual, "Width of U+${code.toString(16)}")
            }
        }
        assertEquals(1, terminalCharacterWidth(-1))
        assertEquals(1, terminalCharacterWidth(0x110000))
    }

    @Test fun unicode18GraphemeBoundariesMatchEveryConformanceVector() {
        val lines = unicodeBreakCases().lines()
        assertEquals(853, lines.size)
        for ((number, line) in lines.withIndex()) {
            val tokens = line.split(' ').filter { it.isNotEmpty() }
            val codes = tokens.filterIndexed { index, _ -> index % 2 == 1 }.map { it.toInt(16) }
            var state = 0
            for (i in 1 until codes.size) {
                val result = graphemeStep(codes[i - 1], codes[i], state, terminalTailoring = false)
                assertEquals(tokens[i * 2] == "÷", result and 1 != 0, "Unicode vector ${number + 1}, boundary $i: $line")
                state = result ushr 1
            }
        }
    }

    @Test fun terminalTailoringChangesOnlyDeclaredEmojiModifierBoundaries() {
        for ((number, line) in unicodeBreakCases().lines().withIndex()) {
            val tokens = line.split(' ').filter { it.isNotEmpty() }
            val codes = tokens.filterIndexed { index, _ -> index % 2 == 1 }.map { it.toInt(16) }
            var state = 0
            for (i in 1 until codes.size) {
                var expected = tokens[i * 2] == "÷"
                if (graphemeClass(codes[i]) == GraphemeClass.MODIFIER && graphemeClass(codes[i - 1]) != GraphemeClass.MODIFIER_BASE) {
                    assertFalse(expected, "Upstream modifier exception changed in vector ${number + 1}")
                    expected = true
                }
                val result = graphemeStep(codes[i - 1], codes[i], state)
                assertEquals(expected, result and 1 != 0, "Tailored vector ${number + 1}, boundary $i: $line")
                state = result ushr 1
            }
        }
    }
}
