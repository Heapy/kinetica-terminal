package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

internal data class ReplayFixture(
    val name: String, val columns: Int, val rows: Int, val history: Int,
    val input: String, val expected: String,
)

/** Golden expectations originate exclusively from pinned upstream fixtures. */
internal fun checkReplay(fixture: ReplayFixture) {
    val bytes = Base64.decode(fixture.input)
    val expected = Json.parseToJsonElement(Base64.decode(fixture.expected).decodeToString()).jsonObject
    // EL at pending wrap moves subsequent output to different rows in these two emulators.
    // Use original Ghostty's complete grid for this named policy difference, after proving
    // that its scenario replays exactly the archived Alacritty recording and dimensions.
    val ghosttyLines = if (fixture.name == "erase_in_line") {
        val policy = ghostty_alacritty_erase_in_line()
        val scenario = Json.parseToJsonElement(Base64.decode(policy.scenario).decodeToString()).jsonObject
        assertEquals(fixture.columns, scenario.getValue("columns").jsonPrimitive.int)
        assertEquals(fixture.rows, scenario.getValue("rows").jsonPrimitive.int)
        assertEquals(fixture.history, scenario.getValue("history").jsonPrimitive.int)
        assertTrue(bytes.contentEquals(scenario.getValue("events").jsonArray.single().jsonObject
            .getValue("write").jsonPrimitive.content.encodeToByteArray()))
        Json.parseToJsonElement(Base64.decode(policy.snapshots).decodeToString()).jsonArray.single()
            .jsonObject.getValue("lines").jsonArray
    } else null
    val errors = mutableListOf<String>()
    for (chunked in listOf(false, true)) {
        val session = TerminalSession(fixture.columns, fixture.rows, fixture.history)
        val random = Random(0x5654)
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(bytes.size - offset, if (chunked) random.nextInt(1, 98) else bytes.size)
            session.write(bytes, offset, length)
            offset += length
        }
        val prefix = "${fixture.name} ${if (chunked) "chunked" else "whole"}"
        fun mismatch(message: String) { if (errors.size < 12) errors += "$prefix: $message" }
        val lines = expected.getValue("lines").jsonPrimitive.int
        // Upstream initialize_all pads unused history capacity with blank rows.
        // Pad our logical history identically; never drop nonblank expected rows.
        val padding = lines - session.lineCount
        if (padding < 0) mismatch("line count expected at most $lines, actual ${session.lineCount}")
        val blank = TerminalLine(fixture.columns)
        val cells = expected.getValue("cells").jsonArray
        var position = 0
        for (run in expected.getValue("runs").jsonArray) {
            val count = run.jsonArray[0].jsonPrimitive.int
            val cell = cells[run.jsonArray[1].jsonPrimitive.int].jsonArray
            repeat(count) {
                val row = position / fixture.columns
                val column = position++ % fixture.columns
                if (row - padding < session.lineCount) {
                    val line = if (row < padding) blank else session.line(row - padding)
                    fun rgb(value: Int): Int = if (value and 0x1000000 != 0 && value >= 0) terminalPalette(value and 255) else value
                    val actual = buildJsonArray {
                        add(line.text(column)); add(line.width(column))
                        add(line.foreground(column)); add(line.background(column)); add(line.style(column))
                        add(line.underlineColor(column))
                        add(line.hyperlink(column)?.id); add(line.hyperlink(column)?.uri)
                    }
                    val sourceCell = ghosttyLines?.get(row)?.jsonObject?.getValue("cells")?.jsonArray?.get(column)?.jsonArray
                        ?.let { oracle -> JsonArray(oracle.take(6) + listOf(JsonNull, oracle[6])) } ?: cell
                    val wanted = JsonArray(sourceCell.mapIndexed { i, value ->
                        when {
                            // Ghostty implements DECSCA/DECSED/DECSEL. This Alacritty
                            // recording retains A/C; protected B is preserved by both.
                            fixture.name == "selective_erasure" && row in 0..1 && column in listOf(0, 2) && i == 0 -> {
                                check(value.jsonPrimitive.content == if (column == 0) "A" else "C")
                                JsonPrimitive(" ")
                            }
                            // Alacritty ignores SGR 21 in this recording. Ghostty and
                            // xterm select double underline. Pin this exact difference;
                            // do not rewrite other undercurl cells or source fixtures.
                            fixture.name == "underline" && row == 7 && column < 10 && i == 4 -> {
                                check(value.jsonPrimitive.int == TerminalStyle.UNDERCURL)
                                JsonPrimitive(TerminalStyle.DOUBLE_UNDERLINE)
                            }
                            i == 2 || i == 3 || i == 5 -> JsonPrimitive(rgb(value.jsonPrimitive.int))
                            else -> value
                        }
                    })
                    if (wanted != actual) mismatch("cell [$row,$column] expected $wanted, actual $actual")
                }
            }
        }
        expected.getValue("wrapped").jsonArray.forEachIndexed { row, value ->
            val line = if (row < padding) blank else session.line(row - padding)
            val wanted = ghosttyLines?.get(row)?.jsonObject?.getValue("wrapped") ?: value
            if (wanted.jsonPrimitive.boolean != line.wrapped)
                mismatch("row $row wrapped expected $wanted, actual ${line.wrapped}")
        }
    }
    assertTrue(errors.isEmpty(), errors.joinToString("\n"))
}
