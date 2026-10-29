package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalDndSessionTest {
    @Test fun unicodeStringFeedsAndByteFeedsRegisterTheSameMimeBytes() {
        val input = "\u001b]72;t=a:i=17;text/é😀\u0007\u001b]72;t=q:i=9\u001b\\"
        for (feed in 0..3) {
            val session = TerminalSession()
            val events = mutableListOf<TerminalDndEvent>()
            val replies = mutableListOf<ByteArray>()
            session.onInput { replies += it.toByteArray() }
            session.onDnd { events += it }
            when (feed) {
                0 -> session.write(input)
                1 -> input.forEach { session.write(it.toString()) }
                2 -> session.write(input.encodeToByteArray())
                else -> input.encodeToByteArray().forEach { session.write(byteArrayOf(it)) }
            }
            assertEquals(listOf(TerminalDndEvent.REGISTRATION), events)
            assertContentEquals("text/é😀".encodeToByteArray(), session.kittyDnd.state!!.registered.snapshot())
            assertEquals("\u001b]72;t=q:i=9\u001b\\", replies.single().decodeToString())
        }
    }

    @Test fun stalledTransportBoundsDataResponsesAndResumesFollowingCommandsInOrder() {
        val session = TerminalSession()
        session.write("\u001b]72;t=a:i=7;application/octet-stream\u0007")
        val data = ByteArray(256 * 1024) { it.toByte() }
        session.acceptDndResult(assertNotNull(session.kittyDnd.drop(TerminalDndPosition(0,0,0,0,1),
            listOf(TerminalDndItem("application/octet-stream".encodeToByteArray(), data)))))
        val jobs = ArrayDeque<() -> Unit>()
        val output = mutableListOf<Byte>()
        val chunks = mutableListOf<Int>()
        session.onInput { chunks += it.toByteArray().size }
        var writable = false
        val scheduler = TerminalScheduler { _, action -> jobs.addLast(action); TerminalDisposable { jobs.remove(action) } }
        val transport = object : TerminalByteTransport {
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!writable) return 0
                val size = minOf(length, 997)
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
        val request = "\u001b]72;t=r:x=1\u0007\u001b]72;t=q:i=8\u0007\u001bP+q436F\u001b\\Z"
        assertTrue(connection.receive(request.encodeToByteArray())); run()
        assertTrue(session.outputPaused)
        assertTrue(connection.pendingInputBytes in 1..32768)
        assertEquals(" ", session.line(0).text(0))
        writable = true; run()
        val expected = buildString {
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + 3072, data.size)
                append("\u001b]72;t=r:x=1:i=7:m=${if (end == data.size) 0 else 1};")
                append(Base64.encode(data, offset, end)); append('\u0007'); offset = end
            }
            append("\u001b]72;t=r:x=1:i=7\u0007\u001b]72;t=q:i=8\u0007")
            append(terminalTerminfoCapabilities.getValue("436F"))
        }
        assertContentEquals(expected.encodeToByteArray(), output.toByteArray())
        assertEquals("Z", session.line(0).text(0))
        assertFalse(session.outputPaused)
        assertTrue(chunks.all { it in 1..32768 })
        assertTrue(chunks.size > 10)
        writable = false; output.clear()
        assertTrue(connection.receive(request.encodeToByteArray())); run()
        connection.dispose()
        assertFalse(session.outputPaused)
        assertTrue(output.isEmpty())
        assertTrue(jobs.isEmpty())
    }

    @Test fun resetCancelsUnsentDataButKeepsActiveDeliveryAndOriginalDropState() {
        val session = TerminalSession()
        session.write("\u001b]72;t=a:i=7\u0007")
        session.kittyDnd.drop(TerminalDndPosition(0,0,0,0,1),
            listOf(TerminalDndItem("x".encodeToByteArray(), ByteArray(100000))))
        val completions = mutableListOf<(Boolean) -> Unit>()
        val bytes = mutableListOf<ByteArray>()
        session.onInput {
            bytes += it.toByteArray()
            completions += (it as TerminalInputData.ProtocolReply).trackDelivery()
        }
        session.write("\u001b]72;t=r:x=1\u0007")
        assertTrue(session.outputPaused)
        assertEquals(1, bytes.size)
        val stale = completions.single()
        session.reset()
        assertTrue(session.outputPaused)
        assertTrue(session.kittyDnd.state!!.dropped)
        stale(true)
        assertFalse(session.outputPaused)
        assertEquals(1, bytes.size)
        session.write("\u001b]72;t=q:i=9\u0007")
        assertTrue(session.outputPaused)
        stale(false) // A stale duplicate completion cannot finish the new response.
        assertTrue(session.outputPaused)
        completions.last()(false)
        assertFalse(session.outputPaused)
        assertEquals("\u001b]72;t=q:i=9\u0007", bytes.last().decodeToString())
    }

    @Test fun byteReplyStreamPreservesNonUtf8AndDoesNotPullPastItsBound() {
        var pulled = 0
        val packets = mutableListOf<ByteArray>()
        val complete = ArrayDeque<(Boolean) -> Unit>()
        val stream = TerminalReplyStream({ bytes, done -> packets += bytes; complete += done }, {}, chunkBytes = 4)
        stream.offerBytes(sequence { repeat(1000) { pulled++; yield(byteArrayOf(0, -128, -1)) } })
        assertEquals(2, pulled)
        assertContentEquals(byteArrayOf(0, -128, -1), packets.single())
        stream.cancel()
        assertTrue(stream.busy)
        complete.removeFirst()(true)
        assertFalse(stream.busy)
        assertEquals(2, pulled)
    }
}
