package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalAccessibilityTest {
    @Test fun rangesCountUtf16AndIdentifyTheSelectedOccurrence() {
        val session = TerminalSession(16, 3)
        val view = TerminalViewport(session, {})
        try {
            session.write("a😀e\u0301界\r\na😀e\u0301界")
            view.beginSelection(1, 1); view.extendSelection(6, 1)
            val value = view.accessibleText()
            assertEquals("a😀e\u0301界\na😀e\u0301界\n", value.text)
            assertEquals(view.visibleText(), value.text)
            assertEquals(listOf(TerminalAccessibleRange(8, 13)), value.selections)
            assertEquals("😀e\u0301界", value.selectedStrings().single())
            view.clearSelection()
            assertTrue(view.accessibleText().selections.isEmpty())
            assertNull(view.accessibleSelectionKey())
        } finally { view.dispose() }
    }

    @Test fun rectangularRangesExpandWideCellsAndSkipTrailingBlanks() {
        val session = TerminalSession(12, 3)
        val view = TerminalViewport(session, {})
        try {
            session.write("a😀z\r\nAB界x\r\nq")
            view.beginSelection(2, 0); view.extendSelection(3, 2, rectangle = true)
            assertEquals(listOf("😀", "界"), view.accessibleText().selectedStrings())
            view.beginSelection(2, 0); view.extendSelection(2, 2, rectangle = true)
            assertTrue(view.accessibleText().selections.isEmpty(), "Zero-width rectangles have no selected glyphs")
            assertNull(view.accessibleSelectionKey())
        } finally { view.dispose() }
    }

    @Test fun physicalWrapsAndTrimmedSpacesStayInTheSameCoordinateSpace() {
        val session = TerminalSession(4, 3)
        val view = TerminalViewport(session, {})
        try {
            session.write("abcdefgh\r\nx  ")
            view.beginSelection(2, 0); view.extendSelection(4, 2)
            val value = view.accessibleText()
            assertEquals("abcd\nefgh\nx", value.text)
            assertEquals(listOf(TerminalAccessibleRange(2, 11)), value.selections)
            assertEquals("cd\nefgh\nx", value.selectedStrings().single())
            assertEquals("cdefgh\nx", view.selectedText(), "Clipboard selection joins soft wraps; AX ranges index physical rows")
        } finally { view.dispose() }
    }

    @Test fun selectionIsClippedToTheViewportDuringScrollbackNavigation() {
        val session = TerminalSession(10, 3)
        val view = TerminalViewport(session, {})
        try {
            session.write((0..5).joinToString("\r\n") { "line-$it" })
            view.scrollTo(3)
            view.beginSelection(2, 0); view.extendSelection(4, 2)
            view.cancelSelectionDrag()
            assertEquals(listOf("ne-0\nline-1\nline"), view.accessibleText().selectedStrings())
            view.scrollTo(2)
            assertEquals("line-1\nline-2\nline-3", view.accessibleText().text)
            assertEquals(listOf("line-1\nline"), view.accessibleText().selectedStrings())
            view.scrollTo(0)
            assertTrue(view.accessibleText().selections.isEmpty())
            view.selectAll()
            assertEquals(listOf(view.visibleText()), view.accessibleText().selectedStrings())
        } finally { view.dispose() }
    }

    @Test fun heldFramesKeepAccessibleTextAndSelectionTogether() {
        val session = TerminalSession(8, 3)
        val view = TerminalViewport(session, {})
        try {
            session.write("old😀")
            view.beginSelection(0, 0); view.extendSelection(5, 0); view.cancelSelectionDrag()
            val before = view.accessibleText()
            val key = view.accessibleSelectionKey()
            session.write("\u001b[?2026h\u001b[Hnewxx")
            assertEquals(before, view.accessibleText())
            assertEquals(key, view.accessibleSelectionKey())
            session.write("\u001b[?2026l")
            assertEquals("newxx\n\n", view.accessibleText().text)
            assertTrue(view.accessibleText().selections.isEmpty(), "Overwritten selection is invalidated on release")
            assertNull(view.accessibleSelectionKey())
        } finally { view.dispose() }
    }

    private fun TerminalAccessibleText.selectedStrings(): List<String> = selections.map { text.substring(it.start, it.end) }
}
