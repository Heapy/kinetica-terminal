package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalProtocolDeliveryTest {
    @Test fun synchronousReplyChainsDoNotGrowTheCallStack() {
        val session = TerminalSession()
        val buffer = TerminalInputBuffer(8)
        session.onInput { assertTrue(buffer.tryAdd(it)); buffer.consume(buffer.size) }
        var completed = 0
        fun next() {
            session.sendProtocolReply(byteArrayOf(42)) {
                assertTrue(it)
                if (++completed < 10_000) next()
            }
        }
        next()
        assertEquals(10_000, completed)
    }

    @Test fun wrappedAndGrownQueueAcknowledgesOnlyConsumedReplyBoundaries() {
        val buffer = TerminalInputBuffer(8)
        val delivered = mutableListOf<Pair<Int, Boolean>>()
        buffer.add("abcdef"); buffer.consume(4); buffer.add("ghij")
        assertTrue(buffer.tryAddProtocolReply(ByteArray(20)) { delivered.add(1 to it) })
        assertTrue(buffer.tryAddProtocolReply(ByteArray(3)) { delivered.add(2 to it) })
        buffer.consume(25)
        assertTrue(delivered.isEmpty())
        buffer.consume(1)
        assertEquals(listOf(1 to true), delivered)
        buffer.consume(3)
        assertEquals(listOf(1 to true, 2 to true), delivered)
        assertNull(buffer.buffer)
        assertTrue(buffer.tryAddProtocolReply(byteArrayOf(1)) { delivered.add(3 to it) })
        buffer.consume(1)
        assertEquals(3 to true, delivered.last())
    }

    @Test fun clearCancelsUndeliveredRepliesAndAllowsReentrantNewWork() {
        val buffer = TerminalInputBuffer(8)
        val delivered = mutableListOf<Boolean>()
        assertTrue(buffer.tryAddProtocolReply(byteArrayOf(1, 2)) {
            delivered.add(it)
            assertTrue(buffer.tryAddProtocolReply(byteArrayOf(3)) { result -> delivered.add(result) })
        })
        buffer.consume(1)
        buffer.clear()
        assertEquals(listOf(false), delivered)
        assertEquals(1, buffer.size)
        buffer.consume(1); buffer.clear()
        assertEquals(listOf(false, true), delivered)
    }

    @Test fun callbacksMayEnqueueAndConsumeWithoutCorruptingOtherReceipts() {
        val buffer = TerminalInputBuffer(8)
        val delivered = mutableListOf<Int>()
        assertTrue(buffer.tryAddProtocolReply(byteArrayOf(1)) {
            assertTrue(it); delivered.add(1)
            assertTrue(buffer.tryAddProtocolReply(byteArrayOf(3)) { assertTrue(it); delivered.add(3) })
            buffer.consume(1)
        })
        assertTrue(buffer.tryAddProtocolReply(byteArrayOf(2)) { assertTrue(it); delivered.add(2) })
        buffer.consume(2)
        assertEquals(listOf(1, 3, 2), delivered)
        assertEquals(0, buffer.size)
    }

    @Test fun callbackFailureDoesNotLoseOtherCompletedOrCanceledReceipts() {
        for (cancel in listOf(false, true)) {
            val buffer = TerminalInputBuffer(8)
            var second: Boolean? = null
            assertTrue(buffer.tryAddProtocolReply(byteArrayOf(1)) { error("callback failed") })
            assertTrue(buffer.tryAddProtocolReply(byteArrayOf(2)) { second = it })
            assertFailsWith<IllegalStateException> { if (cancel) buffer.clear() else buffer.consume(2) }
            assertEquals(!cancel, second)
            assertEquals(0, buffer.size)
        }
    }

    @Test fun synchronousDeliveryWaitsForAllObserversAndAllTransports() {
        val session = TerminalSession()
        val first = TerminalInputBuffer(8); val second = TerminalInputBuffer(8)
        val completed = mutableListOf<Boolean>()
        session.onInput { assertTrue(first.tryAdd(it)); first.consume(first.size) }
        session.onInput {
            assertTrue(completed.isEmpty(), "Dispatch has not reached all observers")
            assertTrue(second.tryAdd(it))
        }
        session.sendProtocolReply(byteArrayOf(1, 2)) { completed.add(it) }
        assertTrue(completed.isEmpty())
        second.consume(1); assertTrue(completed.isEmpty())
        second.consume(1); assertEquals(listOf(true), completed)
        first.clear(); second.clear(); assertEquals(listOf(true), completed)
    }

    @Test fun missingRejectedCanceledOrThrowingConsumersCannotReportSuccess() {
        for (mode in listOf("missing", "observer", "reject", "cancel", "throw")) {
            val session = TerminalSession()
            val buffer = TerminalInputBuffer(8)
            val completed = mutableListOf<Boolean>()
            when (mode) {
                "observer" -> session.onInput { it.toByteArray() }
                "reject" -> {
                    buffer.add("12345678")
                    assertTrue(buffer.tryAddProtocolReply(ByteArray(TERMINAL_CLIPBOARD_REPLY_BYTES)))
                }
                "cancel" -> session.onInput { assertTrue(buffer.tryAdd(it)) }
                "throw" -> {
                    session.onInput { assertTrue(buffer.tryAdd(it)); buffer.consume(buffer.size) }
                    session.onInput { error("observer failed") }
                }
            }
            if (mode == "reject") session.onInput { assertFalse(buffer.tryAdd(it)) }
            val send = { session.sendProtocolReply(byteArrayOf(1)) { completed.add(it) } }
            if (mode == "throw") assertFailsWith<IllegalStateException> { send() } else send()
            if (mode == "cancel") { assertTrue(completed.isEmpty()); buffer.clear() }
            assertEquals(listOf(false), completed, mode)
            buffer.clear(); assertEquals(listOf(false), completed)
        }
    }
}
