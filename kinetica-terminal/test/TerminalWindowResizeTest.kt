package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalWindowResizeTest {
    @Test fun requestsDoNotResizeOrReplyUntilTheHostAppliesMeasuredGeometry() {
        val session = TerminalSession(12, 3)
        val requests = mutableListOf<TerminalWindowResizeRequest>()
        val replies = mutableListOf<TerminalInputData>()
        var resized = 0
        session.onResize { _, _ -> resized++ }
        session.onInput(replies::add)
        val observation = session.onWindowResizeRequested(requests::add)
        session.write("abcdefghijkl\u001b[8;20;80t\u001b[8;;100t")
        assertEquals(listOf(TerminalWindowResizeRequest(20, 80), TerminalWindowResizeRequest(0, 100)), requests)
        assertEquals(12, session.columns); assertEquals(3, session.rows)
        assertTrue(session.cursorPendingWrap); assertEquals(0, resized); assertTrue(replies.isEmpty())
        observation.dispose()
        session.write("\u001b[8;30;90t")
        assertEquals(2, requests.size)
        // A host can decline, or finish its layout and apply the measured grid later.
        session.resize(80, 20)
        assertEquals(1, resized); assertEquals(80, session.columns); assertEquals(20, session.rows)
    }

    @Test fun duplicateObserversOwnIndependentSubscriptionsAcrossResets() {
        val session = TerminalSession()
        val events = mutableListOf<TerminalWindowResizeRequest>()
        val listener: (TerminalWindowResizeRequest) -> Unit = events::add
        val first = session.onWindowResizeRequested(listener)
        val second = session.onWindowResizeRequested(listener)
        first.dispose(); first.dispose()
        session.write("\u001b[?2026h\u001b[8;10;40t")
        assertTrue(session.synchronizedOutput)
        assertEquals(listOf(TerminalWindowResizeRequest(10, 40)), events)
        session.write("\u001b[!p\u001b[8;11;41t\u001bc\u001b[8;12;42t")
        assertEquals(3, events.size)
        second.dispose()
        session.write("\u001b[8;13;43t")
        assertEquals(3, events.size)
    }
}
