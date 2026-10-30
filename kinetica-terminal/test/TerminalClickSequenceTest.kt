package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalClickSequenceTest {
    @Test fun intervalIsBetweenPressesButDistanceIsFromTheFirstPress() {
        val sequence = TerminalClickSequence()
        fun press(time: Double, x: Double = 0.0, y: Double = 0.0, valid: Boolean = true) =
            sequence.press(time, x, y, 500.0, 10.0, valid)
        assertEquals(1, press(0.0))
        assertEquals(2, press(500.0, 6.0, 8.0), "Exact time and Euclidean distance boundary repeats")
        assertEquals(3, press(1000.0, 6.0, 8.0))
        assertEquals(3, press(1500.0, 6.0, 8.0))
        assertEquals(1, press(1501.0, 12.0, 8.0), "Distance remains relative to first click, not previous")
        assertEquals(2, press(1600.0, 12.0, 8.0))
        assertEquals(1, press(2101.0, 12.0, 8.0))
        assertEquals(1, press(2000.0, 12.0, 8.0), "Backwards time restarts")
        assertEquals(1, press(Double.NaN)); assertEquals(1, press(2200.0))
        assertEquals(1, press(2201.0, valid = false), "A recycled or switched screen cannot reuse its anchor")
        sequence.reset(); assertEquals(1, press(2202.0))
    }

    @Test fun realPressesSelectCurrentWordsButDragFromTheOriginalCell() {
        val session = TerminalSession(20, 4)
        val view = TerminalViewport(session, {})
        fun point(x: Double, row: Int = 0) = terminalSelectionPointer(x * 10, row * 20.0 + 10, 10.0, 20.0, 1.0)
        fun press(x: Double, time: Double, row: Int = 0, output: Boolean = false) {
            view.pressPointerSelection(point(x, row), time, 500.0, output)
        }
        try {
            session.write("one two three\r\nsecond")
            press(2.8, 0.0); view.endPointerSelection(point(2.8)); assertEquals("", view.selectedText())
            press(3.7, 500.0); assertEquals(" ", view.selectedText(), "Second press selects under its own pin")
            view.extendPointerSelection(point(7.2), false); view.endPointerSelection(point(7.2))
            assertEquals("one two ", view.selectedText(), "Word drag includes the original first word")
            press(3.7, 1000.0); view.endPointerSelection(point(3.7)); assertEquals("one two three", view.selectedText())
            press(3.7, 1501.0); view.endPointerSelection(point(3.7)); assertEquals("", view.selectedText())
            press(3.7, 1502.0); assertEquals(" ", view.selectedText())
            assertTrue(view.deepPressSelection()); press(3.7, 1503.0); view.endPointerSelection(point(3.7)); assertEquals("", view.selectedText())
            session.write("\u001b[?1049hnew screen")
            press(3.7, 1504.0); view.endPointerSelection(point(3.7)); assertEquals("", view.selectedText())
            session.write("\u001b[?1049l")
            press(3.7, 1505.0); view.endPointerSelection(point(3.7)); assertEquals("", view.selectedText())
        } finally { view.dispose() }
    }

    @Test fun historyMovesTheOriginalPinWithoutChangingTheSpatialRepeatThreshold() {
        val session = TerminalSession(20, 3)
        val view = TerminalViewport(session, {})
        fun point(x: Double, row: Int = 0) = terminalSelectionPointer(x * 10, row * 20.0 + 10, 10.0, 20.0, 1.0)
        try {
            session.write("first\r\nsecond\r\nthird")
            view.pressPointerSelection(point(1.2), 0.0, 500.0, false); view.endPointerSelection(point(1.2))
            session.write("\r\nfourth")
            view.pressPointerSelection(point(1.2), 100.0, 500.0, false)
            assertEquals("second", view.selectedText())
            view.extendPointerSelection(point(2.8), false); view.endPointerSelection(point(2.8))
            assertEquals("first\nsecond", view.selectedText())
            session.reset(); session.write("new")
            view.pressPointerSelection(point(1.2), 101.0, 500.0, false); view.endPointerSelection(point(1.2))
            assertEquals("", view.selectedText())
        } finally { view.dispose() }
    }
}
