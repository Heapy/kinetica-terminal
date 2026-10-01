package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalViewportTest {
    @Test fun renderingTouchesOnlyDirtyRowsAndCoalescesWrites() {
        val session = TerminalSession(80, 24)
        var invalidations = 0
        val viewport = TerminalViewport(session, { invalidations++ })
        val painter = RecordingPainter()
        viewport.paint(painter, 8.0, 16.0)
        painter.ys.clear()
        session.write("abc"); session.write("def")
        viewport.paint(painter, 8.0, 16.0)
        assertEquals(setOf(0.0), painter.ys)
        assertEquals(2, invalidations)
        painter.ys.clear(); viewport.paint(painter, 8.0, 16.0)
        assertTrue(painter.ys.isEmpty())
        viewport.dispose(); session.write("g")
        assertEquals(2, invalidations)
    }

    @Test fun viewportRetainsScrollPositionDuringOutputAndSelectionCopies() {
        val session = TerminalSession(10, 3, 10)
        repeat(8) { session.write("$it\r\n") }
        val viewport = TerminalViewport(session, {})
        viewport.scroll(3)
        val oldTop = session.line(session.historySize - viewport.scrollOffset).text()
        session.write("8\r\n")
        assertEquals(oldTop, session.line(session.historySize - viewport.scrollOffset).text())
        viewport.beginSelection(0, 0); viewport.extendSelection(1, 0)
        assertEquals(oldTop, viewport.selectedText())
        viewport.followOutput(); assertEquals(0, viewport.scrollOffset)
        viewport.dispose()
    }

    @Test fun independentSurfacesDoNotConsumeEachOthersDamage() {
        val session = TerminalSession(10, 2)
        val one = TerminalViewport(session, {}); val two = TerminalViewport(session, {})
        one.paint(RecordingPainter(), 8.0, 16.0); two.paint(RecordingPainter(), 8.0, 16.0)
        session.write("X")
        val first = RecordingPainter(); val second = RecordingPainter()
        one.paint(first, 8.0, 16.0); two.paint(second, 8.0, 16.0)
        assertEquals(first.ys, second.ys); assertFalse(first.ys.isEmpty())
        one.dispose(); two.dispose()
    }

    private class RecordingPainter : TerminalPainter {
        val ys = mutableSetOf<Double>()
        override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {}
        override fun text(value: String, x: Double, y: Double, color: Int, style: Int) { ys += y }
    }
}
