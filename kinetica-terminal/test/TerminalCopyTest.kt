package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalCopyTest {
    @Test fun copyUsesCellFormattingWithoutChangingSelectionText() {
        val session = TerminalSession(8, 4)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("a  \r\n b  \r\nc")
            viewport.selectAll()
            assertTrue(viewport.hasSelection)
            assertEquals("a  \n b  \nc", viewport.selectedText())
            assertEquals("a\n b\nc", viewport.copyText())
            assertEquals("a  \n b  \nc", viewport.copyText(false))
            viewport.beginSelection(0, 0); viewport.extendSelection(3, 0)
            assertEquals("a  ", viewport.copyText(false)); assertEquals("a", viewport.copyText())
            viewport.beginSelection(0, 3); viewport.extendSelection(4, 3)
            assertTrue(viewport.hasSelection, "Selected unwritten cells are still a copyable selection")
            assertEquals("", viewport.copyText()); assertEquals("", viewport.copyText(false))
            viewport.clearSelection(); assertFalse(viewport.hasSelection)
        } finally { viewport.dispose() }
        assertFalse(viewport.hasSelection); assertEquals("", viewport.copyText())
    }

    @Test fun frozenCopyRetainsBothPoliciesAcrossOutputAndClearsInheritedContents() {
        val session = TerminalSession(8, 3)
        val viewport = TerminalViewport(session, {})
        try {
            session.write("a  \r\n b  \r\nc\r\nd\r\ne")
            viewport.selectAll()
            val untrimmed = viewport.copyText(false)
            val trimmed = viewport.copyText(true)
            assertNotEquals(trimmed, untrimmed)
            session.write("\u001b[?2026h\u001b[HREPLACED\u001b[3J")
            assertEquals(untrimmed, viewport.copyText(false), "Retain selected history outside the visible snapshot")
            assertEquals(trimmed, viewport.copyText(true))
            viewport.beginSelection(0, 0); viewport.extendSelection(1, 0)
            assertEquals("c", viewport.copyText()); assertEquals("c", viewport.copyText(false))
            viewport.clearSelection(); assertFalse(viewport.hasSelection); assertEquals("", viewport.copyText())
            viewport.selectAll(); assertEquals("c\nd\ne", viewport.copyText())
            session.write("\u001b[?2026l")
            assertEquals("", viewport.copyText(), "Thaw does not resurrect an inherited selection")
        } finally { viewport.dispose() }
    }
}
