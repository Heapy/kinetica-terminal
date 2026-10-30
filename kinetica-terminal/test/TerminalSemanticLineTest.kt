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
            }
        }
    }
}
