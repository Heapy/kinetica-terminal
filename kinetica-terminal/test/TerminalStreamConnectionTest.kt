package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalStreamConnectionTest {
    private class Loop : TerminalScheduler {
        data class Job(val at: Double, val action: () -> Unit)
        var now = 0.0
        val jobs = mutableListOf<Job>()
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            val job = Job(now + delayMillis, action)
            jobs += job
            return TerminalDisposable { jobs.remove(job) }
        }
        fun runNext() {
            val job = jobs.minBy { it.at }; jobs.remove(job)
            now = maxOf(now, job.at); job.action()
        }
        fun drain() {
            var turns = 0
            while (jobs.isNotEmpty()) { check(turns++ < 10_000) { "Scheduler failed to become idle" }; runNext() }
        }
    }

    private class Transport : TerminalByteTransport {
        val writes = mutableListOf<Byte>()
        val credits = mutableListOf<Int>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        var accept = Int.MAX_VALUE
        var writeHook: (() -> Unit)? = null
        var creditHook: (() -> Unit)? = null
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            writeHook?.invoke()
            val count = minOf(accept, length)
            if (count >= 0) writes.addAll(bytes.copyOfRange(offset, offset + count).toList())
            return count
        }
        override fun resize(columns: Int, rows: Int) { sizes += columns to rows }
        override fun consumed(bytes: Int) { credits += bytes; creditHook?.invoke() }
    }

    private fun connection(session: TerminalSession, transport: TerminalByteTransport, loop: Loop,
        limits: TerminalStreamLimits = TerminalStreamLimits(), rejected: () -> Unit = { fail("Input rejected") },
        failure: (Throwable) -> Unit = { throw AssertionError("Connection failed", it) },
        clock: () -> Double = { loop.now },
    ) = TerminalStreamConnection(session, transport, loop, rejected, failure, limits, clock)

    @Test fun receiveCopiesBytesAndCreditsOnlyParsedOutput() {
        val session = TerminalSession()
        val transport = Transport(); val loop = Loop()
        val connection = connection(session, transport, loop, TerminalStreamLimits(outputBytes = 8))
        val bytes = "ab😀".encodeToByteArray()
        assertTrue(connection.receive(bytes))
        bytes.fill(0)
        assertFalse(connection.receive("XYZ".encodeToByteArray()))
        assertEquals(6, connection.queuedOutputBytes)
        assertEquals("", session.screenLine(0).text())
        assertTrue(transport.credits.isEmpty())
        assertEquals(1, loop.jobs.size)
        loop.drain()
        assertEquals("ab😀", session.screenLine(0).text())
        assertEquals(listOf(6), transport.credits)
        assertEquals(0, connection.queuedOutputBytes)
        assertEquals(listOf(80 to 24), transport.sizes)
        session.resize(90, 30)
        assertEquals(90 to 30, transport.sizes.last())
        connection.dispose()
    }

    @Test fun stalledWriterRetriesWithoutLosingUtf8OrEnqueuingRejectedPrefixes() {
        val session = TerminalSession(); val transport = Transport(); val loop = Loop()
        var rejected = 0
        val connection = connection(session, transport, loop, rejected = { rejected++ })
        transport.accept = 0
        session.sendInput("😀abc")
        loop.runNext()
        assertEquals(7, connection.pendingInputBytes)
        assertEquals(8.0, loop.jobs.single().at - loop.now)
        session.sendInput("x".repeat(1024 * 1024 + 1))
        assertEquals(1, rejected)
        assertEquals(1L, connection.rejectedInputCount)
        assertEquals(7, connection.pendingInputBytes)
        transport.accept = 1
        loop.drain()
        assertEquals("😀abc", transport.writes.toByteArray().decodeToString())
        assertEquals(0, connection.pendingInputBytes)
        connection.dispose()
    }

    @Test fun rawMouseBytesSurvivePartialWritesAndAtomicQueueRejection() {
        val session = TerminalSession(240, 24); val transport = Transport(); val loop = Loop()
        var rejected = 0
        val connection = connection(session, transport, loop, rejected = { rejected++ })
        transport.accept = 0
        session.write("\u001b[?1000h")
        session.sendInput("a".repeat(1024 * 1024 - 4))
        session.sendMouse(95, 0, 0) // Six bytes cannot fit. Do not enqueue four of them.
        assertEquals(1, rejected)
        assertEquals(1024 * 1024 - 4, connection.pendingInputBytes)
        transport.accept = Int.MAX_VALUE; loop.drain()
        assertTrue(transport.writes.all { it == 97.toByte() })
        transport.writes.clear(); transport.accept = 1
        session.sendInput("界😀")
        session.sendMouse(95, 0, 0)
        session.sendMouse(222, 0, 2, release = true, control = true)
        session.sendInput("done")
        loop.drain()
        val expected = "界😀".encodeToByteArray() + byteArrayOf(27, 91, 77, 32, 128.toByte(), 33,
            27, 91, 77, 51, 255.toByte(), 33) + "done".encodeToByteArray()
        assertContentEquals(expected, transport.writes.toByteArray())
        assertEquals(0, connection.pendingInputBytes)
        connection.dispose()
    }

    @Test fun replyBackpressureRetainsTheSuffixAndResumesExactBytes() {
        val session = TerminalSession(); val transport = Transport(); val loop = Loop()
        val connection = connection(session, transport, loop, TerminalStreamLimits(inputBytes = TERMINAL_REPLY_RESERVE))
        val query = "\u001b]4;" + List(1023) { "0;?" }.joinToString(";") + "\u001b\\"
        val reply = "\u001b]4;0;rgb:0000/0000/0000\u001b\\".repeat(1023)
        val source = (query.repeat(8) + "DONE").encodeToByteArray()
        transport.accept = 0
        assertTrue(connection.receive(source))
        loop.runNext()
        assertTrue(connection.outputPausedForInput)
        assertEquals(reply.length, connection.pendingInputBytes)
        val credited = transport.credits.sum()
        assertTrue(credited in 1 until source.size)
        assertEquals(source.size - credited, connection.queuedOutputBytes)
        loop.runNext()
        assertEquals(credited, transport.credits.sum())
        transport.accept = 257
        transport.writeHook = { loop.now += 0.02 }
        loop.drain()
        assertEquals(reply.repeat(8), transport.writes.toByteArray().decodeToString())
        assertEquals(source.size, transport.credits.sum())
        assertEquals("DONE", session.screenLine(0).text())
        assertEquals(0L, connection.rejectedInputCount)
        connection.dispose()
    }

    @Test fun timeAndByteBudgetsYieldWithoutAcknowledgingTheUnparsedSuffix() {
        val session = TerminalSession(80, 2, scrollback = 0)
        val transport = Transport(); val loop = Loop()
        val connection = connection(session, transport, loop, clock = { loop.now += 0.25; loop.now })
        val bytes = "x".repeat(4096).encodeToByteArray()
        assertTrue(connection.receive(bytes))
        loop.runNext()
        assertTrue(transport.credits.sum() in 1 until bytes.size)
        assertEquals(bytes.size - transport.credits.sum(), connection.queuedOutputBytes)
        loop.drain()
        assertEquals(bytes.size, transport.credits.sum())
        connection.dispose()

        val transport2 = Transport(); val loop2 = Loop()
        val connection2 = connection(session, transport2, loop2)
        assertTrue(connection2.receive(ByteArray(300_000) { 'x'.code.toByte() }))
        loop2.runNext()
        assertEquals(256 * 1024, transport2.credits.sum(), "hard cap with a clock that never advances")
        loop2.drain()
        assertEquals(300_000, transport2.credits.sum())
        connection2.dispose()
    }

    @Test fun eofFollowsQueuedBytesAndFlushesIncompleteUtf8Once() {
        val session = TerminalSession(); val transport = Transport(); val loop = Loop()
        val connection = connection(session, transport, loop)
        assertTrue(connection.receive(byteArrayOf('A'.code.toByte(), 0xf0.toByte(), 0x9f.toByte())))
        connection.finishOutput(); connection.finishOutput()
        assertFalse(connection.outputFinished)
        assertFalse(connection.receive(byteArrayOf(0x98.toByte(), 0x80.toByte())))
        loop.drain()
        assertEquals("A�", session.screenLine(0).text())
        assertTrue(connection.outputFinished)
        assertEquals(listOf(3), transport.credits)
        connection.dispose()
    }

    @Test fun reentrantCallbacksPreserveQueueOrderAcrossWrap() {
        val session = TerminalSession(); val transport = Transport(); val loop = Loop()
        val connection = connection(session, transport, loop, TerminalStreamLimits(outputBytes = 20),
            clock = { loop.now += 1.5; loop.now })
        var injected = false
        transport.creditHook = {
            if (!injected) { injected = true; assertTrue(connection.receive("NEXT".encodeToByteArray())) }
        }
        assertTrue(connection.receive(("FIRST\r" + "x".repeat(12)).encodeToByteArray()))
        loop.drain()
        assertEquals("x".repeat(12) + "NEXT", session.screenLine(0).text())
        assertEquals(22, transport.credits.sum())
        transport.writeHook = {
            transport.writeHook = null
            session.sendInput("B")
        }
        session.sendInput("A")
        loop.drain()
        assertEquals("AB", transport.writes.toByteArray().decodeToString())
        connection.dispose()
    }

    @Test fun disposalFromDamageCallbackCancelsQueuesWithoutSendingCredits() {
        val session = TerminalSession(); val transport = Transport(); val loop = Loop()
        val connection = connection(session, transport, loop)
        val observation = session.observe { connection.dispose() }
        assertTrue(connection.receive(ByteArray(32_000) { 'x'.code.toByte() }))
        loop.runNext()
        assertTrue(connection.disposed)
        assertEquals(0, connection.queuedOutputBytes)
        assertEquals(0, connection.pendingInputBytes)
        assertTrue(transport.credits.isEmpty())
        assertTrue(loop.jobs.isEmpty())
        session.sendInput("ignored"); session.resize(90, 30)
        assertTrue(transport.writes.isEmpty())
        assertEquals(1, transport.sizes.size)
        assertFalse(connection.receive(byteArrayOf(1)))
        observation.dispose()
    }

    @Test fun callbackFailuresCloseOnceAndReleaseBothQueues() {
        for (which in listOf("write", "credit", "resize")) {
            val session = TerminalSession(); val loop = Loop()
            val expected = IllegalStateException(which)
            var failures = 0
            val transport = object : TerminalByteTransport {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int = throw expected
                override fun resize(columns: Int, rows: Int) { if (which == "resize") throw expected }
                override fun consumed(bytes: Int) { throw expected }
            }
            val connection = connection(session, transport, loop, failure = { assertSame(expected, it); failures++ })
            if (which != "resize") {
                connection.receive("output".encodeToByteArray())
                if (which == "write") session.sendInput("input")
                loop.drain()
            }
            assertEquals(1, failures)
            assertSame(expected, connection.failure)
            assertTrue(connection.disposed)
            assertEquals(0, connection.queuedOutputBytes)
            assertEquals(0, connection.pendingInputBytes)
            assertTrue(loop.jobs.isEmpty())
        }
    }

    @Test fun invalidTransportCountsAndSchedulerFailureAreReported() {
        val session = TerminalSession(); val loop = Loop()
        var failures = 0
        val transport = object : TerminalByteTransport {
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int = length + 1
            override fun resize(columns: Int, rows: Int) = Unit
            override fun consumed(bytes: Int) = Unit
        }
        val connection = connection(session, transport, loop, failure = { failures++ })
        session.sendInput("a"); loop.drain()
        assertEquals(1, failures)
        assertTrue(connection.disposed)
        val failure = IllegalStateException("scheduler")
        val broken = TerminalStreamConnection(session, transport, TerminalScheduler { _, _ -> throw failure },
            {}, { assertSame(failure, it); failures++ })
        assertFalse(broken.receive("a".encodeToByteArray()))
        assertTrue(broken.disposed)
        assertEquals(0, broken.queuedOutputBytes)
        assertEquals(2, failures)
    }
}
