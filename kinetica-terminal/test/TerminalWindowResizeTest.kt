package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalWindowResizeTest {
    @Test fun deferredResizeCoalescesAxesAndRechecksPolicyAndHostLifetime() {
        val session = TerminalSession()
        val clock = ResizeClock()
        var owner: Any? = Any()
        var allowed = true
        val applied = mutableListOf<TerminalWindowResizeRequest>()
        val binding = TerminalWindowResizeBinding(session, clock, { owner }, { allowed }, applied::add)
        repeat(10_000) { session.write("\u001b[8;;80t\u001b[8;24t") }
        assertEquals(1, clock.jobs.size); assertTrue(applied.isEmpty())
        clock.run()
        assertEquals(listOf(TerminalWindowResizeRequest(24, 80)), applied)
        session.write("\u001b[8;30;90t"); allowed = false; clock.run()
        assertEquals(1, applied.size)
        allowed = true
        session.write("\u001b[8;30;90t"); owner = Any(); clock.run()
        assertEquals(1, applied.size)
        owner = null; session.write("\u001b[8;30;90t"); assertTrue(clock.jobs.isEmpty())
        owner = Any(); session.write("\u001b[8;30;90t")
        val stale = clock.jobs.single()
        binding.cancelPending(); stale(); assertEquals(1, applied.size)
        session.write("\u001b[8;30;90t"); binding.dispose(); stale()
        session.write("\u001b[8;31;91t"); assertTrue(clock.jobs.isEmpty()); assertEquals(1, applied.size)
    }

    @Test fun requestedPixelSizePreservesOmittedAxesAndBoundsGridAllocations() {
        assertEquals(TerminalWindowSize(341.0, 180.0), TerminalWindowResizeRequest(1, 1).windowSize(80, 24, 8.512, 18.0))
        assertEquals(TerminalWindowSize(null, 450.0), TerminalWindowResizeRequest(25, 0).windowSize(80, 24, 8.512, 18.0))
        val maximum = TerminalWindowResizeRequest(65535, 65535).windowSize(80, 24, 1.0, 1.0)
        assertEquals(4096.0, maximum.width); assertTrue(maximum.width!! * maximum.height!! <= 1_000_000)
        val wide = TerminalWindowResizeRequest(0, 65535).windowSize(80, 4096, 1.0, 1.0)
        assertNull(wide.height); assertTrue(wide.width!! * 4096 <= 1_000_000)
    }

    @Test fun policyCanCloseOrReattachTheHostWhileARequestIsBeingApproved() {
        for (close in listOf(false, true)) {
            val session = TerminalSession(); val clock = ResizeClock(); val owner = Any()
            var applying = false; var calls = 0
            lateinit var binding: TerminalWindowResizeBinding
            binding = TerminalWindowResizeBinding(session, clock, { owner }, {
                if (applying) { if (close) binding.dispose() else binding.cancelPending() }
                true
            }, { calls++ })
            session.write("\u001b[8;20;80t"); applying = true; clock.run()
            assertEquals(0, calls); assertTrue(clock.jobs.isEmpty())
            binding.dispose()
        }
    }

    private class ResizeClock : TerminalScheduler {
        val jobs = mutableListOf<() -> Unit>()
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            assertEquals(16, delayMillis); jobs += action
            return TerminalDisposable { jobs.remove(action) }
        }
        fun run() { jobs.removeAt(0)() }
    }

    @Test fun requestsDoNotResizeOrReplyUntilTheHostAppliesMeasuredGeometry() {
        val session = TerminalSession(12, 3)
        val requests = mutableListOf<TerminalWindowResizeRequest>()
        val replies = mutableListOf<TerminalInputData>()
        var resized = 0
        session.onResize { _, _ -> resized++ }
        session.onInput(replies::add)
        val observation = session.onWindowResizeRequested(requests::add)
        session.write("abcdefghijkl\u001b[8;20;80t\u001b[8;;100t")
        assertEquals(listOf(TerminalWindowResizeRequest(20, 80), TerminalWindowResizeRequest(0, 100)), requests)
        assertEquals(12, session.columns); assertEquals(3, session.rows)
        assertTrue(session.cursorPendingWrap); assertEquals(0, resized); assertTrue(replies.isEmpty())
        observation.dispose()
        session.write("\u001b[8;30;90t")
        assertEquals(2, requests.size)
        // A host can decline, or finish its layout and apply the measured grid later.
        session.resize(80, 20)
        assertEquals(1, resized); assertEquals(80, session.columns); assertEquals(20, session.rows)
    }

    @Test fun duplicateObserversOwnIndependentSubscriptionsAcrossResets() {
        val session = TerminalSession()
        val events = mutableListOf<TerminalWindowResizeRequest>()
        val listener: (TerminalWindowResizeRequest) -> Unit = events::add
        val first = session.onWindowResizeRequested(listener)
        val second = session.onWindowResizeRequested(listener)
        first.dispose(); first.dispose()
        session.write("\u001b[?2026h\u001b[8;10;40t")
        assertTrue(session.synchronizedOutput)
        assertEquals(listOf(TerminalWindowResizeRequest(10, 40)), events)
        session.write("\u001b[!p\u001b[8;11;41t\u001bc\u001b[8;12;42t")
        assertEquals(3, events.size)
        second.dispose()
        session.write("\u001b[8;13;43t")
        assertEquals(3, events.size)
    }
}
