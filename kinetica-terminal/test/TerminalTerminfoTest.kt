package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalTerminfoTest {
    @Test fun queriesMatchEveryOriginalCapabilityAndParserBoundary() {
        for (encoded in terminalTerminfoFixtures()) {
            val record = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val case = record.getValue("case").jsonObject
            fun text(key: String) = case.getValue(key).jsonPrimitive.content
            val input = text("input") + text("prefix") + text("repeated").repeat(case.getValue("count").jsonPrimitive.int) + text("suffix")
            val expected = record.getValue("reply").jsonPrimitive.content
            for (chunked in listOf(false, true)) {
                val session = TerminalSession()
                session.terminfoName = case["terminfo_name"]?.jsonPrimitive?.contentOrNull
                val reply = StringBuilder()
                session.onInput { reply.append(it.toByteArray().decodeToString()) }
                if (chunked) input.encodeToByteArray().forEach { session.write(byteArrayOf(it)) }
                else session.write(input)
                assertEquals(expected, reply.toString(), "${text("name")}, chunked=$chunked")
                assertFalse(session.outputPaused)
            }
        }
    }

    @Test fun largeResponsesWaitForDeliveryAndCloseDiscardsUnsentCapabilities() {
        val session = TerminalSession()
        val jobs = ArrayDeque<() -> Unit>()
        val output = mutableListOf<Byte>()
        var writable = false
        val scheduler = TerminalScheduler { _, action -> jobs.addLast(action); TerminalDisposable { jobs.remove(action) } }
        val transport = object : TerminalByteTransport {
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!writable) return 0
                val size = minOf(length, 4093)
                repeat(size) { output += bytes[offset + it] }
                return size
            }
            override fun resize(columns: Int, rows: Int) {}
            override fun consumed(bytes: Int) {}
        }
        val connection = TerminalStreamConnection(session, transport, scheduler, { fail("Rejected input") }, { throw it })
        fun run() {
            var iterations = 0
            while (jobs.isNotEmpty()) {
                check(iterations++ < 10000)
                jobs.removeFirst()()
                if (!writable && connection.pendingInputBytes > 0) return
            }
        }
        val entry = terminalTerminfoCapabilities.maxBy { it.value.length.toDouble() / (it.key.length + 1) }
        val count = 1500
        val request = "\u001bP+q" + List(count) { entry.key }.joinToString(";") + "\u001b\\Z"
        assertTrue(connection.receive(request.encodeToByteArray())); run()
        assertTrue(session.outputPaused)
        assertTrue(connection.pendingInputBytes in 1..32768)
        assertEquals(" ", session.line(0).text(0))
        writable = true; run()
        assertEquals(entry.value.repeat(count), output.toByteArray().decodeToString())
        assertEquals("Z", session.line(0).text(0))
        assertFalse(session.outputPaused)
        writable = false; output.clear()
        assertTrue(connection.receive(request.encodeToByteArray())); run()
        connection.dispose()
        assertFalse(session.outputPaused)
        assertTrue(output.isEmpty())
        val replies = StringBuilder()
        session.onInput { replies.append(it.toByteArray().decodeToString()) }
        session.write("\u001bP+q436F\u001b\\")
        assertEquals(terminalTerminfoCapabilities.getValue("436F"), replies.toString())
    }

    @Test fun replyStreamDoesNotPullUnboundedPacketsAndCancellationKeepsActiveOwnership() {
        var consumed = 0
        var finish: ((Boolean) -> Unit)? = null
        val chunks = mutableListOf<String>()
        val stream = TerminalReplyStream({ bytes, done -> chunks += bytes.decodeToString(); finish = done }, {}, chunkBytes = 8)
        stream.offer(sequence { repeat(1000) { consumed++; yield("abcd") } })
        assertEquals(3, consumed) // Two packets plus one lookahead, independent of request size.
        assertEquals(listOf("abcdabcd"), chunks)
        stream.cancel(); assertTrue(stream.busy)
        assertNotNull(finish)(true)
        assertFalse(stream.busy); assertEquals(3, consumed)
        stream.offer(sequenceOf("new")); assertNotNull(finish)(false)
        assertFalse(stream.busy)
    }
}
