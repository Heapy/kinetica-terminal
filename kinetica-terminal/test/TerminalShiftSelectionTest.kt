package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalShiftSelectionTest {
    private fun point(x: Double, row: Int = 0) = terminalSelectionPointer(x * 10, row * 20.0 + 10, 10.0, 20.0, 1.0)

    @Test fun continuationRetainsOriginalAnchorThresholdAndDoesNotResetTime() {
        val session = TerminalSession(20, 4)
        val view = TerminalViewport(session, {})
        try {
            session.write("0123456789\r\nabcdefghij")
            view.beginPointerSelection(point(2.2), timeMillis = 100.0)
            view.extendPointerSelection(point(4.8), false); view.endPointerSelection(point(4.8))
            assertEquals("234", view.selectedText())
            for (time in listOf(99.0, 100.0, 599.0, 600.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertFalse(view.continuePointerSelection(point(7.8), time, 500.0, false))
                assertEquals("234", view.selectedText())
            }
            assertTrue(view.continuePointerSelection(point(7.8), 601.0, 500.0, false))
            view.endPointerSelection(point(7.8)); assertEquals("234567", view.selectedText())
            assertTrue(view.continuePointerSelection(point(0.2), 602.0, 500.0, false), "Continuation retains original timestamp")
            view.endPointerSelection(point(0.2)); assertEquals("01", view.selectedText(), "Anchor remains the initial press")
            assertTrue(view.continuePointerSelection(point(4.8, 1), 603.0, 500.0, true))
            view.endPointerSelection(point(4.8, 1)); assertEquals("234\ncde", view.selectedText())
        } finally { view.dispose() }
    }

    @Test fun continuationKeepsWordAndLineGranularityAndDoesNotActivatePromptClick() {
        val session = TerminalSession(30, 4)
        val view = TerminalViewport(session, {})
        val sent = StringBuilder()
        val subscription = session.onInput { sent.append(it.toByteArray().decodeToString()) }
        try {
            session.write("\u001b]133;A;click_events=1\u0007$ \u001b]133;B\u0007alpha beta gamma\r\nsecond line\r\nthird")
            view.beginPointerSelection(point(3.2), TerminalSelectionMode.WORD, 0.0); view.endPointerSelection(point(3.2))
            assertTrue(view.continuePointerSelection(point(10.2), 501.0, 500.0, true))
            view.endPointerSelection(point(10.2)); assertEquals("alpha beta", view.selectedText())
            assertTrue(view.continuePointerSelection(point(14.2), 502.0, 500.0, false))
            view.endPointerSelection(point(14.2)); assertEquals("alpha beta gamma", view.selectedText())
            view.beginPointerSelection(point(2.2, 1), TerminalSelectionMode.LINE, 1000.0); view.endPointerSelection(point(2.2, 1))
            assertTrue(view.continuePointerSelection(point(3.2, 2), 1501.0, 500.0, false))
            view.endPointerSelection(point(3.2, 2)); assertEquals("second line\nthird", view.selectedText())
            assertEquals("", sent.toString())
        } finally { subscription.dispose(); view.dispose() }
    }

    @Test fun missingClearedInvalidatedAndDeepPressedOriginsCannotBeContinued() {
        val session = TerminalSession(20, 4)
        val view = TerminalViewport(session, {})
        try {
            session.write("alpha beta")
            assertFalse(view.continuePointerSelection(point(7.8), 501.0, 500.0, false))
            view.selectAll(); assertFalse(view.continuePointerSelection(point(7.8), 501.0, 500.0, false))
            view.beginPointerSelection(point(1.2), timeMillis = 0.0); view.endPointerSelection(point(1.2))
            assertFalse(view.continuePointerSelection(point(7.8), 501.0, 500.0, false), "Empty single click is not a selection")
            view.beginPointerSelection(point(1.2), TerminalSelectionMode.WORD, 0.0); view.endPointerSelection(point(1.2))
            session.write("\u001b[Hchanged")
            assertFalse(view.continuePointerSelection(point(7.8), 501.0, 500.0, false))
            view.beginPointerSelection(point(1.2), TerminalSelectionMode.WORD, 0.0); assertTrue(view.deepPressSelection())
            assertFalse(view.continuePointerSelection(point(7.8), 501.0, 500.0, false))
            view.beginPointerSelection(point(1.2), TerminalSelectionMode.WORD, 0.0); view.clearSelection()
            assertFalse(view.continuePointerSelection(point(7.8), 501.0, 500.0, false))
            view.dispose(); assertFalse(view.continuePointerSelection(point(7.8), 501.0, 500.0, false))
        } finally { view.dispose() }
    }
}
