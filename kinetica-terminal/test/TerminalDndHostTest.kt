package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalDndHostTest {
    private val position = TerminalDndPosition(2, 3, 20, 30, 3)
    private val types = listOf("text/plain")
    private class Harness {
        val session = TerminalSession()
        val jobs = ArrayDeque<() -> Unit>()
        val receipts = ArrayDeque<(Boolean) -> Unit>()
        val bytes = mutableListOf<ByteArray>()
        var tracked = false
        val scheduler = TerminalScheduler { _, action -> jobs += action; TerminalDisposable { jobs.remove(action) } }
        init { session.onInput {
            bytes += it.toByteArray()
            if (tracked) receipts += (it as TerminalInputData.ProtocolReply).trackDelivery()
        } }
        fun host(changed: () -> Unit = {}) = session.attachDndHost(scheduler, changed)
        fun register(id: Int = 7) = session.write("\u001b]72;t=a:i=$id;text/plain\u0007")
        fun text() = bytes.joinToString("") { it.decodeToString() }
        fun run() { var steps = 0; while (jobs.isNotEmpty()) { check(steps++ < 100); jobs.removeFirst()() } }
        fun ack(success: Boolean = true) { receipts.removeFirst()(success); run() }
    }

    @Test fun registrationFeedbackAndDropFollowTheClientAndSourceOperations() {
        val h = Harness()
        var changes = 0
        val host = h.host { changes++ }
        assertNull(host.enter(position, types))
        h.register()
        assertEquals(1, changes)
        assertEquals(1, host.enter(position, types))
        h.session.write("\u001b]72;t=m:o=2;text/plain\u0007")
        assertEquals(2, host.operation(3))
        assertEquals(0, host.operation(1))
        h.session.write("\u001b]72;t=m:o=0\u0007")
        assertEquals(0, host.operation(3))
        assertFalse(host.drop(host.ticket()!!, position, listOf(TerminalDndItem("text/plain".encodeToByteArray(), byteArrayOf(1)))))
        h.session.write("\u001b]72;t=m:o=2:m=1;text/\u0007")
        assertEquals(1, host.operation(3), "Incomplete acceptance uses the default, like Ghostty")
        h.session.write("\u001b]72;m=0;plain\u0007")
        val ticket = host.ticket()!!
        assertTrue(host.drop(ticket, position, listOf(TerminalDndItem("text/plain".encodeToByteArray(), byteArrayOf(0, -1)))))
        assertFalse(host.valid(ticket))
        h.bytes.clear()
        h.session.write("\u001b]72;t=r:x=1\u0007")
        assertEquals("\u001b]72;t=r:x=1:i=7:m=0;AP8=\u0007\u001b]72;t=r:x=1:i=7\u0007", h.text())
        h.session.write("\u001b]72;t=r:o=2\u0007")
        assertNull(h.session.kittyDnd.state!!.items)
        host.dispose()
        assertNull(h.session.dndHosts)
        assertNotNull(h.session.kittyDnd.state)
    }

    @Test fun twentyThousandBlockedMovesKeepOnlyTheLastUnsentPosition() {
        val h = Harness(); val host = h.host(); h.register(); h.tracked = true
        assertEquals(1, host.enter(position, types))
        repeat(20000) { assertEquals(1, host.move(position.copy(x = it.toLong()), types)) }
        assertEquals(1, h.bytes.size)
        assertTrue(h.session.outputPaused)
        h.ack()
        assertEquals(2, h.bytes.size)
        assertTrue(h.bytes.last().decodeToString().contains(":x=19999:"))
        h.ack()
        assertFalse(h.session.outputPaused)
        assertTrue(h.jobs.isEmpty())
        host.dispose()
    }

    @Test fun delayedDropOwnsBytesAndKeepsItsOrderBeforeALaterMove() {
        val h = Harness(); val host = h.host(); h.register(); h.tracked = true
        host.enter(position, types)
        val data = byteArrayOf(65); val mime = "text/plain".encodeToByteArray()
        assertTrue(host.drop(host.ticket()!!, position, listOf(TerminalDndItem(mime, data))))
        data[0] = 66; mime.fill(67)
        host.enter(position.copy(x = 4), types)
        h.ack()
        assertEquals(2, h.bytes.size)
        assertTrue(h.bytes.last().decodeToString().startsWith("\u001b]72;t=M:"))
        assertContentEquals(byteArrayOf(65), h.session.kittyDnd.state!!.items!!.single().data)
        assertEquals("text/plain", h.session.kittyDnd.state!!.items!!.single().mime.decodeToString())
        h.ack()
        assertEquals(3, h.bytes.size)
        assertTrue(h.bytes.last().decodeToString().contains("t=m:x=4:"))
        assertNull(h.session.kittyDnd.state!!.items, "New drag discards the previous drop")
        h.ack(); host.dispose()
    }

    @Test fun staleReadersAndQueuedActionsCannotCrossUnregisterOrHostDisposal() {
        val h = Harness(); val host = h.host(); h.register()
        val ticket = host.ticket()!!
        host.leave()
        assertFalse(host.valid(ticket))
        val second = host.ticket()!!
        h.session.write("\u001b]72;t=A\u0007"); h.register()
        assertFalse(host.valid(second))
        h.tracked = true
        host.enter(position, types)
        val third = host.ticket()!!
        assertTrue(host.drop(third, position, listOf(TerminalDndItem("x".encodeToByteArray(), byteArrayOf(1)))))
        host.dispose()
        assertFalse(host.valid(third))
        assertNull(h.session.kittyDnd.state!!.items)
        h.ack() // Only the leave may follow the already accepted move.
        assertEquals(2, h.bytes.size)
        assertEquals("\u001b]72;t=m:x=-1:y=-1:i=7\u001b\\", h.bytes.last().decodeToString())
        h.ack()
        assertNull(h.session.dndHosts)
        h.tracked = false
        h.session.write("still alive")
        assertEquals("still alive", h.session.screenLine(0).text())
    }

    @Test fun failedDeliveryCancelsPendingHostDataAndReaders() {
        val h = Harness(); val host = h.host(); h.register(); h.tracked = true
        host.enter(position, types)
        val ticket = host.ticket()!!
        assertTrue(host.drop(ticket, position, listOf(TerminalDndItem("x".encodeToByteArray(), byteArrayOf(1)))))
        val later = host.ticket()!!
        h.ack(false)
        assertFalse(host.valid(later))
        assertNull(h.session.kittyDnd.state!!.items)
        assertFalse(h.session.kittyDnd.state!!.hovered)
        assertEquals(1, h.bytes.size)
        assertTrue(h.jobs.isEmpty())
        host.dispose()
    }

    @Test fun hostsSwitchWithALeaveAndShareOneBoundedActionAndDataQueue() {
        val h = Harness(); val first = h.host(); val second = h.host(); h.register()
        first.enter(position, types); h.bytes.clear()
        second.enter(position.copy(x = 8), types)
        assertTrue(h.text().startsWith("\u001b]72;t=m:x=-1:y=-1:i=7\u001b\\\u001b]72;t=m:x=8:"))
        h.tracked = true; second.move(position, types)
        val item = TerminalDndItem("x".encodeToByteArray(), ByteArray(9 * 1024 * 1024))
        assertTrue(second.drop(second.ticket()!!, position, listOf(item)))
        assertFalse(first.drop(first.ticket()!!, position, listOf(item)))
        val others = List(4) { h.host() }
        assertEquals(1, others[0].enter(position, types))
        assertEquals(1, others[1].enter(position, types))
        assertEquals(1, others[2].enter(position, types))
        assertEquals(0, others[3].enter(position, types))
        h.session.cancelPendingReplies()
        h.ack()
        assertFalse(h.session.outputPaused)
        assertEquals(2, h.bytes.size)
        (others + first + second).forEach { it.dispose() }
        assertNull(h.session.dndHosts)
    }

    @Test fun malformedMimeOffersCannotInjectControlsOrLeaveAnOldPendingOffer() {
        for (mime in listOf("", "text/plain other", "text/\u001b]72;t=M", "text/\u0000", "text/\u007f")) {
            val h = Harness(); val host = h.host(); h.register(); h.tracked = true
            host.enter(position, types)
            host.move(position.copy(x = 99), types)
            val stale = host.ticket()!!
            assertEquals(0, host.move(position, listOf(mime)))
            assertFalse(host.valid(stale))
            h.ack()
            assertEquals(2, h.bytes.size)
            assertEquals("\u001b]72;t=m:x=-1:y=-1:i=7\u001b\\", h.bytes.last().decodeToString())
            h.ack()
            assertFalse(h.session.kittyDnd.state!!.hovered)
            assertFalse(host.drop(host.ticket()!!, position, listOf(TerminalDndItem(mime.encodeToByteArray(), byteArrayOf(1)))))
            host.dispose()
        }
    }
}
