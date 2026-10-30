package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalSelectionNavigationTest {
    @Test fun revealUsesTheNormalizedStartAndTracksRetainedRows() {
        val session = TerminalSession(12, 4)
        val viewport = TerminalViewport(session, {})
        try {
            repeat(12) { session.write("row-$it\r\n") }
            viewport.scrollTo(session.historySize)
            viewport.beginSelection(5, 2); viewport.extendSelection(0, 1); viewport.endSelection(0, 1)
            val selected = viewport.selectedText()
            assertEquals("row-1\nrow-2", selected)
            viewport.scrollTo(0)
            repeat(3) { session.write("later-$it\r\n") }
            assertTrue(viewport.scrollToSelection())
            assertEquals(session.historySize - 1, viewport.scrollOffset)
            assertTrue(viewport.visibleText().startsWith("row-1\nrow-2"))
            assertEquals(selected, viewport.selectedText())
            assertTrue(viewport.scrollToSelection(), "An already revealed selection still consumes its binding")
            viewport.scrollTo(0)
            viewport.beginSelection(1, 0); viewport.extendSelection(4, 1, rectangle = true)
            viewport.endSelection(4, 1)
            val rectangle = viewport.selectedText()
            viewport.scrollTo(session.historySize)
            assertTrue(viewport.scrollToSelection())
            assertEquals(0, viewport.scrollOffset, "A selection in the active screen restores the bottom viewport")
            assertEquals(rectangle, viewport.selectedText())
        } finally { viewport.dispose() }
    }

    @Test fun emptyInvalidatedFrozenAndDisposedSelectionsDoNotNavigate() {
        val session = TerminalSession(12, 4)
        val viewport = TerminalViewport(session, {})
        try {
            repeat(12) { session.write("row-$it\r\n") }
            assertFalse(viewport.scrollToSelection())
            viewport.beginSelection(0, 0); viewport.endSelection(0, 0)
            assertFalse(viewport.scrollToSelection(), "A click anchor is not a selection")
            viewport.beginSelection(0, 0); viewport.extendSelection(5, 0); viewport.endSelection(5, 0)
            viewport.scrollTo(session.historySize)
            val heldOffset = viewport.scrollOffset
            session.write("\u001b[?2026h")
            assertFalse(viewport.scrollToSelection())
            assertEquals(heldOffset, viewport.scrollOffset)
            session.write("\u001b[?2026l")
            assertTrue(viewport.scrollToSelection())
            session.write("\u001b[Hchanged")
            assertFalse(viewport.scrollToSelection(), "Overwriting the selected cells invalidates their anchors")
            viewport.selectAll(); session.clearHistory()
            assertFalse(viewport.scrollToSelection(), "Discarded history cannot be revealed")
            session.write("\u001b[?1049h\u001b[Halt")
            viewport.beginSelection(0, 0); viewport.extendSelection(3, 0); viewport.endSelection(3, 0)
            assertTrue(viewport.scrollToSelection(), "An alternate-screen selection is still an available action")
            assertEquals(0, viewport.scrollOffset)
            assertEquals("alt", viewport.selectedText())
            viewport.dispose()
            assertFalse(viewport.scrollToSelection())
        } finally { viewport.dispose() }
    }
}
