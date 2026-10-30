package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalFrozenSelectionTest {
    private fun point(x: Double, row: Int = 0) = terminalSelectionPointer(x * 10, row * 20.0 + 10, 10.0, 20.0, 1.0)

    @Test fun aPendingLinkClickKeepsItsOriginalCellAndCurrentPolicy() {
        val session = TerminalSession(20, 3)
        val view = TerminalViewport(session, {})
        try {
            session.write("\u001b]8;;https://example.org\u0007link\u001b]8;;\u0007")
            view.pressPointerSelection(point(1.2), 0.0, 500.0, false)
            session.write("\u001b[?2026h\u001b[Hchanged")
            assertTrue(view.linkHoverAllowedWhileDragging(point(1.2)))
            assertEquals("https://example.org", view.pointerLinkAt(point(1.2))?.uri)
            assertNull(view.pointerLinkAt(point(2.2)))
            session.osc8LinksEnabled = false; assertNull(view.pointerLinkAt(point(1.2)))
            view.cancelSelectionDrag(); assertNull(view.pointerLinkAt(point(1.2)))
        } finally { view.dispose() }
    }

    @Test fun aHeldDragContinuesAcrossCaptureWithoutReadingHiddenOutput() {
        val session = TerminalSession(20, 4)
        val view = TerminalViewport(session, {})
        try {
            session.write("\u001b[31m0123456789\u001b[0m")
            view.pressPointerSelection(point(2.2), 0.0, 500.0, false)
            view.extendPointerSelection(point(4.8), false)
            assertEquals("234", view.selectedText())
            session.write("\u001b[?2026h\u001b[H\u001b[2KCHANGED")
            view.extendPointerSelection(point(7.8), false); view.endPointerSelection(point(7.8))
            assertEquals("234567", view.selectedText())
            val frame = TerminalGpuFrame(); frame.update(view, 10.0, 20.0)
            assertEquals(listOf(TerminalRect(20.0, 0.0, 60.0, 20.0, session.theme.selection)),
                frame.rows[0].backgrounds.filter { it.color == session.theme.selection })
            val copy = assertNotNull(view.copyData(TerminalCopyFormat.MIXED))
            assertEquals("234567", copy.text); assertContains(assertNotNull(copy.html), "234567")
            assertFalse(copy.html!!.contains("CHANGED"))
            session.write("\u001b[?2026l")
            assertFalse(view.hasSelection, "Snapshot interaction does not resurrect the old live selection")
            view.pressPointerSelection(point(2.2), 1.0, 500.0, false); view.endPointerSelection(point(2.2))
            assertFalse(view.hasSelection, "A frozen-only click sequence cannot leak into the revealed frame")
        } finally { view.dispose() }
    }

    @Test fun repeatClicksAndShiftContinuationUseTheFrozenScreenEvenAfterAnAlternateScreenSwitch() {
        val session = TerminalSession(30, 4)
        val view = TerminalViewport(session, {})
        try {
            session.write("alpha beta gamma")
            view.pressPointerSelection(point(1.2), 0.0, 500.0, false); view.endPointerSelection(point(1.2))
            session.write("\u001b[?2026h\u001b[?1049hHIDDEN")
            view.pressPointerSelection(point(1.2), 100.0, 500.0, false); view.endPointerSelection(point(1.2))
            assertEquals("alpha", view.selectedText(), "The second press uses the retained first pin and count")
            assertTrue(view.continuePointerSelection(point(7.2), 601.0, 500.0, false))
            view.endPointerSelection(point(7.2)); assertEquals("alpha beta", view.selectedText())
            view.pressPointerSelection(point(1.2), 602.0, 500.0, false)
            view.endPointerSelection(point(1.2)); assertFalse(view.hasSelection, "Shift continuation did not refresh the repeat timestamp")
            session.write("\u001b[?2026l")
            assertFalse(view.hasSelection)
        } finally { view.dispose() }
    }

    @Test fun inheritedWordGestureCanContinueAndDeepPressUsesTheOriginalWord() {
        val session = TerminalSession(30, 4)
        val view = TerminalViewport(session, {})
        try {
            session.write("alpha beta gamma")
            view.pressPointerSelection(point(1.2), 0.0, 500.0, false); view.endPointerSelection(point(1.2))
            view.pressPointerSelection(point(1.2), 10.0, 500.0, false); view.endPointerSelection(point(1.2))
            session.write("\u001b[?2026h\u001b[HCHANGED")
            assertTrue(view.continuePointerSelection(point(7.2), 511.0, 500.0, true))
            assertEquals("alpha beta", view.selectedText(), "Word granularity ignores rectangle modifiers")
            assertTrue(view.deepPressSelection()); assertEquals("alpha", view.selectedText())
            assertFalse(view.continuePointerSelection(point(12.2), 2000.0, 500.0, false))
            session.write("\u001b[?2026l"); assertFalse(view.hasSelection)
        } finally { view.dispose() }
    }

    @Test fun inheritedHistoryAnchorAndCopyRemainValidAfterHiddenHistoryErasure() {
        val session = TerminalSession(12, 3, 20)
        val view = TerminalViewport(session, {})
        try {
            session.write((0..9).joinToString("\r\n") { "row-$it" })
            view.scroll(5)
            view.pressPointerSelection(point(0.2), 0.0, 500.0, false)
            view.scrollTo(0); view.extendPointerSelection(point(4.8, 2), false); view.endPointerSelection(point(4.8, 2))
            assertEquals((2..9).joinToString("\n") { "row-$it" }, view.selectedText())
            session.write("\u001b[?2026h\u001b[3J\u001b[H\u001b[2Jhidden")
            assertTrue(view.continuePointerSelection(point(4.8), 501.0, 500.0, false))
            view.endPointerSelection(point(4.8))
            val expected = (2..7).joinToString("\n") { "row-$it" }
            assertEquals(expected, view.selectedText()); assertEquals(expected, view.copyText())
            assertEquals(expected, view.copyData(TerminalCopyFormat.MIXED)?.text)
            view.selectAll(); assertEquals((7..9).joinToString("\n") { "row-$it" }, view.selectedText(), "Select All remains limited to the visible held frame")
            view.clearSelection(); assertFalse(view.hasSelection)
            assertFalse(view.continuePointerSelection(point(4.8), 502.0, 500.0, false))
            session.write("\u001b[?2026l"); assertFalse(view.hasSelection)
        } finally { view.dispose() }
    }

    @Test fun aRepeatOriginOutsideBothViewportAndCurrentSelectionIsCaptured() {
        val session = TerminalSession(20, 3)
        val view = TerminalViewport(session, {})
        try {
            session.write("first\r\nsecond\r\nthird")
            view.pressPointerSelection(point(1.2), 0.0, 500.0, false); view.endPointerSelection(point(1.2))
            session.write("\r\nfourth\u001b[?2026h\u001b[3J\u001b[Hhidden")
            view.pressPointerSelection(point(1.2), 10.0, 500.0, false)
            assertEquals("second", view.selectedText())
            view.extendPointerSelection(point(2.8), false); view.endPointerSelection(point(2.8))
            assertEquals("first\nsecond", view.selectedText())
            session.write("\u001b[?2026l"); assertFalse(view.hasSelection)
        } finally { view.dispose() }
    }

    @Test fun untouchedHoldsKeepTheLiveClickSequenceAndDoNotInvalidateShiftContinuation() {
        val session = TerminalSession(20, 3)
        val view = TerminalViewport(session, {})
        try {
            session.write("alpha beta")
            view.pressPointerSelection(point(1.2), 0.0, 500.0, false); view.endPointerSelection(point(1.2))
            session.write("\u001b[?2026h\u001b[?2026l")
            view.pressPointerSelection(point(1.2), 10.0, 500.0, false); view.endPointerSelection(point(1.2))
            assertEquals("alpha", view.selectedText())
            session.write("\u001b[?2026h\u001b[?2026l")
            assertTrue(view.continuePointerSelection(point(7.2), 511.0, 500.0, false))
            view.endPointerSelection(point(7.2)); assertEquals("alpha beta", view.selectedText())
        } finally { view.dispose() }
    }
}
