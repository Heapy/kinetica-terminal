package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalSemanticLineTest {
    @Test fun semanticLineBoundsMatchOriginalGhosttyAtEveryCell() {
        for (encoded in terminalSemanticLineFixtures()) for (chunked in listOf(false, true)) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession(case.getValue("columns").jsonPrimitive.int, case.getValue("rows").jsonPrimitive.int)
            for ((index, element) in case.getValue("events").jsonArray.withIndex()) {
                val event = element.jsonObject
                event["text"]?.jsonPrimitive?.content?.encodeToByteArray()?.let { bytes ->
                    if (chunked) bytes.forEach { session.write(byteArrayOf(it)) } else session.write(bytes)
                }
                event["resize"]?.jsonArray?.let { session.resize(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) }
                val expected = case.getValue("snapshots").jsonArray[index].jsonObject
                val label = "${case["name"]} event=$index chunked=$chunked"
                assertEquals(expected.getValue("columns").jsonPrimitive.int, session.columns, label)
                assertEquals(expected.getValue("lines").jsonPrimitive.int, session.lineCount, label)
                assertEquals(expected.getValue("markers").jsonArray.map { it.jsonPrimitive.int },
                    (0 until session.lineCount).map { session.line(it).promptMarker.ordinal }, "$label row markers")
                assertEquals(expected.getValue("semantics").jsonArray.map { it.jsonPrimitive.int },
                    (0 until session.lineCount).flatMap { y -> (0 until session.columns).map { session.line(y).semanticContent(it).ordinal } },
                    "$label cell semantics; text=" + (0 until session.lineCount).map { session.line(it).text() })
                for (item in expected.getValue("ranges").jsonArray) {
                    val values = item.jsonArray.map { it.jsonPrimitive.int }
                    val (x, y) = values
                    val actual = terminalSemanticLineRange(y, x, 0, session.lineCount - 1, session::line)
                    assertEquals(values, listOf(x, y, actual.start.column, actual.start.line, actual.end.column, actual.end.line), label)
                }
                val selection = TerminalSelection({ 0 }, { session.lineCount - 1 }, session::line, session::indexOfLine)
                fun checkSelected(value: JsonElement, context: String) {
                    val actual = selection.range()
                    if (value == JsonNull) {
                        assertTrue(actual == null || actual.start == actual.end, context)
                        assertEquals("", selection.text(), context)
                        return
                    }
                    val result = value.jsonObject
                    val points = result.getValue("range").jsonArray.map { it.jsonPrimitive.int }
                    // Ghostty stores inclusive cell pins; our display ranges include complete wide glyphs.
                    val end = points[2] + if (points[2] < session.columns && session.line(points[3]).width(points[2]) == 0) 1 else 0
                    assertEquals(TerminalSelectionRange(TerminalCellPosition(points[1], points[0]),
                        TerminalCellPosition(points[3], end)), actual, context)
                    assertEquals(result.getValue("text").jsonPrimitive.content, selection.text(), context)
                }
                try {
                    for ((cell, selected) in expected.getValue("selected").jsonArray.withIndex()) {
                        selection.begin(TerminalCellPosition(cell / session.columns, cell % session.columns), TerminalSelectionMode.LINE)
                        checkSelected(selected, "$label select=$cell")
                    }
                    for ((gestureIndex, gesture) in expected.getValue("gestures").jsonArray.withIndex()) {
                        for ((stepIndex, step) in gesture.jsonArray.withIndex()) {
                            val value = step.jsonObject
                            val point = value.getValue("position").jsonArray.map { it.jsonPrimitive.int }
                            val position = TerminalCellPosition(point[1], point[0])
                            if (stepIndex == 0) selection.begin(position, TerminalSelectionMode.LINE) else selection.extend(position)
                            checkSelected(value.getValue("selected"), "$label gesture=$gestureIndex step=$stepIndex")
                        }
                    }
                } finally { selection.clear() }
            }
        }
    }
}
