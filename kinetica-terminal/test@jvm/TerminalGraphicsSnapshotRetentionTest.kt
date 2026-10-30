package io.heapy.kinetica.terminal

import java.lang.ref.Reference
import java.lang.ref.WeakReference
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsSnapshotRetentionTest {
    private enum class Hold { SYNCHRONIZED, RESIZE, RESIZE_THEN_SYNCHRONIZED }
    private class Pixels(pixels: TerminalGraphicsPixels) {
        val objectRef = WeakReference(pixels)
        val bytes = WeakReference(pixels.data)
    }
    private class Fixture(val session: TerminalSession, val viewport: TerminalViewport, val clock: Clock,
        val original: Pixels, val replaced: List<Pixels>, val callerFrames: MutableList<TerminalGraphicsFrameEntry>)

    private class Clock : TerminalScheduler {
        private class Task(val at: Long, val action: () -> Unit)
        private var now = 0L
        private val tasks = mutableListOf<Task>()
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            val task = Task(now + delayMillis, action)
            tasks += task
            return TerminalDisposable { tasks.remove(task) }
        }
        fun advance(millis: Int) {
            val target = now + millis
            while (true) {
                val task = tasks.minByOrNull { it.at }?.takeIf { it.at <= target } ?: break
                tasks.remove(task); now = task.at; task.action()
            }
            now = target
        }
    }

    private fun TerminalSession.image(size: Int, value: Byte) {
        val data = Base64.encode(ByteArray(size * size * 4) { value })
        val chunks = data.chunked(4096)
        for ((index, chunk) in chunks.withIndex()) {
            val header = if (index == 0) "a=T,q=2,i=1,f=32,s=$size,v=$size,c=1,r=1,C=1," else ""
            write("\u001b_G${header}m=${if (index == chunks.lastIndex) 0 else 1};$chunk\u001b\\")
        }
    }

    // All strong locals referring to decoded generations die on return from this method.
    private fun fixture(hold: Hold, callerOwnsFrame: Boolean = false): Fixture {
        val session = TerminalSession(10, 4).also {
            it.scrollbackCompression = false
            it.resize(10, 4, TerminalCellSize(8, 16))
            it.setGraphicsStorageLimit(2 * 1024 * 1024)
            it.write("\u001b[?25l")
        }
        val clock = Clock()
        val viewport = TerminalViewport(session, {}, clock)
        session.image(512, 42)
        val first = viewport.graphicsFrame().single()
        val original = Pixels(first.image.pixels)
        val caller = mutableListOf<TerminalGraphicsFrameEntry>()
        if (callerOwnsFrame) caller += first
        when (hold) {
            Hold.SYNCHRONIZED -> session.write("\u001b[?2026h")
            Hold.RESIZE, Hold.RESIZE_THEN_SYNCHRONIZED -> viewport.resizeSession(11, 4)
        }
        val replaced = mutableListOf<Pixels>()
        repeat(3) {
            session.image(1, (it + 1).toByte())
            // Inspect the live store, not the viewport's deliberately frozen generation.
            replaced += Pixels(session.graphicsState.captureFrame(0, session.columns, session.rows, session.cellSize).single().image.pixels)
            if (it == 0 && hold == Hold.RESIZE_THEN_SYNCHRONIZED) session.write("\u001b[?2026h")
        }
        session.write("\u001b_Ga=d,d=A,q=2\u001b\\")
        assertTrue(session.graphicsState.captureFrame(0, session.columns, session.rows, session.cellSize).isEmpty())
        return Fixture(session, viewport, clock, original, replaced, caller)
    }

    private fun assertHeld(fixture: Fixture) {
        System.gc()
        val pixels = fixture.viewport.graphicsFrame().single().image.pixels
        assertSame(assertNotNull(fixture.original.objectRef.get()), pixels)
        assertEquals(512 * 512 * 4, pixels.data.size)
        assertEquals(42.toByte(), pixels.data.first())
        assertEquals(42.toByte(), pixels.data.last())
    }

    private fun assertCollected(pixels: List<Pixels>) {
        repeat(40) {
            if (pixels.all { it.objectRef.get() == null && it.bytes.get() == null }) return
            System.gc(); Thread.sleep(10)
        }
        pixels.forEach {
            assertNull(it.objectRef.get(), "No hidden owner may retain the retired decoded generation")
            assertNull(it.bytes.get(), "The retired decoded byte array must be collectable independently of renderer tiles")
        }
    }

    @Test fun synchronizedReleaseDropsOnlyTheFrozenGenerationAndNeverAccumulatesReplacements() {
        val fixture = fixture(Hold.SYNCHRONIZED)
        try {
            assertHeld(fixture)
            assertCollected(fixture.replaced)
            fixture.session.write("\u001b[?2026l")
            assertTrue(fixture.viewport.graphicsFrame().isEmpty())
            assertCollected(listOf(fixture.original))
        } finally { Reference.reachabilityFence(fixture); fixture.viewport.dispose() }
    }

    @Test fun holdTimeoutsDropPixelsWhileTheSessionViewportAndSchedulerRemainAlive() {
        for (hold in Hold.entries) {
            val fixture = fixture(hold)
            try {
                assertHeld(fixture)
                assertCollected(fixture.replaced)
                fixture.clock.advance(TERMINAL_SYNC_TIMEOUT_MILLIS + 100)
                assertTrue(fixture.viewport.graphicsFrame().isEmpty(), "$hold")
                assertCollected(listOf(fixture.original))
            } finally { Reference.reachabilityFence(fixture); fixture.viewport.dispose() }
        }
    }

    @Test fun disposingAHeldViewportReleasesPixelsWithoutRequiringTheHostToDropTheViewObject() {
        for (hold in Hold.entries) {
            val fixture = fixture(hold)
            try {
                assertHeld(fixture)
                fixture.viewport.dispose()
                assertTrue(fixture.viewport.graphicsFrame().isEmpty())
                assertCollected(fixture.replaced + fixture.original)
                // Timers cancelled on disposal cannot resurrect a snapshot.
                fixture.clock.advance(TERMINAL_SYNC_TIMEOUT_MILLIS + 100)
                assertTrue(fixture.viewport.graphicsFrame().isEmpty())
            } finally { Reference.reachabilityFence(fixture); fixture.viewport.dispose() }
        }
    }

    @Test fun anExplicitCallerSnapshotOwnsPixelsUntilTheCallerReleasesIt() {
        val fixture = fixture(Hold.SYNCHRONIZED, callerOwnsFrame = true)
        try {
            fixture.session.write("\u001b[?2026l")
            fixture.viewport.dispose()
            System.gc()
            assertSame(fixture.original.objectRef.get(), fixture.callerFrames.single().image.pixels)
            assertEquals(42.toByte(), fixture.callerFrames.single().image.pixels.data.first())
            fixture.callerFrames.clear()
            assertCollected(fixture.replaced + fixture.original)
        } finally { Reference.reachabilityFence(fixture); fixture.viewport.dispose() }
    }
}
