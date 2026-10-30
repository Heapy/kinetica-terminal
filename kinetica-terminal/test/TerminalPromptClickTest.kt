package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalPromptClickTest {
    @Test fun coordinatesAndPromptEligibilityMatchOriginalGhostty() {
        for (encoded in terminalPromptClickFixtures()) for (chunked in listOf(false, true)) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val session = TerminalSession(12, 6)
            val viewport = TerminalViewport(session, {})
            val replies = StringBuilder()
            session.onInput { replies.append(it.toByteArray().decodeToString()) }
            try {
                for ((index, value) in case.getValue("events").jsonArray.withIndex()) {
                    val event = value.jsonObject
                    replies.clear()
                    event["text"]?.jsonPrimitive?.content?.encodeToByteArray()?.let { bytes ->
                        if (chunked) bytes.forEach { session.write(byteArrayOf(it)) } else session.write(bytes)
                    }
                    event["scroll"]?.jsonPrimitive?.int?.let(viewport::scrollTo)
                    event["x"]?.jsonPrimitive?.int?.let { x ->
                        val y = event.getValue("y").jsonPrimitive.int
                        viewport.beginSelection(x, y)
                        viewport.endPointerSelection(x, y)
                    }
                    val actual = buildJsonObject { put("reply", replies.toString()) }
                    assertEquals(case.getValue("snapshots").jsonArray[index], actual,
                        "${case["name"]} step=$index chunked=$chunked")
                }
            } finally { viewport.dispose() }
        }
    }

    @Test fun onlyCompletedUnmovedLocalClicksReport() {
        val session = TerminalSession(12, 6)
        val viewport = TerminalViewport(session, {})
        val replies = mutableListOf<String>()
        session.onInput { replies += it.toByteArray().decodeToString() }
        session.write("\u001b]133;A;click_events=2\u0007$ \u001b]133;B\u0007abc")
        assertFalse(viewport.endPointerSelection(3, 0), "No local press")
        viewport.beginSelection(3, 0)
        viewport.extendSelection(4, 0); viewport.extendSelection(3, 0)
        assertFalse(viewport.endPointerSelection(3, 0), "A drag that returns to its origin stays a drag")
        for (mode in listOf(TerminalSelectionMode.WORD, TerminalSelectionMode.LINE)) {
            viewport.beginSelection(3, 0, mode)
            assertFalse(viewport.endPointerSelection(3, 0))
        }
        viewport.beginSelection(3, 0); viewport.cancelSelectionDrag()
        assertFalse(viewport.endPointerSelection(3, 0))
        viewport.beginSelection(3, 0); viewport.setFocused(false)
        assertFalse(viewport.endPointerSelection(3, 0))
        viewport.beginSelection(3, 0); viewport.clearSelection()
        assertFalse(viewport.endPointerSelection(3, 0))
        viewport.beginSelection(-1, 0)
        assertFalse(viewport.endPointerSelection(-1, 0))
        viewport.beginSelection(3, 0); viewport.endSelection(3, 0)
        assertTrue(replies.isEmpty(), "Programmatic selection does not send input")
        viewport.beginSelection(3, 0)
        assertTrue(viewport.endPointerSelection(3, 0))
        assertEquals(listOf("\u001b[<0;4;1M"), replies)
        assertFalse(viewport.endPointerSelection(3, 0), "Release is consumed once")
        viewport.dispose()
    }

    @Test fun policyFrozenFrameResetAndDisposalSuppressClicks() {
        val session = TerminalSession(12, 6)
        val viewport = TerminalViewport(session, {})
        val replies = mutableListOf<String>()
        session.onInput { replies += it.toByteArray().decodeToString() }
        fun prompt() { session.write("\u001b]133;A;click_events=1\u0007$ \u001b]133;B\u0007") }
        fun click(): Boolean { viewport.beginSelection(3, 0); return viewport.endPointerSelection(3, 0) }
        prompt()
        session.cursorClickToMove = false
        assertFalse(click())
        session.reset(); prompt()
        assertFalse(click(), "Host policy survives RIS")
        session.cursorClickToMove = true
        session.write("\u001b[?2026h")
        assertFalse(click(), "Held pixels cannot target a changing prompt")
        session.flushSynchronizedOutput()
        assertTrue(click())
        viewport.beginSelection(3, 0); session.reset(); prompt()
        assertFalse(viewport.endPointerSelection(3, 0), "Reset invalidates the prior gesture")
        viewport.dispose()
        assertFalse(click())
        assertEquals(listOf("\u001b[<0;4;1M"), replies)
    }
}
