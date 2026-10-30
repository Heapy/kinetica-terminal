package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalShapingTest {
    @Test fun textRunBoundariesMatchOriginalGhosttyWithoutPlatformFontFallback() {
        var checked = 0
        for (encoded in terminalShapingFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val text = case.getValue("text").jsonPrimitive.content
            // These characters use the primary JetBrains Mono face. Font fallback is
            // a separate platform contract, not inferred from expected run boundaries.
            if (case["font"]?.jsonPrimitive?.content?.let { it != "jetbrains_mono" } == true ||
                text.any { it.code > 127 && it.code !in 0x300..0x36f }) continue
            val columns = case["columns"]?.jsonPrimitive?.int ?: 80
            val session = TerminalSession(columns, 3)
            session.write(text)
            val selected = BooleanArray(columns)
            case["selection"]?.jsonArray?.let { bounds ->
                for (column in bounds[0].jsonPrimitive.int..bounds[1].jsonPrimitive.int) selected[column] = true
            }
            val actual = terminalFontRuns(session.line(case["row"]?.jsonPrimitive?.int ?: 0),
                case["cursor"]?.jsonPrimitive?.intOrNull, selected) { value, _ -> TerminalResolvedFontCell(0, value) }
            val expected = case.getValue("expected").jsonObject.getValue("runs").jsonArray
            val label = case.getValue("name").jsonPrimitive.content
            assertEquals(expected.size, actual.size, label)
            for ((entry, run) in expected.zip(actual)) {
                val original = entry.jsonObject
                assertEquals(original.int("offset"), run.offset, label)
                assertEquals(original.int("cells"), run.input.columns, label)
                val input = original.getValue("input").jsonArray
                assertEquals(input.joinToString("") { codePointString(it.jsonObject.int("codepoint")) }, run.input.text, label)
                assertContentEquals(input.flatMap { point ->
                    List(codePointString(point.jsonObject.int("codepoint")).length) { point.jsonObject.int("cluster") }
                }.toIntArray(), run.input.clusters, label)
            }
            checked++
        }
        assertTrue(checked >= 40, "Must cover styles, cursor, selection, wrapping and long runs")
    }

    @Test fun resolvedFontsSplitRunsAndSelectorsAreRemovedAfterResolution() {
        val line = TerminalLine(8)
        line.put(0, 'a'.code, 1, -1, -1, 0)
        line.put(1, 0x2764, 2, -1, -1, 0)
        line.combine(1, 0xfe0f)
        line.put(2, 0, 0, -1, -1, 0)
        line.put(3, 'b'.code, 1, -1, -1, 0)
        line.put(4, 0x10fffd, 1, -1, -1, 0)
        line.put(5, 'c'.code, 1, -1, -1, 0)
        val seen = mutableSetOf<String>()
        val runs = terminalFontRuns(line, null, BooleanArray(8)) { text, _ ->
            seen += text
            when (text) {
                "❤️" -> TerminalResolvedFontCell(1, text)
                codePointString(0x10fffd) -> TerminalResolvedFontCell(0, "�")
                else -> TerminalResolvedFontCell(0, text)
            }
        }
        assertTrue("❤️" in seen)
        assertEquals(listOf(0, 1, 3), runs.map { it.offset })
        assertEquals(listOf(1, 2, 3), runs.map { it.input.columns })
        assertEquals(listOf("a", "❤", "b�c"), runs.map { it.input.text })
        assertEquals(listOf(0, 1, 0), runs.map { it.font })
        assertContentEquals(intArrayOf(0, 1, 2), runs.last().input.clusters)
    }

    @Test fun geometricBlocksStayOutsideFontRunsAndHiddenCellsAreNotResolved() {
        val line = TerminalLine(6)
        line.put(0, 'a'.code, 1, -1, -1, 0)
        line.put(1, 0x2588, 1, -1, -1, 0)
        line.put(2, 'b'.code, 1, -1, -1, 0)
        line.put(3, 'x'.code, 1, -1, -1, TerminalStyle.HIDDEN)
        line.put(4, 'c'.code, 1, -1, -1, 0)
        val runs = terminalFontRuns(line, null, BooleanArray(6)) { text, _ ->
            assertTrue(text != "x" && text != "█")
            TerminalResolvedFontCell(0, text)
        }
        assertEquals(listOf("a", "█", "b", "c"), runs.map { it.input.text })
        assertEquals(listOf(0, -1, 0, 0), runs.map { it.font })
        assertEquals(listOf(0, 1, 2, 4), runs.map { it.offset })
    }

    @Test fun originalHarfBuzzGlyphsMapToGhosttyTerminalColumns() {
        var checked = 0
        for (encoded in terminalShapingFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            for (value in case.getValue("expected").jsonObject.getValue("runs").jsonArray) {
                val run = value.jsonObject
                val raw = run.getValue("raw").jsonArray
                if (raw.isEmpty()) continue // Original geometric sprites do not go through HarfBuzz.
                val text = StringBuilder()
                val clusters = mutableListOf<Int>()
                val starts = mutableListOf<Int>()
                for (point in run.getValue("input").jsonArray) {
                    starts += text.length
                    val cp = codePointString(point.jsonObject.int("codepoint"))
                    text.append(cp)
                    repeat(cp.length) { clusters += point.jsonObject.int("cluster") }
                }
                val input = TerminalShapingText(text.toString(), clusters.toIntArray(), run.int("cells"))
                var x = 0
                var y = 0
                val glyphs = raw.map { entry ->
                    val glyph = entry.jsonObject
                    // HarfBuzz returns 26.6 fixed point values. Keep the original raw data
                    // independent of our mapper, including rounding each advance separately.
                    fun pixel(name: String) = (glyph.int(name) + 32) shr 6
                    TerminalRawGlyph(run.int("font_index"), glyph.int("glyph"), starts[glyph.int("index")],
                        pixel("x_advance").toDouble(), (x + pixel("x_offset")).toDouble(),
                        (y + pixel("y_offset")).toDouble()).also {
                        x += pixel("x_advance"); y += pixel("y_advance")
                    }
                }
                val expected = run.getValue("glyphs").jsonArray.map { entry ->
                    val glyph = entry.jsonObject
                    TerminalShapedGlyph(run.int("font_index"), glyph.int("glyph_index"), glyph.int("x"),
                        glyph.int("x_offset"), glyph.int("y_offset"))
                }
                assertEquals(expected, terminalPositionGlyphs(input, glyphs), "${case["name"]} offset=${run["offset"]}")
                checked += expected.size
            }
        }
        assertTrue(checked >= 1100, "Must exercise the full original font corpus")
    }

    @Test fun surrogatePairsAndOutOfOrderMarksKeepLigatureOrigin() {
        val text = TerminalShapingText("a\u0301😀b", intArrayOf(0, 0, 1, 1, 3), 4)
        // A mark from a later cluster appears before that cluster's base. Original
        // Ghostty retains the previous origin so the ligature's marks stay attached.
        val glyphs = listOf(
            TerminalRawGlyph(0, 10, 2, 6.0, -0.5, 0.5),
            TerminalRawGlyph(0, 11, 0, 0.0, 7.5, -0.5),
            TerminalRawGlyph(0, 12, 1, 0.0, 4.0, 2.0),
            TerminalRawGlyph(1, 13, 4, 8.0, 6.0, 0.0),
        )
        assertEquals(listOf(
            TerminalShapedGlyph(0, 10, 1, -1, 1),
            TerminalShapedGlyph(0, 11, 1, 8, -1),
            TerminalShapedGlyph(0, 12, 1, 4, 2),
            TerminalShapedGlyph(1, 13, 3, 0, 0),
        ), terminalPositionGlyphs(text, glyphs, reorder = true))
        assertTrue(text.firstInCluster(4))
        assertFalse(text.firstInCluster(1))
    }

    @Test fun malformedClusterMappingsCannotReachPlatformShapers() {
        assertFailsWith<IllegalArgumentException> { TerminalShapingText("😀", intArrayOf(0, 1), 2) }
        assertFailsWith<IllegalArgumentException> { TerminalShapingText("a", intArrayOf(1), 1) }
        assertFailsWith<IllegalArgumentException> { TerminalShapingText("ab", intArrayOf(0), 2) }
        val clusters = intArrayOf(0)
        val input = TerminalShapingText("a", clusters, 1)
        clusters[0] = 100
        assertEquals(0, input.clusters[0])
        assertFailsWith<IllegalArgumentException> {
            terminalPositionGlyphs(input, listOf(TerminalRawGlyph(0, 1, 1, 1.0, 0.0, 0.0)))
        }
    }

    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
}
