package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalGraphicsTimingTest {
    private class Clock : TerminalScheduler {
        private data class Job(val at: Long, val action: () -> Unit)
        private val jobs = mutableListOf<Job>()
        var now = 0L
            private set
        val pending: Int get() = jobs.size
        fun callbacks(): List<() -> Unit> = jobs.map { it.action }
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            require(delayMillis > 0)
            val job = Job(now + delayMillis,action); jobs += job
            return TerminalDisposable { jobs.remove(job) }
        }
        fun advance(millis: Int) {
            val end = now + millis
            while (true) {
                val job = jobs.minByOrNull { it.at }?.takeIf { it.at <= end } ?: break
                jobs.remove(job); now = job.at; job.action()
            }
            now = end
        }
    }
    private class Fixture {
        val clock = Clock()
        val session = TerminalSession(10,6).also {
            it.setGraphicsStorageLimit(100); it.resize(10,6,TerminalCellSize(8,16)); it.write("\u001b[?25l")
        }
        var draws = 0
        val viewport = TerminalViewport(session,{ draws++ },clock,{ clock.now })
        fun write(header: String) = session.write("\u001b_G$header\u001b\\")
        fun load() {
            write("a=T,i=1,q=2,f=32,s=1,v=1,C=1;AQID/w==")
            write("a=f,i=1,q=2,f=32,s=1,v=1,z=20;BAUG/w==")
            write("a=a,i=1,r=1,z=10,s=3")
        }
        fun pixel(): Int = viewport.graphicsFrame().single().image.pixels.data[0].toInt()
        fun dispose() = viewport.dispose()
    }

    @Test fun wakesAtTheGapButAdvancesOnlyWhenARendererBuildsTheNextFrame() {
        val f = Fixture()
        try {
            f.load(); assertEquals(1,f.pixel()); assertEquals(1,f.clock.pending)
            val before = f.draws
            f.clock.advance(9); assertEquals(before,f.draws)
            f.clock.advance(1); assertEquals(before+1,f.draws)
            assertEquals(0,f.clock.pending)
            f.clock.advance(5000)
            assertEquals(before+1,f.draws,"A stalled renderer must not spin animation timers")
            assertEquals(0,f.session.graphicsState.storage.image(1)!!.animation.currentIndex)
            assertEquals(4,f.pixel()); assertEquals(1,f.clock.pending)
            assertEquals(before+1,f.draws,"Building the requested frame must not enqueue a redundant redraw")
            f.clock.advance(19); assertEquals(4,f.pixel())
            f.clock.advance(1); assertEquals(1,f.pixel())
        } finally { f.dispose() }
    }

    @Test fun hiddenAndDisposedViewsCancelWakeupsIncludingAlreadyQueuedCallbacks() {
        val f = Fixture()
        f.load(); f.pixel()
        val stale = f.clock.callbacks()
        f.viewport.setVisible(false)
        val before = f.draws
        stale.forEach { it() }; f.clock.advance(500)
        assertEquals(before,f.draws); assertEquals(0,f.clock.pending)
        assertEquals(1,f.pixel(),"Hidden readback must not advance the animation")
        f.viewport.setVisible(true)
        assertTrue(f.draws > before); assertEquals(4,f.pixel())
        val pending = f.clock.callbacks()
        f.dispose()
        val disposed = f.draws
        pending.forEach { it() }; f.clock.advance(500)
        assertEquals(disposed,f.draws); assertEquals(0,f.clock.pending)
        assertTrue(f.viewport.graphicsFrame().isEmpty())
    }

    @Test fun synchronizedAndResizeHoldsSuspendAnimationUntilTheCompletedFrameIsReleased() {
        val f = Fixture()
        try {
            f.load(); assertEquals(1,f.pixel())
            f.session.write("\u001b[?2026h")
            f.clock.advance(100); assertEquals(1,f.pixel())
            assertEquals(1,f.clock.pending,"Only the synchronized-output timeout remains")
            f.session.write("\u001b[?2026l")
            assertEquals(4,f.pixel())
            f.viewport.resizeSession(9,6)
            f.clock.advance(49); assertEquals(4,f.pixel())
            f.clock.advance(1); assertEquals(1,f.pixel())
            f.session.write("\u001b[?2026h")
            f.clock.advance(1000)
            assertFalse(f.session.synchronizedOutput)
            assertEquals(4,f.pixel(),"The abandoned-hold timeout must also resume animation")
        } finally { f.dispose() }
    }

    @Test fun finiteStoppedUnplacedAndGaplessAnimationsBecomeIdleAndNewFramesWakeLoadingState() {
        val f = Fixture()
        try {
            f.load(); f.write("a=a,i=1,v=2")
            f.pixel(); f.clock.advance(10); assertEquals(4,f.pixel())
            f.clock.advance(20); assertEquals(4,f.pixel()); assertEquals(0,f.clock.pending)
            f.write("a=a,i=1,s=2,c=1,v=1")
            f.pixel(); f.clock.advance(10); f.pixel(); f.clock.advance(20); f.pixel()
            assertEquals(0,f.clock.pending)
            f.write("a=f,i=1,q=2,f=32,s=1,v=1,z=30;BwgJ/w==")
            assertEquals(7,f.pixel()); assertEquals(1,f.clock.pending)
            f.write("a=a,i=1,s=1"); f.pixel(); assertEquals(0,f.clock.pending)
            f.write("a=a,i=1,s=3"); f.write("a=d,d=i,i=1")
            assertTrue(f.viewport.graphicsFrame().isEmpty()); assertEquals(0,f.clock.pending)
            f.write("a=p,i=1,C=1"); f.pixel(); assertEquals(1,f.clock.pending)
            for (n in 1..3) f.write("a=a,i=1,r=$n,z=-1")
            f.pixel(); assertEquals(0,f.clock.pending)
        } finally { f.dispose() }
    }

    @Test fun viewsShareFrameTimeAndOnlyTheActiveScreenAdvances() {
        val f = Fixture()
        val second = TerminalViewport(f.session,{},f.clock,{ f.clock.now })
        try {
            f.load(); f.pixel()
            f.clock.advance(10)
            assertEquals(4,second.graphicsFrame().single().image.pixels.data[0].toInt())
            assertEquals(4,f.pixel(),"Another view cannot advance again at the same timestamp")
            f.viewport.setVisible(false)
            f.clock.advance(20)
            assertEquals(1,second.graphicsFrame().single().image.pixels.data[0].toInt())
            f.session.write("\u001b[?47h")
            assertTrue(second.graphicsFrame().isEmpty()); assertEquals(0,f.clock.pending)
            f.clock.advance(100)
            f.session.write("\u001b[?47l")
            assertEquals(4,second.graphicsFrame().single().image.pixels.data[0].toInt())
        } finally { second.dispose(); f.dispose() }
    }
}
