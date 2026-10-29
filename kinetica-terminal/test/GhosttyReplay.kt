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
        val effects = mutableListOf<JsonElement>()
        val workingDirectories = mutableListOf<JsonElement>()
        session.onWorkingDirectory { workingDirectories.add(it?.let(::JsonPrimitive) ?: JsonNull) }
        session.onNotification { event -> effects.add(buildJsonArray { add("notification"); add(event.title); add(event.body) }) }
        session.onProgress { event -> effects.add(buildJsonArray {
            add("progress"); add(event.state.ordinal); add(event.percentage?.let(::JsonPrimitive) ?: JsonNull)
        }) }
        val clipboardEvents = mutableListOf<JsonElement>()
        if (scenario["clipboard"]?.jsonPrimitive?.boolean == true) {
            val values = Array(3) { ByteArray(0) }
            session.attachClipboard(object : TerminalClipboard {
                override fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit) {
                    clipboardEvents.add(buildJsonArray { add(location.ordinal); add(JsonNull) })
                    complete(values[location.ordinal])
                }
                override fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit) {
                    clipboardEvents.add(buildJsonArray { add(location.ordinal); add(data.toHexString()) })
                    values[location.ordinal] = data
                    complete()
                }
            }, TerminalClipboardPolicy.ALLOW)
        }
        session.onInput { replies.append(it.toByteArray().toHexString()) }
        session.onRenderHold(holds::add)
        scenario.getValue("events").jsonArray.forEachIndexed { index, element ->
            val event = element.jsonObject
            if ("resize" in event) {
                val size = event.getValue("resize").jsonArray
                val pixels = event["cellSize"]?.jsonArray
                session.resize(size[0].jsonPrimitive.int, size[1].jsonPrimitive.int,
                    TerminalCellSize(pixels?.get(0)?.jsonPrimitive?.int ?: 8, pixels?.get(1)?.jsonPrimitive?.int ?: 16))
            } else if ("colorScheme" in event) {
                session.configureColorScheme(if (event.getValue("colorScheme").jsonPrimitive.int == 1)
                    TerminalColorScheme.DARK else TerminalColorScheme.LIGHT)
            } else if ("checksumReporting" in event) {
                val config = event.getValue("checksumReporting").jsonArray
                session.configureChecksumReporting(config[0].jsonPrimitive.boolean,config[1].jsonPrimitive.int)
            } else if ("mouse" in event) {
                for (item in event.getValue("mouse").jsonArray) {
                    val mouse = item.jsonObject
                    fun number(key: String) = mouse.getValue(key).jsonPrimitive.int
                    session.sendMouse(number("column"), number("row"), number("button"),
                        release = number("action") == 1, motion = number("action") == 2,
                        alt = number("modifiers") and 2 != 0, control = number("modifiers") and 4 != 0)
                }
            } else if ("keyboard" in event) {
                for ((keyIndex, item) in event.getValue("keyboard").jsonArray.withIndex()) {
                    val key = item.jsonObject
                    fun number(name: String) = key[name]?.jsonPrimitive?.int ?: 0
                    val modifiers = number("modifiers")
                    val input = TerminalKey(key.getValue("key").jsonPrimitive.content,
                        shift = modifiers and 1 != 0, alt = modifiers and 2 != 0,
                        control = modifiers and 4 != 0, meta = modifiers and 8 != 0,
                        capsLock = modifiers and 64 != 0, numLock = modifiers and 128 != 0,
                        action = TerminalKeyAction.entries[number("action")],
                        text = key["text"]?.jsonPrimitive?.content.orEmpty(),
                        unshiftedCodepoint = number("unshifted"),
                        baseCodepoint = number("base").takeIf { it != 0 },
                        consumedModifiers = number("consumed"),
                        physicalCode = key["physical"]?.jsonPrimitive?.content,
                        keypad = key["keypad"]?.jsonPrimitive?.content?.let(TerminalKeypadKey::valueOf))
                    assertTrue(session.sendKey(input), "Unencoded keyboard input $keyIndex: $key")
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
            compare("pointerShape", JsonPrimitive(session.pointerShape.ordinal))
            if ("effects" in oracle) compare("effects", JsonArray(effects))
            if ("cursorAtPrompt" in oracle) compare("cursorAtPrompt", JsonPrimitive(session.cursorAtPrompt))
            if ("workingDirectory" in oracle) {
                compare("workingDirectory", session.workingDirectoryUri?.let(::JsonPrimitive) ?: JsonNull)
                compare("workingDirectoryReports", JsonArray(workingDirectories))
            }
            if ("clipboard" in oracle) compare("clipboard", JsonArray(clipboardEvents))
            val oracleHistory = oracle.getValue("history").jsonPrimitive.int
            val expectedHistory = when (scenario["historyPolicy"]?.jsonPrimitive?.content) {
                null -> oracleHistory
                // libghostty prunes whole pages, and scrollClear can retain rows even
                // when its configured history is zero. Keep Kinetica's strict quota.
                "strict-limit" -> if (session.alternateScreen) 0 else minOf(oracleHistory, scenario.getValue("history").jsonPrimitive.int)
                else -> error("Unknown oracle history policy")
            }
            assertEquals(expectedHistory, session.historySize, "$label history")
            compare("title", JsonPrimitive(session.title))
            val oracleReplies = oracle.getValue("replies").jsonPrimitive.content
            assertEquals(oracleReplies, replies.toString(), "$label replies")
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
                val wantedLine = lines[y + oracleHistory - expectedHistory].jsonObject
                val line = session.line(y)
                assertEquals(wantedLine.getValue("wrapped").jsonPrimitive.boolean, line.wrapped, "$label row $y wrap")
                if ("promptMarker" in wantedLine) assertEquals(wantedLine.getValue("promptMarker").jsonPrimitive.int,
                    line.promptMarker.ordinal, "$label row $y prompt marker")
                for (x in 0 until session.columns) {
                    val wanted = wantedLine.getValue("cells").jsonArray[x].jsonArray
                    val actual = buildJsonArray {
                        add(line.text(x)); add(line.width(x)); add(line.rawForeground(x)); add(line.rawBackground(x))
                        add(line.style(x)); add(line.rawUnderlineColor(x)); add(line.hyperlink(x)?.uri); add(line.isProtected(x))
                        if ("promptMarker" in wantedLine) add(line.semanticContent(x).ordinal)
                    }
                    assertEquals(wanted, actual, "$label cell [$y,$x]")
                }
            }
        }
    }
}
