package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGlyphProtocolTest {
    @Test fun parserRepliesOutlineAndGlossaryMatchGhostty() {
        val errors = mutableListOf<String>()
        for (encoded in terminalGlyphFixtures()) for (chunked in listOf(false, true)) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession(12, 6)
            val replies = StringBuilder()
            session.onInput { replies.append(it.toByteArray().toHexString()) }
            fun write(value: String) {
                val bytes = value.encodeToByteArray()
                if (chunked) bytes.forEach { session.write(byteArrayOf(it)) } else session.write(bytes)
            }
            for ((index, value) in case.getValue("events").jsonArray.withIndex()) {
                replies.clear()
                val event = value.jsonObject
                event["text"]?.jsonPrimitive?.content?.let(::write)
                event["capture"]?.jsonPrimitive?.int?.let { length -> write("\u001b_25a1;s" + ";".repeat(length - 1) + "\u001b\\") }
                event["fill"]?.jsonPrimitive?.int?.let { count -> repeat(count) {
                    write("\u001b_25a1;r;cp=${(0xe000 + it).toString(16)};reply=0;AAAAAAAAAAAAAA==\u001b\\")
                } }
                val actual = buildJsonObject {
                    put("replies", replies.toString()); put("count", session.glyphProtocol.count)
                    val entry = session.glyphProtocol.entry(0xe0a0)
                    if (entry == null) put("entry", JsonNull) else putJsonObject("entry") {
                        putJsonArray("contours") { entry.contours.forEach { add(it.toInt() and 65535) } }
                        putJsonArray("points") { entry.points.forEach { add(it) } }
                        put("upm", entry.upm); put("aw", entry.advance); put("lh", entry.height); put("width", entry.width)
                    }
                }
                val expected = case.getValue("snapshots").jsonArray[index]
                if (expected != actual) errors += "${case["name"]} step=$index chunked=$chunked expected=$expected actual=$actual"
            }
        }
        assertTrue(errors.isEmpty(), errors.take(20).joinToString("\n"))
    }

    @Test fun captureIsBoundedAndCancelledCommandsReleaseTheirStorage() {
        val commands = mutableListOf<String>()
        val parser = TerminalGlyphApc(commands::add, limit = 16)
        fun feed(value: String) { value.encodeToByteArray().forEach { parser.feed(it.toInt() and 255) } }
        parser.start(); feed("G" + "x".repeat(100)); assertEquals(0, parser.retainedBytes)
        parser.start(); feed("25a2;" + "x".repeat(100)); assertEquals(0, parser.retainedBytes)
        parser.start(); feed("25a1;s" + ";".repeat(15)); assertEquals(16, parser.retainedBytes)
        parser.end(27); assertEquals(listOf("s" + ";".repeat(15)), commands); assertEquals(0, parser.retainedBytes)
        parser.start(); feed("25a1;s" + ";".repeat(16)); assertEquals(0, parser.retainedBytes)
        parser.end(27); assertEquals(1, commands.size)
        for (terminator in listOf(-1, 0x18, 0x1a, 0x90)) {
            parser.start(); feed("25a1;s"); parser.end(terminator)
            assertEquals(0, parser.retainedBytes); assertEquals(1, commands.size)
        }
        parser.start(); feed("25a1;s"); parser.end(0x9c); assertEquals("s", commands.last())
    }

    @Test fun registrationsAreSessionWideAndIndependentOfGraphicsStorage() {
        val session = TerminalSession()
        val replies = mutableListOf<String>()
        session.onInput { replies += it.toByteArray().decodeToString() }
        val register = "\u001b_25a1;r;cp=e0a0;width=2;size=contain;align=end,baseline;pad=.1,.2,.3,.4;AAAAAAAAAAAAAA==\u001b\\"
        session.write(register)
        assertEquals(1, session.glyphProtocol.count)
        val entry = assertNotNull(session.glyphProtocol.entry(0xe0a0))
        assertEquals(2, entry.width); assertEquals("contain", entry.size); assertEquals("end,baseline", entry.align)
        assertEquals(listOf(.1, .2, .3, .4), entry.pad)
        session.setGraphicsStorageLimit(1); session.setGraphicsStorageLimit(0)
        session.write("\u001b[?1049h\u001b[!p")
        assertSame(entry, session.glyphProtocol.entry(0xe0a0))
        session.write("\u001b[?1049l\u001b_25a1;q;cp=e0a0\u001b\\")
        assertEquals("\u001b_25a1;q;cp=e0a0;status=glossary\u001b\\", replies.last())
        session.write("\u001b_25a1;r;cp=e0a0;" + "A".repeat(1000))
        assertTrue(session.retainedGlyphCommandBytes > 0)
        session.reset()
        assertEquals(0, session.retainedGlyphCommandBytes); assertEquals(0, session.glyphProtocol.count)
        session.write("\u001b_25a1;s\u001b\\")
        assertEquals("\u001b_25a1;s;fmt=glyf\u001b\\", replies.last())
    }
}
