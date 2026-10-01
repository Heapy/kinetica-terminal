package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

internal data class GhosttyFixture(val scenario: String, val snapshots: String)

internal fun checkGhostty(fixture: GhosttyFixture) {
    val scenario = Json.parseToJsonElement(Base64.decode(fixture.scenario).decodeToString()).jsonObject
    val expected = Json.parseToJsonElement(Base64.decode(fixture.snapshots).decodeToString()).jsonArray
    for (bytewise in listOf(false, true)) {
        val session = TerminalSession(scenario.getValue("columns").jsonPrimitive.int,
            scenario.getValue("rows").jsonPrimitive.int, scenario.getValue("history").jsonPrimitive.int)
        val replies = StringBuilder()
        val holds = mutableListOf<Boolean>()
        session.onInput { replies.append(it.toByteArray().toHexString()) }
        session.onRenderHold(holds::add)
        scenario.getValue("events").jsonArray.forEachIndexed { index, element ->
            val event = element.jsonObject
            if ("resize" in event) {
                val size = event.getValue("resize").jsonArray
                session.resize(size[0].jsonPrimitive.int, size[1].jsonPrimitive.int)
            } else if ("mouse" in event) {
                for (item in event.getValue("mouse").jsonArray) {
                    val mouse = item.jsonObject
                    fun number(key: String) = mouse.getValue(key).jsonPrimitive.int
                    session.sendMouse(number("column"), number("row"), number("button"),
                        release = number("action") == 1, motion = number("action") == 2,
                        alt = number("modifiers") and 2 != 0, control = number("modifiers") and 4 != 0)
                }
            } else if ("keys" in event) {
                for (item in event.getValue("keys").jsonArray) {
                    val key = item.jsonObject
                    val modifiers = key.getValue("modifiers").jsonPrimitive.int
                    assertTrue(session.sendKey(TerminalKey(key.getValue("key").jsonPrimitive.content,
                        shift = modifiers and 1 != 0, alt = modifiers and 2 != 0, control = modifiers and 4 != 0,
                        keypad = TerminalKeypadKey.valueOf(key.getValue("keypad").jsonPrimitive.content))),
                        "Unencoded keypad input: $key")
                }
            } else {
                val bytes = event["write"]?.jsonPrimitive?.content?.encodeToByteArray()
                    ?: event.getValue("bytes").jsonPrimitive.content.hexToByteArray()
                if (bytewise) bytes.indices.forEach { session.write(bytes, it, 1) } else session.write(bytes)
            }
            val oracle = expected[index].jsonObject
            val label = "${scenario.getValue("name")} event $index bytewise=$bytewise"
            fun compare(name: String, value: JsonElement) = assertEquals(oracle.getValue(name), value, "$label $name")
            compare("columns", JsonPrimitive(session.columns)); compare("rows", JsonPrimitive(session.rows))
            compare("cursor", buildJsonArray { add(session.cursorColumn); add(session.cursorRow) })
            compare("pendingWrap", JsonPrimitive(session.cursorPendingWrap))
            compare("visible", JsonPrimitive(session.cursorVisible)); compare("alternate", JsonPrimitive(session.alternateScreen))
            compare("history", JsonPrimitive(session.historySize)); compare("title", JsonPrimitive(session.title))
            val oracleReplies = oracle.getValue("replies").jsonPrimitive.content
            // The Neovim recording probes extensions outside the current Kinetica contract.
            // Keep the original oracle transcript in the golden; adapt only these documented
            // negative capability answers. All supported replies and every VT state stay exact.
            val expectedReplies = when (scenario["replyPolicy"]?.jsonPrimitive?.content) {
                null -> oracleReplies
                "kinetica-legacy-capabilities" -> {
                    var hex = oracleReplies
                    for (mode in listOf(69, 2031, 2048)) hex = hex.replace(
                        "\u001b[?$mode;2\$y".encodeToByteArray().toHexString(),
                        "\u001b[?$mode;0\$y".encodeToByteArray().toHexString())
                    hex.replace("\u001b[?0u".encodeToByteArray().toHexString(), "")
                }
                else -> error("Unknown oracle reply policy")
            }
            assertEquals(expectedReplies, replies.toString(), "$label replies")
            compare("modes", buildJsonArray { session.inspectedModes().forEach { add(it) } })
            compare("holds", buildJsonArray { holds.forEach { add(it) } })
            compare("cursorStyle", JsonPrimitive(session.cursorStyle.code))
            compare("colors", buildJsonArray { add(session.theme.foreground); add(session.theme.background); add(session.theme.cursor) })
            val palette = ghosttyDefaultPalette()
            for (entry in oracle.getValue("palette").jsonArray) {
                val color = entry.jsonArray
                palette[color[0].jsonPrimitive.int] = color[1].jsonPrimitive.int
            }
            assertContentEquals(palette, IntArray(256, session::paletteColor), "$label palette")
            val lines = oracle.getValue("lines").jsonArray
            for (y in 0 until session.lineCount) {
                val wantedLine = lines[y].jsonObject
                val line = session.line(y)
                assertEquals(wantedLine.getValue("wrapped").jsonPrimitive.boolean, line.wrapped, "$label row $y wrap")
                for (x in 0 until session.columns) {
                    val wanted = wantedLine.getValue("cells").jsonArray[x].jsonArray
                    val actual = buildJsonArray {
                        add(line.text(x)); add(line.width(x)); add(line.rawForeground(x)); add(line.rawBackground(x))
                        add(line.style(x)); add(line.rawUnderlineColor(x)); add(line.hyperlink(x)?.uri); add(line.isProtected(x))
                    }
                    assertEquals(wanted, actual, "$label cell [$y,$x]")
                }
            }
        }
    }
}
