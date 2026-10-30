package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalContextSelectionTest {
    @Test fun contextMenuKeepsExistingSelectionsAndSelectsWordsOrDetectedLinks() {
        val session = TerminalSession(40, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("alpha beta https://example.org/a\r\nsecond")
            assertTrue(viewport.selectForContextMenu(2, 0)); assertEquals("alpha", viewport.selectedText())
            assertFalse(viewport.selectForContextMenu(4, 0)); assertEquals("alpha", viewport.selectedText())
            assertTrue(viewport.selectForContextMenu(7, 0)); assertEquals("beta", viewport.selectedText())
            assertFalse(viewport.selectForContextMenu(39, 2)); assertEquals("beta", viewport.selectedText())
            assertTrue(viewport.selectForContextMenu(20, 0)); assertNotEquals("https://example.org/a", viewport.selectedText())
            viewport.clearSelection()
            assertTrue(viewport.selectForContextMenu(20, 0, linkModifiers = true)); assertEquals("https://example.org/a", viewport.selectedText())
            viewport.selectAll()
            assertFalse(viewport.selectForContextMenu(7, 0)); assertContains(viewport.selectedText(), "second")
            viewport.clearSelection()
            viewport.beginSelection(1, 0); viewport.extendSelection(4, 1, rectangle = true); viewport.endSelection(4, 1)
            assertFalse(viewport.selectForContextMenu(2, 0))
            assertTrue(viewport.selectForContextMenu(7, 0)); assertEquals("beta", viewport.selectedText())
        } finally { viewport.dispose() }
        assertFalse(viewport.selectForContextMenu(2, 0))
    }

    @Test fun contextMenusReadWrappedHistoryAndThePresentedFrame() {
        val session = TerminalSession(8, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("https://example.org/path\r\nlast")
            viewport.scrollTo(session.historySize)
            assertTrue(viewport.selectForContextMenu(2, 1, linkModifiers = true))
            assertEquals("https://example.org/path", viewport.selectedText())
            viewport.clearSelection()
            session.write("\u001b[?2026h\u001b[3J\u001b[2J\u001b[HCHANGED")
            assertTrue(viewport.selectForContextMenu(2, 1, linkModifiers = true))
            assertEquals("https://example.org/path", viewport.selectedText())
            assertFalse(viewport.selectForContextMenu(-1, 0))
            assertFalse(viewport.selectForContextMenu(0, 3))
            session.write("\u001b[?2026l")
            assertFalse(viewport.hasSelection)
        } finally { viewport.dispose() }
    }

    @Test fun modifiedContextMenuSelectsOnlyTheOsc8CellWithWideCharacterNormalization() {
        val session = TerminalSession(20, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("\u001b]8;;https://example.org\u0007hello 界 world\u001b]8;;\u0007")
            assertTrue(viewport.selectForContextMenu(1, 0)); assertEquals("hello", viewport.selectedText())
            viewport.clearSelection()
            assertTrue(viewport.selectForContextMenu(1, 0, linkModifiers = true)); assertEquals("e", viewport.selectedText())
            assertTrue(viewport.selectForContextMenu(7, 0, linkModifiers = true)); assertEquals("界", viewport.selectedText())
            session.osc8LinksEnabled = false
            assertTrue(viewport.selectForContextMenu(1, 0, linkModifiers = true)); assertEquals("hello", viewport.selectedText())
        } finally { viewport.dispose() }
    }
}
