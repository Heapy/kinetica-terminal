package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalAccessibleDocumentCacheTest {
    @Test fun historyErasureRefreshesTheDocumentBeforeCacheExpiry() {
        for (hostAction in listOf(false, true)) {
            val session = TerminalSession(8, 2)
            val view = TerminalViewport(session, {})
            view.enableAccessibleDocument()
            val cache = TerminalAccessibleDocumentCache({ 0L }, view::accessibleDocument)
            fun read() = cache.get(view.accessibleDocumentKey()).text
            try {
                session.write("old-0\r\nold-1\r\nold-2")
                assertEquals("old-0\nold-1\nold-2", read())
                if (hostAction) session.clearHistory() else session.write("\u001b[3J")
                assertEquals("old-1\nold-2", read(), "History erasure must bypass the cache, hostAction=$hostAction")
            } finally { view.dispose() }
        }
    }

    @Test fun resizeRefreshesTheDocumentBeforeCacheExpiry() {
        val session = TerminalSession(8, 2)
        val view = TerminalViewport(session, {})
        view.enableAccessibleDocument()
        val cache = TerminalAccessibleDocumentCache({ 0L }, view::accessibleDocument)
        fun read() = cache.get(view.accessibleDocumentKey()).text
        try {
            session.write("\u001b[?1049habcdefgh")
            assertEquals("abcdefgh", read())
            session.resize(4, 2)
            assertEquals("abcd", read())
        } finally { view.dispose() }
    }

    @Test fun hiddenCoordinateChangesKeepTheHeldCacheUntilRelease() {
        val session = TerminalSession(8, 2)
        val view = TerminalViewport(session, {})
        view.enableAccessibleDocument()
        val cache = TerminalAccessibleDocumentCache({ 0L }, view::accessibleDocument)
        fun read() = cache.get(view.accessibleDocumentKey())
        try {
            session.write("old-0\r\nold-1\r\nold-2\u001b[?2026h")
            val held = read()
            assertEquals("old-0\nold-1\nold-2", held.text)
            session.write("\u001b[3J\u001b[?1049h\u001b[HALT")
            assertSame(held, read())
            session.write("\u001b[?2026l")
            assertEquals("ALT", read().text)
        } finally { view.dispose() }
    }

    @Test fun readsAreLazyAndSharedUntilExpiryOrCoordinateSpaceChange() {
        var now = 0L
        var reads = 0
        val cache = TerminalAccessibleDocumentCache({ now }) { TerminalAccessibleText("read-${++reads}", emptyList()) }
        assertEquals(0, reads)
        val first = cache.get("primary")
        now = 499; assertSame(first, cache.get("primary")); assertEquals(1, reads)
        now = 500; assertEquals("read-2", cache.get("primary").text)
        assertEquals("read-3", cache.get("alternate").text)
        now = 499; assertEquals("read-4", cache.get("alternate").text, "Clock rollback cannot retain a value indefinitely")
        cache.clear(); assertEquals("read-5", cache.get("alternate").text)
    }

    @Test fun aColdHeldDocumentRetainsHistoryAndTracksSelectionInGlobalCoordinates() {
        val session = TerminalSession(8, 2)
        val view = TerminalViewport(session, {})
        view.enableAccessibleDocument()
        try {
            session.write("old-0\r\nold-1\r\nold-2")
            val key = view.accessibleDocumentKey()
            session.line(0).compress()
            session.write("\u001b[?2026h\u001b[3J\u001b[2J\u001b[Hnew")
            val held = view.accessibleDocumentKey()
            assertNotEquals(key, held)
            assertEquals("old-0\nold-1\nold-2", view.accessibleDocument().text)
            view.beginSelection(0, 0); view.extendSelection(5, 0)
            val selected = view.accessibleDocument()
            assertEquals(listOf(TerminalAccessibleRange(6, 11)), selected.selections)
            assertEquals("old-1", selected.text.substring(6, 11))
            session.write("\u001b[?2026l")
            assertNotEquals(held, view.accessibleDocumentKey())
            assertEquals("new", view.accessibleDocument().text)
        } finally { view.dispose() }
    }

    @Test fun mountingDuringAHoldDoesNotExposeAnIncompleteFrame() {
        val session = TerminalSession(8, 2)
        session.write("old\r\ntext\r\nlast\u001b[?2026h\rnew")
        val view = TerminalViewport(session, {})
        view.enableAccessibleDocument()
        try {
            assertEquals("", view.accessibleDocument().text)
            session.write("\u001b[?2026l")
            assertEquals("old\ntext\nnewt", view.accessibleDocument().text)
        } finally { view.dispose() }
    }
}
