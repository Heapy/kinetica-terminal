package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalShapingTest {
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
