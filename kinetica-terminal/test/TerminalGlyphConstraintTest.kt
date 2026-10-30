package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.test.*

class TerminalGlyphConstraintTest {
    @Test fun originalLookupTablesCoverEveryUnicodeCodepoint() {
        val ranges = fixtures.getValue("ranges").jsonArray
        assertEquals(459, ranges.size)
        for (value in ranges) {
            val range = value.jsonObject
            val expected = range.getValue("constraint").takeUnless { it == JsonNull }?.jsonObject?.constraint()
            val symbol = range.getValue("symbol").jsonPrimitive.boolean
            for (code in range.int("start") until range.int("end")) {
                val actual = TerminalGlyphConstraintData.constraint(code)
                if (actual != expected) assertEquals(expected, actual, "Constraint U+${code.toString(16)}")
                if (TerminalGlyphConstraintData.isSymbol(code) != symbol) assertEquals(symbol,
                    TerminalGlyphConstraintData.isSymbol(code), "Symbol U+${code.toString(16)}")
            }
        }
        for (code in listOf(-1, 0x110000, Int.MAX_VALUE)) {
            assertNull(TerminalGlyphConstraintData.constraint(code)); assertFalse(TerminalGlyphConstraintData.isSymbol(code))
        }
    }

    @Test fun allOriginalGeometryResultsMatch() {
        val cases = fixtures.getValue("geometry").jsonArray
        assertEquals(2250, cases.size)
        for ((index, value) in cases.withIndex()) {
            val case = value.jsonObject
            val m = case.getValue("metrics").jsonObject
            val metrics = TerminalGlyphMetrics(m.number("cell_width"), m.number("cell_height"), m.number("face_width"),
                m.number("face_height"), m.number("face_y"), m.number("icon_height"), m.number("icon_height_single"))
            val actual = case.getValue("constraint").jsonObject.constraint().constrain(case.getValue("glyph").jsonObject.bounds(), metrics, case.int("width"))
            val expected = case.getValue("expected").jsonObject.bounds()
            assertEquals(expected.width, actual.width, 1e-9, "Case $index width")
            assertEquals(expected.height, actual.height, 1e-9, "Case $index height")
            assertEquals(expected.x, actual.x, 1e-9, "Case $index x")
            assertEquals(expected.y, actual.y, 1e-9, "Case $index y")
        }
    }

    @Test fun availableColumnsMatchOriginalTerminalRows() {
        val cases = fixtures.getValue("rows").jsonArray
        assertEquals(759, cases.size)
        for ((index, value) in cases.withIndex()) {
            val case = value.jsonObject
            val session = TerminalSession(case.int("columns"), 1)
            session.write(case.getValue("text").jsonPrimitive.content)
            val line = session.screenLine(0)
            val expected = case.getValue("expected").jsonArray.map { it.jsonPrimitive.int }
            assertEquals(expected, (0 until line.columns).map { terminalGlyphConstraintWidth(line, it) }, "Row $index")
        }
    }

    private fun JsonObject.bounds() = TerminalGlyphBounds(number("width"), number("height"), number("x"), number("y"))
    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.double
    private fun JsonObject.constraint() = TerminalGlyphConstraint(
        size = TerminalGlyphSizing.valueOf(getValue("size").jsonPrimitive.content.uppercase()),
        vertical = TerminalGlyphAlignment.valueOf(getValue("align_vertical").jsonPrimitive.content.uppercase()),
        horizontal = TerminalGlyphAlignment.valueOf(getValue("align_horizontal").jsonPrimitive.content.uppercase()),
        padTop = number("pad_top"), padLeft = number("pad_left"), padRight = number("pad_right"), padBottom = number("pad_bottom"),
        relativeWidth = number("relative_width"), relativeHeight = number("relative_height"),
        relativeX = number("relative_x"), relativeY = number("relative_y"),
        maxAspectRatio = getValue("max_xy_ratio").takeUnless { it == JsonNull }?.jsonPrimitive?.double,
        maxColumns = int("max_constraint_width"), height = TerminalGlyphHeight.valueOf(getValue("height").jsonPrimitive.content.uppercase()),
    )

    companion object {
        private val fixtures by lazy { Json.parseToJsonElement(terminalGlyphConstraintFixtures()).jsonObject }
    }
}
