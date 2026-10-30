package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalEnquiryTest {
    @Test fun answerbackMatchesOriginalParserBoundariesAndResets() {
        for (encoded in terminalEnquiryFixtures()) for (chunked in listOf(false, true)) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession(12, 6)
            val replies = StringBuilder()
            session.onInput { replies.append(it.toByteArray().toHexString()) }
            for ((index, value) in case.getValue("events").jsonArray.withIndex()) {
                val event = value.jsonObject
                event["response"]?.jsonPrimitive?.content?.let {
                    session.configureEnquiryResponse(if (it == "-") null else it.hexToByteArray())
                }
                event["text"]?.jsonPrimitive?.content?.encodeToByteArray()?.let { bytes ->
                    if (chunked) bytes.forEach { session.write(byteArrayOf(it)) } else session.write(bytes)
                }
                val actual = buildJsonObject { put("replies", replies.toString()) }
                assertEquals(case.getValue("snapshots").jsonArray[index], actual,
                    "${case["name"]} step=$index chunked=$chunked")
            }
        }
    }

    @Test fun hostBytesAreCopiedAndPreviouslyEmittedRepliesKeepTheirOriginalContent() {
        val session = TerminalSession()
        val events = mutableListOf<TerminalInputData>()
        session.onInput(events::add)
        val bytes = byteArrayOf(0, -1, 27, 13)
        session.configureEnquiryResponse(bytes)
        bytes.fill(42)
        session.write("\u0005")
        events.single().toByteArray().fill(43)
        session.configureEnquiryResponse("next")
        session.write("\u0005")
        session.configureEnquiryResponse(null)
        session.write("\u0005")
        assertEquals(2, events.size)
        assertContentEquals(byteArrayOf(0, -1, 27, 13), events[0].toByteArray())
        assertEquals("next", events[1].toByteArray().decodeToString())
    }

    @Test fun stringConfigurationUsesUtf8ByteLimitsAndInvalidLengthsDisablePriorResponse() {
        val session = TerminalSession()
        val replies = mutableListOf<ByteArray>()
        session.onInput { replies += it.toByteArray() }
        session.configureEnquiryResponse("界".repeat(85))
        session.write("\u0005")
        assertEquals(255, replies.single().size)
        assertEquals("界".repeat(85), replies.single().decodeToString())
        for (response in listOf("界".repeat(86), "x".repeat(256), "")) {
            session.configureEnquiryResponse(response)
            session.write("\u0005")
            assertEquals(1, replies.size)
        }
        session.configureEnquiryResponse("ready")
        session.reset(); session.write("\u0005")
        assertEquals("ready", replies.last().decodeToString())
    }
}
