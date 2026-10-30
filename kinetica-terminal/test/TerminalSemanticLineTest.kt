package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalSemanticLineTest {
    @Test fun lineAndWordSelectionsMatchOriginalGhosttyAtEveryCell() {
        for (encoded in terminalSemanticLineFixtures()) for (chunked in listOf(false, true)) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession(case.getValue("columns").jsonPrimitive.int, case.getValue("rows").jsonPrimitive.int)
            case["word_boundaries"]?.jsonArray?.let { values ->
                session.selectionWordChars = values.joinToString("") { codePointString(it.jsonPrimitive.int) }
            }
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
                val selection = TerminalSelection({ 0 }, { session.lineCount - 1 }, session::line, session::indexOfLine, wordBoundaries = { session.selectionWordBoundaries })
                fun checkSelected(value: JsonElement, context: String) {
                    val actual = selection.range()
                    if (value == JsonNull) {
                        assertTrue(actual == null || actual.start == actual.end, context)
                        assertEquals("", selection.text(), context)
                        assertEquals("", selection.copyText(true), context)
                        assertEquals("", selection.copyText(false), context)
                        return
                    }
                    val result = value.jsonObject
                    val points = result.getValue("range").jsonArray.map { it.jsonPrimitive.int }
                    // Ghostty stores inclusive cell pins; our display ranges include complete wide glyphs.
                    val end = points[2] + if (points[2] < session.columns && session.line(points[3]).width(points[2]) == 0) 1 else 0
                    assertEquals(TerminalSelectionRange(TerminalCellPosition(points[1], points[0]),
                        TerminalCellPosition(points[3], end)), actual, context)
                    assertEquals(result.getValue("text").jsonPrimitive.content, selection.text(), context)
                    assertEquals(result.getValue("text").jsonPrimitive.content, selection.copyText(false), context)
                    assertEquals(result.getValue("trimmedText").jsonPrimitive.content, selection.copyText(true), context)
                }
                try {
                    selection.selectAll()
                    checkSelected(expected.getValue("all"), "$label select all")
                    selection.clear()
                    for ((dragIndex, item) in expected.getValue("pointerDrags").jsonArray.withIndex()) {
                        val value = item.jsonObject
                        fun point(key: String): TerminalCellPosition = value.getValue(key).jsonArray.let {
                            TerminalCellPosition(it[1].jsonPrimitive.int, it[0].jsonPrimitive.int)
                        }
                        val rectangle = value.getValue("rectangle").jsonPrimitive.boolean
                        val actual = terminalPointerSelectionRange(point("click"), point("drag"),
                            value.getValue("clickX").jsonPrimitive.double, value.getValue("dragX").jsonPrimitive.double,
                            session.columns, value.getValue("width").jsonPrimitive.int, 0, session.lineCount - 1,
                            rectangle, value.getValue("padding").jsonPrimitive.int)
                        val context = "$label pointer drag=$dragIndex $value"
                        val selected = value.getValue("selected")
                        if (selected == JsonNull) assertNull(actual, context)
                        else {
                            val p = selected.jsonObject.getValue("range").jsonArray.map { it.jsonPrimitive.int }
                            assertEquals(TerminalSelectionRange(TerminalCellPosition(p[1], p[0]),
                                TerminalCellPosition(p[3], p[2]), rectangle), actual, context)
                            assertEquals(selected.jsonObject.getValue("text").jsonPrimitive.content,
                                terminalSelectionText(assertNotNull(actual), session::line), context)
                            assertEquals(selected.jsonObject.getValue("trimmedText").jsonPrimitive.content,
                                terminalSelectionText(assertNotNull(actual), session::line, true), context)
                        }
                    }
                    for ((rectangleIndex, rectangle) in expected.getValue("rectangles").jsonArray.withIndex()) {
                        val value = rectangle.jsonObject
                        val p = value.getValue("range").jsonArray.map { it.jsonPrimitive.int }
                        val range = TerminalSelectionRange(TerminalCellPosition(p[1], p[0]), TerminalCellPosition(p[3], p[2]), true)
                        for (reverseX in listOf(false, true)) for (reverseY in listOf(false, true)) {
                            val start = TerminalCellPosition(if (reverseY) p[3] else p[1], if (reverseX) p[2] else p[0])
                            val end = TerminalCellPosition(if (reverseY) p[1] else p[3], if (reverseX) p[0] else p[2])
                            selection.begin(start, TerminalSelectionMode.CHARACTER)
                            selection.extend(end, rectangle = true)
                            val context = "$label rectangle=$rectangleIndex reverseX=$reverseX reverseY=$reverseY"
                            assertEquals(range, selection.range(), context)
                            assertEquals(value.getValue("text").jsonPrimitive.content, selection.text(), context)
                            assertEquals(value.getValue("text").jsonPrimitive.content, selection.copyText(false), context)
                            assertEquals(value.getValue("trimmedText").jsonPrimitive.content, selection.copyText(true), context)
                        }
                    }
                    for ((cell, selected) in expected.getValue("deepPress").jsonArray.withIndex()) {
                        selection.begin(TerminalCellPosition(cell / session.columns, cell % session.columns), TerminalSelectionMode.CHARACTER)
                        assertTrue(selection.deepPress(), "$label deep press=$cell consumes valid anchor")
                        checkSelected(selected, "$label deep press=$cell")
                        assertFalse(selection.deepPress(), "$label repeated deep press=$cell")
                        selection.extend(TerminalCellPosition(session.lineCount - 1, session.columns - 1))
                        checkSelected(selected, "$label drag after deep press=$cell")
                    }
                    for (mode in TerminalSelectionMode.entries.filter { it != TerminalSelectionMode.CHARACTER }) {
                        val (selections, gestures) = when (mode) {
                            TerminalSelectionMode.LINE -> "selected" to "gestures"
                            TerminalSelectionMode.WORD -> "words" to "wordGestures"
                            TerminalSelectionMode.OUTPUT -> "outputs" to "outputGestures"
                            TerminalSelectionMode.CHARACTER -> error("Character gestures are checked separately")
                        }
                        for ((cell, selected) in expected.getValue(selections).jsonArray.withIndex()) {
                            selection.begin(TerminalCellPosition(cell / session.columns, cell % session.columns), mode)
                            checkSelected(selected, "$label mode=$mode select=$cell")
                        }
                        for ((gestureIndex, gesture) in expected.getValue(gestures).jsonArray.withIndex()) {
                            for ((stepIndex, step) in gesture.jsonArray.withIndex()) {
                                val value = step.jsonObject
                                val point = value.getValue("position").jsonArray.map { it.jsonPrimitive.int }
                                val position = TerminalCellPosition(point[1], point[0])
                                if (stepIndex == 0) selection.begin(position, mode) else selection.extend(position)
                                checkSelected(value.getValue("selected"), "$label mode=$mode gesture=$gestureIndex step=$stepIndex")
                            }
                        }
                    }
                } finally { selection.clear() }
            }
        }
    }
}
