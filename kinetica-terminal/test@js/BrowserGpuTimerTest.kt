package io.heapy.kinetica.terminal

import kotlin.test.*

class BrowserGpuTimerTest {
    @Test fun unavailableResultsNeverBlockOrGrowPastEightQueries() {
        val fixture = Fixture()
        repeat(8) { fixture.submit(it.toLong()) }
        fixture.submit(8)
        assertEquals(listOf<Pair<Long, Double?>>(8L to null), fixture.results)
        assertEquals(8, fixture.created)
        repeat(100) { fixture.tick(16.0) }
        assertEquals(0, fixture.resultReads)
        assertEquals(8, fixture.live.size)
        assertEquals(1, fixture.timers.size)
        fixture.available = true
        fixture.tick(16.0)
        assertEquals(0, fixture.live.size)
        assertEquals(0, fixture.timers.size)
        assertEquals(8, fixture.resultReads)
        assertEquals((0L..7).map { it to 2.5 }, fixture.results.drop(1))
        fixture.timer.dispose()
    }

    @Test fun disjointAndLostContextsCannotPublishInvalidDurations() {
        val fixture = Fixture()
        fixture.submit(1); fixture.submit(2)
        fixture.disjoint = true; fixture.available = true
        fixture.tick(16.0)
        assertEquals(listOf<Pair<Long, Double?>>(1L to null, 2L to null), fixture.results)
        assertEquals(0, fixture.resultReads)
        assertEquals(0, fixture.live.size)
        fixture.submit(3)
        fixture.lost = true
        fixture.tick(16.0)
        assertEquals(3L to null, fixture.results.last())
        assertEquals(0, fixture.timers.size)
        assertEquals(2, fixture.deleted, "Context loss itself releases handles; do not call GL deletion on lost objects")
        fixture.timer.dispose()
        assertEquals(3, fixture.results.size)
    }

    @Test fun unsupportedTimeoutAndDisposeReleaseWorkWithoutLateCallbacks() {
        val unsupported = Fixture(supported = false)
        unsupported.submit(1)
        assertEquals(listOf<Pair<Long, Double?>>(1L to null), unsupported.results)
        assertEquals(0, unsupported.created)
        assertEquals(0, unsupported.timers.size)
        val fixture = Fixture()
        fixture.submit(1)
        fixture.tick(5000.0)
        assertEquals(listOf<Pair<Long, Double?>>(1L to null), fixture.results)
        assertEquals(0, fixture.live.size)
        fixture.timer.begin(2) // A renderer throws before ending/submitting this interval.
        fixture.timer.dispose(); fixture.timer.dispose()
        fixture.tick(5000.0)
        assertEquals(listOf<Pair<Long, Double?>>(1L to null), fixture.results)
        assertEquals(0, fixture.live.size)
        assertEquals(0, fixture.timers.size)
    }

    private class Fixture(supported: Boolean = true) {
        val results = mutableListOf<Pair<Long, Double?>>()
        val timers = mutableMapOf<Int, () -> Unit>()
        val live = mutableSetOf<Int>()
        var created = 0
        var deleted = 0
        var available = false
        var lost = false
        var disjoint = false
        var resultReads = 0
        private var clock = 0.0
        private var nextTimer = 0
        private var activeQuery: Int? = null
        private val gl: dynamic = js("({QUERY_RESULT_AVAILABLE:1,QUERY_RESULT:2})")
        val timer: BrowserGpuTimer
        init {
            gl.getExtension = { _: String -> if (supported) js("({TIME_ELAPSED_EXT:3,GPU_DISJOINT_EXT:4})") else null }
            gl.getParameter = { _: Int -> disjoint.also { disjoint = false } }
            gl.createQuery = { (++created).also { live += it } }
            gl.deleteQuery = { handle: Int -> assertNotEquals(activeQuery, handle); assertTrue(live.remove(handle)); deleted++ }
            gl.beginQuery = { _: Int, handle: Int -> assertNull(activeQuery); activeQuery = handle }
            gl.endQuery = { _: Int -> assertNotNull(activeQuery); activeQuery = null }
            gl.isContextLost = { lost }
            gl.getQueryParameter = { _: Int, parameter: Int ->
                if (parameter == 1) available else {
                    assertTrue(available && !lost && !disjoint, "Must not wait for a result or read an invalid duration")
                    resultReads++; 2_500_000.0
                }
            }
            timer = BrowserGpuTimer(gl, object : TerminalRenderObserver {
                override fun onGpuTime(frameId: Long, milliseconds: Double?) { results += frameId to milliseconds }
            }, TerminalScheduler { _, action ->
                val id = ++nextTimer; timers[id] = action
                TerminalDisposable { timers.remove(id) }
            }, { clock })
        }
        fun submit(id: Long) { timer.end(timer.begin(id)) }
        fun tick(millis: Double) {
            clock += millis
            val callbacks = timers.values.toList(); timers.clear()
            callbacks.forEach { it() }
        }
    }
}
