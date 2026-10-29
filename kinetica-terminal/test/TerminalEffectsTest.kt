package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalEffectsTest {
    @Test fun disposalByAnEarlierObserverCannotResubscribeOrScheduleTimers() {
        val session = TerminalSession(); val clock = Clock(); var changes = 0
        lateinit var presentation: TerminalProgressPresentation
        session.onProgress { presentation.dispose() }
        presentation = TerminalProgressPresentation(session, clock) { changes++ }
        session.write("\u001b]9;4;1;50\u0007")
        assertNull(presentation.report); assertTrue(clock.jobs.isEmpty()); assertEquals(0, changes)
    }
    private class Clock : TerminalScheduler {
        val jobs = mutableListOf<() -> Unit>()
        var canceled = 0
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            assertEquals(15_000, delayMillis)
            jobs += action
            return TerminalDisposable { jobs.remove(action); canceled++ }
        }
    }

    @Test fun progressTimeoutRestartsAndStaleCallbacksCannotRemoveNewReports() {
        val session = TerminalSession(); val clock = Clock(); var changes = 0
        val presentation = TerminalProgressPresentation(session, clock) { changes++ }
        session.write("\u001b]9;4;1;25\u0007")
        assertEquals(25, presentation.percentage)
        val stale = clock.jobs.single()
        session.write("\u001b]9;4;4\u0007")
        assertEquals(100, presentation.percentage)
        assertEquals("Operation paused", presentation.label)
        stale(); assertEquals(TerminalProgressState.PAUSE, presentation.report?.state)
        session.write("\u001b]9;4;2\u0007")
        assertNull(presentation.percentage); assertEquals("Operation failed", presentation.label)
        clock.jobs.removeAt(0)()
        assertNull(presentation.report)
        assertEquals(4, changes)
        presentation.dispose()
    }

    @Test fun resetAndDisposalClearProgressAndSubscriptions() {
        val session = TerminalSession(); val clock = Clock(); var changes = 0
        val presentation = TerminalProgressPresentation(session, clock) { changes++ }
        session.write("\u001b[?2026h\u001b]9;4;3\u0007")
        assertEquals(TerminalProgressState.INDETERMINATE, presentation.report?.state)
        session.reset()
        assertNull(presentation.report); assertTrue(clock.jobs.isEmpty())
        session.write("\u001b]9;4;1;10\u0007")
        val stale = clock.jobs.single()
        presentation.dispose(); stale()
        session.write("\u001b]9;4;1;90\u0007")
        assertNull(presentation.report); assertEquals(3, changes); assertTrue(clock.jobs.isEmpty())
    }

    @Test fun notificationsAreImmediateDuringHoldsAndObserverRemovalIsSafe() {
        val session = TerminalSession(); val events = mutableListOf<TerminalNotification>()
        val observation = session.onNotification(events::add)
        session.write("\u001b[?2026h\u001b]777;notify;Done;Task completed\u001b\\")
        assertEquals(listOf(TerminalNotification("Done", "Task completed")), events)
        observation.dispose()
        session.write("\u001b]9;ignored by detached host\u0007")
        assertEquals(1, events.size)
        assertTrue(session.synchronizedOutput)
    }
}
